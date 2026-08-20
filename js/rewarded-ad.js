/* ═══════════════════════════════════════════════════════════════
   rewarded-ad.js — Anuncio recompensado de AdMob (solo APK)
   CodeHub by Wilson.E

   Unidad: ca-app-pub-3780093322926832/4285173985

   Uso desde cualquier botón/página:

     CodeHubRewardedAd.show(function(earned) {
       if (earned) {
         // el usuario vio el anuncio completo → dale la recompensa
         // (ej: desbloquear una app premium, un tema, un crédito, etc.)
       } else {
         // cerró el anuncio antes de tiempo, falló al cargar,
         // o no está disponible (web / sin conexión)
       }
     });

   En web (navegador normal) no hay anuncios de AdMob — CodeHubRewardedAd
   simplemente informa "no disponible" (earned=false) sin romper nada.
   ═══════════════════════════════════════════════════════════════ */
'use strict';

const CodeHubRewardedAd = (() => {
  let _cbCounter = 0;

  function _isNative() {
    return !!(window.CodeHubNative && typeof window.CodeHubNative.showRewardedAd === 'function');
  }

  function isReady() {
    if (!_isNative()) return false;
    try { return !!window.CodeHubNative.isRewardedAdReady(); } catch { return false; }
  }

  function preload() {
    if (!_isNative()) return;
    try { window.CodeHubNative.loadRewardedAd(); } catch {}
  }

  // onResult(earned, amount, type)
  function show(onResult) {
    if (!_isNative()) {
      // Solo disponible dentro de la app (APK) — en web no hay AdMob.
      if (typeof onResult === 'function') onResult(false, 0, '');
      return;
    }
    const cbName = '__chRewardedCb' + (_cbCounter++);
    window[cbName] = function(earned, amount, type) {
      delete window[cbName];
      if (typeof onResult === 'function') onResult(!!earned, amount || 0, type || '');
    };
    try {
      window.CodeHubNative.showRewardedAd(cbName);
    } catch {
      delete window[cbName];
      if (typeof onResult === 'function') onResult(false, 0, '');
    }
  }

  return { isReady, preload, show };
})();

window.CodeHubRewardedAd = CodeHubRewardedAd;
