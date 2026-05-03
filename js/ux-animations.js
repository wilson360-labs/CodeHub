// ═══════════════════════════════════════════════════════════════
//  UX ANIMATIONS — CodeHub by Wilson.E
//  Anime.js: logo, chips, counters, reveals, cursor, mobile nav
// ═══════════════════════════════════════════════════════════════
(function () {
  'use strict';

  function ready(cb) {
    if (document.readyState !== 'loading') cb();
    else document.addEventListener('DOMContentLoaded', cb);
  }

  function waitAnime(cb, n) {
    n = n || 0;
    if (window.anime) return cb();
    if (n > 50) return;
    setTimeout(function () { waitAnime(cb, n + 1); }, 80);
  }

  // ── 1. CUSTOM CURSOR ─────────────────────────────────────────
  function initCursor() {
    var cur = document.getElementById('ch-cursor');
    var ring = document.getElementById('ch-cursor-ring');
    if (!cur || !ring || window.matchMedia('(pointer:coarse)').matches) return;

    var mx = 0, my = 0, rx = 0, ry = 0;
    document.addEventListener('mousemove', function (e) {
      mx = e.clientX; my = e.clientY;
      cur.style.transform = 'translate(' + mx + 'px,' + my + 'px) translate(-50%,-50%)';
    });

    // Ring follows with lag
    (function loop() {
      rx += (mx - rx) * 0.13;
      ry += (my - ry) * 0.13;
      ring.style.transform = 'translate(' + rx + 'px,' + ry + 'px) translate(-50%,-50%)';
      requestAnimationFrame(loop);
    })();

    // Hover state on interactive elements
    document.addEventListener('mouseover', function (e) {
      if (e.target.closest('a,button,[onclick],[role=button],label')) {
        cur.classList.add('hovering');
        ring.classList.add('hovering');
      }
    });
    document.addEventListener('mouseout', function (e) {
      if (e.target.closest('a,button,[onclick],[role=button],label')) {
        cur.classList.remove('hovering');
        ring.classList.remove('hovering');
      }
    });
  }

  // ── 2. LOGO ENTRANCE + HOVER ─────────────────────────────────
  function initLogo() {
    var b1  = document.querySelector('.header-logo-text .bracket:first-child');
    var b2  = document.querySelector('.header-logo-text .bracket:last-child');
    var name  = document.querySelector('#liq-logo-wilson');
    var badge = document.querySelector('#liq-logo-codehub');
    var logo  = document.querySelector('.header-logo');
    if (!logo || !b1) return;

    anime.set([b1, b2, name, badge], { opacity: 0 });
    anime.timeline({ easing: 'easeOutExpo' })
      .add({ targets: b1,    opacity: [0,1], translateX: [-14,0], duration: 380 })
      .add({ targets: name,  opacity: [0,1], translateY: [-10,0], duration: 360 }, '-=180')
      .add({ targets: b2,    opacity: [0,1], translateX: [14,0],  duration: 380 }, '-=280')
      .add({ targets: badge, opacity: [0,1], scale: [0.65,1],     duration: 340 }, '-=200');

    logo.addEventListener('mouseenter', function () {
      anime({ targets: badge, scale: [1,1.1,1], duration: 520, easing: 'easeInOutBack' });
    });
  }

  // ── 3. SKILL CHIPS STAGGER ───────────────────────────────────
  function initChips() {
    var grids = document.querySelectorAll('.skills-grid');
    if (!grids.length) return;
    var seen = new Set();
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (!e.isIntersecting || seen.has(e.target)) return;
        seen.add(e.target);
        var items = e.target.querySelectorAll('.skill-chip,.tool-badge');
        anime.set(items, { opacity: 0, translateY: 20, scale: 0.92 });
        anime({ targets: items, opacity: 1, translateY: 0, scale: 1,
          duration: 400, delay: anime.stagger(50, { start: 60 }), easing: 'easeOutBack' });
      });
    }, { threshold: 0.1 });
    grids.forEach(function (g) { io.observe(g); });
  }

  // ── 4. ANIMATED STAT COUNTERS ────────────────────────────────
  function initCounters() {
    var section = document.getElementById('stats');
    if (!section) return;
    var done = false;
    new IntersectionObserver(function (entries) {
      if (!entries[0].isIntersecting || done) return;
      done = true;
      var els = section.querySelectorAll('.stat-number, [class*="stat-num"]');
      els.forEach(function (el) {
        var raw = parseInt(el.textContent.replace(/\D/g, ''), 10);
        if (!raw || raw < 2) return;
        var suffix = el.textContent.replace(/[\d,]/g, '').trim();
        var obj = { n: 0 };
        anime({ targets: obj, n: raw, round: 1, duration: 1600, easing: 'easeOutExpo',
          update: function () {
            el.textContent = obj.n.toLocaleString() + (suffix ? ' ' + suffix : '');
          }
        });
      });
    }, { threshold: 0.3 }).observe(section);
  }

  // ── 5. SCROLL REVEAL ─────────────────────────────────────────
  function initReveal() {
    var targets = document.querySelectorAll(
      '#services .service-card, .why-card, #skills .skills-header'
    );
    if (!targets.length) return;
    anime.set(targets, { opacity: 0, translateY: 22 });
    var io = new IntersectionObserver(function (entries) {
      var vis = entries.filter(function (e) { return e.isIntersecting; }).map(function (e) { return e.target; });
      if (!vis.length) return;
      anime({ targets: vis, opacity: 1, translateY: 0, duration: 480,
        delay: anime.stagger(65), easing: 'easeOutQuart' });
      vis.forEach(function (el) { io.unobserve(el); });
    }, { threshold: 0.1 });
    targets.forEach(function (el) { io.observe(el); });
  }

  // ── 6. CHIP ICON BOUNCE ON HOVER ────────────────────────────
  function initIconHover() {
    document.addEventListener('mouseover', function (e) {
      var chip = e.target.closest('.skill-chip');
      if (!chip) return;
      var img = chip.querySelector('.sc-icon img');
      if (img) anime({ targets: img, scale: [1,1.25,1], rotate: [0,-8,5,0],
        duration: 450, easing: 'easeInOutBack' });
    });
  }

  // ── 7. MOBILE NAV — active state + haptic ───────────────────
  function initMobileNav() {
    var bar = document.getElementById('mobile-nav-bar');
    if (!bar) return;

    // Mark current page active
    var path = window.location.pathname.replace(/\/$/, '') || '/';
    bar.querySelectorAll('a').forEach(function (a) {
      a.classList.remove('active');
      var href = a.getAttribute('href').replace(/\/$/, '') || '/';
      if (path === href) a.classList.add('active');
    });

    // Anime.js tap feedback + haptic
    bar.querySelectorAll('a').forEach(function (a) {
      a.addEventListener('click', function () {
        var icon = a.querySelector('.mnb-icon');
        anime({ targets: icon, scale: [1, 1.35, 1], duration: 380, easing: 'easeOutBack' });
        if (navigator.vibrate) navigator.vibrate(45);
      });
    });

    // Slide up entrance
    anime({ targets: bar, translateY: [80, 0], opacity: [0, 1],
      duration: 520, delay: 300, easing: 'easeOutExpo' });
  }

  // ── 8. DARK MODE CLIP-PATH TRANSITION ───────────────────────
  function initThemeTransition() {
    var btn = document.querySelector('[onclick*="theme"],[onclick*="Theme"],[onclick*="dark"],[onclick*="Dark"],.theme-toggle,.theme-btn,#theme-btn');
    if (!btn) return;
    btn.addEventListener('click', function (e) {
      var r = btn.getBoundingClientRect();
      var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      var maxR = Math.hypot(Math.max(cx, innerWidth - cx), Math.max(cy, innerHeight - cy));
      var ov = document.createElement('div');
      ov.style.cssText = 'position:fixed;inset:0;z-index:9990;pointer-events:none;' +
        'background:var(--bg,#0a0a0f);clip-path:circle(0px at '+cx+'px '+cy+'px)';
      document.body.appendChild(ov);
      anime({ targets: ov,
        clipPath: ['circle(0px at '+cx+'px '+cy+'px)', 'circle('+(maxR+10)+'px at '+cx+'px '+cy+'px)'],
        duration: 500, easing: 'easeInOutQuart',
        complete: function () { ov.remove(); }
      });
    }, true);
  }

  // ── 9. HERO TEXT STAGGER ────────────────────────────────────
  function initHero() {
    var hero = document.querySelector('.hero-greeting, .hero-name, .hero-title');
    if (!hero) return;
    var els = document.querySelectorAll('.hero-greeting, .hero-name, .hero-title, .hero-sub, .hero-actions');
    anime.set(els, { opacity: 0, translateY: 16 });
    anime({ targets: els, opacity: 1, translateY: 0,
      duration: 600, delay: anime.stagger(90, { start: 200 }), easing: 'easeOutQuart' });
  }

  // ── INIT ────────────────────────────────────────────────────
  ready(function () {
    initCursor();
    initMobileNav();
    waitAnime(function () {
      initLogo();
      initHero();
      initChips();
      initCounters();
      initReveal();
      initIconHover();
      initThemeTransition();
    });
  });

})();
