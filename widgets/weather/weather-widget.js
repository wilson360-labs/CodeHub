/* ═══════════════════════════════════════════════════════════
   CodeHub Widget — Clima (pastilla flotante expandible)
   - Anclada arriba-izquierda, pegada al navbar (no abajo — evita
     taparse con el dock/botones flotantes del borde inferior).
     El offset superior se recalcula con la altura real de <header>.
   - Open-Meteo (CSP ya lo permite) + ubicación desde ch_user_*.
   - Caché propia en localStorage (ch_widget_weather, ~10 min).
   - Recomendaciones inteligentes: lluvia/UV/frío/calor/viento/tormenta.
   - Botón "refresh" en el panel: fuerza un fetch nuevo (ignora caché) y
     muestra "Actualizado hace…" en tiempo real.
   - API pública: window.chWidget = { refresh, refreshNow, toggle }.
   ═══════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var CACHE_KEY = 'ch_widget_weather';
  var TTL = 10 * 60 * 1000; // 10 minutos
  var LIFETIME = 45 * 60 * 1000; // descartar caché vieja (45 min)
  var API = 'https://api.open-meteo.com/v1/forecast';
  var HOURS = 24;

  var WX_ICON = {
    0: '☀️', 1: '🌤️', 2: '⛅', 3: '☁️', 45: '🌫️', 48: '🌫️',
    51: '🌦️', 53: '🌦️', 55: '🌧️', 56: '🌧️', 57: '🌧️',
    61: '🌧️', 63: '🌧️', 65: '🌧️', 66: '🌧️', 67: '🌧️',
    71: '🌨️', 73: '🌨️', 75: '❄️', 77: '❄️',
    80: '🌦️', 81: '🌦️', 82: '⛈️', 85: '🌨️', 86: '❄️',
    95: '⛈️', 96: '⛈️', 99: '⛈️',
  };
  var WX_LABEL = {
    0: 'Despejado', 1: 'Mayormente despejado', 2: 'Parcialmente nublado', 3: 'Nublado',
    45: 'Niebla', 48: 'Niebla con escarcha',
    51: 'Llovizna ligera', 53: 'Llovizna', 55: 'Llovizna intensa', 56: 'Llovizna helada', 57: 'Llovizna helada intensa',
    61: 'Lluvia ligera', 63: 'Lluvia', 65: 'Lluvia intensa', 66: 'Lluvia helada', 67: 'Lluvia helada intensa',
    71: 'Nieve ligera', 73: 'Nieve', 75: 'Nieve intensa', 77: 'Granizo',
    80: 'Chubascos ligeros', 81: 'Chubascos', 82: 'Chubascos intensos', 85: 'Chubascos de nieve', 86: 'Chubascos de nieve intensos',
    95: 'Tormenta eléctrica', 96: 'Tormenta con granizo', 99: 'Tormenta con granizo intenso',
  };

  var _root = null;
  var _open = false;
  var _timer = null;
  var _updTimer = null;
  var _lastUpdateTs = null;

  function q(sel) { return _root ? _root.querySelector(sel) : null; }

  /* ── Etiqueta "Actualizado hace…" (tiempo real) ────── */
  function setUpdatedText(ts) {
    var el = q('.chw-updated-text');
    if (!el) return;
    if (!ts) { el.textContent = 'Sin datos aún'; return; }
    var s = Math.max(0, Math.round((Date.now() - ts) / 1000));
    if (s < 8) el.textContent = 'Actualizado justo ahora';
    else if (s < 60) el.textContent = 'Actualizado hace ' + s + 's';
    else if (s < 3600) el.textContent = 'Actualizado hace ' + Math.round(s / 60) + ' min';
    else el.textContent = 'Actualizado hace ' + Math.round(s / 3600) + ' h';
  }
  function startUpdatedTicker() {
    if (_updTimer) clearInterval(_updTimer);
    _updTimer = setInterval(function () { setUpdatedText(_lastUpdateTs); }, 5000);
  }

  /* ── Offset superior — pega la pastilla justo debajo del navbar,
     usando la altura real del <header> (no es sticky, pero sirve
     como referencia consistente en cualquier ancho/dispositivo). ── */
  function updateTopOffset() {
    var header = document.querySelector('header');
    var h = header ? Math.round(header.getBoundingClientRect().height) : 64;
    if (!h || h < 20) h = 64; // header aún no midió bien (fuente cargando, etc.)
    document.documentElement.style.setProperty('--chw-top-offset', (h + 14) + 'px');
  }

  function readLocation() {
    if (typeof chReadLocation === 'function') return chReadLocation();
    var lat = parseFloat(localStorage.getItem('ch_user_lat'));
    var lon = parseFloat(localStorage.getItem('ch_user_lon'));
    return {
      lat: Number.isFinite(lat) ? lat : null,
      lon: Number.isFinite(lon) ? lon : null,
      city: localStorage.getItem('ch_user_city') || '',
    };
  }

  function loadCache() {
    try {
      var raw = localStorage.getItem(CACHE_KEY);
      if (!raw) return null;
      var c = JSON.parse(raw);
      if (!c || !c.ts || (Date.now() - c.ts) > LIFETIME) return null;
      return c;
    } catch (e) { return null; }
  }
  function saveCache(data) {
    try { localStorage.setItem(CACHE_KEY, JSON.stringify({ ts: Date.now(), data: data })); } catch (e) {}
  }

  function fetchWeather(lat, lon) {
    var url = API + '?latitude=' + lat + '&longitude=' + lon +
      '&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,precipitation&' +
      'hourly=temperature_2m,weather_code,precipitation_probability,uv_index&temperature_unit=celsius&wind_speed_unit=kmh&timezone=auto&forecast_days=2';
    return fetch(url).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    });
  }

  function icon(code) { return WX_ICON[code] || '🌡️'; }
  function label(code) { return WX_LABEL[code] || 'Variable'; }

  function recommend(d) {
    var c = d.current || {};
    var code = c.weather_code;
    var hour0 = d.hours && d.hours[0];
    var rain = (hour0 && hour0.rain) || 0;
    var uv = d.uvNow;

    if (code === 95 || code === 96 || code === 99) return { icon: '⛈️', text: 'Tormenta eléctrica — quédese en resguardo' };
    if (code >= 71 && code <= 77 || code === 85 || code === 86) return { icon: '❄️', text: 'Nieve o aguanieve — abríguese y maneje con precaución' };
    if (rain >= 60 || (code >= 61 && code <= 67) || (code >= 80 && code <= 82)) {
      return { icon: '🌂', text: 'Lleve paraguas — alta probabilidad de lluvia' };
    }
    if (uv != null) {
      if (uv >= 8) return { icon: '☀️', text: 'Radiación UV EXTREMA — bloqueador solar y evite el sol al mediodía' };
      if (uv >= 6) return { icon: '🧴', text: 'UV alto — use bloqueador solar y sombrero' };
    }
    if (c.temperature_2m != null && c.temperature_2m <= 12) return { icon: '🧥', text: 'Hace frío — abríguese bien' };
    if (c.temperature_2m != null && c.temperature_2m >= 32) return { icon: '🥵', text: 'Calor intenso — hidrátese y evite el sol a mediodía' };
    if (c.wind_speed_10m != null && c.wind_speed_10m >= 40) return { icon: '💨', text: 'Viento fuerte — asegure objetos sueltos' };
    if (code === 45 || code === 48) return { icon: '🌫️', text: 'Niebla — maneje con precaución' };
    return { icon: '✨', text: 'Clima agradable — ¡disfruta tu día!' };
  }

  function buildHours(d) {
    var h = d.hourly || {};
    var times = h.time || [];
    var temps = h.temperature_2m || [];
    var codes = h.weather_code || [];
    var rain = h.precipitation_probability || [];
    var uv = h.uv_index || [];

    var currentTime = (d.current && d.current.time) || times[0] || '';
    var start = Math.max(0, times.indexOf(currentTime));

    var out = [];
    for (var i = 0; i < HOURS && (start + i) < times.length; i++) {
      var idx = start + i;
      var t = (times[idx] || '').split('T');
      out.push({
        time: t[1] ? t[1].slice(0, 5) : '--:--',
        temp: Math.round(temps[idx] != null ? temps[idx] : 0),
        code: codes[idx] != null ? codes[idx] : 0,
        rain: Math.round(rain[idx] != null ? rain[idx] : 0),
        uv: uv[idx] != null ? uv[idx] : 0,
        isNow: idx === start,
      });
    }
    return out;
  }

  function render(data, city) {
    var c = data.current || {};
    var t = c.temperature_2m;
    var feels = c.apparent_temperature;
    var code = c.weather_code;
    var tip = recommend(data);

    if (q('.chw-pill-temp')) q('.chw-pill-temp').textContent = t != null ? Math.round(t) + '°' : '--';
    if (q('.chw-pill-city')) q('.chw-pill-city').textContent = city || 'Mi ubicación';
    if (q('.chw-pill-icon')) q('.chw-pill-icon').textContent = icon(code);

    if (q('.chw-now-temp')) q('.chw-now-temp').textContent = t != null ? Math.round(t) + '°' : '--';
    if (q('.chw-now-icon')) q('.chw-now-icon').textContent = icon(code);
    if (q('.chw-now-sub')) q('.chw-now-sub').textContent =
      label(code) + ' · Sensación ' + (feels != null ? Math.round(feels) : '--') + '°C';
    if (q('.chw-now-city')) q('.chw-now-city').textContent = city || 'Mi ubicación';

    _lastUpdateTs = (data && data.ts) || Date.now();
    setUpdatedText(_lastUpdateTs);

    if (q('.chw-tip')) {
      q('.chw-tip').innerHTML = '<i>' + tip.icon + '</i><span>' + tip.text + '</span>';
    }

    var strip = q('.chw-strip');
    if (strip) {
      strip.innerHTML = '';
      buildHours(data).forEach(function (hx) {
        var el = document.createElement('div');
        el.className = 'chw-hour' + (hx.isNow ? ' is-now' : '');
        el.innerHTML =
          '<span class="chw-hour-time">' + (hx.isNow ? 'Ahora' : hx.time) + '</span>' +
          '<span class="chw-hour-icon">' + icon(hx.code) + '</span>' +
          '<span class="chw-hour-rain">' + (hx.rain > 0 ? '💧 ' + hx.rain + '%' : '') + '</span>' +
          '<span class="chw-hour-temp">' + hx.temp + '°</span>';
        strip.appendChild(el);
      });
    }
  }

  function renderEmpty() {
    if (q('.chw-pill-icon')) q('.chw-pill-icon').textContent = '🌡️';
    if (q('.chw-pill-city')) q('.chw-pill-city').textContent = 'Elegir ciudad';
    if (q('.chw-now-temp')) q('.chw-now-temp').textContent = '--';
    if (q('.chw-now-sub')) q('.chw-now-sub').textContent = 'Configura tu ubicación';
    if (q('.chw-tip')) q('.chw-tip').innerHTML = '<i>📍</i><span>Toca <b>“Elegir mi ciudad en el mapa”</b> para activar el widget del clima.</span>';
    if (q('.chw-strip')) q('.chw-strip').innerHTML = '';
    _lastUpdateTs = null;
    setUpdatedText(null);
    _root.setAttribute('data-ready', '0');
  }

  /* ── Refresco forzado (ignora caché) para clima en tiempo real ── */
  function forceRefresh() {
    var btn = q('.chw-refresh-btn');
    if (btn) btn.classList.add('spinning');
    try { localStorage.removeItem(CACHE_KEY); } catch (e) {}

    var loc = readLocation();
    if (!loc.lat || !loc.lon || !Number.isFinite(loc.lat) || !Number.isFinite(loc.lon)) {
      renderEmpty();
      if (btn) btn.classList.remove('spinning');
      return;
    }

    fetchWeather(loc.lat, loc.lon).then(function (d) {
      var data = {
        current: d.current || {},
        hourly: d.hourly || {},
        uvNow: (d.current && d.current.uv_index) != null ? d.current.uv_index : ((d.hourly && d.hourly.uv_index && d.hourly.uv_index[0]) || 0),
        ts: Date.now(),
      };
      saveCache(data);
      render(data, loc.city);
      _root.setAttribute('data-ready', '1');
      if (btn) btn.classList.remove('spinning');
    }, function () {
      if (btn) btn.classList.remove('spinning');
      if (typeof toast === 'function') toast('⚠️ No se pudo actualizar el clima ahora mismo', 'error', 3000);
    });
  }

  function refresh() {
    var loc = readLocation();
    if (!loc.lat || !loc.lon || !Number.isFinite(loc.lat) || !Number.isFinite(loc.lon)) { renderEmpty(); return; }

    var cached = loadCache();
    if (cached && cached.data && (Date.now() - cached.ts) < TTL) {
      render(cached.data, loc.city);
      _root.setAttribute('data-ready', '1');
      return;
    }
    // dato fresco pero viejo en caché → usar mientras se revalida
    if (cached && cached.data && (Date.now() - cached.ts) < LIFETIME) {
      render(cached.data, loc.city);
      _root.setAttribute('data-ready', '1');
    }
    fetchWeather(loc.lat, loc.lon).then(function (d) {
      var data = {
        current: d.current || {},
        hourly: d.hourly || {},
        uvNow: (d.current && d.current.uv_index) != null ? d.current.uv_index : ((d.hourly && d.hourly.uv_index && d.hourly.uv_index[0]) || 0),
        ts: Date.now(),
      };
      saveCache(data);
      render(data, loc.city);
      _root.setAttribute('data-ready', '1');
    }).catch(function () {
      // Sin red y sin caché → mantener la pastilla en estado mínimo
      if (!cached || !cached.data) renderEmpty();
    });
  }

  function toggle() {
    _open = !_open;
    _root.setAttribute('data-open', _open ? '1' : '0');
    if (_open) refresh();
  }

  function openGuia() {
    _open = false;
    if (_root) _root.setAttribute('data-open', '0');
    var section = document.getElementById('weather-section');
    if (!section) return;
    section.scrollIntoView({ behavior: 'smooth', block: 'start' });
    var box = document.getElementById('wx-map-box');
    var tt = document.getElementById('wx-map-toggle');
    if (tt && box && box.classList.contains('closed') && typeof chToggleMap === 'function') {
      setTimeout(function () { try { chToggleMap(); } catch (e) {} }, 500);
    }
  }

  function build() {
    if (_root || document.getElementById('chw-widget')) return;
    _root = document.createElement('div');
    _root.id = 'chw-widget';
    _root.className = 'chw-widget';
    _root.setAttribute('data-open', '0');
    _root.setAttribute('data-ready', '0');
    _root.innerHTML =
      '<button class="chw-pill" type="button" aria-label="Widget del clima" title="Clima">' +
        '<span class="chw-pill-icon">🌡️</span>' +
        '<span class="chw-pill-temp">--</span>' +
        '<span class="chw-pill-city">Elegir ciudad</span>' +
        '<i class="fas fa-chevron-down chw-caret"></i>' +
      '</button>' +
      '<div class="chw-panel">' +
        '<div class="chw-panel-header">' +
          '<div>' +
            '<div class="chw-now-temp">--</div>' +
            '<div class="chw-now-sub">Cargando…</div>' +
            '<div class="chw-updated"><span class="chw-live-dot"></span><span class="chw-updated-text">Cargando…</span></div>' +
          '</div>' +
          '<div class="chw-now-right">' +
            '<button type="button" class="chw-refresh-btn" aria-label="Actualizar clima ahora" title="Actualizar clima ahora">' +
              '<i class="fas fa-rotate"></i>' +
            '</button>' +
            '<div class="chw-now-icon">🌡️</div>' +
            '<div class="chw-now-city">CodeHub</div>' +
          '</div>' +
        '</div>' +
        '<div class="chw-tip"><i>ℹ️</i><span>Cargando clima…</span></div>' +
        '<div class="chw-strip"></div>' +
      '</div>';
    _root.querySelector('.chw-pill').addEventListener('click', function () { toggle(); });
    _root.querySelector('.chw-refresh-btn').addEventListener('click', function (e) {
      e.stopPropagation();
      forceRefresh();
    });

    var noLoc = !readLocation().lat;
    _root.addEventListener('click', function (e) {
      if (noLoc && (e.target.closest('.chw-pill') || e.target.closest('.chw-panel'))) openGuia();
    });

    document.body.appendChild(_root);

    // Offset superior: medir de inmediato + reintentar poco después
    // (fuentes/typing effect pueden cambiar la altura del header al
    // cargar) + recalcular en resize/orientación.
    updateTopOffset();
    setTimeout(updateTopOffset, 400);
    setTimeout(updateTopOffset, 1500);
    var _offsetResizeTimer;
    window.addEventListener('resize', function () {
      clearTimeout(_offsetResizeTimer);
      _offsetResizeTimer = setTimeout(updateTopOffset, 150);
    });
    window.addEventListener('orientationchange', function () {
      setTimeout(updateTopOffset, 300);
    });

    // Eventos externos
    window.addEventListener('storage', function (e) {
      if (e.key && e.key.indexOf('ch_user_') === 0) { _open = false; _root.setAttribute('data-open', '0'); refresh(); }
    });
    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'visible') refresh();
    });
    _timer = setInterval(refresh, TTL);
    startUpdatedTicker();
    refresh();
  }

  // ── Init ──
  // Se construye de inmediato (robusto ante latencia de RC); si la feature
  // está desactivada en config remota, se oculta.
  function init() {
    build();
    if (typeof RC !== 'undefined' && RC.ready) {
      RC.ready().then(function () {
        if (RC.feature('weatherEnabled') === false && _root) {
          _root.style.display = 'none';
        }
      });
    }
  }

  window.chWidget = {
    refresh: refresh,
    refreshNow: forceRefresh,
    toggle: toggle,
    get ready() { return !!_root; },
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();