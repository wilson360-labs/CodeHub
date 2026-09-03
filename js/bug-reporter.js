/* ═══════════════════════════════════════════════════════════
   CodeHub — Autodetector de bugs (frontend web)
   - Captura errores de JS (error + unhandledrejection) con la
     línea/columna/fuente que rompe.
   - Registra una traza de actividad del usuario (sección visible,
     última acción, hash/URL) para dar contexto al reporte.
   - Envía a POST /api/crash-report (backend) que reenvía a
     Telegram del admin con dedupe anti-spam.
   - Solo activo si RC.feature('crashReportEnabled') es true.
   - Nunca lanza ni interfiere con la app (todo en try/catch).
   ═══════════════════════════════════════════════════════════ */
(function () {
  'use strict';
  if (window.__chBugReporter) return;
  window.__chBugReporter = true;

  var ENDPOINT = '/api/crash-report';
  var APP_VERSION = 'codehub-web';
  var enabled = false;
  var enabledChecked = false;

  // ── Traza de actividad del usuario ─────────────────────────
  var activity = [];
  var lastActivity = { section: '', action: '', url: location.href };

  function pushActivity(kind, detail) {
    try {
      var now = Date.now();
      var entry = { t: now, k: kind, d: String(detail || '').slice(0, 180) };
      activity.push(entry);
      if (activity.length > 8) activity.shift();
      lastActivity[kind] = String(detail || '');
    } catch (e) {}
  }

  function currentSection() {
    try {
      if (location.hash) {
        var s = document.getElementById(location.hash.slice(1));
        if (s) return s.id;
      }
      // Sección visible por scroll (primer section que ocupa el viewport)
      var secs = document.querySelectorAll('section[id]');
      for (var i = 0; i < secs.length; i++) {
        var r = secs[i].getBoundingClientRect();
        if (r.top <= window.innerHeight * 0.4 && r.bottom >= window.innerHeight * 0.3) {
          return secs[i].id;
        }
      }
    } catch (e) {}
    return lastActivity.section || '';
  }

  function hookActivity() {
    try {
      document.addEventListener('click', function (ev) {
        var t = ev.target;
        var id = (t && (t.id || t.closest && t.closest('[id]') && t.closest('[id]').id)) || '';
        var cls = (t && t.className && typeof t.className === 'string' ? t.className.split(' ')[0] : '') || '';
        pushActivity('action', (id || cls || t.tagName || 'click'));
      }, true);
      window.addEventListener('hashchange', function () {
        pushActivity('nav', location.hash || 'home');
      });
      // Muestreo de sección visible cada 1.5s (barato)
      setInterval(function () {
        var sec = currentSection();
        if (sec && sec !== lastActivity.section) {
          lastActivity.section = sec;
          pushActivity('section', sec);
        }
      }, 1500);
    } catch (e) {}
  }

  function activitySummary() {
    // Últimas acciones relevantes en orden cronológico
    var marks = activity.slice(-6).map(function (a) {
      var h = new Date(a.t);
      var hm = (h.getHours() < 10 ? '0' : '') + h.getHours() + ':' + (h.getMinutes() < 10 ? '0' : '') + h.getMinutes();
      return hm + ' ' + a.k + '=' + a.d;
    });
    return marks.join('  →  ') || '(sin actividad)';
  }

  // ── Dedupe en cliente (no spamear Telegram) ────────────────
  var seen = {};
  function shouldSend(key) {
    var now = Date.now();
    var prev = seen[key];
    if (prev && now - prev < 60000) return false; // mismo error < 1 min
    seen[key] = now;
    return true;
  }

  // ── Envío (fetch con fallback a sendBeacon) ────────────────
  function sendReport(payload) {
    try {
      if (!enabled) return;
      var body = JSON.stringify(payload);
      if (navigator.sendBeacon) {
        try {
          var sent = navigator.sendBeacon(ENDPOINT, new Blob([body], { type: 'application/json' }));
          if (sent) return;
        } catch (e) {}
      }
      if (navigator.sendBeacon === undefined) {
        // Blob vía sendBeacon usa POST; si no existe, fetch normal
      }
      fetch(ENDPOINT, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: body,
        keepalive: true,
      }).catch(function () {});
    } catch (e) {}
  }

  function makePayload(tag, fatal, exceptionClass, message, stackTrace) {
    var parts = [];
    // Traza con números de línea/columna
    try {
      if (stackTrace && Array.isArray(stackTrace.frames)) {
        parts = stackTrace.frames.slice(0, 12).map(function (fr) {
          return 'at ' + (fr.fn || '<anon>') + ' (' + fr.file + ':' + fr.line + ':' + fr.col + ')';
        });
      } else if (typeof stackTrace === 'string' && stackTrace.trim()) {
        parts = stackTrace.split('\n').slice(0, 12);
      } else if (stackTrace && stackTrace.source) {
        // (ajuste para el APK/otras fuentes)
        parts = [stackTrace.source];
      }
    } catch (e) {}

    return {
      fatal: !!fatal,
      tag: tag || 'web',
      exceptionClass: exceptionClass || 'Error',
      message: String(message || '').slice(0, 1000),
      stackTrace: parts.join('\n').slice(0, 3200),
      appVersion: APP_VERSION,
      platform: 'web',
      deviceModel: (typeof navigator !== 'undefined'
        ? (navigator.userAgent || '').slice(0, 120) + ' · ' + window.innerWidth + 'x' + window.innerHeight
        : 'unknown'),
      androidVersion: 'web',
      timestamp: Date.now(),
      activity: activitySummary(),
      section: lastActivity.section,
      url: location.href,
    };
  }

  // ── Capturadores ────────────────────────────────────────────
  function handleError(msg, url, line, col, errorObj) {
    var key = (url || '') + ':' + (line || '') + ':' + (col || '') + ':' + (msg || '');
    if (!shouldSend(key)) return;
    var stack = errorObj && errorObj.stack ? errorObj.stack : msg + ' (en ' + url + ':' + line + ':' + col + ')';
    var payload = makePayload(
      lastActivity.section || 'global',
      true,
      (errorObj && errorObj.name) || 'Error',
      msg,
      stack
    );
    // Añadir línea/columna explícitos si no están en el stack
    try {
      if (payload.stackTrace && payload.stackTrace.indexOf(url + ':') < 0) {
        payload.stackTrace = 'at <' + (url || '?') + '>:' + (line || '?') + ':' + (col || '?') + '\n' + payload.stackTrace;
      }
    } catch (e) {}
    sendReport(payload);
  }

  function handleRejection(reason) {
    var msg = '';
    try { msg = (reason && (reason.message || reason.reason || reason)) + ''; } catch (e) { msg = String(reason); }
    var key = 'rej:' + msg;
    if (!shouldSend(key)) return;
    var stack = '';
    try { stack = (reason && reason.stack) ? reason.stack : msg; } catch (e) { stack = msg; }
    sendReport(makePayload(
      lastActivity.section || 'global',
      false,
      'UnhandledRejection',
      'Promesa rechazada sin capturar: ' + msg,
      stack
    ));
  }

  // ── Arranque ────────────────────────────────────────────────
  function boot() {
    if (enabledChecked) return;
    enabledChecked = true;
    try {
      if (window.RC && RC.feature) {
        enabled = !!RC.feature('crashReportEnabled');
      } else {
        enabled = true; // RC no disponible aún → activar por defecto
      }
    } catch (e) { enabled = true; }
    if (!enabled) return;
    hookActivity();

    window.addEventListener('error', function (ev) {
      handleError(ev.message, ev.filename, ev.lineno, ev.colno, ev.error);
    });

    window.addEventListener('unhandledrejection', function (ev) {
      handleRejection(ev && ev.reason);
    });

    // Quedarse listo cuando RC termine de cargar (por si llega tarde)
    try {
      if (window.RC && RC.ready) {
        RC.ready().then(function () {
          try { enabled = !!RC.feature('crashReportEnabled'); } catch (e) {}
        });
      }
    } catch (e) {}
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
