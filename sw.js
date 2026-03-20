// ═══════════════════════════════════════════════════════
//  CodeHub SW v4.1 — Wilson.E 2026
//  PWA mejorada: cache inteligente + offline + sync + update
// ═══════════════════════════════════════════════════════

const VERSION   = 'codehub-v4.1';
const API_CACHE = 'codehub-api-v4';
const OFFLINE   = '/offline.html';

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

// ── INSTALL ───────────────────────────────────────────
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

// ── ACTIVATE — limpiar caches viejos ─────────────────
self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(
        keys
          .filter(k => k !== VERSION && k !== API_CACHE)
          .map(k => { console.log('🗑️ Cache eliminado:', k); return caches.delete(k); })
      )
    ).then(() => {
      // Notificar a todos los clientes que hay nueva versión
      self.clients.matchAll({ includeUncontrolled: true }).then(clients => {
        clients.forEach(client => client.postMessage({ type: 'SW_UPDATED', version: VERSION }));
      });
    })
  );
  self.clients.claim();
});

// ── FETCH ─────────────────────────────────────────────
self.addEventListener('fetch', e => {
  const { request } = e;
  const url = new URL(request.url);

  // 1. Railway API — Network only, sin interceptar nunca
  if (url.hostname.includes('railway.app') || url.hostname.includes('up.railway.app')) {
    e.respondWith(fetch(request.clone()).catch(() =>
      new Response(JSON.stringify({ error: 'Sin conexión', offline: true }), {
        headers: { 'Content-Type': 'application/json' }
      })
    ));
    return;
  }

  // 2. Supabase — Network only
  if (url.hostname.includes('supabase.co')) {
    e.respondWith(fetch(request.clone()).catch(() => new Response('', { status: 503 })));
    return;
  }

  // 3. Admin panel — nunca cachear
  if (url.pathname.includes('admin-hub')) {
    e.respondWith(fetch(request.clone()).catch(() => new Response('', { status: 503 })));
    return;
  }

  // 4. APIs externas — Network only
  if (!url.hostname.includes('wilson360-labs') && !url.hostname.includes('localhost') &&
      url.protocol === 'https:' && !url.pathname.match(/\.(html|css|js|png|jpg|svg|webp|ico|json|woff2?)$/)) {
    e.respondWith(fetch(request).catch(() => new Response('', { status: 503 })));
    return;
  }

  // 5. Navegación — Network first, cache fallback
  if (request.mode === 'navigate') {
    e.respondWith(
      fetch(request)
        .then(res => {
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

  // 6. Assets estáticos — Cache first, network fallback
  if (['style','script','image','font'].includes(request.destination)) {
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

  // 7. Todo lo demás — Network first
  e.respondWith(
    fetch(request).catch(() => caches.match(request))
  );
});

// ── MESSAGE ───────────────────────────────────────────
self.addEventListener('message', e => {
  if (e.data?.type === 'SKIP_WAITING') self.skipWaiting();
  if (e.data?.type === 'GET_VERSION')  e.ports[0]?.postMessage({ version: VERSION });
  if (e.data?.type === 'CHECK_UPDATE') {
    self.registration.update().catch(() => {});
  }
});
