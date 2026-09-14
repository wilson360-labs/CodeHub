/**
 * terminal.js — Módulo Terminal de CodeHub (Xterm.js + WebSocket → PTY)
 * CodeHub by Wilson.E · v3
 * ─────────────────────────────────────────────────────────────────
 * Arquitectura HÍBRIDA (una UI, varios transportes):
 *   - Transporte por defecto (web): WebSocket → backend /ws/terminal
 *     (node-pty en Render, sandbox de proceso).
 *   - Transporte APK (fase 2, mismo protocolo sobre el bridge nativo):
 *     la tarjeta se abre dentro de la WebView de CodeHub y hablará el MISMÍSIMO
 *     mensaje JSON {type,id,data} a través de CodeHubNative. Por eso todo el
 *     manejo de sesiones pasa por una abstracción TerminalTransport.
 *
 * Característica clave: MULTI-SESIÓN — una pestaña por PTY, multiplexada
 * sobre una sola conexión mediante `id` de sesión. El protocolo ya soporta
 * split-screen sin cambios (dos sesiones lado a lado).
 */

import { Terminal } from './vendor/xterm/xterm.mjs';
import { FitAddon } from './vendor/xterm/addon-fit.mjs';
import { WebglAddon } from './vendor/xterm/addon-webgl.mjs';

const BACKEND = (typeof window._CH_BACKEND !== 'undefined' && window._CH_BACKEND)
  ? window._CH_BACKEND
  : 'https://codehub-98s6.onrender.com';

const WS_URL = (BACKEND.startsWith('https://') ? 'wss://' : 'ws://') + BACKEND.replace(/^https?:\/\//, '');

/* ── Temas (premium: Dracula / Monokai / Cyberpunk) ─────────────────── */
const THEMES = {
  dracula: {
    label: 'Dracula',
    theme: {
      background: '#282a36', foreground: '#f8f8f2', cursor: '#f8f8f2',
      selectionBackground: '#44475a',
      black: '#21222c', red: '#ff5555', green: '#50fa7b', yellow: '#f1fa8c',
      blue: '#bd93f9', magenta: '#ff79c6', cyan: '#8be9fd', white: '#f8f8f2',
      brightBlack: '#6272a4', brightRed: '#ff6e6e', brightGreen: '#69ff94',
      brightYellow: '#ffffa5', brightBlue: '#d6acff', brightMagenta: '#ff92df',
      brightCyan: '#a4ffff', brightWhite: '#ffffff',
    },
  },
  monokai: {
    label: 'Monokai',
    theme: {
      background: '#272822', foreground: '#f8f8f2', cursor: '#f8f8f0',
      selectionBackground: '#49483e',
      black: '#272822', red: '#f92672', green: '#a6e22e', yellow: '#f4bf75',
      blue: '#66d9ef', magenta: '#ae81ff', cyan: '#a1efe4', white: '#f8f8f2',
      brightBlack: '#75715e', brightRed: '#f92672', brightGreen: '#a6e22e',
      brightYellow: '#f4bf75', brightBlue: '#66d9ef', brightMagenta: '#ae81ff',
      brightCyan: '#a1efe4', brightWhite: '#f9f8f5',
    },
  },
  cyberpunk: {
    label: 'Cyberpunk',
    theme: {
      background: '#0d0221', foreground: '#defae1', cursor: '#00ff9f',
      selectionBackground: 'rgba(0,255,159,.25)',
      black: '#14072f', red: '#ff3860', green: '#7fff00', yellow: '#ffd700',
      blue: '#00d4ff', magenta: '#ff00e6', cyan: '#00ff9f', white: '#e0e0e0',
      brightBlack: '#5c5c8a', brightRed: '#ff6b8a', brightGreen: '#b3ff66',
      brightYellow: '#ffe66d', brightBlue: '#66e6ff', brightMagenta: '#ff66f0',
      brightCyan: '#66ffc9', brightWhite: '#ffffff',
    },
  },
  default: {
    label: 'Clásico',
    theme: {
      background: '#0b0d12', foreground: '#e8e8f0', cursor: '#00e676',
      selectionBackground: 'rgba(47,128,237,.35)',
      black: '#1a1c22', red: '#ff5555', green: '#00e676', yellow: '#ffd700',
      blue: '#38bdf8', magenta: '#b464ff', cyan: '#22d3ee', white: '#e8e8f0',
      brightBlack: '#5a5f6e', brightRed: '#ff6e6e', brightGreen: '#4dff9a',
      brightYellow: '#ffe066', brightBlue: '#7cc9ff', brightMagenta: '#c990ff',
      brightCyan: '#7deaff', brightWhite: '#ffffff',
    },
  },
};

/* ── Transportes (patrón adaptador) ───────────────────────────────────
   Cualquier Transporte debe ofrecer:
     setHandlers({ onOutput, onExit, onReady, onError, onNotice, onStatus })
     send(msg)        → {type:'spawn'|'input'|'resize'|'kill'|'ping', id, ...}
     destroy() */
class WsTransport {
  constructor(url) {
    this._url = url;
    this._ws = null;
    this._handlers = {};
    this._retry = 0;
    this._closedByUser = false;
  }
  setHandlers(h) { this._handlers = h; }
  connect() {
    this._closedByUser = false;
    this._status('connecting');
    try {
      this._ws = new WebSocket(this._url);
    } catch (e) { this._status('error'); return; }
    this._ws.onopen = () => { this._retry = 0; this._status('open'); this.send({ type: 'ping', ts: Date.now() }); };
    this._ws.onmessage = (ev) => this._route(ev.data);
    this._ws.onclose = () => {
      if (this._closedByUser) { this._status('closed'); return; }
      this._status('reconnecting');
      const delay = Math.min(30000, 1000 * Math.pow(1.6, this._retry++));
      setTimeout(() => this.connect(), delay);
    };
    this._ws.onerror = () => this._ws && this._ws.close();
  }
  send(msg) { if (this._ws && this._ws.readyState === 1) this._ws.send(JSON.stringify(msg)); }
  destroy() { this._closedByUser = true; if (this._ws) try { this._ws.close(); } catch {} }
  _route(raw) {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }
    if (this._dispatch) { this._dispatch(msg); return; }
    const h = this._handlers;
    switch (msg.type) {
      case 'output': h.onOutput && h.onOutput(msg.id, msg.data); break;
      case 'exit':   h.onExit && h.onExit(msg.id, msg.code, msg.signal); break;
      case 'error':  h.onError && h.onError(msg.id, msg.message); break;
      case 'notice': h.onNotice && h.onNotice(msg.id, msg.message); break;
      case 'ready':  h.onReady && h.onReady(msg.id, msg); break;
      case 'pong':   break;
    }
  }
  _status(s) { this._handlers.onStatus && this._handlers.onStatus(s); }
}

