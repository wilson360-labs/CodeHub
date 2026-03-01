/**
 * CodeHub AI Proxy — server.js
 * IA: Groq (principal) → Gemini (respaldo automático)
 *
 * Variables Railway:
 *   GROQ_API_KEY    → groq.com (gratis)
 *   GEMINI_API_KEY  → aistudio.google.com (gratis)
 *   MONGODB_URI     → MongoDB Atlas
 *   FRONTEND_URL    → URL de tu sitio
 *   RATE_LIMIT_MAX  → default 50
 */

require('dotenv').config();
const express   = require('express');
const cors      = require('cors');
const mongoose  = require('mongoose');
const rateLimit = require('express-rate-limit');
const helmet    = require('helmet');

const app  = express();
const PORT = process.env.PORT || 3001;

app.set('trust proxy', 1);
app.use(helmet({ contentSecurityPolicy: false }));

const allowedOrigins = [
  process.env.FRONTEND_URL,
  'http://localhost:3000',
  'http://localhost:5500',
  'http://127.0.0.1:5500',
  'http://localhost:8080',
  'https://wilson360-labs.vercel.app',
  'https://wilson360-labs.github.io',
].filter(Boolean);

app.use(cors({
  origin: (origin, cb) => {
    if (!origin || allowedOrigins.includes(origin)) return cb(null, true);
    cb(new Error('CORS bloqueado: ' + origin));
  },
  methods: ['GET','POST'],
  allowedHeaders: ['Content-Type'],
}));

app.use(express.json({ limit: '10kb' }));

const limiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: parseInt(process.env.RATE_LIMIT_MAX) || 50,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Demasiadas solicitudes. Espera unos minutos.', code: 'RATE_LIMIT' },
});
app.use('/api/chat', limiter);

// ── MONGODB ────────────────────────────────────────────
const msgSchema = new mongoose.Schema({
  sessionId: { type: String, required: true, index: true },
  role:      { type: String, enum: ['user','assistant'], required: true },
  content:   { type: String, required: true },
  tokens:    { type: Number, default: 0 },
  model:     { type: String, default: 'groq' },
  createdAt: { type: Date, default: Date.now, expires: 60*60*24*7 },
});
const ChatMessage = mongoose.model('ChatMessage', msgSchema);

let dbConnected = false;
async function connectDB() {
  if (!process.env.MONGODB_URI) { console.warn('⚠️  MONGODB_URI no configurado'); return false; }
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
const SYSTEM_PROMPT = `Eres el asistente IA de CodeHub, portfolio de Wilson.E, desarrollador guatemalteco.
PERSONALIDAD: Conciso, técnico y amigable. Siempre en español. Emojis con moderación. Máx 4 oraciones por respuesta.
SOBRE CODEHUB: Portfolio de Wilson.E (24 años, Guatemala 🇬🇹). 23 herramientas web, apps Android premium, juegos Snake/Tetris, descargador de videos (YouTube, TikTok, Instagram, Facebook, Twitter/X).
Contacto: wilsonenrique686@gmail.com / WhatsApp +502 3513 1808.
FORMATO: Código en bloques, listas con guión, negritas para términos clave.`;

// ── GROQ ───────────────────────────────────────────────
async function callGroq(messages) {
  const res = await fetch('https://api.groq.com/openai/v1/chat/completions', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${process.env.GROQ_API_KEY}`,
    },
    body: JSON.stringify({
      model: 'llama-3.3-70b-versatile',
      max_tokens: 600,
      temperature: 0.7,
      messages,
    }),
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    const e = new Error(err.error?.message || `Groq HTTP ${res.status}`);
    e.status = res.status;
    throw e;
  }
  const data = await res.json();
  return {
    reply:  data.choices[0]?.message?.content || '',
    input:  data.usage?.prompt_tokens     || 0,
    output: data.usage?.completion_tokens || 0,
    model:  'groq/llama-3.3-70b',
  };
}

// ── GEMINI (respaldo) ──────────────────────────────────
async function callGemini(messages) {
  // Convertir historial al formato de Gemini
  const contents = messages
    .filter(m => m.role !== 'system')
    .map(m => ({
      role: m.role === 'assistant' ? 'model' : 'user',
      parts: [{ text: m.content }],
    }));

  // Agregar system prompt como primer mensaje de usuario si no hay historial
  const systemInstruction = { parts: [{ text: SYSTEM_PROMPT }] };

  const res = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=${process.env.GEMINI_API_KEY}`,
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        systemInstruction,
        contents,
        generationConfig: {
          maxOutputTokens: 600,
          temperature: 0.7,
        },
      }),
    }
  );

  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    const e = new Error(err.error?.message || `Gemini HTTP ${res.status}`);
    e.status = res.status;
    throw e;
  }

  const data = await res.json();
  const reply = data.candidates?.[0]?.content?.parts?.[0]?.text || '';
  return {
    reply,
    input:  data.usageMetadata?.promptTokenCount     || 0,
    output: data.usageMetadata?.candidatesTokenCount || 0,
    model:  'gemini-1.5-flash',
  };
}

