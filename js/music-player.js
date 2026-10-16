// Reproductor multimedia flotante de CodeHub — v2
// Local (File API) + streaming (URL) + portada + shuffle/repeat + atajos + export/import.

(function () {
  'use strict';

  if (window.__chMusicPlayerLoaded) return;
  window.__chMusicPlayerLoaded = true;

  var STORAGE_KEY = 'ch:music-player-v2';
  var maxFiles = 250;
  var queue = [];
  var currentIndex = 0;
  var shuffleMode = false;
  var repeatMode = 0; // 0 = off, 1 = all, 2 = one
  var dragging = false;
  var startPointer = { x: 0, y: 0 };
  var startPlayer = { left: 0, top: 0 };
  var playerRoot = null;
  var audioEl = null;
  var refs = {};
  var lastVolume = 0.7;

  var escapeHtml = function (v) {
    return String(v || '').replace(/[&<>"']/g, function (c) {
      return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
    });
  };

  function injectStyles() {
    if (document.getElementById('ch-music-player-css')) return;
    var st = document.createElement('style');
    st.id = 'ch-music-player-css';
    st.textContent = [
      '#chMusicPlayerRoot{position:fixed;width:min(380px,calc(100vw - 20px));z-index:2147483000;right:14px;bottom:14px;font-family:"Space Grotesk",system-ui,sans-serif;user-select:none;transition:left .18s ease,top .18s ease,opacity .18s ease}',
      '#chMusicPlayerRoot.chmp--dragging{transition:none!important}',
      '#chMusicPlayerRoot.chmp--minimized{width:78px!important}',
      '#chMusicPlayerRoot.chmp--hidden{display:none!important}',
      '.chmp__launcher,.chmp__panel{background:rgba(10,10,22,.94);border:1px solid rgba(47,128,237,.35);box-shadow:0 18px 54px rgba(0,0,0,.38),0 0 0 1px rgba(255,255,255,.04) inset;backdrop-filter:blur(12px);-webkit-backdrop-filter:blur(12px);color:#e8e8f0;border-radius:18px}',
      '.chmp__launcher{width:58px;height:58px;border-radius:50%!important;display:grid;place-items:center;margin-left:auto;cursor:pointer;border:none!important;background:linear-gradient(135deg,#2f80ed,#00b894)!important;color:#fff!important;box-shadow:0 10px 28px rgba(0,0,0,.32)!important;font-size:1.2rem}',
      '.chmp__panel{padding:14px;margin-top:10px;overflow:hidden}',
      '.chmp__header{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:10px;cursor:move;touch-action:none}',
      '.chmp__header-info{display:flex;align-items:center;gap:10px;min-width:0;flex:1}',
      '.chmp__cover{width:48px;height:48px;border-radius:10px;object-fit:cover;background:linear-gradient(135deg,#1a1a2e,#2f80ed33);border:1px solid rgba(255,255,255,.1);flex-shrink:0;display:grid;place-items:center;font-size:1.1rem;color:#38bdf8}',
      '.chmp__title{min-width:0;display:grid;gap:2px}',
      '.chmp__title strong{font-size:.88rem;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
      '.chmp__title small{color:rgba(232,232,240,.55);font-size:.68rem;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}',
      '.chmp__actions{display:flex;gap:5px;flex-shrink:0}',
      '.chmp__icon-btn{width:30px;height:30px;border-radius:999px;border:1px solid rgba(255,255,255,.1);background:rgba(255,255,255,.05);color:#fff;display:grid;place-items:center;cursor:pointer;padding:0;font-size:.78rem}',
      '.chmp__icon-btn:hover{background:rgba(255,255,255,.1)}',
      '.chmp__icon-btn.chmp--active{background:rgba(47,128,237,.35);border-color:rgba(47,128,237,.6)}',
      '.chmp__progress{width:100%;accent-color:#2f80ed;cursor:pointer;margin:6px 0 6px;height:20px}',
      '.chmp__meta{display:flex;justify-content:space-between;color:rgba(232,232,240,.5);font-size:.68rem;font-family:"JetBrains Mono",monospace}',
      '.chmp__controls{display:flex;align-items:center;justify-content:center;gap:10px;margin:10px 0 6px}',
      '.chmp__btn{border:none;border-radius:999px;padding:8px 12px;background:rgba(255,255,255,.08);color:#fff;cursor:pointer;font-size:.85rem;min-width:36px;display:grid;place-items:center}',
      '.chmp__btn:hover{background:rgba(255,255,255,.14)}',
      '.chmp__btn--primary{background:linear-gradient(135deg,#2f80ed,#00b894)!important;min-width:52px;height:52px;font-size:1.15rem;border-radius:50%!important;padding:0!important}',
      '.chmp__btn--primary:hover{filter:brightness(1.12)}',
      '.chmp__field{display:grid;gap:5px;margin-bottom:8px}',
      '.chmp__field label{font-size:.68rem;color:rgba(232,232,240,.58)}',
      '.chmp__input{width:100%;border-radius:10px;border:1px solid rgba(255,255,255,.12);background:rgba(255,255,255,.05);color:#fff;padding:7px 10px;font-size:.82rem;box-sizing:border-box}',
      '.chmp__row{display:flex;gap:5px;align-items:center;flex-wrap:wrap}',
      '.chmp__btn-small{border-radius:9px;border:1px solid rgba(255,255,255,.12);background:rgba(255,255,255,.05);color:#fff;padding:6px 9px;cursor:pointer;font-size:.72rem;white-space:nowrap}',
      '.chmp__btn-small:hover{background:rgba(255,255,255,.14)}',
      '.chmp__playlist{max-height:150px;overflow:auto;border-radius:10px;border:1px solid rgba(255,255,255,.08);background:rgba(255,255,255,.03);margin-bottom:8px}',
      '.chmp__playlist-item{display:flex;align-items:center;justify-content:space-between;gap:6px;padding:6px 9px;cursor:pointer;border-bottom:1px solid rgba(255,255,255,.05);font-size:.76rem}',
      '.chmp__playlist-item:last-child{border-bottom:none}',
      '.chmp__playlist-item:hover{background:rgba(255,255,255,.07)}',
      '.chmp__playlist-item.chmp--active{background:rgba(47,128,237,.18);color:#fff}',
      '.chmp__playlist-item b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;min-width:0}',
      '.chmp__playlist-item .chmp__tag{font-size:.62rem;color:rgba(232,232,240,.44);flex-shrink:0;display:flex;gap:4px;align-items:center}',
      '.chmp__playlist-item .chmp__del{background:none;border:none;color:rgba(255,255,255,.25);cursor:pointer;font-size:.68rem;padding:2px 4px;flex-shrink:0}',
      '.chmp__playlist-item .chmp__del:hover{color:#f87171}',
      '.chmp__status{font-size:.7rem;color:rgba(232,232,240,.58);margin-bottom:7px;min-height:1.1em}',
      '.chmp__drop{border:1px dashed rgba(47,128,237,.25);border-radius:10px;padding:7px;font-size:.72rem;color:rgba(232,232,240,.55);margin-bottom:8px;text-align:center}',
      '#chMusicPlayerRoot.chmp--minimized .chmp__panel{display:none}',
      '#chMusicPlayerRoot[data-theme="light"] .chmp__launcher,#chMusicPlayerRoot[data-theme="light"] .chmp__panel{background:rgba(248,250,255,.95);color:#1a1a2e;border-color:rgba(47,128,237,.25)}',
      '#chMusicPlayerRoot[data-theme="light"] .chmp__title small,#chMusicPlayerRoot[data-theme="light"] .chmp__meta,#chMusicPlayerRoot[data-theme="light"] .chmp__status,#chMusicPlayerRoot[data-theme="light"] .chmp__field label{color:rgba(26,26,46,.55)}',
      '#chMusicPlayerRoot[data-theme="light"] .chmp__icon-btn,#chMusicPlayerRoot[data-theme="light"] .chmp__btn,#chMusicPlayerRoot[data-theme="light"] .chmp__btn-small,#chMusicPlayerRoot[data-theme="light"] .chmp__input{border-color:rgba(47,128,237,.18);background:rgba(47,128,237,.06);color:#1a1a2e}',
      '#chMusicPlayerRoot[data-theme="light"] .chmp__icon-btn:hover,#chMusicPlayerRoot[data-theme="light"] .chmp__btn:hover{background:rgba(47,128,237,.12)}',
      '#chMusicPlayerRoot[data-theme="light"] .chmp__playlist{background:rgba(47,128,237,.04);border-color:rgba(47,128,237,.12)}',
      '#chMusicPlayerRoot[data-theme="light"] .chmp__playlist-item:hover{background:rgba(47,128,237,.08)}',
      '#chMusicPlayerRoot[data-theme="light"] .chmp__playlist-item.chmp--active{background:rgba(47,128,237,.14);color:#1a1a2e}',
      '#chMusicPlayerRoot[data-theme="light"] .chmp__playlist-item .chmp__del{color:rgba(26,26,46,.3)}',
      '#chMusicPlayerRoot[data-theme="light"] .chmp__drop{border-color:rgba(47,128,237,.2);color:rgba(26,26,46,.5)}',
      '#chMusicPlayerRoot[data-theme="light"] .chmp__cover{background:linear-gradient(135deg,#e8efff,#d0e4ff);border-color:rgba(47,128,237,.15)}',
      '#chMusicPlayerRoot[data-theme="light"] .chmp__icon-btn.chmp--active{background:rgba(47,128,237,.2)}',
      '@media(max-width:430px){#chMusicPlayerRoot{right:6px;bottom:6px;width:min(340px,calc(100vw - 12px))}.chmp__launcher{width:54px;height:54px!important}.chmp__cover{width:40px;height:40px}}',
    ].join('\n');
    document.head.appendChild(st);
  }

  function formatTime(s) {
    if (!Number.isFinite(s) || s < 0) return '0:00';
    var m = Math.floor(s / 60);
    var r = Math.floor(s % 60);
    return m + ':' + String(r).padStart(2, '0');
  }

  function storageLoad() {
    try {
      var raw = localStorage.getItem(STORAGE_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  }

  function storageSave(state) {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify(state)); } catch (e) {}
  }

  function makeTrack(name, address, local, size, coverData) {
    return {
      id: 't_' + Math.random().toString(36).slice(2, 10),
      name: name, address: address, local: !!local,
      size: size || 0, loaded: false, cover: coverData || ''
    };
  }

  function pickCover(file) {
    if (!file) return '';
    var isImg = /^image\//.test(file.type || '');
    var isAudio = /^audio\//.test(file.type || '');
    if (isImg) return URL.createObjectURL(file);
    if (isAudio) {
      var name = file.name.replace(/\.[^.]+$/, '').replace(/[_]+/g, ' ').toLowerCase();
      return 'data:image/svg+xml;utf8,' + encodeURIComponent(
        '<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96"><rect width="96" height="96" fill="#1a1a2e"/><text x="50%" y="55%" text-anchor="middle" font-size="36" fill="#38bdf8" font-family="sans-serif">' +
        escapeHtml(name.slice(0, 2).toUpperCase()) + '</text></svg>'
      );
    }
    return '';
  }

  function detectPageTheme() {
    var html = document.documentElement;
    var theme = html.getAttribute('data-theme') || html.getAttribute('data-bs-theme') || '';
    if (theme === 'light' || theme === 'dark') return theme;
    if (window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches) return 'light';
    return 'dark';
  }

  function applyTheme() {
    if (!playerRoot) return;
    var theme = detectPageTheme();
    playerRoot.setAttribute('data-theme', theme);
  }

  function clampPos() {
    if (!playerRoot || playerRoot.classList.contains('chmp--minimized')) return;
    var rect = playerRoot.getBoundingClientRect();
    var maxL = Math.max(0, window.innerWidth - rect.width - 6);
    var maxT = Math.max(0, window.innerHeight - rect.height - 6);
    var l = parseFloat(playerRoot.style.left);
    var t = parseFloat(playerRoot.style.top);
    if (!Number.isFinite(l)) l = window.innerWidth - rect.width - 14;
    if (!Number.isFinite(t)) t = window.innerHeight - rect.height - 14;
    playerRoot.style.left = Math.min(Math.max(6, l), maxL) + 'px';
    playerRoot.style.top = Math.min(Math.max(6, t), maxT) + 'px';
    playerRoot.style.right = 'auto';
    playerRoot.style.bottom = 'auto';
  }

  function buildUI() {
    playerRoot = document.createElement('div');
    playerRoot.id = 'chMusicPlayerRoot';
    playerRoot.className = 'chmp--minimized';
    playerRoot.setAttribute('data-theme', detectPageTheme());
    playerRoot.style.left = Math.max(6, window.innerWidth - 92) + 'px';
    playerRoot.style.top = Math.max(6, window.innerHeight - 74) + 'px';
    playerRoot.style.right = 'auto';
    playerRoot.style.bottom = 'auto';
    playerRoot.innerHTML =
      '<button class="chmp__launcher" type="button" aria-label="Abrir reproductor de música" title="Música"><i class="fas fa-music" aria-hidden="true"></i></button>' +
      '<div class="chmp__panel">' +
        '<div class="chmp__header" data-drag-handle="true">' +
          '<div class="chmp__header-info">' +
            '<div class="chmp__cover" id="chmpCover"><i class="fas fa-music" aria-hidden="true"></i></div>' +
            '<div class="chmp__title"><strong id="chmpTitle">Reproductor CodeHub</strong><small id="chmpMeta">Local o streaming</small></div>' +
          '</div>' +
          '<div class="chmp__actions">' +
            '<button class="chmp__icon-btn" data-role="shuffle" title="Aleatorio" aria-label="Aleatorio"><i class="fas fa-shuffle"></i></button>' +
            '<button class="chmp__icon-btn" data-role="repeat" title="Repetir" aria-label="Repetir"><i class="fas fa-redo"></i></button>' +
            '<button class="chmp__icon-btn" data-role="minimize" title="Minimizar" aria-label="Minimizar"><i class="fas fa-minus"></i></button>' +
            '<button class="chmp__icon-btn" data-role="close" title="Cerrar" aria-label="Cerrar"><i class="fas fa-xmark"></i></button>' +
          '</div>' +
        '</div>' +
        '<p class="chmp__status" id="chmpStatus">Atajos: Espacio=play · ←/→=seek · ↑/↓=volumen</p>' +
        '<audio id="chmpAudio" preload="metadata" playsinline></audio>' +
        '<div class="chmp__controls">' +
          '<button class="chmp__btn" data-role="prev" title="Anterior" aria-label="Anterior"><i class="fas fa-backward-step"></i></button>' +
          '<button class="chmp__btn chmp__btn--primary" data-role="play" title="Play/Pause" aria-label="Reproducir"><i class="fas fa-play"></i></button>' +
          '<button class="chmp__btn" data-role="next" title="Siguiente" aria-label="Siguiente"><i class="fas fa-forward-step"></i></button>' +
        '</div>' +
        '<input class="chmp__progress" data-role="seek" type="range" min="0" max="100" value="0" step="0.1" aria-label="Progreso">' +
        '<div class="chmp__meta"><span id="chmpTime">0:00 / 0:00</span><span id="chmpVolLabel">Vol 70%</span></div>' +
        '<input class="chmp__progress" data-role="vol" type="range" min="0" max="1" value="0.7" step="0.01" aria-label="Volumen">' +
        '<div class="chmp__drop">Arrastra archivos o pega una URL de streaming</div>' +
        '<div class="chmp__field"><label for="chmpFiles">▸ Archivos locales</label>' +
          '<input id="chmpFiles" class="chmp__input" type="file" accept="audio/*,image/*" multiple></div>' +
        '<div class="chmp__field"><label for="chmpStream">▸ Streaming (URL)</label>' +
          '<div class="chmp__row"><input id="chmpStream" class="chmp__input" type="url" placeholder="https://…/radio.mp3">' +
          '<button class="chmp__btn-small" data-role="addStream">Agregar</button></div></div>' +
        '<div class="chmp__row" style="margin-bottom:8px">' +
          '<button class="chmp__btn-small" data-role="export">⬇ Exportar</button>' +
          '<button class="chmp__btn-small" data-role="import">⬆ Importar</button>' +
          '<button class="chmp__btn-small" data-role="clear">🗑 Limpiar</button>' +
          '<input id="chmpImportFile" type="file" accept="application/json,.json" style="display:none">' +
        '</div>' +
        '<div class="chmp__playlist" id="chmpPlaylist" aria-label="Lista de reproducción"></div>' +
      '</div>';
    document.body.appendChild(playerRoot);

    refs = {
      audio: playerRoot.querySelector('#chmpAudio'),
      title: playerRoot.querySelector('#chmpTitle'),
      meta: playerRoot.querySelector('#chmpMeta'),
      status: playerRoot.querySelector('#chmpStatus'),
      seek: playerRoot.querySelector('[data-role="seek"]'),
      vol: playerRoot.querySelector('[data-role="vol"]'),
      volLabel: playerRoot.querySelector('#chmpVolLabel'),
      time: playerRoot.querySelector('#chmpTime'),
      playlist: playerRoot.querySelector('#chmpPlaylist'),
      files: playerRoot.querySelector('#chmpFiles'),
      stream: playerRoot.querySelector('#chmpStream'),
      importFile: playerRoot.querySelector('#chmpImportFile'),
      launcher: playerRoot.querySelector('.chmp__launcher'),
      header: playerRoot.querySelector('.chmp__header'),
      cover: playerRoot.querySelector('#chmpCover'),
      play: playerRoot.querySelector('[data-role="play"]'),
      prev: playerRoot.querySelector('[data-role="prev"]'),
      next: playerRoot.querySelector('[data-role="next"]'),
      shuffle: playerRoot.querySelector('[data-role="shuffle"]'),
      repeat: playerRoot.querySelector('[data-role="repeat"]'),
      addStream: playerRoot.querySelector('[data-role="addStream"]'),
      export: playerRoot.querySelector('[data-role="export"]'),
      import: playerRoot.querySelector('[data-role="import"]'),
      clear: playerRoot.querySelector('[data-role="clear"]'),
      drop: playerRoot.querySelector('.chmp__drop')
    };

    refs.audio.volume = 0.7;
  }

  function setStatus(t) { refs.status.textContent = t; }

  function setPlayIcon(playing) {
    refs.play.innerHTML = playing ? '<i class="fas fa-pause"></i>' : '<i class="fas fa-play"></i>';
    refs.play.setAttribute('aria-label', playing ? 'Pausar' : 'Reproducir');
  }

  function updateVolLabel() {
    refs.volLabel.textContent = 'Vol ' + Math.round(refs.audio.volume * 100) + '%';
  }

  function updateCover(track) {
    if (!track || !track.cover) {
      refs.cover.innerHTML = '<i class="fas fa-music" aria-hidden="true"></i>';
      return;
    }
    var img = document.createElement('img');
    img.className = 'chmp__cover';
    img.src = track.cover;
    img.alt = 'Portada de ' + track.name;
    img.onerror = function () {
      refs.cover.innerHTML = '<i class="fas fa-music" aria-hidden="true"></i>';
    };
    var old = refs.cover;
    if (old && old.parentNode) old.parentNode.replaceChild(img, old);
    refs.cover = img;
  }

  function renderPlaylist() {
    if (!queue.length) {
      refs.playlist.innerHTML = '<div class="chmp__playlist-item"><b>Sin pistas</b><span>Agrega archivos o URL</span></div>';
      return;
    }
    refs.playlist.innerHTML = queue.map(function (t, i) {
      var active = i === currentIndex ? ' chmp--active' : '';
      var tag = t.local ? 'local' : 'stream';
      var coverBtn = '';
      return '<div class="chmp__playlist-item' + active + '" data-index="' + i + '">' +
        '<b>' + escapeHtml(t.name) + '</b>' +
        '<span class="chmp__tag">' + tag +
          (queue.length > 1 ? ' <button class="chmp__del" data-del="' + i + '" title="Quitar">✕</button>' : '') +
        '</span></div>';
    }).join('');
  }

  function shufflePick() {
    if (shuffleMode && queue.length > 1) {
      var idx;
      do { idx = Math.floor(Math.random() * queue.length); } while (idx === currentIndex);
      return idx;
    }
    return currentIndex + 1;
  }

  function setTrack(index, autoplay) {
    if (!queue.length) {
      refs.audio.removeAttribute('src');
      refs.title.textContent = 'Reproductor CodeHub';
      refs.meta.textContent = 'Local o streaming';
      refs.time.textContent = '0:00 / 0:00';
      updateCover(null);
      setPlayIcon(false);
      setStatus('Agrega archivos o una URL.');
      return;
    }
    currentIndex = (index + queue.length) % queue.length;
    var track = queue[currentIndex];
    refs.audio.src = track.address;
    refs.audio.load();
    refs.title.textContent = track.name;
    refs.meta.textContent = track.local ? 'Archivo local' : 'Streaming';
    updateCover(track);
    setStatus(track.local ? 'Pista local lista.' : 'Streaming preparado.');
    renderPlaylist();
    if ('mediaSession' in navigator) {
      try {
        navigator.mediaSession.metadata = new MediaMetadata({
          title: track.name, artist: 'CodeHub',
          album: track.local ? 'Archivo local' : 'Streaming',
          artwork: track.cover && track.cover.indexOf('data:') !== 0
            ? [{ src: track.cover, sizes: '96x96', type: 'image/jpeg' }]
            : [{ src: track.cover, sizes: '96x96' }]
        });
        navigator.mediaSession.setActionHandler('play', function () { playTrack(); });
        navigator.mediaSession.setActionHandler('pause', function () { pauseTrack(); });
        navigator.mediaSession.setActionHandler('previoustrack', function () { prevTrack(true); });
        navigator.mediaSession.setActionHandler('nexttrack', function () { nextTrack(true); });
      } catch (e) {}
    }
    if (autoplay) playTrack();
  }

  function playTrack() {
    var p = refs.audio.play();
    if (p && typeof p.then === 'function') {
      p.then(function () { setPlayIcon(true); setStatus('Reproduciendo.'); updateMS(); })
       .catch(function (err) { setPlayIcon(false); setStatus('No se pudo iniciar: ' + (err && err.message ? err.message : 'formato/permiso')); });
    }
  }

  function pauseTrack() {
    refs.audio.pause();
    setPlayIcon(false);
    setStatus('En pausa.');
  }

  function nextTrack(autoplay) {
    if (!queue.length) return;
    if (repeatMode === 2) { setTrack(currentIndex, autoplay !== false); return; }
    var idx = shufflePick();
    if (idx >= queue.length) { setTrack(0, autoplay !== false); return; }
    setTrack(idx, autoplay !== false);
  }

  function prevTrack(autoplay) {
    if (!queue.length) return;
    setTrack(currentIndex - 1, autoplay !== false);
  }

  function updateMS() {
    if ('mediaSession' in navigator) {
      try { navigator.mediaSession.playbackState = refs.audio.paused ? 'paused' : 'playing'; } catch (e) {}
    }
  }

  function expand() { playerRoot.classList.remove('chmp--minimized'); remember(); clampPos(); }
  function minimize() { playerRoot.classList.add('chmp--minimized'); remember(); }
  function closePlayer() { playerRoot.classList.add('chmp--minimized'); setStatus('Cerrado — icono flotante disponible.'); remember(); }
  function remember() { storageSave(storageFormat()); }

  function storageFormat() {
    return {
      minimized: playerRoot.classList.contains('chmp--minimized'),
      volume: refs.audio.volume,
      shuffle: shuffleMode,
      repeat: repeatMode
    };
  }

  function dropFiles(files) {
    if (!files || !files.length) return;
    var added = 0;
    var pendingCover = null;
    for (var i = 0; i < files.length; i++) {
      var f = files[i];
      if (/^audio\//.test(f.type)) {
        if (queue.length >= maxFiles) continue;
        queue.push(makeTrack(f.name.replace(/\.[^.]+$/, ''), URL.createObjectURL(f), true, f.size, pickCover(f)));
        added++;
      } else if (/^image\//.test(f.type) && pendingCover === null) {
        pendingCover = URL.createObjectURL(f);
      }
    }
    if (pendingCover && queue.length) queue[0].cover = pendingCover;
    if (added > 0) {
      setStatus(added + ' pista(s) agregada(s).');
      if (!refs.audio.src) setTrack(currentIndex, false);
      renderPlaylist();
      if (playerRoot.classList.contains('chmp--minimized')) expand();
    }
  }

  function deriveName(url) {
    try {
      var p = new URL(url);
      var last = p.pathname.split('/').pop();
      if (last && last.indexOf('.') !== -1) return decodeURIComponent(last);
    } catch (e) {}
    return 'Stream ' + (queue.filter(function (t) { return !t.local; }).length + 1);
  }

  function addStream() {
    var v = (refs.stream.value || '').trim();
    if (!v) { setStatus('Escribe una URL de streaming.'); return; }
    queue.push(makeTrack(deriveName(v), v, false, 0, ''));
    setTrack(queue.length - 1, false);
    refs.stream.value = '';
    expand();
    setStatus('Streaming agregado.');
  }

  function exportPlaylist() {
    var data = queue.filter(function (t) { return !t.local; }).map(function (t) {
      return { name: t.name, url: t.address };
    });
    if (!data.length) { setStatus('Solo hay archivos locales — nada que exportar.'); return; }
    var blob = new Blob([JSON.stringify({ v: 1, tracks: data }, null, 2)], { type: 'application/json' });
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'codehub-playlist.json';
    a.click();
    setTimeout(function () { URL.revokeObjectURL(a.href); }, 1000);
    setStatus('Playlist exportada (' + data.length + ' stream(s)).');
  }

  function importPlaylist(file) {
    var reader = new FileReader();
    reader.onload = function (e) {
      try {
        var json = JSON.parse(e.target.result);
        var list = json && json.tracks && Array.isArray(json.tracks) ? json.tracks : [];
        var n = 0;
        list.forEach(function (t) {
          if (t && t.url) { queue.push(makeTrack(t.name || deriveName(t.url), t.url, false, 0, '')); n++; }
        });
        setStatus(n + ' stream(s) importados.');
        if (n > 0 && !refs.audio.src) setTrack(0, false);
        renderPlaylist();
      } catch (err) { setStatus('JSON inválido.'); }
    };
    reader.readAsText(file);
  }

  function clearQueue() {
    queue.forEach(function (t) {
      if (t.local && t.address.indexOf('blob:') === 0) { try { URL.revokeObjectURL(t.address); } catch (e) {} }
      if (t.cover && t.cover.indexOf('blob:') === 0) { try { URL.revokeObjectURL(t.cover); } catch (e) {} }
    });
    queue = [];
    currentIndex = 0;
    refs.audio.removeAttribute('src');
    refs.audio.load();
    setPlayIcon(false);
    renderPlaylist();
    setTrack(0, false);
    setStatus('Playlist limpia.');
  }

  function removeTrack(idx) {
    if (idx < 0 || idx >= queue.length) return;
    var t = queue[idx];
    if (t.local && t.address.indexOf('blob:') === 0) { try { URL.revokeObjectURL(t.address); } catch (e) {} }
    queue.splice(idx, 1);
    if (currentIndex >= queue.length) currentIndex = 0;
    renderPlaylist();
    if (!queue.length) setTrack(0, false);
  }

  function toggleShuffle() {
    shuffleMode = !shuffleMode;
    refs.shuffle.classList.toggle('chmp--active', shuffleMode);
    setStatus(shuffleMode ? 'Aleatorio activado.' : 'Aleatorio desactivado.');
    remember();
  }

  function cycleRepeat() {
    repeatMode = (repeatMode + 1) % 3;
    refs.repeat.classList.toggle('chmp--active', repeatMode !== 0);
    refs.repeat.innerHTML = repeatMode === 2 ? '<i class="fas fa-repeat"></i> <span style="font-size:.55rem">1</span>' : '<i class="fas fa-redo"></i>';
    var labels = ['Repetir: apagado', 'Repetir: toda la lista', 'Repetir: una pista'];
    setStatus(labels[repeatMode]);
    remember();
  }

  function onKeyDown(e) {
    if (e.target && /INPUT|TEXTAREA|SELECT/.test(e.target.tagName)) return;
    if (!playerRoot || playerRoot.classList.contains('chmp--hidden')) return;
    var k = e.key;
    if (k === ' ' || k === 'Spacebar') {
      e.preventDefault();
      if (refs.audio.paused) { if (!refs.audio.src && queue.length) setTrack(0, true); else playTrack(); }
      else pauseTrack();
    } else if (k === 'ArrowRight') {
      e.preventDefault();
      if (isFinite(refs.audio.duration)) refs.audio.currentTime = Math.min(refs.audio.duration, refs.audio.currentTime + 5);
    } else if (k === 'ArrowLeft') {
      e.preventDefault();
      if (isFinite(refs.audio.duration)) refs.audio.currentTime = Math.max(0, refs.audio.currentTime - 5);
    } else if (k === 'ArrowUp') {
      e.preventDefault();
      refs.audio.volume = Math.min(1, refs.audio.volume + 0.05);
      refs.vol.value = String(refs.audio.volume);
      updateVolLabel(); remember();
    } else if (k === 'ArrowDown') {
      e.preventDefault();
      refs.audio.volume = Math.max(0, refs.audio.volume - 0.05);
      refs.vol.value = String(refs.audio.volume);
      updateVolLabel(); remember();
    }
  }

  function attach() {
    refs.launcher.addEventListener('click', function () {
      if (playerRoot.classList.contains('chmp--minimized')) { expand(); }
      else { minimize(); }
    });
    playerRoot.querySelector('[data-role="minimize"]').addEventListener('click', minimize);
    playerRoot.querySelector('[data-role="close"]').addEventListener('click', closePlayer);
    playerRoot.querySelector('[data-role="shuffle"]').addEventListener('click', toggleShuffle);
    playerRoot.querySelector('[data-role="repeat"]').addEventListener('click', cycleRepeat);
    refs.play.addEventListener('click', function () {
      if (!refs.audio.src && queue.length) setTrack(0, false);
      if (refs.audio.paused) playTrack(); else pauseTrack();
    });
    refs.prev.addEventListener('click', function () { prevTrack(true); });
    refs.next.addEventListener('click', function () { nextTrack(true); });
    refs.seek.addEventListener('input', function () {
      if (isFinite(refs.audio.duration) && refs.audio.duration > 0)
        refs.audio.currentTime = (Number(refs.seek.value) / 100) * refs.audio.duration;
    });
    refs.vol.addEventListener('input', function () {
      refs.audio.volume = Number(refs.vol.value);
      lastVolume = refs.audio.volume;
      updateVolLabel(); remember();
    });
    refs.files.addEventListener('change', function () { dropFiles(this.files); this.value = ''; });
    refs.addStream.addEventListener('click', addStream);
    refs.stream.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); addStream(); } });
    refs.export.addEventListener('click', exportPlaylist);
    refs.import.addEventListener('click', function () { refs.importFile.click(); });
    refs.importFile.addEventListener('change', function () { if (this.files[0]) importPlaylist(this.files[0]); this.value = ''; });
    refs.clear.addEventListener('click', clearQueue);

    refs.header.addEventListener('pointerdown', function (e) {
      if (e.target && e.target.closest('button,input')) return;
      dragging = true;
      playerRoot.classList.add('chmp--dragging');
      startPointer = { x: e.clientX, y: e.clientY };
      startPlayer = { left: parseFloat(playerRoot.style.left) || 0, top: parseFloat(playerRoot.style.top) || 0 };
      try { refs.header.setPointerCapture(e.pointerId); } catch (err) {}
    });
    document.addEventListener('pointermove', function (e) {
      if (!dragging) return;
      playerRoot.style.left = (startPlayer.left + e.clientX - startPointer.x) + 'px';
      playerRoot.style.top = (startPlayer.top + e.clientY - startPointer.y) + 'px';
      playerRoot.style.right = 'auto';
      playerRoot.style.bottom = 'auto';
    });
    document.addEventListener('pointerup', function () {
      if (!dragging) return;
      dragging = false;
      playerRoot.classList.remove('chmp--dragging');
      clampPos(); remember();
    });

    refs.audio.addEventListener('timeupdate', function () {
      if (isFinite(refs.audio.duration) && refs.audio.duration > 0)
        refs.seek.value = String((refs.audio.currentTime / refs.audio.duration) * 100);
      refs.time.textContent = formatTime(refs.audio.currentTime) + ' / ' + formatTime(refs.audio.duration);
    });
    refs.audio.addEventListener('playing', function () { setPlayIcon(true); setStatus('Reproduciendo.'); updateMS(); });
    refs.audio.addEventListener('pause', function () { setPlayIcon(false); setStatus('En pausa.'); });
    refs.audio.addEventListener('waiting', function () { setStatus('Buffering…'); });
    refs.audio.addEventListener('error', function () { setPlayIcon(false); setStatus('No se pudo reproducir.'); });
    refs.audio.addEventListener('ended', function () { nextTrack(true); });

    refs.playlist.addEventListener('click', function (e) {
      var del = e.target.closest('[data-del]');
      if (del) { e.stopPropagation(); removeTrack(Number(del.getAttribute('data-del'))); return; }
      var item = e.target.closest('[data-index]');
      if (item) setTrack(Number(item.getAttribute('data-index')), true);
    });

    refs.drop.addEventListener('dragover', function (e) { e.preventDefault(); this.style.borderColor = 'rgba(0,184,148,.45)'; });
    refs.drop.addEventListener('dragleave', function () { this.style.borderColor = 'rgba(47,128,237,.25)'; });
    refs.drop.addEventListener('drop', function (e) { e.preventDefault(); this.style.borderColor = 'rgba(47,128,237,.25)'; dropFiles(e.dataTransfer && e.dataTransfer.files); });

    document.addEventListener('keydown', onKeyDown);
    window.addEventListener('resize', clampPos);
    window.addEventListener('storage', function (e) { if (e.key === 'ch-theme' || e.key === 'ch:theme') applyTheme(); });
    document.addEventListener('click', function (e) {
      var el = e.target.closest && e.target.closest('[data-theme-toggle],[data-set-theme]');
      if (el) setTimeout(applyTheme, 50);
    });
  }

  function restore() {
    var st = storageLoad();
    if (st) {
      if (isFinite(st.volume)) { refs.audio.volume = Math.min(1, Math.max(0, st.volume)); refs.vol.value = String(refs.audio.volume); }
      shuffleMode = !!st.shuffle;
      repeatMode = st.repeat || 0;
      if (st.minimized) playerRoot.classList.add('chmp--minimized');
    }
    updateVolLabel();
    if (shuffleMode) refs.shuffle.classList.add('chmp--active');
    if (repeatMode !== 0) {
      refs.repeat.classList.add('chmp--active');
      if (repeatMode === 2) refs.repeat.innerHTML = '<i class="fas fa-repeat"></i> <span style="font-size:.55rem">1</span>';
    }
  }

  function init() {
    if (document.getElementById('chMusicPlayerRoot')) return;
    injectStyles();
    buildUI();
    attach();
    restore();
    renderPlaylist();
    setTrack(0, false);
    applyTheme();
    window.__chMusicPlayer = { expand: expand, minimize: minimize, close: closePlayer, addStream: addStream, clear: clearQueue };
    window.addEventListener('pagehide', function () {
      queue.forEach(function (t) {
        if (t.local && t.address.indexOf('blob:') === 0) { try { URL.revokeObjectURL(t.address); } catch (e) {} }
      });
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once: true });
  else init();
})();