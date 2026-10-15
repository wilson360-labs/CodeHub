/* ═══════════════════════════════════════════════════════════════════════
   CodeHub — Bot de administración por Telegram (Milestone 1)
   Wilson.E 2026 · ver docs/TELEGRAM-ADMIN.md

   Receptor: Telegram hace POST a /api/telegram/webhook con cada novedad del
   chat. Este módulo SOLO atiende al chat del admin (whitelist por
   TELEGRAM_CHAT_ID) y traduce comandos a llamadas a los endpoints
   /api/admin/* que ya existen en server.js: no duplica lógica, solo la
   expone por el móvil.

   Seguridad (todo fail-closed):
     · Exige X-Telegram-Bot-Api-Secret-Token == TELEGRAM_WEBHOOK_SECRET.
       Sin secreto configurado el webhook responde 503 (no se acepta nada).
     · Ignora cualquier chat que no sea el del admin, y avisa del intento.
     · /login <ADMIN_KEY> emite una sesión firmada (misma HMAC y TTL que el
       panel web) que se manda como x-admin-session. Dura 30 minutos.
     · Toda acción destructiva (/desplegar) pide confirmación en 2 pasos.
     · Rate-limit por chat y kill-switch TG_BOT_ENABLED=false.
     · Cada comando queda en el log de administración (actor "telegram").
   ═══════════════════════════════════════════════════════════════════════ */
'use strict';

const http  = require('http');
const https = require('https');
const crypto = require('crypto');

// Config inyectada por server.js en .init(). Null hasta entonces.
let C = null;

// ── Estado en memoria (sobrevive al tráfico, no a los reinicios) ──
const sessions = new Map();   // chatId -> { token, exp }
const rate     = new Map();   // chatId -> { n, at }   (rate-limit por chat)
const pending  = new Map();   // chatId -> { file, at } (pendiente de confirmar)

const CMD_MAX   = 15;             // comandos por ventana
const CMD_WIN   = 60 * 1000;      // 1 minuto
const CONFIRM_TTL = 3 * 60 * 1000; // una confirmación caduca a los 3 minutos

// Workflows que se pueden lanzar desde el chat. Fuera de esta lista no se
// ofrecen botones: mejor pocos y seguros que todos.
const SAFE_DISPATCH = [
  'deploy-triggers.yml', 'rebuild-assets.yml', 'quality.yml', 'build-apk.yml',
  'preview.yml', 'enrich-app-logos.yml', 'extensions-check.yml',
  'dedupe-catalog.yml', 'seed-foss-catalog.yml',
];

const HELP = [
  '🤖 <b>CodeHub Admin</b> — comandos',
  '',
  '/login &lt;clave&gt; — abrir sesión (30 min)',
  '/logout — cerrar sesión',
  '/estado — salud del backend, Mongo, uptime y visitas',
  '/visitas — visitas recientes por día y país',
  '/apps [buscador] — catálogo (uso, versión, categoría)',
  '/app &lt;id&gt; — ficha completa de una app',
  '/runs — último resultado de cada workflow de CI',
  '/jobs — tareas programadas (cron de Render)',
  '/actividad — registro de acciones de administración',
  '/desplegar — lanzar un workflow (con confirmación)',
  '',
  'Todo lo destructivo pide confirmación con botones.',
  'Kill-switch: <code>TG_BOT_ENABLED=false</code> en Render.',
].join('\n');

// ── Utilidades ──────────────────────────────────────────────────
function esc(v) {
  return String(v == null ? '' : v)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function num(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n.toLocaleString('es-GT') : String(v == null ? '—' : v);
}

function relTime(iso) {
  const t = Date.parse(iso || '');
  if (!t) return '—';
  const s = Math.max(0, Math.floor((Date.now() - t) / 1000));
  if (s < 60) return 'hace ' + s + 's';
  if (s < 3600) return 'hace ' + Math.floor(s / 60) + 'min';
  if (s < 86400) return 'hace ' + Math.floor(s / 3600) + 'h';
  return 'hace ' + Math.floor(s / 86400) + 'd';
}

function keyMatches(a, b) {
  if (!a || !b) return false;
  const ba = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  if (ba.length !== bb.length) return false;   // evita timingSafeEqual con largos distintos
  return crypto.timingSafeEqual(ba, bb);
}

// ── Telegram API ────────────────────────────────────────────────
function tgApi(method, payload) {
  return new Promise((resolve) => {
    const token = C.token;
    if (!token) return resolve(null);
    const body = JSON.stringify(payload);
    const req = https.request({
      hostname: 'api.telegram.org',
      path: '/bot' + token + '/' + method,
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) },
      timeout: 10000,
    }, (res) => {
      let raw = '';
      res.on('data', (c) => { raw += c; });
      res.on('end', () => {
        try { resolve(JSON.parse(raw)); } catch { resolve(null); }
      });
    });
    req.on('timeout', () => { req.destroy(); resolve(null); });
    req.on('error', () => resolve(null));
    req.write(body);
    req.end();
  });
}

