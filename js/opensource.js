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

function buildOSCard(app) {
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

  const echoRaw = app.appId === 'os-echo-nightly' ? `
    <div class="os-echo-raw">
      <span>Extensiones</span>
      <button class="os-echo-copy-btn" onclick="copyEchoExtensionUrl()">Copiar extension de pluhings</button>
    </div>` : '';

  const advancedApps = ['os-magisk', 'os-kernelsu', 'os-lsposed', 'os-app-manager', 'os-echo-nightly', 'os-shizuku'];
  const howToBtn = advancedApps.includes(app.appId) ? `
    <button class="how-to-btn" onclick="openHowToDialog('${app.appId}')">
      <i class="fas fa-book"></i> ¿Cómo usar?
    </button>` : '';

  const dlBtn = dlUrl
    ? `<a class="dl-btn dl-primary" href="${dlUrl}" onclick="countDl()" target="_blank" rel="noopener"><i class="fas fa-download"></i> Descargar</a>`
    : `<a class="dl-btn dl-primary" href="${repoUrl || '#'}${repoUrl ? '/releases' : ''}" target="_blank" rel="noopener"><i class="fas fa-download"></i> Descargar</a>`;

  return `
  <div class="app-card" data-app-id="${app.appId}" data-cat="${app.categoria || ''}" data-name="${(app.nombre || '').toLowerCase()} ${(app.categoria || '').toLowerCase()}" data-repo="${app.source_repo || ''}">
    <div class="app-thumb">
      <img src="${img}" alt="${app.nombre}" loading="lazy" onerror="this.parentElement.innerHTML='<div class=app-thumb-fallback>${emoji}</div>'">
      ${badge ? `<span class="app-badge badge-upd">${badge}</span>` : ''}
      <span class="app-verified-badge" style="display:flex">✅ Open Source</span>
      ${version ? `<span class="app-version-tag">${version}</span>` : ''}
      <button class="os-fav-btn ${isFav ? 'active' : ''}" onclick="MyApps.toggle('${app.appId}')" title="${isFav ? 'Quitar de Mis apps' : 'Guardar en Mis apps'}" aria-label="${isFav ? 'Quitar de Mis apps' : 'Guardar en Mis apps'}">
        <i class="fas ${isFav ? 'fa-heart' : 'fa-heart'}"></i>
      </button>
    </div>
    <div class="app-body">
      <div class="app-cat-tag">${emoji} ${app.categoria || ''}</div>
      <div class="app-name">${app.nombre}</div>
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
    const res = await fetch(`${BACKEND}/api/apps`);
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    // El backend devuelve { apps: [...], total }; se acepta también
    // un array plano por compatibilidad.
    const apps = Array.isArray(data) ? data : (data.apps || []);

    const osApps = apps.filter(a => !!a.source_repo);

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
        ? list.map(buildOSCard).join('')
        : `<div style="grid-column:1/-1;text-align:center;padding:1.2rem;color:var(--muted,#8a8a9a);font-size:.82rem">Aún no hay apps en esta categoría.</div>`;
    });

    // Categorías presentes en los datos pero sin sección aún.
    Object.keys(byCategory).forEach(cat => {
      if (OS_CATEGORIES.some(c => c.categoria === cat)) return;
      const id = ensureCategorySection(cat);
      const grid = document.getElementById(`grid-${id}`);
      if (grid) grid.innerHTML = byCategory[cat].map(buildOSCard).join('');
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
        }
      } catch {}
    };
    _ws.onclose = () => { _wsTimer = setTimeout(connectOSWebSocket, 10000); };
    _ws.onerror = () => { try { _ws.close(); } catch {} };
  } catch {}
}

loadOpenSourceCatalog();
connectOSWebSocket();
setInterval(() => {
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
          <img src="${app.imagen}" alt="${app.nombre}" loading="lazy" onerror="this.parentElement.innerHTML='<div class=app-thumb-fallback>📦</div>'">
          ${hasUpdate ? '<span class="app-badge badge-update">🆕 Actualiza</span>' : ''}
        </div>
        <div class="app-body">
          <div class="app-name">${app.nombre}</div>
          ${hasUpdate
            ? `<div class="my-app-version-diff"><span class="my-app-old">v${app.version || '?'}</span> → <span class="my-app-new">${latestVersion}</span></div>`
            : `<div class="my-app-version">v${app.version || 'desconocida'}</div>`
          }
          <div class="app-actions">
            ${hasUpdate
              ? `<a class="dl-btn dl-primary my-app-update-btn" href="${dlUrl}" target="_blank" rel="noopener"><i class="fas fa-arrow-up"></i> Actualizar ahora</a>`
              : `<a class="dl-btn dl-primary" href="${dlUrl}" target="_blank" rel="noopener"><i class="fas fa-check"></i> Última versión</a>`
            }
            <button class="os-fav-btn active" onclick="MyApps.toggle('${app.appId}')" title="Quitar de Mis apps">
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

// ── Check periódico de actualizaciones (cada 5 min) ──
setInterval(() => { MyApps.updateUI(); }, 5 * 60 * 1000);
