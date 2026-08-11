/* ═══════════════════════════════════════
   admin-hub.js — CodeHub by Wilson.E
   Backend: Render · DB: MongoDB + Supabase
═══════════════════════════════════════ */

const BACKEND = 'https://codehub-98s6.onrender.com';
// ⚠️ La contraseña se valida contra el BACKEND (variable ADMIN_KEY en Render)
let ADMIN_KEY = '';

// ── VALIDADOR DE LINKS DE IMAGEN ──────────────────────────────
// Mismo criterio de aceptación que usa Orion Store para sus
// miniaturas: extensión de imagen conocida, capturas scrapeadas
// de Google Play, o raw de GitHub/GitLab/Codeberg (incluye el
// proxy camo.githubusercontent.com). No bloquea rutas locales
// (/img/...) ni el campo vacío.
function isAcceptedImageLink(url) {
  if (!url) return true; // vacío: se valida aparte
  // Ruta local del repo, con o sin "/" inicial — ej: img/Nombre.png o /img/Nombre.png
  // (así es como apps_data.json guarda las rutas, sin barra inicial)
  if (!/^https?:\/\//i.test(url)) {
    const ext = (url.toLowerCase().split('?')[0] || '').split('#')[0];
    return ['.jpg','.jpeg','.png','.webp','.gif','.bmp','.avif','.svg'].some(e => ext.endsWith(e));
  }
  try {
    const u = new URL(url);
    const host = u.hostname;
    const path = u.pathname;
    if (host.startsWith('private-user')) return false;
    const ext = (path.toLowerCase().split('?')[0] || '').split('#')[0];
    if (['.jpg','.jpeg','.png','.webp','.gif','.bmp','.avif','.svg'].some(e => ext.endsWith(e))) return true;
    if (host.endsWith('googleusercontent.com') && path.startsWith('/play-lh')) return true;
    if (host === 'raw.githubusercontent.com') return true;
    if (host === 'camo.githubusercontent.com') return true;
    if ((host.endsWith('gitlab.com') || host.endsWith('gitlab.io')) && path.includes('/-/raw/')) return true;
    if (host === 'codeberg.org' && path.includes('/raw/branch/')) return true;
    // F-Droid: íconos oficiales de apps open source publicadas ahí,
    // en /repo/icons-*/{packageName}.{versionCode}.png
    if (host === 'f-droid.org' && path.startsWith('/repo/')) return true;
    if (host === 'imgs.f-droid.org') return true;
    return false;
  } catch { return false; }
}

// ── CONVERSOR DE LINKS A DESCARGA DIRECTA ────────────────────
// Convierte links de plataformas en URLs de descarga directa.
// Actualizado con los formatos reales vigentes en 2026.
function convertToDirectLink(url) {
  if (!url || url === '#') return url;
  try {
    const u    = new URL(url);
    const host = u.hostname.replace('www.', '');
    const path = u.pathname;

    // ── Google Drive ──────────────────────────────────────────
    // Soporta todos los formatos actuales de Drive:
    //   /file/d/FILE_ID/view  →  export=download
    //   /file/d/FILE_ID/edit  →  export=download
    //   open?id=FILE_ID       →  export=download
    //   uc?id=FILE_ID         →  ya es descarga (normalizar)
    if (host === 'drive.google.com') {
      let id = u.searchParams.get('id');
      if (!id) {
        const m = path.match(/\/d\/([a-zA-Z0-9_-]{10,})/);
        if (m) id = m[1];
      }
      if (id) {
        // Usar el endpoint de export que funciona sin confirmación
        return `https://drive.usercontent.google.com/download?id=${id}&export=download&authuser=0`;
      }
      return url;
    }

    // ── Dropbox ───────────────────────────────────────────────
    // Formatos:
    //   dropbox.com/s/HASH/file.apk?dl=0       → dl=1
    //   dropbox.com/scl/fi/HASH/file.apk?...   → dl=1
    //   dl.dropboxusercontent.com/...           → ya es directo
    if (host === 'dropbox.com' || host === 'dl.dropboxusercontent.com') {
      // Eliminar parámetro rlkey si existe (causa problemas) — mantener solo dl
      const rlkey = u.searchParams.get('rlkey');
      const newU  = new URL(url);
      newU.searchParams.set('dl', '1');
      // Cambiar dominio a dl.dropboxusercontent.com para descarga directa garantizada
      if (host === 'dropbox.com') {
        newU.hostname = 'dl.dropboxusercontent.com';
        // Limpiar parámetros innecesarios dejando solo dl y rlkey si existe
        const dl     = newU.searchParams.get('dl');
        newU.search  = '';
        newU.searchParams.set('dl', '1');
        if (rlkey) newU.searchParams.set('rlkey', rlkey);
      }
      return newU.toString();
    }

    // ── OneDrive ──────────────────────────────────────────────
    // onedrive.live.com/redir?resid=...  → /download?resid=...
    // 1drv.ms/u/s!...                    → se deja (acortador)
    if (host === 'onedrive.live.com') {
      u.pathname = u.pathname.replace('/redir', '/download');
      u.searchParams.delete('authkey');
      u.searchParams.set('download', '1');
      return u.toString();
    }
    if (host === '1drv.ms') return url;

    // ── MediaFire ─────────────────────────────────────────────
    // mediafire.com/file/HASH/nombre.apk/file
    // El link /file/ redirige a página HTML — no hay API pública.
    // Lo dejamos igual; el usuario llega a la página y descarga.
    // Marcamos como "no convertible" para el preview.
    if (host === 'mediafire.com') return url;

    // ── MEGA ──────────────────────────────────────────────────
    // No tiene descarga directa pública — requiere cliente MEGA.
    if (host === 'mega.nz' || host === 'mega.co.nz') return url;

    // ── Terabox / 1024terabox ─────────────────────────────────
    if (host === 'terabox.com' || host === '1024terabox.com') return url;

    // ── GitHub Releases ───────────────────────────────────────
    // github.com/user/repo/releases/download/... ya es directo
    if (host === 'github.com' && path.includes('/releases/download/')) return url;
    // github.com/user/repo/releases/tag/... → no es descarga directa
    if (host === 'github.com' && path.includes('/releases/tag/')) return url;

    // ── Telegram file links ───────────────────────────────────
    if (host === 'api.telegram.org') return url;

    // ── Supabase Storage ─────────────────────────────────────
    if (host.includes('supabase.co') || host.includes('supabase.in')) return url;

    // ── Archive.org ───────────────────────────────────────────
    // archive.org/download/ITEM/file.apk ya es descarga directa
    if (host === 'archive.org' || host.includes('archive.org')) return url;

    // ── Cualquier otro → sin cambios ──────────────────────────
    return url;
  } catch {
    return url;
  }
}

// ── DETECTAR PLATAFORMA ───────────────────────────────────────
function detectPlatform(url) {
  if (!url || url === '#') return null;
  try {
    const host = new URL(url).hostname.replace('www.', '');
    if (host === 'drive.google.com' || host === 'drive.usercontent.google.com') return 'gdrive';
    if (host === 'dropbox.com' || host === 'dl.dropboxusercontent.com')         return 'dropbox';
    if (host === 'onedrive.live.com' || host === '1drv.ms')                     return 'onedrive';
    if (host === 'mediafire.com')                                                return 'mediafire';
    if (host === 'mega.nz' || host === 'mega.co.nz')                            return 'mega';
    if (host === 'terabox.com' || host === '1024terabox.com')                   return 'terabox';
    if (host === 'github.com')                                                   return 'github';
    if (host === 'api.telegram.org')                                             return 'telegram';
    if (host.includes('supabase'))                                               return 'supabase';
    if (host === 'archive.org' || host.includes('archive.org'))                  return 'archive';
    return 'other';
  } catch { return null; }
}

// Íconos y labels por plataforma
const PLATFORM_INFO = {
  gdrive:    { icon: 'fab fa-google-drive', label: 'Google Drive',  color: '#4285f4', direct: true  },
  dropbox:   { icon: 'fab fa-dropbox',      label: 'Dropbox',       color: '#0061ff', direct: true  },
  onedrive:  { icon: 'fab fa-microsoft',    label: 'OneDrive',      color: '#0078d4', direct: true  },
  mediafire: { icon: 'fas fa-fire',         label: 'MediaFire',     color: '#ef3724', direct: false },
  mega:      { icon: 'fas fa-m',            label: 'MEGA',          color: '#d9272e', direct: false },
  terabox:   { icon: 'fas fa-box',          label: 'Terabox',       color: '#1677ff', direct: false },
  github:    { icon: 'fab fa-github',       label: 'GitHub',        color: '#00e676', direct: true  },
  telegram:  { icon: 'fab fa-telegram',     label: 'Telegram',      color: '#229ed9', direct: true  },
  supabase:  { icon: 'fas fa-database',     label: 'Supabase',      color: '#3ecf8e', direct: true  },
  archive:   { icon: 'fas fa-building-columns', label: 'Archive.org', color: '#428bca', direct: true  },
  other:     { icon: 'fas fa-link',         label: 'Link externo',  color: '#aaa',    direct: null  },
};

// ── PREVIEW DE CONVERSIÓN DE LINK (en tiempo real + al cargar) ────
function previewLink(input, spanId) {
  const span = document.getElementById(spanId);
  if (!span) return;
  const raw = (input.value || '').trim();
  if (!raw || raw === '#') { span.innerHTML = ''; return; }

  const converted = convertToDirectLink(raw);
  const platform  = detectPlatform(raw);
  const info      = PLATFORM_INFO[platform] || PLATFORM_INFO.other;

  const platformBadge = platform
    ? `<span style="display:inline-flex;align-items:center;gap:.25rem;background:${info.color}22;color:${info.color};border:1px solid ${info.color}44;border-radius:999px;padding:.1rem .45rem;font-size:.55rem;font-weight:700;margin-right:.3rem">
        <i class="${info.icon}" style="font-size:.55rem"></i>${info.label}
      </span>`
    : '';

  if (converted !== raw) {
    // Link convertible — mostrar resultado
    span.innerHTML = `${platformBadge}<span style="color:#00e5ff;font-size:.55rem">⚡ Convertido →</span> <span style="opacity:.6;font-size:.52rem;word-break:break-all">${converted}</span>`;
  } else if (info.direct === false) {
    // Plataforma sin descarga directa
    span.innerHTML = `${platformBadge}<span style="color:var(--a);font-size:.55rem">⚠️ Sin descarga directa — el usuario verá la página de ${info.label}</span>`;
  } else if (info.direct === true || platform) {
    // Ya es link directo o plataforma reconocida
    span.innerHTML = `${platformBadge}<span style="color:#00e676;font-size:.55rem">✅ Link directo</span>`;
  } else {
    span.innerHTML = '';
  }
}

// ── AUTH ──────────────────────────────────────────────────────
async function checkLogin() {
  const pwd = document.getElementById('pwd-input').value.trim();
  if (!pwd) return;
  const tsToken = document.querySelector('#ts-admin input[name="cf-turnstile-response"]')?.value
               || document.querySelector('input[name="cf-turnstile-response"]')?.value || '';
  const tsLoaded = document.querySelector('#ts-admin iframe');
  if (tsLoaded && !tsToken) {
    document.getElementById('login-err').textContent = '⚠️ Completa la verificación de seguridad';
    return;
  }
  const btn = document.querySelector('.login-btn');
  btn.disabled = true; btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Verificando...';
  try {
    const res = await fetch(`${BACKEND}/api/admin/apps`, { headers: { 'x-admin-key': pwd } });
    if (res.ok) {
      ADMIN_KEY = pwd;
      document.getElementById('login-screen').style.display = 'none';
      document.getElementById('admin-wrap').style.display = 'flex';
      document.getElementById('admin-wrap').style.flexDirection = 'column';
      const data = await res.json();
      initAdmin(data.apps);
    } else {
      document.getElementById('login-err').textContent = '❌ Contraseña incorrecta';
      document.getElementById('pwd-input').value = '';
    }
  } catch {
    document.getElementById('login-err').textContent = '❌ Error de conexión con el servidor';
  }
  btn.disabled = false; btn.innerHTML = '<i class="fas fa-unlock"></i> Ingresar';
}
document.addEventListener('DOMContentLoaded', () => {
  document.getElementById('pwd-input')?.addEventListener('keydown', e => { if (e.key === 'Enter') checkLogin(); });
});

