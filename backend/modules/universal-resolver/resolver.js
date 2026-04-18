/**
 * resolver.js — Motor Heurístico de Resolución Universal de URLs
 * Módulo: Universal Resolver · CodeHub v3
 * ─────────────────────────────────────────────────────────────────
 * Capacidades:
 *   ✅ Seguimiento de redirecciones HTTP 301/302/307/308
 *   ✅ Detección de meta-refresh en HTML
 *   ✅ Detección de window.location JS (heurístico)
 *   ✅ Protección anti-SSRF (bloqueo de IPs privadas/reservadas)
 *   ✅ Límite de saltos configurable (default 20)
 *   ✅ Timeout por salto y timeout total
 *   ✅ User-Agent rotativo para evitar bloqueos
 */

'use strict';

const crypto = require('crypto');
const dns    = require('dns').promises;
const { URL } = require('url');

// ── Configuración ──────────────────────────────────────────────
const CONFIG = {
  MAX_HOPS:        20,
  TIMEOUT_PER_HOP: 8_000,   // ms por petición individual
  TIMEOUT_TOTAL:   25_000,  // ms límite absoluto de la cadena
  MAX_BODY_BYTES:  128_000, // 128 KB para parsear meta-refresh
  CACHE_TTL_MS:    7 * 24 * 60 * 60 * 1000,  // 7 días
  ALLOWED_PROTOCOLS: ['http:', 'https:'],
};

// User-Agents rotativos para minimizar bloqueos
const USER_AGENTS = [
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36',
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 14_4) AppleWebKit/605.1.15 Safari/605.1.15',
  'Mozilla/5.0 (X11; Linux x86_64; rv:124.0) Gecko/20100101 Firefox/124.0',
];

// CIDRs privadas/reservadas para bloqueo SSRF
const PRIVATE_CIDRS = [
  { prefix: '10.',         len: 3  },
  { prefix: '172.16.',     len: 7  },
  { prefix: '172.17.',     len: 7  },
  { prefix: '172.18.',     len: 7  },
  { prefix: '172.19.',     len: 7  },
  { prefix: '172.20.',     len: 7  },
  { prefix: '172.21.',     len: 7  },
  { prefix: '172.22.',     len: 7  },
  { prefix: '172.23.',     len: 7  },
  { prefix: '172.24.',     len: 7  },
  { prefix: '172.25.',     len: 7  },
  { prefix: '172.26.',     len: 7  },
  { prefix: '172.27.',     len: 7  },
  { prefix: '172.28.',     len: 7  },
  { prefix: '172.29.',     len: 7  },
  { prefix: '172.30.',     len: 7  },
  { prefix: '172.31.',     len: 7  },
  { prefix: '192.168.',    len: 8  },
  { prefix: '127.',        len: 4  },
  { prefix: '0.',          len: 2  },
  { prefix: '169.254.',    len: 8  },  // link-local
  { prefix: '100.64.',     len: 7  },  // CGNAT
  { prefix: '::1',         len: 3  },  // IPv6 loopback
  { prefix: 'fc',          len: 2  },  // IPv6 ULA
  { prefix: 'fd',          len: 2  },  // IPv6 ULA
];

// ─────────────────────────────────────────────────────────────────
//  UTILIDADES
// ─────────────────────────────────────────────────────────────────

/**
 * Genera el hash SHA-256 de una URL normalizada.
 * @param {string} url
 * @returns {string} hex
 */
function hashUrl(url) {
  return crypto
    .createHash('sha256')
    .update(url.trim().toLowerCase())
    .digest('hex');
}

/**
 * Valida que la URL tenga protocolo permitido y no apunte a recursos internos.
 * Bloquea patrones SSRF: IPs privadas, localhost, metadata de cloud, etc.
 * @param {string} rawUrl
 * @throws {Error} si la URL es inválida o sospechosa
 * @returns {URL}
 */
async function validateUrlSafety(rawUrl) {
  let parsed;
  try {
    parsed = new URL(rawUrl);
  } catch {
    throw new Error('URL malformada — no se pudo parsear.');
  }

  // Protocolo permitido
  if (!CONFIG.ALLOWED_PROTOCOLS.includes(parsed.protocol)) {
    throw new Error(`Protocolo no permitido: ${parsed.protocol}`);
  }

  const hostname = parsed.hostname.toLowerCase().trim();

  // Bloqueos literales
  const BLOCKED_HOSTS = [
    'localhost',
    '0.0.0.0',
    'metadata.google.internal',
    '169.254.169.254',  // AWS/GCP metadata
    'metadata.azure.com',
  ];
  if (BLOCKED_HOSTS.includes(hostname)) {
    throw new Error(`Host bloqueado por política SSRF: ${hostname}`);
  }

  // Intentar resolver hostname y validar IPs resultantes
  let addresses = [];
  try {
    const resolved = await dns.lookup(hostname, { all: true, family: 0 });
    addresses = resolved.map(r => r.address);
  } catch {
    throw new Error(`No se pudo resolver el hostname: ${hostname}`);
  }

  for (const ip of addresses) {
    for (const cidr of PRIVATE_CIDRS) {
      if (ip.startsWith(cidr.prefix)) {
        throw new Error(`IP interna bloqueada (SSRF): ${ip} → ${hostname}`);
      }
    }
  }

  return parsed;
}

