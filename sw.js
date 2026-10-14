// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
//  CodeHub SW â€” Wilson.E 2026
//  VersiÃ³n vigente: ver la constante VERSION mÃ¡s abajo
//  (build-apk.yml publica la release del APK como apk-<VERSION>).
//  PWA mejorada: cache inteligente + offline + sync + update
//  + Push Notifications (app updates + clima)
//  v5.0: ESTRATEGIA DE CACHÃ‰ CAMBIADA â€” antes html/css/js
//        eran "cache-first" (Â¡el navegador podÃ­a quedarse con
//        una versiÃ³n vieja para siempre si no se subÃ­a VERSION
//        a mano!). Ahora son "network-first": siempre se intenta
//        la red primero, asÃ­ un push a GitHub/Vercel se refleja
//        al instante en la prÃ³xima carga de pÃ¡gina, sin depender
//        de recordar bumpear VERSION. La cachÃ© queda solo como
//        respaldo offline (o si la red tarda demasiado).
//  v5.1: NO interceptar imÃ¡genes/fuentes cross-origin (bug del
//        connect-src del CSP, ver nota en fetch). El navegador
//        las resuelve y cachea de forma nativa.
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

const VERSION = 'codehub-v7.18';
const OFFLINE   = '/offline.html';
// Historial de notificaciones push para el Centro de Notificaciones
const NOTIF_CACHE = 'codehub-notifs-v1';

// Tiempo mÃ¡ximo que se espera a la red antes de servir la copia en
// cachÃ© (si existe) mientras la red sigue intentando en segundo plano.
// Bajo a propÃ³sito: preferimos "rÃ¡pido y quizÃ¡s offline" a "lento".
const NETWORK_TIMEOUT_MS = 4000;

