'use strict';
/**
 * extensions/routes.js — Catálogo privado + proxy de extensiones de Echo.
 *
 * Montado en `/api/extensions`. El cliente SOLO ve URLs de CodeHub:
 *
 *   GET  /                       Lista para instalar (JSON que consume Echo).
 *   GET  /pkg/:id                Descarga el .eapk (proxy del origen).
 *   GET  /pkg/:id?check=1&from=  Responden 200 (hay update) o 204 (no hay).
 *   GET  /icon/:id               Icono reenviado por CodeHub (sin URL origen).
 *   POST /refresh                Invalida la caché del catálogo (ADMIN_KEY).
 *
 * Seguridad/privacidad:
 *   · El origen (repo + asset) nunca se envía al dispositivo.
 *   · El `id` debe existir en el catálogo: no se aceptan URLs del cliente,
 *     así que no hay SSRF.
 */

const express = require('express');
const rateLimit = require('express-rate-limit');

const cat = require('./catalog');

const router = express.Router();

const fail = (res, error, code) => res.status(code || 400).json({ ok: false, error });

const listLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 120,
  standardHeaders: true,
  legacyHeaders: false,
  message: { ok: false, error: 'Demasiadas solicitudes. Espera un momento.' },
});

const pkgLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 30,
  standardHeaders: true,
  legacyHeaders: false,
  message: { ok: false, error: 'Demasiadas descargas. Espera un momento.' },
});

const iconLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 300,
  standardHeaders: true,
  legacyHeaders: false,
  message: { ok: false, error: 'Demasiadas solicitudes. Espera un momento.' },
});

// ── GET / — catálogo público (metadatos, sin origen) ────────────────
router.get('/', listLimiter, async (req, res) => {
  try {
    const items = await cat.loadCatalog();
    res.setHeader('Cache-Control', 'public, max-age=600');
    return res.json(Array.from(items.values()).map(cat.toPublic));
  } catch (e) {
    return fail(res, 'Catálogo no disponible', 502);
  }
});

// ── GET /pkg/:id — proxy de descarga / comprobación de update ───────
router.get('/pkg/:id', pkgLimiter, async (req, res) => {
  const id = String(req.params.id || '');
  let item;
  try {
    const items = await cat.loadCatalog();
    item = items.get(id);
  } catch (e) {
    return fail(res, 'Catálogo no disponible', 502);
  }
  if (!item) return fail(res, 'Extensión no encontrada', 404);

  const from = String(req.query.from || '');
  let asset;
  try {
    asset = await cat.resolveAsset(item);
  } catch (e) {
    return fail(res, 'No se pudo resolver el paquete de origen', 502);
  }
  if (!asset || !asset.url) return fail(res, 'Paquete no disponible', 502);

  const available = cat.hasUpdate(asset, from);
  if (String(req.query.check) === '1') {
    res.status(available ? 200 : 204);
    return res.end();
  }
  if (!available) {
    res.status(204);
    return res.end();
  }

  try {
    const buf = await cat.fetchBinary(asset.url);
    res.setHeader('Content-Type', 'application/vnd.android.package-archive');
    res.setHeader('Content-Length', String(buf.length));
    res.setHeader('Content-Disposition', `attachment; filename="${cat.baseName(asset.name)}"`);
    res.setHeader('Cache-Control', 'no-store');
    return res.send(buf);
  } catch (e) {
    return fail(res, 'No se pudo descargar el paquete', 502);
  }
});

// ── GET /icon/:id — proxy del icono (nunca viaja la URL de origen) ──
router.get('/icon/:id', iconLimiter, async (req, res) => {
  const id = String(req.params.id || '');
  let item;
  try {
    const items = await cat.loadCatalog();
    item = items.get(id);
  } catch (e) {
    return fail(res, 'Catálogo no disponible', 502);
  }
  if (!item || !item.iconUrl) return fail(res, 'Icono no encontrado', 404);

  try {
    const icon = await cat.fetchIcon(item);
    if (!icon) return fail(res, 'Icono no disponible', 404);
    res.setHeader('Content-Type', icon.contentType);
    res.setHeader('Content-Length', String(icon.buf.length));
    res.setHeader('Cache-Control', 'public, max-age=86400');
    return res.send(icon.buf);
  } catch (e) {
    return fail(res, 'No se pudo obtener el icono', 502);
  }
});

// ── POST /refresh — invalida la caché del catálogo (admin) ──────────
router.post('/refresh', async (req, res) => {
  const validKey = process.env.ADMIN_KEY;
  const key = req.headers['x-admin-key'];
  if (!validKey || !key || key !== validKey) return fail(res, 'No autorizado', 401);
  try {
    await cat.loadCatalog(true);
    return res.json({ ok: true });
  } catch (e) {
    return fail(res, 'No se pudo refrescar el catálogo', 502);
  }
});

module.exports = router;
