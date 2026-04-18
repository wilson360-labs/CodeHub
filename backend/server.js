/**
 * CodeHub Backend v3.1 — Wilson.E 2026
 * ─────────────────────────────────────────────────────────────
 * ✅ WebSockets — notificaciones en tiempo real
 * ✅ Redis      — caché (opcional, Railway Redis addon)
 * ✅ Eventos:   visitas, descargas, ratings, contacto, chat IA, nueva app
 *
 * Variables Railway:
 *   GROQ_API_KEY, GEMINI_API_KEY, MONGODB_URI, FRONTEND_URL
 *   ADMIN_KEY, SUPABASE_URL, SUPABASE_KEY (storage bucket: codehub-apks)
 *   RATE_LIMIT_MAX, REDIS_URL (opcional), WS_URL (opcional)
 *   TOGETHER_API_KEY, OPENROUTER_API_KEY, MISTRAL_API_KEY, COHERE_API_KEY
 */

require('dotenv').config();
const express   = require('express');
const cors      = require('cors');
const mongoose  = require('mongoose');
const rateLimit = require('express-rate-limit');
const helmet    = require('helmet');
const multer    = require('multer');
const crypto    = require('crypto');
const http      = require('http');
const { WebSocketServer } = require('ws');
const swaggerSpec        = require('./swagger');

// ── SUPABASE ──────────────────────────────────────────────────
const { createClient } = require('@supabase/supabase-js');
const supabase = (process.env.SUPABASE_URL && process.env.SUPABASE_KEY)
  ? createClient(process.env.SUPABASE_URL, process.env.SUPABASE_KEY)
  : null;

// Helper: registrar evento en Supabase
async function trackEvent(type, page = null, metadata = {}) {
  if (!supabase) return;
  try {
    const today = new Date().toISOString().slice(0, 10);
    // Insertar evento individual
    await supabase.from('events').insert({ type, page, metadata });
    // Actualizar stats diarias
    const col = { visit: 'visits', download: 'downloads', chat: 'chat_msgs', tool: 'tool_uses', contact: 'contacts' }[type];
    if (col) {
      const { data } = await supabase.from('daily_stats').select('id,' + col).eq('date', today).single();
      if (data) {
        await supabase.from('daily_stats').update({ [col]: (data[col] || 0) + 1, updated_at: new Date() }).eq('date', today);
      } else {
        await supabase.from('daily_stats').insert({ date: today, [col]: 1 });
      }
    }
    // Stats por herramienta
    if (type === 'tool' && metadata.tool_name) {
      const { data: t } = await supabase.from('tool_stats').select('id,uses').eq('tool_name', metadata.tool_name).single();
      if (t) await supabase.from('tool_stats').update({ uses: t.uses + 1, last_used: new Date() }).eq('tool_name', metadata.tool_name);
      else await supabase.from('tool_stats').insert({ tool_name: metadata.tool_name, uses: 1 });
    }
    // Stats por descarga
    if (type === 'download' && metadata.app_name) {
      const { data: d } = await supabase.from('download_stats').select('id,downloads').eq('app_name', metadata.app_name).single();
      if (d) await supabase.from('download_stats').update({ downloads: d.downloads + 1, last_download: new Date() }).eq('app_name', metadata.app_name);
      else await supabase.from('download_stats').insert({ app_name: metadata.app_name, downloads: 1 });
    }
  } catch(e) { console.warn('Supabase trackEvent error:', e.message); }
}

const app    = express();
const server = http.createServer(app);
const PORT   = process.env.PORT || 3001;

app.set('trust proxy', 1);
app.use(helmet({ contentSecurityPolicy: false }));

// ── CORS ──────────────────────────────────────────────────────
const allowedOrigins = [
  process.env.FRONTEND_URL,
  'http://localhost:3000', 'http://localhost:5500',
  'http://127.0.0.1:5500', 'http://localhost:8080',
  'https://wilson360-labs.vercel.app',
  'https://wilson360-labs.github.io',
].filter(Boolean);

const corsOptions = {
  origin: (origin, cb) => {
    if (!origin || allowedOrigins.includes(origin)) return cb(null, true);
    cb(new Error('CORS bloqueado: ' + origin));
  },
  methods: ['GET', 'POST', 'PATCH', 'DELETE', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'x-admin-key', 'x-admin-user', 'Accept', 'Authorization'],
  exposedHeaders: ['Content-Length', 'X-Cache'],
  credentials: true,
  preflightContinue: false,
  optionsSuccessStatus: 204,
};
app.use(cors(corsOptions));
app.options('*', cors(corsOptions));
app.use(express.json({ limit: '10kb' }));

// Multer APKs — Telegram es ilimitado en almacenamiento; Supabase (fallback) tiene límite de 50 MB.
// El límite aquí (2 GB) es solo protección del servidor en tránsito, no un límite de Telegram.
const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 2 * 1024 * 1024 * 1024 }, // 2 GB máx en tránsito (Telegram no tiene límite de storage)
  fileFilter: (_, f, cb) => {
    if (f.mimetype === 'application/vnd.android.package-archive' || f.originalname.endsWith('.apk'))
      cb(null, true);
    else cb(new Error('Solo .apk'));
  },
});
const uploadSecurityFile = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 32 * 1024 * 1024 },
});

// Rate limiting
const chatLimiter  = rateLimit({ windowMs: 15*60*1000, max: parseInt(process.env.RATE_LIMIT_MAX)||50, standardHeaders: true, legacyHeaders: false, message: { error: 'Demasiadas solicitudes.', code: 'RATE_LIMIT' } });
const adminLimiter = rateLimit({ windowMs: 15*60*1000, max: 100, standardHeaders: true, legacyHeaders: false });
app.use('/api/chat',  chatLimiter);
app.use('/api/admin', adminLimiter);

// ── REDIS (opcional) ──────────────────────────────────────────
let redis = null;
async function initRedis() {
  if (!process.env.REDIS_URL) { console.log('⚠️  Sin REDIS_URL — usando caché en memoria'); return; }
  try {
    const { createClient } = require('redis');
    redis = createClient({ url: process.env.REDIS_URL });
    redis.on('error', e => console.warn('Redis error:', e.message));
    await redis.connect();
    console.log('✅ Redis conectado');
  } catch (e) { console.warn('⚠️  Redis falló, usando memoria:', e.message); redis = null; }
}

const _mem = new Map();
async function cacheGet(k) {
  if (redis) { const v = await redis.get(k); return v ? JSON.parse(v) : null; }
  const i = _mem.get(k); if (!i) return null;
  if (Date.now() > i.e) { _mem.delete(k); return null; } return i.v;
}
async function cacheSet(k, v, ttl = 60) {
  if (redis) { await redis.setEx(k, ttl, JSON.stringify(v)); return; }
  _mem.set(k, { v, e: Date.now() + ttl * 1000 });
}
async function cacheDel(k) { if (redis) await redis.del(k); _mem.delete(k); }

// ── WEBSOCKETS ────────────────────────────────────────────────
const wss = new WebSocketServer({ server, path: '/ws' });
const wsClients = new Set();

wss.on('connection', (ws, req) => {
  const origin = req.headers.origin;
  if (origin && !allowedOrigins.includes(origin)) { ws.close(1008, 'Origin no permitido'); return; }
  ws.isAlive = true;
  wsClients.add(ws);
  console.log(`🔌 WS conectado — ${wsClients.size} clientes`);
  ws.send(JSON.stringify({ type: 'connected', clients: wsClients.size, ts: Date.now() }));
  ws.on('pong', () => { ws.isAlive = true; });
  ws.on('close', () => { wsClients.delete(ws); });
  ws.on('error', () => wsClients.delete(ws));
});

// Keepalive ping cada 30s
setInterval(() => {
  wsClients.forEach(ws => {
    if (!ws.isAlive) { wsClients.delete(ws); ws.terminate(); return; }
    ws.isAlive = false; ws.ping();
  });
}, 30000);

function broadcast(type, data = {}) {
  const msg = JSON.stringify({ type, ...data, ts: Date.now() });
  wsClients.forEach(ws => { if (ws.readyState === 1) ws.send(msg); });
}

// Contador visitas en memoria
const visits = { today: 0, total: 0, date: new Date().toDateString() };
function trackVisit() {
  const today = new Date().toDateString();
  if (today !== visits.date) { visits.today = 0; visits.date = today; }
  visits.today++; visits.total++;
  broadcast('visit', { today: visits.today, total: visits.total });
  trackEvent('visit');
}

