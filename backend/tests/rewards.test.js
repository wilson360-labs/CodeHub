'use strict';
/**
 * rewards.test.js — Pruebas de la lógica PURA del módulo de anuncios
 * recompensados (backend/modules/rewards/security.js + config.js).
 *
 * No toca red ni MongoDB: aquí solo se valida lo determinista
 * (normalización de postbacks, comparación en tiempo constante,
 * firmas HMAC, montos y secretos). El flujo completo se prueba en
 * el servidor contra el postback S2S real.
 */

const test = require('node:test');
const assert = require('node:assert/strict');

const {
  timingSafeEqual,
  signNonce,
  verifyNonceSignature,
  isValuedStatus,
  normalizePostback,
  randomNonce,
} = require('../modules/rewards/security');

const config = require('../modules/rewards/config');

// ── randomNonce ───────────────────────────────────────────────────
test('randomNonce: 48 hex por defecto y sin repetición', () => {
  const a = randomNonce();
  const b = randomNonce();
  assert.match(a, /^[a-f0-9]{48}$/);
  assert.notEqual(a, b);
});

test('randomNonce: respeta el tamaño pedido', () => {
  assert.match(randomNonce(8), /^[a-f0-9]{16}$/);
});

// ── timingSafeEqual ───────────────────────────────────────────────
test('timingSafeEqual: true con cadenas idénticas', () => {
  assert.equal(timingSafeEqual('secreto', 'secreto'), true);
});

test('timingSafeEqual: false con distinto contenido o longitud', () => {
  assert.equal(timingSafeEqual('secreto', 'secreta'), false);
  assert.equal(timingSafeEqual('secreto', 'secretoLargo'), false);
});

test('timingSafeEqual: nunca true con vacío/null/undefined', () => {
  assert.equal(timingSafeEqual('', ''), false);
  assert.equal(timingSafeEqual(null, 'x'), false);
  assert.equal(timingSafeEqual(undefined, ''), false);
});

// ── firma HMAC de nonces ──────────────────────────────────────────
test('signNonce/verifyNonceSignature: ida y vuelta válida', () => {
  const sig = signNonce('nonce1', 'user-1', 'top-secret');
  assert.equal(verifyNonceSignature('nonce1', 'user-1', sig, 'secret'), true);
  assert.equal(verifyNonceSignature('nonce1', 'user-1', sig, 'otro-secreto'), false);
});

test('verifyNonceSignature: no firmado o ligado a otro usuario falla', () => {
  const sig = signNonce('n1', 'userA', 'k');
  assert.equal(verifyNonceSignature('n1', 'userB', sig, 'k'), false);
  assert.equal(verifyNonceSignature('n1', 'userA', '', 'k'), false);
  assert.equal(verifyNonceSignature('n1', 'userA', null, 'k'), false);
});

// ── isValuedStatus ────────────────────────────────────────────────
test('isValuedStatus: solo "valued" (y equivalentes), case-insensitive', () => {
  assert.equal(isValuedStatus('valued'), true);
  assert.equal(isValuedStatus('Valued'), true);
  assert.equal(isValuedStatus(' valued '), true);
  assert.equal(isValuedStatus('1'), true);
  assert.equal(isValuedStatus('completed'), true);
  assert.equal(isValuedStatus('impression'), false);
  assert.equal(isValuedStatus(''), false);
  assert.equal(isValuedStatus(null), false);
  assert.equal(isValuedStatus(undefined), false);
});

// ── normalizePostback ─────────────────────────────────────────────
test('normalizePostback: Monetag valued con ymid', () => {
  const r = normalizePostback('monetag', {
    reward_event_type: 'valued',
    status: 'ok',
    ymid: 'NONCE123',
    transaction_id: 'TX-1',
    amount: '3',
  });
  assert.equal(r.valued, true);
  assert.equal(r.nonce, 'NONCE123');
  assert.equal(r.ref, 'TX-1');
  assert.equal(r.amount, 3);
  assert.equal(r.source, 'monetag');
});

test('normalizePostback: reward_event_type gana a event_type', () => {
  const r = normalizePostback('monetag', { reward_event_type: 'impression', type: 'valued' });
  assert.equal(r.valued, false);
  assert.equal(r.statusRaw, 'impression');
});

test('normalizePostback: sin transaction_id usa zona:nonce como ref', () => {
  const r = normalizePostback('ayet', { status: '1', user_id: 'U-9', zone_id: 'z7' });
  assert.equal(r.valued, true);
  assert.equal(r.nonce, 'U-9');
  assert.equal(r.ref, 'z7:U-9');
});

