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

const VERSION = 'codehub-v6.68';
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
  '/guias',
  '/opensource',
  '/servicios',
  '/css/index.css',
  '/css/opensource.css',
  '/css/components.css',
  '/css/index-responsive.css',
  '/css/site-tour.css',
  '/css/viewport-guard.css',
  '/css/fontawesome/all.min.css',
  '/css/fontawesome/webfonts/fa-brands-400.woff2',
  '/css/fontawesome/webfonts/fa-regular-400.woff2',
  '/css/fontawesome/webfonts/fa-solid-900.woff2',
  '/css/fontawesome/webfonts/fa-v4compatibility.woff2',
  '/manifest.json',
  '/offline.html',
  '/js/script.js',
  '/js/theme-switcher.js',
  '/js/updater.js',
  '/js/device-detect.js',
  '/js/live-update-check.js',
  '/js/remote-config.js',
  '/js/bug-reporter.js',
  '/js/emi-voice.js',
  '/js/ux-animations.js',
  '/js/emailjs.min.js',
  '/js/auth.js',
  '/js/thinking-orb.js',
  '/changelog.json',
  '/js/site-tour.js',
  '/js/consent-banner.js',
  '/js/connection-alert.js',
  '/js/notifications.js',
  '/js/morphicons-init.js',
  '/js/vendor/morphicons/element.js',
  '/js/vendor/morphicons/controller-CXZuwJ_M.js',
  '/js/vendor/morphicons/dom.js',
  '/js/vendor/morphicons/index.js',
  '/js/vendor/morphicons/normalize-CYnN3Npw.js',
  '/js/vendor/morphicons/spring-CFHloqPP.js',
  '/js/office-generator.js',
  '/js/deep-search.js',
  '/js/weather-map.js',
  '/js/vendor/leaflet/leaflet.css',
  '/js/vendor/leaflet/leaflet.js',
  '/widgets/weather/weather-widget.css',
  '/widgets/weather/weather-widget.js',
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
    // Prune entries older than 7 days
    var cutoff = Date.now() - 7 * 24 * 60 * 60 * 1000;
    list = list.filter(n => (n.ts || 0) > cutoff);
    return writeNotifStore(list);
  }).catch(() => {});
}

// Dominios de teselas de mapa (Leaflet: CARTO, Esri/ArcGIS, OSM, MapTiler).
// BUG CORREGIDO: el SW interceptaba TODAS las peticiones cross-origin
// (imágenes incluidas) y las volvía a pedir con su propio fetch() interno.
// Ese fetch() hecho DESDE el Service Worker se evalúa contra la directiva
// "connect-src" del CSP (no "img-src", aunque el recurso final sea una
// imagen) — un matiz de Chromium/WebView bien documentado. Como el CSP
// solo listaba estos dominios en "img-src" (con comodín https:) y no en
// "connect-src", el proxy interno del SW los bloqueaba en cuanto el SW
// estaba activo — reproducible en móvil/APK (donde el SW se registra de
// forma más consistente) y NO en desktop en la primera carga (SW aún sin
// controlar la página). Esto explicaba que las 4 capas de respaldo
// (MapTiler/CARTO/Esri/OSM) fallaran TODAS a la vez en móvil.
// Fix real: estas peticiones de teselas ni se interceptan — se dejan
// pasar directo al navegador (sin proxy del SW), que las resuelve con su
// mecanismo nativo de imagen/caché HTTP. Así no dependen de connect-src
// ni de la lógica interna del SW. connect-src también se amplió como
// refuerzo (ver index.html / vercel.json) para cualquier fetch()
// explícito futuro sobre estos mismos dominios.
const MAP_TILE_HOSTS = [
  'basemaps.cartocdn.com',
  'arcgisonline.com',
  'tile.openstreetmap.org',
  'api.maptiler.com',
];

