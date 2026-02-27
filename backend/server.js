/**
 * CodeHub AI Proxy — server.js  (COMPLETO Y CORREGIDO)
 * ─────────────────────────────────────────────────────
 * Proxy seguro entre el frontend y la API de OpenAI.
 * La API key NUNCA sale al navegador.
 *
 * Endpoints:
 *   POST /api/chat         → llama a Claude, devuelve respuesta
 *   GET  /api/health       → estado del servidor y MongoDB
 *
 * Variables de entorno (.env):
 *   OPENAI_API_KEY     → tu clave de OpenAI
 *   MONGODB_URI        → tu URI de MongoDB Atlas
 *   PORT               → puerto (default 3001)
 *   FRONTEND_URL       → URL de tu sitio (para CORS)
 *   RATE_LIMIT_MAX     → max requests por ventana (default 20)
 */

require('dotenv').config();
const express   = require('express');
const cors      = require('cors');
const mongoose  = require('mongoose');
const OpenAI = require('openai');
const rateLimit = require('express-rate-limit');
const helmet    = require('helmet');

const app  = express();
const PORT = process.env.PORT || 3001;

// ── SEGURIDAD ──────────────────────────────────────────
app.use(helmet({ contentSecurityPolicy: false }));

// ── CORS ───────────────────────────────────────────────
const allowedOrigins = [
  process.env.FRONTEND_URL,
  'http://localhost:3000',
  'http://localhost:5500',
  'http://127.0.0.1:5500',
  'http://localhost:8080',
].filter(Boolean); // elimina undefined si FRONTEND_URL no está

app.use(cors({
  origin: (origin, cb) => {
    if (!origin || allowedOrigins.includes(origin)) return cb(null, true);
    cb(new Error('CORS: origen no permitido → ' + origin));
  },
  methods: ['GET', 'POST'],
  allowedHeaders: ['Content-Type'],
}));

app.use(express.json({ limit: '10kb' }));

// ── RATE LIMITING ──────────────────────────────────────
const limiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: parseInt(process.env.RATE_LIMIT_MAX) || 20,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Demasiadas solicitudes. Espera 15 minutos.', code: 'RATE_LIMIT' },
  keyGenerator: (req) => req.ip,
});
app.use('/api/chat', limiter);

// ── CLIENTE OPENAI ────────────────────────────────────
const openai = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });

// ── MONGODB — esquema ──────────────────────────────────
const msgSchema = new mongoose.Schema({
  sessionId: { type: String, required: true, index: true },
  role:      { type: String, enum: ['user', 'assistant'], required: true },
  content:   { type: String, required: true },
  tokens:    { type: Number, default: 0 },
  model:     { type: String, default: 'claude-sonnet-4-6' },
  createdAt: { type: Date, default: Date.now, expires: 60 * 60 * 24 * 7 }, // TTL 7 días
});
const ChatMessage = mongoose.model('ChatMessage', msgSchema);

// ── CONECTAR MONGODB ───────────────────────────────────
let dbConnected = false;
async function connectDB() {
  if (!process.env.MONGODB_URI) {
    console.warn('⚠️  MONGODB_URI no configurado — historial desactivado');
    return false;
  }
  try {
    await mongoose.connect(process.env.MONGODB_URI, { serverSelectionTimeoutMS: 5000 });
    console.log('✅ MongoDB Atlas conectado');
    return true;
  } catch (err) {
    console.error('❌ MongoDB error:', err.message);
    return false;
  }
}

// ── SYSTEM PROMPT ──────────────────────────────────────
const SYSTEM_PROMPT = `Eres el asistente IA de CodeHub, el portfolio de Wilson.E, desarrollador guatemalteco.

PERSONALIDAD:
- Conciso, técnico y amigable
- Siempre respondes en español
- Usas emojis con moderación
- Máximo 4 oraciones por respuesta salvo que pidan detalle explícito

SOBRE CODEHUB:
- Portfolio web de Wilson.E (24 años, Guatemala 🇬🇹)
- 18 herramientas web: QR, contraseñas, hash SHA-256/512, Base64, Regex tester, UUID v4, Pomodoro, convertidor de unidades/monedas/IMC/préstamos, velocidad de internet, paleta de colores, generador de memes, compresor de imágenes
- Tienda de apps Android (novedades.html): Spotify Premium, YouTube ReVanced, TikTok, Remini Pro, etc.
- Juegos: Snake y Tetris con Canvas API
- Contacto: wilsonenrique686@gmail.com / WhatsApp +502 4146 8185

FORMATO:
- Código siempre en bloques \`\`\`
- Listas con guión (-)
- Negritas para términos clave`;

