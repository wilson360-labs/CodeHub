'use strict';
/**
 * extensions/catalog.js — Catálogo privado de extensiones de Echo.
 *
 * Lee la lista del ORIGEN (privado), la normaliza y expone:
 *   · toPublic(item)  → metadatos SIN el enlace real; `updateUrl` apunta al
 *                       proxy de CodeHub.
 *   · resolveAsset()  → resuelve en el servidor el .eapk más reciente del
 *                       origen (GitHub / GitLab / archivo directo).
 *
 * El dispositivo nunca ve el repo de origen ni el asset real.
 */

const cfg = require('./config');

const GH_RE = /^https:\/\/api\.github\.com\/repos\/([^/]+)\/([^/]+)\//;
const ABI_ORDER = ['arm64-v8a', 'armeabi-v7a', 'x86_64', 'x86'];

let _catalog = null; // { at, items: Map<id, item> }
let _catalogPending = null;
const _assets = new Map(); // id -> { at, asset }

function isAllowed(id) {
  if (cfg.BLOCKLIST.includes(id)) return false;
  if (cfg.ALLOWLIST.length && !cfg.ALLOWLIST.includes(id)) return false;
  return true;
}

function outboundHeaders(url) {
  const headers = {
    'User-Agent': 'CodeHub-Extensions/1.0 (+https://codehub-98s6.onrender.com)',
    Accept: 'application/json',
  };
  if (/^https:\/\/api\.github\.com\//.test(url)) {
    headers.Accept = 'application/vnd.github+json';
    if (cfg.GITHUB_TOKEN) headers.Authorization = `Bearer ${cfg.GITHUB_TOKEN}`;
  }
  return headers;
}

async function fetchWithTimeout(url, acceptBinary, timeoutMs) {
  const ctl = new AbortController();
  const timer = setTimeout(() => ctl.abort(), timeoutMs || cfg.FETCH_TIMEOUT_MS);
  try {
    return await fetch(url, {
      headers: outboundHeaders(url),
      signal: ctl.signal,
      redirect: 'follow',
    });
  } finally {
    clearTimeout(timer);
  }
}

async function fetchJson(url) {
  const res = await fetchWithTimeout(url, false, cfg.FETCH_TIMEOUT_MS);
  if (!res.ok) throw new Error(`origen HTTP ${res.status}`);
  return res.json();
}

function baseName(url) {
  try {
    const clean = String(url).split('#')[0].split('?')[0];
    return clean.substring(clean.lastIndexOf('/') + 1) || 'extension.eapk';
  } catch {
    return 'extension.eapk';
  }
}

function buildCatalog() {
  return (async () => {
    const data = await fetchJson(cfg.SOURCE_URL);
    if (!Array.isArray(data)) throw new Error('catálogo con formato inválido');
    const items = new Map();
    for (const raw of data) {
      if (!raw || typeof raw !== 'object') continue;
      const id = String(raw.id || '').trim();
      const originUpdateUrl = String(raw.updateUrl || '').trim();
      if (!id || !originUpdateUrl) continue;
      if (!isAllowed(id)) continue;
      items.set(id, {
        id,
        type: raw.type != null ? String(raw.type) : undefined,
        name: raw.name != null ? String(raw.name) : id,
        subtitle: raw.subtitle != null ? String(raw.subtitle) : undefined,
        iconUrl: raw.iconUrl != null ? String(raw.iconUrl) : undefined,
        // Campos privados (NUNCA se serializan al cliente):
        originUpdateUrl,
        serviceUrl: raw.url != null ? String(raw.url) : undefined,
      });
    }
    return items;
  })();
}

async function loadCatalog(force = false) {
  if (!force && _catalog && Date.now() - _catalog.at < cfg.CACHE_TTL_MS) return _catalog.items;
  if (!_catalogPending) {
    _catalogPending = buildCatalog()
      .then((items) => {
        _catalog = { at: Date.now(), items };
        return items;
      })
      .finally(() => {
        _catalogPending = null;
      });
  }
  return _catalogPending;
}

