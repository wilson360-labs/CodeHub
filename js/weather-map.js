/* ═══════════════════════════════════════════════════════════
   CodeHub — Minimapa del clima (Google Maps JS API + fallback Leaflet/OSM)
   - Carga el motor bajo demanda (lazy) solo al abrir el mapa.
   - Google Maps: marcador arrastrable + geocoder + reverse geocoder
     (sin key api en el repo: se lee de RC → /api/config → env GOOGLE_MAPS).
   - Fallback automático a Leaflet/OSM (búsqueda Nominatim) si no hay
     key o si la carga de Google falla/tarda demasiado.
   - Al elegir ciudad guarda ch_user_lat/lon/city localmente y
     sincroniza la suscripción push con el backend.
   ═══════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var _engine = null; // 'google' | 'leaflet'
  var _map = null;
  var _marker = null;
  var _selected = null; // { lat, lon, city }
  var _opened = false;
  var _loading = false;
  var _googleKey = null;
  var _googleFailed = false;

  var _googleMap = null;
  var _geocoder = null;

  // Leaflet autohospedado (offline/APK sin depender de CDN). Fallback CDN
  // solo si el archivo local no existe (p.ej. deploy sin el asset).
  var LEAFLET_CSS = 'js/vendor/leaflet/leaflet.css';
  var LEAFLET_JS  = 'js/vendor/leaflet/leaflet.js';
  var LEAFLET_CSS_B = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css';
  var LEAFLET_JS_B = 'https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js';

  window.chMapSearch = chMapSearch;
  window.chToggleMap = chToggleMap;
  window.chApplyMapCity = chApplyMapCity;

  // ── Key de Google Maps desde RC (backend /api/config, env GOOGLE_MAPS) ──
  if (window.RC && window.RC.ready) {
    window.RC.ready().then(function () {
      _googleKey = (window.RC.ui('googleMapsKey', '') || '').trim();
    });
  }

  function isGoogleMode() { return _engine === 'google'; }

  function ensureGoogle() {
    return new Promise(function (resolve, reject) {
      if (window.google && window.google.maps) return resolve(window.google.maps);
      var s = document.createElement('script');
      s.src = 'https://maps.googleapis.com/maps/api/js?key=' + encodeURIComponent(_googleKey) +
        '&language=es&region=GT&v=weekly';
      s.async = true;
      s.onload = function () { window.google && window.google.maps ? resolve(window.google.maps) : reject(new Error('google maps not available')); };
      s.onerror = function () { reject(new Error('google maps load failed')); };
      document.head.appendChild(s);
      setTimeout(function () {
        if (!window.google || !window.google.maps) reject(new Error('google maps timeout'));
      }, 15000);
    });
  }

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
        // Fallback CSS: si el local 404, cargar CDN (p.ej. deploy sin el asset)
        link.addEventListener('error', function () {
          var link2 = document.createElement('link');
          link2.rel = 'stylesheet';
          link2.href = LEAFLET_CSS_B;
          link2.setAttribute('data-wx-leaflet-css', '1');
          document.head.appendChild(link2);
        });
      }
      if (!document.querySelector('script[data-wx-leaflet-js]')) {
        var done = false;
        function onOk() { if (!done) { done = true; resolve(L); } }
        function onErr() {
          if (done) return;
          // Intento 1 falló → probar CDN alternativo
          if (!window.L && s.src !== LEAFLET_JS_B) {
            s.remove();
            var s2 = document.createElement('script');
            s2.src = LEAFLET_JS_B;
            s2.setAttribute('data-wx-leaflet-js', '1');
            s2.onload = onOk;
            s2.onerror = function () { done = true; reject(new Error('leaflet load failed')); };
            document.head.appendChild(s2);
            return;
          }
          done = true; reject(new Error('leaflet load failed'));
        }
        var s = document.createElement('script');
        s.src = LEAFLET_JS;
        s.setAttribute('data-wx-leaflet-js', '1');
        s.onload = onOk;
        s.onerror = onErr;
        document.head.appendChild(s);
        setTimeout(function () {
          if (!done && !window.L) { done = true; reject(new Error('leaflet timeout')); }
        }, 15000);
      } else {
        resolve(L);
      }
    });
  }

  function initMap(initialLat, initialLon) {
    if (_map || _loading) return;
    _loading = true;
    var boot = Promise.resolve();
    if (!_googleFailed && _googleKey) {
      boot = ensureGoogle().then(function () { _engine = 'google'; })
        .catch(function () { _googleFailed = true; _engine = null; return ensureLeaflet().then(function () { _engine = 'leaflet'; }); });
    } else {
      boot = ensureLeaflet().then(function () { _engine = 'leaflet'; });
    }
    boot.then(function () {
      _loading = false;
      var el = document.getElementById('wx-minimap');
      if (!el) return;
      el.innerHTML = '';
      var lat = initialLat || 14.6349;
      var lon = initialLon || -90.5069;
      if (isGoogleMode()) buildGoogleMap(el, lat, lon);
      else buildLeafletMap(el, lat, lon);

      // Re-centrar en la ubicación guardada si la hay
      var saved = readSavedLoc();
      if (saved && !initialLat) {
        if (isGoogleMode()) {
          _googleMap.setCenter({ lat: saved.lat, lng: saved.lon });
          _googleMap.setZoom(11);
          _marker.setPosition({ lat: saved.lat, lng: saved.lon });
        } else {
          _map.setView([saved.lat, saved.lon], 11);
          _marker.setLatLng([saved.lat, saved.lon]);
        }
        _selected = saved;
        updateCityLabel(saved.city);
      }
    }).catch(function () {
      _loading = false;
      var el = document.getElementById('wx-minimap');
      if (el) el.innerHTML = '<div class="wx-map-fail">No se pudo cargar el mapa.<br>Puedes seguir usando el buscador de ciudades.</div>';
    });
  }

  // ── Google Maps ──
  function buildGoogleMap(el, lat, lon) {
    var gm = window.google.maps;
    _googleMap = new gm.Map(el, {
      center: { lat: lat, lng: lon },
      zoom: 11,
      disableDefaultUI: false,
      zoomControl: true,
      mapTypeControl: false,
      streetViewControl: false,
      fullscreenControl: false,
      gestureHandling: 'greedy',
    });
    _googleMap.set('styles', [
      { featureType: 'poi', elementType: 'labels', stylers: [{ visibility: 'off' }] },
    ]);

    var pin = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="34" height="34">' +
      '<path fill="#e11d48" d="M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5A2.5 2.5 0 1 1 12 6.5a2.5 2.5 0 0 1 0 5z"/></svg>');

    _marker = new gm.Marker({
      map: _googleMap,
      position: { lat: lat, lng: lon },
      draggable: true,
      animation: gm.Animation.DROP,
      icon: { url: pin, size: new gm.Size(34, 34), scaledSize: new gm.Size(34, 34), anchor: new gm.Point(17, 34) },
    });

    _geocoder = new gm.Geocoder();

    _googleMap.addListener('click', function (e) {
      _marker.setPosition(e.latLng);
      reverseGeocode(e.latLng.lat(), e.latLng.lng());
    });
    _marker.addListener('dragend', function () {
      var p = _marker.getPosition();
      reverseGeocode(p.lat(), p.lng());
    });
  }

  function buildLeafletMap(el, lat, lon) {
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
  }

  function openMap() {
    _opened = true;
    var box = document.getElementById('wx-map-box');
    if (box) box.classList.remove('closed');
    var caret = document.getElementById('wx-map-caret');
    if (caret) caret.className = 'fas fa-chevron-up wx-map-caret';
    initMap(null, null);
    if (_googleMap) setTimeout(function () { google.maps.event.trigger(_googleMap, 'resize'); }, 60);
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
    if (isGoogleMode() && _geocoder) {
      _geocoder.geocode({ location: { lat: lat, lng: lon } }, function (results, status) {
        if (status === 'OK' && results && results.length) {
          var addr = results[0].address_components || [];
          var city = pickCity(addressArray(addr));
          var state = pickState(addressArray(addr));
          var name = results[0].formatted_address || '';
          _selected = { lat: lat, lon: lon, city: city || state || name || 'Ubicación seleccionada' };
          updateCityLabel(_selected.city);
        } else {
          _selected = { lat: lat, lon: lon, city: 'Ubicación seleccionada' };
          updateCityLabel(_selected.city);
        }
      });
      return;
    }
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

  function addressArray(components) {
    var map = {};
    for (var i = 0; i < components.length; i++) {
      var c = components[i];
      for (var j = 0; j < c.types.length; j++) {
        map[c.types[j]] = c.long_name;
      }
    }
    return map;
  }
  function pickCity(map) {
    return map.locality || map.sublocality_level_1 || map.postal_town || map.city || '';
  }
  function pickState(map) {
    return map.administrative_area_level_1 || map.administrative_area_level_2 || '';
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
    if (isGoogleMode() && _geocoder) {
      _geocoder.geocode({ address: q }, function (results, status) {
        if (status === 'OK' && results && results.length) {
          var first = results[0];
          var loc = first.geometry.location;
          var addr = addressArray(first.address_components || []);
          var city = pickCity(addr) || pickState(addr) || q;
          _googleMap.setCenter(loc);
          _googleMap.setZoom(12);
          _marker.setPosition(loc);
          _selected = { lat: loc.lat(), lon: loc.lng(), city: city };
          updateCityLabel(city);
          input.blur();
        } else {
          updateCityLabel('No se encontró «' + q + '». Intenta con otro nombre.');
        }
      });
      return;
    }
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

    // Refrescar el widget de clima flotante (widgets/)
    if (window.chWidget && window.chWidget.refresh) { try { window.chWidget.refresh(); } catch (e) {} }

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