// ── POST /api/chat ─────────────────────────────────────
app.post('/api/chat', async (req, res) => {
  const { message, sessionId = 'anon', history = [] } = req.body;

  // Validaciones
  if (!message || typeof message !== 'string') {
    return res.status(400).json({ error: 'El campo "message" es requerido.' });
  }
  if (message.trim().length > 1000) {
    return res.status(400).json({ error: 'Mensaje demasiado largo (máx 1000 chars).' });
  }
  if (!process.env.OPENAI_API_KEY) {
    return res.status(503).json({ error: 'API key no configurada en el servidor.' });
  }

  // Construir historial — últimos 10 turnos para no exceder tokens
  const safeHistory = Array.isArray(history)
    ? history.slice(-10).map(m => ({
        role:    m.role === 'assistant' ? 'assistant' : 'user',
        content: String(m.content).slice(0, 800),
      }))
    : [];

  // Agregar el mensaje actual
  safeHistory.push({ role: 'user', content: message.trim() });

  try {
    // Llamar a OpenAI GPT
    const response = await openai.chat.completions.create({
      model:      'gpt-4o-mini',  // rápido y económico
      max_tokens: 600,
      messages:   [
        { role: 'system', content: SYSTEM_PROMPT },
        ...safeHistory,
      ],
    });

    const reply     = response.choices[0]?.message?.content || 'No pude generar una respuesta.';
    const inputTok  = response.usage?.prompt_tokens     || 0;
    const outputTok = response.usage?.completion_tokens || 0;

    // Guardar en MongoDB si está conectado (no bloquea la respuesta)
    if (dbConnected) {
      ChatMessage.insertMany([
        { sessionId, role: 'user',      content: message.trim(), tokens: inputTok,  model: 'gpt-4o-mini' },
        { sessionId, role: 'assistant', content: reply,           tokens: outputTok, model: 'gpt-4o-mini' },
      ]).catch(err => console.error('MongoDB save error:', err.message));
    }

    // Responder al frontend — nunca incluir datos internos ni la key
    return res.json({
      reply,
      usage: { input: inputTok, output: outputTok, total: inputTok + outputTok },
    });

  } catch (err) {
    console.error('[/api/chat] Error:', err.message);

    if (err.status === 401) return res.status(500).json({ error: 'API key inválida. Contacta al administrador.' });
    if (err.status === 429) return res.status(429).json({ error: 'Límite de API alcanzado. Intenta en un momento.' });
    if (err.status === 529) return res.status(503).json({ error: 'Servicio temporalmente saturado. Intenta de nuevo.' });

    return res.status(500).json({ error: 'Error interno del servidor.' });
  }
});

// ── GET /api/health ────────────────────────────────────
app.get('/api/health', (req, res) => {
  res.json({
    status:    'ok',
    mongo:     dbConnected ? 'connected' : 'disconnected',
    openai: !!process.env.OPENAI_API_KEY ? 'configured' : 'missing',
    uptime:    Math.floor(process.uptime()) + 's',
  });
});

// ── ARRANCAR ───────────────────────────────────────────
(async () => {
  dbConnected = await connectDB();
  app.listen(PORT, () => {
    console.log(`🚀 CodeHub backend en puerto ${PORT}`);
    console.log(`   MongoDB: ${dbConnected ? '✅ conectado' : '⚠️  sin conexión'}`);
    console.log(`   OpenAI API: ${process.env.OPENAI_API_KEY ? '✅ configurada' : '❌ FALTA LA KEY'}`);
  });
})();