function send(chatId, text, keyboard) {
  const payload = {
    chat_id: chatId,
    text: String(text).slice(0, 3900),
    parse_mode: 'HTML',
    disable_web_page_preview: true,
  };
  if (keyboard) payload.reply_markup = { inline_keyboard: keyboard };
  return tgApi('sendMessage', payload);
}

function answerCb(id, text) {
  return tgApi('answerCallbackQuery', {
    callback_query_id: id,
    text: String(text || '').slice(0, 190),
  });
}

// ── Llamadas internas a la API admin ───────────────────────────
// Se hacen contra el propio backend (loopback por defecto) reusando sus
// validaciones, su_rate-limits y su auditoría.
function api(method, route, body, token) {
  return new Promise((resolve) => {
    let base;
    try { base = new URL(C.backendUrl); } catch { base = new URL('http://127.0.0.1:' + C.port); }
    const lib = base.protocol === 'http:' ? http : https;
    const payload = body === undefined ? null : JSON.stringify(body);
    const headers = { 'Content-Type': 'application/json', 'x-admin-user': 'telegram' };
    if (payload) headers['Content-Length'] = Buffer.byteLength(payload);
    if (token) headers['x-admin-session'] = token;

    const req = lib.request({
      hostname: base.hostname,
      port: base.port || (base.protocol === 'http:' ? 80 : 443),
      path: base.pathname.replace(/\/$/, '') + route,
      method,
      headers,
      timeout: 15000,
    }, (res) => {
      let raw = '';
      res.on('data', (c) => { raw += c; });
      res.on('end', () => {
        let json = null;
        try { json = JSON.parse(raw); } catch { /* cuerpo no-JSON */ }
        resolve({ status: res.statusCode, json, text: raw.slice(0, 300) });
      });
    });
    req.on('timeout', () => { req.destroy(); resolve({ status: 0, json: null, text: 'timeout' }); });
    req.on('error', (e) => resolve({ status: 0, json: null, text: String((e && e.message) || e) }));
    if (payload) req.write(payload);
    req.end();
  });
}

// ── Sesión ──────────────────────────────────────────────────────
function sessionOf(chatId) {
  const id = String(chatId);
  const s = sessions.get(id);
  if (!s) return null;
  if (s.exp <= Date.now()) { sessions.delete(id); return null; }
  return s.token;
}

function openSession(chatId) {
  const exp = Date.now() + C.sessionTtl;
  const token = C.signSession({ admin: true, via: 'telegram', exp });
  sessions.set(String(chatId), { token, exp });
  return token;
}

function throttled(chatId) {
  const id = String(chatId);
  const now = Date.now();
  const cur = rate.get(id);
  if (!cur || now - cur.at > CMD_WIN) { rate.set(id, { n: 1, at: now }); return false; }
  cur.n++;
  return cur.n > CMD_MAX;
}

// ── Comandos ───────────────────────────────────────────────────
async function cmdLogin(chatId, arg) {
  if (!C.adminKey) { await send(chatId, '⚠️ Falta ADMIN_KEY en el servidor.'); return; }
  if (!arg) { await send(chatId, 'Uso: <code>/login &lt;clave&gt;</code>'); return; }
  if (!keyMatches(arg, C.adminKey)) {
    C.logAdmin('telegram', 'bot.login', { ok: false }, 'error', 'security');
    C.tgAlert('botloginfail', () => '🔐 <b>LOGIN FALLIDO del bot</b>\nChat: <code>' + chatId + '</code>', { windowMs: 30000 });
    await send(chatId, '❌ Clave incorrecta.');
    return;
  }
  openSession(chatId);
  C.logAdmin('telegram', 'bot.login', { ok: true }, 'ok', 'security');
  C.tgAlert('botlogin', () => '✅ <b>Bot admin</b>: sesión abierta\nChat: <code>' + chatId + '</code>', { windowMs: 60000 });
  await send(chatId, '✅ Sesión abierta por ' + Math.round(C.sessionTtl / 60000) + ' minutos.\nPrueba /estado o /ayuda.');
}

