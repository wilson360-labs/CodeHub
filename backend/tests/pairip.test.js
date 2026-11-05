'use strict';
/**
 * pairip.test.js — Pruebas del analizador de PairIP (backend/pairip.js).
 *
 * Sin red: se construyen APKs sintéticos en memoria (ZIP a mano) con y sin
 * `libpairpcore.so` / clases `com.pairip.*` y se comprueba el informe.
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const zlib = require('node:zlib');

const { analyzeApk, stripPairip } = require('../pairip');

// ── Constructor de ZIP mínimo (store + deflate) ───────────────────
function crc32(buf) {
  let c;
  const table = [];
  for (let n = 0; n < 256; n++) {
    c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c >>> 0;
  }
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) crc = table[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function buildZip(files) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const file of files) {
    const nameBuf = Buffer.from(file.name, 'utf8');
    const data = Buffer.isBuffer(file.data) ? file.data : Buffer.from(file.data);
    const deflated = zlib.deflateRawSync(data);
    const crc = crc32(data);

    const local = Buffer.alloc(30 + nameBuf.length);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0, 6);
    local.writeUInt16LE(8, 8);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(deflated.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    nameBuf.copy(local, 30);
    locals.push(local, deflated);

    const central = Buffer.alloc(46 + nameBuf.length);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0, 8);        // flags
    central.writeUInt16LE(8, 10);       // método: deflate
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(deflated.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBuf.length, 28);
    central.writeUInt32LE(offset, 42);
    nameBuf.copy(central, 46);
    centrals.push(central);

    offset += local.length + deflated.length;
  }
  const centralBuf = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(files.length, 8);
  eocd.writeUInt16LE(files.length, 10);
  eocd.writeUInt32LE(centralBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, centralBuf, eocd]);
}

function apkWithPairip() {
  return buildZip([
    { name: 'AndroidManifest.xml', data: Buffer.from('manifest-binary-placeholder') },
    { name: 'classes.dex', data: Buffer.from('dex\n035\0Lcom/pairip/VMRunner;Lcom/pairip/SignatureCheck;') },
    { name: 'lib/arm64-v8a/libpairpcore.so', data: Buffer.from('ELF-pairipcore') },
    { name: 'lib/armeabi-v7a/libpairpcore.so', data: Buffer.from('ELF-pairipcore') },
    { name: 'META-INF/CERT.RSA', data: Buffer.from('signature') },
  ]);
}

function apkSinPairip() {
  return buildZip([
    { name: 'AndroidManifest.xml', data: Buffer.from('manifest-binary-placeholder') },
    { name: 'classes.dex', data: Buffer.from('dex\n035\0Lcom/example/MainActivity;') },
    { name: 'lib/arm64-v8a/libfoo.so', data: Buffer.from('ELF-foo') },
  ]);
}

test('pairip: detecta libpairpcore.so y clases com.pairip', () => {
  const report = analyzeApk(apkWithPairip());
  assert.equal(report.pairip, true);
  assert.equal(report.pairipNative, true);
  assert.equal(report.pairipDex, true);
  assert.deepEqual(report.abis, ['arm64-v8a', 'armeabi-v7a']);
  assert.ok(report.pairipMarkers.includes('com/pairip/VMRunner'));
  assert.equal(report.signedV1, true);
  assert.equal(report.dexFiles, 1);
  assert.match(report.sha256, /^[a-f0-9]{64}$/);
});

test('pairip: un APK normal no se marca', () => {
  const report = analyzeApk(apkSinPairip());
  assert.equal(report.pairip, false);
  assert.equal(report.pairipNative, false);
  assert.equal(report.pairipDex, false);
  assert.deepEqual(report.abis, []);
  assert.equal(report.signedV1, false);
});

test('pairip: archivo inválido lanza error claro', () => {
  assert.throws(() => analyzeApk(Buffer.from('no soy un zip')), /ZIP|inválido/i);
  assert.throws(() => analyzeApk(Buffer.alloc(4)), /pequeño|inválido/i);
});

test('strip: elimina libpairpcore.so y la firma v1, conserva el resto', () => {
  const { buffer, removed } = stripPairip(apkWithPairip());
  assert.ok(removed.some((n) => n === 'lib/arm64-v8a/libpairpcore.so'));
  assert.ok(removed.some((n) => n === 'META-INF/CERT.RSA'));
  const after = analyzeApk(buffer);
  assert.equal(after.pairipNative, false);
  assert.equal(after.pairipDex, true); // las clases Java quedan: el bypass runtime las anula
  assert.equal(after.signedV1, false);
  assert.ok(after.entries >= 2);
});

test('strip: un APK sin PairIP devuelve error claro', () => {
  assert.throws(() => stripPairip(apkSinPairip()), /Nada que eliminar/i);
});
