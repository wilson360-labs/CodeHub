'use strict';
/**
 * index.js — Barrel export del módulo Rewards.
 * Módulo: Anuncios recompensados con verificación de servidor · CodeHub
 * ─────────────────────────────────────────────────────────────────
 * Uso en server.js:
 *
 *   const rewardsRouter = require('./modules/rewards');
 *   app.use('/api/rewards', requireAuth, rewardsRouter);
 *
 * Ver docs/REWARDED-ADS.md para el contrato completo y las variables
 * de entorno.
 */

module.exports = require('./routes');
