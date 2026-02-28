/**
 * CodeHub Backend — server.js
 * Desplegado en Railway. Variables de entorno configuradas en el dashboard de Railway.
 * MongoDB Atlas conectado. CORS configurado para GitHub Pages.
 *
 * Variables en Railway Dashboard:
 *   OPENAI_API_KEY   → sk-proj-...
 *   MONGODB_URI      → mongodb+srv://...
 *   FRONTEND_URL     → https://TU_USUARIO.github.io
 *   PORT             → (Railway lo asigna automáticamente)
 *   RATE_LIMIT_MAX   → 20
 */

require('dotenv').config();
const express   = require('express');
const cors      = require('cors');
const mongoose  = require('mongoose');
const OpenAI    = require('openai');
const rateLimit = require('express-rate-limit');
const helmet    = require('helmet');

const app  = express();
const PORT = process.env.PORT || 3001;
const RAILWAY_URL = 'https://codehub-production-729d.up.railway.app';

// ── SEGURIDAD ──────────────────────────────────────────────
app.use(helmet({ contentSecurityPolicy: false }));

// ── CORS ───────────────────────────────────────────────────
// Acepta: GitHub Pages (cualquier *.github.io), localhost para desarrollo,
// la URL custom de FRONTEND_URL en Railway Variables, y llamadas server-to-server.
const ALLOWED_ORIGINS = [
  process.env.FRONTEND_URL,       // desde Railway Variables
  'http://localhost:5500',
  'http://localhost:5501',
  'http://127.0.0.1:5500',
  'http://127.0.0.1:5501',
  'http://localhost:3000',
  'http://localhost:8080',
  'null',                          // file:// en algunos navegadores
].filter(Boolean);

app.use(cors({
  origin: (origin, cb) => {
    if (!origin) return cb(null, true);                          // curl / Postman / Railway health
    if (origin.endsWith('.github.io')) return cb(null, true);   // cualquier GitHub Pages
    if (/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/.test(origin)) return cb(null, true);
    if (ALLOWED_ORIGINS.includes(origin)) return cb(null, true);
    console.warn(`[CORS] Bloqueado: ${origin}`);
    cb(new Error(`CORS: origen no permitido → ${origin}`));
  },
  methods: ['GET', 'POST', 'DELETE', 'OPTIONS'],
  allowedHeaders: ['Content-Type'],
}));

app.use(express.json({ limit: '10kb' }));

// ── RATE LIMITING ──────────────────────────────────────────
app.use('/api/chat', rateLimit({
  windowMs: 15 * 60 * 1000,
  max: parseInt(process.env.RATE_LIMIT_MAX) || 20,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Demasiadas solicitudes. Espera 15 minutos.', code: 'RATE_LIMIT' },
}));

// ── OPENAI ─────────────────────────────────────────────────
// Instancia única — Railway garantiza que OPENAI_API_KEY esté disponible al arrancar.
// Si no está, el servidor arranca igual pero /api/chat devuelve 503 descriptivo.
let openai = null;
if (process.env.OPENAI_API_KEY) {
  openai = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });
  console.log('✅ OpenAI inicializado');
} else {
  console.warn('⚠️  OPENAI_API_KEY no configurada — agrega la variable en Railway Dashboard');
}

// ── MONGODB ────────────────────────────────────────────────
// Esquema de mensajes con TTL de 7 días (MongoDB limpia automáticamente)
let dbConnected = false;

const ChatSchema = new mongoose.Schema({
  sessionId: { type: String, required: true, index: true },
  role:      { type: String, enum: ['user', 'assistant'], required: true },
  content:   { type: String, required: true },
  model:     { type: String, default: 'gpt-4o-mini' },
  tokens:    { type: Number, default: 0 },
  createdAt: { type: Date, default: Date.now, expires: 60 * 60 * 24 * 7 }, // TTL 7 días
});
const ChatMessage = mongoose.model('ChatMessage', ChatSchema);

async function connectDB() {
  if (!process.env.MONGODB_URI) {
    console.warn('⚠️  MONGODB_URI no configurada — historial desactivado');
    return;
  }
  try {
    await mongoose.connect(process.env.MONGODB_URI, {
      serverSelectionTimeoutMS: 5000,
      socketTimeoutMS: 10000,
    });
    dbConnected = true;
    console.log('✅ MongoDB Atlas conectado');
  } catch (err) {
    console.error('❌ MongoDB error:', err.message);
  }
}

