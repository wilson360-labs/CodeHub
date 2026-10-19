// ── UTILIDADES PURAS ─────────────────────────────────────────────
// Extraídas de server.js para poder testearlas sin levantar el
// servidor completo (Mongo, Redis, WebSockets, etc.). Ninguna función
// de este archivo toca red, DB ni estado global — son deterministas.

const ALLOWED_IMAGE_MIME = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/gif']);

function splitSqlStatements(sql) {
  const statements = [];
  let cur = '';
  let i = 0;
  const n = sql.length;
  let inSingle = false, inDouble = false, dollarTag = null, inLineComment = false;
  while (i < n) {
    const ch = sql[i];
    if (inLineComment) {
      cur += ch;
      if (ch === '\n') inLineComment = false;
      i++; continue;
    }
    if (dollarTag) {
      cur += ch;
      if (sql.startsWith(dollarTag, i)) {
        cur += dollarTag.slice(1);
        i += dollarTag.length;
        dollarTag = null;
        continue;
      }
      i++; continue;
    }
    if (inSingle) {
      cur += ch;
      if (ch === "'" && sql[i + 1] === "'") { cur += "'"; i += 2; continue; }
      if (ch === "'") inSingle = false;
      i++; continue;
    }
    if (inDouble) {
      cur += ch;
      if (ch === '"') inDouble = false;
      i++; continue;
    }
    if (ch === '-' && sql[i + 1] === '-') { inLineComment = true; cur += ch; i++; continue; }
    if (ch === "'") { inSingle = true; cur += ch; i++; continue; }
    if (ch === '"') { inDouble = true; cur += ch; i++; continue; }
    if (ch === '$') {
      const m = sql.slice(i).match(/^\$[a-zA-Z_]*\$/);
      if (m) { dollarTag = m[0]; cur += dollarTag; i += dollarTag.length; continue; }
    }
    if (ch === ';') { statements.push(cur); cur = ''; i++; continue; }
    cur += ch; i++;
  }
  if (cur.trim()) statements.push(cur);
  return statements.map(s => s.trim()).filter(Boolean);
}

const IPV4_RE = /^\d{1,3}(\.\d{1,3}){3}$/;
const IPV6_RE = /^[0-9a-f:]+$/i;

// IP privada / no enrutable (RFC1918, loopback, link-local, ULA, vacía).
function isPrivateIp(ip) {
  const s = String(ip || '').trim();
  if (!s || s === 'unknown' || s === 'localhost' || s === '::1') return true;
  if (/^127\./.test(s)) return true;
  if (/^10\./.test(s)) return true;
  if (/^192\.168\./.test(s)) return true;
  if (/^172\.(1[6-9]|2\d|3[01])\./.test(s)) return true;
  if (/^169\.254\./.test(s)) return true;
  if (/^f[cd][0-9a-f]{2}:/i.test(s) || /^fe80:/i.test(s)) return true;
  return false;
}

// IP del cliente. Prioriza la IP PÚBLICA más a la DERECHA de la cadena de
// proxies: Render (como la mayoría de PaaS) termina el TLS y añade su origen
// a la derecha de X-Forwarded-For, mientras que lo que inyecte el cliente
// queda a la izquierda. Con `trust proxy: 1` req.ip cae en la dirección
// interna (10.x) de Render, así que se descarta. Recorrer de derecha a
// izquierda evita el spoofing de los límites por IP. Solo se aceptan IPs
// reales: x-forwarded-for/x-real-ip nunca se usan como texto arbitrario.
function clientIp(req) {
  const norm = (c) => String(c).replace(/^::ffff:/, '').trim();
  const isIp = (c) => c && c !== 'unknown' && c !== '?' && (IPV4_RE.test(c) || IPV6_RE.test(c));

  const xff = String(req?.headers?.['x-forwarded-for'] || '')
    .split(',').map((s) => s.trim()).filter(Boolean);

  // Cadena controlada por el proxy: req.ip (derivado de XFF) + XFF.
  const chain = [];
  if (req?.ip) chain.push(req.ip);
  chain.push(...xff);
  const chainIps = chain.map(norm).filter(isIp);
  for (let i = chainIps.length - 1; i >= 0; i--) {
    if (!isPrivateIp(chainIps[i])) return chainIps[i];
  }

  // Respaldo: cabeceras/socket que no controla el proxy.
  const tail = [req?.headers?.['x-real-ip'], req?.socket?.remoteAddress]
    .map(norm).filter(isIp);
  for (const c of tail) if (!isPrivateIp(c)) return c;

  // Sin IP pública (red local/dev): primera válida para no quedarnos en 'unknown'.
  return chainIps[0] || tail[0] || 'unknown';
}

function truncate(text, max = 400) {
  if (!text) return '';
  const clean = String(text).replace(/\r\n/g, '\n').trim();
  return clean.length > max ? clean.slice(0, max).trim() + '…' : clean;
}

function parseImageDataUrl(dataUrl) {
  if (typeof dataUrl !== 'string') return null;
  const m = dataUrl.match(/^data:(image\/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=]+)$/);
  if (!m) return null;
  const mimeType = m[1].toLowerCase();
  if (!ALLOWED_IMAGE_MIME.has(mimeType)) return null;
  // ~4MB de imagen en base64 pesa ~5.5MB de texto; ponemos un techo razonable
  if (m[2].length > 6_000_000) return null;
  return { mimeType, data: m[2] };
}

module.exports = { splitSqlStatements, clientIp, isPrivateIp, truncate, parseImageDataUrl, ALLOWED_IMAGE_MIME };