function logout() {
  ADMIN_KEY = '';
  document.getElementById('login-screen').style.display = 'flex';
  document.getElementById('admin-wrap').style.display = 'none';
  document.getElementById('pwd-input').value = '';
  document.getElementById('login-err').textContent = '';
}

// ── TABS ──────────────────────────────────────────────────────
function switchTab(id, btn) {
  document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.admin-tab').forEach(b => b.classList.remove('active'));
  document.getElementById('tab-' + id).classList.add('active');
  btn.classList.add('active');
  if (id === 'requests') loadAdminRequests();
  if (id === 'stats')    { loadAdminRatings(); loadSupabaseStats(); }
  if (id === 'add')      renderAddForm();
  if (id === 'visitors') loadVisitors();
  if (id === 'status')   checkStatus();
  if (id === 'dbrun')    updateDbRunBtn();
  if (id === 'github')   loadGhAutomation();
}

// ── INIT ──────────────────────────────────────────────────────
let appsData = [];

function initAdmin(apps) {
  appsData = apps || [];
  renderApps();
  loadAdminStats();
}

// ── GITHUB AUTOMATION ─────────────────────────────────────────
const GH_WORKFLOWS = [
  { file: 'seed-foss-catalog.yml',      name: '🌱 Seed catálogo FOSS',      desc: 'Upsert de las apps de foss-catalog-seed.json en MongoDB + check de versiones.' },
  { file: 'check-app-updates.yml',      name: '🔄 Monitor de actualizaciones', desc: 'Revisa GitHub Releases del catálogo y actualiza versiones/APKs.' },
  { file: 'dedupe-catalog.yml',         name: '🧹 Limpiar duplicados',      desc: 'Elimina apps duplicadas (dry-run por defecto).' },
  { file: 'autoposter-workflow.yml',    name: '🤖 AutoPoster Blogger',      desc: 'Publica el siguiente post del blog en cola.' },
  { file: 'enrich-app-logos.yml',       name: '🖼️ Aplicar logos FOSS',      desc: 'Busca el logo real (fastlane/mipmap/avatar org) para apps que muestran la portada del repo y lo sube a img/.' },
];

async function loadGhAutomation() {
  const listEl  = document.getElementById('gh-workflow-list');
  const runsEl  = document.getElementById('gh-runs-list');
  listEl.innerHTML = GH_WORKFLOWS.map(w =>
    `<div class="blog-row" style="display:flex;align-items:center;gap:.8rem;justify-content:space-between;flex-wrap:wrap;padding:.7rem;border:1px solid var(--border);border-radius:9px;margin-bottom:.55rem">
       <div style="min-width:0;flex:1">
         <div style="font-weight:700;font-size:.8rem">${w.name}</div>
         <div style="font-size:.7rem;color:var(--muted);margin-top:.15rem">${w.desc}</div>
       </div>
       <button class="blog-btn-primary" onclick="dispatchWorkflow('${w.file}', this)" style="white-space:nowrap">
         <i class="fas fa-play"></i> Ejecutar
       </button>
     </div>`
  ).join('');
  await loadGhRuns();
}

async function loadGhRuns() {
  const runsEl = document.getElementById('gh-runs-list');
  try {
    const res = await fetch(`${BACKEND}/api/admin/github/runs`, { headers: { 'x-admin-key': ADMIN_KEY } });
    if (!res.ok) { runsEl.innerHTML = `<div style="color:var(--muted);font-size:.72rem">${(await res.json()).error || 'Error cargando runs'}</div>`; return; }
    const { runs } = await res.json();
    const rows = GH_WORKFLOWS.map(w => {
      const r = runs[w.file];
      if (!r) return `<div style="font-size:.72rem;padding:.35rem 0"><b style="color:var(--text)">${w.name}:</b> <span style="color:var(--muted)">sin ejecuciones todavía</span></div>`;
      const icon = r.conclusion === 'success' ? '✅' : (r.conclusion ? '❌' : (r.status === 'completed' ? '⚠️' : '⏳'));
      const when = new Date(r.created_at).toLocaleString();
      return `<div style="font-size:.72rem;padding:.35rem 0;display:flex;align-items:center;gap:.5rem;flex-wrap:wrap">
        <b style="color:var(--text)">${w.name}:</b>
        <span>${icon} ${r.status}${r.conclusion ? ' / ' + r.conclusion : ''}</span>
        <span style="color:var(--muted)">${when}</span>
        <a href="${r.html_url}" target="_blank" rel="noopener" style="color:var(--a)">ver run ↗</a>
      </div>`;
    }).join('');
    runsEl.innerHTML = rows || '<div style="color:var(--muted);font-size:.7rem">Sin datos.</div>';
  } catch (e) {
    runsEl.innerHTML = `<div style="color:var(--muted);font-size:.72rem">Error de conexión: ${e.message}</div>`;
  }
}

async function dispatchWorkflow(file, btn) {
  const w = GH_WORKFLOWS.find(x => x.file === file);
  if (!confirm(`¿Disparar el workflow "${w ? w.name : file}"?`)) return;
  btn.disabled = true;
  btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Disparando…';
  try {
    const res = await fetch(`${BACKEND}/api/admin/github/dispatch`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-admin-key': ADMIN_KEY },
      body: JSON.stringify({ workflow: file }),
    });
    const data = await res.json().catch(() => ({}));
    if (res.ok) {
      toast('🚀 Workflow disparado. Revisá la pestaña de runs en un momento.');
      setTimeout(loadGhRuns, 4000);
    } else {
      alert('❌ ' + (data.error || 'Error disparando workflow'));
    }
  } catch (e) {
    alert('❌ Error de conexión: ' + e.message);
  }
  btn.disabled = false;
  btn.innerHTML = '<i class="fas fa-play"></i> Ejecutar';
}

// ── DETECCIÓN DE DUPLICADOS ─────────────────────────────────────
// Se ejecuta en el cliente sobre `appsData` (ya cargado), así que
// no depende de un match exacto en MongoDB — normaliza (trim,
// minúsculas, sin ".git" ni "/" final) antes de comparar, por eso
// detecta pares que el dedupe-script por igualdad exacta se salta
// (ej. "TeamNewPipe/NewPipe" vs "teamnewpipe/newpipe ").
// Agrupa por:
//   1) source_repo normalizado (si la app lo tiene) — más confiable,
//      dos apps nunca deberían compartir el mismo repo de GitHub.
//   2) nombre normalizado — respaldo para apps sin source_repo o con
//      source_repo distinto por error.
let showOnlyDuplicates = false;

function normRepo(s)   { return (s || '').trim().toLowerCase().replace(/\.git$/, '').replace(/\/+$/, ''); }
function normNombre(s) { return (s || '').trim().toLowerCase(); }

function computeDuplicateGroups() {
  const byRepo = {}, byNombre = {};
  appsData.forEach(a => {
    const r = normRepo(a.source_repo);
    if (r) (byRepo[r] = byRepo[r] || []).push(a);
    const n = normNombre(a.nombre);
    if (n) (byNombre[n] = byNombre[n] || []).push(a);
  });

  // appId -> { group: [apps...], keepAppId, reason }
  const dupInfo = {};
  const groups = [...Object.values(byRepo), ...Object.values(byNombre)]
    .filter(g => g.length > 1);

  groups.forEach(group => {
    // Deduplicar por si el mismo grupo ya se armó por las dos vías.
    const uniq = [...new Map(group.map(a => [a.appId, a])).values()];
    if (uniq.length < 2) return;
    // Sugerencia de cuál conservar: el que tenga enlace real, y entre
    // esos el más reciente por updatedAt.
    const conEnlace = uniq.filter(a => a.enlace && a.enlace !== '#');
    const pool = conEnlace.length ? conEnlace : uniq;
    const keep = pool.slice().sort((a, b) => new Date(b.updatedAt || 0) - new Date(a.updatedAt || 0))[0];
    uniq.forEach(a => {
      dupInfo[a.appId] = { group: uniq, keepAppId: keep.appId };
    });
  });

  return dupInfo;
}

function toggleDuplicateFilter() {
  showOnlyDuplicates = !showOnlyDuplicates;
  renderApps();
}

async function deleteAppSilent(appId) {
  const res = await fetch(`${BACKEND}/api/admin/apps/${appId}`, {
    method: 'DELETE', headers: { 'x-admin-key': ADMIN_KEY }
  });
  if (!res.ok) throw new Error((await res.json()).error || `HTTP ${res.status}`);
}

async function deleteDuplicateGroup(appIds, keepAppId) {
  const losers = appIds.filter(id => id !== keepAppId);
  const names = losers.map(id => (appsData.find(a => a.appId === id) || {}).nombre || id).join(', ');
  if (!confirm(`Se conservará "${keepAppId}" y se eliminará: ${names}\n\n¿Continuar? No se puede deshacer.`)) return;
  let ok = 0, fail = 0;
  for (const id of losers) {
    try { await deleteAppSilent(id); ok++; }
    catch (e) { fail++; console.error('Error borrando', id, e); }
  }
  appsData = appsData.filter(a => !losers.includes(a.appId));
  renderApps();
  toast(fail ? `⚠️ ${ok} eliminada(s), ${fail} con error` : `🗑️ ${ok} duplicado(s) eliminado(s)`);
}

