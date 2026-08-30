// ═══════════════════════════════════════════════════════════════
//  DEVICE DETECT — CodeHub by Wilson.E
//  Detección robusta de dispositivo (no depende solo del ancho CSS,
//  que puede fallar con zoom de página, "solicitar sitio de escritorio",
//  DPI raros, etc). Marca <html> con clases que el resto del CSS/JS
//  puede usar como fuente de verdad, y expone variables --app-height /
//  --app-width calculadas con visualViewport para que paneles como el
//  de WIL.E COPILOT se dimensionen de forma inteligente sin distorsión.
// ═══════════════════════════════════════════════════════════════
(function () {
  'use strict';

  var root = document.documentElement;
  var ua = navigator.userAgent || '';

  function detect() {
    var coarse = window.matchMedia && window.matchMedia('(pointer: coarse)').matches;
    var noHover = window.matchMedia && window.matchMedia('(hover: none)').matches;
    var touchCapable = ('ontouchstart' in window) || (navigator.maxTouchPoints > 0);

    var isAndroid = /Android/i.test(ua);
    var isIOS = /iPhone|iPad|iPod/i.test(ua) || (/Macintosh/i.test(ua) && navigator.maxTouchPoints > 1);
    var isMobileUA = /Mobi|Android|iPhone|iPod|IEMobile|BlackBerry|Opera Mini/i.test(ua);
    var isTabletUA = /iPad|Tablet|Nexus 7|Nexus 10|KFAPWI/i.test(ua) || (isAndroid && !/Mobile/i.test(ua));

    // Ancho lógico "real" del layout, robusto ante barras de dirección móviles
    var w = (window.visualViewport && window.visualViewport.width) || window.innerWidth;

    // Señal combinada: no confiamos solo en el ancho (puede fallar con zoom
    // o "ver como escritorio"), ni solo en el user-agent (puede ser falso
    // en navegadores tipo Brave con "Desktop mode"). Combinamos varias señales.
    var mobileSignals = 0;
    if (coarse) mobileSignals++;
    if (noHover) mobileSignals++;
    if (touchCapable) mobileSignals++;
    if (isMobileUA) mobileSignals++;
    if (w <= 900) mobileSignals++;

    var isTablet = isTabletUA && w > 600 && w <= 1180;
    var isMobile = !isTablet && (mobileSignals >= 3 || (isMobileUA && w <= 900));

    root.classList.toggle('is-mobile', isMobile);
    root.classList.toggle('is-tablet', isTablet);
    root.classList.toggle('is-desktop', !isMobile && !isTablet);
    root.classList.toggle('is-touch', touchCapable || coarse);
    root.classList.toggle('is-android', isAndroid);
    root.classList.toggle('is-ios', isIOS);

    root.setAttribute('data-app-width', Math.round(w));
  }

  function setViewportUnits() {
    var vh = (window.visualViewport && window.visualViewport.height) || window.innerHeight;
    var vw = (window.visualViewport && window.visualViewport.width) || window.innerWidth;
    // px reales, evita el "salto" del dvh en navegadores que no lo soportan
    // bien y evita que paneles fijos (como #ai-panel) queden más grandes
    // que la pantalla visible cuando aparece el teclado o la barra de URL.
    root.style.setProperty('--app-height', vh + 'px');
    root.style.setProperty('--app-width', vw + 'px');
  }

  detect();
  setViewportUnits();

  var resizeTimer;
  function onViewportChange() {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      detect();
      setViewportUnits();
    }, 120);
  }

  window.addEventListener('resize', onViewportChange);
  window.addEventListener('orientationchange', onViewportChange);
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', onViewportChange);
  }

  window.CodeHubDevice = { detect: detect, setViewportUnits: setViewportUnits };
})();