test('normalizePostback: usa ad_amount/value como respaldo', () => {
  const r = normalizePostback('adgem', { status: 'valued', user_id: 'U1', value: '12' });
  assert.equal(r.amount, 12);
});

test('normalizePostback: defaultValued cuando la red no manda estado', () => {
  const r = normalizePostback('adgem', { user_id: 'U-1', transaction_id: 'T' }, { defaultValued: true });
  assert.equal(r.valued, true);
  assert.equal(r.statusRaw, '');
});

test('normalizePostback: sanitiza el nombre de fuente', () => {
  const r = normalizePostback('Bad/../Source!!', {});
  assert.equal(r.source, 'bad-sourc');
});

test('normalizePostback: sin datos no valora y amount 0', () => {
  const r = normalizePostback('default', {});
  assert.equal(r.valued, false);
  assert.equal(r.nonce, '');
  assert.equal(r.amount, 0);
});

// ── config (reads env at call time) ──────────────────────────────
test('config: isEnabled false sin secretos y true al configurarlos', () => {
  const saved = { ...process.env };
  delete process.env.REWARD_POSTBACK_SECRETS;
  delete process.env.REWARD_POSTBACK_SECRET;
  const keys = ['MONETAG', 'ADGEM', 'AYET', 'BITLABS'];
  for (const k of keys) delete process.env[k + '_POSTBACK_SECRET'];
  assert.equal(config.isEnabled(), false);

  process.env.REWARD_POSTBACK_SECRET = 's1';
  assert.equal(config.isEnabled(), true);
  assert.equal(config.getSecretFor('monetag'), 's1'); // único → vale para todo
  assert.equal(config.getSecretFor('desconocida'), 's1');

  process.env.REWARD_POSTBACK_SECRETS = JSON.stringify({ monetag: 'm', adgem: 'a' });
  assert.equal(config.getSecretFor('monetag'), 'm');
  assert.equal(config.getSecretFor('adgem'), 'a');
  assert.equal(config.getSecretFor('otra'), 's1'); // cae al default

  delete process.env.REWARD_POSTBACK_SECRETS;
  delete process.env.REWARD_POSTBACK_SECRET;
  assert.equal(config.isEnabled(), false);
});

test('resolveAmount: adUnit → default → red → 1', () => {
  const prev = process.env.REWARD_AMOUNTS;
  process.env.REWARD_AMOUNTS = JSON.stringify({ default: 2, bonus: 7 });
  assert.equal(config.resolveAmount('bonus', 99), 7);
  assert.equal(config.resolveAmount('video', 99), 2);
  delete process.env.REWARD_AMOUNTS;
  assert.equal(config.resolveAmount('video', 5), 5);
  assert.equal(config.resolveAmount('video', 0), 1);
  if (prev === undefined) delete process.env.REWARD_AMOUNTS;
  else process.env.REWARD_AMOUNTS = prev;
});

test('getDailyCap: por defecto 50 y respeta env', () => {
  const prev = process.env.REWARD_DAILY_CAP;
  delete process.env.REWARD_DAILY_CAP;
  assert.equal(config.getDailyCap(), 50);
  process.env.REWARD_DAILY_CAP = '120';
  assert.equal(config.getDailyCap(), 120);
  if (prev === undefined) delete process.env.REWARD_DAILY_CAP;
  else process.env.REWARD_DAILY_CAP = prev;
});

test('isUserIdSource: solo redes habilitadas acreditan por user_id', () => {
  const prev = process.env.REWARD_USER_ID_SOURCES;
  delete process.env.REWARD_USER_ID_SOURCES;
  assert.equal(config.isUserIdSource('adgem'), false);
  process.env.REWARD_USER_ID_SOURCES = 'adgem, bitlabs';
  assert.equal(config.isUserIdSource('adgem'), true);
  assert.equal(config.isUserIdSource('ADGEM'), true);
  assert.equal(config.isUserIdSource('monetag'), false);
  if (prev === undefined) delete process.env.REWARD_USER_ID_SOURCES;
  else process.env.REWARD_USER_ID_SOURCES = prev;
});

test('getDefaultAdUnit: normaliza y cae a default', () => {
  const prev = process.env.REWARD_AD_UNIT;
  process.env.REWARD_AD_UNIT = 'Vídeo Premium!';
  const unit = config.getDefaultAdUnit();
  assert.match(unit, /^[a-z0-9_-]+$/);
  delete process.env.REWARD_AD_UNIT;
  assert.equal(config.getDefaultAdUnit(), 'default');
  if (prev !== undefined) process.env.REWARD_AD_UNIT = prev;
});