// ── RENDER APPS ───────────────────────────────────────────────
function renderApps() {
  const list = document.getElementById('apps-list');
  const verified = appsData.filter(a => a.verified).length;
  const set = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v; };
  set('st-apps', appsData.length);
  set('st-verified', verified);

  const dupInfo = computeDuplicateGroups();
  const dupAppIds = Object.keys(dupInfo);
  const dupGroupCount = new Set(dupAppIds.map(id => dupInfo[id].group.map(a => a.appId).sort().join('|'))).size;

  const banner = document.getElementById('dup-banner');
  if (banner) {
    banner.style.display = dupGroupCount ? 'flex' : 'none';
    banner.innerHTML = dupGroupCount ? `
      <span><i class="fas fa-triangle-exclamation"></i> ${dupGroupCount} grupo(s) de apps duplicadas detectadas (${dupAppIds.length} filas) — resaltadas en rojo abajo.</span>
      <button onclick="toggleDuplicateFilter()" style="padding:.3rem .7rem;border-radius:8px;background:rgba(255,107,107,.12);border:1px solid rgba(255,107,107,.3);color:#ff6b6b;font-family:var(--mono);font-size:.62rem;cursor:pointer;white-space:nowrap">
        ${showOnlyDuplicates ? '<i class="fas fa-list"></i> Ver todas' : '<i class="fas fa-filter"></i> Ver solo duplicadas'}
      </button>` : '';
  }

  const rows = showOnlyDuplicates ? appsData.filter(a => dupInfo[a.appId]) : appsData;

  if (!rows.length) {
    list.innerHTML = `<div style="padding:2rem;text-align:center;font-family:var(--mono);font-size:.76rem;color:var(--muted)">${showOnlyDuplicates ? 'No hay duplicados 🎉' : 'No hay apps. Usa "Seed desde JSON" o agrega una manualmente.'}</div>`;
    return;
  }

  list.innerHTML = rows.map(app => {
    const enlace    = app.enlace || '#';
    const pluginEnl = app.plugin_enlace || '';
    // Detectar qué storage tiene el APK principal
    const storageBadge = (() => {
      const url = enlace;
      if (!url || url === '#') return '';
      if (url.includes('archive.org'))    return '<small style="color:#428bca">🏛️ Archive.org</small>';
      if (url.includes('api.telegram'))   return '<small style="color:#229ed9">📨 Telegram</small>';
      if (url.includes('supabase'))       return '<small style="color:var(--g)">☁️ Supabase</small>';
      if (url.includes('drive.google'))   return '<small style="color:#4285f4">🔵 Drive</small>';
      if (url.includes('dropbox'))        return '<small style="color:#0061ff">📦 Dropbox</small>';
      return '';
    })();
    return `
    <div class="app-row" id="row-${app.appId}" style="${dupInfo[app.appId] ? 'outline:2px solid rgba(255,107,107,.55);outline-offset:-1px;background:rgba(255,107,107,.05)' : ''}">
      <div class="app-name-cell">
        <div style="display:flex;align-items:center;gap:.5rem;margin-bottom:.3rem">
          <img id="img-prev-${app.appId}" src="${app.imagen || ''}" alt="" style="width:36px;height:36px;border-radius:10px;object-fit:cover;background:var(--card2);flex-shrink:0;${app.imagen ? '' : 'display:none'}" onerror="this.style.display='none'">
          <div>
            ${app.nombre}
            <small>${app.categoria || ''} · ${app.appId}</small>
          </div>
        </div>
        ${dupInfo[app.appId] ? `
        <div style="margin-top:.35rem;padding:.4rem .5rem;border-radius:8px;background:rgba(255,107,107,.1);border:1px solid rgba(255,107,107,.25)">
          <div style="font-family:var(--mono);font-size:.56rem;color:#ff6b6b;font-weight:700;margin-bottom:.25rem">
            <i class="fas fa-clone"></i> Duplicado con: ${dupInfo[app.appId].group.filter(g => g.appId !== app.appId).map(g => g.appId).join(', ')}
          </div>
          ${app.appId === dupInfo[app.appId].keepAppId
            ? `<span style="font-family:var(--mono);font-size:.55rem;color:var(--g)"><i class="fas fa-check"></i> Sugerido: conservar esta</span>`
            : `<span style="font-family:var(--mono);font-size:.55rem;color:var(--muted)">Sugerido: conservar "${dupInfo[app.appId].keepAppId}"</span>`}
          <button onclick='deleteDuplicateGroup(${JSON.stringify(dupInfo[app.appId].group.map(g => g.appId))}, "${dupInfo[app.appId].keepAppId}")' style="display:block;margin-top:.3rem;width:100%;padding:.28rem .4rem;border-radius:7px;background:rgba(255,107,107,.15);border:1px solid rgba(255,107,107,.35);color:#ff6b6b;font-family:var(--mono);font-size:.58rem;cursor:pointer;font-weight:700">
            <i class="fas fa-broom"></i> Resolver duplicado (conservar ${dupInfo[app.appId].keepAppId})
          </button>
        </div>` : ''}
        ${storageBadge}
        <input class="ver-input" type="text" id="img-${app.appId}" value="${(app.imagen || '').replace(/"/g,'&quot;')}" placeholder="/img/NombreApp.png o URL" style="width:100%;margin-top:.35rem;font-size:.56rem" oninput="previewRowImagen('${app.appId}')">
        <div id="img-hint-${app.appId}" style="font-size:.55rem;color:var(--muted);margin-top:.2rem;word-break:break-all"></div>
        <div style="display:flex;gap:.3rem;margin-top:.35rem">
          <input class="ver-input" type="text" id="srcurl-${app.appId}" placeholder="🔗 Repo GitHub / F-Droid / Play Store / imagen" style="flex:1;font-size:.55rem">
          <button onclick="extractIcon('${app.appId}')" id="extract-btn-${app.appId}" title="Extraer el ícono real y subirlo a img/ en tu repo" style="padding:.28rem .5rem;border-radius:8px;background:rgba(0,229,255,.08);border:1px solid rgba(0,229,255,.2);color:var(--c);font-family:var(--mono);font-size:.55rem;cursor:pointer;white-space:nowrap;flex-shrink:0">
            <i class="fas fa-download"></i> Extraer
          </button>
        </div>
      </div>
      <div>
        <input class="ver-input" type="text" value="${app.version || ''}" id="ver-${app.appId}" placeholder="1.0.0">
      </div>
      <div>
        <span class="badge-verified ${app.verified ? 'yes' : 'no'}" id="vbadge-${app.appId}" onclick="toggleVerified('${app.appId}')">
          ${app.verified ? '✅ Sí' : '○ No'}
        </span>
      </div>
      <div>
        <select style="background:rgba(255,255,255,.05);border:1px solid var(--border);border-radius:7px;padding:.28rem .4rem;color:var(--text);font-family:var(--mono);font-size:.63rem;outline:none;width:100%" id="badge-${app.appId}">
          <option value="🆕" ${app.tag==='🆕'||app.tag==='🆕 Nuevo'?'selected':''}>🆕 Nuevo</option>
          <option value="🔄 Actualizada" ${app.tag==='🔄 Actualizada'?'selected':''}>🔄 Actualizada</option>
          <option value="🔥 Popular" ${app.tag==='🔥 Popular'?'selected':''}>🔥 Popular</option>
          <option value="⚡ Beta" ${app.tag==='⚡ Beta'?'selected':''}>⚡ Beta</option>
          <option value="⭐" ${app.tag==='⭐'?'selected':''}>⭐ Destacada</option>
        </select>
      </div>
      <div style="display:flex;flex-direction:column;gap:.38rem">
        <textarea class="changelog-input" rows="2" id="cl-${app.appId}">${app.changelog || ''}</textarea>
        <div style="display:flex;align-items:center;gap:.4rem">
          <label style="font-family:var(--mono);font-size:.56rem;color:var(--muted);white-space:nowrap">📅 Actualizado:</label>
          <input class="ver-input" type="date" id="upd-${app.appId}" value="${app.updatedAt ? app.updatedAt.substring(0,10) : new Date().toISOString().substring(0,10)}" style="flex:1;font-size:.58rem">
        </div>
        <input class="ver-input" type="url" value="${enlace === '#' ? '' : enlace}" id="link-${app.appId}" placeholder="https://... (o sube APK)" oninput="previewLink(this,'lp-${app.appId}')">
        <span id="lp-${app.appId}" style="font-family:var(--mono);font-size:.55rem;color:var(--muted);word-break:break-all;min-height:.8rem;display:block"></span>
        <input class="ver-input" type="url" value="${pluginEnl}" id="plugin-${app.appId}" placeholder="🧩 Plugin URL (opcional)" oninput="previewLink(this,'pp-${app.appId}')">
        <span id="pp-${app.appId}" style="font-family:var(--mono);font-size:.55rem;color:var(--muted);word-break:break-all;min-height:.8rem;display:block"></span>
        <input class="ver-input" type="url" value="${app.tutorial_url||''}" id="tutorial-${app.appId}" placeholder="🎬 Tutorial YouTube (opcional)" style="border-color:rgba(255,0,80,.18)">
      </div>
      <div style="display:flex;flex-direction:column;gap:.38rem">
        <button class="save-row-btn" id="sbtn-${app.appId}" onclick="saveRow('${app.appId}')">
          <i class="fas fa-save"></i> Guardar
        </button>
        <label style="cursor:pointer;display:block">
          <span style="display:flex;align-items:center;justify-content:center;gap:.35rem;padding:.3rem .5rem;border-radius:8px;background:rgba(0,229,255,.08);border:1px solid rgba(0,229,255,.2);color:var(--c);font-family:var(--mono);font-size:.6rem;font-weight:700">
            <i class="fas fa-cloud-arrow-up"></i> APK → Storage
          </span>
          <input type="file" accept=".apk" style="display:none" onchange="uploadAPK('${app.appId}','main',this)">
        </label>
        <button onclick="openPreview('${app.appId}')" style="padding:.28rem .5rem;border-radius:8px;background:rgba(255,189,69,.08);border:1px solid rgba(255,189,69,.2);color:var(--a);font-family:var(--mono);font-size:.6rem;cursor:pointer">
          <i class="fas fa-eye"></i> Preview
        </button>
        <button onclick="deleteAPKFile('${app.appId}','main','${app.nombre.replace(/'/g,'&#39;')}')" style="padding:.28rem .5rem;border-radius:8px;background:rgba(255,140,0,.08);border:1px solid rgba(255,140,0,.25);color:#ffb347;font-family:var(--mono);font-size:.6rem;cursor:pointer">
          <i class="fas fa-file-circle-xmark"></i> Limpiar APK
        </button>
        <button onclick="deleteApp('${app.appId}','${app.nombre.replace(/'/g,'&#39;')}')" style="padding:.28rem .5rem;border-radius:8px;background:rgba(255,107,107,.08);border:1px solid rgba(255,107,107,.2);color:#ff6b6b;font-family:var(--mono);font-size:.6rem;cursor:pointer">
          <i class="fas fa-trash"></i> Eliminar
        </button>
      </div>
    </div>`;
  }).join('');

  // ── Auto-iniciar preview para inputs ya cargados con links que serán convertidos
  appsData.forEach(app => {
    const enlace    = app.enlace || '#';
    const plugin    = app.plugin_enlace || '';
    const linkInput   = document.getElementById('link-'   + app.appId);
    const pluginInput = document.getElementById('plugin-' + app.appId);
    if (linkInput)   previewLink(linkInput,   'lp-' + app.appId);
    if (pluginInput && plugin) previewLink(pluginInput, 'pp-' + app.appId);
    // Muestra de una el aviso si la imagen guardada es un banner de repo
    // u otro link no reconocido, sin que el admin tenga que tocar el campo.
    if (app.imagen) previewRowImagen(app.appId);
  });
}

// ── TOGGLE VERIFIED ───────────────────────────────────────────
function toggleVerified(appId) {
  const app = appsData.find(a => a.appId === appId);
  if (!app) return;
  app.verified = !app.verified;
  const badge = document.getElementById('vbadge-' + appId);
  badge.className = 'badge-verified ' + (app.verified ? 'yes' : 'no');
  badge.textContent = app.verified ? '✅ Sí' : '○ No';
}

// ── NORMALIZADOR DE RUTAS LOCALES DE IMAGEN ─────────────────────
// getOptimizedImageUrl() (opensource.js) solo trata como ruta local
// los valores que empiezan con "/" — cualquier otra cosa la manda
// al proxy wsrv.nl como si fuera una URL externa, y una ruta local
// sin "/" inicial (ej: "img/kernelsu.png") se rompe en el sitio.
// Esta función corrige eso automáticamente: si es local y no trae
// "/" inicial, se la agrega. URLs http(s), data: y blob: no se tocan.
function normalizeImagePath(val) {
  if (!val) return val;
  if (/^https?:\/\//i.test(val) || val.startsWith('data:') || val.startsWith('blob:') || val.startsWith('/')) return val;
  return '/' + val.replace(/^\.?\/+/, '');
}

// ── PREVIEW EN VIVO DEL ÍCONO AL EDITAR UNA FILA ────────────────
// Mismo criterio que previewNewImagen(), pero por appId, para poder
// cambiar la miniatura de las apps ya publicadas desde el panel.
let _rowImgDebounce = {};
function previewRowImagen(appId) {
  clearTimeout(_rowImgDebounce[appId]);
  _rowImgDebounce[appId] = setTimeout(() => {
    const input = document.getElementById('img-' + appId);
    const img   = document.getElementById('img-prev-' + appId);
    const hint  = document.getElementById('img-hint-' + appId);
    if (!input || !img || !hint) return;
    const val = normalizeImagePath(input.value.trim());

    if (!val) { img.style.display = 'none'; hint.textContent = ''; return; }

    if (val.includes('opengraph.githubassets.com')) {
      img.style.display = 'none';
      hint.textContent = '⚠️ Ese es el banner/portada social del repo, no el ícono. Usa el ícono real (assets/, mipmap-xxxhdpi/, etc.)';
      hint.style.color = 'var(--danger, #f66)';
      return;
    }

    if (!isAcceptedImageLink(val)) {
      img.style.display = 'none';
      hint.textContent = '⚠️ Link no reconocido — usa jpg/png/webp, captura de Play Store o raw de GitHub/GitLab/Codeberg';
      hint.style.color = 'var(--danger, #f66)';
      return;
    }

    const optimized = (typeof getOptimizedImageUrl === 'function' && !val.startsWith('/'))
      ? getOptimizedImageUrl(val, 72, 72)
      : val;
    img.src = optimized;
    img.style.display = 'block';
    img.onerror = () => { hint.textContent = '⚠️ El link parece válido pero la imagen no cargó — verifícalo'; hint.style.color = 'var(--danger, #f66)'; };
    img.onload  = () => { hint.textContent = '✅ Imagen cargada correctamente'; hint.style.color = 'var(--ok, #6f6)'; };
  }, 300);
}

// ── EXTRAER ÍCONO DESDE URL UNIVERSAL (repo/F-Droid/Play Store) ─
// Le pega al backend con el link que el admin ya tiene a mano; el
// backend baja el ícono real (no el banner del repo) y lo sube a
// img/ en GitHub. Al terminar, completa el campo imagen y guarda
// la fila automáticamente para que quede en un solo paso.
async function extractIcon(appId) {
  const app = appsData.find(a => a.appId === appId);
  if (!app) return;
  const urlInput = document.getElementById('srcurl-' + appId);
  const sourceUrl = urlInput?.value.trim();
  if (!sourceUrl) return toast('⚠️ Pegá primero el link del repo, F-Droid, Play Store o la imagen');

  const btn = document.getElementById('extract-btn-' + appId);
  const originalHtml = btn.innerHTML;
  btn.disabled = true; btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i>';
  try {
    const res = await fetch(`${BACKEND}/api/admin/extract-icon`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-admin-key': ADMIN_KEY },
      body: JSON.stringify({ sourceUrl, filename: appId }),
    });
    const d = await res.json();
    if (!res.ok) throw new Error(d.error || 'No se pudo extraer el ícono');

    const imgInput = document.getElementById('img-' + appId);
    if (imgInput) imgInput.value = d.imagen;
    await previewRowImagen(appId);
    toast('✅ Ícono subido a tu repo (' + d.imagen + ') — guardando...');
    await saveRow(appId);
    logAdminActivity('🖼️ Ícono de ' + app.nombre + ' extraído y subido a ' + d.imagen);
  } catch (e) {
    toast('❌ ' + e.message);
  } finally {
    btn.disabled = false; btn.innerHTML = originalHtml;
  }
}

