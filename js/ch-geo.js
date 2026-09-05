/* ═══════════════════════════════════════════════════════════
   CHGeo — Motor geográfico unificado CodeHub (Clima + Sismos)
   Un solo motor de trabajo para las dos secciones "geo":
   - Ubicación		: lee la misma fuente (ch_user_* / chReadLocation)
   - Leaflet		: singleton compartido (sin duplicar vendor/CDN)
   - Cielo día/noche	: fase amanecer→día→atardecer→noche con CSS
   - Panel de Sol	: arco animado de salida/puesta (sin emojis)
   - Geo dock		: navegación común Clima ⇄ Sismos con scroll-spy
   Aditivo: las APIs existentes del clima y sismos quedan intactas.
   ═══════════════════════════════════════════════════════════ */
(function (win) {
  'use strict';

  var BACKEND = (win._CH_BACKEND) ? win._CH_BACKEND : 'https://codehub-98s6.onrender.com';

  var LEAFLET_CSS   = 'js/vendor/leaflet/leaflet.css';
  var LEAFLET_JS    = 'js/vendor/leaflet/leaflet.js';
  var LEAFLET_CSS_B = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css';
  var LEAFLET_JS_B  = 'https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js';

  var _sun = null;            // { riseTs, setTs }
  var _posTimer = null;

  /* ── Helpers ─────────────────────────────────────────── */
  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  function readLocation() {
    if (typeof win.chReadLocation === 'function') {
      try {
        var l = win.chReadLocation();
        if (l && (l.lat != null || l.lon != null)) return l;
      } catch (e) {}
    }
    var lat = parseFloat(win.localStorage.getItem('ch_user_lat'));
    var lon = parseFloat(win.localStorage.getItem('ch_user_lon'));
    return {
      lat: Number.isFinite(lat) ? lat : null,
      lon: Number.isFinite(lon) ? lon : null,
      city: win.localStorage.getItem('ch_user_city') || '',
    };
  }

  function fetchJSON(url, opts) {
    return fetch(url, opts).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    });
  }

  /* ── Leaflet singleton (reutiliza lo que ya cargó weather-map) ── */
  function ensureLeaflet() {
    return new Promise(function (resolve, reject) {
      if (win.L && win.L.map) return resolve(win.L);
      var done = false;
      var timer = setTimeout(function () { if (!done) { done = true; reject(new Error('leaflet timeout')); } }, 15000);

      function cssReady() {
        return !!document.querySelector('link[data-wx-leaflet-css], link[data-chgeo-leaflet-css], link[data-sismos-leaflet-css]');
      }
      function maybe() {
        if (done) return;
        if (win.L && win.L.map && cssReady()) { done = true; clearTimeout(timer); resolve(win.L); }
      }

      if (!cssReady()) {
        var css = document.createElement('link');
        css.rel = 'stylesheet';
        css.href = LEAFLET_CSS;
        css.setAttribute('data-chgeo-leaflet-css', '1');
        css.onerror = function () { css.href = LEAFLET_CSS_B; };
        document.head.appendChild(css);
      }
      if (!document.querySelector('script[data-wx-leaflet-js], script[data-chgeo-leaflet-js], script[data-sismos-leaflet-js]')) {
        var s = document.createElement('script');
        s.src = LEAFLET_JS;
        s.setAttribute('data-chgeo-leaflet-js', '1');
        s.async = true;
        s.onload = maybe;
        s.onerror = function () {
          var s2 = document.createElement('script');
          s2.src = LEAFLET_JS_B;
          s2.setAttribute('data-chgeo-leaflet-js', '1');
          s2.async = true;
          s2.onload = maybe;
          s2.onerror = function () {
            if (!done) { done = true; clearTimeout(timer); reject(new Error('leaflet load failed')); }
          };
          document.body.appendChild(s2);
        };
        document.body.appendChild(s);
      }
      setTimeout(maybe, 400);
      var iv = setInterval(function () { maybe(); if (done) clearInterval(iv); }, 600);
      setTimeout(function () { clearInterval(iv); }, 16000);
    });
  }

  /* ── Cielo: fases día/noche + reloj solar ───────────────── */
  // Ventana de transición amanecer/atardecer (~45 min antes/después).
  var TWILIGHT = 45 * 60 * 1000;

  function phaseFor(date, riseTs, setTs) {
    var t = date.getTime();
    if (riseTs == null || setTs == null || !isFinite(riseTs) || !isFinite(setTs)) {
      // Sin datos de sol → estimar por hora local (6h–18h día).
      var h = date.getHours() + date.getMinutes() / 60;
      if (h < 6 || h >= 18) return 'night';
      if (h < 7.5) return 'dawn';
      if (h < 16.5) return 'day';
      return 'dusk';
    }
    if (t < riseTs - TWILIGHT) return 'night';
    if (t < riseTs) return 'dawn';
    if (t < setTs) return 'day';
    if (t < setTs + TWILIGHT) return 'dusk';
    return 'night';
  }

  function sunProgress(date, riseTs, setTs) {
    if (setTs <= riseTs) return 0.5;
    var t = Math.max(riseTs, Math.min(setTs, date.getTime()));
    return (t - riseTs) / (setTs - riseTs);
  }

  function applySky(phase) {
    var ids = ['weather-section', 'sismos-section'];
    for (var i = 0; i < ids.length; i++) {
      var el = document.getElementById(ids[i]);
      if (el) el.setAttribute('data-sky', phase);
    }
    document.documentElement.setAttribute('data-sky', phase);
  }

  function fmtClock(ts) {
    return new Date(ts).toLocaleTimeString('es-GT', { hour: '2-digit', minute: '2-digit', hour12: false });
  }

  function updateSunPos() {
    if (!_sun) return;
    var el = document.getElementById('wx-sun');
    if (!el || _sun.riseTs == null || _sun.setTs == null) return;
    var now = new Date();
    var skyEl = el.querySelector('.wx-sun-sky');
    var phase = phaseFor(now, _sun.riseTs, _sun.setTs);
    if (skyEl) skyEl.setAttribute('data-sky', phase);
    applySky(phase);

    // Sol viaja por el arco: izquierda(horizonte salida) → cenit → derecha(puesta).
    var prog = sunProgress(now, _sun.riseTs, _sun.setTs);
    var a = (prog * 180 - 90) * Math.PI / 180;
    var x = 50 + 50 * Math.sin(a);
    var y = 100 - (1 - Math.cos(a)) * 55;

    var disc = el.querySelector('.wx-sun-disc');
    if (disc) {
      disc.style.setProperty('--sun-x', x.toFixed(2) + '%');
      disc.style.setProperty('--sun-y', y.toFixed(2) + '%');
    }
    var moon = el.querySelector('.wx-moon-disc');
    if (moon) {
      moon.style.setProperty('--moon-x', (100 - x).toFixed(2) + '%');
      moon.style.setProperty('--moon-y', y.toFixed(2) + '%');
    }
  }

  /* Renderiza el panel de Sol (amarran los datos de clima al motor). */
  function renderSun(riseTs, setTs) {
    _sun = { riseTs: riseTs, setTs: setTs };
    var el = document.getElementById('wx-sun');
    if (!el) {
      applySky(phaseFor(new Date(), riseTs, setTs));
      return;
    }
    if (riseTs == null || setTs == null || !isFinite(riseTs) || !isFinite(setTs)) {
      el.hidden = true;
      applySky(phaseFor(new Date(), null, null));
      return;
    }
    el.hidden = false;
    var riseEl = document.getElementById('wx-sunrise-t');
    var setEl = document.getElementById('wx-sunset-t');
    var durEl = document.getElementById('wx-sun-dur');
    if (riseEl) riseEl.textContent = fmtClock(riseTs);
    if (setEl) setEl.textContent = fmtClock(setTs);
    if (durEl) {
      var mins = Math.round((setTs - riseTs) / 60000);
      durEl.textContent = (Math.floor(mins / 60)) + 'h ' + (mins % 60) + 'm de luz';
    }
    updateSunPos();
  }

  function startPosTicker() {
    if (_posTimer) clearInterval(_posTimer);
    _posTimer = setInterval(updateSunPos, 30000);
  }

  /* ── Geo dock: navegación común Clima ⇄ Sismos ───────────── */
  var GEO_SECTIONS = [
    { id: 'weather-section', icon: 'fa-cloud-sun', label: 'Clima' },
    { id: 'sismos-section',  icon: 'fa-house-crack', label: 'Sismos' },
  ];

  function dockHTML(activeId) {
    return '<div class="geo-dock" role="tablist" aria-label="Centro geográfico">' +
      GEO_SECTIONS.map(function (s) {
        return '<button type="button" class="geo-dock-btn' + (s.id === activeId ? ' active' : '') + '" ' +
          'data-geo="' + s.id + '" onclick="CHGeo.goToGeo(\'' + s.id + '\')" role="tab">' +
          '<i class="fas ' + s.icon + '"></i> ' + s.label + '</button>';
      }).join('') +
      '</div>';
  }

  function injectDocks() {
    GEO_SECTIONS.forEach(function (s) {
      var section = document.getElementById(s.id);
      if (!section) return;
      if (section.querySelector('.geo-dock')) return;
      var hdr = section.querySelector('.wx-header');
      if (!hdr) return;
      hdr.insertAdjacentHTML('afterend', dockHTML(s.id));
    });
  }

  function goToGeo(id) {
    var el = document.getElementById(id);
    if (!el) return;
    el.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  function initGeoDock() {
    injectDocks();
    // Si el clima está desactivado en config remota, el botón "Clima"
    // del dock apuntaría a una sección oculta → se elimina.
    ready().then(function () {
      if (feature('weatherEnabled') === false) {
        var btns = document.querySelectorAll('.geo-dock-btn[data-geo="weather-section"]');
        for (var i = 0; i < btns.length; i++) btns[i].style.display = 'none';
      }
    });
    if (win.IntersectionObserver) {
      // Un solo scroll-spy: marca el botón de la sección geo visible.
      (function () {
        var activeId = null;
        var obs = new IntersectionObserver(function (entries) {
          entries.forEach(function (en) {
            if (!en.isIntersecting || activeId === en.target.id) return;
            activeId = en.target.id;
            var btns = document.querySelectorAll('.geo-dock-btn');
            for (var i = 0; i < btns.length; i++) {
              btns[i].classList.toggle('active', btns[i].getAttribute('data-geo') === activeId);
            }
          });
        }, { threshold: 0.25 });
        GEO_SECTIONS.forEach(function (s) {
          var el = document.getElementById(s.id);
          if (el) obs.observe(el);
        });
      })();
    }
  }

  /* ── Config remota (para no duplicar RC.ready en cada módulo) ── */
  function ready() {
    return (win.RC && win.RC.ready) ? win.RC.ready() : Promise.resolve(win.RC || {});
  }

  function feature(name, dflt) {
    return (win.RC && win.RC.feature) ? win.RC.feature(name) : dflt;
  }

  /* ── API pública ─────────────────────────────────────────── */
  win.CHGeo = {
    backend: BACKEND,
    esc: esc,
    readLocation: readLocation,
    fetchJSON: fetchJSON,
    ensureLeaflet: ensureLeaflet,
    renderSun: renderSun,
    phaseFor: phaseFor,
    sunProgress: sunProgress,
    applySky: applySky,
    goToGeo: goToGeo,
    ready: ready,
    feature: feature,
    get sun() { return _sun; },
  };

  /* ── Init ── */
  function boot() {
    initGeoDock();
    startPosTicker();
    // Estado de cielo inicial (sin datos de sol → estimado por hora).
    applySky(phaseFor(new Date(), null, null));
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();

})(window);