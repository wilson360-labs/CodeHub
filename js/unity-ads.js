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

  // ── DIAGNÓSTICO (para responder "¿por qué no se ven los anuncios?") ──
  // En el APK no hay logcat a mano, así que el estado del SDK se puede pedir
  // y ver desde la propia web:
  //   chAdsStatus(console.log)            → estado puntual (JSON)
  //   chAdsDebug(true)                    → badge fijo que se refresca solo
  //   abrir la URL con ?ads_debug=1       → activa el badge automáticamente
  //   abrir la URL con ?ads_test=1|0      → modo test de Unity (al reiniciar)
  function status(cb) {
    if (!N || typeof N.adsStatus !== 'function') { if (cb) cb(null); return; }
    const name = '__ch_ads_' + Date.now() + '_' + Math.random().toString(36).slice(2);
    const done = json => { delete window[name]; try { if (cb) cb(json); } catch (e) {} };
    window[name] = done;
    try { N.adsStatus(name); } catch (e) { done(null); }
  }
  window.chAdsStatus = status;

  const DBG_KEY = 'ch_ads_debug';
  function readStatus() {
    status(json => {
      window.chAdsLast = json;
      try { console.info('[unity-ads] estado:', json); } catch (e) {}
      const el = document.getElementById('ch-ads-debug');
      if (el && json) el.textContent = json;
    });
  }

  function debug(on) {
    try { localStorage.setItem(DBG_KEY, on ? '1' : '0'); } catch (e) {}
    let el = document.getElementById('ch-ads-debug');
    if (!on) { if (el) el.remove(); clearInterval(window.__chAdsTimer); return; }
    if (!el) {
      el = document.createElement('pre');
      el.id = 'ch-ads-debug';
      el.style.cssText = 'position:fixed;left:8px;bottom:8px;z-index:99999;max-width:92vw;' +
        'max-height:40vh;overflow:auto;margin:0;padding:8px 10px;border-radius:8px;' +
        'font:11px/1.45 ui-monospace,Consolas,monospace;white-space:pre-wrap;word-break:break-word;' +
        'background:rgba(0,0,0,.86);color:#7CFFB2;border:1px solid rgba(255,255,255,.18)';
      el.textContent = 'consultando estado de anuncios…';
      document.body.appendChild(el);
    }
    readStatus();
    clearInterval(window.__chAdsTimer);
    window.__chAdsTimer = setInterval(readStatus, 5000);
  }
  window.chAdsDebug = debug;

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

    // Parámetros de diagnóstico en la URL (y estado inicial en consola).
    try {
      const p = new URLSearchParams(location.search);
      if (p.get('ads_test') === '1' || p.get('ads_test') === '0') {
        const on = p.get('ads_test') === '1';
        if (typeof N.setAdsTestMode === 'function') {
          N.setAdsTestMode(on);
          console.info('[unity-ads] modo test =', on, '· se aplica al reiniciar la app');
        }
      }
      if (p.get('ads_debug') === '1') debug(true);
      else if (localStorage.getItem(DBG_KEY) === '1') debug(true);
      else setTimeout(readStatus, 1500);
    } catch (e) { setTimeout(readStatus, 1500); }
  }

  window.CodeHubNativeAds = {
    showBanner, hideBanner,
    interstitial,
    isBannerVisible: () => bannerVisible,
    status,
    debug
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
