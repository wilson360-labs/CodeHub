/**
 * CodeHub Backend v2.0
 * ─────────────────────────────────────────────────────────────
 * Variables Railway necesarias:
 *   GROQ_API_KEY        → groq.com
 *   GEMINI_API_KEY      → aistudio.google.com (opcional, fallback)
 *   MONGODB_URI         → MongoDB Atlas
 *   FRONTEND_URL        → https://wilson360-labs.vercel.app
 *   ADMIN_KEY           → tu contraseña del panel admin
 *   B2_KEY_ID           → Backblaze App Key ID
 *   B2_APP_KEY          → Backblaze Application Key
 *   B2_BUCKET_ID        → Backblaze Bucket ID (privado)
 *   B2_BUCKET_NAME      → codehub-apks
 *   RATE_LIMIT_MAX      → 50 (default)
 */

require('dotenv').config();
const express   = require('express');
const cors      = require('cors');
const mongoose  = require('mongoose');
const rateLimit = require('express-rate-limit');
const helmet    = require('helmet');
const multer    = require('multer');
const crypto    = require('crypto');

const app  = express();
const PORT = process.env.PORT || 3001;

app.set('trust proxy', 1);
app.use(helmet({ contentSecurityPolicy: false }));

// ── CORS ──────────────────────────────────────────────────────
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
  methods: ['GET','POST','PATCH','DELETE'],
  allowedHeaders: ['Content-Type', 'x-admin-key'],
}));

app.use(express.json({ limit: '10kb' }));

// Multer en memoria para APKs (máx 200MB)
const upload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: 200 * 1024 * 1024 },
  fileFilter: (req, file, cb) => {
    if (file.mimetype === 'application/vnd.android.package-archive' ||
        file.originalname.endsWith('.apk')) {
      cb(null, true);
    } else {
      cb(new Error('Solo se permiten archivos .apk'));
    }
  }
});

// ── RATE LIMITING ─────────────────────────────────────────────
const chatLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: parseInt(process.env.RATE_LIMIT_MAX) || 50,
  standardHeaders: true, legacyHeaders: false,
  message: { error: 'Demasiadas solicitudes. Espera unos minutos.', code: 'RATE_LIMIT' },
});
const adminLimiter = rateLimit({
  windowMs: 15 * 60 * 1000, max: 100,
  standardHeaders: true, legacyHeaders: false,
});

app.use('/api/chat', chatLimiter);
app.use('/api/admin', adminLimiter);

// ── MONGODB ───────────────────────────────────────────────────
// Chat messages
const msgSchema = new mongoose.Schema({
  sessionId: { type: String, required: true, index: true },
  role:      { type: String, enum: ['user','assistant'], required: true },
  content:   { type: String, required: true },
  tokens:    { type: Number, default: 0 },
  model:     { type: String, default: 'groq' },
  createdAt: { type: Date, default: Date.now, expires: 60*60*24*7 },
});
const ChatMessage = mongoose.model('ChatMessage', msgSchema);

// Apps de la tienda
const appSchema = new mongoose.Schema({
  appId:        { type: String, required: true, unique: true }, // app-1, app-2...
  nombre:       { type: String, required: true },
  descripcion:  { type: String, default: '' },
  version:      { type: String, default: '' },
  tag:          { type: String, default: '🆕' },
  changelog:    { type: String, default: '' },
  imagen:       { type: String, default: '' },
  categoria:    { type: String, default: '' },
  verified:     { type: Boolean, default: true },
  enlace:       { type: String, default: '#' },
  plugin_enlace:{ type: String, default: null },
  // Si el APK fue subido a B2
  b2_file_id:   { type: String, default: null },
  b2_url:       { type: String, default: null },
  b2_plugin_file_id: { type: String, default: null },
  b2_plugin_url:     { type: String, default: null },
  updatedAt:    { type: Date, default: Date.now },
  createdAt:    { type: Date, default: Date.now },
});
const App = mongoose.model('App', appSchema);