class NativeTransport {
  /** Fase 2 (APK): habla el MISMO protocolo por el puente CodeHubNative.
      La tarjeta se muestra igual; este adaptador se encarga de mapear
      ws.send/setHandlers a window.__chNative.onTerminalMessage... */
  setHandlers(h) { this._handlers = h; }
  connect() { this._handlers.onStatus && this._handlers.onStatus('native'); }
  send(msg) {
    if (window.__chNative && window.__chNative.terminalSend) {
      try { window.__chNative.terminalSend(JSON.stringify(msg)); return; } catch {}
    }
    this._handlers.onError && this._handlers.onError(msg.id, 'Transporte nativo no disponible.');
  }
  destroy() {}
}

/* ── Estado global del módulo ──────────────────────────────────────── */
const state = {
  transport: null,
  backendOk: null,
  listeners: new Map(),      // id → { onOutput, onExit }
  sessions: new Map(),       // id → { term, fit, webgl, pane, tabBtn, title }
  tabsOrder: [],
  activeId: null,
  theme: localStorage.getItem('ch_term_theme') || 'default',
};

const el = {
  card: null, toolbar: null, tabs: null, body: null,
  themeSel: null, shellSel: null, stateDot: null, stateTxt: null, newBtn: null,
};

/* ── Utilidades DOM ────────────────────────────────────────────────── */
function $id(id) { return document.getElementById(id); }

function make(html) {
  const t = document.createElement('template');
  t.innerHTML = html.trim();
  return t.content.firstElementChild;
}

