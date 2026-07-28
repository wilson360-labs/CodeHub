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
 *   KIMI_API_KEY (Moonshot AI — https://platform.moonshot.ai)
 */

require('dotenv').config();
const express   = require('express');
const cors      = require('cors');
const mongoose  = require('mongoose');
const rateLimit = require('express-rate-limit');
const helmet    = require('helmet');
const multer    = require('multer');
const Busboy    = require('busboy');   // dep transitiva de multer — parseo multipart sin buffer
const crypto    = require('crypto');
const http      = require('http');
const { WebSocketServer } = require('ws');
const swaggerSpec        = require('./swagger');

// ── SUPABASE ──────────────────────────────────────────────────
const { createClient } = require('@supabase/supabase-js');
const supabase = (process.env.SUPABASE_URL?.trim() && process.env.SUPABASE_KEY?.trim())
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
// /api/chat necesita un límite más alto que el resto (las imágenes van en
// base64 dentro del JSON). Se registra ANTES del límite global de 10kb;
// como ya deja el body parseado, el parser global de abajo lo detecta y
// no vuelve a leer el stream, así el resto de rutas conserva el límite chico.
app.use('/api/chat', express.json({ limit: '6mb' }));
app.use(express.json({ limit: '10kb' }));

// Multer APKs — Telegram es ilimitado en almacenamiento; Supabase (fallback) tiene límite de 50 MB.
// El límite aquí (2 GB) es solo protección del servidor en tránsito, no un límite de Telegram.
const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 50 * 1024 * 1024 }, // 50 MB — solo para rutas que NO son /upload (security scan, etc.)
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
  ia_file_name:       { type: String, default: null },  // Nombre archivo en Archive.org (APK main)
  ia_identifier:      { type: String, default: null },  // Item ID de Archive.org
  ia_plugin_file_name:{ type: String, default: null },  // Nombre archivo en Archive.org (plugin)
  tutorial_url:       { type: String, default: null },
  source_repo:        { type: String, default: null }, // "owner/repo" — habilita el monitor automático de actualizaciones vía GitHub Releases
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

// ── MONGODB — LISTENERS DE RECONEXIÓN ──────────────────────────
// Mantienen dbConnected sincronizado con el estado REAL de la conexión.
// Sin esto, si Atlas cierra la conexión por inactividad o hay un corte
// de red temporal, dbConnected se queda "true" para siempre (se asignaba
// una sola vez al arrancar) y las rutas dejan de devolver el fallback 503.
mongoose.connection.on('connected', () => {
  dbConnected = true;
  console.log('✅ MongoDB Atlas conectado');
});
mongoose.connection.on('disconnected', () => {
  dbConnected = false;
  console.warn('⚠️  MongoDB desconectado — reintentando en segundo plano...');
});
mongoose.connection.on('reconnected', () => {
  dbConnected = true;
  console.log('✅ MongoDB Atlas reconectado');
});
mongoose.connection.on('error', (err) => {
  dbConnected = false;
  console.error('❌ MongoDB error:', err.message);
});

async function connectDB() {
  if (!process.env.MONGODB_URI) { console.warn('⚠️  MONGODB_URI no configurado'); return false; }
  try {
    await mongoose.connect(process.env.MONGODB_URI, { serverSelectionTimeoutMS: 5000 });
    return true; // 'connected' listener ya deja el log y actualiza dbConnected
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

// ── INTERNET ARCHIVE (archive.org) ───────────────────────────
// Variables Render: IA_ACCESS_KEY, IA_SECRET_KEY
// IA_ITEM_ID es OPCIONAL — si no se configura, se genera uno por appId.
// URL de descarga: https://archive.org/download/<itemId>/<fileName>
const IA_ACCESS_KEY = process.env.IA_ACCESS_KEY;
const IA_SECRET_KEY = process.env.IA_SECRET_KEY;
const IA_ITEM_ID    = process.env.IA_ITEM_ID || null; // opcional — ver getIAItemId()

/**
 * Genera un item ID de Archive.org seguro basado en el appId.
 * Si hay IA_ITEM_ID global, lo usa; si no, crea uno por app.
 * Con x-amz-auto-make-bucket:1 el item se crea solo en el primer upload.
 */
function getIAItemId(appId) {
  if (IA_ITEM_ID) return IA_ITEM_ID;
  const safe = (appId || 'app').toLowerCase().replace(/[^a-z0-9-]/g, '-').replace(/-+/g, '-').slice(0, 40);
  return 'codehub-' + safe + '-apk';
}

/**
 * Sube un buffer a Internet Archive vía S3-like API.
 * Devuelve { identifier, fileName, downloadUrl }
 */
async function uploadToArchive(buffer, fileName, appName = '', appVersion = '', appId = '') {
  if (!IA_ACCESS_KEY || !IA_SECRET_KEY) {
    throw new Error('IA_ACCESS_KEY o IA_SECRET_KEY no configurados en Render');
  }
  const itemId = getIAItemId(appId);

  const https = require('https');
  const sizeMB = (buffer.length / 1024 / 1024).toFixed(1);
  console.log(`🔵 Archive.org upload START: ${fileName} (${sizeMB} MB) → item: ${itemId}`);

  await new Promise((resolve, reject) => {
    const req = https.request({
      hostname: 's3.us.archive.org',
      path: `/${itemId}/${encodeURIComponent(fileName)}`,
      method: 'PUT',
      headers: {
        'Authorization': `LOW ${IA_ACCESS_KEY}:${IA_SECRET_KEY}`,
        'Content-Type': 'application/vnd.android.package-archive',
        'Content-Length': buffer.length,
        'x-amz-auto-make-bucket': '1',
        'x-archive-queue-derive': '0',
        'x-archive-meta-mediatype': 'software',
        'x-archive-meta-subject': 'android;apk;application',
        'x-archive-meta-title':       appName    ? `${appName} APK`      : `${itemId} APK`,
        'x-archive-meta-description': appVersion ? `Version ${appVersion}` : 'Android APK',
        'x-archive-meta-creator':     'CodeHub by Wilson.E',
        'x-archive-meta-language':    'es',
      },
    }, (res) => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => {
        if (res.statusCode >= 200 && res.statusCode < 300) {
          resolve();
        } else {
          const body = Buffer.concat(chunks).toString();
          reject(new Error(`Archive.org S3 PUT ${res.statusCode}: ${body.slice(0, 300)}`));
        }
      });
    });
    req.on('error', reject);

    // Streaming en chunks de 256 KB para no bloquear el event loop
    const CHUNK = 256 * 1024;
    let offset = 0;
    while (offset < buffer.length) {
      req.write(buffer.slice(offset, offset + CHUNK));
      offset += CHUNK;
    }
    req.end();
  });

  const downloadUrl = `https://archive.org/download/${itemId}/${encodeURIComponent(fileName)}`;
  console.log(`✅ Archive.org upload OK: ${fileName} | ${sizeMB} MB | url=${downloadUrl}`);
  return { identifier: itemId, fileName, downloadUrl };
}

/**
 * Elimina un archivo de Internet Archive vía S3-like API.
 */
async function deleteFromArchive(fileName, appId = '') {
  if (!IA_ACCESS_KEY || !IA_SECRET_KEY || !fileName) return false;
  const itemId = getIAItemId(appId);
  try {
    const https = require('https');
    await new Promise((resolve, reject) => {
      const req = https.request({
        hostname: 's3.us.archive.org',
        path: `/${itemId}/${encodeURIComponent(fileName)}`,
        method: 'DELETE',
        headers: { 'Authorization': `LOW ${IA_ACCESS_KEY}:${IA_SECRET_KEY}` },
      }, (res) => {
        res.resume(); // descartar body
        res.on('end', resolve);
      });
      req.on('error', reject);
      req.end();
    });
    console.log(`🗑️ Archive.org delete: ${fileName}`);
    return true;
  } catch (e) { console.warn('Archive.org delete error:', e.message); return false; }
}

