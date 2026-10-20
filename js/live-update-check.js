// ============================================================
// CODEHUB — DETECCIÓN DE ACTUALIZACIÓN EN VIVO (silenciosa)
// ------------------------------------------------------------
// No compara números de versión que alguien tiene que recordar
// subir a mano. Compara el ETag / Last-Modified real que Vercel
// pone en cada deploy nuevo (cambia automáticamente cada vez que
// haces push a GitHub y Vercel termina de desplegar).
//
// POLÍTICA: la web NO muestra diálogos de actualización. Al
// detectar un deploy nuevo SOLO pide al Service Worker que revise
// (reg.update()); el registro del SW en index.html ya hace
// skipWaiting y recarga sola la pestaña. El usuario simplemente
// nota el contenido fresco, sin interrupciones.
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

  function applySilently() {
    // Actualización silenciosa: sin diálogos en la web. El registro
    // del SW (index.html) detección de reg.waiting → SKIP_WAITING →
    // controllerchange → reload, es quien completa el cambio.
    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.getRegistration()
        .then(reg => { if (reg) return reg.update(); return null; })
        .catch(() => {});
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
      applySilently();
    }
  }

  setInterval(checkForRealUpdate, CHECK_EVERY_MS);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') checkForRealUpdate();
  });
  window.addEventListener('online', checkForRealUpdate);

  checkForRealUpdate();
})();
