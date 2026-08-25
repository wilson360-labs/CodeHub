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
const compression = require('compression');
const multer    = require('multer');
const Busboy    = require('busboy');   // dep transitiva de multer — parseo multipart sin buffer
const crypto    = require('crypto');
const http      = require('http');
const fs        = require('fs');
const path      = require('path');
const { WebSocketServer } = require('ws');
const swaggerSpec        = require('./swagger');

// ── SUPABASE ──────────────────────────────────────────────────
const { createClient } = require('@supabase/supabase-js');
const supabase = (process.env.SUPABASE_URL?.trim() && process.env.SUPABASE_KEY?.trim())
  ? createClient(process.env.SUPABASE_URL, process.env.SUPABASE_KEY)
  : null;

// Helper: registrar evento en Supabase (fire-and-forget — no bloquea la petición)
async function trackEvent(type, page = null, metadata = {}) {
  if (!supabase) return;
  setImmediate(async () => {
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
  });
}

// ── SKILLS — catálogo de capacidades de IA (skills/…) ──────────
// Cada skill es un folder con skill.json. Se sirven por GET /api/skills
// y su system_prompt_inject se inyecta en /api/chat cuando el usuario
// manda skill_id (p.ej. 'pdf-ia' al adjuntar un PDF).
const SKILLS_DIR = path.join(__dirname, '../skills');
const skillsCache = new Map();

function loadSkillJson(id) {
  if (!id || typeof id !== 'string') return null;
  if (skillsCache.has(id)) return skillsCache.get(id);
  const file = path.join(SKILLS_DIR, id, 'skill.json');
  try {
    if (!fs.existsSync(file)) return null;
    const data = JSON.parse(fs.readFileSync(file, 'utf8'));
    skillsCache.set(id, data);
    return data;
  } catch (e) {
    console.warn('Skill no cargable:', id, e.message);
    return null;
  }
}

// ── DB RUNNER — divide un script .sql en sentencias individuales ──
// Respeta strings entre comillas simples/dobles y bloques con
// dollar-quoting ($$ ... $$ o $tag$ ... $tag$, típico de funciones
// plpgsql) para no cortar un ';' que esté dentro de esos bloques.
function splitSqlStatements(sql) {
  const statements = [];
  let cur = '';
  let i = 0;
  const n = sql.length;
  let inSingle = false, inDouble = false, dollarTag = null, inLineComment = false;
  while (i < n) {
    const ch = sql[i];
    if (inLineComment) {
      cur += ch;
      if (ch === '\n') inLineComment = false;
      i++; continue;
    }
    if (dollarTag) {
      cur += ch;
      if (sql.startsWith(dollarTag, i)) {
        cur += dollarTag.slice(1);
        i += dollarTag.length;
        dollarTag = null;
        continue;
      }
      i++; continue;
    }
    if (inSingle) {
      cur += ch;
      if (ch === "'" && sql[i + 1] === "'") { cur += "'"; i += 2; continue; }
      if (ch === "'") inSingle = false;
      i++; continue;
    }
    if (inDouble) {
      cur += ch;
      if (ch === '"') inDouble = false;
      i++; continue;
    }
    if (ch === '-' && sql[i + 1] === '-') { inLineComment = true; cur += ch; i++; continue; }
    if (ch === "'") { inSingle = true; cur += ch; i++; continue; }
    if (ch === '"') { inDouble = true; cur += ch; i++; continue; }
    if (ch === '$') {
      const m = sql.slice(i).match(/^\$[a-zA-Z_]*\$/);
      if (m) { dollarTag = m[0]; cur += dollarTag; i += dollarTag.length; continue; }
    }
    if (ch === ';') { statements.push(cur); cur = ''; i++; continue; }
    cur += ch; i++;
  }
  if (cur.trim()) statements.push(cur);
  return statements.map(s => s.trim()).filter(Boolean);
}

const app    = express();
const server = http.createServer(app);
const PORT   = process.env.PORT || 3001;

app.set('trust proxy', 1);
app.use(helmet({ contentSecurityPolicy: false }));
app.use(compression());

// ── SECURITY: Anti-bot & hardening ───────────────────────────
// Block known scanner/bot User-Agents targeting admin endpoints
const _BOT_UA_RE = /python-urllib|python-requests|go-http-client|java\/|curl\/|wget\/|scrapy|nikto|sqlmap|nmap|masscan|zgrab|gobuster|dirbuster|hydra|medusa|wfuzz|ffuf|nuclei|httpx|censys|shodan|zoomye/i;
app.use('/api/admin', (req, res, next) => {
  const ua = (req.headers['user-agent'] || '').slice(0, 200);
  if (ua && _BOT_UA_RE.test(ua)) {
    const ip = clientIp(req);
    tgAlert('botprobe', () => `🤖 <b>BOT DETECTADO en /api/admin</b>\nIP: <code>${ip}</code>\nUA: ${ua.slice(0, 70)}`, { windowMs: 30000 });
    return res.status(403).json({ error: 'Acceso no autorizado' });
  }
  next();
});
// Honeypot: hidden endpoint that real users never hit — bots do
app.all('/api/admin/secret-panel', (req, res) => {
  const ip = clientIp(req);
  const ua = (req.headers['user-agent'] || '').slice(0, 100).replace(/[<>]/g, '');
  tgAlert('honeypot', () => `🍯 <b>HONEYPOT TRIGGERED</b>\nIP: <code>${ip}</code>\nUA: ${ua}`, { windowMs: 60000 });
  if (!_adminBans.has(ip)) _adminBans.set(ip, { expiresAt: Date.now() + ADMIN_BAN_DURATION_MS });
  return res.status(404).json({ error: 'Not found' });
});

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
    cb(null, false);
  },
  methods: ['GET', 'POST', 'PATCH', 'DELETE', 'OPTIONS'],
  allowedHeaders: ['Content-Type', 'x-admin-key', 'x-admin-user', 'x-admin-session', 'Accept', 'Authorization'],
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
// /api/admin — algunas rutas mandan payloads más grandes que el límite
// global de 10kb: el seed masivo del catálogo (ej. las 48 apps Open
// Source, ~23kb) o extract-icon con imágenes en base64. Mismo patrón
// que /api/chat arriba: se registra antes del límite global.
app.use('/api/admin', express.json({ limit: '5mb' }));
// /api/crash-report — stack traces + log de crash acumulado (app Android)
// pueden superar el límite chico global; mismo patrón de arriba.
app.use('/api/crash-report', express.json({ limit: '200kb' }));
// /api/webhook — necesita el body crudo (Buffer) además del JSON parseado,
// para poder validar la firma HMAC-SHA256 que manda GitHub en el header
// X-Hub-Signature-256. El `verify` callback guarda esos bytes en
// req.rawBody antes de que Express los descarte tras parsear el JSON.
app.use('/api/webhook', express.json({
  limit: '200kb',
  verify: (req, _res, buf) => { req.rawBody = buf; },
}));
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
const chatLimiter  = rateLimit({ windowMs: 15*60*1000, max: parseInt(process.env.RATE_LIMIT_MAX)||50, standardHeaders: true, legacyHeaders: false, message: { error: 'Demasiadas solicitudes.', code: 'RATE_LIMIT' }, handler: rateLimitHandler });
const adminLimiter = rateLimit({ windowMs: 15*60*1000, max: 100, standardHeaders: true, legacyHeaders: false, handler: rateLimitHandler });
// Auth admin: máximo 5 intentos por 15 min por IP (Turnstile + key check)
const adminAuthLimiter = rateLimit({ windowMs: 15*60*1000, max: 5, standardHeaders: true, legacyHeaders: false, message: { error: 'Demasiados intentos de autenticación. Espera 15 minutos.', code: 'ADMIN_AUTH_RATE_LIMIT' }, handler: rateLimitHandler });
// App Android: hasta 40 reportes de crash por IP cada 15 min (cubre loops de
// crash reales) sin abrir la puerta a flood del endpoint público.
const crashLimiter = rateLimit({ windowMs: 15*60*1000, max: 40, standardHeaders: true, legacyHeaders: false, handler: rateLimitHandler });
// Imágenes: límite separado para que generar imágenes no agote el cupo del chat.
const imageLimiter = rateLimit({ windowMs: 15*60*1000, max: 20, standardHeaders: true, legacyHeaders: false, message: { error: 'Límite de generación de imágenes alcanzado.', code: 'IMAGE_RATE_LIMIT' }, handler: rateLimitHandler });

// ── ADMIN BAN SYSTEM (anti brute-force) ───────────────────────
// Map<ip, { expiresAt, attempts, firstAttemptAt }>
const _adminBans  = new Map();
const _adminFails = new Map();
const ADMIN_FAIL_THRESHOLD  = 5;   // fallos antes de ban
const ADMIN_BAN_DURATION_MS = 30 * 60 * 1000; // 30 minutos

function _cleanExpiredBans() {
  const now = Date.now();
  for (const [ip, info] of _adminBans) {
    if (info.expiresAt <= now) _adminBans.delete(ip);
  }
  for (const [ip, info] of _adminFails) {
    if (info.windowEnd <= now) _adminFails.delete(ip);
  }
}
setInterval(_cleanExpiredBans, 60_000);

function _isIPBanned(ip) {
  _cleanExpiredBans();
  const ban = _adminBans.get(ip);
  if (!ban) return false;
  if (ban.expiresAt <= Date.now()) { _adminBans.delete(ip); return false; }
  return true;
}

function _recordAdminFail(ip, ua) {
  const now = Date.now();
  const windowMs = 15 * 60 * 1000;
  let entry = _adminFails.get(ip);
  if (!entry || entry.windowEnd <= now) {
    entry = { count: 1, windowEnd: now + windowMs, firstAttemptAt: now };
    _adminFails.set(ip, entry);
    return 1;
  }
  entry.count++;
  if (entry.count >= ADMIN_FAIL_THRESHOLD && !_adminBans.has(ip)) {
    _adminBans.set(ip, { expiresAt: now + ADMIN_BAN_DURATION_MS });
    tgAlert('adminban', () => {
      return `🚫 <b>BAN ADMIN — IP Bloqueada</b>\nIP: <code>${ip}</code>\nIntentos: ${entry.count} fallos en 15 min\nBloqueada por 30 min\nUA: ${(ua || '').slice(0, 70).replace(/[<>]/g, '')}`;
    }, { windowMs: 60000 });
    console.warn(`🚫 ADMIN BAN: IP ${ip} banned for ${ADMIN_BAN_DURATION_MS / 60000}min after ${entry.count} failed attempts`);
  }
  return entry.count;
}

// ── SESSION TOKEN (HMAC-SHA256) ────────────────────────────────
const SESSION_SECRET = process.env.ADMIN_SESSION_SECRET || process.env.ADMIN_KEY || crypto.randomBytes(32).toString('hex');
const SESSION_TTL_MS = 30 * 60 * 1000; // 30 minutos

function _signSession(payload) {
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'S' })).toString('base64url');
  const body   = Buffer.from(JSON.stringify(payload)).toString('base64url');
  const sig    = crypto.createHmac('sha256', SESSION_SECRET).update(header + '.' + body).digest('base64url');
  return header + '.' + body + '.' + sig;
}

function _verifySession(token) {
  try {
    const [header, body, sig] = token.split('.');
    if (!header || !body || !sig) return null;
    const expected = crypto.createHmac('sha256', SESSION_SECRET).update(header + '.' + body).digest('base64url');
    const sigBuf = Buffer.from(sig);
    const expBuf = Buffer.from(expected);
    if (sigBuf.length !== expBuf.length) return null;
    if (!crypto.timingSafeEqual(sigBuf, expBuf)) return null;
    const payload = JSON.parse(Buffer.from(body, 'base64url').toString());
    if (!payload.exp || payload.exp <= Date.now()) return null;
    return payload;
  } catch { return null; }
}

// ── Contador diario de EMI por usuario/dispositivo ───────────
// Key: 'u:<user_id>' o 'd:<ip>'. Value: { date, count }.
const _emiUsage = new Map();
const EMI_DAILY_LIMIT_GUEST = 10;
const EMI_DAILY_LIMIT_REGISTERED = 50;

function getEmiUsage(key) {
  const entry = _emiUsage.get(key);
  const today = new Date().toISOString().slice(0, 10);
  if (!entry || entry.date !== today) { _emiUsage.set(key, { date: today, count: 0 }); return 0; }
  return entry.count;
}

function incrEmiUsage(key) {
  const today = new Date().toISOString().slice(0, 10);
  const entry = _emiUsage.get(key);
  if (!entry || entry.date !== today) { _emiUsage.set(key, { date: today, count: 1 }); return 1; }
  entry.count++;
  return entry.count;
}
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

