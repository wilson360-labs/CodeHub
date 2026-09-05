/* ════════════════════════════════════════════════════════════════════
   view-transitions.js — Transiciones de vista tipo "tabs de Android"
   CodeHub by Wilson.E
   Intercepta el menú inferior (mobile-nav-bar) y hace slide lateral
   entre "secciones" con la View Transitions API nativa.
   - Inicio (/): vive en este documento → document.startViewTransition
     + scroll a #hero, con dirección fwd/back según el orden de tabs.
   - Tools/Apps/Servicios: páginas propias → MPA View Transitions vía
     CSS (@view-transition: navigation auto) heredando la dirección
     en <html data-vt> antes de recargar.
   - Fallback: navegación normal del navegador si la API no existe.
   Vanilla JS, sin dependencias. ¿Sin API? El sitio sigue perfecto.
   ════════════════════════════════════════════════════════════════════ */
(() => {
  'use strict';
  var NAV = document.getElementById('mobile-nav-bar');
  if (!NAV) return;

  /* Tabs locales que "deslizan" sin salir de la página (id de sección) */
  var LOCAL = { '/': 'hero' };

  var order = Array.prototype.slice.call(NAV.querySelectorAll('a')).map(function (a) {
    return (a.getAttribute('href') || '/').replace(/\/$/, '') || '/';
  });

  var hasVT = typeof document.startViewTransition === 'function';

  function currentTab() {
    var cur = NAV.dataset.current;
    return cur ? cur.replace(/\/$/, '') || '/' : '/';
  }

  function dirFor(href) {
    var cur = order.indexOf(currentTab());
    var next = order.indexOf(href);
    if (cur === -1 || next === -1 || next === cur) return 'fwd';
    return next > cur ? 'fwd' : 'back';
  }

  function markActive(a) {
    var links = NAV.querySelectorAll('a');
    for (var i = 0; i < links.length; i++) links[i].classList.remove('active');
    a.classList.add('active');
    NAV.dataset.current = (a.getAttribute('href') || '/').replace(/\/$/, '') || '/';
  }

  NAV.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a') : null;
    if (!a) return;

    var href = (a.getAttribute('href') || '/').replace(/\/$/, '') || '/';
    var dir = dirFor(href);
    document.documentElement.dataset.vt = dir; /* la MPA también lo hereda */

    /* Inicio: slide nativo dentro del documento */
    if (LOCAL[href] && hasVT) {
      e.preventDefault();
      var t = document.startViewTransition(function () {
        var sec = document.getElementById(LOCAL[href]);
        if (sec) window.scrollTo(0, sec.getBoundingClientRect().top + window.scrollY);
        markActive(a);
      });
      var done = t.finished ? t.finished : Promise.resolve();
      done.catch(function () {}).finally(function () {
        delete document.documentElement.dataset.vt;
      });
    }

    /* Cross-page: marca al instante y navega (la transición la hace CSS) */
    if (LOCAL[href] && !hasVT) {
      e.preventDefault();
      var sec2 = document.getElementById(LOCAL[href]);
      if (sec2) sec2.scrollIntoView({ behavior: 'smooth', block: 'start' });
      markActive(a);
    }
    markActive(a);
  }, true);
})();