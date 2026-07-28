/* ═══════════════════════════════════════
   Novedades — Scripts
   CodeHub by Wilson.E
═══════════════════════════════════════ */

// ── OPTIMIZADOR DE IMÁGENES (proxy wsrv.nl) ───────────────────
// Mismo patrón que usa Orion Store: reescribe la URL original de
// la app para que pase por un CDN de resize/compresión antes de
// llegar al navegador. Solo se usa en el catálogo Open Source,
// donde los íconos vienen de fuentes externas (raw de GitHub,
// GitLab, Codeberg, capturas de Play Store, etc.) y suelen venir
// en tamaño original sin optimizar.
function getOptimizedImageUrl(url, width, height) {
  if (!url) return '';
  // Local si NO es una URL http(s): cubre rutas con o sin "/" inicial
  // y data:/blob: URIs. Se normaliza a raíz absoluta para que resuelva
  // igual sin importar desde qué página (ej. /pages/...) se renderice.
  if (!/^https?:\/\//i.test(url)) {
    if (url.startsWith('data:') || url.startsWith('blob:') || url.startsWith('/')) return url;
    return '/' + url.replace(/^\.?\/+/, '');
  }

  let sourceUrl = url;
  // Si es una imagen scrapeada de Google Play, forzamos alta resolución
  // antes de mandarla al proxy (evita íconos borrosos de 100x100).
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
// Misma función que en admin-hub.js — convierte links de nube
// a descarga directa al momento de renderizar las cards.
function convertToDirectLink(url) {
  if (!url || url === '#') return url;
  try {
    const u    = new URL(url);
    const host = u.hostname.replace('www.', '');

    // Google Drive
    if (host === 'drive.google.com') {
      let id = u.searchParams.get('id');
      if (!id) { const m = u.pathname.match(/\/d\/([a-zA-Z0-9_-]+)/); if (m) id = m[1]; }
      if (id) return `https://drive.google.com/uc?export=download&id=${id}&confirm=t`;
      return url;
    }
    // Dropbox
    if (host === 'dropbox.com' || host === 'dl.dropboxusercontent.com') {
      u.searchParams.set('dl', '1'); return u.toString();
    }
    // OneDrive
    if (host === 'onedrive.live.com') {
      u.pathname = u.pathname.replace('/redir', '/download');
      u.searchParams.set('download', '1'); return u.toString();
    }
    // MediaFire
    if (host === 'mediafire.com') {
      u.pathname = u.pathname.replace(/^\/file\//, '/download/'); return u.toString();
    }
    // MEGA, Terabox, Telegram, resto → sin cambios
    return url;
  } catch { return url; }
}

// ── PROGRESO DE SCROLL ───────────────
window.addEventListener('scroll', () => {
  const s = document.documentElement.scrollTop;
  const h = document.documentElement.scrollHeight - window.innerHeight;
  document.getElementById('pbar').style.width = (s / h * 100) + '%';
});

// ── TOAST ────────────────────────────
function toast(m) {
  const t = document.getElementById('toast');
  t.textContent = m;
  t.classList.add('on');
  setTimeout(() => t.classList.remove('on'), 2100);
}

// ── CONTADOR DESCARGAS (localStorage) ──
function countDl() {
  const today = new Date().toDateString();
  const data = JSON.parse(localStorage.getItem('ch_dl') || '{"date":"","count":0}');
  const count = data.date === today ? data.count + 1 : 1;
  localStorage.setItem('ch_dl', JSON.stringify({ date: today, count }));
  document.getElementById('sc-dl').textContent = count;
  toast('⬇ Descarga iniciada');
}

// Cargar contador guardado
(function() {
  const today = new Date().toDateString();
  const data = JSON.parse(localStorage.getItem('ch_dl') || '{"date":"","count":0}');
  if (data.date === today) document.getElementById('sc-dl').textContent = data.count;

  // Fecha de actualización en badge
  const d = new Date();
  document.getElementById('update-badge').textContent = '● ' + d.toLocaleDateString('es-GT', { month: 'short', day: 'numeric' });
})();

// ── FILTROS ──────────────────────────
let activeTag = 'all';
function filterCat(tag, btn) {
  activeTag = tag;
  document.querySelectorAll('.cat-chip').forEach(c => c.classList.remove('on'));
  btn.classList.add('on');
  applyFilter(document.getElementById('search').value);
}

function filterApps() {
  applyFilter(document.getElementById('search').value);
}

function applyFilter(q) {
  const ql = q.toLowerCase();
  let visible = 0;
  document.querySelectorAll('.app-card').forEach(card => {
    const name = card.dataset.name || '';
    const cat  = card.dataset.cat || '';
    const mq = !ql || name.includes(ql);
    const mt = activeTag === 'all' || cat === activeTag;
    const show = mq && mt;
    card.classList.toggle('hidden', !show);
    if (show) visible++;
  });

  // Mensaje sin resultados
  let noR = document.getElementById('no-results');
  if (!visible) {
    if (!noR) {
      noR = document.createElement('div');
      noR.id = 'no-results';
      noR.className = 'no-results';
      noR.innerHTML = '<i class="fas fa-search" style="font-size:2rem;margin-bottom:.8rem;display:block;opacity:.3"></i>No se encontraron apps para "<b>' + q + '</b>"';
      document.getElementById('app-grid').appendChild(noR);
    }
  } else if (noR) noR.remove();
}

// ═══════════════════════════════════════════════════════════
//  RATING + SOLICITUDES — MongoDB via Railway
// ═══════════════════════════════════════════════════════════
const BACKEND = 'https://codehub-98s6.onrender.com';
let allRatings = {};

// Cargar ratings al iniciar
async function loadRatings() {
  try {
    const res = await fetch(`${BACKEND}/api/ratings`);
    const d   = await res.json();
    allRatings = d.ratings || {};
    renderRatings();
  } catch(e) { console.warn('Ratings no disponibles'); }
}

function renderRatings() {
  document.querySelectorAll('.app-card').forEach(card => {
    const id  = card.dataset.id;
    const rat = card.querySelector('.app-rating');
    if (!rat || !id) return;
    const info = allRatings[id];
    if (info && info.count > 0) {
      rat.querySelector('.rating-info').textContent = `${info.avg} ★ (${info.count} votos)`;
      highlightStars(rat, Math.round(info.avg));
    }
    // Hover stars
    rat.querySelectorAll('.star').forEach(star => {
      star.addEventListener('mouseenter', () => {
        if (rat.dataset.voted) return;
        const v = parseInt(star.dataset.v);
        rat.querySelectorAll('.star').forEach((s,i) => s.classList.toggle('active', i < v));
      });
      star.addEventListener('mouseleave', () => {
        if (rat.dataset.voted) return;
        rat.querySelectorAll('.star').forEach(s => s.classList.remove('active'));
        const info = allRatings[card.dataset.id];
        if (info) highlightStars(rat, Math.round(info.avg));
      });
      star.addEventListener('click', () => voteApp(card, star));
    });
  });
}

function highlightStars(rat, count) {
  rat.querySelectorAll('.star').forEach((s, i) => {
    s.classList.toggle('voted', i < count);
    s.classList.remove('active');
  });
}

async function voteApp(card, star) {
  const rat   = card.querySelector('.app-rating');
  if (rat.dataset.voted) { toast('Ya votaste esta app'); return; }
  const stars = parseInt(star.dataset.v);
  const appId = card.dataset.id;
  const appName = card.querySelector('.app-name')?.textContent || appId;
  try {
    const res = await fetch(`${BACKEND}/api/ratings`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ appId, appName, stars }),
    });
    const d = await res.json();
    if (d.ok || d.avg !== undefined) {
      allRatings[appId] = { avg: d.avg, count: d.count };
      rat.dataset.voted = '1';
      highlightStars(rat, stars);
      rat.querySelector('.rating-info').textContent = `${d.avg} ★ (${d.count} votos)`;
      toast(`⭐ Votaste ${stars} estrellas para ${appName}`);
    } else {
      toast(d.error || 'No se pudo votar');
    }
  } catch(e) { toast('Error de conexión'); }
}

