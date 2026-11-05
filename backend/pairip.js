'use strict';
/**
 * Analizador de PairIP para APKs — sin dependencias externas.
 *
 * PairIP es el SDK de Google que algunos APK usan para:
 *   · exigir que la app se instaló desde Google Play ("Get this app from Play"),
 *   · validar la firma del APK,
 *   · detectar depuradores/Frida.
 * Se apoya en `libpairpcore.so` (nativo) y en clases `com.pairip.*` (DEX).
 *
 * Este módulo SOLO analiza: no modifica el APK. El bypass real es en runtime
 * (módulo LSPosed), ver docs/PAIRIP-FIX.md.
 *
 * Implementación ZIP + AXML propias para no añadir dependencias al backend.
 */

const zlib = require('zlib');
const crypto = require('crypto');

const EOCD_SIG = 0x06054b50;
const CEN_SIG = 0x02014b50;
const LOC_SIG = 0x04034b50;

const PAIRIP_SO = 'libpairpcore.so';
const PAIRIP_MARKERS = [
  'com/pairip/VMRunner',
  'Lcom/pairip/VMRunner;',
  'com/pairip/licensecheck/LicenseActivity',
  'com/pairip/licensecheck/LicenseClient',
  'com/pairip/licensecheck3/LicenseClientV3',
  'com/pairip/SignatureCheck',
];

/** Namespaces que NO son el paquete de la app (heurística). */
const FOREIGN_NAMESPACES = [
  'android.', 'androidx.', 'com.android.', 'com.google.android.',
  'java.', 'javax.', 'kotlin.', 'dalvik.', 'sun.', 'org.w3c.',
];

// ── ZIP ────────────────────────────────────────────────────────────

function findEndOfCentralDirectory(buf) {
  const min = Math.max(0, buf.length - 22 - 0xffff);
  for (let i = buf.length - 22; i >= min; i--) {
    if (buf.readUInt32LE(i) === EOCD_SIG) return i;
  }
  return -1;
}