// ── SAVE ROW ──────────────────────────────────────────────────
async function saveRow(appId) {
  const app = appsData.find(a => a.appId === appId);
  if (!app) return;
  const btn = document.getElementById('sbtn-' + appId);
  btn.disabled = true; btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i>';
  const rawEnlace       = document.getElementById('link-' + appId)?.value.trim() || app.enlace;
  const rawPlugin       = document.getElementById('plugin-' + appId)?.value.trim() || null;
  const convertedEnlace = convertToDirectLink(rawEnlace);
  const convertedPlugin = convertToDirectLink(rawPlugin);

  // Reflejar en el input si el link fue transformado
  const linkInput   = document.getElementById('link-' + appId);
  const pluginInput = document.getElementById('plugin-' + appId);
  if (linkInput   && convertedEnlace !== rawEnlace)  linkInput.value   = convertedEnlace;
  if (pluginInput && convertedPlugin !== rawPlugin)   pluginInput.value = convertedPlugin || '';

  const rawImagen   = document.getElementById('img-' + appId)?.value.trim();
  const normImagen  = normalizeImagePath(rawImagen);
  const imgInput    = document.getElementById('img-' + appId);
  if (imgInput && normImagen !== rawImagen) imgInput.value = normImagen;

  const body = {
    version:       document.getElementById('ver-' + appId)?.value.trim(),
    changelog:     document.getElementById('cl-' + appId)?.value.trim(),
    tag:           document.getElementById('badge-' + appId)?.value,
    verified:      app.verified,
    enlace:        convertedEnlace,
    plugin_enlace: convertedPlugin,
    tutorial_url:  document.getElementById('tutorial-' + appId)?.value.trim() || null,
    imagen:        normImagen,
    updatedAt:     document.getElementById('upd-' + appId)?.value || new Date().toISOString().substring(0,10),
  };
  if (normImagen && !isAcceptedImageLink(normImagen)) {
    toast('⚠️ Ese link de imagen puede no cargar bien — usa jpg/png/webp, una captura de Play Store o un raw de GitHub/GitLab/Codeberg');
  }
  try {
    const res = await fetch(`${BACKEND}/api/admin/apps/${appId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', 'x-admin-key': ADMIN_KEY },
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new Error((await res.json()).error);
    const d = await res.json();
    Object.assign(app, d.app);
    btn.classList.add('saved'); btn.innerHTML = '<i class="fas fa-check"></i> OK';
    toast('✅ ' + app.nombre + ' actualizada');

    // ── Registrar en log de actividades ───────────────────
    const verStr = body.version ? ' v' + body.version : '';
    const clShort = body.changelog ? ' · ' + body.changelog.split(' · ')[0].substring(0, 40) : '';
    logAdminActivity('🔄 ' + app.nombre + ' actualizada' + verStr + clShort);

    // ── Disparar push notification a todos los suscriptores ─
    if (body.tag && body.tag.includes('Actualiz')) {
      sendAppUpdatePush(app, body);
    }

  } catch (e) {
    toast('❌ Error: ' + e.message);
    btn.innerHTML = '<i class="fas fa-save"></i> Guardar';
  }
  setTimeout(() => {
    btn.disabled = false; btn.classList.remove('saved');
    btn.innerHTML = '<i class="fas fa-save"></i> Guardar';
  }, 2500);
}

// ── ENVIAR PUSH A SUSCRIPTORES ────────────────────────────────
async function sendAppUpdatePush(app, body) {
  try {
    const payload = {
      type:      'app_update',
      title:     app.nombre + ' se actualizó',
      body:      body.changelog
                   ? body.changelog.split(' · ')[0].substring(0, 80)
                   : 'Nueva versión' + (body.version ? ' ' + body.version : '') + ' disponible',
      appId:     app.appId,
      version:   body.version || '',
      changelog: body.changelog || '',
      icon:      '/splash/codehub.png',
      url:       '/opensource.html',
      updatedAt: new Date().toISOString(),
    };

    const res = await fetch(BACKEND + '/api/push/notify', {
      method:  'POST',
      headers: { 'Content-Type': 'application/json', 'x-admin-key': ADMIN_KEY },
      body:    JSON.stringify(payload),
    });

    if (res.ok) {
      const d = await res.json();
      toast('📲 Push enviado' + (d.sent ? ' · ' + d.sent + ' dispositivos' : ''));
    }
  } catch (e) {
    console.warn('Push dispatch error:', e);
  }
}

// ── LOG DE ACTIVIDADES ADMIN ──────────────────────────────────
function logAdminActivity(msg) {
  const KEY = 'ch_activity_log';
  try {
    const log = JSON.parse(localStorage.getItem(KEY) || '[]');
    log.unshift({ msg, ts: Date.now() });
    if (log.length > 80) log.length = 80;
    localStorage.setItem(KEY, JSON.stringify(log));
  } catch (e) {}
}

// ── UPLOAD APK ────────────────────────────────────────────────
// Usa XHR para obtener progreso real de subida (fetch no expone upload progress).
// Muestra barra de progreso inline junto al botón durante la subida.
function uploadAPK(appId, slot, input) {
  const file = input.files[0];
  if (!file) return;
  const sizeMB  = (file.size / 1024 / 1024).toFixed(1);
  const destino = file.size > 50 * 1024 * 1024 ? '🏛️ Archive.org' : '📨 Telegram';
  const label   = input.previousElementSibling;

  // Crear/reutilizar barra de progreso inline
  let progressWrap = input.parentElement.querySelector('.apk-progress-wrap');
  if (!progressWrap) {
    progressWrap = document.createElement('div');
    progressWrap.className = 'apk-progress-wrap';
    progressWrap.style.cssText = 'margin-top:.4rem;height:5px;background:rgba(255,255,255,.08);border-radius:999px;overflow:hidden;';
    const bar = document.createElement('div');
    bar.className = 'apk-progress-bar';
    bar.style.cssText = 'height:100%;width:0%;background:linear-gradient(90deg,var(--p),var(--p2));border-radius:999px;transition:width .2s;';
    progressWrap.appendChild(bar);
    input.parentElement.appendChild(progressWrap);
  }
  const progressBar = progressWrap.querySelector('.apk-progress-bar');
  progressBar.style.width = '0%';
  progressWrap.style.display = 'block';

  toast(`⬆️ ${file.name} (${sizeMB} MB) → ${destino}...`);
  label.innerHTML = `<i class="fas fa-spinner fa-spin"></i> 0% · ${sizeMB} MB → ${destino}`;
  label.style.color = 'var(--a)';

  const formData = new FormData();
  formData.append('apk', file);
  formData.append('slot', slot);

  const xhr = new XMLHttpRequest();
  xhr.open('POST', `${BACKEND}/api/admin/apps/${appId}/upload`);
  xhr.setRequestHeader('x-admin-key', ADMIN_KEY);

  xhr.upload.addEventListener('progress', (e) => {
    if (!e.lengthComputable) return;
    const pct = Math.round((e.loaded / e.total) * 100);
    progressBar.style.width = pct + '%';
    label.innerHTML = `<i class="fas fa-spinner fa-spin"></i> ${pct}% · ${(e.loaded/1024/1024).toFixed(1)}/${sizeMB} MB → ${destino}`;
  });

  xhr.addEventListener('load', async () => {
    progressBar.style.width = '100%';
    try {
      const d = JSON.parse(xhr.responseText);
      if (xhr.status >= 400) throw new Error(d.error || 'Error del servidor');
      const storageLabel = { telegram: '📨 Telegram', supabase: '☁️ Supabase', archive: '🏛️ Archive.org' }[d.storage] || d.storage;
      toast(`✅ APK subido · ${d.sizeMB} MB · ${storageLabel}`);
      const linkInput = document.getElementById('link-' + appId);
      if (linkInput) linkInput.value = d.downloadUrl;
      const app = appsData.find(a => a.appId === appId);
      if (app) { if (slot === 'plugin') app.plugin_enlace = d.downloadUrl; else app.enlace = d.downloadUrl; }
      label.innerHTML = '<i class="fas fa-check"></i> Subido ✅';
      label.style.color = 'var(--g)';
      setTimeout(() => { progressWrap.style.display = 'none'; progressBar.style.width = '0%'; }, 2000);
      await refreshApps();
    } catch (e) {
      toast('❌ Error: ' + e.message);
      label.innerHTML = '<i class="fas fa-cloud-arrow-up"></i> APK → Storage';
      label.style.color = 'var(--c)';
      progressBar.style.background = '#ff6b6b';
      setTimeout(() => { progressWrap.style.display = 'none'; progressBar.style.background = 'linear-gradient(90deg,var(--p),var(--p2))'; }, 2500);
    }
    input.value = '';
  });

  xhr.addEventListener('error', () => {
    toast('❌ Error de red al subir el APK');
    label.innerHTML = '<i class="fas fa-cloud-arrow-up"></i> APK → Storage';
    label.style.color = 'var(--c)';
    progressWrap.style.display = 'none';
    input.value = '';
  });

  xhr.send(formData);
}

// ── DELETE APP ────────────────────────────────────────────────
// ── LIMPIAR APK (elimina solo el archivo de Telegram/Storage/Archive.org, mantiene la app) ─
async function deleteAPKFile(appId, slot = 'main', nombre = '') {
  if (!confirm(`¿Eliminar el APK de "${nombre}" de Telegram/Storage?\nLa app se mantiene en la tienda — solo se borra el archivo.\nTras esto puedes subir la nueva versión.`)) return;
  try {
    const res = await fetch(`${BACKEND}/api/admin/apps/${appId}/apk?slot=${slot}`, {
      method: 'DELETE', headers: { 'x-admin-key': ADMIN_KEY }
    });
    const d = await res.json();
    if (!res.ok) throw new Error(d.error);
    // Actualizar el enlace localmente para reflejar que ya no hay APK
    const app = appsData.find(a => a.appId === appId);
    if (app) { if (slot === 'plugin') app.plugin_enlace = null; else app.enlace = '#'; }
    renderApps();
    toast(`🗑️ APK eliminado de ${d.deleted.archive ? 'Archive.org' : d.deleted.telegram ? 'Telegram' : 'Storage'}: ${nombre}`);
  } catch (e) { toast('❌ Error: ' + e.message); }
}

async function deleteApp(appId, nombre) {
  if (!confirm(`¿Eliminar "${nombre}" de la tienda? No se puede deshacer.`)) return;
  try {
    const res = await fetch(`${BACKEND}/api/admin/apps/${appId}`, {
      method: 'DELETE', headers: { 'x-admin-key': ADMIN_KEY }
    });
    if (!res.ok) throw new Error((await res.json()).error);
    appsData = appsData.filter(a => a.appId !== appId);
    renderApps();
    toast('🗑️ Eliminada: ' + nombre);
  } catch (e) { toast('❌ Error: ' + e.message); }
}

// ── REFRESH APPS ──────────────────────────────────────────────
async function refreshApps() {
  try {
    const res = await fetch(`${BACKEND}/api/admin/apps`, { headers: { 'x-admin-key': ADMIN_KEY } });
    if (!res.ok) return;
    const d = await res.json();
    appsData = d.apps;
    renderApps();
    toast('🔄 Apps actualizadas');
  } catch {}
}

// ── PREVIEW ───────────────────────────────────────────────────
function openPreview(appId) {
  const app = appsData.find(a => a.appId === appId);
  if (!app) return;
  document.getElementById('prev-img').src = app.imagen || '';
  document.getElementById('prev-name').textContent = app.nombre;
  document.getElementById('prev-ver').textContent = 'v' + (app.version || '—') + ' · ' + (app.categoria || '');
  document.getElementById('prev-desc').textContent = app.descripcion || '';
  document.getElementById('preview-overlay').style.display = 'flex';
}
function closePreview() { document.getElementById('preview-overlay').style.display = 'none'; }

// ── SEARCH desktop ────────────────────────────────────────────
function searchApps(q) {
  const resEl = document.getElementById('srch-res');
  if (!resEl) return;
  if (!q.trim()) { resEl.style.display = 'none'; return; }
  const matches = appsData.filter(a =>
    a.nombre.toLowerCase().includes(q.toLowerCase()) || a.appId.includes(q.toLowerCase())
  );
  if (!matches.length) { resEl.style.display = 'none'; return; }
  resEl.style.display = 'block';
  resEl.innerHTML = matches.map(a => `
    <div class="srch-row" onclick="scrollToApp('${a.appId}')">
      <div><div class="srch-name">${a.nombre}</div><div class="srch-cat">${a.categoria || ''} · v${a.version || '—'}</div></div>
    </div>`).join('');
}

// ── SEARCH mobile ─────────────────────────────────────────────
function searchAppsM(q) {
  const resEl = document.getElementById('srch-res-m');
  if (!resEl) return;
  if (!q.trim()) { resEl.style.display = 'none'; return; }
  const matches = appsData.filter(a =>
    a.nombre.toLowerCase().includes(q.toLowerCase()) || a.appId.includes(q.toLowerCase())
  );
  if (!matches.length) { resEl.style.display = 'none'; return; }
  resEl.style.display = 'block';
  resEl.innerHTML = matches.map(a => `
    <div class="srch-row" onclick="scrollToApp('${a.appId}');document.getElementById('srch-res-m').style.display='none'">
      <div><div class="srch-name">${a.nombre}</div><div class="srch-cat">${a.categoria || ''} · v${a.version || '—'}</div></div>
    </div>`).join('');
}

function scrollToApp(appId) {
  ['srch-res','srch-res-m'].forEach(id => { const el = document.getElementById(id); if (el) el.style.display = 'none'; });
  const row = document.getElementById('row-' + appId);
  if (!row) return;
  row.scrollIntoView({ behavior: 'smooth', block: 'center' });
  row.style.outline = '2px solid var(--p)';
  setTimeout(() => { row.style.outline = ''; }, 1800);
}

// ── ADD FORM ──────────────────────────────────────────────────
function renderAddForm() {
  const panel = document.getElementById('tab-add');
  if (!panel) return;
  const nextId = 'app-' + (appsData.length + 1);
  panel.innerHTML = `
    <div class="section-title"><i class="fas fa-plus-circle"></i> Agregar Nueva App</div>
    <div class="card" style="max-width:640px">
      <div style="margin-bottom:1rem">
        <label style="font-family:var(--mono);font-size:.58rem;color:var(--muted);display:block;margin-bottom:.4rem;text-transform:uppercase;letter-spacing:.08em">Catálogo</label>
        <div id="dest-hint" style="font-size:.68rem;color:var(--c, #00e5ff);padding:.55rem .75rem;border-radius:10px;background:rgba(0,229,255,.06);border:1px solid rgba(0,229,255,.18)"><i class="fab fa-github"></i> Todas las apps se publican en el catálogo <strong>Open Source</strong> (aparecen en /opensource y activan el monitor automático de GitHub Releases).</div>
      </div>
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:.85rem;margin-bottom:1rem">
        <div>
          <label style="font-family:var(--mono);font-size:.58rem;color:var(--muted);display:block;margin-bottom:.3rem;text-transform:uppercase;letter-spacing:.08em">App ID</label>
          <input class="ver-input" id="new-appId" value="${nextId}" style="width:100%" placeholder="app-14">
        </div>
        <div>
          <label style="font-family:var(--mono);font-size:.58rem;color:var(--muted);display:block;margin-bottom:.3rem;text-transform:uppercase;letter-spacing:.08em">Nombre</label>
          <input class="ver-input" id="new-nombre" style="width:100%" placeholder="NewPipe" oninput="onNewAppFieldsChange()">
        </div>
        <div>
          <label style="font-family:var(--mono);font-size:.58rem;color:var(--muted);display:block;margin-bottom:.3rem;text-transform:uppercase;letter-spacing:.08em">Versión</label>
          <input class="ver-input" id="new-version" style="width:100%" placeholder="1.0.0">
        </div>
        <div>
          <label style="font-family:var(--mono);font-size:.58rem;color:var(--muted);display:block;margin-bottom:.3rem;text-transform:uppercase;letter-spacing:.08em">
            Categoría <span id="new-catalog-badge" style="text-transform:none;letter-spacing:0;font-weight:700">💎 Premium</span>
          </label>
          <select class="ver-input" id="new-cat" style="width:100%">
            <option>Música</option><option>Video</option><option>Foto</option>
            <option>Utilidad</option><option>Seguridad</option><option>Juegos</option>
            <option>Root y Sistema</option><option>Multimedia</option>
            <option>Productividad</option><option>Lectura</option><option>Mensajería</option>
            <option>VPN y Privacidad</option><option>Redes Sociales</option><option>Tecnología</option>
          </select>
          <div id="new-cat-hint" style="font-size:.62rem;color:var(--muted);margin-top:.3rem"></div>
        </div>
        <div style="grid-column:1/-1">
          <label style="font-family:var(--mono);font-size:.58rem;color:var(--muted);display:block;margin-bottom:.3rem;text-transform:uppercase;letter-spacing:.08em">Descripción</label>
          <input class="ver-input" id="new-desc" style="width:100%" placeholder="Disfruta de..." oninput="onNewAppFieldsChange()">
        </div>
        <div style="grid-column:1/-1">
          <label style="font-family:var(--mono);font-size:.58rem;color:var(--muted);display:block;margin-bottom:.3rem;text-transform:uppercase;letter-spacing:.08em">Link Descarga</label>
          <input class="ver-input" id="new-enlace" style="width:100%" placeholder="https://...">
        </div>
        <div style="grid-column:1/-1">
          <label style="font-family:var(--mono);font-size:.58rem;color:var(--muted);display:block;margin-bottom:.3rem;text-transform:uppercase;letter-spacing:.08em">Imagen (ruta o URL)</label>
          <div style="display:flex;gap:.7rem;align-items:center">
            <img id="new-imagen-preview" src="" alt="" style="width:44px;height:44px;border-radius:12px;object-fit:cover;background:var(--card2);flex-shrink:0;display:none">
            <input class="ver-input" id="new-imagen" style="width:100%" placeholder="/img/NombreApp.png o https://raw.githubusercontent.com/..." oninput="previewNewImagen()">
          </div>
          <div id="new-imagen-hint" style="font-size:.62rem;color:var(--muted);margin-top:.35rem"></div>
          <div style="display:flex;gap:.5rem;margin-top:.5rem">
            <input class="ver-input" type="text" id="new-srcurl" placeholder="🔗 O pegá acá: repo GitHub / F-Droid / Play Store / imagen" style="flex:1">
            <button type="button" onclick="extractIconNew()" id="extract-btn-new" title="Extraer el ícono real y subirlo a img/ en tu repo (necesita el AppId cargado arriba)" style="padding:.4rem .7rem;border-radius:8px;background:rgba(0,229,255,.08);border:1px solid rgba(0,229,255,.2);color:var(--c);font-family:var(--mono);font-size:.62rem;cursor:pointer;white-space:nowrap">
              <i class="fas fa-download"></i> Extraer
            </button>
          </div>
        </div>
        <div style="grid-column:1/-1">
          <label style="font-family:var(--mono);font-size:.58rem;color:var(--muted);display:block;margin-bottom:.3rem;text-transform:uppercase;letter-spacing:.08em">Repositorio Open Source (opcional)</label>
          <input class="ver-input" id="new-source-repo" style="width:100%" placeholder="owner/repo — ej: TeamNewPipe/NewPipe" oninput="onNewAppFieldsChange()">
          <div style="font-size:.62rem;color:var(--muted);margin-top:.35rem">Si lo llenas, la app aparece en el catálogo Open Source en vez de Premium, y activa el monitor automático de releases de GitHub.</div>
        </div>
      </div>
      <button class="pub-btn" id="create-app-btn" style="background:linear-gradient(135deg,var(--p),var(--p2))" onclick="createApp()">
        <i class="fas fa-plus"></i> Crear App
      </button>
    </div>`;
  setAppDestino('opensource');
  updateCatalogBadge();
}

// ── DESTINO DE LA NUEVA APP: siempre Open Source (estático) ──
function setAppDestino(destino) {
  window._appDestino = 'opensource';
  const btn = document.getElementById('create-app-btn');
  const hint = document.getElementById('dest-hint');
  if (btn) btn.innerHTML = '<i class="fab fa-github"></i> Crear App Open Source';
  if (hint) hint.textContent = 'Se crea directo en MongoDB con source_repo — aparece en /opensource y el cron de GitHub Releases empieza a monitorearla en la próxima corrida. Repositorio Open Source es obligatorio.';
}

// ── AUTO-DETECCIÓN DE CATÁLOGO (Open Source vs Premium) Y CATEGORÍA ──
// Heurística por palabras clave, 100% en el navegador (sin llamar al
// backend ni a ninguna IA). Corre sobre nombre + descripción + repo
// cada vez que el admin escribe, y sugiere la categoría más probable.
// El admin siempre puede corregirla a mano en el <select>.
const CATEGORY_KEYWORDS = {
  'Redes Sociales':   ['whatsapp', 'instagram', 'tiktok', 'facebook', 'twitter', 'threads', 'reddit', 'status', 'red social'],
  'Mensajería':       ['mail', 'correo', 'messenger', 'sms', 'signal', 'tusky', 'mastodon', 'k-9', 'thunderbird', 'chat'],
  'Música':           ['music', 'música', 'spotify', 'tidal', 'deezer', 'audio', 'song', 'podcast', 'antennapod', 'reproductor de música'],
  'Video':            ['video', 'stream', 'pelicula', 'película', 'movie', 'netflix', 'player', 'reproductor de video', 'tv', 'anime', 'cloudstream', 'flix'],
  'Foto':             ['photo', 'foto', 'gallery', 'galería', 'camera', 'cámara', 'picsart', 'remini', 'scanner', 'escáner', 'imagen'],
  'Root y Sistema':   ['root', 'magisk', 'kernel', 'shizuku', 'debloat', 'uninstall', 'desinstal', 'freeze', 'congela', 'optimiz', 'lsposed', 'xposed', 'app manager'],
  'VPN y Privacidad': ['vpn', 'proxy', 'privacy', 'privacidad', ' tor ', 'firewall', 'dns', 'adblock', 'bloquea anuncios', 'anti-track'],
  'Productividad':    ['file manager', 'explorador de archivos', 'archivo', 'nota', 'tarea', 'calendar', 'calendario', 'password', 'contraseñ', 'nextcloud', 'office'],
  'Lectura':          ['reader', 'lector', 'manga', 'book', 'libro', 'pdf', 'comic', 'cómic'],
  'Tecnología':       ['sdk', 'terminal', 'developer', 'desarroll', 'api', 'debug', 'emulator', 'emulador', 'ide', 'compilador'],
  'Utilidad':         ['tool', 'utilidad', 'recorder', 'grabad', 'saver', 'guarda', 'cleaner', 'limpia', 'booster', 'optimizador', 'descargador'],
  'Juegos':           ['game', 'juego', 'gaming', 'emulador de juegos'],
  'Seguridad':        ['security', 'seguridad', 'antivirus', 'malware', 'bitwarden', 'contraseñas'],
};

function detectCategoria() {
  const text = [
    document.getElementById('new-nombre')?.value || '',
    document.getElementById('new-desc')?.value || '',
    document.getElementById('new-source-repo')?.value || '',
  ].join(' ').toLowerCase();
  if (!text.trim()) return null;

  let best = null, bestScore = 0;
  for (const [cat, keywords] of Object.entries(CATEGORY_KEYWORDS)) {
    const score = keywords.reduce((n, k) => n + (text.includes(k) ? 1 : 0), 0);
    if (score > bestScore) { bestScore = score; best = cat; }
  }
  return best;
}

function updateCatalogBadge() {
  const repo  = document.getElementById('new-source-repo')?.value.trim();
  const badge = document.getElementById('new-catalog-badge');
  if (!badge) return;
  if (repo) { badge.textContent = '🌐 Open Source'; badge.style.color = 'var(--c, #00e5ff)'; }
  else      { badge.textContent = '💎 Premium';      badge.style.color = 'var(--p, #ff0080)'; }
}

let _catDetectDebounce = null;
function onNewAppFieldsChange() {
  updateCatalogBadge();
  clearTimeout(_catDetectDebounce);
  _catDetectDebounce = setTimeout(() => {
    const hint = document.getElementById('new-cat-hint');
    const detected = detectCategoria();
    const select = document.getElementById('new-cat');
    if (!select) return;
    if (detected) {
      select.value = detected;
      if (hint) hint.textContent = `🔍 Categoría detectada automáticamente: ${detected} (podés cambiarla si no es correcta)`;
    } else if (hint) {
      hint.textContent = '';
    }
  }, 350);
}

// ── PREVIEW EN VIVO DEL ÍCONO AL CREAR APP ──────────────────────
// Usa el mismo optimizador (wsrv.nl) que ya corre en opensource.js
// para que el preview refleje exactamente lo que verá el usuario.
let _previewDebounce = null;
function previewNewImagen() {
  clearTimeout(_previewDebounce);
  _previewDebounce = setTimeout(() => {
    const val   = document.getElementById('new-imagen').value.trim();
    const img   = document.getElementById('new-imagen-preview');
    const hint  = document.getElementById('new-imagen-hint');
    if (!val) { img.style.display = 'none'; hint.textContent = ''; return; }

    if (val.includes('opengraph.githubassets.com')) {
      img.style.display = 'none';
      hint.textContent = '⚠️ Ese es el banner/portada social del repo, no el ícono de la app. Busca el ícono real dentro del repo (assets/, fastlane/, mipmap-xxxhdpi/) o en su ficha de F-Droid.';
      hint.style.color = 'var(--danger, #f66)';
      return;
    }

    if (!isAcceptedImageLink(val)) {
      img.style.display = 'none';
      hint.textContent = '⚠️ Link no reconocido — usa jpg/png/webp, captura de Play Store, raw de GitHub/GitLab/Codeberg, o ícono de F-Droid';
      hint.style.color = 'var(--danger, #f66)';
      return;
    }

    const optimized = (typeof getOptimizedImageUrl === 'function' && !val.startsWith('/'))
      ? getOptimizedImageUrl(val, 88, 88)
      : val;
    img.src = optimized;
    img.style.display = 'block';
    img.onerror = () => { hint.textContent = '⚠️ El link parece válido pero la imagen no cargó — verifícalo'; hint.style.color = 'var(--danger, #f66)'; };
    img.onload  = () => { hint.textContent = '✅ Imagen cargada correctamente'; hint.style.color = 'var(--ok, #6f6)'; };
  }, 300);
}

// ── EXTRAER ÍCONO EN EL FORMULARIO DE NUEVA APP ─────────────────
// Igual que extractIcon(), pero para una app que todavía no existe
// en la base de datos: solo sube la imagen a img/ y completa el
// campo "Imagen" del formulario. "Crear App" hace el resto.
async function extractIconNew() {
  const appIdVal = document.getElementById('new-appId').value.trim();
  if (!appIdVal) return toast('⚠️ Completá el AppId arriba primero (se usa como nombre del archivo)');
  const sourceUrl = document.getElementById('new-srcurl').value.trim();
  if (!sourceUrl) return toast('⚠️ Pegá primero el link del repo, F-Droid, Play Store o la imagen');

  const btn = document.getElementById('extract-btn-new');
  const originalHtml = btn.innerHTML;
  btn.disabled = true; btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i>';
  try {
    const res = await fetch(`${BACKEND}/api/admin/extract-icon`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-admin-key': ADMIN_KEY },
      body: JSON.stringify({ sourceUrl, filename: appIdVal }),
    });
    const d = await res.json();
    if (!res.ok) throw new Error(d.error || 'No se pudo extraer el ícono');

    document.getElementById('new-imagen').value = d.imagen;
    previewNewImagen();
    toast('✅ Ícono subido a tu repo (' + d.imagen + ') — ahora dale "Crear App"');
  } catch (e) {
    toast('❌ ' + e.message);
  } finally {
    btn.disabled = false; btn.innerHTML = originalHtml;
  }
}

async function createApp() {
  const body = {
    appId:       document.getElementById('new-appId').value.trim(),
    nombre:      document.getElementById('new-nombre').value.trim(),
    version:     document.getElementById('new-version').value.trim(),
    categoria:   document.getElementById('new-cat').value,
    descripcion: document.getElementById('new-desc').value.trim(),
    enlace:      convertToDirectLink(document.getElementById('new-enlace').value.trim() || '#'),
    imagen:      normalizeImagePath(document.getElementById('new-imagen').value.trim()),
    source_repo: document.getElementById('new-source-repo').value.trim() || null,
    tag: '🆕', verified: true,
  };
  if (!body.appId || !body.nombre) return toast('❌ AppId y Nombre son obligatorios');
  if (body.imagen && !isAcceptedImageLink(body.imagen)) {
    toast('⚠️ Ese link de imagen puede no cargar bien — usa jpg/png/webp, una captura de Play Store o un raw de GitHub/GitLab/Codeberg');
  }

  // ── DESTINO: OPEN SOURCE — requiere repo (activa el monitor de releases) ──
  if (!body.source_repo) {
    return toast('❌ Repositorio Open Source (owner/repo) es obligatorio');
  }

  try {
    const res = await fetch(`${BACKEND}/api/admin/apps`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-admin-key': ADMIN_KEY },
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new Error((await res.json()).error);
    toast('✅ App Open Source creada: ' + body.nombre + ' — el monitor de releases la revisa en la próxima corrida del cron');
    await refreshApps();
    document.querySelectorAll('.admin-tab')[0].click();
  } catch (e) { toast('❌ ' + e.message); }
}

// ── SEED ──────────────────────────────────────────────────────
async function seedFromJSON() {
  if (!confirm('Importar apps base a MongoDB. ¿Continuar?')) return;
  try {
    const res = await fetch('apps_data.json');
    const mapped = await res.json();
    const seedRes = await fetch(`${BACKEND}/api/admin/seed`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-admin-key': ADMIN_KEY },
      body: JSON.stringify({ apps: mapped }),
    });
    if (!seedRes.ok) throw new Error((await seedRes.json()).error);
    const d = await seedRes.json();
    toast(`✅ Seed: ${d.created} creadas, ${d.updated} actualizadas`);
    await refreshApps();
  } catch (e) { toast('❌ Error en seed: ' + e.message); }
}

// Parsea una Response como JSON de forma segura — si el body no es JSON
// (ej. una página de error HTML del hosting/proxy), devuelve null en vez
// de tronar con "Unexpected token '<'". Así el mensaje de error que ve
// el admin siempre explica qué pasó de verdad.
async function safeJson(res) {
  const text = await res.text();
  try { return JSON.parse(text); }
  catch { return null; }
}

async function postAdminSeed(apps, isRetry) {
  const seedRes = await fetch(`${BACKEND}/api/admin/seed`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'x-admin-key': ADMIN_KEY },
    body: JSON.stringify({ apps }),
  });
  const data = await safeJson(seedRes);

  if (!seedRes.ok || !data) {
    // El backend en Render (free tier) se duerme tras inactividad — la
    // primera petición tras despertar puede tardar y el proxy devuelve
    // una página HTML de error en vez de JSON. Se reintenta una vez.
    if (!isRetry) {
      toast('⏳ El backend se está despertando (cold-start de Render), reintentando en 6s…');
      await new Promise(r => setTimeout(r, 6000));
      return postAdminSeed(apps, true);
    }
    throw new Error(
      data?.error ||
      `El backend respondió HTTP ${seedRes.status} sin JSON válido — puede seguir despertando, probá de nuevo en unos segundos, o revisa que ${BACKEND} esté arriba.`
    );
  }
  return data;
}

// Siembra las 48 apps del catálogo Open Source (con su source_repo)
// desde opensource_seed.json — activa el monitor automático de
// GitHub Releases para todas de una vez. Correr una sola vez;
// vuelve a correrse sin duplicar (usa appId como clave de upsert).
async function seedOpenSourceFromJSON() {
  if (!confirm('Importar las 48 apps Open Source a MongoDB (con source_repo). ¿Continuar?')) return;
  try {
    // cache:'no-store' + query de cache-busting: evita que el navegador
    // reuse una respuesta 404 vieja de intentos anteriores a que el
    // archivo existiera en el deploy.
    const res = await fetch(`/opensource_seed.json?v=${Date.now()}`, { cache: 'no-store' });
    if (!res.ok) throw new Error(`No se pudo leer opensource_seed.json (HTTP ${res.status}) — confirma que esté en la raíz del repo y que el deploy ya lo tenga publicado`);
    const ct = res.headers.get('content-type') || '';
    if (!ct.includes('json')) throw new Error(`opensource_seed.json respondió con Content-Type "${ct}" en vez de JSON — probablemente es una página de error del hosting, no el archivo`);
    const apps = await res.json();

    const d = await postAdminSeed(apps, false);
    toast(`✅ Seed Open Source: ${d.created} creadas, ${d.updated} actualizadas`);
    await refreshApps();
  } catch (e) { toast('❌ Error en seed Open Source: ' + e.message); }
}

// ── SOLICITUDES ───────────────────────────────────────────────
async function loadAdminRequests() {
  const list = document.getElementById('req-list-admin');
  list.innerHTML = '<div style="padding:2rem;text-align:center;font-family:var(--mono);font-size:.74rem;color:var(--muted)">Cargando...</div>';
  try {
    const res = await fetch(`${BACKEND}/api/requests`);
    const d   = await res.json();
    const set = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v; };
    set('st-requests', d.requests?.length || 0);
    if (!d.requests?.length) {
      list.innerHTML = '<div style="padding:2rem;text-align:center;font-family:var(--mono);font-size:.74rem;color:var(--muted)">No hay solicitudes pendientes</div>';
      return;
    }
    list.innerHTML = d.requests.map(r => `
      <div class="req-row">
        <div class="req-name">${r.appName}<small>${r.reason || ''}</small></div>
        <div class="req-votes">+${r.votes} votos</div>
        <button class="req-action req-done"   onclick="markRequest('${r._id}','done')"><i class="fas fa-check"></i> Agregar</button>
        <button class="req-action req-reject" onclick="markRequest('${r._id}','rejected')"><i class="fas fa-times"></i> Rechazar</button>
      </div>`).join('');
  } catch {
    list.innerHTML = '<div style="padding:2rem;text-align:center;font-family:var(--mono);font-size:.74rem;color:#ff6b6b">Error conectando al backend</div>';
  }
}

async function markRequest(id, status) {
  try {
    await fetch(`${BACKEND}/api/requests/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', 'x-admin-key': ADMIN_KEY },
      body: JSON.stringify({ status }),
    });
    toast(status === 'done' ? '✅ Marcada como agregada' : '🗑️ Rechazada');
    loadAdminRequests();
  } catch { toast('❌ Error'); }
}

