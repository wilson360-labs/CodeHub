// ============================================================
// CODEHUB — DETECCIÓN DE ACTUALIZACIÓN EN VIVO (sin versiones)
// ------------------------------------------------------------
// No compara números de versión que alguien tiene que recordar
// subir a mano. Compara el ETag / Last-Modified real que Vercel
// pone en cada deploy nuevo (cambia automáticamente cada vez que
// haces push a GitHub y Vercel termina de desplegar).
//
// Apenas detecta que ese valor cambió respecto al que tenía la
// pestaña abierta, aplica la actualización de inmediato:
// recarga la página (el sw.js ya sirve todo "network-first", así
// que después del reload todo queda al día automáticamente).
//
// Chequeo liviano (HEAD, sin descargar la página completa) cada
// pocos segundos + siempre que la pestaña vuelve a estar visible,
// que es cuando de verdad importa detectarlo rápido.
// ============================================================

(function () {
  const CHECK_URL      = '/index.html';
  const CHECK_EVERY_MS = 60000; // 60s: suficiente para detectar deploys, sin abusar de red

  let knownTag = null;
  let checking = false;

  async function fetchTag() {
    try {
      const res = await fetch(CHECK_URL, { method: 'HEAD', cache: 'no-store' });
      if (!res.ok) return null;
      return res.headers.get('etag') || res.headers.get('last-modified') || null;
    } catch (e) {
      return null; // sin red / offline: no hacemos nada, se reintenta luego
    }
  }

  function isUserTyping() {
    const el = document.activeElement;
    if (!el) return false;
    const tag = el.tagName;
    return tag === 'TEXTAREA' || (tag === 'INPUT' && el.value && el.value.length > 0);
  }

  function applyUpdateNow() {
    // Refresca también la caché del service worker antes de recargar.
    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.getRegistration().then(reg => reg && reg.update());
    }
    location.reload();
  }

  function scheduleUpdate() {
    // Si el usuario está escribiendo algo (ej. en el chat de EMI COPILOT
    // o el formulario de contacto), no le tiramos el trabajo a la mitad:
    // esperamos a que termine (blur) o, como tope, 20s.
    if (isUserTyping()) {
      const el = document.activeElement;
      let done = false;
      const finish = () => { if (!done) { done = true; applyUpdateNow(); } };
      el.addEventListener('blur', finish, { once: true });
      setTimeout(finish, 20000);
      return;
    }
    applyUpdateNow();
  }

  async function checkForRealUpdate() {
    if (checking) return;
    if (document.visibilityState === 'visible') { /* ok */ }
    else return;
    checking = true;
    const tag = await fetchTag();
    checking = false;
    if (!tag) return;

    if (knownTag === null) {
      knownTag = tag; // primera lectura: solo establece la línea base
      return;
    }
    if (tag !== knownTag) {
      knownTag = tag;
      console.log('%c🔄 Nueva versión detectada en el servidor — actualizando…', 'color:#00e5ff;font-weight:bold');
      scheduleUpdate();
    }
  }

  setInterval(checkForRealUpdate, CHECK_EVERY_MS);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') checkForRealUpdate();
  });
  window.addEventListener('online', checkForRealUpdate);

  // Línea base al cargar la página.
  checkForRealUpdate();
})();
