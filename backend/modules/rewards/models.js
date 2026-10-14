'use strict';
/**
 * rewards/models.js — Modelos Mongoose del sistema de recompensas.
 *
 *   RewardNonce   — nonces single-use emitidos por /token (TTL 15 min).
 *   RewardLedger  — libro mayor inmutable (índice único en `ref` →
 *                   idempotencia: jamás se acredita dos veces el mismo).
 *   RewardBalance — saldo por usuario (créditos actuales).
 *
 * Colecciones independientes del resto del backend. MONGODB_URI ya es
 * requisito del servidor, así que no añaden una dependencia nueva.
 */

const mongoose = require('mongoose');
const { NONCE_TTL_MS } = require('./security');

// ── Nonces de rewarded video (single-use) ─────────────────────
const RewardNonceSchema = new mongoose.Schema(
  {
    nonce:  { type: String, required: true, unique: true, index: true },
    userId: { type: String, required: true, index: true },
    adUnit: { type: String, default: 'default' },
    used:   { type: Boolean, default: false, index: true },
    usedAt: { type: Date, default: null },
    ip:     { type: String, default: null },
    source: { type: String, default: null },
    createdAt: { type: Date, default: Date.now },
    // TTL de MongoDB: el documento se borra solo cuando expiresAt < now().
    expiresAt: {
      type: Date,
      default: () => new Date(Date.now() + NONCE_TTL_MS),
      expires: 0,
    },
  },
  { versionKey: false, collection: 'reward_nonces' },
);

// ── Libro mayor (cada movimiento es una fila) ─────────────────
const RewardLedgerSchema = new mongoose.Schema(
  {
    userId: { type: String, required: true, index: true },
    amount: { type: Number, required: true },   // > 0 gana, < 0 gasta
    source: { type: String, default: 'unknown' }, // red o 'spend'
    ref:    { type: String, required: true, unique: true }, // idempotencia
    adUnit: { type: String, default: null },
    ip:     { type: String, default: null },
    meta:   { type: mongoose.Schema.Types.Mixed, default: {} },
    createdAt: { type: Date, default: Date.now, index: true },
  },
  { versionKey: false, collection: 'reward_ledger' },
);

RewardLedgerSchema.index({ userId: 1, createdAt: -1 });

// ── Saldo por usuario ─────────────────────────────────────────
const RewardBalanceSchema = new mongoose.Schema(
  {
    userId:  { type: String, required: true, unique: true, index: true },
    credits: { type: Number, default: 0 },
    updatedAt: { type: Date, default: Date.now },
  },
  { versionKey: false, collection: 'reward_balances' },
);

// Reutiliza modelos ya compilados (evita OverwriteModelError en hot-reload).
module.exports = {
  RewardNonce:   mongoose.models.RewardNonce   || mongoose.model('RewardNonce', RewardNonceSchema),
  RewardLedger:  mongoose.models.RewardLedger  || mongoose.model('RewardLedger', RewardLedgerSchema),
  RewardBalance: mongoose.models.RewardBalance || mongoose.model('RewardBalance', RewardBalanceSchema),
};
