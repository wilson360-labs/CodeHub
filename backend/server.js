/**
 * CodeHub AI Proxy — server.js
 * ─────────────────────────────────────────────────────
 * Proxy seguro entre el frontend y la API de Anthropic.
 * La API key NUNCA sale al navegador.
 *
 * Endpoints:
 *   POST /api/chat          → llama a Claude, devuelve respuesta
 *   GET  /api/chat/history  → historial de la sesión (MongoDB)
 *   GET  /api/health        → estado del servidor
 *
 * Variables de entorno (.env):
 *   ANTHROPIC_API_KEY   → tu clave de Anthropic
 *   MONGODB_URI         → tu URI de MongoDB Atlas
 *   PORT                → puerto (default 3001)
 *   FRONTEND_URL        → URL de tu sitio (para CORS)
 *   RATE_LIMIT_MAX      → max requests por ventana (default 20)
 */

require('dotenv').config();
const express    = require('express');
const cors       = require('cors');
const mongoose   = require('mongoose');
const Anthropic  = require('@anthropic-ai/sdk');
const rateLimit  = require('express-rate-limit');
const helmet     = require('helmet');

const app  = express();
const PORT = process.env.PORT || 3001;

// ── SEGURIDAD BÁSICA ──────────────────────────────────
app.use(helmet({ contentSecurityPolicy: false }));

// ── CORS — solo permite tu dominio frontend ───────────
const allowedOrigins = [
  process.env.FRONTEND_URL || 'http://localhost:8080',
  'http://localhost:3000',
  'http://localhost:5500',  // Live Server de VS Code
  'http://127.0.0.1:5500',
];
app.use(cors({
  origin: (origin, cb) => {
    // Permitir requests sin origin (Postman, curl) en desarrollo
    if (!origin || allowedOrigins.includes(origin)) return cb(null, true);
    cb(new Error('CORS: origen no permitido → ' + origin));
  },
  methods: ['GET', 'POST'],
  allowedHeaders: ['Content-Type'],
}));

app.use(express.json({ limit: '10kb' })); // Limitar tamaño del body

// ── RATE LIMITING — evita abuso de la API key ─────────
const limiter = rateLimit({
  windowMs: 15 * 60 * 1000,                         // 15 minutos
  max: parseInt(process.env.RATE_LIMIT_MAX) || 20,  // 20 mensajes por ventana
  standardHeaders: true,
  legacyHeaders: false,
  message: {
    error: 'Demasiadas solicitudes. Espera 15 minutos.',
    code: 'RATE_LIMIT',
  },
  keyGenerator: (req) => req.ip,  // Por IP
});
app.use('/api/chat', limiter);

// ── CLIENTE ANTHROPIC ─────────────────────────────────
const anthropic = new Anthropic({
  apiKey: process.env.ANTHROPIC_API_KEY,
});

// ── MONGODB — esquema de conversaciones ──────────────
const msgSchema = new mongoose.Schema({
  sessionId:  { type: String, required: true, index: true },
  role:       { type: String, enum: ['user', 'assistant'], required: true },
  content:    { type: String, required: true },
  tokens:     { type: Number, default: 0 },
  model:      { type: String, default: 'claude-sonnet-4-6' },
  createdAt:  { type: Date, default: Date.now, expires: 60 * 60 * 24 * 7 }, // TTL 7 días
});

const ChatMessage = mongoose.model('ChatMessage', msgSchema);

// ── CONECTAR MONGODB ──────────────────────────────────
async function connectDB() {
  if (!process.env.MONGODB_URI) {
    console.warn('⚠️  MONGODB_URI no configurado — los logs de chat se desactivarán');
    return false;
  }
  try {
    await mongoose.connect(process.env.MONGODB_URI, {
      serverSelectionTimeoutMS: 5000,
    });
    console.log('✅ MongoDB Atlas conectado');
    return true;
  } catch (err) {
    console.error('❌ MongoDB error:', err.message);
    return false;
  }
}

let dbConnected = false;

// ── SISTEMA PROMPT DE CODEHUB ─────────────────────────
const SYSTEM_PROMPT = `Eres el asistente IA de CodeHub, el portfolio de Wilson.E, desarrollador guatemalteco.

PERSONALIDAD:
- Conciso, técnico y amigable
- Siempre respondes en español
- Usas emojis con moderación
- Máximo 4 oraciones por respuesta salvo que pidan detalle explícito

SOBRE CODEHUB:
- Portfolio web de Wilson.E (24 años, Guatemala 🇬🇹)
- 18 herramientas web: QR, contraseñas (crypto), hash SHA-256/512, Base64, Regex tester, UUID v4, Pomodoro, convertidor de unidades/monedas/IMC/préstamos, velocidad de internet, paleta de colores, generador de memes, compresor de imágenes, info de dispositivo, IP checker
- Tienda de apps Android (novedades.html): Spotify Premium, YouTube ReVanced, TikTok, Remini Pro, etc.
- Juegos: Snake y Tetris con Canvas API
- Contacto: wilsonenrique686@gmail.com / WhatsApp +502 4146 8185

FORMATO:
- Código siempre en bloques \`\`\`
- Listas con guión (-)
- Negritas para términos clave`;

// ─────────────────────────────────────────────────────
//  POST /api/chat
//  Body: { message: string, sessionId: string, history: array }
// ─────────────────────────────────────────────────────
app.post('/api/chat', async (req, res) => {
  const { message, sessionId = 'anon', history = [] } = req.body;

  // Validaciones
  if (!message || typeof message !== 'string') {
    return res.status(400).json({ error: 'El campo "message" es requerido.' });
  }
  if (message.trim().length > 1000) {
    return res.status(400).json({ error: 'Mensaje demasiado largo (máx 1000 chars).' });
  }
  if (!process.env.ANTHROPIC_API_KEY) {
    return res.status(503).json({ error: 'API key no configurada en el servidor.' });
  }

  // Construir historial — últimos 10 turnos para no exceder tokens