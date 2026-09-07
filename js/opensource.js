/* ═══════════════════════════════════════
   Open Source — Catálogo dinámico
   CodeHub by Wilson.E

   Fuente única: GET /api/apps.
   Se muestran solo las apps que tengan `source_repo` ("owner/repo")
   configurado en MongoDB — esas son las que el cron
   backend/scripts/check-app-updates.js mantiene al día contra
   GitHub Releases (versión, changelog y — si el release trae un
   .apk adjunto — el enlace de descarga).
═══════════════════════════════════════ */

const BACKEND = 'https://codehub-98s6.onrender.com';

// ── ESCAPADO PARA HTML/ATRIBUTOS ────────────────────────────
function esc(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

// ── TOAST PROPIO — feedback visual sin depender de otros bundles ──
// (en esta página no existe toast() global; esto evita feedback mudo)
function OSToast(msg, type) {
  let el = document.getElementById('os-toast');
  if (!el) {
    el = document.createElement('div');
    el.id = 'os-toast';
    el.setAttribute('role', 'status');
    document.body.appendChild(el);
  }
  const err = type === 'error';
  el.className = 'show' + (err ? ' os-toast-err' : (type === 'success' ? ' os-toast-ok' : ''));
  const icon = err ? 'fa-solid fa-circle-xmark' : (type === 'success' ? 'fa-solid fa-circle-check' : 'fa-solid fa-circle-info');
  el.innerHTML = `<i class="${icon}"></i><span>${esc(msg)}</span>`;
  clearTimeout(OSToast._t);
  OSToast._t = setTimeout(() => el.classList.remove('show'), err ? 3800 : 2400);
}

// ── OPTIMIZADOR DE IMÁGENES (proxy wsrv.nl) ───────────────────
function getOptimizedImageUrl(url, width, height) {
  if (!url) return '';
  if (!/^https?:\/\//i.test(url)) {
    if (url.startsWith('data:') || url.startsWith('blob:') || url.startsWith('/')) return url;
    return '/' + url.replace(/^\.?\/+/, '');
  }
  let sourceUrl = url;
  if (sourceUrl.includes('googleusercontent.com')) {
    sourceUrl = /=[swh]\d+/.test(sourceUrl)
      ? sourceUrl.replace(/=[swh]\d+[^/]*$/, '=w512-h512-rw')
      : sourceUrl + '=w512-h512-rw';
  }
  const encoded = encodeURIComponent(sourceUrl);
  let quality = 80;
  if (width && width <= 112) quality = 65;
  else if (width && width < 200) quality = 72;
  let query = `?url=${encoded}&output=webp&q=${quality}&l=1&il=${width && width < 200 ? 0 : 1}&maxage=31d&n=-1`;
  if (width)  query += `&w=${width}`;
  if (height) query += `&h=${height}`;
  return `https://wsrv.nl/${query}`;
}

// ── CONVERSOR DE LINKS A DESCARGA DIRECTA ────────────────────
function convertToDirectLink(url) {
  if (!url || url === '#') return url;
  try {
    const u    = new URL(url);
    const host = u.hostname.replace('www.', '');
    if (host === 'drive.google.com') {
      let id = u.searchParams.get('id');
      if (!id) { const m = u.pathname.match(/\/d\/([a-zA-Z0-9_-]+)/); if (m) id = m[1]; }
      if (id) return `https://drive.google.com/uc?export=download&id=${id}&confirm=t`;
      return url;
    }
    if (host === 'dropbox.com' || host === 'dl.dropboxusercontent.com') {
      u.searchParams.set('dl', '1'); return u.toString();
    }
    if (host === 'onedrive.live.com') {
      u.pathname = u.pathname.replace('/redir', '/download');
      u.searchParams.set('download', '1'); return u.toString();
    }
    if (host === 'mediafire.com') {
      u.pathname = u.pathname.replace(/^\/file\//, '/download/'); return u.toString();
    }
    return url;
  } catch { return url; }
}

// ── CONTADOR DESCARGAS (localStorage) — opcional, no falla si el
// widget de stats no está presente en esta página ──────────────
function countDl() {
  try {
    const today = new Date().toDateString();
    const data = JSON.parse(localStorage.getItem('ch_dl') || '{"date":"","count":0}');
    const count = data.date === today ? data.count + 1 : 1;
    localStorage.setItem('ch_dl', JSON.stringify({ date: today, count }));
  } catch {}
}

// ── "Actualizado hace X días" ─────────────────────────────────
function timeAgo(dateStr) {
  if (!dateStr) return null;
  const then = new Date(dateStr).getTime();
  if (Number.isNaN(then)) return null;
  const diffMs = Date.now() - then;
  const days = Math.floor(diffMs / 86400000);
  if (days <= 0) {
    const hours = Math.floor(diffMs / 3600000);
    if (hours <= 0) return 'Actualizado hace un momento';
    return `Actualizado hace ${hours} hora${hours === 1 ? '' : 's'}`;
  }
  if (days === 1) return 'Actualizado ayer';
  if (days < 30) return `Actualizado hace ${days} días`;
  const months = Math.floor(days / 30);
  if (months < 12) return `Actualizado hace ${months} mes${months === 1 ? '' : 'es'}`;
  const years = Math.floor(months / 12);
  return `Actualizado hace ${years} año${years === 1 ? '' : 's'}`;
}

// ── CATEGORÍAS — id de sección (debe calzar con los <section id="…">
// ya presentes en opensource.html) + emoji para el tag ────────
const OS_CATEGORIES = [
  { categoria: 'Root y Sistema',  id: 'cat-root-y-sistema',   emoji: '🛠️' },
  { categoria: 'Música',          id: 'cat-música',           emoji: '🎵' },
  { categoria: 'Video',           id: 'cat-video',            emoji: '📺' },
  { categoria: 'VPN y Privacidad',id: 'cat-vpn-y-privacidad', emoji: '🔒' },
  { categoria: 'Productividad',   id: 'cat-productividad',    emoji: '✅' },
  { categoria: 'Lectura',         id: 'cat-lectura',          emoji: '📖' },
  { categoria: 'Mensajería',      id: 'cat-mensajería',       emoji: '💬' },
  { categoria: 'Fotografía',      id: 'cat-fotografía',       emoji: '📸' },
  { categoria: 'Utilidades',      id: 'cat-utilidades',       emoji: '🧰' },
];
const CAT_EMOJI = Object.fromEntries(OS_CATEGORIES.map(c => [c.categoria, c.emoji]));

// ── DIÁLOGOS DE INSTRUCCIONES ────────────────────────────────
function openHowToDialog(appId) {
  const modal = document.getElementById(`how-to-${appId}-modal`);
  if (modal) modal.classList.add('active');
}

function closeHowToDialog(appId) {
  const modal = document.getElementById(`how-to-${appId}-modal`);
  if (modal) modal.classList.remove('active');
}

document.addEventListener('keydown', e => {
  if (e.key === 'Escape') {
    const detail = document.getElementById('app-detail-overlay');
    if (detail && !detail.hidden) {
      closeAppDetail();
      return;
    }
    document.querySelectorAll('.how-to-modal.active').forEach(m => m.classList.remove('active'));
  }
});

function copyEchoExtensionUrl() {
  const extensionUrl = 'https://raw.githubusercontent.com/itsmechinmoy/echo-extensions/refs/heads/main/echo_extensions.json';
  navigator.clipboard.writeText(extensionUrl).then(() => {
    OSToast('✅ URL de extensiones copiada', 'success');
  }).catch(err => {
    console.error('Error al copiar:', err);
    OSToast('❌ No se pudo copiar', 'error');
  });
}

function setupBackToTopButton() {
  const btn = document.getElementById('to-top-btn');
  if (!btn) return;

  const toggleVisibility = () => {
    const shouldShow = window.scrollY > 500;
    btn.classList.toggle('visible', shouldShow);
  };

  btn.addEventListener('click', () => {
    window.scrollTo({ top: 0, behavior: 'smooth' });
  });

  window.addEventListener('scroll', toggleVisibility, { passive: true });
  toggleVisibility();
}

function buildOSCard(app, ratingInfo) {
  const img     = getOptimizedImageUrl(app.imagen || '', 192, 192);
  const version = app.version ? `v${app.version.replace(/^v/i, '')}` : null;
  const desc    = app.descripcion || '';
  const enlace  = convertToDirectLink(app.enlace && app.enlace !== '#' ? app.enlace : null);
  const dlUrl   = enlace ? `${BACKEND}/api/dl/${encodeURIComponent(app.appId)}` : null;
  const repoUrl = app.source_repo ? `https://github.com/${app.source_repo}` : null;
  const emoji   = CAT_EMOJI[app.categoria] || '📦';
  const updated = timeAgo(app.updatedAt);
  const badge   = (app.tag || '').includes('Actualiz') ? app.tag : null;
  const isFav   = MyApps.has(app.appId);
  const avg     = ratingInfo?.avg || 0;
  const count   = ratingInfo?.count || 0;

  const echoRaw = app.appId === 'os-echo-nightly' ? `
    <div class="os-echo-raw">
      <span>Extensiones</span>
      <button class="os-echo-copy-btn" data-haptic="tab" onclick="copyEchoExtensionUrl()">Copiar extension de pluhings</button>
    </div>` : '';

  const advancedApps = ['os-magisk', 'os-kernelsu', 'os-lsposed', 'os-app-manager', 'os-echo-nightly', 'os-shizuku'];
  const howToBtn = advancedApps.includes(app.appId) ? `
    <button class="how-to-btn" data-haptic="game" onclick="openHowToDialog('${esc(app.appId)}')">
      <i class="fas fa-book"></i> ¿Cómo usar?
    </button>` : '';

  const dlBtn = dlUrl
    ? `<a class="dl-btn dl-primary" data-haptic="tab" href="${dlUrl}" onclick="countDl()" target="_blank" rel="noopener"><i class="fas fa-download"></i> Descargar</a>`
    : `<a class="dl-btn dl-primary" data-haptic="tab" href="${repoUrl || '#'}${repoUrl ? '/releases' : ''}" target="_blank" rel="noopener"><i class="fas fa-download"></i> Descargar</a>`;

  const starsHtml = [1,2,3,4,5].map(n =>
    `<i class="fa-star ${n <= Math.round(avg) ? 'fas' : 'far'}" data-star="${n}" onclick="OSRatings.submit('${esc(app.appId)}', ${n}, '${esc(app.nombre)}')"></i>`
  ).join('');

  return `
  <div class="app-card" data-app-id="${app.appId}" data-cat="${app.categoria || ''}" data-name="${(app.nombre || '').toLowerCase()} ${(app.categoria || '').toLowerCase()}" data-repo="${app.source_repo || ''}" data-package="${app.packageName || ''}">
    <div class="app-thumb">
      <img src="${img}" alt="${esc(app.nombre)}" loading="lazy" decoding="async" onerror="this.parentElement.innerHTML='<div class=app-thumb-fallback>${emoji}</div>'">
      ${badge ? `<span class="app-badge badge-upd">${badge}</span>` : ''}
      <span class="app-verified-badge" style="display:flex">✅ Open Source</span>
      ${version ? `<span class="app-version-tag">${version}</span>` : ''}
      <button class="os-fav-btn ${isFav ? 'active' : ''}" data-haptic="tab" onclick="MyApps.toggle('${esc(app.appId)}')" title="${isFav ? 'Quitar de Mis apps' : 'Guardar en Mis apps'}" aria-label="${isFav ? 'Quitar de Mis apps' : 'Guardar en Mis apps'}">
        <i class="fas fa-heart"></i>
      </button>
    </div>
    <div class="app-body">
      <div class="app-cat-tag">${emoji} ${esc(app.categoria)}</div>
      <div class="app-name">${esc(app.nombre)}</div>
      <div class="os-rating" data-rating-for="${app.appId}" title="${count} voto${count === 1 ? '' : 's'}">
        <span class="os-rating-stars">${starsHtml}</span>
        <span class="os-rating-meta">${avg > 0 ? avg.toFixed(1) : '—'} <span class="os-rating-count">(${count})</span></span>
      </div>
      <div class="app-desc">${desc}</div>
      ${echoRaw}
      <div class="app-actions">
        ${howToBtn}
        ${dlBtn}
      </div>
      ${updated ? `<div class="os-updated-tag"><i class="fas fa-clock-rotate-left"></i> ${updated}</div>` : ''}
    </div>
  </div>`;
}

// ── RATINGS — enviar voto real + reflejar resultado al instante ────
const OSRatings = (() => {
  const voted = JSON.parse(localStorage.getItem('ch_os_voted') || '{}');
  function saveVoted() { try { localStorage.setItem('ch_os_voted', JSON.stringify(voted)); } catch {} }

  async function submit(appId, stars, appName) {
    if (voted[appId]) return; // ya votó desde este dispositivo
    const payload = { appId, appName, stars };
    // Sin conexión: encolar el voto y avisar — se reenvía solo al volver.
    if (!navigator.onLine) {
      const ok = await window.ChQueue.add({ url: `${BACKEND}/api/ratings`, method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload), kind: 'rating' });
      if (ok && window.ChQueue.toast) window.ChQueue.toast('📴 Sin conexión — tu voto se enviará cuando vuelvas');
      return;
    }
    try {
      const res = await fetch(`${BACKEND}/api/ratings`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      const d = await res.json();
      if (res.ok) {
        voted[appId] = stars; saveVoted();
        updateCard(appId, d.avg, d.count);
      } else if (d.avg != null) {
        // Ya había votado desde este IP en otra sesión — igual reflejar el estado real
        voted[appId] = true; saveVoted();
        updateCard(appId, d.avg, d.count);
      }
    } catch (e) {
      console.warn('rating error:', e.message);
      // Fallo de red: encolar para reenvío automático (offline-first)
      const ok = await window.ChQueue.add({ url: `${BACKEND}/api/ratings`, method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload), kind: 'rating' });
      if (ok && window.ChQueue.toast) window.ChQueue.toast('📴 Sin conexión — tu voto se enviará cuando vuelvas');
    }
  }

  function updateCard(appId, avg, count) {
    document.querySelectorAll(`[data-rating-for="${appId}"]`).forEach(el => {
      el.title = `${count} voto${count === 1 ? '' : 's'}`;
      el.querySelectorAll('.fa-star').forEach(star => {
        const n = parseInt(star.dataset.star, 10);
        star.className = `fa-star ${n <= Math.round(avg) ? 'fas' : 'far'}`;
      });
      const meta = el.querySelector('.os-rating-meta');
      if (meta) meta.innerHTML = `${avg > 0 ? avg.toFixed(1) : '—'} <span class="os-rating-count">(${count})</span>`;
    });
  }

  return { submit, updateCard };
})();

function ensureCategorySection(categoria) {
  const known = OS_CATEGORIES.find(c => c.categoria === categoria);
  if (known) return known.id;

  // Categoría nueva que aún no tiene sección estática en la página
  // (por ejemplo, se agregó una app con una categoría no prevista
  // desde admin-hub) — se crea la sección al vuelo antes del bloque
  // de anuncio final, y se agrega también al índice de arriba.
  const id = 'cat-' + categoria.toLowerCase().replace(/\s+/g, '-');
  if (document.getElementById(id)) return id;

  const section = document.createElement('section');
  section.className = 'os-category';
  section.id = id;
  section.innerHTML = `
    <div class="os-cat-header"><h2>📦 ${categoria}</h2></div>
    <div class="app-grid" id="grid-${id}"></div>`;
  const adSlot = document.querySelector('.ad-slot:last-of-type') || document.querySelector('footer');
  adSlot.parentElement.insertBefore(section, adSlot);

  const toc = document.querySelector('.os-toc');
  if (toc) {
    const a = document.createElement('a');
    a.href = `#${id}`;
    a.textContent = `📦 ${categoria}`;
    toc.appendChild(a);
  }
  OS_CATEGORIES.push({ categoria, id, emoji: '📦' });
  osEnsureCollapseUI(section);
  const stored = osStoredCollapse();
  osSetCollapsed(section, id in stored ? stored[id] : true, false);
  return id;
}

/* ═══════════════════════════════════════════════════════════════
   Secciones plegables — cuadros de apps expandibles/contraíbles.
   Un clic en la cabecera de una categoría despliega/colapsa su grid.
   El estado persiste por categoría en localStorage ('os.catCollapsed');
   el índice expande la sección destino y los botones permiten
   expandir/contraer todo. Deep-link: #cat-... abre esa categoría.
   ═══════════════════════════════════════════════════════════════ */
const OS_COLLAPSED_KEY = 'os.catCollapsed';

function osStoredCollapse() {
  try { return JSON.parse(localStorage.getItem(OS_COLLAPSED_KEY) || '{}') || {}; }
  catch { return {}; }
}

function osPersistCollapse(state) {
  try { localStorage.setItem(OS_COLLAPSED_KEY, JSON.stringify(state)); } catch {}
}

function osIsCatalogSection(section) {
  return /^cat-/.test(section.id || '');
}

function osDefaultCollapsed(section) {
  return osIsCatalogSection(section);
}

function osSetCollapsed(section, collapsed, persist) {
  section.setAttribute('data-collapsed', collapsed ? 'true' : 'false');
  const toggle = section.querySelector('.os-cat-toggle');
  if (toggle) toggle.setAttribute('aria-expanded', String(!collapsed));
  if (persist) {
    const state = osStoredCollapse();
    state[section.id] = collapsed;
    osPersistCollapse(state);
  }
}

function osToggleSection(section) {
  osSetCollapsed(section, section.getAttribute('data-collapsed') !== 'true', true);
}

// Prepara la cabecera de una sección: wrapper del texto, contador de
// apps y botón chevron accesible. Idempotente, sirve para secciones
// estáticas y para las que se crean al vuelo.
function osEnsureCollapseUI(section) {
  const header = section.querySelector('.os-cat-header');
  if (!header || header.dataset.osReady) return;

  const kids = Array.from(header.childNodes);
  if (!header.querySelector(':scope > .os-cat-copy')) {
    const copy = document.createElement('div');
    copy.className = 'os-cat-copy';
    header.appendChild(copy);
    kids.forEach(n => copy.appendChild(n));
  }

  if (!header.querySelector('.os-cat-meta')) {
    const meta = document.createElement('div');
    meta.className = 'os-cat-meta';
    const count = document.createElement('span');
    count.className = 'os-cat-count';
    const toggle = document.createElement('button');
    toggle.type = 'button';
    toggle.className = 'os-cat-toggle';
    toggle.setAttribute('aria-label', 'Alternar sección');
    toggle.setAttribute('aria-expanded', 'true');
    toggle.innerHTML = '<i class="fa-solid fa-chevron-down"></i>';
    toggle.addEventListener('click', e => { e.stopPropagation(); osToggleSection(section); });
    meta.appendChild(count);
    meta.appendChild(toggle);
    header.appendChild(meta);
  }

  header.tabIndex = 0;
  header.addEventListener('click', e => {
    if (e.target.closest('a,button,[role="button"]')) return;
    osToggleSection(section);
  });
  header.addEventListener('keydown', e => {
    if (e.target === header && (e.key === 'Enter' || e.key === ' ')) {
      e.preventDefault();
      osToggleSection(section);
    }
  });
  header.dataset.osReady = '1';
}

function osSetCount(section, count) {
  const el = section.querySelector('.os-cat-count');
  if (el) el.textContent = `${count} ${count === 1 ? 'app' : 'apps'}`;
}

function osSetupCollapsible() {
  document.querySelectorAll('.os-category').forEach(section => {
    osEnsureCollapseUI(section);
    const stored = osStoredCollapse();
    osSetCollapsed(section, section.id in stored ? stored[section.id] : osDefaultCollapsed(section), false);
  });

  const toc = document.querySelector('.os-toc');
  if (toc) {
    // Un clic en el índice abre la categoría destino antes del scroll.
    toc.addEventListener('click', e => {
      const a = e.target.closest('a[href^="#"]');
      if (!a) return;
      const section = document.getElementById((a.getAttribute('href') || '').slice(1));
      if (section && osIsCatalogSection(section)) osSetCollapsed(section, false, true);
    });
    // Botones de expandir/contraer todo al inicio del índice.
    if (!document.getElementById('os-toc-expand')) {
      const expand = document.createElement('button');
      expand.type = 'button';
      expand.id = 'os-toc-expand';
      expand.className = 'os-toc-collapse';
      expand.innerHTML = '<i class="fa-solid fa-table-cells-large"></i> Expandir todo';
      expand.addEventListener('click', () => {
        document.querySelectorAll('.os-category').forEach(s => {
          if (osIsCatalogSection(s)) osSetCollapsed(s, false, true);
        });
      });
      const collapse = document.createElement('button');
      collapse.type = 'button';
      collapse.id = 'os-toc-collapse';
      collapse.className = 'os-toc-collapse';
      collapse.innerHTML = '<i class="fa-solid fa-compress"></i> Contraer todo';
      collapse.addEventListener('click', () => {
        document.querySelectorAll('.os-category').forEach(s => {
          if (osIsCatalogSection(s)) osSetCollapsed(s, true, true);
        });
      });
      toc.insertBefore(collapse, toc.firstChild);
      toc.insertBefore(expand, toc.firstChild);
    }
  }

  // Deep-link: si se llega con #cat-... en la URL, abrir esa categoría.
  if (location.hash) {
    const section = document.getElementById(location.hash.slice(1));
    if (section && osIsCatalogSection(section)) osSetCollapsed(section, false, true);
  }
}

async function loadOpenSourceCatalog() {
  const heroCount = document.getElementById('os-hero-count');
  try {
    const [res, ratingsRes] = await Promise.all([
      fetch(`${BACKEND}/api/apps`),
      fetch(`${BACKEND}/api/ratings`).catch(() => null),
    ]);
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    const ratingsData = ratingsRes && ratingsRes.ok ? await ratingsRes.json().catch(() => ({})) : {};
    window.__osRatings = ratingsData.ratings || {};
    // El backend devuelve { apps: [...], total }; se acepta también
    // un array plano por compatibilidad.
    const apps = Array.isArray(data) ? data : (data.apps || []);

    const osApps = apps.filter(a => !!a.source_repo);
    window.__osCatalog = osApps; // usado por DeviceApps para el escaneo de instaladas

    if (heroCount) heroCount.textContent = `${osApps.length} apps`;

    const byCategory = {};
    osApps.forEach(a => {
      const cat = a.categoria || 'Utilidades';
      (byCategory[cat] = byCategory[cat] || []).push(a);
    });

    OS_CATEGORIES.forEach(({ categoria, id }) => {
      const grid = document.getElementById(`grid-${id}`);
      if (!grid) return;
      const list = byCategory[categoria] || [];
      grid.innerHTML = list.length
        ? list.map(a => buildOSCard(a, window.__osRatings[a.appId])).join('')
        : `<div style="grid-column:1/-1;text-align:center;padding:1.2rem;color:var(--muted,#8a8a9a);font-size:.82rem">Aún no hay apps en esta categoría.</div>`;
    });

    // Categorías presentes en los datos pero sin sección aún.
    Object.keys(byCategory).forEach(cat => {
      if (OS_CATEGORIES.some(c => c.categoria === cat)) return;
      const id = ensureCategorySection(cat);
      const grid = document.getElementById(`grid-${id}`);
      if (grid) grid.innerHTML = byCategory[cat].map(a => buildOSCard(a, window.__osRatings[a.appId])).join('');
    });

    // Contadores "N apps" por sección (el setup/plegado ya está aplicado).
    document.querySelectorAll('.os-category').forEach(sec => {
      if (!osIsCatalogSection(sec)) return;
      const grid = sec.querySelector('.app-grid');
      osSetCount(sec, grid ? grid.querySelectorAll('.app-card').length : 0);
    });
  } catch (e) {
    console.error('Error cargando catálogo Open Source:', e);
    if (heroCount) heroCount.textContent = 'Error al cargar';
    document.querySelectorAll('.app-grid').forEach(grid => {
      grid.innerHTML = `<div style="grid-column:1/-1;text-align:center;padding:1.2rem;color:var(--muted,#8a8a9a);font-size:.82rem"><i class="fas fa-exclamation-triangle"></i> No se pudo conectar con el servidor. Recarga la página.</div>`;
    });
  } finally {
    // Avisa a opensource.html que el catálogo ya terminó de renderizarse
    // (con datos o con el mensaje de error), para que el guide tour pueda
    // apuntar a un botón de descarga real en vez de al placeholder "Cargando…".
    document.dispatchEvent(new CustomEvent('os:catalog-loaded'));
  }
}

/* ═══════════════════════════════════════════════════════════════
   Catálogo 2.0 — Búsqueda global, filtro por categoría y orden.
   Con un filtro activo se ocultan las secciones por categoría y se
   muestra un grid único de resultados (mismas tarjetas, mismo rating
   en vivo). Con todo limpio se vuelve al catálogo agrupado.
   Atajo: "/" enfoca la búsqueda desde cualquier parte de la página.
   ═══════════════════════════════════════════════════════════════ */
const OS_FILTER = { q: '', cat: 'all', sort: 'catalog' };

function osResultsActive() {
  return !!(OS_FILTER.q.trim() || OS_FILTER.cat !== 'all' || OS_FILTER.sort !== 'catalog');
}

function osFilterMatches(app) {
  const q = OS_FILTER.q.trim().toLowerCase();
  if (q) {
    const hay = [app.nombre, app.categoria, app.source_repo, app.descripcion, app.packageName, app.version]
      .filter(Boolean).join(' ').toLowerCase();
    if (!hay.includes(q)) return false;
  }
  if (OS_FILTER.cat !== 'all' && (app.categoria || 'Utilidades') !== OS_FILTER.cat) return false;
  return true;
}

function osSortApps(a, b) {
  const kind = OS_FILTER.sort;
  if (kind === 'name') return (a.nombre || '').localeCompare(b.nombre || '', 'es');
  if (kind === 'rating') {
    const ra = window.__osRatings?.[a.appId]?.avg || 0;
    const rb = window.__osRatings?.[b.appId]?.avg || 0;
    if (rb !== ra) return rb - ra;
    const ca = window.__osRatings?.[a.appId]?.count || 0;
    const cb = window.__osRatings?.[b.appId]?.count || 0;
    if (cb !== ca) return cb - ca;
    return (a.nombre || '').localeCompare(b.nombre || '', 'es');
  }
  if (kind === 'updated') return String(b.updatedAt || '').localeCompare(String(a.updatedAt || ''));
  return 0;
}

function osApplyFilter(force) {
  const wrap = document.getElementById('os-results-wrap');
  if (!wrap) return;
  const active = osResultsActive();

  document.querySelectorAll('.os-category').forEach(sec => {
    if (osIsCatalogSection(sec)) sec.style.display = active ? 'none' : '';
  });
  ['my-apps-section', 'device-apps-section'].forEach(id => {
    const sec = document.getElementById(id);
    if (sec) sec.style.display = active ? 'none' : '';
  });
  wrap.hidden = !active;
  if (!active) return;

  const list = (window.__osCatalog || []).filter(osFilterMatches).slice().sort(osSortApps);

  const info = document.getElementById('os-results-info');
  if (info) {
    let t = `${list.length} ${list.length === 1 ? 'app' : 'apps'}`;
    if (OS_FILTER.q.trim()) t += ` para "${OS_FILTER.q.trim()}"`;
    if (OS_FILTER.cat !== 'all') t += ` en ${OS_FILTER.cat}`;
    info.textContent = t;
  }
  const grid = document.getElementById('os-results-grid');
  if (grid) {
    grid.innerHTML = list.length
      ? list.map(a => buildOSCard(a, window.__osRatings?.[a.appId])).join('')
      : '';
  }
  const empty = document.getElementById('os-results-empty');
  if (empty) empty.hidden = list.length > 0;
  const clear = document.getElementById('os-search-clear');
  if (clear) clear.hidden = !OS_FILTER.q.trim();
}

function osUpdateChips() {
  const wrap = document.getElementById('os-chips');
  if (!wrap) return;
  const counts = {};
  (window.__osCatalog || []).forEach(app => {
    const cat = app.categoria || 'Utilidades';
    counts[cat] = (counts[cat] || 0) + 1;
  });
  const cats = OS_CATEGORIES.map(c => c.categoria)
    .concat(Object.keys(counts).filter(c => !OS_CATEGORIES.some(o => o.categoria === c)));
  const total = (window.__osCatalog || []).length;

  let html = `<button type="button" class="os-chip${OS_FILTER.cat === 'all' ? ' on' : ''}" data-cat="all"><i class="fa-solid fa-border-all"></i> Todo <span class="os-chip-count">${total}</span></button>`;
  cats.forEach(cat => {
    const emoji = CAT_EMOJI[cat] || '📦';
    html += `<button type="button" class="os-chip${OS_FILTER.cat === cat ? ' on' : ''}" data-cat="${esc(cat)}">${emoji} ${esc(cat)} <span class="os-chip-count">${counts[cat] || 0}</span></button>`;
  });
  wrap.innerHTML = html;
  wrap.querySelectorAll('.os-chip').forEach(btn => {
    btn.addEventListener('click', () => {
      OS_FILTER.cat = btn.dataset.cat;
      osUpdateChips();
      osApplyFilter();
    });
  });
}

function osClearFilters() {
  OS_FILTER.q = ''; OS_FILTER.cat = 'all'; OS_FILTER.sort = 'catalog';
  const input = document.getElementById('os-search-input');
  const sort = document.getElementById('os-sort-select');
  if (input) input.value = '';
  if (sort) sort.value = 'catalog';
  osUpdateChips();
  osApplyFilter();
}

function osBindSearchTools() {
  const input = document.getElementById('os-search-input');
  const clear = document.getElementById('os-search-clear');
  const sort = document.getElementById('os-sort-select');
  const resultsClear = document.getElementById('os-results-clear');
  const emptyClear = document.querySelector('.os-results-empty [data-clear]');
  if (!input) return;
  let timer = null;
  input.addEventListener('input', () => {
    clearTimeout(timer);
    timer = setTimeout(() => { OS_FILTER.q = input.value; osApplyFilter(); }, 200);
  });
  input.addEventListener('keydown', e => {
    if (e.key === 'Escape') { input.value = ''; OS_FILTER.q = ''; osApplyFilter(); input.blur(); }
  });
  if (clear) clear.addEventListener('click', () => { input.value = ''; OS_FILTER.q = ''; osApplyFilter(); input.focus(); });
  if (sort) sort.addEventListener('change', () => { OS_FILTER.sort = sort.value; osApplyFilter(); });
  if (resultsClear) resultsClear.addEventListener('click', osClearFilters);
  if (emptyClear) emptyClear.addEventListener('click', osClearFilters);

  document.addEventListener('keydown', e => {
    if (e.key === '/' && !e.ctrlKey && !e.metaKey && !e.altKey &&
        !/^(input|textarea|select)$/i.test((document.activeElement && document.activeElement.tagName) || '')) {
      e.preventDefault();
      input.focus();
    }
  });
}

osBindSearchTools();
osUpdateChips();
osApplyFilter();
document.addEventListener('os:catalog-loaded', () => { osUpdateChips(); osApplyFilter(); });

/* ═══════════════════════════════════════════════════════════════
   Catálogo 2.0 — Modal de detalle de app (datos que el backend ya
   entrega: changelog, tutorial_url, plugin_enlace, packageName…).
   Clic en una tarjeta abre el detalle; los elementos accionables
   (descargar, favorito, cómo usar, estrellas, copiar) no lo hacen.
   ═══════════════════════════════════════════════════════════════ */
const OS_ADVANCED_APPS = ['os-magisk', 'os-kernelsu', 'os-lsposed', 'os-app-manager', 'os-echo-nightly', 'os-shizuku'];

function renderAppChangelog(text) {
  const lines = String(text || '').split(/\r?\n/).map(l => l.trim()).filter(Boolean);
  if (!lines.length) return '<p>Sin notas de cambio para esta versión.</p>';
  return lines.map(l => /^[•\-\*\d\.]\s?/.test(l) || /^\d+\.\s/.test(l)
    ? `<li>${esc(l.replace(/^[•\-\*\d\.]\s?/, ''))}</li>`
    : `<p>${esc(l)}</p>`).join('');
}

function renderAppDetail(app) {
  const body = document.getElementById('app-detail-body');
  if (!body) return;
  const emoji = CAT_EMOJI[app.categoria] || '📦';
  const img = getOptimizedImageUrl(app.imagen || '', 128, 128);
  const version = app.version ? `v${app.version.replace(/^v/i, '')}` : null;
  const updated = timeAgo(app.updatedAt);
  const rating = window.__osRatings?.[app.appId] || {};
  const avg = rating.avg || 0;
  const count = rating.count || 0;
  const repoUrl = app.source_repo ? `https://github.com/${app.source_repo}` : null;
  const enlace = convertToDirectLink(app.enlace && app.enlace !== '#' ? app.enlace : null);
  const dlUrl = enlace ? `${BACKEND}/api/dl/${encodeURIComponent(app.appId)}` : null;
  const pluginUrl = convertToDirectLink(app.plugin_enlace && app.plugin_enlace !== '#' ? app.plugin_enlace : null);
  const tutorialUrl = app.tutorial_url && app.tutorial_url !== '#' ? app.tutorial_url : null;
  const advanced = OS_ADVANCED_APPS.includes(app.appId);
  const isFav = MyApps.has(app.appId);
  const changelog = app.changelog && String(app.changelog).trim();

  const badges = [`<span>✅ Open Source</span>`]
    .concat(version ? [`<span>Versión ${esc(version)}</span>`] : [])
    .concat(app.verified ? [`<span>Verificada</span>`] : [])
    .join('');

  const starsHtml = [1, 2, 3, 4, 5].map(n =>
    `<i class="fa-star ${n <= Math.round(avg) ? 'fas' : 'far'}" data-star="${n}" onclick="OSRatings.submit('${esc(app.appId)}', ${n}, '${esc(app.nombre)}')"></i>`
  ).join('');

  const echoRaw = app.appId === 'os-echo-nightly' ? `
    <div class="app-detail-block">
      <div class="app-detail-block-h"><i class="fa-solid fa-puzzle-piece"></i> Extensiones</div>
      <div class="os-echo-raw" style="margin-top:.3rem">
        <button class="os-echo-copy-btn" data-haptic="tab" onclick="copyEchoExtensionUrl()">Copiar URL de extensiones</button>
      </div>
    </div>` : '';

  const metaItems = [];
  if (repoUrl) metaItems.push(`<a href="${repoUrl}" target="_blank" rel="noopener" data-haptic="tab"><i class="fa-brands fa-github"></i> Repositorio</a>`);
  if (app.source_repo) metaItems.push(`<button onclick="copyDetailText('${esc(app.source_repo)}','repo')" data-haptic="tab"><i class="fa-solid fa-tag"></i> ${esc(app.source_repo)}</button>`);
  if (app.packageName) metaItems.push(`<button onclick="copyDetailText('${esc(app.packageName)}','packageName')" data-haptic="tab"><i class="fa-solid fa-box"></i> ${esc(app.packageName)}</button>`);
  if (pluginUrl) metaItems.push(`<a href="${pluginUrl}" target="_blank" rel="noopener" data-haptic="tab"><i class="fa-solid fa-plug"></i> Plugin</a>`);
  if (tutorialUrl) metaItems.push(`<a href="${tutorialUrl}" target="_blank" rel="noopener" data-haptic="tab"><i class="fa-solid fa-book"></i> Tutorial</a>`);

  const howToBtn = advanced ? `
    <button class="how-to-btn" data-haptic="game" onclick="openHowToDialog('${esc(app.appId)}')">
      <i class="fas fa-book"></i> ¿Cómo usar?
    </button>` : '';

  const dlBtn = dlUrl
    ? `<a class="dl-btn dl-primary" data-haptic="tab" href="${dlUrl}" onclick="countDl()" target="_blank" rel="noopener"><i class="fas fa-download"></i> Descargar</a>`
    : `<a class="dl-btn dl-primary" data-haptic="tab" href="${(repoUrl || '') + '/releases'}" target="_blank" rel="noopener" ${repoUrl ? '' : 'aria-disabled="true"'}><i class="fas fa-download"></i> Ver en Releases</a>`;

  body.innerHTML = `
    <div class="app-detail-head">
      <img class="app-detail-img" src="${img}" alt="${esc(app.nombre)}" loading="lazy" decoding="async" onerror="this.outerHTML='<div class=app-detail-img-fallback>${emoji}</div>'">
      <div class="app-detail-titlewrap">
        <div class="app-detail-cat">${emoji} ${esc(app.categoria || 'Utilidades')}</div>
        <div class="app-detail-title" id="app-detail-title">${esc(app.nombre)}</div>
        <div class="app-detail-badges">${badges}</div>
      </div>
    </div>
    <div class="app-detail-score">
      <div class="os-rating" data-rating-for="${esc(app.appId)}" title="${count} voto${count === 1 ? '' : 's'}">
        <span class="os-rating-stars">${starsHtml}</span>
        <span class="os-rating-meta">${avg > 0 ? avg.toFixed(1) : '—'} <span class="os-rating-count">(${count})</span></span>
      </div>
      ${updated ? `<span class="os-updated-tag" style="margin:0"><i class="fas fa-clock-rotate-left"></i> ${updated}</span>` : ''}
    </div>
    <p class="app-detail-desc">${esc(app.descripcion || 'Sin descripción.')}</p>
    ${changelog ? `
    <div class="app-detail-block" id="app-detail-changelog-block">
      <div class="app-detail-block-h"><i class="fa-solid fa-clock-rotate-left"></i> Novedades</div>
      <div class="app-detail-changelog capped" id="app-detail-changelog">${renderAppChangelog(changelog)}</div>
      <button class="app-detail-more" id="app-detail-changelog-more" type="button" onclick="osToggleChangelog(this)">Ver todo</button>
    </div>` : ''}
    ${echoRaw}
    ${metaItems.length ? `<div class="app-detail-meta">${metaItems.join('')}</div>` : ''}
    <div class="app-detail-actions">
      ${howToBtn}
      ${dlBtn}
      <button class="os-fav-btn ${isFav ? 'active' : ''}" data-haptic="tab" onclick="MyApps.toggle('${esc(app.appId)}')" title="${isFav ? 'Quitar de Mis apps' : 'Guardar en Mis apps'}" style="position:static">
        <i class="fas fa-heart"></i>
      </button>
    </div>`;
}

function openAppDetail(appId) {
  const overlay = document.getElementById('app-detail-overlay');
  const app = (window.__osCatalog || []).find(a => a.appId === appId);
  if (!overlay) return;
  if (!app) { OSToast('No encontramos el detalle de esa app', 'error'); return; }
  renderAppDetail(app);
  overlay.hidden = false;
  document.body.style.overflow = 'hidden';
  const closeBtn = document.getElementById('app-detail-close');
  if (closeBtn) closeBtn.focus();
}

function closeAppDetail() {
  const overlay = document.getElementById('app-detail-overlay');
  if (!overlay) return;
  overlay.hidden = true;
  document.body.style.overflow = '';
}

function osToggleChangelog(btn) {
  const block = document.getElementById('app-detail-changelog');
  if (!block) return;
  const capped = block.classList.toggle('capped');
  btn.textContent = capped ? 'Ver todo' : 'Ver menos';
}

function copyDetailText(text, label) {
  navigator.clipboard.writeText(text || '').then(() => {
    OSToast(`✅ ${label || 'Texto'} copiado`, 'success');
  }).catch(() => OSToast('❌ No se pudo copiar', 'error'));
}

function osBindDetailTools() {
  const overlay = document.getElementById('app-detail-overlay');
  if (overlay) {
    overlay.addEventListener('click', e => { if (e.target === overlay) closeAppDetail(); });
  }
  const closeBtn = document.getElementById('app-detail-close');
  if (closeBtn) closeBtn.addEventListener('click', closeAppDetail);

  document.addEventListener('click', e => {
    if (e.target.closest('#app-detail-overlay, .how-to-modal, #we-drawer, #we-backdrop')) return;
    const card = e.target.closest('.app-card');
    if (!card || !card.dataset.appId) return;
    if (e.target.closest('a, button, .os-rating-stars, .os-echo-copy-btn')) return;
    openAppDetail(card.dataset.appId);
  });

  document.addEventListener('keydown', e => {
    if ((e.key === 'Enter' || e.key === ' ') && e.target && e.target.classList && e.target.classList.contains('app-card')) {
      e.preventDefault();
      openAppDetail(e.target.dataset.appId);
    }
  });
}

osBindDetailTools();

// ── TIEMPO REAL ─────────────────────────────────────────
// El backend emite 'apps_changed' (total y apps open source) cada vez que
// el admin crea, edita, borra o siembra apps. Con el contador se actualiza
// al instante sin esperar el TTL de la caché; el catálogo se recarga solo
// si el número de apps open source cambió.
let _ws = null;
let _wsTimer = null;
let _lastOsCount = null;

// Pausa el polling cuando la pestaña/WebView no está visible (batería),
// y retoma al volver. Devuelve una API { stop, start }.
function pausableInterval(fn, ms) {
  let t = null;
  const stop = () => { if (t) { clearInterval(t); t = null; } };
  const start = () => { if (!t) { t = setInterval(fn, ms); } };
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { stop(); } else { start(); }
  });
  start();
  return { stop, start };
}

