'use strict';
/**
 * rewards/routes.js — API de anuncios recompensados verificados por servidor.
 *
 * Endpoints (montados en /api/rewards con `requireAuth` opcional):
 *   GET  /config              Config pública (¿recompensas activas?, proveedores).
 *   POST /token               Emite un nonce single-use (requiere sesión).
 *   GET|POST /postback[/:source]  Postback S2S de la red → acredita.
 *   GET  /balance             Saldo + ganado hoy (sesión).
 *   GET  /history             Últimos movimientos (sesión).
 *   POST /spend               Gasta créditos (canje).
 *   GET  /admin/stats         Métricas (ADMIN_KEY).
 *
 * Flujo de seguridad (ver docs/REWARDED-ADS.md):
 *   1) /token emite un nonce single-use ligado al usuario.
 *   · El front pasa el nonce a la red como `ymid`/`custom_data`.
 *   · La red llama al postback S2S (con `secret`) al completarse el anuncio.
 *   · Aquí se consume el nonce, se verifica el estado `valued` y se
 *     acredita de forma idempotente (índice único en `ref`).
 *   · El navegador NUNCA acredita: solo consulta /balance.
 */

const express   = require('express');
const rateLimit = require('express-rate-limit');
const crypto    = require('crypto');

const { clientIp } = require('../../utils');
const { RewardNonce, RewardLedger, RewardBalance } = require('./models');
const {
  randomNonce, signNonce, timingSafeEqual, normalizePostback, NONCE_TTL_MS,
} = require('./security');
const cfg = require('./config');

const router = express.Router();

const ok   = (res, data, code = 200) => res.status(code).json({ ok: true, ...data });
const fail = (res, error, code = 400) => res.status(code).json({ ok: false, error });

function startOfToday() {
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  return d;
}

// ── Rate limits ────────────────────────────────────────────────
const tokenLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 20,
  standardHeaders: true,
  legacyHeaders: false,
  message: { ok: false, error: 'Demasiadas solicitudes. Espera un momento.' },
  keyGenerator: (req) => (req.authUser && req.authUser.id) || clientIp(req),
});
const spendLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 30,
  standardHeaders: true,
  legacyHeaders: false,
  message: { ok: false, error: 'Demasiadas solicitudes. Espera un momento.' },
  keyGenerator: (req) => (req.authUser && req.authUser.id) || clientIp(req),
});

const readLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 90,
  standardHeaders: true,
  legacyHeaders: false,
  message: { ok: false, error: 'Demasiadas solicitudes.' },
  keyGenerator: (req) => (req.authUser && req.authUser.id) || clientIp(req),
});

// ─────────────────────────────────────────────────────────────────
//  GET /config — el front sabe si las recompensas están activas
// ─────────────────────────────────────────────────────────────────
router.get('/config', (req, res) => {
  ok(res, {
    enabled: cfg.isEnabled(),
    providers: cfg.getProviders(),
    adUnit: cfg.getDefaultAdUnit(),
    dailyCap: cfg.getDailyCap(),
    nonceTtlMs: NONCE_TTL_MS,
  });
});

// ─────────────────────────────────────────────────────────────────
//  POST /token — emite un nonce single-use para una visualización
// ─────────────────────────────────────────────────────────────────
router.post('/token', tokenLimiter, async (req, res) => {
  if (!req.authUser) return fail(res, 'Inicia sesión para ganar recompensas', 401);
  if (!cfg.isEnabled()) return fail(res, 'Recompensas no disponibles', 503);

  const adUnit = String(req.body?.adUnit || cfg.getDefaultAdUnit())
    .trim().toLowerCase().replace(/[^a-z0-9_-]/g, '').slice(0, 32) || 'default';

  try {
    const nonce = randomNonce();
    await RewardNonce.create({
      nonce,
      userId: req.authUser.id,
      adUnit,
      ip: clientIp(req),
    });
    ok(res, {
      nonce,
      sig: signNonce(nonce, req.authUser.id, cfg.getHmacSecret()),
      ttlMs: NONCE_TTL_MS,
      adUnit,
    });
  } catch (e) {
    console.error('rewards/token:', e.message);
    fail(res, 'No se pudo crear el token de recompensa', 500);
  }
});

