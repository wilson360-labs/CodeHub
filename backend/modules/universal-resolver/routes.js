/**
 * routes.js — Rutas Express del Universal Resolver
 * Módulo: Universal Resolver · CodeHub v3
 * ─────────────────────────────────────────────────────────────────
 * Endpoints:
 *   POST /api/resolver/resolve   — Resolver una URL acortada
 *   GET  /api/resolver/cache/:hash — Consultar caché por hash SHA-256
 *   DELETE /api/resolver/cache/:hash — Eliminar entrada de caché (admin)
 *   GET  /api/resolver/stats     — Estadísticas del módulo (admin)
 */

'use strict';

const express      = require('express');
const rateLimit    = require('express-rate-limit');
const { resolve, hashUrl } = require('./resolver');
const ResolvedLink = require('./ResolvedLink.model');

const router = express.Router();

// ── Rate limiting específico del módulo ───────────────────────
const resolverLimiter = rateLimit({
  windowMs: 60 * 1000,  // 1 minuto
  max:      15,
  standardHeaders: true,
  legacyHeaders:   false,
  message: { ok: false, error: 'Demasiadas solicitudes. Espera un momento.' },
  keyGenerator: (req) =>
    req.headers['x-real-ip'] ||
    (req.headers['x-forwarded-for'] || '').split(',')[0].trim() ||
    req.ip,
});

// ── Middleware de autenticación admin (reutiliza ADMIN_KEY del server) ──
function requireAdmin(req, res, next) {
  const key = req.headers['x-admin-key'] || req.body?.adminKey;
  if (!process.env.ADMIN_KEY) {
    return res.status(503).json({ ok: false, error: 'Servidor no configurado — falta ADMIN_KEY' });
  }
  if (key !== process.env.ADMIN_KEY) {
    return res.status(403).json({ ok: false, error: 'Credenciales incorrectas' });
  }
  next();
}

// ─────────────────────────────────────────────────────────────────
//  POST /api/resolver/resolve
// ─────────────────────────────────────────────────────────────────
/**
 * @swagger
 * /api/resolver/resolve:
 *   post:
 *     summary: Resuelve una URL acortada hasta su destino final
 *     tags: [Universal Resolver]
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             required: [url]
 *             properties:
 *               url:
 *                 type: string
 *                 example: "https://bit.ly/ejemplo"
 *               force_refresh:
 *                 type: boolean
 *                 description: Ignorar caché y re-resolver
 *                 example: false
 *     responses:
 *       200:
 *         description: URL resuelta exitosamente
 *       400:
 *         description: URL inválida o bloqueada por SSRF
 *       429:
 *         description: Rate limit excedido
 */
router.post('/resolve', resolverLimiter, async (req, res) => {
  const rawUrl      = String(req.body?.url || '').trim();
  const forceRefresh = Boolean(req.body?.force_refresh);

  // ── Validación básica de entrada ──────────────────────────────
  if (!rawUrl) {
    return res.status(400).json({ ok: false, error: 'El campo "url" es requerido.' });
  }
  if (rawUrl.length > 2048) {
    return res.status(400).json({ ok: false, error: 'URL demasiado larga (máx. 2048 caracteres).' });
  }

  // Verificar protocolo antes de hacer DNS
  try {
    const proto = new URL(rawUrl).protocol;
    if (!['http:', 'https:'].includes(proto)) {
      return res.status(400).json({ ok: false, error: `Protocolo no permitido: ${proto}` });
    }
  } catch {
    return res.status(400).json({ ok: false, error: 'URL malformada. Incluye http:// o https://' });
  }

  // IP del solicitante
  const requesterIp =
    req.headers['x-real-ip'] ||
    (req.headers['x-forwarded-for'] || '').split(',')[0].trim() ||
    req.ip ||
    null;

  // ── Buscar en caché MongoDB ───────────────────────────────────
  if (!forceRefresh) {
    try {
      const urlHash = hashUrl(rawUrl);
      const cached  = await ResolvedLink.findOne({ hash: urlHash });

      if (cached) {
        // Refrescar TTL sin bloquear la respuesta
        ResolvedLink.updateOne(
          { hash: urlHash },
          {
            last_verified: new Date(),
            expires_at:    new Date(Date.now() + 7 * 24 * 60 * 60 * 1000),
          }
        ).catch(() => {});

        return res.json({
          ok:     true,
          cached: true,
          data: {
            hash:               cached.hash,
            original_short_url: cached.original_short_url,
            final_resolved_url: cached.final_resolved_url,
            hops_count:         cached.hops_count,
            hops_chain:         cached.hops_chain,
            resolution_method:  cached.resolution_method,
            status_code:        cached.status_code,
            last_verified:      cached.last_verified,
          },
        });
      }
    } catch (dbErr) {
      // Caché no disponible — continuar con resolución en vivo
      console.warn('[universal-resolver] MongoDB caché error:', dbErr.message);
    }
  }

  // ── Resolución en vivo ────────────────────────────────────────
  let result;
  try {
    result = await resolve(rawUrl, requesterIp);
  } catch (err) {
    const isSSRF    = err.message.includes('SSRF') || err.message.includes('bloqueado');
    const isTimeout = err.message.includes('abort') || err.name === 'AbortError';
    const isInvalid = err.message.includes('malformada') || err.message.includes('protocolo');

    if (isSSRF || isInvalid) {
      return res.status(400).json({ ok: false, error: err.message });
    }
    if (isTimeout) {
      return res.status(504).json({ ok: false, error: 'Tiempo de espera agotado al resolver la URL.' });
    }
    console.error('[universal-resolver] resolve error:', err.message);
    return res.status(500).json({ ok: false, error: 'Error interno al resolver la URL.' });
  }

  // ── Persistir en MongoDB (sin bloquear respuesta) ─────────────
  try {
    await ResolvedLink.findOneAndUpdate(
      { hash: result.hash },
      {
        $set: {
          ...result,
          last_verified: new Date(),
          expires_at:    new Date(Date.now() + 7 * 24 * 60 * 60 * 1000),
        },
      },
      { upsert: true, new: true, setDefaultsOnInsert: true }
    );
  } catch (dbErr) {
    // No fallar la respuesta por error de persistencia
    console.warn('[universal-resolver] MongoDB persist error:', dbErr.message);
  }

  return res.json({
    ok:     true,
    cached: false,
    data:   result,
  });
});

