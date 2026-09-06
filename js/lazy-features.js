// Lazy-load de scripts de datos pesados (mapa, clima, sismos, métricas).
// Se cargan tras la primera interacción o al rato de quedar idle la página,
// para no generar un "burst" de ejecución en el arranque de dispositivos
// lentos / APK. Los scripts usan IIFE inmediato o el guard de readyState,
// así que es seguro inyectarlos después del primer paint.
(function () {
  var FEATURES = [
    'js/weather-map.js?v=20260904sf',
    'js/ch-geo.js?v=20260905a',
    'js/sismos.js?v=20260905a',
    'js/sys-metrics.js?v=20260905b',
    'js/security-scan.js?v=20260905b',
    'widgets/weather/weather-widget.js?v=20260901b'
  ];
  var loaded = {};
  var done = false;

  function load() {
    FEATURES.forEach(function (src) {
      if (loaded[src]) return;
      loaded[src] = true;
      var s = document.createElement('script');
      s.src = src;
      s.async = true;
      document.body.appendChild(s);
    });
  }

  function trigger() {
    if (done) return;
    done = true;
    window.removeEventListener('pointerdown', trigger);
    window.removeEventListener('scroll', trigger);
    window.removeEventListener('keydown', trigger);
    if (document.readyState === 'complete') load();
    else window.addEventListener('load', load, { once: true });
  }

  window.addEventListener('pointerdown', trigger, { passive: true });
  window.addEventListener('scroll', trigger, { passive: true });
  window.addEventListener('keydown', trigger, { passive: true });

  // Respaldo idle: aunque no haya interacción, carga tras unos segundos.
  if ('requestIdleCallback' in window) requestIdleCallback(trigger, { timeout: 4000 });
  else setTimeout(trigger, 3500);
})();