// ── SOLICITUDES ──────────────────────────────────────────────
function openReqModal() {
  document.getElementById('req-modal').style.display = 'flex';
  loadRequests();
}
function closeReqModal() {
  document.getElementById('req-modal').style.display = 'none';
}
async function loadRequests() {
  const list = document.getElementById('req-list');
  list.innerHTML = '<div class="req-loading">Cargando...</div>';
  try {
    const res = await fetch(`${BACKEND}/api/requests`);
    const d   = await res.json();
    if (!d.requests?.length) {
      list.innerHTML = '<div class="req-loading">No hay solicitudes aún. ¡Sé el primero!</div>';
      return;
    }
    list.innerHTML = d.requests.map(r => `
      <div class="req-item">
        <span class="req-item-name">${r.appName}</span>
        <span class="req-item-votes">+${r.votes} votos</span>
      </div>`).join('');
  } catch(e) {
    list.innerHTML = '<div class="req-loading">No disponible ahora</div>';
  }
}
async function submitRequest() {
  const name   = document.getElementById('req-name').value.trim();
  const reason = document.getElementById('req-reason').value.trim();
  if (!name) { toast('Escribe el nombre de la app'); return; }
  const btn = document.querySelector('.req-submit-btn');
  btn.disabled = true; btn.textContent = 'Enviando...';
  try {
    const res = await fetch(`${BACKEND}/api/requests`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        appName: name,
        reason,
        turnstileToken: document.querySelector('.req-form input[name="cf-turnstile-response"]')?.value
            || document.querySelector('input[name="cf-turnstile-response"]')?.value || '',
    }),
    });
    const d = await res.json();
    if (d.ok) {
      toast(d.message || 'Solicitud enviada ✅');
      document.getElementById('req-name').value = '';
      document.getElementById('req-reason').value = '';
      loadRequests();
    } else {
      toast(d.error || 'Error al enviar');
    }
  } catch(e) { toast('Error de conexión'); }
  btn.disabled = false;
  btn.innerHTML = '<i class="fas fa-paper-plane"></i> Enviar solicitud';
}

