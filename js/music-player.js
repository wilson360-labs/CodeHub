// Reproductor multimedia flotante de CodeHub.
// Funciona con archivos locales (File API) y con URLs de streaming.
// El audio se mantiene mientras la pestaña siga abierta; no requiere login.

(function () {
  'use strict';

  if (window.__chMusicPlayerLoaded) return;
  window.__chMusicPlayerLoaded = true;

  var STORAGE_KEY = 'ch:music-player';
  var maxFiles = 250;
  var queue = [];
  var currentIndex = 0;
  var dragging = false;
  var startPointer = { x: 0, y: 0 };
  var startPlayer = { left: 0, top: 0 };
  var launcherPinned = true;

  function injectStyles() {
    if (document.getElementById('ch-music-player-css')) return;
    var style = document.createElement('style');
    style.id = 'ch-music-player-css';
    style.textContent = `
      #chMusicPlayerRoot {
        position: fixed;
        width: min(360px, calc(100vw - 24px));
        z-index: 2147483000;
        right: 16px;
        bottom: 16px;
        font-family: "Space Grotesk", system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
        transition: transform .18s ease, opacity .18s ease;
        user-select: none;
      }
      #chMusicPlayerRoot.chmp--dragging { transition: none; }
      #chMusicPlayerRoot.chmp--minimized { width: 80px; }
      #chMusicPlayerRoot.chmp--hidden { display: none; }
      .chmp__launcher,
      .chmp__panel {
        background: rgba(10, 10, 22, .94);
        border: 1px solid rgba(47,128,237,.35);
        box-shadow: 0 18px 54px rgba(0,0,0,.38), 0 0 0 1px rgba(255,255,255,.04) inset;
        backdrop-filter: blur(12px);
        -webkit-backdrop-filter: blur(12px);
        color: #e8e8f0;
      }
      .chmp__launcher {
        width: 58px; height: 58px;
        border-radius: 50%;
        display: grid;
        place-items: center;
        margin-left: auto;
        cursor: pointer;
        border: none;
        background: linear-gradient(135deg, #2f80ed, #00b894);
        color: #fff;
        box-shadow: 0 10px 28px rgba(0,0,0,.32);
      }
      .chmp__panel {
        border-radius: 18px;
        padding: 14px;
        margin-top: 12px;
        overflow: hidden;
      }
      .chmp__header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 8px;
        margin-bottom: 10px;
        cursor: move;
        touch-action: none;
      }
      .chmp__title {
        min-width: 0;
        display: grid;
        gap: 2px;
      }
      .chmp__title strong {
        font-size: .96rem;
        white-space: nowrap;
        overflow: hidden;
        text-overflow: ellipsis;
      }
      .chmp__title small {
        color: rgba(232,232,240,.55);
        font-size: .72rem;
      }
      .chmp__actions {
        display: flex;
        gap: 6px;
        flex-shrink: 0;
      }
      .chmp__icon-btn {
        width: 30px;
        height: 30px;
        border-radius: 999px;
        border: 1px solid rgba(255,255,255,.1);
        background: rgba(255,255,255,.05);
        color: #fff;
        display: grid;
        place-items: center;
        cursor: pointer;
        padding: 0;
      }
      .chmp__icon-btn:hover { background: rgba(255,255,255,.1); }
      .chmp__controls {
        display: grid;
        grid-template-columns: auto auto auto 1fr;
        align-items: center;
        gap: 8px;
        margin-bottom: 10px;
      }
      .chmp__btn {
        border: none;
        border-radius: 999px;
        padding: 8px 11px;
        background: rgba(255,255,255,.08);
        color: #fff;
        cursor: pointer;
        font-size: .9rem;
      }
      .chmp__btn--primary {
        background: linear-gradient(135deg, #2f80ed, #00b894);
        min-width: 58px;
      }
      .chmp__btn:hover { background: rgba(255,255,255,.14); }
      .chmp__btn--primary:hover { filter: brightness(1.08); }
      .chmp__progress {
        width: 100%;
        accent-color: #2f80ed;
        cursor: pointer;
        margin: 6px 0 8px;
      }
      .chmp__meta {
        display: flex;
        justify-content: space-between;
        color: rgba(232,232,240,.5);
        font-size: .72rem;
        font-family: "JetBrains Mono", ui-monospace, monospace;
      }
      .chmp__field { display: grid; gap: 6px; margin-bottom: 10px; }
      .chmp__field label { font-size: .72rem; color: rgba(232,232,240,.58); }
      .chmp__input {
        width: 100%;
        border-radius: 10px;
        border: 1px solid rgba(255,255,255,.12);
        background: rgba(255,255,255,.05);
        color: #fff;
        padding: 8px 10px;
        font-size: .86rem;
      }
      .chmp__row { display: flex; gap: 6px; align-items: center; }
      .chmp__btn-small {
        border-radius: 10px;
        border: 1px solid rgba(255,255,255,.12);
        background: rgba(255,255,255,.05);
        color: #fff;
        padding: 8px 10px;
        cursor: pointer;
        font-size: .78rem;
      }
      .chmp__btn-small:hover { background: rgba(255,255,255,.14); }
      .chmp__playlist {
        max-height: 158px;
        overflow: auto;
        border-radius: 10px;
        border: 1px solid rgba(255,255,255,.08);
        background: rgba(255,255,255,.03);
        margin-bottom: 10px;
      }
      .chmp__playlist-item {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 8px;
        padding: 7px 9px;
        cursor: pointer;
        border-bottom: 1px solid rgba(255,255,255,.05);
      }
      .chmp__playlist-item:last-child { border-bottom: none; }
      .chmp__playlist-item:hover { background: rgba(255,255,255,.07); }
      .chmp__playlist-item.chmp--active { background: rgba(47,128,237,.18); color: #fff; }
      .chmp__playlist-item b {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        max-width: 200px;
      }
      .chmp__playlist-item span { font-size: .7rem; color: rgba(232,232,240,.44); }
      .chmp__status { font-size: .76rem; color: rgba(232,232,240,.58); margin-bottom: 8px; }
      .chmp__drop {
        border: 1px dashed rgba(47,128,237,.25);
        border-radius: 10px;
        padding: 8px;
        font-size: .76rem;
        color: rgba(232,232,240,.55);
      }
      #chMusicPlayerRoot.chmp--minimized .chmp__panel { display: none; }
      @media (max-width: 430px) {
        #chMusicPlayerRoot { right: 8px; bottom: 8px; width: min(340px, calc(100vw - 16px)); }
        .chmp__launcher { width: 54px; height: 54px; }
      }
    `;
    document.head.appendChild(style);
  }

  function formatTime(sec) {
    if (!Number.isFinite(sec)) return '0:00';
    var m = Math.floor(sec / 60);
    var s = Math.floor(sec % 60);
    return m + ':' + String(s).padStart(2, '0');
  }

  function cssVariable(element, name, value) {
    element.style[name] = value;
  }

  function storageLoad() {
    try {
      var raw = localStorage.getItem(STORAGE_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      return null;
    }
  }

  function storageSave(state) {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    } catch (e) {}
  }

  function makeTrack(name, address, local, size) {
    return {
      id: 'track_' + Math.random().toString(36).slice(2, 10),
      name: name,
      address: address,
      local: !!local,
      size: size || 0,
      loaded: false
    };
  }

  function clearObjectUrls() {
    queue.forEach(function (track) {
      if (track.local && track.address && track.address.indexOf('blob:') === 0) {
        try { URL.revokeObjectURL(track.address); } catch (e) {}
      }
    });
  }

  function clampPlayerPosition() {
    var player = document.getElementById('chMusicPlayerRoot');
    if (!player) return;
    var rect = player.getBoundingClientRect();
    var maxLeft = Math.max(0, window.innerWidth - rect.width - 8);
    var maxTop = Math.max(0, window.innerHeight - rect.height - 8);
    var left = parseFloat(player.style.left || player.getBoundingClientRect().left);
    var top = parseFloat(player.style.top || player.getBoundingClientRect().top);
    if (!Number.isFinite(left)) left = window.innerWidth - rect.width - 16;
    if (!Number.isFinite(top)) top = window.innerHeight - rect.height - 16;
    player.style.left = Math.min(Math.max(8, left), maxLeft) + 'px';
    player.style.top = Math.min(Math.max(8, top), maxTop) + 'px';
    player.style.right = 'auto';
    player.style.bottom = 'auto';
  }

  function ensureAudioContextPolicy(audio) {
    if (!audio) return;
  }

  function buildPlayer() {
    injectStyles();

    var root = document.createElement('div');
    root.id = 'chMusicPlayerRoot';
    root.className = 'chmp--minimized';
    root.style.left = Math.max(8, window.innerWidth - 96) + 'px';
    root.style.top = Math.max(8, window.innerHeight - 78) + 'px';
    root.style.right = 'auto';
    root.style.bottom = 'auto';
    root.innerHTML =
      '<button class="chmp__launcher" type="button" aria-label="Abrir reproductor de música" title="Música">' +
        '<i class="fas fa-music" aria-hidden="true"></i>' +
      '</button>' +
      '<div class="chmp__panel">' +
        '<div class="chmp__header" data-drag-handle="true">' +
          '<div class="chmp__title">' +
            '<strong id="chmpTitle">Reproductor CodeHub</strong>' +
            '<small id="chmpMeta">Archivo local o streaming</small>' +
          '</div>' +
          '<div class="chmp__actions">' +
            '<button class="chmp__icon-btn" type="button" data-role="minimize" title="Minimizar" aria-label="Minimizar"><i class="fas fa-minus"></i></button>' +
            '<button class="chmp__icon-btn" type="button" data-role="close" title="Cerrar" aria-label="Cerrar"><i class="fas fa-xmark"></i></button>' +
          '</div>' +
        '</div>' +
        '<p class="chmp__status" id="chmpStatus">Arrastra el encabezado, minimiza o cierra la ventana.</p>' +
        '<audio id="chmpAudio" preload="metadata" playsinline></audio>' +
        '<div class="chmp__controls">' +
          '<button class="chmp__btn chmp__btn--primary" type="button" data-role="play" aria-label="Reproducir"><i class="fas fa-play"></i></button>' +
          '<button class="chmp__btn" type="button" data-role="prev" aria-label="Pista anterior"><i class="fas fa-backward-step"></i></button>' +
          '<button class="chmp__btn" type="button" data-role="next" aria-label="Siguiente pista"><i class="fas fa-forward-step"></i></button>' +
          '<input class="chmp__progress" type="range" min="0" max="100" value="0" step="0.1" aria-label="Progreso">' +
        '</div>' +
        '<div class="chmp__meta"><span id="chmpTime">0:00 / 0:00</span><span id="chmpVolumeLabel">Vol 70%</span></div>' +
        '<input class="chmp__progress chmp__volume" type="range" min="0" max="1" value="0.7" step="0.01" aria-label="Volumen">' +
        '<div class="chmp__field">' +
          '<label for="chmpLocalFiles">Archivos locales</label>' +
          '<input id="chmpLocalFiles" class="chmp__input" type="file" accept="audio/*,audio/mpeg,audio/ogg,audio/wav,audio/flac" multiple>' +
        '</div>' +
        '<div class="chmp__drop">Arrastra aquí un archivo o pega una URL de streaming.</div>' +
        '<div class="chmp__field">' +
          '<label for="chmpStreamUrl">Streaming (URL)</label>' +
          '<div class="chmp__row">' +
            '<input id="chmpStreamUrl" class="chmp__input" type="url" placeholder="https://tu-stream/radio.mp3">' +
            '<button class="chmp__btn-small" type="button" data-role="addStream">Agregar</button>' +
          '</div>' +
        '</div>' +
        '<div class="chmp__playlist" id="chmpPlaylist" aria-label="Lista de reproducción"></div>' +
      '</div>';

    document.body.appendChild(root);

    var audio = root.querySelector('#chmpAudio');
    var title = root.querySelector('#chmpTitle');
    var meta = root.querySelector('#chmpMeta');
    var status = root.querySelector('#chmpStatus');
    var progress = root.querySelector('.chmp__progress');
    var volume = root.querySelector('.chmp__volume');
    var volumeLabel = root.querySelector('#chmpVolumeLabel');
    var timeLabel = root.querySelector('#chmpTime');
    var playlist = root.querySelector('#chmpPlaylist');
    var localInput = root.querySelector('#chmpLocalFiles');
    var streamUrlInput = root.querySelector('#chmpStreamUrl');
    var launcher = root.querySelector('.chmp__launcher');
    var header = root.querySelector('.chmp__header');
    var minimizeBtn = root.querySelector('[data-role="minimize"]');
    var closeBtn = root.querySelector('[data-role="close"]');
    var playBtn = root.querySelector('[data-role="play"]');
    var prevBtn = root.querySelector('[data-role="prev"]');
    var nextBtn = root.querySelector('[data-role="next"]');
    var addStreamBtn = root.querySelector('[data-role="addStream"]');
    var sourceReady = false;

    audio.volume = 0.7;

    function setStatus(text) {
      status.textContent = text;
    }

    function setPlayIcon(playing) {
      playBtn.innerHTML = playing ? '<i class="fas fa-pause"></i>' : '<i class="fas fa-play"></i>';
    }

    function updateVolumeLabel() {
      var pct = Math.round(Number(volume.value || audio.volume) * 100);
      volumeLabel.textContent = 'Vol ' + pct + '%';
    }

    function renderPlaylist() {
      if (!queue.length) {
        playlist.innerHTML = '<div class="chmp__playlist-item"><b>Sin pistas</b><span>Agrega archivos o una URL</span></div>';
        return;
      }
      playlist.innerHTML = queue.map(function (track, idx) {
        return (
          '<div class="chmp__playlist-item' + (idx === currentIndex ? ' chmp--active' : '') + '" data-index="' + idx + '">' +
            '<b>' + escapeHtml(track.name) + '</b>' +
            '<span>' + (track.local ? 'local' : (track.local ? 'local' : 'stream')) + '</span>' +
          '</div>'
        );
      }).join('');
    }

    function escapeHtml(value) {
      return String(value || '').replace(/[&<>"']/g, function (char) {
        return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char];
      });
    }

    function ensureTrack(index) {
      if (!queue.length) {
        audio.removeAttribute('src');
        title.textContent = 'Reproductor CodeHub';
        meta.textContent = 'Archivo local o streaming';
        setStatus('Agrega archivos o una URL.');
        timeLabel.textContent = '0:00 / 0:00';
        setPlayIcon(false);
        sourceReady = false;
        return;
      }
      currentIndex = (index + queue.length) % queue.length;
      var track = queue[currentIndex];
      if (!track.loaded) {
        track.url = track.address;
        track.loaded = true;
      }
      audio.src = track.address;
      audio.load();
      title.textContent = track.name;
      meta.textContent = track.local ? 'Fuente local' : 'Streaming';
      setStatus(track.local ? 'Pista local lista.' : 'Streaming preparado.');
      sourceReady = true;
      renderPlaylist();
      if ('mediaSession' in navigator) {
        navigator.mediaSession.metadata = new MediaMetadata({
          title: track.name,
          artist: 'CodeHub',
          album: track.local ? 'Archivo local' : 'Streaming'
        });
        navigator.mediaSession.setActionHandler('play', function () { audio.play(); });
        navigator.mediaSession.setActionHandler('pause', function () { audio.pause(); });
        navigator.mediaSession.setActionHandler('previoustrack', function () { previous(true); });
        navigator.mediaSession.setActionHandler('nexttrack', function () { next(true); });
      }
    }

    function play() {
      try {
        var result = audio.play();
        if (result && typeof result.then === 'function') {
          result.then(function () {
            setPlayIcon(true);
            setStatus('Reproduciendo.');
            updateNowPlaying();
          }).catch(function (error) {
            setPlayIcon(false);
            setStatus('No se pudo iniciar: ' + (error && error.message ? error.message : 'permiso o formato'));
          });
        }
      } catch (error) {
        setPlayIcon(false);
        setStatus('No se pudo iniciar la reproducción.');
      }
    }

    function pause() {
      audio.pause();
      setPlayIcon(false);
      setStatus('En pausa.');
    }

    function previous(playAfter) {
      if (!queue.length) return;
      ensureTrack(currentIndex - 1);
      if (playAfter) play();
    }

    function next(playAfter) {
      if (!queue.length) return;
      ensureTrack(currentIndex + 1);
      if (playAfter) play();
    }

    function updateNowPlaying() {
      var track = queue[currentIndex];
      if (!track) return;
      if ('mediaSession' in navigator) {
        try {
          navigator.mediaSession.playbackState = audio.paused ? 'paused' : 'playing';
        } catch (e) {}
      }
    }

    function expand() {
      root.classList.remove('chmp--minimized');
      launcherPinned = false;
      rememberState();
    }

    function minimize() {
      root.classList.add('chmp--minimized');
      launcherPinned = false;
      rememberState();
    }

    function closePlayer() {
      root.classList.add('chmp--minimized');
      setStatus('Reproductor cerrado. Vuelve a abrirlo con el icono flotante.');
      rememberState();
    }

    function rememberState() {
      storageSave({ minimized: root.classList.contains('chmp--minimized') });
    }

    function restoreState() {
      var state = storageLoad();
      if (state && state.minimized) root.classList.add('chmp--minimized');
    }

    function choosePosition() {
      if (!root.classList.contains('chmp--minimized')) {
        clampPlayerPosition();
      }
    }

    function dropFiles(files) {
      if (!files || !files.length) return;
      var added = 0;
      Array.from(files).forEach(function (file) {
        if (!file.type || file.type.indexOf('audio/') !== 0) return;
        if (queue.length + added >= maxFiles) return;
        queue.push(makeTrack(file.name, URL.createObjectURL(file), true, file.size));
        added++;
      });
      if (added > 0) {
        setStatus(added + ' pista(s) agregada(s).');
        if (!audio.src) ensureTrack(currentIndex);
        renderPlaylist();
        if (root.classList.contains('chmp--minimized')) expand();
      }
    }

    function uploadToQueueFromInput() {
      var files = localInput.files;
      dropFiles(files);
      if (localInput) localInput.value = '';
    }

    function addStreamUrl() {
      var value = (streamUrlInput.value || '').trim();
      if (!value) {
        setStatus('Escribe una URL de streaming.');
        return;
      }
      var name = deriveNameFromUrl(value);
      queue.push(makeTrack(name, value, false, 0));
      ensureTrack(queue.length - 1);
      streamUrlInput.value = '';
      renderPlaylist();
      expand();
      setStatus('Streaming agregado.');
    }

    function deriveNameFromUrl(value) {
      try {
        var parsed = new URL(value);
        var last = parsed.pathname.split('/').pop();
        if (last && last.indexOf('.') !== -1) return decodeURIComponent(last);
      } catch (e) {}
      return 'Streaming ' + (queue.filter(function (t) { return !t.local; }).length + 1);
    }

    function attachHandlers() {
      launcher.addEventListener('click', function () {
        if (root.classList.contains('chmp--minimized')) {
          root.classList.remove('chmp--minimized');
          rememberState();
        } else {
          minimize();
        }
      });

      minimizeBtn.addEventListener('click', function () { minimize(); });
      closeBtn.addEventListener('click', function () { closePlayer(); });

      playBtn.addEventListener('click', function () {
        if (!audio.src && queue.length) ensureTrack(0);
        if (audio.paused) play(); else pause();
      });
      prevBtn.addEventListener('click', function () { previous(true); });
      nextBtn.addEventListener('click', function () { next(true); });

      progress.addEventListener('input', function () {
        if (isFinite(audio.duration) && audio.duration > 0) {
          audio.currentTime = (Number(progress.value) / 100) * audio.duration;
        }
      });
      volume.addEventListener('input', function () {
        audio.volume = Number(volume.value);
        updateVolumeLabel();
        storageSave(storageFormat());
      });

      localInput.addEventListener('change', uploadToQueueFromInput);
      addStreamBtn.addEventListener('click', addStreamUrl);
      streamUrlInput.addEventListener('keydown', function (evt) {
        if (evt.key === 'Enter') { evt.preventDefault(); addStreamUrl(); }
      });

      header.addEventListener('pointerdown', function (event) {
        if (event.target && (event.target.closest('button') || event.target.closest('input') || event.target.closest('[data-role="close"]'))) {
          return;
        }
        dragging = true;
        root.classList.add('chmp--dragging');
        startPointer = { x: event.clientX, y: event.clientY };
        startPlayer = { left: playerLeft(), top: playerTop() };
        try { header.setPointerCapture(event.pointerId); } catch (e) {}
      });

      document.addEventListener('pointermove', function (event) {
        if (!dragging) return;
        var left = startPlayer.left + (event.clientX - startPointer.x);
        var top = startPlayer.top + (event.clientY - startPointer.y);
        root.style.left = left + 'px';
        root.style.top = top + 'px';
        root.style.right = 'auto';
        root.style.bottom = 'auto';
        if (event.pointerId) {
          try {
            header.setPointerCapture(event.pointerId);
          } catch (e) {}
        }
      });
      document.addEventListener('pointerup', function () {
        if (!dragging) return;
        dragging = false;
        root.classList.remove('chmp--dragging');
        clampPlayerPosition();
        rememberState();
      });
      document.addEventListener('pointercancel', function () {
        dragging = false;
        root.classList.remove('chmp--dragging');
        clampPlayerPosition();
      });

      audio.addEventListener('timeupdate', function () {
        if (isFinite(audio.duration) && audio.duration > 0) {
          progress.value = String((audio.currentTime / audio.duration) * 100);
        }
        timeLabel.textContent = formatTime(audio.currentTime) + ' / ' + formatTime(audio.duration);
      });
      audio.addEventListener('loadedmetadata', function () {
        timeLabel.textContent = formatTime(audio.currentTime) + ' / ' + formatTime(audio.duration);
      });
      audio.addEventListener('ended', function () {
        setStatus('Pista finalizada.');
        next(false);
      });
      audio.addEventListener('waiting', function () { setStatus('Buffering...'); });
      audio.addEventListener('playing', function () {
        setPlayIcon(true);
        setStatus('Reproduciendo.');
        updateNowPlaying();
      });
      audio.addEventListener('pause', function () {
        setPlayIcon(false);
        setStatus('En pausa.');
      });
      audio.addEventListener('error', function () {
        setPlayIcon(false);
        setStatus('No se pudo reproducir la pista actual.');
      });

      playlist.addEventListener('click', function (event) {
        var item = event.target.closest('[data-index]');
        if (!item) return;
        var index = Number(item.getAttribute('data-index'));
        if (Number.isFinite(index)) {
          ensureTrack(index);
          audio.play().catch(function () {});
        }
      });

      root.querySelector('.chmp__drop').addEventListener('dragover', function (event) {
        event.preventDefault();
        this.style.borderColor = 'rgba(0,184,148,.45)';
      });

      root.querySelector('.chmp__drop').addEventListener('dragleave', function () {
        this.style.borderColor = 'rgba(47,128,237,.25)';
      });

      root.querySelector('.chmp__drop').addEventListener('drop', function (event) {
        event.preventDefault();
        this.style.borderColor = 'rgba(47,128,237,.25)';
        dropFiles(event.dataTransfer && event.dataTransfer.files);
      });

      window.addEventListener('resize', function () {
        if (!root.classList.contains('chmp--minimized')) {
          choosePosition();
        }
      });
    }

    function playerLeft() {
      var styleLeft = parseFloat(document.getElementById('chMusicPlayerRoot').style.left);
      if (Number.isFinite(styleLeft)) return styleLeft;
      return window.innerWidth - (document.getElementById('chMusicPlayerRoot').getBoundingClientRect().width || 360) - 16;
    }

    function playerTop() {
      var styleTop = parseFloat(document.getElementById('chMusicPlayerRoot').style.top);
      if (Number.isFinite(styleTop)) return styleTop;
      return window.innerHeight - (document.getElementById('chMusicPlayerRoot').getBoundingClientRect().height || 260) - 16;
    }

    function storageFormat() {
      var volumeValue = document.querySelector('#chMusicPlayerRoot .chmp__volume');
      return { minimized: document.getElementById('chMusicPlayerRoot').classList.contains('chmp--minimized'), volume: Number(volumeValue ? volumeValue.value : 0.7) };
    }

    function init() {
      var existing = document.getElementById('chMusicPlayerRoot');
      if (existing) return;
      buildPlayer();
      attachHandlers();
      window.__chMusicPlayer = { expand: expand, minimize: minimize, close: closePlayer };
      var restored = storageLoad();
      if (restored) {
        var volume = document.querySelector('#chMusicPlayerRoot .chmp__volume');
        if (volume && Number.isFinite(restored.volume)) {
          volume.value = String(restored.volume);
          document.getElementById('chmpAudio').volume = restored.volume;
          updateVolumeLabel();
        }
        if (restored.minimized) {
          document.getElementById('chMusicPlayerRoot').classList.add('chmp--minimized');
        }
      }
      ensureTrack(0);
      window.addEventListener('pagehide', function () {
        queue.forEach(function (track) {
          if (track.local && track.address && track.address.indexOf('blob:') === 0) {
            try { URL.revokeObjectURL(track.address); } catch (e) {}
          }
        });
      });
    }

    if (typeof MutationObserver === 'function') {
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init, { once: true });
      } else {
        init();
      }
    } else {
      init();
    }
  }
})();