// ── HISTORIAL EN MEMORIA (respaldo si MongoDB cae) ─────────
// La sesión persiste en RAM entre requests del mismo usuario en la misma instancia.
// MongoDB guarda el historial entre reinicios del servidor.
const SESSION_CACHE = new Map();

async function getHistory(sessionId) {
  // 1. Intentar desde caché en RAM (más rápido)
  if (SESSION_CACHE.has(sessionId)) {
    const entry = SESSION_CACHE.get(sessionId);
    entry.ts = Date.now();
    return entry.msgs;
  }
  // 2. Si hay MongoDB, cargar historial persistido
  if (dbConnected) {
    try {
      const msgs = await ChatMessage.find({ sessionId })
        .sort({ createdAt: 1 })
        .limit(20)
        .select('role content -_id')
        .lean();
      const history = msgs.map(m => ({ role: m.role, content: m.content }));
      SESSION_CACHE.set(sessionId, { msgs: history, ts: Date.now() });
      return history;
    } catch (err) {
      console.error('MongoDB getHistory error:', err.message);
    }
  }
  return [];
}

async function saveMessage(sessionId, role, content, tokens = 0) {
  // Actualizar caché en RAM
  const entry = SESSION_CACHE.get(sessionId) || { msgs: [], ts: Date.now() };
  entry.msgs.push({ role, content });
  entry.msgs = entry.msgs.slice(-20); // mantener últimos 20 turnos
  entry.ts = Date.now();
  SESSION_CACHE.set(sessionId, entry);

  // Limpiar sesiones viejas (>30 min sin actividad)
  if (SESSION_CACHE.size > 200) {
    const cutoff = Date.now() - 30 * 60 * 1000;
    for (const [k, v] of SESSION_CACHE) if (v.ts < cutoff) SESSION_CACHE.delete(k);
  }

  // Persistir en MongoDB de forma asíncrona (no bloquea la respuesta)
  if (dbConnected) {
    ChatMessage.create({ sessionId, role, content, tokens, model: 'gpt-4o-mini' })
      .catch(err => console.error('MongoDB save error:', err.message));
  }
}

// ── SYSTEM PROMPT ──────────────────────────────────────────
const SYSTEM_PROMPT = `Eres el asistente IA de CodeHub, el portfolio de Wilson.E, desarrollador guatemalteco de 24 años.

PERSONALIDAD:
- Conciso, técnico y amigable
- SIEMPRE respondes en español
- Emojis con moderación
- Máximo 4 oraciones por respuesta salvo que pidan detalle explícito

SOBRE CODEHUB:
- Portfolio web de Wilson.E (Guatemala 🇬🇹)
- 23+ herramientas web 100% client-side: generador de contraseñas, QR, hash SHA-256/512, Base64, Regex tester, UUID v4, Pomodoro, convertidor de unidades/monedas/IMC/préstamos, velocidad de internet, paleta de colores, generador de memes, compresor de imágenes, analizador de red WiFi, traductor de texto, calculadora científica, lorem ipsum, contador de texto, conversor de colores HEX/RGB/HSL
- Tienda de apps Android (novedades.html): Spotify Premium, YouTube ReVanced, TikTok Premium, Remini Pro, PicsArt, MX Player y más
- Juegos: Snake y Tetris con Canvas API
- Descargador de videos: YouTube, TikTok, Instagram, Facebook, Twitter/X — motor propio con cobalt.tools + Invidious
- Contacto: wilsonenrique686@gmail.com / WhatsApp +502 4146 8185

FORMATO:
- Código siempre en bloques \`\`\`
- Listas con guión (-)
- **Negritas** para términos clave`;