async function cmdStatus(chatId, token) {
  const [h, l] = await Promise.all([
    api('GET', '/api/health', undefined, token),
    api('GET', '/api/stats/live', undefined, token),
  ]);
  if (h.status !== 200) {
    await send(chatId, '⚠️ No pude leer /api/health (HTTP ' + h.status + ').');
    return;
  }
  const x = h.json || {};
  const s = l.json || {};
  const ok = (key, good) => (good ? '🟢 ' : '🔴 ') + key + ': ' + esc(x[key] === undefined || x[key] === '' ? '—' : x[key]);
  await send(chatId, [
    '📊 <b>Estado de CodeHub</b>',
    '',
    'Backend v' + esc(x.version) + ' · ' + (x.status === 'ok' ? '🟢 ok' : '🔴 ' + esc(x.status)),
    ok('mongo', x.mongo === 'connected'),
    'caché: ' + esc(x.redis),
    'ws: ' + esc(x.ws),
    'uptime: ' + esc(x.uptime),
    '',
    'Visitas hoy: <b>' + num(s.visitors) + '</b>',
    'Visitas totales: ' + num(s.total),
    'WS en vivo: ' + num(s.wsClients),
  ].join('\n'));
}

async function cmdVisitas(chatId, token) {
  const r = await api('GET', '/api/admin/visitors?limit=200', undefined, token);
  if (r.status !== 200) {
    await send(chatId, '⚠️ ' + esc((r.json && r.json.error) || ('HTTP ' + r.status)));
    return;
  }
  const rows = (r.json && r.json.visitors) || [];
  if (!rows.length) { await send(chatId, 'Todavía no hay visitas registradas.'); return; }

  const byDay = new Map();
  const byCountry = new Map();
  for (const v of rows) {
    const d = String(v.visited_at || v.createdAt || v.ts || '').slice(0, 10) || '—';
    byDay.set(d, (byDay.get(d) || 0) + 1);
    const c = String(v.country || v.countryCode || v.pais || '—').slice(0, 24);
    byCountry.set(c, (byCountry.get(c) || 0) + 1);
  }
  const top = (m) => Array.from(m.entries())
    .sort((a, b) => b[1] - a[1]).slice(0, 5)
    .map(([k, v]) => '  · ' + esc(k) + ': ' + num(v)).join('\n');

  await send(chatId, [
    '👀 <b>Visitas</b> (últimas ' + num(rows.length) + ')',
    '',
    'Por día:', top(byDay),
    '',
    'Por país:', top(byCountry),
  ].join('\n'));
}

/** Campos reales del modelo App que indican que hay binario subido. */
function hasBinary(a) {
  return !!(a.b2_file_name || a.ia_file_name || a.tg_message_id);
}

async function cmdApps(chatId, token, q) {
  const r = await api('GET', '/api/admin/apps', undefined, token);
  if (r.status !== 200) {
    await send(chatId, '⚠️ ' + esc((r.json && r.json.error) || ('HTTP ' + r.status)));
    return;
  }
  const all = (r.json && r.json.apps) || [];
  let list = all;
  if (q) {
    const n = q.toLowerCase();
    list = all.filter((a) => (a.appId + ' ' + a.nombre).toLowerCase().includes(n));
  }
  if (!list.length) { await send(chatId, 'Sin resultados para «' + esc(q) + '».'); return; }

  const withApk = list.filter(hasBinary).length;
  const lines = [
    '📦 <b>Apps</b>: ' + num(list.length) + ' de ' + num(all.length) + ' · con binario: ' + num(withApk),
    '',
  ];
  list.slice(0, 30).forEach((a, i) => {
    lines.push((i + 1) + '. <b>' + esc(a.nombre) + '</b> <code>' + esc(a.appId) + '</code>');
    lines.push('   v' + esc(a.version || '?') + ' · ' + esc(a.categoria || 'sin categoría') +
      (a.verified ? ' · ✅' : '') + (hasBinary(a) ? '' : ' · ⚠️ sin APK'));
  });
  if (list.length > 30) lines.push('… y ' + num(list.length - 30) + ' más. Afina con /apps &lt;buscador&gt;.');
  await send(chatId, lines.join('\n'));
}

