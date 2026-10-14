'use strict';
/**
 * rewards/security.js — Lógica pura y determinista del sistema de
 * anuncios recompensados. SIN red, SIN DB, SIN estado global: todo
 * aquí es testeable de forma aislada (backend/tests/rewards.test.js).
 *
 * Regla de oro: la recompensa NUNCA se concede por un callback del
 * navegador. Estas funciones solo preparan/validan datos — la decisión
 * de acreditar vive en routes.js y depende de un postback S2S de la red.
 */

const crypto = require('crypto');

// ── Constantes ────────────────────────────────────────────────
const NONCE_BYTES  = 24;               // → 48 caracteres hex
const NONCE_TTL_MS = 15 * 60 * 1000;   // 15 minutos de vida

// Estados que significan "el anuncio se completó y la red lo paga".
const VALUED_VALUES = new Set([
  'valued', '1', 'true', 'completed', 'complete',
  'ok', 'success', 'approved', 'credited',
]);

/** Genera un nonce aleatorio criptográficamente seguro. */
function randomNonce(bytes = NONCE_BYTES) {
  return crypto.randomBytes(bytes).toString('hex');
}

/**
 * Comparación de strings en tiempo constante. Evita timing attacks al
 * validar el `secret` del postback o las firmas HMAC. Nunca lanza por
 * longitudes distintas (devuelve false).
 */
function timingSafeEqual(a, b) {
  const ba = Buffer.from(String(a == null ? '' : a));
  const bb = Buffer.from(String(b == null ? '' : b));
  if (ba.length === 0 || ba.length !== bb.length) return false;
  return crypto.timingSafeEqual(ba, bb);
}

/** Firma HMAC de un nonce ligado a un usuario (defensa en profundidad). */
function signNonce(nonce, userId, secret) {
  return crypto
    .createHmac('sha256', String(secret || ''))
    .update(`${nonce}.${userId || ''}`)
    .digest('base64url');
}

/** Verifica la firma de un nonce en tiempo constante. */
function verifyNonceSignature(nonce, userId, signature, secret) {
  if (!signature) return false;
  return timingSafeEqual(signature, signNonce(nonce, userId, secret));
}

/** ¿El estado del evento significa que la visualización se completó y paga? */
function isValuedStatus(value) {
  if (value === undefined || value === null) return false;
  return VALUED_VALUES.has(String(value).trim().toLowerCase());
}

/**
 * Normaliza el postback de una red a un contrato común:
 *   { valued, nonce, ref, amount, source, statusRaw }
 *
 * Se adapta a:
 *   · Monetag   → reward_event_type=valued  + ymid
 *   · ayeT/CPA  → status=1                  + user_id/transaction_id
 *   · AdGem v3  → JSON firmado              + user_id/transaction_id
 *   · genérico  → ymid | custom_data | user_id ...
 *
 * @param {string} source  Identificador de la red (monetag, adgem...).
 * @param {object} params  Query/body del postback (valores string).
 * @param {object} [opts]  { defaultValued }: si la red no manda estado y
 *                         su postback ya viene firmado (p. ej. AdGem v3).
 */
function normalizePostback(source, params = {}, opts = {}) {
  const pick = (...keys) => {
    for (const k of keys) {
      const v = params[k];
      if (v !== undefined && v !== null && String(v) !== '') return String(v);
    }
    return '';
  };

  // El orden importa: reward_event_type (Monetag) gana a event_type.
  const statusRaw = pick('reward_event_type', 'status', 'event', 'type');
  const valued = statusRaw ? isValuedStatus(statusRaw) : Boolean(opts.defaultValued);

  // Identidad del evento: para rewarded video es el nonce que emitimos;
  // para offerwalls es nuestro user_id. Ambos viajan en el mismo hueco.
  const nonce = pick('ymid', 'custom_data', 'custom', 'user_id', 'subid', 'sub_id', 'uid', 'click_id');

  const ref = pick(
    'transaction_id', 'txid', 'transactionId', 'event_id', 'ref', 'id',
  ) || `${pick('zone_id', 'zone')}:${nonce}`;

  const amount = parseInt(pick('amount', 'reward_amount', 'value') || '0', 10) || 0;

  const src = String(source || pick('source') || 'default')
    .trim().toLowerCase().replace(/[^a-z0-9_-]/g, '').slice(0, 32) || 'default';

  return { valued, nonce, ref, amount: amount > 0 ? amount : 0, source: src, statusRaw };
}

module.exports = {
  NONCE_BYTES,
  NONCE_TTL_MS,
  VALUED_VALUES,
  randomNonce,
  timingSafeEqual,
  signNonce,
  verifyNonceSignature,
  isValuedStatus,
  normalizePostback,
};
