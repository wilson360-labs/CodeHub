(function () {
  'use strict';

  var STORAGE_KEY = 'theme';
  var root = document.documentElement;
  var btn = null;

  function getInitialTheme() {
    var saved = null;
    try { saved = localStorage.getItem(STORAGE_KEY); } catch (e) {}
    if (saved === 'light' || saved === 'dark') return saved;
    if (window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches) return 'light';
    return 'dark';
  }

  function applyTheme(theme, save) {
    var isLight = theme === 'light';
    root.setAttribute('data-theme', isLight ? 'light' : 'dark');
    document.body.classList.toggle('light-mode', isLight);
    if (btn) {
      btn.classList.toggle('active', isLight);
      var icon = btn.querySelector('.theme-toggle-icon');
      if (icon) {
        icon.className = 'fas theme-toggle-icon ' + (isLight ? 'fa-sun' : 'fa-moon');
      }
      btn.setAttribute('aria-pressed', String(isLight));
      btn.setAttribute('title', isLight ? 'Cambiar a modo oscuro' : 'Cambiar a modo claro');
    }
    if (save) {
      try { localStorage.setItem(STORAGE_KEY, theme); } catch (e) {}
    }
    document.dispatchEvent(new CustomEvent('ch:themechange', { detail: { theme: theme } }));
  }

  function toggleTheme() {
    var isLight = root.getAttribute('data-theme') === 'light';
    applyTheme(isLight ? 'dark' : 'light', true);
  }

  document.addEventListener('DOMContentLoaded', function () {
    btn = document.getElementById('theme-toggle');
    if (btn) {
      btn.addEventListener('click', toggleTheme);
    }
    // Sincronizar con el panel de configuración si existe el grupo tema
    var themeGroup = document.querySelectorAll('#config-panel [data-theme-option]');
    if (themeGroup.length) {
      document.addEventListener('ch:themechange', function (e) {
        themeGroup.forEach(function (el) {
          el.classList.toggle('active', el.getAttribute('data-theme-option') === e.detail.theme);
        });
      });
    }
    // Aplicar el tema guardado (o el del sistema) al cargar
    applyTheme(getInitialTheme(), false);
  });

  // Aplicar temprano si el body ya está disponible (script diferido no siempre lo garantiza)
  if (document.body) applyTheme(getInitialTheme(), false);

  window.CodeHubTheme = { apply: applyTheme, toggle: toggleTheme, get: function () { return root.getAttribute('data-theme') || 'dark'; } };
})();
