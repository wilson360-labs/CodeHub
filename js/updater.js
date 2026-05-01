// ============================================================
// CODEHUB — SISTEMA DE ACTUALIZACIÓN v3.0
// Detección de cambios de links por app-id
// Soporta: Dropbox · Google Drive · Mediafire · Directo
// ------------------------------------------------------------
// Para actualizar una app: edita apps_data.json y sube a GitHub
// El sistema detecta cambios automáticamente al cargar la página
// ============================================================

const UPDATER_CONFIG = {
  // URL del apps_data.json en tu repo GitHub (raw)
  dataURL: 'https://raw.githubusercontent.com/wilson360-labs/CodeHub/main/apps_data.json',

  // Fallback: carga local si el remoto falla
  localDataURL: './apps_data.json',

  // Re-chequeo en ms (0 = solo al cargar la página)
  checkInterval: 0,

  // Mostrar badge "🔗" en la card cuando cambia el link
  showLinkBadge: true,

  // Animación al detectar cambios
  animateChanges: true,
};

// ─── MAPA DE IDs ─────────────────────────────────────────────
// Relaciona data-id del HTML con el nombre en apps_data.json
// Agrega nuevas apps aquí cuando las añadas al HTML
const APP_ID_MAP = {
  'app-1':  'Spotify Premium',
  'app-2':  'Spotify Lite Premium',
  'app-3':  'YouTube ReVanced',
  'app-4':  'YouTube Music ReVanced',
  'app-5':  'TikTok Premium',
  'app-6':  'Flicks Remix',
  'app-7':  'TeraBox Premium',
  'app-8':  'MX Player Pro',
  'app-9':  'PicsArt Premium',
  'app-10': 'Remini Pro',
  'app-11': 'Magic Eraser Mod',
  'app-12': 'CamScanner Mod',
  'app-13': 'DNS AdGuard Pro',
};

// ─── ESTADO ───────────────────────────────────────────────────
const CACHE_KEY = 'ch_apps_cache_v3';

// ─── DETECCIÓN DE PROVEEDOR ───────────────────────────────────

function detectProvider(url) {
  if (!url || url === '#') return 'direct';
  if (/dropbox\.com|dl\.dropbox\.com/i.test(url))        return 'dropbox';
  if (/drive\.google\.com|docs\.google\.com/i.test(url)) return 'gdrive';
  if (/mediafire\.com/i.test(url))                        return 'mediafire';
  return 'direct';
}

function getProviderLabel(url) {
  const labels = { dropbox: '📦 Dropbox', gdrive: '☁️ Drive', mediafire: '🔥 Mediafire', direct: '🔗 Directo' };
  return labels[detectProvider(url)] || '🔗';
}

// ─── NORMALIZACIÓN DE LINKS ───────────────────────────────────

function normalizeDropbox(url) {
  if (!url) return url;
  // Quitar dl=0 y agregar dl=1 para descarga directa
  let clean = url.replace(/[?&]dl=0/, '').replace(/\?$/, '').replace(/&$/, '');
  return clean + (clean.includes('?') ? '&dl=1' : '?dl=1');
}

function normalizeGDrive(url) {
  if (!url) return url;
  // /file/d/ID/view → uc?export=download&id=ID
  const m = url.match(/\/file\/d\/([^/?#]+)/);
  if (m) return 'https://drive.google.com/uc?export=download&id=' + m[1];
  return url; // ya está en formato correcto
}

function normalizeLink(url) {
  if (!url || url === '#') return url;
  const p = detectProvider(url);
  if (p === 'dropbox') return normalizeDropbox(url);
  if (p === 'gdrive')  return normalizeGDrive(url);
  return url;
}

// ─── CACHE ────────────────────────────────────────────────────

function loadCache() {
  try { return JSON.parse(localStorage.getItem(CACHE_KEY) || '{}'); }
  catch { return {}; }
}

function saveCache(cache) {
  try { localStorage.setItem(CACHE_KEY, JSON.stringify(cache)); } catch {}
}

// ─── CARGA DE DATOS ───────────────────────────────────────────

async function loadAppsData() {
  // Intentar remoto (GitHub raw)
  try {
    const res = await fetch(UPDATER_CONFIG.dataURL + '?t=' + Date.now());
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const text = await res.text();
    const clean = text.replace(/\/\/[^\n]*/g, '').replace(/,(\s*[}\]])/g, '$1');
    return JSON.parse(clean);
  } catch (e) {
    console.warn('[Updater] Remoto falló, usando local:', e.message);
  }
  // Fallback local
  try {
    const res = await fetch(UPDATER_CONFIG.localDataURL + '?t=' + Date.now());
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const text = await res.text();
    const clean = text.replace(/\/\/[^\n]*/g, '').replace(/,(\s*[}\]])/g, '$1');
    return JSON.parse(clean);
  } catch (e) {
    console.error('[Updater] apps_data.json no disponible:', e.message);
    return null;
  }
}