// ── FALLBACK CHAIN: Groq → Gemini ─────────────────────
async function callAI(messages) {
  // Intentar Groq primero
  if (process.env.GROQ_API_KEY) {
    try {
      const result = await callGroq(messages);
      console.log(`✅ Groq respondió (${result.output} tokens)`);
      return result;
    } catch (err) {
      console.warn(`⚠️  Groq falló (${err.status}): ${err.message} — intentando Gemini...`);
      // Solo hacer fallback si es error de límite o servidor, no de key inválida
      if (err.status === 401) throw err;
    }
  }

  // Fallback a Gemini
  if (process.env.GEMINI_API_KEY) {
    try {
      const result = await callGemini(messages);
      console.log(`✅ Gemini respondió como respaldo (${result.output} tokens)`);
      return result;
    } catch (err) {
      console.error(`❌ Gemini también falló (${err.status}): ${err.message}`);
      throw err;
    }
  }

  throw new Error('No hay API keys configuradas (GROQ_API_KEY o GEMINI_API_KEY)');
}

// ── POST /api/chat ─────────────────────────────────────
app.post('/api/chat', async (req, res) => {
  const { message, sessionId = 'anon', history = [] } = req.body;

  if (!message || typeof message !== 'string')
    return res.status(400).json({ error: 'El campo "message" es requerido.' });
  if (message.trim().length > 1000)
    return res.status(400).json({ error: 'Mensaje demasiado largo (máx 1000 chars).' });
  if (!process.env.GROQ_API_KEY && !process.env.GEMINI_API_KEY)
    return res.status(503).json({ error: 'Servidor sin API key configurada.' });

  const safeHistory = Array.isArray(history)
    ? history.slice(-10).map(m => ({
        role:    m.role === 'assistant' ? 'assistant' : 'user',
        content: String(m.content).slice(0, 800),
      }))
    : [];
  safeHistory.push({ role: 'user', content: message.trim() });

  const messages = [{ role: 'system', content: SYSTEM_PROMPT }, ...safeHistory];

  try {
    const { reply, input, output, model } = await callAI(messages);

    if (dbConnected) {
      ChatMessage.insertMany([
        { sessionId, role: 'user',      content: message.trim(), tokens: input,  model },
        { sessionId, role: 'assistant', content: reply,           tokens: output, model },
      ]).catch(e => console.error('MongoDB save error:', e.message));
    }

    return res.json({ reply, usage: { input, output, total: input + output }, model });

  } catch (err) {
    console.error('[/api/chat] Error final:', err.status, err.message);
    if (err.status === 401) return res.status(500).json({ error: 'API key inválida.' });
    if (err.status === 429) return res.status(429).json({ error: 'Límite alcanzado en todos los proveedores. Intenta en unos minutos.' });
    return res.status(500).json({ error: 'Error interno del servidor.' });
  }
});

// ── GET /api/health ────────────────────────────────────
app.get('/api/health', (req, res) => {
  res.json({
    status:    'ok',
    mongo:     dbConnected ? 'connected' : 'disconnected',
    groq:      process.env.GROQ_API_KEY    ? 'configured' : 'missing',
    gemini:    process.env.GEMINI_API_KEY  ? 'configured' : 'missing',
    model:     'groq/llama-3.3-70b → gemini-1.5-flash (fallback)',
    uptime:    Math.floor(process.uptime()) + 's',
  });
});

// ── ARRANCAR ───────────────────────────────────────────
(async () => {
  dbConnected = await connectDB();
  app.listen(PORT, () => {
    console.log(`🚀 CodeHub backend en puerto ${PORT}`);
    console.log(`   MongoDB: ${dbConnected ? '✅ conectado' : '⚠️  sin conexión'}`);
    console.log(`   Groq:    ${process.env.GROQ_API_KEY   ? '✅ configurado' : '❌ falta key'}`);
    console.log(`   Gemini:  ${process.env.GEMINI_API_KEY ? '✅ configurado' : '⚠️  no configurado (opcional)'}`);
    console.log(`   Cadena:  Groq → Gemini (fallback automático)`);
  });
})();

