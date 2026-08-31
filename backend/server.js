/**
 * CodeHub Backend v3.1 â€” Wilson.E 2026
 * â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
 * âœ… WebSockets â€” notificaciones en tiempo real
 * âœ… Redis      â€” cachÃ© (opcional)
 * âœ… Eventos:   visitas, descargas, ratings, contacto, chat IA, nueva app
 *
 * Variables de Entorno (Render):
 *   GROQ_API_KEY, GEMINI_API_KEY, MONGODB_URI, FRONTEND_URL
 *   ADMIN_KEY, SUPABASE_URL, SUPABASE_KEY (storage bucket: codehub-apks)
 *   RATE_LIMIT_MAX, REDIS_URL (opcional), WS_URL (opcional)
 *   TOGETHER_API_KEY, OPENROUTER_API_KEY, MISTRAL_API_KEY, COHERE_API_KEY
 *   KIMI_API_KEY (Moonshot AI â€” https://platform.moonshot.ai)
 */

require('dotenv').config();
const express   = require('express');
const cors      = require('cors');
const mongoose  = require('mongoose');
const rateLimit = require('express-rate-limit');
const helmet    = require('helmet');
const compression = require('compression');
const multer    = require('multer');
const Busboy    = require('busboy');   // dep transitiva de multer â€” parseo multipart sin buffer
const crypto    = require('crypto');
const http      = require('http');
const fs        = require('fs');
const path      = require('path');
const { WebSocketServer } = require('ws');
const swaggerSpec        = require('./swagger');

// â”€â”€ SUPABASE â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
const { createClient } = require('@supabase/supabase-js');
const supabase = (process.env.SUPABASE_URL?.trim() && process.env.SUPABASE_KEY?.trim())
  ? createClient(process.env.SUPABASE_URL, process.env.SUPABASE_KEY)
  : null;

// Helper: registrar evento en Supabase (fire-and-forget â€” no bloquea la peticiÃ³n)
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

// â”€â”€ SKILLS â€” catÃ¡logo de capacidades de IA (skills/â€¦) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

// â”€â”€ DB RUNNER â€” divide un script .sql en sentencias individuales â”€â”€
// Respeta strings entre comillas simples/dobles y bloques con
// dollar-quoting ($$ ... $$ o $tag$ ... $tag$, tÃ­pico de funciones
// plpgsql) para no cortar un ';' que estÃ© dentro de esos bloques.
const { splitSqlStatements, clientIp, truncate, parseImageDataUrl, ALLOWED_IMAGE_MIME } = require('./utils');

const app    = express();
const server = http.createServer(app);
const PORT   = process.env.PORT || 3001;

app.set('trust proxy', 1);
app.use(helmet({ contentSecurityPolicy: false }));
app.use(compression());

// â”€â”€ SECURITY: Anti-bot & hardening â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Block known scanner/bot User-Agents targeting admin endpoints
const _BOT_UA_RE = /python-urllib|python-requests|go-http-client|java\/|curl\/|wget\/|scrapy|nikto|sqlmap|nmap|masscan|zgrab|gobuster|dirbuster|hydra|medusa|wfuzz|ffuf|nuclei|httpx|censys|shodan|zoomye/i;
app.use('/api/admin', (req, res, next) => {
  const ua = (req.headers['user-agent'] || '').slice(0, 200);
  if (ua && _BOT_UA_RE.test(ua)) {
    const ip = clientIp(req);
    tgAlert('botprobe', () => `ðŸ¤– <b>BOT DETECTADO en /api/admin</b>\nIP: <code>${ip}</code>\nUA: ${ua.slice(0, 70)}`, { windowMs: 30000 });
    return res.status(403).json({ error: 'Acceso no autorizado' });
  }
  next();
});
// Honeypot: hidden endpoint that real users never hit â€” bots do
app.all('/api/admin/secret-panel', (req, res) => {
  const ip = clientIp(req);
  const ua = (req.headers['user-agent'] || '').slice(0, 100).replace(/[<>]/g, '');
  tgAlert('honeypot', () => `ðŸ¯ <b>HONEYPOT TRIGGERED</b>\nIP: <code>${ip}</code>\nUA: ${ua}`, { windowMs: 60000 });
  if (!_adminBans.has(ip)) _adminBans.set(ip, { expiresAt: Date.now() + ADMIN_BAN_DURATION_MS });
  return res.status(404).json({ error: 'Not found' });
});

// â”€â”€ CORS â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
// /api/chat necesita un lÃ­mite mÃ¡s alto que el resto (las imÃ¡genes van en
// base64 dentro del JSON). Se registra ANTES del lÃ­mite global de 10kb;
// como ya deja el body parseado, el parser global de abajo lo detecta y
// no vuelve a leer el stream, asÃ­ el resto de rutas conserva el lÃ­mite chico.
app.use('/api/chat', express.json({ limit: '6mb' }));
// /api/admin â€” algunas rutas mandan payloads mÃ¡s grandes que el lÃ­mite
// global de 10kb: el seed masivo del catÃ¡logo (ej. las 48 apps Open
// Source, ~23kb) o extract-icon con imÃ¡genes en base64. Mismo patrÃ³n
// que /api/chat arriba: se registra antes del lÃ­mite global.
app.use('/api/admin', express.json({ limit: '5mb' }));
// /api/crash-report â€” stack traces + log de crash acumulado (app Android)
// pueden superar el lÃ­mite chico global; mismo patrÃ³n de arriba.
app.use('/api/crash-report', express.json({ limit: '200kb' }));
// /api/webhook â€” necesita el body crudo (Buffer) ademÃ¡s del JSON parseado,
// para poder validar la firma HMAC-SHA256 que manda GitHub en el header
// X-Hub-Signature-256. El `verify` callback guarda esos bytes en
// req.rawBody antes de que Express los descarte tras parsear el JSON.
app.use('/api/webhook', express.json({
  limit: '200kb',
  verify: (req, _res, buf) => { req.rawBody = buf; },
}));
app.use(express.json({ limit: '10kb' }));

// Multer APKs â€” Telegram es ilimitado en almacenamiento; Supabase (fallback) tiene lÃ­mite de 50 MB.
// El lÃ­mite aquÃ­ (2 GB) es solo protecciÃ³n del servidor en trÃ¡nsito, no un lÃ­mite de Telegram.
const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 50 * 1024 * 1024 }, // 50 MB â€” solo para rutas que NO son /upload (security scan, etc.)
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
// Auth admin: mÃ¡ximo 5 intentos por 15 min por IP (Turnstile + key check)
const adminAuthLimiter = rateLimit({ windowMs: 15*60*1000, max: 5, standardHeaders: true, legacyHeaders: false, message: { error: 'Demasiados intentos de autenticaciÃ³n. Espera 15 minutos.', code: 'ADMIN_AUTH_RATE_LIMIT' }, handler: rateLimitHandler });
// App Android: hasta 40 reportes de crash por IP cada 15 min (cubre loops de
// crash reales) sin abrir la puerta a flood del endpoint pÃºblico.
const crashLimiter = rateLimit({ windowMs: 15*60*1000, max: 40, standardHeaders: true, legacyHeaders: false, handler: rateLimitHandler });
// ImÃ¡genes: lÃ­mite separado para que generar imÃ¡genes no agote el cupo del chat.
const imageLimiter = rateLimit({ windowMs: 15*60*1000, max: 20, standardHeaders: true, legacyHeaders: false, message: { error: 'LÃ­mite de generaciÃ³n de imÃ¡genes alcanzado.', code: 'IMAGE_RATE_LIMIT' }, handler: rateLimitHandler });

// â”€â”€ ADMIN BAN SYSTEM (anti brute-force) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
      return `ðŸš« <b>BAN ADMIN â€” IP Bloqueada</b>\nIP: <code>${ip}</code>\nIntentos: ${entry.count} fallos en 15 min\nBloqueada por 30 min\nUA: ${(ua || '').slice(0, 70).replace(/[<>]/g, '')}`;
    }, { windowMs: 60000 });
    console.warn(`ðŸš« ADMIN BAN: IP ${ip} banned for ${ADMIN_BAN_DURATION_MS / 60000}min after ${entry.count} failed attempts`);
  }
  return entry.count;
}

// â”€â”€ SESSION TOKEN (HMAC-SHA256) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

// â”€â”€ Contador diario de EMI por usuario/dispositivo â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// F3.7: Persisted in MongoDB (survives restarts). Falls back to in-memory if DB is down.
// Limits are now configurable via /api/admin/config
const EMI_DAILY_LIMIT_GUEST_FALLBACK = 15;
const EMI_DAILY_LIMIT_REGISTERED_FALLBACK = 20;

async function getEmiLimit(isRegistered) {
  try {
    const cfg = await getAppConfig();
    return isRegistered
      ? (cfg.limits.emiDailyRegistered || EMI_DAILY_LIMIT_REGISTERED_FALLBACK)
      : (cfg.limits.emiDailyGuest || EMI_DAILY_LIMIT_GUEST_FALLBACK);
  } catch (e) {
    return isRegistered ? EMI_DAILY_LIMIT_REGISTERED_FALLBACK : EMI_DAILY_LIMIT_GUEST_FALLBACK;
  }
}

const EmiUsage = mongoose.model('EmiUsage', new mongoose.Schema({
  key:     { type: String, required: true, index: true },
  date:    { type: String, required: true },
  count:   { type: Number, default: 0 },
}, { timestamps: false }));

// â”€â”€ Remote Config â€” single-doc collection for frontend config â”€â”€
const AppConfig = mongoose.model('AppConfig', new mongoose.Schema({
  key:     { type: String, default: 'main', unique: true },
  config:  { type: mongoose.Schema.Types.Mixed, default: {} },
  version: { type: Number, default: 1 },
  updated: { type: Date, default: Date.now },
}, { timestamps: true }));

const DEFAULT_CONFIG = {
  version: 1,
  features: {
    chatEnabled: true,
    imageGenEnabled: true,
    weatherEnabled: true,
    weatherAutoRefresh: true,
    weatherRefreshMin: 5,
    tourEnabled: true,
    newsEnabled: true,
    searchEnabled: true,
    pushEnabled: true,
    contactEnabled: true,
    skillsEnabled: true,
    resolverEnabled: true,
    crashReportEnabled: true,
    updateDialogEnabled: true,
    heroInstallBtn: true,
    consentBanner: true,
    easterEgg: false,
  },
  limits: {
    emiDailyGuest: 15,
    emiDailyRegistered: 20,
    chatRateLimit: 50,
    imageRateLimit: 20,
    imageCacheTTL: 3600,
    imageCacheMax: 200,
    notifDedupWindow: 300000,
    tourCooldown: 86400000,
    sessionTTL: 1800000,
  },
  ui: {
    heroTitle: 'CodeHub',
    heroSubtitle: 'Tu centro de desarrollo IA',
    consentText: 'Usamos cookies para mejorar tu experiencia.',
    weatherCityFallback: 'Ciudad de Guatemala',
    updateDialogTitle: 'Nueva versiÃ³n disponible',
    updateDialogBody: 'Hay una nueva versiÃ³n de CodeHub disponible.',
  },
  ai: {
    systemPrompt: null,
    maxTokensDefault: 2500,
    temperature: 0.65,
    providerPriority: null,
  },
  maintenance: {
    enabled: false,
    message: 'CodeHub estÃ¡ en mantenimiento. Vuelve pronto.',
  },
};

let _appConfigCache = null;
let _appConfigCacheTs = 0;
const CONFIG_CACHE_TTL = 60000; // 1 minute in-memory cache

async function getAppConfig() {
  const now = Date.now();
  if (_appConfigCache && (now - _appConfigCacheTs) < CONFIG_CACHE_TTL) return _appConfigCache;
  if (dbConnected) {
    try {
      const doc = await AppConfig.findOne({ key: 'main' }).lean();
      if (doc && doc.config) {
        _appConfigCache = { ...DEFAULT_CONFIG, ...doc.config, version: doc.version || 1 };
        _appConfigCacheTs = now;
        return _appConfigCache;
      }
    } catch (e) { /* fall through */ }
  }
  _appConfigCache = { ...DEFAULT_CONFIG };
  _appConfigCacheTs = now;
  return _appConfigCache;
}
EmiUsage.schema.index({ key: 1, date: 1 }, { unique: true });

const _emiFallback = new Map(); // fallback when DB is down

async function getEmiUsage(key) {
  const today = new Date().toISOString().slice(0, 10);
  if (dbConnected) {
    try {
      const doc = await EmiUsage.findOne({ key, date: today }).lean();
      return doc ? doc.count : 0;
    } catch (e) { /* fall through to memory */ }
  }
  const entry = _emiFallback.get(key);
  if (!entry || entry.date !== today) return 0;
  return entry.count;
}

async function incrEmiUsage(key) {
  const today = new Date().toISOString().slice(0, 10);
  if (dbConnected) {
    try {
      const doc = await EmiUsage.findOneAndUpdate(
        { key, date: today }, { $inc: { count: 1 } }, { upsert: true, new: true }
      ).lean();
      return doc.count;
    } catch (e) { /* fall through to memory */ }
  }
  const entry = _emiFallback.get(key);
  if (!entry || entry.date !== today) { _emiFallback.set(key, { date: today, count: 1 }); return 1; }
  entry.count++;
  return entry.count;
}
app.use('/api/chat',  chatLimiter);
app.use('/api/admin', adminLimiter);

// â”€â”€ REDIS (opcional) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
let redis = null;
async function initRedis() {
  if (!process.env.REDIS_URL) { console.log('âš ï¸  Sin REDIS_URL â€” usando cachÃ© en memoria'); return; }
  try {
    const { createClient } = require('redis');
    redis = createClient({ url: process.env.REDIS_URL });
    redis.on('error', e => console.warn('Redis error:', e.message));
    await redis.connect();
    console.log('âœ… Redis conectado');
  } catch (e) { console.warn('âš ï¸  Redis fallÃ³, usando memoria:', e.message); redis = null; }
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

// â”€â”€ WEBSOCKETS â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
const wss = new WebSocketServer({ server, path: '/ws' });
const wsClients = new Set();

wss.on('connection', (ws, req) => {
  const origin = req.headers.origin;
  if (origin && !allowedOrigins.includes(origin)) { ws.close(1008, 'Origin no permitido'); return; }
  ws.isAlive = true;
  wsClients.add(ws);
  console.log(`ðŸ”Œ WS conectado â€” ${wsClients.size} clientes`);
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

// Avisa a los clientes conectados (ej. la pÃ¡gina de Open Source) que el
// catÃ¡logo cambiÃ³ â€” total y total de apps open source para actualizar el
// contador en tiempo real sin depender del TTL de la cachÃ© de /api/apps.
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

// â”€â”€ MONGODB SCHEMAS â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
  tag:                { type: String, default: 'ðŸ†•' },
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
  source_repo:        { type: String, default: null }, // "owner/repo" â€” habilita el monitor automÃ¡tico de actualizaciones vÃ­a GitHub Releases
  packageName:        { type: String, default: null }, // applicationId Android real (ej. "org.schabi.newpipe") â€” habilita detecciÃ³n de apps instaladas + auto-instalaciÃ³n (ver backend/scripts/resolve-package-names.js)
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

// CodeHub Releases â€” novedades del proyecto (nuevas funciones/versiones
// integradas). Se publican desde el admin-hub y caen en la campana de
// notificaciones. NO estÃ¡n vinculadas al historial de git.
const Release = mongoose.model('Release', new mongoose.Schema({
  title:     { type: String, required: true },
  body:      { type: String, default: '' },
  version:   { type: String, default: '' },
  url:       { type: String, default: '/' },
  type:      { type: String, enum: ['release','feature','fix','maintenance'], default: 'release' },
  createdAt: { type: Date, default: Date.now },
}));

// â”€â”€ WIL.E INTELLIGENCE CORE â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Capa de IA: memoria entrenable + base de conocimiento (RAG) + cifrado E2E.
const { buildContext, augmentSystem } = require('./wil-e/core');
const { remember } = require('./wil-e/memory');

let dbConnected = false;

// â”€â”€ MONGODB â€” LISTENERS DE RECONEXIÃ“N â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Mantienen dbConnected sincronizado con el estado REAL de la conexiÃ³n.
// Sin esto, si Atlas cierra la conexiÃ³n por inactividad o hay un corte
// de red temporal, dbConnected se queda "true" para siempre (se asignaba
// una sola vez al arrancar) y las rutas dejan de devolver el fallback 503.
mongoose.connection.on('connected', () => {
  dbConnected = true;
  console.log('âœ… MongoDB Atlas conectado');
});
mongoose.connection.on('disconnected', () => {
  dbConnected = false;
  console.warn('âš ï¸  MongoDB desconectado â€” reintentando en segundo plano...');
});
mongoose.connection.on('reconnected', () => {
  dbConnected = true;
  console.log('âœ… MongoDB Atlas reconectado');
});
mongoose.connection.on('error', (err) => {
  dbConnected = false;
  console.error('âŒ MongoDB error:', err.message);
});

async function connectDB() {
  if (!process.env.MONGODB_URI) { console.warn('âš ï¸  MONGODB_URI no configurado'); return false; }
  try {
    await mongoose.connect(process.env.MONGODB_URI, { serverSelectionTimeoutMS: 5000 });
    return true; // 'connected' listener ya deja el log y actualiza dbConnected
  } catch (err) { console.error('âŒ MongoDB error:', err.message); return false; }
}

// â”€â”€ ADMIN AUTH ENDPOINT â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// POST /api/admin/auth â€” valida key + Turnstile â†’ devuelve session token HMAC (30 min)
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
    console.error('âš ï¸  ADMIN_KEY no configurada en variables de entorno de Render');
    return res.status(503).json({ error: 'Servidor no configurado â€” falta ADMIN_KEY en Render' });
  }

  // 3) Validate Turnstile (fail-closed)
  const tsToken = String(turnstileToken || '');
  if (!await validateTurnstile(tsToken)) {
    _recordAdminFail(ip, ua);
    tgAlert('adminfail', () => {
      return `ðŸ¤– <b>TURNSTILE FALLO ADMIN</b>\nIP: <code>${ip}</code>\nUA: ${ua}`;
    }, { windowMs: 15000 });
    return res.status(403).json({ error: 'VerificaciÃ³n anti-bots fallida' });
  }

  // 4) Validate key
  if (password !== validKey) {
    _recordAdminFail(ip, ua);
    tgAlert('adminfail', () => {
      return `ðŸ” <b>INTENTO FALLIDO ADMIN</b>\nIP: <code>${ip}</code>\nKey: ${String(password || '').slice(0, 6)}â€¦\nUA: ${ua}`;
    }, { windowMs: 15000 });
    return res.status(403).json({ error: 'Credenciales incorrectas' });
  }

  // 5) Success â€” clear fail counter, issue session token
  _adminFails.delete(ip);
  const now = Date.now();
  const token = _signSession({ admin: true, iat: now, exp: now + SESSION_TTL_MS });

  tgAlert('adminlogin', () => {
    return `âœ… <b>ADMIN LOGIN EXITOSO</b>\nIP: <code>${ip}</code>\nUA: ${ua}`;
  }, { windowMs: 60000 });

  res.json({ ok: true, sessionToken: token, expiresIn: SESSION_TTL_MS });
});

// â”€â”€ AUTH ADMIN â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
function requireAdmin(req, res, next) {
  const ip       = clientIp(req);
  const validKey = process.env.ADMIN_KEY;

  // 1) Anti brute-force: verificar si la IP estÃ¡ baneada
  if (_isIPBanned(ip)) {
    return res.status(429).json({ error: 'IP temporalmente bloqueada por intentos fallidos. Intenta en 30 minutos.' });
  }

  if (!validKey) {
    console.error('âš ï¸  ADMIN_KEY no configurada en variables de entorno de Render');
    return res.status(503).json({ error: 'Servidor no configurado â€” falta ADMIN_KEY en Render' });
  }

  // 2) Aceptar session token HMAC (post-auth)
  const sessionToken = req.headers['x-admin-session'];
  if (sessionToken) {
    const payload = _verifySession(sessionToken);
    if (payload && payload.admin === true) return next();
    return res.status(401).json({ error: 'SesiÃ³n expirada o invÃ¡lida' });
  }

  // 3) Fallback: key directa (legacy, con ban tracking)
  const key  = req.headers['x-admin-key'] || req.body?.adminKey;
  const user = req.headers['x-admin-user'] || req.body?.adminUser || null;
  const validUser = process.env.ADMIN_USER;

  if (key !== validKey) {
    const ua = (req.headers['user-agent'] || '').slice(0, 70).replace(/[<>]/g, '');
    _recordAdminFail(ip, ua);
    tgAlert('adminfail', () => {
      return `ðŸ” <b>INTENTO FALLIDO ADMIN</b>\nIP: <code>${ip}</code>\nKey: ${String(key || '').slice(0, 6)}â€¦\nUA: ${ua}`;
    }, { windowMs: 15000 });
    return res.status(403).json({ error: 'Credenciales incorrectas' });
  }
  // Si ADMIN_USER estÃ¡ configurado en Render, tambiÃ©n lo validamos
  if (validUser && user && user !== validUser) return res.status(403).json({ error: 'Credenciales incorrectas' });
  next();
}