function readCentralDirectory(buf) {
  const eocd = findEndOfCentralDirectory(buf);
  if (eocd < 0) throw new Error('No parece un ZIP/APK válido (sin EOCD)');
  const count = buf.readUInt16LE(eocd + 10);
  let offset = buf.readUInt32LE(eocd + 16);
  const entries = [];
  for (let i = 0; i < count && offset + 46 <= buf.length; i++) {
    if (buf.readUInt32LE(offset) !== CEN_SIG) break;
    const method = buf.readUInt16LE(offset + 10);
    const compressedSize = buf.readUInt32LE(offset + 20);
    const size = buf.readUInt32LE(offset + 24);
    const nameLen = buf.readUInt16LE(offset + 28);
    const extraLen = buf.readUInt16LE(offset + 30);
    const commentLen = buf.readUInt16LE(offset + 32);
    const localOffset = buf.readUInt32LE(offset + 42);
    const name = buf.toString('utf8', offset + 46, offset + 46 + nameLen);
    entries.push({ name, method, compressedSize, size, localOffset });
    offset += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

function readEntry(buf, entry) {
  const off = entry.localOffset;
  if (off + 30 > buf.length || buf.readUInt32LE(off) !== LOC_SIG) return null;
  const nameLen = buf.readUInt16LE(off + 26);
  const extraLen = buf.readUInt16LE(off + 28);
  const start = off + 30 + nameLen + extraLen;
  const end = start + entry.compressedSize;
  if (end > buf.length) return null;
  const raw = buf.subarray(start, end);
  if (entry.method === 0) return raw;
  if (entry.method === 8) {
    try { return zlib.inflateRawSync(raw); } catch { return null; }
  }
  return null;
}

// ── AXML (AndroidManifest.xml binario) ────────────────────────────

function readStringPool(buf) {
  // Cabecera del chunk XML: type(2)=0x0003, headerSize(2), size(4).
  if (buf.length < 8 || buf.readUInt16LE(0) !== 0x0003) return [];
  const strings = [];
  let offset = 8;
  while (offset + 8 <= buf.length) {
    const type = buf.readUInt16LE(offset);
    const headerSize = buf.readUInt16LE(offset + 2);
    const chunkSize = buf.readUInt32LE(offset + 4);
    if (chunkSize <= 0) break;
    if (type === 0x0001) {
      const stringCount = buf.readUInt32LE(offset + 8);
      const flags = buf.readUInt32LE(offset + 16);
      const stringsStart = buf.readUInt32LE(offset + 20);
      const isUtf8 = (flags & 0x100) !== 0;
      const offsetsAt = offset + headerSize;
      const dataAt = offset + stringsStart;
      for (let i = 0; i < stringCount; i++) {
        const p = offsetsAt + i * 4;
        if (p + 4 > buf.length) break;
        let cursor = dataAt + buf.readUInt32LE(p);
        try {
          if (isUtf8) {
            let charLen = buf.readUInt16LE(cursor); cursor += 2;
            if (charLen & 0x8000) { charLen = ((charLen & 0x7fff) << 16) | buf.readUInt16LE(cursor); cursor += 2; }
            let byteLen = buf.readUInt8(cursor); cursor += 1;
            if (byteLen & 0x80) { byteLen = ((byteLen & 0x7f) << 8) | buf.readUInt8(cursor); cursor += 1; }
            strings.push(buf.toString('utf8', cursor, cursor + byteLen));
          } else {
            let len = buf.readUInt16LE(cursor); cursor += 2;
            if (len & 0x8000) { len = ((len & 0x7fff) << 16) | buf.readUInt16LE(cursor); cursor += 2; }
            strings.push(buf.toString('utf16le', cursor, cursor + len * 2));
          }
        } catch { /* string rota: se ignora */ }
      }
      break;
    }
    offset += chunkSize;
  }
  return strings;
}

function looksLikePackage(value) {
  if (!value || value.length > 120) return false;
  if (!/^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(value)) return false;
  const lower = value.toLowerCase();
  if (FOREIGN_NAMESPACES.some((ns) => lower.startsWith(ns))) return false;
  return (value.match(/\./g) || []).length >= 2;
}

function looksLikeVersion(value) {
  if (!value || value.length > 40) return false;
  return /^\d+(\.\d+)+([._-][A-Za-z0-9]+)*$/.test(value);
}

function pickPackage(strings) {
  const candidates = strings.filter(looksLikePackage);
  if (!candidates.length) return '';
  candidates.sort((a, b) => {
    const dots = (s) => (s.match(/\./g) || []).length;
    const prio = (s) => (/^(com|org|net|io|app|me|de|dev)\./i.test(s) ? 0 : 1);
    return prio(a) - prio(b) || dots(b) - dots(a) || a.length - b.length;
  });
  return candidates[0];
}

function pickVersion(strings) {
  const candidates = strings.filter(looksLikeVersion);
  if (!candidates.length) return '';
  candidates.sort((a, b) => (b.match(/\./g) || []).length - (a.match(/\./g) || []).length);
  return candidates[0];
}

// ── Análisis ──────────────────────────────────────────────────────

/**
 * @param {Buffer} buffer contenido del APK
 * @returns {object} informe de PairIP y metadatos del APK
 */
function analyzeApk(buffer) {
  if (!Buffer.isBuffer(buffer) || buffer.length < 22) {
    throw new Error('Archivo demasiado pequeño o inválido');
  }
  const entries = readCentralDirectory(buffer);
  if (!entries.length) throw new Error('El ZIP no tiene entradas legibles');

  const byName = new Map(entries.map((e) => [e.name, e]));
  const names = entries.map((e) => e.name);

  // ABIs con libpairpcore.so → PairIP nativo.
  const abis = [];
  for (const name of names) {
    const m = name.match(/^lib\/([^/]+)\/libpairpcore\.so$/i);
    if (m) abis.push(m[1]);
  }

  // Clases PairIP dentro de los DEX.
  const markers = [];
  const dexEntries = entries.filter((e) => /^classes\d*\.dex$/i.test(e.name));
  for (const dex of dexEntries) {
    const data = readEntry(buffer, dex);
    if (!data) continue;
    for (const marker of PAIRIP_MARKERS) {
      if (data.includes(marker) && !markers.includes(marker)) markers.push(marker);
    }
  }

  // Metadatos del manifiesto (heurísticos: el paquete y la versión).
  let packageName = '';
  let versionName = '';
  const manifestEntry = byName.get('AndroidManifest.xml');
  if (manifestEntry) {
    const manifest = readEntry(buffer, manifestEntry);
    if (manifest) {
      const strings = readStringPool(manifest);
      packageName = pickPackage(strings);
      versionName = pickVersion(strings);
    }
  }

  const signedV1 = names.some((n) => /^META-INF\/.*\.(RSA|DSA|EC)$/i.test(n));

  const pairipNative = abis.length > 0;
  const pairipDex = markers.length > 0;

  return {
    size: buffer.length,
    sha256: crypto.createHash('sha256').update(buffer).digest('hex'),
    packageName,
    versionName,
    entries: entries.length,
    dexFiles: dexEntries.length,
    abis: [...new Set(abis)].sort(),
    pairip: pairipNative || pairipDex,
    pairipNative,
    pairipDex,
    pairipMarkers: markers,
    signedV1,
  };
}

module.exports = { analyzeApk, readCentralDirectory, readEntry, readStringPool };