function toPublic(item) {
  const out = {
    id: item.id,
    name: item.name,
    // El cliente reemplaza este updateUrl por el del proxy: el real no viaja.
    updateUrl: `${cfg.BACKEND_URL}/api/extensions/pkg/${encodeURIComponent(item.id)}`,
  };
  if (item.type != null) out.type = item.type;
  if (item.subtitle != null) out.subtitle = item.subtitle;
  if (item.iconUrl != null) out.iconUrl = item.iconUrl;
  return out;
}

function pickAsset(assets) {
  const apks = (Array.isArray(assets) ? assets : []).filter(
    (a) => a && typeof a.name === 'string' && /apk$/i.test(a.name) && a.browser_download_url
  );
  if (!apks.length) return null;
  for (const abi of ABI_ORDER) {
    const hit = apks.find((a) => a.name.includes(abi));
    if (hit) return hit;
  }
  return apks[0];
}

async function resolveGitHub(originUrl) {
  const m = GH_RE.exec(originUrl);
  if (!m) return null;
  const url = `https://api.github.com/repos/${m[1]}/${m[2]}/releases/latest`;
  const rel = await fetchJson(url);
  const asset = pickAsset(rel && rel.assets);
  if (!asset) return null;
  return {
    url: String(asset.browser_download_url),
    version: String((rel && rel.tag_name) || ''),
    name: String(asset.name || baseName(asset.browser_download_url)),
  };
}

function findApkUrlDeep(node, depth) {
  const d = depth || 0;
  if (node == null || d > 6) return null;
  if (typeof node === 'string') return /\.apk(k)?(\?|$)/i.test(node) ? node : null;
  if (Array.isArray(node)) {
    for (const v of node) {
      const hit = findApkUrlDeep(v, d + 1);
      if (hit) return hit;
    }
    return null;
  }
  if (typeof node === 'object') {
    for (const v of Object.values(node)) {
      const hit = findApkUrlDeep(v, d + 1);
      if (hit) return hit;
    }
  }
  return null;
}

async function resolveGeneric(originUrl) {
  if (/\.apk(k)?(\?|$)/i.test(originUrl)) {
    return { url: originUrl, version: '', name: baseName(originUrl) };
  }
  const data = await fetchJson(originUrl);
  const url = findApkUrlDeep(data, 0);
  if (!url) return null;
  const version = String((data && (data.tag_name || data.tagName || data.name)) || '');
  return { url, version, name: baseName(url) };
}

async function resolveAsset(item, force = false) {
  const cached = _assets.get(item.id);
  if (!force && cached && Date.now() - cached.at < cfg.ASSET_TTL_MS) return cached.asset;

  const asset = GH_RE.test(item.originUpdateUrl)
    ? await resolveGitHub(item.originUpdateUrl)
    : await resolveGeneric(item.originUpdateUrl);

  if (asset) _assets.set(item.id, { at: Date.now(), asset });
  return asset;
}

/** Mismo criterio que Echo: hay actualización si la etiqueta cambió. */
function hasUpdate(asset, from) {
  if (!asset) return false;
  if (!from) return true;
  return asset.version !== from;
}

async function fetchBinary(url) {
  const res = await fetchWithTimeout(url, true, cfg.FETCH_TIMEOUT_MS * 3);
  if (!res.ok) throw new Error(`asset HTTP ${res.status}`);
  const declared = Number(res.headers.get('content-length') || 0);
  if (declared && declared > cfg.MAX_BYTES) throw new Error('asset demasiado grande');
  const buf = Buffer.from(await res.arrayBuffer());
  if (buf.length > cfg.MAX_BYTES) throw new Error('asset demasiado grande');
  return buf;
}

module.exports = {
  loadCatalog,
  toPublic,
  resolveAsset,
  hasUpdate,
  fetchBinary,
  baseName,
};