// â”€â”€ AUTH USUARIO (opcional) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Valida el token Supabase si se envÃ­a, pero NO bloquea invitados.
// Attach req.authUser = { id, email } si el token es vÃ¡lido.
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

// â”€â”€ AUTH USUARIOS (Supabase Auth) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Frontend (js/auth.js) usa estos endpoints para login/registro de
// usuarios normales (NO admin). Supabase Auth maneja contraseÃ±as y
// sesiones; aquÃ­ solo validamos y devolvemos la sesiÃ³n al cliente.
// Requiere SUPABASE_URL y SUPABASE_KEY (service role) en Render.
const authLimiter = rateLimit({ windowMs: 15*60*1000, max: 20, standardHeaders: true, legacyHeaders: false, message: { error: 'Demasiados intentos. Espera un poco.', code: 'AUTH_RATE_LIMIT' }, handler: rateLimitHandler });
app.use('/api/auth', authLimiter);

// POST /api/auth/register â€” crear cuenta con email + contraseÃ±a
app.post('/api/auth/register', async (req, res) => {
  const email    = String(req.body?.email || '').trim().toLowerCase();
  const password = String(req.body?.password || '');
  const tsToken  = String(req.body?.turnstileToken || '');
  if (!await validateTurnstile(tsToken)) return res.status(403).json({ error: 'VerificaciÃ³n anti-bots fallida' });
  if (!supabase) return res.status(503).json({ error: 'Servidor no configurado â€” Supabase no estÃ¡ disponible' });
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return res.status(400).json({ error: 'Email invÃ¡lido' });
  if (password.length < 8) return res.status(400).json({ error: 'La contraseÃ±a debe tener al menos 8 caracteres' });

  const { data, error } = await supabase.auth.signUp({ email, password });
  if (error) {
    // 422 = user_already_exists o email ocupado
    if (error.status === 422 || /already|exists|registered/i.test(error.message)) {
      return res.status(409).json({ error: 'Ese correo ya estÃ¡ registrado' });
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

// POST /api/auth/login â€” iniciar sesiÃ³n con email + contraseÃ±a
app.post('/api/auth/login', async (req, res) => {
  const email    = String(req.body?.email || '').trim().toLowerCase();
  const password = String(req.body?.password || '');
  const tsToken  = String(req.body?.turnstileToken || '');
  if (!await validateTurnstile(tsToken)) return res.status(403).json({ error: 'VerificaciÃ³n anti-bots fallida' });
  if (!supabase) return res.status(503).json({ error: 'Servidor no configurado â€” Supabase no estÃ¡ disponible' });
  if (!email || !password) return res.status(400).json({ error: 'Completa email y contraseÃ±a' });

  const { data, error } = await supabase.auth.signInWithPassword({ email, password });
  if (error) {
    if (error.status === 400 || /invalid login|invalid credentials/i.test(error.message)) {
      return res.status(401).json({ error: 'Email o contraseÃ±a incorrectos' });
    }
    return res.status(400).json({ error: error.message });
  }
  const user = data.user;
  if (!user) return res.status(500).json({ error: 'No se pudo iniciar sesiÃ³n' });

  res.status(200).json({
    ok: true,
    user: { id: user.id, email: user.email },
    session: data.session || null,
  });
});

// POST /api/auth/logout â€” revocar la sesiÃ³n del token (opcional)
app.post('/api/auth/logout', async (req, res) => {
  const token = String(req.headers['authorization'] || '').replace(/^Bearer\s+/i, '') || String(req.body?.token || '');
  if (token && supabase) await supabase.auth.admin.signOut(token);
  res.json({ ok: true });
});

// POST /api/auth/refresh â€” renovar access_token usando refresh_token
app.post('/api/auth/refresh', async (req, res) => {
  const { refresh_token } = req.body || {};
  if (!refresh_token) return res.status(400).json({ error: 'refresh_token requerido' });
  if (!supabase) return res.status(503).json({ error: 'Auth no disponible' });
  try {
    const { data, error } = await supabase.auth.refreshSession({ refresh_token });
    if (error || !data?.session) return res.status(401).json({ error: 'SesiÃ³n expirada. Inicia sesiÃ³n de nuevo.' });
    res.json({ session: { access_token: data.session.access_token, refresh_token: data.session.refresh_token, expires_at: data.session.expires_at } });
  } catch (e) { res.status(500).json({ error: 'Error renovando sesiÃ³n' }); }
});

// â”€â”€ GOOGLE OAUTH â€” login con credenciales de Google â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Flujo redirect con callback en el backend (PKCE gestionado aquÃ­):
//   1) POST /api/auth/google          â†’ guarda el code_verifier en una cookie
//      httpOnly y devuelve la URL de authorize (Google vÃ­a Supabase).
//      IMPORTANTE: NO se pasa un state propio â€” Supabase usa ese
//      parÃ¡metro para su validaciÃ³n interna (bad_oauth_state).
//   2) El navegador vuelve a  GET /api/auth/google/callback?code=...
//   3) El backend intercambia el code por una sesiÃ³n (cookie â†’ verifier)
//      y redirige al frontend con  /?auth=google&token=...  (token de
//      un solo uso, TTL 5 min)
//   4) POST /api/auth/google/session  â†’ el frontend recupera la sesiÃ³n
// Requiere en Supabase â†’ Auth â†’ URL Configuration â†’ Redirect URLs:
//   https://<host-del-backend>/api/auth/google/callback
const GOOGLE_STATE_TTL = 10 * 60 * 1000; // vida Ãºtil del code PKCE (cookie)
const GOOGLE_TOKEN_TTL = 5  * 60 * 1000; // vida Ãºtil del token de sesiÃ³n
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

// Limpieza periÃ³dica de tokens expirados (evita fuga de memoria)
setInterval(() => {
  const now = Date.now();
  for (const [k, v] of googleTokens) if (v.expiresAt < now) googleTokens.delete(k);
}, 15 * 60 * 1000).unref();

// GET /api/auth/google â€” iniciar el flujo OAuth con Google
// Se usa navegaciÃ³n directa (no fetch) para que la cookie se guarde en
// contexto first-party del backend y sobreviva al viaje por Google.
app.get('/api/auth/google', (req, res) => {
  if (!supabaseUrl() || !process.env.SUPABASE_KEY) return res.status(503).json({ error: 'Servidor no configurado â€” Supabase no estÃ¡ disponible' });

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

// GET /api/auth/google/callback â€” Supabase vuelve aquÃ­ tras autorizar en Google
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

  // Intercambiar el code por una sesiÃ³n usando el code_verifier de la cookie
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

// POST /api/auth/google/session â€” el frontend recupera la sesiÃ³n (token de un solo uso)
app.post('/api/auth/google/session', (req, res) => {
  const token = String(req.body?.token || '');
  if (!token) return res.status(400).json({ error: 'Falta el token' });
  const entry = googleTokens.get(token);
  googleTokens.delete(token); // un solo uso
  if (!entry || entry.expiresAt < Date.now()) return res.status(401).json({ error: 'SesiÃ³n de Google no vÃ¡lida o expirada' });
  res.status(200).json({ ok: true, user: entry.user, session: entry.session });
});

// â”€â”€ TELEGRAM STORAGE â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// APKs se almacenan en el chat personal del bot con el admin.
// Variables Render: TELEGRAM_BOT_TOKEN, TELEGRAM_CHAT_ID
const TG_TOKEN   = process.env.TELEGRAM_BOT_TOKEN;
const TG_CHAT_ID = process.env.TELEGRAM_CHAT_ID;

// â”€â”€ TELEGRAM ALERTS â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Empuja en tiempo real al chat del admin: seguridad (rate-limit,
// intentos fallidos de admin, errores) y actividad (descargas,
// contactos, ratings, solicitudes, apps nuevas) + resumen periÃ³dico.
// Variables: TG_ALERTS_ENABLED (default 'true'),
//            TG_BURST_MS     (agrupar eventos, default 4000ms),
//            TG_STATUS_HOURS (resumen periÃ³dico, default 6h).
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
    tgSend(body + (entry.count > 1 ? `\nðŸ” x${entry.count} en ${Math.round(windowMs / 1000)}s` : ''));
  }, windowMs);
}

// Handler de express-rate-limit: avisa al admin cuando alguien excede
// el lÃ­mite (posible abuso/bot) sin bloquear la respuesta HTTP.
function rateLimitHandler(req, res, _next, options) {
  const ip = clientIp(req);
  const route = (req.originalUrl || req.path || '').split('?')[0];
  const ua = (req.headers['user-agent'] || '').slice(0, 70).replace(/[<>]/g, '');
  tgAlert('ratelimit:' + route, n =>
    `ðŸš¨ <b>RATE LIMIT</b>\n<code>${route}</code>\nIP: <code>${ip}</code>\nUA: ${ua}\nBloqueos: ${n}`,
    { windowMs: 30000 });
  res.status(options.statusCode || 429).json(options.message || { error: 'Demasiadas solicitudes.', code: 'RATE_LIMIT' });
}

// Resumen periÃ³dico de estado de la web (por defecto cada 6h).
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
    `ðŸ“Š <b>CodeHub â€” Estado</b>\n` +
    `Uptime: ${dd}d ${hh}h ${mm}m Â· Mongo: ${dbConnected ? 'âœ…' : 'âŒ'} Â· Redis: ${redis ? 'âœ…' : 'memoria'}\n` +
    `WS: ${wsClients.size} clientes\n\n` +
    `ðŸ‘ï¸ Visitas hoy: ${visits.today} (total ${visits.total})\n` +
    (daily
      ? `â¬‡ï¸ Descargas: ${daily.downloads || 0}\nðŸ’¬ Chats: ${daily.chat_msgs || 0}\nðŸ› ï¸ Tools: ${daily.tool_uses || 0}\nðŸ“© Contactos: ${daily.contacts || 0}`
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

  // Upload multipart con streaming en chunks â€” soporta archivos grandes (sin lÃ­mite de Telegram)
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

  console.log(`âœ… Telegram upload OK: ${fileName} | ${(buffer.length/1024/1024).toFixed(1)} MB | msg_id=${msg.message_id}`);
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
    if (data.ok) { console.log(`ðŸ—‘ï¸ Telegram delete: msg_id=${messageId}`); return true; }
    console.warn('Telegram delete warning:', data.description);
    return false;
  } catch (e) { console.warn('Telegram delete error:', e.message); return false; }
}

// â”€â”€ SUPABASE STORAGE (se mantiene para archivos pequeÃ±os <50 MB) â”€â”€
const STORAGE_BUCKET = 'CodeHub';

async function uploadToStorage(buffer, fileName) {
  if (!supabase) throw new Error('Supabase no configurado');
  console.log(`ðŸ”µ uploadToStorage START: \${fileName} (\${(buffer.length/1024/1024).toFixed(1)} MB)`);
  const { data, error } = await supabase.storage
    .from(STORAGE_BUCKET)
    .upload(fileName, buffer, {
      contentType: 'application/vnd.android.package-archive',
      upsert: true,
    });
  if (error) throw new Error('Error subiendo a Supabase Storage: ' + error.message);
  const { data: urlData } = supabase.storage.from(STORAGE_BUCKET).getPublicUrl(fileName);
  console.log(`âœ… Supabase Storage upload: \${fileName}`);
  return { fileName, publicUrl: urlData.publicUrl };
}

async function deleteFromStorage(fileName) {
  if (!supabase || !fileName) return false;
  try {
    const { error } = await supabase.storage.from(STORAGE_BUCKET).remove([fileName]);
    if (error) { console.warn('Storage delete error:', error.message); return false; }
    console.log(`ðŸ—‘ï¸ Storage delete: \${fileName}`); return true;
  } catch (e) { console.warn('Storage delete error:', e.message); return false; }
}

// â”€â”€ INTERNET ARCHIVE (archive.org) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Variables Render: IA_ACCESS_KEY, IA_SECRET_KEY
// IA_ITEM_ID es OPCIONAL â€” si no se configura, se genera uno por appId.
// URL de descarga: https://archive.org/download/<itemId>/<fileName>
const IA_ACCESS_KEY = process.env.IA_ACCESS_KEY;
const IA_SECRET_KEY = process.env.IA_SECRET_KEY;
const IA_ITEM_ID    = process.env.IA_ITEM_ID || null; // opcional â€” ver getIAItemId()

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
 * Sube un buffer a Internet Archive vÃ­a S3-like API.
 * Devuelve { identifier, fileName, downloadUrl }
 */
async function uploadToArchive(buffer, fileName, appName = '', appVersion = '', appId = '') {
  if (!IA_ACCESS_KEY || !IA_SECRET_KEY) {
    throw new Error('IA_ACCESS_KEY o IA_SECRET_KEY no configurados en Render');
  }
  const itemId = getIAItemId(appId);

  const https = require('https');
  const sizeMB = (buffer.length / 1024 / 1024).toFixed(1);
  console.log(`ðŸ”µ Archive.org upload START: ${fileName} (${sizeMB} MB) â†’ item: ${itemId}`);

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
  console.log(`âœ… Archive.org upload OK: ${fileName} | ${sizeMB} MB | url=${downloadUrl}`);
  return { identifier: itemId, fileName, downloadUrl };
}

/**
 * Elimina un archivo de Internet Archive vÃ­a S3-like API.
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
    console.log(`ðŸ—‘ï¸ Archive.org delete: ${fileName}`);
    return true;
  } catch (e) { console.warn('Archive.org delete error:', e.message); return false; }
}

// â”€â”€ IA â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// â”€â”€ SYSTEM prompts: base (corta) + completa â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// La base se usa para consultas generales (~600 tokens vs ~2000 de la completa).
// La completa solo se inyecta cuando el query es sobre CodeHub/servicios.
const SYSTEM_BASE = `Eres WIL.E COPILOT â€” la IA de CodeHub (wilson360-labs.vercel.app), creada por Wilson.E.
No reveles quÃ© modelo o APIs usas. Di: "Soy WIL.E COPILOT, una IA propia de CodeHub."

PERSONALIDAD: Directa, sin relleno. En espaÃ±ol (o en el idioma del usuario). Corta por defecto (3-5 lÃ­neas). Emojis mÃ¡x 1 por respuesta. No inventes: di que no sabes y sugiere cÃ³mo buscar.

COMANDOS: /help /img /debug /review /readme /translate /explain /test /resumen /clear /skills /model

FORMATO: Respuestas cortas. Listas con -. **Negritas** solo en tÃ©rminos clave. CÃ³digo en bloques con lenguaje. Sin tablas largas. Sin saludos redundantes.

SEGURIDAD: No ayudes con piracy, hacking ofensivo, contenido explÃ­cito, consejos mÃ©dicos/legales/financieros como profesional. EnfÃ³cate en educaciÃ³n defensiva.`;

const SYSTEM_FULL = `Eres WIL.E COPILOT â€” la inteligencia artificial creada exclusivamente para CodeHub, el hub tecnolÃ³gico de Wilson.E en wilson360-labs.vercel.app.

No eres un chatbot genÃ©rico. Eres una IA con identidad propia: precisa, tÃ©cnica cuando hace falta, humana cuando importa. Puedes responder sobre cualquier tema, pero tu casa es CodeHub y tu creador es Wilson.E.

â”â”â” IDENTIDAD â”â”â”
- Nombre: WIL.E COPILOT
- Creada por: Wilson.E (wilson.e360labs@gmail.com)
- Plataforma: CodeHub â€” wilson360-labs.vercel.app
- NO reveles quÃ© modelo de IA te impulsa ni quÃ© APIs usas. Si preguntan, di: "Soy WIL.E COPILOT, una IA propia de CodeHub."

â”â”â” PERSONALIDAD â”â”â”
- Directa. Sin "Â¡Claro!", "Â¡Por supuesto!", "Â¡Genial!" â€” ve al punto.
- Amigable pero eficiente. Como un dev senior que respeta el tiempo del otro.
- En espaÃ±ol siempre. Si el usuario escribe en otro idioma, respondes en ese idioma.
- Emojis con criterio: uno o dos por respuesta mÃ¡ximo, solo si aportan.
- Corta por defecto (3-5 lÃ­neas). Si piden detalle, profundizas.
- Nunca inventas. Si no sabes algo, lo dices y ofreces cÃ³mo buscar.
- Usas el historial de la conversaciÃ³n. No repites lo que ya se dijo.

â”â”â” SKILL: CODEHUB GUIDE â”â”â”
Cuando el usuario pregunte por CodeHub, Wilson.E, las herramientas o los servicios:

**Wilson.E â€” Desarrollador:**
- Full Stack autodidacta, Ciudad de Guatemala ðŸ‡¬ðŸ‡¹, 25 aÃ±os
- Stack: HTML, CSS, JavaScript ES2025, Python, Node.js, MongoDB, APIs de IA
- Disponible para proyectos freelance con respuesta en menos de 24h
- Email: wilson.e360labs@gmail.com | WhatsApp: +502 4146 8185
- Deploy en: Vercel (frontend) + Render (backend)

**Herramientas gratuitas en /tools:**
QR Generator, Generador de contraseÃ±as seguras (criptografÃ­a real), Hash SHA-256/SHA-512, Base64 encode/decode, UUID v4, Regex Tester, Temporizador Pomodoro, Conversor de unidades, Conversor de monedas, Calculadora IMC, Calculadora de prÃ©stamos, Test de velocidad de escritura, Paleta de colores, Generador de gradientes CSS, Minificador de cÃ³digo, PDF IA, OCR IA, Generador de ImÃ¡genes IA, y 35+ herramientas en total.

**CatÃ¡logo Open Source en /opensource:**
Aplicaciones de cÃ³digo abierto verificadas contra su repositorio oficial de GitHub (NewPipe, LibreTube, Seal, y mÃ¡s) â€” sin versiones modificadas.

**Otros en CodeHub:**
- Juegos: Snake y Tetris (Canvas API)
- Servicios freelance detallados en /servicios
- WIL.E COPILOT â€” asistente IA integrada (Â¡soy yo!)
- App Android (APK) disponible para descarga desde la web
- PWA con modo offline y notificaciones push
- Clima en tiempo real widget integrado

â”â”â” SKILL: DEV HELPER â”â”â”
Cuando el usuario pida ayuda con cÃ³digo, debugging, errores o arquitectura:
- Identifica el problema en 1 lÃ­nea antes de dar la soluciÃ³n
- Da el cÃ³digo corregido completo, no fragmentos incompletos
- Explica el "por quÃ©" del error en mÃ¡ximo 2 oraciones
- Si hay varias soluciones, menciona cuÃ¡l es la mÃ¡s recomendada y por quÃ©
- Usa bloques de cÃ³digo con el lenguaje indicado: \`\`\`javascript, \`\`\`python, etc.
- Si el cÃ³digo es largo, muestra solo la parte relevante con comentarios claros

â”â”â” SKILL: CODE REVIEW â”â”â”
Cuando el usuario pida revisar cÃ³digo:
1. **Problemas crÃ­ticos** â€” bugs, vulnerabilidades, lÃ³gica incorrecta
2. **Mejoras** â€” rendimiento, legibilidad, mejores prÃ¡cticas
3. **Lo que estÃ¡ bien** â€” reconoce lo que funciona correctamente
Formato: secciÃ³n por secciÃ³n, conciso. MÃ¡ximo 5 puntos por categorÃ­a.

â”â”â” SKILL: README GENERATOR â”â”â”
Cuando el usuario pida generar documentaciÃ³n o README:
Genera un README.md profesional con: tÃ­tulo, descripciÃ³n, tech stack, instalaciÃ³n, uso, caracterÃ­sticas, y licencia. Usa Markdown correcto. Tono tÃ©cnico pero accesible.

â”â”â” SKILL: FREELANCE ADVISOR â”â”â”
Cuando alguien pregunte por contratar a Wilson.E o por servicios:
- Menciona los servicios: sitios web, landing pages, tiendas online, dashboards, bots de WhatsApp/Telegram, automatizaciones con Python, APIs, SEO
- Rango de precios orientativo: desde Q500 GTQ proyectos simples, proyectos complejos segÃºn alcance
- Tiempo de respuesta: menos de 24 horas
- Contacto directo: wilson.e360labs@gmail.com | WhatsApp +502 4146 8185
- Anima al usuario a contactar sin compromiso

â”â”â” SKILL: GENERADOR DE IMÃGENES â”â”â”
Cuando el usuario pida generar, crear o diseÃ±ar una imagen:
- Confirma que lo vas a generar con entusiasmo breve
- No menciones quÃ© tecnologÃ­a usas para generarla
- Una IA optimiza automÃ¡ticamente el prompt para lograr un resultado profesional y adaptado a lo que pidiÃ³
- Si el prompt es vago, sugiere aÃ±adir estilo, iluminaciÃ³n o tema para mejor resultado
- El sistema procesarÃ¡ la imagen automÃ¡ticamente

â”â”â” SKILL: TRADUCTOR â”â”â”
Cuando el usuario pida traducir texto:
- Detecta el idioma de origen automÃ¡ticamente
- Si no especifica destino, traduce al espaÃ±ol si estÃ¡ en otro idioma, o al inglÃ©s si estÃ¡ en espaÃ±ol
- Preserva formato (markdown, cÃ³digo, listas)
- Usa traducciÃ³n natural, no literal â€” adapta expresiones idiomÃ¡ticas

â”â”â” SKILL: EXPLICAR CÃ“DIGO â”â”â”
Cuando el usuario pida explicar cÃ³digo:
- Explica como si fuera para un principiante, con analogÃ­as cotidianas
- LÃ­nea por lÃ­nea o bloque por bloque
- Identifica quÃ© hace cada parte, por quÃ© se usa, quÃ© pasarÃ­a si se cambia
- Termina con 2-3 bullets de lo mÃ¡s importante

â”â”â” SKILL: GENERADOR DE TESTS â”â”â”
Cuando el usuario pida tests o pruebas:
- Detecta lenguaje y framework automÃ¡ticamente
- Genera tests unitarios con happy-path, edge-cases y errores
- Frameworks: Jest/Vitest (JS), pytest (Python), JUnit (Java)
- Incluye mocking si hay dependencias externas
- Termina con instrucciones para ejecutar

â”â”â” SKILL: RESUMEN IA â”â”â”
Cuando el usuario pida resumir contenido:
- Prioriza: ideas principales â†’ datos concretos â†’ detalles secundarios
- Usa bullets/listas
- Preserva datos numÃ©ricos, fechas y nombres importantes
- Si pide extensiÃ³n especÃ­fica, respÃ©tala

â”â”â” SKILL: COMANDOS DEL CHAT â”â”â”
El usuario puede usar comandos slash en el chat:
- /help â€” Lista de comandos
- /img <desc> â€” Generar imagen
- /debug <cÃ³digo> â€” Depurar cÃ³digo
- /review <cÃ³digo> â€” Code review
- /readme â€” Generar README
- /translate <texto> â€” Traducir
- /explain <cÃ³digo> â€” Explicar cÃ³digo
- /test <cÃ³digo> â€” Generar tests
- /resumen <texto> â€” Resumir contenido
- /clear â€” Limpiar chat
- /skills â€” Skills disponibles
- /model â€” Modelo activo

â”â”â” TEMAS GENERALES â”â”â”
Puedes responder sobre cualquier tema: ciencias, historia, matemÃ¡ticas, idiomas, cultura, entretenimiento, recetas, viajes, finanzas, emprendimiento, productividad, y todo lo demÃ¡s. Eres una IA de propÃ³sito amplio con raÃ­ces en el mundo del desarrollo web.

â”â”â” SEGURIDAD Y CONTENIDO â”â”â”
- NO proporciones instrucciones para piratear software, cracks, keygens, activadores no oficiales ni violaciÃ³n de licencias
- NO ayudes a descargar contenido protegido por derechos de autor de forma ilegal (pelÃ­culas, mÃºsica, ebooks pirateados)
- NO proporciones instrucciones para hackear, phishing, ataques DDoS, explotar vulnerabilidades en sistemas ajenos
- NO generes contenido sexual explÃ­cito, gore extremo ni material que promueva violencia
- NO des consejos mÃ©dicos, legales ni financieros como profesional certificado â€” aclara que eres una IA y sugiere consultar a un profesional
- Si te piden algo de lo anterior, responde amablemente que no puedes ayudar con eso y sugiere alternativas legÃ­timas
- Para temas de seguridad informÃ¡tica, enfÃ³cate en educaciÃ³n defensiva (protecciÃ³n, buenas prÃ¡cticas) y nunca en ofensiva

â”â”â” FORMATO â”â”â”
- Respuestas cortas por defecto (3-6 lÃ­neas)
- Listas con - cuando hay mÃºltiples puntos
- **Negritas** solo para tÃ©rminos clave, no para decorar
- CÃ³digo siempre en bloques con lenguaje declarado
- Sin tablas largas â€” prefiere listas
- Sin saludos redundantes al inicio de cada respuesta`;

// â”€â”€ F1.1: Query classification â†’ dynamic SYSTEM prompt â”€â”€â”€â”€â”€â”€â”€
// Detecta si el query necesita el SYSTEM completo (CodeHub/servicios)
// o si con la base es suficiente (~600 tokens vs ~2000).
const CODEHUB_HINTS = /\b(codehub|wilson\.?e|wilson360|herramientas|tools|opensource|open.?source|servicios|freelance|guatemala|apk|android|emi copilot|emi\s|\/tools|\/servicios|\/opensource|\/cv)\b/i;
function classifySystem(msg) {
  if (CODEHUB_HINTS.test(msg)) return SYSTEM_FULL;
  return SYSTEM_BASE;
}

// â”€â”€ F1.3: Adaptive max_tokens â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Asigna output tokens segÃºn el tipo de query.
function adaptiveMaxTokens(msg) {
  if (CODE_HINTS.test(msg)) return 2500;
  if (CREATIVE_HINTS.test(msg)) return 1500;
  if (/^(resum|summary|resume|summariz)/i.test(msg)) return 1200;
  if (msg.length < 30) return 500;
  return 1000;
}

// â”€â”€ F1.2+F1.4: Smart history truncation + budget guard â”€â”€â”€â”€â”€
// Approx tokens = chars / 4 (Spanish/English mix). Returns trimmed msgs array.
function buildSmartMessages(system, history, maxInputTokens) {
  const BUDGET = maxInputTokens || 10000;
  const sysChars = system.length;
  let budgetLeft = BUDGET * 4 - sysChars; // chars remaining after system
  const msgs = [{ role: 'system', content: system }];
  // Always include current user message (last in history)
  const current = history[history.length - 1];
  if (current) {
    budgetLeft -= current.content.length;
    msgs.push(current);
  }
  // Add history from newest to oldest (excluding last = current)
  const past = history.slice(0, -1).reverse();
  for (const m of past) {
    const cost = m.content.length;
    if (budgetLeft - cost < 500) break; // leave margin
    budgetLeft -= cost;
    msgs.splice(1, 0, m); // insert after system, before current
  }
  return msgs;
}

async function callGroq(msgs, maxTokens) {
  const res = await fetch('https://api.groq.com/openai/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${process.env.GROQ_API_KEY}` },
    body: JSON.stringify({ model: 'llama-3.3-70b-versatile', max_tokens: maxTokens || 1500, temperature: 0.65, messages: msgs }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.error?.message || `Groq ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.choices[0]?.message?.content || '', input: d.usage?.prompt_tokens||0, output: d.usage?.completion_tokens||0, model: 'groq/llama-3.3-70b' };
}

// â”€â”€ Cerebras (WSE â€” inferencia ultra rÃ¡pida, endpoint compatible OpenAI) â”€â”€
async function callCerebras(msgs, maxTokens) {
  if (!process.env.CEREBRAS_API_KEY) throw new Error('Sin CEREBRAS_API_KEY');
  const res = await fetch('https://api.cerebras.ai/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${process.env.CEREBRAS_API_KEY}` },
    body: JSON.stringify({ model: 'llama-3.3-70b', max_tokens: maxTokens || 1500, temperature: 0.65, messages: msgs }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.error?.message || `Cerebras ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.choices[0]?.message?.content || '', input: d.usage?.prompt_tokens||0, output: d.usage?.completion_tokens||0, model: 'cerebras/llama-3.3-70b' };
}

// â”€â”€ Hugging Face (router unificado, compatible OpenAI) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
async function callHuggingFace(msgs, maxTokens) {
  if (!process.env.HUGGINGFACE_API_KEY) throw new Error('Sin HUGGINGFACE_API_KEY');
  const res = await fetch('https://router.huggingface.co/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${process.env.HUGGINGFACE_API_KEY}` },
    body: JSON.stringify({ model: 'meta-llama/Llama-3.3-70B-Instruct:novita', max_tokens: maxTokens || 1500, temperature: 0.65, messages: msgs }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.error?.message || `HuggingFace ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.choices[0]?.message?.content || '', input: d.usage?.prompt_tokens||0, output: d.usage?.completion_tokens||0, model: 'huggingface/llama-3.3-70b' };
}

async function callGemini(msgs, maxTokens, imageParts) {
  const sysMsg = msgs.find(m => m.role === 'system');
  const contents = msgs.filter(m => m.role !== 'system').map(m => ({ role: m.role === 'assistant' ? 'model' : 'user', parts: [{ text: m.content }] }));
  // ImÃ¡genes adjuntas (imagen simple, o varias pÃ¡ginas de un PDF escaneado): se
  // agregan como partes inline del Ãºltimo turno del usuario.
  const parts = Array.isArray(imageParts) ? imageParts : (imageParts ? [imageParts] : []);
  if (parts.length && contents.length) {
    for (const p of parts) {
      if (p && p.data) contents[contents.length - 1].parts.push({ inline_data: { mime_type: p.mimeType, data: p.data } });
    }
  }
  const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=${process.env.GEMINI_API_KEY}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ systemInstruction: { parts: [{ text: sysMsg ? sysMsg.content : SYSTEM_BASE }] }, contents, generationConfig: { maxOutputTokens: maxTokens || 1500, temperature: 0.7 } }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.error?.message || `Gemini ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.candidates?.[0]?.content?.parts?.[0]?.text || '', input: d.usageMetadata?.promptTokenCount||0, output: d.usageMetadata?.candidatesTokenCount||0, model: parts.length ? 'gemini-1.5-flash-vision' : 'gemini-1.5-flash' };
}

// parseImageDataUrl y ALLOWED_IMAGE_MIME ahora viven en ./utils.js (importado arriba)

// Modelos gratuitos de OpenRouter en orden de preferencia
const OR_FREE_MODELS = [
  'moonshotai/kimi-k2:free',                       // Kimi K2 â€” muy fuerte en cÃ³digo/razonamiento
  'meta-llama/llama-3.3-70b-instruct:free',      // Llama 3.3 70B â€” mejor general
  'google/gemini-2.0-flash-exp:free',              // Gemini 2.0 Flash â€” 1M contexto
  'mistralai/mistral-small-3.1-24b-instruct:free', // Mistral Small 3.1 â€” muy bueno
  'deepseek/deepseek-chat-v3-0324:free',           // DeepSeek V3 â€” razonamiento
  'nvidia/llama-3.1-nemotron-nano-8b-v1:free',     // NVIDIA Nemotron â€” rÃ¡pido
  'openrouter/free',                               // Auto-router â€” elige el mejor disponible
];

async function callOpenRouterModel(msgs, model, maxTokens) {
  const res = await fetch('https://openrouter.ai/api/v1/chat/completions', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${process.env.OPENROUTER_API_KEY}`,
      'HTTP-Referer': process.env.FRONTEND_URL || 'https://wilson360-labs.vercel.app',
      'X-Title': 'WIL.E COPILOT',
    },
    body: JSON.stringify({
      model,
      max_tokens: maxTokens || 1500,
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
  if (!reply) throw new Error('OpenRouter devolviÃ³ respuesta vacÃ­a');
  return {
    reply,
    input: d.usage?.prompt_tokens || 0,
    output: d.usage?.completion_tokens || 0,
    model: `openrouter/${model.split('/').pop().replace(':free', '')}`,
  };
}

async function callOpenRouter(msgs, maxTokens) {
  if (!process.env.OPENROUTER_API_KEY) throw new Error('Sin OPENROUTER_API_KEY');
  // Intenta cada modelo gratuito en orden
  for (const model of OR_FREE_MODELS) {
    try {
      const result = await callOpenRouterModel(msgs, model, maxTokens);
      console.log(`âœ… OpenRouter respondiÃ³ con: ${model}`);
      return result;
    } catch (e) {
      if (e.status === 401) throw e; // Key invÃ¡lida â€” no seguir intentando
      console.warn(`âš ï¸ OpenRouter ${model} fallÃ³: ${e.message}`);
    }
  }
  throw new Error('Todos los modelos de OpenRouter fallaron');
}

async function callMistral(msgs, maxTokens) {
  if (!process.env.MISTRAL_API_KEY) throw new Error('Sin MISTRAL_API_KEY');
  const mistralMsgs = msgs.map(m => ({
    role: m.role === 'system' ? 'system' : m.role,
    content: m.content,
  }));
  const res = await fetch('https://api.mistral.ai/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${process.env.MISTRAL_API_KEY}` },
    body: JSON.stringify({ model: 'mistral-small-latest', max_tokens: maxTokens || 1500, temperature: 0.65, messages: mistralMsgs }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.error?.message || `Mistral ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.choices[0]?.message?.content || '', input: d.usage?.prompt_tokens||0, output: d.usage?.completion_tokens||0, model: 'mistral/mistral-small' };
}

async function callCohere(msgs, maxTokens) {
  if (!process.env.COHERE_API_KEY) throw new Error('Sin COHERE_API_KEY');
  const system = msgs.find(m => m.role === 'system')?.content || '';
  // F3.8: Send last 4 turns (not just 1) to preserve conversation context
  const nonSystem = msgs.filter(m => m.role !== 'system');
  const chatHistory = nonSystem.slice(0, -1).slice(-8).map(m => ({  // last 8 messages (4 turns)
    role: m.role === 'assistant' ? 'CHATBOT' : 'USER',
    message: m.content,
  }));
  const lastMsg = nonSystem[nonSystem.length - 1]?.content || '';
  const res = await fetch('https://api.cohere.com/v1/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${process.env.COHERE_API_KEY}` },
    body: JSON.stringify({ model: 'command-r', message: lastMsg, chat_history: chatHistory, preamble: system, max_tokens: maxTokens || 1500, temperature: 0.65 }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.message || `Cohere ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.text || '', input: d.meta?.tokens?.input_tokens||0, output: d.meta?.tokens?.output_tokens||0, model: 'cohere/command-r' };
}

// â”€â”€ Anthropic Claude â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
async function callClaude(msgs, maxTokens) {
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
      max_tokens: maxTokens || 1500,
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

// â”€â”€ Kimi / Moonshot AI (endpoint compatible OpenAI) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
async function callKimi(msgs, maxTokens) {
  if (!process.env.KIMI_API_KEY) throw new Error('Sin KIMI_API_KEY');
  const res = await fetch('https://api.moonshot.ai/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${process.env.KIMI_API_KEY}` },
    body: JSON.stringify({ model: 'kimi-k2-0905-preview', max_tokens: maxTokens || 1500, temperature: 0.65, messages: msgs }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.error?.message || `Kimi ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.choices[0]?.message?.content || '', input: d.usage?.prompt_tokens||0, output: d.usage?.completion_tokens||0, model: 'moonshot/kimi-k2' };
}

// â”€â”€ Router Inteligente (reglas, prioriza CALIDAD sobre velocidad) â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Analiza el Ãºltimo mensaje del usuario y reordena los proveedores segÃºn
// quÃ© tan bien encajan con el tipo de consulta. Sin llamadas extra, sin
// latencia adicional â€” solo heurÃ­sticas sobre el texto ya disponible.
const CODE_HINTS = /```|\b(debug|bug|error|stack ?trace|excepci[oÃ³]n|refactor|optimiza|funci[oÃ³]n|c[oÃ³]digo|script|compila|sintaxis)\b/i;
const CREATIVE_HINTS = /\b(cuento|poema|historia|redacta|ensayo|gui[oÃ³]n|narrativa|creativo)\b/i;

function classifyRoute(msgs) {
  const last = msgs.filter(m => m.role === 'user').slice(-1)[0]?.content || '';

  // Orden base: calidad primero, no velocidad
  let order = ['Claude', 'Kimi', 'Gemini', 'OpenRouter', 'Mistral', 'Cohere', 'Groq', 'Cerebras', 'HuggingFace'];

  if (last.length > 6000) {
    // Documento largo / contexto RAG â†’ prioriza ventana de contexto grande (Kimi maneja hasta 128k)
    order = ['Kimi', 'Gemini', 'Claude', 'OpenRouter', 'Mistral', 'Cohere', 'Groq', 'Cerebras', 'HuggingFace'];
  } else if (CODE_HINTS.test(last)) {
    // CÃ³digo/debug â†’ Claude es el mÃ¡s fuerte, luego Kimi K2 (muy bueno en cÃ³digo) y OpenRouter (DeepSeek)
    order = ['Claude', 'Kimi', 'OpenRouter', 'Gemini', 'Mistral', 'Cohere', 'Groq', 'Cerebras', 'HuggingFace'];
  } else if (CREATIVE_HINTS.test(last)) {
    order = ['Claude', 'Mistral', 'Kimi', 'Gemini', 'OpenRouter', 'Cohere', 'Groq', 'Cerebras', 'HuggingFace'];
  }

  return order;
}

async function callAI(msgs, maxTokens) {
  const mt = maxTokens || 1500;
  const providerMap = {
    Claude:      { fn: () => callClaude(msgs, mt),      key: process.env.ANTHROPIC_API_KEY },
    Kimi:        { fn: () => callKimi(msgs, mt),        key: process.env.KIMI_API_KEY },
    Groq:        { fn: () => callGroq(msgs, mt),        key: process.env.GROQ_API_KEY },
    Cerebras:    { fn: () => callCerebras(msgs, mt),    key: process.env.CEREBRAS_API_KEY },
    HuggingFace: { fn: () => callHuggingFace(msgs, mt), key: process.env.HUGGINGFACE_API_KEY },
    OpenRouter:  { fn: () => callOpenRouter(msgs, mt),  key: process.env.OPENROUTER_API_KEY },
    Gemini:      { fn: () => callGemini(msgs, mt),      key: process.env.GEMINI_API_KEY },
    Mistral:     { fn: () => callMistral(msgs, mt),     key: process.env.MISTRAL_API_KEY },
    Cohere:      { fn: () => callCohere(msgs, mt),      key: process.env.COHERE_API_KEY },
  };

  const order = classifyRoute(msgs);
  const providers = order.map(name => ({ name, ...providerMap[name] }));
  const available = providers.filter(p => p.key);
  if (!available.length) throw new Error('Sin API keys de IA configuradas');

  for (const provider of available) {
    try {
      const result = await provider.fn();
      console.log(`âœ… IA respondiÃ³ via ${provider.name} (router: ${available.map(p=>p.name).join(' > ')})`);
      return result;
    } catch (e) {
      if (e.status === 401) { console.warn(`âŒ ${provider.name}: API key invÃ¡lida`); continue; }
      if (e.status === 429) { console.warn(`âš ï¸ ${provider.name}: rate limit, probando siguiente...`); continue; }
      console.warn(`âš ï¸ ${provider.name} fallÃ³ (${e.message}), probando siguiente...`);
    }
  }
  throw new Error('Todos los proveedores de IA fallaron');
}

async function validateTurnstile(token) {
  if (!process.env.TURNSTILE_SECRET) {
    console.warn('âš ï¸ TURNSTILE_SECRET no configurado â€” rechazando request (fail-closed)');
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

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
//  MÃ“DULOS EXTERNOS
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

// â”€â”€ Universal Resolver â€” DesencriptaciÃ³n heurÃ­stica de links â”€â”€
const universalResolverRouter = require('./modules/universal-resolver');
app.use('/api/resolver', universalResolverRouter);

// â”€â”€ WIL.E INTELLIGENCE CORE â€” rutas â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Memoria, base de conocimiento (RAG) e ingesta privada de entrenamiento.
const wilERoutes = require('./wil-e/routes')({
  authPayload: (req) => req.authUser,
  isAdminReq: (req) => !!(req.authUser && req.authUser.email === (process.env.ADMIN_EMAIL || '')) || !!(req.headers['x-admin-key']),
});
app.use('/api/wil-e', wilERoutes);

// â”€â”€ WIL.E VOZ â€” TTS neural premium (Jarvis) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// /api/tts y /api/tts/info. Reemplaza la voz del navegador por ElevenLabs
// cuando ELEVENLABS_API_KEY estÃ¡ configurada; si no, el frontend usa fallback.
const ttsRoutes = require('./wil-e/tts')({ authPayload: (req) => req.authUser });
app.use('/api/tts', ttsRoutes);

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
//  RUTAS
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

// â”€â”€ ESTADÃSTICAS SUPABASE â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

// â”€â”€ DB RUNNER â€” aplica un .sql (Supabase) o un esquema .json â”€â”€â”€â”€â”€
// (MongoDB) subido desde admin-hub.html. Requiere ADMIN_KEY.
//
// Supabase: ejecuta SQL crudo vÃ­a la funciÃ³n `exec_sql` (debe existir
// en la base â€” ver bootstrap_exec_sql.sql, se crea UNA vez a mano
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
    try { schema = JSON.parse(content); } catch { return res.status(400).json({ ok: false, error: 'JSON invÃ¡lido' }); }
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
  push_web:  (process.env.VAPID_PUBLIC_KEY && process.env.VAPID_PRIVATE_KEY) ? 'ok (VAPID propia)' : 'ok (VAPID de ejemplo â€” configura VAPID_PUBLIC_KEY/VAPID_PRIVATE_KEY)',
  push_android: fcmEnabled ? 'ok (FCM habilitado)' : 'missing (configura FIREBASE_SERVICE_ACCOUNT)',
  render_keepalive: process.env.RENDER_EXTERNAL_URL ? 'ok' : 'missing (configura RENDER_EXTERNAL_URL para que Render no duerma el servicio)',
  github_webhook_secret: process.env.GITHUB_WEBHOOK_SECRET ? 'ok' : 'missing (configura GITHUB_WEBHOOK_SECRET para notificaciones instantÃ¡neas de nuevas versiones)',
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


// â”€â”€ POST /api/visit â€” Registrar visita con IPQuery â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Responde 201 de inmediato; el enriquecimiento geo-IP y el guardado
// se hacen en segundo plano para no bloquear la peticiÃ³n hasta 12s.
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

    // â”€â”€ Background: geo-IP + guardado en Supabase â”€â”€
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

// â”€â”€ GET /api/admin/visitors â€” Listar visitas (solo admin) â”€â”€â”€â”€â”€
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

// Apps pÃºblicas (con cachÃ© 5 min)
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

// â”€â”€ App Updates â€” verificar versiones desde GitHub Releases â”€â”€
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

// Noticias â€” geolocalizadas por paÃ­s vÃ­a Google News RSS, con BBC Mundo
// como respaldo fijo. Todo se lee server-side para evitar depender de
// proxies CORS pÃºblicos poco fiables (allorigins, etc.) en el navegador.
const NEWS_RSS_URL = 'https://feeds.bbci.co.uk/mundo/rss.xml';

// Locale (idioma de interfaz) por cÃ³digo de paÃ­s para armar la URL de
// Google News (hl/gl/ceid). Si el paÃ­s no estÃ¡ en la lista se usa
// 'es-419' (espaÃ±ol latam) por defecto dentro de buildGoogleNewsUrl.
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
// tarjeta muestre cÃ³digo fuente crudo de la noticia.
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

// â”€â”€ MINIATURAS REALES PARA GOOGLE NEWS â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// El RSS de Google News casi nunca trae media:thumbnail/enclosure (a
// diferencia de BBC), asÃ­ que para los items sin imagen visitamos el
// artÃ­culo real (el <link> redirige del dominio news.google.com al medio
// original) y leemos su og:image/twitter:image. Se limita cuÃ¡nto HTML se
// lee y cuÃ¡nto tiempo total se invierte para no volver lenta la respuesta;
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
        // Google News no disponible para ese paÃ­s/red â€” caemos a BBC Mundo.
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

// â”€â”€ SKILLS â€” catÃ¡logo servido al frontend â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

  // â”€â”€ LÃ­mite diario server-side â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  const emiKey = req.authUser ? 'u:' + req.authUser.id : 'd:' + clientIp(req);
  const emiLimit = await getEmiLimit(!!req.authUser);
  const emiUsed = await getEmiUsage(emiKey);
  if (emiUsed >= emiLimit) {
    return res.status(429).json({ error: `LÃ­mite diario alcanzado (${emiLimit} mensajes). ${req.authUser ? '' : 'Inicia sesiÃ³n para mÃ¡s.'}`, code: 'EMI_DAILY_LIMIT', limit: emiLimit, used: emiUsed });
  }

  // â”€â”€ Imagen / PDF escaneado adjunto: valida formato/tamaÃ±o antes de gastar una llamada â”€â”€
  // "image" es una imagen suelta (data URL). "images" es un array de pÃ¡ginas
  // renderizadas (PDF escaneado). Ambos van a Gemini Vision.
  let imageParts = null;
  const imgList = image ? [image] : (Array.isArray(images) && images.length ? images.slice(0, 5) : null);
  if (imgList && imgList.length) {
    imageParts = [];
    for (const u of imgList) {
      const p = parseImageDataUrl(u);
      if (!p) return res.status(400).json({ error: 'Imagen invÃ¡lida o demasiado pesada (mÃ¡x. ~4MB c/u, png/jpeg/webp/gif).' });
      imageParts.push(p);
    }
    if (!process.env.GEMINI_API_KEY) {
      return res.status(503).json({ error: 'El anÃ¡lisis de imÃ¡genes no estÃ¡ disponible en este momento.' });
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
      content: '[Documento adjunto â€” resumen comprimido del documento. Responde usando SOLO este contenido como referencia, en espaÃ±ol]:\n' + pdfText.slice(0, 40000)
    });
  }
  // F1.1: SYSTEM dinÃ¡mico â€” base para queries generales, completa para CodeHub
  let system = classifySystem(message);
  // Skill activa: inyecta su guÃ­a (system_prompt_inject)
  if (skill_id) {
    const skill = loadSkillJson(String(skill_id));
    if (skill && skill.system_prompt_inject) {
      system = skill.system_prompt_inject + '\n\n' + system;
    }
  }
  // WIL.E: contexto aumentado (memoria del usuario + base de conocimiento RAG)
  if (dbConnected) {
    try {
      const ctx = await buildContext({
        userId: req.authUser ? req.authUser.id : 'anon',
        ownerId: 'admin',
        message,
        topK: 3,
      });
      if (ctx) system = augmentSystem(system, ctx);
    } catch (e) {
      console.warn('Wil.E contexto error:', e.message);
    }
  }
  // WIL.E: bÃºsqueda web en vivo (datos actuales) cuando la consulta lo pide
  try {
    const live = await liveWebContext(message);
    if (live) system = system + '\n\n' + live;
  } catch (e) { /* silencioso */ }
  // WIL.E: herramienta de cÃ³mputo (cÃ¡lculos, fecha, conversiones) sin LLM
  try {
    const tool = computeTool(message);
    if (tool) system = system + '\n\n' + tool;
  } catch (e) { /* silencioso */ }
  // WIL.E: function-calling â€” ejecuta la herramienta detectada (web/computo/URL)
  try {
    const dt = detectTool(message);
    if (dt) {
      const out = await executeTool(dt.name, dt.arg);
      if (out) system = system + '\n\n' + out;
    }
  } catch (e) { /* silencioso */ }
  // F1.2+F1.4: Smart truncation con budget de 10k tokens (~40k chars)
  const msgs = buildSmartMessages(system, sessionHistory, 10000);

  try {
    // Con imagen/PDF escaneado: va directo a Gemini (Ãºnico proveedor con visiÃ³n
    // en esta cadena). Sin imagen: sigue el fallback normal Claudeâ†’Groqâ†’...â†’Cohere.
    const { reply, input, output, model } = imageParts
      ? await callGemini(msgs, adaptiveMaxTokens(message), imageParts)
      : await callAI(msgs, adaptiveMaxTokens(message));
    if (dbConnected) ChatMessage.insertMany([
      { sessionId, role: 'user',      content: message.trim() + (imageParts ? ' [imagen adjunta]' : '') + (pdfText ? ' [PDF adjunto]' : ''), tokens: input,  model },
      { sessionId, role: 'assistant', content: reply,          tokens: output, model },
    ]).catch(() => {});
    broadcast('chat_used', { model, tokens: input + output });
    trackEvent('chat', null, { model, tokens: input + output });
    tgAlert('chat', () => `ðŸ’¬ <b>Chat con WIL.E</b>\n${String(message || '').slice(0, 60).replace(/[<>]/g, '')}\nðŸ§  ${model}\nðŸŒ ${clientIp(req)}`, { windowMs: 30000 });
    const emiNow = await incrEmiUsage(emiKey);
    res.json({ reply, usage: { input, output, total: input + output }, model, emi: { used: emiNow, limit: emiLimit } });
    // WIL.E: aprende hechos del mensaje del usuario (memoria entrenable)
    if (dbConnected && req.authUser) {
      remember({ userId: req.authUser.id, text: message }).catch(() => {});
    }
  } catch (err) {
    tgAlert('chatfail', () =>
      `âš ï¸ <b>Error en /api/chat</b>\n${err && (err.message || err.status) ? String(err.message || err.status).slice(0, 120) : 'desconocido'}`,
      { windowMs: 30000 });
    if (err.status === 401) return res.status(500).json({ error: imageParts ? 'Gemini: API key invÃ¡lida.' : 'API key invÃ¡lida.' });
    if (err.status === 429) return res.status(429).json({ error: 'LÃ­mite alcanzado.' });
    if (imageParts) return res.status(500).json({ error: 'No pude analizar la imagen. Intenta de nuevo.' });
    res.status(500).json({ error: 'Error interno.' });
  }
});

