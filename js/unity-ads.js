/* ═══════════════════════════════════════════════════════════════════════
   unity-ads.js — Banner + intersticial nativos (Unity Ads) en el APK
   CodeHub by Wilson.E · ver docs/ADS-COMPLIANCE.md

   Solo actúa dentro del WebView de la app (window.CodeHubNative). En el
   navegador normal no hay SDK y este archivo no hace absolutamente nada.

   Reglas de colocación (inteligentes, nunca invasivas):
   - BANNER: usa la franja inferior reservada por MainActivity, así que
     nunca tapa el contenido. Se muestra en páginas de contenido y se
     oculta en pantallas densas (admin, analytics, juegos, editor de CV)
     marcando <html data-no-ads> o <body data-no-ads>.
   - INTERSTICIAL: SOLO tras una acción del usuario (p. ej. abrir la ficha
     de una app en Open Source), con enfriamiento y tope por sesión, y
     NUNCA encima del flujo de descarga recompensada.

   Si falta el SDK, el consentimiento (UMP) o el anuncio no cargó, no hace
   absolutamente nada: jamás bloquea ni rompe la navegación.
   ═══════════════════════════════════════════════════════════════════════ */
(() => {
  'use strict';

  const N = window.CodeHubNative;
  if (!N || typeof N.showNativeBanner !== 'function') return;   // web / APK viejo

  // ── ¿Esta página admite anuncios? ──────────────────────────────
  function noAds() {
    try {
      const de = document.documentElement;
      const bd = document.body;
      if (de && de.hasAttribute('data-no-ads')) return true;
      if (bd && bd.hasAttribute('data-no-ads')) return true;
      const p = (location.pathname || '') + (location.hash || '');
      return /(^|\/)(admin-hub|analytics|snake|tetris|mines|memory|rps)(\/|\.|$)/i.test(p);
    } catch (e) { return false; }
  }

  // ── BANNER (franja inferior reservada por el nativo) ────────────
  let bannerVisible = false;

  function showBanner() {
    if (bannerVisible || noAds()) return;
    try { N.showNativeBanner(); bannerVisible = true; } catch (e) {}
  }
  function hideBanner() {
    // Incondicional: oculta aunque la bandera local venga en false (p. ej. al
    // entrar a una página sin anuncios tras una que sí los mostraba).
    try { N.hideNativeBanner(); } catch (e) {}
    bannerVisible = false;
  }

  // ── INTERSTICIAL (acción del usuario + enfriamiento + tope) ─────
  const GAP_MS = 3 * 60 * 1000;    // mínimo entre intersticiales
  const MAX_SESSION = 4;           // tope por sesión
  const TS_KEY = 'ch_ad_it_ts';    // último mostrado (persistente)
  const CNT_KEY = 'ch_ad_it_cnt';  // contador de sesión

  const num = (store, k) => { try { return Number(store.getItem(k) || 0) || 0; } catch (e) { return 0; } };
  const lastTs = () => num(localStorage, TS_KEY);
  const sessionCount = () => num(sessionStorage, CNT_KEY);
  const markShown = () => {
    try { localStorage.setItem(TS_KEY, String(Date.now())); } catch (e) {}
    try { sessionStorage.setItem(CNT_KEY, String(sessionCount() + 1)); } catch (e) {}
  };

  let busy = false;

  function interstitial(reason) {
    if (!N || typeof N.showInterstitialAd !== 'function') return;
    if (noAds() || document.hidden || busy) return;
    if (Date.now() - lastTs() < GAP_MS) return;
    if (sessionCount() >= MAX_SESSION) return;

    let ready = false;
    try { ready = !!N.isInterstitialAdReady(); } catch (e) {}
    if (!ready) { try { N.loadInterstitialAd(); } catch (e) {} return; }   // calienta y sale

    busy = true;
    const cb = '__ch_it_' + Date.now() + '_' + Math.random().toString(36).slice(2);
    // Watchdog: si el nativo nunca responde (Activity destruida a mitad del
    // anuncio), liberamos el candado para no bloquear futuros intersticiales.
    const watchdog = setTimeout(() => {
      if (window[cb]) { delete window[cb]; busy = false; }
    }, 90 * 1000);
    window[cb] = shown => {
      clearTimeout(watchdog);
      delete window[cb];
      busy = false;
      if (shown) markShown();
    };
    try { N.showInterstitialAd(cb); }
    catch (e) { clearTimeout(watchdog); delete window[cb]; busy = false; }
  }

  // ── Momentos "inteligentes" ────────────────────────────────────
  function init() {
    if (noAds()) hideBanner();                    // limpia un banner previo si toca
    else setTimeout(showBanner, 1200);            // deja pintar la página primero

    // Abrir la ficha de una app en Open Source = pausa natural del usuario.
    document.addEventListener('click', e => {
      const t = e.target;
      if (!t || !t.closest) return;
      const card = t.closest('.app-card');
      if (!card || !card.dataset.appId) return;
      if (t.closest('a, button, input, textarea, select, .os-rating-stars, .os-echo-copy-btn')) return;
      interstitial('app-detail');
    }, true);

    // Permitir que otras vistas pidan un intersticial explícito:
    //   document.dispatchEvent(new CustomEvent('codehub:ad-break', { detail: { reason } }))
    document.addEventListener('codehub:ad-break', e => {
      interstitial(e && e.detail && e.detail.reason);
    });

    // Al volver a primer plano, recolocar el banner según la página actual.
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) return;
      if (noAds()) hideBanner(); else showBanner();
    });
  }

  window.CodeHubNativeAds = {
    showBanner, hideBanner,
    interstitial,
    isBannerVisible: () => bannerVisible
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
