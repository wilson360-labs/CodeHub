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

  function build() {
    var el = document.createElement('div');
    el.id = 'chNativeMusic';
    el.className = 'ch-nm';
    el.setAttribute('role', 'group');
    el.setAttribute('aria-label', 'Control de música');
    el.hidden = true;
    el.innerHTML =
      '<span class="ch-nm__bar"><i></i></span>' +
      '<span class="ch-nm__row">' +
        '<button type="button" class="ch-nm__meta" data-act="open" aria-label="Abrir reproductor" title="Abrir reproductor">' +
          '<strong class="ch-nm__title"></strong>' +
          '<small class="ch-nm__sub"></small>' +
        '</button>' +
        '<button type="button" class="ch-nm__btn" data-act="prev" aria-label="Anterior" title="Anterior">' +
          '<i class="fas fa-backward-step" aria-hidden="true"></i></button>' +
        '<button type="button" class="ch-nm__btn ch-nm__play" data-act="toggle" aria-label="Reproducir" title="Reproducir">' +
          '<i class="fas fa-play" aria-hidden="true"></i></button>' +
        '<button type="button" class="ch-nm__btn" data-act="next" aria-label="Siguiente" title="Siguiente">' +
          '<i class="fas fa-forward-step" aria-hidden="true"></i></button>' +
      '</span>';

    refs.title = el.querySelector('.ch-nm__title');
    refs.sub = el.querySelector('.ch-nm__sub');
    refs.fill = el.querySelector('.ch-nm__bar i');
    refs.play = el.querySelector('.ch-nm__play');

    // Delegación: un solo listener para los cuatro botones.
    el.addEventListener('click', function (ev) {
      var t = ev && ev.target;
      var btn = t && t.closest ? t.closest('[data-act]') : null;
      if (!btn || !el.contains(btn)) return;
      var act = btn.getAttribute('data-act');
      if (act === 'toggle') native('musicTogglePlay');
      else if (act === 'next') native('musicNext');
      else if (act === 'prev') native('musicPrev');
      else if (act === 'open') native('openMusic');
    });

    document.body.appendChild(el);
    root = el;
  }

  // Queda justo encima del dock flotante, midiendo su alto real en cada
  // breakpoint (no hay que replicar aquí la tabla de breakpoints).
  function layout() {
    if (!root) return;
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
      ? '<i class="fas fa-pause" aria-hidden="true"></i>'
      : '<i class="fas fa-play" aria-hidden="true"></i>';
    refs.play.setAttribute('aria-label', state.playing ? 'Pausar' : 'Reproducir');
    refs.fill.style.width = progressPct().toFixed(2) + '%';
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