function connectOSWebSocket() {
  try {
    if (_ws) { try { _ws.close(); } catch {} }
    clearTimeout(_wsTimer);
    const wsUrl = (BACKEND.startsWith('https://') ? 'wss://' : 'ws://') + BACKEND.replace(/^https?:\/\//, '') + '/ws';
    _ws = new WebSocket(wsUrl);
    _ws.onmessage = e => {
      try {
        const msg = JSON.parse(e.data);
        if (msg.type === 'apps_changed' && typeof msg.os === 'number') {
          const heroCount = document.getElementById('os-hero-count');
          if (heroCount) heroCount.textContent = `${msg.os} apps`;
          if (msg.os !== _lastOsCount) { _lastOsCount = msg.os; loadOpenSourceCatalog(); }
        } else if (msg.type === 'new_app') {
          loadOpenSourceCatalog();
        } else if (msg.type === 'new_rating' && msg.appId) {
          if (window.__osRatings) window.__osRatings[msg.appId] = { avg: msg.avg, count: msg.count };
          OSRatings.updateCard(msg.appId, msg.avg, msg.count);
        }
      } catch {}
    };
    _ws.onclose = () => {
      if (!document.hidden) _wsTimer = setTimeout(connectOSWebSocket, 10000);
    };
    _ws.onerror = () => { try { _ws.close(); } catch {} };
  } catch {}
}

osSetupCollapsible();
loadOpenSourceCatalog();
connectOSWebSocket();
pausableInterval(() => {
  if (!_ws || _ws.readyState !== 1) {
    const heroCount = document.getElementById('os-hero-count');
    if (heroCount) fetch(`${BACKEND}/api/apps`)
      .then(r => r.json())
      .then(data => {
        const apps = Array.isArray(data) ? data : (data.apps || []);
        const os = apps.filter(a => !!a.source_repo).length;
        heroCount.textContent = `${os} apps`;
        if (os !== _lastOsCount) { _lastOsCount = os; loadOpenSourceCatalog(); }
        else _lastOsCount = os;
      })
      .catch(() => {});
  }
}, 5 * 60 * 1000);

document.addEventListener('visibilitychange', () => {
  if (document.hidden) return;
  if (_wsTimer) { clearTimeout(_wsTimer); _wsTimer = null; }
  if (!_ws || _ws.readyState > 1) connectOSWebSocket();
  MyApps.updateUI();
  DeviceApps.updateUI();
});

/* ═══════════════════════════════════════════════════════════════
   MyApps — Guardar apps favoritas + verificar actualizaciones
   ═══════════════════════════════════════════════════════════════ */
const MyApps = (() => {
  const STORAGE_KEY = 'ch_my_apps';

  function _load() {
    try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]'); }
    catch { return []; }
  }

  function _save(list) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(list));
  }

  function has(appId) {
    return _load().some(a => a.appId === appId);
  }

  function toggle(appId) {
    let list = _load();
    const idx = list.findIndex(a => a.appId === appId);
    if (idx >= 0) {
      list.splice(idx, 1);
    } else {
      const card = document.querySelector(`.app-card[data-app-id="${appId}"]`);
      if (card) {
        const nombre = card.querySelector('.app-name')?.textContent || '';
        const version = card.querySelector('.app-version-tag')?.textContent?.replace(/^v/, '') || '';
        const imagen = card.querySelector('.app-thumb img')?.src || '';
        const repo = card.dataset.repo || '';
        list.push({ appId, nombre, version, imagen, source_repo: repo });
      }
    }
    _save(list);
    _updateUI();
    _refreshFavButtons();
    OSToast(idx >= 0 ? '📦 App removida de Mis apps' : '❤️ App guardada en Mis apps', 'info');
  }

  function _refreshFavButtons() {
    document.querySelectorAll('.os-fav-btn').forEach(btn => {
      const card = btn.closest('.app-card');
      const appId = card?.dataset?.appId;
      if (!appId) return;
      const fav = has(appId);
      btn.classList.toggle('active', fav);
      btn.title = fav ? 'Quitar de Mis apps' : 'Guardar en Mis apps';
      btn.querySelector('i').className = 'fas fa-heart';
    });
  }

  async function _updateUI() {
    const list = _load();
    const section = document.getElementById('my-apps-section');
    const tocLink = document.getElementById('my-apps-toc-link');
    const grid = document.getElementById('my-apps-grid');
    if (!section || !grid) return;

    if (list.length === 0) {
      section.style.display = 'none';
      if (tocLink) tocLink.style.display = 'none';
      return;
    }

    section.style.display = '';
    if (tocLink) tocLink.style.display = '';

    // Check for updates via backend
    let updates = {};
    try {
      const res = await fetch(`${BACKEND}/api/app-updates`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ apps: list })
      });
      if (res.ok) {
        const data = await res.json();
        data.forEach(u => { updates[u.appId] = u; });
      }
    } catch (e) { console.warn('Update check failed:', e); }

    grid.innerHTML = list.map(app => {
      const u = updates[app.appId];
      const hasUpdate = u && u.hasUpdate;
      const latestVersion = u?.latestVersion || '';
      const dlUrl = u?.downloadUrl || `https://github.com/${app.source_repo}/releases/latest`;

      return `
      <div class="app-card my-app-card ${hasUpdate ? 'has-update' : ''}" data-app-id="${app.appId}">
        <div class="app-thumb">
          <img src="${app.imagen}" alt="${esc(app.nombre)}" loading="lazy" decoding="async" onerror="this.parentElement.innerHTML='<div class=app-thumb-fallback>📦</div>'">
          ${hasUpdate ? '<span class="app-badge badge-update">🆕 Actualiza</span>' : ''}
        </div>
        <div class="app-body">
          <div class="app-name">${esc(app.nombre)}</div>
          ${hasUpdate
            ? `<div class="my-app-version-diff"><span class="my-app-old">v${esc(app.version) || '?'}</span> → <span class="my-app-new">${esc(latestVersion)}</span></div>`
            : `<div class="my-app-version">v${esc(app.version) || 'desconocida'}</div>`
          }
          <div class="app-actions">
            ${hasUpdate
              ? `<a class="dl-btn dl-primary my-app-update-btn" data-haptic="game" href="${dlUrl}" target="_blank" rel="noopener"><i class="fas fa-arrow-up"></i> Actualizar ahora</a>`
              : `<a class="dl-btn dl-primary" data-haptic="tab" href="${dlUrl}" target="_blank" rel="noopener"><i class="fas fa-check"></i> Última versión</a>`
            }
            <button class="os-fav-btn active" data-haptic="tab" onclick="MyApps.toggle('${esc(app.appId)}')" title="Quitar de Mis apps">
              <i class="fas fa-heart"></i>
            </button>
          </div>
        </div>
      </div>`;
    }).join('');
  }

  return { has, toggle, updateUI: _updateUI };
})();