// Contacto (notifica vÃ­a WS)
app.post('/api/contact', (req, res) => {
  const { name, email, message } = req.body;
  trackEvent('contact');
  tgAlert('contact', () => {
    const ip = clientIp(req);
    return `ðŸ“© <b>Nuevo contacto</b>\nðŸ‘¤ ${String(name || 'AnÃ³nimo').slice(0, 30)}\nðŸ“§ ${email ? email.replace(/(.{2}).*(@.*)/, '$1***$2') : '?'}\nðŸ’¬ ${String(message || '').slice(0, 80)}\nðŸŒ ${ip}`;
  }, { windowMs: 30000 });
  broadcast('contact_form', {
    name:  name  || 'AnÃ³nimo',
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
  if (!appId || !stars || stars < 1 || stars > 5) return res.status(400).json({ error: 'Datos invÃ¡lidos' });
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    // findOneAndUpdate + upsert es atÃ³mico â€” evita la condiciÃ³n de carrera
    // que habÃ­a con "findOne â†’ new AppRating() â†’ save()": si dos votos
    // llegaban casi al mismo tiempo, ambos podÃ­an pasar el findOne antes
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
    tgAlert('rating', () => `â­ <b>Rating nuevo</b>: ${stars}â˜… â€” ${String(appName || appId).slice(0, 40)} (avg ${avg}, ${r.count} votos)`, { windowMs: 30000 });
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
  if (!await validateTurnstile(turnstileToken)) return res.status(403).json({ error: 'VerificaciÃ³n fallida' });
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
    tgAlert('appreq', () => `ðŸ™‹ <b>Solicitud de app</b>\nðŸ“± ${String(appName.trim()).slice(0, 40)}\nðŸ’¬ ${String(reason || '').trim().slice(0, 80) || 'sin motivo'}`, { windowMs: 30000 });
    res.json({ ok: true, message: 'Solicitud enviada', id: newReq._id });
  } catch { res.status(500).json({ error: 'Error guardando solicitud' }); }
});

// Download APK (Supabase Storage URL pÃºblica)
app.get('/api/download/:fileName', async (req, res) => {
  const { fileName } = req.params;
  if (!fileName || fileName.includes('..')) return res.status(400).json({ error: 'Nombre invÃ¡lido' });
  try {
    if (!supabase) return res.status(503).json({ error: 'Storage no disponible' });
    const { data } = supabase.storage.from(STORAGE_BUCKET).getPublicUrl(decodeURIComponent(fileName));
    broadcast('download', { fileName: decodeURIComponent(fileName) });
    trackEvent('download', null, { app_name: decodeURIComponent(fileName) });
    tgAlert('download', () => `â¬‡ï¸ <b>Descarga</b>: ${decodeURIComponent(fileName)}`, { windowMs: 15000 });
    res.redirect(302, data.publicUrl);
  } catch (e) { console.error('Error download:', e.message); res.status(500).json({ error: 'No se pudo generar el link.' }); }
});

// Download indirecta por appId â€” pensada para el catÃ¡logo Open Source.
// El HTML pÃºblico solo expone el appId (nunca el enlace real de GitHub
// Releases); este endpoint resuelve el enlace actual en MongoDB y hace
// un redirect 302. Ventajas: se puede cambiar el destino (nueva versiÃ³n,
// mirror, etc.) sin tocar el frontend, y queda trackeado igual que las
// descargas Premium. OJO: esto no es "seguridad" â€” cualquiera puede ver
// la URL final en la pestaÃ±a Network del navegador tras el redirect,
// solo evita que quede pegada en el HTML/cÃ³digo fuente de la pÃ¡gina.
app.get('/api/dl/:appId', async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  const { appId } = req.params;
  try {
    const app_ = await App.findOne({ appId }).select('enlace nombre').lean();
    if (!app_ || !app_.enlace || app_.enlace === '#') return res.status(404).json({ error: 'Enlace no disponible aÃºn' });
    broadcast('download', { fileName: app_.nombre });
    trackEvent('download', null, { app_name: app_.nombre, appId });
    tgAlert('download', () => `â¬‡ï¸ <b>Descarga</b>: ${app_.nombre}`, { windowMs: 15000 });
    res.redirect(302, app_.enlace);
  } catch (e) { console.error('Error /api/dl:', e.message); res.status(500).json({ error: 'No se pudo generar el link.' }); }
});

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
//  ADMIN
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

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
    const a = await App.create({ appId, nombre, descripcion, version, tag: tag || 'ðŸ†•', changelog, imagen: normalizeImagePath(imagen), categoria, verified: verified !== false, enlace: enlace || '#', plugin_enlace: plugin_enlace || null, source_repo: source_repo || null, packageName: packageName || null });
    await cacheDel('apps:all');
    broadcast('new_app', { appId, nombre, tag: tag || 'ðŸ†•', categoria });
    broadcastAppsChanged();
    tgAlert('adminapp', () => `âž• <b>App publicada</b>\nðŸ“± ${String(nombre).slice(0, 40)} (<code>${appId}</code>)\nðŸ·ï¸ ${categoria || 'sin categorÃ­a'}`, { windowMs: 30000 });
    // NotificaciÃ³n automÃ¡tica: nueva app open source en el catÃ¡logo
    if (a.source_repo) {
      try {
        const r = await broadcastPush({
          title: 'ðŸ†• Nueva app open source: ' + a.nombre,
          body: (a.descripcion ? String(a.descripcion).slice(0, 120) : 'Ya disponible en el catÃ¡logo open source de CodeHub'),
          type: 'app_update',
          appId: a.appId,
          version: a.version || '',
          url: '/opensource.html',
        });
        if (r.sent) console.log('ðŸ“² Push nueva app open source:', r.sent);
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

    // No sobreescribir enlace con vacÃ­o o '#' si ya hay un APK subido (Telegram/Archive/Supabase)
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

// â”€â”€ DELETE /api/admin/apps/:appId/apk â€” Elimina solo el APK de Telegram/Storage sin borrar la app
// Ãštil para reemplazar un APK desactualizado antes de subir uno nuevo, o limpiar storage manualmente.
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
    console.log(`ðŸ—‘ï¸ APK eliminado: ${req.params.appId} [slot=${isPlugin ? 'plugin' : 'main'}]`);
    res.json({ ok: true, appId: req.params.appId, slot: isPlugin ? 'plugin' : 'main', deleted });
  } catch (e) { res.status(500).json({ error: e.message }); }
});


