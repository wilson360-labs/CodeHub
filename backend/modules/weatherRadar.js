'use strict';

const express = require('express');
const rateLimit = require('express-rate-limit');

const FRAME_STEP_MS = 5 * 60 * 1000;
const FRAME_COUNT = 5;
const FRAME_WINDOW_MS = FRAME_STEP_MS * (FRAME_COUNT - 1);
const TILE_CACHE_TTL_MS = 30 * 60 * 1000;
const TILE_CACHE_MAX = 256;

module.exports = function createWeatherRadarRouter() {
  const router = express.Router();
  const tileCache = new Map();
  const tileLimiter = rateLimit({
    windowMs: 15 * 60 * 1000,
    max: 90,
    standardHeaders: true,
    legacyHeaders: false,
    message: { ok: false, code: 'WEATHER_RADAR_RATE_LIMIT', error: 'Demasiadas solicitudes del radar. Espera unos minutos.' },
  });

  let providerHour = Math.floor(Date.now() / 3600000);
  let providerCallsThisHour = 0;
  const providerHourlyCap = Math.max(1, Number.parseInt(process.env.WEATHER_RADAR_MAX_TILES_PER_HOUR, 10) || 20);

  function hasApiKey() {
    return Boolean(process.env.TOMORROW_IO_API_KEY?.trim());
  }

  function configured(res) {
    if (hasApiKey()) return true;
    res.set('Cache-Control', 'no-store').status(503).json({
      ok: false,
      code: 'WEATHER_RADAR_NOT_CONFIGURED',
      error: 'El radar de lluvia todavía no está habilitado.',
    });
    return false;
  }

  router.get('/frames', (req, res) => {
    if (!configured(res)) return;
    const latest = Math.floor(Date.now() / FRAME_STEP_MS) * FRAME_STEP_MS;
    const frames = Array.from({ length: FRAME_COUNT }, (_, index) => new Date(latest - (FRAME_COUNT - 1 - index) * FRAME_STEP_MS).toISOString());
    res.set('Cache-Control', 'public, max-age=60').json({
      ok: true,
      frames,
      intervalSeconds: FRAME_STEP_MS / 1000,
      attribution: 'Datos de precipitación: Tomorrow.io · Mapa: OpenStreetMap / OpenFreeMap',
    });
  });

  router.get('/tile/:z/:x/:y', tileLimiter, async (req, res) => {
    if (!configured(res)) return;

    const time = String(req.query.time || '');
    const z = Number(req.params.z);
    const x = Number(req.params.x);
    const y = Number(req.params.y);
    const timestamp = Date.parse(time);
    const now = Date.now();
    const currentFrame = Math.floor(now / FRAME_STEP_MS) * FRAME_STEP_MS;
    const maxTileIndex = Number.isInteger(z) && z >= 0 && z <= 12 ? (2 ** z) - 1 : -1;

    if (!Number.isFinite(timestamp) || !Number.isInteger(z) || maxTileIndex < 0
        || !Number.isInteger(x) || !Number.isInteger(y) || x < 0 || y < 0
        || x > maxTileIndex || y > maxTileIndex
        || timestamp % FRAME_STEP_MS !== 0
        || timestamp > currentFrame || timestamp < currentFrame - FRAME_WINDOW_MS) {
      return res.status(400).json({ ok: false, code: 'WEATHER_RADAR_BAD_TILE', error: 'Fotograma o tesela no válidos.' });
    }

    const cacheKey = `${timestamp}:${z}:${x}:${y}`;
    const cached = tileCache.get(cacheKey);
    if (cached && cached.expiresAt > now) {
      res.set('Cache-Control', 'public, max-age=1800').type('png').send(cached.body);
      return;
    }
    if (cached) tileCache.delete(cacheKey);

    const hour = Math.floor(now / 3600000);
    if (hour !== providerHour) {
      providerHour = hour;
      providerCallsThisHour = 0;
    }
    if (providerCallsThisHour >= providerHourlyCap) {
      return res.status(429).json({ ok: false, code: 'WEATHER_RADAR_PROVIDER_LIMIT', error: 'El radar alcanzó el límite horario. Inténtalo más tarde.' });
    }

    const frameTime = new Date(timestamp).toISOString();
    const url = `https://api.tomorrow.io/v4/map/tile/${z}/${x}/${y}/precipitationIntensity/${encodeURIComponent(frameTime)}.png`;
    providerCallsThisHour++;
    try {
      const upstream = await fetch(url, {
        headers: { apikey: process.env.TOMORROW_IO_API_KEY.trim(), Accept: 'image/png' },
        signal: AbortSignal.timeout(8000),
      });
      if (!upstream.ok) {
        const status = upstream.status === 429 ? 429 : 502;
        return res.status(status).json({ ok: false, code: 'WEATHER_RADAR_PROVIDER_ERROR', error: 'No se pudo cargar este fotograma del radar.' });
      }
      const contentType = upstream.headers.get('content-type') || '';
      if (!contentType.toLowerCase().startsWith('image/')) {
        return res.status(502).json({ ok: false, code: 'WEATHER_RADAR_BAD_RESPONSE', error: 'El proveedor devolvió un fotograma no válido.' });
      }
      const body = Buffer.from(await upstream.arrayBuffer());
      if (!body.length || body.length > 1024 * 1024) {
        return res.status(502).json({ ok: false, code: 'WEATHER_RADAR_BAD_RESPONSE', error: 'El fotograma del radar está vacío o excede el tamaño permitido.' });
      }
      if (tileCache.size >= TILE_CACHE_MAX) tileCache.delete(tileCache.keys().next().value);
      tileCache.set(cacheKey, { body, expiresAt: now + TILE_CACHE_TTL_MS });
      res.set('Cache-Control', 'public, max-age=1800').type('png').send(body);
    } catch (error) {
      const timeout = error?.name === 'TimeoutError' || error?.name === 'AbortError';
      res.status(timeout ? 504 : 502).json({
        ok: false,
        code: timeout ? 'WEATHER_RADAR_TIMEOUT' : 'WEATHER_RADAR_UNAVAILABLE',
        error: 'El servicio de radar no está disponible ahora.',
      });
    }
  });

  return router;
};