// Iniciar al cargar
window.addEventListener('load', loadRatings);


// ═══════════════════════════════════════════════════════════
//  BADGES VERIFICADO + VERSION + CHANGELOG
// ═══════════════════════════════════════════════════════════
function initAppBadges() {
  document.querySelectorAll('.app-card').forEach(card => {
    const verified  = card.dataset.verified === 'true';
    const version   = card.dataset.version || '';
    const changelog = card.dataset.changelog || '';

    // Badge verificado
    const vBadge = card.querySelector('.app-verified-badge');
    if (vBadge && verified) vBadge.style.display = 'block';

    // Version tag
    const vTag = card.querySelector('.app-version-tag');
    if (vTag && version) vTag.textContent = 'v' + version;

    // Changelog text
    const clText = card.querySelector('.app-changelog-text');
    if (clText && changelog) {
      clText.innerHTML = changelog.split(' · ').map(c => `<div>→ ${c}</div>`).join('');
    }
  });
}

function toggleChangelog(btn) {
  const wrap = btn.previousElementSibling;
  const isOpen = wrap.style.display !== 'none';
  wrap.style.display = isOpen ? 'none' : 'block';
  btn.innerHTML = isOpen
    ? '<i class="fas fa-clock-rotate-left"></i> Ver cambios'
    : '<i class="fas fa-chevron-up"></i> Ocultar';
}

window.addEventListener('load', () => {
  initAppBadges();
});