// ── FETCH ─────────────────────────────────────────────
self.addEventListener('fetch', e => {
  const { request } = e;
  const url = new URL(request.url);

  // Teselas de mapa: no interceptar, dejar pasar tal cual (ver nota arriba).
  if (MAP_TILE_HOSTS.some(h => url.hostname === h || url.hostname.endsWith('.' + h))) {
    return;
  }

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
  // BUG CORREGIDO: este catch-all (peticiones cross-origin sin extensión
  // conocida) atrapaba de paso las teselas de Esri/ArcGIS (sin sufijo
  // .png en la URL) y las reenviaba con fetch() del SW — mismo problema
  // de connect-src explicado arriba. Ya no aplica: MAP_TILE_HOSTS se
  // filtra antes de llegar aquí. Se excluyen además por tipo (destination
  // 'image') como refuerzo, por si algún proveedor de teselas futuro no
  // está en la lista.
  if (request.destination !== 'image' &&
      !url.hostname.includes('wilson360-labs') && !url.hostname.includes('localhost') &&
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

  if (Notification.permission !== 'granted') return;

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
      if (existing) { existing.focus(); existing.navigate(targetUrl).catch(() => {}); return; }
      return self.clients.openWindow(targetUrl).catch(() => {});
    }).catch(() => {})
  );
});