// ─────────────────────────────────────────────────────────────────
//  Postback server-to-server (lo llama la RED, nunca el navegador)
// ─────────────────────────────────────────────────────────────────
async function handlePostback(req, res) {
  const source = String(req.params.source || req.query.source || req.body?.source || 'default');
  const params = { ...(req.query || {}), ...(req.body || {}) };

  // 1) Autenticidad del origen: el secreto vive solo en el panel de la
  //    red y en Render; nunca se expone al cliente.
  const secret = cfg.getSecretFor(source);
  if (!secret) return fail(res, 'Recompensas deshabilitadas (sin secretos configurados)', 503);
  const provided = params.secret || req.headers['x-reward-secret'] || '';
  if (!timingSafeEqual(provided, secret)) return fail(res, 'Firma inválida', 403);

  const norm = normalizePostback(source, params, {
    defaultValued: ['adgem', 'adgate', 'offertoro'].includes(String(source).toLowerCase()),
  });

  // Sin identidad de usuario no hay nada que acreditar. Respondemos 200
  // para que la red deje de reintentar (no es un error reintentable).
  if (!norm.nonce) return ok(res, { credited: false, reason: 'no_nonce' });
  if (!norm.valued) return ok(res, { credited: false, reason: 'not_valued' });

  const ip = clientIp(req);
  try {
    let targetUserId = null;
    let adUnit = cfg.getDefaultAdUnit();

    // 2) Modo rewarded video: el valor que llega es un nonce que emitimos.
    //    Se consume de forma ATÓMICA y single-use.
    const nonceDoc = await RewardNonce.findOneAndUpdate(
      { nonce: norm.nonce, used: false, expiresAt: { $gt: new Date() } },
      { $set: { used: true, usedAt: new Date(), ip, source: norm.source } },
      { new: false },
    ).lean();
    if (nonceDoc) {
      targetUserId = nonceDoc.userId;
      adUnit = nonceDoc.adUnit || adUnit;
    } else if (cfg.isUserIdSource(norm.source)) {
      // 3) Modo offerwall: la red ya firmó el postback con el secreto y
      //    nos devuelve el user_id que le pasamos al abrir el muro.
      const uid = String(norm.nonce);
      if (/^[A-Za-z0-9._:-]{6,80}$/.test(uid)) targetUserId = uid;
    }

    if (!targetUserId) return ok(res, { credited: false, reason: 'unknown_user' });

    // 4) Tope diario por usuario (anti-abuso)
    const todayCount = await RewardLedger.countDocuments({
      userId: targetUserId, amount: { $gt: 0 }, createdAt: { $gte: startOfToday() },
    });
    if (todayCount >= cfg.getDailyCap()) {
      return ok(res, { credited: false, reason: 'daily_cap' });
    }

    // 5) Monto + libro mayor idempotente (índice único en `ref`)
    const amount = cfg.resolveAmount(adUnit, norm.amount);
    const ref = String(norm.ref || norm.nonce);
    try {
      await RewardLedger.create({
        userId: targetUserId,
        amount,
        source: norm.source,
        ref,
        adUnit,
        ip,
        meta: {
          zone_id: params.zone_id || params.zone || null,
          request_var: params.request_var || null,
          estimated_price: params.estimated_price || null,
        },
      });
    } catch (e) {
      if (e && e.code === 11000) return ok(res, { credited: false, reason: 'duplicate' });
      throw e;
    }

    // 6) Acredita el saldo
    const bal = await RewardBalance.findOneAndUpdate(
      { userId: targetUserId },
      { $inc: { credits: amount }, $set: { updatedAt: new Date() } },
      { upsert: true, new: true },
    ).lean();

    return ok(res, { credited: true, amount, credits: (bal && bal.credits) || 0 });
  } catch (e) {
    console.error('rewards/postback:', e.message);
    // 500 → la red reintenta (Monetag hasta ~38 h). No acreditamos a medias.
    return fail(res, 'Error procesando la recompensa', 500);
  }
}

router.get('/postback', handlePostback);
router.post('/postback', handlePostback);
router.get('/postback/:source', handlePostback);
router.post('/postback/:source', handlePostback);