// Ratings
const ratingSchema = new mongoose.Schema({
  appId:   { type: String, required: true, index: true },
  appName: { type: String },
  ratings: [{ ip: String, stars: Number, createdAt: { type: Date, default: Date.now } }],
  total:   { type: Number, default: 0 },
  count:   { type: Number, default: 0 },
});
const AppRating = mongoose.model('AppRating', ratingSchema);

// Solicitudes
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

// ── MIDDLEWARE AUTH ADMIN ─────────────────────────────────────
function requireAdmin(req, res, next) {
  const key = req.headers['x-admin-key'] || req.body?.adminKey;
  const validKey = process.env.ADMIN_KEY || 'wilson2026ultra';
  if (key !== validKey) return res.status(403).json({ error: 'No autorizado' });
  next();
}

// ── BACKBLAZE B2 PRIVADO + URLs FIRMADAS ─────────────────────
// Variables Railway necesarias:
//   B2_KEY_ID        → keyID de la App Key
//   B2_APP_KEY       → applicationKey (solo se muestra una vez)
//   B2_BUCKET_ID     → ID del bucket codehub-apks
//   B2_BUCKET_NAME   → codehub-apks

let _b2Auth = null;       // { token, apiUrl, downloadUrl, expiry }

async function getB2Auth() {
  // Reutilizar token si no expiró (válido 24h, renovar cada 20h)
  if (_b2Auth && Date.now() < _b2Auth.expiry) return _b2Auth;

  const keyId  = process.env.B2_KEY_ID;
  const appKey = process.env.B2_APP_KEY;
  if (!keyId || !appKey) throw new Error('B2_KEY_ID y B2_APP_KEY no configurados en Railway');

  const creds = Buffer.from(`${keyId}:${appKey}`).toString('base64');
  const res   = await fetch('https://api.backblazeb2.com/b2api/v3/b2_authorize_account', {
    headers: { Authorization: 'Basic ' + creds },
  });
  if (!res.ok) throw new Error('B2 auth falló: ' + res.status);
  const d = await res.json();

  _b2Auth = {
    token:       d.authorizationToken,
    apiUrl:      d.apiInfo?.storageApi?.apiUrl || d.apiUrl,
    downloadUrl: d.apiInfo?.storageApi?.downloadUrl || d.downloadUrl,
    expiry:      Date.now() + 20 * 60 * 60 * 1000,
  };
  console.log('✅ B2 autenticado');
  return _b2Auth;
}

async function uploadToR2(buffer, fileName, mimeType = 'application/vnd.android.package-archive') {
  const auth     = await getB2Auth();
  const bucketId = process.env.B2_BUCKET_ID;
  if (!bucketId) throw new Error('B2_BUCKET_ID no configurado en Railway');

  // 1. Obtener upload URL
  const urlRes = await fetch(`${auth.apiUrl}/b2api/v3/b2_get_upload_url`, {
    method:  'POST',
    headers: { Authorization: auth.token, 'Content-Type': 'application/json' },
    body:    JSON.stringify({ bucketId }),
  });
  if (!urlRes.ok) throw new Error('Error obteniendo upload URL B2: ' + urlRes.status);
  const { uploadUrl, authorizationToken: uploadAuth } = await urlRes.json();

  // 2. SHA1 del archivo
  const sha1 = require('crypto').createHash('sha1').update(buffer).digest('hex');

  // 3. Subir
  const upRes = await fetch(uploadUrl, {
    method:  'POST',
    headers: {
      Authorization:       uploadAuth,
      'X-Bz-File-Name':    encodeURIComponent(fileName),
      'Content-Type':      mimeType,
      'Content-Length':    String(buffer.length),
      'X-Bz-Content-Sha1': sha1,
    },
    body: buffer,
  });
  if (!upRes.ok) {
    const e = await upRes.json().catch(() => ({}));
    throw new Error('Error subiendo a B2: ' + (e.message || upRes.status));
  }
  const data = await upRes.json();
  console.log(`✅ B2 upload: ${fileName} (${(buffer.length/1024/1024).toFixed(1)} MB)`);

  // La downloadUrl real se genera por demanda desde /api/download/:fileName
  // No exponemos la URL directa del bucket privado
  return {
    fileId:      data.fileId,
    fileName:    data.fileName,
    downloadUrl: null, // se genera bajo demanda con URL firmada
  };
}

