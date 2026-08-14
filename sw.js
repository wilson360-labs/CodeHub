// ═══════════════════════════════════════════════════════
//  CodeHub SW v5.0 — Wilson.E 2026
//  PWA mejorada: cache inteligente + offline + sync + update
//  + Push Notifications (app updates + clima)
//  v5.0: ESTRATEGIA DE CACHÉ CAMBIADA — antes html/css/js
//        eran "cache-first" (¡el navegador podía quedarse con
//        una versión vieja para siempre si no se subía VERSION
//        a mano!). Ahora son "network-first": siempre se intenta
//        la red primero, así un push a GitHub/Vercel se refleja
//        al instante en la próxima carga de página, sin depender
//        de recordar bumpear VERSION. La caché queda solo como
//        respaldo offline (o si la red tarda demasiado).
// ═══════════════════════════════════════════════════════

const VERSION   = 'codehub-v6.19';
const API_CACHE = 'codehub-api-v4';
const OFFLINE   = '/offline.html';
// Historial de notificaciones push para el Centro de Notificaciones
const NOTIF_CACHE = 'codehub-notifs-v1';

// Tiempo máximo que se espera a la red antes de servir la copia en
// caché (si existe) mientras la red sigue intentando en segundo plano.
// Bajo a propósito: preferimos "rápido y quizás offline" a "lento".
const NETWORK_TIMEOUT_MS = 4000;

const PRECACHE = [
  '/',
  '/index.html',
  '/tools',
  '/opensource',
  '/downloader',
  '/servicios',
  '/css/opensource.css',
  '/css/components.css',
  '/css/index-responsive.css',
  '/css/site-tour.css',
  '/css/viewport-guard.css',
  '/manifest.json',
  '/offline.html',
  '/js/script.js',
  '/js/theme-switcher.js',
  '/js/updater.js',
  '/js/device-detect.js',
  '/js/live-update-check.js',
  '/js/emi-voice.js',
  '/js/ux-animations.js',
  '/js/auth.js',
  '/js/thinking-orb.js',
  '/js/site-tour.js',
  '/js/consent-banner.js',
  '/js/connection-alert.js',
  '/js/notifications.js',
  '/data/roadmap.json',
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
  // El SW nuevo queda en estado "waiting" hasta que el cliente confirme
  // (index.html ya hace esto automáticamente, ver onSWWaiting()).
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

// ── ESTRATEGIAS DE CACHÉ ───────────────────────────────

// network-first: intenta la red; si tarda más de NETWORK_TIMEOUT_MS o
// falla (offline), usa la copia en caché mientras tanto. Si la red
// responde bien, siempre actualiza la caché para la próxima vez.
function networkFirst(request, fallback) {
  return new Promise(resolve => {
    let settled = false;
    const timer = setTimeout(async () => {
      if (settled) return;
      const cached = await caches.match(request);
      if (cached) { settled = true; resolve(cached); }
    }, NETWORK_TIMEOUT_MS);

    fetch(request).then(res => {
      clearTimeout(timer);
      if (res && res.ok) {
        const clone = res.clone();
        caches.open(VERSION).then(c => c.put(request, clone));
      }
      if (!settled) { settled = true; resolve(res); }
    }).catch(async () => {
      clearTimeout(timer);
      if (settled) return;
      const cached = await caches.match(request);
      settled = true;
      resolve(cached || fallback || new Response('', { status: 504 }));
    });
  });
}

// stale-while-revalidate: sirve la caché al instante si existe (rápido),
// y en paralelo pide la versión fresca a la red para la próxima carga.
// Pensado para imágenes/fuentes: cambian poco y priorizamos velocidad.
function staleWhileRevalidate(request) {
  return caches.match(request).then(cached => {
    const fresh = fetch(request).then(res => {
      if (res && res.ok) {
        const clone = res.clone();
        caches.open(VERSION).then(c => c.put(request, clone));
      }
      return res;
    }).catch(() => cached);
    return cached || fresh;
  });
}

// ── HISTORIAL DE NOTIFICACIONES (para el panel en-app) ──
// Guarda las últimas notificaciones push recibidas (aunque la app esté
// cerrada) para que el Centro de Notificaciones las muestre al reabrir.
const NOTIF_MAX = 25;

function readNotifStore() {
  return caches.open(NOTIF_CACHE).then(c => c.match('/ch-notifs')).then(r => {
    if (!r) return [];
    return r.json().catch(() => []);
  });
}

function writeNotifStore(list) {
  return caches.open(NOTIF_CACHE).then(c =>
    c.put('/ch-notifs', new Response(JSON.stringify(list.slice(0, NOTIF_MAX)), {
      headers: { 'Content-Type': 'application/json' }
    }))
  ).catch(() => {});
}

function pushToNotifStore(payload) {
  readNotifStore().then(list => {
    list.unshift({
      title: payload.title || 'CodeHub',
      body: payload.body || '',
      type: payload.type || 'general',
      url: payload.url || '/opensource',
      icon: payload.icon || '/splash/codehub.png',
      ts: Date.now(),
    });
    return writeNotifStore(list);
  }).catch(() => {});
}

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
  if (url.pathname.includes('admin-hub') || url.pathname.includes('/admin') || url.pathname.includes('/api/admin')) {
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
      networkFirst(request).then(async res => {
        if (res && res.status === 504) {
          const off = await caches.match(OFFLINE);
          return off || res;
        }
        return res;
      })
    );
    return;
  }
  // HTML/CSS/JS: network-first — así los pushes a GitHub/Vercel se ven
  // al instante. Antes esto era cache-first y podía quedar "pegado" a
  // una versión vieja indefinidamente.
  if (['style', 'script'].includes(request.destination) ||
      url.pathname.match(/\.(html|css|js)$/)) {
    e.respondWith(networkFirst(request));
    return;
  }
  // Imágenes/fuentes: stale-while-revalidate — rápido y se refresca solo.
  if (['image', 'font'].includes(request.destination)) {
    e.respondWith(staleWhileRevalidate(request));
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
    lang: 'es',
    dir: 'auto',
    data: { url: url || '/opensource', type, appId },
    actions: [],
  };

  if (type === 'app_update') {
    options.tag       = `app-update-${appId}`;
    options.renotify  = true;
    options.actions   = [
      { action: 'view',    title: '⬇️ Ver app' },
      { action: 'dismiss', title: 'Cerrar' },
    ];
    options.data.url = '/opensource';
  } else if (type === 'weather') {
    options.tag      = 'codehub-weather';
    options.renotify = false;
    options.actions  = [{ action: 'open', title: '🌤 Ver clima' }];
    options.data.url = url || '/index.html#weather-section';
  } else if (type === 'release') {
    options.tag      = 'codehub-release';
    options.renotify = true;
    options.actions  = [
      { action: 'view',    title: '🔎 Ver novedad' },
      { action: 'dismiss', title: 'Cerrar' },
    ];
    options.data.url = url || '/';
  } else {
    options.tag = 'codehub-general';
  }

  e.waitUntil(self.registration.showNotification(title || 'CodeHub', options));

  // Reenviar a las páginas abiertas para que el Centro de
  // Notificaciones (js/notifications.js) las muestre también.
  e.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(clients => {
      clients.forEach(client => client.postMessage({
        type: 'CH_PUSH',
        title: title || 'CodeHub',
        body: body || '',
        notifType: type || 'general',
        url: url || '/opensource',
        icon: icon || '/splash/codehub.png',
      }));
    }).catch(() => {})
  );
  // Guardar en el historial para el panel (aunque la app esté cerrada)
  e.waitUntil(pushToNotifStore({ title, body, type, url, icon }));
});