// ── RATINGS ───────────────────────────────────────────────────
async function loadAdminRatings() {
  const list = document.getElementById('ratings-list');
  try {
    const res = await fetch(`${BACKEND}/api/ratings`);
    const d   = await res.json();
    const ratings = d.ratings || {};
    const total = Object.values(ratings).reduce((s, r) => s + r.count, 0);
    const set = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v; };
    set('st-ratings', total);
    if (!Object.keys(ratings).length) {
      list.innerHTML = '<div style="padding:2rem;text-align:center;font-family:var(--mono);font-size:.74rem;color:var(--muted)">Aún no hay ratings</div>';
      return;
    }
    list.innerHTML = Object.entries(ratings).map(([id, r]) => {
      const app = appsData.find(a => a.appId === id);
      const stars = '⭐'.repeat(Math.round(r.avg));
      return `<div class="req-row">
        <div class="req-name">${app?.nombre || id}</div>
        <div style="font-family:var(--mono);font-size:.74rem;color:var(--a)">${stars} ${r.avg}/5</div>
        <div style="font-family:var(--mono);font-size:.63rem;color:var(--muted)">${r.count} votos</div>
        <div></div>
      </div>`;
    }).join('');
  } catch {
    list.innerHTML = '<div style="padding:2rem;text-align:center;font-family:var(--mono);font-size:.74rem;color:#ff6b6b">Error</div>';
  }
}

