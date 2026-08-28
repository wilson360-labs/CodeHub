/* ═══════════════════════════════════════════════════════════
   CodeHub — Minimapa del clima (Leaflet + OpenStreetMap)
   - Carga Leaflet bajo demanda (lazy) solo al abrir el mapa.
   - Mapa con marcador arrastrable + búsqueda de ciudad (Nominatim).
   - Al elegir ciudad guarda ch_user_lat/lon/city localmente y
     sincroniza la suscripción push con el backend.
   ═══════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var _map = null;
  var _marker = null;
  var _selected = null; // { lat, lon, city }
  var _opened = false;
  var _loading = false;

  var LEAFLET_CSS = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css';
  var LEAFLET_JS  = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js';
  var LEAFLET_JS_B = 'https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js';

  window.chMapSearch = chMapSearch;
  window.chToggleMap = chToggleMap;
  window.chApplyMapCity = chApplyMapCity;

  function ensureLeaflet() {
    return new Promise(function (resolve, reject) {
      if (window.L) return resolve(L);
      if (!document.querySelector('link[data-wx-leaflet-css]')) {
        var link = document.createElement('link');
        link.rel = 'stylesheet';
        link.href = LEAFLET_CSS;
        link.setAttribute('data-wx-leaflet-css', '1');
        document.head.appendChild(link);
        link.addEventListener('load', function () { try { if (window.L) _map && _map.invalidateSize(); } catch (e) {} });
      }
      var done = false;
      function onOk() { if (!done) { done = true; resolve(L); } }
      function onErr() {
        if (done) return;
        // Intento 1 falló → probar CDN alternativo (jsdelivr)
        if (!window.L && s.src === LEAFLET_JS) {
          s.remove();
          var s2 = document.createElement('script');
          s2.src = LEAFLET_JS_B;
          s2.onload = onOk;
          s2.onerror = function () { done = true; reject(new Error('leaflet load failed')); };
          document.head.appendChild(s2);
          return;
        }
        done = true; reject(new Error('leaflet load failed'));
      }
      var s = document.createElement('script');
      s.src = LEAFLET_JS;
      s.onload = onOk;
      s.onerror = onErr;
      document.head.appendChild(s);
      setTimeout(function () {
        if (!done && !window.L) { done = true; reject(new Error('leaflet timeout')); }
      }, 15000);
    });
  }

  function initMap(initialLat, initialLon) {
    if (_map || _loading) return;
    _loading = true;
    ensureLeaflet().then(function () {
      _loading = false;
      var el = document.getElementById('wx-minimap');
      if (!el) return;
      el.innerHTML = '';
      var lat = initialLat || 14.6349;
      var lon = initialLon || -90.5069;
      _map = L.map('wx-minimap', { scrollWheelZoom: false, zoomControl: false }).setView([lat, lon], 11);
      L.control.zoom({ position: 'bottomright' }).addTo(_map);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OSM</a>',
      }).addTo(_map);

      var icon = L.divIcon({
        className: 'wx-map-marker',
        html: '<svg viewBox="0 0 24 24" width="34" height="34"><path fill="#e11d48" d="M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5A2.5 2.5 0 1 1 12 6.5a2.5 2.5 0 0 1 0 5z"/></svg>',
        iconSize: [34, 34],
        iconAnchor: [17, 34],
      });

      _marker = L.marker([lat, lon], { icon: icon, draggable: true }).addTo(_map);

      _map.on('click', function (e) {
        if (_marker) _marker.setLatLng(e.latlng);
        reverseGeocode(e.latlng.lat, e.latlng.lng);
      });
      _marker.on('dragend', function () {
        var p = _marker.getLatLng();
        reverseGeocode(p.lat, p.lng);
      });

      // Re-centrar en la ubicación guardada si la hay
      var saved = readSavedLoc();
      if (saved && !initialLat) {
        _map.setView([saved.lat, saved.lon], 11);
        _marker.setLatLng([saved.lat, saved.lon]);
        _selected = saved;
        updateCityLabel(saved.city);
      }

      // El mapa se abre en un contenedor "visible" pero fuera/abajo de la
      // pantalla; recalculamos las dimensiones varias veces para alinear
      // los tiles correctamente.
      setTimeout(function () { if (_map) _map.invalidateSize(); }, 120);
      setTimeout(function () { if (_map) _map.invalidateSize(); }, 500);
    }).catch(function () {
      _loading = false;
      var el = document.getElementById('wx-minimap');
      if (el) el.innerHTML = '<div class="wx-map-fail">No se pudo cargar el mapa.<br>Puedes seguir usando el buscador de ciudades.</div>';
    });
  }

  function openMap() {
    _opened = true;
    var box = document.getElementById('wx-map-box');
    if (box) box.classList.remove('closed');
    var caret = document.getElementById('wx-map-caret');
    if (caret) caret.className = 'fas fa-chevron-up wx-map-caret';
    initMap(null, null);
    if (_map) setTimeout(function () { _map.invalidateSize(); }, 60);
  }

  function closeMap() {
    _opened = false;
    var box = document.getElementById('wx-map-box');
    if (box) box.classList.add('closed');
    var caret = document.getElementById('wx-map-caret');
    if (caret) caret.className = 'fas fa-chevron-down wx-map-caret';
  }

  function chToggleMap() {
    if (_opened) closeMap();
    else openMap();
  }

  function readSavedLoc() {
    var lat = parseFloat(localStorage.getItem('ch_user_lat'));
    var lon = parseFloat(localStorage.getItem('ch_user_lon'));
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) return null;
    return { lat: lat, lon: lon, city: localStorage.getItem('ch_user_city') || '' };
  }

  function reverseGeocode(lat, lon) {
    fetch('https://nominatim.openstreetmap.org/reverse?lat=' + lat + '&lon=' + lon +
      '&format=json&accept-language=es&zoom=10')
      .then(function (r) { return r.json(); })
      .then(function (d) {
        var city = d.address && (d.address.city || d.address.town || d.address.village || d.address.county) || '';
        var state = (d.address && (d.address.state || '')) || '';
        _selected = { lat: lat, lon: lon, city: city || state || 'Ubicación seleccionada' };
        updateCityLabel(_selected.city);
      })
      .catch(function () {
        _selected = { lat: lat, lon: lon, city: 'Ubicación seleccionada' };
        updateCityLabel(_selected.city);
      });
  }

  function updateCityLabel(city) {
    var el = document.getElementById('wx-map-city');
    if (el) el.textContent = city;
  }

  function chMapSearch() {
    var input = document.getElementById('wx-map-search-input');
    if (!input) return;
    var q = input.value.trim();
    if (!q) return;
    if (!_map && !_loading) initMap(null, null);
    fetch('https://nominatim.openstreetmap.org/search?q=' + encodeURIComponent(q) +
      '&format=json&limit=5&accept-language=es&addressdetails=1')
      .then(function (r) { return r.json(); })
      .then(function (results) {
        if (!results || !results.length) {
          var lbl = document.getElementById('wx-map-city');
          if (lbl) lbl.textContent = 'No se encontró «' + q + '». Intenta con otro nombre.';
          return;
        }
        var first = results[0];
        var lat = parseFloat(first.lat);
        var lon = parseFloat(first.lon);
        var city = first.address && (first.address.city || first.address.town || first.address.village || first.address.state_district || first.address.state) || q;
        if (_map) _map.setView([lat, lon], 12);
        if (_marker) _marker.setLatLng([lat, lon]);
        _selected = { lat: lat, lon: lon, city: city };
        updateCityLabel(city);
        input.blur();
      })
      .catch(function () {
        var lbl = document.getElementById('wx-map-city');
        if (lbl) lbl.textContent = 'Error en la búsqueda. Intenta de nuevo.';
      });
  }

  function chApplyMapCity() {
    if (!_selected) {
      var lbl = document.getElementById('wx-map-city');
      if (lbl) lbl.textContent = 'Selecciona una ubicación en el mapa primero.';
      return;
    }
    var lat = _selected.lat, lon = _selected.lon, city = _selected.city;

    localStorage.setItem('ch_user_lat', lat);
    localStorage.setItem('ch_user_lon', lon);
    localStorage.setItem('ch_user_city', city);

    var label = city + ' 📍';
    if (typeof fetchWeatherByCoords === 'function') {
      fetchWeatherByCoords(lat, lon, label);
    }
    syncPushLocation(lat, lon, city);

    closeMap();
    if (typeof toast === 'function') toast('Ciudad guardada: ' + city, 'weather', 3000);
  }

  function syncPushLocation(lat, lon, city) {
    var loc = { lat: lat, lon: lon, city: city };
    if (window.CodeHubNative && CodeHubNative.saveLocation) {
      try { CodeHubNative.saveLocation(lat, lon, city, city); } catch (e) {}
    }
    var endpoint = localStorage.getItem('ch_push_endpoint');
    var alertsOn = localStorage.getItem('ch_weather_alerts') === '1';
    if (endpoint) {
      var body = { endpoint: endpoint, location: loc };
      if (alertsOn) body.prefs = { alerts: true };
      fetch((window.BACKEND_INDEX || 'https://codehub-98s6.onrender.com') + '/api/push/settings', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      }).catch(function () {});
    }
  }
})();