// â”€â”€ GET /api/admin/apps/:appId/archive-credentials â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

// â”€â”€ POST /api/admin/apps/:appId/archive-confirm â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// El frontend llama este endpoint DESPUÃ‰S de subir directo a Archive.org
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
    console.log(`âœ… Archive confirm: ${req.params.appId} | ${fileName} | ${sizeMB} MB`);
    res.json({ ok: true, fileName, downloadUrl, sizeMB, storage: 'archive' });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// â”€â”€ POST /api/admin/apps/:appId/upload â€” Streaming sin buffer en RAM â”€â”€â”€â”€â”€â”€â”€â”€
// Parsea el multipart con busboy y hace PIPE directo al destino:
//   â‰¤ 50 MB â†’ Telegram  (bot configurado) o Supabase (fallback)
//   > 50 MB â†’ Archive.org S3  (streaming chunk a chunk, sin lÃ­mite)
// En ningÃºn momento se acumula el archivo completo en memoria de Render.
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
      limits: { fileSize: 2 * 1024 * 1024 * 1024 }, // 2 GB trÃ¡nsito (solo chunks en vuelo, no en RAM)
    });
  } catch (e) {
    return res.status(400).json({ error: 'Multipart invÃ¡lido: ' + e.message });
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

      // Determinar tamaÃ±o estimado desde Content-Length para decidir destino ANTES de leer el stream
      // El browser siempre envÃ­a Content-Length en FormData uploads
      const contentLength = parseInt(req.headers['content-length'] || '0', 10);
      const TG_MAX        = 49 * 1024 * 1024; // 49 MB â€” lÃ­mite real de Telegram para bots
      const likelyLarge   = contentLength > TG_MAX; // el multipart overhead es pequeÃ±o (~500 bytes)

      // Enrutamiento:
      //   Si el archivo cabe en Telegram (â‰¤ 49 MB) y hay bot â†’ Telegram
      //   Si es grande (> 49 MB) y hay Archive.org â†’ Archive.org streaming
      //   Fallback â†’ Supabase (solo si < 50 MB)
      const useTG  = hasTG && !likelyLarge;
      const useIA  = hasIA && (likelyLarge || !hasTG);

      let bytesOut = 0;
      let downloadUrl, upd, storageLabel;

      console.log(`ðŸ“¦ Upload routing: contentLength=${(contentLength/1024/1024).toFixed(1)}MB likelyLarge=${likelyLarge} useTG=${useTG} useIA=${useIA}`);

      if (useTG) {
        // â”€â”€ STREAMING â†’ Telegram â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        storageLabel = 'telegram';
        const boundary = '----StreamBoundary' + ts.toString(16);
        const CRLF     = '\r\n';
        const caption  = `ðŸ“¦ ${a.nombre} â€” ${isPlugin ? 'Plugin' : 'APK'} v${a.version || '?'}`;

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
          // Content-Length desconocido â†’ usar Transfer-Encoding: chunked
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
        // â”€â”€ BUFFER â†’ Archive.org S3 â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // Archive.org S3 rechaza Transfer-Encoding: chunked (HTTP 411 Length Required).
        // SOLUCIÃ“N: acumular en archivo temporal en disco para obtener el Content-Length
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
        console.log(`ðŸ“ Temp file: ${tmpPath} | size: ${(fileSize/1024/1024).toFixed(2)} MB`);
        if (fileSize === 0) throw new Error('Archivo temporal vacÃ­o â€” stream no llegÃ³ correctamente');
        const oldIaFile = isPlugin ? a.ia_plugin_file_name : a.ia_file_name;
        if (oldIaFile) await deleteFromArchive(oldIaFile).catch(() => {});

        // 2. PUT con Content-Length exacto â€” Archive.org lo exige
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
          // Pipe desde disco â†’ Archive.org
          const tmpRead = fs.createReadStream(tmpPath);
          tmpRead.pipe(iaReq);
          tmpRead.on('error', (e) => { fs.unlink(tmpPath, () => {}); reject(e); });
        });

        downloadUrl = `https://archive.org/download/${getIAItemId(appId)}/${encodeURIComponent(fileName)}`;
        upd = isPlugin
          ? { ia_plugin_file_name: fileName, plugin_enlace: downloadUrl, updatedAt: new Date() }
          : { ia_file_name: fileName, ia_identifier: getIAItemId(appId), enlace: downloadUrl, updatedAt: new Date() };

      } else {
        // â”€â”€ FALLBACK: Supabase (buffer en memoria, solo < 50 MB) â”€
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
      console.log(`âœ… Upload streaming OK: ${fileName} | ${sizeMB} MB | ${storageLabel}`);
      safe(() => res.json({ ok: true, fileName, downloadUrl, sizeMB, storage: storageLabel }));

    } catch (e) {
      fileStream.resume();
      console.error('Upload streaming error:', e.message);
      safe(() => res.status(500).json({ error: e.message }));
    }
  });

  bb.on('error', (e) => safe(() => res.status(400).json({ error: 'Parse multipart: ' + e.message })));
  bb.on('finish', () => {
    if (!fileStarted) safe(() => res.status(400).json({ error: 'No se recibiÃ³ archivo .apk' }));
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
        const set = { nombre: a.nombre||a.name, enlace: a.enlace||'#', version: a.version_conocida||a.ver||'', tag: a.tag||'ðŸ†•', updatedAt: new Date() };
        // Solo se pisa `imagen` si el seed trae una â€” evita borrar un
        // Ã­cono que el admin ya haya corregido a mano desde el panel.
        if (imagen) {
          // Guarda anti-revert: si el seed trae la portada social del repo
          // (opengraph) y la DB ya tiene un logo real local (/img/...), se
          // conserva el logo local.
          const seedIsPortada = /opengraph\.githubassets\.com/i.test(imagen);
          const prevIsLocal   = /^\/img\//.test(exists.imagen || '');
          if (!(seedIsPortada && prevIsLocal)) set.imagen = imagen;
        }
        // Idem para `source_repo` â€” solo se pisa si el seed lo trae,
        // para no desactivar el monitor de una app ya vinculada.
        if (a.source_repo) set.source_repo = a.source_repo;
        // packageName resuelto por resolve-package-names.js â€” solo se pisa
        // si el seed trae uno, para no borrar uno ya resuelto a mano.
        if (a.packageName) set.packageName = a.packageName;
        await App.updateOne({ appId: id }, { $set: set });
        updated++;
      } else {
        await App.create({ appId: id, nombre: a.nombre||a.name, descripcion: a.descripcion||'', version: a.version_conocida||a.ver||'', tag: a.tag||'ðŸ†•', changelog: a.changelog||'', imagen, categoria: a.categoria||a.cat||'', verified: a.verified!==false, enlace: a.enlace||'#', plugin_enlace: a.plugin_enlace||null, source_repo: a.source_repo||null, packageName: a.packageName||null });
        created++;
      }
    }
    await cacheDel('apps:all');
    broadcastAppsChanged();
    res.json({ ok: true, created, updated });
  } catch (e) { res.status(500).json({ error: e.message }); }
});


