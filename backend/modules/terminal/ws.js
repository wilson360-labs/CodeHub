/**
 * ws.js — Protocolo WebSocket del Módulo Terminal
 * Módulo: Terminal · CodeHub v3
 * ─────────────────────────────────────────────────────────────────
 * Path propio: /ws/terminal (independiente del /ws de broadcast de server.js).
 * El protocolo es JSON y está pensado para ser COMPARTIDO con el APK:
 * más adelante el bridge nativo de Android hablará MISMOS mensajes sobre su
 * propio transporte, reutilizando Xterm.js en el frontend tal cual.
 *
 * Mensajes cliente→servidor:
 *   { type:'spawn', id, shell?:'sh'|'bash', cols, rows, cwd? }
 *   { type:'input',  id, data }
 *   { type:'resize', id, cols, rows }
 *   { type:'kill',   id }
 *   { type:'ping', ts }
 *
 * Mensajes servidor→cliente:
 *   { type:'ready',  id, pid, shell }
 *   { type:'output', id, data }
 *   { type:'exit',   id, code, signal }
 *   { type:'error',  id, message }
 *   { type:'notice', id, message }
 *   { type:'pong', ts }
 *
 * Política de abuso: origin-check (misma lista que el WSS de server.js),
 * máx. sesiones por conexión, rate-limit de mensajes, tamaño de chunk.
 */

'use strict';

const { WebSocketServer } = require('ws');
const config = require('./config');
const { PTY_AVAILABLE, PTYSession } = require('./pty-manager');

/**
 * Crea y monta el WSS del terminal sobre el servidor HTTP existente.
 * @param {import('http').Server} server
 * @param {string[]} allowedOrigins
 * @returns {import('ws').WebSocketServer}
 */
function mountTerminalWs(server, allowedOrigins) {
  const wss = new WebSocketServer({ server, path: config.WS_PATH });

  wss.on('connection', (ws, req) => {
    const origin = req.headers.origin;
    if (origin && !allowedOrigins.includes(origin)) {
      ws.close(1008, 'Origin no permitido');
      return;
    }

    // Sesiones de ESTE cliente (PTY vivas). Se eliminan al cerrar el WS.
    const sessions = new Map();

    // Pequeño rate-limit por ventana fija.
    let hits = 0; let windowStart = Date.now();

    ws.sendJson = (msg) => { if (ws.readyState === 1) ws.send(JSON.stringify(msg)); };
    ws._onSessionExit = (id) => sessions.delete(id);

    ws.on('message', (raw) => {
      // Rate-limit
      const now = Date.now();
      if (now - windowStart > config.RATE.windowMs) { hits = 0; windowStart = now; }
      hits++;
      if (hits > config.RATE.maxMessages) { ws.sendJson({ type: 'error', message: 'Rate limit excedido.' }); return; }

      // Parse seguro
      let msg;
      try { msg = JSON.parse(raw.toString('utf8')); } catch {
        ws.sendJson({ type: 'error', message: 'JSON inválido.' });
        return;
      }
      if (!msg || typeof msg !== 'object' || typeof msg.type !== 'string') return;

      const id = typeof msg.id === 'string' ? msg.id.slice(0, 48) : '';

      switch (msg.type) {
        case 'ping':
          ws.sendJson({ type: 'pong', ts: msg.ts });
          break;

        case 'spawn': {
          if (sessions.size >= config.MAX_SESSIONS_PER_WS) {
            ws.sendJson({ type: 'error', id, message: 'Máximo de ' + config.MAX_SESSIONS_PER_WS + ' sesiones por conexión.' });
            return;
          }
          if (!id) { ws.sendJson({ type: 'error', message: 'Falta id de sesión.' }); return; }
          const shell = config.ALLOWED_SHELLS.includes(msg.shell) ? msg.shell : config.ALLOWED_SHELLS[1];
          const sess = new PTYSession(id, ws, shell, msg.cols, msg.rows, msg.cwd);
          if (!sess.proc) return;                       // error ya emitido por la sesión
          sessions.set(id, sess);
          ws.sendJson({ type: 'ready', id, pid: sess.pid, shell });
          break;
        }

        case 'input': {
          const sess = sessions.get(id);
          if (!sess) { ws.sendJson({ type: 'error', id, message: 'Sesión inexistente.' }); return; }
          if (typeof msg.data !== 'string') return;
          if (msg.data.length > config.MAX_CHUNK_BYTES) {
            ws.sendJson({ type: 'error', id, message: 'Chunk demasiado grande.' });
            return;
          }
          sess.write(msg.data);
          break;
        }

        case 'resize': {
          const sess = sessions.get(id);
          if (!sess) return;
          sess.resize(msg.cols, msg.rows);
          break;
        }

        case 'kill': {
          const sess = sessions.get(id);
          if (sess) { sess.kill(); sessions.delete(id); }
          break;
        }
        default:
          ws.sendJson({ type: 'error', message: 'Tipo de mensaje desconocido.' });
      }
    });

    ws.on('close', () => {
      for (const sess of sessions.values()) sess.kill();
      sessions.clear();
    });
    ws.on('error', () => {
      for (const sess of sessions.values()) sess.kill();
      sessions.clear();
    });
  });

  if (!PTY_AVAILABLE) {
    console.warn('[terminal] WS /ws/terminal montado, pero node-pty NO está disponible: los spawn fallarán.');
  }
  return wss;
}

module.exports = { mountTerminalWs };