// Avisa a los clientes conectados (ej. la página de Open Source) que el
// catálogo cambió — total y total de apps open source para actualizar el
// contador en tiempo real sin depender del TTL de la caché de /api/apps.
async function broadcastAppsChanged() {
  if (!dbConnected) return;
  try {
    const [total, os] = await Promise.all([
      App.countDocuments({}),
      App.countDocuments({ source_repo: { $ne: null } }),
    ]);
    broadcast('apps_changed', { total, os });
  } catch {}
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
  packageName:        { type: String, default: null }, // applicationId Android real (ej. "org.schabi.newpipe") — habilita detección de apps instaladas + auto-instalación (ver backend/scripts/resolve-package-names.js)
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

// CodeHub Releases — novedades del proyecto (nuevas funciones/versiones
// integradas). Se publican desde el admin-hub y caen en la campana de
// notificaciones. NO están vinculadas al historial de git.
const Release = mongoose.model('Release', new mongoose.Schema({
  title:     { type: String, required: true },
  body:      { type: String, default: '' },
  version:   { type: String, default: '' },
  url:       { type: String, default: '/' },
  type:      { type: String, enum: ['release','feature','fix','maintenance'], default: 'release' },
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

// ── ADMIN AUTH ENDPOINT ─────────────────────────────────────
// POST /api/admin/auth — valida key + Turnstile → devuelve session token HMAC (30 min)
app.post('/api/admin/auth', adminAuthLimiter, async (req, res) => {
  const ip = clientIp(req);
  const ua = (req.headers['user-agent'] || '').slice(0, 100).replace(/[<>]/g, '');

  // 1) Anti ban check
  if (_isIPBanned(ip)) {
    return res.status(429).json({ error: 'IP temporalmente bloqueada por intentos fallidos. Intenta en 30 minutos.' });
  }

  // 2) Rate limit warnings on specific IP threshold
  const failCount = (_adminFails.get(ip) || {}).count || 0;

  const { password, turnstileToken } = req.body || {};
  const validKey = process.env.ADMIN_KEY;

  if (!validKey) {
    console.error('⚠️  ADMIN_KEY no configurada en variables de entorno de Render');
    return res.status(503).json({ error: 'Servidor no configurado — falta ADMIN_KEY en Render' });
  }

  // 3) Validate Turnstile (fail-closed)
  const tsToken = String(turnstileToken || '');
  if (!await validateTurnstile(tsToken)) {
    _recordAdminFail(ip, ua);
    tgAlert('adminfail', () => {
      return `🤖 <b>TURNSTILE FALLO ADMIN</b>\nIP: <code>${ip}</code>\nUA: ${ua}`;
    }, { windowMs: 15000 });
    return res.status(403).json({ error: 'Verificación anti-bots fallida' });
  }

  // 4) Validate key
  if (password !== validKey) {
    _recordAdminFail(ip, ua);
    tgAlert('adminfail', () => {
      return `🔐 <b>INTENTO FALLIDO ADMIN</b>\nIP: <code>${ip}</code>\nKey: ${String(password || '').slice(0, 6)}…\nUA: ${ua}`;
    }, { windowMs: 15000 });
    return res.status(403).json({ error: 'Credenciales incorrectas' });
  }

  // 5) Success — clear fail counter, issue session token
  _adminFails.delete(ip);
  const now = Date.now();
  const token = _signSession({ admin: true, iat: now, exp: now + SESSION_TTL_MS });

  tgAlert('adminlogin', () => {
    return `✅ <b>ADMIN LOGIN EXITOSO</b>\nIP: <code>${ip}</code>\nUA: ${ua}`;
  }, { windowMs: 60000 });

  res.json({ ok: true, sessionToken: token, expiresIn: SESSION_TTL_MS });
});

// ── AUTH ADMIN ────────────────────────────────────────────────
function requireAdmin(req, res, next) {
  const ip       = clientIp(req);
  const validKey = process.env.ADMIN_KEY;

  // 1) Anti brute-force: verificar si la IP está baneada
  if (_isIPBanned(ip)) {
    return res.status(429).json({ error: 'IP temporalmente bloqueada por intentos fallidos. Intenta en 30 minutos.' });
  }

  if (!validKey) {
    console.error('⚠️  ADMIN_KEY no configurada en variables de entorno de Render');
    return res.status(503).json({ error: 'Servidor no configurado — falta ADMIN_KEY en Render' });
  }

  // 2) Aceptar session token HMAC (post-auth)
  const sessionToken = req.headers['x-admin-session'];
  if (sessionToken) {
    const payload = _verifySession(sessionToken);
    if (payload && payload.admin === true) return next();
    return res.status(401).json({ error: 'Sesión expirada o inválida' });
  }

  // 3) Fallback: key directa (legacy, con ban tracking)
  const key  = req.headers['x-admin-key'] || req.body?.adminKey;
  const user = req.headers['x-admin-user'] || req.body?.adminUser || null;
  const validUser = process.env.ADMIN_USER;

  if (key !== validKey) {
    const ua = (req.headers['user-agent'] || '').slice(0, 70).replace(/[<>]/g, '');
    _recordAdminFail(ip, ua);
    tgAlert('adminfail', () => {
      return `🔐 <b>INTENTO FALLIDO ADMIN</b>\nIP: <code>${ip}</code>\nKey: ${String(key || '').slice(0, 6)}…\nUA: ${ua}`;
    }, { windowMs: 15000 });
    return res.status(403).json({ error: 'Credenciales incorrectas' });
  }
  // Si ADMIN_USER está configurado en Render, también lo validamos
  if (validUser && user && user !== validUser) return res.status(403).json({ error: 'Credenciales incorrectas' });
  next();
}

// ── AUTH USUARIO (opcional) ────────────────────────────────────
// Valida el token Supabase si se envía, pero NO bloquea invitados.
// Attach req.authUser = { id, email } si el token es válido.
async function requireAuth(req, res, next) {
  if (!supabase) { req.authUser = null; return next(); }
  const auth = req.headers.authorization || '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7) : null;
  if (!token) { req.authUser = null; return next(); }
  try {
    const { data, error } = await supabase.auth.getUser(token);
    if (error || !data?.user) { req.authUser = null; return next(); }
    req.authUser = { id: data.user.id, email: data.user.email };
  } catch { req.authUser = null; }
  next();
}

// ── AUTH USUARIOS (Supabase Auth) ────────────────────────────────
// Frontend (js/auth.js) usa estos endpoints para login/registro de
// usuarios normales (NO admin). Supabase Auth maneja contraseñas y
// sesiones; aquí solo validamos y devolvemos la sesión al cliente.
// Requiere SUPABASE_URL y SUPABASE_KEY (service role) en Render.
const authLimiter = rateLimit({ windowMs: 15*60*1000, max: 20, standardHeaders: true, legacyHeaders: false, message: { error: 'Demasiados intentos. Espera un poco.', code: 'AUTH_RATE_LIMIT' }, handler: rateLimitHandler });
app.use('/api/auth', authLimiter);

// POST /api/auth/register — crear cuenta con email + contraseña
app.post('/api/auth/register', async (req, res) => {
  const email    = String(req.body?.email || '').trim().toLowerCase();
  const password = String(req.body?.password || '');
  const tsToken  = String(req.body?.turnstileToken || '');
  if (!await validateTurnstile(tsToken)) return res.status(403).json({ error: 'Verificación anti-bots fallida' });
  if (!supabase) return res.status(503).json({ error: 'Servidor no configurado — Supabase no está disponible' });
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return res.status(400).json({ error: 'Email inválido' });
  if (password.length < 8) return res.status(400).json({ error: 'La contraseña debe tener al menos 8 caracteres' });

  const { data, error } = await supabase.auth.signUp({ email, password });
  if (error) {
    // 422 = user_already_exists o email ocupado
    if (error.status === 422 || /already|exists|registered/i.test(error.message)) {
      return res.status(409).json({ error: 'Ese correo ya está registrado' });
    }
    return res.status(400).json({ error: error.message });
  }
  const user = data.user;
  if (!user) return res.status(500).json({ error: 'No se pudo crear el usuario' });

  res.status(201).json({
    ok: true,
    user: { id: user.id, email: user.email },
    session: data.session || null,
    needsConfirmation: !data.session,
    message: data.session ? 'Cuenta creada' : 'Revisa tu correo para confirmar la cuenta',
  });
});

// POST /api/auth/login — iniciar sesión con email + contraseña
app.post('/api/auth/login', async (req, res) => {
  const email    = String(req.body?.email || '').trim().toLowerCase();
  const password = String(req.body?.password || '');
  const tsToken  = String(req.body?.turnstileToken || '');
  if (!await validateTurnstile(tsToken)) return res.status(403).json({ error: 'Verificación anti-bots fallida' });
  if (!supabase) return res.status(503).json({ error: 'Servidor no configurado — Supabase no está disponible' });
  if (!email || !password) return res.status(400).json({ error: 'Completa email y contraseña' });

  const { data, error } = await supabase.auth.signInWithPassword({ email, password });
  if (error) {
    if (error.status === 400 || /invalid login|invalid credentials/i.test(error.message)) {
      return res.status(401).json({ error: 'Email o contraseña incorrectos' });
    }
    return res.status(400).json({ error: error.message });
  }
  const user = data.user;
  if (!user) return res.status(500).json({ error: 'No se pudo iniciar sesión' });

  res.status(200).json({
    ok: true,
    user: { id: user.id, email: user.email },
    session: data.session || null,
  });
});

// POST /api/auth/logout — revocar la sesión del token (opcional)
app.post('/api/auth/logout', async (req, res) => {
  const token = String(req.headers['authorization'] || '').replace(/^Bearer\s+/i, '') || String(req.body?.token || '');
  if (token && supabase) await supabase.auth.admin.signOut(token);
  res.json({ ok: true });
});

// POST /api/auth/refresh — renovar access_token usando refresh_token
app.post('/api/auth/refresh', async (req, res) => {
  const { refresh_token } = req.body || {};
  if (!refresh_token) return res.status(400).json({ error: 'refresh_token requerido' });
  if (!supabase) return res.status(503).json({ error: 'Auth no disponible' });
  try {
    const { data, error } = await supabase.auth.refreshSession({ refresh_token });
    if (error || !data?.session) return res.status(401).json({ error: 'Sesión expirada. Inicia sesión de nuevo.' });
    res.json({ session: { access_token: data.session.access_token, refresh_token: data.session.refresh_token, expires_at: data.session.expires_at } });
  } catch (e) { res.status(500).json({ error: 'Error renovando sesión' }); }
});

// ── GOOGLE OAUTH — login con credenciales de Google ──────────────
// Flujo redirect con callback en el backend (PKCE gestionado aquí):
//   1) POST /api/auth/google          → guarda el code_verifier en una cookie
//      httpOnly y devuelve la URL de authorize (Google vía Supabase).
//      IMPORTANTE: NO se pasa un state propio — Supabase usa ese
//      parámetro para su validación interna (bad_oauth_state).
//   2) El navegador vuelve a  GET /api/auth/google/callback?code=...
//   3) El backend intercambia el code por una sesión (cookie → verifier)
//      y redirige al frontend con  /?auth=google&token=...  (token de
//      un solo uso, TTL 5 min)
//   4) POST /api/auth/google/session  → el frontend recupera la sesión
// Requiere en Supabase → Auth → URL Configuration → Redirect URLs:
//   https://<host-del-backend>/api/auth/google/callback
const GOOGLE_STATE_TTL = 10 * 60 * 1000; // vida útil del code PKCE (cookie)
const GOOGLE_TOKEN_TTL = 5  * 60 * 1000; // vida útil del token de sesión
const GOOGLE_PKCE_COOKIE = 'ch_google_pkce';
const googleTokens = new Map(); // token de un uso -> { user, session, expiresAt }

function base64url(buf) {
  return Buffer.from(buf).toString('base64')
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}
function frontendUrl() {
  return process.env.FRONTEND_URL?.trim() || 'https://wilson360-labs.vercel.app';
}
function supabaseUrl() {
  return process.env.SUPABASE_URL?.trim() || '';
}
function getCookie(req, name) {
  const header = req.headers.cookie || '';
  const m = header.split(';').map(s => s.trim()).find(s => s.startsWith(name + '='));
  return m ? decodeURIComponent(m.slice(name.length + 1)) : null;
}

// Limpieza periódica de tokens expirados (evita fuga de memoria)
setInterval(() => {
  const now = Date.now();
  for (const [k, v] of googleTokens) if (v.expiresAt < now) googleTokens.delete(k);
}, 15 * 60 * 1000).unref();

// GET /api/auth/google — iniciar el flujo OAuth con Google
// Se usa navegación directa (no fetch) para que la cookie se guarde en
// contexto first-party del backend y sobreviva al viaje por Google.
app.get('/api/auth/google', (req, res) => {
  if (!supabaseUrl() || !process.env.SUPABASE_KEY) return res.status(503).json({ error: 'Servidor no configurado — Supabase no está disponible' });

  const verifier  = base64url(crypto.randomBytes(48));
  const challenge = base64url(crypto.createHash('sha256').update(verifier).digest());

  // Guardar el code_verifier en una cookie httpOnly (viaja con el navegador)
  res.setHeader('Set-Cookie', `${GOOGLE_PKCE_COOKIE}=${verifier}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=${Math.floor(GOOGLE_STATE_TTL / 1000)}`);

  const callback = `${req.protocol}://${req.get('host')}/api/auth/google/callback`;
  const url = `${supabaseUrl()}/auth/v1/authorize?` + new URLSearchParams({
    provider: 'google',
    redirect_to: callback,
    code_challenge: challenge,
    code_challenge_method: 'S256',
    prompt: 'select_account',
  }).toString();

  res.redirect(url);
});

// GET /api/auth/google/callback — Supabase vuelve aquí tras autorizar en Google
app.get('/api/auth/google/callback', async (req, res) => {
  const { code, error } = req.query;
  const FRONT = frontendUrl();
  const fail  = msg => {
    res.setHeader('Set-Cookie', `${GOOGLE_PKCE_COOKIE}=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0`);
    res.redirect(`${FRONT}/?auth=google&error=${encodeURIComponent(msg)}`);
  };
  if (error) return fail(String(error));
  if (!code) return fail('missing_code');

  const verifier = getCookie(req, GOOGLE_PKCE_COOKIE);
  if (!verifier) return fail('missing_verifier');

  // Intercambiar el code por una sesión usando el code_verifier de la cookie
  const tokenUrl = `${supabaseUrl()}/auth/v1/token?grant_type=pkce`;
  let resp;
  try {
    resp = await fetch(tokenUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        apikey: process.env.SUPABASE_KEY,
        Authorization: `Bearer ${process.env.SUPABASE_KEY}`,
      },
      body: JSON.stringify({ auth_code: String(code), code_verifier: verifier }),
    });
  } catch (e) {
    console.error('Google token exchange error:', e.message);
    return fail('token_exchange_failed');
  }
  const payload = await resp.json().catch(() => ({}));
  if (!resp.ok || !payload.access_token || !payload.user) {
    console.error('Google token exchange rejected:', payload.error_description || payload.error);
    return fail(payload.error_description || payload.error || 'token_exchange_failed');
  }

  const user = payload.user;
  const name = user.user_metadata?.full_name || user.user_metadata?.name || '';
  const oneTime = base64url(crypto.randomBytes(24));
  googleTokens.set(oneTime, {
    user: { id: user.id, email: user.email, name: name || String(user.email || '').split('@')[0] },
    session: {
      access_token: payload.access_token,
      refresh_token: payload.refresh_token || null,
      expires_at: payload.expires_at || null,
      expires_in: payload.expires_in || null,
    },
    expiresAt: Date.now() + GOOGLE_TOKEN_TTL,
  });

  // Borrar la cookie y redirigir al frontend con el token de un solo uso
  res.setHeader('Set-Cookie', `${GOOGLE_PKCE_COOKIE}=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0`);
  res.redirect(`${FRONT}/?auth=google&token=${oneTime}`);
});

// POST /api/auth/google/session — el frontend recupera la sesión (token de un solo uso)
app.post('/api/auth/google/session', (req, res) => {
  const token = String(req.body?.token || '');
  if (!token) return res.status(400).json({ error: 'Falta el token' });
  const entry = googleTokens.get(token);
  googleTokens.delete(token); // un solo uso
  if (!entry || entry.expiresAt < Date.now()) return res.status(401).json({ error: 'Sesión de Google no válida o expirada' });
  res.status(200).json({ ok: true, user: entry.user, session: entry.session });
});

// ── TELEGRAM STORAGE ─────────────────────────────────────────
// APKs se almacenan en el chat personal del bot con el admin.
// Variables Render: TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID
const TG_TOKEN   = process.env.TELEGRAM_BOT_TOKEN;
const TG_CHAT_ID = process.env.TELEGRAM_CHAT_ID;

// ── TELEGRAM ALERTS ─────────────────────────────────────────
// Empuja en tiempo real al chat del admin: seguridad (rate-limit,
// intentos fallidos de admin, errores) y actividad (descargas,
// contactos, ratings, solicitudes, apps nuevas) + resumen periódico.
// Variables: TG_ALERTS_ENABLED (default 'true'),
//            TG_BURST_MS     (agrupar eventos, default 4000ms),
//            TG_STATUS_HOURS (resumen periódico, default 6h).
const TG_ALERTS_ENABLED = process.env.TG_ALERTS_ENABLED !== 'false';
const TG_BURST_MS       = Math.max(500, parseInt(process.env.TG_BURST_MS || '4000', 10));
const TG_STATUS_HOURS   = Math.max(1, parseInt(process.env.TG_STATUS_HOURS || '6', 10) || 6);
const tgBurst = new Map(); // type -> { count, timer }

function tgSend(text) {
  if (!TG_TOKEN || !TG_CHAT_ID) return Promise.resolve(false);
  const https = require('https');
  const body = JSON.stringify({ chat_id: TG_CHAT_ID, text, disable_web_page_preview: true, parse_mode: 'HTML' });
  return new Promise(resolve => {
    const req = https.request({
      hostname: 'api.telegram.org',
      path: `/bot${TG_TOKEN}/sendMessage`,
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
    }, res => { res.resume(); res.on('end', () => resolve(res.statusCode === 200)); });
    req.on('error', () => resolve(false));
    req.setTimeout(8000, () => { req.destroy(); resolve(false); });
    req.write(body); req.end();
  });
}

// Agrupa eventos iguales en una ventana corta para no spamear.
// Si llegan 50 rate-limits en 3s, manda UN solo mensaje con el conteo.
function tgAlert(type, text, opts = {}) {
  if (!TG_ALERTS_ENABLED || !TG_TOKEN || !TG_CHAT_ID) return;
  const windowMs = opts.windowMs || TG_BURST_MS;
  const cur = tgBurst.get(type);
  if (cur) { cur.count++; return; }
  const entry = { count: 1 };
  tgBurst.set(type, entry);
  entry.timer = setTimeout(() => {
    tgBurst.delete(type);
    const body = typeof text === 'function' ? text(entry.count) : text;
    tgSend(body + (entry.count > 1 ? `\n🔁 x${entry.count} en ${Math.round(windowMs / 1000)}s` : ''));
  }, windowMs);
}

function clientIp(req) {
  return String(
    req.headers['x-real-ip'] ||
    (req.headers['x-forwarded-for'] || '').split(',')[0].trim() ||
    req.socket?.remoteAddress ||
    req.ip || '?'
  ).replace(/^::ffff:/, '').trim();
}

// Handler de express-rate-limit: avisa al admin cuando alguien excede
// el límite (posible abuso/bot) sin bloquear la respuesta HTTP.
function rateLimitHandler(req, res, _next, options) {
  const ip = clientIp(req);
  const route = (req.originalUrl || req.path || '').split('?')[0];
  const ua = (req.headers['user-agent'] || '').slice(0, 70).replace(/[<>]/g, '');
  tgAlert('ratelimit:' + route, n =>
    `🚨 <b>RATE LIMIT</b>\n<code>${route}</code>\nIP: <code>${ip}</code>\nUA: ${ua}\nBloqueos: ${n}`,
    { windowMs: 30000 });
  res.status(options.statusCode || 429).json(options.message || { error: 'Demasiadas solicitudes.', code: 'RATE_LIMIT' });
}

// Resumen periódico de estado de la web (por defecto cada 6h).
async function tgStatusReport() {
  if (!TG_ALERTS_ENABLED || !TG_TOKEN || !TG_CHAT_ID) return;
  const up = Math.floor(process.uptime());
  const dd = Math.floor(up / 86400), hh = Math.floor((up % 86400) / 3600), mm = Math.floor((up % 3600) / 60);
  const today = new Date().toISOString().slice(0, 10);
  let daily = null;
  if (supabase) {
    try {
      const { data } = await supabase.from('daily_stats').select('*').eq('date', today).single();
      daily = data;
    } catch {}
  }
  const msg =
    `📊 <b>CodeHub — Estado</b>\n` +
    `Uptime: ${dd}d ${hh}h ${mm}m · Mongo: ${dbConnected ? '✅' : '❌'} · Redis: ${redis ? '✅' : 'memoria'}\n` +
    `WS: ${wsClients.size} clientes\n\n` +
    `👁️ Visitas hoy: ${visits.today} (total ${visits.total})\n` +
    (daily
      ? `⬇️ Descargas: ${daily.downloads || 0}\n💬 Chats: ${daily.chat_msgs || 0}\n🛠️ Tools: ${daily.tool_uses || 0}\n📩 Contactos: ${daily.contacts || 0}`
      : 'Stats diarias: sin datos (Supabase no configurado)') +
    `\n\nhttps://wilson360-labs.vercel.app`;
  await tgSend(msg);
}

if (TG_ALERTS_ENABLED) {
  setTimeout(() => {
    tgStatusReport();
    setInterval(tgStatusReport, TG_STATUS_HOURS * 3600 * 1000);
  }, 20000);
}

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
QR Generator, Generador de contraseñas seguras (criptografía real), Hash SHA-256/SHA-512, Base64 encode/decode, UUID v4, Regex Tester, Temporizador Pomodoro, Conversor de unidades, Conversor de monedas, Calculadora IMC, Calculadora de préstamos, Test de velocidad de escritura, Paleta de colores, Generador de gradientes CSS, Minificador de código, PDF IA, OCR IA, Generador de Imágenes IA, y 35+ herramientas en total.

**Catálogo Open Source en /opensource:**
Aplicaciones de código abierto verificadas contra su repositorio oficial de GitHub (NewPipe, LibreTube, Seal, y más) — sin versiones modificadas.

**Otros en CodeHub:**
- Juegos: Snake y Tetris (Canvas API)
- Servicios freelance detallados en /servicios
- EMI COPILOT — asistente IA integrada (¡soy yo!)
- App Android (APK) disponible para descarga desde la web
- PWA con modo offline y notificaciones push
- Clima en tiempo real widget integrado

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

━━━ SKILL: TRADUCTOR ━━━
Cuando el usuario pida traducir texto:
- Detecta el idioma de origen automáticamente
- Si no especifica destino, traduce al español si está en otro idioma, o al inglés si está en español
- Preserva formato (markdown, código, listas)
- Usa traducción natural, no literal — adapta expresiones idiomáticas

━━━ SKILL: EXPLICAR CÓDIGO ━━━
Cuando el usuario pida explicar código:
- Explica como si fuera para un principiante, con analogías cotidianas
- Línea por línea o bloque por bloque
- Identifica qué hace cada parte, por qué se usa, qué pasaría si se cambia
- Termina con 2-3 bullets de lo más importante

━━━ SKILL: GENERADOR DE TESTS ━━━
Cuando el usuario pida tests o pruebas:
- Detecta lenguaje y framework automáticamente
- Genera tests unitarios con happy-path, edge-cases y errores
- Frameworks: Jest/Vitest (JS), pytest (Python), JUnit (Java)
- Incluye mocking si hay dependencias externas
- Termina con instrucciones para ejecutar

━━━ SKILL: RESUMEN IA ━━━
Cuando el usuario pida resumir contenido:
- Prioriza: ideas principales → datos concretos → detalles secundarios
- Usa bullets/listas
- Preserva datos numéricos, fechas y nombres importantes
- Si pide extensión específica, respétala

━━━ SKILL: COMANDOS DEL CHAT ━━━
El usuario puede usar comandos slash en el chat:
- /help — Lista de comandos
- /img <desc> — Generar imagen
- /debug <código> — Depurar código
- /review <código> — Code review
- /readme — Generar README
- /translate <texto> — Traducir
- /explain <código> — Explicar código
- /test <código> — Generar tests
- /resumen <texto> — Resumir contenido
- /clear — Limpiar chat
- /skills — Skills disponibles
- /model — Modelo activo

━━━ TEMAS GENERALES ━━━
Puedes responder sobre cualquier tema: ciencias, historia, matemáticas, idiomas, cultura, entretenimiento, recetas, viajes, finanzas, emprendimiento, productividad, y todo lo demás. Eres una IA de propósito amplio con raíces en el mundo del desarrollo web.

━━━ SEGURIDAD Y CONTENIDO ━━━
- NO proporciones instrucciones para piratear software, cracks, keygens, activadores no oficiales ni violación de licencias
- NO ayudes a descargar contenido protegido por derechos de autor de forma ilegal (películas, música, ebooks pirateados)
- NO proporciones instrucciones para hackear, phishing, ataques DDoS, explotar vulnerabilidades en sistemas ajenos
- NO generes contenido sexual explícito, gore extremo ni material que promueva violencia
- NO des consejos médicos, legales ni financieros como profesional certificado — aclara que eres una IA y sugiere consultar a un profesional
- Si te piden algo de lo anterior, responde amablemente que no puedes ayudar con eso y sugiere alternativas legítimas
- Para temas de seguridad informática, enfócate en educación defensiva (protección, buenas prácticas) y nunca en ofensiva

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
    body: JSON.stringify({ model: 'llama-3.3-70b-versatile', max_tokens: 1500, temperature: 0.65, messages: msgs }),
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
    body: JSON.stringify({ model: 'llama-3.3-70b', max_tokens: 1500, temperature: 0.65, messages: msgs }),
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
    body: JSON.stringify({ model: 'meta-llama/Llama-3.3-70B-Instruct:novita', max_tokens: 1500, temperature: 0.65, messages: msgs }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.error?.message || `HuggingFace ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.choices[0]?.message?.content || '', input: d.usage?.prompt_tokens||0, output: d.usage?.completion_tokens||0, model: 'huggingface/llama-3.3-70b' };
}

async function callGemini(msgs, imageParts) {
  const sysMsg = msgs.find(m => m.role === 'system');
  const contents = msgs.filter(m => m.role !== 'system').map(m => ({ role: m.role === 'assistant' ? 'model' : 'user', parts: [{ text: m.content }] }));
  // Imágenes adjuntas (imagen simple, o varias páginas de un PDF escaneado): se
  // agregan como partes inline del último turno del usuario.
  const parts = Array.isArray(imageParts) ? imageParts : (imageParts ? [imageParts] : []);
  if (parts.length && contents.length) {
    for (const p of parts) {
      if (p && p.data) contents[contents.length - 1].parts.push({ inline_data: { mime_type: p.mimeType, data: p.data } });
    }
  }
  const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=${process.env.GEMINI_API_KEY}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ systemInstruction: { parts: [{ text: sysMsg ? sysMsg.content : SYSTEM }] }, contents, generationConfig: { maxOutputTokens: 1500, temperature: 0.7 } }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.error?.message || `Gemini ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.candidates?.[0]?.content?.parts?.[0]?.text || '', input: d.usageMetadata?.promptTokenCount||0, output: d.usageMetadata?.candidatesTokenCount||0, model: parts.length ? 'gemini-1.5-flash-vision' : 'gemini-1.5-flash' };
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
      max_tokens: 1500,
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
    body: JSON.stringify({ model: 'mistral-small-latest', max_tokens: 1500, temperature: 0.65, messages: mistralMsgs }),
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
    body: JSON.stringify({ model: 'command-r', message: lastMsg, chat_history: chatHistory, preamble: system, max_tokens: 1500, temperature: 0.65 }),
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
      max_tokens: 1500,
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
    body: JSON.stringify({ model: 'kimi-k2-0905-preview', max_tokens: 1500, temperature: 0.65, messages: msgs }),
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
  if (!process.env.TURNSTILE_SECRET) {
    console.warn('⚠️ TURNSTILE_SECRET no configurado — rechazando request (fail-closed)');
    return false;
  }
  if (!token) return false;
  try {
    const r = await fetch('https://challenges.cloudflare.com/turnstile/v0/siteverify', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ secret: process.env.TURNSTILE_SECRET, response: token }),
    });
    return (await r.json()).success === true;
  } catch { return false; }
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

