/* ═══════════════════════════════════════════════════════════
   CodeHub Remote Config — frontend loader
   Fetches config from /api/config, caches in localStorage,
   provides RC.get() for feature flags, limits, UI, etc.
   ═══════════════════════════════════════════════════════════ */
(function() {
  'use strict';

  var STORAGE_KEY = 'ch_remote_config';
  var REFRESH_INTERVAL = 5 * 60 * 1000; // 5 minutes
  var _config = null;
  var _version = 0;
  var _refreshTimer = null;
  var _readyResolve = null;
  var _readyPromise = new Promise(function(resolve) { _readyResolve = resolve; });

  var DEFAULTS = {
    version: 1,
    features: {
      chatEnabled: true,
      imageGenEnabled: true,
      weatherEnabled: true,
      weatherAutoRefresh: true,
      weatherRefreshMin: 5,
      tourEnabled: true,
      newsEnabled: true,
      searchEnabled: true,
      pushEnabled: true,
      contactEnabled: true,
      skillsEnabled: true,
      resolverEnabled: true,
      crashReportEnabled: true,
      updateDialogEnabled: true,
      heroInstallBtn: true,
      consentBanner: true,
      easterEgg: false,
    },
    limits: {
      emiDailyGuest: 10,
      emiDailyRegistered: 50,
      chatRateLimit: 50,
      imageRateLimit: 20,
      imageCacheTTL: 3600,
      imageCacheMax: 200,
      notifDedupWindow: 300000,
      tourCooldown: 86400000,
      sessionTTL: 1800000,
    },
    ui: {
      heroTitle: 'CodeHub',
      heroSubtitle: 'Tu centro de desarrollo IA',
      consentText: 'Usamos cookies para mejorar tu experiencia.',
      weatherCityFallback: 'Ciudad de Guatemala',
      updateDialogTitle: 'Nueva versión disponible',
      updateDialogBody: 'Hay una nueva versión de CodeHub disponible.',
    },
    ai: {
      systemPrompt: null,
      maxTokensDefault: 2500,
      temperature: 0.65,
      providerPriority: null,
    },
    maintenance: {
      enabled: false,
      message: 'CodeHub está en mantenimiento. Vuelve pronto.',
    },
  };

  function deepMerge(target, source) {
    var result = {};
    var keys = Object.keys(target);
    for (var i = 0; i < keys.length; i++) result[keys[i]] = target[keys[i]];
    if (!source) return result;
    var skeys = Object.keys(source);
    for (var j = 0; j < skeys.length; j++) {
      var k = skeys[j];
      if (source[k] && typeof source[k] === 'object' && !Array.isArray(source[k]) && target[k] && typeof target[k] === 'object') {
        result[k] = deepMerge(target[k], source[k]);
      } else {
        result[k] = source[k];
      }
    }
    return result;
  }

  function loadFromStorage() {
    try {
      var raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return null;
      var parsed = JSON.parse(raw);
      if (parsed && parsed._ts && (Date.now() - parsed._ts) < 3600000) {
        return parsed;
      }
    } catch (e) { /* ignore */ }
    return null;
  }

  function saveToStorage(data) {
    try {
      data._ts = Date.now();
      localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
    } catch (e) { /* quota exceeded — ignore */ }
  }

  async function fetchConfig() {
    try {
      var stored = loadFromStorage();
      var clientVersion = stored ? (stored.version || 0) : 0;
      var url = '/api/config' + (clientVersion ? '?v=' + clientVersion : '');
      var res = await fetch(url);
      if (!res.ok) throw new Error('HTTP ' + res.status);
      var body = await res.json();
      if (body.ok && body.config) {
        var serverVersion = body.config.version || 0;
        var hasKey = !!(body.config.ui && body.config.ui.googleMapsKey);
        if (hasKey || serverVersion > clientVersion || !stored) {
          _config = deepMerge(DEFAULTS, body.config);
          _version = _config.version || 1;
          saveToStorage(_config);
        } else {
          _config = stored;
          _version = stored.version || 1;
        }
      } else {
        _config = stored || DEFAULTS;
        _version = _config.version || 1;
      }
    } catch (e) {
      console.warn('[RC] fetch error, using cache/defaults:', e.message);
      var fallback = loadFromStorage();
      _config = fallback || DEFAULTS;
      _version = (_config && _config.version) || 1;
    }
    if (_readyResolve) { _readyResolve(_config); _readyResolve = null; }
    return _config;
  }

  function startAutoRefresh() {
    if (_refreshTimer) clearInterval(_refreshTimer);
    _refreshTimer = setInterval(function() { fetchConfig(); }, REFRESH_INTERVAL);
  }

  // ── Public API ──
  window.RC = {
    ready: function() { return _readyPromise; },

    get: function(path, fallback) {
      if (!_config) return fallback !== undefined ? fallback : DEFAULTS;
      var parts = (path || '').split('.');
      var val = _config;
      for (var i = 0; i < parts.length; i++) {
        if (val && typeof val === 'object' && parts[i] in val) {
          val = val[parts[i]];
        } else {
          return fallback !== undefined ? fallback : undefined;
        }
      }
      return val;
    },

    feature: function(name) {
      return !!RC.get('features.' + name, true);
    },

    limit: function(name, fallback) {
      return RC.get('limits.' + name, fallback !== undefined ? fallback : 0);
    },

    ui: function(name, fallback) {
      return RC.get('ui.' + name, fallback || '');
    },

    isMaintenance: function() {
      return !!RC.get('maintenance.enabled', false);
    },

    maintenanceMessage: function() {
      return RC.get('maintenance.message', 'En mantenimiento.');
    },

    version: function() { return _version; },

    all: function() { return _config || DEFAULTS; },

    refresh: function() { return fetchConfig(); },
  };

  // ── Init ──
  fetchConfig().then(function() { startAutoRefresh(); });
})();
