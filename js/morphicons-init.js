// ═══════════════════════════════════════════════════════
//  CodeHub — Morphicons (v1.7.0, MIT) — Wilson.E 2026
//  Custom element <morph-icon>: iconos stroke-based que
//  morfean entre sí con física de resorte. Vendor local en
//  js/vendor/morphicons/ (árbol ESM completo).
//  Se define el elemento y se exponen los helpers para que
//  theme-switcher.js y los toggles hagan morphTo().
// ═══════════════════════════════════════════════════════
import { defineMorphIcon } from './vendor/morphicons/element.js';

defineMorphIcon('morph-icon');

// Paths Lucide (24×24, stroke) usados por los toggles de CodeHub.
window.CodeHubMorphPaths = {
  moon:  'M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z',
  sun:   'M12 17a5 5 0 0 0 0-10 5 5 0 0 0 0 10zM12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42',
  bell:  'M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.94 1.94 0 0 0 3.4 0',
  bellOff: 'M8.7 3A6 6 0 0 1 18 8a21.3 21.3 0 0 0 .6 5M17 17H3s3-2 3-9a4.67 4.67 0 0 1 .3-1.7M10.3 21a1.94 1.94 0 0 0 3.4 0M2 2l20 20',
};

// Morfea el primer <morph-icon> dentro de `root` (si existe) hacia el path.
window.CodeHubMorphTo = function (root, pathName, spring) {
  if (!root || !window.CodeHubMorphPaths) return false;
  var el = root.querySelector('morph-icon[data-morph]');
  if (!el || typeof el.morphTo !== 'function') return false;
  var d = window.CodeHubMorphPaths[pathName];
  if (!d) return false;
  try { el.morphTo(d, spring); } catch (e) {}
  return true;
};

// Ocultar los iconos FA de respaldo cuando el morph-icon está activo.
function chHideFaIcons() {
  var groups = [
    { btnSel: '[data-theme-toggle]', faSel: '[data-fa-theme-icon]' },
    { btnSel: '#wx-alerts-toggle', faSel: '[data-fa-wx-icon]' },
  ];
  groups.forEach(function (g) {
    var btns = document.querySelectorAll(g.btnSel);
    Array.prototype.forEach.call(btns, function (btn) {
      var fa = btn.querySelector(g.faSel);
      if (fa) fa.style.display = 'none';
    });
  });
}

document.addEventListener('DOMContentLoaded', function () {
  chHideFaIcons();
  // Sincronizar el morph con el tema actual (theme-switcher puede haber
  // corrido antes que este módulo y dejado el icono FA en su rama fallback).
  if (window.CodeHubTheme) {
    window.CodeHubTheme.apply(window.CodeHubTheme.get(), false);
  }
  document.dispatchEvent(new CustomEvent('ch:morphicons-ready'));
});