async function cmdApp(chatId, token, id) {
  if (!id) { await send(chatId, 'Uso: <code>/app &lt;id&gt;</code>'); return; }
  const r = await api('GET', '/api/admin/apps', undefined, token);
  if (r.status !== 200) {
    await send(chatId, '⚠️ ' + esc((r.json && r.json.error) || ('HTTP ' + r.status)));
    return;
  }
  const all = (r.json && r.json.apps) || [];
  const n = id.toLowerCase();
  const a = all.find((x) => x.appId === id) ||
            all.find((x) => x.appId.toLowerCase() === n) ||
            all.find((x) => String(x.nombre || '').toLowerCase() === n);
  if (!a) { await send(chatId, 'No encontré la app «' + esc(id) + '».'); return; }

  await send(chatId, [
    '📱 <b>' + esc(a.nombre) + '</b>',
    '<code>' + esc(a.appId) + '</code>',
    '',
    'Paquete: <code>' + esc(a.packageName || '—') + '</code>',
    'Versión: ' + esc(a.version || '?') + ' ' + esc(a.tag || ''),
    'Categoría: ' + esc(a.categoria || 'sin categoría'),
    'Verificada: ' + (a.verified ? '✅' : '—'),
    'Repositorio: ' + (a.source_repo ? 'sí (open source)' : '—'),
    'Actualizado: ' + relTime(a.updatedAt),
    '',
    'Descripción: ' + esc(String(a.descripcion || '—').slice(0, 200)),
    a.changelog ? 'Novedades: ' + esc(String(a.changelog).slice(0, 200)) : '',
  ].filter(Boolean).join('\n'));
}

function runIcon(run) {
  if (!run) return '⚪';
  if (run.status !== 'completed') return '⏳';
  return run.conclusion === 'success' ? '✅'
    : run.conclusion === 'failure' ? '❌'
    : run.conclusion === 'cancelled' ? '🚫' : '⚠️';
}

async function cmdRuns(chatId, token) {
  const r = await api('GET', '/api/admin/github/runs', undefined, token);
  if (r.status !== 200) {
    await send(chatId, '⚠️ ' + esc((r.json && r.json.error) || ('HTTP ' + r.status)) +
      '\nRequiere GITHUB_TOKEN en Render.');
    return;
  }
  const runs = (r.json && r.json.runs) || {};
  const files = Object.keys(runs).sort();
  if (!files.length) { await send(chatId, 'No hay workflows que listar.'); return; }

  const lines = ['🔁 <b>CI/CD</b> — último run por workflow', ''];
  for (const f of files) {
    const run = runs[f];
    lines.push(runIcon(run) + ' <code>' + esc(f) + '</code>' +
      (run ? ' · ' + esc(run.conclusion || run.status) + ' · ' + relTime(run.created_at) : ' · sin runs'));
  }
  await send(chatId, lines.join('\n'));
}

async function cmdJobs(chatId, token) {
  const r = await api('GET', '/api/admin/jobs', undefined, token);
  if (r.status !== 200) {
    await send(chatId, '⚠️ ' + esc((r.json && r.json.error) || ('HTTP ' + r.status)));
    return;
  }
  const jobs = (r.json && r.json.jobs) || [];
  if (!jobs.length) { await send(chatId, 'No hay tareas programadas definidas en render.yaml.'); return; }
  const lines = ['⏰ <b>Tareas programadas</b> (render.yaml)', ''];
  for (const j of jobs) {
    lines.push(j.icon + ' <b>' + esc(j.name) + '</b>');
    lines.push('   ' + esc(j.runtime) + ' · <code>' + esc(j.schedule) + '</code>');
    lines.push('   <code>' + esc(j.startCommand) + '</code>');
  }
  await send(chatId, lines.join('\n'));
}