// Render initial My Apps state after catalog loads
document.addEventListener('os:catalog-loaded', () => MyApps.updateUI());

// ── Check periódico de actualizaciones (cada 5 min, pausa si invisible) ──
pausableInterval(() => { MyApps.updateUI(); }, 5 * 60 * 1000);

/* ═══════════════════════════════════════════════════════════════
   DeviceApps — Detección real de apps instaladas (solo dentro del
   APK, vía window.CodeHubNative — ver CodeHubBridge.java) +
   actualización automática con Shizuku si está disponible.

   En web (navegador normal) esta sección no existe: no hay forma de
   listar apps instaladas de un dispositivo desde JS de página web,
   eso solo lo puede hacer código nativo Android con permiso de
   visibilidad de paquetes. Por eso "Mis apps" (favoritos manuales,
   arriba) sigue siendo el mecanismo en web.
   ═══════════════════════════════════════════════════════════════ */
const DeviceApps = (() => {
  const isNative = () => !!(window.CodeHubNative && window.CodeHubNative.getInstalledVersions);

  // Genera un nombre de callback único en window para cada llamada al
  // bridge nativo (que solo puede invocar funciones globales por nombre,
  // vía webView.loadUrl("javascript:nombre(...)")) y se autolimpia.
  function _withCallback(prefix, fn) {
    const name = `__os_${prefix}_${Date.now()}_${Math.random().toString(36).slice(2)}`;
    window[name] = (...args) => { try { fn(...args); } finally { delete window[name]; } };
    return name;
  }

  async function _shizukuStatus() {
    if (!isNative() || !window.CodeHubNative.isShizukuAvailable) {
      return { available: false, granted: false };
    }
    let available = false, granted = false;
    try { available = !!window.CodeHubNative.isShizukuAvailable(); } catch (e) {}
    try { granted = available && !!window.CodeHubNative.hasShizukuPermission(); } catch (e) {}
    return { available, granted };
  }

  function _requestShizukuPermission() {
    return new Promise(resolve => {
      const cb = _withCallback('shizuku_perm', granted => resolve(!!granted));
      try { window.CodeHubNative.requestShizukuPermission(cb); }
      catch (e) { delete window[cb]; resolve(false); }
    });
  }

  function _installApp(app, dlUrl, preferSilent) {
    return new Promise(resolve => {
      const cb = _withCallback('install', (status, message) => resolve({ status, message }));
      try { window.CodeHubNative.downloadAndInstallApk(dlUrl, app.appId, !!preferSilent, cb); }
      catch (e) { delete window[cb]; resolve({ status: 'error', message: e.message || 'error' }); }
    });
  }

  async function _renderShizukuBanner() {
    const banner = document.getElementById('device-apps-shizuku-banner');
    if (!banner) return;
    const { available, granted } = await _shizukuStatus();

    if (!available) {
      banner.style.display = 'none';
      return;
    }
    banner.style.display = 'flex';
    if (granted) {
      banner.className = 'device-shizuku-banner ok';
      banner.innerHTML = `<i class="fas fa-bolt"></i> Instalación automática activa (Shizuku) — las actualizaciones se instalan sin confirmación.`;
    } else {
      banner.className = 'device-shizuku-banner pending';
      banner.innerHTML = `<i class="fas fa-bolt"></i> Shizuku detectado. <button id="device-shizuku-activate">Activar instalación automática</button>`;
      const btn = document.getElementById('device-shizuku-activate');
      if (btn) btn.onclick = async () => {
        btn.disabled = true; btn.textContent = 'Esperando confirmación...';
        const granted2 = await _requestShizukuPermission();
        OSToast(granted2 ? '⚡ Instalación automática activada' : '❌ Permiso denegado', granted2 ? 'success' : 'error');
        _renderShizukuBanner();
        if (granted2) _updateUI();
      };
    }
  }

  async function _updateUI() {
    const section = document.getElementById('device-apps-section');
    const tocLink = document.getElementById('device-apps-toc-link');
    const grid = document.getElementById('device-apps-grid');
    if (!section || !grid) return;

    if (!isNative()) { section.style.display = 'none'; if (tocLink) tocLink.style.display = 'none'; return; }

    const catalog = (window.__osCatalog || []).filter(a => !!a.packageName);
    if (catalog.length === 0) { section.style.display = 'none'; if (tocLink) tocLink.style.display = 'none'; return; }

    let installedMap = {};
    try {
      const pkgs = catalog.map(a => a.packageName);
      const raw = window.CodeHubNative.getInstalledVersions(JSON.stringify(pkgs));
      installedMap = JSON.parse(raw || '{}');
    } catch (e) { console.warn('DeviceApps: fallo detectando instaladas', e); }

    const installedApps = catalog
      .map(a => ({ ...a, installedVersion: installedMap[a.packageName] }))
      .filter(a => a.installedVersion !== null && a.installedVersion !== undefined);

    if (installedApps.length === 0) {
      section.style.display = 'none';
      if (tocLink) tocLink.style.display = 'none';
      return;
    }

    section.style.display = '';
    if (tocLink) tocLink.style.display = '';
    await _renderShizukuBanner();

    // Reusa /api/app-updates comparando contra la versión REAL instalada
    // en el dispositivo (más precisa que la que guarda el catálogo).
    let updates = {};
    try {
      const res = await fetch(`${BACKEND}/api/app-updates`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ apps: installedApps.map(a => ({ appId: a.appId, version: a.installedVersion, source_repo: a.source_repo })) })
      });
      if (res.ok) { (await res.json()).forEach(u => { updates[u.appId] = u; }); }
    } catch (e) { console.warn('DeviceApps: fallo chequeando updates', e); }

    const { granted: shizukuReady } = await _shizukuStatus();

    grid.innerHTML = installedApps.map(app => {
      const u = updates[app.appId];
      const hasUpdate = u && u.hasUpdate;
      const latestVersion = u?.latestVersion || '';
      const dlUrl = u?.downloadUrl || (app.source_repo ? `https://github.com/${app.source_repo}/releases/latest` : '#');
      const isApk = /\.apk(\?|$)/i.test(dlUrl || '');

      return `
      <div class="app-card device-app-card ${hasUpdate ? 'has-update' : ''}" data-app-id="${app.appId}">
        <div class="app-thumb">
          <img src="${app.imagen}" alt="${esc(app.nombre)}" loading="lazy" decoding="async" onerror="this.parentElement.innerHTML='<div class=app-thumb-fallback>📦</div>'">
          <span class="app-badge badge-installed">📲 Instalada</span>
          ${hasUpdate ? '<span class="app-badge badge-update">🆕 Actualiza</span>' : ''}
        </div>
        <div class="app-body">
          <div class="app-name">${esc(app.nombre)}</div>
          ${hasUpdate
            ? `<div class="my-app-version-diff"><span class="my-app-old">v${esc(app.installedVersion) || '?'}</span> → <span class="my-app-new">${esc(latestVersion)}</span></div>`
            : `<div class="my-app-version">v${esc(app.installedVersion) || 'desconocida'} — actualizada</div>`
          }
          <div class="app-actions" id="device-actions-${esc(app.appId)}">
            ${hasUpdate && isApk
              ? `<button class="dl-btn dl-primary device-update-btn" data-haptic="game" data-appid="${esc(app.appId)}" data-url="${esc(dlUrl)}" data-silent="${shizukuReady}">
                   <i class="fas fa-arrow-up"></i> ${shizukuReady ? 'Actualizar automáticamente' : 'Actualizar'}
                 </button>`
              : hasUpdate
                ? `<a class="dl-btn dl-primary" data-haptic="tab" href="${dlUrl}" target="_blank" rel="noopener"><i class="fas fa-arrow-up"></i> Ver actualización</a>`
                : `<span class="dl-btn dl-check"><i class="fas fa-check"></i> Al día</span>`
            }
          </div>
        </div>
      </div>`;
    }).join('');

    grid.querySelectorAll('.device-update-btn').forEach(btn => {
      btn.onclick = async () => {
        const appId = btn.dataset.appid;
        const url = btn.dataset.url;
        const silent = btn.dataset.silent === 'true';
        const app = installedApps.find(a => a.appId === appId);
        btn.disabled = true;
        btn.innerHTML = `<i class="fas fa-spinner fa-spin"></i> ${silent ? 'Instalando...' : 'Descargando...'}`;
        const result = await _installApp(app, url, silent);
        if (result.status === 'installed') {
          OSToast(`✅ ${app.nombre} actualizada`, 'success');
          setTimeout(_updateUI, 1500);
        } else if (result.status === 'prompted') {
          btn.innerHTML = `<i class="fas fa-check"></i> Confirmá la instalación`;
        } else {
          btn.disabled = false;
          btn.innerHTML = `<i class="fas fa-arrow-up"></i> Reintentar`;
          OSToast(`❌ No se pudo actualizar ${app.nombre}: ${result.message || ''}`, 'error');
        }
      };
    });
  }

  return { updateUI: _updateUI, isNative };
})();

document.addEventListener('os:catalog-loaded', () => DeviceApps.updateUI());
pausableInterval(() => { DeviceApps.updateUI(); }, 5 * 60 * 1000);

// ── HÁPTICA EN ELEMENTOS ESTÁTICOS (el resto vive en las plantillas) ──
(function wireStaticHaptics() {
  document.querySelectorAll('.back-link, .logo-link, .os-toc a, .to-top-btn, .we-trigger').forEach(el => {
    if (!el.hasAttribute('data-haptic')) el.setAttribute('data-haptic', el.classList.contains('we-trigger') ? 'game' : 'tab');
  });
})();