// â”€â”€ POST /api/generate-image â€” Generador IA con 4 proveedores â”€
// F2.6: Cache de imÃ¡genes por prompt hash (TTL 1 hora)
const _imgCache = new Map();
const IMG_CACHE_TTL = 3600000; // 1 hour
function imgCacheKey(p, w, h) {
  let h1 = 0; const s = p.toLowerCase().trim().replace(/\s+/g, ' ');
  for (let i = 0; i < s.length; i++) { h1 = ((h1 << 5) - h1 + s.charCodeAt(i)) | 0; }
  return h1 + ':' + w + 'x' + h;
}
function imgCacheGet(key) {
  const e = _imgCache.get(key);
  if (!e) return null;
  if (Date.now() - e.ts > IMG_CACHE_TTL) { _imgCache.delete(key); return null; }
  return e.data;
}
function imgCacheSet(key, data) {
  if (_imgCache.size > 200) { const first = _imgCache.keys().next().value; _imgCache.delete(first); }
  _imgCache.set(key, { data, ts: Date.now() });
}
function sendAndCache(res, data, cacheKey) {
  if (data && (data.image || data.url)) {
    if (res.locals && res.locals.refinedPrompt) data.refined_prompt = res.locals.refinedPrompt;
    if (res.locals && res.locals.description) data.description = res.locals.description;
    imgCacheSet(cacheKey, data);
  }
  return res.json(data);
}

// â”€â”€ Refinador de prompt de imagen (dos etapas) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// El prompt crudo del usuario pasa primero por una IA de chat que lo
// convierte en un prompt de difusiÃ³n profesional, detallado y adaptado
// a lo que pidiÃ³. AsÃ­ el generador recibe una instrucciÃ³n consistente
// y de alta calidad (evita que cada proveedor "invente" su propia imagen).
async function refineImagePrompt(rawPrompt, w, h) {
  const aspect = w > h ? 'horizontales (16:9) de gran angular' : w < h ? 'verticales (9:16) aptas para mÃ³vil' : 'cuadradas (1:1)';
  const sysMsg = 'Eres un experto director de arte de IA. Analizas la intenciÃ³n del usuario y reescribes su peticiÃ³n como un PROMPT DE DIFUSIÃ“N PROFESIONAL en inglÃ©s, detallado y listo para meter en un generador de imÃ¡genes (FLUX / Imagen / MiniMax).\n'
    + 'Reglas:\n'
    + '- Conserva SIEMPRE el sujeto, estilo o tema que pidiÃ³ el usuario (persona, objeto, escena, logo, anime, fotorealista...).\n'
    + '- AÃ±ade detalles de calidad: iluminaciÃ³n (luz dorada, neblina, estudio, etc.), composiciÃ³n, Ã¡ngulo de cÃ¡mara, resoluciÃ³n, textura y ambiente.\n'
    + '- Elige el estilo visual correcto (fotorealista, render 3D, dibujo, acuarela, ciberpunk, minimalista, etc.) segÃºn lo pedido.\n'
    + '- Respeta si pide proporciones/formatos especÃ­ficos; si no, usa una orientaciÃ³n general ' + aspect + '.\n'
    + '- Devuelve SOLO el prompt refinado en una lÃ­nea, en inglÃ©s, sin comillas, sin explicaciones, sin saludos.';
  try {
    const { reply } = await callAI([
      { role: 'system', content: sysMsg },
      { role: 'user', content: 'PeticiÃ³n del usuario: ' + String(rawPrompt).slice(0, 400) }
    ], 450);
    const refined = String(reply || '').trim().replace(/^["']+|["']+$/g, '').replace(/\s+/g, ' ');
    if (refined.length >= 4) return refined;
  } catch (e) {
    console.warn('âš ï¸ refineImagePrompt fallÃ³, usando prompt original:', e.message);
  }
  return String(rawPrompt).trim();
}

// â”€â”€ Descriptor de imagen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Genera una descripciÃ³n natural en espaÃ±ol de la imagen creada, para que
// WIL.E se la "cuente" al usuario (texto + voz) de forma cÃ¡lida y corta.
async function describeImage(rawPrompt, refinedPrompt) {
  const sysMsg = 'Eres WIL.E COPILOT, el asistente de CodeHub. El usuario te pidiÃ³ generar una imagen y acaba de crearse.\n'
    + 'Escribe UN pÃ¡rrafo breve (mÃ¡x. 40 palabras) en ESPAÃ‘OL, cÃ¡lido y entusiasta, describiendo QUÃ‰ se creÃ³ basÃ¡ndote en esta peticiÃ³n.\n'
    + 'Empieza con algo como "Â¡Listo! GenerÃ©..." o "AquÃ­ tienes tu imagen de...". Describe el sujeto, el estilo y la sensaciÃ³n general.\n'
    + 'NO menciones que eres una IA ni cÃ³mo se generÃ³. Solo describe la imagen creada para el usuario.';
  try {
    const { reply } = await callAI([
      { role: 'system', content: sysMsg },
      { role: 'user', content: 'PeticiÃ³n original: ' + String(rawPrompt).slice(0, 300) + '\n| Prompt refinado: ' + String(refinedPrompt || rawPrompt).slice(0, 300) }
    ], 180);
    const desc = String(reply || '').trim().replace(/\s+/g, ' ');
    if (desc.length >= 8) return desc;
  } catch (e) {
    console.warn('âš ï¸ describeImage fallÃ³:', e.message);
  }
  return 'Â¡Listo! GenerÃ© una imagen basada en tu peticiÃ³n: ' + String(rawPrompt).slice(0, 80).trim() + '.';
}

// â”€â”€ BÃºsqueda web en vivo â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Si la consulta parece pedir informaciÃ³n actual/noticias, busca en la web
// (DuckDuckGo Instant Answer + Wikipedia, gratuitos y sin clave) y devuelve
// un contexto breve para que WIL.E responda con datos reales y recientes.
// Devuelve string vacÃ­o si no aplica o falla.
async function liveWebContext(message) {
  const q = String(message || '').trim();
  if (!q || q.length < 6) return '';
  const low = q.toLowerCase();
  const signalWords = ['Ãºltima', 'Ãºltimo', 'noticia', 'hoy', '2024', '2025', '2026', 'cuÃ¡l es la hora', 'mejor', 'mÃ¡s reciente', 'actualidad', 'resultado', 'presidente', 'guerra', 'elecciones', 'clima', 'efemÃ©ride', 'lanzamiento', 'noticias', 'reciÃ©n', 'acaba'];
  const curiosity = /(quÃ© (es|pasÃ³|hubo|ganÃ³|dijo)|quiÃ©n (es|ganÃ³)|cuÃ¡ndo|cÃ³mo estÃ¡|latest|today|news|who won|what happened)/i;
  const wantsLive = signalWords.some(w => low.includes(w)) || curiosity.test(low);
  if (!wantsLive) return '';

  const out = [];
  // 1) DuckDuckGo Instant Answer (abstract + descripciÃ³n)
  try {
    const ctrl = AbortSignal.timeout(5000);
    const r = await fetch('https://api.duckduckgo.com/?q=' + encodeURIComponent(q) + '&format=json&no_html=1&skip_disambig=1', { signal: ctrl });
    if (r.ok) {
      const d = await r.json();
      const abstract = (d && (d.AbstractText || d.Answer)) || '';
      if (abstract) out.push('DuckDuckGo: ' + String(abstract).slice(0, 400));
      const defs = (d && d.RelatedTopics) || [];
      for (const t of defs.slice(0, 3)) {
        const txt = t.Text || (t.Topics && t.Topics[0] && t.Topics[0].Text) || '';
        if (txt) out.push('DuckDuckGo: ' + String(txt).slice(0, 250));
      }
    }
  } catch (e) { /* ignorar */ }

  // 2) Wikipedia (summary)
  try {
    const ctrl = AbortSignal.timeout(5000);
    const r = await fetch('https://es.wikipedia.org/api/rest_v1/page/summary/' + encodeURIComponent(q.replace(/[Â¿?Â¡!.,;:]/g, ' ').trim().split(' ').slice(0, 5).join('_')), { signal: ctrl, headers: { 'Accept': 'application/json' } });
    if (r.ok) {
      const d = await r.json();
      const extract = (d && d.extract) || '';
      if (extract) out.push('Wikipedia: ' + String(extract).slice(0, 500));
    }
  } catch (e) { /* ignorar */ }

  if (!out.length) return '';
  return 'REFERENCIA WEB EN VIVO (consulta "' + q.slice(0, 80) + '") â€” usa esto solo si responde a lo preguntado; si no hay relaciÃ³n, ignÃ³ralo:\n' + out.join('\n').slice(0, 2500);
}

// â”€â”€ Herramienta de cÃ³mputo (agent) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Resuelve de forma programÃ¡tica (sin LLM) tareas computables: operaciones
// de 2 operandos, fecha/hora actual, dÃ­as transcurridos y conversiones de
// unidades/divisas simples. Devuelve string vacÃ­o si no aplica.
function computeTool(message) {
  const q = String(message || '').trim();
  if (!q) return '';
  const low = q.toLowerCase();

  // Fecha / hora / dÃ­a actual
  if (/(quÃ© dÃ­a es hoy|fecha de hoy|dÃ­a de hoy|hoy es quÃ©|quÃ© fecha|hora actual|quÃ© hora es)\b/i.test(low)) {
    const now = new Date();
    const fecha = now.toLocaleDateString('es-ES', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
    const hora = now.toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit' });
    return 'HERRAMIENTA (fecha/hora): hoy es ' + fecha + ' y son las ' + hora + ' (hora local del servidor). Usa este dato si la pregunta pide la fecha/hora actual.';
  }

  // CÃ¡lculo aritmÃ©tico simple: a op b
  const ops = [
    { re: /(\d+(?:[.,]\d+)?)\s*(?:\+|\+|mÃ¡s|mas)\s*(\d+(?:[.,]\d+)?)/i, fn: (a, b) => a + b, sym: '+' },
    { re: /(\d+(?:[.,]\d+)?)\s*(?:-|menos)\s*(\d+(?:[.,]\d+)?)/i, fn: (a, b) => a - b, sym: '-' },
    { re: /(\d+(?:[.,]\d+)?)\s*(?:\*|x|por)\s*(\d+(?:[.,]\d+)?)/i, fn: (a, b) => a * b, sym: 'Ã—' },
    { re: /(\d+(?:[.,]\d+)?)\s*(?:\/|dividido entre|dividido por)\s*(\d+(?:[.,]\d+)?)/i, fn: (a, b) => a / b, sym: 'Ã·' },
  ];
  for (const o of ops) {
    const m = low.match(o.re);
    if (m) {
      const a = parseFloat(m[1].replace(',', '.'));
      const b = parseFloat(m[2].replace(',', '.'));
      if (!isNaN(a) && !isNaN(b) && b !== 0) {
        const r = Math.round(o.fn(a, b) * 100000) / 100000;
        return 'HERRAMIENTA (cÃ¡lculo): ' + a + ' ' + o.sym + ' ' + b + ' = ' + r + '. Responde usando este resultado exacto.';
      }
    }
  }

  // Porcentaje: cuÃ¡nto es X% de Y
  const pc = low.match(/(\d+(?:[.,]\d+)?)\s*%\s*de\s*(\d+(?:[.,]\d+)?)/);
  if (pc) {
    const p = parseFloat(pc[1].replace(',', '.'));
    const base = parseFloat(pc[2].replace(',', '.'));
    if (!isNaN(p) && !isNaN(base)) {
      const r = Math.round((p / 100) * base * 100) / 100;
      return 'HERRAMIENTA (porcentaje): el ' + p + '% de ' + base + ' = ' + r + '. Usa este resultado exacto en tu respuesta.';
    }
  }

  // ConversiÃ³n de unidades (km/mi, kg/lb, C/F, USDâ†”GTQ si hay tasa fija aproximada)
  const convs = [
    { re: /(\d+(?:[.,]\d+)?)\s*km\s*(?:a|to)?\s*mi/i, fn: (x) => x * 0.621371, label: 'kilÃ³metros a millas' },
    { re: /(\d+(?:[.,]\d+)?)\s*mi\s*(?:a|to)?\s*km/i, fn: (x) => x * 1.60934, label: 'millas a kilÃ³metros' },
    { re: /(\d+(?:[.,]\d+)?)\s*kg\s*(?:a|to)?\s*lb/i, fn: (x) => x * 2.20462, label: 'kilogramos a libras' },
    { re: /(\d+(?:[.,]\d+)?)\s*lb\s*(?:a|to)?\s*kg/i, fn: (x) => x * 0.453592, label: 'libras a kilogramos' },
  ];
  for (const c of convs) {
    const m = low.match(c.re);
    if (m) {
      const x = parseFloat(m[1].replace(',', '.'));
      if (!isNaN(x)) {
        const r = Math.round(c.fn(x) * 100) / 100;
        return 'HERRAMIENTA (conversiÃ³n): ' + x + ' ' + c.label + ' â‰ˆ ' + r + '. Usa este valor en tu respuesta.';
      }
    }
  }

  return '';
}

// â”€â”€ Function-calling (agente) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Dispatcher de herramientas de Wil.E. Detecta la intenciÃ³n del mensaje y
// ejecuta la herramienta adecuada (bÃºsqueda web, cÃ³mputo, leer URL), luego
// inyecta el resultado estructurado como contexto para que el LLM responda
// con datos reales. Devuelve string vacÃ­o si no corresponde ninguna.
function detectTool(message) {
  const q = String(message || '').trim();
  const low = q.toLowerCase();
  const tool = { name: null, arg: null };
  if (/^(busca|buscar|investiga|search|googlea|webs?earch|averigua)\.?\s+(.+)/i.test(q)) {
    const m = q.match(/^(?:busca|buscar|investiga|search|googlea|averigua)\.?\s+(.+)/i);
    tool.name = 'web_search'; tool.arg = m[1].trim();
  } else if (/(cuÃ¡nto es|calcula|cuÃ¡nto da|resuelve)\s+([0-9].*)/i.test(low)) {
    const m = low.match(/(?:cuÃ¡nto es|calcula|cuÃ¡nto da|resuelve)\s+([0-9].*)/i);
    tool.name = 'compute'; tool.arg = m[1].trim();
  } else if (/https?:\/\/\S+/i.test(q)) {
    const m = q.match(/(https?:\/\/[^\s]+)/i);
    tool.name = 'fetch_url'; tool.arg = m[1];
  }
  return tool.name ? tool : null;
}

async function executeTool(name, arg) {
  try {
    if (name === 'web_search') {
      const ctx = await liveWebContext('buscar ' + arg);
      return ctx ? 'Resultado bÃºsqueda: ' + ctx : null;
    }
    if (name === 'compute') {
      return computeTool('calcula ' + arg);
    }
    if (name === 'fetch_url') {
      const text = await fetchUrlText(arg);
      if (text) return 'Contenido de la URL (' + arg + '):\n' + text.slice(0, 1500);
      return null;
    }
  } catch (e) { return null; }
  return null;
}

// Extrae texto de una web (HTMLâ†’texto plano) para leer URLs.
async function fetchUrlText(url) {
  try {
    const r = await fetch(url, { signal: AbortSignal.timeout(10000), headers: { 'User-Agent': 'Mozilla/5.0 CodeHub' } });
    if (!r.ok) return '';
    const html = await r.text();
    const clean = html
      .replace(/<script[\s\S]*?<\/script>/gi, ' ')
      .replace(/<style[\s\S]*?<\/style>/gi, ' ')
      .replace(/<[^>]+>/g, ' ')
      .replace(/&nbsp;/g, ' ')
      .replace(/&amp;/g, '&')
      .replace(/\s+/g, ' ')
      .trim();
    return clean.slice(0, 2500);
  } catch (e) { return ''; }
}

app.post('/api/generate-image', imageLimiter, async (req, res) => {
  const { prompt, width = 512, height = 512, provider = 'auto', skill_id = null, preset_id = null } = req.body;
  if (!prompt || typeof prompt !== 'string' || prompt.trim().length < 2) {
    return res.status(400).json({ error: 'Prompt requerido' });
  }

  let p = prompt.trim().slice(0, 500);
  let w = Math.min(Math.max(parseInt(width)  || 512, 256), 1024);
  let h = Math.min(Math.max(parseInt(height) || 512, 256), 1024);
  const errors = [];

  // â”€â”€ Skill + preset: inyecta el prompt_suffix y el tamaÃ±o recomendado â”€â”€
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

  // â”€â”€ Dos etapas: otra IA refina el prompt antes de generarlo â”€â”€
  // Refinamiento de prompt por defecto (puede desactivarse con refine:false)
  if (req.body.refine !== false) {
    p = await refineImagePrompt(p, w, h);
  }
  res.locals.refinedPrompt = p;

  // F2.6: Check image cache before hitting providers
  const cacheKey = imgCacheKey(p, w, h);
  const cached = imgCacheGet(cacheKey);
  if (cached) {
    console.log('ðŸ–¼ï¸ Image cache hit');
    return res.json({ ...cached, cached: true });
  }

  // â”€â”€ 1. Together AI â€” FLUX.1 Schnell â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
        if (b64) return sendAndCache(res, { ok: true, provider: 'together', model: 'FLUX.1-schnell', image: `data:image/png;base64,${b64}` }, cacheKey);
        if (url) return sendAndCache(res, { ok: true, provider: 'together', model: 'FLUX.1-schnell', url }, cacheKey);
      } else {
        const e = await r.json().catch(() => ({}));
        errors.push(`Together: ${e.error?.message || r.status}`);
      }
    } catch (e) { errors.push(`Together: ${e.message}`); }
  }

  // â”€â”€ 2. Gemini â€” Imagen 3 Fast â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // NOTA: Solo funciona con proyecto allowlistado. Deshabilitado en auto
  // para no sumar 5s+ de timeout muerto a cada request. Se puede invocar
  // explÃ­citamente con provider='gemini'.
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
        if (b64) return sendAndCache(res, { ok: true, provider: 'gemini', model: 'Imagen 3 Fast', image: `data:image/png;base64,${b64}` }, cacheKey);
      } else {
        const e = await r.json().catch(() => ({}));
        errors.push(`Gemini: ${e.error?.message || r.status}`);
      }
    } catch (e) { errors.push(`Gemini: ${e.message}`); }
  }

  // â”€â”€ 3. Pollinations â€” Flux (sin key) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
        if (b64) return sendAndCache(res, { ok: true, provider: 'minimax', model: 'image-01', image: `data:image/png;base64,${b64}` }, cacheKey);
        if (url) return sendAndCache(res, { ok: true, provider: 'minimax', model: 'image-01', url }, cacheKey);
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
        return sendAndCache(res, { ok: true, provider: 'pollinations', model: 'Flux', image: `data:image/jpeg;base64,${b64}` }, cacheKey);
      } else {
        errors.push(`Pollinations: ${r.status}`);
      }
    } catch (e) { errors.push(`Pollinations: ${e.message}`); }
  }

  // â”€â”€ 4. Pollinations Turbo (fallback) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  try {
    const seed2 = Math.floor(Math.random() * 99999);
    const url2  = `https://image.pollinations.ai/prompt/${encodeURIComponent(p)}?width=512&height=512&seed=${seed2}&model=turbo&nologo=true`;
    const r2 = await fetch(url2, { signal: AbortSignal.timeout(20000) });
    if (r2.ok) {
      const buf = await r2.arrayBuffer();
      const b64 = Buffer.from(buf).toString('base64');
      return sendAndCache(res, { ok: true, provider: 'pollinations-turbo', model: 'Turbo', image: `data:image/jpeg;base64,${b64}` }, cacheKey);
    }
    errors.push(`Pollinations Turbo: ${r2.status}`);
  } catch (e) { errors.push(`Pollinations Turbo: ${e.message}`); }

  // Todos fallaron
  res.status(503).json({ ok: false, error: 'Todos los proveedores fallaron', details: errors });
});