/* ── Estado/indicador de conexión ──────────────────────────────────── */
const STATE_LABEL = {
  connecting: 'Conectando…', open: 'Conectado', reconnecting: 'Reconectando…',
  closed: 'Desconectado', error: 'Error', native: 'APK · nativo',
};
function setStateStatus(s) {
  if (!el.stateDot || !el.stateTxt) return;
  el.stateTxt.textContent = s ? (STATE_LABEL[s] || s) : '';
  el.stateDot.dataset.s = s || '';
  if (!el.card) return;
  el.card.classList[!s || s === 'open' || s === 'native' ? 'remove' : 'add']('term-off');
}

/* ── Tabs (una pestaña por sesión) ─────────────────────────────────── */
function upsertTab(id, label) {
  let tabBtn = state.sessions.get(id).tabBtn;
  if (tabBtn) return tabBtn;
  const btn = make('<button class="term-tab"></button>');
  btn.textContent = label;
  btn.title = 'Cerrar ' + label + ' (×)';
  btn.addEventListener('click', () => activate(id));
  btn.addEventListener('auxclick', (e) => { if (e.button === 1) killSession(id); });
  el.tabs.appendChild(btn);
  return btn;
}

function activate(id) {
  if (!state.sessions.has(id)) return;
  state.activeId = id;
  for (const [sid, s] of state.sessions) {
    const show = sid === id;
    s.pane.style.display = show ? '' : 'none';
    s.tabBtn.classList.toggle('on', show);
    if (show) {
      requestAnimationFrame(() => { try { s.fit.fit(); s.term.focus(); } catch {} });
    }
  }
}

/* ── Crear / matar sesión ──────────────────────────────────────────── */
function spawnSession(shell) {
  const id = 't' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  const theme = THEMES[state.theme] || THEMES.default;
  const term = new Terminal({
    fontFamily: "'JetBrains Mono', monospace",
    fontSize: 13,
    lineHeight: 1.2,
    cursorBlink: true,
    cursorStyle: 'block',
    allowProposedApi: true,
    scrollback: 4000,
    theme: theme.theme,
    convertEol: true,
  });
  const fit = new FitAddon();
  term.loadAddon(fit);

  const pane = make('<div class="term-pane"></div>');
  el.body.appendChild(pane);
  term.open(pane);

  // WebGL con fallback a DOM/canvas si no hay soporte.
  if (typeof WebglAddon !== 'undefined') {
    try { term.loadAddon(new WebglAddon()); } catch { /* renderer estándar */ }
  }

  const s = { id, term, fit, pane, tabBtn: null, title: 'Sesión ' + (state.sessions.size + 1) };
  state.sessions.set(id, s);
  s.tabBtn = upsertTab(id, s.title);
  activate(id);

  term.onData((data) => state.transport.send({ type: 'input', id, data }));
  term.onResize(({ cols, rows }) => state.transport.send({ type: 'resize', id, cols, rows }));

  // Dispatcher central por-sesión (evita encadenar overrides en el transporte).
  state.transport._dispatch = (msg) => {
    const { type, id: mid, data, code, signal, message } = msg;
    const l = state.listeners.get(mid);
    if (!l) return;
    if (type === 'output' && l.onOutput) l.onOutput(data);
    else if (type === 'exit' && l.onExit) l.onExit(code, signal);
    else if (type === 'notice' && l.onNotice) l.onNotice(message);
    else if ((type === 'error' || type === 'spawn_fail') && l.onError) l.onError(message || 'Error remoto.');
  };

  state.listeners.set(id, {
    onOutput: (data) => term.write(data),
    onExit: (code, signal) => {
      term.write('\r\n\x1b[90m[proceso terminado' + (signal ? ' · ' + signal : '') + ']\x1b[0m\r\n');
      s.tabBtn.classList.add('done');
    },
    onError: (message) => term.write('\r\n\x1b[31m' + (message || 'Error') + '\x1b[0m\r\n'),
    onNotice: (message) => term.write('\r\n' + (message || '') + '\r\n'),
  });

  state.transport.send({ type: 'spawn', id, shell, cols: fit.cols || 80, rows: fit.rows || 24 });
  requestAnimationFrame(() => { try { fit.fit(); } catch {} });
  return id;
}

