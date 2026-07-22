// ═══════════════════════════════════════════════════════════════
//  THINKING ORB — CodeHub by Wilson.E
//  Indicador animado en canvas 2D (sin dependencias, sin WebGL).
//  Estados: "composing" (escribiendo/pensando) y "working" (generando).
// ═══════════════════════════════════════════════════════════════
(function () {
  'use strict';

  var reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  function withAlpha(rgbaStr, a) {
    return rgbaStr.replace(/[\d.]+\)\s*$/, a.toFixed(2) + ')');
  }

  function create(canvas, opts) {
    opts = opts || {};
    var state = opts.state || 'composing';
    var size = opts.size || 32;
    var color = opts.color || 'rgba(255,255,255,0.9)';
    var dpr = Math.min(window.devicePixelRatio || 1, 2);

    canvas.width = size * dpr;
    canvas.height = size * dpr;
    canvas.style.width = size + 'px';
    canvas.style.height = size + 'px';

    var ctx = canvas.getContext('2d');
    ctx.scale(dpr, dpr);

    var running = true;
    var raf = null;
    var t0 = null;

    function drawComposing(t) {
      // Bandas onduladas de puntos — representa "pensando / escribiendo"
      ctx.clearRect(0, 0, size, size);
      var cx = size / 2, cy = size / 2, r = size * 0.36;
      var bands = 3;
      for (var b = 0; b < bands; b++) {
        var bandR = r * (0.5 + b * 0.24);
        var dots = 8 + b * 3;
        var phase = t * 0.0017 + b * 1.15;
        for (var i = 0; i < dots; i++) {
          var a = (i / dots) * Math.PI * 2 + phase;
          var wob = Math.sin(a * 3 + t * 0.0032 + b) * (size * 0.045);
          var x = cx + Math.cos(a) * (bandR + wob);
          var y = cy + Math.sin(a) * (bandR + wob) * 0.52;
          var alpha = 0.28 + 0.55 * ((Math.sin(a * 2 + t * 0.0042 + b) + 1) / 2);
          ctx.beginPath();
          ctx.fillStyle = withAlpha(color, alpha);
          ctx.arc(x, y, size * 0.024, 0, Math.PI * 2);
          ctx.fill();
        }
      }
    }

    function drawWorking(t) {
      // Partículas en órbitas inclinadas — representa "generando / trabajando"
      ctx.clearRect(0, 0, size, size);
      var cx = size / 2, cy = size / 2;
      var orbits = 3;
      for (var o = 0; o < orbits; o++) {
        var rx = size * (0.15 + o * 0.12);
        var ry = rx * 0.42;
        var tilt = (o - 1) * 0.55;
        var speed = 0.0013 + o * 0.0007;
        var dir = o % 2 ? -1 : 1;
        var count = 5;
        for (var i = 0; i < count; i++) {
          var a = (i / count) * Math.PI * 2 + t * speed * dir;
          var ex = Math.cos(a) * rx;
          var ey = Math.sin(a) * ry;
          var x = cx + ex * Math.cos(tilt) - ey * Math.sin(tilt);
          var y = cy + ex * Math.sin(tilt) + ey * Math.cos(tilt);
          var depth = (Math.sin(a) + 1) / 2;
          ctx.beginPath();
          ctx.fillStyle = withAlpha(color, 0.3 + 0.6 * depth);
          ctx.arc(x, y, size * (0.018 + 0.02 * depth), 0, Math.PI * 2);
          ctx.fill();
        }
      }
    }

    function frame(t) {
      if (!running) return;
      if (t0 === null) t0 = t;
      var elapsed = t - t0;
      if (state === 'working') drawWorking(elapsed);
      else drawComposing(elapsed);
      raf = requestAnimationFrame(frame);
    }

    function start() {
      if (raf) return;
      running = true;
      raf = requestAnimationFrame(frame);
    }
    function stop() {
      running = false;
      if (raf) cancelAnimationFrame(raf);
      raf = null;
    }

    if (reduceMotion) {
      // Un solo frame estático, sin animación continua
      t0 = 0;
      if (state === 'working') drawWorking(400); else drawComposing(400);
    } else {
      start();
      if ('IntersectionObserver' in window) {
        var io = new IntersectionObserver(function (entries) {
          entries.forEach(function (e) {
            if (e.isIntersecting) start(); else stop();
          });
        });
        io.observe(canvas);
      }
      document.addEventListener('visibilitychange', function () {
        if (document.hidden) stop(); else start();
      });
    }

    return {
      setState: function (s) { state = s; },
      destroy: stop
    };
  }

  window.ThinkingOrb = { create: create };
})();
