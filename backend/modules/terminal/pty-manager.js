/**
 * pty-manager.js — Gestor de sesiones PTY del Módulo Terminal
 * Módulo: Terminal · CodeHub v3
 * ─────────────────────────────────────────────────────────────────
 * Carga node-pty de forma diferida (lazy require): si la librería nativa
 * no está instalada o no se compiló en Render, el módulo arranca igual y
 * responde un error claro al intentar spawn (sin tumbar del servidor).
 *
 * node-pty es el transporte PTY real (renderers web y APK usan el MISMO
 * protocolo JSON de ws.js; en el APK el bridge nativo hablará el mismo
 * protocolo sobre un transporte distinto en una fase posterior).
 */

'use strict';

const { spawn } = require('child_process');
const config = require('./config');
const { sanitizedEnvironment, isDenied, nonRootIds, sanitizedCwd } = require('./sandbox');

/** Lazy require de node-pty — admite que no esté disponible. */
let ptyImpl = null;
try {
  ptyImpl = require('node-pty');
} catch (err) {
  console.warn('[terminal] node-pty no disponible:', err.message);
  ptyImpl = null;
}

const PTY_AVAILABLE = !!ptyImpl;

/**
 * Una sesión PTY asociada a un identificador de sesión (`id`).
 * Toda salida/salida va por callbacks hacia el emisor (ws o bridge).
 */
class PTYSession {
  constructor(id, socket, shell, cols, rows, cwd) {
    this.id = id;
    this.socket = socket;                 // emisor de mensajes (tiene .sendJson)
    this.shell = shell;
    this.pid = null;
    this.startTs = Date.now();
    this.proc = null;
    this._idleTimer = null;
    this._exitSent = false;

    if (!PTY_AVAILABLE) {
      this._emitError('PTY no disponible en este servidor (node-pty no pudo cargarse).');
      return;
    }

    const sandbox = nonRootIds() || {};
    let file = shell;                     // 'sh' | 'bash'
    let args = ['--noprofile', '--norc']; // evita que rc/profile del server contaminen el entorno
    if (shell === 'sh') args = [];

    try {
      this.proc = ptyImpl.spawn(file, args, {
        name: 'xterm-256color',
        cols: Math.max(config.COLS.min, Math.min(Math.floor(cols) || 80, config.COLS.max)),
        rows: Math.max(config.ROWS.min, Math.min(Math.floor(rows) || 24, config.ROWS.max)),
        cwd: sanitizedCwd(cwd),
        env: sanitizedEnvironment({ SHELL: file }),
        ...sandbox,
      });
      this.pid = this.proc.pid;

      this.proc.onData((data) => {
        this._resetIdle();
        this._emit({ type: 'output', id: this.id, data });
      });

      this.proc.onExit(({ exitCode, signal }) => {
        this._clearIdle();
        this._emittedExit();
        this._emit({ type: 'exit', id: this.id, code: exitCode, signal: signal || null });
      });
      this._resetIdle();
    } catch (err) {
      this._emitError('No se pudo lanzar la shell (' + err.message + ').');
      this.proc = null;
    }
  }

  write(data) {
    if (!this.proc) return;
    if (isDenied(data)) {
      this._emit({ type: 'notice', id: this.id, message: config.DENY_NOTICE });
      return;
    }
    this._resetIdle();
    try { this.proc.write(data); } catch { this._killSilent(); }
  }

  resize(cols, rows) {
    if (!this.proc) return;
    try {
      this.proc.resize(
        Math.max(config.COLS.min, Math.min(Math.floor(cols) || 80, config.COLS.max)),
        Math.max(config.ROWS.min, Math.min(Math.floor(rows) || 24, config.ROWS.max)),
      );
    } catch { /* la pty puede haber muerto entre input y resize */ }
  }

  kill() {
    this._killSilent();
    this._clearIdle();
  }

  get alive() {
    return !!(this.proc && !this._exitSent);
  }

  _killSilent() {
    if (!this.proc) return;
    try { this.proc.kill(); } catch { /* ya cerrada */ }
  }

  _resetIdle() {
    this._clearIdle();
    this._idleTimer = setTimeout(() => {
      this._emit({ type: 'notice', id: this.id, message: 'Sesión cerrada por inactividad (límite de sandbox).' });
      this.kill();
    }, config.SESSION_IDLE_MS);
    if (this._idleTimer.unref) this._idleTimer.unref();
  }

  _clearIdle() {
    if (this._idleTimer) { clearTimeout(this._idleTimer); this._idleTimer = null; }
  }

  _emittedExit() {
    this._exitSent = true;
    this._clearIdle();
    this.socket._onSessionExit && this.socket._onSessionExit(this.id);
  }

  _emit(msg) {
    try { this.socket.sendJson(msg); } catch { /* ws cerrado */ }
  }

  _emitError(message) {
    this._emit({ type: 'error', id: this.id, message });
    this.proc = null;
  }
}

module.exports = { PTY_AVAILABLE, PTYSession };