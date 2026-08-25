// ============================================================
// CODEHUB — DETECCIÓN DE ACTUALIZACIÓN EN VIVO (sin versiones)
// ------------------------------------------------------------
// No compara números de versión que alguien tiene que recordar
// subir a mano. Compara el ETag / Last-Modified real que Vercel
// pone en cada deploy nuevo (cambia automáticamente cada vez que
// haces push a GitHub y Vercel termina de desplegar).
//
// Apenas detecta que ese valor cambió respecto al que tenía la
// pestaña abierta, muestra el diálogo de actualización con el
// changelog (en vez de recargar silenciosamente).
//
// Chequeo liviano (HEAD, sin descargar la página completa) cada
// pocos segundos + siempre que la pestaña vuelve a estar visible,
// que es cuando de verdad importa detectarlo rápido.
// ============================================================

(function () {
  const CHECK_URL      = '/index.html';
  const CHECK_EVERY_MS = 60000;

  let knownTag = null;
  let checking = false;

  async function fetchTag() {
    try {
      const res = await fetch(CHECK_URL, { method: 'HEAD', cache: 'no-store' });
      if (!res.ok) return null;
      return res.headers.get('etag') || res.headers.get('last-modified') || null;
    } catch (e) {
      return null;
    }
  }

  function isUserTyping() {
    const el = document.activeElement;
    if (!el) return false;
    const tag = el.tagName;
    return tag === 'TEXTAREA' || (tag === 'INPUT' && el.value && el.value.length > 0);
  }

  function showUpdateNotification() {
    // Si el usuario está escribiendo, esperar a que termine
    if (isUserTyping()) {
      const el = document.activeElement;
      let done = false;
      const finish = () => { if (!done) { done = true; doShow(); } };
      el.addEventListener('blur', finish, { once: true });
      setTimeout(finish, 20000);
      return;
    }
    doShow();
  }

  function doShow() {
    // Usar el nuevo diálogo con changelog si está disponible
    if (typeof window.chCheckAndUpdate === 'function') {
      window.chCheckAndUpdate(true);
    } else {
      // Fallback: recarga silenciosa si el diálogo aún no cargó
      if ('serviceWorker' in navigator) {
        navigator.serviceWorker.getRegistration().then(reg => reg && reg.update());
      }
      location.reload();
    }
  }

  async function checkForRealUpdate() {
    if (checking) return;
    if (document.visibilityState !== 'visible') return;
    checking = true;
    const tag = await fetchTag();
    checking = false;
    if (!tag) return;

    if (knownTag === null) {
      knownTag = tag;
      return;
    }
    if (tag !== knownTag) {
      knownTag = tag;
      console.log('%c🔄 Nueva versión detectada en el servidor — mostrando actualización…', 'color:#00e5ff;font-weight:bold');
      showUpdateNotification();
    }
  }

  setInterval(checkForRealUpdate, CHECK_EVERY_MS);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') checkForRealUpdate();
  });
  window.addEventListener('online', checkForRealUpdate);

  checkForRealUpdate();
})();
