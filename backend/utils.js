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

// IP del cliente priorizando req.ip (Express con trust proxy 1) y validando
// que el valor sea una IP real. x-real-ip / x-forwarded-for se aceptan solo
// como cadena de reenvío y nunca texto arbitrario (evita spoofing).
function clientIp(req) {
  const candidates = [];
  if (req?.ip) candidates.push(req.ip);
  if (req?.headers) {
    const xff = (req.headers['x-forwarded-for'] || '').split(',');
    for (const entry of xff) candidates.push(entry.trim());
    if (req.headers['x-real-ip']) candidates.push(String(req.headers['x-real-ip']).trim());
  }
  if (req?.socket?.remoteAddress) candidates.push(req.socket.remoteAddress);
  for (const c of candidates) {
    const clean = String(c).replace(/^::ffff:/, '').trim();
    if (!clean || clean === 'unknown' || clean === '?') continue;
    if (IPV4_RE.test(clean) || IPV6_RE.test(clean)) return clean;
  }
  return 'unknown';
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

module.exports = { splitSqlStatements, clientIp, truncate, parseImageDataUrl, ALLOWED_IMAGE_MIME };
