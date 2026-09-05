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
    document.querySelectorAll('.how-to-modal.active').forEach(m => m.classList.remove('active'));
  }
});

function copyEchoExtensionUrl() {
  const extensionUrl = 'https://raw.githubusercontent.com/itsmechinmoy/echo-extensions/refs/heads/main/echo_extensions.json';
  navigator.clipboard.writeText(extensionUrl).then(() => {
    const toast = document.createElement('div');
    toast.style.cssText = 'position:fixed;top:20px;left:50%;transform:translateX(-50%);background:rgba(34,197,94,.95);color:#fff;padding:1rem 1.5rem;border-radius:8px;font-weight:700;z-index:10000;box-shadow:0 4px 12px rgba(0,0,0,.3)';
    toast.textContent = '✅ URL de extensiones copiada';
    document.body.appendChild(toast);
    setTimeout(() => { toast.remove(); }, 2500);
  }).catch(err => {
    console.error('Error al copiar:', err);
    const toast = document.createElement('div');
    toast.style.cssText = 'position:fixed;top:20px;left:50%;transform:translateX(-50%);background:rgba(239,68,68,.95);color:#fff;padding:1rem 1.5rem;border-radius:8px;font-weight:700;z-index:10000;box-shadow:0 4px 12px rgba(0,0,0,.3)';
    toast.textContent = '❌ No se pudo copiar';
    document.body.appendChild(toast);
    setTimeout(() => { toast.remove(); }, 2500);
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
    try {
      const res = await fetch(`${BACKEND}/api/ratings`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ appId, appName, stars })
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
    } catch (e) { console.warn('rating error:', e.message); }
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
  return id;
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
    if (typeof toast === 'function') {
      toast(idx >= 0 ? '📦 App removida de Mis apps' : '❤️ App guardada en Mis apps', 'info', 2000);
    }
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
        if (typeof toast === 'function') {
          toast(granted2 ? '⚡ Instalación automática activada' : '❌ Permiso denegado', granted2 ? 'success' : 'error', 2500);
        }
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
          if (typeof toast === 'function') toast(`✅ ${app.nombre} actualizada`, 'success', 2500);
          setTimeout(_updateUI, 1500);
        } else if (result.status === 'prompted') {
          btn.innerHTML = `<i class="fas fa-check"></i> Confirmá la instalación`;
        } else {
          btn.disabled = false;
          btn.innerHTML = `<i class="fas fa-arrow-up"></i> Reintentar`;
          if (typeof toast === 'function') toast(`❌ No se pudo actualizar ${app.nombre}: ${result.message || ''}`, 'error', 3500);
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