// ─── DETECTAR CAMBIOS ─────────────────────────────────────────

function detectChanges(apps, cache) {
  const changes = [];
  apps.forEach(app => {
    const htmlId = Object.keys(APP_ID_MAP).find(
      id => APP_ID_MAP[id].toLowerCase() === app.nombre.toLowerCase()
    );
    if (!htmlId) return;

    const prev   = cache[htmlId] || {};
    const eNorm  = normalizeLink(app.enlace);
    const pNorm  = normalizeLink(app.plugin_enlace);

    if (app.enlace && app.enlace !== '#' && prev.enlace && prev.enlace !== eNorm) {
      changes.push({ htmlId, nombre: app.nombre, campo: 'APK principal',
        anterior: prev.enlace, nuevo: eNorm, provider: detectProvider(app.enlace) });
    }
    if (app.plugin_enlace && prev.plugin_enlace && prev.plugin_enlace !== pNorm) {
      changes.push({ htmlId, nombre: app.nombre, campo: 'Plugin',
        anterior: prev.plugin_enlace, nuevo: pNorm, provider: detectProvider(app.plugin_enlace) });
    }
  });
  return changes;
}

// ─── APLICAR DATOS AL HTML ────────────────────────────────────

function applyDataToCards(apps) {
  const cache = loadCache();
  let count = 0;

  apps.forEach(app => {
    const htmlId = Object.keys(APP_ID_MAP).find(
      id => APP_ID_MAP[id].toLowerCase() === app.nombre.toLowerCase()
    );
    if (!htmlId) return;

    const card = document.querySelector('[data-id="' + htmlId + '"]');
    if (!card) return;

    const prev  = cache[htmlId] || {};
    const eNorm = normalizeLink(app.enlace);
    const pNorm = normalizeLink(app.plugin_enlace);
    const linkChanged = prev.enlace && prev.enlace !== eNorm;

    // Botón principal
    if (app.enlace && app.enlace !== '#') {
      const btn = card.querySelector('.dl-btn.dl-primary');
      if (btn) {
        btn.href = eNorm;
        // Inyectar badge de proveedor si no existe
        if (!btn.querySelector('.ch-provider')) {
          const b = document.createElement('small');
          b.className = 'ch-provider';
          b.style.cssText = 'font-size:.65rem;opacity:.6;margin-left:6px';
          btn.appendChild(b);
        }
        btn.querySelector('.ch-provider').textContent = getProviderLabel(app.enlace);
        count++;
      }
    }

    // Botón plugin
    if (app.plugin_enlace) {
      const btn = card.querySelector('.dl-btn.dl-ghost');
      if (btn) {
        btn.href = pNorm;
        if (!btn.querySelector('.ch-provider')) {
          const b = document.createElement('small');
          b.className = 'ch-provider';
          b.style.cssText = 'font-size:.65rem;opacity:.6;margin-left:6px';
          btn.appendChild(b);
        }
        btn.querySelector('.ch-provider').textContent = getProviderLabel(app.plugin_enlace);
      }
    }

    // Badge visual si el link cambió
    if (linkChanged && UPDATER_CONFIG.showLinkBadge) {
      const vTag = card.querySelector('.app-version-tag');
      if (vTag && !vTag.dataset.linkUpdated) {
        vTag.dataset.linkUpdated = '1';
        const badge = document.createElement('span');
        badge.textContent = ' 🔗';
        badge.title = 'Link actualizado recientemente';
        vTag.appendChild(badge);
      }
    }

    // Guardar en cache
    cache[htmlId] = { enlace: eNorm, plugin_enlace: pNorm,
      version: app.version_conocida, fecha: app.ultima_fecha };
  });

  saveCache(cache);
  return count;
}

// ─── LOG EN CONSOLA ───────────────────────────────────────────

function logReport(changes, total) {
  if (changes.length) {
    console.group('%c[Updater] 🔄 ' + changes.length + ' link(s) cambiados', 'color:#fb923c;font-weight:bold');
    changes.forEach(c => {
      const icons = { dropbox:'📦', gdrive:'☁️', mediafire:'🔥', direct:'🔗' };
      console.log((icons[c.provider] || '🔗') + ' ' + c.nombre + ' — ' + c.campo);
      console.log('   Anterior:', c.anterior);
      console.log('   Nuevo:   ', c.nuevo);
    });
    console.groupEnd();
  } else {
    console.log('%c[Updater] ✅ Todos los links al día (' + total + ' apps)', 'color:#4ade80');
  }
}