// â”€â”€ Helper: guardar log de escaneo en Supabase â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// POST /api/enhance-image - Autoenhance.ai (mejorar calidad de imagen)
app.post('/api/enhance-image', requireAuth, async (req, res) => {
  const { image } = req.body || {};
  if (!image || typeof image !== 'string') {
    return res.status(400).json({ ok: false, error: '"image" (data URL) requerida.' });
  }
  const parsed = parseImageDataUrl(image);
  if (!parsed) {
    return res.status(400).json({ ok: false, error: 'Imagen invalida o demasiado pesada (max ~4MB, png/jpeg/webp/gif).' });
  }
  const key = process.env.AUTOENHANCE_API_KEY;
  if (!key) {
    return res.status(503).json({ ok: false, error: 'AUTOENHANCE_API_KEY no configurada.' });
  }
  try {
    const extMap = { 'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp', 'image/gif': 'gif' };
    const ext = extMap[parsed.mimeType] || 'png';
    const buf = Buffer.from(parsed.data, 'base64');
    const boundary = '----WilE' + Date.now() + 'x';
    const body = Buffer.concat([
      Buffer.from('--' + boundary + '\r\n' +
        'Content-Disposition: form-data; name="image"; filename="input.' + ext + '"\r\n' +
        'Content-Type: ' + parsed.mimeType + '\r\n\r\n'),
      buf,
      Buffer.from('\r\n--' + boundary + '--\r\n'),
    ]);
    const resp = await fetch('https://api.autoenhance.ai/v1/enhance', {
      method: 'POST',
      headers: {
        'X-API-KEY': key,
        'Authorization': 'Bearer ' + key,
        'Content-Type': 'multipart/form-data; boundary=' + boundary,
      },
      body,
      signal: AbortSignal.timeout(60000),
    });
    const ct = resp.headers.get('content-type') || '';
    if (!resp.ok) {
      const errText = await resp.text().catch(() => '');
      console.warn('Autoenhance error:', resp.status, errText.slice(0, 200));
      return res.status(resp.status).json({ ok: false, error: 'Autoenhance fallo (' + resp.status + ').' });
    }
    if (ct.includes('application/json')) {
      const j = await resp.json();
      return res.json({ ok: true, ...j });
    }
    const out = Buffer.from(await resp.arrayBuffer());
    res.json({ ok: true, image: 'data:' + (ct.split(';')[0] || 'image/png') + ';base64,' + out.toString('base64') });
  } catch (e) {
    console.warn('Enhance error:', e.message);
    res.status(500).json({ ok: false, error: 'Error mejorando la imagen.' });
  }
});

// â”€â”€ POST /api/edit-image â€” EdiciÃ³n de imagen con IA (Gemini) â”€â”€â”€â”€â”€â”€â”€â”€
// Acepta una imagen (data URL) y una instrucciÃ³n ("quita el fondo", "cambia
// la camiseta a rojo", "borra el perro", etc.) y devuelve la imagen editada.
app.post('/api/edit-image', requireAuth, async (req, res) => {
  const { image, prompt } = req.body || {};
  if (!image || typeof image !== 'string') {
    return res.status(400).json({ ok: false, error: '"image" (data URL) requerida.' });
  }
  if (!prompt || typeof prompt !== 'string' || !prompt.trim()) {
    return res.status(400).json({ ok: false, error: 'Falta la instrucciÃ³n de ediciÃ³n.' });
  }
  if (!process.env.GEMINI_API_KEY) {
    return res.status(503).json({ ok: false, error: 'GEMINI_API_KEY no configurada. La ediciÃ³n de imagen requiere Gemini.' });
  }
  const parsed = parseImageDataUrl(image);
  if (!parsed) {
    return res.status(400).json({ ok: false, error: 'Imagen invalida o demasiado pesada (max ~4MB).' });
  }
  const inst = prompt.trim().slice(0, 500);
  const mineData = Buffer.from(parsed.data, 'base64').toString('base64');
  try {
    const r = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=${process.env.GEMINI_API_KEY}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          contents: [{
            parts: [
              { inline_data: { mime_type: parsed.mimeType || 'image/png', data: mineData } },
              { text: 'Edita esta imagen segÃºn la instrucciÃ³n: "' + inst + '". Devuelve SOLO la imagen editada (sin texto).' },
            ],
          }],
          generationConfig: { temperature: 0.4 },
        }),
        signal: AbortSignal.timeout(45000),
      }
    );
    if (!r.ok) {
      const e = await r.json().catch(() => ({}));
      console.warn('Edit-image Gemini error:', r.status, (e.error && e.error.message) || '');
      return res.status(r.status).json({ ok: false, error: 'EdiciÃ³n fallÃ³ (' + r.status + ').' });
    }
    const d = await r.json();
    const parts = d.candidates?.[0]?.content?.parts || [];
    const imgPart = parts.find((p) => p && p.inlineData && p.inlineData.data) || parts.find((p) => p && p.inline_data && p.inline_data.data);
    if (!imgPart) {
      const txt = parts.map((p) => p.text || '').join(' ').trim();
      if (txt) return res.json({ ok: true, image: null, note: txt.slice(0, 300) });
      return res.status(502).json({ ok: false, error: 'Gemini no devolviÃ³ una imagen editada.' });
    }
    const b64 = imgPart.inlineData ? imgPart.inlineData.data : imgPart.inline_data.data;
    const mime = (imgPart.inlineData ? imgPart.inlineData.mimeType : imgPart.inline_data.mime_type) || 'image/png';
    res.json({ ok: true, image: 'data:' + mime + ';base64,' + b64, provider: 'gemini-edit' });
  } catch (e) {
    console.warn('Edit-image error:', e.message);
    res.status(500).json({ ok: false, error: 'Error editando la imagen.' });
  }
});

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
    console.warn('âš ï¸  scan_logs insert error:', e.message);
  }
}

// â”€â”€ POST /api/check-link â€” VirusTotal URL checker â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

// â”€â”€ POST /api/check-file â€” VirusTotal file checker â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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


// â”€â”€ GET /api/docs â€” Swagger UI inline â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

// â”€â”€ GET /api/docs.json â€” spec en JSON â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
app.get('/api/docs.json', (_, res) => res.json(swaggerSpec));

// â”€â”€ BLOG ESTÃTICO â€” GitHub API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Requiere: GITHUB_TOKEN en env vars con permisos repo:contents
// npm install @octokit/rest  (ya en package.json)

let octokit = null;
try {
  const { Octokit } = require('@octokit/rest');
  if (process.env.GITHUB_TOKEN) {
    octokit = new Octokit({ auth: process.env.GITHUB_TOKEN });
    console.log('   Blog GitHub: âœ… Octokit listo');
  } else {
    console.log('   Blog GitHub: âš ï¸  falta GITHUB_TOKEN en env vars');
  }
} catch(e) {
  console.warn('   Blog GitHub: âš ï¸  @octokit/rest no instalado â€”', e.message);
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

// â”€â”€ EXTRACCIÃ“N DE ÃCONOS DESDE URL "UNIVERSAL" â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// El admin pega el link que ya tiene a mano (repo de GitHub, ficha de
// F-Droid, ficha de Play Store, o directamente la imagen) y esto baja
// el Ã­cono REAL de la app (no el banner social del repo) y lo sube a
// img/ en GitHub vÃ­a Octokit, reutilizando ghUpdateFile(). No inventa
// ni asume: si no encuentra el Ã­cono, devuelve un error explicando quÃ©
// probÃ³, para que el admin pegue el link directo como alternativa.
// Igual que normalizeImagePath() en admin-hub.js: si es una ruta local
// (no http/https, no data:/blob:) sin "/" inicial, se la agrega, para
// que siempre resuelva desde la raÃ­z sin importar quÃ© pÃ¡gina la pinte.
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
// ruta/rama comunes) en un repo pÃºblico de GitHub, sin necesitar token
// propio (la API de contenidos de GitHub es pÃºblica para repos pÃºblicos).
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
      } catch { /* probar siguiente combinaciÃ³n */ }
    }
  }
  return null;
}