// ── DB RUNNER — aplica un .sql (Supabase) o un esquema .json ─────
// (MongoDB) subido desde admin-hub.html. Requiere ADMIN_KEY.
//
// Supabase: ejecuta SQL crudo vía la función `exec_sql` (debe existir
// en la base — ver bootstrap_exec_sql.sql, se crea UNA vez a mano
// desde el SQL Editor de Supabase, ya que hace falta para poder
// correr SQL arbitrario desde el cliente JS).
//
// Mongo: espera { "collections": [ { "name": "...", "indexes": [{ "keys": {...}, "options": {...} }] } ] }
app.post('/api/admin/db/run', requireAdmin, async (req, res) => {
  const { target, content } = req.body || {};
  if (!target || !content) return res.status(400).json({ ok: false, error: 'Falta target o content' });

  if (target === 'supabase') {
    if (!supabase) return res.status(503).json({ ok: false, error: 'Supabase no configurado' });
    const statements = splitSqlStatements(content);
    if (!statements.length) return res.status(400).json({ ok: false, error: 'El archivo no tiene sentencias SQL' });
    const results = [];
    for (const stmt of statements) {
      try {
        const { error } = await supabase.rpc('exec_sql', { query: stmt });
        if (error) throw error;
        results.push({ ok: true, stmt: stmt.slice(0, 90).replace(/\s+/g, ' ') });
      } catch (e) {
        results.push({ ok: false, stmt: stmt.slice(0, 90).replace(/\s+/g, ' '), error: e.message });
      }
    }
    const failed = results.filter(r => !r.ok).length;
    return res.json({ ok: failed === 0, results, failed, total: results.length });
  }

  if (target === 'mongo') {
    if (!dbConnected) return res.status(503).json({ ok: false, error: 'MongoDB no disponible' });
    let schema;
    try { schema = JSON.parse(content); } catch { return res.status(400).json({ ok: false, error: 'JSON inválido' }); }
    const collections = Array.isArray(schema) ? schema : schema.collections;
    if (!Array.isArray(collections)) {
      return res.status(400).json({ ok: false, error: 'Formato esperado: { "collections": [ { "name": "...", "indexes": [...] } ] }' });
    }
    const results = [];
    for (const col of collections) {
      if (!col?.name) { results.push({ ok: false, collection: '(sin nombre)', error: 'Falta "name"' }); continue; }
      try {
        await mongoose.connection.db.createCollection(col.name).catch(e => {
          if (!/already exists/i.test(e.message)) throw e;
        });
        if (Array.isArray(col.indexes)) {
          for (const idx of col.indexes) {
            await mongoose.connection.db.collection(col.name).createIndex(idx.keys, idx.options || {});
          }
        }
        results.push({ ok: true, collection: col.name });
      } catch (e) {
        results.push({ ok: false, collection: col.name, error: e.message });
      }
    }
    const failed = results.filter(r => !r.ok).length;
    return res.json({ ok: failed === 0, results, failed, total: results.length });
  }

  res.status(400).json({ ok: false, error: 'target debe ser "supabase" o "mongo"' });
});

// Health
app.get('/api/health', (_, res) => res.json({
  status: 'ok', version: '3.2',
  mongo:     dbConnected ? 'connected' : 'disconnected',
  redis:     redis       ? 'connected' : 'memory',
  ws:        wsClients.size + ' clients',
  push_web:  (process.env.VAPID_PUBLIC_KEY && process.env.VAPID_PRIVATE_KEY) ? 'ok (VAPID propia)' : 'ok (VAPID de ejemplo — configura VAPID_PUBLIC_KEY/VAPID_PRIVATE_KEY)',
  push_android: fcmEnabled ? 'ok (FCM habilitado)' : 'missing (configura FIREBASE_SERVICE_ACCOUNT)',
  render_keepalive: process.env.RENDER_EXTERNAL_URL ? 'ok' : 'missing (configura RENDER_EXTERNAL_URL para que Render no duerma el servicio)',
  github_webhook_secret: process.env.GITHUB_WEBHOOK_SECRET ? 'ok' : 'missing (configura GITHUB_WEBHOOK_SECRET para notificaciones instantáneas de nuevas versiones)',
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
// Responde 201 de inmediato; el enriquecimiento geo-IP y el guardado
// se hacen en segundo plano para no bloquear la petición hasta 12s.
app.post('/api/visit', (req, res) => {
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

    // Capturamos lo necesario del request antes de ir a background
    const page = String(req.body?.page || '/').slice(0, 200);
    const ua   = (req.headers['user-agent'] || '').slice(0, 300);

    res.status(201).json({ ok: true, ip: finalIp });

    // ── Background: geo-IP + guardado en Supabase ──
    (async () => {
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
        page,
        ua,
        visited_at:   new Date().toISOString(),
      };

      try {
        if (supabase) await supabase.from('visitor_logs').insert(record);
      } catch (e3) { console.warn('visitor_logs insert error:', e3.message); }

      trackVisit();
    })().catch(e => console.warn('visit background error:', e.message));
  } catch (e) {
    console.warn('visit error:', e.message);
    try { res.status(201).json({ ok: false }); } catch (_) {}
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
  url: process.env.WS_URL || 'wss://codehub-98s6.onrender.com/ws',
}));

// Apps públicas (con caché 5 min)
app.get('/api/apps', async (_, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible', apps: [] });
  try {
    const cached = await cacheGet('apps:all');
    if (cached) { res.set('X-Cache', 'HIT'); return res.json(cached); }

    const apps = await App.find({}).sort({ createdAt: 1 }).lean();
    const base  = process.env.BACKEND_URL || 'https://codehub-98s6.onrender.com';
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
      packageName:  a.packageName || null,
      updatedAt:    a.updatedAt,
    }));
    const result = { apps: mapped, total: mapped.length };
    await cacheSet('apps:all', result, 300);
    res.set('X-Cache', 'MISS'); res.json(result);
  } catch { res.status(500).json({ error: 'Error obteniendo apps' }); }
});

