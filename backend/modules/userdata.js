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
  history text not null default '[]',
  updated_at timestamptz default now()
);
alter table public.user_data add column if not exists history text not null default '[]';
`;

// Claves permitidas: ids de tool o de settings (nombres cortos seguro).
const KEY_RE = /^[a-zA-Z0-9_-]{1,48}$/;
const MAX_FAVORITES = 200;
const MAX_HISTORY = 40;

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

function sanitizeFavoritesPatch(fv) {
  // PATCH: true/truthy => agrega, false/falsy => elimina. Las claves ausentes no cambian.
  const out = {};
  for (const k of Object.keys(fv)) {
    if (KEY_RE.test(k)) out[k] = !!fv[k];
  }
  return out;
}

function sanitizeSettings(st) {
  const out = {};
  for (const k of Object.keys(st)) {
    if (!KEY_RE.test(k)) continue;
    const v = st[k];
    if (k === 'loc' && v && typeof v === 'object' && !Array.isArray(v)) {
      // Región (ciudad/país) elegida por el usuario para notificaciones de sismos/clima.
      const l = {};
      if (Number.isFinite(+v.lat)) l.lat = Math.min(Math.max(+v.lat, -90), 90);
      if (Number.isFinite(+v.lon)) l.lon = Math.min(Math.max(+v.lon, -180), 180);
      if (typeof v.city === 'string') l.city = String(v.city).slice(0, 120);
      if (typeof v.country === 'string') l.country = String(v.country).slice(0, 80);
      if (typeof v.src === 'string' && /^[a-z]{1,16}$/.test(v.src)) l.src = v.src;
      if (Object.keys(l).length) out.loc = l;
      continue;
    }
    // Valores primitivos (strings/números/booleanos/nulos), sin anidar.
    if ((typeof v === 'object' && v !== null) || typeof v === 'function') continue;
    out[k] = (typeof v === 'string') ? String(v).slice(0, 200) : v;
  }
  return out;
}

function sanitizeHistory(h) {
  // Array [{id, ts}] más recientes primero; cap MAX_HISTORY.
  if (!Array.isArray(h)) return null;
  const seen = new Set();
  const out = [];
  for (const item of h) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) continue;
    if (!KEY_RE.test(String(item.id)) || !Number.isFinite(+item.ts)) continue;
    if (seen.has(item.id)) continue;
    seen.add(item.id);
    out.push({ id: String(item.id), ts: Math.floor(+item.ts) });
    if (out.length >= MAX_HISTORY) break;
  }
  return out;
}

function createUserDataRouter(supabase) {
  const router = require('express').Router();
  const ok = (res, data, code = 200) => res.status(code).json({ ok: true, data });
  const fail = (res, error, code = 400) => res.status(code).json({ ok: false, error });
  const notAuthed = (res) => fail(res, 'No autenticado', 401);

  // GET /api/user/data → { favorites, settings, history } del usuario autenticado
  router.get('/data', async (req, res) => {
    if (!supabase) return fail(res, 'Servidor sin Supabase', 503);
    if (!req.authUser) return notAuthed(res);
    const { data, error } = await supabase
      .from('user_data')
      .select('favorites, settings, history')
      .eq('user_id', req.authUser.id)
      .limit(1)
      .maybeSingle();
    if (error && error.code !== 'PGRST116') return fail(res, 'Error leyendo tus datos', 500);
    let favorites = {};
    let settings = {};
    let history = [];
    try { if (data && data.favorites) favorites = JSON.parse(data.favorites); } catch (e) {}
    try { if (data && data.settings) settings = JSON.parse(data.settings); } catch (e) {}
    try { if (data && data.history) history = JSON.parse(data.history); } catch (e) {}
    ok(res, { favorites, settings, history });
  });

  // PUT /api/user/data  { favorites?, settings?, history? }
  // · favorites: PATCH (true agrega / false elimina; ausente no cambia)
  // · settings/history: reemplazo del campo enviado (preserva el resto)
  router.put('/data', async (req, res) => {
    if (!supabase) return fail(res, 'Servidor sin Supabase', 503);
    if (!req.authUser) return notAuthed(res);
    const body = req.body || {};
    const hasFav = body.favorites !== undefined;
    const hasSet = body.settings !== undefined;
    const hasHis = body.history !== undefined;
    if (!hasFav && !hasSet && !hasHis) return fail(res, 'Nada que guardar');
    if (hasFav && (!body.favorites || typeof body.favorites !== 'object' || Array.isArray(body.favorites)))
      return fail(res, 'favorites debe ser un objeto');
    if (hasSet && (!body.settings || typeof body.settings !== 'object' || Array.isArray(body.settings)))
      return fail(res, 'settings debe ser un objeto');

    // Leer el registro actual para preservar los campos no enviados y parchear favoritos.
    let favorites = {};
    let settings = {};
    let history = [];
    const { data: row } = await supabase.from('user_data').select('favorites, settings, history').eq('user_id', req.authUser.id).limit(1).maybeSingle();
    try { if (row && row.favorites) favorites = JSON.parse(row.favorites); } catch (e) {}
    try { if (row && row.settings) settings = JSON.parse(row.settings); } catch (e) {}
    try { if (row && row.history) history = JSON.parse(row.history); } catch (e) {}

    if (hasFav) {
      const patch = sanitizeFavoritesPatch(body.favorites);
      for (const k of Object.keys(patch)) {
        if (patch[k]) favorites[k] = true;
        else delete favorites[k];
      }
      if (Object.keys(favorites).length > MAX_FAVORITES) return fail(res, 'Demasiados favoritos');
    }
    if (hasSet) settings = sanitizeSettings(body.settings);
    if (hasHis) {
      const h = sanitizeHistory(body.history);
      if (!h) return fail(res, 'history debe ser un array de {id, ts}');
      history = h;
    }

    const { error } = await supabase
      .from('user_data')
      .upsert(
        {
          user_id: req.authUser.id,
          favorites: JSON.stringify(favorites),
          settings: JSON.stringify(settings),
          history: JSON.stringify(history),
          updated_at: new Date().toISOString()
        },
        { onConflict: 'user_id' }
      );
    if (error) return fail(res, 'No se pudo guardar tus datos', 500);
    ok(res, { favorites, settings, history });
  });

  return router;
}

module.exports = { createUserDataRouter, ensureUserDataTables, USER_DATA_SQL };