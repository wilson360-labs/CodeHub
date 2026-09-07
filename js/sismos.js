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

  // ---- Delegación al motor CHGeo (si está disponible) ────────
  // Cuando ch-geo.js ya cargó, sismos reutiliza su singleton de Leaflet,
  // su ubicación, su esc y su fetch. Si CHGeo no existe (deploy viejo),
  // el módulo funciona por sí solo como antes (compatibilidad hacia atrás).
  var BACKEND = (window.CHGeo && window.CHGeo.backend)
    ? window.CHGeo.backend
    : ((typeof _CH_BACKEND !== 'undefined' && _CH_BACKEND)
        ? _CH_BACKEND : 'https://codehub-98s6.onrender.com');

  function esc(s) {
    return (window.CHGeo && CHGeo.esc) ? CHGeo.esc(s)
      : String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  // Cargadores locales de Leaflet (vendor primero, CDN de respaldo).
  // Se usan cuando CHGeo aún no terminó de cargar o no existe.
  var LEAFLET_CSS   = 'js/vendor/leaflet/leaflet.css';
  var LEAFLET_JS    = 'js/vendor/leaflet/leaflet.js';
  var LEAFLET_CSS_B = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css';
  var LEAFLET_JS_B  = 'https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js';

  function waitForLeaflet(resolve, reject, ms) {
    var start = Date.now();
    var t = setInterval(function () {
      if (window.L && window.L.map) { clearInterval(t); resolve(window.L); }
      else if (Date.now() - start > ms) { clearInterval(t); reject(new Error('leaflet timeout')); }
    }, 150);
  }

  // Carga local el JS (CSS se inyecta en paralelo) sin duplicar si ya lo
  // trajo weather-map o CHGeo; con fallback a CDN si falla la descarga.
  function loadLocalLeaflet() {
    return new Promise(function (resolve, reject) {
      if (!document.querySelector('link[data-wx-leaflet-css], link[data-chgeo-leaflet-css], link[data-sismos-leaflet-css]')) {
        var l = document.createElement('link');
        l.rel = 'stylesheet';
        l.href = LEAFLET_CSS;
        l.setAttribute('data-sismos-leaflet-css', '1');
        l.onerror = function () { l.href = LEAFLET_CSS_B; };
        document.head.appendChild(l);
      }
      // Ya hay un script de Leaflet (en curso o cargado): esperar a que L exista.
      if (document.querySelector('script[data-wx-leaflet-js], script[data-chgeo-leaflet-js], script[data-sismos-leaflet-js]')) {
        waitForLeaflet(resolve, reject, 15000);
        return;
      }
      var s = document.createElement('script');
      s.src = LEAFLET_JS;
      s.setAttribute('data-sismos-leaflet-js', '1');
      s.async = true;
      var resolved = false;
      s.onload = function () {
        if (resolved) return; resolved = true;
        if (window.L && window.L.map) resolve(window.L);
        else waitForLeaflet(resolve, reject, 3000);
      };
      s.onerror = function () {
        if (resolved) return; resolved = true;
        try { document.body.removeChild(s); } catch (e) {}
        var s2 = document.createElement('script');
        s2.src = LEAFLET_JS_B;
        s2.setAttribute('data-sismos-leaflet-js', '1');
        s2.async = true;
        s2.onload = function () {
          if (window.L && window.L.map) resolve(window.L);
          else waitForLeaflet(resolve, reject, 3000);
        };
        s2.onerror = function () { reject(new Error('leaflet load failed')); };
        document.body.appendChild(s2);
      };
      document.body.appendChild(s);
    });
  }

  // Leaflet puede llegar por 3 caminos distintos según el orden de carga
  // async de lazy-features (weather-map, ch-geo, sismos). Este ensure es
  // robusto a ese orden: 1) delega en CHGeo si ya está listo, 2) usa L si
  // ya existe, 3) espera 2.5s a CHGeo (suele tardar una fracción), y si no
  // aparece, carga Leaflet localmente con fallback CDN. Antes esto podía
  // fallar dejando el mapa con "No se pudo cargar el mapa de sismos".
  function ensureLeaflet() {
    return new Promise(function (resolve, reject) {
      var timer = setTimeout(function () { reject(new Error('leaflet timeout')); }, 15000);
      var settled = false;
      function ok() {
        if (settled) return; settled = true; clearTimeout(timer);
        resolve((window.L && window.L.map) ? window.L : null);
      }
      function fail(msg) {
        if (settled) return; settled = true; clearTimeout(timer);
        reject(new Error(msg || 'leaflet load failed'));
      }

      if (window.CHGeo && CHGeo.ensureLeaflet) {
        CHGeo.ensureLeaflet().then(ok, fail);
        return;
      }
      if (window.L && window.L.map) { ok(); return; }

      var waited = 0;
      var checker = setInterval(function () {
        waited += 200;
        if (window.L && window.L.map) { clearInterval(checker); ok(); }
        else if (window.CHGeo && CHGeo.ensureLeaflet) {
          clearInterval(checker);
          CHGeo.ensureLeaflet().then(ok, fail);
        } else if (waited >= 2500) {
          clearInterval(checker);
          loadLocalLeaflet().then(ok, fail);
        }
      }, 200);
    });
  }

  var _map = null;
  var _markers = [];
  var _quakes = [];
  var _currentMag = 4;
  var _placeFilter = ''; // país seleccionado en "lugares con sismos detectados" ('' = todos)
  var _center = null; // {lat,lon} si el usuario usó "Mi zona" o la ciudad de clima
  var _loaded = false;
  var _loading = false;

  // ---- Utilidades -------------------------------------------------
  // De "12 km S of Acajutla, El Salvador" → "El Salvador" (mismo criterio del backend).
  function placeCountry(place) {
    if (!place) return '';
    var parts = String(place).split(',').map(function (s) { return s.trim(); }).filter(Boolean);
    if (!parts.length) return '';
    var last = parts[parts.length - 1];
    if (/region|ridge|rise|plateau|trench|basin|ocean|mid-atlantic|off the (coast|shore)|northern|southern|eastern|western|central\b/i.test(last)) return '';
    return last;
  }

  function normCountry(s) {
    return String(s || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '')
      .toLowerCase().replace(/\./g, '').replace(/\s+/g, ' ').trim();
  }
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

  function matchesPlace(q) {
    if (!_placeFilter) return true;
    return normCountry(placeCountry(q.place)) === normCountry(_placeFilter);
  }

  // ---- "Lugares con sismos detectados" ----------------------------
  // Agrupa los eventos del filtro actual por país, pinta chips y expone
  // los toggles públicos usados por el HTML inline.
  function currentPlaces() {
    var map = {};
    _quakes.filter(function (q) { return q.mag >= _currentMag; }).forEach(function (q) {
      var c = placeCountry(q.place);
      if (!c) { c = 'Otras zonas'; }
      map[c] = (map[c] || 0) + 1;
    });
    var out = Object.keys(map).map(function (c) { return { country: c, count: map[c] }; });
    out.sort(function (a, b) { return b.count - a.count; });
    return out;
  }

  function renderPlaces() {
    var body = document.getElementById('sismos-places-body');
    var chips = document.getElementById('sismos-places-chips');
    var count = document.getElementById('sismos-places-count');
    if (!chips || !body) return;
    var places = currentPlaces();
    if (count) count.textContent = places.length ? places.length + ' lugares' : '—';
    var active = _placeFilter;
    var html = '<button type="button" class="sismos-place-chip' + (active ? '' : ' active') + '" onclick="chSetSismosPlace(\'\', this)"><i class="fas fa-globe"></i> Todos <b>' + _quakes.filter(function (q) { return q.mag >= _currentMag; }).length + '</b></button>';
    places.forEach(function (p) {
      var isActive = active && normCountry(p.country) === normCountry(active);
      html += '<button type="button" class="sismos-place-chip' + (isActive ? ' active' : '') + '" onclick="chSetSismosPlace(this.dataset.c, this)" data-c="' + esc(p.country) + '"><i class="fas fa-earth-americas"></i> ' + esc(p.country) + ' <b>' + p.count + '</b></button>';
    });
    chips.innerHTML = html;
  }

  window.chToggleSismosPlaces = function () {
    var box = document.getElementById('sismos-places');
    var head = document.getElementById('sismos-places-header');
    if (!box) return;
    var open = box.classList.toggle('open');
    if (head) head.setAttribute('aria-expanded', open ? 'true' : 'false');
  };

  window.chSetSismosPlace = function (country, btn) {
    var box = document.getElementById('sismos-places');
    if (box) box.classList.remove('open');
    var c = btn ? btn.getAttribute('data-c') : country;
    _placeFilter = c || '';
    var all = document.querySelectorAll('.sismos-place-chip');
    for (var i = 0; i < all.length; i++) {
      all[i].classList.toggle('active', all[i] === btn);
    }
    updateSummary();
    renderList();
    renderCarousel();
    if (_map) plotQuakes();
  };

  // ---- Mapa -------------------------------------------------------
  function buildMap() {
    var el = document.getElementById('sismos-map');
    if (!el) return;
    el.innerHTML = '';
    if (_map) { try { _map.remove(); } catch (e) {} _map = null; }

    var L = window.L;
    // Centro: 1) "Mi zona" elegida, 2) la ciudad del clima (mismo motor),
    // 3) Guatemala por defecto.
    var seed = _center;
    if (!seed && window.CHGeo) {
      var locHome = CHGeo.readLocation();
      if (locHome && Number.isFinite(locHome.lat) && Number.isFinite(locHome.lon)) {
        seed = { lat: locHome.lat, lon: locHome.lon };
      }
    }
    var lat = seed ? seed.lat : 14.6;
    var lon = seed ? seed.lon : -90.5;

    _map = L.map('sismos-map', { scrollWheelZoom: false, zoomControl: true }).setView([lat, lon], seed ? 7 : 6);

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
    var filtered = _quakes.filter(function (q) { return q.mag >= _currentMag && matchesPlace(q); });
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
    var filtered = _quakes.filter(function (q) { return q.mag >= _currentMag && matchesPlace(q); });
    if (!filtered.length) {
      list.innerHTML = '<div class="sismos-none">' + (_placeFilter ? 'Sin sismos ≥ M' + _currentMag + ' en ' + esc(_placeFilter) + ' en las últimas 24 h.' : 'Sin sismos ≥ M' + _currentMag + ' en las últimas 24 h.') + '</div>';
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
      (filtered.length > 1 ? ' · muestra de ' + filtered.length + ' eventos con magnitud ≥ ' + _currentMag + (_placeFilter ? ' en ' + _placeFilter : '') : '') + '</div>';
  }

  function updateSummary() {
    var chip = document.getElementById('sismos-count-chip');
    if (!chip) return;
    var filtered = _quakes.filter(function (q) { return q.mag >= _currentMag && matchesPlace(q); });
    var base = '🌋 ' + filtered.length + ' sismos ≥ M' + _currentMag + ' hoy (fuente: USGS)';
    if (_placeFilter) base = '🌋 ' + filtered.length + ' sismos en ' + _placeFilter + ' (fuente: USGS)';
    chip.textContent = base;
  }

  // ---- Carousel deslizante sobre el mapa (estilo Weather Channel) --
  // Refleja los mismos filtros que la lista: recorre los eventos con un
  // gesto lateral y al tocar una tarjeta centra el mapa en ese sismo.
  function renderCarousel() {
    var box = document.getElementById('sismos-carousel');
    if (!box) return;
    if (!_map) {
      box.innerHTML = '';
      return;
    }
    var filtered = _quakes.filter(function (q) { return q.mag >= _currentMag && matchesPlace(q); });
    if (!filtered.length) {
      box.innerHTML = '<div class="sismos-car-empty">Sin sismos ≥ M' + _currentMag +
        (_placeFilter ? ' en ' + esc(_placeFilter) : '') + ' en 24 h.</div>';
      return;
    }
    box.innerHTML = filtered.map(function (q) {
      var cls = magClass(q.mag);
      return '<button type="button" class="sismos-car-card" onclick="chSismosFocus(' + q.lat + ',' + q.lon + ',' + (+q.mag) + ')">' +
        '<span class="sismos-car-badge ' + cls + '"><b>' + q.mag.toFixed(1) + '</b><span>MAG</span></span>' +
        '<span class="sismos-car-main">' +
        '<span class="sismos-car-place">' + esc(q.place || 'Lugar desconocido') + '</span>' +
        '<span class="sismos-car-meta">' + esc(nowAge(q.time)) + (q.depth != null ? ' · ' + Math.round(q.depth) + ' km' : '') + '</span>' +
        '</span>' +
        '<span class="sismos-car-arrow"><i class="fas fa-angle-right"></i></span>' +
        '</button>';
    }).join('');
  }

  // ---- Carga de datos ---------------------------------------------
  function loadSismos(force) {
    if (_loading) return;
    if (_loaded && !force) { renderList(); renderCarousel(); updateSummary(); renderPlaces(); return; }
    _loading = true;
    var chip = document.getElementById('sismos-count-chip');
    if (chip) chip.textContent = 'Consultando actividad sísmica…';
    var list = document.getElementById('sismos-list');
    if (list) list.innerHTML = '<div class="sismos-none">Consultando actividad sísmica…</div>';
    var chips = document.getElementById('sismos-places-chips');
    if (chips) chips.innerHTML = '<span class="sismos-places-empty">Consultando lugares…</span>';

    fetch(BACKEND + '/api/sismos?minMag=0&limit=120')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        _quakes = (data && data.quakes) || [];
        _loaded = true;
        updateSummary();
        renderList();
        renderCarousel();
        renderPlaces();
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
    renderCarousel();
    renderPlaces();
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
      } else {
        buildMap();
        renderCarousel();
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
      renderCarousel();
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