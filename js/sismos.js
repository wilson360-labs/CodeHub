/* ============================================================
   CodeHub — Sección "Sismos / Terremotos"
   Basemap satélite estilo "Weather Channel" (Esri World_Imagery
   + capa de topónimos World_Boundaries_and_Places) + lista de
   eventos del día desde el backend (/api/sismos, proxy de USGS).
   Archivo independiente: no toca weather-map ni otras secciones.
   Leaflet se reutiliza de js/vendor/leaflet con el mismo patrón de
   carga (local primero, CDN de respaldo).
   ============================================================ */
(function () {
  'use strict';

  var BACKEND = (typeof _CH_BACKEND !== 'undefined' && _CH_BACKEND)
    ? _CH_BACKEND : 'https://codehub-98s6.onrender.com';
  var LEAFLET_CSS = 'js/vendor/leaflet/leaflet.css';
  var LEAFLET_JS  = 'js/vendor/leaflet/leaflet.js';
  var LEAFLET_CSS_B = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css';
  var LEAFLET_JS_B = 'https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js';

  var _map = null;
  var _markers = [];
  var _quakes = [];
  var _currentMag = 4;
  var _center = null; // {lat,lon} si el usuario usó "Mi zona"
  var _loaded = false;
  var _loading = false;

  // ---- Utilidades -------------------------------------------------
  function nowAge(ts) {
    if (!ts) return '';
    var mins = Math.floor((Date.now() - ts) / 60000);
    if (mins < 1) return 'hace un momento';
    if (mins < 60) return 'hace ' + mins + ' min';
    var h = Math.floor(mins / 60);
    if (h < 24) return 'hace ' + h + ' h';
    var d = Math.floor(h / 24);
    return (d === 1 ? 'hace 1 día' : 'hace ' + d + ' días') + ' (' + new Date(ts).toLocaleString('es-ES', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) + ')';
  }

  function magColor(mag) {
    if (mag >= 6) return '#e11d48';
    if (mag >= 5) return '#f59e0b';
    if (mag >= 4) return '#38bdf8';
    return '#2f80ed';
  }

  function magLabel(mag) { return 'M' + mag.toFixed(1); }

  function esc(s) {
    return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  // ---- Carga diferida de Leaflet (idéntica al patrón de weather-map) -
  // Reutiliza las etiquetas que pueda haber cargado weather-map (mismo
  // vendor local o CDN), para no duplicar recursos.
  function ensureLeaflet() {
    return new Promise(function (resolve, reject) {
      if (window.L && window.L.map) return resolve(window.L);
      var done = false;
      var timer = setTimeout(function () { if (!done) { done = true; reject(new Error('leaflet timeout')); } }, 15000);
      function maybe() {
        if (done) return;
        var cssReady = document.querySelector('link[data-wx-leaflet-css], link[data-sismos-leaflet-css]');
        if (window.L && window.L.map && cssReady) {
          done = true; clearTimeout(timer); resolve(window.L);
        }
      }
      var existingCss = document.querySelector('link[data-wx-leaflet-css], link[data-sismos-leaflet-css]');
      if (!existingCss) {
        var css = document.createElement('link');
        css.rel = 'stylesheet'; css.href = LEAFLET_CSS; css.setAttribute('data-sismos-leaflet-css', '1');
        css.onerror = function () { css.href = LEAFLET_CSS_B; };
        document.head.appendChild(css);
      }
      var existingJs = document.querySelector('script[data-wx-leaflet-js], script[data-sismos-leaflet-js]');
      if (!existingJs) {
        var s = document.createElement('script');
        s.src = LEAFLET_JS; s.setAttribute('data-sismos-leaflet-js', '1');
        s.async = true;
        s.onload = maybe;
        s.onerror = function () {
          var s2 = document.createElement('script');
          s2.src = LEAFLET_JS_B; s2.setAttribute('data-sismos-leaflet-js', '1');
          s2.async = true; s2.onload = maybe; s2.onerror = function () {
            if (!done) { done = true; clearTimeout(timer); reject(new Error('leaflet load failed')); }
          };
          document.body.appendChild(s2);
        };
        document.body.appendChild(s);
      } else {
        maybe();
      }
      // Si weather-map ya tiene el script cargado pero aun no L, esperar
      // sin duplicar: verificar de nuevo cuando termine la hoja.
      setTimeout(maybe, 400);
      var intv = setInterval(function () { maybe(); if (done) clearInterval(intv); }, 600);
      setTimeout(function () { clearInterval(intv); }, 16000);
      return maybe;
    });
  }

  // ---- Mapa -------------------------------------------------------
  function buildMap() {
    var el = document.getElementById('sismos-map');
    if (!el) return;
    el.innerHTML = '';
    if (_map) { try { _map.remove(); } catch (e) {} _map = null; }

    var L = window.L;
    var lat = _center ? _center.lat : 14.6;   // Centro por defecto: Guatemala
    var lon = _center ? _center.lon : -90.5;

    _map = L.map('sismos-map', { scrollWheelZoom: false, zoomControl: true }).setView([lat, lon], _center ? 7 : 6);

    // Basemap "estilo Weather Channel": satélite Esri + capa de topónimos.
    // Ambos dominios ya están permitidos en el CSP de vercel.json
    // (https://*.arcgisonline.com).
    var sat = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
      maxZoom: 19,
      attribution: 'Tiles &copy; Esri &mdash; Source: Esri, Maxar, Earthstar Geographics',
    });
    var labels = L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}', {
      maxZoom: 19,
      attribution: '&copy; Esri &mdash; &copy; OpenStreetMap contributors',
    });
    sat.addTo(_map);
    labels.addTo(_map);

    // Respaldo si la red del usuario bloquea Esri: bajar a OSM.
    var badTiles = 0;
    function onTileError() {
      badTiles++;
      if (badTiles > 8 && _map && !_map._esriFallbackDone) {
        _map._esriFallbackDone = true;
        try {
          sat.remove();
          labels.remove();
          L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            subdomains: 'abc', maxZoom: 19,
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OSM</a>',
          }).addTo(_map);
        } catch (e) {}
      }
    }
    sat.on('tileerror', onTileError);
    labels.on('tileerror', onTileError);

    // Leyenda de magnitud.
    var legend = L.control({ position: 'bottomleft' });
    legend.onAdd = function () {
      var d = L.DomUtil.create('div', 'sismos-legend');
      d.innerHTML = '<span><i style="background:#38bdf8"></i> M4–4.9</span>' +
        '<span><i style="background:#f59e0b"></i> M5–5.9</span>' +
        '<span><i style="background:#e11d48"></i> M6+</span>';
      return d;
    };
    legend.addTo(_map);

    plotQuakes();
    setTimeout(function () { try { _map.invalidateSize(); } catch (e) {} }, 120);
    if (window.ResizeObserver) {
      try {
        var ro = new ResizeObserver(function () { try { _map.invalidateSize(); } catch (e) {} });
        ro.observe(el);
      } catch (e) {}
    }
  }

  function magClass(mag) {
    if (mag >= 6) return 'sm-6';
    if (mag >= 5) return 'sm-5';
    if (mag >= 4) return 'sm-4';
    return 'sm-0';
  }

  function plotQuakes() {
    if (!_map) return;
    var L = window.L;
    _markers.forEach(function (m) { try { _map.removeLayer(m); } catch (e) {} });
    _markers = [];
    var filtered = _quakes.filter(function (q) { return q.mag >= _currentMag; });
    filtered.forEach(function (q) {
      var r = Math.max(7, 4 + q.mag * 2.4);
      var circle = L.circleMarker([q.lat, q.lon], {
        radius: r, color: magColor(q.mag), weight: 2,
        fillColor: magColor(q.mag), fillOpacity: 0.45,
      });
      var age = nowAge(q.time);
      circle.bindPopup('<b>' + magLabel(q.mag) + '</b> · ' + esc(q.place) +
        '<br><small>' + esc(age) + ' · ' + (q.depth != null ? Math.round(q.depth) : '?') + ' km</small>' +
        (q.url ? '<br><a href="' + esc(q.url) + '" target="_blank" rel="noopener">Detalle USGS</a>' : ''));
      circle.addTo(_map);
      _markers.push(circle);
    });
  }

  // ---- Lista ------------------------------------------------------
  function renderList() {
    var list = document.getElementById('sismos-list');
    if (!list) return;
    var filtered = _quakes.filter(function (q) { return q.mag >= _currentMag; });
    if (!filtered.length) {
      list.innerHTML = '<div class="sismos-none">Sin sismos ≥ M' + _currentMag + ' en las últimas 24 h.</div>';
      return;
    }
    list.innerHTML = filtered.map(function (q) {
      var cls = magClass(q.mag);
      return '<div class="sismos-item" onclick="chSismosFocus(' + q.lat + ',' + q.lon + ',' + (+q.mag) + ')">' +
        '<div class="sismos-badge ' + cls + '"><b>' + q.mag.toFixed(1) + '</b><span>MAG</span></div>' +
        '<div class="sismos-item-main">' +
        '<div class="sismos-item-place">' + esc(q.place || 'Lugar desconocido') + '</div>' +
        '<div class="sismos-item-meta">' + esc(nowAge(q.time)) +
        (q.depth != null ? ' · Prof. ' + Math.round(q.depth) + ' km' : '') + '</div>' +
        '</div></div>';
    }).join('') +
      '<div class="sismos-none" style="padding:.6rem">Toca un sismo para centrar el mapa' +
      (filtered.length > 1 ? ' · muestra de ' + filtered.length + ' eventos con magnitud ≥ ' + _currentMag : '') + '</div>';
  }

  function updateSummary() {
    var chip = document.getElementById('sismos-count-chip');
    if (!chip) return;
    var filtered = _quakes.filter(function (q) { return q.mag >= _currentMag; });
    chip.textContent = '🌋 ' + filtered.length + ' sismos ≥ M' + _currentMag + ' hoy (fuente: USGS)';
  }

  // ---- Carga de datos ---------------------------------------------
  function loadSismos(force) {
    if (_loading) return;
    if (_loaded && !force) { renderList(); updateSummary(); return; }
    _loading = true;
    var chip = document.getElementById('sismos-count-chip');
    if (chip) chip.textContent = 'Consultando actividad sísmica…';
    var list = document.getElementById('sismos-list');
    if (list) list.innerHTML = '<div class="sismos-none">Consultando actividad sísmica…</div>';

    fetch(BACKEND + '/api/sismos?minMag=0&limit=120')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        _quakes = (data && data.quakes) || [];
        _loaded = true;
        updateSummary();
        renderList();
        if (_map) plotQuakes();
      })
      .catch(function () {
        var l2 = document.getElementById('sismos-list');
        if (l2) l2.innerHTML = '<div class="sismos-none">No se pudo consultar la actividad sísmica. Revisa tu conexión.</div>';
        var chip2 = document.getElementById('sismos-count-chip');
        if (chip2) chip2.textContent = '⚠️ Error al consultar sismos';
      })
      .then(function () { _loading = false; });
  }

  // ---- Acciones públicas (usadas por el HTML inline) ---------------
  window.chSismosSetMag = function (mag, btn) {
    _currentMag = mag;
    var btns = document.querySelectorAll('.sismos-mag-btn');
    for (var i = 0; i < btns.length; i++) btns[i].classList.toggle('active', btns[i] === btn);
    updateSummary();
    renderList();
    if (_map) plotQuakes();
  };

  window.chSismosFocus = function (lat, lon, mag) {
    if (!_map) return;
    _map.setView([lat, lon], 8);
    var L = window.L;
    var circle = L.circleMarker([lat, lon], {
      radius: 10, color: '#fff', weight: 3,
      fillColor: magColor(mag), fillOpacity: 0.9,
    });
    circle.addTo(_map).bindPopup('<b>' + magLabel(mag) + '</b><br>Evento seleccionado').openPopup();
  };

  window.chSismosUseMyLocation = function () {
    if (!navigator.geolocation) {
      if (window.toast) toast('Tu navegador no permite geolocalización', 'sismos', 2500);
      return;
    }
    var btn = document.getElementById('sismos-loc-btn');
    if (btn) { btn.disabled = true; btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Localizando…'; }
    navigator.geolocation.getCurrentPosition(function (pos) {
      _center = { lat: pos.coords.latitude, lon: pos.coords.longitude };
      if (_map) {
        _map.setView([_center.lat, _center.lon], 8);
        var L = window.L;
        L.circleMarker([_center.lat, _center.lon], {
          radius: 6, color: '#22c55e', weight: 2, fillColor: '#22c55e', fillOpacity: 0.5,
        }).addTo(_map).bindPopup('<b>Tu ubicación</b>').openPopup();
        if (_map._sismosLegendColorChange) {}
      } else {
        buildMap();
      }
      if (btn) { btn.disabled = false; btn.innerHTML = '<i class="fas fa-location-dot"></i> Mi zona'; }
    }, function () {
      if (btn) { btn.disabled = false; btn.innerHTML = '<i class="fas fa-location-dot"></i> Mi zona'; }
      if (window.toast) toast('No se pudo obtener tu ubicación', 'sismos', 2500);
    }, { enableHighAccuracy: true, timeout: 10000 });
  };

  window.chSismosReload = function () {
    var btn = document.getElementById('sismos-refresh');
    if (btn) { btn.querySelector('i').classList.add('fa-spin'); }
    loadSismos(true);
    if (btn) { setTimeout(function () { var i = btn.querySelector('i'); if (i) i.classList.remove('fa-spin'); }, 800); }
  };

  // ---- Arranque: solo cuando la sección existe --------------------
  function init() {
    var section = document.getElementById('sismos-section');
    if (!section) return;
    ensureLeaflet().then(function () {
      buildMap();
      loadSismos(false);
    }).catch(function () {
      var el = document.getElementById('sismos-map');
      if (el) el.innerHTML = '<div class="wx-map-fail">No se pudo cargar el mapa de sismos.<br>La lista de eventos sigue disponible abajo.</div>';
      loadSismos(false);
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();