const PRECACHE = [
  '/',
  '/index.html',
  '/tools',
  '/guias',
  '/opensource',
  '/servicios',
  '/acerca',
  '/cv-generador',
  '/css/index.css',
  '/css/opensource.css',
  '/css/components.css',
  '/css/index-responsive.css',
  '/css/site-tour.css',
  '/css/sys-tools.css',
  '/css/optimizer.css',
  '/css/native-feel.css',
  '/css/viewport-guard.css',
  '/css/prefs.css',
  '/js/prefs.js',
  '/css/terminal.css',
  '/css/xterm.css',
  '/fonts/codehub-fonts.css',
  '/fonts/jetbrains-mono-latin.woff2',
  '/fonts/jetbrains-mono-latin-ext.woff2',
  '/fonts/space-grotesk-latin.woff2',
  '/fonts/space-grotesk-latin-ext.woff2',
  '/fonts/syne-latin.woff2',
  '/fonts/syne-latin-ext.woff2',
  '/fonts/bebas-neue-latin.woff2',
  '/fonts/bebas-neue-latin-ext.woff2',
  '/css/fontawesome/all.min.css',
  '/css/fontawesome/webfonts/fa-brands-400.woff2',
  '/css/fontawesome/webfonts/fa-regular-400.woff2',
  '/css/fontawesome/webfonts/fa-solid-900.woff2',
  '/css/fontawesome/webfonts/fa-v4compatibility.woff2',
  '/manifest.json',
  '/offline.html',
  '/js/script.js',
  '/js/lazy-features.js',
  '/js/theme-switcher.js',
  '/js/updater.js',
  '/js/device-detect.js',
  '/js/live-update-check.js',
  '/js/remote-config.js',
  '/js/bug-reporter.js',
  '/js/emi-voice.js',
  '/js/ux-animations.js',
  '/js/vendor/anime.min.js',
  '/js/emailjs.min.js',
  '/js/auth.js',
  '/js/thinking-orb.js',
  '/changelog.json',
  '/js/site-tour.js',
  '/js/optimizer.js',
  '/js/terminal.js',
  '/js/vendor/xterm/xterm.mjs',
  '/js/vendor/xterm/addon-fit.mjs',
  '/js/vendor/xterm/addon-webgl.mjs',
  '/js/opensource.js',
  '/js/offline-queue.js',
  '/js/consent-banner.js',
  '/js/connection-alert.js',
  '/js/notifications.js',
  '/js/office-generator.js',
  '/js/deep-search.js',
  '/js/weather-map.js',
  '/js/ch-geo.js',
  '/js/sismos.js',
  '/js/haptics.js',
  '/js/sys-metrics.js',
  '/js/security-scan.js',
  '/js/vendor/leaflet/leaflet.css',
  '/js/vendor/leaflet/leaflet.js',
  '/widgets/weather/weather-widget.css',
  '/widgets/weather/weather-widget.js',
  // dist/ (esbuild Fase 0.1 - bundles hasheados que index.html carga)
  '/dist/assets/js/auth.14f7caec39.min.js',
  '/dist/assets/js/bug-reporter.fa420fbbab.min.js',
  '/dist/assets/js/connection-alert.c76b7978b9.min.js',
  '/dist/assets/js/consent-banner.b3efc7679a.min.js',
  '/dist/assets/js/deep-search.0171f144d5.min.js',
  '/dist/assets/js/device-detect.4bdd073619.min.js',
  '/dist/assets/js/emi-voice.d7c975d11a.min.js',
  '/dist/assets/js/haptics.ee67eff258.min.js',
  '/dist/assets/js/lazy-features.d93159e9dd.min.js',
  '/dist/assets/js/live-update-check.c7bd39bcbb.min.js',
  '/dist/assets/js/morphicons-init.a38de871fc.min.js',
  '/dist/assets/js/notifications.024f9d794c.min.js',
  '/dist/assets/js/office-generator.8d6cdf13ee.min.js',
  '/dist/assets/js/offline-queue.6b72f7a657.min.js',
  '/dist/assets/js/prefs.759f7e9abd.min.js',
  '/dist/assets/js/remote-config.353c271f68.min.js',
  '/dist/assets/js/rewarded-ad.466d6994d0.min.js',
  '/dist/assets/js/script.40e4e0e08a.min.js',
  '/dist/assets/js/security-scan.5a8b5156fe.min.js',
  '/dist/assets/js/site-tour.86fe554324.min.js',
  '/dist/assets/js/sys-metrics.441cf23ca5.min.js',
  '/dist/assets/js/theme-switcher.87479ab399.min.js',
  '/dist/assets/js/thinking-orb.2b4982d5a0.min.js',
  '/dist/assets/js/updater.cdaf77f61c.min.js',
  '/dist/assets/js/ux-animations.b6a116abe1.min.js',
  '/dist/assets/widgets/weather/weather-widget.fd1d5d4d92.min.js',
  '/data/roadmap.json',
];

// â”€â”€ INSTALL â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(VERSION).then(cache =>
      Promise.allSettled(PRECACHE.map(url =>
        cache.add(url).catch(() => console.warn('No se pudo cachear:', url))
      ))
    )
  );
  // NO llamar self.skipWaiting() aquÃ­.
  // El SW nuevo queda en estado "waiting" hasta que el cliente confirme
  // (index.html ya hace esto automÃ¡ticamente, ver onSWWaiting()).
});

// â”€â”€ ACTIVATE â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(
        keys
          .filter(k => k !== VERSION && !k.startsWith('codehub-api-'))
          .map(k => { console.log('ðŸ—‘ï¸ Cache eliminado:', k); return caches.delete(k); })
      )
    ).then(() => {
      self.clients.matchAll({ includeUncontrolled: true }).then(clients => {
        clients.forEach(client => client.postMessage({ type: 'SW_UPDATED', version: VERSION }));
      });
    })
  );
  self.clients.claim();
});

// â”€â”€ ESTRATEGIAS DE CACHÃ‰ â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

