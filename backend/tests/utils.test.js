const test = require('node:test');
const assert = require('node:assert/strict');
const { splitSqlStatements, clientIp, truncate, parseImageDataUrl } = require('../utils');

// ── splitSqlStatements ───────────────────────────────────────────
test('splitSqlStatements: separa sentencias simples por ;', () => {
  const r = splitSqlStatements('select 1; select 2;');
  assert.deepEqual(r, ['select 1', 'select 2']);
});

test('splitSqlStatements: ignora ; dentro de strings con comillas simples', () => {
  const r = splitSqlStatements("insert into t (a) values ('x;y'); select 1;");
  assert.deepEqual(r, ["insert into t (a) values ('x;y')", 'select 1']);
});

test('splitSqlStatements: ignora ; dentro de comentarios de línea', () => {
  const r = splitSqlStatements('select 1; -- comentario ; con punto y coma\nselect 2;');
  assert.deepEqual(r, ['select 1', '-- comentario ; con punto y coma\nselect 2']);
});

test('splitSqlStatements: ignora ; dentro de bloques $$ (dollar-quoted, funciones)', () => {
  const r = splitSqlStatements('create function f() returns void as $$ begin select 1; end; $$ language sql;');
  assert.equal(r.length, 1);
});

test('splitSqlStatements: última sentencia sin ; final igual se incluye', () => {
  const r = splitSqlStatements('select 1; select 2');
  assert.deepEqual(r, ['select 1', 'select 2']);
});

test('splitSqlStatements: string vacío devuelve []', () => {
  assert.deepEqual(splitSqlStatements(''), []);
  assert.deepEqual(splitSqlStatements('   '), []);
});

// ── clientIp ──────────────────────────────────────────────────────
// Contrato endurecido: prioriza req.ip (Express con trust proxy 1), valida
// el formato IP y NUNCA acepta strings arbitrarios de headers del cliente.
test('clientIp: prioriza req.ip (IP válida) sobre headers', () => {
  const req = { headers: { 'x-real-ip': '1.2.3.4', 'x-forwarded-for': '5.6.7.8' }, socket: {}, ip: '9.9.9.9' };
  assert.equal(clientIp(req), '9.9.9.9');
});

test('clientIp: usa x-forwarded-for (primer valor, solo si es IP) sin req.ip', () => {
  const req = { headers: { 'x-forwarded-for': '5.6.7.8, 10.0.0.1' }, socket: {}, ip: undefined };
  assert.equal(clientIp(req), '5.6.7.8');
});

test('clientIp: acepta x-real-ip solo como cadena de reenvío y si es IP', () => {
  const req = { headers: { 'x-real-ip': '1.2.3.4' }, socket: {}, ip: undefined };
  assert.equal(clientIp(req), '1.2.3.4');
});

test('clientIp: normaliza IPv4-mapped (::ffff:) a IPv4', () => {
  const req = { headers: {}, socket: { remoteAddress: '::ffff:5.6.7.8' }, ip: undefined };
  assert.equal(clientIp(req), '5.6.7.8');
});

test('clientIp: cae a req.socket.remoteAddress si no hay req.ip', () => {
  const req = { headers: {}, socket: { remoteAddress: '9.9.9.9' } };
  assert.equal(clientIp(req), '9.9.9.9');
});

test('clientIp: rechaza strings arbitrarios de headers (anti host-injection)', () => {
  const req = { headers: { 'x-real-ip': 'evil.com/?x=1', 'x-forwarded-for': 'no-es-ip' }, socket: {}, ip: undefined };
  assert.equal(clientIp(req), 'unknown');
});

test('clientIp: sin nada disponible devuelve "unknown"', () => {
  const req = { headers: {}, socket: {} };
  assert.equal(clientIp(req), 'unknown');
});

// ── truncate ──────────────────────────────────────────────────────
test('truncate: texto corto no se toca', () => {
  assert.equal(truncate('hola', 400), 'hola');
});

test('truncate: texto largo se corta y agrega …', () => {
  const r = truncate('a'.repeat(10), 5);
  assert.equal(r, 'aaaaa…');
});

test('truncate: texto vacío/null/undefined devuelve ""', () => {
  assert.equal(truncate(''), '');
  assert.equal(truncate(null), '');
  assert.equal(truncate(undefined), '');
});

test('truncate: normaliza CRLF a LF antes de medir', () => {
  assert.equal(truncate('a\r\nb', 400), 'a\nb');
});

// ── parseImageDataUrl ─────────────────────────────────────────────
test('parseImageDataUrl: acepta un data URL PNG válido', () => {
  const r = parseImageDataUrl('data:image/png;base64,AAAA');
  assert.deepEqual(r, { mimeType: 'image/png', data: 'AAAA' });
});

test('parseImageDataUrl: rechaza mime type no permitido', () => {
  assert.equal(parseImageDataUrl('data:image/svg+xml;base64,AAAA'), null);
});

test('parseImageDataUrl: rechaza formato inválido (no es data URL)', () => {
  assert.equal(parseImageDataUrl('no-es-un-data-url'), null);
  assert.equal(parseImageDataUrl(''), null);
  assert.equal(parseImageDataUrl(null), null);
  assert.equal(parseImageDataUrl(undefined), null);
});

test('parseImageDataUrl: rechaza payload base64 excesivamente grande (>6MB)', () => {
  const huge = 'A'.repeat(6_000_001);
  assert.equal(parseImageDataUrl(`data:image/png;base64,${huge}`), null);
});
