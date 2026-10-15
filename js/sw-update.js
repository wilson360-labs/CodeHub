/* ═══════════════════════════════════════════════════════════════════════
   sw-update.js — mantiene el Service Worker al día en TODAS las páginas
   CodeHub by Wilson.E

   Por qué existe: solo index.html registraba /sw.js y llamaba a reg.update().
   Si entrabas directo a /servicios, /tools, /opensource… (o desde el WebView
   del APK), nadie pedía la actualización y el SW podía quedarse sirviendo una
   versión vieja "pegada" indefinidamente: cambios desplegados que no se ven.

   Qué hace:
     - Registra /sw.js con updateViaCache:'none' → el navegador SIEMPRE
       revalida el propio sw.js (no lo sirve de la caché HTTP).
     - Busca actualización al cargar, al volver a primer plano y cada 60 s.
     - Cuando hay una versión nueva: SKIP_WAITING + recarga única (solo en
       actualizaciones reales, nunca en la primera instalación).
     - Expone window.chForceUpdate(): desregistra el SW, borra TODAS las
       cachés y recarga sin caché. También se dispara con la URL ?force=1
       (útil como deep link desde el APK).

   Si index.html ya trae su propio bloque (window.__chSwInline) aquí no
   duplicamos el manejador; solo añadimos el botón de reparación.
   ═══════════════════════════════════════════════════════════════════════ */
(() => {
  'use strict';

  if (!('serviceWorker' in navigator)) return;
  if (window.__chSwUpdate) return;
  window.__chSwUpdate = true;

  // ── Reparación manual: SW fuera + cachés borradas + recarga limpia ──
  async function forceUpdate() {
    try {
      const regs = await navigator.serviceWorker.getRegistrations();
      await Promise.all(regs.map(r => r.unregister()));
    } catch (e) {}
    try {
      if (window.caches) {
        const keys = await caches.keys();
        await Promise.all(keys.map(k => caches.delete(k)));
      }
    } catch (e) {}
    try {
      const u = new URL(location.href);
      u.searchParams.delete('force');
      u.searchParams.delete('force_update');
      u.searchParams.set('_r', String(Date.now()));
      location.replace(u.toString());
    } catch (e) {
      location.reload();
    }
  }
  window.chForceUpdate = forceUpdate;

  // ?force=1 → repara y sale (sin dejar el parámetro, para no entrar en bucle).
  try {
    const p = new URLSearchParams(location.search);
    if (p.get('force') === '1' || p.get('force_update') === '1') {
      forceUpdate();
      return;
    }
  } catch (e) {}

  // index.html ya gestiona el registro/recarga por su cuenta.
  if (window.__chSwInline) return;

  let refreshing = false;
  const hadController = !!navigator.serviceWorker.controller;

  navigator.serviceWorker.register('/sw.js', { updateViaCache: 'none' })
    .then(reg => {
      navigator.serviceWorker.addEventListener('controllerchange', () => {
        // Solo recargamos si YA había un SW controlando: es una actualización
        // real, no la primera instalación (evita un refresco en la 1ª visita).
        if (refreshing || !hadController) return;
        refreshing = true;
        location.reload();
      });

      function activateWaiting() {
        if (reg.waiting) reg.waiting.postMessage({ type: 'SKIP_WAITING' });
      }

      if (reg.waiting) activateWaiting();

      reg.addEventListener('updatefound', () => {
        const sw = reg.installing;
        if (!sw) return;
        sw.addEventListener('statechange', () => {
          if (sw.state === 'installed' && navigator.serviceWorker.controller) activateWaiting();
        });
      });

      const check = () => reg.update().catch(() => {});
      check();
      setInterval(check, 60 * 1000);
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'visible') check();
      });
    })
    .catch(() => {});
})();