function killSession(id) {
  const s = state.sessions.get(id);
  if (!s) return;
  state.transport.send({ type: 'kill', id });
  state.listeners.delete(id);
  try { s.term.dispose(); } catch {}
  s.pane.remove(); s.tabBtn.remove();
  state.sessions.delete(id);
  state.tabsOrder = state.tabsOrder.filter((x) => x !== id);
  if (state.activeId === id) {
    state.activeId = state.tabsOrder[0] || null;
    if (state.activeId) activate(state.activeId);
  }
  if (!state.sessions.size) setStateStatus(state.transport ? 'open' : null);
}

/* ── Toolbar: tema y shell ─────────────────────────────────────────── */
function buildToolbar() {
  el.themeSel = make('<select class="term-sel" title="Tema"></select>');
  for (const [key, t] of Object.entries(THEMES)) {
    const opt = make('<option></option>');
    opt.value = key; opt.textContent = t.label;
    if (key === state.theme) opt.selected = true;
    el.themeSel.appendChild(opt);
  }
  el.themeSel.addEventListener('change', () => {
    state.theme = el.themeSel.value;
    localStorage.setItem('ch_term_theme', state.theme);
    const theme = THEMES[state.theme].theme;
    for (const s of state.sessions.values()) s.term.options.theme = theme;
  });

  el.shellSel = make('<select class="term-sel" title="Shell"><option value="bash">bash</option><option value="sh">sh</option></select>');
  state.shellSel = el.shellSel;

  el.newBtn = make('<button class="tb primary" title="Nueva pestaña de shell"><i class="fas fa-plus"></i> Nueva</button>');
  el.newBtn.addEventListener('click', () => spawnSession(state.shellSel.value || 'bash'));

  el.stateDot = make('<span class="term-dot" data-s=""></span>');
  el.stateTxt = make('<span class="term-state-txt"></span>');

  el.toolbar.appendChild(el.newBtn);
  el.toolbar.appendChild(el.shellSel);
  el.toolbar.appendChild(el.themeSel);
  el.toolbar.appendChild(el.stateDot);
  el.toolbar.appendChild(el.stateTxt);
}

/* ── Inicialización ────────────────────────────────────────────────── */
async function boot() {
  el.card = $id('term-card');
  if (!el.card) return;
  el.card.style.display = '';
  el.toolbar = el.card.querySelector('.term-toolbar');
  el.tabs = el.card.querySelector('.term-tabs');
  el.body = el.card.querySelector('.term-body');

  buildToolbar();

  // Elegir transporte: dentro del APK (CodeHubNative) → bridge; web → WS.
  const isNative = !!window.__chNative;
  state.transport = isNative ? new NativeTransport() : new WsTransport(WS_URL + '/ws/terminal');
  state.transport.setHandlers({
    onStatus: setStateStatus,
    onError: (id, message) => {
      if (id && state.sessions.has(id)) state.sessions.get(id).term.write('\r\n\x1b[31m' + (message || 'Error') + '\x1b[0m\r\n');
      else setStateStatus('error');
    },
    onNotice: (id, message) => {
      if (id && state.sessions.has(id)) state.sessions.get(id).term.write('\r\n' + (message || '') + '\r\n');
    },
  });

  // Verificar backend disponible (no crítico — WS reconecta solo).
  if (!isNative) fetch(BACKEND + '/api/terminal/health').then((r) => r.json()).then((d) => {
    state.backendOk = d.pty === true;
  }).catch(() => { state.backendOk = false; });

  state.transport.connect();

  // Auto-abrir la primera pestaña tras un instante (si el card está visible).
  setTimeout(() => {
    if (!state.sessions.size) spawnSession('bash');
  }, 250);

  // Refit al cambiar tamaño de ventana.
  window.addEventListener('resize', () => {
    if (state.activeId && state.sessions.has(state.activeId)) {
      try { state.sessions.get(state.activeId).fit.fit(); } catch {}
    }
  });
}

/* Exposición global (estilo optimizer.js) para onclick desde tools.html */
window.__termNew = () => state.shellSel && state.shellSel.value && spawnSession(state.shellSel.value);
window.__termKill = (id) => killSession(id);
window.__termTheme = (key) => { if (THEMES[key]) { el.themeSel.value = key; el.themeSel.dispatchEvent(new Event('change')); } };

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
else boot();