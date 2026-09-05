// ═══════════════════════════════════════════════════════
//  CodeHub — CENTRO DE NOTIFICACIONES v1.0
//  Wilson.E 2026
//
//  Panel de notificaciones visible en PC y móvil (campana
//  en el header + panel flotante). Conectado al Service
//  Worker (push real del backend, clima, actualizaciones)
//  y a las actividades del proyecto.
//
//  Compatible con instalación:
//   - Web (navegador): panel + push nativo del SW.
//   - Instalada (PWA standalone / APK TWA): el panel es
//     DOM puro, funciona igual; las nativas las pinta el SW.
// ═══════════════════════════════════════════════════════
(function () {
  if (window.__chNotifLoaded) return;
  window.__chNotifLoaded = true;

  var KEY      = 'ch_notifs_v1';
  var MAX      = 30;
  var BADGE_ID = 'notif-badge';
  var PANEL_ID = 'notif-panel';
  var LIST_ID  = 'notif-list';
  var BTN_ID   = 'notif-btn';

  function getLog() {
    try { return JSON.parse(localStorage.getItem(KEY) || '[]'); }
    catch (e) { return []; }
  }

  function saveLog(log) {
    try {
      // Auto-prune notifications older than 7 days
      var cutoff = Date.now() - 7 * 24 * 60 * 60 * 1000;
      log = log.filter(function (n) { return (n.ts || 0) > cutoff; });
      localStorage.setItem(KEY, JSON.stringify(log.slice(0, MAX)));
    } catch (e) {}
  }

  function timeAgo(ts) {
    var s = Math.floor((Date.now() - ts) / 1000);
    if (s < 60) return 'ahora';
    var m = Math.floor(s / 60);
    if (m < 60) return 'hace ' + m + ' min';
    var h = Math.floor(m / 60);
    if (h < 24) return 'hace ' + h + ' h';
    var d = Math.floor(h / 24);
    if (d === 1) return 'ayer';
    return new Date(ts).toLocaleDateString('es-GT', { day: '2-digit', month: 'short' });
  }

  function typeIcon(type) {
    var icons = {
      app_update:   '<i class="fas fa-download"></i>',
      weather:      '<i class="fas fa-cloud-sun-rain"></i>',
      announcement: '<i class="fas fa-bullhorn"></i>',
      system:       '<i class="fas fa-rotate"></i>',
      activity:     '<i class="fas fa-list-check"></i>',
      security:     '<i class="fas fa-shield-halved"></i>',
      release:      '<i class="fas fa-rocket"></i>',
      feature:      '<i class="fas fa-lightbulb"></i>',
      fix:          '<i class="fas fa-wrench"></i>',
      maintenance:  '<i class="fas fa-plug"></i>',
      seismic:      '<i class="fas fa-house-crack"></i>',
    };
    return icons[type] || '<i class="fas fa-bell"></i>';
  }

  function typeColor(type) {
    var colors = {
      app_update:   'rgba(47,128,237,.16)',
      weather:      'rgba(56,189,248,.16)',
      announcement: 'rgba(234,179,8,.14)',
      system:       'rgba(139,92,246,.16)',
      activity:     'rgba(34,197,94,.14)',
      security:     'rgba(248,113,113,.14)',
      release:      'rgba(168,85,247,.18)',
      feature:      'rgba(34,197,94,.16)',
      fix:          'rgba(59,130,246,.16)',
      maintenance:  'rgba(234,179,8,.16)',
      seismic:      'rgba(234,88,12,.16)',
    };
    return colors[type] || 'rgba(47,128,237,.12)';
  }

  // ── Badge (no leídas) ────────────────────────────────
  function updateBadge() {
    var badge = document.getElementById(BADGE_ID);
    if (!badge) return;
    var unread = getLog().filter(function (n) { return !n.read; }).length;
    if (unread > 0) {
      badge.textContent = unread > 9 ? '9+' : unread;
      badge.style.display = 'flex';
    } else {
      badge.style.display = 'none';
    }
    if ('setAppBadge' in navigator) {
      if (unread > 0) { navigator.setAppBadge(unread).catch(function(){}); }
      else { navigator.clearAppBadge().catch(function(){}); }
    }
  }

  // ── Render del panel ─────────────────────────────────
  function render() {
    var list = document.getElementById(LIST_ID);
    if (!list) return;
    var log = getLog();
    if (!log.length) {
      list.innerHTML =
        '<div class="notif-empty"><i class="fas fa-bell-slash"></i>' +
        '<span>Sin notificaciones todavía</span>' +
        '<small>Aquí verás apps nuevas, clima y novedades de CodeHub</small></div>';
      return;
    }
    list.innerHTML = log.map(function (n) {
      var url = n.url ? (' data-url="' + n.url.replace(/"/g, '&quot;') + '"') : '';
      return (
        '<div class="notif-item' + (n.read ? '' : ' unread') + '"' + url + ' role="button" tabindex="0">' +
          '<div class="notif-item-icon" style="background:' + typeColor(n.type) + '">' + typeIcon(n.type) + '</div>' +
          '<div class="notif-item-body">' +
            '<div class="notif-item-title">' + escapeHtml(n.title || 'CodeHub') + '</div>' +
            (n.body ? '<div class="notif-item-text">' + escapeHtml(n.body) + '</div>' : '') +
            '<div class="notif-item-time">' + timeAgo(n.ts) + '</div>' +
          '</div>' +
        '</div>'
      );
    }).join('');
  }

  function escapeHtml(str) {
    return String(str == null ? '' : str)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  // ── Añadir notificación ─────────────────────────────
  // chNotifLog(title, body, type, url, icon)
  //  - Guarda en el historial y pinta el panel/badge.
  //  - Si el permiso push está otorgado, además muestra la
  //    notificación nativa a través del Service Worker
  //    (LOCAL_PUSH), con navegación al hacer clic.
  //  - skipNative=true: el SW ya mostró la nativa (push real
  //    o LOCAL_PUSH), solo se registra en el panel para no
  //    duplicar la notificación del sistema.
  function add(title, body, type, url, icon, skipNative) {
    // Dedup: skip if same title+body exists within last 5 minutes
    var log = getLog();
    var now = Date.now();
    var key = String(title || '') + '|' + String(body || '');
    for (var i = 0; i < Math.min(log.length, 20); i++) {
      var existing = log[i];
      if ((existing.title || '') + '|' + (existing.body || '') === key && (now - (existing.ts || 0)) < 5 * 60 * 1000) {
        return existing;
      }
    }
    var item = {
      id: now + '-' + Math.random().toString(36).slice(2, 7),
      title: String(title || 'CodeHub'),
      body: String(body || ''),
      type: type || 'general',
      url: url || '',
      icon: icon || '',
      ts: now,
      read: false,
    };
    log.unshift(item);
    saveLog(log);
    render();
    updateBadge();
    if (!skipNative) nativePush(item);
    return item;
  }

  // Mostrar nativa vía SW (compatible web + instalada)
  function nativePush(item) {
    if (typeof Notification !== 'undefined' && Notification.permission !== 'granted') return;
    if (!('serviceWorker' in navigator)) {
      try { new Notification(item.title, { body: item.body, icon: item.icon || '/splash/codehub.png' }); } catch (e) {}
      return;
    }
    try {
      navigator.serviceWorker.ready.then(function (reg) {
        reg.showNotification(item.title, {
          body: item.body,
          icon: item.icon || '/splash/codehub.png',
          badge: '/splash/codehub.png',
          tag: 'ch-notif-' + item.id,
          vibrate: [150, 100, 150],
          data: { url: item.url || '/', type: item.type },
        });
      });
    } catch (e) {}
  }

  // ── Acciones ─────────────────────────────────────────
  function toggle(e) {
    if (e) { e.stopPropagation(); }
    var panel = document.getElementById(PANEL_ID);
    if (!panel) return;
    var open = panel.classList.contains('open');
    if (open) { close(); }
    else { openPanel(); }
  }

  function openPanel() {
    var panel = document.getElementById(PANEL_ID);
    var btn   = document.getElementById(BTN_ID);
    if (!panel) return;
    panel.classList.add('open');
    if (btn) btn.setAttribute('aria-expanded', 'true');
    render();
    markAllRead();
    // Cerrar al hacer clic fuera o con Esc
    setTimeout(function () {
      document.addEventListener('click', onDocClick, { once: true });
    }, 10);
  }

  function onDocClick(e) {
    var btn = document.getElementById(BTN_ID);
    if (btn && btn.contains(e.target)) return;
    close();
  }

  function close() {
    var panel = document.getElementById(PANEL_ID);
    var btn   = document.getElementById(BTN_ID);
    if (panel) panel.classList.remove('open');
    if (btn) btn.setAttribute('aria-expanded', 'false');
  }

  function markAllRead() {
    var log = getLog();
    var changed = false;
    log.forEach(function (n) { if (!n.read) { n.read = true; changed = true; } });
    if (changed) { saveLog(log); updateBadge(); }
  }

  function clear() {
    saveLog([]);
    render();
    updateBadge();
    close();
    // Vaciar también el historial guardado en el SW
    if ('serviceWorker' in navigator && navigator.serviceWorker.controller) {
      try {
        var channel = new MessageChannel();
        navigator.serviceWorker.controller.postMessage({ type: 'CLEAR_NOTIFS' }, [channel.port2]);
      } catch (e) {}
    }
  }

  // ── Delegación de clics en la lista ──────────────────
  function onListClick(e) {
    var item = e.target.closest('.notif-item');
    if (!item) return;
    var url = item.getAttribute('data-url');
    var idx = Array.prototype.indexOf.call(item.parentNode.children, item);
    var log = getLog();
    if (log[idx]) { log[idx].read = true; saveLog(log); updateBadge(); render(); }
    close();
    if (url) {
      if (url.startsWith('/') || url.startsWith('#') || url.startsWith('http')) {
        location.href = url;
      } else {
        location.href = '/' + url;
      }
    }
  }

  // ── Cargar notificaciones guardadas por el SW ───────
  // Si un push llegó con la app cerrada, el SW lo guardó en su
  // caché; al abrir el panel lo recuperamos y lo mostramos.
  function loadFromSW() {
    if (!('serviceWorker' in navigator) || !navigator.serviceWorker.controller) return;
    try {
      var channel = new MessageChannel();
      channel.port1.onmessage = function (e) {
        var list = (e.data && e.data.notifs) || [];
        if (!list.length) return;
        var log = getLog();
        var existingIds = log.map(function (n) { return n.title + '|' + n.body + '|' + n.ts; });
        var added = 0;
        list.forEach(function (n) {
          var key = (n.title || '') + '|' + (n.body || '') + '|' + (n.ts || 0);
          if (existingIds.indexOf(key) !== -1) return;
          var item = {
            id: (n.ts || Date.now()) + '-' + Math.random().toString(36).slice(2, 7),
            title: String(n.title || 'CodeHub'),
            body: String(n.body || ''),
            type: n.type || 'general',
            url: n.url || '',
            icon: n.icon || '',
            ts: n.ts || Date.now(),
            read: false,
          };
          log.push(item);
          existingIds.push(key);
          added++;
        });
        if (added) { saveLog(log); render(); updateBadge(); }
      };
      navigator.serviceWorker.controller.postMessage({ type: 'GET_NOTIFS' }, [channel.port2]);
    } catch (e) {}
  }

  // ── Cargar CodeHub Releases recientes ───────────────────
  // Los releases publicados desde el admin-hub se guardan en el backend
  // (MongoDB) y se muestran en la campana aunque el push nativo no haya
  // llegado (app cerrada o permisos sin otorgar).
  function loadReleases() {
    var backend = (typeof window.BACKEND !== 'undefined' && window.BACKEND)
      || (typeof _CH_BACKEND !== 'undefined' ? _CH_BACKEND : '')
      || 'https://codehub-98s6.onrender.com';
    if (!backend) return;
    try {
      fetch(backend + '/api/releases')
        .then(r => r.ok ? r.json() : { ok: false })
        .then(data => {
          if (!data || !data.ok || !Array.isArray(data.releases) || !data.releases.length) return;
          var log = getLog();
          var existingIds = log.map(function (n) { return n.title + '|' + n.body; });
          var added = 0;
          data.releases.forEach(function (rel) {
            var ts = new Date(rel.createdAt).getTime() || Date.now();
            var title = rel.version ? ('🚀 CodeHub ' + rel.version) : '🚀 CodeHub Release';
            var body = rel.title + (rel.body ? ' — ' + String(rel.body).slice(0, 120) : '');
            var key = title + '|' + body;
            if (existingIds.indexOf(key) !== -1) return;
            log.push({
              id: ts + '-' + Math.random().toString(36).slice(2, 7),
              title: title,
              body: body.slice(0, 180),
              type: rel.type || 'release',
              url: rel.url || '/',
              icon: '/splash/codehub.png',
              ts: ts,
              read: false,
            });
            existingIds.push(key);
            added++;
          });
          if (added) {
            log.sort(function (a, b) { return b.ts - a.ts; });
            saveLog(log);
            render();
            updateBadge();
          }
        })
        .catch(function () {});
    } catch (e) {}
  }

  // ── Escuchar Service Worker (push real / updates) ────
  function wireSW() {
    if (!('serviceWorker' in navigator)) return;
    navigator.serviceWorker.addEventListener('message', function (e) {
      var d = e.data;
      if (!d || !d.type) return;
      if (d.type === 'CH_PUSH') {
        // skipNative: el SW ya mostró la notificación del sistema
        add(d.title, d.body, d.notifType || d.type, d.url, d.icon, true);
      } else if (d.type === 'SW_UPDATED') {
        add('🔄 CodeHub actualizado', 'La aplicación se actualizó a la última versión', 'system');
      }
    });
  }

  // ── WebSocket en vivo — releases y apps nuevas sin esperar el poll ──
  // Antes la campana solo se refrescaba con loadReleases() cada 60s;
  // con esto el aviso llega apenas el backend lo emite (mismo canal
  // /ws que ya usa opensource.js), y el poll queda solo como respaldo
  // por si el socket se cae.
  var _notifWs = null, _notifWsTimer = null;
  function connectNotifWS() {
    try {
      var backend = (typeof window.BACKEND !== 'undefined' && window.BACKEND)
        || (typeof _CH_BACKEND !== 'undefined' ? _CH_BACKEND : '')
        || 'https://codehub-98s6.onrender.com';
      var wsUrl = (backend.indexOf('https://') === 0 ? 'wss://' : 'ws://') + backend.replace(/^https?:\/\//, '') + '/ws';
      _notifWs = new WebSocket(wsUrl);
      _notifWs.onmessage = function (e) {
        try {
          var msg = JSON.parse(e.data);
          if (msg.type === 'codehub_release') {
            add('🚀 CodeHub ' + (msg.version || ''), msg.title || 'Nueva actualización disponible', 'release', msg.url || '/', '/splash/codehub.png');
          } else if (msg.type === 'new_app') {
            add('🆕 ' + (msg.nombre || 'Nueva app'), 'Ya está disponible en el catálogo Open Source', 'app_update', '/opensource', '/splash/codehub.png');
          }
        } catch (e) {}
      };
      _notifWs.onclose = function () { _notifWsTimer = setTimeout(connectNotifWS, 15000); };
      _notifWs.onerror = function () { try { _notifWs.close(); } catch (e) {} };
    } catch (e) {}
  }

  // ── Init ─────────────────────────────────────────────
  function init() {
    // Escuchar clics en items y botón limpiar
    document.addEventListener('DOMContentLoaded', function () {
      var list = document.getElementById(LIST_ID);
      if (list) list.addEventListener('click', onListClick);
      var btn = document.getElementById(BTN_ID);
      if (btn) btn.addEventListener('click', toggle);
      var clsBtn = document.getElementById('notif-clear');
      if (clsBtn) clsBtn.addEventListener('click', clear);
      document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') close();
      });
      render();
      updateBadge();
      // Recuperar push recibidos con la app cerrada (un poco después,
      // cuando el SW ya esté controlando la página).
      setTimeout(loadFromSW, 1500);
      // CodeHub Releases recientes publicados desde el admin-hub.
      setTimeout(loadReleases, 2500);
      // WS en vivo para releases/apps nuevas (instantáneo).
      connectNotifWS();
      // Respaldo por si el WS se cae: poll cada 5 min en vez de 60s.
      setInterval(loadReleases, 5 * 60 * 1000);
      // Also re-render badge periodically in case SW pushed while panel was closed
      setInterval(function () { render(); updateBadge(); }, 30000);
    });
    wireSW();
  }

  init();

  // Exponer API global (el resto del proyecto la usa)
  window.chNotifLog = add;
  window.chNotifToggle = toggle;
  window.chNotifMarkAll = markAllRead;
  window.chNotifClear = clear;
})();
