// ═══════════════════════════════════════════════════════════════
//  UX ANIMATIONS v2 — CodeHub by Wilson.E
//  Post-splash cinematic entrance + all UX enhancements
// ═══════════════════════════════════════════════════════════════
(function () {
  'use strict';

  // ── Utilities ───────────────────────────────────────────────
  function $(sel, ctx) { return (ctx || document).querySelector(sel); }
  function $$(sel, ctx) { return Array.from((ctx || document).querySelectorAll(sel)); }
  function ready(cb) {
    document.readyState !== 'loading' ? cb() : document.addEventListener('DOMContentLoaded', cb);
  }
  function waitAnime(cb, n) {
    n = n || 0;
    if (window.anime) return cb();
    if (n > 60) return;
    setTimeout(function () { waitAnime(cb, n + 1); }, 80);
  }

  // ── 1. POST-SPLASH CINEMATIC ENTRANCE ───────────────────────
  // Runs once after the splash screen fades out
  function initPostSplashEntrance() {
    // Watch for splash removal
    var splash = document.getElementById('ch-splash');
    if (!splash) { runEntrance(); return; }

    var observer = new MutationObserver(function (mutations) {
      mutations.forEach(function (m) {
        m.addedNodes.forEach(function (node) {
          if (node.nodeType === 1 && node.classList && node.classList.contains('ch-splash--out')) {
            setTimeout(runEntrance, 500); // wait for splash fade duration
            observer.disconnect();
          }
        });
        // Also watch for class change on splash itself
        if (m.type === 'attributes' && m.attributeName === 'class') {
          if (splash.classList.contains('ch-splash--out')) {
            setTimeout(runEntrance, 500);
            observer.disconnect();
          }
        }
      });
    });

    observer.observe(splash, { attributes: true, attributeFilter: ['class'] });

    // Fallback: if splash is already gone or takes too long
    setTimeout(function () {
      if (splash.style.display === 'none' || splash.classList.contains('ch-splash--out')) {
        runEntrance();
        observer.disconnect();
      }
    }, 4500);
  }

  function runEntrance() {
    if (window._chEntranceDone) return;
    window._chEntranceDone = true;

    waitAnime(function () {
      var header  = $('header');
      var hero    = $$('.hero-greeting, .hero-name, .hero-title, .hero-desc, .hero-stack, .hero-ctas, .hero-terminal');
      var scanline = $('#hero-scanline, .hero-scanline');

      // Set initial states
      if (header) anime.set(header, { opacity: 0, translateY: -20 });
      if (hero.length) anime.set(hero, { opacity: 0, translateY: 28, filter: 'blur(4px)' });

      // Timeline: header → hero elements cascade
      var tl = anime.timeline({ easing: 'easeOutExpo' });

      if (header) {
        tl.add({
          targets: header,
          opacity: [0, 1],
          translateY: [-20, 0],
          duration: 600
        });
      }

      if (hero.length) {
        tl.add({
          targets: hero,
          opacity: [0, 1],
          translateY: [28, 0],
          filter: ['blur(4px)', 'blur(0px)'],
          duration: 700,
          delay: anime.stagger(80)
        }, '-=300');
      }

      // Subtle page-wide reveal: background particles appear
      var canvas = $('#ch-hero-canvas, canvas:not(#ch-splash-canvas)');
      if (canvas) {
        tl.add({ targets: canvas, opacity: [0, 1], duration: 800 }, '-=400');
      }
    });
  }

  // ── 2. CUSTOM CURSOR ────────────────────────────────────────
  function initCursor() {
    if (window.matchMedia('(pointer:coarse)').matches) return;
    var cur  = document.getElementById('ch-cursor');
    var ring = document.getElementById('ch-cursor-ring');
    if (!cur || !ring) return;

    var mx = -100, my = -100, rx = -100, ry = -100;

    document.addEventListener('mousemove', function (e) {
      mx = e.clientX; my = e.clientY;
      cur.style.left = mx + 'px';
      cur.style.top  = my + 'px';
    });

    (function loop() {
      rx += (mx - rx) * 0.13;
      ry += (my - ry) * 0.13;
      ring.style.left = rx + 'px';
      ring.style.top  = ry + 'px';
      requestAnimationFrame(loop);
    })();

    document.addEventListener('mouseover', function (e) {
      if (e.target.closest('a,button,[onclick],[role=button],label,.skill-chip,.service-card')) {
        cur.classList.add('hovering');
        ring.classList.add('hovering');
      }
    });
    document.addEventListener('mouseout', function (e) {
      if (e.target.closest('a,button,[onclick],[role=button],label,.skill-chip,.service-card')) {
        cur.classList.remove('hovering');
        ring.classList.remove('hovering');
      }
    });
  }

  // ── 4. SKILL CHIPS STAGGER ──────────────────────────────────
  function initChips() {
    var grids = $$('.skills-grid');
    if (!grids.length) return;
    var seen = new Set();
    new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (!e.isIntersecting || seen.has(e.target)) return;
        seen.add(e.target);
        var items = $$('. skill-chip,.tool-badge', e.target);
        // fix: remove space
        items = Array.from(e.target.querySelectorAll('.skill-chip,.tool-badge'));
        if (!items.length) return;
        anime.set(items, { opacity: 0, translateY: 22, scale: 0.9 });
        anime({ targets: items, opacity: 1, translateY: 0, scale: 1,
          duration: 420, delay: anime.stagger(55, { start: 50 }), easing: 'easeOutBack' });
      });
    }, { threshold: 0.1 }).observe(grids[0].parentElement || grids[0]);

    // Observe each grid individually
    grids.forEach(function (g) {
      var io = new IntersectionObserver(function (entries) {
        if (!entries[0].isIntersecting || seen.has(entries[0].target)) return;
        seen.add(entries[0].target);
        var items = Array.from(entries[0].target.querySelectorAll('.skill-chip,.tool-badge'));
        if (!items.length) return;
        anime.set(items, { opacity: 0, translateY: 22, scale: 0.9 });
        anime({ targets: items, opacity: 1, translateY: 0, scale: 1,
          duration: 420, delay: anime.stagger(55, { start: 50 }), easing: 'easeOutBack' });
        io.disconnect();
      }, { threshold: 0.08 });
      io.observe(g);
    });
  }

  // ── 6. SCROLL REVEAL ────────────────────────────────────────
  function initReveal() {
    var targets = $$([
      '#services .service-card',
      '.why-card',
      '#skills .skills-header',
      '#weather-section',
      '#news-section',
      '#open-to-work .otw-info-card',
      '#open-to-work .contact-form-wrap'
    ].join(', '));
    if (!targets.length) return;
    anime.set(targets, { opacity: 0, translateY: 26 });
    var io = new IntersectionObserver(function (entries) {
      var vis = entries.filter(function (e) { return e.isIntersecting; }).map(function (e) { return e.target; });
      if (!vis.length) return;
      anime({ targets: vis, opacity: 1, translateY: 0, duration: 500,
        delay: anime.stagger(70), easing: 'easeOutQuart' });
      vis.forEach(function (el) { io.unobserve(el); });
    }, { threshold: 0.12 });
    targets.forEach(function (el) { io.observe(el); });
  }

  // ── 7. ICON BOUNCE ON HOVER ─────────────────────────────────
  function initIconHover() {
    document.addEventListener('mouseover', function (e) {
      var chip = e.target.closest('.skill-chip');
      if (!chip) return;
      var img = chip.querySelector('.sc-icon img');
      if (img) anime({ targets: img, scale: [1,1.3,1], rotate: [0,-10,6,0],
        duration: 480, easing: 'easeInOutBack' });
    });
  }

  // ── 8. MOBILE BOTTOM NAV ────────────────────────────────────
  function initMobileNav() {
    var bar = document.getElementById('mobile-nav-bar');
    if (!bar) return;

    var path = window.location.pathname.replace(/\/$/, '') || '/';
    bar.querySelectorAll('a').forEach(function (a) {
      a.classList.remove('active');
      var href = (a.getAttribute('href') || '').replace(/\/$/, '') || '/';
      if (path === href) a.classList.add('active');
    });

    bar.querySelectorAll('a').forEach(function (a) {
      a.addEventListener('click', function () {
        bar.querySelectorAll('a').forEach(function(x){ x.classList.remove('active'); });
        a.classList.add('active');
        anime({ targets: a.querySelector('.mnb-icon'), scale: [1,1.4,1],
          duration: 400, easing: 'easeOutBack' });
        if (navigator.vibrate) navigator.vibrate(42);
      });
    });

    // Only animate on mobile (640px)
    if (window.innerWidth > 640) return;
    anime.set(bar, { translateY: 80, opacity: 0 });
    setTimeout(function () {
      anime({ targets: bar, translateY: 0, opacity: 1,
        duration: 550, easing: 'easeOutExpo' });
    }, 1200);
  }

  // ── 9. DARK MODE CLIP-PATH ──────────────────────────────────
  function initThemeTransition() {
    // Find theme button by common patterns
    var btn = document.querySelector(
      '[onclick*="theme"],[onclick*="Theme"],[onclick*="dark"],[onclick*="Dark"],' +
      '[onclick*="toggleDark"],[onclick*="setTheme"],.theme-toggle,#theme-btn,.cfg-item-toggle'
    );
    if (!btn) return;
    btn.addEventListener('click', function () {
      var r = btn.getBoundingClientRect();
      var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      var maxR = Math.hypot(Math.max(cx, innerWidth - cx), Math.max(cy, innerHeight - cy)) + 20;
      var ov = document.createElement('div');
      var isDark = document.documentElement.classList.contains('light') || document.body.classList.contains('light');
      ov.style.cssText = 'position:fixed;inset:0;z-index:9992;pointer-events:none;' +
        'background:' + (isDark ? '#06060f' : '#f5f5f0') + ';' +
        'clip-path:circle(0px at ' + cx + 'px ' + cy + 'px)';
      document.body.appendChild(ov);
      anime({ targets: ov,
        clipPath: ['circle(0px at '+cx+'px '+cy+'px)',
                   'circle('+maxR+'px at '+cx+'px '+cy+'px)'],
        duration: 520, easing: 'easeInOutQuart',
        complete: function () { ov.remove(); }
      });
    }, true);
  }

  // ── 10. SERVICE CARD MAGNETIC HOVER ────────────────────────
  function initMagneticCards() {
    if (window.matchMedia('(pointer:coarse)').matches) return;
    $$('.service-card').forEach(function (card) {
      card.addEventListener('mousemove', function (e) {
        var r = card.getBoundingClientRect();
        var dx = (e.clientX - r.left - r.width / 2) / r.width;
        var dy = (e.clientY - r.top - r.height / 2) / r.height;
        anime({ targets: card,
          rotateY: dx * 8, rotateX: -dy * 6,
          translateZ: 12,
          duration: 200, easing: 'linear' });
      });
      card.addEventListener('mouseleave', function () {
        anime({ targets: card, rotateY: 0, rotateX: 0, translateZ: 0,
          duration: 400, easing: 'easeOutElastic(1,.6)' });
      });
    });
  }

  // ── 11. FOOTER TERMINAL — typing effect al entrar en viewport ──
  function initFooterTerminalTyping() {
    var box = document.getElementById('footer-terminal');
    var cmd = document.getElementById('footer-terminal-cmd');
    if (!box || !cmd) return;
    // Nota: antes se omitía con prefers-reduced-motion; ahora siempre anima.

    var fullText = cmd.textContent;
    var played = false;

    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting || played) return;
        played = true;
        io.disconnect();
        cmd.textContent = '';
        var i = 0;
        (function typeNext() {
          if (i > fullText.length) return;
          cmd.textContent = fullText.slice(0, i);
          i++;
          setTimeout(typeNext, 26);
        })();
      });
    }, { threshold: 0.4 });

    io.observe(box);
  }

  // ── 12. TEXT MORPH — badge del logo cicla roles con fade ──
  // Recrea el componente textmorph de originkit en vanilla:
  // las palabras están apiladas (grid 1/1) y se alterna .is-active;
  // la transición blur+scale+fade funde las letras entre una palabra
  // y la siguiente (sin filtro gooey, que rompía la legibilidad).
  function initTextMorph() {
    var wrap = document.getElementById('tm-words');
    if (!wrap) return;
    var words = [].slice.call(wrap.querySelectorAll('.tm-word'));
    if (words.length < 2) return;

    // Sin animación si el usuario prefiere reducir movimiento
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;

    var HOLD = 2400;      // ms que cada palabra queda visible
    var i = 0;

    (function cycle() {
      setTimeout(function () {
        words[i].classList.remove('is-active');
        i = (i + 1) % words.length;
        words[i].classList.add('is-active');
        cycle();
      }, HOLD);
    })();
  }

  // ── INIT ────────────────────────────────────────────────────
  ready(function () {
    // initCursor(); // DISABLED: custom cursor animation causes lag
    initMobileNav();
    initPostSplashEntrance();
    initFooterTerminalTyping();
    initTextMorph();
    waitAnime(function () {
      initChips();
      initReveal();
      initIconHover();
      initThemeTransition();
      // initMagneticCards(); // DISABLED: magnetic hover causes lag on mousemove
    });
  });

})();
