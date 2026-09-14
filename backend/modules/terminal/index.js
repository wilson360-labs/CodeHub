/**
 * index.js — Barrel + rutas REST del Módulo Terminal
 * Módulo: Terminal · CodeHub v3
 * ─────────────────────────────────────────────────────────────────
 * Uso en server.js (sección MÓDULOS EXTERNOS):
 *
 *   const terminalModule = require('./modules/terminal');
 *   terminalModule.attach({ app, server, allowedOrigins });
 *
 * - app.use('/api/terminal', router)  → health + estado de la PTY.
 * - server (http)                      → monta WSS en /ws/terminal.
 */

'use strict';

const { mountTerminalWs } = require('./ws');
const { PTY_AVAILABLE } = require('./pty-manager');
const config = require('./config');

function attach({ app, server, allowedOrigins = [] }) {
  // Health: express.Router se importa lazy para no romper si express no está
  // disponible (tests, smoke tests con node_modules parcial, etc.).
  if (app && typeof app.use === 'function') {
    const express = require('express');
    const router = express.Router();
    router.get('/health', (req, res) => {
      res.json({
        ok: true,
        pty: PTY_AVAILABLE,
        maxSessions: config.MAX_SESSIONS_PER_WS,
        shells: config.ALLOWED_SHELLS,
        path: config.WS_PATH,
      });
    });
    app.use('/api/terminal', router);
  }

  if (typeof server !== 'undefined' && server && server.on) {
    mountTerminalWs(server, allowedOrigins);
  }

  if (!PTY_AVAILABLE) {
    console.warn('[terminal] ⚠️  node-pty no cargó: ejecutá `npm install` en /backend con toolchain nativo.');
  }
}

module.exports = { attach, PTY_AVAILABLE, config };