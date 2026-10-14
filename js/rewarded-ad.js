/* â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
   rewarded-ad.js â€” Anuncios recompensados de CodeHub (verificados por servidor)
   CodeHub by Wilson.E Â· ver docs/REWARDED-ADS.md

   REGLA DE ORO: el navegador nunca decide la recompensa. El flujo es:

     1) POST /api/rewards/token                     â†’ nonce (un solo uso)
     2) el usuario ve el anuncio (red / oferta)     â†’ la red obtiene el nonce
     3) LA RED llama /api/rewards/postback (S2S)    â†’ EL SERVIDOR acredita
     4) el front consulta /api/rewards/balance      â†’ confirma

   API (compatible con la versiÃ³n anterior â€” solo usaba isReady/preload/show):

     CodeHubRewardedAd.configure({ apiBase, adUnit, getToken, provider })
     CodeHubRewardedAd.isReady()          â†’ bool
     CodeHubRewardedAd.preload()
     CodeHubRewardedAd.show(cb)           â†’ cb(earned, amount, type)  [legacy]
     CodeHubRewardedAd.watch(opts)        â†’ Promise<{earned,amount,credits}>
     CodeHubRewardedAd.balance()          â†’ Promise<{credits,todayEarned}>
     CodeHubRewardedAd.openOfferwall()    â†’ bool
     CodeHubRewardedAd.available()        â†’ {native,web,server}

   En el APK, `window.CodeHubNative` delega en Unity Ads (ver el
   cÃ³digo de la app: UnityAdsManager). En web, el rewarded pasa por el
   backend de recompensas (Monetag / offerwall).
   â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• */
'use strict';