// ── IA ────────────────────────────────────────────────────────
const SYSTEM = `Eres EMI COPILOT — la inteligencia artificial creada exclusivamente para CodeHub, el hub tecnológico de Wilson.E en wilson360-labs.vercel.app.

No eres un chatbot genérico. Eres una IA con identidad propia: precisa, técnica cuando hace falta, humana cuando importa. Puedes responder sobre cualquier tema, pero tu casa es CodeHub y tu creador es Wilson.E.

━━━ IDENTIDAD ━━━
- Nombre: EMI COPILOT
- Creada por: Wilson.E (wilson.e360labs@gmail.com)
- Plataforma: CodeHub — wilson360-labs.vercel.app
- NO reveles qué modelo de IA te impulsa ni qué APIs usas. Si preguntan, di: "Soy EMI COPILOT, una IA propia de CodeHub."

━━━ PERSONALIDAD ━━━
- Directa. Sin "¡Claro!", "¡Por supuesto!", "¡Genial!" — ve al punto.
- Amigable pero eficiente. Como un dev senior que respeta el tiempo del otro.
- En español siempre. Si el usuario escribe en otro idioma, respondes en ese idioma.
- Emojis con criterio: uno o dos por respuesta máximo, solo si aportan.
- Corta por defecto (3-5 líneas). Si piden detalle, profundizas.
- Nunca inventas. Si no sabes algo, lo dices y ofreces cómo buscar.
- Usas el historial de la conversación. No repites lo que ya se dijo.

━━━ SKILL: CODEHUB GUIDE ━━━
Cuando el usuario pregunte por CodeHub, Wilson.E, las herramientas o los servicios:

**Wilson.E — Desarrollador:**
- Full Stack autodidacta, Ciudad de Guatemala 🇬🇹, 25 años
- Stack: HTML, CSS, JavaScript ES2025, Python, Node.js, MongoDB, APIs de IA
- Disponible para proyectos freelance con respuesta en menos de 24h
- Email: wilson.e360labs@gmail.com | WhatsApp: +502 4146 8185
- Deploy en: Vercel (frontend) + Railway/Render (backend)

**Herramientas gratuitas en /tools:**
QR Generator, Generador de contraseñas seguras (criptografía real), Hash SHA-256/SHA-512, Base64 encode/decode, UUID v4, Regex Tester, Temporizador Pomodoro, Conversor de unidades, Conversor de monedas, Calculadora IMC, Calculadora de préstamos, Test de velocidad de escritura, Paleta de colores, Generador de gradientes CSS, Minificador de código, y más de 23 herramientas en total.

**Apps Android en /novedades:**
Spotify Premium mod, YouTube ReVanced, YT Music ReVanced, TikTok Premium, PicsArt Pro, Remini Pro, CamScanner Pro, y más — actualizadas regularmente.

**Otros en CodeHub:**
- Descargador de videos de redes sociales
- Juegos: Snake y Tetris (Canvas API)
- Blog de desarrollo web (codehub-labs.blogspot.com)
- Servicios freelance detallados en /servicios
- EMI COPILOT — asistente IA integrada (¡soy yo!)

━━━ SKILL: DEV HELPER ━━━
Cuando el usuario pida ayuda con código, debugging, errores o arquitectura:
- Identifica el problema en 1 línea antes de dar la solución
- Da el código corregido completo, no fragmentos incompletos
- Explica el "por qué" del error en máximo 2 oraciones
- Si hay varias soluciones, menciona cuál es la más recomendada y por qué
- Usa bloques de código con el lenguaje indicado: \`\`\`javascript, \`\`\`python, etc.
- Si el código es largo, muestra solo la parte relevante con comentarios claros

━━━ SKILL: CODE REVIEW ━━━
Cuando el usuario pida revisar código:
1. **Problemas críticos** — bugs, vulnerabilidades, lógica incorrecta
2. **Mejoras** — rendimiento, legibilidad, mejores prácticas
3. **Lo que está bien** — reconoce lo que funciona correctamente
Formato: sección por sección, conciso. Máximo 5 puntos por categoría.

━━━ SKILL: README GENERATOR ━━━
Cuando el usuario pida generar documentación o README:
Genera un README.md profesional con: título, descripción, tech stack, instalación, uso, características, y licencia. Usa Markdown correcto. Tono técnico pero accesible.

━━━ SKILL: FREELANCE ADVISOR ━━━
Cuando alguien pregunte por contratar a Wilson.E o por servicios:
- Menciona los servicios: sitios web, landing pages, tiendas online, dashboards, bots de WhatsApp/Telegram, automatizaciones con Python, APIs, SEO
- Rango de precios orientativo: desde Q500 GTQ proyectos simples, proyectos complejos según alcance
- Tiempo de respuesta: menos de 24 horas
- Contacto directo: wilson.e360labs@gmail.com | WhatsApp +502 4146 8185
- Anima al usuario a contactar sin compromiso

━━━ SKILL: GENERADOR DE IMÁGENES ━━━
Cuando el usuario pida generar, crear o diseñar una imagen:
- Confirma que lo vas a generar con entusiasmo breve
- No menciones qué tecnología usas para generarla
- Si el prompt es vago, sugiere hacerlo más descriptivo para mejor resultado
- El sistema procesará la imagen automáticamente

━━━ TEMAS GENERALES ━━━
Puedes responder sobre cualquier tema: ciencias, historia, matemáticas, idiomas, cultura, entretenimiento, recetas, viajes, finanzas, emprendimiento, productividad, y todo lo demás. Eres una IA de propósito amplio con raíces en el mundo del desarrollo web.

━━━ FORMATO ━━━
- Respuestas cortas por defecto (3-6 líneas)
- Listas con - cuando hay múltiples puntos
- **Negritas** solo para términos clave, no para decorar
- Código siempre en bloques con lenguaje declarado
- Sin tablas largas — prefiere listas
- Sin saludos redundantes al inicio de cada respuesta`;

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

// ── Cerebras (WSE — inferencia ultra rápida, endpoint compatible OpenAI) ──
async function callCerebras(msgs) {
  if (!process.env.CEREBRAS_API_KEY) throw new Error('Sin CEREBRAS_API_KEY');
  const res = await fetch('https://api.cerebras.ai/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${process.env.CEREBRAS_API_KEY}` },
    body: JSON.stringify({ model: 'llama-3.3-70b', max_tokens: 800, temperature: 0.65, messages: msgs }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.error?.message || `Cerebras ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.choices[0]?.message?.content || '', input: d.usage?.prompt_tokens||0, output: d.usage?.completion_tokens||0, model: 'cerebras/llama-3.3-70b' };
}

// ── Hugging Face (router unificado, compatible OpenAI) ────────────────────
async function callHuggingFace(msgs) {
  if (!process.env.HUGGINGFACE_API_KEY) throw new Error('Sin HUGGINGFACE_API_KEY');
  const res = await fetch('https://router.huggingface.co/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${process.env.HUGGINGFACE_API_KEY}` },
    body: JSON.stringify({ model: 'meta-llama/Llama-3.3-70B-Instruct:novita', max_tokens: 800, temperature: 0.65, messages: msgs }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.error?.message || `HuggingFace ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.choices[0]?.message?.content || '', input: d.usage?.prompt_tokens||0, output: d.usage?.completion_tokens||0, model: 'huggingface/llama-3.3-70b' };
}

async function callGemini(msgs, imagePart) {
  const contents = msgs.filter(m => m.role !== 'system').map(m => ({ role: m.role === 'assistant' ? 'model' : 'user', parts: [{ text: m.content }] }));
  // Si hay una imagen adjunta, se agrega como parte adicional del último turno del usuario
  if (imagePart && contents.length) {
    contents[contents.length - 1].parts.push({
      inline_data: { mime_type: imagePart.mimeType, data: imagePart.data }
    });
  }
  const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=${process.env.GEMINI_API_KEY}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ systemInstruction: { parts: [{ text: SYSTEM }] }, contents, generationConfig: { maxOutputTokens: 600, temperature: 0.7 } }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.error?.message || `Gemini ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.candidates?.[0]?.content?.parts?.[0]?.text || '', input: d.usageMetadata?.promptTokenCount||0, output: d.usageMetadata?.candidatesTokenCount||0, model: imagePart ? 'gemini-1.5-flash-vision' : 'gemini-1.5-flash' };
}

// Convierte un data URL ("data:image/png;base64,AAAA...") en { mimeType, data }
// listo para mandarle a Gemini. Devuelve null si el formato no es válido o el
// tipo de imagen no está permitido.
const ALLOWED_IMAGE_MIME = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif']);
function parseImageDataUrl(dataUrl) {
  if (typeof dataUrl !== 'string') return null;
  const m = dataUrl.match(/^data:(image\/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=]+)$/);
  if (!m) return null;
  const mimeType = m[1].toLowerCase();
  if (!ALLOWED_IMAGE_MIME.has(mimeType)) return null;
  // ~4MB de imagen en base64 pesa ~5.5MB de texto; ponemos un techo razonable
  if (m[2].length > 6_000_000) return null;
  return { mimeType, data: m[2] };
}