async function loadAdminStats() {
  try {
    const [reqRes, ratRes] = await Promise.all([
      fetch(`${BACKEND}/api/requests`),
      fetch(`${BACKEND}/api/ratings`)
    ]);
    const req = await reqRes.json();
    const rat = await ratRes.json();
    const set = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v; };
    set('st-requests', req.requests?.length || 0);
    set('st-ratings',  Object.values(rat.ratings || {}).reduce((s, r) => s + r.count, 0));
  } catch {}
}

// ── SUPABASE STATS ────────────────────────────────────────────
// Backend devuelve: { daily[], tools[], downloads[], total_events }
// daily[]     -> { date, visits, downloads, chat_msgs, tool_uses, contacts }
// tools[]     -> { tool_name, uses }
// downloads[] -> { app_name, downloads }
async function loadSupabaseStats() {
  const set = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v ?? '—'; };
  try {
    const res = await fetch(`${BACKEND}/api/stats/supabase`);
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const d = await res.json();
    if (d.error) throw new Error(d.error);

    // Fila de hoy para los KPIs diarios
    const today = new Date().toISOString().slice(0, 10);
    const todayRow = (d.daily || []).find(r => r.date === today) || {};

    set('sb-events',    d.total_events       ?? 0);
    set('sb-visits',    todayRow.visits       ?? 0);
    set('sb-downloads', todayRow.downloads    ?? 0);
    set('sb-chats',     todayRow.chat_msgs    ?? 0);

    // Renderizar gráficas con el mapping correcto
    renderCharts({
      daily_visits:  (d.daily     || []).slice(0, 30).reverse(), // asc para el chart
      top_tools:      d.tools     || [],
      top_downloads:  d.downloads || [],
    });
  } catch (e) {
    console.warn('Supabase stats error:', e.message);
    ['sb-events','sb-visits','sb-downloads','sb-chats'].forEach(id => set(id, '—'));
  }
}