// ── SCHEMAS NUEVOS ─────────────────────────────────────────

// Rating de apps
const ratingSchema = new mongoose.Schema({
  appId:    { type: String, required: true, index: true },
  appName:  { type: String, required: true },
  ratings:  [{ ip: String, stars: Number, createdAt: { type: Date, default: Date.now } }],
  total:    { type: Number, default: 0 },
  count:    { type: Number, default: 0 },
});
const AppRating = mongoose.model('AppRating', ratingSchema);

// Solicitudes de apps
const requestSchema = new mongoose.Schema({
  appName:  { type: String, required: true },
  reason:   { type: String, default: '' },
  ip:       { type: String },
  votes:    { type: Number, default: 1 },
  voters:   [String],
  status:   { type: String, enum: ['pending','done','rejected'], default: 'pending' },
  createdAt:{ type: Date, default: Date.now },
});
const AppRequest = mongoose.model('AppRequest', requestSchema);

// ── GET /api/ratings — obtener ratings de todas las apps ──
app.get('/api/ratings', async (req, res) => {
  if (!dbConnected) return res.json({ ratings: {} });
  try {
    const all = await AppRating.find({}, 'appId total count');
    const ratings = {};
    all.forEach(r => {
      ratings[r.appId] = {
        avg: r.count > 0 ? Math.round((r.total / r.count) * 10) / 10 : 0,
        count: r.count,
      };
    });
    res.json({ ratings });
  } catch(e) { res.json({ ratings: {} }); }
});

// ── POST /api/ratings — votar una app ──
app.post('/api/ratings', async (req, res) => {
  const { appId, appName, stars } = req.body;
  const ip = req.ip || 'anon';
  if (!appId || !stars || stars < 1 || stars > 5)
    return res.status(400).json({ error: 'Datos inválidos' });
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    let rating = await AppRating.findOne({ appId });
    if (!rating) rating = new AppRating({ appId, appName: appName || appId, ratings: [], total: 0, count: 0 });
    // Solo 1 voto por IP
    const already = rating.ratings.find(r => r.ip === ip);
    if (already) return res.status(409).json({ error: 'Ya votaste esta app', avg: rating.count > 0 ? Math.round((rating.total/rating.count)*10)/10 : 0, count: rating.count });
    rating.ratings.push({ ip, stars });
    rating.total += stars;
    rating.count += 1;
    await rating.save();
    res.json({ ok: true, avg: Math.round((rating.total / rating.count) * 10) / 10, count: rating.count });
  } catch(e) { res.status(500).json({ error: 'Error guardando rating' }); }
});

// ── GET /api/requests — obtener solicitudes (top 20) ──
app.get('/api/requests', async (req, res) => {
  if (!dbConnected) return res.json({ requests: [] });
  try {
    const reqs = await AppRequest.find({ status: 'pending' }).sort({ votes: -1 }).limit(20);
    res.json({ requests: reqs });
  } catch(e) { res.json({ requests: [] }); }
});

// ── POST /api/requests — solicitar nueva app ──
app.post('/api/requests', async (req, res) => {
  const { appName, reason } = req.body;
  const ip = req.ip || 'anon';
  if (!appName || appName.trim().length < 2)
    return res.status(400).json({ error: 'Nombre de app requerido' });
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    // Verificar si ya existe
    const existing = await AppRequest.findOne({ appName: new RegExp(appName.trim(), 'i'), status: 'pending' });
    if (existing) {
      if (existing.voters.includes(ip))
        return res.status(409).json({ error: 'Ya votaste por esta solicitud', votes: existing.votes });
      existing.votes += 1;
      existing.voters.push(ip);
      await existing.save();
      return res.json({ ok: true, message: 'Voto agregado a solicitud existente', votes: existing.votes });
    }
    const newReq = new AppRequest({ appName: appName.trim(), reason: reason?.trim() || '', ip, voters: [ip] });
    await newReq.save();
    res.json({ ok: true, message: 'Solicitud enviada', id: newReq._id });
  } catch(e) { res.status(500).json({ error: 'Error guardando solicitud' }); }
});
