/* ═══════════════════════════════════════
   Catálogo Open Source — Scripts
   CodeHub by Wilson.E
   Reutiliza las clases CSS de novedades.css
   pero es un flujo separado: sin ad-gate,
   descarga directa al asset del release de
   GitHub. Solo muestra apps que tengan
   `source_repo` (así se distingue del
   catálogo de apps premium desbloqueadas).
═══════════════════════════════════════ */

const BACKEND = 'https://codehub-98s6.onrender.com';

let ALL_OS_APPS = [];
let CURRENT_CAT = 'all';

const CAT_EMOJI = {
  'Root y Sistema':   '🛠️',
  'Música':            '🎵',
  'Video':             '📺',
  'VPN y Privacidad':  '🔒',
  'Productividad':     '✅',
  'Lectura':           '📖',
};

function buildOSCard(app) {
  const img     = app.imagen || '';
  const version = app.version ? `v${app.version.replace(/^v/i, '')}` : 'Sin versión aún';
  const desc    = app.descripcion || '';
  const enlace  = app.enlace && app.enlace !== '#' ? app.enlace : null;
  const repoUrl = app.source_repo ? `https://github.com/${app.source_repo}` : null;
  const emoji   = CAT_EMOJI[app.categoria] || '📦';
  const badge   = app.tag || '🆕';

  const dlBtn = enlace
    ? `<a class="dl-btn" href="${enlace}" target="_blank" rel="noopener">
         <i class="fas fa-download"></i> Descargar APK
       </a>`
    : `<a class="dl-btn" href="${repoUrl || '#'}" target="_blank" rel="noopener" style="opacity:.75">
         <i class="fas fa-clock"></i> Ver en GitHub (sin release aún)
       </a>`;

  const repoBtn = repoUrl
    ? `<a class="dl-btn dl-plugin" href="${repoUrl}" target="_blank" rel="noopener" title="Código fuente">
         <i class="fab fa-github"></i> Repo
       </a>`
    : '';

  return `
  <div class="app-card" data-cat="${app.categoria || ''}" data-name="${(app.nombre || '').toLowerCase()}">
    <div class="app-thumb">
      <img src="${img}" alt="${app.nombre}" onerror="this.parentElement.innerHTML='<div class=app-thumb-fallback>${emoji}</div>'">
      <span class="app-badge">${badge}</span>
      <span class="app-verified-badge" style="display:flex">✅ Open Source</span>
      <span class="app-version-tag">${version}</span>
    </div>
    <div class="app-body">
      <div class="app-cat-tag">${emoji} ${app.categoria || ''}</div>
      <div class="app-name">${app.nombre}</div>
      <div class="app-desc">${desc}</div>
      <div class="app-actions">${dlBtn}${repoBtn}</div>
    </div>
  </div>`;
}

function renderOSChips() {
  const cats = [...new Set(ALL_OS_APPS.map(a => a.categoria).filter(Boolean))];
  const wrap = document.getElementById('cat-chips');
  cats.forEach(cat => {
    const btn = document.createElement('button');
    btn.className = 'cat-chip';
    btn.textContent = `${CAT_EMOJI[cat] || '📦'} ${cat}`;
    btn.onclick = () => filterOSCat(cat, btn);
    wrap.appendChild(btn);
  });
  document.getElementById('sc-cats').textContent = cats.length;
}

function renderOSGrid() {
  const grid = document.getElementById('os-app-grid');
  const q = (document.getElementById('search').value || '').toLowerCase().trim();

  const filtered = ALL_OS_APPS.filter(a => {
    const matchCat = CURRENT_CAT === 'all' || a.categoria === CURRENT_CAT;
    const matchQ   = !q || (a.nombre || '').toLowerCase().includes(q) || (a.descripcion || '').toLowerCase().includes(q);
    return matchCat && matchQ;
  });

  if (!filtered.length) {
    grid.innerHTML = '<div style="grid-column:1/-1;text-align:center;padding:3rem;color:var(--muted)">No hay apps que coincidan con la búsqueda.</div>';
    return;
  }
  grid.innerHTML = filtered.map(buildOSCard).join('');
}

function filterOSApps() { renderOSGrid(); }

function filterOSCat(cat, btn) {
  CURRENT_CAT = cat;
  document.querySelectorAll('#cat-chips .cat-chip').forEach(c => c.classList.remove('on'));
  btn.classList.add('on');
  renderOSGrid();
}

async function loadOSCatalog() {
  const grid = document.getElementById('os-app-grid');
  try {
    const res  = await fetch(`${BACKEND}/api/apps`);
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    const apps = Array.isArray(data) ? data : (data.apps || []);

    // Solo apps del catálogo open source: las que tienen source_repo vinculado
    ALL_OS_APPS = apps.filter(a => !!a.source_repo);

    document.getElementById('sc-apps').textContent = ALL_OS_APPS.length;
    renderOSChips();
    renderOSGrid();
  } catch (e) {
    grid.innerHTML = '<div style="grid-column:1/-1;text-align:center;padding:3rem;color:var(--muted)"><i class="fas fa-exclamation-triangle" style="font-size:1.5rem;margin-bottom:.8rem;display:block"></i>Error cargando el catálogo. Recarga la página.</div>';
  }
}

window.addEventListener('scroll', () => {
  const s = document.documentElement.scrollTop;
  const h = document.documentElement.scrollHeight - window.innerHeight;
  const bar = document.getElementById('pbar');
  if (bar) bar.style.width = (s / h * 100) + '%';
});

document.addEventListener('DOMContentLoaded', loadOSCatalog);
