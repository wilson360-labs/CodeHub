/**
 * CodeHub AI Proxy — server.js
 * Migrado a Groq (gratis) — llama-3.3-70b-versatile
 */

require('dotenv').config();
const express  = require('express');
const cors     = require('cors');
const mongoose = require('mongoose');
const rateLimit = require('express-rate-limit');
const helmet   = require('helmet');

const app  = express();
const PORT = process.env.PORT || 3001;

// Trust proxy — necesario en Railway
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
    cb(new Error('CORS: origen no permitido → ' + origin));
  },
  methods: ['GET', 'POST'],
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

const msgSchema = new mongoose.Schema({
  sessionId: { type: String, required: true, index: true },
  role:      { type: String, enum: ['user', 'assistant'], required: true },
  content:   { type: String, required: true },
  tokens:    { type: Number, default: 0 },
  model:     { type: String, default: 'llama-3.3-70b-versatile' },
  createdAt: { type: Date, default: Date.now, expires: 60 * 60 * 24 * 7 },
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

const SYSTEM_PROMPT = `Eres el asistente IA de CodeHub, portfolio de Wilson.E, desarrollador guatemalteco.
PERSONALIDAD: Conciso, técnico y amigable. Siempre en español. Emojis con moderación. Máx 4 oraciones.
SOBRE CODEHUB: Portfolio de Wilson.E (24 años, Guatemala 🇬🇹). 23 herramientas web, apps Android, juegos Snake/Tetris, descargador de videos (YouTube, TikTok, Instagram, Facebook, Twitter/X).
Contacto: wilsonenrique686@gmail.com / WhatsApp +502 4146 8185.
FORMATO: Código en bloques, listas con guión, negritas para términos clave.`;

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
  return res.json();
}

app.post('/api/chat', async (req, res) => {
  const { message, sessionId = 'anon', history = [] } = req.body;
  if (!message || typeof message !== 'string')
    return res.status(400).json({ error: 'El campo "message" es requerido.' });
  if (message.trim().length > 1000)
    return res.status(400).json({ error: 'Mensaje demasiado largo.' });
  if (!process.env.GROQ_API_KEY)
    return res.status(503).json({ error: 'API key no configurada.' });

  const safeHistory = Array.isArray(history)
    ? history.slice(-10).map(m => ({ role: m.role === 'assistant' ? 'assistant' : 'user', content: String(m.content).slice(0, 800) }))
    : [];
  safeHistory.push({ role: 'user', content: message.trim() });

  try {
    const data = await callGroq([{ role: 'system', content: SYSTEM_PROMPT }, ...safeHistory]);
    const reply    = data.choices[0]?.message?.content || 'No pude generar respuesta.';
    const inputTok  = data.usage?.prompt_tokens     || 0;
    const outputTok = data.usage?.completion_tokens || 0;

    if (dbConnected) {
      ChatMessage.insertMany([
        { sessionId, role: 'user',      content: message.trim(), tokens: inputTok,  model: 'llama-3.3-70b-versatile' },
        { sessionId, role: 'assistant', content: reply,           tokens: outputTok, model: 'llama-3.3-70b-versatile' },
      ]).catch(e => console.error('MongoDB save error:', e.message));
    }

    return res.json({ reply, usage: { input: inputTok, output: outputTok, total: inputTok + outputTok } });

  } catch (err) {
    console.error('[/api/chat] Error:', err.status, err.message);
    if (err.status === 401) return res.status(500).json({ error: 'API key inválida.' });
    if (err.status === 429) return res.status(429).json({ error: 'Límite alcanzado. Intenta en un momento.' });
    return res.status(500).json({ error: 'Error interno del servidor.' });
  }
});

app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok',
    mongo:  dbConnected ? 'connected' : 'disconnected',
    ai:     process.env.GROQ_API_KEY ? 'groq-configured' : 'missing',
    model:  'llama-3.3-70b-versatile',
    uptime: Math.floor(process.uptime()) + 's',
  });
});

(async () => {
  dbConnected = await connectDB();
  app.listen(PORT, () => {
    console.log(`🚀 CodeHub backend en puerto ${PORT}`);
    console.log(`   MongoDB: ${dbConnected ? '✅ conectado' : '⚠️  sin conexión'}`);
    console.log(`   Groq AI: ${process.env.GROQ_API_KEY ? '✅ configurada' : '❌ FALTA LA KEY'}`);
    console.log(`   Modelo:  llama-3.3-70b-versatile`);
  });
})();
