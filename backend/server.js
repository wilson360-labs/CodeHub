/**
 * CodeHub Backend v3.0 — Wilson.E 2026
 * ─────────────────────────────────────────────────────────────
 * ✅ WebSockets — notificaciones en tiempo real
 * ✅ Redis      — caché (opcional, Railway Redis addon)
 * ✅ Eventos:   visitas, descargas, ratings, contacto, chat IA, nueva app
 *
 * Variables Railway:
 *   GROQ_API_KEY, GEMINI_API_KEY, MONGODB_URI, FRONTEND_URL
 *   ADMIN_KEY, B2_KEY_ID, B2_APP_KEY, B2_BUCKET_ID, B2_BUCKET_NAME
 *   RATE_LIMIT_MAX, REDIS_URL (opcional), WS_URL (opcional)
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

app.use(cors({
  origin: (origin, cb) => {
    if (!origin || allowedOrigins.includes(origin)) return cb(null, true);
    cb(new Error('CORS bloqueado: ' + origin));
  },
  methods: ['GET', 'POST', 'PATCH', 'DELETE'],
  allowedHeaders: ['Content-Type', 'x-admin-key'],
}));
app.use(express.json({ limit: '10kb' }));

// Multer APKs (máx 200 MB)
const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 200 * 1024 * 1024 },
  fileFilter: (_, f, cb) => {
    if (f.mimetype === 'application/vnd.android.package-archive' || f.originalname.endsWith('.apk'))
      cb(null, true);
    else cb(new Error('Solo .apk'));
  },
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
  const key   = req.headers['x-admin-key'] || req.body?.adminKey;
  const valid = process.env.ADMIN_KEY || 'wilson2026ultra';
  if (key !== valid) return res.status(403).json({ error: 'No autorizado' });
  next();
}

// ── BACKBLAZE B2 ──────────────────────────────────────────────
let _b2 = null;
async function getB2Auth() {
  if (_b2 && Date.now() < _b2.expiry) return _b2;
  const keyId = process.env.B2_KEY_ID, appKey = process.env.B2_APP_KEY;
  if (!keyId || !appKey) throw new Error('B2_KEY_ID y B2_APP_KEY no configurados');
  const creds = Buffer.from(`${keyId}:${appKey}`).toString('base64');
  const res = await fetch('https://api.backblazeb2.com/b2api/v3/b2_authorize_account', { headers: { Authorization: 'Basic ' + creds } });
  if (!res.ok) throw new Error('B2 auth falló: ' + res.status);
  const d = await res.json();
  _b2 = {
    token: d.authorizationToken,
    apiUrl: d.apiInfo?.storageApi?.apiUrl || d.apiUrl,
    downloadUrl: d.apiInfo?.storageApi?.downloadUrl || d.downloadUrl,
    expiry: Date.now() + 20 * 60 * 60 * 1000,
  };
  console.log('✅ B2 autenticado'); return _b2;
}

async function uploadToB2(buffer, fileName) {
  const auth = await getB2Auth(), bucketId = process.env.B2_BUCKET_ID;
  if (!bucketId) throw new Error('B2_BUCKET_ID no configurado');
  const urlRes = await fetch(`${auth.apiUrl}/b2api/v3/b2_get_upload_url`, {
    method: 'POST', headers: { Authorization: auth.token, 'Content-Type': 'application/json' },
    body: JSON.stringify({ bucketId }),
  });
  if (!urlRes.ok) throw new Error('Error upload URL B2: ' + urlRes.status);
  const { uploadUrl, authorizationToken: uploadAuth } = await urlRes.json();
  const sha1 = crypto.createHash('sha1').update(buffer).digest('hex');
  const upRes = await fetch(uploadUrl, {
    method: 'POST',
    headers: {
      Authorization: uploadAuth,
      'X-Bz-File-Name': encodeURIComponent(fileName),
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Length': String(buffer.length),
      'X-Bz-Content-Sha1': sha1,
    },
    body: buffer,
  });
  if (!upRes.ok) { const e = await upRes.json().catch(() => ({})); throw new Error('Error subiendo B2: ' + (e.message || upRes.status)); }
  const data = await upRes.json();
  console.log(`✅ B2 upload: ${fileName} (${(buffer.length/1024/1024).toFixed(1)} MB)`);
  return { fileId: data.fileId, fileName: data.fileName };
}

async function deleteFromB2(fileName) {
  if (!fileName) return false;
  try {
    const auth = await getB2Auth();
    const listRes = await fetch(`${auth.apiUrl}/b2api/v3/b2_list_file_names`, {
      method: 'POST', headers: { Authorization: auth.token, 'Content-Type': 'application/json' },
      body: JSON.stringify({ bucketId: process.env.B2_BUCKET_ID, prefix: fileName, maxFileCount: 1 }),
    });
    const { files } = await listRes.json(); const file = files?.[0]; if (!file) return false;
    await fetch(`${auth.apiUrl}/b2api/v3/b2_delete_file_version`, {
      method: 'POST', headers: { Authorization: auth.token, 'Content-Type': 'application/json' },
      body: JSON.stringify({ fileId: file.fileId, fileName: file.fileName }),
    });
    console.log(`🗑️ B2 delete: ${fileName}`); return true;
  } catch (e) { console.warn('B2 delete error:', e.message); return false; }
}

async function getB2SignedUrl(fileName, validSeconds = 86400) {
  const auth = await getB2Auth(), bucketName = process.env.B2_BUCKET_NAME || 'codehub-apks';
  const tokenRes = await fetch(`${auth.apiUrl}/b2api/v3/b2_get_download_authorization`, {
    method: 'POST', headers: { Authorization: auth.token, 'Content-Type': 'application/json' },
    body: JSON.stringify({ bucketId: process.env.B2_BUCKET_ID, fileNamePrefix: fileName, validDurationInSeconds: validSeconds }),
  });
  if (!tokenRes.ok) throw new Error('Error download auth: ' + tokenRes.status);
  const { authorizationToken: dlToken } = await tokenRes.json();
  return `${auth.downloadUrl}/file/${bucketName}/${encodeURIComponent(fileName)}?Authorization=${dlToken}`;
}

// ── IA ────────────────────────────────────────────────────────
const SYSTEM = `Eres el asistente IA de CodeHub, portfolio de Wilson.E, dev full stack guatemalteco (24 años, Guatemala 🇬🇹).
PERSONALIDAD: Conciso, técnico y amigable. Siempre en español. Emojis con moderación. Máx 4 oraciones.
SOBRE CODEHUB: 23+ herramientas web, apps Android premium, juegos Snake/Tetris, descargador de videos.
Contacto: wilsonenrique686@gmail.com / WhatsApp +502 4146 8185.
FORMATO: Código en bloques, listas con guión, negritas para términos clave.`;

async function callGroq(msgs) {
  const res = await fetch('https://api.groq.com/openai/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${process.env.GROQ_API_KEY}` },
    body: JSON.stringify({ model: 'llama-3.3-70b-versatile', max_tokens: 600, temperature: 0.7, messages: msgs }),
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

async function callAI(msgs) {
  if (process.env.GROQ_API_KEY) { try { return await callGroq(msgs); } catch (e) { if (e.status === 401) throw e; console.warn('Groq falló, usando Gemini...'); } }
  if (process.env.GEMINI_API_KEY) return await callGemini(msgs);
  throw new Error('Sin API keys de IA');
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
//  RUTAS
// ════════════════════════════════════════════════════════════════

// Health
app.get('/api/health', (_, res) => res.json({
  status: 'ok', version: '3.0',
  mongo:  dbConnected ? 'connected' : 'disconnected',
  redis:  redis       ? 'connected' : 'memory',
  ws:     wsClients.size + ' clients',
  groq:   process.env.GROQ_API_KEY   ? 'ok' : 'missing',
  gemini: process.env.GEMINI_API_KEY ? 'ok' : 'missing',
  b2:     (process.env.B2_KEY_ID && process.env.B2_APP_KEY) ? 'configured' : 'missing',
  uptime: Math.floor(process.uptime()) + 's',
}));

// Stats en vivo
app.get('/api/stats/live', (_, res) => {
  trackVisit();
  res.json({ visitors: visits.today, total: visits.total, wsClients: wsClients.size });
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
      enlace:       a.b2_file_name        ? `${base}/api/download/${encodeURIComponent(a.b2_file_name)}`        : (a.enlace || '#'),
      plugin_enlace:a.b2_plugin_file_name ? `${base}/api/download/${encodeURIComponent(a.b2_plugin_file_name)}` : (a.plugin_enlace || null),
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
  const { message, sessionId = 'anon', history = [] } = req.body;
  if (!message || typeof message !== 'string') return res.status(400).json({ error: '"message" requerido.' });
  if (message.trim().length > 1000) return res.status(400).json({ error: 'Mensaje muy largo.' });
  if (!process.env.GROQ_API_KEY && !process.env.GEMINI_API_KEY) return res.status(503).json({ error: 'Sin API keys.' });

  const safeHist = (Array.isArray(history) ? history.slice(-10) : [])
    .map(m => ({ role: m.role === 'assistant' ? 'assistant' : 'user', content: String(m.content).slice(0, 800) }));
  safeHist.push({ role: 'user', content: message.trim() });
  const msgs = [{ role: 'system', content: SYSTEM }, ...safeHist];

  try {
    const { reply, input, output, model } = await callAI(msgs);
    if (dbConnected) ChatMessage.insertMany([
      { sessionId, role: 'user',      content: message.trim(), tokens: input,  model },
      { sessionId, role: 'assistant', content: reply,          tokens: output, model },
    ]).catch(() => {});
    broadcast('chat_used', { model, tokens: input + output });
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

// Download APK (URL firmada B2 + evento WS)
app.get('/api/download/:fileName', async (req, res) => {
  const { fileName } = req.params;
  if (!fileName || fileName.includes('..')) return res.status(400).json({ error: 'Nombre inválido' });
  try {
    const url = await getB2SignedUrl(decodeURIComponent(fileName), 86400);
    broadcast('download', { fileName: decodeURIComponent(fileName) });
    res.redirect(302, url);
  } catch (e) { console.error('Error URL firmada:', e.message); res.status(500).json({ error: 'No se pudo generar el link.' }); }
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
    if (a.b2_file_name)        await deleteFromB2(a.b2_file_name);
    if (a.b2_plugin_file_name) await deleteFromB2(a.b2_plugin_file_name);
    await App.deleteOne({ appId: req.params.appId }); await cacheDel('apps:all');
    res.json({ ok: true });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.post('/api/admin/apps/:appId/upload', requireAdmin, upload.single('apk'), async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  if (!req.file) return res.status(400).json({ error: 'No se recibió archivo' });
  const { appId } = req.params, isPlugin = req.body.slot === 'plugin';
  try {
    const a = await App.findOne({ appId }); if (!a) return res.status(404).json({ error: 'App no encontrada' });
    const fileName = `${appId}_${isPlugin ? 'plugin' : 'main'}_${Date.now()}.apk`;
    if (!isPlugin && a.b2_file_name)        await deleteFromB2(a.b2_file_name);
    if ( isPlugin && a.b2_plugin_file_name) await deleteFromB2(a.b2_plugin_file_name);
    const { fileId } = await uploadToB2(req.file.buffer, fileName);
    const upd = isPlugin
      ? { b2_plugin_file_id: fileId, b2_plugin_file_name: fileName, updatedAt: new Date() }
      : { b2_file_id: fileId,        b2_file_name: fileName,        updatedAt: new Date() };
    await App.updateOne({ appId }, upd); await cacheDel('apps:all');
    res.json({ ok: true, fileId, fileName, sizeMB: (req.file.size / 1024 / 1024).toFixed(1) });
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

// ── ARRANCAR ──────────────────────────────────────────────────
(async () => {
  await initRedis();
  dbConnected = await connectDB();
  getB2Auth().catch(e => console.warn('B2 pre-auth:', e.message));

  server.listen(PORT, () => {
    console.log(`🚀 CodeHub Backend v3.0 en puerto ${PORT}`);
    console.log(`   MongoDB:    ${dbConnected ? '✅' : '⚠️  sin conexión'}`);
    console.log(`   Redis:      ${redis       ? '✅' : '⚠️  usando memoria'}`);
    console.log(`   WebSockets: ✅ /ws`);
    console.log(`   Groq:       ${process.env.GROQ_API_KEY   ? '✅' : '❌ falta'}`);
    console.log(`   Gemini:     ${process.env.GEMINI_API_KEY ? '✅' : '⚠️  opcional'}`);
    console.log(`   Backblaze:  ${process.env.B2_KEY_ID      ? '✅' : '⚠️  sin configurar'}`);
    console.log(`   Together:   ${process.env.TOGETHER_API_KEY ? '✅' : '⚠️  sin configurar'}`);
  });
})();