// ─── TOAST VISUAL ─────────────────────────────────────────────

function showToast(changes) {
  if (!changes.length) return;
  const old = document.getElementById('ch-updater-toast');
  if (old) old.remove();

  const toast = document.createElement('div');
  toast.id = 'ch-updater-toast';
  toast.innerHTML = '<span>🔄 ' + changes.length + ' app' + (changes.length > 1 ? 's' : '') +
    ' con link actualizado</span><button onclick="this.parentNode.remove()">✕</button>';
  toast.style.cssText = [
    'position:fixed;bottom:24px;left:50%;transform:translateX(-50%)',
    'background:#1a1a2e;border:1px solid rgba(255,69,0,.4);color:#fff',
    'padding:10px 18px;border-radius:10px;font-size:.85rem',
    'display:flex;gap:12px;align-items:center;z-index:9999',
    'box-shadow:0 4px 20px rgba(0,0,0,.5)'
  ].join(';');
  toast.querySelector('button').style.cssText = 'background:none;border:none;color:#ff6b35;cursor:pointer;font-size:1rem';
  document.body.appendChild(toast);
  setTimeout(() => toast?.remove(), 7000);
}

// ─── ANIMACIÓN EN CARDS ───────────────────────────────────────

function animateCards(changes) {
  if (!UPDATER_CONFIG.animateChanges || !changes.length) return;
  [...new Set(changes.map(c => c.htmlId))].forEach(id => {
    const card = document.querySelector('[data-id="' + id + '"]');
    if (!card) return;
    card.style.transition = 'box-shadow .4s';
    card.style.boxShadow  = '0 0 0 2px rgba(255,69,0,.7)';
    setTimeout(() => { card.style.boxShadow = ''; }, 2500);
  });
}

// ─── FUNCIÓN PRINCIPAL ────────────────────────────────────────

async function checkForUpdates(showMessage = false) {
  const loading = document.getElementById('loading-indicator');
  if (loading) loading.style.display = 'block';

  try {
    const data = await loadAppsData();
    if (!data || !data.apps) throw new Error('Datos inválidos');

    const cache   = loadCache();
    const changes = detectChanges(data.apps, cache);
    const total   = applyDataToCards(data.apps);

    logReport(changes, total);
    showToast(changes);
    animateCards(changes);

    if (!changes.length && showMessage) {
      const n = document.getElementById('update-notification');
      if (n) { n.textContent = '✅ Todos los links están al día'; n.className = 'update-notification show success'; setTimeout(() => n.classList.remove('show'), 4000); }
    }
  } catch (err) {
    console.error('[Updater] Error:', err.message);
  } finally {
    if (loading) loading.style.display = 'none';
    setTimeout(() => { if (loading) loading.style.display = 'none'; }, 8000);
  }
}

// ─── BÚSQUEDA ─────────────────────────────────────────────────

function buscarApp() {
  const q = (document.getElementById('search')?.value || '').toLowerCase();
  document.querySelectorAll('.app-card').forEach(card => {
    const name = (card.querySelector('h2')?.innerText || '').toLowerCase();
    card.style.display = name.includes(q) ? '' : 'none';
  });
}

// ─── PARTÍCULAS ───────────────────────────────────────────────

function createParticles() {
  const c = document.getElementById('particles');
  if (!c) return;
  for (let i = 0; i < 50; i++) {
    const p = document.createElement('div');
    p.className = 'particle';
    p.style.left = Math.random() * 100 + '%';
    p.style.animationDelay = Math.random() * 8 + 's';
    p.style.animationDuration = (Math.random() * 10 + 5) + 's';
    c.appendChild(p);
  }
}

// ─── INIT ─────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
  document.getElementById('search')?.addEventListener('keypress', e => {
    if (e.key === 'Enter') buscarApp();
  });

  createParticles();
  checkForUpdates();

  if (UPDATER_CONFIG.checkInterval > 0) {
    setInterval(() => checkForUpdates(), UPDATER_CONFIG.checkInterval);
  }

  console.log('%c💎 CodeHub Premium Apps', 'color:#ff6b35;font-size:18px;font-weight:bold');
  console.log('%c🔄 Updater v3.0  |  Dropbox · Drive · Mediafire', 'color:#64748b;font-size:.8rem');
});

window.checkForUpdates = checkForUpdates;
window.buscarApp       = buscarApp;