async function cmdActividad(chatId, token) {
  const r = await api('GET', '/api/admin/activity', undefined, token);
  if (r.status !== 200) {
    await send(chatId, '⚠️ ' + esc((r.json && r.json.error) || ('HTTP ' + r.status)));
    return;
  }
  const ops = (r.json && r.json.ops) || [];
  if (!ops.length) { await send(chatId, 'El registro está vacío.'); return; }
  const lines = ['🧾 <b>Actividad</b> (' + esc(r.json.source) + ')', ''];
  for (const o of ops.slice(0, 15)) {
    const mark = o.status === 'ok' ? '✅' : o.status === 'error' ? '❌' : '⏳';
    const who = o.actor === 'telegram' ? '🤖' : '👤';
    lines.push(mark + ' ' + who + ' ' + esc(o.action) + ' · ' + relTime(o.ts) +
      (o.error ? '\n   ↳ ' + esc(String(o.error).slice(0, 120)) : ''));
  }
  await send(chatId, lines.join('\n'));
}

async function cmdDeploy(chatId, token, arg) {
  const r = await api('GET', '/api/admin/github/workflows', undefined, token);
  if (r.status !== 200) {
    await send(chatId, '⚠️ ' + esc((r.json && r.json.error) || ('HTTP ' + r.status)) +
      '\nRequiere GITHUB_TOKEN en Render.');
    return;
  }
  const avail = ((r.json && r.json.workflows) || []).filter((w) => w.state === 'active');
  const usable = avail.filter((w) => SAFE_DISPATCH.includes(w.path));

  const want = arg ? (arg.endsWith('.yml') ? arg : arg + '.yml') : null;
  if (want) {
    const hit = usable.find((w) => w.path.toLowerCase() === want.toLowerCase());
    if (!hit) { await send(chatId, 'Ese workflow no está en la lista permitida. Usa /desplegar sin argumentos.'); return; }
    return askConfirm(chatId, hit.path);
  }

  if (!usable.length) { await send(chatId, 'No hay workflows lanzables disponibles.'); return; }
  const rows = [];
  for (let i = 0; i < usable.length; i += 2) {
    rows.push(usable.slice(i, i + 2).map((w) => ({
      text: '🚀 ' + w.path.replace(/\.yml$/, ''),
      callback_data: 'wf:' + w.path,
    })));
  }
  await send(chatId, [
    '🚀 <b>¿Qué quieres desplegar?</b>',
    '',
    ...usable.map((w) => '· <code>' + esc(w.path) + '</code> — ' + esc(w.name)),
    '',
    'Toca uno y te pediré confirmación.',
  ].join('\n'), rows);
}

function askConfirm(chatId, file) {
  pending.set(String(chatId), { file, at: Date.now() });
  return send(chatId, [
    '⚠️ <b>Confirmar</b>',
    '',
    'Vas a lanzar <code>' + esc(file) + '</code> en la rama principal.',
    'Esto dispara acciones reales en GitHub (build, deploy o APK).',
  ].join('\n'), [[
    { text: '✅ Sí, lanzar', callback_data: 'wfok:' + file },
    { text: '✖ Cancelar', callback_data: 'wfno:0' },
  ]]);
}

async function doDispatch(chatId, token, file) {
  const r = await api('POST', '/api/admin/github/dispatch', { workflow: file, inputs: {} }, token);
  if (r.status !== 200 || !(r.json && r.json.ok)) {
    await send(chatId, '❌ No se pudo lanzar: ' + esc((r.json && r.json.error) || ('HTTP ' + r.status)));
    return;
  }
  C.logAdmin('telegram', 'workflow.dispatch', { workflow: file }, 'ok', 'workflow');
  C.tgAlert('botdispatch', () => '🚀 <b>Workflow lanzado desde el bot</b>\n<code>' + esc(file) + '</code>\nChat: <code>' + chatId + '</code>');
  await send(chatId, [
    '🚀 Lanzado <code>' + esc(file) + '</code>',
    '',
    'Sigue el progreso con /runs',
    esc(r.json.run_url || ''),
  ].join('\n'), [[{ text: '🔁 Ver runs', callback_data: 'nav:runs' }]]);
}