// ── MESSAGE ───────────────────────────────────────────
self.addEventListener('message', e => {
  if (e.data?.type === 'SKIP_WAITING') self.skipWaiting();
  if (e.data?.type === 'GET_VERSION')  e.ports[0]?.postMessage({ version: VERSION });
  if (e.data?.type === 'CHECK_UPDATE') self.registration.update().catch(() => {});

  // La app instalada pide sincronización (permisos de internet + sync)
  if (e.data?.type === 'REGISTER_SYNC') {
    const reg = self.registration;
    try { if ('sync' in reg) reg.sync.register(SYNC_TAG); } catch (err) {}
    try {
      if ('periodicSync' in reg && typeof reg.periodicSync.register === 'function') {
        reg.periodicSync.register(PERIODIC_TAG, { minInterval: 30 * 60 * 1000 }).catch(() => {});
      }
    } catch (err) {}
  }

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

// ── BACKGROUND SYNC ────────────────────────────────────
// Con la app instalada y permiso de notificaciones otorgado, el
// navegador despierta el SW cuando recupera conexión ("sincronización
// de internet") y cuando hay una sincronización periódica. Aquí
// re-chequeamos novedades (CodeHub Releases) y clima para notificar
// en tiempo real aunque la app esté cerrada.
const SYNC_TAG   = 'codehub-sync';
const PERIODIC_TAG = 'codehub-periodic-sync';
const SYNC_BACKEND = 'https://codehub-98s6.onrender.com';

// Revisa si hay releases recientes y, si el usuario no los ha visto,
// muestra la notificación y la guarda en el Centro de Notificaciones.
function syncCheckReleases() {
  return fetch(SYNC_BACKEND + '/api/releases', { headers: { 'Accept': 'application/json' } })
    .then(r => r.ok ? r.json() : { releases: [] })
    .then(data => {
      if (!data || !data.ok || !Array.isArray(data.releases) || !data.releases.length) return;
      const latest = data.releases[0];
      return readNotifStore().then(list => {
        const seen = list.some(n => n.url === latest.url || (n.body || '').includes(latest.title));
        if (seen) return;
        const title = latest.version ? ('🚀 CodeHub ' + latest.version) : '🚀 CodeHub Release';
        const body = latest.title + (latest.body ? ' — ' + String(latest.body).slice(0, 110) : '');
        self.registration.showNotification(title, {
          body,
          icon: '/splash/codehub.png',
          badge: '/splash/codehub.png',
          tag: 'codehub-release',
          vibrate: [200, 100, 200],
          data: { url: latest.url || '/', type: 'release' },
          actions: [{ action: 'view', title: '🔎 Ver novedad' }],
        });
        pushToNotifStore({ title, body, type: 'release', url: latest.url || '/', icon: '/splash/codehub.png' });
        // Avisar a la página abierta (panel en-app)
        self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(clients => {
          clients.forEach(c => c.postMessage({
            type: 'CH_PUSH', title, body, notifType: 'release', url: latest.url || '/', icon: '/splash/codehub.png',
          }));
        }).catch(() => {});
      });
    })
    .catch(() => {});
}

// Guarda el último estado de clima sincronizado para evitar spam
function syncWeatherLast() {
  return caches.open(NOTIF_CACHE).then(c => c.match('/ch-sync-wx')).then(r => r ? r.json() : {});
}
function syncWeatherSave(state) {
  return caches.open(NOTIF_CACHE).then(c =>
    c.put('/ch-sync-wx', new Response(JSON.stringify(state), { headers: { 'Content-Type': 'application/json' } }))
  ).catch(() => {});
}

// Chequeo de clima (misma lógica de alertas que el backend)
// Lee la ubicación guardada por el usuario en vez de usar coords hardcodeadas
function syncCheckWeather() {
  // First, try to read user's saved location from cache
  return caches.open(NOTIF_CACHE).then(c => c.match('/ch-user-loc')).then(r => {
    if (!r) return null;
    return r.json().catch(() => null);
  }).then(loc => {
    const lat = (loc && loc.lat) || null;
    const lon = (loc && loc.lon) || null;
    const city = (loc && loc.city) || '';
    if (!lat || !lon) return; // No location saved — skip weather check
    return fetch('https://api.open-meteo.com/v1/forecast?latitude=' + lat + '&longitude=' + lon + '&current=temperature_2m,weather_code,wind_speed_10m,precipitation&timezone=auto')
    .then(r => r.ok ? r.json() : null)
    .then(d => {
      if (!d || !d.current) return;
      const c = d.current;
      let msg = '';
      let cond = '';
      if (c.weather_code >= 95)            { msg = '⛈️ Tormenta eléctrica en tu zona — evita zonas abiertas'; cond = 'storm'; }
      else if (c.weather_code >= 61 && c.weather_code <= 67) { msg = '🌧️ Lluvia en tu zona — lleva paraguas'; cond = 'rain'; }
      else if (c.wind_speed_10m > 50)      { msg = '💨 Viento fuerte (' + c.wind_speed_10m + ' km/h) — precaución'; cond = 'wind'; }
      else if (c.temperature_2m > 33)      { msg = '🌡️ Calor extremo (' + c.temperature_2m + '°C) — hidrátate'; cond = 'heat'; }
      else if (c.temperature_2m < 0)       { msg = '🥶 Frío intenso — abrígate bien'; cond = 'cold'; }
      if (!msg) return syncWeatherSave({ cond: null, ts: Date.now() });
      return syncWeatherLast().then(prev => {
        if (prev && prev.cond === cond && Date.now() - (prev.ts || 0) < 2 * 60 * 60 * 1000) return;
        self.registration.showNotification('CodeHub Clima', {
          body: msg + ' · ' + (city || 'tu zona'),
          icon: '/splash/codehub.png',
          badge: '/splash/codehub.png',
          tag: 'codehub-weather',
          vibrate: [150, 100, 150],
          data: { url: '/index.html#weather-section', type: 'weather' },
        });
        pushToNotifStore({ title: 'CodeHub Clima', body: msg, type: 'weather', url: '/#weather-section', icon: '/splash/codehub.png' });
        return syncWeatherSave({ cond, ts: Date.now() });
      });
    }).catch(() => {});
  }).catch(() => {});
}

self.addEventListener('sync', e => {
  if (e.tag === SYNC_TAG) {
    e.waitUntil(Promise.allSettled([syncCheckReleases(), syncCheckWeather()]));
  }
});

self.addEventListener('periodicsync', e => {
  if (e.tag === PERIODIC_TAG) {
    e.waitUntil(Promise.allSettled([syncCheckReleases(), syncCheckWeather()]));
  }
});
