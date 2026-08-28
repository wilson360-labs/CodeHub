/* ═══════════════════════════════════════════════════════════
   CodeHub — Minimapa del clima (Leaflet + OpenStreetMap)
   - Carga Leaflet bajo demanda (lazy) desde CDN.
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

  var LEAFLET_CSS = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css';
  var LEAFLET_JS  = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js';

  window.chMapSearch = chMapSearch;
  window.chToggleMap = chToggleMap;
  window.chApplyMapCity = chApplyMapCity;

  function ensureLeaflet() {
    return new Promise(function (resolve, reject) {
      if (window.L) return resolve(L);
      // CSS
      if (!document.querySelector('link[data-wx-leaflet-css]')) {
        var link = document.createElement('link');
        link.rel = 'stylesheet';
        link.href = LEAFLET_CSS;
        link.setAttribute('data-wx-leaflet-css', '1');
        document.head.appendChild(link);
      }
      // JS
      var s = document.createElement('script');
      s.src = LEAFLET_JS;
      s.onload = function () { resolve(L); };
      s.onerror = reject;
      document.head.appendChild(s);
    });
  }

  function initMap(initialLat, initialLon) {
    if (_map) return;
    ensureLeaflet().then(function (L) {
      var el = document.getElementById('wx-minimap');
      if (!el) return;
      el.innerHTML = '';
      var lat = initialLat || 14.6349;
      var lon = initialLon || -90.5069;
      _map = L.map('wx-minimap', { scrollWheelZoom: false }).setView([lat, lon], 11);
      L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; OpenStreetMap',
      }).addTo(_map);

      var icon = L.divIcon({
        className: 'wx-map-marker',
        html: '<svg viewBox="0 0 24 24" width="32" height="32"><path fill="#e11d48" d="M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5A2.5 2.5 0 1 1 12 6.5a2.5 2.5 0 0 1 0 5z"/></svg>',
        iconSize: [32, 32],
        iconAnchor: [16, 32],
      });

      _marker = L.marker([lat, lon], { icon: icon, draggable: true }).addTo(_map);

      // Click en el mapa: mover marcador + guardar
      _map.on('click', function (e) {
        if (_marker) _marker.setLatLng(e.latlng);
        reverseGeocode(e.latlng.lat, e.latlng.lng);
      });
      _marker.on('dragend', function (e) {
        var p = _marker.getLatLng();
        reverseGeocode(p.lat, p.lng);
      });

      // Ajustar tamaño tras el render inicial (la sección de clima suele
      // estar fuera de pantalla) para que los tiles se alineen bien.
      setTimeout(function () { if (_map) _map.invalidateSize(); }, 250);
      setTimeout(function () { if (_map) _map.invalidateSize(); }, 800);

      // Recentrar al abrir si ya hay ubicación guardada
      var saved = readSavedLoc();
      if (saved && !initialLat) {
        _map.setView([saved.lat, saved.lon], 11);
        if (_marker) _marker.setLatLng([saved.lat, saved.lon]);
        _selected = saved;
        updateCityLabel(saved.city);
      }
    }).catch(function () {
      var el = document.getElementById('wx-minimap');
      if (el) el.innerHTML = '<div style="padding:1rem;color:var(--muted);font-size:.8rem;text-align:center">No se pudo cargar el mapa (revisa tu conexión).</div>';
    });
  }

  function openMap() {
    _opened = true;
    var box = document.getElementById('wx-map-box');
    if (box) box.classList.remove('closed');
    var btn = document.getElementById('wx-map-collapse');
    if (btn) btn.innerHTML = '<i class="fas fa-chevron-down"></i>';
    var saved = readSavedLoc();
    initMap(saved ? saved.lat : null, saved ? saved.lon : null);
    // Ajustar tamaño del mapa tras mostrarse
    setTimeout(function () { if (_map) _map.invalidateSize(); }, 180);
  }

  function closeMap() {
    _opened = false;
    var box = document.getElementById('wx-map-box');
    if (box) box.classList.add('closed');
    var btn = document.getElementById('wx-map-collapse');
    if (btn) btn.innerHTML = '<i class="fas fa-chevron-up"></i>';
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

  // Reverse geocoding + guardar selección (sin disparar push aún)
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

  // Búsqueda con Nominatim (limita a ciudades)
  function chMapSearch() {
    var input = document.getElementById('wx-map-search-input');
    if (!input) return;
    var q = input.value.trim();
    if (!q) return;
    if (!_map && !window.L) initMap(null, null);
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
        var city = first.display_name ? first.display_name.split(',').slice(0, 2).join(',') : q;
        if (_map) { _map.setView([lat, lon], 12); }
        if (_marker) { _marker.setLatLng([lat, lon]); }
        _selected = { lat: lat, lon: lon, city: city };
        updateCityLabel(city);
        // Limpiar resultados duplicados que podrían abarrotar
        input.blur();
      })
      .catch(function () {
        var lbl = document.getElementById('wx-map-city');
        if (lbl) lbl.textContent = 'Error en la búsqueda. Intenta de nuevo.';
      });
  }

  // Guardar ciudad elegida: local + widget + suscripción push
  function chApplyMapCity() {
    if (!_selected) {
      var lbl = document.getElementById('wx-map-city');
      if (lbl) lbl.textContent = 'Selecciona una ubicación en el mapa primero.';
      return;
    }
    var lat = _selected.lat, lon = _selected.lon, city = _selected.city;

    // 1) Guardar localmente
    localStorage.setItem('ch_user_lat', lat);
    localStorage.setItem('ch_user_lon', lon);
    localStorage.setItem('ch_user_city', city);

    // 2) Actualizar widget de clima
    var label = city + ' 📍';
    if (typeof fetchWeatherByCoords === 'function') {
      fetchWeatherByCoords(lat, lon, label);
    }

    // 3) Sincronizar suscripción push / FCM
    syncPushLocation(lat, lon, city);

    // Feedback
    closeMap();
    if (typeof toast === 'function') toast('Ciudad guardada: ' + city, 'weather', 3000);
  }

  function syncPushLocation(lat, lon, city) {
    var loc = { lat: lat, lon: lon, city: city };
    // FCM (Android nativo)
    if (window.CodeHubNative && CodeHubNative.saveLocation) {
      try { CodeHubNative.saveLocation(lat, lon, city, city); } catch (e) {}
    }
    // Web push
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

  // Inicializar el mapa al cargar (queda visible por defecto),
  // centrándolo en la ubicación guardada si existe.
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { initMap(null, null); });
  } else {
    initMap(null, null);
  }
})();
