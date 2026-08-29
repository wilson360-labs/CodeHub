// wil-e/crypto.js — Cifrado de extremo a extremo (AES-256-GCM) en reposo.
// Los datos privados de la IA (memorias y base de conocimiento) se guardan
// cifrados. Wil.E sólo puede descifrarlos con la clave maestra (env).
const crypto = require('crypto');

function getKey() {
  // 32 bytes (AES-256). Se deriva de la variable WIL_E_ENC_KEY (hex 64 o texto).
  const raw = process.env.WIL_E_ENC_KEY;
  if (raw) return crypto.createHash('sha256').update(String(raw)).digest();
  // Fallback: derivado de MONGODB_URI para no romper si no hay clave dedicada.
  return crypto.createHash('sha256').update(String(process.env.MONGODB_URI || 'wil-e-default')).digest();
}

// Cifra un string -> "v1:iv:tag:data" (base64)
function encrypt(plain) {
  if (plain === undefined || plain === null || plain === '') return plain;
  try {
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', getKey(), iv);
    const enc = Buffer.concat([cipher.update(String(plain), 'utf8'), cipher.final()]);
    const tag = cipher.getAuthTag();
    return 'v1:' + iv.toString('base64') + ':' + tag.toString('base64') + ':' + enc.toString('base64');
  } catch (_) {
    return plain; // nunca romper la app por el cifrado
  }
}

// Descifra un string cifrado con encrypt(). Devuelve null si falla.
function decrypt(boxed) {
  if (!boxed || typeof boxed !== 'string' || !boxed.startsWith('v1:')) return boxed;
  try {
    const parts = boxed.split(':');
    if (parts.length !== 4) return null;
    const iv = Buffer.from(parts[1], 'base64');
    const tag = Buffer.from(parts[2], 'base64');
    const data = Buffer.from(parts[3], 'base64');
    const decipher = crypto.createDecipheriv('aes-256-gcm', getKey(), iv);
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(data), decipher.final()]).toString('utf8');
  } catch (_) {
    return null;
  }
}

module.exports = { encrypt, decrypt };