// ─────────────────────────────────────────────────────────────────
//  GET /balance — saldo y ganado hoy (el front confirma con esto)
// ─────────────────────────────────────────────────────────────────
router.get('/balance', readLimiter, async (req, res) => {
  if (!req.authUser) return fail(res, 'No autenticado', 401);
  try {
    const [bal, agg] = await Promise.all([
      RewardBalance.findOne({ userId: req.authUser.id }).lean(),
      RewardLedger.aggregate([
        { $match: { userId: req.authUser.id, amount: { $gt: 0 }, createdAt: { $gte: startOfToday() } } },
        { $group: { _id: null, total: { $sum: '$amount' } } },
      ]),
    ]);
    ok(res, {
      credits: (bal && bal.credits) || 0,
      todayEarned: (agg[0] && agg[0].total) || 0,
      dailyCap: cfg.getDailyCap(),
      enabled: cfg.isEnabled(),
    });
  } catch (e) {
    fail(res, 'No se pudo leer el saldo', 500);
  }
});

// ─────────────────────────────────────────────────────────────────
//  GET /history — últimos movimientos del usuario
// ─────────────────────────────────────────────────────────────────
router.get('/history', readLimiter, async (req, res) => {
  if (!req.authUser) return fail(res, 'No autenticado', 401);
  const limit = Math.min(Math.max(parseInt(req.query.limit, 10) || 20, 1), 100);
  try {
    const items = await RewardLedger.find({ userId: req.authUser.id })
      .sort({ createdAt: -1 })
      .limit(limit)
      .select('amount source ref createdAt -_id')
      .lean();
    ok(res, { items });
  } catch (e) {
    fail(res, 'No se pudo leer el historial', 500);
  }
});

// ─────────────────────────────────────────────────────────────────
//  POST /spend — canjear créditos (decremento atómico)
// ─────────────────────────────────────────────────────────────────
router.post('/spend', spendLimiter, async (req, res) => {
  if (!req.authUser) return fail(res, 'No autenticado', 401);
  const amount = parseInt(req.body?.amount, 10);
  if (!Number.isFinite(amount) || amount <= 0 || amount > 100000) {
    return fail(res, 'Monto inválido');
  }
  const reason = String(req.body?.reason || 'canje').replace(/[^a-zA-Z0-9 _-]/g, '').slice(0, 60);
  try {
    // Solo descuenta si el saldo alcanza (condición atómica).
    const bal = await RewardBalance.findOneAndUpdate(
      { userId: req.authUser.id, credits: { $gte: amount } },
      { $inc: { credits: -amount }, $set: { updatedAt: new Date() } },
      { new: true },
    ).lean();
    if (!bal) return fail(res, 'Saldo insuficiente', 400);

    await RewardLedger.create({
      userId: req.authUser.id,
      amount: -amount,
      source: 'spend',
      ref: `spend_${crypto.randomUUID()}`,
      meta: { reason },
    });
    ok(res, { credits: bal.credits, spent: amount });
  } catch (e) {
    console.error('rewards/spend:', e.message);
    fail(res, 'No se pudo canjear', 500);
  }
});

// ─────────────────────────────────────────────────────────────────
//  GET /admin/stats — métricas (ADMIN_KEY)
// ─────────────────────────────────────────────────────────────────
router.get('/admin/stats', async (req, res) => {
  const key = req.headers['x-admin-key'];
  if (!process.env.ADMIN_KEY || !timingSafeEqual(key || '', process.env.ADMIN_KEY)) {
    return fail(res, 'Credenciales incorrectas', 403);
  }
  try {
    const [users, total, todayAgg] = await Promise.all([
      RewardBalance.countDocuments({ credits: { $gt: 0 } }),
      RewardLedger.countDocuments({ amount: { $gt: 0 } }),
      RewardLedger.aggregate([
        { $match: { amount: { $gt: 0 }, createdAt: { $gte: startOfToday() } } },
        { $group: { _id: null, total: { $sum: '$amount' }, count: { $sum: 1 } } },
      ]),
    ]);
    ok(res, {
      enabled: cfg.isEnabled(),
      providers: cfg.getProviders(),
      usersWithCredits: users,
      totalRewards: total,
      todayCredits: (todayAgg[0] && todayAgg[0].total) || 0,
      todayCount: (todayAgg[0] && todayAgg[0].count) || 0,
      dailyCap: cfg.getDailyCap(),
    });
  } catch (e) {
    fail(res, 'No se pudieron leer las métricas', 500);
  }
});

module.exports = router;