// ── App Updates — verificar versiones desde GitHub Releases ──
// POST /api/app-updates  { apps: [{ appId, version, source_repo }] }
// Retorna: [{ appId, currentVersion, latestVersion, hasUpdate, downloadUrl }]
app.post('/api/app-updates', async (req, res) => {
  try {
    const { apps } = req.body;
    if (!Array.isArray(apps) || apps.length === 0) return res.json([]);
    const results = await Promise.allSettled(apps.map(async (app) => {
      if (!app.source_repo) return { appId: app.appId, currentVersion: app.version, latestVersion: app.version, hasUpdate: false, downloadUrl: null };
      const cacheKey = `update:${app.source_repo}`;
      const cached = await cacheGet(cacheKey);
      if (cached) return { appId: app.appId, currentVersion: app.version, ...cached };
      const ghRes = await fetch(`https://api.github.com/repos/${app.source_repo}/releases/latest`, {
        headers: { 'User-Agent': 'CodeHub-Catalog', 'Accept': 'application/vnd.github.v3+json' },
        signal: AbortSignal.timeout(5000)
      });
      if (!ghRes.ok) return { appId: app.appId, currentVersion: app.version, latestVersion: app.version, hasUpdate: false, downloadUrl: null };
      const release = await ghRes.json();
      const latest = (release.tag_name || '').replace(/^v/i, '');
      const current = (app.version || '').replace(/^v/i, '');
      const apkAsset = (release.assets || []).find(a => a.name && a.name.endsWith('.apk'));
      const downloadUrl = apkAsset ? apkAsset.browser_download_url : release.html_url;
      const result = { latestVersion: release.tag_name || latest, hasUpdate: latest !== current && !!latest, downloadUrl };
      await cacheSet(cacheKey, result, 600);
      return { appId: app.appId, currentVersion: app.version, ...result };
    }));
    res.json(results.map(r => r.status === 'fulfilled' ? r.value : { appId: '?', hasUpdate: false }));
  } catch { res.status(500).json({ error: 'Error checking updates' }); }
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

// Limpia texto plano de un item RSS: decodifica entidades HTML (&lt; &gt;
// &amp; ...), quita cualquier tag sobrante y colapsa espacios. Evita que la
// tarjeta muestre código fuente crudo de la noticia.
const NEWS_ENTITIES = { '&lt;':'<', '&gt;':'>', '&amp;':'&', '&quot;':'"', '&#39;':"'", '&apos;':"'", '&nbsp;':' ' };
const cleanNewsText = (raw) => {
  let s = String(raw || '');
  s = s.replace(/&(?:#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (m, code) => {
    if (code[0] === '#') {
      const isHex = code[1] === 'x' || code[1] === 'X';
      const n = parseInt(code.slice(isHex ? 2 : 1), isHex ? 16 : 10);
      return Number.isNaN(n) ? m : String.fromCodePoint(n);
    }
    return NEWS_ENTITIES[m] !== undefined ? NEWS_ENTITIES[m] : m;
  });
  s = s.replace(/<\/?(?:script|style)[^>]*>[\s\S]*?<\/?(?:script|style)>/gi, ' ');
  s = s.replace(/<[^>]+>/g, ' ');
  return s.replace(/\s+/g, ' ').trim();
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
    const title = cleanNewsText(NEWS_TAG_RE(block, 'title')).slice(0, 160);
    const url_  = NEWS_TAG_RE(block, 'link');
    const pub   = NEWS_TAG_RE(block, 'pubDate');
    const date  = pub ? new Date(pub).toLocaleDateString('es-GT', { day: 'numeric', month: 'short', year: 'numeric' }) : '';
    const image = NEWS_IMG_RE(block);
    // Google News trae el medio original en <source>; BBC no lo trae (usamos sourceLabel fijo).
    const src   = cleanNewsText(NEWS_TAG_RE(block, 'source') || sourceLabel || '');
    let desc = cleanNewsText(NEWS_TAG_RE(block, 'description')).slice(0, 130);
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

// ── SKILLS — catálogo servido al frontend ──────────────────────
app.get('/api/skills', (req, res) => {
  try {
    const indexPath = path.join(SKILLS_DIR, 'index.json');
    if (!fs.existsSync(indexPath)) return res.json({ skills: [], total: 0 });
    const index = JSON.parse(fs.readFileSync(indexPath, 'utf8'));
    const enriched = (index.skills || [])
      .filter(s => s.active)
      .map(s => {
        const detail = loadSkillJson(s.id);
        return detail ? {
          ...s,
          presets: detail.presets,
          ui: detail.ui,
          examples: detail.examples,
          sizes: detail.sizes,
          providers: detail.providers_priority,
        } : s;
      });
    res.json({ skills: enriched, total: enriched.length });
  } catch (e) {
    res.status(500).json({ error: 'Error cargando skills', detail: e.message });
  }
});

app.get('/api/skills/:id', (req, res) => {
  const skill = loadSkillJson(req.params.id);
  if (!skill) return res.status(404).json({ error: 'Skill no encontrada' });
  res.json(skill);
});

// Chat IA
app.post('/api/chat', requireAuth, async (req, res) => {
  const { message, sessionId = 'anon', image, images, pdfText, skill_id } = req.body;
  if (!message || typeof message !== 'string') return res.status(400).json({ error: '"message" requerido.' });
  if (message.trim().length > 1000) return res.status(400).json({ error: 'Mensaje muy largo.' });
  if (!process.env.GROQ_API_KEY && !process.env.GEMINI_API_KEY) return res.status(503).json({ error: 'Sin API keys.' });

  // ── Límite diario server-side ─────────────────────────────────
  const emiKey = req.authUser ? 'u:' + req.authUser.id : 'd:' + clientIp(req);
  const emiLimit = req.authUser ? EMI_DAILY_LIMIT_REGISTERED : EMI_DAILY_LIMIT_GUEST;
  const emiUsed = getEmiUsage(emiKey);
  if (emiUsed >= emiLimit) {
    return res.status(429).json({ error: `Límite diario alcanzado (${emiLimit} mensajes). ${req.authUser ? '' : 'Inicia sesión para más.'}`, code: 'EMI_DAILY_LIMIT', limit: emiLimit, used: emiUsed });
  }

  // ── Imagen / PDF escaneado adjunto: valida formato/tamaño antes de gastar una llamada ──
  // "image" es una imagen suelta (data URL). "images" es un array de páginas
  // renderizadas (PDF escaneado). Ambos van a Gemini Vision.
  let imageParts = null;
  const imgList = image ? [image] : (Array.isArray(images) && images.length ? images.slice(0, 5) : null);
  if (imgList && imgList.length) {
    imageParts = [];
    for (const u of imgList) {
      const p = parseImageDataUrl(u);
      if (!p) return res.status(400).json({ error: 'Imagen inválida o demasiado pesada (máx. ~4MB c/u, png/jpeg/webp/gif).' });
      imageParts.push(p);
    }
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
  // PDF adjunto: el texto comprimido se inyecta como contexto antes de la pregunta
  // del usuario (aplica a cualquier proveedor de texto, no solo Gemini).
  if (typeof pdfText === 'string' && pdfText.trim()) {
    sessionHistory.splice(sessionHistory.length - 1, 0, {
      role: 'user',
      content: '[Documento adjunto — resumen comprimido del documento. Responde usando SOLO este contenido como referencia, en español]:\n' + pdfText.slice(0, 40000)
    });
  }
  let system = SYSTEM;
  // Skill activa: inyecta su guía (system_prompt_inject) para que EMI delegue
  // en procesamiento local / use el contexto correcto sin gastar tokens de más.
  if (skill_id) {
    const skill = loadSkillJson(String(skill_id));
    if (skill && skill.system_prompt_inject) {
      system = skill.system_prompt_inject + '\n\n' + system;
    }
  }
  const msgs = [{ role: 'system', content: system }, ...sessionHistory];

  try {
    // Con imagen/PDF escaneado: va directo a Gemini (único proveedor con visión
    // en esta cadena). Sin imagen: sigue el fallback normal Claude→Groq→...→Cohere.
    const { reply, input, output, model } = imageParts
      ? await callGemini(msgs, imageParts)
      : await callAI(msgs);
    if (dbConnected) ChatMessage.insertMany([
      { sessionId, role: 'user',      content: message.trim() + (imageParts ? ' [imagen adjunta]' : '') + (pdfText ? ' [PDF adjunto]' : ''), tokens: input,  model },
      { sessionId, role: 'assistant', content: reply,          tokens: output, model },
    ]).catch(() => {});
    broadcast('chat_used', { model, tokens: input + output });
    trackEvent('chat', null, { model, tokens: input + output });
    tgAlert('chat', () => `💬 <b>Chat con EMI</b>\n${String(message || '').slice(0, 60).replace(/[<>]/g, '')}\n🧠 ${model}\n🌐 ${clientIp(req)}`, { windowMs: 30000 });
    const emiNow = incrEmiUsage(emiKey);
    res.json({ reply, usage: { input, output, total: input + output }, model, emi: { used: emiNow, limit: emiLimit } });
  } catch (err) {
    tgAlert('chatfail', () =>
      `⚠️ <b>Error en /api/chat</b>\n${err && (err.message || err.status) ? String(err.message || err.status).slice(0, 120) : 'desconocido'}`,
      { windowMs: 30000 });
    if (err.status === 401) return res.status(500).json({ error: imageParts ? 'Gemini: API key inválida.' : 'API key inválida.' });
    if (err.status === 429) return res.status(429).json({ error: 'Límite alcanzado.' });
    if (imageParts) return res.status(500).json({ error: 'No pude analizar la imagen. Intenta de nuevo.' });
    res.status(500).json({ error: 'Error interno.' });
  }
});

// Contacto (notifica vía WS)
app.post('/api/contact', (req, res) => {
  const { name, email, message } = req.body;
  trackEvent('contact');
  tgAlert('contact', () => {
    const ip = clientIp(req);
    return `📩 <b>Nuevo contacto</b>\n👤 ${String(name || 'Anónimo').slice(0, 30)}\n📧 ${email ? email.replace(/(.{2}).*(@.*)/, '$1***$2') : '?'}\n💬 ${String(message || '').slice(0, 80)}\n🌐 ${ip}`;
  }, { windowMs: 30000 });
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
    // findOneAndUpdate + upsert es atómico — evita la condición de carrera
    // que había con "findOne → new AppRating() → save()": si dos votos
    // llegaban casi al mismo tiempo, ambos podían pasar el findOne antes
    // de que el primero guardara, creando 2 documentos con el mismo appId.
    let r = await AppRating.findOneAndUpdate(
      { appId },
      { $setOnInsert: { appId, appName: appName || appId, ratings: [], total: 0, count: 0 } },
      { upsert: true, new: true }
    );
    const already = r.ratings.find(x => x.ip === ip);
    if (already) return res.status(409).json({ error: 'Ya votaste', avg: r.count > 0 ? Math.round((r.total/r.count)*10)/10 : 0, count: r.count });
    r.ratings.push({ ip, stars }); r.total += stars; r.count += 1;
    await r.save(); await cacheDel('ratings:all');
    const avg = Math.round((r.total / r.count) * 10) / 10;
    broadcast('new_rating', { appId, appName: appName || appId, stars, avg, count: r.count });
    tgAlert('rating', () => `⭐ <b>Rating nuevo</b>: ${stars}★ — ${String(appName || appId).slice(0, 40)} (avg ${avg}, ${r.count} votos)`, { windowMs: 30000 });
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
    await newReq.save();
    tgAlert('appreq', () => `🙋 <b>Solicitud de app</b>\n📱 ${String(appName.trim()).slice(0, 40)}\n💬 ${String(reason || '').trim().slice(0, 80) || 'sin motivo'}`, { windowMs: 30000 });
    res.json({ ok: true, message: 'Solicitud enviada', id: newReq._id });
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
    tgAlert('download', () => `⬇️ <b>Descarga</b>: ${decodeURIComponent(fileName)}`, { windowMs: 15000 });
    res.redirect(302, data.publicUrl);
  } catch (e) { console.error('Error download:', e.message); res.status(500).json({ error: 'No se pudo generar el link.' }); }
});

// Download indirecta por appId — pensada para el catálogo Open Source.
// El HTML público solo expone el appId (nunca el enlace real de GitHub
// Releases); este endpoint resuelve el enlace actual en MongoDB y hace
// un redirect 302. Ventajas: se puede cambiar el destino (nueva versión,
// mirror, etc.) sin tocar el frontend, y queda trackeado igual que las
// descargas Premium. OJO: esto no es "seguridad" — cualquiera puede ver
// la URL final en la pestaña Network del navegador tras el redirect,
// solo evita que quede pegada en el HTML/código fuente de la página.
app.get('/api/dl/:appId', async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  const { appId } = req.params;
  try {
    const app_ = await App.findOne({ appId }).select('enlace nombre').lean();
    if (!app_ || !app_.enlace || app_.enlace === '#') return res.status(404).json({ error: 'Enlace no disponible aún' });
    broadcast('download', { fileName: app_.nombre });
    trackEvent('download', null, { app_name: app_.nombre, appId });
    tgAlert('download', () => `⬇️ <b>Descarga</b>: ${app_.nombre}`, { windowMs: 15000 });
    res.redirect(302, app_.enlace);
  } catch (e) { console.error('Error /api/dl:', e.message); res.status(500).json({ error: 'No se pudo generar el link.' }); }
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
    const { appId, nombre, descripcion, version, tag, changelog, imagen, categoria, verified, enlace, plugin_enlace, source_repo, packageName } = req.body;
    if (!appId || !nombre) return res.status(400).json({ error: 'appId y nombre son requeridos' });
    if (await App.findOne({ appId })) return res.status(409).json({ error: 'Ya existe una app con ese appId' });
    const a = await App.create({ appId, nombre, descripcion, version, tag: tag || '🆕', changelog, imagen: normalizeImagePath(imagen), categoria, verified: verified !== false, enlace: enlace || '#', plugin_enlace: plugin_enlace || null, source_repo: source_repo || null, packageName: packageName || null });
    await cacheDel('apps:all');
    broadcast('new_app', { appId, nombre, tag: tag || '🆕', categoria });
    broadcastAppsChanged();
    tgAlert('adminapp', () => `➕ <b>App publicada</b>\n📱 ${String(nombre).slice(0, 40)} (<code>${appId}</code>)\n🏷️ ${categoria || 'sin categoría'}`, { windowMs: 30000 });
    // Notificación automática: nueva app open source en el catálogo
    if (a.source_repo) {
      try {
        const r = await broadcastPush({
          title: '🆕 Nueva app open source: ' + a.nombre,
          body: (a.descripcion ? String(a.descripcion).slice(0, 120) : 'Ya disponible en el catálogo open source de CodeHub'),
          type: 'app_update',
          appId: a.appId,
          version: a.version || '',
          url: '/opensource.html',
        });
        if (r.sent) console.log('📲 Push nueva app open source:', r.sent);
      } catch (e) { console.warn('Push nueva app open source error:', e.message); }
    }
    res.json({ ok: true, app: a });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.patch('/api/admin/apps/:appId', requireAdmin, async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    const update = {};
    ['nombre','descripcion','version','tag','changelog','imagen','categoria','verified','enlace','plugin_enlace','tutorial_url','source_repo','packageName']
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
    await cacheDel('apps:all'); broadcastAppsChanged(); res.json({ ok: true, app: a });
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
    broadcastAppsChanged();
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
        if (imagen) {
          // Guarda anti-revert: si el seed trae la portada social del repo
          // (opengraph) y la DB ya tiene un logo real local (/img/...), se
          // conserva el logo local.
          const seedIsPortada = /opengraph\.githubassets\.com/i.test(imagen);
          const prevIsLocal   = /^\/img\//.test(exists.imagen || '');
          if (!(seedIsPortada && prevIsLocal)) set.imagen = imagen;
        }
        // Idem para `source_repo` — solo se pisa si el seed lo trae,
        // para no desactivar el monitor de una app ya vinculada.
        if (a.source_repo) set.source_repo = a.source_repo;
        // packageName resuelto por resolve-package-names.js — solo se pisa
        // si el seed trae uno, para no borrar uno ya resuelto a mano.
        if (a.packageName) set.packageName = a.packageName;
        await App.updateOne({ appId: id }, { $set: set });
        updated++;
      } else {
        await App.create({ appId: id, nombre: a.nombre||a.name, descripcion: a.descripcion||'', version: a.version_conocida||a.ver||'', tag: a.tag||'🆕', changelog: a.changelog||'', imagen, categoria: a.categoria||a.cat||'', verified: a.verified!==false, enlace: a.enlace||'#', plugin_enlace: a.plugin_enlace||null, source_repo: a.source_repo||null, packageName: a.packageName||null });
        created++;
      }
    }
    await cacheDel('apps:all');
    broadcastAppsChanged();
    res.json({ ok: true, created, updated });
  } catch (e) { res.status(500).json({ error: e.message }); }
});


