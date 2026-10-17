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
          '<strong class="ch-nm__title"></strong>' +
          '<small class="ch-nm__sub"></small>' +
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

  // Sin posición guardada queda justo encima del dock flotante, midiendo su
  // alto real en cada breakpoint (no hay que replicar aquí la tabla de
  // breakpoints). Si el usuario ya lo movió, manda su posición.
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
    var dock = document.getElementById('hub-dock');
    var px = 116; // dock por defecto (desktop): bottom 28 + alto ~88
    if (dock && dock.getClientRects().length) {
      var gap = window.innerHeight - dock.getBoundingClientRect().top;
      if (gap > 0) px = Math.max(72, Math.round(gap) + 8);
    }
    root.style.bottom = px + 'px';
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
    refs.title.textContent = state.title || 'Pista en reproducción';
    refs.sub.textContent = state.artist || '';
    refs.play.innerHTML = state.playing
      ? '<svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor" aria-hidden="true"><path d="M7 5 h3 v14 h-3 z M14 5 h3 v14 h-3 z"/></svg>'
      : '<svg viewBox="0 0 24 24" width="15" height="15" fill="currentColor" aria-hidden="true"><path d="M7 5 L17 12 L7 19 Z"/></svg>';
    refs.play.setAttribute('aria-label', state.playing ? 'Pausar' : 'Reproducir');
    if (refs.live) refs.live.textContent = (state.title || 'Pista') + (state.playing ? ' — reproduciendo' : ' — en pausa');
    if (refs.fill) refs.fill.style.width = progressPct().toFixed(2) + '%';
  }

  function show() {
    if (!root) build();
    if (root.hidden) {
      root.hidden = false;
      layout();
      window.addEventListener('resize', layout);
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
