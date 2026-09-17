// ═══════════════════════════════════════════════════════════════
//  COMMUNITY — Herramientas comunitarias de ciberseguridad
//  CodeHub by Wilson.E
//  · Central de estafas (reportar y consultar URLs/dominios)
//  · Muro de alertas comunitario
//  · Verificador colaborativo de hashes (reputación de archivos)
//
//  Datos en Supabase (service role). Sin login: cualquiera reporta.
//  Protecciones: límites por IP, sanitización, tamaños máximos y
//  validación de formato (URL y SHA-256). Los votos se aplican con
//  read-modify-write para no depender de funciones SQL extra.
// ═══════════════════════════════════════════════════════════════
'use strict';

const COMMUNITY_SQL = `
create table if not exists public.community_reports (
  id bigint generated always as identity primary key,
  url text not null,
  domain text,
  report_type text default 'phishing',
  note text,
  status text default 'approved',
  votes_scam bigint default 1,
  votes_safe bigint default 0,
  created_at timestamptz default now()
);
create index if not exists community_reports_url_ix on public.community_reports (url);
create index if not exists community_reports_domain_ix on public.community_reports (domain);
create index if not exists community_reports_status_ix on public.community_reports (status);

create table if not exists public.community_alerts (
  id bigint generated always as identity primary key,
  title text not null,
  body text,
  author text default 'Comunidad',
  status text default 'approved',
  created_at timestamptz default now()
);
create index if not exists community_alerts_status_ix on public.community_alerts (status);

create table if not exists public.community_hashes (
  id bigint generated always as identity primary key,
  file_name text,
  sha256 text not null unique,
  verdict text,
  votes_good bigint default 0,
  votes_bad bigint default 0,
  created_at timestamptz default now()
);
create index if not exists community_hashes_sha256_ix on public.community_hashes (sha256);
`;

const HASH_RE = /^[a-fA-F0-9]{64}$/;
const VERDICTS = ['confiable', 'sospechoso', 'malicioso'];

// ── Utilidades ────────────────────────────────────────────────
function cleanStr(s, max) {
  const t = String(s == null ? '' : s).replace(/<[^>]*>/g, '').trim();
  return t.slice(0, max);
}
function normDomain(url) {
  try { return new URL(url).hostname.toLowerCase(); } catch (e) { return null; }
}
function clientIp(req) {
  const fwd = (req.headers['x-forwarded-for'] || '').split(',')[0].trim();
  return fwd || req.socket?.remoteAddress || 'anon';
}

// ── Rate limiting en memoria (ventana deslizante por IP) ───────
const HITS = new Map();
function rate(ip, kind, max, windowMs) {
  const now = Date.now();
  const key = ip + '|' + kind;
  const arr = (HITS.get(key) || []).filter((t) => now - t < windowMs);
  if (arr.length >= max) { HITS.set(key, arr); return false; }
  arr.push(now);
  HITS.set(key, arr);
  return true;
}
if (typeof setInterval === 'function') {
  const iv = setInterval(() => {
    const now = Date.now();
    for (const [k, arr] of HITS) {
      const live = arr.filter((t) => now - t < 600000);
      if (live.length) HITS.set(k, live); else HITS.delete(k);
    }
  }, 600000);
  if (iv.unref) iv.unref();
}

// ── Asegurado de tablas (mismo patrón que ensureStatsTables) ──
async function ensureCommunityTables(supabase, splitSqlStatements) {
  if (!supabase) return false;
  const spl = typeof splitSqlStatements === 'function' ? splitSqlStatements : (s) => s.split(';').filter((x) => x.trim());
  try {
    const statements = spl(COMMUNITY_SQL);
    for (const stmt of statements) {
      const { error } = await supabase.rpc('exec_sql', { query: stmt });
      if (error) {
        console.warn('⚠️  Community: no se pudo crear tabla — ' + error.message);
        return false;
      }
    }
    console.log('✅ Community: tablas comunitarias listas');
    return true;
  } catch (e) {
    console.warn('⚠️  Community: error asegurando tablas:', e.message);
    return false;
  }
}