// ── MONGODB SCHEMAS ───────────────────────────────────────────
const ChatMessage = mongoose.model('ChatMessage', new mongoose.Schema({
  sessionId: { type: String, required: true, index: true },
  role:      { type: String, enum: ['user','assistant'], required: true },
  content:   { type: String, required: true },
  tokens:    { type: Number, default: 0 },
  model:     { type: String, default: 'groq' },
  createdAt: { type: Date, default: Date.now, expires: 60*60*24*7 },
}));

const App = mongoose.model('App', new mongoose.Schema({
  appId:              { type: String, required: true, unique: true },
  nombre:             { type: String, required: true },
  descripcion:        { type: String, default: '' },
  version:            { type: String, default: '' },
  tag:                { type: String, default: '🆕' },
  changelog:          { type: String, default: '' },
  imagen:             { type: String, default: '' },
  categoria:          { type: String, default: '' },
  verified:           { type: Boolean, default: true },
  enlace:             { type: String, default: '#' },
  plugin_enlace:      { type: String, default: null },
  b2_file_id:         { type: String, default: null },
  b2_file_name:       { type: String, default: null },
  b2_plugin_file_id:  { type: String, default: null },
  b2_plugin_file_name:{ type: String, default: null },
  tg_message_id:      { type: Number, default: null },  // ID mensaje Telegram APK main
  tg_file_id:         { type: String, default: null },  // file_id Telegram APK main
  tg_plugin_msg_id:   { type: Number, default: null },  // ID mensaje Telegram APK plugin
  tg_plugin_file_id:  { type: String, default: null },  // file_id Telegram APK plugin
  tutorial_url:       { type: String, default: null },
  updatedAt:          { type: Date, default: Date.now },
  createdAt:          { type: Date, default: Date.now },
}));

const AppRating = mongoose.model('AppRating', new mongoose.Schema({
  appId:   { type: String, required: true, index: true },
  appName: { type: String },
  ratings: [{ ip: String, stars: Number, createdAt: { type: Date, default: Date.now } }],
  total:   { type: Number, default: 0 },
  count:   { type: Number, default: 0 },
}));

const AppRequest = mongoose.model('AppRequest', new mongoose.Schema({
  appName:   { type: String, required: true },
  reason:    { type: String, default: '' },
  ip:        { type: String },
  votes:     { type: Number, default: 1 },
  voters:    [String],
  status:    { type: String, enum: ['pending','done','rejected'], default: 'pending' },
  createdAt: { type: Date, default: Date.now },
}));

let dbConnected = false;
async function connectDB() {
  if (!process.env.MONGODB_URI) { console.warn('⚠️  MONGODB_URI no configurado'); return false; }
  try {
    await mongoose.connect(process.env.MONGODB_URI, { serverSelectionTimeoutMS: 5000 });
    console.log('✅ MongoDB Atlas conectado'); return true;
  } catch (err) { console.error('❌ MongoDB error:', err.message); return false; }
}

// ── AUTH ADMIN ────────────────────────────────────────────────
function requireAdmin(req, res, next) {
  const key      = req.headers['x-admin-key']  || req.body?.adminKey;
  const user     = req.headers['x-admin-user'] || req.body?.adminUser || null;
  const validKey  = process.env.ADMIN_KEY;
  const validUser = process.env.ADMIN_USER;   // opcional — si no está, solo valida la key

  if (!validKey) {
    console.error('⚠️  ADMIN_KEY no configurada en variables de entorno de Render');
    return res.status(503).json({ error: 'Servidor no configurado — falta ADMIN_KEY en Render' });
  }
  if (key !== validKey) return res.status(403).json({ error: 'Credenciales incorrectas' });
  // Si ADMIN_USER está configurado en Render, también lo validamos
  if (validUser && user && user !== validUser) return res.status(403).json({ error: 'Credenciales incorrectas' });
  next();
}

// ── TELEGRAM STORAGE ─────────────────────────────────────────
// APKs se almacenan en el chat personal del bot con el admin.
// Variables Render: TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID
const TG_TOKEN   = process.env.TELEGRAM_BOT_TOKEN;
const TG_CHAT_ID = process.env.TELEGRAM_CHAT_ID;

/**
 * Sube un buffer como documento a Telegram.
 * Devuelve { messageId, fileId, downloadUrl }
 */
async function uploadToTelegram(buffer, fileName, caption = '') {
  if (!TG_TOKEN || !TG_CHAT_ID) throw new Error('TELEGRAM_BOT_TOKEN o TELEGRAM_CHAT_ID no configurados en Render');

  const https    = require('https');
  const boundary = '----FormBoundary' + Date.now().toString(16);
  const CRLF     = '\r\n';

  // Construir multipart/form-data manualmente sin dependencias
  const addField = (name, value) =>
    `--${boundary}${CRLF}Content-Disposition: form-data; name="${name}"${CRLF}${CRLF}${value}${CRLF}`;

  const fileHeader =
    `--${boundary}${CRLF}` +
    `Content-Disposition: form-data; name="document"; filename="${fileName}"${CRLF}` +
    `Content-Type: application/vnd.android.package-archive${CRLF}${CRLF}`;

  const preamble = Buffer.from(addField('chat_id', TG_CHAT_ID) + addField('caption', caption || fileName) + fileHeader);
  const closing  = Buffer.from(`${CRLF}--${boundary}--${CRLF}`);
  const totalLength = preamble.length + buffer.length + closing.length;

  // Helper GET simple para Telegram
  const httpsGet = (path) => new Promise((resolve, reject) => {
    https.get({ hostname: 'api.telegram.org', path }, (res) => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => {
        try { resolve(JSON.parse(Buffer.concat(chunks).toString())); }
        catch (e) { reject(new Error('JSON parse: ' + e.message)); }
      });
    }).on('error', reject);
  });

  // Upload multipart con streaming en chunks — soporta archivos grandes (sin límite de Telegram)
  const uploadData = await new Promise((resolve, reject) => {
    const req = https.request({
      hostname: 'api.telegram.org',
      path: `/bot${TG_TOKEN}/sendDocument`,
      method: 'POST',
      headers: {
        'Content-Type': `multipart/form-data; boundary=${boundary}`,
        'Content-Length': totalLength,
      },
    }, (res) => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => {
        try { resolve(JSON.parse(Buffer.concat(chunks).toString())); }
        catch (e) { reject(new Error('JSON parse respuesta: ' + e.message)); }
      });
    });

    req.on('error', reject);

    // Escribir en chunks de 64 KB para no bloquear el event loop con archivos grandes
    const CHUNK = 64 * 1024;
    req.write(preamble);
    let offset = 0;
    while (offset < buffer.length) {
      req.write(buffer.slice(offset, offset + CHUNK));
      offset += CHUNK;
    }
    req.write(closing);
    req.end();
  });

  if (!uploadData.ok) throw new Error('Telegram sendDocument: ' + (uploadData.description || JSON.stringify(uploadData)));

  const msg    = uploadData.result;
  const fileId = msg.document?.file_id;

  // Obtener file_path para URL de descarga directa
  const fileData = await httpsGet(`/bot${TG_TOKEN}/getFile?file_id=${fileId}`);
  if (!fileData.ok) throw new Error('Telegram getFile: ' + fileData.description);

  const filePath    = fileData.result?.file_path;
  const downloadUrl = `https://api.telegram.org/file/bot${TG_TOKEN}/${filePath}`;

  console.log(`✅ Telegram upload OK: ${fileName} | ${(buffer.length/1024/1024).toFixed(1)} MB | msg_id=${msg.message_id}`);
  return { messageId: msg.message_id, fileId, downloadUrl };
}

async function deleteFromTelegram(messageId) {
  if (!TG_TOKEN || !TG_CHAT_ID || !messageId) return false;
  try {
    const https = require('https');
    const data  = await new Promise((resolve, reject) => {
      https.get(
        `https://api.telegram.org/bot${TG_TOKEN}/deleteMessage?chat_id=${TG_CHAT_ID}&message_id=${messageId}`,
        (res) => {
          const chunks = [];
          res.on('data', c => chunks.push(c));
          res.on('end', () => {
            try { resolve(JSON.parse(Buffer.concat(chunks).toString())); }
            catch (e) { reject(e); }
          });
        }
      ).on('error', reject);
    });
    if (data.ok) { console.log(`🗑️ Telegram delete: msg_id=${messageId}`); return true; }
    console.warn('Telegram delete warning:', data.description);
    return false;
  } catch (e) { console.warn('Telegram delete error:', e.message); return false; }
}

// ── SUPABASE STORAGE (se mantiene para archivos pequeños <50 MB) ──
const STORAGE_BUCKET = 'CodeHub';