async function deleteFromR2(fileName) {
  if (!fileName) return false;
  try {
    const auth = await getB2Auth();
    // Buscar el fileId por nombre
    const listRes = await fetch(`${auth.apiUrl}/b2api/v3/b2_list_file_names`, {
      method:  'POST',
      headers: { Authorization: auth.token, 'Content-Type': 'application/json' },
      body:    JSON.stringify({ bucketId: process.env.B2_BUCKET_ID, prefix: fileName, maxFileCount: 1 }),
    });
    const listData = await listRes.json();
    const file = listData.files?.[0];
    if (!file) return false;

    await fetch(`${auth.apiUrl}/b2api/v3/b2_delete_file_version`, {
      method:  'POST',
      headers: { Authorization: auth.token, 'Content-Type': 'application/json' },
      body:    JSON.stringify({ fileId: file.fileId, fileName: file.fileName }),
    });
    console.log(`🗑️ B2 delete: ${fileName}`);
    return true;
  } catch (e) {
    console.warn('B2 delete error:', e.message);
    return false;
  }
}

// Generar URL de descarga firmada (válida por 24h)
async function getB2DownloadUrl(fileName, validSeconds = 86400) {
  const auth = await getB2Auth();
  const bucketName = process.env.B2_BUCKET_NAME || 'codehub-apks';

  // Generar auth token de descarga con duración limitada
  const tokenRes = await fetch(`${auth.apiUrl}/b2api/v3/b2_get_download_authorization`, {
    method:  'POST',
    headers: { Authorization: auth.token, 'Content-Type': 'application/json' },
    body:    JSON.stringify({
      bucketId:               process.env.B2_BUCKET_ID,
      fileNamePrefix:         fileName,
      validDurationInSeconds: validSeconds,
    }),
  });
  if (!tokenRes.ok) throw new Error('Error generando download auth: ' + tokenRes.status);
  const { authorizationToken: dlToken } = await tokenRes.json();

  return `${auth.downloadUrl}/file/${bucketName}/${encodeURIComponent(fileName)}?Authorization=${dlToken}`;
}

// ── SYSTEM PROMPT ─────────────────────────────────────────────
const SYSTEM_PROMPT = `Eres el asistente IA de CodeHub, portfolio de Wilson.E, desarrollador guatemalteco.
PERSONALIDAD: Conciso, técnico y amigable. Siempre en español. Emojis con moderación. Máx 4 oraciones por respuesta.
SOBRE CODEHUB: Portfolio de Wilson.E (24 años, Guatemala 🇬🇹). 23 herramientas web, apps Android premium, juegos Snake/Tetris, descargador de videos (YouTube, TikTok, Instagram, Facebook, Twitter/X).
Contacto: wilsonenrique686@gmail.com / WhatsApp +502 3513 1808.
FORMATO: Código en bloques, listas con guión, negritas para términos clave.`;

