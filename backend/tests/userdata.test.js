/**
 * userdata.test.js — Pruebas de integración de /api/user/data
 * (createUserDataRouter) con un Supabase fake en memoria que simula
 * la tabla user_data (favorites/settings/history como JSON strings).
 *
 * Cubre: PATCH de favoritos (true agrega / false elimina), historial con
 * cap y validación, settings con región "loc" y sanitización.
 */
'use strict';

const { test } = require('node:test');
const assert = require('node:assert');

function makeApp() {
  const express = require('express');
  const { createUserDataRouter } = require('../modules/userdata');
  let row = null;
  const supabase = {
    from() {
      return {
        select() { return sel; },
        upsert(obj) { row = obj; return { error: null }; },
      };
    },
  };
  const sel = {
    eq() { return this; },
    limit() { return this; },
    async maybeSingle() { return { data: row, error: null }; },
  };
  const app = express();
  app.use(express.json());
  app.use((req, res, next) => { req.authUser = { id: 'u-test', email: 't@x.dev' }; next(); });
  app.use('/api/user', createUserDataRouter(supabase));
  const state = { getRow: () => row };
  return { app, state };
}

function request(app, method, path, body, token) {
  const http = require('http');
  const server = app.listen(0);
  const port = server.address().port;
  return new Promise((resolve, reject) => {
    const payload = body ? JSON.stringify(body) : null;
    const req = http.request({ host: '127.0.0.1', port, path, method, headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {}),
    } }, (res) => {
      let data = '';
      res.on('data', (d) => { data += d; });
      res.on('end', () => { server.close(); resolve({ status: res.statusCode, body: JSON.parse(data) }); });
    });
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

test('PATCH de favoritos: true agrega, false elimina, ausentes no cambian', async () => {
  const { app, state } = makeApp();
  let r = await request(app, 'PUT', '/api/user/data', { favorites: { pixa: true, pixb: true } });
  assert.equal(r.status, 200);
  assert.deepEqual(r.body.data.favorites, { pixa: true, pixb: true });
  assert.deepEqual(JSON.parse(state.getRow().favorites), { pixa: true, pixb: true });

  r = await request(app, 'PUT', '/api/user/data', { favorites: { pixa: false } });
  assert.equal(r.status, 200);
  // pixa se elimina; pixb se preserva (no fue mencionado)
  assert.deepEqual(JSON.parse(state.getRow().favorites), { pixb: true });

  r = await request(app, 'GET', '/api/user/data', null);
  assert.equal(r.status, 200);
  assert.deepEqual(r.body.data.favorites, { pixb: true });
});

test('historial: reemplaza campo, valida shape y cap de 40', async () => {
  const { app } = makeApp();
  // input válido con 45 ítems (40 válidos únicos + duplicados fuera de cap)
  const h = [];
  for (let i = 0; i < 46; i++) h.push({ id: 'tool' + (i % 45), ts: i });
  let r = await request(app, 'PUT', '/api/user/data', { history: h });
  assert.equal(r.status, 200);
  assert.equal(r.body.data.history.length, 40);

  r = await request(app, 'PUT', '/api/user/data', { history: 'x' });
  assert.equal(r.status, 400);

  r = await request(app, 'PUT', '/api/user/data', { history: [{ id: 'a/b', ts: 1 }] });
  assert.equal(r.status, 200);
  assert.equal(r.body.data.history.length, 0);
});

test('settings: primitivos + region loc; objetos anidados se descartan', async () => {
  const { app, state } = makeApp();
  const r = await request(app, 'PUT', '/api/user/data', {
    settings: {
      theme: 'dark',
      font: 'md',
      lang: 'es',
      loc: { lat: 14.6, lon: -90.5, city: 'Ciudad de Guatemala', country: 'Guatemala', src: 'search' },
      nested: { x: 1 },
      arr: [1, 2],
    },
  });
  assert.equal(r.status, 200);
  const saved = JSON.parse(state.getRow().settings);
  assert.equal(saved.theme, 'dark');
  assert.deepEqual(saved.loc, { lat: 14.6, lon: -90.5, city: 'Ciudad de Guatemala', country: 'Guatemala', src: 'search' });
  assert.equal(saved.nested, undefined);
});

test('sin payload → 400; tipos inválidos → 400', async () => {
  const { app } = makeApp();
  let r = await request(app, 'PUT', '/api/user/data', {});
  assert.equal(r.status, 400);
  r = await request(app, 'PUT', '/api/user/data', { favorites: [1, 2] });
  assert.equal(r.status, 400);
  r = await request(app, 'PUT', '/api/user/data', { settings: 'nope' });
  assert.equal(r.status, 400);
});