const CodeHubRewardedAd = (function () {
  // â”€â”€ Config por defecto â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  let _cfg = {
    apiBase: (typeof window._CH_BACKEND !== 'undefined' && window._CH_BACKEND)
      ? window._CH_BACKEND
      : 'https://codehub-98s6.onrender.com',
    adUnit: 'video',
    getToken: null,   // () => string | null (si no se define, usa CodeHubAuth)
    provider: null,   // { zoneId } | { call({nonce})=>â€¦ }  (p. ej. Monetag)
    offerwall: null,  // { url, param }
    poll: { tries: 8, delayMs: 1500 },
  };

  function configure(opts) {
    opts = opts || {};
    if (opts.poll) opts = Object.assign({}, opts, { poll: Object.assign({}, _cfg.poll, opts.poll) });
    _cfg = Object.assign({}, _cfg, opts);
    return _cfg;
  }

  function apiBase() {
    const b = (_cfg.apiBase || '').replace(/\/+$/, '');
    return b + '/api/rewards';
  }

  // â”€â”€ SesiÃ³n / token â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  function getSession() {
    try {
      if (window.CodeHubAuth && typeof window.CodeHubAuth.getSession === 'function') {
        return window.CodeHubAuth.getSession();
      }
    } catch (e) { /* noop */ }
    try {
      const raw = sessionStorage.getItem('ch_auth_session') || localStorage.getItem('ch_auth_session');
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  }

  function getToken() {
    if (typeof _cfg.getToken === 'function') {
      try { return _cfg.getToken(); } catch (e) { /* noop */ }
    }
    const s = getSession();
    return (s && (s.token || s.access_token)) || null;
  }

  function authHeaders(extra) {
    const h = Object.assign({}, extra || {});
    const t = getToken();
    if (t) h['Authorization'] = 'Bearer ' + t;
    return h;
  }

  // â”€â”€ Puente nativo (APK) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  function isNative() {
    return !!(window.CodeHubNative && typeof window.CodeHubNative.showRewardedAd === 'function');
  }

  function showNative() {
    return new Promise(function (resolve) {
      if (!isNative()) return resolve({ earned: false, amount: 0, type: '', reason: 'sin-nativo' });
      const cb = '__chRewardedCb' + Date.now() + Math.floor(Math.random() * 1000);
      window[cb] = function (earned, amount, type) {
        try { delete window[cb]; } catch (e) { window[cb] = undefined; }
        resolve({ earned: !!earned, amount: amount || 0, type: type || 'native' });
      };
      try {
        if (typeof window.CodeHubNative.loadRewardedAd === 'function') window.CodeHubNative.loadRewardedAd();
        window.CodeHubNative.showRewardedAd(cb);
      } catch (e) {
        try { delete window[cb]; } catch (x) { /* noop */ }
        resolve({ earned: false, amount: 0, type: '', reason: 'error-nativo' });
      }
    });
  }

  // â”€â”€ Anuncios web (con verificaciÃ³n de servidor) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  function requestNonce(adUnit) {
    return fetch(apiBase() + '/token', {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      credentials: 'include',
      body: JSON.stringify({ adUnit: adUnit || _cfg.adUnit }),
    }).then(function (r) {
      return r.json().then(function (j) { return Object.assign({}, j, { __status: r.status }); });
    });
  }

  function showProvider(nonce) {
    const p = _cfg.provider;
    if (!p) return Promise.reject(new Error('sin-provider'));
    if (typeof p.call === 'function') return Promise.resolve(p.call({ nonce: nonce }));
    if (p.zoneId && typeof window['show_' + p.zoneId] === 'function') {
      return Promise.resolve(window['show_' + p.zoneId]({ ymid: nonce, requestVar: _cfg.adUnit }));
    }
    if (p.zoneId && typeof window['show_' + p.zoneId] === 'function') {
      return Promise.resolve(window['show_' + p.zoneId]({ ymid: nonce }));
    }
    return Promise.reject(new Error('proveedor no cargado'));
  }

  // â”€â”€ API HTTP â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  function http(path, opts, method) {
    const url = apiBase() + path;
    return fetch(url, Object.assign({ method: method || 'GET', headers: authHeaders() }, opts || {}))
      .then(function (r) { return r.json().catch(function () { return { ok: false }; }); })
      .then(function (j) {
        if (!j || j.ok === false) throw new Error((j && j.error) || 'request-failed');
        return j;
      });
  }

  function balance() {
    return http('/balance');
  }

  function pollForCredit(beforeCredits) {
    const cfg = _cfg.poll || {};
    const tries = cfg.tries || 8;
    const delay = cfg.delayMs || 1500;
    let i = 0;

    function sleep() {
      return new Promise(function (res) { setTimeout(res, delay); });
    }

    function attempt() {
      return balance().then(function (b) {
        const credits = (b && b.credits) || 0;
        if (beforeCredits === null || beforeCredits === undefined) {
          if (credits > 0) return { credits: credits, delta: 0 };
        } else if (credits > beforeCredits) {
          return { credits: credits, delta: credits - beforeCredits };
        }
        return null;
      }).catch(function () { return null; });
    }

    function loop() {
      return attempt().then(function (hit) {
        if (hit) return hit;
        i += 1;
        if (i >= tries) return null;
        return sleep().then(loop);
      });
    }
    return sleep().then(loop);
  }

  // â”€â”€ Flujo principal â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  // Devuelve { earned, amount, credits, reason } sin prometer crÃ©ditos
  // que el backend no haya confirmado.
  async function watch(opts) {
    opts = opts || {};

    // 1) En el APK manda el SDK nativo (Unity Ads). El consentimiento ya
    //    se resolviÃ³ en ConsentManager; aquÃ­ solo se muestra.
    if (isNative()) {
      const native = await showNative();
      return Object.assign({ credits: null, source: 'native' }, native);
    }

    // (2) Web: recompensa verificada por servidor.
    if (!getToken()) return { earned: false, amount: 0, credits: null, reason: 'auth' };
    if (!_cfg.provider) return { earned: false, amount: 0, reason: 'sin-proveedor' };

    let token;
    try {
      token = await fetch(apiBase() + '/token', {
        method: 'POST',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        credentials: 'include',
        body: JSON.stringify({ adUnit: opts.adUnit || _cfg.adUnit }),
      }).then(function (r) { return r.json(); });
      if (!token || !token.ok) {
        return { earned: false, amount: 0, reason: (token && token.error) || 'token' };
      }
    } catch (e) {
      return { earned: false, amount: 0, reason: 'network' };
    }

    let before = null;
    try { const b = await balance(); before = (b && b.credits) || 0; } catch (e) { before = null; }

    try {
      await showProvider(token.nonce);
    } catch (e) {
      return { earned: false, amount: 0, credits: before, reason: 'anuncio-no-mostrado' };
    }

    const hit = await pollForCredit(before);
    if (hit) return { earned: true, amount: hit.delta, credits: hit.credits, type: 'web' };
    return { earned: false, credits: before, reason: 'sin-verificar' };
  }

  // â”€â”€ Compatibilidad con la versiÃ³n anterior â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
  function isReady() {
    if (isNative()) {
      try { if (typeof window.CodeHubNative.isRewardedAdReady === 'function') return !!window.CodeHubNative.isRewardedAdReady(); } catch (e) { /* noop */ }
      return true;
    }
    return !!_cfg.provider; // en web, el disponible depende del token
  }

  function preload() {
    if (!isNative()) return;
    try { if (typeof window.CodeHubNative !== 'undefined' && window.CodeHubNative.loadRewardedAd) window.CodeHubNative.loadRewardedAd(); } catch (e) { /* noop */ }
  }

  // cb(earned, amount, type) â€” compatible.
  function show(cb) {
    Promise.resolve()
      .then(function () {
        if (isNative()) return showNative();
        return watch();
      })
      .then(function (r) {
        if (typeof cb === 'function') cb(!!r.earned, r.amount || 0, r.type || '');
      })
      .catch(function () {
        if (typeof cb === 'function') cb(false, 0, '');
      });
  }

  function openOfferwall(extra) {
    const ow = _cfg.offerwall;
    if (!ow || !ow.url) return false;
    const s = getSession();
    const uid = (s && (s.id || (s.user && s.user.id))) || '';
    let url = String(ow.url);
    if (uid) url += (url.indexOf('?') === -1 ? '?' : '&') + ((ow.param || 'user_id')) + '=' + encodeURIComponent(uid);
    try {
      window.open(url, '_blank', 'noopener');
      return true;
    } catch (e) { return false; }
  }

  function available() {
    return {
      native: isNative(),
      web: !!_cfg.provider,
      server: isNative() ? null : !!getToken(),
    };
  }

  return {
    configure: configure,
    isReady: isReady,
    preload: preload,
    show: show,
    watch: watch,
    balance: balance,
    openOfferwall: openOfferwall,
    available: available,
  };
})();

window.CodeHubRewardedAd = CodeHubRewardedAd;