// ── GROQ ──────────────────────────────────────────────────────
async function callGroq(messages) {
  const res = await fetch('https://api.groq.com/openai/v1/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${process.env.GROQ_API_KEY}` },
    body: JSON.stringify({ model: 'llama-3.3-70b-versatile', max_tokens: 600, temperature: 0.7, messages }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.error?.message || `Groq HTTP ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.choices[0]?.message?.content || '', input: d.usage?.prompt_tokens || 0, output: d.usage?.completion_tokens || 0, model: 'groq/llama-3.3-70b' };
}

// ── GEMINI ────────────────────────────────────────────────────
async function callGemini(messages) {
  const contents = messages.filter(m => m.role !== 'system').map(m => ({ role: m.role === 'assistant' ? 'model' : 'user', parts: [{ text: m.content }] }));
  const res = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=${process.env.GEMINI_API_KEY}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ systemInstruction: { parts: [{ text: SYSTEM_PROMPT }] }, contents, generationConfig: { maxOutputTokens: 600, temperature: 0.7 } }),
  });
  if (!res.ok) { const e = await res.json().catch(() => ({})); const err = new Error(e.error?.message || `Gemini HTTP ${res.status}`); err.status = res.status; throw err; }
  const d = await res.json();
  return { reply: d.candidates?.[0]?.content?.parts?.[0]?.text || '', input: d.usageMetadata?.promptTokenCount || 0, output: d.usageMetadata?.candidatesTokenCount || 0, model: 'gemini-1.5-flash' };
}

async function callAI(messages) {
  if (process.env.GROQ_API_KEY) {
    try { return await callGroq(messages); }
    catch (err) { if (err.status === 401) throw err; console.warn('Groq falló, usando Gemini...'); }
  }
  if (process.env.GEMINI_API_KEY) return await callGemini(messages);
  throw new Error('Sin API keys de IA configuradas');
}

// ── TURNSTILE ─────────────────────────────────────────────────
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
// RUTAS PÚBLICAS
// ════════════════════════════════════════════════════════════════

// ── GET /api/health ───────────────────────────────────────────
app.get('/api/health', (req, res) => {
  res.json({
    status: 'ok', mongo: dbConnected ? 'connected' : 'disconnected',
    groq: process.env.GROQ_API_KEY ? 'ok' : 'missing',
    gemini: process.env.GEMINI_API_KEY ? 'ok' : 'missing',
    b2: (process.env.B2_KEY_ID && process.env.B2_APP_KEY) ? 'configured' : 'missing',
    uptime: Math.floor(process.uptime()) + 's',
  });
});

// ── GET /api/apps — tienda pública ────────────────────────────
app.get('/api/apps', async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible', apps: [] });
  try {
    const apps = await App.find({}).sort({ createdAt: 1 }).lean();
    // Usar URL de B2 si existe, si no usar el enlace manual
    const backendBase = process.env.BACKEND_URL || `https://codehub-production-729d.up.railway.app`;
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
      // Si tiene APK en B2, usar ruta de descarga del backend (URL firmada)
      // Si no, usar el enlace manual (Dropbox/Drive/Mediafire)
      enlace:       a.b2_file_name
                      ? `${backendBase}/api/download/${encodeURIComponent(a.b2_file_name)}`
                      : (a.enlace || '#'),
      plugin_enlace: a.b2_plugin_file_name
                      ? `${backendBase}/api/download/${encodeURIComponent(a.b2_plugin_file_name)}`
                      : (a.plugin_enlace || null),
      tutorial_url: a.tutorial_url || null,
      updatedAt:    a.updatedAt,
    }));
    res.json({ apps: mapped, total: mapped.length });
  } catch (e) { res.status(500).json({ error: 'Error obteniendo apps' }); }
});