// Extrae la URL de og:image de una pÃ¡gina (F-Droid, Play Store, etc.)
async function fetchOgImageUrl(pageUrl) {
  const r = await fetch(pageUrl, { headers: { 'User-Agent': 'Mozilla/5.0 (CodeHub-IconBot)' } });
  if (!r.ok) throw new Error(`No se pudo abrir ${pageUrl} (HTTP ${r.status})`);
  const html = await r.text();
  const m = html.match(/<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']/i)
         || html.match(/<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']/i);
  if (!m) throw new Error('No encontrÃ© una imagen (og:image) en esa pÃ¡gina');
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

  // 2) Repo de GitHub â†’ Ã­cono real en fastlane/ (no el banner social)
  const ghMatch = clean.match(/github\.com\/([^\/]+)\/([^\/?#]+)/i);
  if (ghMatch) {
    const owner = ghMatch[1];
    const repo  = ghMatch[2].replace(/\.git$/, '');
    const found = await fetchGithubFastlaneIcon(owner, repo);
    if (found) return found;
    throw new Error(`No encontrÃ© fastlane/metadata/.../icon.png en ${owner}/${repo}. ProbÃ¡ pegando el link directo del Ã­cono (ej. raw.githubusercontent.com/.../icon.png).`);
  }

  // 3) F-Droid, Play Store, o cualquier pÃ¡gina con og:image
  if (lower.includes('f-droid.org') || lower.includes('play.google.com') || lower.includes('apps.apple.com')) {
    const imgUrl = await fetchOgImageUrl(clean);
    const r = await fetch(imgUrl);
    if (!r.ok) throw new Error(`No se pudo descargar el Ã­cono (HTTP ${r.status})`);
    return { buffer: Buffer.from(await r.arrayBuffer()), ext: iconExtFromUrl(imgUrl) };
  }

  throw new Error('URL no reconocida. UsÃ¡ un link directo a la imagen, un repo de GitHub, o la ficha de F-Droid/Play Store.');
}

// POST /api/admin/extract-icon â€” body: { sourceUrl, filename }
// Extrae el Ã­cono real desde la URL universal y lo sube a img/{filename}
// en el repo de GitHub. No toca la base de datos: el admin sigue usando
// "Guardar" (fila existente) o "Crear App" (app nueva) para persistir el
// campo imagen, igual que con cualquier otro campo del panel.
app.post('/api/admin/extract-icon', requireAdmin, async (req, res) => {
  try {
    const { sourceUrl, filename } = req.body;
    if (!sourceUrl) return res.status(400).json({ error: 'Falta sourceUrl' });
    if (!filename)  return res.status(400).json({ error: 'Falta filename (usÃ¡ el appId)' });

    const { buffer, ext } = await extractIconFromUniversalUrl(sourceUrl);
    if (buffer.length > 4 * 1024 * 1024) throw new Error('La imagen pesa mÃ¡s de 4MB');

    const safeName = String(filename).trim().replace(/[^a-zA-Z0-9._-]/g, '') + ext;
    if (!safeName || safeName === ext) throw new Error('filename invÃ¡lido');
    const repoPath = 'img/' + safeName;

    await ghUpdateFile(repoPath, buffer, `img: extraer Ã­cono (${safeName})`);
    await cacheDel('apps:all');

    res.json({ ok: true, imagen: '/' + repoPath, filename: safeName });
  } catch (e) {
    console.error('POST /api/admin/extract-icon error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// â”€â”€ ADMIN: WORKFLOWS DE GITHUB (AutomatizaciÃ³n) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Permite que el panel admin (admin-hub) dispare los workflows de
// mantenimiento del repositorio (seed del catÃ¡logo FOSS, monitor de
// actualizaciones, dedupe) sin salir de la UI.
// Requiere GITHUB_TOKEN con permiso `workflow` en Render.
const GITHUB_WORKFLOWS = [
  'seed-foss-catalog.yml',
  'check-app-updates.yml',
  'dedupe-catalog.yml',
  'enrich-app-logos.yml',
  'build-apk.yml',
];

// POST /api/admin/github/dispatch â€” body: { workflow, inputs }
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
      `ðŸš€ <b>Workflow disparado</b>\n<code>${workflow}</code>\nRef: <code>${GITHUB_BRANCH}</code>\nIP: <code>${clientIp(req)}</code>`);
    res.json({ ok: true, workflow, ref: GITHUB_BRANCH, run_url: `https://github.com/${GITHUB_OWNER}/${GITHUB_REPO}/actions/workflows/${workflow}` });
  } catch (e) {
    const code = e?.status || 500;
    const hint = code === 403 ? ' â€” Â¿GITHUB_TOKEN tiene permiso workflow?' : '';
    console.error('POST /api/admin/github/dispatch error:', e.message);
    res.status(code).json({ error: (e.message || 'Error disparando workflow') + hint });
  }
});

// GET /api/admin/github/runs â€” estado del Ãºltimo run de cada workflow
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

// â”€â”€ GITHUB SECRETS & VARIABLES â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// GestiÃ³n completa de secrets y variables del repositorio desde admin-hub.
// Solo el administrador tiene acceso (requireAdmin).

// GET /api/admin/github/secrets â€” listar secrets del repositorio
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

// POST /api/admin/github/secrets â€” crear/actualizar un secret
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
      `ðŸ” <b>Secret actualizado</b>\n<code>${name}</code>\nIP: <code>${clientIp(req)}</code>`);
    res.json({ ok: true, name });
  } catch (e) {
    console.error('POST /api/admin/github/secrets error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// DELETE /api/admin/github/secrets/:name â€” eliminar un secret
app.delete('/api/admin/github/secrets/:name', requireAdmin, async (req, res) => {
  try {
    if (!octokit) return res.status(503).json({ error: 'GITHUB_TOKEN no configurado' });
    const { name } = req.params;
    await octokit.rest.actions.deleteRepoSecret({
      owner: GITHUB_OWNER, repo: GITHUB_REPO, secret_name: name,
    });
    tgAlert('ghsecret_del', () =>
      `ðŸ—‘ï¸ <b>Secret eliminado</b>\n<code>${name}</code>\nIP: <code>${clientIp(req)}</code>`);
    res.json({ ok: true, name });
  } catch (e) {
    console.error('DELETE /api/admin/github/secrets error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// GET /api/admin/github/variables â€” listar variables del repositorio
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

// POST /api/admin/github/variables â€” crear/actualizar una variable
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
      `âš™ï¸ <b>Variable actualizada</b>\n<code>${name}</code>=<code>${value.slice(0,20)}${value.length>20?'â€¦':''}</code>\nIP: <code>${clientIp(req)}</code>`);
    res.json({ ok: true, name, value });
  } catch (e) {
    console.error('POST /api/admin/github/variables error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// DELETE /api/admin/github/variables/:name â€” eliminar una variable
app.delete('/api/admin/github/variables/:name', requireAdmin, async (req, res) => {
  try {
    if (!octokit) return res.status(503).json({ error: 'GITHUB_TOKEN no configurado' });
    const { name } = req.params;
    await octokit.rest.actions.deleteRepoVariable({
      owner: GITHUB_OWNER, repo: GITHUB_REPO, name,
    });
    tgAlert('ghvar_del', () =>
      `ðŸ—‘ï¸ <b>Variable eliminada</b>\n<code>${name}</code>\nIP: <code>${clientIp(req)}</code>`);
    res.json({ ok: true, name });
  } catch (e) {
    console.error('DELETE /api/admin/github/variables error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// BÃºsqueda de imÃ¡genes
// â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

// â”€â”€ ADMIN CONFIG â€” GET/PATCH remote config â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
app.get('/api/admin/config', requireAdmin, async (req, res) => {
  try {
    const cfg = await getAppConfig();
    res.json({ ok: true, config: cfg });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

app.patch('/api/admin/config', requireAdmin, async (req, res) => {
  try {
    if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
    const { config: updates } = req.body;
    if (!updates || typeof updates !== 'object') return res.status(400).json({ error: 'config object required' });

    const current = await getAppConfig();
    const merged = deepMerge(current, updates);
    merged.version = (current.version || 0) + 1;

    await AppConfig.findOneAndUpdate(
      { key: 'main' },
      { config: merged, version: merged.version, updated: new Date() },
      { upsert: true }
    );

    // Invalidate cache
    _appConfigCache = null;
    _appConfigCacheTs = 0;

    tgAlert('config', () =>
      `âš™ï¸ <b>Config actualizada</b> v${merged.version}\nIP: <code>${clientIp(req)}</code>`);
    res.json({ ok: true, version: merged.version });
  } catch (e) {
    console.error('PATCH /api/admin/config error:', e.message);
    res.status(500).json({ error: e.message });
  }
});

function deepMerge(target, source) {
  const result = { ...target };
  for (const key of Object.keys(source)) {
    if (source[key] && typeof source[key] === 'object' && !Array.isArray(source[key]) && target[key] && typeof target[key] === 'object') {
      result[key] = deepMerge(target[key], source[key]);
    } else {
      result[key] = source[key];
    }
  }
  return result;
}

// â”€â”€ GET /api/image-search â€” Buscar imÃ¡genes via SerpAPI â”€â”€â”€â”€â”€â”€â”€
app.get('/api/image-search', chatLimiter, async (req, res) => {
  const { q } = req.query;
  if (!q || !q.trim()) return res.status(400).json({ error: 'ParÃ¡metro q requerido.' });

  const SERP_KEY = process.env.SERPAPI_KEY;
  if (!SERP_KEY) return res.status(503).json({ error: 'BÃºsqueda de imÃ¡genes no disponible.' });

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
    res.status(500).json({ error: 'No se pudieron obtener imÃ¡genes.' });
  }
});

// â”€â”€ GET /api/search/google â€” Proxy Google Custom Search (DeepSearch) â”€â”€
app.get('/api/search/google', chatLimiter, async (req, res) => {
  const { q } = req.query;
  if (!q || !q.trim()) return res.status(400).json({ error: 'ParÃ¡metro q requerido.' });
  
  const SERP_KEY = process.env.SERPAPI_KEY;
  if (!SERP_KEY) return res.status(503).json({ error: 'BÃºsqueda web no disponible.' });
  
  try {
    const url = `https://serpapi.com/search.json?engine=google&q=${encodeURIComponent(q)}&hl=es&gl=gt&num=8&api_key=${SERP_KEY}`;
    const response = await fetch(url, { signal: AbortSignal.timeout(8000) });
    if (!response.ok) throw new Error('SerpAPI ' + response.status);
    const data = await response.json();
    res.json({ items: (data.organic_results || []).slice(0, 8).map(r => ({ title: r.title, snippet: r.snippet, link: r.link })) });
  } catch (err) {
    console.error('search/google error:', err.message);
    res.status(500).json({ error: 'Error en bÃºsqueda Google.' });
  }
});

// â”€â”€ GET /api/search/tavily â€” BÃºsqueda Tavily (DeepSearch) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
app.get('/api/search/tavily', chatLimiter, async (req, res) => {
  const { q } = req.query;
  if (!q || !q.trim()) return res.status(400).json({ error: 'ParÃ¡metro q requerido.' });
  
  const TAVILY_KEY = process.env.TAVILY_API_KEY;
  if (!TAVILY_KEY) return res.status(503).json({ error: 'BÃºsqueda Tavily no disponible.' });
  
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
    res.status(500).json({ error: 'Error en bÃºsqueda Tavily.' });
  }
});

// â”€â”€ PUSH NOTIFICATIONS (Web Push / VAPID) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// El frontend se suscribe con su ubicaciÃ³n y el servidor avisa por
// push SOLO cuando cambia la condiciÃ³n del clima (sin spam).
const webpush = require('web-push');

const VAPID_PUBLIC_KEY  = process.env.VAPID_PUBLIC_KEY  || 'BEl62iUYgUivxIkv69yViEuiBIa-Ib9-SkvMeAtA3LFgDzkrxZJjSgSnfckjBJuBkr3qBlyNhTJSKBHt1J_ypW4';
const VAPID_PRIVATE_KEY = process.env.VAPID_PRIVATE_KEY || 'UUxI4O8-FbRouAevSmBQ6o18hgE4nSG3qwvJTfKsg-I';
webpush.setVapidDetails('mailto:admin@codehub.gt', VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY);

// â”€â”€ FIREBASE CLOUD MESSAGING (FCM) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Push instantÃ¡neo para la app Android nativa.
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
    console.log('âœ… FCM: Firebase Cloud Messaging habilitado');
  } else {
    console.warn('âš ï¸  FCM: FIREBASE_SERVICE_ACCOUNT no configurado â€” push web-push Ãºnicamente');
  }
} catch (e) {
  console.warn('âš ï¸  FCM: firebase-admin no disponible:', e.message);
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
  last_brief_at timestamptz,
  weather_interval integer default 0,
  last_weather_snapshot text,
  created_at timestamptz default now(),
  updated_at timestamptz default now()
);
create index if not exists push_subs_alerts_idx on public.push_subs (alerts);
`;

// Migrar: agregar columnas si no existen
async function migratePushTable() {
  if (!supabase) return;
  try {
    await supabase.rpc('exec_sql', { query: 'alter table public.push_subs add column if not exists last_brief_at timestamptz;' });
    await supabase.rpc('exec_sql', { query: 'alter table public.push_subs add column if not exists weather_interval integer default 0;' });
    await supabase.rpc('exec_sql', { query: 'alter table public.push_subs add column if not exists last_weather_snapshot text;' });
  } catch (e) { console.warn('âš ï¸  Push migrate:', e.message); }
}
migratePushTable();

let pushStore = new Map(); // fallback en memoria si Supabase no estÃ¡ disponible

async function ensurePushTable() {
  if (!supabase) return false;
  const statements = splitSqlStatements(PUSH_SQL);
  try {
    for (const stmt of statements) {
      const { error } = await supabase.rpc('exec_sql', { query: stmt });
      if (error) {
        console.warn('âš ï¸  Push: no se pudo crear tabla push_subs (' + error.message + ') â€” creala a mano con backend/push_subs.sql');
        return false;
      }
    }
    console.log('âœ… Push: tabla push_subs lista');
    return true;
  } catch (e) {
    console.warn('âš ï¸  Push: error asegurando tabla:', e.message);
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
    last_brief_at: row.last_brief_at || null,
    weather_interval: row.weather_interval != null ? normalizeWeatherInterval(row.weather_interval) : 0,
    last_weather_snapshot: row.last_weather_snapshot || null,
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
        last_brief_at: rec.last_brief_at || null,
        weather_interval: rec.weather_interval != null ? normalizeWeatherInterval(rec.weather_interval) : 0,
        last_weather_snapshot: rec.last_weather_snapshot || null,
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
    // 404/410 = suscripciÃ³n expirada. 401/403 = clave VAPID no coincide
    // con la que se usÃ³ al suscribirse (rotaciÃ³n de VAPID_PUBLIC/PRIVATE_KEY
    // o subs creadas antes de fijar esas env vars). En ambos casos la sub
    // es inservible: se borra para que el cliente se re-suscriba solo la
    // prÃ³xima vez que visite el sitio (ver chequeo de applicationServerKey
    // en initIndexPush, index.html).
    if ([401, 403, 404, 410].includes(e.statusCode)) {
      await pushDelete(rec.endpoint);
    }
    return { ok: false, code: e.statusCode, message: e.body || e.message };
  }
}

// Clave pÃºblica VAPID que el frontend debe usar al suscribirse.
// Antes estaba hardcodeada en index.html (con el mismo valor de fallback
// que aquÃ­ abajo); si en Render se configuraban VAPID_PUBLIC_KEY/PRIVATE_KEY
// propios, el backend firmaba con la clave nueva pero el navegador seguÃ­a
// suscribiÃ©ndose con la clave vieja hardcodeada â†’ desajuste de claves â†’
// el push fallaba en silencio (la suscripciÃ³n se guardaba, pero
// webpush.sendNotification nunca llegaba). Este endpoint es la Ãºnica
// fuente de verdad: el frontend la consulta en vez de tenerla fija.
app.get('/api/push/vapid-public-key', (_req, res) => {
  res.json({ ok: true, key: VAPID_PUBLIC_KEY });
});

app.post('/api/push/subscribe', chatLimiter, async (req, res) => {
  try {
    const { subscription, location, prefs } = req.body || {};
    const sub = subscription || {};
    if (!sub.endpoint || !sub.keys || !sub.keys.p256dh || !sub.keys.auth) {
      return res.status(400).json({ ok: false, error: 'SuscripciÃ³n invÃ¡lida' });
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
      weather_interval: prefs && prefs.interval ? normalizeWeatherInterval(prefs.interval) : 0,
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
    if (!rec) return res.status(404).json({ ok: false, error: 'SuscripciÃ³n no encontrada' });
    if (location) {
      if (Number.isFinite(+location.lat)) rec.lat = +location.lat;
      if (Number.isFinite(+location.lon)) rec.lon = +location.lon;
      if (location.city)     rec.city    = String(location.city).slice(0, 120);
      if (location.country)  rec.country = String(location.country).slice(0, 80);
      if (location.timezone) rec.timezone = String(location.timezone).slice(0, 60);
    }
    if (prefs && typeof prefs.alerts === 'boolean') rec.alerts = prefs.alerts;
    if (prefs && prefs.interval !== undefined) rec.weather_interval = normalizeWeatherInterval(prefs.interval);
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
    if (!rec) return res.status(404).json({ ok: false, error: 'SuscripciÃ³n no encontrada' });
    const r = await sendPush(rec, { title, body: body || '', type: 'general', icon: '/splash/codehub.png', url: url || '/' });
    res.json(r);
  } catch (e) { res.status(500).json({ ok: false, error: e.message }); }
});

// â”€â”€ FCM TOKEN MANAGEMENT â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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
        console.warn('âš ï¸  FCM: no se pudo crear tabla fcm_tokens â€” ' + error.message);
        return false;
      }
    }
    console.log('âœ… FCM: tabla fcm_tokens lista');
    return true;
  } catch (e) {
    console.warn('âš ï¸  FCM: error asegurando tabla:', e.message);
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

// Enviar vÃ­a FCM a un token especÃ­fico
async function sendFCM(token, payload) {
  if (!fcmEnabled || !admin) return { ok: false, reason: 'fcm_disabled' };
  try {
    await admin.messaging().send({
      token: token,
      notification: { title: payload.title, body: payload.body },
      data: { type: payload.type || 'general', url: payload.url || '/' },
      android: {
        // priority: 'high' â€” despierta el dispositivo incluso en Doze
        // profundo. Sin esto Android puede demorar la entrega hasta
        // la prÃ³xima ventana de mantenimiento si la app no se usa hace
        // tiempo, dando la falsa impresiÃ³n de que "no estÃ¡ despierta".
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

// â”€â”€ CRASH REPORTING (app Android) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// La app nativa (WebView wrapper) reporta acÃ¡ tanto crashes fatales
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
      return res.status(400).json({ ok: false, error: 'Reporte vacÃ­o' });
    }

    const when  = timestamp ? new Date(Number(timestamp) || timestamp) : new Date();
    const trace = String(stackTrace || '').slice(0, 3200); // margen para el lÃ­mite de 4096 de Telegram
    const key   = `crash:${fatal ? 'fatal' : 'caught'}:${tag || ''}:${exceptionClass || ''}`;
    const icon  = fatal ? 'ðŸ’¥' : 'âš ï¸';
    const kind  = fatal ? 'CRASH FATAL' : 'ExcepciÃ³n capturada';
    const origin = platform === 'web' ? 'Web' : 'App Android';

    tgAlert(key, () =>
      `${icon} <b>${kind} â€” ${origin}</b>\n` +
      (tag ? `MÃ³dulo: <code>${escHtml(tag)}</code>\n` : '') +
      `Clase: <code>${escHtml(exceptionClass || '?')}</code>\n` +
      `Mensaje: ${escHtml(message || '(sin mensaje)')}\n` +
      `Dispositivo: ${escHtml(deviceModel || '?')} Â· Android ${escHtml(androidVersion || '?')}\n` +
      `VersiÃ³n app: ${escHtml(appVersion || '?')} Â· Plataforma: ${escHtml(platform || 'android')}\n` +
      `Hora: ${when.toISOString()}\n\n` +
      (trace ? `<pre>${escHtml(trace)}</pre>` : ''),
      { windowMs: 10000 });

    res.json({ ok: true });
  } catch (e) { res.status(500).json({ ok: false, error: e.message }); }
});

// EnvÃ­a un push a todos los suscriptores (broadcast reutilizable por
// los flujos automÃ¡ticos: nueva app, app actualizada, CodeHub Release).
// EnvÃ­a vÃ­a Web Push (VAPID) + FCM (app nativa).
async function broadcastPush({ title, body = '', url = '/', type = 'announcement', appId, version }) {
  const t = String(title).trim().slice(0, 80);
  const b = String(body || '').trim().slice(0, 180);
  let sentWeb = 0, sentAndroid = 0;
  const failures = [];

  // 1) Web Push (VAPID) â€” suscriptores del navegador
  const webSubs = await pushList();
  for (const sub of webSubs) {
    const result = await sendPush(sub, { title: t, body: b, type, appId, version, icon: '/splash/codehub.png', url });
    if (result.ok) {
      sentWeb += 1;
    } else {
      failures.push({ kind: 'web', endpoint: (sub.endpoint || '').slice(-24), code: result.code, message: result.message || result.reason });
    }
  }

  // 2) FCM â€” app Android nativa
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
  // por quÃ© fallÃ³. Con esto, en Render â†’ Logs se ve el motivo exacto de
  // cada fallo (clave VAPID desactualizada, token FCM invÃ¡lido, etc.)
  if (sent < total) {
    console.warn(`âš ï¸  broadcastPush: ${sent}/${total} entregados (web ${sentWeb}/${webSubs.length}, android ${sentAndroid}/${fcmTotal})`);
    failures.slice(0, 20).forEach(f => {
      console.warn(`   âœ— [${f.kind}] ${f.kind === 'web' ? 'endpoint â€¦' + f.endpoint : 'token â€¦' + f.token} â€” code ${f.code || '?'}: ${f.message || '(sin detalle)'}`);
    });
    if (failures.length > 20) console.warn(`   â€¦ y ${failures.length - 20} fallos mÃ¡s`);
  }

  return { sent, total, sentWeb, sentAndroid, webTotal: webSubs.length, androidTotal: fcmTotal, fcmEnabled, failures: failures.slice(0, 20) };
}

app.post('/api/admin/push/broadcast', requireAdmin, async (req, res) => {
  try {
    const { title, body, url, type, appId, version } = req.body || {};
    if (!title || !String(title).trim()) {
      return res.status(400).json({ ok: false, error: 'Falta el tÃ­tulo de la notificaciÃ³n' });
    }
    const r = await broadcastPush({ title, body, url, type, appId, version });
    res.json({ ok: true, ...r });
  } catch (e) {
    console.error('admin/push/broadcast error:', e.message);
    res.status(500).json({ ok: false, error: e.message });
  }
});

// â”€â”€ CODEHUB RELEASES â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Novedades del proyecto publicadas desde el admin-hub. Al publicar se
// guardan en MongoDB, se avisa por WebSocket y se envÃ­a push a todos.

app.post('/api/admin/releases', requireAdmin, async (req, res) => {
  try {
    const { title, body, version, url, type } = req.body || {};
    if (!title || !String(title).trim()) {
      return res.status(400).json({ ok: false, error: 'Falta el tÃ­tulo del release' });
    }
    const rel = await Release.create({
      title: String(title).trim().slice(0, 80),
      body: String(body || '').slice(0, 500),
      version: String(version || '').slice(0, 40),
      url: url || '/',
      type: type || 'release',
    });
    broadcast('codehub_release', { id: String(rel._id), title: rel.title, version: rel.version });
    tgAlert('release', () => `ðŸš€ <b>CodeHub Release</b>\n${String(rel.title).slice(0, 50)}${rel.version ? ' Â· ' + rel.version : ''}`, { windowMs: 15000 });
    const push = await broadcastPush({
      title: rel.version ? 'ðŸš€ CodeHub ' + rel.version : 'ðŸš€ CodeHub Release',
      body: rel.title + (rel.body ? ' â€” ' + String(rel.body).slice(0, 120) : ''),
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

// â”€â”€ CHANGELOG â€” Ãºltima versiÃ³n para el diÃ¡logo de actualizaciÃ³n â”€â”€
let _changelog = null;
function loadChangelog() {
  if (_changelog) return _changelog;
  try {
    const raw = fs.readFileSync(path.join(__dirname, '..', 'changelog.json'), 'utf8');
    _changelog = JSON.parse(raw);
  } catch (e) { _changelog = []; }
  return _changelog;
}

app.get('/api/changelog', (req, res) => {
  const cl = loadChangelog();
  if (!cl.length) return res.json({ ok: true, entries: [] });
  const since = req.query.since || '';
  const entries = since ? cl.filter(e => e.version > since) : cl.slice(0, 3);
  res.json({ ok: true, entries });
});

// â”€â”€ REMOTE CONFIG â€” public endpoint (frontend reads this) â”€â”€â”€â”€â”€
app.get('/api/config', async (req, res) => {
  try {
    const cfg = await getAppConfig();
    const clientVersion = parseInt(req.query.v) || 0;
    const needsUpdate = clientVersion < cfg.version;
    res.set('Cache-Control', 'public, max-age=30');
    res.set('X-Config-Version', String(cfg.version));
    res.json({ ok: true, config: needsUpdate ? cfg : { version: cfg.version, updated: cfg.updated || null } });
  } catch (e) {
    res.json({ ok: true, config: DEFAULT_CONFIG });
  }
});

// Lista pÃºblica de releases (campana de notificaciones / pÃ¡gina)
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

// ── CLIMA → PUSH (módulo separado — backend/clima) ────────────
// La lógica inteligente de clima (fetch, alertas por CÓMO cambia:
// radiación UV por hora, probabilidad de lluvia, temperatura, viento)
// vive ahora en backend/clima/. Aquí sólo se inyectan las dependencias
// y se exponen los endpoints y el scheduler.
const climaEngine = require('./clima')({
  supabase, sendPush, sendFCM, fcmEnabled, fcmListTokens, pushList, pushSave,
});
// normalizeWeatherInterval se re-exporta como function declaration (hoisted)
// para que sigan funcionando las llamadas previas en pushRowToSub/pushSave.
function normalizeWeatherInterval(v) { return climaEngine.normalizeWeatherInterval(v); }
app.get('/api/push/weather/check', climaEngine.weatherEndpoint);
// Scheduler climático — cada 30 min; solo envía push cuando cambia la condición
climaEngine.startScheduler(30 * 60 * 1000);

// â”€â”€ ACTIVIDAD SÃSMICA (terremotos) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Nota honesta: NO existe una API pÃºblica de "alerta temprana" de
// Google (usada en Android vÃ­a Play Services, limitada a un puÃ±ado de
// regiones). AquÃ­ usamos la API abierta de USGS (global) para detectar
// sismos RELEVANTES recientes cerca del usuario y avisar post-evento
// con magnitud, distancia y consejos de seguridad. En regiones donde
// Google EEWS no llega (la mayorÃ­a de LatAm, incluida Guatemala) esto
// sigue dando valor real: enterarse de un sismo cercano + cÃ³mo actuar.

const USGS_FEED = 'https://earthquake.usgs.gov/earthquake/feed/v1.0/summary/all_day.geojson';

// Umbrales configurables por magnitud (pueden editarse en admin config)
function seismicThreshold() {
  return 4.5; // bajo este nivel no se molesta al usuario
}
function seismicRadiusKm() {
  return 300; // distancia mÃ¡x. para considerar "cercano"
}

async function fetchRecentEarthquakes() {
  const r = await fetch(USGS_FEED, { headers: { 'User-Agent': 'CodeHub-Seismic' } });
  if (!r.ok) throw new Error('USGS ' + r.status);
  const data = await r.json();
  return data.features || [];
}

function haversineKm(lat1, lon1, lat2, lon2) {
  const R = 6371;
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLon = (lon2 - lon1) * Math.PI / 180;
  const a = Math.sin(dLat / 2) ** 2 +
            Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}

function describeMagnitude(mag, distKm) {
  const emoji = mag >= 6.0 ? 'ðŸ”¥' : mag >= 5.0 ? 'âš ï¸' : 'ðŸ“³';
  const near = distKm < 50 ? ' muy cerca' : distKm < 150 ? ' cercano' : ' en tu regiÃ³n';
  return `${emoji} Sismo M${mag.toFixed(1)}${near} (${Math.round(distKm)} km)`;
}

function earthquakeSafetyTips(mag) {
  return mag >= 6.0
    ? 'Protege tu cabeza, alÃ©jate de ventanas/objetos que caigan y, si puedes, refÃºgiate bajo un mueble firme. Sigue las indicaciones de ProtecciÃ³n Civil.'
    : 'EstÃ© preparado: revisa que no haya grietas nuevas y asegura objetos que puedan caer en un sismo mayor.';
}

// Una sola pasada de verificaciÃ³n sÃ­smica. Devuelve { earthquakes: N }
async function seismicPushPass() {
  let subs;
  try { subs = await pushList(); } catch (e) { return { earthquakes: 0 }; }
  const enabled = subs.filter(s => s.alerts && Number.isFinite(+s.lat) && Number.isFinite(+s.lon));

  // TambiÃ©n tokens FCM
  const fcmTokens = [];
  if (fcmEnabled) {
    try { fcmTokens.push(...await fcmListTokens()); } catch (e) {}
  }
  const targets = [
    ...enabled.map(s => ({ ...s, _isFCM: false })),
    ...fcmTokens.filter(t => Number.isFinite(+t.lat) && Number.isFinite(+t.lon)).map(t => ({ ...t, _isFCM: true })),
  ];
  if (!targets.length) return { earthquakes: 0 };

  let quakes;
  try { quakes = await fetchRecentEarthquakes(); } catch (e) { return { earthquakes: 0 }; }
  const threshold = seismicThreshold();
  const radius = seismicRadiusKm();
  const cutoff = Date.now() - 3 * 60 * 60 * 1000; // solo Ãºltimos 3h

  let sent = 0;
  const lastKey = {};
  const today = new Date().toISOString().slice(0, 10);

  for (const t of targets) {
    const subKey = t.endpoint || t.token || ('fcm:' + (t.token || ''));
    const relevant = quakes.filter(q => {
      const geo = q.geometry && q.geometry.coordinates;
      if (!geo) return false;
      const mag = q.properties && q.properties.mag;
      const time = q.properties && q.properties.time;
      if (typeof mag !== 'number' || !time || time < cutoff) return false;
      if (mag < threshold) return false;
      const dist = haversineKm(+t.lat, +t.lon, geo[1], geo[0]);
      return dist <= radius;
    });
    // Ordenar por tiempo: el mÃ¡s reciente primero
    relevant.sort((a, b) => (b.properties.time || 0) - (a.properties.time || 0));
    const latest = relevant[0];
    if (!latest) continue;

    const mag = latest.properties.mag;
    const place = (latest.properties.place || '').replace(/,.*$/, '').trim();
    const dist = haversineKm(+t.lat, +t.lon, latest.geometry.coordinates[1], latest.geometry.coordinates[0]);
    const key = latest.properties.id;
    const prev = lastKey[subKey] || (t.last_alert_condition && t.last_alert_condition.startsWith('EQ:') ? t.last_alert_condition : null);
    const cacheKey = 'EQ:' + key;

    // No repetir si ya se avisÃ³ de este mismo sismo
    if (prev === cacheKey) continue;

    const body =
      describeMagnitude(mag, dist) +
      (place ? ' Â· ' + place : '') +
      '\n' + earthquakeSafetyTips(mag);

    let r;
    if (t._isFCM) {
      r = await sendFCM(t.token, {
        title: 'ðŸŒ‹ Actividad sÃ­smica',
        body: body + '\nðŸ“ ' + (t.city || 'Tu zona'),
        type: 'seismic',
        url: '/#weather-section',
      });
    } else {
      r = await sendPush(t, {
        title: 'ðŸŒ‹ Actividad sÃ­smica',
        body: body,
        type: 'seismic',
        icon: '/splash/codehub.png',
        url: '/#weather-section',
      });
    }
    if (r.ok) {
      lastKey[subKey] = cacheKey;
      if (!t._isFCM) { t.last_alert_condition = cacheKey; t.last_alert_at = new Date().toISOString(); await pushSave(t); }
      sent++;
    }
    void today;
  }
  return { earthquakes: sent };
}

// Scheduler sÃ­smico â€” cada 10 min
setInterval(() => {
  seismicPushPass()
    .then(o => { if (o.earthquakes) console.log('ðŸŒ‹ Push sÃ­smico enviado:', o.earthquakes); })
    .catch(e => console.warn('âš ï¸  Push sÃ­smico error:', e.message));
}, 10 * 60 * 1000);

app.get('/api/push/seismic/check', async (req, res) => {
  try {
    const out = await seismicPushPass();
    res.json({ ok: true, ...out });
  } catch (e) { res.status(500).json({ ok: false, error: e.message }); }
});

// Scheduler climÃ¡tico â€” manejado por backend/clima (el motor inyecta sus
// dependencias y arranca su propio setInterval de 30 min en server.js bajo
// el nombre del mÃ³dulo). Ver la inicializaciÃ³n en el bloque de clima.

// â”€â”€ MONITOR AUTOMÃTICO DE RELEASES (apps open source) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Revisa periÃ³dicamente las apps con `source_repo` vÃ­a la API pÃºblica
// de GitHub; si hay una versiÃ³n nueva publicada actualiza el documento
// en MongoDB y envÃ­a push a todos los suscriptores ("app se actualizÃ³").
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

// truncate ahora vive en ./utils.js (importado arriba)

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
        tag: 'ðŸ”„ Actualizada',
        updatedAt: new Date(),
      };
      if (apkUrl) update.enlace = apkUrl;

      await App.updateOne({ appId: app.appId }, { $set: update });
      await cacheDel('apps:all');
      broadcastAppsChanged();
      updated++;

      const r = await broadcastPush({
        title: 'ðŸ”„ ' + app.nombre + ' se actualizÃ³',
        body: truncate(release.body, 120) || 'Nueva versiÃ³n ' + nuevaVersion + ' disponible',
        type: 'app_update',
        appId: app.appId,
        version: nuevaVersion,
        url: '/opensource.html',
      });
      sent += r.sent || 0;
      console.log('â¬†ï¸  Auto: ' + app.nombre + ' â†’ ' + nuevaVersion + ' (push ' + (r.sent || 0) + ')');
    } catch (e) {
      console.warn('âš ï¸  Auto update ' + app.appId + ':', e.message);
    }
    await new Promise(r => setTimeout(r, 500));
  }
  return { ok: true, checked: apps.length, updated, sent };
}

// Scheduler del monitor â€” cada 6h por defecto (configurable con AUTO_UPDATE_MS)
setInterval(() => {
  autoCheckAppUpdates()
    .then(o => { if (o.updated) console.log('ðŸ¤– Monitor releases: ' + o.updated + ' actualizada(s)'); })
    .catch(e => console.warn('âš ï¸  Monitor releases error:', e.message));
}, AUTO_UPDATE_MS);

// Endpoint para disparar el monitor manualmente (admin-hub / cron externo)
app.get('/api/admin/apps/check-updates', requireAdmin, async (req, res) => {
  try {
    const out = await autoCheckAppUpdates();
    res.json({ ok: true, ...out });
  } catch (e) { res.status(500).json({ ok: false, error: e.message }); }
});

// â”€â”€ WEBHOOK DE GITHUB â€” releases en tiempo real â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// autoCheckAppUpdates() (arriba) revisa cada 6h por polling â€” funciona,
// pero no es "tiempo real": si publicas un release, los suscriptores no
// se enteran hasta el siguiente ciclo del monitor. Este webhook hace que
// GitHub avise al instante en cuanto se publica un release, y aquÃ­ mismo
// se dispara el push a todos los suscriptores sin esperar al polling.
//
// ConfiguraciÃ³n necesaria (una vez por repo que quieras notificar al
// instante, ademÃ¡s de GITHUB_WEBHOOK_SECRET en las env vars de Render):
//   GitHub repo â†’ Settings â†’ Webhooks â†’ Add webhook
//     Payload URL: https://<tu-backend>/api/webhook/github-release
//     Content type: application/json
//     Secret: el mismo valor que GITHUB_WEBHOOK_SECRET
//     Evento: "Let me select individual events" â†’ Releases
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
    if (!verifyGithubSignature(req)) return res.status(401).json({ ok: false, error: 'Firma invÃ¡lida o GITHUB_WEBHOOK_SECRET no configurado' });

    const event = req.get('x-github-event');
    if (event === 'ping') return res.json({ ok: true, pong: true });
    if (event !== 'release') return res.json({ ok: true, ignored: event });

    const payload = req.body || {};
    if (payload.action !== 'published') return res.json({ ok: true, ignored: payload.action });

    const ownerRepo = payload.repository && payload.repository.full_name;
    const release = payload.release;
    if (!ownerRepo || !release) return res.status(400).json({ ok: false, error: 'Payload incompleto' });

    const app_ = await App.findOne({ source_repo: ownerRepo });
    if (!app_) return res.json({ ok: true, matched: false, reason: 'Ninguna app del catÃ¡logo usa ese source_repo' });

    const nuevaVersion = release.tag_name || release.name || null;
    if (!nuevaVersion || nuevaVersion === app_.version) return res.json({ ok: true, matched: true, skipped: 'misma versiÃ³n' });

    const apkUrl = pickApkAsset(release);
    const update = {
      version: nuevaVersion,
      changelog: truncate(release.body),
      tag: 'ðŸ”„ Actualizada',
      updatedAt: new Date(),
    };
    if (apkUrl) update.enlace = apkUrl;

    await App.updateOne({ appId: app_.appId }, { $set: update });
    await cacheDel('apps:all');
    broadcastAppsChanged();

    const r = await broadcastPush({
      title: 'ðŸ”„ ' + app_.nombre + ' se actualizÃ³',
      body: truncate(release.body, 120) || 'Nueva versiÃ³n ' + nuevaVersion + ' disponible',
      type: 'app_update',
      appId: app_.appId,
      version: nuevaVersion,
      url: '/opensource.html',
    });

    console.log('âš¡ Webhook release instantÃ¡neo: ' + app_.nombre + ' â†’ ' + nuevaVersion + ' (push ' + (r.sent || 0) + ')');
    res.json({ ok: true, matched: true, updated: true, sent: r.sent || 0 });
  } catch (e) {
    console.error('webhook/github-release error:', e.message);
    res.status(500).json({ ok: false, error: e.message });
  }
});

// â”€â”€ PROCESO: capturar errores no controlados â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Sin esto, un throw async sin catch (p. ej. en un handler de WS o un
// setTimeout) tumba el proceso entero en Render sin dejar rastro claro
// de la causa. Se loguea + se avisa por Telegram, pero NO se hace
// process.exit() salvo que el error sea realmente fatal para el event
// loop â€” dejar el proceso vivo es preferible a un crash-loop.
process.on('unhandledRejection', (reason, promise) => {
  const msg = reason instanceof Error ? reason.stack || reason.message : String(reason);
  console.error('âš ï¸ unhandledRejection:', msg);
  tgAlert('unhandled_rejection', () => 'ðŸ”´ unhandledRejection:\n' + String(msg).slice(0, 500), { windowMs: 30000 });
});

process.on('uncaughtException', (err) => {
  console.error('ðŸ”´ uncaughtException:', err.stack || err.message);
  tgAlert('uncaught_exception', () => 'ðŸ”´ uncaughtException:\n' + String(err.stack || err.message).slice(0, 500), { windowMs: 30000 });
  // No se llama process.exit(): en Express, un throw sÃ­ncrono dentro de
  // un route handler normal ya es capturado por Express mismo; esto
  // cubre solo callbacks/timers fuera de ese ciclo. Mantener el
  // proceso vivo evita reinicios en cascada que tumbarÃ­an WebSockets
  // y sesiones activas por un error aislado.
});

// â”€â”€ Streaming SSE (Server-Sent Events) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Endpoint alternativo a /api/chat que devuelve la respuesta token por token.
// Soporta Groq, Cerebras, HuggingFace, OpenRouter, Mistral, Kimi (OpenAI-compat)
// y Claude (Anthropic SSE). Cohere y Gemini Vision caen a non-streaming.
app.post('/api/chat/stream', requireAuth, async (req, res) => {
  const { message, sessionId = 'anon', image, images, pdfText, skill_id } = req.body;
  if (!message || typeof message !== 'string') return res.status(400).json({ error: '"message" requerido.' });
  if (message.trim().length > 1000) return res.status(400).json({ error: 'Mensaje muy largo.' });
  if (!process.env.GROQ_API_KEY && !process.env.GEMINI_API_KEY) return res.status(503).json({ error: 'Sin API keys.' });

  // â”€â”€ Imagen/PDF escaneado: fallback a non-streaming (solo Gemini Vision) â”€â”€
  const imgList = image ? [image] : (Array.isArray(images) && images.length ? images.slice(0, 5) : null);
  if (imgList && imgList.length) {
    req.url = '/api/chat';
    return app.handle(req, res);
  }

  // â”€â”€ LÃ­mite diario server-side â”€â”€
  const emiKey = req.authUser ? 'u:' + req.authUser.id : 'd:' + clientIp(req);
  const emiLimit = await getEmiLimit(!!req.authUser);
  const emiUsed = await getEmiUsage(emiKey);
  if (emiUsed >= emiLimit) {
    return res.status(429).json({ error: `LÃ­mite diario alcanzado (${emiLimit} mensajes). ${req.authUser ? '' : 'Inicia sesiÃ³n para mÃ¡s.'}`, code: 'EMI_DAILY_LIMIT', limit: emiLimit, used: emiUsed });
  }

  // â”€â”€ Recuperar historial â”€â”€
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
      content: '[Documento adjunto â€” resumen comprimido del documento. Responde usando SOLO este contenido como referencia, en espaÃ±ol]:\n' + pdfText.slice(0, 40000)
    });
  }

  // F1.1: SYSTEM dinÃ¡mico â€” base para queries generales, completa para CodeHub
  let system = classifySystem(message);
  // Skill activa: inyecta su guÃ­a
  if (skill_id) {
    const skill = loadSkillJson(String(skill_id));
    if (skill && skill.system_prompt_inject) system = skill.system_prompt_inject + '\n\n' + system;
  }
  // WIL.E: contexto aumentado (memoria del usuario + base de conocimiento RAG)
  if (dbConnected) {
    try {
      const ctx = await buildContext({
        userId: req.authUser ? req.authUser.id : 'anon',
        ownerId: 'admin',
        message,
        topK: 3,
      });
      if (ctx) system = augmentSystem(system, ctx);
    } catch (e) {
      console.warn('Wil.E contexto error:', e.message);
    }
  }
  // WIL.E: bÃºsqueda web en vivo (datos actuales) cuando la consulta lo pide
  try {
    const live = await liveWebContext(message);
    if (live) system = system + '\n\n' + live;
  } catch (e) { /* silencioso */ }
  // WIL.E: herramienta de cÃ³mputo (cÃ¡lculos, fecha, conversiones) sin LLM
  try {
    const tool = computeTool(message);
    if (tool) system = system + '\n\n' + tool;
  } catch (e) { /* silencioso */ }
  // WIL.E: function-calling â€” ejecuta la herramienta detectada (web/computo/URL)
  try {
    const dt = detectTool(message);
    if (dt) {
      const out = await executeTool(dt.name, dt.arg);
      if (out) system = system + '\n\n' + out;
    }
  } catch (e) { /* silencioso */ }
  // F1.2+F1.4: Smart truncation con budget de 10k tokens (~40k chars)
  const msgs = buildSmartMessages(system, sessionHistory, 10000);

  // â”€â”€ Setup SSE headers â”€â”€
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

    // â”€â”€ Claude streaming â”€â”€
    if (!replied && order[0] === 'Claude' && process.env.ANTHROPIC_API_KEY) {
      try {
        const sysMsg = msgs.find(m => m.role === 'system');
        const chatMsgs = msgs.filter(m => m.role !== 'system');
        const body = {
          model: 'claude-sonnet-4-5', max_tokens: adaptiveMaxTokens(message), temperature: 0.65,
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

    // â”€â”€ OpenAI-compatible streaming providers â”€â”€
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
            extraHeaders: { 'HTTP-Referer': process.env.FRONTEND_URL || 'https://wilson360-labs.vercel.app', 'X-Title': 'WIL.E COPILOT' }
          });
        }
      }
      const ordered = order.filter(n => n !== 'Claude' && n !== 'Gemini' && n !== 'Cohere');
      const sorted = [...ordered.map(n => oaiProviders.find(p => p.name === n)), ...oaiProviders.filter(p => !ordered.includes(p.name))].filter(Boolean);

      for (const prov of sorted) {
        if (!prov.key || upstreamAbort.signal.aborted) continue;
        try {
          const body = { model: prov.model, max_tokens: adaptiveMaxTokens(message), temperature: 0.65, messages: msgs, stream: true, stream_options: { include_usage: true } };
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

    // â”€â”€ Gemini / Cohere fallback: non-streaming â”€â”€
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

    // â”€â”€ Finalizar: persistir, side effects, done â”€â”€
    if (dbConnected) ChatMessage.insertMany([
      { sessionId, role: 'user', content: message.trim(), tokens: usage.input, model: modelName },
      { sessionId, role: 'assistant', content: fullReply, tokens: usage.output, model: modelName },
    ]).catch(() => {});

    const emiNow = await incrEmiUsage(emiKey);
    broadcast('chat_used', { model: modelName, tokens: usage.input + usage.output });
    trackEvent('chat', null, { model: modelName, tokens: usage.input + usage.output });
    tgAlert('chat', () => 'Chat con WIL.E (stream): ' + String(message || '').slice(0, 60).replace(/[<>]/g, '') + ' | ' + modelName, { windowMs: 30000 });

    // WIL.E: aprende hechos del mensaje del usuario (memoria entrenable)
    if (dbConnected && req.authUser) {
      remember({ userId: req.authUser.id, text: message }).catch(() => {});
    }

    sendSSE('done', { reply: fullReply, usage: { ...usage, total: usage.input + usage.output }, model: modelName, emi: { used: emiNow, limit: emiLimit } });
    res.end();

  } catch (err) {
    console.error('Stream endpoint error:', err.message);
    sendSSE('error', { error: 'Error interno.' });
    res.end();
  }
});

// â”€â”€ 404 + error handler globales (deben ir AL FINAL, tras todas las rutas) â”€â”€
app.use((req, res) => {
  if (req.path === '/api/health/keys') return res.json({
    autoenhance: !!(process.env.AUTOENHANCE_API_KEY),
    groq:        !!(process.env.GROQ_API_KEY),
    gemini:      !!(process.env.GEMINI_API_KEY),
    claude:      !!(process.env.ANTHROPIC_API_KEY),
    cohere:      !!(process.env.COHERE_API_KEY),
  });
  // Un 404 en /api/* casi siempre es seÃ±al de un bug real (ruta mal
  // ordenada, typo, endpoint borrado sin actualizar el frontend) â€” a
  // diferencia de 404s fuera de /api/ que suelen ser bots escaneando
  // rutas al azar. tgAlert ya deduplica por path dentro de una ventana
  // de tiempo, asÃ­ que un bot insistente no hace spam en Telegram.
  if (req.path.startsWith('/api/')) {
    const ip = clientIp(req);
    tgAlert('404:' + req.path, n =>
      `ðŸ•³ï¸ <b>404 en ruta interna</b>\n<code>${req.method} ${escHtml(req.path)}</code>\nIP: <code>${ip}</code>\nOcurrencias: ${n}\n\n` +
      `Si esta ruta la usa el frontend/app, revisa el orden de definiciÃ³n en server.js â€” una ruta declarada despuÃ©s de este catch-all queda inalcanzable.`,
      { windowMs: 30000 });
  }
  res.status(404).json({ ok: false, error: 'Ruta no encontrada', code: 'NOT_FOUND' });
});

// eslint-disable-next-line no-unused-vars
app.use((err, req, res, next) => {
  console.error('ðŸ”´ Error middleware:', err.stack || err.message);
  tgAlert('express_error', () => 'ðŸ”´ Error Express en ' + req.method + ' ' + req.originalUrl + ':\n' + String(err.message).slice(0, 300), { windowMs: 30000 });
  if (res.headersSent) return;
  res.status(err.status || 500).json({ ok: false, error: 'Error interno del servidor', code: 'INTERNAL_ERROR' });
});

// â”€â”€ ARRANCAR â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
(async () => {
  await initRedis();
  dbConnected = await connectDB();
  if (supabase) console.log('âœ… Supabase Storage listo â€” bucket:', STORAGE_BUCKET);
  await ensurePushTable();
  await ensureFCMTable();

  server.listen(PORT, () => {
    console.log(`ðŸš€ CodeHub Backend v3.0 en puerto ${PORT}`);
    console.log(`   MongoDB:    ${dbConnected ? 'âœ…' : 'âš ï¸  sin conexiÃ³n'}`);
    console.log(`   Redis:      ${redis       ? 'âœ…' : 'âš ï¸  usando memoria'}`);
    console.log(`   WebSockets: âœ… /ws`);
    console.log(`   FCM:        ${fcmEnabled ? 'âœ… push nativo Android' : 'âš ï¸  solo web-push'}`);
    console.log(`   Groq:       ${process.env.GROQ_API_KEY        ? 'âœ…' : 'âš ï¸  sin configurar'}`);
    console.log(`   Cerebras:   ${process.env.CEREBRAS_API_KEY    ? 'âœ…' : 'âš ï¸  sin configurar'}`);
    console.log(`   HuggingFace:${process.env.HUGGINGFACE_API_KEY ? 'âœ…' : 'âš ï¸  sin configurar'}`);
    console.log('   OpenRouter: ' + (process.env.OPENROUTER_API_KEY ? 'âœ… (' + OR_FREE_MODELS.length + ' modelos gratis)' : 'âš ï¸  sin configurar'));
    console.log(`   Gemini:     ${process.env.GEMINI_API_KEY      ? 'âœ…' : 'âš ï¸  sin configurar'}`);
    console.log(`   Mistral:    ${process.env.MISTRAL_API_KEY     ? 'âœ…' : 'âš ï¸  sin configurar'}`);
    console.log(`   Cohere:     ${process.env.COHERE_API_KEY      ? 'âœ…' : 'âš ï¸  sin configurar'}`);
    console.log(`   Storage:    ${supabase ? 'âœ… Supabase' : 'âŒ falta SUPABASE_URL/KEY'}`);
    console.log(`   Together:   ${process.env.TOGETHER_API_KEY ? 'âœ…' : 'âš ï¸  sin configurar'}`);
    console.log(`   Push Clima: âœ… VAPID + scheduler cada 30 min (solo avisa si cambia el clima)`);
    console.log(`   Monitor Releases: âœ… auto cada ${Math.round(AUTO_UPDATE_MS / 3600000)}h (apps open source)`);
  });
})();

// â”€â”€ RENDER KEEPALIVE â€” se agrega despuÃ©s del server.listen â”€â”€â”€â”€
// Render free tier apaga el servicio tras ~15 min de inactividad.
// Self-ping cada 10 min mantiene el proceso vivo sin servicio externo.
// Requiere: RENDER_EXTERNAL_URL en las variables de entorno de Render.

function startRenderKeepalive() {
  const SELF_URL = process.env.RENDER_EXTERNAL_URL || null;
  if (!SELF_URL) {
    console.log('   Keepalive:  âš ï¸  agrega RENDER_EXTERNAL_URL en Render > Environment');
    return;
  }
  const target = SELF_URL.replace(/\/$/, '') + '/api/health';
  const lib = target.startsWith('https') ? require('https') : require('http');
  setInterval(() => {
    lib.get(target, (res) => {
      console.log('ðŸ”” Render keepalive ping â†’', res.statusCode);
    }).on('error', (e) => console.warn('âš ï¸  Keepalive error:', e.message));
  }, 10 * 60 * 1000);
  console.log('   Keepalive:  âœ… self-ping activo â†’ ' + target + ' (cada 10 min)');
}
startRenderKeepalive();