async function uploadToStorage(buffer, fileName) {
  if (!supabase) throw new Error('Supabase no configurado');
  console.log(`🔵 uploadToStorage START: \${fileName} (\${(buffer.length/1024/1024).toFixed(1)} MB)`);
  const { data, error } = await supabase.storage
    .from(STORAGE_BUCKET)
    .upload(fileName, buffer, {
      contentType: 'application/vnd.android.package-archive',
      upsert: true,
    });
  if (error) throw new Error('Error subiendo a Supabase Storage: ' + error.message);
  const { data: urlData } = supabase.storage.from(STORAGE_BUCKET).getPublicUrl(fileName);
  console.log(`✅ Supabase Storage upload: \${fileName}`);
  return { fileName, publicUrl: urlData.publicUrl };
}

async function deleteFromStorage(fileName) {
  if (!supabase || !fileName) return false;
  try {
    const { error } = await supabase.storage.from(STORAGE_BUCKET).remove([fileName]);
    if (error) { console.warn('Storage delete error:', error.message); return false; }
    console.log(`🗑️ Storage delete: \${fileName}`); return true;
  } catch (e) { console.warn('Storage delete error:', e.message); return false; }
}

// ── IA ────────────────────────────────────────────────────────
const SYSTEM = `Eres **EMI IA**, un asistente inteligente de propósito general integrado en el portfolio de Wilson.E.

## Quién eres
Eres un asistente versátil que puede responder sobre CUALQUIER tema — no estás limitado a CodeHub. Piensa en ti como un amigo muy preparado: sabe de programación, ciencias, historia, matemáticas, idiomas, cultura, entretenimiento, consejos personales, y mucho más. Si alguien te pregunta algo, lo respondes con honestidad y claridad.

## Personalidad
- Directo, amigable y sin rodeos. Como un buen amigo que sabe de todo.
- Siempre en español, salvo que el usuario escriba en otro idioma — en ese caso respondes en su idioma.
- Emojis con moderación, solo para dar énfasis natural.
- Respuestas cortas y claras por defecto. Si el usuario quiere más detalle, profundizas.
- Nunca inventas información. Si no sabes algo, lo dices directamente.
- Usas el historial de la conversación para dar respuestas coherentes.

## Puedes ayudar con cualquier tema, incluyendo:
- Programación, código, debugging, frameworks, arquitectura de software
- Matemáticas, física, química, biología, ciencias en general
- Historia, geografía, cultura, idiomas
- Consejos personales, productividad, vida cotidiana
- Entretenimiento, películas, música, videojuegos
- Escritura, creatividad, ideas, brainstorming
- Recetas, cocina, viajes, salud general
- Negocios, finanzas personales, emprendimiento
- Y cualquier otra cosa que el usuario necesite

## Sobre CodeHub (solo cuando te pregunten)
Si alguien pregunta sobre CodeHub o Wilson.E, responde con esto:

- **Wilson.E**: Dev Full Stack autodidacta de Guatemala City 🇬🇹. Stack: HTML, CSS, JS, Node.js, Python, MongoDB. Freelance disponible. Contacto: wilson.e360labs@gmail.com | WhatsApp +502 4146 8185
- **Herramientas** (tools.html): QR, contraseñas, Hash SHA-256/512, Base64, UUID, Regex, Pomodoro, conversor unidades/monedas, IMC, préstamos, test escritura, color, gradientes CSS, minificador, y más.
- **Apps Android** (novedades.html): Spotify Premium, YouTube ReVanced, YT Music ReVanced, TikTok Premium, PicsArt, Remini Pro, CamScanner, y más.
- **Otros**: Descargador de videos, juegos Snake y Tetris, servicios freelance en servicios.html.

## Formato de respuestas
- Código siempre en bloques con el lenguaje indicado
- Listas con guión (-) cuando hay varios puntos
- **Negritas** para términos clave
- Sin tablas largas — prefiere listas
- Nunca empieces con "¡Claro!" o "Por supuesto!" — ve directo al punto`;

