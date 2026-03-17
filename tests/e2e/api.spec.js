// @ts-check
const { test, expect } = require('@playwright/test');

const API = 'https://codehub-production-729d.up.railway.app';

test.describe('Backend API — Health y endpoints', () => {

  test('GET /api/health — backend online', async ({ request }) => {
    const res = await request.get(`${API}/api/health`);
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.status).toBe('ok');
    expect(body.version).toBeTruthy();
  });

  test('GET /api/apps — retorna array de apps', async ({ request }) => {
    const res = await request.get(`${API}/api/apps`);
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body).toHaveProperty('apps');
    expect(Array.isArray(body.apps)).toBe(true);
    expect(body.apps.length).toBeGreaterThan(0);
  });

  test('GET /api/apps — cada app tiene campos requeridos', async ({ request }) => {
    const res = await request.get(`${API}/api/apps`);
    const { apps } = await res.json();
    const app = apps[0];
    expect(app).toHaveProperty('appId');
    expect(app).toHaveProperty('nombre');
    expect(app).toHaveProperty('version');
    expect(app).toHaveProperty('verified');
  });

  test('GET /api/ratings — retorna objeto ratings', async ({ request }) => {
    const res = await request.get(`${API}/api/ratings`);
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body).toHaveProperty('ratings');
  });

  test('GET /api/requests — retorna array', async ({ request }) => {
    const res = await request.get(`${API}/api/requests`);
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body).toHaveProperty('requests');
    expect(Array.isArray(body.requests)).toBe(true);
  });

  test('POST /api/chat — responde con IA', async ({ request }) => {
    const res = await request.post(`${API}/api/chat`, {
      data: { message: 'Hola, ¿qué es CodeHub?', sessionId: 'playwright-test' },
    });
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body).toHaveProperty('reply');
    expect(body.reply.length).toBeGreaterThan(10);
  });

  test('POST /api/chat — rechaza mensajes vacíos', async ({ request }) => {
    const res = await request.post(`${API}/api/chat`, {
      data: { message: '' },
    });
    expect(res.status()).toBe(400);
  });

  test('POST /api/ratings — rechaza datos inválidos', async ({ request }) => {
    const res = await request.post(`${API}/api/ratings`, {
      data: { appId: '', stars: 10 },
    });
    expect(res.status()).toBe(400);
  });

  test('GET /api/docs — Swagger UI accesible', async ({ request }) => {
    const res = await request.get(`${API}/api/docs`);
    expect(res.status()).toBe(200);
    const text = await res.text();
    expect(text).toContain('swagger');
  });

  test('GET /api/docs.json — spec JSON válido', async ({ request }) => {
    const res = await request.get(`${API}/api/docs.json`);
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.openapi).toBe('3.0.0');
    expect(body.info.title).toContain('CodeHub');
  });

  test('Admin sin key — 403', async ({ request }) => {
    const res = await request.get(`${API}/api/admin/apps`);
    expect(res.status()).toBe(403);
  });

});