// ── CHARTS ────────────────────────────────────────────────────
const _charts = {};
function renderCharts(data) {
  if (typeof Chart === 'undefined') return;
  const palette   = ['#2f80ed','#00e676','#00e5ff','#38bdf8','#a855f7','#ff5f56','#22c55e','#22c55e'];
  const gridColor = 'rgba(255,255,255,.05)';
  const tickFont  = { size: 10, family: 'JetBrains Mono' };
  const tickColor = 'rgba(240,240,250,.4)';

  const mkBar = (id, labels, values, color) => {
    const ctx = document.getElementById(id);
    if (!ctx) return;
    if (_charts[id]) _charts[id].destroy();
    _charts[id] = new Chart(ctx, {
      type: 'bar',
      data: { labels, datasets: [{ data: values, backgroundColor: color + '33', borderColor: color, borderWidth: 2, borderRadius: 6 }] },
      options: { responsive: true, maintainAspectRatio: true,
        plugins: { legend: { display: false } },
        scales: {
          x: { grid: { color: gridColor }, ticks: { color: tickColor, font: tickFont } },
          y: { grid: { color: gridColor }, ticks: { color: tickColor, font: tickFont }, beginAtZero: true }
        }
      }
    });
  };

  const mkDoughnut = (id, labels, values) => {
    const ctx = document.getElementById(id);
    if (!ctx) return;
    if (_charts[id]) _charts[id].destroy();
    _charts[id] = new Chart(ctx, {
      type: 'doughnut',
      data: { labels, datasets: [{ data: values, backgroundColor: palette.map(c => c + '88'), borderColor: palette, borderWidth: 2 }] },
      options: { responsive: true, maintainAspectRatio: true,
        plugins: { legend: { position: 'bottom', labels: { color: tickColor, font: tickFont, padding: 12, boxWidth: 10 } } }
      }
    });
  };

  if (data.daily_visits?.length)   mkBar('chart-visits',     data.daily_visits.map(d => d.date?.slice(5) || ''),   data.daily_visits.map(d => d.visits || 0),      '#00e5ff');
  if (data.top_tools?.length)      mkDoughnut('chart-tools',     data.top_tools.map(t => t.tool_name),              data.top_tools.map(t => t.uses));
  if (data.top_downloads?.length)  mkDoughnut('chart-downloads', data.top_downloads.map(t => t.app_name),           data.top_downloads.map(t => t.downloads));
}

// ═══════════════════════════════════════════
//  DB RUNNER — subir .sql/.json y aplicarlo a Supabase o MongoDB
// ═══════════════════════════════════════════
let dbTarget = null;

function selectDbTarget(target) {
  dbTarget = target;
  document.getElementById('db-card-supabase').classList.toggle('active', target === 'supabase');
  document.getElementById('db-card-mongo').classList.toggle('active', target === 'mongo');

  const input = document.getElementById('db-file-input');
  input.accept = target === 'supabase' ? '.sql,.txt' : '.json';

  document.getElementById('db-drop-label').textContent =
    target === 'supabase' ? 'Arrastra tu archivo .sql aquí' : 'Arrastra tu archivo .json aquí';
  document.getElementById('db-drop-sub').textContent =
    target === 'supabase'
      ? 'Se ejecutará como SQL crudo contra Postgres (requiere exec_sql — ver bootstrap_exec_sql.sql)'
      : 'Formato: { "collections": [{ "name": "...", "indexes": [{ "keys": {...} }] }] }';
  document.getElementById('db-run-target').textContent = target === 'supabase' ? 'Supabase' : 'MongoDB';
  updateDbRunBtn();
}

function handleDbDrop(e) {
  e.preventDefault();
  e.currentTarget.classList.remove('drag-over');
  if (e.dataTransfer.files?.length) handleDbFile(e.dataTransfer.files);
}

function handleDbFile(files) {
  const f = files?.[0];
  if (!f) return;
  if (!dbTarget) { toast('⚠️ Elige primero Supabase o MongoDB'); return; }
  const reader = new FileReader();
  reader.onload = () => { document.getElementById('db-content').value = reader.result; updateDbRunBtn(); };
  reader.onerror = () => toast('❌ No se pudo leer el archivo');
  reader.readAsText(f);
}

function updateDbRunBtn() {
  const btn = document.getElementById('db-run-btn');
  if (!btn) return;
  const has = !!dbTarget && document.getElementById('db-content').value.trim().length > 0;
  btn.disabled = !has;
}

function clearDbRunner() {
  document.getElementById('db-content').value = '';
  document.getElementById('db-file-input').value = '';
  updateDbRunBtn();
  renderDbLog([]);
}

function dbEsc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c]));
}

function renderDbLog(results, meta) {
  const box = document.getElementById('db-log');
  if (!box) return;
  if (!results || !results.length) {
    box.innerHTML = '<div style="color:var(--muted);font-family:var(--mono);font-size:.7rem">Sin ejecuciones todavía.</div>';
    return;
  }
  const head = meta ? `<div style="margin-bottom:.6rem;font-family:var(--mono);font-size:.72rem;font-weight:700">${dbEsc(meta)}</div>` : '';
  box.innerHTML = head + results.map(r => {
    const label = r.stmt || r.collection || '(sin nombre)';
    return `<div class="db-log-line">
      <i class="fas ${r.ok ? 'fa-circle-check' : 'fa-circle-xmark'}" style="color:${r.ok ? 'var(--g)' : '#ff6b6b'}"></i>
      <div>
        <div style="color:${r.ok ? 'var(--text)' : '#ff6b6b'}">${dbEsc(label)}</div>
        ${r.ok ? '' : `<div style="color:#ff6b6b;opacity:.85;margin-top:.15rem">${dbEsc(r.error)}</div>`}
      </div>
    </div>`;
  }).join('');
}

async function runDbScript() {
  if (!dbTarget) return;
  const content = document.getElementById('db-content').value.trim();
  if (!content) return;

  const label = dbTarget === 'supabase' ? 'Supabase (SQL crudo)' : 'MongoDB (esquema de colecciones)';
  if (!confirm(`⚠️ Vas a ejecutar cambios reales en ${label}.\n\nRevisa que el contenido sea correcto — esta acción no se deshace automáticamente.\n\n¿Continuar?`)) return;

  const btn = document.getElementById('db-run-btn');
  const originalHtml = btn.innerHTML;
  btn.disabled = true;
  btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Ejecutando...';

  try {
    const res = await fetch(`${BACKEND}/api/admin/db/run`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-admin-key': ADMIN_KEY },
      body: JSON.stringify({ target: dbTarget, content }),
    });
    const data = await res.json();
    if (!res.ok && !data.results) throw new Error(data.error || `HTTP ${res.status}`);

    const total = data.total ?? (data.results || []).length;
    const failed = data.failed ?? (data.results || []).filter(r => !r.ok).length;
    renderDbLog(data.results || [], `${data.ok ? '✅' : '⚠️'} ${total - failed}/${total} sentencias OK`);
    toast(data.ok ? '✅ Ejecutado sin errores' : `⚠️ ${failed} fallaron — revisa el log`);
  } catch (e) {
    renderDbLog([{ ok: false, stmt: 'Error de conexión', error: e.message }]);
    toast('❌ ' + e.message);
  } finally {
    btn.disabled = false;
    btn.innerHTML = originalHtml;
    updateDbRunBtn();
  }
}

// ═══════════════════════════════════════════
//  VISITANTES — Supabase visitor_logs
// ═══════════════════════════════════════════
let _allVisitors = [];

