// ═══════════════════════════════════════════════════════════════
//  USERDATA — Datos de usuario por cuenta (Fase 1: producto)
//  CodeHub by Wilson.E
//  · Favoritos de herramientas (sync nube para registrados)
//  · Settings de usuario (preferencias guardadas en la nube)
//
//  Autenticado por el middleware `requireAuth` de server.js
//  (Bearer token de Supabase Auth). Guarda JSON en Supabase
//  (tabla user_data), wire-compatible con el resto del backend.
// ═══════════════════════════════════════════════════════════════
'use strict';

const USER_DATA_SQL = `
create table if not exists public.user_data (
  user_id text primary key,
  favorites text not null default '{}',
  settings text not null default '{}',
  updated_at timestamptz default now()
);
`;

// Claves permitidas: ids de tool o de settings (nombres cortos seguro).
const KEY_RE = /^[a-zA-Z0-9_-]{1,48}$/;

async function ensureUserDataTables(supabase, splitSqlStatements) {
  if (!supabase) return false;
  const spl = typeof splitSqlStatements === 'function' ? splitSqlStatements : (s) => s.split(';').filter((x) => x.trim());
  try {
    const statements = spl(USER_DATA_SQL);
    for (const stmt of statements) {
      const { error } = await supabase.rpc('exec_sql', { query: stmt });
      if (error) {
        console.warn('⚠️  UserData: no se pudo crear la tabla — ' + error.message);
        return false;
      }
    }
    console.log('✅ UserData: tabla user_data lista');
    return true;
  } catch (e) {
    console.warn('⚠️  UserData: error asegurando tablas:', e.message);
    return false;
  }
}

function sanitizeFavorites(fv) {
  const out = {};
  const keys = Object.keys(fv);
  for (const k of keys) {
    if (KEY_RE.test(k) && fv[k]) out[k] = true;
  }
  return out;
}

function sanitizeSettings(st) {
  const out = {};
  for (const k of Object.keys(st)) {
    if (!KEY_RE.test(k)) continue;
    const v = st[k];
    // Solo valores primitivos (strings/números/booleanos/nulos), sin anidar.
    if ((typeof v === 'object' && v !== null) || typeof v === 'function') continue;
    out[k] = (typeof v === 'string') ? String(v).slice(0, 200) : v;
  }
  return out;
}

function createUserDataRouter(supabase) {
  const router = require('express').Router();
  const ok = (res, data, code = 200) => res.status(code).json({ ok: true, data });
  const fail = (res, error, code = 400) => res.status(code).json({ ok: false, error });
  const notAuthed = (res) => fail(res, 'No autenticado', 401);

  // GET /api/user/data → { favorites, settings } del usuario autenticado
  router.get('/data', async (req, res) => {
    if (!supabase) return fail(res, 'Servidor sin Supabase', 503);
    if (!req.authUser) return notAuthed(res);
    const { data, error } = await supabase
      .from('user_data')
      .select('favorites, settings')
      .eq('user_id', req.authUser.id)
      .limit(1)
      .maybeSingle();
    if (error && error.code !== 'PGRST116') return fail(res, 'Error leyendo tus datos', 500);
    let favorites = {};
    let settings = {};
    try { if (data && data.favorites) favorites = JSON.parse(data.favorites); } catch (e) {}
    try { if (data && data.settings) settings = JSON.parse(data.settings); } catch (e) {}
    ok(res, { favorites, settings });
  });

  // PUT /api/user/data  { favorites?, settings? } → upsert (preserva el otro campo)
  router.put('/data', async (req, res) => {
    if (!supabase) return fail(res, 'Servidor sin Supabase', 503);
    if (!req.authUser) return notAuthed(res);
    const body = req.body || {};
    const hasFav = body.favorites !== undefined;
    const hasSet = body.settings !== undefined;
    if (!hasFav && !hasSet) return fail(res, 'Nada que guardar');
    if (hasFav && (!body.favorites || typeof body.favorites !== 'object' || Array.isArray(body.favorites)))
      return fail(res, 'favorites debe ser un objeto');
    if (hasSet && (!body.settings || typeof body.settings !== 'object' || Array.isArray(body.settings)))
      return fail(res, 'settings debe ser un objeto');

    // Leer el registro actual para preservar el campo no enviado.
    let favorites = {};
    let settings = {};
    const { data: row } = await supabase.from('user_data').select('favorites, settings').eq('user_id', req.authUser.id).limit(1).maybeSingle();
    try { if (row && row.favorites) favorites = JSON.parse(row.favorites); } catch (e) {}
    try { if (row && row.settings) settings = JSON.parse(row.settings); } catch (e) {}
    if (hasFav) favorites = sanitizeFavorites(body.favorites);
    if (hasSet) settings = sanitizeSettings(body.settings);
    if (Object.keys(favorites).length > 200) return fail(res, 'Demasiados favoritos');

    const { error } = await supabase
      .from('user_data')
      .upsert(
        { user_id: req.authUser.id, favorites: JSON.stringify(favorites), settings: JSON.stringify(settings), updated_at: new Date().toISOString() },
        { onConflict: 'user_id' }
      );
    if (error) return fail(res, 'No se pudo guardar tus datos', 500);
    ok(res, { favorites, settings });
  });

  return router;
}

module.exports = { createUserDataRouter, ensureUserDataTables, USER_DATA_SQL };