async function callGroq(msgs) {
  const res = await fetch('https://api.groq.com/openai/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${process.env.GROQ_API_KEY}` },
    body: JSON.stringify({ model: 'llama-3.3-70b-versatile', max_tokens: 800, temperature: 0.65, messages: msgs }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.error?.message || `Groq ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.choices[0]?.message?.content || '', input: d.usage?.prompt_tokens||0, output: d.usage?.completion_tokens||0, model: 'groq/llama-3.3-70b' };
}

async function callGemini(msgs) {
  const contents = msgs.filter(m => m.role !== 'system').map(m => ({ role: m.role === 'assistant' ? 'model' : 'user', parts: [{ text: m.content }] }));
  const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=${process.env.GEMINI_API_KEY}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ systemInstruction: { parts: [{ text: SYSTEM }] }, contents, generationConfig: { maxOutputTokens: 600, temperature: 0.7 } }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.error?.message || `Gemini ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.candidates?.[0]?.content?.parts?.[0]?.text || '', input: d.usageMetadata?.promptTokenCount||0, output: d.usageMetadata?.candidatesTokenCount||0, model: 'gemini-1.5-flash' };
}


// Modelos gratuitos de OpenRouter en orden de preferencia
const OR_FREE_MODELS = [
  'meta-llama/llama-3.3-70b-instruct:free',      // Llama 3.3 70B — mejor general
  'google/gemini-2.0-flash-exp:free',              // Gemini 2.0 Flash — 1M contexto
  'mistralai/mistral-small-3.1-24b-instruct:free', // Mistral Small 3.1 — muy bueno
  'deepseek/deepseek-chat-v3-0324:free',           // DeepSeek V3 — razonamiento
  'nvidia/llama-3.1-nemotron-nano-8b-v1:free',     // NVIDIA Nemotron — rápido
  'openrouter/free',                               // Auto-router — elige el mejor disponible
];

async function callOpenRouterModel(msgs, model) {
  const res = await fetch('https://openrouter.ai/api/v1/chat/completions', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${process.env.OPENROUTER_API_KEY}`,
      'HTTP-Referer': process.env.FRONTEND_URL || 'https://wilson360-labs.vercel.app',
      'X-Title': 'EMI IA',
    },
    body: JSON.stringify({
      model,
      max_tokens: 800,
      temperature: 0.65,
      messages: msgs,
    }),
  });
  if (!res.ok) {
    const e = await res.json().catch(() => ({}));
    const err = new Error(e.error?.message || `OpenRouter ${res.status}`);
    err.status = res.status;
    throw err;
  }
  const d = await res.json();
  const reply = d.choices[0]?.message?.content || '';
  if (!reply) throw new Error('OpenRouter devolvió respuesta vacía');
  return {
    reply,
    input: d.usage?.prompt_tokens || 0,
    output: d.usage?.completion_tokens || 0,
    model: `openrouter/${model.split('/').pop().replace(':free', '')}`,
  };
}

async function callOpenRouter(msgs) {
  if (!process.env.OPENROUTER_API_KEY) throw new Error('Sin OPENROUTER_API_KEY');
  // Intenta cada modelo gratuito en orden
  for (const model of OR_FREE_MODELS) {
    try {
      const result = await callOpenRouterModel(msgs, model);
      console.log(`✅ OpenRouter respondió con: ${model}`);
      return result;
    } catch (e) {
      if (e.status === 401) throw e; // Key inválida — no seguir intentando
      console.warn(`⚠️ OpenRouter ${model} falló: ${e.message}`);
    }
  }
  throw new Error('Todos los modelos de OpenRouter fallaron');
}

async function callMistral(msgs) {
  if (!process.env.MISTRAL_API_KEY) throw new Error('Sin MISTRAL_API_KEY');
  const mistralMsgs = msgs.map(m => ({
    role: m.role === 'system' ? 'system' : m.role,
    content: m.content,
  }));
  const res = await fetch('https://api.mistral.ai/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${process.env.MISTRAL_API_KEY}` },
    body: JSON.stringify({ model: 'mistral-small-latest', max_tokens: 800, temperature: 0.65, messages: mistralMsgs }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.error?.message || `Mistral ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.choices[0]?.message?.content || '', input: d.usage?.prompt_tokens||0, output: d.usage?.completion_tokens||0, model: 'mistral/mistral-small' };
}

async function callCohere(msgs) {
  if (!process.env.COHERE_API_KEY) throw new Error('Sin COHERE_API_KEY');
  const system = msgs.find(m => m.role === 'system')?.content || '';
  const chatHistory = msgs.filter(m => m.role !== 'system').slice(0, -1).map(m => ({
    role: m.role === 'assistant' ? 'CHATBOT' : 'USER',
    message: m.content,
  }));
  const lastMsg = msgs.filter(m => m.role !== 'system').slice(-1)[0]?.content || '';
  const res = await fetch('https://api.cohere.com/v1/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${process.env.COHERE_API_KEY}` },
    body: JSON.stringify({ model: 'command-r', message: lastMsg, chat_history: chatHistory, preamble: system, max_tokens: 800, temperature: 0.65 }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.message || `Cohere ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.text || '', input: d.meta?.tokens?.input_tokens||0, output: d.meta?.tokens?.output_tokens||0, model: 'cohere/command-r' };
}

async function callAI(msgs) {
  const providers = [
    { name: 'Groq',        fn: () => callGroq(msgs),        key: process.env.GROQ_API_KEY },
    { name: 'OpenRouter',  fn: () => callOpenRouter(msgs),  key: process.env.OPENROUTER_API_KEY },
    { name: 'Gemini',      fn: () => callGemini(msgs),      key: process.env.GEMINI_API_KEY },
    { name: 'Mistral',     fn: () => callMistral(msgs),     key: process.env.MISTRAL_API_KEY },
    { name: 'Cohere',      fn: () => callCohere(msgs),      key: process.env.COHERE_API_KEY },
  ];

  const available = providers.filter(p => p.key);
  if (!available.length) throw new Error('Sin API keys de IA configuradas');

  for (const provider of available) {
    try {
      const result = await provider.fn();
      console.log(`✅ IA respondió via ${provider.name}`);
      return result;
    } catch (e) {
      if (e.status === 401) { console.warn(`❌ ${provider.name}: API key inválida`); continue; }
      if (e.status === 429) { console.warn(`⚠️ ${provider.name}: rate limit, probando siguiente...`); continue; }
      console.warn(`⚠️ ${provider.name} falló (${e.message}), probando siguiente...`);
    }
  }
  throw new Error('Todos los proveedores de IA fallaron');
}

async function validateTurnstile(token) {
  if (!process.env.TURNSTILE_SECRET) return true;
  if (!token) return false;
  try {
    const r = await fetch('https://challenges.cloudflare.com/turnstile/v0/siteverify', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ secret: process.env.TURNSTILE_SECRET, response: token }),
    });
    return (await r.json()).success === true;
  } catch { return true; }
}

// ════════════════════════════════════════════════════════════════
//  MÓDULOS EXTERNOS
// ════════════════════════════════════════════════════════════════

// ── Universal Resolver — Desencriptación heurística de links ──
const universalResolverRouter = require('./modules/universal-resolver');
app.use('/api/resolver', universalResolverRouter);

// ════════════════════════════════════════════════════════════════
//  RUTAS
// ════════════════════════════════════════════════════════════════

// ── ESTADÍSTICAS SUPABASE ────────────────────────────────────
app.get('/api/stats/supabase', async (_, res) => {
  if (!supabase) return res.status(503).json({ error: 'Supabase no configurado' });
  try {
    const [daily, tools, downloads, total] = await Promise.all([
      supabase.from('daily_stats').select('*').order('date', { ascending: false }).limit(30),
      supabase.from('tool_stats').select('*').order('uses', { ascending: false }).limit(10),
      supabase.from('download_stats').select('*').order('downloads', { ascending: false }).limit(10),
      supabase.from('events').select('id', { count: 'exact', head: true }),
    ]);
    res.json({
      daily:     daily.data     || [],
      tools:     tools.data     || [],
      downloads: downloads.data || [],
      total_events: total.count || 0,
    });
  } catch(e) { res.status(500).json({ error: e.message }); }
});

// Health
app.get('/api/health', (_, res) => res.json({
  status: 'ok', version: '3.2',
  mongo:     dbConnected ? 'connected' : 'disconnected',
  redis:     redis       ? 'connected' : 'memory',
  ws:        wsClients.size + ' clients',
  groq:      process.env.GROQ_API_KEY        ? 'ok' : 'missing',
  openrouter:process.env.OPENROUTER_API_KEY  ? 'ok (' + OR_FREE_MODELS.length + ' modelos)' : 'missing',
  gemini:    process.env.GEMINI_API_KEY      ? 'ok' : 'missing',
  minimax:   process.env.MINIMAX_API_KEY     ? 'ok' : 'missing',
  virustotal:process.env.VIRUSTOTAL_API_KEY  ? 'ok' : 'missing',
  mistral:   process.env.MISTRAL_API_KEY     ? 'ok' : 'missing',
  cohere:    process.env.COHERE_API_KEY      ? 'ok' : 'missing',
  storage:   supabase ? 'supabase' : 'missing',
  uptime:    Math.floor(process.uptime()) + 's',
  ip_geo:    'ip-api.com + ipwho.is (fallback)',
}));

// Stats en vivo
app.get('/api/stats/live', (_, res) => {
  trackVisit();
  res.json({ visitors: visits.today, total: visits.total, wsClients: wsClients.size });
});


// ── POST /api/visit — Registrar visita con IPQuery ───────────
app.post('/api/visit', async (req, res) => {
  try {
    // Vercel/Render: IP real del cliente
    const rawIp =
      req.headers['x-real-ip'] ||
      (req.headers['x-forwarded-for'] || '').split(',')[0].trim() ||
      req.body?.ip ||
      req.socket?.remoteAddress ||
      req.ip || 'unknown';

    const finalIp = rawIp.replace(/^::ffff:/, '').trim();
    const isLocal = /^(127\.|10\.|192\.168\.|::1|localhost|^$)/i.test(finalIp);

    let geo = {};
    if (!isLocal && finalIp !== 'unknown') {
      // Primario: ip-api.com (gratuito, sin key, muy fiable)
      try {
        const fields = 'status,country,countryCode,regionName,city,isp,org,proxy,hosting';
        const r = await fetch(
          `http://ip-api.com/json/${encodeURIComponent(finalIp)}?fields=${fields}`,
          { signal: AbortSignal.timeout(6000) }
        );
        if (r.ok) {
          const d = await r.json();
          if (d.status === 'success') {
            geo = d;
            console.log(`ip-api [${finalIp}]:`, d.country, d.city);
          }
        }
      } catch (e) { console.warn('ip-api error:', e.message); }

      // Fallback: ipwho.is (HTTPS, sin key)
      if (!geo.country) {
        try {
          const r2 = await fetch(
            `https://ipwho.is/${encodeURIComponent(finalIp)}`,
            { signal: AbortSignal.timeout(6000) }
          );
          if (r2.ok) {
            const d2 = await r2.json();
            if (d2.success) {
              geo = {
                country:     d2.country,
                countryCode: d2.country_code,
                regionName:  d2.region,
                city:        d2.city,
                isp:         d2.connection?.isp  || null,
                org:         d2.connection?.org  || null,
                proxy:       false,
                hosting:     false,
              };
              console.log(`ipwho.is [${finalIp}]:`, d2.country, d2.city);
            }
          }
        } catch (e2) { console.warn('ipwho.is error:', e2.message); }
      }
    }

    const record = {
      ip:           finalIp,
      country:      geo?.country      || null,
      country_code: geo?.countryCode  || null,
      city:         geo?.city         || null,
      region:       geo?.regionName   || null,
      isp:          geo?.isp          || null,
      org:          geo?.org          || null,
      is_vpn:       geo?.proxy        || false,
      is_proxy:     geo?.proxy        || false,
      is_bot:       geo?.hosting      || false,
      risk_score:   geo?.proxy ? 60 : (geo?.hosting ? 30 : 0),
      page:         String(req.body?.page || '/').slice(0, 200),
      ua:           (req.headers['user-agent'] || '').slice(0, 300),
      visited_at:   new Date().toISOString(),
    };

    // Guardar en Supabase y registrar visita en paralelo
    const [_saved] = await Promise.allSettled([
      supabase ? supabase.from('visitor_logs').insert(record) : Promise.resolve(),
    ]);
    if (_saved?.status === 'rejected') console.warn('visitor_logs insert error:', _saved.reason?.message);
    trackVisit();
    res.json({ ok: true, ip: finalIp });
  } catch (e) {
    console.warn('visit error:', e.message);
    res.json({ ok: false });
  }
});

// ── GET /api/admin/visitors — Listar visitas (solo admin) ─────
app.get('/api/admin/visitors', requireAdmin, async (req, res) => {
  if (!supabase) return res.status(503).json({ error: 'Supabase no configurado' });
  try {
    const limit = Math.min(parseInt(req.query.limit) || 200, 500);
    const { data, error } = await supabase
      .from('visitor_logs')
      .select('*')
      .order('visited_at', { ascending: false })
      .limit(limit);
    if (error) throw error;
    res.json({ ok: true, visitors: data || [] });
  } catch (e) {
    res.status(500).json({ ok: false, error: e.message });
  }
});

