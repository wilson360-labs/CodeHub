'use strict';
/**
 * rewards/config.js — Configuración por variables de entorno del
 * sistema de recompensas. Nada aquí concede recompensas: solo lee y
 * valida configuración (secretos, montos, topes).
 *
 * Variables:
 *   REWARD_POSTBACK_SECRETS   JSON { fuente: secreto } — preferido.
 *   REWARD_POSTBACK_SECRET    Secreto único (cualquier fuente).
 *   MONETAG_POSTBACK_SECRET   Atajo por red (también ADGEM_/AYET_/BITLABS_).
 *   REWARD_HMAC_SECRET        Secreto para firmar nonces (fallback: ADMIN_*).
 *   REWARD_AMOUNTS            JSON { adUnit: créditos } (ej. {"default":1,"bonus":5}).
 *   REWARD_DAILY_CAP          Máximo de créditos por usuario y día (def. 50).
 *   REWARD_AD_UNIT            Unidad por defecto (def. "default").
 *   REWARD_USER_ID_SOURCES    CSV de redes que acreditan por user_id
 *                             (offerwalls, p. ej. "adgem,bitlabs").
 *                             Vacío = solo modo nonce (rewarded video).
 */

const crypto = require('crypto');

// Secreto para firmar nonces. Los nonces también viven en DB, así que
// un secreto efímero (sin env) no rompe nada; solo añade defensa extra.
const _hmacSecret = process.env.REWARD_HMAC_SECRET
  || process.env.ADMIN_SESSION_SECRET
  || process.env.ADMIN_KEY
  || crypto.randomBytes(32).toString('hex');

function parseJsonEnv(name) {
  const raw = process.env[name];
  if (!raw) return null;
  try {
    const v = JSON.parse(raw);
    return v && typeof v === 'object' && !Array.isArray(v) ? v : null;
  } catch {
    return null;
  }
}

/** Mapa { fuente: secreto } de todos los secretos de postback configurados. */
function getSecrets() {
  const map = {};
  const json = parseJsonEnv('REWARD_POSTBACK_SECRETS');
  if (json) {
    for (const [k, v] of Object.entries(json)) {
      if (v) map[String(k).toLowerCase()] = String(v);
    }
  }
  const singles = {
    default:  process.env.REWARD_POSTBACK_SECRET,
    monetag:  process.env.MONETAG_POSTBACK_SECRET,
    adgem:    process.env.ADGEM_POSTBACK_SECRET,
    ayet:     process.env.AYET_POSTBACK_SECRET,
    bitlabs:  process.env.BITLABS_POSTBACK_SECRET,
  };
  for (const [k, v] of Object.entries(singles)) if (v) map[k] = String(v);
  return map;
}

/** Secreto para una fuente; si solo hay uno configurado, se usa para todo. */
function getSecretFor(source) {
  const map = getSecrets();
  const s = String(source || '').trim().toLowerCase();
  if (map[s]) return map[s];
  if (map.default) return map.default;
  const keys = Object.keys(map);
  return keys.length === 1 ? map[keys[0]] : null;
}

/** ¿Hay al menos un secreto? Sin secreto, el módulo queda deshabilitado. */
function isEnabled() {
  return Object.keys(getSecrets()).length > 0;
}

/** Nombres de red configurados (para /api/rewards/config). */
function getProviders() {
  return Object.keys(getSecrets());
}

/** Montos por unidad de anuncio { adUnit: créditos }. */
function getAmounts() {
  const json = parseJsonEnv('REWARD_AMOUNTS') || {};
  const out = {};
  for (const [k, v] of Object.entries(json)) {
    const n = parseInt(v, 10);
    if (Number.isFinite(n) && n > 0) out[String(k).toLowerCase()] = n;
  }
  return out;
}

/**
 * Créditos a otorgar: prioriza monto por adUnit → monto default →
 * monto que envía la red (offerwalls) → 1.
 */
function resolveAmount(adUnit, networkAmount = 0) {
  const amounts = getAmounts();
  const byUnit = parseInt(amounts[String(adUnit || '').toLowerCase()], 10);
  if (Number.isFinite(byUnit) && byUnit > 0) return byUnit;
  const byDefault = parseInt(amounts.default, 10);
  if (Number.isFinite(byDefault) && byDefault > 0) return byDefault;
  const net = parseInt(networkAmount, 10);
  if (Number.isFinite(net) && net > 0) return net;
  return 1;
}

function getDailyCap() {
  const n = parseInt(process.env.REWARD_DAILY_CAP, 10);
  return Number.isFinite(n) && n > 0 ? n : 50;
}

function getDefaultAdUnit() {
  return String(process.env.REWARD_AD_UNIT || 'default')
    .trim().toLowerCase().replace(/[^a-z0-9_-]/g, '').slice(0, 32) || 'default';
}

/** Fuentes que pueden acreditar por user_id (offerwalls). Vacío = desactivado. */
function getUserIdSources() {
  return String(process.env.REWARD_USER_ID_SOURCES || '')
    .split(',').map((s) => s.trim().toLowerCase()).filter(Boolean);
}

function isUserIdSource(source) {
  return getUserIdSources().includes(String(source || '').trim().toLowerCase());
}

function getHmacSecret() {
  return _hmacSecret;
}

module.exports = {
  getSecrets,
  getSecretFor,
  isEnabled,
  getProviders,
  getAmounts,
  resolveAmount,
  getDailyCap,
  getDefaultAdUnit,
  getUserIdSources,
  isUserIdSource,
  getHmacSecret,
  parseJsonEnv,
};
