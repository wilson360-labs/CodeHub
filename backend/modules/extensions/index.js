'use strict';
/**
 * index.js — Barrel export del módulo Extensions.
 * Módulo: catálogo privado + proxy de extensiones de Echo · CodeHub
 * ─────────────────────────────────────────────────────────────────
 * Uso en server.js:
 *
 *   const extensionsRouter = require('./modules/extensions');
 *   app.use('/api/extensions', requireAuth, extensionsRouter);
 *
 * Ver backend/modules/extensions/config.js para las variables de entorno.
 */

module.exports = require('./routes');