// ═══════════════════════════════════════════════════════════
//  TURNSTILE EN SOLICITAR APP
// ═══════════════════════════════════════════════════════════
// El widget se renderiza dinámicamente al abrir el modal
function openReqModal() {
  document.getElementById('req-modal').style.display = 'flex';
  loadRequests();
  // Renderizar Turnstile si existe el contenedor
  if (typeof turnstile !== 'undefined' && document.getElementById('turnstile-req')) {
    turnstile.render('#turnstile-req', {
      sitekey: '0x4AAAAAAClKd5T1R81GltW_',
      theme: 'dark',
      language: 'es',
    });
  }
}

async function submitRequest() {
  const name   = document.getElementById('req-name').value.trim();
  const reason = document.getElementById('req-reason').value.trim();
  if (!name) { toast('Escribe el nombre de la app'); return; }

  // Verificar Turnstile
  const tsInput = document.querySelector('#turnstile-req iframe')?.closest('[name="cf-turnstile-response"]')
               || document.querySelector('input[name="cf-turnstile-response"]');
  // Continuar sin bloquear si turnstile no cargó (degradación elegante)

  const btn = document.querySelector('.req-submit-btn');
  btn.disabled = true; btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Enviando...';
  try {
    const res = await fetch(`${BACKEND}/api/requests`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        appName: name,
        reason,
        turnstileToken: document.querySelector('.req-form input[name="cf-turnstile-response"]')?.value
            || document.querySelector('input[name="cf-turnstile-response"]')?.value || '',
    }),
    });
    const d = await res.json();
    if (d.ok) {
      toast(d.message || 'Solicitud enviada ✅');
      document.getElementById('req-name').value = '';
      document.getElementById('req-reason').value = '';
      loadRequests();
    } else { toast(d.error || 'Error al enviar'); }
  } catch(e) { toast('Error de conexión'); }
  btn.disabled = false;
  btn.innerHTML = '<i class="fas fa-paper-plane"></i> Enviar solicitud';
}


// ── CARGA DINÁMICA DE APPS DESDE BACKEND ─────────────────────
// Fuente única: /api/apps. Se separa en dos catálogos por la
// presencia (o no) de `source_repo`, sin depender de una página
// aparte — ver switchCatalog() / renderCatalog() más abajo.
const APP_META = {"app-1": {"img": "/img/Spotify.png", "desc": "Música sin anuncios, calidad máxima, descargas y saltos ilimitados.", "catTag": "Música", "emoji": "🎵"}, "app-2": {"img": "/img/SpoLite.png", "desc": "Versión ligera de Spotify con funciones premium activadas.", "catTag": "Música", "emoji": "🎵"}, "app-3": {"img": "/img/YouTube.jpeg", "desc": "YouTube sin anuncios, SponsorBlock integrado y gestor de descargas.", "catTag": "Video", "emoji": "📺"}, "app-4": {"img": "/img/YTMusic.png", "desc": "YouTube Music con reproducción en segundo plano sin restricciones.", "catTag": "Música", "emoji": "🎵"}, "app-5": {"img": "/img/TikTok.svg", "desc": "TikTok sin anuncios, sin marca de agua en descargas y región desbloqueada.", "catTag": "Video", "emoji": "📺"}, "app-6": {"img": "/img/Netflix.png", "desc": "Cliente alternativo de Netflix con calidad 4K desbloqueada.", "catTag": "Video", "emoji": "📺"}, "app-7": {"img": "/img/Terabox.png", "desc": "Almacenamiento en la nube premium con transferencias más rápidas.", "catTag": "Utilidad", "emoji": "🛠️"}, "app-8": {"img": "/img/Player.jpg", "desc": "Reproductor de video con soporte para todos los formatos y AV1.", "catTag": "Video", "emoji": "📺"}, "app-9": {"img": "/img/Picsart.jpg", "desc": "Editor de fotos con todas las herramientas IA desbloqueadas.", "catTag": "Foto", "emoji": "📸"}, "app-10": {"img": "/img/Remini.png", "desc": "Mejora la calidad de fotos antiguas o borrosas con IA avanzada.", "catTag": "Foto", "emoji": "📸"}, "app-11": {"img": "/img/Eraser.jpg", "desc": "Elimina objetos, personas o fondos de tus fotos con un toque.", "catTag": "Foto", "emoji": "📸"}, "app-12": {"img": "/img/CamScanner.png", "desc": "Escáner de documentos con OCR preciso y múltiples formatos de exportación.", "catTag": "Utilidad", "emoji": "🛠️"}, "app-13": {"img": "/img/dnspro.png", "desc": "Bloquea anuncios y rastreadores a nivel DNS en todo el dispositivo.", "catTag": "Seguridad", "emoji": "🔒"}};
const CAT_MAP  = {"Música": "musica", "Video": "video", "Foto": "foto", "Utilidad": "util", "Seguridad": "util"};
const PREMIUM_CHIPS = [
  { id: 'musica', label: '🎵 Música' },
  { id: 'video',  label: '📺 Video' },
  { id: 'foto',   label: '📸 Foto' },
  { id: 'util',   label: '🛠️ Utilidad' },
];
const OS_CAT_EMOJI = {
  'Root y Sistema':   '🛠️',
  'Música':            '🎵',
  'Video':             '📺',
  'VPN y Privacidad':  '🔒',
  'Productividad':     '✅',
  'Lectura':           '📖',
};

