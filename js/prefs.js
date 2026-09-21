// ═══════════════════════════════════════════════════════
//  prefs.js — Personalización de estilos (sin animaciones)
//  Aplica preferencias de apariencia (acento, esquinas,
//  densidad, contraste, fondo, kill-switches de widgets)
//  como atributos de <html> (data-accent, data-radius, …).
//  Se carga en <head> (síncrono, antes del render) para
//  evitar flash de estilos. Persistencia: localStorage cfg_*
//  igual que las demás preferencias del panel (setCfg).
// ═══════════════════════════════════════════════════════
(function () {
  'use strict';

  var KEYS = ['accent', 'radius', 'density', 'contrast', 'bg', 'wf', 'wh', 'ws', 'wk'];
  var DEFAULTS = { accent: 'azul', radius: 'medio', density: 'normal', contrast: 'normal', bg: 'auto', wf: 'on', wh: 'on', ws: 'on', wk: 'on' };
  var SWATCH_HEX = { azul: '#2f80ed', cian: '#0ea5e9', verde: '#16a34a', violeta: '#7c4dff', rosa: '#ec4899', rojo: '#ef4444', ambar: '#f59e0b' };

  function read(key) {
    try {
      return localStorage.getItem('cfg_' + key) || DEFAULTS[key];
    } catch (e) { return DEFAULTS[key]; }
  }

  function deepen(hex) {
    var h = String(hex || '').replace('#', '');
    if (!/^[0-9a-fA-F]{3}$|^[0-9a-fA-F]{6}$/.test(h)) return '#2563eb';
    if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
    var n = parseInt(h, 16);
    var r = Math.round(((n >> 16) & 255) * 0.78);
    var g = Math.round(((n >> 8) & 255) * 0.78);
    var b = Math.round((n & 255) * 0.78);
    return '#' + ((r << 16) | (g << 8) | b).toString(16).padStart(6, '0');
  }

  function apply() {
    var el = document.documentElement;
    var accent = read('accent');
    el.dataset.accent = accent;
    el.dataset.radius = read('radius');
    el.dataset.density = read('density');
    el.dataset.contrast = read('contrast');
    el.dataset.bg = read('bg');
    el.dataset.wf = read('wf');
    el.dataset.wh = read('wh');
    el.dataset.ws = read('ws');
    el.dataset.wk = read('wk');

    if (accent && accent.charAt(0) === '#') {
      el.style.setProperty('--accent', accent);
      el.style.setProperty('--primary', accent);
      el.style.setProperty('--primary-deep', deepen(accent));
    } else {
      el.style.removeProperty('--accent');
      el.style.removeProperty('--primary');
      el.style.removeProperty('--primary-deep');
    }
    markPanel();
  }

  function markPanel() {
    var accent = read('accent');
    document.querySelectorAll('[data-pref]').forEach(function (btn) {
      var val = read(btn.getAttribute('data-pref'));
      if (btn.classList.contains('cfg-opt')) {
        btn.classList.toggle('active', btn.dataset.prefval === val);
      } else if (btn.type === 'checkbox') {
        btn.checked = (val === 'on');
      }
    });
    var ci = document.getElementById('pref-accent-color');
    if (ci) ci.value = (accent.charAt(0) === '#') ? accent : (SWATCH_HEX[accent] || '#2f80ed');

    document.querySelectorAll('[data-pref-toggle]').forEach(function (chk) {
      chk.checked = (read(chk.getAttribute('data-pref-toggle')) === 'on');
    });
  }

  function set(key, val) {
    if (KEYS.indexOf(key) === -1) return;
    try { localStorage.setItem('cfg_' + key, val); } catch (e) {}
    apply();
  }

  function reset() {
    KEYS.forEach(function (k) {
      try { localStorage.removeItem('cfg_' + k); } catch (e) {}
    });
    apply();
  }

  window.addEventListener('storage', function (e) {
    if (e.key && e.key.indexOf('cfg_') === 0) apply();
  });

  document.addEventListener('input', function (e) {
    if (e.target && e.target.id === 'pref-accent-color' && e.target.value) set('accent', e.target.value);
  });

  document.addEventListener('change', function (e) {
    var t = e.target;
    if (t && t.getAttribute('data-pref-toggle')) set(t.getAttribute('data-pref-toggle'), t.checked ? 'on' : 'off');
  });

  window.Prefs = { set: set, reset: reset, apply: apply, read: read };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', apply);
  } else {
    apply();
  }
})();