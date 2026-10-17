'use strict';
/**
 * extensions/config.js — Config del módulo de extensiones de Echo.
 *
 * Todo lo que apunta al ORIGEN es privado y vive solo en el servidor:
 * el dispositivo nunca recibe la URL real (ni el repo de GitHub ni el
 * asset de descarga). Solo habla con CodeHub.
 *
 * Variables de entorno (todas opcionales):
 *   ECHO_EXTENSIONS_SOURCE_URL     JSON con la lista de extensiones.
 *   ECHO_EXTENSIONS_ALLOWLIST      ids permitidos (coma). Vacío = todos.
 *   ECHO_EXTENSIONS_BLOCKLIST      ids vetados (coma).
 *   ECHO_EXTENSIONS_CACHE_TTL_MS   TTL del catálogo (def. 6h).
 *   ECHO_EXTENSIONS_ASSET_TTL_MS   TTL de la resolución del .eapk (def. 30min).
 *   ECHO_EXTENSIONS_MAX_BYTES      Tamaño máximo a reenviar (def. 80 MB).
 *   ECHO_EXTENSIONS_FETCH_TIMEOUT_MS  Timeout saliente (def. 20s).
 *   BACKEND_URL                    URL pública del backend (para armar el proxy).
 *   GITHUB_TOKEN / GH_TOKEN        Opcional, sube el límite de la API de GitHub.
 */

function num(value, fallback) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? n : fallback;
}

function list(value) {
  return String(value || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

const cfg = {
  SOURCE_URL:
    process.env.ECHO_EXTENSIONS_SOURCE_URL ||
    'https://raw.githubusercontent.com/wilson360-labs/CodeHub/main/backend/data/codebeat-extensions.json',

  BACKEND_URL: (process.env.BACKEND_URL || 'https://codehub-98s6.onrender.com').replace(/\/+$/, ''),

  ALLOWLIST: list(process.env.ECHO_EXTENSIONS_ALLOWLIST),
  BLOCKLIST: list(process.env.ECHO_EXTENSIONS_BLOCKLIST),

  CACHE_TTL_MS: num(process.env.ECHO_EXTENSIONS_CACHE_TTL_MS, 6 * 60 * 60 * 1000),
  ASSET_TTL_MS: num(process.env.ECHO_EXTENSIONS_ASSET_TTL_MS, 30 * 60 * 1000),
  MAX_BYTES: num(process.env.ECHO_EXTENSIONS_MAX_BYTES, 80 * 1024 * 1024),
  // Los iconos tambien se reenvian desde CodeHub: si el icono del origen vive
  // en un repo (p.ej. raw.githubusercontent) el dispositivo no debe verlo.
  MAX_ICON_BYTES: num(process.env.ECHO_EXTENSIONS_MAX_ICON_BYTES, 512 * 1024),
  FETCH_TIMEOUT_MS: num(process.env.ECHO_EXTENSIONS_FETCH_TIMEOUT_MS, 20000),

  GITHUB_TOKEN: process.env.GITHUB_TOKEN || process.env.GH_TOKEN || '',
};

module.exports = cfg;