function buildAppCard(app) {
  const meta      = APP_META[app.appId] || {};
  const img       = meta.img     || '/img/default.png';
  const desc      = meta.desc    || app.descripcion || '';
  const catTag    = meta.catTag  || app.categoria   || 'App';
  const dataCat   = CAT_MAP[catTag] || 'util';
  const verified  = app.verified ? 'true' : 'false';
  const badge     = app.tag || '🆕';
  const badgeClass = badge.includes('Actualiz') ? 'badge-updated' : 'badge-new';
  const changelog = app.changelog || '';
  const version   = app.version  || '';
  const enlace    = convertToDirectLink(app.enlace   || '#');
  const plugin    = convertToDirectLink(app.plugin_enlace || null);
  const tutorial  = app.tutorial_url  || null;
  const hasLink   = enlace && enlace !== '#';

  // Botón principal APK
  const mainBtn = hasLink
    ? `<a class="dl-btn dl-primary" href="${enlace}" onclick="countDl()" ${enlace.startsWith('http') ? 'target="_blank" rel="noopener"' : ''}>
         <i class="fas fa-download"></i> Descargar APK
       </a>`
    : `<span class="dl-btn" style="opacity:.45;cursor:not-allowed;pointer-events:none">
         <i class="fas fa-clock"></i> Próximamente
       </span>`;

  // Botón plugin — solo si existe, al lado del APK
  const pluginBtn = plugin
    ? `<a class="dl-btn dl-plugin" href="${plugin}" onclick="countDl()" target="_blank" rel="noopener" title="Plugin requerido">
         <i class="fas fa-puzzle-piece"></i> Plugin
       </a>`
    : '';

  // Botón tutorial — solo si existe, debajo de los botones
  const tutorialBtn = tutorial
    ? `<a class="app-tutorial" href="${tutorial}" target="_blank" rel="noopener">
         <i class="fab fa-youtube"></i> Ver tutorial de instalación
       </a>`
    : '';

  return `
  <div class="app-card" data-id="${app.appId}" data-version="${version}" data-verified="${verified}" data-changelog="${changelog}" data-cat="${dataCat}" data-name="${app.nombre?.toLowerCase()} ${catTag.toLowerCase()}">
    <div class="app-thumb">
      <img src="${img}" alt="${app.nombre}" onerror="this.parentElement.innerHTML='<div class=app-thumb-fallback>${meta.emoji||'📱'}</div>'">
      <span class="app-badge ${badgeClass}">${badge}</span>
      <span class="app-verified-badge" style="display:${app.verified?'flex':'none'}">✅ Verificado</span>
      <span class="app-version-tag">${version}</span>
    </div>
    <div class="app-body">
      <div class="app-cat-tag">${catTag}</div>
      <div class="app-name">${app.nombre}</div>
      <div class="app-desc">${desc}</div>
      <div class="app-actions">${mainBtn}${pluginBtn}</div>
      ${tutorialBtn}
      <div class="app-changelog-wrap" style="display:none">
        <div class="app-changelog-text">${changelog}</div>
      </div>
      <button class="app-changelog-btn" onclick="toggleChangelog(this)">
        <i class="fas fa-clock-rotate-left"></i> Ver cambios
      </button>
      <div class="app-rating" data-init="false">
        <div class="stars-wrap">
          <span class="star" data-v="1">★</span>
          <span class="star" data-v="2">★</span>
          <span class="star" data-v="3">★</span>
          <span class="star" data-v="4">★</span>
          <span class="star" data-v="5">★</span>
        </div>
        <span class="rating-info">— sin votos</span>
      </div>
    </div>
  </div>`;
}