// ─────────────────────────────────────────────────────────────────
//  GET /api/resolver/cache/:hash
// ─────────────────────────────────────────────────────────────────
/**
 * Consulta una entrada de caché directamente por su hash SHA-256.
 * Útil para verificar si una URL ya fue procesada sin re-resolverla.
 */
router.get('/cache/:hash', async (req, res) => {
  const { hash } = req.params;
  if (!/^[a-f0-9]{64}$/.test(hash)) {
    return res.status(400).json({ ok: false, error: 'Hash inválido. Se esperaba SHA-256 en hex (64 chars).' });
  }

  try {
    const entry = await ResolvedLink.findOne({ hash }).lean();
    if (!entry) {
      return res.status(404).json({ ok: false, error: 'No encontrado en caché.' });
    }
    return res.json({ ok: true, data: entry });
  } catch (err) {
    return res.status(500).json({ ok: false, error: 'Error consultando caché.' });
  }
});

// ─────────────────────────────────────────────────────────────────
//  DELETE /api/resolver/cache/:hash  (admin)
// ─────────────────────────────────────────────────────────────────
/**
 * Elimina una entrada de caché para forzar re-resolución en la próxima petición.
 */
router.delete('/cache/:hash', requireAdmin, async (req, res) => {
  const { hash } = req.params;
  if (!/^[a-f0-9]{64}$/.test(hash)) {
    return res.status(400).json({ ok: false, error: 'Hash inválido.' });
  }

  try {
    const result = await ResolvedLink.deleteOne({ hash });
    if (result.deletedCount === 0) {
      return res.status(404).json({ ok: false, error: 'Entrada no encontrada en caché.' });
    }
    return res.json({ ok: true, message: 'Entrada eliminada del caché.' });
  } catch (err) {
    return res.status(500).json({ ok: false, error: 'Error eliminando entrada.' });
  }
});

// ─────────────────────────────────────────────────────────────────
//  GET /api/resolver/stats  (admin)
// ─────────────────────────────────────────────────────────────────
/**
 * Estadísticas del módulo: total de entradas, más usados, distribución de métodos.
 */
router.get('/stats', requireAdmin, async (req, res) => {
  try {
    const [total, methods, recent] = await Promise.all([
      ResolvedLink.countDocuments(),
      ResolvedLink.aggregate([
        { $group: { _id: '$resolution_method', count: { $sum: 1 } } },
        { $sort: { count: -1 } },
      ]),
      ResolvedLink.find()
        .sort({ last_verified: -1 })
        .limit(10)
        .select('original_short_url final_resolved_url hops_count resolution_method last_verified')
        .lean(),
    ]);

    return res.json({
      ok:    true,
      stats: {
        total_cached:       total,
        resolution_methods: methods,
        recently_resolved:  recent,
      },
    });
  } catch (err) {
    return res.status(500).json({ ok: false, error: 'Error obteniendo estadísticas.' });
  }
});

module.exports = router;
