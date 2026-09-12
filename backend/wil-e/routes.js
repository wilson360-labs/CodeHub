// wil-e/routes.js — Endpoints REST de la capa de IA de Wil.E.
//   GET  /api/wil-e/kb/search?q=...  — busca en la base de conocimiento (RAG)
//   POST /api/wil-e/kb/ingest       — ingesta privada (x-admin) para "entrenar"
//   POST /api/wil-e/kb/clear        — limpia conocimiento (privado)
//   GET  /api/wil-e/memory          — memoria del usuario autenticado
//   DELETE /api/wil-e/memory        — borra la memoria del usuario
const express = require('express');
const { retrieve, ingest, clear } = require('./knowledge');
const { recall } = require('./memory');
const { rememberNote } = require('./memory');
const { AIMemory } = require('./models');

module.exports = function (opts) {
  const router = express.Router();
  const { getOwnerId, authPayload, isAdminReq, requireUser } = opts || {};

  // owner legítimo: solo un admin puede operar sobre un body.ownerId ajeno;
  // el resto de peticiones SIEMPRE derivan el userId de la sesión autenticada.
  const resolveOwner = (req) => {
    if (isAdminReq && isAdminReq(req) && req.body && req.body.ownerId) return req.body.ownerId;
    const p = authPayload ? authPayload(req) : null;
    return (p && p.id) || (req.authUser && req.authUser.id) || '';
  };

  // Middleware: exige sesión válida (Bearer Supabase) — sin sesión no se
  // accede a memoria ni a la RAG (antes un invitado caía al owner 'admin').
  const requireAuthed = (req, res, next) => {
    const ok = requireUser ? requireUser(req) : !!(req.authUser && req.authUser.id);
    if (!ok) return res.status(401).json({ error: 'Autenticación requerida' });
    next();
  };

  // ── Búsqueda RAG ──────────────────────────────────────────────
  router.get('/kb/search', requireAuthed, async (req, res) => {
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
  router.get('/memory', requireAuthed, async (req, res) => {
    try {
      const userId = resolveOwner(req);
      const mem = await recall({ userId, limit: 20 });
      const mems = await AIMemory.find({ userId }).sort({ updatedAt: -1 }).limit(30);
      const facts = mems.map((m) => ({
        id: String(m._id),
        key: m.key,
        kind: m.kind,
        value: m.decrypted() || '',
        source: m.source,
        tags: m.tags || [],
        updatedAt: m.updatedAt,
      }));
      res.json({ userId, memory: mem, count: facts.length, facts });
    } catch (e) {
      res.status(500).json({ error: 'memory error', detail: String(e.message) });
    }
  });

  router.delete('/memory', requireAuthed, async (req, res) => {
    try {
      const userId = resolveOwner(req);
      if (!userId) return res.status(401).json({ error: 'Autenticación requerida' });
      await AIMemory.deleteMany({ userId });
      res.json({ ok: true, userId });
    } catch (e) {
      res.status(500).json({ error: 'memory delete error', detail: String(e.message) });
    }
  });

  // ── Borrar UN recuerdo concreto (por clave semántica) ─────────
  router.delete('/memory/:key', requireAuthed, async (req, res) => {
    try {
      const userId = resolveOwner(req);
      if (!userId) return res.status(401).json({ error: 'Autenticación requerida' });
      const r = await AIMemory.deleteMany({ userId, key: req.params.key });
      res.json({ ok: true, userId, deleted: r.deletedCount || 0 });
    } catch (e) {
      res.status(500).json({ error: 'memory delete error', detail: String(e.message) });
    }
  });

  // ── Nota de memoria (entrenar a Wil.E con texto libre) ─────────
  // Guarda texto en la memoria privada del usuario autenticado. Sin permiso
  // admin: solo afecta su propio userId (no el KB global).
  router.post('/memory/note', async (req, res) => {
    try {
      const { text, title } = req.body || {};
      const p = authPayload ? authPayload(req) : null;
      const userId = (p && p.id) || req.authUser?.id;
      if (!userId) return res.status(401).json({ error: 'Autenticación requerida' });
      if (!text || typeof text !== 'string' || !text.trim()) {
        return res.status(400).json({ error: 'Falta text' });
      }
      const n = await rememberNote({ userId, text: text.trim(), title: title || '' });
      if (n <= 0) return res.status(400).json({ error: 'No se pudo guardar la nota (texto muy corto o error).' });
      res.json({ ok: true, userId, saved: n });
    } catch (e) {
      res.status(500).json({ error: 'note error', detail: String(e.message) });
    }
  });

  return router;
};
