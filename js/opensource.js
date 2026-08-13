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

// ── DIÁLOGO DE INSTRUCCIONES ────────────────────────────────
function showHowToUseDialog() {
  const modal = document.getElementById('shizuku-how-to-use-modal');
  if (modal) modal.classList.add('active');
}

function closeHowToUseDialog() {
  const modal = document.getElementById('shizuku-how-to-use-modal');
  if (modal) modal.classList.remove('active');
}

function copyEchoExtensionUrl() {
  const extensionUrl = 'https://raw.githubusercontent.com/itsmechinmoy/echo-extensions/refs/heads/main/echo_extensions.json';
  navigator.clipboard.writeText(extensionUrl).then(() => {
    const feedback = document.getElementById('echo-copy-feedback');
    if (feedback) {
      feedback.textContent = '✅ URL copiada al portapapeles';
      feedback.style.display = 'block';
      setTimeout(() => { feedback.style.display = 'none'; }, 2500);
    }
  }).catch(err => {
    console.error('Error al copiar:', err);
    const feedback = document.getElementById('echo-copy-feedback');
    if (feedback) {
      feedback.textContent = '❌ No se pudo copiar';
      feedback.style.display = 'block';
      setTimeout(() => { feedback.style.display = 'none'; }, 2500);
    }
  });
}

function buildOSCard(app) {
  const img     = getOptimizedImageUrl(app.imagen || '', 192, 192);
  const version = app.version ? `v${app.version.replace(/^v/i, '')}` : null;
  const desc    = app.descripcion || '';
  const enlace  = convertToDirectLink(app.enlace && app.enlace !== '#' ? app.enlace : null);
  // El botón de descarga pasa por /api/dl/:appId (no expone el link
  // crudo de GitHub Releases/mirror y queda trackeado server-side).
  const dlUrl   = enlace ? `${BACKEND}/api/dl/${encodeURIComponent(app.appId)}` : null;
  const repoUrl = app.source_repo ? `https://github.com/${app.source_repo}` : null;
  const emoji   = CAT_EMOJI[app.categoria] || '📦';
  const updated = timeAgo(app.updatedAt);
  const badge   = (app.tag || '').includes('Actualiz') ? app.tag : null;

  const shizukuMenu = app.appId === 'os-shizuku' ? `
    <div class="os-mini-menu" aria-label="Mini menú de Shizuku">
      <div class="os-mini-summary">Mini menú</div>
      <div class="os-mini-panel">
        <button class="os-mini-btn" onclick="showHowToUseDialog()">
          <strong>¿Cómo usar?</strong>
          <span>Abre el diálogo de instrucciones</span>
        </button>
        <div class="os-mini-item">
          <strong>¿Para qué sirve?</strong>
          <span>Permite a apps de Android acceder a APIs de sistema sin root. Es la base para muchas herramientas de control, optimización y limpieza avanzada.</span>
        </div>
      </div>
    </div>` : '';

  const echoRaw = app.appId === 'os-echo-nightly' ? `
    <div class="os-echo-raw">
      <span>Extensiones</span>
      <button class="os-echo-copy-btn" onclick="copyEchoExtensionUrl()">Copiar extension de pluhings</button>
    </div>` : '';

  // Solo botón de descarga: el enlace real (GitHub Releases/mirror) nunca
  // se expone directo en el DOM, siempre pasa por /api/dl/:appId. El botón
  // "Código fuente" que enlazaba crudo a `repoUrl` fue removido a propósito;
  // `repoUrl` se conserva únicamente como fallback interno de descarga.
  const dlBtn = dlUrl
    ? `<a class="dl-btn dl-primary" href="${dlUrl}" onclick="countDl()" target="_blank" rel="noopener"><i class="fas fa-download"></i> Descargar</a>`
    : `<a class="dl-btn dl-primary" href="${repoUrl || '#'}${repoUrl ? '/releases' : ''}" target="_blank" rel="noopener"><i class="fas fa-download"></i> Descargar</a>`;

  return `
  <div class="app-card" data-cat="${app.categoria || ''}" data-name="${(app.nombre || '').toLowerCase()} ${(app.categoria || '').toLowerCase()}">
    <div class="app-thumb">
      <img src="${img}" alt="${app.nombre}" loading="lazy" onerror="this.parentElement.innerHTML='<div class=app-thumb-fallback>${emoji}</div>'">
      ${badge ? `<span class="app-badge badge-upd">${badge}</span>` : ''}
      <span class="app-verified-badge" style="display:flex">✅ Open Source</span>
      ${version ? `<span class="app-version-tag">${version}</span>` : ''}
    </div>
    <div class="app-body">
      <div class="app-cat-tag">${emoji} ${app.categoria || ''}</div>
      <div class="app-name">${app.nombre}</div>
      <div class="app-desc">${desc}</div>
      ${echoRaw}
      ${shizukuMenu}
      <div class="app-actions">${dlBtn}</div>
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
  // Respaldo: si el WS no está disponible (firewalls, proxies), refrescar
  // el contador cada 5 min vía el endpoint cacheado (sin recargar tarjetas
  // si no cambió la cantidad).
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