// ── POST /api/chat ────────────────────────────────────────────
app.post('/api/chat', async (req, res) => {
  const { message, sessionId = 'anon', history = [] } = req.body;
  if (!message || typeof message !== 'string') return res.status(400).json({ error: '"message" requerido.' });
  if (message.trim().length > 1000) return res.status(400).json({ error: 'Mensaje muy largo (máx 1000).' });
  if (!process.env.GROQ_API_KEY && !process.env.GEMINI_API_KEY) return res.status(503).json({ error: 'Sin API keys.' });

  const safeHistory = (Array.isArray(history) ? history.slice(-10) : []).map(m => ({ role: m.role === 'assistant' ? 'assistant' : 'user', content: String(m.content).slice(0, 800) }));
  safeHistory.push({ role: 'user', content: message.trim() });
  const messages = [{ role: 'system', content: SYSTEM_PROMPT }, ...safeHistory];

  try {
    const { reply, input, output, model } = await callAI(messages);
    if (dbConnected) ChatMessage.insertMany([{ sessionId, role: 'user', content: message.trim(), tokens: input, model }, { sessionId, role: 'assistant', content: reply, tokens: output, model }]).catch(() => {});
    res.json({ reply, usage: { input, output, total: input + output }, model });
  } catch (err) {
    if (err.status === 401) return res.status(500).json({ error: 'API key inválida.' });
    if (err.status === 429) return res.status(429).json({ error: 'Límite alcanzado. Intenta más tarde.' });
    res.status(500).json({ error: 'Error interno.' });
  }
});

// ── GET /api/ratings ──────────────────────────────────────────
app.get('/api/ratings', async (req, res) => {
  if (!dbConnected) return res.json({ ratings: {} });
  try {
    const all = await AppRating.find({}, 'appId total count');
    const ratings = {};
    all.forEach(r => { ratings[r.appId] = { avg: r.count > 0 ? Math.round((r.total/r.count)*10)/10 : 0, count: r.count }; });
    res.json({ ratings });
  } catch { res.json({ ratings: {} }); }
});

// ── POST /api/ratings ─────────────────────────────────────────
app.post('/api/ratings', async (req, res) => {
  const { appId, appName, stars } = req.body;
  const ip = req.ip || 'anon';
  if (!appId || !stars || stars < 1 || stars > 5) return res.status(400).json({ error: 'Datos inválidos' });
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    let r = await AppRating.findOne({ appId });
    if (!r) r = new AppRating({ appId, appName: appName || appId, ratings: [], total: 0, count: 0 });
    const already = r.ratings.find(x => x.ip === ip);
    if (already) return res.status(409).json({ error: 'Ya votaste', avg: r.count > 0 ? Math.round((r.total/r.count)*10)/10 : 0, count: r.count });
    r.ratings.push({ ip, stars }); r.total += stars; r.count += 1;
    await r.save();
    res.json({ ok: true, avg: Math.round((r.total/r.count)*10)/10, count: r.count });
  } catch { res.status(500).json({ error: 'Error guardando rating' }); }
});

// ── GET /api/requests ─────────────────────────────────────────
app.get('/api/requests', async (req, res) => {
  if (!dbConnected) return res.json({ requests: [] });
  try {
    const reqs = await AppRequest.find({ status: 'pending' }).sort({ votes: -1 }).limit(20);
    res.json({ requests: reqs });
  } catch { res.json({ requests: [] }); }
});

// ── POST /api/requests ────────────────────────────────────────
app.post('/api/requests', async (req, res) => {
  const { appName, reason, turnstileToken } = req.body;
  const ip = req.ip || 'anon';
  if (!appName || appName.trim().length < 2) return res.status(400).json({ error: 'Nombre requerido' });
  if (!await validateTurnstile(turnstileToken)) return res.status(403).json({ error: 'Verificación fallida' });
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    const existing = await AppRequest.findOne({ appName: new RegExp(appName.trim(), 'i'), status: 'pending' });
    if (existing) {
      if (existing.voters.includes(ip)) return res.status(409).json({ error: 'Ya votaste', votes: existing.votes });
      existing.votes += 1; existing.voters.push(ip);
      await existing.save();
      return res.json({ ok: true, message: 'Voto agregado', votes: existing.votes });
    }
    const newReq = new AppRequest({ appName: appName.trim(), reason: reason?.trim() || '', ip, voters: [ip] });
    await newReq.save();
    res.json({ ok: true, message: 'Solicitud enviada', id: newReq._id });
  } catch { res.status(500).json({ error: 'Error guardando solicitud' }); }
});


