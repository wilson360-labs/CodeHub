/**
 * index.js — Barrel export del módulo Universal Resolver
 * Módulo: Universal Resolver · CodeHub v3
 * ─────────────────────────────────────────────────────────────────
 * Uso en server.js:
 *
 *   const resolverRouter = require('./modules/universal-resolver');
 *   app.use('/api/resolver', resolverRouter);
 */

'use strict';

module.exports = require('./routes');
