import { describe, it, expect } from 'vitest';

const API = 'https://codehub-98s6.onrender.com';

describe('Backend API — CodeHub (Render)', () => {

  it('GET /api/health — backend online', async () => {
    const res = await fetch(`${API}/api/health`);
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.status).toBe('ok');
    expect(body.version).toBeTruthy();
  });

  it('GET /api/apps — retorna array de apps', async () => {
    const res = await fetch(`${API}/api/apps`);
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body).toHaveProperty('apps');
    expect(Array.isArray(body.apps)).toBe(true);
    expect(body.apps.length).toBeGreaterThan(0);
  });

  it('GET /api/apps — cada app tiene campos requeridos', async () => {
    const res = await fetch(`${API}/api/apps`);
    const { apps } = await res.json();
    const app = apps[0];
    expect(app).toHaveProperty('appId');
    expect(app).toHaveProperty('nombre');
    expect(app).toHaveProperty('version');
    expect(app).toHaveProperty('verified');
  });

  it('GET /api/ratings — retorna objeto ratings', async () => {
    const res = await fetch(`${API}/api/ratings`);
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body).toHaveProperty('ratings');
  });

  it('GET /api/requests — retorna array', async () => {
    const res = await fetch(`${API}/api/requests`);
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body).toHaveProperty('requests');
    expect(Array.isArray(body.requests)).toBe(true);
  });

  it('POST /api/chat — responde con IA', async () => {
    const res = await fetch(`${API}/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: 'Hola, ¿qué es CodeHub?', sessionId: 'vitest-ci' }),
    });
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body).toHaveProperty('reply');
    expect(body.reply.length).toBeGreaterThan(10);
  });

  it('POST /api/chat — rechaza mensajes vacíos', async () => {
    const res = await fetch(`${API}/api/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: '' }),
    });
    expect(res.status).toBe(400);
  });

  it('POST /api/ratings — rechaza datos inválidos', async () => {
    const res = await fetch(`${API}/api/ratings`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ appId: '', stars: 10 }),
    });
    expect(res.status).toBe(400);
  });

  it('GET /api/docs.json — spec OpenAPI válido', async () => {
    const res = await fetch(`${API}/api/docs.json`);
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.openapi).toBe('3.0.0');
    expect(body.info.title).toContain('CodeHub');
  });

  it('Admin sin key — 403', async () => {
    const res = await fetch(`${API}/api/admin/apps`);
    expect(res.status).toBe(403);
  });

});