// ── Router ────────────────────────────────────────────────────
function createCommunityRouter(supabase) {
  const router = require('express').Router();
  const ok = (res, data, code = 200) => res.status(code).json({ ok: true, data });
  const fail = (res, error, code = 400) => res.status(code).json({ ok: false, error });
  const ip = (req) => clientIp(req);

  // ── Central de estafas ──────────────────────────────────────
  // GET /reports?q=dominio&limit=N  → reportes aprobados (más votados primero)
  router.get('/reports', async (req, res) => {
    if (!supabase) return fail(res, 'Backend sin Supabase', 503);
    if (!rate(ip(req), 'rq', 40, 60000)) return fail(res, 'Demasiadas búsquedas. Espera un momento.', 429);
    const q = cleanStr(req.query.q || '', 120).toLowerCase();
    const limit = Math.min(Math.max(+(req.query.limit || 20), 1), 50);
    let query = supabase
      .from('community_reports')
      .select('*')
      .eq('status', 'approved')
      .order('votes_scam', { ascending: false })
      .limit(limit);
    if (q) query = query.ilike('url', `%${q}%`);
    const { data, error } = await query;
    if (error) return fail(res, 'Error consultando reportes', 500);
    ok(res, data || []);
  });

  // POST /reports  { url, report_type?, note? }
  router.post('/reports', async (req, res) => {
    if (!supabase) return fail(res, 'Backend sin Supabase', 503);
    if (!rate(ip(req), 'wr', 5, 60000)) return fail(res, 'Demasiados reportes. Espera un minuto.', 429);
    const raw = cleanStr(req.body?.url || '', 2048);
    if (!/^https?:\/\//i.test(raw)) return fail(res, 'Ingresa una URL válida que empiece por http:// o https://');
    const domain = normDomain(raw);
    if (!domain) return fail(res, 'URL no válida');
    const report_type = cleanStr(req.body?.report_type || 'phishing', 40);
    const note = cleanStr(req.body?.note || '', 300);

    const { data: dup } = await supabase.from('community_reports').select('id').eq('url', raw).limit(1);
    if (dup && dup.length) return fail(res, 'Esa URL ya está reportada en la comunidad.');

    const { data, error } = await supabase
      .from('community_reports')
      .insert({ url: raw, domain, report_type, note, votes_scam: 1, votes_safe: 0, status: 'approved' })
      .select().single();
    if (error) return fail(res, 'No se pudo guardar el reporte', 500);
    ok(res, data, 201);
  });

  // POST /reports/vote  { id, verdict: 'scam' | 'safe' }
  router.post('/reports/vote', async (req, res) => {
    if (!supabase) return fail(res, 'Backend sin Supabase', 503);
    if (!rate(ip(req), 'wv', 10, 60000)) return fail(res, 'Demasiados votos. Espera un momento.', 429);
    const id = cleanStr(req.body?.id || '', 30);
    const verdict = req.body?.verdict;
    if (!id || !['scam', 'safe'].includes(verdict)) return fail(res, 'Voto no válido');
    const { data: row } = await supabase.from('community_reports').select('votes_scam,votes_safe').eq('id', id).single();
    if (!row) return fail(res, 'Reporte no encontrado', 404);
    const upd = verdict === 'scam'
      ? { votes_scam: (row.votes_scam || 0) + 1 }
      : { votes_safe: (row.votes_safe || 0) + 1 };
    const { error } = await supabase.from('community_reports').update(upd).eq('id', id);
    if (error) return fail(res, 'No se pudo registrar el voto', 500);
    ok(res, { id, votes_scam: (row.votes_scam || 0) + (verdict === 'scam' ? 1 : 0), votes_safe: (row.votes_safe || 0) + (verdict === 'safe' ? 1 : 0) });
  });

  // ── Muro de alertas ─────────────────────────────────────────
  router.get('/alerts', async (req, res) => {
    if (!supabase) return fail(res, 'Backend sin Supabase', 503);
    if (!rate(ip(req), 'ra', 40, 60000)) return fail(res, 'Demasiadas peticiones. Espera un momento.', 429);
    const limit = Math.min(Math.max(+(req.query.limit || 20), 1), 50);
    const { data, error } = await supabase
      .from('community_alerts')
      .select('*')
      .eq('status', 'approved')
      .order('created_at', { ascending: false })
      .limit(limit);
    if (error) return fail(res, 'Error consultando alertas', 500);
    ok(res, data || []);
  });

  // POST /alerts  { title, body? }
  router.post('/alerts', async (req, res) => {
    if (!supabase) return fail(res, 'Backend sin Supabase', 503);
    if (!rate(ip(req), 'wa', 3, 60000)) return fail(res, 'Demasiadas alertas. Espera un minuto.', 429);
    const title = cleanStr(req.body?.title || '', 120);
    const body = cleanStr(req.body?.body || '', 600);
    const author = cleanStr(req.body?.author || 'Comunidad', 40);
    if (title.length < 4) return fail(res, 'El título es demasiado corto');
    const { data, error } = await supabase
      .from('community_alerts')
      .insert({ title, body, author, status: 'approved' })
      .select().single();
    if (error) return fail(res, 'No se pudo publicar la alerta', 500);
    ok(res, data, 201);
  });

  // ── Verificador colaborativo de hashes ──────────────────────
  // GET /hashes?sha=64hex  o  ?q=nombre_archivo
  router.get('/hashes', async (req, res) => {
    if (!supabase) return fail(res, 'Backend sin Supabase', 503);
    if (!rate(ip(req), 'rh', 40, 60000)) return fail(res, 'Demasiadas peticiones. Espera un momento.', 429);
    const limit = Math.min(Math.max(+(req.query.limit || 10), 1), 30);
    const sha = cleanStr(req.query.sha || '', 64).toLowerCase();
    const q = cleanStr(req.query.q || '', 120);
    let query = supabase.from('community_hashes').select('*').limit(limit);
    if (HASH_RE.test(sha)) query = query.eq('sha256', sha);
    else if (q) query = query.ilike('file_name', `%${q}%`);
    else query = query.order('created_at', { ascending: false });
    const { data, error } = await query;
    if (error) return fail(res, 'Error consultando hashes', 500);
    ok(res, data || []);
  });

  // POST /hashes  { file_name?, sha256, verdict }
  router.post('/hashes', async (req, res) => {
    if (!supabase) return fail(res, 'Backend sin Supabase', 503);
    if (!rate(ip(req), 'wh', 5, 60000)) return fail(res, 'Demasiados reportes. Espera un minuto.', 429);
    const sha256 = cleanStr(req.body?.sha256 || '', 64).toLowerCase();
    if (!HASH_RE.test(sha256)) return fail(res, 'El hash debe ser SHA-256 en hexadecimal (64 caracteres)');
    const file_name = cleanStr(req.body?.file_name || '', 160);
    const verdict = cleanStr(req.body?.verdict || '', 20);
    if (!VERDICTS.includes(verdict)) return fail(res, 'Veredicto no válido (confiable | sospechoso | malicioso)');

    const { data: existing } = await supabase.from('community_hashes').select('*').eq('sha256', sha256).limit(1);
    if (existing && existing.length) {
      return res.status(200).json({ ok: true, data: existing[0], note: 'Ese hash ya existe en la comunidad (no se sobreescribe).' });
    }
    const { data, error } = await supabase
      .from('community_hashes')
      .insert({ file_name: file_name || null, sha256, verdict, votes_good: 0, votes_bad: 0 })
      .select().single();
    if (error) return fail(res, 'No se pudo guardar el hash', 500);
    ok(res, data, 201);
  });

  // POST /hashes/vote  { id, verdict: 'good' | 'bad' }
  router.post('/hashes/vote', async (req, res) => {
    if (!supabase) return fail(res, 'Backend sin Supabase', 503);
    if (!rate(ip(req), 'hv', 10, 60000)) return fail(res, 'Demasiados votos. Espera un momento.', 429);
    const id = cleanStr(req.body?.id || '', 30);
    const verdict = req.body?.verdict;
    if (!id || !['good', 'bad'].includes(verdict)) return fail(res, 'Voto no válido');
    const { data: row } = await supabase.from('community_hashes').select('votes_good,votes_bad').eq('id', id).single();
    if (!row) return fail(res, 'Hash no encontrado', 404);
    const upd = verdict === 'good'
      ? { votes_good: (row.votes_good || 0) + 1 }
      : { votes_bad: (row.votes_bad || 0) + 1 };
    const { error } = await supabase.from('community_hashes').update(upd).eq('id', id);
    if (error) return fail(res, 'No se pudo registrar el voto', 500);
    ok(res, { id, votes_good: (row.votes_good || 0) + (verdict === 'good' ? 1 : 0), votes_bad: (row.votes_bad || 0) + (verdict === 'bad' ? 1 : 0) });
  });

  return router;
}

module.exports = { createCommunityRouter, ensureCommunityTables, COMMUNITY_SQL };