// ── POST /api/generate-image — Generador IA con 4 proveedores ─
app.post('/api/generate-image', imageLimiter, async (req, res) => {
  const { prompt, width = 512, height = 512, provider = 'auto', skill_id = null, preset_id = null } = req.body;
  if (!prompt || typeof prompt !== 'string' || prompt.trim().length < 2) {
    return res.status(400).json({ error: 'Prompt requerido' });
  }

  let p = prompt.trim().slice(0, 500);
  let w = Math.min(Math.max(parseInt(width)  || 512, 256), 1024);
  let h = Math.min(Math.max(parseInt(height) || 512, 256), 1024);
  const errors = [];

  // ── Skill + preset: inyecta el prompt_suffix y el tamaño recomendado ──
  if (skill_id && preset_id) {
    const skill = loadSkillJson(String(skill_id));
    const preset = skill && (skill.presets || []).find(x => x.id === preset_id);
    if (preset) {
      p = `${p}, ${preset.prompt_suffix}`.slice(0, 700);
      if (preset.recommended_size && !req.body.width) {
        const [pw, ph] = String(preset.recommended_size).split('x').map(Number);
        if (pw && ph) { w = pw; h = ph; }
      }
    }
  }

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
  // NOTA: Solo funciona con proyecto allowlistado. Deshabilitado en auto
  // para no sumar 5s+ de timeout muerto a cada request. Se puede invocar
  // explícitamente con provider='gemini'.
  if (process.env.GEMINI_API_KEY && provider === 'gemini') {
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
      const r = await fetch(polUrl, { signal: AbortSignal.timeout(15000) });
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
    .swagger-ui .topbar { background: #2f80ed; }
    .swagger-ui .topbar .download-url-wrapper { display: none; }
    .swagger-ui .info .title { color: #2f80ed; }
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

// ── ADMIN: WORKFLOWS DE GITHUB (Automatización) ──────────────
// Permite que el panel admin (admin-hub) dispare los workflows de
// mantenimiento del repositorio (seed del catálogo FOSS, monitor de
// actualizaciones, dedupe) sin salir de la UI.
// Requiere GITHUB_TOKEN con permiso `workflow` en Render.
const GITHUB_WORKFLOWS = [
  'seed-foss-catalog.yml',
  'check-app-updates.yml',
  'dedupe-catalog.yml',
  'enrich-app-logos.yml',
  'build-apk.yml',
];

// POST /api/admin/github/dispatch — body: { workflow, inputs }
app.post('/api/admin/github/dispatch', requireAdmin, async (req, res) => {
  try {
    const { workflow, inputs = {} } = req.body || {};
    if (!workflow || !GITHUB_WORKFLOWS.includes(workflow)) {
      return res.status(400).json({ error: 'Workflow no permitido', allowed: GITHUB_WORKFLOWS });
    }
    if (!octokit) {
      return res.status(503).json({ error: 'GITHUB_TOKEN no configurado en Render (se requiere con permiso workflow)' });
    }
    await octokit.rest.actions.createWorkflowDispatch({
      owner: GITHUB_OWNER,
      repo:  GITHUB_REPO,
      workflow_id: workflow,
      ref:   GITHUB_BRANCH,
      inputs,
    });
    tgAlert('ghdispatch', () =>
      `🚀 <b>Workflow disparado</b>\n<code>${workflow}</code>\nRef: <code>${GITHUB_BRANCH}</code>\nIP: <code>${clientIp(req)}</code>`);
    res.json({ ok: true, workflow, ref: GITHUB_BRANCH, run_url: `https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}/actions/workflows/${workflow}` });
  } catch (e) {
    const code = e?.status || 500;
    const hint = code === 403 ? ' — ¿GITHUB_TOKEN tiene permiso workflow?' : '';
    console.error('POST /api/admin/github/dispatch error:', e.message);
    res.status(code).json({ error: (e.message || 'Error disparando workflow') + hint });
  }
});

// GET /api/admin/github/runs — estado del último run de cada workflow
app.get('/api/admin/github/runs', requireAdmin, async (req, res) => {
  try {
    if (!octokit) return res.status(503).json({ error: 'GITHUB_TOKEN no configurado en Render' });
    const out = {};
    for (const wf of GITHUB_WORKFLOWS) {
      try {
        const { data } = await octokit.rest.actions.listWorkflowRuns({
          owner: GITHUB_OWNER, repo: GITHUB_REPO, workflow_id: wf, per_page: 1,
        });
        const run = data.workflow_runs?.[0] || null;
        out[wf] = run ? {
          status: run.status, conclusion: run.conclusion,
          created_at: run.created_at, html_url: run.html_url,
          display_title: run.display_title,
        } : null;
      } catch { out[wf] = null; }
    }
    res.json({ ok: true, runs: out });
  } catch (e) {
    console.error('GET /api/admin/github/runs error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// ── GITHUB SECRETS & VARIABLES ────────────────────────────────
// Gestión completa de secrets y variables del repositorio desde admin-hub.
// Solo el administrador tiene acceso (requireAdmin).

// GET /api/admin/github/secrets — listar secrets del repositorio
app.get('/api/admin/github/secrets', requireAdmin, async (req, res) => {
  try {
    if (!octokit) return res.status(503).json({ error: 'GITHUB_TOKEN no configurado' });
    const { data } = await octokit.rest.actions.listRepoSecrets({
      owner: GITHUB_OWNER, repo: GITHUB_REPO, per_page: 100,
    });
    res.json({ ok: true, secrets: data.secrets.map(s => ({ name: s.name, created_at: s.created_at, updated_at: s.updated_at })) });
  } catch (e) {
    console.error('GET /api/admin/github/secrets error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// POST /api/admin/github/secrets — crear/actualizar un secret
app.post('/api/admin/github/secrets', requireAdmin, async (req, res) => {
  try {
    if (!octokit) return res.status(503).json({ error: 'GITHUB_TOKEN no configurado' });
    const { name, value } = req.body || {};
    if (!name || !value) return res.status(400).json({ error: 'Falta name o value' });

    // Obtener la public key del repositorio
    const { data: pubKey } = await octokit.rest.actions.getRepoPublicKey({
      owner: GITHUB_OWNER, repo: GITHUB_REPO,
    });

    // Encriptar el valor con libsodium
    const sodium = require('libsodium-wrappers');
    await sodium.ready;
    const key = sodium.from_base64(pubKey.key, sodium.base64_variants.ORIGINAL);
    const encrypted = sodium.crypto_box_seal(
      sodium.from_string(value),
      key
    );
    const encryptedValue = sodium.to_base64(encrypted, sodium.base64_variants.ORIGINAL);

    // Crear o actualizar el secret
    try {
      await octokit.rest.actions.createOrUpdateRepoSecret({
        owner: GITHUB_OWNER, repo: GITHUB_REPO,
        secret_name: name, encrypted_value: encryptedValue,
        key_id: pubKey.key_id,
      });
    } catch (createErr) {
      if (createErr.status === 404) {
        // Si no existe, intentar crear
        await octokit.rest.actions.createOrUpdateRepoSecret({
          owner: GITHUB_OWNER, repo: GITHUB_REPO,
          secret_name: name, encrypted_value: encryptedValue,
          key_id: pubKey.key_id,
        });
      } else { throw createErr; }
    }

    tgAlert('ghsecret', () =>
      `🔐 <b>Secret actualizado</b>\n<code>${name}</code>\nIP: <code>${clientIp(req)}</code>`);
    res.json({ ok: true, name });
  } catch (e) {
    console.error('POST /api/admin/github/secrets error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// DELETE /api/admin/github/secrets/:name — eliminar un secret
app.delete('/api/admin/github/secrets/:name', requireAdmin, async (req, res) => {
  try {
    if (!octokit) return res.status(503).json({ error: 'GITHUB_TOKEN no configurado' });
    const { name } = req.params;
    await octokit.rest.actions.deleteRepoSecret({
      owner: GITHUB_OWNER, repo: GITHUB_REPO, secret_name: name,
    });
    tgAlert('ghsecret_del', () =>
      `🗑️ <b>Secret eliminado</b>\n<code>${name}</code>\nIP: <code>${clientIp(req)}</code>`);
    res.json({ ok: true, name });
  } catch (e) {
    console.error('DELETE /api/admin/github/secrets error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// GET /api/admin/github/variables — listar variables del repositorio
app.get('/api/admin/github/variables', requireAdmin, async (req, res) => {
  try {
    if (!octokit) return res.status(503).json({ error: 'GITHUB_TOKEN no configurado' });
    const { data } = await octokit.rest.actions.listRepoVariables({
      owner: GITHUB_OWNER, repo: GITHUB_REPO, per_page: 100,
    });
    res.json({ ok: true, variables: data.variables.map(v => ({ name: v.name, value: v.value, created_at: v.created_at, updated_at: v.updated_at })) });
  } catch (e) {
    console.error('GET /api/admin/github/variables error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// POST /api/admin/github/variables — crear/actualizar una variable
app.post('/api/admin/github/variables', requireAdmin, async (req, res) => {
  try {
    if (!octokit) return res.status(503).json({ error: 'GITHUB_TOKEN no configurado' });
    const { name, value } = req.body || {};
    if (!name || !value) return res.status(400).json({ error: 'Falta name o value' });
    try {
      await octokit.rest.actions.createRepoVariable({
        owner: GITHUB_OWNER, repo: GITHUB_REPO, name, value,
      });
    } catch (createErr) {
      if (createErr.status === 422) {
        await octokit.rest.actions.updateRepoVariable({
          owner: GITHUB_OWNER, repo: GITHUB_REPO, name, value,
        });
      } else { throw createErr; }
    }
    tgAlert('ghvar', () =>
      `⚙️ <b>Variable actualizada</b>\n<code>${name}</code>=<code>${value.slice(0,20)}${value.length>20?'…':''}</code>\nIP: <code>${clientIp(req)}</code>`);
    res.json({ ok: true, name, value });
  } catch (e) {
    console.error('POST /api/admin/github/variables error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// DELETE /api/admin/github/variables/:name — eliminar una variable
app.delete('/api/admin/github/variables/:name', requireAdmin, async (req, res) => {
  try {
    if (!octokit) return res.status(503).json({ error: 'GITHUB_TOKEN no configurado' });
    const { name } = req.params;
    await octokit.rest.actions.deleteRepoVariable({
      owner: GITHUB_OWNER, repo: GITHUB_REPO, name,
    });
    tgAlert('ghvar_del', () =>
      `🗑️ <b>Variable eliminada</b>\n<code>${name}</code>\nIP: <code>${clientIp(req)}</code>`);
    res.json({ ok: true, name });
  } catch (e) {
    console.error('DELETE /api/admin/github/variables error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// ─────────────────────────────────────────────────────────────
// Búsqueda de imágenes
// ─────────────────────────────────────────────────────────────

// ── GET /api/image-search — Buscar imágenes via SerpAPI ───────
app.get('/api/image-search', chatLimiter, async (req, res) => {
  const { q } = req.query;
  if (!q || !q.trim()) return res.status(400).json({ error: 'Parámetro q requerido.' });

  const SERP_KEY = process.env.SERPAPI_KEY;
  if (!SERP_KEY) return res.status(503).json({ error: 'Búsqueda de imágenes no disponible.' });

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

// ── GET /api/search/google — Proxy Google Custom Search (DeepSearch) ──
app.get('/api/search/google', chatLimiter, async (req, res) => {
  const { q } = req.query;
  if (!q || !q.trim()) return res.status(400).json({ error: 'Parámetro q requerido.' });
  
  const SERP_KEY = process.env.SERPAPI_KEY;
  if (!SERP_KEY) return res.status(503).json({ error: 'Búsqueda web no disponible.' });
  
  try {
    const url = `https://serpapi.com/search.json?engine=google&q=${encodeURIComponent(q)}&hl=es&gl=gt&num=8&api_key=${SERP_KEY}`;
    const response = await fetch(url, { signal: AbortSignal.timeout(8000) });
    if (!response.ok) throw new Error('SerpAPI ' + response.status);
    const data = await response.json();
    res.json({ items: (data.organic_results || []).slice(0, 8).map(r => ({ title: r.title, snippet: r.snippet, link: r.link })) });
  } catch (err) {
    console.error('search/google error:', err.message);
    res.status(500).json({ error: 'Error en búsqueda Google.' });
  }
});

// ── GET /api/search/tavily — Búsqueda Tavily (DeepSearch) ───────────
app.get('/api/search/tavily', chatLimiter, async (req, res) => {
  const { q } = req.query;
  if (!q || !q.trim()) return res.status(400).json({ error: 'Parámetro q requerido.' });
  
  const TAVILY_KEY = process.env.TAVILY_API_KEY;
  if (!TAVILY_KEY) return res.status(503).json({ error: 'Búsqueda Tavily no disponible.' });
  
  try {
    const response = await fetch('https://api.tavily.com/search', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ api_key: TAVILY_KEY, query: q, max_results: 8, include_answer: true }),
      signal: AbortSignal.timeout(8000)
    });
    if (!response.ok) throw new Error('Tavily ' + response.status);
    const data = await response.json();
    res.json({ results: (data.results || []).slice(0, 8), answer: data.answer || null });
  } catch (err) {
    console.error('search/tavily error:', err.message);
    res.status(500).json({ error: 'Error en búsqueda Tavily.' });
  }
});

// ── PUSH NOTIFICATIONS (Web Push / VAPID) ─────────────────────
// El frontend se suscribe con su ubicación y el servidor avisa por
// push SOLO cuando cambia la condición del clima (sin spam).
const webpush = require('web-push');

const VAPID_PUBLIC_KEY  = process.env.VAPID_PUBLIC_KEY  || 'BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSnfckjBJuBkr3qBlyNhTJSKBHt1J_ypW4';
const VAPID_PRIVATE_KEY = process.env.VAPID_PRIVATE_KEY || 'UUxI4O8-FbRouAevSmBQ6o18hgE4nSG3qwvJTfKsg-I';
webpush.setVapidDetails('mailto:admin@codehub.gt', VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY);

// ── FIREBASE CLOUD MESSAGING (FCM) ───────────────────────────
// Push instantáneo para la app Android nativa.
let admin = null;
let fcmEnabled = false;
try {
  admin = require('firebase-admin');
  const serviceAccount = process.env.FIREBASE_SERVICE_ACCOUNT
    ? JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT)
    : null;
  if (serviceAccount) {
    admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });
    fcmEnabled = true;
    console.log('✅ FCM: Firebase Cloud Messaging habilitado');
  } else {
    console.warn('⚠️  FCM: FIREBASE_SERVICE_ACCOUNT no configurado — push web-push únicamente');
  }
} catch (e) {
  console.warn('⚠️  FCM: firebase-admin no disponible:', e.message);
}

const PUSH_SQL = `
create table if not exists public.push_subs (
  id bigint generated always as identity primary key,
  endpoint text not null unique,
  keys_p256dh text,
  keys_auth text,
  lat double precision,
  lon double precision,
  city text,
  country text,
  timezone text,
  user_agent text,
  alerts boolean default true,
  last_alert_condition text,
  last_alert_at timestamptz,
  created_at timestamptz default now(),
  updated_at timestamptz default now()
);
create index if not exists push_subs_alerts_idx on public.push_subs (alerts);
`;

let pushStore = new Map(); // fallback en memoria si Supabase no está disponible

async function ensurePushTable() {
  if (!supabase) return false;
  const statements = splitSqlStatements(PUSH_SQL);
  try {
    for (const stmt of statements) {
      const { error } = await supabase.rpc('exec_sql', { query: stmt });
      if (error) {
        console.warn('⚠️  Push: no se pudo crear tabla push_subs (' + error.message + ') — creala a mano con backend/push_subs.sql');
        return false;
      }
    }
    console.log('✅ Push: tabla push_subs lista');
    return true;
  } catch (e) {
    console.warn('⚠️  Push: error asegurando tabla:', e.message);
    return false;
  }
}

function pushRowToSub(row) {
  return {
    endpoint: row.endpoint,
    keys: { p256dh: row.keys_p256dh, auth: row.keys_auth },
    lat: row.lat, lon: row.lon, city: row.city, country: row.country,
    timezone: row.timezone, user_agent: row.user_agent,
    alerts: row.alerts, last_alert_condition: row.last_alert_condition, last_alert_at: row.last_alert_at,
  };
}

async function pushList() {
  if (supabase) {
    try {
      const { data, error } = await supabase.from('push_subs').select('*');
      if (!error && Array.isArray(data)) return data.map(pushRowToSub);
    } catch (e) { console.warn('Push list error:', e.message); }
  }
  return Array.from(pushStore.values());
}

async function pushSave(rec) {
  if (supabase) {
    try {
      const { error } = await supabase.from('push_subs').upsert({
        endpoint:       rec.endpoint,
        keys_p256dh:    rec.keys?.p256dh || null,
        keys_auth:      rec.keys?.auth   || null,
        lat:            rec.lat  ?? null,
        lon:            rec.lon  ?? null,
        city:           rec.city || null,
        country:        rec.country || null,
        timezone:       rec.timezone || null,
        user_agent:     rec.user_agent || null,
        alerts:         rec.alerts !== false,
        last_alert_condition: rec.last_alert_condition || null,
        last_alert_at:  rec.last_alert_at || null,
        updated_at:     new Date(),
      }, { onConflict: 'endpoint' });
      if (!error) return true;
      console.warn('Push save supabase error:', error.message);
    } catch (e) { console.warn('Push save error:', e.message); }
  }
  pushStore.set(rec.endpoint, rec);
  return true;
}

async function pushDelete(endpoint) {
  if (supabase) {
    try { await supabase.from('push_subs').delete().eq('endpoint', endpoint); } catch (e) {}
  }
  pushStore.delete(endpoint);
}

async function sendPush(rec, payload) {
  if (!rec || !rec.endpoint || !rec.keys || !rec.keys.p256dh || !rec.keys.auth) {
    return { ok: false, reason: 'incomplete' };
  }
  try {
    await webpush.sendNotification(
      { endpoint: rec.endpoint, keys: { p256dh: rec.keys.p256dh, auth: rec.keys.auth } },
      JSON.stringify(payload),
      { TTL: 3600 }
    );
    return { ok: true };
  } catch (e) {
    // 404/410 = suscripción expirada. 401/403 = clave VAPID no coincide
    // con la que se usó al suscribirse (rotación de VAPID_PUBLIC/PRIVATE_KEY
    // o subs creadas antes de fijar esas env vars). En ambos casos la sub
    // es inservible: se borra para que el cliente se re-suscriba solo la
    // próxima vez que visite el sitio (ver chequeo de applicationServerKey
    // en initIndexPush, index.html).
    if ([401, 403, 404, 410].includes(e.statusCode)) {
      await pushDelete(rec.endpoint);
    }
    return { ok: false, code: e.statusCode, message: e.body || e.message };
  }
}

// Clave pública VAPID que el frontend debe usar al suscribirse.
// Antes estaba hardcodeada en index.html (con el mismo valor de fallback
// que aquí abajo); si en Render se configuraban VAPID_PUBLIC_KEY/PRIVATE_KEY
// propios, el backend firmaba con la clave nueva pero el navegador seguía
// suscribiéndose con la clave vieja hardcodeada → desajuste de claves →
// el push fallaba en silencio (la suscripción se guardaba, pero
// webpush.sendNotification nunca llegaba). Este endpoint es la única
// fuente de verdad: el frontend la consulta en vez de tenerla fija.
app.get('/api/push/vapid-public-key', (_req, res) => {
  res.json({ ok: true, key: VAPID_PUBLIC_KEY });
});

app.post('/api/push/subscribe', chatLimiter, async (req, res) => {
  try {
    const { subscription, location, prefs } = req.body || {};
    const sub = subscription || {};
    if (!sub.endpoint || !sub.keys || !sub.keys.p256dh || !sub.keys.auth) {
      return res.status(400).json({ ok: false, error: 'Suscripción inválida' });
    }
    const loc = location || {};
    const rec = {
      endpoint:   sub.endpoint,
      keys:       { p256dh: sub.keys.p256dh, auth: sub.keys.auth },
      lat:        Number.isFinite(+loc.lat)  ? +loc.lat  : null,
      lon:        Number.isFinite(+loc.lon)  ? +loc.lon  : null,
      city:       (loc.city   || '').slice(0, 120) || null,
      country:    (loc.country || '').slice(0, 80)  || null,
      timezone:   (loc.timezone || '').slice(0, 60) || null,
      user_agent: (req.get('user-agent') || '').slice(0, 200),
      alerts:     prefs ? prefs.alerts !== false : true,
    };
    await pushSave(rec);
    res.json({ ok: true });
  } catch (e) {
    console.error('push/subscribe error:', e.message);
    res.status(500).json({ ok: false, error: e.message });
  }
});

app.post('/api/push/unsubscribe', chatLimiter, async (req, res) => {
  const { endpoint } = req.body || {};
  if (!endpoint) return res.status(400).json({ ok: false, error: 'Falta endpoint' });
  await pushDelete(endpoint);
  res.json({ ok: true });
});

app.post('/api/push/settings', chatLimiter, async (req, res) => {
  try {
    const { endpoint, location, prefs } = req.body || {};
    if (!endpoint) return res.status(400).json({ ok: false, error: 'Falta endpoint' });
    const list = await pushList();
    let rec = list.find(r => r.endpoint === endpoint);
    if (!rec) return res.status(404).json({ ok: false, error: 'Suscripción no encontrada' });
    if (location) {
      if (Number.isFinite(+location.lat)) rec.lat = +location.lat;
      if (Number.isFinite(+location.lon)) rec.lon = +location.lon;
      if (location.city)     rec.city    = String(location.city).slice(0, 120);
      if (location.country)  rec.country = String(location.country).slice(0, 80);
      if (location.timezone) rec.timezone = String(location.timezone).slice(0, 60);
    }
    if (prefs && typeof prefs.alerts === 'boolean') rec.alerts = prefs.alerts;
    await pushSave(rec);
    res.json({ ok: true });
  } catch (e) { res.status(500).json({ ok: false, error: e.message }); }
});

app.post('/api/push/notify', chatLimiter, async (req, res) => {
  try {
    const { endpoint, title, body, url } = req.body || {};
    if (!endpoint || !title) return res.status(400).json({ ok: false, error: 'Falta endpoint o title' });
    const list = await pushList();
    const rec = list.find(r => r.endpoint === endpoint);
    if (!rec) return res.status(404).json({ ok: false, error: 'Suscripción no encontrada' });
    const r = await sendPush(rec, { title, body: body || '', type: 'general', icon: '/splash/codehub.png', url: url || '/' });
    res.json(r);
  } catch (e) { res.status(500).json({ ok: false, error: e.message }); }
});

// ── FCM TOKEN MANAGEMENT ─────────────────────────────────────
let fcmTokens = new Map(); // fallback en memoria

const FCM_SQL = `
create table if not exists public.fcm_tokens (
  id bigint generated always as identity primary key,
  token text not null unique,
  lat double precision,
  lon double precision,
  city text,
  country text,
  app_name text default 'CodeHub',
  app_version text,
  platform text default 'android',
  user_agent text,
  created_at timestamptz default now(),
  updated_at timestamptz default now()
);
`;

async function ensureFCMTable() {
  if (!supabase) return false;
  try {
    const statements = splitSqlStatements(FCM_SQL);
    for (const stmt of statements) {
      const { error } = await supabase.rpc('exec_sql', { query: stmt });
      if (error) {
        console.warn('⚠️  FCM: no se pudo crear tabla fcm_tokens — ' + error.message);
        return false;
      }
    }
    console.log('✅ FCM: tabla fcm_tokens lista');
    return true;
  } catch (e) {
    console.warn('⚠️  FCM: error asegurando tabla:', e.message);
    return false;
  }
}

async function fcmListTokens() {
  if (supabase) {
    try {
      const { data, error } = await supabase.from('fcm_tokens').select('*');
      if (!error && Array.isArray(data)) return data;
    } catch (e) {}
  }
  return Array.from(fcmTokens.values());
}

async function fcmSaveToken(rec) {
  if (supabase) {
    try {
      const { error } = await supabase.from('fcm_tokens').upsert({
        token: rec.token,
        lat: rec.lat ?? null,
        lon: rec.lon ?? null,
        city: rec.city || null,
        country: rec.country || null,
        app_name: rec.appName || 'CodeHub',
        app_version: rec.appVersion || null,
        platform: rec.platform || 'android',
        user_agent: rec.userAgent || null,
        updated_at: new Date(),
      }, { onConflict: 'token' });
      if (!error) return true;
      console.warn('FCM save error:', error.message);
    } catch (e) { console.warn('FCM save error:', e.message); }
  }
  fcmTokens.set(rec.token, rec);
  return true;
}

async function fcmUpdateLocation(token, lat, lon) {
  if (supabase) {
    try {
      const { error } = await supabase.from('fcm_tokens').update({ lat, lon, updated_at: new Date() }).eq('token', token);
      if (!error) return true;
    } catch (e) {}
  }
  const rec = fcmTokens.get(token);
  if (rec) { rec.lat = lat; rec.lon = lon; }
  return true;
}

async function fcmDeleteToken(token) {
  if (supabase) {
    try { await supabase.from('fcm_tokens').delete().eq('token', token); } catch (e) {}
  }
  fcmTokens.delete(token);
}

// Enviar vía FCM a un token específico
async function sendFCM(token, payload) {
  if (!fcmEnabled || !admin) return { ok: false, reason: 'fcm_disabled' };
  try {
    await admin.messaging().send({
      token: token,
      notification: { title: payload.title, body: payload.body },
      data: { type: payload.type || 'general', url: payload.url || '/' },
      android: {
        // priority: 'high' — despierta el dispositivo incluso en Doze
        // profundo. Sin esto Android puede demorar la entrega hasta
        // la próxima ventana de mantenimiento si la app no se usa hace
        // tiempo, dando la falsa impresión de que "no está despierta".
        priority: 'high',
        notification: {
          channelId: payload.type === 'weather' ? 'codehub_weather' : 'codehub_updates',
          icon: 'ic_launcher_real',
          color: '#00ff88',
        },
      },
    });
    return { ok: true };
  } catch (e) {
    if (e.code === 'messaging/registration-token-not-registered' || e.code === 'messaging/invalid-registration-token') {
      await fcmDeleteToken(token);
    }
    return { ok: false, code: e.code, message: e.message };
  }
}

app.post('/api/push/fcm-subscribe', chatLimiter, async (req, res) => {
  try {
    const { token, lat, lon, appName, appVersion, platform, userAgent } = req.body || {};
    if (!token) return res.status(400).json({ ok: false, error: 'Falta token FCM' });
    await fcmSaveToken({ token, lat, lon, appName, appVersion, platform, userAgent });
    res.json({ ok: true });
  } catch (e) { res.status(500).json({ ok: false, error: e.message }); }
});

app.post('/api/push/fcm-location', chatLimiter, async (req, res) => {
  try {
    const { token, lat, lon } = req.body || {};
    if (!token) return res.status(400).json({ ok: false, error: 'Falta token' });
    await fcmUpdateLocation(token, lat, lon);
    res.json({ ok: true });
  } catch (e) { res.status(500).json({ ok: false, error: e.message }); }
});

// ── CRASH REPORTING (app Android) ────────────────────────────
// La app nativa (WebView wrapper) reporta acá tanto crashes fatales
// (Thread.UncaughtExceptionHandler) como excepciones atrapadas y
// errores de JS del sitio dentro del WebView (window.onerror /
// unhandledrejection). Reenviamos al chat de Telegram del admin
// agrupando por firma (misma clase+tag) para no floodear si el
// mismo bug crashea la app repetidas veces seguidas.
function escHtml(s) {
  return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

app.post('/api/crash-report', crashLimiter, async (req, res) => {
  try {
    const {
      fatal, tag, exceptionClass, message, stackTrace,
      appVersion, platform, deviceModel, androidVersion, timestamp,
    } = req.body || {};

    if (!exceptionClass && !stackTrace && !message) {
      return res.status(400).json({ ok: false, error: 'Reporte vacío' });
    }

    const when  = timestamp ? new Date(Number(timestamp) || timestamp) : new Date();
    const trace = String(stackTrace || '').slice(0, 3200); // margen para el límite de 4096 de Telegram
    const key   = `crash:${fatal ? 'fatal' : 'caught'}:${tag || ''}:${exceptionClass || ''}`;
    const icon  = fatal ? '💥' : '⚠️';
    const kind  = fatal ? 'CRASH FATAL' : 'Excepción capturada';

    tgAlert(key, () =>
      `${icon} <b>${kind} — App Android</b>\n` +
      (tag ? `Módulo: <code>${escHtml(tag)}</code>\n` : '') +
      `Clase: <code>${escHtml(exceptionClass || '?')}</code>\n` +
      `Mensaje: ${escHtml(message || '(sin mensaje)')}\n` +
      `Dispositivo: ${escHtml(deviceModel || '?')} · Android ${escHtml(androidVersion || '?')}\n` +
      `Versión app: ${escHtml(appVersion || '?')} · Plataforma: ${escHtml(platform || 'android')}\n` +
      `Hora: ${when.toISOString()}\n\n` +
      (trace ? `<pre>${escHtml(trace)}</pre>` : ''),
      { windowMs: 10000 });

    res.json({ ok: true });
  } catch (e) { res.status(500).json({ ok: false, error: e.message }); }
});

// Envía un push a todos los suscriptores (broadcast reutilizable por
// los flujos automáticos: nueva app, app actualizada, CodeHub Release).
// Envía vía Web Push (VAPID) + FCM (app nativa).
async function broadcastPush({ title, body = '', url = '/', type = 'announcement', appId, version }) {
  const t = String(title).trim().slice(0, 80);
  const b = String(body || '').trim().slice(0, 180);
  let sentWeb = 0, sentAndroid = 0;
  const failures = [];

  // 1) Web Push (VAPID) — suscriptores del navegador
  const webSubs = await pushList();
  for (const sub of webSubs) {
    const result = await sendPush(sub, { title: t, body: b, type, appId, version, icon: '/splash/codehub.png', url });
    if (result.ok) {
      sentWeb += 1;
    } else {
      failures.push({ kind: 'web', endpoint: (sub.endpoint || '').slice(-24), code: result.code, message: result.message || result.reason });
    }
  }

  // 2) FCM — app Android nativa
  let fcmTotal = 0;
  if (fcmEnabled) {
    const fcmSubs = await fcmListTokens();
    fcmTotal = fcmSubs.length;
    for (const rec of fcmSubs) {
      const result = await sendFCM(rec.token, { title: t, body: b, type, url });
      if (result.ok) {
        sentAndroid += 1;
      } else {
        failures.push({ kind: 'android', token: (rec.token || '').slice(-12), code: result.code, message: result.message });
      }
    }
  }

  const sent = sentWeb + sentAndroid;
  const total = webSubs.length + fcmTotal;

  // Log detallado: sin esto, un "0 de 12" en el admin-hub no dice NADA de
  // por qué falló. Con esto, en Render → Logs se ve el motivo exacto de
  // cada fallo (clave VAPID desactualizada, token FCM inválido, etc.)
  if (sent < total) {
    console.warn(`⚠️  broadcastPush: ${sent}/${total} entregados (web ${sentWeb}/${webSubs.length}, android ${sentAndroid}/${fcmTotal})`);
    failures.slice(0, 20).forEach(f => {
      console.warn(`   ✗ [${f.kind}] ${f.kind === 'web' ? 'endpoint …' + f.endpoint : 'token …' + f.token} — code ${f.code || '?'}: ${f.message || '(sin detalle)'}`);
    });
    if (failures.length > 20) console.warn(`   … y ${failures.length - 20} fallos más`);
  }

  return { sent, total, sentWeb, sentAndroid, webTotal: webSubs.length, androidTotal: fcmTotal, fcmEnabled, failures: failures.slice(0, 20) };
}

app.post('/api/admin/push/broadcast', requireAdmin, async (req, res) => {
  try {
    const { title, body, url, type, appId, version } = req.body || {};
    if (!title || !String(title).trim()) {
      return res.status(400).json({ ok: false, error: 'Falta el título de la notificación' });
    }
    const r = await broadcastPush({ title, body, url, type, appId, version });
    res.json({ ok: true, ...r });
  } catch (e) {
    console.error('admin/push/broadcast error:', e.message);
    res.status(500).json({ ok: false, error: e.message });
  }
});

// ── CODEHUB RELEASES ────────────────────────────────────────────
// Novedades del proyecto publicadas desde el admin-hub. Al publicar se
// guardan en MongoDB, se avisa por WebSocket y se envía push a todos.

app.post('/api/admin/releases', requireAdmin, async (req, res) => {
  try {
    const { title, body, version, url, type } = req.body || {};
    if (!title || !String(title).trim()) {
      return res.status(400).json({ ok: false, error: 'Falta el título del release' });
    }
    const rel = await Release.create({
      title: String(title).trim().slice(0, 80),
      body: String(body || '').slice(0, 500),
      version: String(version || '').slice(0, 40),
      url: url || '/',
      type: type || 'release',
    });
    broadcast('codehub_release', { id: String(rel._id), title: rel.title, version: rel.version });
    tgAlert('release', () => `🚀 <b>CodeHub Release</b>\n${String(rel.title).slice(0, 50)}${rel.version ? ' · ' + rel.version : ''}`, { windowMs: 15000 });
    const push = await broadcastPush({
      title: rel.version ? '🚀 CodeHub ' + rel.version : '🚀 CodeHub Release',
      body: rel.title + (rel.body ? ' — ' + String(rel.body).slice(0, 120) : ''),
      type: 'release',
      version: rel.version || '',
      url: rel.url || '/',
    });
    res.json({ ok: true, release: rel, push });
  } catch (e) {
    console.error('admin/releases error:', e.message);
    res.status(500).json({ ok: false, error: e.message });
  }
});

app.get('/api/admin/releases', requireAdmin, async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    const releases = await Release.find({}).sort({ createdAt: -1 }).limit(50).lean();
    res.json({ ok: true, releases });
  } catch (e) { res.status(500).json({ ok: false, error: e.message }); }
});

app.delete('/api/admin/releases/:id', requireAdmin, async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    await Release.deleteOne({ _id: req.params.id });
    res.json({ ok: true });
  } catch (e) { res.status(500).json({ ok: false, error: e.message }); }
});

// Lista pública de releases (campana de notificaciones / página)
app.get('/api/releases', async (req, res) => {
  if (!dbConnected) return res.json({ ok: true, releases: [] });
  try {
    const cached = await cacheGet('releases:latest');
    if (cached) { res.set('X-Cache', 'HIT'); return res.json({ ok: true, releases: cached }); }
    const releases = await Release.find({}).sort({ createdAt: -1 }).limit(20).lean();
    await cacheSet('releases:latest', releases, 60);
    res.json({ ok: true, releases });
  } catch (e) { res.status(500).json({ ok: false, error: e.message }); }
});

// ── CLIMA → PUSH (alertas y recomendaciones) ─────────────────
const WX_ALERTS = [
  { cond: 'storm', test: c => c.weather_code >= 95 || (c.precipitation > 8 && c.wind_speed_10m > 35),
    msg: c => '⛈️ Tormenta eléctrica en tu zona — evita zonas abiertas y desconecta aparatos' },
  { cond: 'rain',  test: c => (c.weather_code >= 61 && c.weather_code <= 67) || Number(c.precipitation_probability || 0) >= 70,
    msg: c => '🌧️ Probabilidad alta de lluvia (' + Math.round(Number(c.precipitation_probability || 0)) + '%) — lleva paraguas y revisa el pronóstico antes de salir' },
  { cond: 'wind',  test: c => c.wind_speed_10m > 50,
    msg: c => '💨 Viento fuerte (' + Math.round(c.wind_speed_10m) + ' km/h) — precaución al manejar' },
  { cond: 'radiation', test: c => Number(c.uv_index || 0) >= 7,
    msg: c => '☀️ Radiación alta (' + Number(c.uv_index || 0).toFixed(1) + ') — usa bloqueador y evita el sol fuerte al mediodía' },
  { cond: 'heat',  test: c => c.temperature_2m > 33 || c.apparent_temperature > 38,
    msg: c => '🌡️ Calor extremo (' + Math.round(c.temperature_2m) + '°C, sensación ' + Math.round(c.apparent_temperature) + '°C) — hidrátate y evita el sol de 11 a 15h' },
  { cond: 'cold',  test: c => c.temperature_2m < 0,
    msg: c => '🥶 Frío intenso (' + Math.round(c.temperature_2m) + '°C) — abrígate bien' },
];

async function fetchWeatherFor(lat, lon) {
  const url = 'https://api.open-meteo.com/v1/forecast?latitude=' + lat + '&longitude=' + lon +
    '&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation' +
    '&hourly=temperature_2m,precipitation_probability,uv_index' +
    '&forecast_days=2&wind_speed_unit=kmh&timezone=auto';
  const r = await fetch(url);
  if (!r.ok) throw new Error('open-meteo ' + r.status);
  const data = await r.json();
  const hourly = data.hourly || {};
  const rainProb = (hourly.precipitation_probability || []).map(v => Number(v || 0));
  const uvIndex = (hourly.uv_index || []).map(v => Number(v || 0));
  const current = data.current || {};
  return {
    ...current,
    precipitation_probability: rainProb.length ? Math.max(...rainProb) : (Number(current.precipitation || 0) > 0 ? 70 : 0),
    uv_index: uvIndex.length ? Math.max(...uvIndex) : 0,
  };
}

function detectAlert(current) {
  for (const a of WX_ALERTS) {
    try { if (a.test(current)) return { cond: a.cond, body: a.msg(current) }; } catch (e) {}
  }
  return null;
}

async function weatherPushPass() {
  let subs;
  try { subs = await pushList(); } catch (e) { return { sent: 0 }; }
  const enabled = subs.filter(s => s.alerts && Number.isFinite(+s.lat) && Number.isFinite(+s.lon));
  if (!enabled.length) {
    // Incluso sin suscriptores web, intentar enviar a FCM (Android)
    if (!fcmEnabled) return { sent: 0 };
  }

  // Agrupar por coordenadas redondeadas para no repetir llamadas a Open-Meteo
  const groups = new Map();
  for (const s of enabled) {
    const key = (+s.lat).toFixed(1) + ',' + (+s.lon).toFixed(1);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(s);
  }

  // También agrupar tokens FCM por coordenadas
  const fcmGroups = new Map();
  if (fcmEnabled) {
    try {
      const fcmTokens = await fcmListTokens();
      for (const t of fcmTokens) {
        if (Number.isFinite(+t.lat) && Number.isFinite(+t.lon)) {
          const key = (+t.lat).toFixed(1) + ',' + (+t.lon).toFixed(1);
          if (!fcmGroups.has(key)) fcmGroups.set(key, []);
          fcmGroups.get(key).push(t);
        }
      }
    } catch (e) {}
  }

  // Merge all coordinate groups
  for (const [key, tokens] of fcmGroups) {
    if (!groups.has(key)) groups.set(key, []);
    // Mark FCM tokens so we know which send method to use
    for (const t of tokens) {
      groups.get(key).push({ ...t, _isFCM: true });
    }
  }

  let sent = 0;
  for (const [key, group] of groups) {
    const parts = key.split(',');
    let current;
    try { current = await fetchWeatherFor(parts[0], parts[1]); } catch (e) { continue; }
    const alert = detectAlert(current);
    for (const s of group) {
      if (alert) {
        if (s.last_alert_condition !== alert.cond) {
          let r;
          if (s._isFCM) {
            // Enviar a Android vía FCM
            r = await sendFCM(s.token, {
              title: 'CodeHub Clima',
              body: alert.body,
              type: 'weather',
              url: '/#weather-section',
            });
          } else {
            // Enviar a navegador vía Web Push
            r = await sendPush(s, {
              title: 'CodeHub Clima',
              body:  s.city ? alert.body + ' · ' + s.city : alert.body,
              type:  'weather',
              icon:  '/splash/codehub.png',
              url:   '/#weather-section',
            });
          }
          if (r.ok) {
            s.last_alert_condition = alert.cond;
            s.last_alert_at = new Date().toISOString();
            if (!s._isFCM) await pushSave(s);
            sent++;
          }
        }
      } else if (s.last_alert_condition && !s._isFCM) {
        s.last_alert_condition = null;
        s.last_alert_at = null;
        await pushSave(s);
      }
    }
  }
  return { sent };
}

app.get('/api/push/weather/check', async (req, res) => {
  try {
    const out = await weatherPushPass();
    res.json({ ok: true, ...out });
  } catch (e) { res.status(500).json({ ok: false, error: e.message }); }
});

// Scheduler — revisa cada 30 min; solo envía push cuando cambia la condición
setInterval(() => {
  weatherPushPass()
    .then(o => { if (o.sent) console.log('🌤️ Push clima enviado:', o.sent); })
    .catch(e => console.warn('⚠️  Push clima error:', e.message));
}, 30 * 60 * 1000);

// ── MONITOR AUTOMÁTICO DE RELEASES (apps open source) ──────────
// Revisa periódicamente las apps con `source_repo` vía la API pública
// de GitHub; si hay una versión nueva publicada actualiza el documento
// en MongoDB y envía push a todos los suscriptores ("app se actualizó").
const AUTO_UPDATE_MS = Math.max(30 * 60 * 1000, Number(process.env.AUTO_UPDATE_MS) || 6 * 60 * 60 * 1000);
const GITHUB_MONITOR_TOKEN = process.env.GITHUB_TOKEN || null;

async function fetchLatestRelease(ownerRepo) {
  const url = `https://api.github.com/repos/${ownerRepo}/releases/latest`;
  const headers = { 'Accept': 'application/vnd.github+json', 'User-Agent': 'CodeHub-App-Update-Monitor' };
  if (GITHUB_MONITOR_TOKEN) headers['Authorization'] = 'Bearer ' + GITHUB_MONITOR_TOKEN;
  const res = await fetch(url, { headers });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error('GitHub API ' + res.status + ' para ' + ownerRepo);
  return res.json();
}

function pickApkAsset(release) {
  if (!Array.isArray(release.assets)) return null;
  const apk = release.assets.find(a => a.name && a.name.toLowerCase().endsWith('.apk'));
  return apk ? apk.browser_download_url : null;
}

function truncate(text, max = 400) {
  if (!text) return '';
  const clean = String(text).replace(/\r\n/g, '\n').trim();
  return clean.length > max ? clean.slice(0, max).trim() + '…' : clean;
}

async function autoCheckAppUpdates() {
  if (!dbConnected) return { ok: false, reason: 'no-db', updated: 0, sent: 0 };
  const apps = await App.find({ source_repo: { $ne: null } }).lean().catch(() => []);
  if (!apps.length) return { ok: true, updated: 0, sent: 0, checked: 0 };

  let updated = 0, sent = 0;
  for (const app of apps) {
    try {
      const release = await fetchLatestRelease(app.source_repo);
      if (!release) continue;
      const nuevaVersion = release.tag_name || release.name || null;
      if (!nuevaVersion || nuevaVersion === app.version) continue;

      const apkUrl = pickApkAsset(release);
      const update = {
        version: nuevaVersion,
        changelog: truncate(release.body),
        tag: '🔄 Actualizada',
        updatedAt: new Date(),
      };
      if (apkUrl) update.enlace = apkUrl;

      await App.updateOne({ appId: app.appId }, { $set: update });
      await cacheDel('apps:all');
      broadcastAppsChanged();
      updated++;

      const r = await broadcastPush({
        title: '🔄 ' + app.nombre + ' se actualizó',
        body: truncate(release.body, 120) || 'Nueva versión ' + nuevaVersion + ' disponible',
        type: 'app_update',
        appId: app.appId,
        version: nuevaVersion,
        url: '/opensource.html',
      });
      sent += r.sent || 0;
      console.log('⬆️  Auto: ' + app.nombre + ' → ' + nuevaVersion + ' (push ' + (r.sent || 0) + ')');
    } catch (e) {
      console.warn('⚠️  Auto update ' + app.appId + ':', e.message);
    }
    await new Promise(r => setTimeout(r, 500));
  }
  return { ok: true, checked: apps.length, updated, sent };
}

// Scheduler del monitor — cada 6h por defecto (configurable con AUTO_UPDATE_MS)
setInterval(() => {
  autoCheckAppUpdates()
    .then(o => { if (o.updated) console.log('🤖 Monitor releases: ' + o.updated + ' actualizada(s)'); })
    .catch(e => console.warn('⚠️  Monitor releases error:', e.message));
}, AUTO_UPDATE_MS);

// Endpoint para disparar el monitor manualmente (admin-hub / cron externo)
app.get('/api/admin/apps/check-updates', requireAdmin, async (req, res) => {
  try {
    const out = await autoCheckAppUpdates();
    res.json({ ok: true, ...out });
  } catch (e) { res.status(500).json({ ok: false, error: e.message }); }
});

// ── WEBHOOK DE GITHUB — releases en tiempo real ─────────────────
// autoCheckAppUpdates() (arriba) revisa cada 6h por polling — funciona,
// pero no es "tiempo real": si publicas un release, los suscriptores no
// se enteran hasta el siguiente ciclo del monitor. Este webhook hace que
// GitHub avise al instante en cuanto se publica un release, y aquí mismo
// se dispara el push a todos los suscriptores sin esperar al polling.
//
// Configuración necesaria (una vez por repo que quieras notificar al
// instante, además de GITHUB_WEBHOOK_SECRET en las env vars de Render):
//   GitHub repo → Settings → Webhooks → Add webhook
//     Payload URL: https://<tu-backend>/api/webhook/github-release
//     Content type: application/json
//     Secret: el mismo valor que GITHUB_WEBHOOK_SECRET
//     Evento: "Let me select individual events" → Releases
function verifyGithubSignature(req) {
  const secret = process.env.GITHUB_WEBHOOK_SECRET;
  if (!secret) return false; // sin secreto configurado, no se acepta el webhook
  const sig = req.get('x-hub-signature-256') || '';
  const expected = 'sha256=' + crypto.createHmac('sha256', secret).update(req.rawBody || '').digest('hex');
  try {
    return sig.length === expected.length &&
      crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expected));
  } catch (e) { return false; }
}

app.post('/api/webhook/github-release', async (req, res) => {
  try {
    if (!verifyGithubSignature(req)) return res.status(401).json({ ok: false, error: 'Firma inválida o GITHUB_WEBHOOK_SECRET no configurado' });

    const event = req.get('x-github-event');
    if (event === 'ping') return res.json({ ok: true, pong: true });
    if (event !== 'release') return res.json({ ok: true, ignored: event });

    const payload = req.body || {};
    if (payload.action !== 'published') return res.json({ ok: true, ignored: payload.action });

    const ownerRepo = payload.repository && payload.repository.full_name;
    const release = payload.release;
    if (!ownerRepo || !release) return res.status(400).json({ ok: false, error: 'Payload incompleto' });

    const app_ = await App.findOne({ source_repo: ownerRepo });
    if (!app_) return res.json({ ok: true, matched: false, reason: 'Ninguna app del catálogo usa ese source_repo' });

    const nuevaVersion = release.tag_name || release.name || null;
    if (!nuevaVersion || nuevaVersion === app_.version) return res.json({ ok: true, matched: true, skipped: 'misma versión' });

    const apkUrl = pickApkAsset(release);
    const update = {
      version: nuevaVersion,
      changelog: truncate(release.body),
      tag: '🔄 Actualizada',
      updatedAt: new Date(),
    };
    if (apkUrl) update.enlace = apkUrl;

    await App.updateOne({ appId: app_.appId }, { $set: update });
    await cacheDel('apps:all');
    broadcastAppsChanged();

    const r = await broadcastPush({
      title: '🔄 ' + app_.nombre + ' se actualizó',
      body: truncate(release.body, 120) || 'Nueva versión ' + nuevaVersion + ' disponible',
      type: 'app_update',
      appId: app_.appId,
      version: nuevaVersion,
      url: '/opensource.html',
    });

    console.log('⚡ Webhook release instantáneo: ' + app_.nombre + ' → ' + nuevaVersion + ' (push ' + (r.sent || 0) + ')');
    res.json({ ok: true, matched: true, updated: true, sent: r.sent || 0 });
  } catch (e) {
    console.error('webhook/github-release error:', e.message);
    res.status(500).json({ ok: false, error: e.message });
  }
});

// ── ARRANCAR ──────────────────────────────────────────────────
(async () => {
  await initRedis();
  dbConnected = await connectDB();
  if (supabase) console.log('✅ Supabase Storage listo — bucket:', STORAGE_BUCKET);
  await ensurePushTable();
  await ensureFCMTable();

  server.listen(PORT, () => {
    console.log(`🚀 CodeHub Backend v3.0 en puerto ${PORT}`);
    console.log(`   MongoDB:    ${dbConnected ? '✅' : '⚠️  sin conexión'}`);
    console.log(`   Redis:      ${redis       ? '✅' : '⚠️  usando memoria'}`);
    console.log(`   WebSockets: ✅ /ws`);
    console.log(`   FCM:        ${fcmEnabled ? '✅ push nativo Android' : '⚠️  solo web-push'}`);
    console.log(`   Groq:       ${process.env.GROQ_API_KEY        ? '✅' : '⚠️  sin configurar'}`);
    console.log(`   Cerebras:   ${process.env.CEREBRAS_API_KEY    ? '✅' : '⚠️  sin configurar'}`);
    console.log(`   HuggingFace:${process.env.HUGGINGFACE_API_KEY ? '✅' : '⚠️  sin configurar'}`);
    console.log('   OpenRouter: ' + (process.env.OPENROUTER_API_KEY ? '✅ (' + OR_FREE_MODELS.length + ' modelos gratis)' : '⚠️  sin configurar'));
    console.log(`   Gemini:     ${process.env.GEMINI_API_KEY      ? '✅' : '⚠️  sin configurar'}`);
    console.log(`   Mistral:    ${process.env.MISTRAL_API_KEY     ? '✅' : '⚠️  sin configurar'}`);
    console.log(`   Cohere:     ${process.env.COHERE_API_KEY      ? '✅' : '⚠️  sin configurar'}`);
    console.log(`   Storage:    ${supabase ? '✅ Supabase' : '❌ falta SUPABASE_URL/KEY'}`);
    console.log(`   Together:   ${process.env.TOGETHER_API_KEY ? '✅' : '⚠️  sin configurar'}`);
    console.log(`   Push Clima: ✅ VAPID + scheduler cada 30 min (solo avisa si cambia el clima)`);
    console.log(`   Monitor Releases: ✅ auto cada ${Math.round(AUTO_UPDATE_MS / 3600000)}h (apps open source)`);
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

// ── Streaming SSE (Server-Sent Events) ─────────────────────────────────────
// Endpoint alternativo a /api/chat que devuelve la respuesta token por token.
// Soporta Groq, Cerebras, HuggingFace, OpenRouter, Mistral, Kimi (OpenAI-compat)
// y Claude (Anthropic SSE). Cohere y Gemini Vision caen a non-streaming.
app.post('/api/chat/stream', requireAuth, async (req, res) => {
  const { message, sessionId = 'anon', image, images, pdfText, skill_id } = req.body;
  if (!message || typeof message !== 'string') return res.status(400).json({ error: '"message" requerido.' });
  if (message.trim().length > 1000) return res.status(400).json({ error: 'Mensaje muy largo.' });
  if (!process.env.GROQ_API_KEY && !process.env.GEMINI_API_KEY) return res.status(503).json({ error: 'Sin API keys.' });

  // ── Imagen/PDF escaneado: fallback a non-streaming (solo Gemini Vision) ──
  const imgList = image ? [image] : (Array.isArray(images) && images.length ? images.slice(0, 5) : null);
  if (imgList && imgList.length) {
    req.url = '/api/chat';
    return app.handle(req, res);
  }

  // ── Límite diario server-side ──
  const emiKey = req.authUser ? 'u:' + req.authUser.id : 'd:' + clientIp(req);
  const emiLimit = req.authUser ? EMI_DAILY_LIMIT_REGISTERED : EMI_DAILY_LIMIT_GUEST;
  const emiUsed = getEmiUsage(emiKey);
  if (emiUsed >= emiLimit) {
    return res.status(429).json({ error: `Límite diario alcanzado (${emiLimit} mensajes). ${req.authUser ? '' : 'Inicia sesión para más.'}`, code: 'EMI_DAILY_LIMIT', limit: emiLimit, used: emiUsed });
  }

  // ── Recuperar historial ──
  let sessionHistory = [];
  if (dbConnected && sessionId !== 'anon') {
    try {
      const pastMsgs = await ChatMessage.find({ sessionId }).sort({ createdAt: -1 }).limit(10).lean();
      sessionHistory = pastMsgs.reverse().map(m => ({ role: m.role === 'assistant' ? 'assistant' : 'user', content: String(m.content).slice(0, 800) }));
    } catch (e) { console.warn('Error recuperando historial:', e.message); }
  }
  sessionHistory.push({ role: 'user', content: message.trim() });
  if (typeof pdfText === 'string' && pdfText.trim()) {
    sessionHistory.splice(sessionHistory.length - 1, 0, {
      role: 'user',
      content: '[Documento adjunto — resumen comprimido del documento. Responde usando SOLO este contenido como referencia, en español]:\n' + pdfText.slice(0, 40000)
    });
  }

  let system = SYSTEM;
  if (skill_id) {
    const skill = loadSkillJson(String(skill_id));
    if (skill && skill.system_prompt_inject) system = skill.system_prompt_inject + '\n\n' + system;
  }
  const msgs = [{ role: 'system', content: system }, ...sessionHistory];

  // ── Setup SSE headers ──
  res.setHeader('Content-Type', 'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection', 'keep-alive');
  res.setHeader('X-Accel-Buffering', 'no');
  res.flushHeaders();

  const upstreamAbort = new AbortController();
  req.on('close', () => upstreamAbort.abort());

  let replied = false;
  let fullReply = '';
  let usage = { input: 0, output: 0 };
  let modelName = '';

  function sendSSE(evt, data) {
    if (res.destroyed) return;
    res.write('event: ' + evt + '\ndata: ' + JSON.stringify(data) + '\n\n');
  }

  async function tryStream(name, endpoint, headers, body) {
    const r = await fetch(endpoint, { method: 'POST', headers, body: JSON.stringify(body), signal: upstreamAbort.signal });
    if (!r.ok) {
      const e = await r.json().catch(() => ({}));
      const err = new Error(e.error?.message || name + ' ' + r.status);
      err.status = r.status;
      throw err;
    }
    return r;
  }

  async function consumeOpenAIStream(resp, onChunk) {
    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      const lines = buf.split('\n');
      buf = lines.pop() || '';
      for (const line of lines) {
        if (!line.startsWith('data: ')) continue;
        const payload = line.slice(6).trim();
        if (payload === '[DONE]') return;
        try {
          const d = JSON.parse(payload);
          const delta = d.choices?.[0]?.delta?.content;
          if (delta) onChunk(delta);
          if (d.usage) { usage.input = d.usage.prompt_tokens || 0; usage.output = d.usage.completion_tokens || 0; }
        } catch {}
      }
    }
  }

  async function consumeClaudeStream(resp, onChunk) {
    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      const lines = buf.split('\n');
      buf = lines.pop() || '';
      for (const line of lines) {
        if (!line.startsWith('data: ')) continue;
        try {
          const d = JSON.parse(line.slice(6));
          if (d.type === 'content_block_delta' && d.delta?.text) onChunk(d.delta.text);
          if (d.type === 'message_delta' && d.usage) usage.output = d.usage.output_tokens || 0;
          if (d.type === 'message_start' && d.message?.usage) usage.input = d.message.usage.input_tokens || 0;
        } catch {}
      }
    }
  }

  try {
    const order = classifyRoute(msgs);

    // ── Claude streaming ──
    if (!replied && order[0] === 'Claude' && process.env.ANTHROPIC_API_KEY) {
      try {
        const sysMsg = msgs.find(m => m.role === 'system');
        const chatMsgs = msgs.filter(m => m.role !== 'system');
        const body = {
          model: 'claude-sonnet-4-5', max_tokens: 1500, temperature: 0.65,
          system: sysMsg?.content || '',
          messages: chatMsgs.map(m => ({ role: m.role, content: m.content })),
          stream: true
        };
        const r = await tryStream('Claude', 'https://api.anthropic.com/v1/messages', {
          'Content-Type': 'application/json',
          'x-api-key': process.env.ANTHROPIC_API_KEY,
          'anthropic-version': '2023-06-01'
        }, body);
        modelName = 'anthropic/claude-sonnet';
        replied = true;
        await consumeClaudeStream(r, (chunk) => { fullReply += chunk; sendSSE('chunk', { text: chunk }); });
      } catch (e) {
        console.warn('Claude streaming fallo, intentando siguiente...');
      }
    }

    // ── OpenAI-compatible streaming providers ──
    if (!replied) {
      const oaiProviders = [
        { name: 'Kimi', endpoint: 'https://api.moonshot.ai/v1/chat/completions', key: process.env.KIMI_API_KEY, model: 'kimi-k2-0905-preview', label: 'moonshot/kimi-k2' },
        { name: 'Groq', endpoint: 'https://api.groq.com/openai/v1/chat/completions', key: process.env.GROQ_API_KEY, model: 'llama-3.3-70b-versatile', label: 'groq/llama-3.3-70b' },
        { name: 'Cerebras', endpoint: 'https://api.cerebras.ai/v1/chat/completions', key: process.env.CEREBRAS_API_KEY, model: 'llama-3.3-70b', label: 'cerebras/llama-3.3-70b' },
        { name: 'HuggingFace', endpoint: 'https://router.huggingface.co/v1/chat/completions', key: process.env.HUGGINGFACE_API_KEY, model: 'meta-llama/Llama-3.3-70B-Instruct:novita', label: 'huggingface/llama-3.3-70b' },
        { name: 'Mistral', endpoint: 'https://api.mistral.ai/v1/chat/completions', key: process.env.MISTRAL_API_KEY, model: 'mistral-small-latest', label: 'mistral/mistral-small' },
      ];
      if (process.env.OPENROUTER_API_KEY) {
        for (const m of OR_FREE_MODELS) {
          oaiProviders.push({
            name: 'OpenRouter', endpoint: 'https://openrouter.ai/api/v1/chat/completions',
            key: process.env.OPENROUTER_API_KEY, model: m,
            label: 'openrouter/' + m.split('/').pop().replace(':free', ''),
            extraHeaders: { 'HTTP-Referer': process.env.FRONTEND_URL || 'https://wilson360-labs.vercel.app', 'X-Title': 'EMI COPILOT' }
          });
        }
      }
      const ordered = order.filter(n => n !== 'Claude' && n !== 'Gemini' && n !== 'Cohere');
      const sorted = [...ordered.map(n => oaiProviders.find(p => p.name === n)), ...oaiProviders.filter(p => !ordered.includes(p.name))].filter(Boolean);

      for (const prov of sorted) {
        if (!prov.key || upstreamAbort.signal.aborted) continue;
        try {
          const body = { model: prov.model, max_tokens: 1500, temperature: 0.65, messages: msgs, stream: true, stream_options: { include_usage: true } };
          const headers = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + prov.key, ...prov.extraHeaders };
          const r = await tryStream(prov.name, prov.endpoint, headers, body);
          modelName = prov.label;
          replied = true;
          await consumeOpenAIStream(r, (chunk) => { fullReply += chunk; sendSSE('chunk', { text: chunk }); });
          console.log('Streaming via ' + prov.name + ' (' + prov.model + ')');
          break;
        } catch (e) {
          if (e.status === 401 || e.status === 429) { console.warn(prov.name + ': ' + e.status); continue; }
          console.warn(prov.name + ' streaming fallo: ' + e.message);
        }
      }
    }

    // ── Gemini / Cohere fallback: non-streaming ──
    if (!replied) {
      try {
        const result = await callAI(msgs);
        fullReply = result.reply;
        usage = { input: result.input, output: result.output };
        modelName = result.model;
        replied = true;
        sendSSE('chunk', { text: fullReply });
      } catch (e) {
        sendSSE('error', { error: 'Todos los proveedores de IA fallaron.' });
        res.end();
        return;
      }
    }

    // ── Finalizar: persistir, side effects, done ──
    if (dbConnected) ChatMessage.insertMany([
      { sessionId, role: 'user', content: message.trim(), tokens: usage.input, model: modelName },
      { sessionId, role: 'assistant', content: fullReply, tokens: usage.output, model: modelName },
    ]).catch(() => {});

    const emiNow = incrEmiUsage(emiKey);
    broadcast('chat_used', { model: modelName, tokens: usage.input + usage.output });
    trackEvent('chat', null, { model: modelName, tokens: usage.input + usage.output });
    tgAlert('chat', () => 'Chat con EMI (stream): ' + String(message || '').slice(0, 60).replace(/[<>]/g, '') + ' | ' + modelName, { windowMs: 30000 });

    sendSSE('done', { reply: fullReply, usage: { ...usage, total: usage.input + usage.output }, model: modelName, emi: { used: emiNow, limit: emiLimit } });
    res.end();

  } catch (err) {
    console.error('Stream endpoint error:', err.message);
    sendSSE('error', { error: 'Error interno.' });
    res.end();
  }
});
