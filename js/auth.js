/* ═══════════════════════════════════════════════════════════════════
   CodeHub — AUTH (invitado/registrado) — Wilson.E 2026
   ──────────────────────────────────────────────────────────────────
   Módulo de sesión del frontend. Se conecta a /api/auth/* del backend
   (Supabase Auth: registro y login con email+password). Incluye Google
   OAuth (flujo redirect con PKCE vía cookie) y Cloudflare Turnstile
   (anti-bots, se valida en el servidor). La sesión se guarda en
   sessionStorage (se borra al cerrar la pestaña) — no en localStorage,
   para reducir exposición.

   API expuesta: window.CodeHubAuth = {
     isLogged(), getUser(), openLogin(mode), closeLogin(),
     loginWithGoogle(), submitLogin(), submitRegister(), logout(),
     checkLimit(scope), consumeLimit(scope), on(cb)
   }

   Reglas de seguridad (por diseño, antes de conectar backend):
   - El frontend NUNCA recibe la service role key de Supabase.
   - La sesión se guarda en sessionStorage (se borra al cerrar la
     pestaña) — no en localStorage, para reducir exposición.
   - Cloudflare Turnstile se verifica en el servidor (no aquí).
   ═══════════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  var BACKEND = (typeof _CH_BACKEND !== 'undefined') ? _CH_BACKEND : 'https://codehub-98s6.onrender.com';
  var SESSION_KEY = 'ch_auth_session';
  var DEVICE_KEY  = 'ch_device_id';
  var USAGE_KEY   = 'ch_usage';

  // ── Identificador de dispositivo (invitado) ──────────────────────
  function deviceId() {
    try {
      var id = localStorage.getItem(DEVICE_KEY);
      if (!id) {
        id = 'dev_' + Math.random().toString(36).slice(2) + Date.now().toString(36);
        localStorage.setItem(DEVICE_KEY, id);
      }
      return id;
    } catch (e) { return 'dev_unknown'; }
  }

  // ── Sesión ───────────────────────────────────────────────────────
  var session = null;
  var listeners = [];

  function loadSession() {
    try {
      // Check localStorage first (persistent "remember me"), then sessionStorage
      var raw = localStorage.getItem(SESSION_KEY) || sessionStorage.getItem(SESSION_KEY);
      session = raw ? JSON.parse(raw) : null;
    } catch (e) { session = null; }
    scheduleRefresh();
  }
  function saveSession(s, remember) {
    session = s;
    try {
      if (s) {
        if (remember) {
          localStorage.setItem(SESSION_KEY, JSON.stringify(s));
          sessionStorage.removeItem(SESSION_KEY);
        } else {
          sessionStorage.setItem(SESSION_KEY, JSON.stringify(s));
          localStorage.removeItem(SESSION_KEY);
        }
      } else {
        sessionStorage.removeItem(SESSION_KEY);
        localStorage.removeItem(SESSION_KEY);
      }
    } catch (e) {}
    emit();
  }
  function emit() {
    for (var i = 0; i < listeners.length; i++) {
      try { listeners[i](session); } catch (e) {}
    }
    updateSideUI();
  }

  // ── Reflejar sesión en el menú lateral (botón cuenta + perfil) ──
  function updateSideUI() {
    var btn = document.getElementById('side-account-btn');
    if (btn) {
      var label = btn.querySelector('span');
      var icon  = btn.querySelector('i');
      if (session) {
        if (label) {
          var n = session.user ? (session.user.name || session.user.email) : 'Mi cuenta';
          label.textContent = n.length > 18 ? n.slice(0, 18) + '…' : n;
        }
        if (icon) icon.className = 'fas fa-user-check';
        btn.setAttribute('title', 'Ver mi cuenta');
      } else {
        var dict = (typeof i18n !== 'undefined' && i18n[typeof currentLang !== 'undefined' ? currentLang : 'es'])
          ? i18n[typeof currentLang !== 'undefined' ? currentLang : 'es']
          : {};
        if (label) label.textContent = dict.sidePrefAccount || 'Mi cuenta';
        if (icon) icon.className = 'fas fa-user-circle';
        btn.setAttribute('title', 'Mi cuenta — Iniciar sesión');
      }
    }
  }

  // ── Límites de uso (EMI y funciones premium) ──────────────────────
  // Invitado: cuota diaria por dispositivo.
  // Registrado: sin límite (backend verificará con user_id).
  var LIMITS = {
    emi_daily: { guest: 10, registered: Infinity }
  };

  function today() { return new Date().toISOString().slice(0, 10); }

  function getUsage() {
    try {
      var raw = localStorage.getItem(USAGE_KEY);
      var obj = raw ? JSON.parse(raw) : {};
      if (obj.date !== today()) obj = { date: today(), items: {} };
      return obj;
    } catch (e) { return { date: today(), items: {} }; }
  }
  function setUsage(u) {
    try { localStorage.setItem(USAGE_KEY, JSON.stringify(u)); } catch (e) {}
  }

  function scopeKey(scope) {
    return (session ? 'u:' + session.id : 'd:' + deviceId()) + ':' + scope;
  }

  function checkLimit(scope) {
    var cfg = LIMITS[scope];
    if (!cfg) return { allowed: true, remaining: Infinity, limit: Infinity };
    var max = session ? cfg.registered : cfg.guest;
    var u = getUsage();
    var used = u.items[scopeKey(scope)] || 0;
    return { allowed: used < max, remaining: Math.max(0, max - used), limit: max, used: used };
  }

  function consumeLimit(scope) {
    var cfg = LIMITS[scope];
    if (!cfg) return;
    if (session) return; // registrado: sin límite local
    var u = getUsage();
    var k = scopeKey(scope);
    u.items[k] = (u.items[k] || 0) + 1;
    setUsage(u);
  }

  // ── Capa backend (Supabase Auth vía servidor) ────────────────────
  function _api(path, payload) {
    return fetch(BACKEND + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include', // guardar/enviar cookies del backend (Google OAuth PKCE)
      body: JSON.stringify(payload),
    }).then(function (r) {
      return r.json().catch(function () { return { error: 'Respuesta inválida' }; }).then(function (body) {
        if (!r.ok) throw body;
        return body;
      });
    });
  }

  // ── Auto-refresh de sesión ────────────────────────────────────
  var _refreshTimer = null;
  function scheduleRefresh() {
    if (_refreshTimer) clearTimeout(_refreshTimer);
    if (!session || !session.token) return;
    // Supabase tokens expire in ~1 hour. Refresh at 50 min.
    _refreshTimer = setTimeout(function() {
      if (!session || !session.token) return;
      // Try to refresh using the stored refresh_token
      if (session.refresh_token) {
        fetch(BACKEND + '/api/auth/refresh', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ refresh_token: session.refresh_token })
        }).then(function(r) { return r.json(); }).then(function(data) {
          if (data.session) {
            session.token = data.session.access_token;
            session.refresh_token = data.session.refresh_token;
            saveSession(session);
            scheduleRefresh();
          }
        }).catch(function() {});
      }
    }, 50 * 60 * 1000); // 50 minutes
  }

  // ── Modal ────────────────────────────────────────────────────────
  var panel = null;

  function ensurePanel() {
    if (panel) return panel;
    panel = document.getElementById('auth-panel');
    return panel;
  }

  function setMode(mode) {
    var p = ensurePanel();
    if (!p) return;
    p.querySelectorAll('.auth-tab').forEach(function (b) {
      b.classList.toggle('active', b.getAttribute('data-mode') === mode);
    });
    var loginForm = p.querySelector('.auth-form-login');
    var regForm = p.querySelector('.auth-form-register');
    if (loginForm) loginForm.style.display = (mode === 'login') ? '' : 'none';
    if (regForm) regForm.style.display = (mode === 'register') ? '' : 'none';
  }

  // ── Acciones ─────────────────────────────────────────────────────
  function closeLogin() {
    var p = ensurePanel();
    if (!p) return;
    p.classList.remove('open');
    p.setAttribute('aria-hidden', 'true');
  }

  function openLogin(mode) {
    var p = ensurePanel();
    if (!p) return;
    p.classList.add('open');
    p.setAttribute('aria-hidden', 'false');
    setMode(mode || 'login');
    // Turnstile: renderizar (o refrescar) el widget anti-bots del panel
    renderTurnstile();
    if (session && session.user) {
      showAccount(p);
    }
  }

  function showAccount(p) {
    var acc = p.querySelector('.auth-account');
    var forms = p.querySelector('.auth-forms');
    if (!acc || !forms) return;
    forms.style.display = 'none';
    acc.style.display = '';
    var name = p.querySelector('.auth-acc-name');
    var email = p.querySelector('.auth-acc-email');
    if (name) name.textContent = session.user.name || session.user.email || 'Usuario';
    if (email) email.textContent = session.user.email || '';
    var prov = p.querySelector('#auth-stat-prov');
    if (prov) prov.textContent = session.provider ? session.provider.toUpperCase() : 'GOOGLE';
    var ses = p.querySelector('#auth-stat-session');
    if (ses) ses.textContent = '●';
  }

  function getField(selector) {
    var p = ensurePanel();
    var el = p ? p.querySelector(selector) : null;
    return el ? el.value.trim() : '';
  }

  // ── Turnstile (anti-bots) ────────────────────────────────────────
  var TURNSTILE_SITEKEY = '0x4AAAAAAClKd5T1R81GltW_';
  var tsWidgetId = null;
  var tsTokenValue = '';

  function tsContainer() {
    var p = ensurePanel();
    return p ? p.querySelector('#turnstile-auth') : null;
  }

  function tsTheme() {
    return (document.documentElement.getAttribute('data-theme') === 'light') ? 'light' : 'dark';
  }

  function renderTurnstile(attempt) {
    attempt = attempt || 0;
    var el = tsContainer();
    if (!el) return;
    if (typeof window.turnstile === 'undefined') {
      if (attempt < 20) setTimeout(function () { renderTurnstile(attempt + 1); }, 250);
      return;
    }
    if (tsWidgetId !== null) {
      try { window.turnstile.reset(tsWidgetId); } catch (e) {}
      return;
    }
    try {
      tsWidgetId = window.turnstile.render(el, {
        sitekey: TURNSTILE_SITEKEY,
        theme: tsTheme(),
        language: (typeof currentLang !== 'undefined' && currentLang === 'es') ? 'es' : 'en',
        callback: function (token) { tsTokenValue = token || ''; },
        'expired-callback': function () { tsTokenValue = ''; },
      });
    } catch (e) {}
  }

  function resetTurnstile() {
    tsTokenValue = '';
    if (tsWidgetId !== null && typeof window.turnstile !== 'undefined') {
      try { window.turnstile.reset(tsWidgetId); } catch (e) {}
    }
  }

  function getTurnstileToken() {
    if (tsTokenValue) return tsTokenValue;
    var el = tsContainer();
    if (!el) return '';
    var inp = el.querySelector('input[name="cf-turnstile-response"]');
    return inp ? inp.value : '';
  }

  function setStatus(msg, isError) {
    var p = ensurePanel();
    if (!p) return;
    var el = p.querySelector('.auth-status');
    if (!el) return;
    el.textContent = msg || '';
    el.classList.toggle('error', !!isError);
  }

  function submitLogin() {
    var email = getField('#auth-login-email');
    var pass  = getField('#auth-login-pass');
    if (!email || !pass) { setStatus('Completa email y contraseña', true); return; }
    var ts = getTurnstileToken();
    if (!ts) { setStatus('Completa la verificación anti-bots', true); renderTurnstile(); return; }
    setStatus('Verificando…');
    _api('/api/auth/login', { email: email, password: pass, turnstileToken: ts }).then(function (r) {
      var remember = !!(document.getElementById('auth-remember-me') && document.getElementById('auth-remember-me').checked);
      saveSession({ id: r.user.id, user: { email: r.user.email, name: r.user.email.split('@')[0] }, token: r.session && r.session.access_token, refresh_token: r.session && r.session.refresh_token }, remember);
      scheduleRefresh();
      closeLogin();
      setStatus('');
      resetTurnstile();
    }).catch(function (e) {
      setStatus(e && e.error ? e.error : 'Error al iniciar sesión', true);
      if (e && /verificac/i.test(e.error)) renderTurnstile();
    });
  }

  function submitRegister() {
    var email = getField('#auth-reg-email');
    var pass  = getField('#auth-reg-pass');
    var pass2 = getField('#auth-reg-pass2');
    if (!email || !pass) { setStatus('Completa email y contraseña', true); return; }
    if (pass.length < 8) { setStatus('La contraseña debe tener al menos 8 caracteres', true); return; }
    if (pass !== pass2) { setStatus('Las contraseñas no coinciden', true); return; }
    var ts = getTurnstileToken();
    if (!ts) { setStatus('Completa la verificación anti-bots', true); renderTurnstile(); return; }
    setStatus('Creando cuenta…');
    _api('/api/auth/register', { email: email, password: pass, device_id: deviceId(), turnstileToken: ts }).then(function (r) {
      if (r.needsConfirmation) {
        setStatus('Revisa tu correo para confirmar la cuenta. Luego inicia sesión.', false);
        resetTurnstile();
        return;
      }
      saveSession({ id: r.user.id, user: { email: r.user.email, name: r.user.email.split('@')[0] }, token: r.session && r.session.access_token, refresh_token: r.session && r.session.refresh_token }, true);
      scheduleRefresh();
      closeLogin();
      setStatus('');
      resetTurnstile();
    }).catch(function (e) {
      setStatus(e && e.error ? e.error : 'Error al crear la cuenta', true);
      if (e && /verificac/i.test(e.error)) renderTurnstile();
    });
  }

  function loginWithGoogle() {
    // Navegación directa (no fetch) para que la cookie PKCE se guarde en
    // el navegador en contexto first-party y llegue al callback.
    window.location.href = BACKEND + '/api/auth/google';
  }

  // Retorno del flujo Google OAuth: /?auth=google&token=...  o  ?auth=google&error=...
  function handleGoogleReturn() {
    var params;
    try { params = new URLSearchParams(window.location.search); }
    catch (e) { return; }
    if (params.get('auth') !== 'google') return;
    // Limpiar la URL de inmediato (no dejar el token/error visible)
    try { history.replaceState({}, '', window.location.pathname + (window.location.hash || '')); } catch (e) {}

    if (params.get('error')) {
      setStatus('No se pudo iniciar con Google. Intenta de nuevo.', true);
      openLogin('login');
      return;
    }
    var token = params.get('token');
    if (!token) return;
    setStatus('Recuperando tu sesión…');
    _api('/api/auth/google/session', { token: token }).then(function (r) {
      saveSession({ id: r.user.id, user: { email: r.user.email, name: r.user.name || r.user.email.split('@')[0] }, token: r.session && r.session.access_token, refresh_token: r.session && r.session.refresh_token, provider: 'google' }, true);
      scheduleRefresh();
      openLogin('login');
      if (typeof toast === 'function') toast('✔ ¡Sesión iniciada con Google, ' + (r.user.name || '') + '!', 'success');
    }).catch(function (e) {
      setStatus(e && e.error ? e.error : 'No se pudo recuperar la sesión de Google', true);
      openLogin('login');
    });
  }

  function logout() {
    var t = session && session.token;
    if (t) _api('/api/auth/logout', { token: t }).catch(function () {});
    saveSession(null);
    closeLogin();
  }

  // ── Init ─────────────────────────────────────────────────────────
  function bind() {
    var p = ensurePanel();
    if (!p) return;

    p.querySelectorAll('.auth-tab').forEach(function (b) {
      b.addEventListener('click', function () { setMode(b.getAttribute('data-mode')); });
    });

    var close = p.querySelector('.auth-close');
    if (close) close.addEventListener('click', closeLogin);

    p.addEventListener('click', function (e) {
      if (e.target === p) closeLogin();
    });

    var loginBtn = p.querySelector('#auth-login-submit');
    if (loginBtn) loginBtn.addEventListener('click', submitLogin);

    var regBtn = p.querySelector('#auth-reg-submit');
    if (regBtn) regBtn.addEventListener('click', submitRegister);

    var googleBtn = p.querySelector('.auth-google');
    if (googleBtn) googleBtn.addEventListener('click', loginWithGoogle);

    var logoutBtn = p.querySelector('#auth-logout');
    if (logoutBtn) logoutBtn.addEventListener('click', logout);

    // Enter en inputs → submit
    p.querySelectorAll('input').forEach(function (inp) {
      inp.addEventListener('keydown', function (e) {
        if (e.key === 'Enter') {
          if (inp.closest('.auth-form-login')) submitLogin();
          else submitRegister();
        }
      });
    });

    // Ojito: mostrar/ocultar contraseña
    p.querySelectorAll('.auth-eye').forEach(function (btn) {
      btn.addEventListener('click', function () {
        var input = p.querySelector('#' + btn.getAttribute('data-toggle-pass'));
        if (!input) return;
        var show = input.type === 'password';
        input.type = show ? 'text' : 'password';
        btn.classList.toggle('active', show);
        btn.setAttribute('aria-label', show ? 'Ocultar contraseña' : 'Mostrar contraseña');
        btn.querySelector('i').className = show ? 'fas fa-eye-slash' : 'fas fa-eye';
      });
    });
  }

  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && panel && panel.classList.contains('open')) closeLogin();
  });

  loadSession();

  // Exponer API global
  window.CodeHubAuth = {
    isLogged: function () { return !!session; },
    getUser: function () { return session ? session.user : null; },
    getSession: function () { return session; },
    deviceId: deviceId,
    openLogin: openLogin,
    closeLogin: closeLogin,
    loginWithGoogle: loginWithGoogle,
    submitLogin: submitLogin,
    submitRegister: submitRegister,
    logout: logout,
    checkLimit: checkLimit,
    consumeLimit: consumeLimit,
    on: function (cb) { if (typeof cb === 'function') listeners.push(cb); }
  };

  // Bindear tras DOMContentLoaded (y ya si está listo)
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bind);
  } else {
    bind();
  }
  // Procesar el retorno del flujo Google OAuth (?auth=google&token=...) y abrir el dashboard
  handleGoogleReturn();
  // Reflejar el estado inicial y actualizar si cambia el idioma
  updateSideUI();
  document.addEventListener('ch:langchange', function () {
    if (session) { updateSideUI(); return; }
    // Invitado: restaurar el texto traducido
    var btn = document.getElementById('side-account-btn');
    if (btn) {
      var label = btn.querySelector('span');
      var dict = (typeof i18n !== 'undefined' && i18n[currentLang])
        ? i18n[currentLang] : {};
      if (label) label.textContent = dict.sidePrefAccount || 'Mi cuenta';
    }
  });
})();
