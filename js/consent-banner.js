/* ═══════════════════════════════════════════════════════════════
   CODEHUB — Consentimiento de cookies (GDPR/CCPA) + Consent Mode v2
   -----------------------------------------------------------------
   - Muestra un banner de consentimiento la primera vez.
   - Si el usuario acepta, se conceden ad_storage, ad_user_data,
     ad_personalization y analytics_storage (vía gtag consent update).
   - Si rechaza, los anuncios se sirven en modo NO personalizado
     (permitido por AdSense) y se deniega analytics_storage.
   - La decisión se guarda en localStorage['ch_consent'].
   El snippet inline del <head> de cada página define los defaults
   ANTES de cargar gtag/GTM/adsbygoogle; este archivo gestiona la UI.
   ═══════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var KEY = 'ch_consent';
  var PREF_ACCEPTED = 'accepted';
  var PREF_DECLINED = 'declined';

  /* ---- Helpers seguros ---- */
  function getPref() {
    try { return localStorage.getItem(KEY); } catch (e) { return null; }
  }
  function setPref(v) {
    try { localStorage.setItem(KEY, v); } catch (e) {}
  }
  function updateConsent(granted) {
    try {
      if (window.gtag) {
        window.gtag('consent', 'update', {
          'ad_storage': granted ? 'granted' : 'denied',
          'ad_user_data': granted ? 'granted' : 'denied',
          'ad_personalization': granted ? 'granted' : 'denied',
          'analytics_storage': granted ? 'granted' : 'denied'
        });
      }
    } catch (e) {}
    try {
      if (window.dataLayer) {
        window.dataLayer.push({
          event: granted ? 'consent_granted' : 'consent_denied'
        });
      }
    } catch (e) {}
  }

  /* ---- Inyectar estilos (no depende de CSS externo) ---- */
  var css = [
    '#ch-consent{position:fixed;left:0;right:0;bottom:0;z-index:2147483000;',
    '  background:rgba(6,8,16,.96);border-top:1px solid rgba(255,255,255,.1);',
    '  box-shadow:0 -12px 40px rgba(0,0,0,.55);',
    '  font-family:"Syne",sans-serif;padding:1rem 1.25rem .95rem;',
    '  display:none;transform:translateY(100%);transition:transform .35s cubic-bezier(.2,.9,.2,1);',
    '  -webkit-backdrop-filter:blur(14px);backdrop-filter:blur(14px);}',
    '#ch-consent.show{display:block;transform:translateY(0);}',
    '#ch-consent-inner{max-width:1080px;margin:0 auto;display:flex;gap:1.1rem;',
    '  flex-wrap:wrap;align-items:center;justify-content:space-between;}',
    '#ch-consent-text{flex:1 1 320px;font-size:.82rem;line-height:1.6;color:#b8bcc9;min-width:0;}',
    '#ch-consent-text strong{color:#fff;font-weight:700;}',
    '#ch-consent-text a{color:#38bdf8;text-decoration:none;border-bottom:1px solid rgba(56,189,248,.35);}',
    '#ch-consent-text a:hover{color:#7dd3fc;}',
    '#ch-consent-btns{display:flex;gap:.55rem;flex-wrap:wrap;align-items:center;}',
    '#ch-consent-btn-ok,#ch-consent-btn-no{font-family:"Syne",sans-serif;font-weight:600;',
    '  font-size:.8rem;padding:.55rem 1.15rem;border-radius:999px;cursor:pointer;',
    '  border:1px solid transparent;transition:all .22s;}',
    '#ch-consent-btn-ok{background:linear-gradient(135deg,#2f80ed,#38bdf8);color:#fff;}',
    '#ch-consent-btn-ok:hover{filter:brightness(1.12);box-shadow:0 4px 18px rgba(56,189,248,.35);}',
    '#ch-consent-btn-no{background:transparent;color:#8a8f9f;border-color:rgba(255,255,255,.14);}',
    '#ch-consent-btn-no:hover{border-color:rgba(255,255,255,.35);color:#fff;}',
    '#ch-consent-close{position:absolute;top:.55rem;right:.75rem;color:#6a6f7f;background:none;',
    '  border:none;font-size:1.05rem;cursor:pointer;line-height:1;padding:.2rem .4rem;}',
    '#ch-consent-close:hover{color:#fff;}',
    '@media (max-width:640px){#ch-consent{padding:1rem 1rem .9rem;}',
    '  #ch-consent-btns{width:100%;}',
    '  #ch-consent-btn-ok{flex:1;}',
    '  #ch-consent-btn-no{flex:1;}',
    '  #ch-consent-text{font-size:.78rem;}}',
    '@media (prefers-reduced-motion:reduce){#ch-consent{transition:none;}}'
  ].join('\n');

  var styleEl = document.createElement('style');
  styleEl.textContent = css;
  document.head.appendChild(styleEl);

  /* ---- Crear DOM del banner ---- */
  var bar = document.createElement('div');
  bar.id = 'ch-consent';
  bar.setAttribute('role', 'dialog');
  bar.setAttribute('aria-live', 'polite');
  bar.innerHTML =
    '<button id="ch-consent-close" aria-label="Cerrar y seguir navegando" title="Continuar sin aceptar">&times;</button>' +
    '<div id="ch-consent-inner">' +
    '  <p id="ch-consent-text">' +
    '    <strong>Tu privacidad importa.</strong> Este sitio usa cookies para medir el tráfico ' +
    '    (Google Analytics) y mostrar anuncios de Google AdSense. Si aceptas, podremos ' +
    '    personalizar los anuncios según tu navegación; si rechazas, seguirás viendo ' +
    '    anuncios no personalizados. Puedes cambiar tu decisión cuando quieras. ' +
    '    <a href="/privacy" rel="noopener">Política de privacidad</a> · ' +
    '    <a href="/terms" rel="noopener">Términos de uso</a>' +
    '  </p>' +
    '  <div id="ch-consent-btns">' +
    '    <button id="ch-consent-btn-no" type="button">Rechazar</button>' +
    '    <button id="ch-consent-btn-ok" type="button">Aceptar</button>' +
    '  </div>' +
    '</div>';

  document.body.appendChild(bar);

  function dismiss() {
    bar.classList.remove('show');
    setTimeout(function () { if (bar.parentNode) bar.parentNode.removeChild(bar); }, 400);
  }
  function decide(accepted) {
    setPref(accepted ? PREF_ACCEPTED : PREF_DECLINED);
    updateConsent(accepted);
    dismiss();
  }

  document.getElementById('ch-consent-btn-ok').addEventListener('click', function () { decide(true); });
  document.getElementById('ch-consent-btn-no').addEventListener('click', function () { decide(false); });
  document.getElementById('ch-consent-close').addEventListener('click', function () { decide(false); });

  /* ---- Mostrar solo si aún no hay decisión ---- */
  var pref = getPref();
  // Respect RC: skip if consentBanner disabled
  var rcConsentEnabled = (typeof RC === 'undefined') || RC.feature('consentBanner');
  if (rcConsentEnabled && pref !== PREF_ACCEPTED && pref !== PREF_DECLINED) {
    setTimeout(function () { bar.classList.add('show'); }, 900);
  }

  /* ---- API global para reabrir / consultar ---- */
  window.CodeHubConsent = {
    open: function () {
      if (bar && bar.parentNode) bar.classList.add('show');
    },
    getPref: getPref,
    accept: function () { decide(true); },
    decline: function () { decide(false); }
  };
})();