// ── Router ─────────────────────────────────────────────────────
const COMMANDS = {
  '/start':     (c) => send(c, HELP),
  '/ayuda':     (c) => send(c, HELP),
  '/help':      (c) => send(c, HELP),
  '/estado':    (c, t) => cmdStatus(c, t),
  '/status':    (c, t) => cmdStatus(c, t),
  '/visitas':   (c, t) => cmdVisitas(c, t),
  '/apps':      (c, t, a) => cmdApps(c, t, a),
  '/app':       (c, t, a) => cmdApp(c, t, a),
  '/runs':      (c, t) => cmdRuns(c, t),
  '/jobs':      (c, t) => cmdJobs(c, t),
  '/actividad': (c, t) => cmdActividad(c, t),
  '/desplegar': (c, t, a) => cmdDeploy(c, t, a),
  '/deploy':    (c, t, a) => cmdDeploy(c, t, a),
};

async function onCommand(chatId, text) {
  const parts = text.trim().split(/\s+/);
  const name = parts[0].split('@')[0].toLowerCase();
  const arg  = parts.slice(1).join(' ').trim();

  C.logAdmin('telegram', 'bot.cmd', { cmd: name }, 'ok', 'bot');

  if (name === '/login')  return cmdLogin(chatId, arg);
  if (name === '/logout') {
    sessions.delete(String(chatId));
    pending.delete(String(chatId));
    return send(chatId, '👋 Sesión cerrada.');
  }

  const fn = COMMANDS[name];
  if (!fn) return send(chatId, 'No conozco ese comando. /ayuda');

  // Todo menos /login y /ayuda exige sesión.
  const token = sessionOf(chatId);
  if (!token) return send(chatId, '🔐 Primero abre sesión: <code>/login &lt;clave&gt;</code>');
  return fn(chatId, token, arg);
}

async function onCallback(cb) {
  const msg = cb.message || {};
  const chatId = msg.chat && msg.chat.id;
  if (!chatId || !C.chatId || String(chatId) !== String(C.chatId)) {
    return answerCb(cb.id, 'No autorizado');
  }
  const data = String(cb.data || '');
  await answerCb(cb.id, 'Procesando…');

  // Navegación rápida desde los botones (solo comandos conocidos).
  if (data.startsWith('nav:')) {
    const cmd = data.slice(4).toLowerCase();
    if (!COMMANDS[cmd]) return;
    const token = sessionOf(chatId);
    if (!token) return send(chatId, '🔐 Sesión caducada: /login &lt;clave&gt;');
    return onCommand(chatId, '/' + cmd);
  }

  if (data === 'wfno:0') {
    pending.delete(String(chatId));
    return send(chatId, '✖ Cancelado.');
  }

  // callback_data viaja dentro del update: se re-valida contra la lista
  // permitida antes de ofrecer nada (si no, un update manipulado podría
  // llegar a lanzar un workflow fuera de SAFE_DISPATCH).
  if (data.startsWith('wf:')) {
    const file = data.slice(3);
    if (!isSafeWorkflow(file)) return send(chatId, 'Ese workflow no está permitido.');
    pending.set(String(chatId), { file, at: Date.now() });
    return askConfirm(chatId, file);
  }

  if (data.startsWith('wfok:')) {
    const file = data.slice(5);
    if (!isSafeWorkflow(file)) return send(chatId, 'Ese workflow no está permitido.');
    const p = pending.get(String(chatId));
    if (!p || p.file !== file || Date.now() - p.at > CONFIRM_TTL) {
      pending.delete(String(chatId));
      return send(chatId, '⌛ Esa confirmación caducó. Vuelve a /desplegar.');
    }
    pending.delete(String(chatId));
    const token = sessionOf(chatId);
    if (!token) return send(chatId, '🔐 Sesión caducada: /login &lt;clave&gt;');
    return doDispatch(chatId, token, file);
  }
}

/** Solo se aceptan los nombres exactos de SAFE_DISPATCH (sin trampas). */
function isSafeWorkflow(file) {
  const f = String(file || '').trim();
  return SAFE_DISPATCH.some((s) => s.toLowerCase() === f.toLowerCase());
}

