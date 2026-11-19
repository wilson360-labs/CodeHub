// ============================================================
// CODEHUB — DIÁLOGO DE CHANGELOG / CHANGELOG DIALOG (ES · EN)
// ------------------------------------------------------------
// Lee /changelog.json (la entrada más reciente = último update) y
// muestra un diálogo una sola vez por versión. El idioma sigue a
// ch_lang (applyLang) y se actualiza si el usuario cambia idioma.
// Cerrar: botón, tecla Esc o toque fuera de la tarjeta.
//
// Uso manual: window.chOpenChangelog()  (ignora "ya visto")
// ============================================================

(function () {
  'use strict';

  const SEEN_KEY = 'ch_seen_changelog';
  const SOURCE = '/changelog.json';
  const SHOW_DELAY_MS = 1400;
  const STYLE_ID = 'ch-changelog-style';

  let latest = null;
  let overlay = null;
  let lastFocus = null;

  function currentLang() {
    try {
      const stored = localStorage.getItem('ch_lang') || localStorage.getItem('cfg_lang');
      if (stored === 'en' || stored === 'es') return stored;
    } catch (e) { /* almacenamiento bloqueado */ }
    const html = String(document.documentElement.lang || '').toLowerCase();
    if (html.indexOf('en') === 0) return 'en';
    const browser = String(navigator.language || 'es').toLowerCase();
    return browser.indexOf('en') === 0 ? 'en' : 'es';
  }

  function typeLabel(type, en) {
    const value = String(type || '').toLowerCase();
    if (value === 'fix') return en ? 'Fix' : 'Corrección';
    if (value === 'chore' || value === 'perf' || value === 'refactor') {
      return en ? 'Maintenance' : 'Mantenimiento';
    }
    return en ? 'New' : 'Novedades';
  }

  function localized(entry) {
    const en = currentLang() === 'en';
    const fallbackChanges = Array.isArray(entry.changes) ? entry.changes : [];
    const changes = en && Array.isArray(entry.changes_en) && entry.changes_en.length
      ? entry.changes_en
      : fallbackChanges;
    return {
      title: (en && entry.title_en) || entry.title || (en ? 'CodeHub update' : 'Actualización de CodeHub'),
      changes: changes,
      badge: en ? "What's new" : 'Novedades',
      close: en ? 'Close' : 'Cerrar',
      closeAria: en ? 'Close changelog' : 'Cerrar el registro de cambios',
      version: String(entry.version || ''),
      date: String(entry.date || ''),
      type: typeLabel(entry.type, en),
      mainLabel: en ? 'CodeHub update' : 'Actualización de CodeHub'
    };
  }

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function ensureStyles() {
    if (document.getElementById(STYLE_ID)) return;
    const style = document.createElement('style');
    style.id = STYLE_ID;
    style.textContent = `
      .ch-clg-overlay{position:fixed;inset:0;z-index:99997;display:flex;align-items:center;justify-content:center;
        padding:1rem;padding-bottom:calc(1rem + env(safe-area-inset-bottom,0px));
        background:rgba(4,6,16,.72);-webkit-backdrop-filter:blur(8px);backdrop-filter:blur(8px);
        opacity:0;transition:opacity .18s ease;font-family:inherit}
      .ch-clg-overlay.is-open{opacity:1}
      .ch-clg-card{position:relative;width:100%;max-width:420px;max-height:82vh;overflow:auto;overscroll-behavior:contain;
        background:var(--card,#141428);color:var(--text,#e8e8f3);
        border:1px solid var(--border,rgba(124,77,255,.35));border-radius:18px;
        box-shadow:0 24px 64px rgba(0,0,0,.55);padding:1.25rem 1.25rem 1rem;
        transform:translateY(10px) scale(.98);transition:transform .18s ease}
      .ch-clg-overlay.is-open .ch-clg-card{transform:none}
      .ch-clg-top{display:flex;align-items:center;gap:.5rem;flex-wrap:wrap;margin-bottom:.6rem}
      .ch-clg-badge{display:inline-flex;align-items:center;gap:.3rem;font-size:.66rem;font-weight:800;
        letter-spacing:.05em;text-transform:uppercase;padding:.28rem .55rem;border-radius:999px;
        color:#c9b8ff;background:rgba(124,77,255,.16);border:1px solid rgba(124,77,255,.35)}
      .ch-clg-version{font-size:.7rem;font-weight:700;color:var(--muted,#9aa0b4);font-family:var(--mono,monospace)}
      .ch-clg-x{margin-left:auto;width:32px;height:32px;flex:none;display:inline-flex;align-items:center;justify-content:center;
        border-radius:10px;border:1px solid var(--border,rgba(255,255,255,.12));background:transparent;
        color:inherit;font-size:1rem;line-height:1;cursor:pointer}
      .ch-clg-x:hover,.ch-clg-x:focus-visible{background:rgba(124,77,255,.16);outline:none}
      .ch-clg-title{margin:.1rem 0 .35rem;font-size:1.05rem;line-height:1.3;font-weight:800}
      .ch-clg-meta{font-size:.7rem;color:var(--muted,#9aa0b4);margin-bottom:.85rem;font-family:var(--mono,monospace)}
      .ch-clg-list{list-style:none;margin:0;padding:0;display:flex;flex-direction:column;gap:.45rem}
      .ch-clg-list li{position:relative;padding-left:1.05rem;font-size:.82rem;line-height:1.45;color:var(--text-soft,var(--text,#cfd2e6));
        overflow-wrap:anywhere}
      .ch-clg-list li::before{content:"";position:absolute;left:.12rem;top:.52em;width:6px;height:6px;border-radius:50%;
        background:linear-gradient(135deg,#7c4dff,#22c55e)}
      .ch-clg-close{margin-top:1rem;width:100%;min-height:42px;border-radius:12px;border:0;cursor:pointer;
        font-weight:700;font-size:.82rem;color:#fff;background:linear-gradient(135deg,#7c4dff,#3b6dff)}
      .ch-clg-close:focus-visible{outline:2px solid #9d7bff;outline-offset:2px}
      @media (max-width:380px){.ch-clg-card{padding:1rem .9rem .85rem;border-radius:16px}.ch-clg-title{font-size:1rem}
        .ch-clg-list li{font-size:.78rem}}
      @media (prefers-reduced-motion:reduce){.ch-clg-overlay,.ch-clg-card{transition:none}}
    `;
    document.head.appendChild(style);
  }

  function markSeen(version) {
    try { localStorage.setItem(SEEN_KEY, String(version)); } catch (e) { /* modo privado */ }
  }

  function removeOverlay() {
    if (!overlay) return;
    const node = overlay;
    overlay = null;
    node.classList.remove('is-open');
    setTimeout(function () { if (node.parentNode) node.parentNode.removeChild(node); }, 180);
    document.removeEventListener('keydown', onKeyDown);
    if (lastFocus && typeof lastFocus.focus === 'function') {
      try { lastFocus.focus(); } catch (e) { /* sin foco */ }
    }
    lastFocus = null;
  }

  function closeDialog() {
    if (overlay && latest) markSeen(latest.version);
    removeOverlay();
  }

  function onKeyDown(event) {
    if (event.key === 'Escape') {
      event.preventDefault();
      closeDialog();
      return;
    }
    if (event.key !== 'Tab' || !overlay) return;
    const focusables = overlay.querySelectorAll('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])');
    if (!focusables.length) return;
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  function render(entry) {
    if (!overlay) return;
    const copy = localized(entry);
    const card = overlay.querySelector('.ch-clg-card');
    if (!card) return;
    card.querySelector('.ch-clg-badge').textContent = '✨ ' + copy.badge;
    card.querySelector('.ch-clg-version').textContent = copy.version;
    card.querySelector('.ch-clg-title').textContent = copy.title;
    card.querySelector('.ch-clg-meta').textContent = [copy.date, copy.type].filter(Boolean).join(' · ');
    card.querySelector('.ch-clg-close').textContent = copy.close;
    const closeX = card.querySelector('.ch-clg-x');
    closeX.setAttribute('aria-label', copy.closeAria);
    const list = card.querySelector('.ch-clg-list');
    list.innerHTML = copy.changes.map(function (line) {
      return '<li>' + escapeHtml(line) + '</li>';
    }).join('');
    const dialog = card;
    dialog.setAttribute('aria-label', copy.mainLabel + (copy.version ? ' ' + copy.version : ''));
  }

  function show(entry) {
    if (!entry || overlay) return;
    ensureStyles();
    const copy = localized(entry);
    if (!copy.changes.length) return;
    lastFocus = document.activeElement;
    overlay = document.createElement('div');
    overlay.className = 'ch-clg-overlay';
    overlay.setAttribute('data-changelog-overlay', '');
    overlay.innerHTML = `
      <div class="ch-clg-card" role="dialog" aria-modal="true" aria-labelledby="ch-clg-title">
        <div class="ch-clg-top">
          <span class="ch-clg-badge"></span>
          <span class="ch-clg-version"></span>
          <button type="button" class="ch-clg-x" aria-label="">✕</button>
        </div>
        <h2 class="ch-clg-title" id="ch-clg-title"></h2>
        <div class="ch-clg-meta"></div>
        <ul class="ch-clg-list"></ul>
        <button type="button" class="ch-clg-close"></button>
      </div>`;
    overlay.addEventListener('click', function (event) {
      if (event.target === overlay || event.target.closest('[data-changelog-close]')) closeDialog();
    });
    overlay.querySelector('.ch-clg-x').setAttribute('data-changelog-close', '');
    overlay.querySelector('.ch-clg-close').setAttribute('data-changelog-close', '');
    document.body.appendChild(overlay);
    render(entry);
    document.addEventListener('keydown', onKeyDown);
    requestAnimationFrame(function () {
      overlay.classList.add('is-open');
      const button = overlay.querySelector('.ch-clg-close');
      if (button) button.focus();
    });
  }

  function load() {
    fetch(SOURCE, { cache: 'no-store' })
      .then(function (response) { return response.ok ? response.json() : null; })
      .then(function (list) {
        if (!Array.isArray(list) || !list.length) return;
        latest = list[0];
        let seen = null;
        try { seen = localStorage.getItem(SEEN_KEY); } catch (e) { /* modo privado */ }
        if (seen === String(latest.version)) return;
        setTimeout(function () {
          if (!overlay && latest) show(latest);
        }, SHOW_DELAY_MS);
      })
      .catch(function () { /* sin changelog: no molestar */ });
  }

  document.addEventListener('ch:langchange', function () {
    if (overlay && latest) render(latest);
  });

  window.chOpenChangelog = function () {
    if (latest) show(latest);
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', load, { once: true });
  } else {
    load();
  }
})();