// network-first: intenta la red; si tarda mÃ¡s de NETWORK_TIMEOUT_MS o
// falla (offline), usa la copia en cachÃ© mientras tanto. Si la red
// responde bien, siempre actualiza la cachÃ© para la prÃ³xima vez.
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
      // Solo se cachean respuestas vÃ¡lidas de GET: un fetch HEAD (usado por
      // live-update-check) devolverÃ­a ok=true pero con body vacÃ­o y pisarÃ­a
      // la copia navegable de '/index.html' en cachÃ© (pÃ¡gina en blanco offline).
      if (res && res.ok && request.method === 'GET') {
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

// stale-while-revalidate: sirve la cachÃ© al instante si existe (rÃ¡pido),
// y en paralelo pide la versiÃ³n fresca a la red para la prÃ³xima carga.
// Pensado para imÃ¡genes/fuentes: cambian poco y priorizamos velocidad.
function staleWhileRevalidate(request) {
  return caches.match(request).then(cached => {
    const fresh = fetch(request).then(res => {
      if (res && res.ok && request.method === 'GET') {
        const clone = res.clone();
        caches.open(VERSION).then(c => c.put(request, clone));
      }
      return res;
    }).catch(() => cached);
    return cached || fresh;
  });
}

// stale-while-revalidate para la API del catÃ¡logo Open Source. Igual
// que la anterior pero con *fallback JSON* cuando NO hay copia en
// cachÃ© ni conexiÃ³n (no devuelve undefined, que romperÃ­a respondWith).
function staleWhileRevalidateApi(request) {
  return caches.match(request).then(cached => {
    const fresh = fetch(request).then(res => {
      if (res && res.ok) {
        const clone = res.clone();
        caches.open(VERSION).then(c => c.put(request, clone));
      }
      return res;
    }).catch(() => cached || new Response(
      JSON.stringify({ error: 'Sin conexiÃ³n', offline: true }),
      { headers: { 'Content-Type': 'application/json' } }
    ));
    return cached || fresh;
  });
}

// â”€â”€ HISTORIAL DE NOTIFICACIONES (para el panel en-app) â”€â”€
// Guarda las Ãºltimas notificaciones push recibidas (aunque la app estÃ©
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
// (imÃ¡genes incluidas) y las volvÃ­a a pedir con su propio fetch() interno.
// Ese fetch() hecho DESDE el Service Worker se evalÃºa contra la directiva
// "connect-src" del CSP (no "img-src", aunque el recurso final sea una
// imagen) â€” un matiz de Chromium/WebView bien documentado. Como el CSP
// solo listaba estos dominios en "img-src" (con comodÃ­n https:) y no en
// "connect-src", el proxy interno del SW los bloqueaba en cuanto el SW
// estaba activo â€” reproducible en mÃ³vil/APK (donde el SW se registra de
// forma mÃ¡s consistente) y NO en desktop en la primera carga (SW aÃºn sin
// controlar la pÃ¡gina). Esto explicaba que las 4 capas de respaldo
// (MapTiler/CARTO/Esri/OSM) fallaran TODAS a la vez en mÃ³vil.
// Fix real: estas peticiones de teselas ni se interceptan â€” se dejan
// pasar directo al navegador (sin proxy del SW), que las resuelve con su
// mecanismo nativo de imagen/cachÃ© HTTP. AsÃ­ no dependen de connect-src
// ni de la lÃ³gica interna del SW. connect-src tambiÃ©n se ampliÃ³ como
// refuerzo (ver index.html / vercel.json) para cualquier fetch()
// explÃ­cito futuro sobre estos mismos dominios.
const MAP_TILE_HOSTS = [
  'basemaps.cartocdn.com',
  'arcgisonline.com',
  'tile.openstreetmap.org',
  'api.maptiler.com',
];

// â”€â”€ FETCH â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
self.addEventListener('fetch', e => {
  const { request } = e;
  const url = new URL(request.url);

  // Teselas de mapa: no interceptar, dejar pasar tal cual (ver nota arriba).
  if (MAP_TILE_HOSTS.some(h => url.hostname === h || url.hostname.endsWith('.' + h))) {
    return;
  }

  // â”€â”€ ImÃ¡genes/fuentes CROSS-ORIGIN: NO interceptar â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // Mismo bug documentado arriba (teselas): un fetch() hecho DESDE el
  // Service Worker se evalÃºa contra la directiva "connect-src" del CSP,
  // no contra "img-src"/"font-src" (matiz de Chromium/WebView). Como
  // connect-src solo lista res.cloudinary.com, el SW bloqueaba el resto
  // de hosts de imÃ¡genes (i.ibb.co, i.postimg.cc, cdn.simpleicons.org,
  // wsrv.nl, unsplashâ€¦) en cuanto controlaba la pÃ¡gina â€” consistente en
  // mÃ³vil/WebView/APK. El fallo del fetch interno tampoco guardaba nada
  // en cachÃ© ("las imÃ¡genes no se muestran ni se cachean"). Se dejan
  // pasar directo al navegador: Ã©l las resuelve con img-src/font-src
  // (que sÃ­ permiten https: por esquema) y usa su cachÃ© HTTP nativa.
  // Solo las del MISMO origen se siguen cacheando (stale-while-revalidate)
  // para que la app funcione offline.
  if ((request.destination === 'image' || request.destination === 'font') &&
      url.origin !== self.location.origin) {
    return;
  }

  if (url.hostname.includes("onrender.com")) {
    // Datos del catÃ¡logo Open Source: stale-while-revalidate para que
    // el catÃ¡logo aguante offline (sirve copia en cachÃ© y refresca en
    // segundo plano; sin cachÃ© ni red devuelve un JSON claro).
    if (request.method === 'GET' &&
        (url.pathname === '/api/apps' || url.pathname === '/api/ratings')) {
      e.respondWith(staleWhileRevalidateApi(request));
      return;
    }
    e.respondWith(fetch(request.clone()).catch(() =>
      new Response(JSON.stringify({ error: 'Sin conexiÃ³n', offline: true }), {
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
  // BUG CORREGIDO: este catch-all (peticiones cross-origin sin extensiÃ³n
  // conocida) atrapaba de paso las teselas de Esri/ArcGIS (sin sufijo
  // .png en la URL) y las reenviaba con fetch() del SW â€” mismo problema
  // de connect-src explicado arriba. Ya no aplica: MAP_TILE_HOSTS se
  // filtra antes de llegar aquÃ­. Se excluyen ademÃ¡s por tipo (destination
  // 'image') como refuerzo, por si algÃºn proveedor de teselas futuro no
  // estÃ¡ en la lista.
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
  // HTML/CSS/JS: network-first â€” asÃ­ los pushes a GitHub/Vercel se ven
  // al instante. Antes esto era cache-first y podÃ­a quedar "pegado" a
  // una versiÃ³n vieja indefinidamente.
  if (['style', 'script'].includes(request.destination) ||
      url.pathname.match(/\.(html|css|js)$/)) {
    e.respondWith(networkFirst(request));
    return;
  }
  // ImÃ¡genes/fuentes del MISMO origen: stale-while-revalidate â€” rÃ¡pido
  // y se refresca solo (las cross-origin se dejan pasar: ver arriba).
  if (['image', 'font'].includes(request.destination)) {
    e.respondWith(staleWhileRevalidate(request));
    return;
  }
  e.respondWith(
    fetch(request).catch(() => caches.match(request).then(cached => cached || Response.error()))
  );
});

// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
//  PUSH â€” recibir notificaciÃ³n del servidor
// â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
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
      { action: 'view',    title: 'â¬‡ï¸ Ver app' },
      { action: 'dismiss', title: 'Cerrar' },
    ];
    options.data.url = '/opensource';
  } else if (type === 'weather') {
    options.tag      = 'codehub-weather';
    options.renotify = false;
    options.actions  = [{ action: 'open', title: 'ðŸŒ¤ Ver clima' }];
    options.data.url = url || '/index.html#weather-section';
  } else if (type === 'release') {
    options.tag      = 'codehub-release';
    options.renotify = true;
    options.actions  = [
      { action: 'view',    title: 'ðŸ”Ž Ver novedad' },
      { action: 'dismiss', title: 'Cerrar' },
    ];
    options.data.url = url || '/';
  } else if (type === 'seismic') {
    options.tag      = 'codehub-seismic';
    options.renotify = true;
    options.vibrate  = [120, 60, 120, 60, 240];
    options.actions  = [
      { action: 'open_map', title: 'ðŸ—º Ver en el mapa' },
      { action: 'dismiss',  title: 'Cerrar' },
    ];
    options.data.url = url || '/#sismos-section';
  } else {
    options.tag = 'codehub-general';
  }

  if (Notification.permission !== 'granted') return;

  e.waitUntil(self.registration.showNotification(title || 'CodeHub', options));

  // Reenviar a las pÃ¡ginas abiertas para que el Centro de
  // Notificaciones (js/notifications.js) las muestre tambiÃ©n.
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
  // Guardar en el historial para el panel (aunque la app estÃ© cerrada)
  e.waitUntil(pushToNotifStore({ title, body, type, url, icon }));
});

// â”€â”€ NOTIFICATION CLICK â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
self.addEventListener('notificationclick', e => {
  e.notification.close();
  if (e.action === 'dismiss') return;
  const targetUrl = e.action === 'open_map' ? '/#sismos-section' : (e.notification.data?.url || '/opensource');

  e.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(clients => {
      const existing = clients.find(c => c.url.includes(self.location.origin));
      if (existing) { existing.focus(); existing.navigate(targetUrl).catch(() => {}); return; }
      return self.clients.openWindow(targetUrl).catch(() => {});
    }).catch(() => {})
  );
});

// â”€â”€ MESSAGE â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
self.addEventListener('message', e => {
  if (e.data?.type === 'SKIP_WAITING') self.skipWaiting();
  if (e.data?.type === 'GET_VERSION')  e.ports[0]?.postMessage({ version: VERSION });
  if (e.data?.type === 'CHECK_UPDATE') self.registration.update().catch(() => {});

  // La app instalada pide sincronizaciÃ³n (permisos de internet + sync)
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

  // Vaciar el historial de notificaciones (usuario limpiÃ³ el panel)
  if (e.data?.type === 'CLEAR_NOTIFS') {
    writeNotifStore([]).then(() => e.ports[0]?.postMessage({ cleared: true })).catch(() => {});
  }

  // Push local (clima en tiempo real desde la pÃ¡gina)
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
    // TambiÃ©n al Centro de Notificaciones de la pÃ¡gina abierta
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

// â”€â”€ BACKGROUND SYNC â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Con la app instalada y permiso de notificaciones otorgado, el
// navegador despierta el SW cuando recupera conexiÃ³n ("sincronizaciÃ³n
// de internet") y cuando hay una sincronizaciÃ³n periÃ³dica. AquÃ­
// re-chequeamos novedades (CodeHub Releases) y clima para notificar
// en tiempo real aunque la app estÃ© cerrada.
const SYNC_TAG   = 'codehub-sync';
const PERIODIC_TAG = 'codehub-periodic-sync';
const SYNC_BACKEND = 'https://codehub-98s6.onrender.com';

// Revisa si hay releases recientes y, si el usuario no los ha visto,
// muestra la notificaciÃ³n y la guarda en el Centro de Notificaciones.
function syncCheckReleases() {
  return fetch(SYNC_BACKEND + '/api/releases', { headers: { 'Accept': 'application/json' } })
    .then(r => r.ok ? r.json() : { releases: [] })
    .then(data => {
      if (!data || !data.ok || !Array.isArray(data.releases) || !data.releases.length) return;
      const latest = data.releases[0];
      return readNotifStore().then(list => {
        const seen = list.some(n => n.url === latest.url || (n.body || '').includes(latest.title));
        if (seen) return;
        const title = latest.version ? ('ðŸš€ CodeHub ' + latest.version) : 'ðŸš€ CodeHub Release';
        const body = latest.title + (latest.body ? ' â€” ' + String(latest.body).slice(0, 110) : '');
        self.registration.showNotification(title, {
          body,
          icon: '/splash/codehub.png',
          badge: '/splash/codehub.png',
          tag: 'codehub-release',
          vibrate: [200, 100, 200],
          data: { url: latest.url || '/', type: 'release' },
          actions: [{ action: 'view', title: 'ðŸ”Ž Ver novedad' }],
        });
        pushToNotifStore({ title, body, type: 'release', url: latest.url || '/', icon: '/splash/codehub.png' });
        // Avisar a la pÃ¡gina abierta (panel en-app)
        self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(clients => {
          clients.forEach(c => c.postMessage({
            type: 'CH_PUSH', title, body, notifType: 'release', url: latest.url || '/', icon: '/splash/codehub.png',
          }));
        }).catch(() => {});
      });
    })
    .catch(() => {});
}

// Guarda el Ãºltimo estado de clima sincronizado para evitar spam
function syncWeatherLast() {
  return caches.open(NOTIF_CACHE).then(c => c.match('/ch-sync-wx')).then(r => r ? r.json() : {});
}
function syncWeatherSave(state) {
  return caches.open(NOTIF_CACHE).then(c =>
    c.put('/ch-sync-wx', new Response(JSON.stringify(state), { headers: { 'Content-Type': 'application/json' } }))
  ).catch(() => {});
}

// Chequeo de clima (misma lÃ³gica de alertas que el backend)
// Lee la ubicaciÃ³n guardada por el usuario en vez de usar coords hardcodeadas
function syncCheckWeather() {
  // First, try to read user's saved location from cache
  return caches.open(NOTIF_CACHE).then(c => c.match('/ch-user-loc')).then(r => {
    if (!r) return null;
    return r.json().catch(() => null);
  }).then(loc => {
    const lat = (loc && loc.lat) || null;
    const lon = (loc && loc.lon) || null;
    const city = (loc && loc.city) || '';
    if (!lat || !lon) return; // No location saved â€” skip weather check
    return fetch('https://api.open-meteo.com/v1/forecast?latitude=' + lat + '&longitude=' + lon + '&current=temperature_2m,weather_code,wind_speed_10m,precipitation&timezone=auto')
    .then(r => r.ok ? r.json() : null)
    .then(d => {
      if (!d || !d.current) return;
      const c = d.current;
      let msg = '';
      let cond = '';
      if (c.weather_code >= 95)            { msg = 'â›ˆï¸ Tormenta elÃ©ctrica en tu zona â€” evita zonas abiertas'; cond = 'storm'; }
      else if (c.weather_code >= 61 && c.weather_code <= 67) { msg = 'ðŸŒ§ï¸ Lluvia en tu zona â€” lleva paraguas'; cond = 'rain'; }
      else if (c.wind_speed_10m > 50)      { msg = 'ðŸ’¨ Viento fuerte (' + c.wind_speed_10m + ' km/h) â€” precauciÃ³n'; cond = 'wind'; }
      else if (c.temperature_2m > 33)      { msg = 'ðŸŒ¡ï¸ Calor extremo (' + c.temperature_2m + 'Â°C) â€” hidrÃ¡tate'; cond = 'heat'; }
      else if (c.temperature_2m < 0)       { msg = 'ðŸ¥¶ FrÃ­o intenso â€” abrÃ­gate bien'; cond = 'cold'; }
      if (!msg) return syncWeatherSave({ cond: null, ts: Date.now() });
      return syncWeatherLast().then(prev => {
        if (prev && prev.cond === cond && Date.now() - (prev.ts || 0) < 2 * 60 * 60 * 1000) return;
        self.registration.showNotification('CodeHub Clima', {
          body: msg + ' Â· ' + (city || 'tu zona'),
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
    // Avisar a las pÃ¡ginas abiertas para que vacÃ­en la cola de escrituras
    // offline (ratings, memoria de Wil.E) reciÃ©n recuperada la conexiÃ³n.
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(clients => {
      clients.forEach(c => c.postMessage({ type: 'CH_QUEUE_FLUSH' }));
    }).catch(() => {});
  }
});

self.addEventListener('periodicsync', e => {
  if (e.tag === PERIODIC_TAG) {
    e.waitUntil(Promise.allSettled([syncCheckReleases(), syncCheckWeather()]));
  }
});