async function handleUpdate(req, res) {
  // 1) Origen: secreto compartido con Telegram (fail-closed).
  if (!C.webhookSecret) {
    console.error('telegram-bot: TELEGRAM_WEBHOOK_SECRET no configurado — webhook deshabilitado');
    return res.status(503).json({ ok: false, error: 'webhook no configurado' });
  }
  const got = req.get('X-Telegram-Bot-Api-Secret-Token');
  if (got !== C.webhookSecret) {
    C.tgAlert('tgwebhook', () => '🚫 <b>Webhook de Telegram rechazado</b>\nSecret inválido\nIP: <code>' + req.ip + '</code>', { windowMs: 30000 });
    return res.sendStatus(403);
  }

  // 2) Responder siempre rápido: Telegram reintenta si tarda.
  res.sendStatus(200);

  try {
    const u = req.body || {};

    if (u.callback_query) { await onCallback(u.callback_query); return; }

    const msg = u.message;
    if (!msg || !msg.chat) return;

    // 3) Whitelist de chat.
    if (!C.chatId || String(msg.chat.id) !== String(C.chatId)) {
      C.tgAlert('tgchat', () => '🚫 <b>Chat no autorizado en el bot</b>\nID: <code>' + esc(msg.chat.id) + '</code>', { windowMs: 30000 });
      return;
    }

    // 4) Solo comandos de texto por ahora.
    const text = String(msg.text || '').trim();
    if (!text.startsWith('/')) return;

    if (throttled(msg.chat.id)) {
      await send(msg.chat.id, '⏳ Vas muy rápido. Espera un minuto.');
      return;
    }
    await onCommand(msg.chat.id, text);
  } catch (e) {
    console.error('telegram-bot: error procesando update:', e.message);
  }
}

// ── Arranque ───────────────────────────────────────────────────
function registerWebhook() {
  if (!C.token || !C.webhookSecret || !C.publicUrl) return;
  const url = C.publicUrl.replace(/\/$/, '') + '/api/telegram/webhook';
  tgApi('setWebhook', {
    url,
    secret_token: C.webhookSecret,
    allowed_updates: ['message', 'callback_query'],
    drop_pending_updates: false,
  }).then((r) => {
    if (r && r.ok) console.log('🤖 Webhook de Telegram registrado:', url);
    else console.warn('⚠️ No se pudo registrar el webhook de Telegram:', r && r.description);
  }).catch(() => {});
}

function init(deps) {
  const { app } = deps;
  if (!app) throw new Error('telegram-bot: falta la app de Express');

  const enabled = String(process.env.TG_BOT_ENABLED || 'true').toLowerCase() !== 'false';
  const token   = (process.env.TELEGRAM_BOT_TOKEN || process.env.TELEGRAM_TOKEN || '').trim();

  C = {
    app,
    token,
    chatId: (process.env.TELEGRAM_CHAT_ID || '').trim(),
    webhookSecret: (process.env.TELEGRAM_WEBHOOK_SECRET || '').trim(),
    adminKey: deps.adminKey || process.env.ADMIN_KEY || '',
    backendUrl: deps.backendUrl || ('http://127.0.0.1:' + (deps.port || 3001)),
    publicUrl: (deps.publicUrl || process.env.BACKEND_URL || '').trim(),
    port: deps.port || 3001,
    signSession: deps.signSession,
    sessionTtl: deps.sessionTtl || 30 * 60 * 1000,
    tgAlert: deps.tgAlert || (() => {}),
    logAdmin: deps.logAdmin || (() => {}),
  };

  if (!C.token || !C.chatId) {
    console.warn('⚠️ telegram-bot: falta TELEGRAM_BOT_TOKEN o TELEGRAM_CHAT_ID — bot desactivado');
    return { enabled: false, handleUpdate };
  }
  if (!C.webhookSecret) {
    console.error('⚠️ telegram-bot: falta TELEGRAM_WEBHOOK_SECRET — el webhook rechazará todo (fail-closed)');
  }
  if (!C.signSession) throw new Error('telegram-bot: falta signSession (server.js debe pasar _signSession)');

  if (!enabled) {
    console.log('ℹ️ telegram-bot: desactivado por TG_BOT_ENABLED=false');
    return { enabled: false, handleUpdate };
  }

  app.post('/api/telegram/webhook', handleUpdate);

  console.log('🤖 Bot de administración por Telegram listo (chat ' + C.chatId + ')');
  registerWebhook();
  return { enabled: true, handleUpdate };
}

module.exports = { init, handleUpdate: (req, res) => handleUpdate(req, res) };