// Info WebSocket para el frontend
app.get('/api/ws-info', (_, res) => res.json({
  clients: wsClients.size,
  url: process.env.WS_URL || 'wss://codehub-production-729d.up.railway.app/ws',
}));

// Apps públicas (con caché 5 min)
app.get('/api/apps', async (_, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible', apps: [] });
  try {
    const cached = await cacheGet('apps:all');
    if (cached) { res.set('X-Cache', 'HIT'); return res.json(cached); }

    const apps = await App.find({}).sort({ createdAt: 1 }).lean();
    const base  = process.env.BACKEND_URL || 'https://codehub-production-729d.up.railway.app';
    const mapped = apps.map(a => ({
      appId:        a.appId,
      nombre:       a.nombre,
      descripcion:  a.descripcion,
      version:      a.version,
      tag:          a.tag,
      changelog:    a.changelog,
      imagen:       a.imagen,
      categoria:    a.categoria,
      verified:     a.verified,
      enlace:       a.enlace || '#',
      plugin_enlace:a.plugin_enlace || null,
      tutorial_url: a.tutorial_url || null,
      updatedAt:    a.updatedAt,
    }));
    const result = { apps: mapped, total: mapped.length };
    await cacheSet('apps:all', result, 300);
    res.set('X-Cache', 'MISS'); res.json(result);
  } catch { res.status(500).json({ error: 'Error obteniendo apps' }); }
});

// Chat IA
app.post('/api/chat', async (req, res) => {
  const { message, sessionId = 'anon' } = req.body;
  if (!message || typeof message !== 'string') return res.status(400).json({ error: '"message" requerido.' });
  if (message.trim().length > 1000) return res.status(400).json({ error: 'Mensaje muy largo.' });
  if (!process.env.GROQ_API_KEY && !process.env.GEMINI_API_KEY) return res.status(503).json({ error: 'Sin API keys.' });

  // Recuperar historial real de MongoDB (ultimos 10 mensajes de la sesion)
  let sessionHistory = [];
  if (dbConnected && sessionId !== 'anon') {
    try {
      const pastMsgs = await ChatMessage
        .find({ sessionId })
        .sort({ createdAt: -1 })
        .limit(10)
        .lean();
      // Vienen en orden descendente, los invertimos
      sessionHistory = pastMsgs.reverse().map(m => ({
        role: m.role === 'assistant' ? 'assistant' : 'user',
        content: String(m.content).slice(0, 800),
      }));
    } catch (e) {
      console.warn('Error recuperando historial:', e.message);
    }
  }

  sessionHistory.push({ role: 'user', content: message.trim() });
  const msgs = [{ role: 'system', content: SYSTEM }, ...sessionHistory];

  try {
    const { reply, input, output, model } = await callAI(msgs);
    if (dbConnected) ChatMessage.insertMany([
      { sessionId, role: 'user',      content: message.trim(), tokens: input,  model },
      { sessionId, role: 'assistant', content: reply,          tokens: output, model },
    ]).catch(() => {});
    broadcast('chat_used', { model, tokens: input + output });
    trackEvent('chat', null, { model, tokens: input + output });
    res.json({ reply, usage: { input, output, total: input + output }, model });
  } catch (err) {
    if (err.status === 401) return res.status(500).json({ error: 'API key inválida.' });
    if (err.status === 429) return res.status(429).json({ error: 'Límite alcanzado.' });
    res.status(500).json({ error: 'Error interno.' });
  }
});

// Contacto (notifica vía WS)
app.post('/api/contact', (req, res) => {
  const { name, email } = req.body;
  trackEvent('contact');
  broadcast('contact_form', {
    name:  name  || 'Anónimo',
    email: email ? email.replace(/(.{2}).*(@.*)/, '$1***$2') : '?',
  });
  res.json({ ok: true });
});

// Ratings
app.get('/api/ratings', async (_, res) => {
  if (!dbConnected) return res.json({ ratings: {} });
  try {
    const cached = await cacheGet('ratings:all'); if (cached) return res.json(cached);
    const all = await AppRating.find({}, 'appId total count');
    const ratings = {};
    all.forEach(r => { ratings[r.appId] = { avg: r.count > 0 ? Math.round((r.total / r.count) * 10) / 10 : 0, count: r.count }; });
    const result = { ratings };
    await cacheSet('ratings:all', result, 120);
    res.json(result);
  } catch { res.json({ ratings: {} }); }
});

app.post('/api/ratings', async (req, res) => {
  const { appId, appName, stars } = req.body; const ip = req.ip || 'anon';
  if (!appId || !stars || stars < 1 || stars > 5) return res.status(400).json({ error: 'Datos inválidos' });
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    let r = await AppRating.findOne({ appId });
    if (!r) r = new AppRating({ appId, appName: appName || appId, ratings: [], total: 0, count: 0 });
    const already = r.ratings.find(x => x.ip === ip);
    if (already) return res.status(409).json({ error: 'Ya votaste', avg: r.count > 0 ? Math.round((r.total/r.count)*10)/10 : 0, count: r.count });
    r.ratings.push({ ip, stars }); r.total += stars; r.count += 1;
    await r.save(); await cacheDel('ratings:all');
    const avg = Math.round((r.total / r.count) * 10) / 10;
    broadcast('new_rating', { appId, appName: appName || appId, stars, avg, count: r.count });
    res.json({ ok: true, avg, count: r.count });
  } catch { res.status(500).json({ error: 'Error guardando rating' }); }
});

// Requests de apps
app.get('/api/requests', async (_, res) => {
  if (!dbConnected) return res.json({ requests: [] });
  try { const reqs = await AppRequest.find({ status: 'pending' }).sort({ votes: -1 }).limit(20); res.json({ requests: reqs }); }
  catch { res.json({ requests: [] }); }
});

app.post('/api/requests', async (req, res) => {
  const { appName, reason, turnstileToken } = req.body; const ip = req.ip || 'anon';
  if (!appName || appName.trim().length < 2) return res.status(400).json({ error: 'Nombre requerido' });
  if (!await validateTurnstile(turnstileToken)) return res.status(403).json({ error: 'Verificación fallida' });
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    const existing = await AppRequest.findOne({ appName: new RegExp(appName.trim(), 'i'), status: 'pending' });
    if (existing) {
      if (existing.voters.includes(ip)) return res.status(409).json({ error: 'Ya votaste', votes: existing.votes });
      existing.votes += 1; existing.voters.push(ip); await existing.save();
      return res.json({ ok: true, message: 'Voto agregado', votes: existing.votes });
    }
    const newReq = new AppRequest({ appName: appName.trim(), reason: reason?.trim() || '', ip, voters: [ip] });
    await newReq.save(); res.json({ ok: true, message: 'Solicitud enviada', id: newReq._id });
  } catch { res.status(500).json({ error: 'Error guardando solicitud' }); }
});

// Download APK (Supabase Storage URL pública)
app.get('/api/download/:fileName', async (req, res) => {
  const { fileName } = req.params;
  if (!fileName || fileName.includes('..')) return res.status(400).json({ error: 'Nombre inválido' });
  try {
    if (!supabase) return res.status(503).json({ error: 'Storage no disponible' });
    const { data } = supabase.storage.from(STORAGE_BUCKET).getPublicUrl(decodeURIComponent(fileName));
    broadcast('download', { fileName: decodeURIComponent(fileName) });
    trackEvent('download', null, { app_name: decodeURIComponent(fileName) });
    res.redirect(302, data.publicUrl);
  } catch (e) { console.error('Error download:', e.message); res.status(500).json({ error: 'No se pudo generar el link.' }); }
});

// ════════════════════════════════════════════════════════════════
//  ADMIN
// ════════════════════════════════════════════════════════════════

app.get('/api/admin/apps', requireAdmin, async (_, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try { const apps = await App.find({}).sort({ createdAt: 1 }).lean(); res.json({ apps, total: apps.length }); }
  catch { res.status(500).json({ error: 'Error obteniendo apps' }); }
});

