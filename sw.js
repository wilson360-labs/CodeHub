// ═══════════════════════════════════════════════════════
//  CodeHub SW v4.4 — Wilson.E 2026
//  PWA mejorada: cache inteligente + offline + sync + update
//  + Push Notifications (app updates + clima)
//  v4.4: auto-update — el cliente ahora aplica el SW nuevo y
//        limpia caché vieja automáticamente sin esperar clic manual
// ═══════════════════════════════════════════════════════

const VERSION   = 'codehub-v4.4';
const API_CACHE = 'codehub-api-v4';
const OFFLINE   = '/offline.html';

const PRECACHE = [
  '/',
  '/index.html',
  '/tools',
  '/novedades',
  '/downloader',
  '/servicios',
  '/css/novedades.css',
  '/manifest.json',
  '/offline.html',
  '/js/script.js',
  '/js/theme-switcher.js',
  '/js/updater.js',
  '/js/device-detect.js',
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
  // NO llamar self.skipWaiting() aquí.
  // El SW nuevo queda en estado "waiting" hasta que el usuario confirme.
});

// ── ACTIVATE ─────────────────────────────────────────
self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(
        keys
          .filter(k => k !== VERSION && k !== API_CACHE)
          .map(k => { console.log('🗑️ Cache eliminado:', k); return caches.delete(k); })
      )
    ).then(() => {
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

  if (url.hostname.includes("onrender.com")) {
    e.respondWith(fetch(request.clone()).catch(() =>
      new Response(JSON.stringify({ error: 'Sin conexión', offline: true }), {
        headers: { 'Content-Type': 'application/json' }
      })
    ));
    return;
  }
  if (url.hostname.includes('supabase.co')) {
    e.respondWith(fetch(request.clone()).catch(() => new Response('', { status: 503 })));
    return;
  }
  if (url.pathname.includes('admin-hub')) {
    e.respondWith(fetch(request.clone()).catch(() => new Response('', { status: 503 })));
    return;
  }
  if (!url.hostname.includes('wilson360-labs') && !url.hostname.includes('localhost') &&
      url.protocol === 'https:' && !url.pathname.match(/\.(html|css|js|png|jpg|svg|webp|ico|json|woff2?)$/)) {
    e.respondWith(fetch(request).catch(() => new Response('', { status: 503 })));
    return;
  }
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
  e.respondWith(fetch(request).catch(() => caches.match(request)));
});

// ══════════════════════════════════════════════════════
//  PUSH — recibir notificación del servidor
// ══════════════════════════════════════════════════════
self.addEventListener('push', e => {
  if (!e.data) return;
  let payload;
  try { payload = e.data.json(); }
  catch { payload = { title: 'CodeHub', body: e.data.text(), type: 'general' }; }

  const { title, body, type, appId, icon, url } = payload;

  const options = {
    body: body || '',
    icon: icon || '/splash/codehub.png',
    badge: '/splash/codehub.png',
    vibrate: [200, 100, 200],
    timestamp: Date.now(),
    requireInteraction: false,
    data: { url: url || '/novedades', type, appId },
    actions: [],
  };

  if (type === 'app_update') {
    options.tag       = `app-update-${appId}`;
    options.renotify  = true;
    options.actions   = [
      { action: 'view',    title: '⬇️ Ver app' },
      { action: 'dismiss', title: 'Cerrar' },
    ];
    options.data.url = '/novedades';
  } else if (type === 'weather') {
    options.tag      = 'codehub-weather';
    options.renotify = false;
    options.actions  = [{ action: 'open', title: '🌤 Ver clima' }];
    options.data.url = '/index.html#weather-section';
  } else {
    options.tag = 'codehub-general';
  }

  e.waitUntil(self.registration.showNotification(title || 'CodeHub', options));
});

// ── NOTIFICATION CLICK ────────────────────────────────
self.addEventListener('notificationclick', e => {
  e.notification.close();
  if (e.action === 'dismiss') return;
  const targetUrl = e.notification.data?.url || '/novedades';

  e.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(clients => {
      const existing = clients.find(c => c.url.includes(self.location.origin));
      if (existing) { existing.focus(); existing.navigate(targetUrl); return; }
      return self.clients.openWindow(targetUrl);
    })
  );
});

// ── MESSAGE ───────────────────────────────────────────
self.addEventListener('message', e => {
  if (e.data?.type === 'SKIP_WAITING') self.skipWaiting();
  if (e.data?.type === 'GET_VERSION')  e.ports[0]?.postMessage({ version: VERSION });
  if (e.data?.type === 'CHECK_UPDATE') self.registration.update().catch(() => {});

  // Push local (clima en tiempo real desde la página)
  if (e.data?.type === 'LOCAL_PUSH') {
    const { title, body, notifType, appId, icon, url } = e.data;
    self.registration.showNotification(title, {
      body,
      icon: icon || '/splash/codehub.png',
      badge: '/splash/codehub.png',
      tag: notifType === 'weather' ? 'codehub-weather' : `app-update-${appId || 'gen'}`,
      vibrate: [150, 100, 150],
      data: { url: url || '/novedades', type: notifType },
      renotify: true,
    });
  }
});
