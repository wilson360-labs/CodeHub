/* ═══════════════════════════════════════════════════════════
   CodeHub — Escenas de clima animadas (estilo Samsung One UI 8.5)
   - Solo DOM + CSS (@keyframes en css/viewport-guard.css). Sin canvas,
     sin librerías, sin assets de terceros.
   - Se monta sobre cualquier contenedor: la tarjeta #wx-card y el panel
     del widget flotante (.chw-panel).
   - Escena por condición (clear/partly/cloudy/rain/storm/snow/fog) y por
     momento del día (data-day="1" día / "0" noche).
   - Rendimiento: menos partículas en móvil (≤720px), pausa fuera de
     pantalla (IntersectionObserver) y con la pestaña oculta, y sin
     animación con prefers-reduced-motion.
   - API: window.CHWxScene = { mount, hourly, unmount }
   ═══════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var reduced = false;
  try { reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) {}

  var ICON = {
    0: '☀️', 1: '🌤️', 2: '⛅', 3: '☁️', 45: '🌫️', 48: '🌫️',
    51: '🌦️', 53: '🌦️', 55: '🌧️', 56: '🌧️', 57: '🌧️',
    61: '🌧️', 63: '🌧️', 65: '🌧️', 66: '🌧️', 67: '🌧️',
    71: '🌨️', 73: '🌨️', 75: '❄️', 77: '❄️',
    80: '🌦️', 81: '🌦️', 82: '⛈️', 85: '🌨️', 86: '❄️',
    95: '⛈️', 96: '⛈️', 99: '⛈️'
  };

  // [móvil, escritorio] — recuento de partículas por condición
  var COUNT = {
    rain: [26, 46], storm: [34, 62], snow: [22, 38]
  };
  var CLOUDS = { clear: 0, partly: 3, cloudy: 5, rain: 5, storm: 5, snow: 4, fog: 2 };

  var state = new WeakMap(); // host -> { sig, layer, io, ro }

  function rnd(a, b) { return a + Math.random() * (b - a); }
  function small() { return (window.innerWidth || 800) <= 720; }
  function el(cls, style) {
    var d = document.createElement('i');
    d.className = cls;
    if (style) d.style.cssText = style;
    return d;
  }

  function build(bucket, day, lite) {
    var layer = document.createElement('div');
    layer.className = 'wxs';
    layer.setAttribute('aria-hidden', 'true');
    var frag = document.createDocumentFragment();
    var idx = small() || lite ? 0 : 1;
    var night = !day;

    // Manchas de color (profundidad "mesh" de One UI)
    frag.appendChild(el('wxs-blob wxs-blob-a'));
    frag.appendChild(el('wxs-blob wxs-blob-b'));

    // Astros
    if (night) {
      var stars = (bucket === 'clear' || bucket === 'partly') ? (idx ? 26 : 16) : (bucket === 'cloudy' ? 8 : 0);
      for (var s = 0; s < stars; s++) {
        frag.appendChild(el('wxs-star',
          'left:' + rnd(2, 98).toFixed(1) + '%;top:' + rnd(2, 55).toFixed(1) + '%;' +
          '--d:' + rnd(0, 4).toFixed(2) + 's;--s:' + rnd(1.2, 2.6).toFixed(1) + 'px'));
      }
      if (bucket === 'clear' || bucket === 'partly' || bucket === 'cloudy') frag.appendChild(el('wxs-moon'));
    } else if (bucket === 'clear' || bucket === 'partly') {
      frag.appendChild(el('wxs-sun'));
      frag.appendChild(el('wxs-rays'));
    } else if (bucket === 'cloudy') {
      frag.appendChild(el('wxs-sun wxs-sun-dim'));
    }

    // Nubes (derivas lentas, tamaños y velocidades distintos)
    var nc = CLOUDS[bucket] || 0;
    for (var c = 0; c < nc; c++) {
      var w = rnd(130, 260);
      frag.appendChild(el('wxs-cloud',
        'width:' + w.toFixed(0) + 'px;height:' + (w * 0.38).toFixed(0) + 'px;' +
        'top:' + rnd(-4, 46).toFixed(0) + '%;' +
        '--o:' + rnd(0.55, 0.95).toFixed(2) + ';' +
        '--dur:' + rnd(38, 80).toFixed(0) + 's;' +
        'animation-delay:-' + rnd(0, 70).toFixed(0) + 's'));
    }

    // Niebla
    if (bucket === 'fog') {
      for (var f = 0; f < 3; f++) {
        frag.appendChild(el('wxs-fog',
          'top:' + (22 + f * 24) + '%;--dur:' + (22 + f * 9) + 's;animation-delay:-' + (f * 7) + 's'));
      }
    }

    // Lluvia / tormenta
    if (bucket === 'rain' || bucket === 'storm') {
      var n = COUNT[bucket][idx];
      for (var r = 0; r < n; r++) {
        frag.appendChild(el('wxs-drop',
          'left:' + rnd(-5, 105).toFixed(1) + '%;' +
          'height:' + rnd(12, 24).toFixed(0) + 'px;' +
          'opacity:' + rnd(0.35, 0.8).toFixed(2) + ';' +
          'animation-duration:' + rnd(0.55, 1.05).toFixed(2) + 's;' +
          'animation-delay:-' + rnd(0, 1.2).toFixed(2) + 's'));
      }
    }
    if (bucket === 'storm') {
      frag.appendChild(el('wxs-flash'));
      frag.appendChild(el('wxs-bolt'));
    }

    // Nieve
    if (bucket === 'snow') {
      var ns = COUNT.snow[idx];
      for (var k = 0; k < ns; k++) {
        var sz = rnd(2, 5.5);
        frag.appendChild(el('wxs-flake',
          'left:' + rnd(0, 100).toFixed(1) + '%;' +
          'width:' + sz.toFixed(1) + 'px;height:' + sz.toFixed(1) + 'px;' +
          'opacity:' + rnd(0.5, 0.95).toFixed(2) + ';' +
          '--sx:' + rnd(-26, 26).toFixed(0) + 'px;' +
          'animation-duration:' + rnd(6, 11).toFixed(1) + 's;' +
          'animation-delay:-' + rnd(0, 10).toFixed(1) + 's'));
      }
    }

    layer.appendChild(frag);
    return layer;
  }

  function setFall(host, layer) {
    var h = host.offsetHeight || 480;
    layer.style.setProperty('--fall', (h + 40) + 'px');
  }

  function mount(host, opts) {
    if (!host || !host.appendChild) return;
    opts = opts || {};
    var bucket = opts.bucket || host.getAttribute('data-wx') || 'clear';
    var day = opts.day != null ? !!opts.day : host.getAttribute('data-day') !== '0';
    var lite = !!opts.lite;
    var sig = bucket + '|' + (day ? 1 : 0) + '|' + (small() || lite ? 's' : 'l') + '|' + (reduced ? 'r' : 'm');
    var st = state.get(host);
    if (st && st.sig === sig) return;
    if (st) unmount(host);

    var layer = build(bucket, day, lite);
    if (reduced) layer.setAttribute('data-static', '1');
    host.classList.add('wxs-on');
    host.insertBefore(layer, host.firstChild);
    setFall(host, layer);

    var io = null, ro = null;
    try {
      io = new IntersectionObserver(function (ents) {
        var vis = ents[0] && ents[0].isIntersecting;
        host.setAttribute('data-wxs-paused', vis && !document.hidden ? '0' : '1');
      }, { threshold: 0.01 });
      io.observe(host);
    } catch (e) {}
    try {
      ro = new ResizeObserver(function () { setFall(host, layer); });
      ro.observe(host);
    } catch (e) {}

    state.set(host, { sig: sig, layer: layer, io: io, ro: ro });
  }

  function unmount(host) {
    var st = state.get(host);
    if (!st) return;
    if (st.io) st.io.disconnect();
    if (st.ro) st.ro.disconnect();
    if (st.layer && st.layer.parentNode) st.layer.parentNode.removeChild(st.layer);
    state.delete(host);
  }

  /* ── Pronóstico por horas de la tarjeta (estilo Samsung) ── */
  function hourly(h, nowIso) {
    var card = document.getElementById('wx-card');
    if (!card) return;
    var box = document.getElementById('wx-hourly');
    if (!h || !h.time || !h.time.length) { if (box) box.hidden = true; return; }
    if (!box) {
      box = document.createElement('div');
      box.id = 'wx-hourly';
      box.className = 'wx-hourly';
      box.setAttribute('role', 'list');
      var main = card.querySelector('.wx-main');
      if (main && main.parentNode) main.parentNode.insertBefore(box, main.nextSibling);
      else card.appendChild(box);
    }
    var start = 0;
    if (nowIso) {
      var floor = String(nowIso).slice(0, 13) + ':00';
      for (var i = 0; i < h.time.length; i++) { if (h.time[i] >= floor) { start = i; break; } }
    }
    var out = '';
    for (var j = 0; j < 24 && start + j < h.time.length; j++) {
      var k = start + j;
      var t = String(h.time[k]).split('T')[1] || '';
      var temp = h.temperature_2m && h.temperature_2m[k];
      var code = h.weather_code && h.weather_code[k];
      var rain = h.precipitation_probability && h.precipitation_probability[k];
      out += '<div class="wx-hr' + (j === 0 ? ' is-now' : '') + '" role="listitem">' +
        '<span class="wx-hr-t">' + (j === 0 ? 'Ahora' : t.slice(0, 5)) + '</span>' +
        '<span class="wx-hr-i">' + (ICON[code] || '🌡️') + '</span>' +
        '<span class="wx-hr-r">' + (rain > 0 ? rain + '%' : '') + '</span>' +
        '<span class="wx-hr-d">' + (temp != null ? Math.round(temp) + '°' : '--') + '</span>' +
        '</div>';
    }
    box.innerHTML = out;
    box.hidden = false;
  }

  /* ── Auto-montaje de la tarjeta #wx-card ── */
  function initCard() {
    var card = document.getElementById('wx-card');
    if (!card) return;
    mount(card);
    try {
      new MutationObserver(function () { mount(card); })
        .observe(card, { attributes: true, attributeFilter: ['data-wx', 'data-day'] });
    } catch (e) {}
    var t;
    window.addEventListener('resize', function () {
      clearTimeout(t);
      t = setTimeout(function () { mount(card); }, 200);
    });
  }

  document.addEventListener('visibilitychange', function () {
    var card = document.getElementById('wx-card');
    if (card && document.hidden) card.setAttribute('data-wxs-paused', '1');
  });

  window.CHWxScene = { mount: mount, unmount: unmount, hourly: hourly };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initCard);
  else initCard();
})();