// ── POST /api/chat ─────────────────────────────────────────
app.post('/api/chat', async (req, res) => {
  const { message, sessionId = 'anon' } = req.body;

  // Validaciones de entrada
  if (!message || typeof message !== 'string' || !message.trim())
    return res.status(400).json({ error: 'El campo "message" es requerido.', code: 'BAD_REQUEST' });
  if (message.length > 1000)
    return res.status(400).json({ error: 'Mensaje demasiado largo (máx 1000 caracteres).', code: 'TOO_LONG' });

  // Verificar que OpenAI esté disponible
  if (!openai)
    return res.status(503).json({
      error: '🔑 El chatbot no está configurado. Agrega OPENAI_API_KEY en Railway Dashboard → Variables.',
      code: 'NO_API_KEY',
    });

  // Cargar historial de la sesión (RAM + MongoDB)
  const history = await getHistory(sessionId);
  history.push({ role: 'user', content: message.trim() });

  try {
    // Timeout de 25s para evitar cuelgues en Railway
    const controller = new AbortController();
    const timeoutId  = setTimeout(() => controller.abort(), 25000);

    const response = await openai.chat.completions.create({
      model:       'gpt-4o-mini',
      max_tokens:  600,
      temperature: 0.75,
      messages: [
        { role: 'system', content: SYSTEM_PROMPT },
        ...history.slice(-10), // últimos 10 turnos para no saturar tokens
      ],
    }, { signal: controller.signal });

    clearTimeout(timeoutId);

    const reply    = response.choices[0]?.message?.content || 'No pude generar una respuesta.';
    const inputTok = response.usage?.prompt_tokens     || 0;
    const outTok   = response.usage?.completion_tokens || 0;

    // Guardar ambos mensajes en RAM + MongoDB
    await saveMessage(sessionId, 'user',      message.trim(), inputTok);
    await saveMessage(sessionId, 'assistant', reply,          outTok);

    console.log(`✅ Chat [${sessionId.slice(0,8)}] in:${inputTok} out:${outTok} db:${dbConnected}`);

    return res.json({
      reply,
      usage: { input: inputTok, output: outTok, total: inputTok + outTok },
    });

  } catch (err) {
    console.error('[/api/chat] Error:', err.status, err.message);

    if (err.name === 'AbortError')  return res.status(504).json({ error: 'La IA tardó demasiado. Reintenta.', code: 'TIMEOUT' });
    if (err.status === 401)         return res.status(500).json({ error: 'API key inválida. Verifica en Railway Variables.', code: 'BAD_KEY' });
    if (err.status === 429)         return res.status(429).json({ error: 'Límite de OpenAI alcanzado. Espera un momento.', code: 'RATE_LIMIT' });
    if (err.status === 402)         return res.status(402).json({ error: 'Sin créditos en OpenAI. Recarga tu cuenta.', code: 'NO_CREDITS' });
    if (err.status === 529)         return res.status(503).json({ error: 'OpenAI saturado. Reintenta en unos segundos.', code: 'OVERLOADED' });

    return res.status(500).json({ error: 'Error interno del servidor.', code: 'INTERNAL' });
  }
});

// ── DELETE /api/chat/:sessionId — limpiar historial ────────
app.delete('/api/chat/:sessionId', async (req, res) => {
  const { sessionId } = req.params;
  SESSION_CACHE.delete(sessionId);
  if (dbConnected) {
    await ChatMessage.deleteMany({ sessionId }).catch(() => {});
  }
  res.json({ ok: true });
});

// ── GET /api/health ────────────────────────────────────────
app.get('/api/health', (_req, res) => {
  res.json({
    status:    'ok',
    service:   'CodeHub Backend',
    version:   '2.1.0',
    openai:    openai                      ? '✅ configurado' : '❌ FALTA OPENAI_API_KEY',
    mongodb:   dbConnected                 ? '✅ conectado'   : '⚠️  sin conexión',
    sessions:  SESSION_CACHE.size,
    uptime:    Math.floor(process.uptime()) + 's',
    cors:      '✅ *.github.io + localhost',
    env:       process.env.NODE_ENV || 'development',
    railway:   RAILWAY_URL,
  });
});

// ── ROOT ───────────────────────────────────────────────────
app.get('/', (_req, res) => {
  res.json({ message: 'CodeHub Backend corriendo ✅', health: RAILWAY_URL + '/api/health' });
});

// ── ARRANCAR ───────────────────────────────────────────────
(async () => {
  await connectDB();
  app.listen(PORT, () => {
    console.log('\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
    console.log('🚀  CodeHub Backend v2.1.0');
    console.log(`    Puerto:   ${PORT}`);
    console.log(`    OpenAI:   ${openai            ? '✅ configurado' : '❌ FALTA OPENAI_API_KEY'}`);
    console.log(`    MongoDB:  ${dbConnected       ? '✅ conectado'   : '⚠️  sin conexión'}`);
    console.log(`    CORS:     ✅ *.github.io + localhost`);
    console.log(`    Env:      ${process.env.NODE_ENV || 'development'}`);
    console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n');
  });
})();