function buildOSCard(app) {
  // Ícono optimizado vía proxy (resize + WebP + caché de 31 días).
  // 192px cubre bien la retina para el thumb que pinta el CSS.
  const img     = getOptimizedImageUrl(app.imagen || '', 192, 192);
  const version = app.version ? `v${app.version.replace(/^v/i, '')}` : 'Sin versión aún';
  const desc    = app.descripcion || '';
  const enlace  = convertToDirectLink(app.enlace && app.enlace !== '#' ? app.enlace : null);
  const repoUrl = app.source_repo ? `https://github.com/${app.source_repo}` : null;
  const emoji   = OS_CAT_EMOJI[app.categoria] || '📦';
  const badge   = app.tag || '🆕';
  const badgeClass = badge.includes('Actualiz') ? 'badge-upd' : 'badge-new';

  const dlBtn = enlace
    ? `<a class="dl-btn dl-primary" href="${enlace}" onclick="countDl()" target="_blank" rel="noopener">
         <i class="fas fa-download"></i> Descargar APK
       </a>`
    : `<a class="dl-btn dl-ghost" href="${repoUrl || '#'}" target="_blank" rel="noopener">
         <i class="fas fa-clock"></i> Ver en GitHub
       </a>`;

  const repoBtn = repoUrl
    ? `<a class="dl-btn dl-plugin" href="${repoUrl}" target="_blank" rel="noopener" title="Código fuente">
         <i class="fab fa-github"></i> Repo
       </a>`
    : '';

  return `
  <div class="app-card" data-id="${app.appId}" data-cat="${app.categoria || ''}" data-name="${(app.nombre || '').toLowerCase()} ${(app.categoria || '').toLowerCase()}">
    <div class="app-thumb">
      <img src="${img}" alt="${app.nombre}" onerror="this.parentElement.innerHTML='<div class=app-thumb-fallback>${emoji}</div>'">
      <span class="app-badge ${badgeClass}">${badge}</span>
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

// ── ESTADO DE CATÁLOGO (Premium ⇄ Open Source) ────────────────
let CURRENT_CATALOG = 'premium';
let PREMIUM_APPS = [];
let OS_APPS = [];

function buildChips(list) {
  const wrap = document.getElementById('cat-chips');
  wrap.innerHTML = '<button class="cat-chip on" onclick="filterCat(\'all\',this)">Todas</button>';
  list.forEach(c => {
    const btn = document.createElement('button');
    btn.className = 'cat-chip';
    btn.textContent = c.label;
    btn.onclick = () => filterCat(c.id, btn);
    wrap.appendChild(btn);
  });
}

function renderCatalog() {
  activeTag = 'all';
  const grid = document.getElementById('app-grid');
  const isOS = CURRENT_CATALOG === 'opensource';
  const apps = isOS ? OS_APPS : PREMIUM_APPS;

  document.getElementById('hero-tag').innerHTML = isOS
    ? '<span class="hero-dot"></span> 100% Open Source · Gratis · Solo Android'
    : `<span class="hero-dot"></span> ${PREMIUM_APPS.length} apps · Gratis · Solo Android`;
  document.getElementById('hero-title').innerHTML = isOS ? 'Catálogo<br>Open Source' : 'Apps con todo<br>desbloqueado';
  document.getElementById('hero-sub').textContent = isOS
    ? 'Apps con código abierto, verificadas contra su repositorio oficial. Descarga directa desde GitHub Releases, sin modificaciones ni anuncios de por medio.'
    : 'Curadas y probadas. Sin registros, sin pagos, solo descarga e instala.';

  document.getElementById('sc-apps').textContent = apps.length;
  document.getElementById('sc-apps-label').textContent = isOS ? 'Apps en el catálogo' : 'Apps disponibles';

  if (isOS) {
    const cats = [...new Set(apps.map(a => a.categoria).filter(Boolean))];
    document.getElementById('sc-new').textContent = cats.length;
    document.getElementById('sc-new-label').textContent = 'Categorías';
    buildChips(cats.map(c => ({ id: c, label: `${OS_CAT_EMOJI[c] || '📦'} ${c}` })));
  } else {
    document.getElementById('sc-new').textContent = apps.filter(a => (a.tag || '').includes('🆕')).length;
    document.getElementById('sc-new-label').textContent = 'Nuevas este mes';
    buildChips(PREMIUM_CHIPS);
  }

  const search = document.getElementById('search');
  search.value = '';
  search.placeholder = isOS ? 'Buscar app open source…' : 'Buscar app…';

  if (!apps.length) {
    grid.innerHTML = `<div style="grid-column:1/-1;text-align:center;padding:3rem;color:var(--muted)">${isOS ? 'Aún no hay apps open source publicadas en el catálogo.' : 'No hay apps disponibles.'}</div>`;
    return;
  }

  grid.innerHTML = apps.map(isOS ? buildOSCard : buildAppCard).join('');

  if (!isOS && typeof loadRatings === 'function') loadRatings();
  applyFilter('');
}

function switchCatalog(cat) {
  if (cat === CURRENT_CATALOG) return;
  CURRENT_CATALOG = cat;
  document.getElementById('tab-premium').classList.toggle('on', cat === 'premium');
  document.getElementById('tab-opensource').classList.toggle('on', cat === 'opensource');
  document.getElementById('tab-indicator').classList.toggle('os', cat === 'opensource');
  history.replaceState(null, '', cat === 'opensource' ? '#open-source' : location.pathname + location.search);
  renderCatalog();
}

async function loadAppsFromBackend() {
  const grid = document.getElementById('app-grid');
  try {
    const res  = await fetch(`${BACKEND}/api/apps`);
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const apps = await res.json();

    // Una sola fuente de datos, separada por catálogo según
    // tenga (open source) o no (premium) un `source_repo` vinculado.
    PREMIUM_APPS = apps.filter(a => !a.source_repo);
    OS_APPS      = apps.filter(a => !!a.source_repo);

    document.getElementById('count-premium').textContent = PREMIUM_APPS.length;
    document.getElementById('count-opensource').textContent = OS_APPS.length;

    if (location.hash === '#open-source') {
      CURRENT_CATALOG = 'opensource';
      document.getElementById('tab-premium').classList.remove('on');
      document.getElementById('tab-opensource').classList.add('on');
      document.getElementById('tab-indicator').classList.add('os');
    }

    renderCatalog();
    console.log(`✅ ${PREMIUM_APPS.length} apps premium + ${OS_APPS.length} open source cargadas desde backend`);
  } catch (e) {
    console.error('Error cargando apps:', e);
    grid.innerHTML = '<div style="grid-column:1/-1;text-align:center;padding:3rem;color:var(--muted)"><i class="fas fa-exclamation-triangle" style="font-size:1.5rem;margin-bottom:.8rem;display:block"></i>Error cargando apps. Recarga la página.</div>';
  }
}

// Cargar apps al iniciar
loadAppsFromBackend();