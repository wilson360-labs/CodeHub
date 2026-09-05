/* ════════════════════════════════════════════════════════════════════
   haptics.js — Feedback háptico (Vibration API)
   CodeHub by Wilson.E
   Patrones (ms) en tono sutil, sin aturdir al usuario:
     Haptics.tab()  → pulso casi imperceptible (12ms)   [tabs, ítems]
     Haptics.game() → doble clic rápido  (8-30-8)       [minijuegos]
     Haptics.warn() → pulsación larga sutil (35-60-35)  [avisos/riesgo]
   Guardas de batería:
     - no hay navigator.vibrate  → no-op
     - página oculta (background) → no-op
     - throttle de 40ms entre patrón y patrón
     - opt-out global persistente (localStorage codehub-haptics)
   Uso declarativo: <el data-haptic="tab|game|warn"> se vibra solo en
   clic. Uso programático: Haptics.game() desde juegos o efectos.
   ════════════════════════════════════════════════════════════════════ */
(() => {
  'use strict';
  var SUPPORTED = typeof navigator !== 'undefined' && 'vibrate' in navigator;
  var MIN_GAP = 40;          /* ms mínimos entre vibraciones */
  var STORE = 'codehub-haptics';
  var enabled = true;
  var last = 0;

  try { enabled = localStorage.getItem(STORE) !== '0'; } catch (e) {}

  function fire(pattern) {
    if (!SUPPORTED || !enabled) return;
    if (document.hidden || document.visibilityState !== 'visible') return; /* batería */
    var now = performance.now();
    if (now - last < MIN_GAP) return;
    last = now;
    try { navigator.vibrate(pattern); } catch (e) {}
  }

  var Haptics = {
    isEnabled: function () { return enabled; },
    setEnabled: function (on) {
      enabled = !!on;
      try { localStorage.setItem(STORE, enabled ? '1' : '0'); } catch (e) {}
    },
    tab:  function () { fire(12); },
    game: function () { fire([8, 30, 8]); },
    warn: function () { fire([35, 60, 35]); }
  };

  /* Delegación automática: [data-haptic] vibra al clic, sin importar
     dónde se asignó el handler (Zona Experimental, juegos, menús). */
  document.addEventListener('click', function (e) {
    var el = e.target && e.target.closest ? e.target.closest('[data-haptic]') : null;
    if (el) { var fn = Haptics[el.getAttribute('data-haptic')]; if (typeof fn === 'function') fn(); }
  }, true);

  Object.defineProperty(window, 'Haptics', {
    value: Object.freeze(Haptics), writable: false, configurable: false
  });
})();