/* ═══════════════════════════════════════════════════════════════
   CODEHUB — Alerta de conexión (online/offline)
   -----------------------------------------------------------------
   - Muestra una barra fija cuando el usuario pierde acceso a internet.
   - Al recuperar la conexión, avisa brevemente y se oculta.
   - Detecta eventos online/offline + navigator.onLine al cargar
     (útil para recargas hechas estando ya desconectado).
   - No depende de CSS externo: inyecta sus propios estilos.
   ═══════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  if (window.CodeHubConnection) return;
  window.CodeHubConnection = true;

  var SHOW_DELAY = 350;       // ms tras perder conexión (evita parpadeos)
  var ONLINE_DURATION = 3200; // ms que permanece el aviso "de vuelta en línea"

  /* ---- Inyectar estilos ---- */
  var css = [
    '#ch-conn{position:fixed;top:0;left:0;right:0;z-index:2147483001;',
    '  display:none;transform:translateY(-110%);',
    '  transition:transform .3s cubic-bezier(.2,.9,.2,1);}',
    '#ch-conn.show{display:block;transform:translateY(0);}',
    '#ch-conn-inner{max-width:1080px;margin:0 auto;display:flex;gap:.7rem;',
    '  align-items:center;justify-content:center;padding:.65rem 1.1rem;',
    '  font-family:"Syne",sans-serif;font-size:.82rem;font-weight:600;',
    '  color:#fff;text-align:center;}',
    '#ch-conn.ch-off{background:rgba(190,35,52,.94);',
    '  box-shadow:0 6px 24px rgba(0,0,0,.35);}',
    '#ch-conn.ch-on{background:rgba(17,128,71,.94);',
    '  box-shadow:0 6px 24px rgba(0,0,0,.25);}',
    '#ch-conn-icon{flex:0 0 auto;font-size:.95rem;line-height:1;}',
    '#ch-conn-text{min-width:0;}',
    '#ch-conn-dismiss{flex:0 0 auto;background:none;border:none;color:rgba(255,255,255,.85);',
    '  font-size:1.05rem;line-height:1;cursor:pointer;padding:.1rem .35rem;border-radius:6px;}',
    '#ch-conn-dismiss:hover{color:#fff;background:rgba(255,255,255,.15);}',
    '@media (max-width:640px){#ch-conn-inner{font-size:.76rem;padding:.55rem .9rem;gap:.5rem;}}',
    '@media (prefers-reduced-motion:reduce){#ch-conn{transition:none;}}'
  ].join('\n');

  var styleEl = document.createElement('style');
  styleEl.textContent = css;
  document.head.appendChild(styleEl);

  /* ---- DOM ---- */
  var bar = document.createElement('div');
  bar.id = 'ch-conn';
  bar.setAttribute('role', 'status');
  bar.setAttribute('aria-live', 'polite');
  bar.innerHTML =
    '<div id="ch-conn-inner">' +
    '  <span id="ch-conn-icon"></span>' +
    '  <span id="ch-conn-text"></span>' +
    '  <button id="ch-conn-dismiss" type="button" aria-label="Cerrar aviso">&times;</button>' +
    '</div>';

  document.body.appendChild(bar);

  var iconEl = document.getElementById('ch-conn-icon');
  var textEl = document.getElementById('ch-conn-text');
  var dismissBtn = document.getElementById('ch-conn-dismiss');

  var offTimer = null;
  var onTimer = null;

  function clearTimers() {
    clearTimeout(offTimer);
    clearTimeout(onTimer);
  }

  function renderOffline() {
    bar.classList.remove('ch-on');
    bar.classList.add('ch-off');
    iconEl.textContent = '⚠';
    textEl.textContent = 'No tienes conexión a internet.';
    bar.classList.add('show');
  }

  function renderOnline() {
    bar.classList.remove('ch-off');
    bar.classList.add('ch-on');
    iconEl.textContent = '✔';
    textEl.textContent = 'Conexión restablecida.';
    bar.classList.add('show');
    onTimer = setTimeout(hide, ONLINE_DURATION);
  }

  function hide() {
    clearTimers();
    bar.classList.remove('show');
  }

  function handleOffline() {
    clearTimeout(offTimer);
    offTimer = setTimeout(renderOffline, SHOW_DELAY);
  }

  function handleOnline() {
    clearTimers();
    renderOnline();
  }

  /* ---- Estado inicial (recarga estando ya sin red) ---- */
  var initialOnline = (typeof navigator !== 'undefined' && navigator.onLine !== undefined)
    ? navigator.onLine : true;
  if (!initialOnline) renderOffline();

  window.addEventListener('offline', handleOffline);
  window.addEventListener('online', handleOnline);
  dismissBtn.addEventListener('click', hide);

  /* ---- API global ---- */
  window.CodeHubConnection = {
    hide: hide,
    isOnline: function () { return navigator.onLine; }
  };
})();