/**
 * Extrae la URL de redirección desde una etiqueta <meta http-equiv="refresh">.
 * Ejemplo: <meta http-equiv="refresh" content="0; url=https://destino.com">
 * @param {string} html
 * @param {string} baseUrl — para resolver URLs relativas
 * @returns {string|null}
 */
function extractMetaRefresh(html, baseUrl) {
  // Patrón amplio para capturar variantes de comillas y espacios
  const patterns = [
    /<meta[^>]+http-equiv\s*=\s*["']?refresh["']?[^>]+content\s*=\s*["']?\d+\s*;\s*url\s*=\s*([^"'\s>]+)/i,
    /<meta[^>]+content\s*=\s*["']?\d+\s*;\s*url\s*=\s*["']?([^"'\s>]+)["']?[^>]+http-equiv\s*=\s*["']?refresh["']?/i,
  ];

  for (const pattern of patterns) {
    const match = html.match(pattern);
    if (match && match[1]) {
      const rawTarget = match[1].replace(/['"]/g, '').trim();
      try {
        return new URL(rawTarget, baseUrl).href;
      } catch {
        return null;
      }
    }
  }
  return null;
}

/**
 * Extrae una redirección JavaScript heurística (window.location).
 * Solo detecta patrones simples; no ejecuta JS.
 * @param {string} html
 * @param {string} baseUrl
 * @returns {string|null}
 */
function extractJsRedirect(html, baseUrl) {
  const jsPatterns = [
    /window\.location(?:\.href)?\s*=\s*["']([^"']+)["']/i,
    /window\.location\.replace\s*\(\s*["']([^"']+)["']\s*\)/i,
    /location\.href\s*=\s*["']([^"']+)["']/i,
  ];

  for (const pattern of jsPatterns) {
    const match = html.match(pattern);
    if (match && match[1]) {
      try {
        return new URL(match[1], baseUrl).href;
      } catch {
        return null;
      }
    }
  }
  return null;
}

/**
 * Realiza una petición HEAD (con fallback a GET) siguiendo redirecciones manualmente.
 * Devuelve el status final y el encabezado Location si aplica.
 *
 * @param {string} url
 * @param {AbortSignal} signal
 * @returns {Promise<{status: number, location: string|null, body: string|null, finalUrl: string}>}
 */
async function fetchStep(url, signal) {
  const ua = USER_AGENTS[Math.floor(Math.random() * USER_AGENTS.length)];
  const headers = {
    'User-Agent':      ua,
    'Accept':          'text/html,application/xhtml+xml,*/*;q=0.9',
    'Accept-Language': 'es-GT,es;q=0.9,en;q=0.8',
    'Cache-Control':   'no-cache',
  };

  // Intentar HEAD primero (más liviano)
  try {
    const res = await fetch(url, {
      method:   'HEAD',
      redirect: 'manual',   // No seguir — lo hacemos nosotros
      headers,
      signal,
    });

    const location = res.headers.get('location');
    if ([301, 302, 303, 307, 308].includes(res.status) && location) {
      return { status: res.status, location, body: null, finalUrl: url };
    }
    if (res.status === 200) {
      return { status: 200, location: null, body: null, finalUrl: url };
    }
    // HEAD puede fallar en algunos servidores — caer a GET
  } catch {
    // HEAD no soportado, intentar GET
  }

  // GET con lectura parcial del body (para meta-refresh / js redirect)
  const res = await fetch(url, {
    method:   'GET',
    redirect: 'manual',
    headers,
    signal,
  });

  const location = res.headers.get('location');
  let body = null;

  // Leer body solo si necesitamos buscar meta-refresh
  if (res.status === 200) {
    try {
      const reader  = res.body.getReader();
      const chunks  = [];
      let   total   = 0;
      while (total < CONFIG.MAX_BODY_BYTES) {
        const { done, value } = await reader.read();
        if (done) break;
        chunks.push(value);
        total += value.length;
      }
      reader.cancel();
      body = Buffer.concat(chunks.map(c => Buffer.from(c))).toString('utf8', 0, CONFIG.MAX_BODY_BYTES);
    } catch {
      body = null;
    }
  }

  return { status: res.status, location, body, finalUrl: url };
}

// ─────────────────────────────────────────────────────────────────
//  FUNCIÓN PRINCIPAL
// ─────────────────────────────────────────────────────────────────

/**
 * Resuelve una URL corta/redireccionadora hasta su destino final.
 *
 * @param {string} inputUrl — URL a resolver (puede ser un acortador)
 * @param {string} [requesterIp] — IP del solicitante (para logs)
 * @returns {Promise<ResolverResult>}
 *
 * @typedef {Object} ResolverResult
 * @property {string}   original_short_url  — URL de entrada normalizada
 * @property {string}   final_resolved_url  — URL final tras todos los saltos
 * @property {number}   hops_count          — Número de saltos detectados
 * @property {string[]} hops_chain          — Cadena de URLs intermedias
 * @property {string}   resolution_method   — Método usado en el último salto
 * @property {number}   status_code         — Código HTTP final
 * @property {string}   hash                — SHA-256 de original_short_url
 * @property {boolean}  is_safe             — Pasó la validación SSRF
 * @property {string}   resolved_by_ip      — IP que disparó la resolución
 */
async function resolve(inputUrl, requesterIp = null) {
  // 1. Validación SSRF de la URL original
  const safeParsed = await validateUrlSafety(inputUrl);
  const normalizedInput = safeParsed.href;

  const totalAbort = new AbortController();
  const totalTimer = setTimeout(() => totalAbort.abort(), CONFIG.TIMEOUT_TOTAL);

  const hopsChain   = [];
  let   currentUrl  = normalizedInput;
  let   hopsCount   = 0;
  let   finalStatus = 200;
  let   lastMethod  = 'direct';

  try {
    while (hopsCount <= CONFIG.MAX_HOPS) {
      // Timeout individual por salto
      const hopAbort = new AbortController();
      const hopTimer = setTimeout(() => hopAbort.abort(), CONFIG.TIMEOUT_PER_HOP);

      // Combinar señales (total + por salto)
      const signal = hopAbort.signal;
      totalAbort.signal.addEventListener('abort', () => hopAbort.abort(), { once: true });

      let stepResult;
      try {
        stepResult = await fetchStep(currentUrl, signal);
      } finally {
        clearTimeout(hopTimer);
      }

      const { status, location, body } = stepResult;
      finalStatus = status;

      // ── Redirección HTTP ──────────────────────────────────────
      if ([301, 302, 303, 307, 308].includes(status) && location) {
        let nextUrl;
        try {
          nextUrl = new URL(location, currentUrl).href;
        } catch {
          // Location inválida — detener
          break;
        }

        // Validar SSRF del destino de la redirección también
        try {
          await validateUrlSafety(nextUrl);
        } catch (ssrfErr) {
          throw new Error(`SSRF detectado en redirección: ${ssrfErr.message}`);
        }

        hopsChain.push(currentUrl);
        currentUrl = nextUrl;
        hopsCount++;
        lastMethod = 'http_redirect';
        continue;
      }

      // ── Respuesta 200 — buscar meta-refresh y JS redirects ────
      if (status === 200 && body) {
        const metaUrl = extractMetaRefresh(body, currentUrl);
        if (metaUrl) {
          try {
            await validateUrlSafety(metaUrl);
          } catch (ssrfErr) {
            throw new Error(`SSRF en meta-refresh: ${ssrfErr.message}`);
          }
          hopsChain.push(currentUrl);
          currentUrl = metaUrl;
          hopsCount++;
          lastMethod = 'meta_refresh';
          continue;
        }

        // Heurística JS (solo si no hay meta-refresh)
        const jsUrl = extractJsRedirect(body, currentUrl);
        if (jsUrl) {
          try {
            await validateUrlSafety(jsUrl);
          } catch {
            // Si la URL JS no es segura, la ignoramos y detenemos
            break;
          }
          hopsChain.push(currentUrl);
          currentUrl = jsUrl;
          hopsCount++;
          lastMethod = 'js_redirect';
          continue;
        }
      }

      // ── Sin más redirecciones — URL final encontrada ──────────
      if (hopsCount === 0) lastMethod = 'direct';
      break;
    }
  } finally {
    clearTimeout(totalTimer);
  }

  return {
    hash:               hashUrl(normalizedInput),
    original_short_url: normalizedInput,
    final_resolved_url: currentUrl,
    hops_count:         hopsCount,
    hops_chain:         hopsChain,
    resolution_method:  lastMethod,
    status_code:        finalStatus,
    is_safe:            true,
    resolved_by_ip:     requesterIp,
  };
}

module.exports = { resolve, hashUrl, validateUrlSafety };