app.post('/api/admin/apps', requireAdmin, async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    const { appId, nombre, descripcion, version, tag, changelog, imagen, categoria, verified, enlace, plugin_enlace } = req.body;
    if (!appId || !nombre) return res.status(400).json({ error: 'appId y nombre son requeridos' });
    if (await App.findOne({ appId })) return res.status(409).json({ error: 'Ya existe una app con ese appId' });
    const a = await App.create({ appId, nombre, descripcion, version, tag: tag || '🆕', changelog, imagen, categoria, verified: verified !== false, enlace: enlace || '#', plugin_enlace: plugin_enlace || null });
    await cacheDel('apps:all');
    broadcast('new_app', { appId, nombre, tag: tag || '🆕', categoria });
    res.json({ ok: true, app: a });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.patch('/api/admin/apps/:appId', requireAdmin, async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    const update = {};
    ['nombre','descripcion','version','tag','changelog','imagen','categoria','verified','enlace','plugin_enlace','tutorial_url']
      .forEach(f => { if (req.body[f] !== undefined) update[f] = req.body[f]; });
    update.updatedAt = new Date();
    const a = await App.findOneAndUpdate({ appId: req.params.appId }, update, { new: true });
    if (!a) return res.status(404).json({ error: 'App no encontrada' });
    await cacheDel('apps:all'); res.json({ ok: true, app: a });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.delete('/api/admin/apps/:appId', requireAdmin, async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    const a = await App.findOne({ appId: req.params.appId });
    if (!a) return res.status(404).json({ error: 'App no encontrada' });

    // Limpiar archivos en Telegram (mensajes con el APK)
    if (a.tg_message_id)     await deleteFromTelegram(a.tg_message_id);
    if (a.tg_plugin_msg_id)  await deleteFromTelegram(a.tg_plugin_msg_id);

    // Limpiar archivos en Supabase (fallback)
    if (a.b2_file_name)        await deleteFromStorage(a.b2_file_name);
    if (a.b2_plugin_file_name) await deleteFromStorage(a.b2_plugin_file_name);

    await App.deleteOne({ appId: req.params.appId });
    await cacheDel('apps:all');
    res.json({ ok: true });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// ── DELETE /api/admin/apps/:appId/apk — Elimina solo el APK de Telegram/Storage sin borrar la app
// Útil para reemplazar un APK desactualizado antes de subir uno nuevo, o limpiar storage manualmente.
// Query param: ?slot=main (default) | ?slot=plugin
app.delete('/api/admin/apps/:appId/apk', requireAdmin, async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    const a = await App.findOne({ appId: req.params.appId });
    if (!a) return res.status(404).json({ error: 'App no encontrada' });

    const isPlugin = req.query.slot === 'plugin';
    const upd = { updatedAt: new Date() };
    let deleted = { telegram: false, supabase: false };

    if (isPlugin) {
      if (a.tg_plugin_msg_id)    { deleted.telegram = await deleteFromTelegram(a.tg_plugin_msg_id); }
      if (a.b2_plugin_file_name) { deleted.supabase = await deleteFromStorage(a.b2_plugin_file_name); }
      Object.assign(upd, {
        tg_plugin_msg_id: null, tg_plugin_file_id: null,
        b2_plugin_file_name: null, plugin_enlace: null,
      });
    } else {
      if (a.tg_message_id) { deleted.telegram = await deleteFromTelegram(a.tg_message_id); }
      if (a.b2_file_name)  { deleted.supabase = await deleteFromStorage(a.b2_file_name); }
      Object.assign(upd, {
        tg_message_id: null, tg_file_id: null,
        b2_file_name: null, enlace: '#',
      });
    }

    await App.updateOne({ appId: req.params.appId }, upd);
    await cacheDel('apps:all');
    console.log(`🗑️ APK eliminado: ${req.params.appId} [slot=${isPlugin ? 'plugin' : 'main'}]`);
    res.json({ ok: true, appId: req.params.appId, slot: isPlugin ? 'plugin' : 'main', deleted });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.post('/api/admin/apps/:appId/upload', requireAdmin, upload.single('apk'), async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  if (!req.file)    return res.status(400).json({ error: 'No se recibió archivo' });
  const { appId } = req.params;
  const isPlugin   = req.body.slot === 'plugin';
  const sizeMB     = (req.file.size / 1024 / 1024).toFixed(1);
  try {
    const a = await App.findOne({ appId });
    if (!a) return res.status(404).json({ error: 'App no encontrada' });

    const fileName = `${appId}_${isPlugin ? 'plugin' : 'main'}_${Date.now()}.apk`;
    const caption  = `📦 ${a.nombre} — ${isPlugin ? 'Plugin' : 'APK'} v${a.version || '?'} (${sizeMB} MB)`;

    // Usar Telegram si está configurado, si no caer en Supabase
    const useTelegram = !!(TG_TOKEN && TG_CHAT_ID);
    let downloadUrl, upd;

    if (useTelegram) {
      // 1. Intentar eliminar mensaje anterior del chat (APK viejo).
      // Si ya fue borrado manualmente desde Telegram, deleteFromTelegram retorna false sin lanzar error.
      const oldMsgId = isPlugin ? a.tg_plugin_msg_id : a.tg_message_id;
      if (oldMsgId) {
        await deleteFromTelegram(oldMsgId);
        // Limpiar el ID en DB independientemente — si falla el delete, el msg ya no existe
        await App.updateOne({ appId }, isPlugin
          ? { tg_plugin_msg_id: null, tg_plugin_file_id: null }
          : { tg_message_id: null, tg_file_id: null }
        );
      }

      // 2. Subir nuevo APK a Telegram
      const { messageId, fileId, downloadUrl: tgUrl } = await uploadToTelegram(req.file.buffer, fileName, caption);
      downloadUrl = tgUrl;

      upd = isPlugin
        ? { tg_plugin_msg_id: messageId, tg_plugin_file_id: fileId, plugin_enlace: tgUrl, updatedAt: new Date() }
        : { tg_message_id: messageId,    tg_file_id: fileId,        enlace: tgUrl,         updatedAt: new Date() };
    } else {
      // Fallback: Supabase Storage (límite 50 MB)
      if (!isPlugin && a.b2_file_name) await deleteFromStorage(a.b2_file_name);
      if  (isPlugin && a.b2_plugin_file_name) await deleteFromStorage(a.b2_plugin_file_name);
      const { publicUrl } = await uploadToStorage(req.file.buffer, fileName);
      downloadUrl = publicUrl;
      upd = isPlugin
        ? { b2_plugin_file_name: fileName, plugin_enlace: publicUrl, updatedAt: new Date() }
        : { b2_file_name: fileName,        enlace: publicUrl,         updatedAt: new Date() };
    }

    await App.updateOne({ appId }, upd);
    await cacheDel('apps:all');
    res.json({ ok: true, fileName, downloadUrl, sizeMB, storage: useTelegram ? 'telegram' : 'supabase' });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.patch('/api/requests/:id', requireAdmin, async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try { await AppRequest.findByIdAndUpdate(req.params.id, { status: req.body.status }); res.json({ ok: true }); }
  catch { res.status(500).json({ error: 'Error actualizando' }); }
});

app.post('/api/admin/seed', requireAdmin, async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    const { apps } = req.body;
    if (!apps || !Array.isArray(apps)) return res.status(400).json({ error: 'Se esperaba { apps: [...] }' });
    let created = 0, updated = 0;
    for (const a of apps) {
      const id = a.appId || a.id;
      const exists = await App.findOne({ appId: id });
      if (exists) {
        await App.updateOne({ appId: id }, { $set: { nombre: a.nombre||a.name, enlace: a.enlace||'#', version: a.version_conocida||a.ver||'', tag: a.tag||'🆕', updatedAt: new Date() } });
        updated++;
      } else {
        await App.create({ appId: id, nombre: a.nombre||a.name, descripcion: a.descripcion||'', version: a.version_conocida||a.ver||'', tag: a.tag||'🆕', changelog: a.changelog||'', imagen: a.imagen||'', categoria: a.categoria||a.cat||'', verified: a.verified!==false, enlace: a.enlace||'#', plugin_enlace: a.plugin_enlace||null });
        created++;
      }
    }
    await cacheDel('apps:all');
    res.json({ ok: true, created, updated });
  } catch (e) { res.status(500).json({ error: e.message }); }
});


// ── POST /api/generate-image — Generador IA con 4 proveedores ─
app.post('/api/generate-image', chatLimiter, async (req, res) => {
  const { prompt, width = 512, height = 512, provider = 'auto' } = req.body;
  if (!prompt || typeof prompt !== 'string' || prompt.trim().length < 2) {
    return res.status(400).json({ error: 'Prompt requerido' });
  }

  const p   = prompt.trim().slice(0, 500);
  const w   = Math.min(Math.max(parseInt(width)  || 512, 256), 1024);
  const h   = Math.min(Math.max(parseInt(height) || 512, 256), 1024);
  const errors = [];

  // ── 1. Together AI — FLUX.1 Schnell ───────────────────────
  if (process.env.TOGETHER_API_KEY && (provider === 'auto' || provider === 'together')) {
    try {
      const r = await fetch('https://api.together.xyz/v1/images/generations', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${process.env.TOGETHER_API_KEY}`
        },
        body: JSON.stringify({
          model: 'black-forest-labs/FLUX.1-schnell-Free',
          prompt: p,
          width: w,
          height: h,
          steps: 4,
          n: 1,
        })
      });
      if (r.ok) {
        const d = await r.json();
        const b64 = d.data?.[0]?.b64_json;
        const url = d.data?.[0]?.url;
        if (b64) return res.json({ ok: true, provider: 'together', model: 'FLUX.1-schnell', image: `data:image/png;base64,${b64}` });
        if (url) return res.json({ ok: true, provider: 'together', model: 'FLUX.1-schnell', url });
      } else {
        const e = await r.json().catch(() => ({}));
        errors.push(`Together: ${e.error?.message || r.status}`);
      }
    } catch (e) { errors.push(`Together: ${e.message}`); }
  }

  // ── 2. Gemini — Imagen 3 Fast ─────────────────────────────
  if (process.env.GEMINI_API_KEY && (provider === 'auto' || provider === 'gemini')) {
    try {
      const r = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/imagen-3.0-fast-generate-001:predict?key=${process.env.GEMINI_API_KEY}`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            instances: [{ prompt: p }],
            parameters: { sampleCount: 1, aspectRatio: w > h ? '16:9' : w === h ? '1:1' : '9:16' }
          })
        }
      );
      if (r.ok) {
        const d = await r.json();
        const b64 = d.predictions?.[0]?.bytesBase64Encoded;
        if (b64) return res.json({ ok: true, provider: 'gemini', model: 'Imagen 3 Fast', image: `data:image/png;base64,${b64}` });
      } else {
        const e = await r.json().catch(() => ({}));
        errors.push(`Gemini: ${e.error?.message || r.status}`);
      }
    } catch (e) { errors.push(`Gemini: ${e.message}`); }
  }

  // ── 3. Pollinations — Flux (sin key) ──────────────────────
  // MiniMax - image-01
  if (process.env.MINIMAX_API_KEY && (provider === 'auto' || provider === 'minimax')) {
    try {
      const aspectRatio = w > h ? '16:9' : w < h ? '9:16' : '1:1';
      const r = await fetch('https://api.minimax.io/v1/image_generation', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${process.env.MINIMAX_API_KEY}`
        },
        body: JSON.stringify({
          model: 'image-01',
          prompt: p,
          aspect_ratio: aspectRatio,
          response_format: 'base64',
          n: 1,
        })
      });
      if (r.ok) {
        const d = await r.json();
        const b64 =
          d.data?.base64?.[0] ||
          d.data?.images?.[0]?.base64 ||
          d.data?.image_base64?.[0];
        const url = d.data?.image_urls?.[0];
        if (b64) return res.json({ ok: true, provider: 'minimax', model: 'image-01', image: `data:image/png;base64,${b64}` });
        if (url) return res.json({ ok: true, provider: 'minimax', model: 'image-01', url });
        errors.push('MiniMax: respuesta sin imagen');
      } else {
        const e = await r.json().catch(() => ({}));
        errors.push(`MiniMax: ${e.base_resp?.status_msg || e.message || r.status}`);
      }
    } catch (e) { errors.push(`MiniMax: ${e.message}`); }
  }

  if (provider === 'auto' || provider === 'pollinations') {
    try {
      const seed = Math.floor(Math.random() * 99999);
      const polUrl = `https://image.pollinations.ai/prompt/${encodeURIComponent(p)}?width=${w}&height=${h}&seed=${seed}&model=flux&nologo=true`;
      const r = await fetch(polUrl, { signal: AbortSignal.timeout(25000) });
      if (r.ok) {
        const buf = await r.arrayBuffer();
        const b64 = Buffer.from(buf).toString('base64');
        return res.json({ ok: true, provider: 'pollinations', model: 'Flux', image: `data:image/jpeg;base64,${b64}` });
      } else {
        errors.push(`Pollinations: ${r.status}`);
      }
    } catch (e) { errors.push(`Pollinations: ${e.message}`); }
  }

  // ── 4. Pollinations Turbo (fallback) ──────────────────────
  try {
    const seed2 = Math.floor(Math.random() * 99999);
    const url2  = `https://image.pollinations.ai/prompt/${encodeURIComponent(p)}?width=512&height=512&seed=${seed2}&model=turbo&nologo=true`;
    const r2 = await fetch(url2, { signal: AbortSignal.timeout(20000) });
    if (r2.ok) {
      const buf = await r2.arrayBuffer();
      const b64 = Buffer.from(buf).toString('base64');
      return res.json({ ok: true, provider: 'pollinations-turbo', model: 'Turbo', image: `data:image/jpeg;base64,${b64}` });
    }
    errors.push(`Pollinations Turbo: ${r2.status}`);
  } catch (e) { errors.push(`Pollinations Turbo: ${e.message}`); }

  // Todos fallaron
  res.status(503).json({ ok: false, error: 'Todos los proveedores fallaron', details: errors });
});

// ── Helper: guardar log de escaneo en Supabase ───────────────
async function saveScanLog({ type, target, verdict, riskScore, provider, metadata = {} }) {
  if (!supabase) return;
  try {
    await supabase.from('scan_logs').insert({
      type,
      target,
      verdict,
      risk_score: riskScore,
      provider,
      metadata,
      scanned_at: new Date().toISOString(),
    });
  } catch (e) {
    console.warn('⚠️  scan_logs insert error:', e.message);
  }
}

// ── POST /api/check-link — VirusTotal URL checker ────────────
app.post('/api/check-link', chatLimiter, async (req, res) => {
  const rawUrl = String(req.body?.url || '').trim();
  if (!rawUrl) return res.status(400).json({ ok: false, error: 'URL requerida' });
  if (!process.env.VIRUSTOTAL_API_KEY) {
    return res.status(503).json({ ok: false, error: 'VIRUSTOTAL_API_KEY no configurada' });
  }

  let parsedUrl;
  try {
    parsedUrl = new URL(rawUrl);
    if (!/^https?:$/.test(parsedUrl.protocol)) throw new Error('Protocolo no soportado');
  } catch (_) {
    return res.status(400).json({ ok: false, error: 'URL invalida. Usa http:// o https://' });
  }

  try {
    const submitRes = await fetch('https://www.virustotal.com/api/v3/urls', {
      method: 'POST',
      headers: {
        'x-apikey': process.env.VIRUSTOTAL_API_KEY,
        'Content-Type': 'application/x-www-form-urlencoded'
      },
      body: `url=${encodeURIComponent(parsedUrl.toString())}`
    });

    const submitData = await submitRes.json().catch(() => ({}));
    if (!submitRes.ok) {
      return res.status(submitRes.status).json({
        ok: false,
        error: submitData.error?.message || `VirusTotal ${submitRes.status}`
      });
    }

    const analysisId = submitData.data?.id;
    if (!analysisId) {
      return res.status(502).json({ ok: false, error: 'VirusTotal no devolvio un analysis id' });
    }

    let analysisData = null;
    for (let i = 0; i < 4; i++) {
      const analysisRes = await fetch(`https://www.virustotal.com/api/v3/analyses/${encodeURIComponent(analysisId)}`, {
        headers: { 'x-apikey': process.env.VIRUSTOTAL_API_KEY }
      });
      analysisData = await analysisRes.json().catch(() => ({}));
      if (analysisRes.ok && analysisData.data?.attributes?.status === 'completed') break;
      await new Promise(resolve => setTimeout(resolve, 1800));
    }

    const attrs = analysisData?.data?.attributes || {};
    const stats = attrs.stats || {};
    const malicious = stats.malicious || 0;
    const suspicious = stats.suspicious || 0;
    const harmless = stats.harmless || 0;
    const undetected = stats.undetected || 0;
    const timeout = stats.timeout || 0;
    const riskScore = Math.min(100, (malicious * 25) + (suspicious * 12));

    let verdict = 'clean';
    let recommendation = 'No se detectaron senales claras de riesgo, pero aun conviene revisar el dominio antes de abrirlo.';

    if (malicious > 0) {
      verdict = 'malicious';
      recommendation = 'No abras este link. Bloquealo, no descargues archivos y evita compartirlo.';
    } else if (suspicious > 0) {
      verdict = 'suspicious';
      recommendation = 'Tratalo como sospechoso. Verifica el dominio, evita iniciar sesion y no descargues nada.';
    } else if (timeout > 0 && harmless === 0) {
      verdict = 'unknown';
      recommendation = 'El analisis no fue concluyente. Revisa el dominio manualmente antes de confiar.';
    }

    await saveScanLog({
      type: 'url',
      target: parsedUrl.toString(),
      verdict,
      riskScore,
      provider: 'virustotal',
      metadata: { malicious, suspicious, harmless, undetected, host: parsedUrl.hostname },
    });

    res.json({
      ok: true,
      provider: 'virustotal',
      url: parsedUrl.toString(),
      host: parsedUrl.hostname,
      verdict,
      riskScore,
      recommendation,
      stats: {
        malicious,
        suspicious,
        harmless,
        undetected,
        timeout
      },
      analysis: {
        id: analysisId,
        status: attrs.status || 'queued',
        date: attrs.date || null
      }
    });
  } catch (e) {
    res.status(500).json({ ok: false, error: e.message || 'No se pudo analizar el link' });
  }
});

// ── POST /api/check-file — VirusTotal file checker ───────────
app.post('/api/check-file', chatLimiter, (req, res) => {
  uploadSecurityFile.single('file')(req, res, async (err) => {
    if (err) {
      return res.status(400).json({
        ok: false,
        error: err.code === 'LIMIT_FILE_SIZE'
          ? 'El archivo supera el limite de 32 MB'
          : (err.message || 'No se pudo procesar el archivo')
      });
    }
    if (!req.file) return res.status(400).json({ ok: false, error: 'Archivo requerido' });
    if (!process.env.VIRUSTOTAL_API_KEY) {
      return res.status(503).json({ ok: false, error: 'VIRUSTOTAL_API_KEY no configurada' });
    }

    try {
      const sha256 = crypto.createHash('sha256').update(req.file.buffer).digest('hex');
      const form = new FormData();
      form.append('file', new Blob([req.file.buffer], { type: req.file.mimetype || 'application/octet-stream' }), req.file.originalname);

      const submitRes = await fetch('https://www.virustotal.com/api/v3/files', {
        method: 'POST',
        headers: { 'x-apikey': process.env.VIRUSTOTAL_API_KEY },
        body: form
      });

      const submitData = await submitRes.json().catch(() => ({}));
      if (!submitRes.ok) {
        return res.status(submitRes.status).json({
          ok: false,
          error: submitData.error?.message || `VirusTotal ${submitRes.status}`
        });
      }

      const analysisId = submitData.data?.id;
      if (!analysisId) {
        return res.status(502).json({ ok: false, error: 'VirusTotal no devolvio un analysis id para el archivo' });
      }

      let analysisData = null;
      for (let i = 0; i < 5; i++) {
        const analysisRes = await fetch(`https://www.virustotal.com/api/v3/analyses/${encodeURIComponent(analysisId)}`, {
          headers: { 'x-apikey': process.env.VIRUSTOTAL_API_KEY }
        });
        analysisData = await analysisRes.json().catch(() => ({}));
        if (analysisRes.ok && analysisData.data?.attributes?.status === 'completed') break;
        await new Promise(resolve => setTimeout(resolve, 1800));
      }

      const attrs = analysisData?.data?.attributes || {};
      const stats = attrs.stats || {};
      const malicious = stats.malicious || 0;
      const suspicious = stats.suspicious || 0;
      const harmless = stats.harmless || 0;
      const undetected = stats.undetected || 0;
      const riskScore = Math.min(100, (malicious * 25) + (suspicious * 12));

      let verdict = 'clean';
      let recommendation = 'No se detectaron amenazas claras, pero aun conviene verificar el origen del archivo antes de abrirlo.';
      if (malicious > 0) {
        verdict = 'malicious';
        recommendation = 'No abras ni ejecutes este archivo. Eliminalo o aisla la muestra.';
      } else if (suspicious > 0) {
        verdict = 'suspicious';
        recommendation = 'Tratalo como sospechoso. Evita ejecutarlo y confirma su origen primero.';
      }

      await saveScanLog({
        type: 'file',
        target: req.file.originalname,
        verdict,
        riskScore,
        provider: 'virustotal',
        metadata: { sha256, malicious, suspicious, harmless, undetected, mime: req.file.mimetype, size: req.file.size },
      });

      res.json({
        ok: true,
        provider: 'virustotal',
        fileName: req.file.originalname,
        mime: req.file.mimetype || 'application/octet-stream',
        size: req.file.size,
        sha256,
        verdict,
        riskScore,
        recommendation,
        stats: {
          malicious,
          suspicious,
          harmless,
          undetected
        },
        analysis: {
          id: analysisId,
          status: attrs.status || 'queued',
          date: attrs.date || null
        }
      });
    } catch (e) {
      res.status(500).json({ ok: false, error: e.message || 'No se pudo analizar el archivo' });
    }
  });
});


// ── GET /api/docs — Swagger UI inline ────────────────────────
app.get('/api/docs', (_, res) => {
  res.send(`<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>CodeHub API Docs</title>
  <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css">
  <style>
    body { margin: 0; background: #0a0a14; }
    .swagger-ui .topbar { background: #ff4500; }
    .swagger-ui .topbar .download-url-wrapper { display: none; }
    .swagger-ui .info .title { color: #ff4500; }
  </style>
</head>
<body>
  <div id="swagger-ui"></div>
  <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
  <script>
    SwaggerUIBundle({
      spec: ${JSON.stringify(swaggerSpec)},
      dom_id: '#swagger-ui',
      deepLinking: true,
      presets: [SwaggerUIBundle.presets.apis, SwaggerUIBundle.SwaggerUIStandalonePreset],
      layout: 'StandaloneLayout',
      tryItOutEnabled: true,
    });
  </script>
</body>
</html>`);
});

// ── GET /api/docs.json — spec en JSON ─────────────────────────
app.get('/api/docs.json', (_, res) => res.json(swaggerSpec));

// ── GET /api/image-search — Buscar imágenes via SerpAPI ───────
app.get('/api/image-search', chatLimiter, async (req, res) => {
  const { q } = req.query;
  if (!q || !q.trim()) return res.status(400).json({ error: 'Parámetro q requerido.' });

  const SERP_KEY = process.env.SERPAPI_KEY || 'ee57e47c06b28164f49977cc56a421483001b0e058f6826d36c085579d92cab2';

  try {
    const url = `https://serpapi.com/search.json?engine=google_images&q=${encodeURIComponent(q)}&hl=es&gl=gt&num=6&api_key=${SERP_KEY}`;
    const response = await fetch(url);
    if (!response.ok) throw new Error('SerpAPI error: ' + response.status);
    const data = await response.json();

    const images = (data.images_results || []).slice(0, 6).map(img => ({
      thumbnail: img.thumbnail,
      original:  img.original,
      title:     img.title || q,
      source:    img.source || '',
      link:      img.link  || img.original || '#',
    }));

    res.json({ images, query: q });
  } catch (err) {
    console.error('image-search error:', err.message);
    res.status(500).json({ error: 'No se pudieron obtener imágenes.' });
  }
});

// ── ARRANCAR ──────────────────────────────────────────────────
(async () => {
  await initRedis();
  dbConnected = await connectDB();
  if (supabase) console.log('✅ Supabase Storage listo — bucket:', STORAGE_BUCKET);

  server.listen(PORT, () => {
    console.log(`🚀 CodeHub Backend v3.0 en puerto ${PORT}`);
    console.log(`   MongoDB:    ${dbConnected ? '✅' : '⚠️  sin conexión'}`);
    console.log(`   Redis:      ${redis       ? '✅' : '⚠️  usando memoria'}`);
    console.log(`   WebSockets: ✅ /ws`);
    console.log(`   Groq:       ${process.env.GROQ_API_KEY        ? '✅' : '⚠️  sin configurar'}`);
    console.log('   OpenRouter: ' + (process.env.OPENROUTER_API_KEY ? '✅ (' + OR_FREE_MODELS.length + ' modelos gratis)' : '⚠️  sin configurar'));
    console.log(`   Gemini:     ${process.env.GEMINI_API_KEY      ? '✅' : '⚠️  sin configurar'}`);
    console.log(`   Mistral:    ${process.env.MISTRAL_API_KEY     ? '✅' : '⚠️  sin configurar'}`);
    console.log(`   Cohere:     ${process.env.COHERE_API_KEY      ? '✅' : '⚠️  sin configurar'}`);
    console.log(`   Storage:    ${supabase ? '✅ Supabase' : '❌ falta SUPABASE_URL/KEY'}`);
    console.log(`   Together:   ${process.env.TOGETHER_API_KEY ? '✅' : '⚠️  sin configurar'}`);
  });
})();