async function loadVisitors() {
  const body = document.getElementById('vt-body');
  if (!body) return;
  body.innerHTML = '<tr><td colspan="8" style="text-align:center;padding:2.5rem;font-family:var(--mono);color:var(--muted)"><i class="fas fa-spinner fa-spin" style="margin-right:.5rem"></i>Cargando visitantes desde Supabase...</td></tr>';
  try {
    const res  = await fetch(`${BACKEND}/api/admin/visitors?limit=500`, {
      headers: { 'x-admin-key': ADMIN_KEY }
    });
    const data = await res.json();
    if (!res.ok || !data.ok) throw new Error(data.error || 'Error del servidor');
    _allVisitors = data.visitors || [];
    renderVisitors(_allVisitors);
    updateVisitorKPIs(_allVisitors);
  } catch (e) {
    body.innerHTML = `<tr><td colspan="8" style="text-align:center;padding:2rem;color:#ff6b6b;font-family:var(--mono)">${e.message}</td></tr>`;
  }
}

function countryFlag(code) {
  if (!code || code.length !== 2) return '🌐';
  return String.fromCodePoint(...[...code.toUpperCase()].map(c => 0x1F1E6 + c.charCodeAt(0) - 65));
}

function riskPill(score) {
  if (!score) return '<span style="color:var(--muted);font-size:.65rem">—</span>';
  const color = score >= 70 ? '#ff5f56' : score >= 30 ? '#ffbd2e' : '#00e676';
  const bg    = score >= 70 ? 'rgba(255,95,86,.12)' : score >= 30 ? 'rgba(255,189,46,.12)' : 'rgba(0,230,118,.12)';
  return `<span class="risk-pill" style="background:${bg};color:${color}">${score}/100</span>`;
}

function fmtDate(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleDateString('es-GT', { day: '2-digit', month: 'short', year: '2-digit' })
       + ' ' + d.toLocaleTimeString('es-GT', { hour: '2-digit', minute: '2-digit' });
}

function renderVisitors(list) {
  const body = document.getElementById('vt-body');
  if (!body) return;
  if (!list.length) {
    body.innerHTML = '<tr><td colspan="8" style="text-align:center;padding:2.5rem;font-family:var(--mono);color:var(--muted)">Sin visitas registradas aún — visita index.html para generar datos</td></tr>';
    return;
  }
  body.innerHTML = list.map(v => `
    <tr onclick="showVisitorJSON(${JSON.stringify(JSON.stringify(v))})">
      <td style="font-family:var(--mono);font-size:.7rem;color:var(--c)">${v.ip || '—'}</td>
      <td>${countryFlag(v.country_code)} <span style="font-size:.76rem">${v.country || '—'}</span></td>
      <td style="color:var(--muted);font-size:.7rem">${v.city || '—'}${v.region ? ', '+v.region : ''}</td>
      <td style="max-width:150px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:.68rem;color:var(--muted)" title="${v.isp||''}">${v.isp || v.org || '—'}</td>
      <td>${riskPill(v.risk_score)}</td>
      <td>
        ${v.is_vpn   ? '<span class="flag-badge" style="background:rgba(255,189,46,.15);color:#ffbd2e">VPN</span>'   : ''}
        ${v.is_proxy ? '<span class="flag-badge" style="background:rgba(255,95,86,.15);color:#ff5f56">PROXY</span>' : ''}
        ${v.is_bot   ? '<span class="flag-badge" style="background:rgba(168,85,247,.15);color:#a855f7">BOT</span>'  : ''}
        ${(!v.is_vpn && !v.is_proxy && !v.is_bot) ? '<span style="color:var(--muted);font-size:.65rem">—</span>' : ''}
      </td>
      <td style="font-family:var(--mono);font-size:.65rem;color:var(--muted)">${v.page || '/'}</td>
      <td style="font-family:var(--mono);font-size:.65rem;color:var(--muted);white-space:nowrap">${fmtDate(v.visited_at)}</td>
    </tr>
  `).join('');
}

function showVisitorJSON(jsonStr) {
  const data = JSON.parse(jsonStr);
  document.getElementById('vt-json-content').textContent = JSON.stringify(data, null, 2);
  document.getElementById('vt-json-overlay').style.display = 'flex';
}
function closeVisitorJSON() { document.getElementById('vt-json-overlay').style.display = 'none'; }

function updateVisitorKPIs(list) {
  const today     = new Date().toDateString();
  const todayN    = list.filter(v => new Date(v.visited_at).toDateString() === today).length;
  const countries = new Set(list.map(v => v.country_code).filter(Boolean)).size;
  const vpnProxy  = list.filter(v => v.is_vpn || v.is_proxy).length;
  const set = (id, v) => { const el = document.getElementById(id); if (el) el.textContent = v; };
  set('vt-total',     list.length);
  set('vt-today',     todayN);
  set('vt-countries', countries);
  set('vt-vpn',       vpnProxy);
}

function filterVisitors() {
  const q = (document.getElementById('vt-search')?.value || '').toLowerCase();
  const filtered = q
    ? _allVisitors.filter(v => [v.ip, v.country, v.city, v.region, v.isp, v.org, v.page]
        .some(f => (f || '').toLowerCase().includes(q)))
    : _allVisitors;
  renderVisitors(filtered);
}

function exportVisitors() {
  if (!_allVisitors.length) { toast('No hay datos para exportar'); return; }
  const headers = ['IP','País','Código','Ciudad','Región','ISP','Org','VPN','Proxy','Bot','Riesgo','Página','Fecha'];
  const rows = _allVisitors.map(v => [
    v.ip, v.country, v.country_code, v.city, v.region,
    v.isp, v.org, v.is_vpn, v.is_proxy, v.is_bot,
    v.risk_score, v.page, v.visited_at
  ].map(x => `"${String(x ?? '').replace(/"/g,'""')}"`).join(','));
  const csv  = [headers.join(','), ...rows].join('\n');
  const blob = new Blob([csv], { type: 'text/csv' });
  const a    = Object.assign(document.createElement('a'), {
    href: URL.createObjectURL(blob),
    download: `visitantes_${new Date().toISOString().slice(0,10)}.csv`
  });
  a.click(); toast('✅ CSV exportado');
}

// ── STATUS CHECK ──────────────────────────────────────────────
async function checkStatus() {
  const services = [
    { id: 'backend',  name: 'Backend — Render',   url: BACKEND },
    { id: 'mongo',    name: 'MongoDB Atlas',       url: BACKEND },
    { id: 'supabase', name: 'Supabase',            url: BACKEND },
    { id: 'groq',     name: 'Groq AI',             url: BACKEND },
    { id: 'vercel',   name: 'Vercel (Frontend)',   url: 'https://wilson360-labs.vercel.app' },
  ];
  const grid = document.getElementById('svc-grid');
  grid.innerHTML = services.map(s => `
    <div class="svc-card loading" id="svc-${s.id}">
      <div><span class="svc-dot"></span><span class="svc-name">${s.name}</span></div>
      <div class="svc-url">${s.url.replace('https://','')}</div>
      <div class="svc-latency">Verificando...</div>
    </div>`).join('');

  const setCard = (id, status, latency) => {
    const el = document.getElementById('svc-' + id);
    if (!el) return;
    el.className = 'svc-card ' + status;
    el.querySelector('.svc-latency').textContent = latency;
  };

  try {
    const t0  = Date.now();
    const res = await fetch(`${BACKEND}/api/health`);
    const d   = await res.json();
    const lat = Date.now() - t0;
    setCard('backend',  'online',                                         `${lat}ms`);
    setCard('mongo',    d.mongo    === 'connected' ? 'online' : 'offline', d.mongo === 'connected' ? `${lat}ms` : 'Desconectado');
    setCard('supabase', d.supabase !== false       ? 'online' : 'offline', d.supabase !== false    ? `${lat}ms` : 'Sin configurar');
    setCard('groq',     d.groq     !== false       ? 'online' : 'warning', d.groq !== false        ? `${lat}ms` : 'Sin API key');
  } catch {
    ['backend','mongo','supabase','groq'].forEach(k => setCard(k, 'offline', 'Sin respuesta'));
  }

  try {
    const t0 = Date.now();
    await fetch('https://wilson360-labs.vercel.app', { mode: 'no-cors' });
    setCard('vercel', 'online', `${Date.now()-t0}ms`);
  } catch {
    setCard('vercel', 'warning', 'CORS (ok)');
  }
}

// ── BULK UPLOAD ───────────────────────────────────────────────
function handleBulkDrop(e) {
  e.preventDefault();
  document.getElementById('bulk-drop').classList.remove('drag-over');
  handleBulkFiles(e.dataTransfer.files);
}

function handleBulkFiles(files) {
  const list = document.getElementById('bulk-list');
  const arr  = Array.from(files);
  list.innerHTML = arr.map((f, i) => `
    <div class="bulk-item" id="bulk-item-${i}">
      <i class="fas fa-file-arrow-up" style="color:var(--c);font-size:.88rem"></i>
      <span class="bulk-item-name">${f.name}</span>
      <span class="bulk-item-size">${(f.size/1024/1024).toFixed(1)} MB</span>
      <span id="bstatus-${i}" style="font-family:var(--mono);font-size:.63rem;color:var(--muted);white-space:nowrap">Pendiente</span>
      <div id="bbar-wrap-${i}" style="display:none;width:60px;height:4px;background:rgba(255,255,255,.08);border-radius:999px;overflow:hidden;">
        <div id="bbar-${i}" style="height:100%;width:0%;background:linear-gradient(90deg,var(--p),var(--p2));border-radius:999px;transition:width .15s;"></div>
      </div>
    </div>`).join('');
  if (!arr.length) return;
  const prog = document.getElementById('bulk-progress');
  const bar  = document.getElementById('bulk-bar');
  const stat = document.getElementById('bulk-status');
  prog.style.display = 'block';
  bar.style.width = '0%';
  stat.textContent = 'Procesando...';

  // ⚠️ FIX: forEach+async no espera — usar for-of secuencial con Promise
  (async () => {
    for (let i = 0; i < arr.length; i++) {
      const file  = arr[i];
      const match = file.name.match(/^(.+?)_(main|plugin)\.apk$/i);
      const appId = match ? match[1] : null;
      const slot  = match ? match[2] : 'main';
      const bstat    = document.getElementById('bstatus-' + i);
      const bbarWrap = document.getElementById('bbar-wrap-' + i);
      const bbar     = document.getElementById('bbar-' + i);

      if (!appId || !appsData.find(a => a.appId === appId)) {
        bstat.textContent = '⚠️ No encontrada'; bstat.style.color = 'var(--a)';
        bar.style.width = ((i + 1) / arr.length * 100) + '%';
        continue;
      }

      bstat.textContent = '⬆️ 0%'; bstat.style.color = 'var(--c)';
      bbarWrap.style.display = 'block';
      stat.textContent = `Subiendo ${i + 1}/${arr.length}: ${file.name}`;

      await new Promise((resolve) => {
        const fd = new FormData();
        fd.append('apk', file); fd.append('slot', slot);
        const xhr = new XMLHttpRequest();
        xhr.open('POST', `${BACKEND}/api/admin/apps/${appId}/upload`);
        xhr.setRequestHeader('x-admin-key', ADMIN_KEY);
        xhr.upload.addEventListener('progress', (e) => {
          if (!e.lengthComputable) return;
          const pct = Math.round(e.loaded / e.total * 100);
          bbar.style.width = pct + '%';
          bstat.textContent = `⬆️ ${pct}%`;
        });
        xhr.addEventListener('load', () => {
          bbar.style.width = '100%';
          if (xhr.status >= 400) {
            bstat.textContent = '❌ Error'; bstat.style.color = '#ff6b6b';
          } else {
            bstat.textContent = '✅ OK'; bstat.style.color = 'var(--g)';
          }
          bar.style.width = ((i + 1) / arr.length * 100) + '%';
          resolve();
        });
        xhr.addEventListener('error', () => {
          bstat.textContent = '❌ Red'; bstat.style.color = '#ff6b6b';
          bar.style.width = ((i + 1) / arr.length * 100) + '%';
          resolve();
        });
        xhr.send(fd);
      });
    }
    stat.textContent = '✅ Proceso completado';
    refreshApps();
  })();
}

// ── TOAST ─────────────────────────────────────────────────────
function toast(m) {
  const t = document.getElementById('toast');
  t.textContent = m; t.classList.add('show');
  setTimeout(() => t.classList.remove('show'), 3500);
}
