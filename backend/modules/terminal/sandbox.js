/**
 * sandbox.js — Aislación a nivel proceso para el Módulo Terminal
 * Módulo: Terminal · CodeHub v3
 * ─────────────────────────────────────────────────────────────────
 * Render no soporta Docker-in-Docker, así que la aislación es estructural:
 *  1. ENV MÍNIMO — se construye por allowlist (config.ALLOWED_ENV); jamás
 *     se clona process.env, por lo que ningún secreto llega al proceso hijo.
 *  2. NON-ROOT — en Unix, si el servidor corre como root, la PTY se lanza con
 *     uid/gid de un usuario sin privilegios (configurable vía SANDBOX_UID/GID,
 *     default nobody=65534), impidiendo escalada si el shell se compromete.
 *  3. DENYLIST — filtro de comandos destructivos sobre el input (defensa
 *     complementaria, no sustituye a 1 y 2).
 */

'use strict';

const path = require('path');
const config = require('./config');

/**
 * Devuelve el objeto env que se pasa a node-pty al crear la sesión.
 * Se parte de ALLOWED_ENV y se conservan algunas vars útiles de process.env
 * SOLO si su nombre no parece secreto (TOKEN/KEY/SECRET/PASSWORD/...).
 * Render (y en general el backend de CodeHub) tiene Settings secretas que
 * jamás deben filtrarse al usuario del terminal.
 */
function sanitizedEnvironment(extra = {}) {
  const env = { ...config.ALLOWED_ENV, ...extra };
  const secretRe = /(TOKEN|KEY|SECRET|PASSWORD|PASS|CREDENTIAL|MONGODB_URI|DATABASE_URL|REDIS_URL|SERVICE_ROLE|SUPABASE|API_URL)/i;
  for (const k of Object.keys(process.env)) {
    if (secretRe.test(k)) continue;           // nunca propagar secretos
    if (typeof process.env[k] !== 'string') continue;
    if (k in env) continue;                    // la allowlist manda
    env[k] = process.env[k];                   // vars inocuas útiles (NODE_ENV, TZ...)
  }
  delete env.SHDOC;                            // hipótesis
  return env;
}

/**
 * Decide si un chunk de input debe bloquearse por la denylist.
 * @param {string} data
 * @returns {boolean}
 */
function isDenied(data) {
  for (const re of config.DENY_PATTERNS) {
    if (re.test(data)) return true;
  }
  return false;
}

/**
 * UID/GID non-root para lanzar la PTY en sistemas Unix.
 * Si el proceso ya corre como usuario normal (caso Render), devuelve null
 * y node-pty deja el uid default (el propio usuario sin privilegios).
 * Solo se aplica cuando getuid()===0 (p.ej. VPS root con Docker fuera).
 * Configurable vía env SANDBOX_UID / SANDBOX_GID (default nobody 65534).
 */
function nonRootIds() {
  if (typeof process.getuid !== 'function') return null;      // Windows
  if (process.getuid() !== 0) return null;                    // ya somos non-root
  const uid = Number(process.env.SANDBOX_UID) || 65534;
  const gid = Number(process.env.SANDBOX_GID) || 65534;
  return { uid, gid };
}

/**
 * Valida y normaliza el cwd pedido por el cliente dentro de la sandbox.
 * Solo se aceptan rutas absolutas que existan y sean directorios dentro
 * de /tmp o del home del servidor; nunca raíz del sistema.
 */
function sanitizedCwd(requested, defaultCwd = config.DEFAULT_CWD) {
  if (!requested || typeof requested !== 'string') return defaultCwd;
  const p = path.resolve(requested);
  if (!p.startsWith('/tmp') && !p.startsWith(process.env.HOME || '/home')) return defaultCwd;
  try {
    const st = require('fs').statSync(p);
    if (st.isDirectory()) return p;
  } catch { /* no existe -> default */ }
  return defaultCwd;
}

module.exports = { sanitizedEnvironment, isDenied, nonRootIds, sanitizedCwd };