// ── GET /api/download/:fileName — descarga firmada desde B2 ──
// Ruta pública — genera URL firmada válida 24h y redirige
app.get('/api/download/:fileName', async (req, res) => {
  const { fileName } = req.params;
  if (!fileName || fileName.includes('..')) return res.status(400).json({ error: 'Nombre inválido' });

  try {
    const signedUrl = await getB2DownloadUrl(decodeURIComponent(fileName), 86400);
    // Redirigir directo a Backblaze con la URL firmada
    res.redirect(302, signedUrl);
  } catch (e) {
    console.error('Error generando URL firmada:', e.message);
    res.status(500).json({ error: 'No se pudo generar el link de descarga. Intenta más tarde.' });
  }
});

// ════════════════════════════════════════════════════════════════
// RUTAS ADMIN (requieren ADMIN_KEY)
// ════════════════════════════════════════════════════════════════

// ── GET /api/admin/apps — listar todas para el panel ─────────
app.get('/api/admin/apps', requireAdmin, async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    const apps = await App.find({}).sort({ createdAt: 1 }).lean();
    res.json({ apps, total: apps.length });
  } catch { res.status(500).json({ error: 'Error obteniendo apps' }); }
});

// ── POST /api/admin/apps — crear app nueva ───────────────────
app.post('/api/admin/apps', requireAdmin, async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    const { appId, nombre, descripcion, version, tag, changelog, imagen, categoria, verified, enlace, plugin_enlace } = req.body;
    if (!appId || !nombre) return res.status(400).json({ error: 'appId y nombre son requeridos' });
    const exists = await App.findOne({ appId });
    if (exists) return res.status(409).json({ error: 'Ya existe una app con ese appId' });
    const app = new App({ appId, nombre, descripcion, version, tag: tag || '🆕', changelog, imagen, categoria, verified: verified !== false, enlace: enlace || '#', plugin_enlace: plugin_enlace || null });
    await app.save();
    res.json({ ok: true, app });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// ── PATCH /api/admin/apps/:appId — editar app ────────────────
app.patch('/api/admin/apps/:appId', requireAdmin, async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    const { appId } = req.params;
    const fields = ['nombre','descripcion','version','tag','changelog','imagen','categoria','verified','enlace','plugin_enlace'];
    const update = {};
    fields.forEach(f => { if (req.body[f] !== undefined) update[f] = req.body[f]; });
    update.updatedAt = new Date();
    const app = await App.findOneAndUpdate({ appId }, update, { new: true });
    if (!app) return res.status(404).json({ error: 'App no encontrada' });
    res.json({ ok: true, app });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// ── DELETE /api/admin/apps/:appId — eliminar app ─────────────
app.delete('/api/admin/apps/:appId', requireAdmin, async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    const app = await App.findOne({ appId: req.params.appId });
    if (!app) return res.status(404).json({ error: 'App no encontrada' });
    // Eliminar APKs de B2 si existen
    if (app.b2_file_name)      await deleteFromR2(app.b2_file_name);
    if (app.b2_plugin_file_name) await deleteFromR2(app.b2_plugin_file_name);
    await App.deleteOne({ appId: req.params.appId });
    res.json({ ok: true });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// ── POST /api/admin/apps/:appId/upload — subir APK a B2 ──────
app.post('/api/admin/apps/:appId/upload', requireAdmin, upload.single('apk'), async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  if (!req.file)    return res.status(400).json({ error: 'No se recibió ningún archivo' });

  const { appId }   = req.params;
  const { slot }    = req.body; // 'main' o 'plugin'
  const isPlugin    = slot === 'plugin';

  try {
    const app = await App.findOne({ appId });
    if (!app) return res.status(404).json({ error: 'App no encontrada' });

    // Nombre del archivo en B2: appId_slot_timestamp.apk
    const timestamp = Date.now();
    const fileName  = `${appId}_${isPlugin ? 'plugin' : 'main'}_${timestamp}.apk`;

    // Eliminar versión anterior de B2 si existe
    if (!isPlugin && app.b2_file_name)      await deleteFromR2(app.b2_file_name);
    if (isPlugin  && app.b2_plugin_file_name) await deleteFromR2(app.b2_plugin_file_name);

    // Subir nuevo APK
    const { fileId, downloadUrl } = await uploadToR2(req.file.buffer, fileName);

    // Actualizar MongoDB
    const updateFields = isPlugin
      ? { b2_plugin_file_id: fileId, b2_plugin_file_name: fileName, b2_plugin_url: null, updatedAt: new Date() }
      : { b2_file_id: fileId, b2_file_name: fileName, b2_url: null, updatedAt: new Date() };

    await App.updateOne({ appId }, updateFields);

    console.log(`✅ APK subido a B2: ${fileName} (${(req.file.size / 1024 / 1024).toFixed(1)} MB)`);
    res.json({ ok: true, downloadUrl, fileId, fileName, sizeMB: (req.file.size / 1024 / 1024).toFixed(1) });

  } catch (e) {
    console.error('Error subiendo APK:', e.message);
    res.status(500).json({ error: e.message });
  }
});

// ── PATCH /api/requests/:id — gestionar solicitudes ──────────
app.patch('/api/requests/:id', requireAdmin, async (req, res) => {
  const { status } = req.body;
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    await AppRequest.findByIdAndUpdate(req.params.id, { status });
    res.json({ ok: true });
  } catch { res.status(500).json({ error: 'Error actualizando' }); }
});

// ── POST /api/admin/seed — poblar BD con apps_data.json ──────
// Solo usar una vez para migrar los datos iniciales
app.post('/api/admin/seed', requireAdmin, async (req, res) => {
  if (!dbConnected) return res.status(503).json({ error: 'DB no disponible' });
  try {
    const { apps } = req.body;
    if (!apps || !Array.isArray(apps)) return res.status(400).json({ error: 'Se esperaba { apps: [...] }' });
    let created = 0, updated = 0;
    for (const a of apps) {
      const exists = await App.findOne({ appId: a.appId || a.id });
      if (exists) {
        await App.updateOne({ appId: a.appId || a.id }, { $set: { nombre: a.nombre || a.name, enlace: a.enlace || '#', version: a.version_conocida || a.ver || '', tag: a.tag || '🆕', updatedAt: new Date() } });
        updated++;
      } else {
        await App.create({ appId: a.appId || a.id, nombre: a.nombre || a.name, descripcion: a.descripcion || '', version: a.version_conocida || a.ver || '', tag: a.tag || '🆕', changelog: a.changelog || '', imagen: a.imagen || '', categoria: a.categoria || a.cat || '', verified: a.verified !== false, enlace: a.enlace || '#', plugin_enlace: a.plugin_enlace || null });
        created++;
      }
    }
    res.json({ ok: true, created, updated });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

// ── ARRANCAR ──────────────────────────────────────────────────
(async () => {
  dbConnected = await connectDB();
  getB2Auth().catch(e => console.warn("B2 pre-auth:", e.message)); // pre-autenticar
  app.listen(PORT, () => {
    console.log(`🚀 CodeHub Backend v2.0 en puerto ${PORT}`);
    console.log(`   MongoDB:    ${dbConnected ? '✅' : '⚠️  sin conexión'}`);
    console.log(`   Groq:       ${process.env.GROQ_API_KEY   ? '✅' : '❌ falta'}`);
    console.log(`   Gemini:     ${process.env.GEMINI_API_KEY ? '✅' : '⚠️  opcional'}`);
    console.log(`   Backblaze:  ${process.env.B2_KEY_ID ? '✅' : '⚠️  configura B2_KEY_ID, B2_APP_KEY, B2_BUCKET_ID, B2_BUCKET_NAME'}`);
  });
})();