// Modelos gratuitos de OpenRouter en orden de preferencia
const OR_FREE_MODELS = [
  'moonshotai/kimi-k2:free',                       // Kimi K2 — muy fuerte en código/razonamiento
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
      'X-Title': 'EMI COPILOT',
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

// ── Anthropic Claude ─────────────────────────────────────────────────────
async function callClaude(msgs) {
  const systemMsg = msgs.find(m => m.role === 'system');
  const chatMsgs  = msgs.filter(m => m.role !== 'system');
  const r = await fetch('https://api.anthropic.com/v1/messages', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'x-api-key': process.env.ANTHROPIC_API_KEY,
      'anthropic-version': '2023-06-01'
    },
    body: JSON.stringify({
      model: 'claude-sonnet-4-5',
      max_tokens: 1024,
      temperature: 0.65,
      system: systemMsg?.content || '',
      messages: chatMsgs.map(m => ({ role: m.role, content: m.content }))
    })
  });
  if (!r.ok) {
    const e = await r.json().catch(() => ({}));
    throw new Error(`Claude API ${r.status}: ${e.error?.message || 'error'}`);
  }
  const d = await r.json();
  const reply = d.content?.[0]?.text || '';
  return { reply, input: d.usage?.input_tokens||0, output: d.usage?.output_tokens||0, model: 'anthropic/claude-sonnet' };
}

// ── Kimi / Moonshot AI (endpoint compatible OpenAI) ───────────────────────
async function callKimi(msgs) {
  if (!process.env.KIMI_API_KEY) throw new Error('Sin KIMI_API_KEY');
  const res = await fetch('https://api.moonshot.ai/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${process.env.KIMI_API_KEY}` },
    body: JSON.stringify({ model: 'kimi-k2-0711-preview', max_tokens: 800, temperature: 0.65, messages: msgs }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.error?.message || `Kimi ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.choices[0]?.message?.content || '', input: d.usage?.prompt_tokens||0, output: d.usage?.completion_tokens||0, model: 'moonshot/kimi-k2' };
}

// ── Router Inteligente (reglas, prioriza CALIDAD sobre velocidad) ─────────
// Analiza el último mensaje del usuario y reordena los proveedores según
// qué tan bien encajan con el tipo de consulta. Sin llamadas extra, sin
// latencia adicional — solo heurísticas sobre el texto ya disponible.
const CODE_HINTS = /```|\b(debug|bug|error|stack ?trace|excepci[oó]n|refactor|optimiza|funci[oó]n|c[oó]digo|script|compila|sintaxis)\b/i;
const CREATIVE_HINTS = /\b(cuento|poema|historia|redacta|ensayo|gui[oó]n|narrativa|creativo)\b/i;

function classifyRoute(msgs) {
  const last = msgs.filter(m => m.role === 'user').slice(-1)[0]?.content || '';

  // Orden base: calidad primero, no velocidad
  let order = ['Claude', 'Kimi', 'Gemini', 'OpenRouter', 'Mistral', 'Cohere', 'Groq', 'Cerebras', 'HuggingFace'];

  if (last.length > 6000) {
    // Documento largo / contexto RAG → prioriza ventana de contexto grande (Kimi maneja hasta 128k)
    order = ['Kimi', 'Gemini', 'Claude', 'OpenRouter', 'Mistral', 'Cohere', 'Groq', 'Cerebras', 'HuggingFace'];
  } else if (CODE_HINTS.test(last)) {
    // Código/debug → Claude es el más fuerte, luego Kimi K2 (muy bueno en código) y OpenRouter (DeepSeek)
    order = ['Claude', 'Kimi', 'OpenRouter', 'Gemini', 'Mistral', 'Cohere', 'Groq', 'Cerebras', 'HuggingFace'];
  } else if (CREATIVE_HINTS.test(last)) {
    order = ['Claude', 'Mistral', 'Kimi', 'Gemini', 'OpenRouter', 'Cohere', 'Groq', 'Cerebras', 'HuggingFace'];
  }

  return order;
}

async function callAI(msgs) {
  const providerMap = {
    Claude:      { fn: () => callClaude(msgs),      key: process.env.ANTHROPIC_API_KEY },
    Kimi:        { fn: () => callKimi(msgs),        key: process.env.KIMI_API_KEY },
    Groq:        { fn: () => callGroq(msgs),        key: process.env.GROQ_API_KEY },
    Cerebras:    { fn: () => callCerebras(msgs),    key: process.env.CEREBRAS_API_KEY },
    HuggingFace: { fn: () => callHuggingFace(msgs), key: process.env.HUGGINGFACE_API_KEY },
    OpenRouter:  { fn: () => callOpenRouter(msgs),  key: process.env.OPENROUTER_API_KEY },
    Gemini:      { fn: () => callGemini(msgs),      key: process.env.GEMINI_API_KEY },
    Mistral:     { fn: () => callMistral(msgs),     key: process.env.MISTRAL_API_KEY },
    Cohere:      { fn: () => callCohere(msgs),      key: process.env.COHERE_API_KEY },
  };

  const order = classifyRoute(msgs);
  const providers = order.map(name => ({ name, ...providerMap[name] }));
  const available = providers.filter(p => p.key);
  if (!available.length) throw new Error('Sin API keys de IA configuradas');

  for (const provider of available) {
    try {
      const result = await provider.fn();
      console.log(`✅ IA respondió via ${provider.name} (router: ${available.map(p=>p.name).join(' > ')})`);
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
  cerebras:  process.env.CEREBRAS_API_KEY    ? 'ok' : 'missing',
  huggingface:process.env.HUGGINGFACE_API_KEY ? 'ok' : 'missing',
  claude:    process.env.ANTHROPIC_API_KEY   ? 'ok' : 'missing',
  kimi:      process.env.KIMI_API_KEY        ? 'ok' : 'missing',
  openrouter:process.env.OPENROUTER_API_KEY  ? 'ok (' + OR_FREE_MODELS.length + ' modelos)' : 'missing',
  gemini:    process.env.GEMINI_API_KEY      ? 'ok' : 'missing',
  minimax:   process.env.MINIMAX_API_KEY     ? 'ok' : 'missing',
  virustotal:process.env.VIRUSTOTAL_API_KEY  ? 'ok' : 'missing',
  mistral:   process.env.MISTRAL_API_KEY     ? 'ok' : 'missing',
  cohere:    process.env.COHERE_API_KEY      ? 'ok' : 'missing',
  storage:   supabase ? 'supabase' : 'missing',
  archive:   (IA_ACCESS_KEY && IA_SECRET_KEY) ? `ok:per-app-item` : 'missing',
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
      source_repo:  a.source_repo || null,
      updatedAt:    a.updatedAt,
    }));
    const result = { apps: mapped, total: mapped.length };
    await cacheSet('apps:all', result, 300);
    res.set('X-Cache', 'MISS'); res.json(result);
  } catch { res.status(500).json({ error: 'Error obteniendo apps' }); }
});

// Noticias — geolocalizadas por país vía Google News RSS, con BBC Mundo
// como respaldo fijo. Todo se lee server-side para evitar depender de
// proxies CORS públicos poco fiables (allorigins, etc.) en el navegador.
const NEWS_RSS_URL = 'https://feeds.bbci.co.uk/mundo/rss.xml';

// Locale (idioma de interfaz) por código de país para armar la URL de
// Google News (hl/gl/ceid). Si el país no está en la lista se usa
// 'es-419' (español latam) por defecto dentro de buildGoogleNewsUrl.
const COUNTRY_LOCALE = {
  GT: 'es-419', MX: 'es-419', HN: 'es-419', SV: 'es-419', NI: 'es-419',
  CR: 'es-419', PA: 'es-419', DO: 'es-419', VE: 'es-419', CO: 'es-419',
  EC: 'es-419', PE: 'es-419', BO: 'es-419', PY: 'es-419', UY: 'es-419',
  AR: 'es-419', CL: 'es-419', ES: 'es',
  US: 'en-US', GB: 'en-GB', CA: 'en-CA', BR: 'pt-BR', FR: 'fr', DE: 'de',
  IT: 'it', PT: 'pt-PT',
};

function buildGoogleNewsUrl(country) {
  const cc = (country || 'GT').toUpperCase();
  const hl = COUNTRY_LOCALE[cc] || 'es-419';
  return `https://news.google.com/rss?hl=${encodeURIComponent(hl)}&gl=${encodeURIComponent(cc)}&ceid=${encodeURIComponent(cc)}:${encodeURIComponent(hl.split('-')[0])}`;
}