// ── NOTIFICATION CLICK ────────────────────────────────
self.addEventListener('notificationclick', e => {
  e.notification.close();
  if (e.action === 'dismiss') return;
  const targetUrl = e.notification.data?.url || '/opensource';

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

  // Pedir el historial de notificaciones guardadas (panel en-app)
  if (e.data?.type === 'GET_NOTIFS') {
    readNotifStore().then(list => e.ports[0]?.postMessage({ notifs: list })).catch(() => {});
  }

  // Vaciar el historial de notificaciones (usuario limpió el panel)
  if (e.data?.type === 'CLEAR_NOTIFS') {
    writeNotifStore([]).then(() => e.ports[0]?.postMessage({ cleared: true })).catch(() => {});
  }

  // Push local (clima en tiempo real desde la página)
  if (e.data?.type === 'LOCAL_PUSH') {
    const { title, body, notifType, appId, icon, url } = e.data;
    self.registration.showNotification(title, {
      body,
      icon: icon || '/splash/codehub.png',
      badge: '/splash/codehub.png',
      tag: notifType === 'weather' ? 'codehub-weather' : `app-update-${appId || 'gen'}`,
      vibrate: [150, 100, 150],
      data: { url: url || '/opensource', type: notifType },
      renotify: true,
    });
    // También al Centro de Notificaciones de la página abierta
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(clients => {
      clients.forEach(client => client.postMessage({
        type: 'CH_PUSH',
        title,
        body: body || '',
        notifType: notifType || 'general',
        url: url || '/opensource',
        icon: icon || '/splash/codehub.png',
      }));
    }).catch(() => {});
  }
});
