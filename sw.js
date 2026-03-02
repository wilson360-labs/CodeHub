// CodeHub SW — Wilson.E 2026
const CACHE = 'codehub-v1';
const OFFLINE_URL = '/404.html';

const ASSETS = [
  '/',
  '/index.html',
  '/tools.html',
  '/novedades.html',
  '/novedades.css',
  '/codehub-ultra.html',
  '/downloader.html',
  '/manifest.json',
  '/404.html',
];

// Instalar — cachear assets principales
self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE).then(cache => {
      return Promise.allSettled(ASSETS.map(url => cache.add(url)));
    })
  );
  self.skipWaiting();
});

// Activar — limpiar caches viejos
self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    )
  );
  self.clients.claim();
});

// Fetch — cache first para assets, network first para API
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);

  // API calls — siempre network
  if (url.hostname.includes('railway.app') || url.pathname.startsWith('/api/')) {
    return e.respondWith(fetch(e.request).catch(() => new Response(JSON.stringify({ error: 'Sin conexión' }), { headers: { 'Content-Type': 'application/json' } })));
  }

  // Assets estáticos — cache first
  e.respondWith(
    caches.match(e.request).then(cached => {
      if (cached) return cached;
      return fetch(e.request).then(res => {
        // Cachear respuestas válidas
        if (res && res.status === 200 && res.type === 'basic') {
          const clone = res.clone();
          caches.open(CACHE).then(cache => cache.put(e.request, clone));
        }
        return res;
      }).catch(() => {
        // Sin conexión — mostrar 404 offline
        if (e.request.destination === 'document') {
          return caches.match(OFFLINE_URL);
        }
      });
    })
  );
});
