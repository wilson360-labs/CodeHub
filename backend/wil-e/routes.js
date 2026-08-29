// wil-e/routes.js — Endpoints REST de la capa de IA de Wil.E.
//   GET  /api/wil-e/kb/search?q=...  — busca en la base de conocimiento (RAG)
//   POST /api/wil-e/kb/ingest       — ingesta privada (x-admin) para "entrenar"
//   POST /api/wil-e/kb/clear        — limpia conocimiento (privado)
//   GET  /api/wil-e/memory          — memoria del usuario autenticado
//   DELETE /api/wil-e/memory        — borra la memoria del usuario
const express = require('express');
const { retrieve, ingest, clear } = require('./knowledge');
const { recall } = require('./memory');
const { AIMemory } = require('./models');

module.exports = function (opts) {
  const router = express.Router();
  const { getOwnerId, authPayload, isAdminReq } = opts || {};

  const resolveOwner = (req) => {
    // Un admin puede operar sobre un owner; si no, usa el owner del payload.
    if (isAdminReq && isAdminReq(req) && req.body && req.body.ownerId) return req.body.ownerId;
    const p = authPayload ? authPayload(req) : null;
    return (p && p.id) || req.authUser?.id || (req.body && req.body.ownerId) || 'admin';
  };

  // ── Búsqueda RAG ──────────────────────────────────────────────
  router.get('/kb/search', async (req, res) => {
    try {
      const query = (req.query.q || '').trim();
      if (!query) return res.json({ results: [] });
      const ownerId = resolveOwner(req);
      const docs = await retrieve({
        ownerId,
        query,
        category: req.query.category || undefined,
        topK: Number(req.query.topK) || 3,
      });
      res.json({ results: docs, count: docs.length, mode: docs.length ? 'rag' : 'none' });
    } catch (e) {
      res.status(500).json({ error: 'kb search error', detail: String(e.message) });
    }
  });

  // ── Ingesta (entrenar a Wil.E con tus datos) — PRIVADO ────────
  router.post('/kb/ingest', async (req, res) => {
    try {
      if (isAdminReq && !isAdminReq(req)) {
        return res.status(403).json({ error: 'Acceso no autorizado' });
      }
      const { title, text, category, keywords, meta, ownerId } = req.body || {};
      const owner = ownerId || resolveOwner(req);
      if (!text) return res.status(400).json({ error: 'Falta text' });

      const n = await ingest({
        ownerId: owner,
        category: category || 'general',
        title: title || 'Documento',
        text,
        keywords: Array.isArray(keywords) ? keywords : [],
        meta: { ...(meta || {}), source: (meta && meta.source) || (title || 'ingest') },
      });
      res.json({ ok: true, owner, chunks: n });
    } catch (e) {
      res.status(500).json({ error: 'ingest error', detail: String(e.message) });
    }
  });

  // ── Limpiar conocimiento — PRIVADO ────────────────────────────
  router.post('/kb/clear', async (req, res) => {
    try {
      if (isAdminReq && !isAdminReq(req)) {
        return res.status(403).json({ error: 'Acceso no autorizado' });
      }
      const owner = resolveOwner(req);
      const r = await clear({ ownerId: owner, category: req.body && req.body.category });
      res.json({ ok: true, deleted: r.deletedCount || 0 });
    } catch (e) {
      res.status(500).json({ error: 'clear error', detail: String(e.message) });
    }
  });

  // ── Memoria del usuario ───────────────────────────────────────
  router.get('/memory', async (req, res) => {
    try {
      const userId = resolveOwner(req);
      const mem = await recall({ userId, limit: 20 });
      res.json({ userId, memory: mem, count: mem ? mem.split('\n').length : 0 });
    } catch (e) {
      res.status(500).json({ error: 'memory error', detail: String(e.message) });
    }
  });

  router.delete('/memory', async (req, res) => {
    try {
      const userId = resolveOwner(req);
      await AIMemory.deleteMany({ userId });
      res.json({ ok: true, userId });
    } catch (e) {
      res.status(500).json({ error: 'memory delete error', detail: String(e.message) });
    }
  });

  return router;
};
