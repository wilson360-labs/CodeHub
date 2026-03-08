// ═══════════════════════════════════════════════════════
//  CodeHub SW v3.0 — Wilson.E 2026
//  PWA mejorada: cache inteligente + offline + sync
// ═══════════════════════════════════════════════════════

const VERSION   = 'codehub-v3';
const API_CACHE = 'codehub-api-v3';
const OFFLINE   = '/offline.html';

// Assets que se cachean al instalar
const PRECACHE = [
  '/',
  '/index.html',
  '/tools.html',
  '/novedades.html',
  '/downloader.html',
  '/servicios.html',
  '/novedades.css',
  '/manifest.json',
  '/offline.html',
  '/script.js',
  '/theme-switcher.js',
  '/updater.js',
];

// ── INSTALL — precachear todo ──────────────────────────
self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(VERSION).then(cache =>
      Promise.allSettled(PRECACHE.map(url =>
        cache.add(url).catch(() => console.warn('No se pudo cachear:', url))
      ))
    )
  );
  self.skipWaiting();
});

// ── ACTIVATE — limpiar caches viejos ──────────────────
self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(
        keys
          .filter(k => k !== VERSION && k !== API_CACHE)
          .map(k => { console.log('🗑️ Cache eliminado:', k); return caches.delete(k); })
      )
    )
  );
  self.clients.claim();
});

// ── FETCH — estrategia por tipo de recurso ─────────────
self.addEventListener('fetch', e => {
  const { request } = e;
  const url = new URL(request.url);

  // 1. API Railway — Network only con fallback JSON
  if (url.hostname.includes('railway.app')) {
    e.respondWith(
      fetch(request).catch(() =>
        new Response(JSON.stringify({ error: 'Sin conexión', offline: true }), {
          headers: { 'Content-Type': 'application/json' }
        })
      )
    );
    return;
  }

  // 2. APIs externas (groq, gemini, etc.) — Network only
  if (!url.hostname.includes('wilson360-labs') && !url.hostname.includes('localhost') && url.protocol === 'https:' && !url.pathname.match(/\.(html|css|js|png|jpg|svg|webp|ico|json|woff2?)$/)) {
    e.respondWith(fetch(request).catch(() => new Response('', { status: 503 })));
    return;
  }

  // 3. Navegación a páginas — Network first, cache fallback
  if (request.mode === 'navigate') {
    e.respondWith(
      fetch(request)
        .then(res => {
          // Actualizar cache con versión fresca
          if (res.ok) {
            const clone = res.clone();
            caches.open(VERSION).then(c => c.put(request, clone));
          }
          return res;
        })
        .catch(async () => {
          const cached = await caches.match(request);
          return cached || caches.match(OFFLINE);
        })
    );
    return;
  }

  // 4. Assets estáticos (CSS, JS, imágenes) — Cache first, network fallback
  if (request.destination === 'style' || request.destination === 'script' || request.destination === 'image' || request.destination === 'font') {
    e.respondWith(
      caches.match(request).then(cached => {
        if (cached) return cached;
        return fetch(request).then(res => {
          if (res && res.ok) {
            const clone = res.clone();
            caches.open(VERSION).then(c => c.put(request, clone));
          }
          return res;
        }).catch(() => new Response('', { status: 404 }));
      })
    );
    return;
  }

  // 5. Todo lo demás — Network first
  e.respondWith(
    fetch(request).catch(() => caches.match(request))
  );
});

// ── MESSAGE — forzar actualización desde el cliente ───
self.addEventListener('message', e => {
  if (e.data?.type === 'SKIP_WAITING') self.skipWaiting();
  if (e.data?.type === 'GET_VERSION')  e.ports[0]?.postMessage({ version: VERSION });
});
