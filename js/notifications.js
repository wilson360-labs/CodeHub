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
    try { localStorage.setItem(KEY, JSON.stringify(log.slice(0, MAX))); } catch (e) {}
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
    var item = {
      id: Date.now() + '-' + Math.random().toString(36).slice(2, 7),
      title: String(title || 'CodeHub'),
      body: String(body || ''),
      type: type || 'general',
      url: url || '',
      icon: icon || '',
      ts: Date.now(),
      read: false,
    };
    var log = getLog();
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
