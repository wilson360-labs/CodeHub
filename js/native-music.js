// Control de música flotante — solo dentro del APK (WebView).
//
// El estado de reproducción lo empuja Android (MusicSessionBridge →
// window.__chMusicOnState) y los botones vuelven por CodeHubNative
// (musicTogglePlay / musicNext / musicPrev / openMusic).
//
// En el navegador nadie llama a __chMusicOnState, así que no se monta nada:
// ahí el propio Android ya trae los controles de reproducción en la barra de
// notificaciones y el reproductor web flotante (music-player.js) sigue siendo
// el dueño de los archivos locales y el streaming.

(function () {
  'use strict';

  if (window.__chNativeMusicLoaded) return;
  window.__chNativeMusicLoaded = true;

  // Sin puente nativo no hay estado que reflejar.
  if (!window.CodeHubNative) return;

  var root = null;
  var refs = {};
  var ticker = null;
  var state = {
    hasTrack: false,
    playing: false,
    title: '',
    artist: '',
    positionMs: 0,
    durationMs: -1,
    at: 0
  };

  function native(name) {
    try {
      var api = window.CodeHubNative;
      if (api && typeof api[name] === 'function') api[name]();
    } catch (e) {}
  }

  // ── Arrastre (mantener presionado 350 ms y mover) ──────────────────
  // La posición se guarda en el WebView: cada teléfono puede dejar el
  // control donde le quede cómodo, y así no se pelea con el FAB de WIL.E
  // ni con el dock. Sin posición guardada vuelve al lugar por defecto.
  var POS_KEY = 'ch:native-music-pos';
  var savedPos = null;
  var drag = { timer: null, active: false, moved: false, id: -1, sx: 0, sy: 0, cx: 0, cy: 0, ox: 0, oy: 0 };
  var suppressClick = false;
  var suppressTimer = null;

  try {
    var raw = window.localStorage.getItem(POS_KEY);
    if (raw) {
      var p = JSON.parse(raw);
      if (p && isFinite(p.l) && isFinite(p.t)) savedPos = { l: +p.l, t: +p.t };
    }
  } catch (e) {}

  // Nunca fuera del viewport (aunque roten el teléfono o abran el teclado).
  function clampPos(l, t) {
    var w = root ? root.offsetWidth : 0;
    var h = root ? root.offsetHeight : 0;
    var maxX = Math.max(0, window.innerWidth - w);
    var maxY = Math.max(0, window.innerHeight - h);
    return {
      l: Math.min(Math.max(0, l), maxX),
      t: Math.min(Math.max(0, t), maxY)
    };
  }

  // Coloca el control en coordenadas absolutas (desactiva centrado/bottom).
  function applyPos(l, t) {
    if (!root) return;
    var c = clampPos(l, t);
    root.style.left = Math.round(c.l) + 'px';
    root.style.top = Math.round(c.t) + 'px';
    root.style.right = 'auto';
    root.style.bottom = 'auto';
    root.style.transform = 'none';
  }

  // Vuelve al layout por defecto (centrado sobre el dock, vía CSS).
  function resetInlinePos() {
    if (!root) return;
    root.style.left = '';
    root.style.top = '';
    root.style.right = '';
    root.style.bottom = '';
    root.style.transform = '';
  }

  function onPointerDown(ev) {
    if (ev.pointerType === 'mouse' && ev.button !== 0) return;
    // Un gesto nuevo invalida cualquier supresión pendiente: un tap
    // legítimo nunca se pierde.
    suppressClick = false;
    if (suppressTimer) {
      window.clearTimeout(suppressTimer);
      suppressTimer = null;
    }
    if (drag.timer || drag.active) return; // un solo puntero
    drag.id = ev.pointerId;
    drag.sx = drag.cx = ev.clientX;
    drag.sy = drag.cy = ev.clientY;
    drag.moved = false;
    drag.timer = window.setTimeout(startDrag, 350);
  }

  function onPointerMove(ev) {
    drag.cx = ev.clientX;
    drag.cy = ev.clientY;
    if (drag.active) {
      if (ev.pointerId !== drag.id) return;
      if (Math.abs(drag.cx - drag.sx) > 2 || Math.abs(drag.cy - drag.sy) > 2) drag.moved = true;
      var c = clampPos(drag.cx - drag.ox, drag.cy - drag.oy);
      root.style.left = Math.round(c.l) + 'px';
      root.style.top = Math.round(c.t) + 'px';
      return;
    }
    // Antes de los 350 ms, un desplazamiento >8px es un scroll, no un arrastre.
    if (!drag.timer || ev.pointerId !== drag.id) return;
    if (Math.abs(drag.cx - drag.sx) > 8 || Math.abs(drag.cy - drag.sy) > 8) {
      window.clearTimeout(drag.timer);
      drag.timer = null;
      drag.id = -1;
    }
  }

  function startDrag() {
    drag.timer = null;
    if (!root || root.hidden) { drag.id = -1; return; }
    var r = root.getBoundingClientRect();
    drag.active = true;
    drag.moved = false;
    drag.ox = drag.cx - r.left;
    drag.oy = drag.cy - r.top;
    applyPos(r.left, r.top);          // fija left/top reales y quita el centrado
    root.style.transform = 'scale(1.03)';
    root.classList.add('ch-nm--drag');
    try { root.setPointerCapture(drag.id); } catch (e) {}
    try { if (navigator.vibrate) navigator.vibrate(12); } catch (e) {}
  }

  function endDrag() {
    var id = drag.id;
    var moved = drag.moved;
    drag.active = false;
    drag.moved = false;
    drag.id = -1;
    if (!root) return;
    root.classList.remove('ch-nm--drag');
    try { root.releasePointerCapture(id); } catch (e) {}
    if (moved) {
      var l = parseFloat(root.style.left) || 0;
      var t = parseFloat(root.style.top) || 0;
      applyPos(l, t);                 // re-clampea y quita el scale
      root.classList.add('ch-nm--placed');
      savedPos = {
        l: Math.round(parseFloat(root.style.left) || 0),
        t: Math.round(parseFloat(root.style.top) || 0)
      };
      try { window.localStorage.setItem(POS_KEY, JSON.stringify(savedPos)); } catch (e) {}
    } else if (savedPos) {
      applyPos(savedPos.l, savedPos.t); // long-press sin mover: donde estaba
    } else {
      resetInlinePos();               // ...o al sitio por defecto
      layout();
    }
    // El navegador dispara el click del botón sobre el que soltamos: se
    // descarta ese click (y por si acaso se autolimpia a los 700 ms).
    suppressClick = true;
    if (suppressTimer) window.clearTimeout(suppressTimer);
    suppressTimer = window.setTimeout(function () {
      suppressClick = false;
      suppressTimer = null;
    }, 700);
  }

  function onPointerUp(ev) {
    if (ev && ev.pointerId !== drag.id) return; // otro dedo: no es el nuestro
    if (drag.id === -1) return;
    if (drag.timer) {                  // tap normal: no hubo long-press
      window.clearTimeout(drag.timer);
      drag.timer = null;
      drag.id = -1;
      return;
    }
    if (!drag.active) return;
    endDrag();
  }

  function build() {
    var el = document.createElement('div');
    el.id = 'chNativeMusic';
    el.className = 'ch-nm';
    el.setAttribute('role', 'group');
    el.setAttribute('aria-label', 'Control de música');
    el.hidden = true;
    el.innerHTML =
      '<span class="ch-nm__bar"><i></i></span>' +
      '<span class="ch-nm__live" aria-live="polite" aria-atomic="true"></span>' +
      '<span class="ch-nm__row">' +
        '<button type="button" class="ch-nm__meta" data-act="open" aria-label="Abrir reproductor" title="Abrir reproductor">' +
          '<span class="ch-nm__cover" aria-hidden="true">' +
            '<svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M9 3 L9 17.5 A3 3 0 1 1 7 14.5 L7 7 L17 5 L17 15.5 A3 3 0 1 1 15 12.5 L15 3 z"/></svg>' +
            '<span class="ch-nm__eq"><i></i><i></i><i></i><i></i></span>' +
          '</span>' +
          '<span class="ch-nm__txt">' +
            '<strong class="ch-nm__title"></strong>' +
            '<small class="ch-nm__sub"></small>' +
          '</span>' +
        '</button>' +
        '<button type="button" class="ch-nm__btn" data-act="prev" aria-label="Anterior" title="Anterior">' +
          '<svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" aria-hidden="true"><path d="M18 5 L10 12 L18 19 Z M6 5 h2 v14 h-2 z"/></svg></button>' +
        '<button type="button" class="ch-nm__btn ch-nm__play" data-act="toggle" aria-label="Reproducir" title="Reproducir">' +
          '<svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor" aria-hidden="true"><path d="M7 5 L17 12 L7 19 Z"/></svg></button>' +
        '<button type="button" class="ch-nm__btn" data-act="next" aria-label="Siguiente" title="Siguiente">' +
          '<svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" aria-hidden="true"><path d="M6 5 L14 12 L6 19 Z M16 5 h2 v14 h-2 z"/></svg></button>' +
      '</span>';

    refs.title = el.querySelector('.ch-nm__title');
    refs.sub = el.querySelector('.ch-nm__sub');
    refs.fill = el.querySelector('.ch-nm__bar i');
    refs.play = el.querySelector('.ch-nm__play');
    refs.live = el.querySelector('.ch-nm__live');

    // Delegación: un solo listener para los cuatro botones.
    el.addEventListener('click', function (ev) {
      // Si venimos de un arrastre, el click de soltar no cuenta.
      if (suppressClick) {
        suppressClick = false;
        if (suppressTimer) {
          window.clearTimeout(suppressTimer);
          suppressTimer = null;
        }
        ev.preventDefault();
        ev.stopPropagation();
        return;
      }
      var t = ev && ev.target;
      var btn = t && t.closest ? t.closest('[data-act]') : null;
      if (!btn || !el.contains(btn)) return;
      var act = btn.getAttribute('data-act');
      if (act === 'toggle') native('musicTogglePlay');
      else if (act === 'next') native('musicNext');
      else if (act === 'prev') native('musicPrev');
      else if (act === 'open') native('openMusic');
    });

    // Arrastre por long-press: los movimientos se escuchan en document
    // para no perder el gesto si el dedo sale del control.
    el.addEventListener('pointerdown', onPointerDown);
    el.addEventListener('contextmenu', function (ev) { ev.preventDefault(); });
    document.addEventListener('pointermove', onPointerMove);
    document.addEventListener('pointerup', onPointerUp);
    document.addEventListener('pointercancel', onPointerUp);

    if (savedPos) el.classList.add('ch-nm--placed');

    document.body.appendChild(el);
    root = el;
  }

  // Sin posición guardada queda justo encima del flotante de clima
  // (o del dock si no existe). Así no queda escondido y deja ver bien
  // ambos controles. Si el usuario ya lo movió, manda su posición.
  function layout() {
    if (!root) return;
    if (drag.active) { // resize a mitad de arrastre: solo re-clampear
      var d = clampPos(parseFloat(root.style.left) || 0, parseFloat(root.style.top) || 0);
      root.style.left = Math.round(d.l) + 'px';
      root.style.top = Math.round(d.t) + 'px';
      return;
    }
    if (savedPos) {
      applyPos(savedPos.l, savedPos.t);
      return;
    }
    var px = 116;
    // Prioridad: widget de clima flotante (.chw-widget / .chw-panel)
    var wx = document.querySelector('.chw-widget, #chw-widget, .chw-panel');
    if (wx && wx.getClientRects().length) {
      var wr = wx.getBoundingClientRect();
      if (wr.top > 0) {
        var gapWx = window.innerHeight - wr.top;
        if (gapWx > 0) px = Math.max(92, Math.round(gapWx) + 14);
      }
    }
    // Fallback: hub-dock
    if (px === 116) {
      var dock = document.getElementById('hub-dock');
      if (dock && dock.getClientRects().length) {
        var gap = window.innerHeight - dock.getBoundingClientRect().top;
        if (gap > 0) px = Math.max(92, Math.round(gap) + 14);
      }
    }
    root.style.left = '';
    root.style.right = 'auto';
    root.style.transform = '';
    root.style.bottom = px + 'px';
  }

  function showMoveTipOnce() {
    try {
      if (localStorage.getItem('ch:nm-move-tip-shown')) return;
      localStorage.setItem('ch:nm-move-tip-shown', '1');
    } catch (e) {}
    if (!document.body) return;
    var tip = document.createElement('div');
    tip.className = 'ch-nm-tip';
    tip.textContent = 'Mantén presionado el reproductor para moverlo de posición';
    document.body.appendChild(tip);
    setTimeout(function () {
      if (tip && tip.parentNode) tip.parentNode.removeChild(tip);
    }, 3200);
  }

  function progressPct() {
    var d = state.durationMs;
    if (!(d > 0)) return 0;
    var p = state.positionMs + (state.playing ? Date.now() - state.at : 0);
    if (p < 0) p = 0;
    if (p > d) p = d;
    return (p / d) * 100;
  }

  function render() {
    if (!root) return;
    // textContent, nunca innerHTML: el título/artista vienen del nativo.
    root.setAttribute('data-playing', state.playing ? '1' : '0');
    refs.title.textContent = state.title || 'Pista en reproducción';
    refs.sub.textContent = state.artist || '';
    refs.play.innerHTML = state.playing
      ? '<svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor" aria-hidden="true"><path d="M7 5 h3 v14 h-3 z M14 5 h3 v14 h-3 z"/></svg>'
      : '<svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor" aria-hidden="true"><path d="M7 5 L17 12 L7 19 Z"/></svg>';
    refs.play.setAttribute('aria-label', state.playing ? 'Pausar' : 'Reproducir');
    if (refs.live) refs.live.textContent = (state.title || 'Pista') + (state.playing ? ' — reproduciendo' : ' — en pausa');
    if (refs.fill) refs.fill.style.width = progressPct().toFixed(2) + '%';
  }

  function showMoveDialogOnce() {
    try {
      if (localStorage.getItem('ch:nm-move-dialog-shown')) return;
      localStorage.setItem('ch:nm-move-dialog-shown', '1');
    } catch (e) {}
    if (!document.body) return;
    var ov = document.createElement('div');
    ov.className = 'ch-nm-move-dlg';
    ov.innerHTML = '<div class="ch-nm-move-dlg__card"><div class="ch-nm-move-dlg__icon"><svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor"><path d="M12 2a1 1 0 0 1 .894.553l2 4A1 1 0 0 1 14 8H10a1 1 0 0 1-.894-1.447l2-4A1 1 0 0 1 12 2zm-4.447 9.553a1 1 0 0 1 1.447.894v4a1 1 0 0 1-1.447.894l-4-2A1 1 0 0 1 3 14v-4a1 1 0 0 1 .553-.894l4-2zm8.894 0 4 2A1 1 0 0 1 21 12v4a1 1 0 0 1-.553.894l-4 2A1 1 0 0 1 15 18v-4a1 1 0 0 1 .447-1.447zM12 22a1 1 0 0 1-.894-.553l-2-4A1 1 0 0 1 10 16h4a1 1 0 0 1 .894 1.447l-2 4A1 1 0 0 1 12 22z"/></svg></div><div class="ch-nm-move-dlg__text"><strong>Reproductor movible</strong><small>Mantén presionado el reproductor para cambiarlo de posición.</small></div><button class="ch-nm-move-dlg__btn" type="button">Entendido</button></div>';
    document.body.appendChild(ov);
    function close() {
      if (!ov || !ov.parentNode) return;
      ov.classList.add('is-hide');
      setTimeout(function () { if (ov.parentNode) ov.parentNode.removeChild(ov); }, 220);
    }
    ov.querySelector('.ch-nm-move-dlg__btn').addEventListener('click', close);
    ov.addEventListener('click', function (e) { if (e.target === ov) close(); });
    setTimeout(close, 3800);
  }

  function show() {
    if (!root) build();
    if (root.hidden) {
      root.hidden = false;
      layout();
      window.addEventListener('resize', layout);
      showMoveDialogOnce();
    }
    if (!ticker) {
      // Solo mientras se ve y hay pista: la barra avanza sola sin pedirle
      // nada al nativo (la posición exacta se re-sincroniza en cada push).
      ticker = window.setInterval(function () {
        if (refs.fill) refs.fill.style.width = progressPct().toFixed(2) + '%';
      }, 1000);
    }
    render();
  }

  function hide() {
    if (!root) return;
    if (!root.hidden) {
      root.hidden = true;
      window.removeEventListener('resize', layout);
    }
    if (ticker) {
      window.clearInterval(ticker);
      ticker = null;
    }
  }

  // Entrada única del estado nativo.
  window.__chMusicOnState = function (s) {
    if (!s) return;
    state.hasTrack = !!s.hasTrack;
    state.playing = !!s.playing;
    state.title = s.title ? String(s.title) : '';
    state.artist = s.artist ? String(s.artist) : '';
    state.positionMs = Number(s.positionMs) > 0 ? Number(s.positionMs) : 0;
    state.durationMs = Number(s.durationMs) > 0 ? Number(s.durationMs) : -1;
    state.at = Date.now();
    if (!state.hasTrack) {
      hide();
      return;
    }
    show();
  };

  // La página se cargó con la app ya en primer plano: pide el estado vigente
  // para no depender del último empuje nativo (que pudo no llegar).
  native('musicRequestState');
})();