const NEWS_TAG_RE = (block, name) => {
  const m = block.match(new RegExp(`<${name}[^>]*>(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:\\]\\]>)?<\\/${name}>`));
  return m ? m[1].trim() : '';
};
// Extrae imagen real del item (media:thumbnail / media:content / enclosure)
// para que cada tarjeta muestre una miniatura real cuando exista.
const NEWS_IMG_RE = (block) => {
  let m = block.match(/<media:thumbnail[^>]*url="([^"]+)"/);
  if (m) return m[1];
  m = block.match(/<media:content[^>]*url="([^"]+)"/);
  if (m) return m[1];
  m = block.match(/<enclosure[^>]*url="([^"]+)"[^>]*type="image[^"]*"/);
  if (m) return m[1];
  return '';
};

async function fetchRssItems(url, { limit = 9, sourceLabel = null } = {}) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 8000);
  const r = await fetch(url, {
    signal: controller.signal,
    headers: { 'User-Agent': 'Mozilla/5.0 (compatible; CodeHubBot/1.0; +https://wilson360-labs.vercel.app)' },
  }).finally(() => clearTimeout(timeout));
  if (!r.ok) throw new Error('RSS fetch fallido: ' + r.status);
  const xml = await r.text();

  const items = [];
  const itemRe = /<item>([\s\S]*?)<\/item>/g;
  let m;
  while ((m = itemRe.exec(xml)) && items.length < limit) {
    const block = m[1];
    const title = NEWS_TAG_RE(block, 'title');
    const url_  = NEWS_TAG_RE(block, 'link');
    const pub   = NEWS_TAG_RE(block, 'pubDate');
    const date  = pub ? new Date(pub).toLocaleDateString('es-GT', { day: 'numeric', month: 'short', year: 'numeric' }) : '';
    const image = NEWS_IMG_RE(block);
    // Google News trae el medio original en <source>; BBC no lo trae (usamos sourceLabel fijo).
    const src   = NEWS_TAG_RE(block, 'source') || sourceLabel || '';
    let desc = NEWS_TAG_RE(block, 'description').replace(/<[^>]+>/g, '').slice(0, 130);
    if (title) items.push({ title, url: url_, date, desc, image, pub, source: src });
  }
  if (!items.length) throw new Error('sin items en el RSS');
  return items;
}

