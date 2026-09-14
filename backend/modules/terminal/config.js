/**
 * config.js — Límites y política de la sandbox del Módulo Terminal
 * Módulo: Terminal · CodeHub v3
 * ─────────────────────────────────────────────────────────────────
 * Render no puede Docker-in-Docker: la aislación es a nivel proceso
 * (env mínimo sin secretos + non-root + denylist + límites duros).
 * Documentado en el README del módulo.
 */

'use strict';

/** Path del WebSocket del terminal (independiente del /ws de broadcast). */
const WS_PATH = '/ws/terminal';

/** Máximo de PTY concurrentes por conexión WS. */
const MAX_SESSIONS_PER_WS = 4;

/** Límites de rate-limit de mensajes por conexión. */
const RATE = {
  maxMessages: 120,          // ventana de:
  windowMs: 60 * 1000,       // 60s
};

/** Tamaño máximo de un chunk de input/output (bytes). */
const MAX_CHUNK_BYTES = 1024 * 8;

/** Dimensiones de la PTY (clamp). */
const COLS = { min: 20, max: 240 };
const ROWS = { min: 5, max: 120 };

/** Shells permitidas. sh = mínima superficie; bash = cómoda. */
const ALLOWED_SHELLS = ['sh', 'bash'];

/** Timeout de sesión inactiva (ms) — mata la PTY si no se teclea nada. */
const SESSION_IDLE_MS = 30 * 60 * 1000;

/** CWD por defecto dentro de la sandbox (Render: FS efímero). */
const DEFAULT_CWD = '/tmp';

/**
 * Variables admitidas en el env del hijo. NUNCA se pasa process.env completo:
 * aquí solo entran estas, y solo si el valor es string. Así ningún secreto
 * (tokens, keys, credenciales de Mongo/Supabase/Groq/...) puede filtrarse.
 */
const ALLOWED_ENV = {
  TERM: 'xterm-256color',
  COLORTERM: 'truecolor',
  LANG: 'C.UTF-8',
  LC_ALL: 'C.UTF-8',
  HOME: '/tmp',
  PATH: '/usr/local/bin:/usr/bin:/bin',
  USER: 'nobody',
  LOGNAME: 'nobody',
  SHELL: '/bin/bash',
};

/**
 * Denylist de comandos peligrosos, evaluada sobre el stream de input.
 * Es una red de seguridad COMPLEMENTARIA a la sandbox real (non-root + env
 * limpio): se aplica por chunk, así que un comando tecleado letra a letra
 * puede escapar del filtro; la protección estructural es el proceso no-root
 * sin secretos en el entorno.
 */
const DENY_PATTERNS = [
  /\brm\s+(-[a-z]*\s+)*-r\w*\s+\//,              // rm -rf /
  /\bmkfs(?:\s|\.)/,                              // mkfs / mkfs.ext4
  /\b(dd|cat|echo[^\n]*)\s[^\n]*(\/dev\/sd[a-z]|\/dev\/hd[a-z]|\/dev\/mem)/,
  /\b(shutdown|reboot|halt|poweroff)\b/,          // apagado / reinicio
  /\binit\s+0\b/,
  /:\(\)\s*\{/,                                   // fork bomb
  /\b>\/\s*\/dev\/(sd|hd)[a-z]/,                  // redirección a disco
  /\b(cat|tail|head|less|more|grep)\s+\/etc\/shadow\b/,
  /\bchmod\s+(-R\s+)?777\s+\//,                   // permisos masivos en /
  /\bchown\s+-R\s+[^\s]+\s+\//,
  /\b(sudo|doas)\b/,                              // escalada de privilegios
  /\b(curl|wget)[^\n]*\|\s*(ba)?sh\b/,            // descargar y ejecutar
  /\bfdisk\b|\b(cfdisk|parted|mkfs)\b/,
  /\b>\/dev\/null\b\s*[;|&]\s*\brm\b/,            // rm silenciado
];

/** Mensaje que se envía al usuario cuando un chunk entra en la denylist. */
const DENY_NOTICE = '⛔ Comando bloqueado por la sandbox de CodeHub.';

module.exports = {
  WS_PATH,
  MAX_SESSIONS_PER_WS,
  RATE,
  MAX_CHUNK_BYTES,
  COLS,
  ROWS,
  ALLOWED_SHELLS,
  SESSION_IDLE_MS,
  DEFAULT_CWD,
  ALLOWED_ENV,
  DENY_PATTERNS,
  DENY_NOTICE,
};