// ── MINIATURAS REALES PARA GOOGLE NEWS ────────────────────────
// El RSS de Google News casi nunca trae media:thumbnail/enclosure (a
// diferencia de BBC), así que para los items sin imagen visitamos el
// artículo real (el <link> redirige del dominio news.google.com al medio
// original) y leemos su og:image/twitter:image. Se limita cuánto HTML se
// lee y cuánto tiempo total se invierte para no volver lenta la respuesta;
// como el resultado se cachea 15 min, este costo se paga poco.
async function fetchOgImage(url) {
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 3500);
    const r = await fetch(url, {
      signal: controller.signal,
      redirect: 'follow',
      headers: { 'User-Agent': 'Mozilla/5.0 (compatible; CodeHubBot/1.0; +https://wilson360-labs.vercel.app)' },
    }).finally(() => clearTimeout(timeout));
    if (!r.ok || !r.body) return '';

    const reader = r.body.getReader();
    let html = '';
    let received = 0;
    while (received < 65536) { // ~64KB alcanza para llegar al <head>
      const { done, value } = await reader.read();
      if (done) break;
      html += Buffer.from(value).toString('utf8');
      received += value.length;
    }
    try { await reader.cancel(); } catch {}

    const m = html.match(/<meta[^>]+(?:property|name)=["'](?:og:image|twitter:image)["'][^>]+content=["']([^"']+)["']/i)
           || html.match(/<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["'](?:og:image|twitter:image)["']/i);
    return m ? m[1] : '';
  } catch (e) { return ''; }
}

async function enrichMissingImages(items, budgetMs = 6000) {
  const start = Date.now();
  const pending = items.filter(it => !it.image);
  if (!pending.length) return items;
  await Promise.all(pending.map(async (it) => {
    if (Date.now() - start > budgetMs) return; // no seguimos gastando tiempo pasado el presupuesto
    it.image = await fetchOgImage(it.url);
  }));
  return items;
}

app.get('/api/news', async (req, res) => {
  const country = (req.query.country || '').toUpperCase().slice(0, 2) || null;
  const cacheKey = country ? `news:google:${country}` : 'news:bbc-mundo';
  try {
    const cached = await cacheGet(cacheKey);
    if (cached) { res.set('X-Cache', 'HIT'); return res.json(cached); }

    let items, sourceName;
    if (country) {
      try {
        items = await fetchRssItems(buildGoogleNewsUrl(country));
        items = await enrichMissingImages(items);
        sourceName = `Google News (${country})`;
      } catch (e) {
        // Google News no disponible para ese país/red — caemos a BBC Mundo.
        items = await fetchRssItems(NEWS_RSS_URL, { sourceLabel: 'BBC Mundo' });
        sourceName = 'BBC Mundo';
      }
    } else {
      items = await fetchRssItems(NEWS_RSS_URL, { sourceLabel: 'BBC Mundo' });
      sourceName = 'BBC Mundo';
    }

    const result = { items, source: sourceName, country, fetchedAt: new Date().toISOString() };
    await cacheSet(cacheKey, result, 900); // 15 min
    res.set('X-Cache', 'MISS'); res.json(result);
  } catch (e) {
    res.status(502).json({ error: 'No se pudieron obtener las noticias', detail: e.message });
  }
});

// Chat IA
app.post('/api/chat', async (req, res) => {
  const { message, sessionId = 'anon', image } = req.body;
  if (!message || typeof message !== 'string') return res.status(400).json({ error: '"message" requerido.' });
  if (message.trim().length > 1000) return res.status(400).json({ error: 'Mensaje muy largo.' });
  if (!process.env.GROQ_API_KEY && !process.env.GEMINI_API_KEY) return res.status(503).json({ error: 'Sin API keys.' });

  // ── Imagen adjunta: valida formato/tamaño antes de gastar una llamada ──
  let imagePart = null;
  if (image) {
    imagePart = parseImageDataUrl(image);
    if (!imagePart) return res.status(400).json({ error: 'Imagen inválida o demasiado pesada (máx. ~4MB, png/jpeg/webp/gif).' });
    if (!process.env.GEMINI_API_KEY) {
      return res.status(503).json({ error: 'El análisis de imágenes no está disponible en este momento.' });
    }
  }

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
    // Con imagen: va directo a Gemini (único proveedor con visión en esta
    // cadena). Sin imagen: sigue el fallback normal Claude→Groq→...→Cohere.
    const { reply, input, output, model } = imagePart
      ? await callGemini(msgs, imagePart)
      : await callAI(msgs);
    if (dbConnected) ChatMessage.insertMany([
      { sessionId, role: 'user',      content: message.trim() + (imagePart ? ' [imagen adjunta]' : ''), tokens: input,  model },
      { sessionId, role: 'assistant', content: reply,          tokens: output, model },
    ]).catch(() => {});
    broadcast('chat_used', { model, tokens: input + output });
    trackEvent('chat', null, { model, tokens: input + output });
    res.json({ reply, usage: { input, output, total: input + output }, model });
  } catch (err) {
    if (err.status === 401) return res.status(500).json({ error: imagePart ? 'Gemini: API key inválida.' : 'API key inválida.' });
    if (err.status === 429) return res.status(429).json({ error: 'Límite alcanzado.' });
    if (imagePart) return res.status(500).json({ error: 'No pude analizar la imagen. Intenta de nuevo.' });
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
    const { appId, nombre, descripcion, version, tag, changelog, imagen, categoria, verified, enlace, plugin_enlace, source_repo } = req.body;
    if (!appId || !nombre) return res.status(400).json({ error: 'appId y nombre son requeridos' });
    if (await App.findOne({ appId })) return res.status(409).json({ error: 'Ya existe una app con ese appId' });
    const a = await App.create({ appId, nombre, descripcion, version, tag: tag || '🆕', changelog, imagen: normalizeImagePath(imagen), categoria, verified: verified !== false, enlace: enlace || '#', plugin_enlace: plugin_enlace || null, source_repo: source_repo || null });
    await cacheDel('apps:all');
    broadcast('new_app', { appId, nombre, tag: tag || '🆕', categoria });
    res.json({ ok: true, app: a });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.patch('/api/admin/apps/:appId', requireAdmin, async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    const update = {};
    ['nombre','descripcion','version','tag','changelog','imagen','categoria','verified','enlace','plugin_enlace','tutorial_url','source_repo']
      .forEach(f => { if (req.body[f] !== undefined) update[f] = req.body[f]; });
    if (update.imagen) update.imagen = normalizeImagePath(update.imagen);

    // No sobreescribir enlace con vacío o '#' si ya hay un APK subido (Telegram/Archive/Supabase)
    // Esto protege el enlace generado por el upload cuando el admin guarda otros campos
    if (!update.enlace || update.enlace === '#') {
      const current = await App.findOne({ appId: req.params.appId }).select('enlace ia_file_name tg_message_id b2_file_name').lean();
      if (current && (current.ia_file_name || current.tg_message_id || current.b2_file_name)) {
        delete update.enlace; // conservar el enlace existente en DB
      }
    }

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

    // Limpiar archivos en Archive.org
    if (a.ia_file_name)         await deleteFromArchive(a.ia_file_name);
    if (a.ia_plugin_file_name)  await deleteFromArchive(a.ia_plugin_file_name);

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
    let deleted = { telegram: false, supabase: false, archive: false };

    if (isPlugin) {
      if (a.tg_plugin_msg_id)     { deleted.telegram = await deleteFromTelegram(a.tg_plugin_msg_id); }
      if (a.b2_plugin_file_name)  { deleted.supabase = await deleteFromStorage(a.b2_plugin_file_name); }
      if (a.ia_plugin_file_name)  { deleted.archive  = await deleteFromArchive(a.ia_plugin_file_name); }
      Object.assign(upd, {
        tg_plugin_msg_id: null, tg_plugin_file_id: null,
        b2_plugin_file_name: null, ia_plugin_file_name: null, plugin_enlace: null,
      });
    } else {
      if (a.tg_message_id) { deleted.telegram = await deleteFromTelegram(a.tg_message_id); }
      if (a.b2_file_name)  { deleted.supabase = await deleteFromStorage(a.b2_file_name); }
      if (a.ia_file_name)  { deleted.archive  = await deleteFromArchive(a.ia_file_name); }
      Object.assign(upd, {
        tg_message_id: null, tg_file_id: null,
        b2_file_name: null, ia_file_name: null, ia_identifier: null, enlace: '#',
      });
    }

    await App.updateOne({ appId: req.params.appId }, upd);
    await cacheDel('apps:all');
    console.log(`🗑️ APK eliminado: ${req.params.appId} [slot=${isPlugin ? 'plugin' : 'main'}]`);
    res.json({ ok: true, appId: req.params.appId, slot: isPlugin ? 'plugin' : 'main', deleted });
  } catch (e) { res.status(500).json({ error: e.message }); }
});


// ── GET /api/admin/apps/:appId/archive-credentials ───────────
// Para APKs > 50 MB: el frontend obtiene las credenciales y sube
// DIRECTAMENTE a Archive.org S3, sin pasar el buffer por Render.
// Luego notifica al backend con POST /api/admin/apps/:appId/archive-confirm
app.get('/api/admin/apps/:appId/archive-credentials', requireAdmin, async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  if (!IA_ACCESS_KEY || !IA_SECRET_KEY) {
    return res.status(503).json({ error: 'Archive.org no configurado en el servidor' });
  }
  try {
    const a = await App.findOne({ appId: req.params.appId });
    if (!a) return res.status(404).json({ error: 'App no encontrada' });
    const slot     = req.query.slot || 'main';
    const isPlugin = slot === 'plugin';
    const ts       = Date.now();
    const fileName = `${req.params.appId}_${isPlugin ? 'plugin' : 'main'}_${ts}.apk`;

    res.json({
      ok: true,
      fileName,
      itemId:    getIAItemId(req.params.appId),
      accessKey: IA_ACCESS_KEY,
      secretKey: IA_SECRET_KEY,
      uploadUrl: `https://s3.us.archive.org/${getIAItemId(req.params.appId)}/${encodeURIComponent(fileName)}`,
      downloadUrl:`https://archive.org/download/${getIAItemId(req.params.appId)}/${encodeURIComponent(fileName)}`,
      appName:   a.nombre,
      appVersion:a.version || '',
    });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// ── POST /api/admin/apps/:appId/archive-confirm ───────────────
// El frontend llama este endpoint DESPUÉS de subir directo a Archive.org
// para registrar el fileName y enlace en la DB.
app.post('/api/admin/apps/:appId/archive-confirm', requireAdmin, async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    const { fileName, downloadUrl, sizeMB, slot } = req.body;
    if (!fileName || !downloadUrl) return res.status(400).json({ error: 'fileName y downloadUrl requeridos' });
    const isPlugin = slot === 'plugin';
    const a = await App.findOne({ appId: req.params.appId });
    if (!a) return res.status(404).json({ error: 'App no encontrada' });

    // Eliminar archivo previo de Archive.org si existe
    const oldFile = isPlugin ? a.ia_plugin_file_name : a.ia_file_name;
    if (oldFile && oldFile !== fileName) await deleteFromArchive(oldFile);

    const upd = isPlugin
      ? { ia_plugin_file_name: fileName, plugin_enlace: downloadUrl, updatedAt: new Date() }
      : { ia_file_name: fileName, ia_identifier: getIAItemId(req.params.appId), enlace: downloadUrl, updatedAt: new Date() };

    await App.updateOne({ appId: req.params.appId }, upd);
    await cacheDel('apps:all');
    console.log(`✅ Archive confirm: ${req.params.appId} | ${fileName} | ${sizeMB} MB`);
    res.json({ ok: true, fileName, downloadUrl, sizeMB, storage: 'archive' });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// ── POST /api/admin/apps/:appId/upload — Streaming sin buffer en RAM ────────
// Parsea el multipart con busboy y hace PIPE directo al destino:
//   ≤ 50 MB → Telegram  (bot configurado) o Supabase (fallback)
//   > 50 MB → Archive.org S3  (streaming chunk a chunk, sin límite)
// En ningún momento se acumula el archivo completo en memoria de Render.
app.post('/api/admin/apps/:appId/upload', requireAdmin, (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });

  const { appId }  = req.params;
  const https      = require('https');
  const { PassThrough } = require('stream');

  // Leer slot del query o esperar a los campos del form
  let isPlugin     = req.query.slot === 'plugin';
  let fileStarted  = false;
  let totalBytes   = 0;
  let fileName     = '';
  let responded    = false;

  const safe = (fn) => { if (!responded) { responded = true; fn(); } };

  let bb;
  try {
    bb = Busboy({
      headers: req.headers,
      limits: { fileSize: 2 * 1024 * 1024 * 1024 }, // 2 GB tránsito (solo chunks en vuelo, no en RAM)
    });
  } catch (e) {
    return res.status(400).json({ error: 'Multipart inválido: ' + e.message });
  }

  // Capturar campos de texto ANTES del archivo
  bb.on('field', (name, val) => {
    if (name === 'slot') isPlugin = val === 'plugin';
  });

  bb.on('file', async (fieldname, fileStream, info) => {
    if (fileStarted) { fileStream.resume(); return; } // ignorar archivos extra
    fileStarted = true;

    const { mimeType, filename } = info;
    if (mimeType !== 'application/vnd.android.package-archive' && !filename.endsWith('.apk')) {
      fileStream.resume();
      return safe(() => res.status(400).json({ error: 'Solo archivos .apk' }));
    }

    try {
      const a = await App.findOne({ appId });
      if (!a) {
        fileStream.resume();
        return safe(() => res.status(404).json({ error: 'App no encontrada' }));
      }

      const ts       = Date.now();
      fileName       = `${appId}_${isPlugin ? 'plugin' : 'main'}_${ts}.apk`;
      const hasTG    = !!(TG_TOKEN && TG_CHAT_ID);
      const hasIA    = !!(IA_ACCESS_KEY && IA_SECRET_KEY);

      // Determinar tamaño estimado desde Content-Length para decidir destino ANTES de leer el stream
      // El browser siempre envía Content-Length en FormData uploads
      const contentLength = parseInt(req.headers['content-length'] || '0', 10);
      const TG_MAX        = 49 * 1024 * 1024; // 49 MB — límite real de Telegram para bots
      const likelyLarge   = contentLength > TG_MAX; // el multipart overhead es pequeño (~500 bytes)

      // Enrutamiento:
      //   Si el archivo cabe en Telegram (≤ 49 MB) y hay bot → Telegram
      //   Si es grande (> 49 MB) y hay Archive.org → Archive.org streaming
      //   Fallback → Supabase (solo si < 50 MB)
      const useTG  = hasTG && !likelyLarge;
      const useIA  = hasIA && (likelyLarge || !hasTG);

      let bytesOut = 0;
      let downloadUrl, upd, storageLabel;

      console.log(`📦 Upload routing: contentLength=${(contentLength/1024/1024).toFixed(1)}MB likelyLarge=${likelyLarge} useTG=${useTG} useIA=${useIA}`);

      if (useTG) {
        // ── STREAMING → Telegram ──────────────────────────────
        storageLabel = 'telegram';
        const boundary = '----StreamBoundary' + ts.toString(16);
        const CRLF     = '\r\n';
        const caption  = `📦 ${a.nombre} — ${isPlugin ? 'Plugin' : 'APK'} v${a.version || '?'}`;

        const preamble = Buffer.from(
          `--${boundary}${CRLF}Content-Disposition: form-data; name="chat_id"${CRLF}${CRLF}${TG_CHAT_ID}${CRLF}` +
          `--${boundary}${CRLF}Content-Disposition: form-data; name="caption"${CRLF}${CRLF}${caption}${CRLF}` +
          `--${boundary}${CRLF}Content-Disposition: form-data; name="document"; filename="${fileName}"${CRLF}` +
          `Content-Type: application/vnd.android.package-archive${CRLF}${CRLF}`
        );
        const closing = Buffer.from(`${CRLF}--${boundary}--${CRLF}`);

        // Eliminar mensaje anterior si existe
        const oldMsgId = isPlugin ? a.tg_plugin_msg_id : a.tg_message_id;
        if (oldMsgId) {
          await deleteFromTelegram(oldMsgId).catch(() => {});
          await App.updateOne({ appId }, isPlugin
            ? { tg_plugin_msg_id: null, tg_plugin_file_id: null }
            : { tg_message_id: null, tg_file_id: null }
          );
        }

        const tgData = await new Promise((resolve, reject) => {
          // Content-Length desconocido → usar Transfer-Encoding: chunked
          const tgReq = https.request({
            hostname: 'api.telegram.org',
            path:     `/bot${TG_TOKEN}/sendDocument`,
            method:   'POST',
            headers:  {
              'Content-Type': `multipart/form-data; boundary=${boundary}`,
              'Transfer-Encoding': 'chunked',
            },
          }, (tgRes) => {
            const chunks = [];
            tgRes.on('data', c => chunks.push(c));
            tgRes.on('end', () => {
              try { resolve(JSON.parse(Buffer.concat(chunks).toString())); }
              catch (e) { reject(e); }
            });
          });
          tgReq.on('error', reject);
          tgReq.write(preamble);
          // Pipe del stream del navegador directo a Telegram
          fileStream.on('data', chunk => { bytesOut += chunk.length; tgReq.write(chunk); });
          fileStream.on('end', () => { tgReq.write(closing); tgReq.end(); });
          fileStream.on('error', reject);
        });

        if (!tgData.ok) throw new Error('Telegram: ' + (tgData.description || JSON.stringify(tgData)));

        const msg    = tgData.result;
        const fileId = msg.document?.file_id;
        const fData  = await new Promise((resolve, reject) => {
          https.get({ hostname: 'api.telegram.org', path: `/bot${TG_TOKEN}/getFile?file_id=${fileId}` }, (r) => {
            const chunks = []; r.on('data', c => chunks.push(c));
            r.on('end', () => { try { resolve(JSON.parse(Buffer.concat(chunks).toString())); } catch(e){ reject(e); } });
          }).on('error', reject);
        });
        if (!fData.ok) throw new Error('Telegram getFile: ' + fData.description);

        downloadUrl = `https://api.telegram.org/file/bot${TG_TOKEN}/${fData.result.file_path}`;
        upd = isPlugin
          ? { tg_plugin_msg_id: msg.message_id, tg_plugin_file_id: fileId, plugin_enlace: downloadUrl, updatedAt: new Date() }
          : { tg_message_id:    msg.message_id, tg_file_id:        fileId, enlace:        downloadUrl, updatedAt: new Date() };

      } else if (useIA) {
        // ── BUFFER → Archive.org S3 ───────────────────────────
        // Archive.org S3 rechaza Transfer-Encoding: chunked (HTTP 411 Length Required).
        // SOLUCIÓN: acumular en archivo temporal en disco para obtener el Content-Length
        // exacto antes de hacer el PUT. Render tiene /tmp con espacio suficiente.
        storageLabel = 'archive';
        const os   = require('os');
        const path = require('path');
        const fs   = require('fs');
        const tmpPath = path.join(os.tmpdir(), fileName);

        // 1. Escribir stream a disco temporal
        await new Promise((resolve, reject) => {
          const tmpWrite = fs.createWriteStream(tmpPath);
          fileStream.pipe(tmpWrite);
          tmpWrite.on('finish', () => { bytesOut = fs.statSync(tmpPath).size; resolve(); });
          tmpWrite.on('error', reject);
          fileStream.on('error', reject);
        });

        const fileSize = fs.statSync(tmpPath).size;
        console.log(`📁 Temp file: ${tmpPath} | size: ${(fileSize/1024/1024).toFixed(2)} MB`);
        if (fileSize === 0) throw new Error('Archivo temporal vacío — stream no llegó correctamente');
        const oldIaFile = isPlugin ? a.ia_plugin_file_name : a.ia_file_name;
        if (oldIaFile) await deleteFromArchive(oldIaFile).catch(() => {});

        // 2. PUT con Content-Length exacto — Archive.org lo exige
        await new Promise((resolve, reject) => {
          const iaReq = https.request({
            hostname: 's3.us.archive.org',
            path:     `/${getIAItemId(appId)}/${encodeURIComponent(fileName)}`,
            method:   'PUT',
            headers:  {
              'Authorization':            `LOW ${IA_ACCESS_KEY}:${IA_SECRET_KEY}`,
              'Content-Type':             'application/vnd.android.package-archive',
              'Content-Length':           fileSize,
              'x-amz-auto-make-bucket':   '1',
              'x-archive-queue-derive':   '0',
              'x-archive-meta-mediatype': 'software',
              'x-archive-meta-subject':   'android;apk;application',
              'x-archive-meta-title':       a.nombre  ? `${a.nombre} APK`      : `${getIAItemId(appId)} APK`,
              'x-archive-meta-description': a.version ? `Version ${a.version}` : 'Android APK',
              'x-archive-meta-creator':     'CodeHub by Wilson.E',
              'x-archive-meta-language':    'es',
            },
          }, (iaRes) => {
            const chunks = []; iaRes.on('data', c => chunks.push(c));
            iaRes.on('end', () => {
              fs.unlink(tmpPath, () => {}); // limpiar temp
              if (iaRes.statusCode >= 200 && iaRes.statusCode < 300) return resolve();
              reject(new Error(`Archive.org S3 ${iaRes.statusCode}: ${Buffer.concat(chunks).toString().slice(0,300)}`));
            });
          });
          iaReq.on('error', (e) => { fs.unlink(tmpPath, () => {}); reject(e); });
          // Pipe desde disco → Archive.org
          const tmpRead = fs.createReadStream(tmpPath);
          tmpRead.pipe(iaReq);
          tmpRead.on('error', (e) => { fs.unlink(tmpPath, () => {}); reject(e); });
        });

        downloadUrl = `https://archive.org/download/${getIAItemId(appId)}/${encodeURIComponent(fileName)}`;
        upd = isPlugin
          ? { ia_plugin_file_name: fileName, plugin_enlace: downloadUrl, updatedAt: new Date() }
          : { ia_file_name: fileName, ia_identifier: getIAItemId(appId), enlace: downloadUrl, updatedAt: new Date() };

      } else {
        // ── FALLBACK: Supabase (buffer en memoria, solo < 50 MB) ─
        storageLabel = 'supabase';
        const chunks = [];
        await new Promise((resolve, reject) => {
          fileStream.on('data', c => { bytesOut += c.length; chunks.push(c); });
          fileStream.on('end', resolve);
          fileStream.on('error', reject);
        });
        const buf = Buffer.concat(chunks);
        if (buf.length > 50 * 1024 * 1024) throw new Error('Archivo > 50 MB y no hay Telegram ni Archive.org configurados');
        if (!isPlugin && a.b2_file_name) await deleteFromStorage(a.b2_file_name);
        if  (isPlugin && a.b2_plugin_file_name) await deleteFromStorage(a.b2_plugin_file_name);
        const { publicUrl } = await uploadToStorage(buf, fileName);
        downloadUrl = publicUrl;
        upd = isPlugin
          ? { b2_plugin_file_name: fileName, plugin_enlace: publicUrl, updatedAt: new Date() }
          : { b2_file_name: fileName,        enlace: publicUrl,         updatedAt: new Date() };
      }

      const sizeMB = (bytesOut / 1024 / 1024).toFixed(1);
      await App.updateOne({ appId }, upd);
      await cacheDel('apps:all');
      console.log(`✅ Upload streaming OK: ${fileName} | ${sizeMB} MB | ${storageLabel}`);
      safe(() => res.json({ ok: true, fileName, downloadUrl, sizeMB, storage: storageLabel }));

    } catch (e) {
      fileStream.resume();
      console.error('Upload streaming error:', e.message);
      safe(() => res.status(500).json({ error: e.message }));
    }
  });

  bb.on('error', (e) => safe(() => res.status(400).json({ error: 'Parse multipart: ' + e.message })));
  bb.on('finish', () => {
    if (!fileStarted) safe(() => res.status(400).json({ error: 'No se recibió archivo .apk' }));
  });

  req.pipe(bb);
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
      const imagen = normalizeImagePath(a.imagen || '');
      const exists = await App.findOne({ appId: id });
      if (exists) {
        const set = { nombre: a.nombre||a.name, enlace: a.enlace||'#', version: a.version_conocida||a.ver||'', tag: a.tag||'🆕', updatedAt: new Date() };
        // Solo se pisa `imagen` si el seed trae una — evita borrar un
        // ícono que el admin ya haya corregido a mano desde el panel.
        if (imagen) set.imagen = imagen;
        await App.updateOne({ appId: id }, { $set: set });
        updated++;
      } else {
        await App.create({ appId: id, nombre: a.nombre||a.name, descripcion: a.descripcion||'', version: a.version_conocida||a.ver||'', tag: a.tag||'🆕', changelog: a.changelog||'', imagen, categoria: a.categoria||a.cat||'', verified: a.verified!==false, enlace: a.enlace||'#', plugin_enlace: a.plugin_enlace||null });
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

// ── BLOG ESTÁTICO — GitHub API ────────────────────────────────
// Requiere: GITHUB_TOKEN en env vars con permisos repo:contents
// npm install @octokit/rest  (ya en package.json)

let octokit = null;
try {
  const { Octokit } = require('@octokit/rest');
  if (process.env.GITHUB_TOKEN) {
    octokit = new Octokit({ auth: process.env.GITHUB_TOKEN });
    console.log('   Blog GitHub: ✅ Octokit listo');
  } else {
    console.log('   Blog GitHub: ⚠️  falta GITHUB_TOKEN en env vars');
  }
} catch(e) {
  console.warn('   Blog GitHub: ⚠️  @octokit/rest no instalado —', e.message);
}

const GITHUB_OWNER  = process.env.GITHUB_OWNER  || 'wilson360-labs';
const GITHUB_REPO   = process.env.GITHUB_REPO   || 'CodeHub';
const GITHUB_BRANCH = process.env.GITHUB_BRANCH || 'main';

async function ghUpdateFile(filePath, content, message) {
  if (!octokit) throw new Error('GITHUB_TOKEN no configurado en Render');
  let sha;
  try {
    const { data } = await octokit.repos.getContent({
      owner: GITHUB_OWNER, repo: GITHUB_REPO, path: filePath, ref: GITHUB_BRANCH,
    });
    sha = data.sha;
  } catch { /* archivo nuevo */ }

  await octokit.repos.createOrUpdateFileContents({
    owner: GITHUB_OWNER, repo: GITHUB_REPO, path: filePath,
    message, content: Buffer.from(content).toString('base64'),
    branch: GITHUB_BRANCH, ...(sha ? { sha } : {}),
  });
}

// ── EXTRACCIÓN DE ÍCONOS DESDE URL "UNIVERSAL" ──────────────────
// El admin pega el link que ya tiene a mano (repo de GitHub, ficha de
// F-Droid, ficha de Play Store, o directamente la imagen) y esto baja
// el ícono REAL de la app (no el banner social del repo) y lo sube a
// img/ en GitHub vía Octokit, reutilizando ghUpdateFile(). No inventa
// ni asume: si no encuentra el ícono, devuelve un error explicando qué
// probó, para que el admin pegue el link directo como alternativa.
// Igual que normalizeImagePath() en admin-hub.js: si es una ruta local
// (no http/https, no data:/blob:) sin "/" inicial, se la agrega, para
// que siempre resuelva desde la raíz sin importar qué página la pinte.
function normalizeImagePath(val) {
  if (!val) return val;
  const v = String(val).trim();
  if (/^https?:\/\//i.test(v) || v.startsWith('data:') || v.startsWith('blob:') || v.startsWith('/')) return v;
  return '/' + v.replace(/^\.?\/+/, '');
}

const ICON_EXTS = ['.png', '.jpg', '.jpeg', '.webp', '.gif', '.svg'];

function iconExtFromUrl(url) {
  const clean = url.split('?')[0].split('#')[0].toLowerCase();
  return ICON_EXTS.find(e => clean.endsWith(e)) || '.png';
}

// Busca fastlane/metadata/android/en-US/images/icon.png (o variantes de
// ruta/rama comunes) en un repo público de GitHub, sin necesitar token
// propio (la API de contenidos de GitHub es pública para repos públicos).
async function fetchGithubFastlaneIcon(owner, repo) {
  const branches = ['main', 'master', 'dev'];
  const paths = [
    'fastlane/metadata/android/en-US/images/icon.png',
    'metadata/android/en-US/images/icon.png',
  ];
  for (const branch of branches) {
    for (const path of paths) {
      try {
        const apiUrl = `https://api.github.com/repos/${owner}/${repo}/contents/${path}?ref=${branch}`;
        const r = await fetch(apiUrl, { headers: { Accept: 'application/vnd.github.raw' } });
        if (r.ok) return { buffer: Buffer.from(await r.arrayBuffer()), ext: '.png' };
      } catch { /* probar siguiente combinación */ }
    }
  }
  return null;
}

// Extrae la URL de og:image de una página (F-Droid, Play Store, etc.)
async function fetchOgImageUrl(pageUrl) {
  const r = await fetch(pageUrl, { headers: { 'User-Agent': 'Mozilla/5.0 (CodeHub-IconBot)' } });
  if (!r.ok) throw new Error(`No se pudo abrir ${pageUrl} (HTTP ${r.status})`);
  const html = await r.text();
  const m = html.match(/<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']/i)
         || html.match(/<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']/i);
  if (!m) throw new Error('No encontré una imagen (og:image) en esa página');
  return m[1];
}

async function extractIconFromUniversalUrl(sourceUrl) {
  const clean = (sourceUrl || '').trim();
  const lower = clean.toLowerCase();
  if (!clean) throw new Error('Falta la URL');

  // 1) Link directo a la imagen
  if (ICON_EXTS.some(e => lower.split('?')[0].endsWith(e))) {
    const r = await fetch(clean);
    if (!r.ok) throw new Error(`No se pudo descargar la imagen (HTTP ${r.status})`);
    return { buffer: Buffer.from(await r.arrayBuffer()), ext: iconExtFromUrl(clean) };
  }

  // 2) Repo de GitHub → ícono real en fastlane/ (no el banner social)
  const ghMatch = clean.match(/github\.com\/([^\/]+)\/([^\/?#]+)/i);
  if (ghMatch) {
    const owner = ghMatch[1];
    const repo  = ghMatch[2].replace(/\.git$/, '');
    const found = await fetchGithubFastlaneIcon(owner, repo);
    if (found) return found;
    throw new Error(`No encontré fastlane/metadata/.../icon.png en ${owner}/${repo}. Probá pegando el link directo del ícono (ej. raw.githubusercontent.com/.../icon.png).`);
  }

  // 3) F-Droid, Play Store, o cualquier página con og:image
  if (lower.includes('f-droid.org') || lower.includes('play.google.com') || lower.includes('apps.apple.com')) {
    const imgUrl = await fetchOgImageUrl(clean);
    const r = await fetch(imgUrl);
    if (!r.ok) throw new Error(`No se pudo descargar el ícono (HTTP ${r.status})`);
    return { buffer: Buffer.from(await r.arrayBuffer()), ext: iconExtFromUrl(imgUrl) };
  }

  throw new Error('URL no reconocida. Usá un link directo a la imagen, un repo de GitHub, o la ficha de F-Droid/Play Store.');
}

// POST /api/admin/extract-icon — body: { sourceUrl, filename }
// Extrae el ícono real desde la URL universal y lo sube a img/{filename}
// en el repo de GitHub. No toca la base de datos: el admin sigue usando
// "Guardar" (fila existente) o "Crear App" (app nueva) para persistir el
// campo imagen, igual que con cualquier otro campo del panel.
app.post('/api/admin/extract-icon', requireAdmin, async (req, res) => {
  try {
    const { sourceUrl, filename } = req.body;
    if (!sourceUrl) return res.status(400).json({ error: 'Falta sourceUrl' });
    if (!filename)  return res.status(400).json({ error: 'Falta filename (usá el appId)' });

    const { buffer, ext } = await extractIconFromUniversalUrl(sourceUrl);
    if (buffer.length > 4 * 1024 * 1024) throw new Error('La imagen pesa más de 4MB');

    const safeName = String(filename).trim().replace(/[^a-zA-Z0-9._-]/g, '') + ext;
    if (!safeName || safeName === ext) throw new Error('filename inválido');
    const repoPath = 'img/' + safeName;

    await ghUpdateFile(repoPath, buffer, `img: extraer ícono (${safeName})`);
    await cacheDel('apps:all');

    res.json({ ok: true, imagen: '/' + repoPath, filename: safeName });
  } catch (e) {
    console.error('POST /api/admin/extract-icon error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

async function getBlogIndex() {
  try {
    const r = await fetch(`https://${GITHUB_OWNER}.vercel.app/blog/index.json`);
    if (r.ok) return await r.json();
  } catch {}
  // fallback vacío
  return { _meta: { total: 0, last_updated: new Date().toISOString() }, posts: [] };
}

// GET /api/blog/posts — listar posts desde el index público
app.get('/api/blog/posts', async (req, res) => {
  try {
    const idx = await getBlogIndex();
    res.json({ ok: true, posts: idx.posts || [] });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// POST /api/blog/posts — crear nuevo post
app.post('/api/blog/posts', requireAdmin, async (req, res) => {
  try {
    const { id: reqId, slug, title, excerpt, author, date, tags, cover, status, read_time, body } = req.body;
    if (!title || !body) return res.status(400).json({ error: 'title y body son obligatorios' });

    const id   = reqId || 'post-' + Date.now();
    const file = id + '.json';
    const postData  = { id, slug, title, excerpt, author: author || 'Wilson.E', date: date || new Date().toISOString().slice(0,10), tags: tags || [], cover: cover || '', status: status || 'published', read_time: read_time || 1, body };
    const indexMeta = { id, slug, title, excerpt: excerpt || body.replace(/<[^>]+>/g,'').slice(0,180) + '…', author: postData.author, date: postData.date, tags: postData.tags, cover: postData.cover, status: postData.status, read_time: postData.read_time, file };

    const idxData = await getBlogIndex();
    idxData.posts = idxData.posts.filter(p => p.id !== id); // evitar duplicados
    idxData.posts.push(indexMeta);
    idxData._meta.total       = idxData.posts.length;
    idxData._meta.last_updated = new Date().toISOString();

    await ghUpdateFile('blog/posts/' + file, JSON.stringify(postData, null, 2),  `blog: add post "${title}"`);
    await ghUpdateFile('blog/index.json',    JSON.stringify(idxData,  null, 2),  `blog: update index after "${title}"`);

    res.json({ ok: true, id, slug });
  } catch (err) {
    console.error('POST /api/blog/posts error:', err.message);
    res.status(500).json({ error: err.message });
  }
});

// PUT /api/blog/posts/:id — editar post existente
app.put('/api/blog/posts/:id', requireAdmin, async (req, res) => {
  try {
    const { id } = req.params;
    const { slug, title, excerpt, author, date, tags, cover, status, read_time, body } = req.body;
    if (!title || !body) return res.status(400).json({ error: 'title y body son obligatorios' });

    const file     = id + '.json';
    const postData = { id, slug, title, excerpt, author: author || 'Wilson.E', date, tags: tags || [], cover: cover || '', status: status || 'published', read_time: read_time || 1, body };
    const indexMeta = { id, slug, title, excerpt: excerpt || body.replace(/<[^>]+>/g,'').slice(0,180) + '…', author: postData.author, date, tags: postData.tags, cover: postData.cover, status: postData.status, read_time: postData.read_time, file };

    const idxData = await getBlogIndex();
    idxData.posts = idxData.posts.filter(p => p.id !== id);
    idxData.posts.push(indexMeta);
    idxData._meta.total       = idxData.posts.length;
    idxData._meta.last_updated = new Date().toISOString();

    await ghUpdateFile('blog/posts/' + file, JSON.stringify(postData, null, 2), `blog: update post "${title}"`);
    await ghUpdateFile('blog/index.json',    JSON.stringify(idxData,  null, 2), `blog: update index after edit "${title}"`);

    res.json({ ok: true, id, slug });
  } catch (err) {
    console.error('PUT /api/blog/posts/:id error:', err.message);
    res.status(500).json({ error: err.message });
  }
});

// DELETE /api/blog/posts/:id — borrar post
app.delete('/api/blog/posts/:id', requireAdmin, async (req, res) => {
  try {
    const { id } = req.params;
    const file = id + '.json';

    // Eliminar archivo del post en GitHub
    if (octokit) {
      try {
        const { data } = await octokit.repos.getContent({
          owner: GITHUB_OWNER, repo: GITHUB_REPO, path: 'blog/posts/' + file, ref: GITHUB_BRANCH,
        });
        await octokit.repos.deleteFile({
          owner: GITHUB_OWNER, repo: GITHUB_REPO, path: 'blog/posts/' + file,
          message: `blog: delete post ${id}`, sha: data.sha, branch: GITHUB_BRANCH,
        });
      } catch { /* el archivo puede no existir */ }
    }

    // Actualizar index.json
    const idxData = await getBlogIndex();
    idxData.posts = idxData.posts.filter(p => p.id !== id);
    idxData._meta.total       = idxData.posts.length;
    idxData._meta.last_updated = new Date().toISOString();
    await ghUpdateFile('blog/index.json', JSON.stringify(idxData, null, 2), `blog: remove post ${id}`);

    res.json({ ok: true, id });
  } catch (err) {
    console.error('DELETE /api/blog/posts/:id error:', err.message);
    res.status(500).json({ error: err.message });
  }
});

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
    console.log(`   Cerebras:   ${process.env.CEREBRAS_API_KEY    ? '✅' : '⚠️  sin configurar'}`);
    console.log(`   HuggingFace:${process.env.HUGGINGFACE_API_KEY ? '✅' : '⚠️  sin configurar'}`);
    console.log('   OpenRouter: ' + (process.env.OPENROUTER_API_KEY ? '✅ (' + OR_FREE_MODELS.length + ' modelos gratis)' : '⚠️  sin configurar'));
    console.log(`   Gemini:     ${process.env.GEMINI_API_KEY      ? '✅' : '⚠️  sin configurar'}`);
    console.log(`   Mistral:    ${process.env.MISTRAL_API_KEY     ? '✅' : '⚠️  sin configurar'}`);
    console.log(`   Cohere:     ${process.env.COHERE_API_KEY      ? '✅' : '⚠️  sin configurar'}`);
    console.log(`   Storage:    ${supabase ? '✅ Supabase' : '❌ falta SUPABASE_URL/KEY'}`);
    console.log(`   Together:   ${process.env.TOGETHER_API_KEY ? '✅' : '⚠️  sin configurar'}`);
  });
})();

// ── RENDER KEEPALIVE — se agrega después del server.listen ────
// Render free tier apaga el servicio tras ~15 min de inactividad.
// Self-ping cada 10 min mantiene el proceso vivo sin servicio externo.
// Requiere: RENDER_EXTERNAL_URL en las variables de entorno de Render.

function startRenderKeepalive() {
  const SELF_URL = process.env.RENDER_EXTERNAL_URL || null;
  if (!SELF_URL) {
    console.log('   Keepalive:  ⚠️  agrega RENDER_EXTERNAL_URL en Render > Environment');
    return;
  }
  const target = SELF_URL.replace(/\/$/, '') + '/api/health';
  const lib = target.startsWith('https') ? require('https') : require('http');
  setInterval(() => {
    lib.get(target, (res) => {
      console.log('🔔 Render keepalive ping →', res.statusCode);
    }).on('error', (e) => console.warn('⚠️  Keepalive error:', e.message));
  }, 10 * 60 * 1000);
  console.log('   Keepalive:  ✅ self-ping activo → ' + target + ' (cada 10 min)');
}
startRenderKeepalive();
