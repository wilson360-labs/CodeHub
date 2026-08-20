/* ═══════════════════════════════════════════════════════════════
   native-nav.js — Navegación tipo app nativa
   CodeHub by Wilson.E
   
   Sistema de navegación SPA-like que intercepta clicks en links
   internos y usa View Transitions API para transiciones suaves
   como una app compilada. Sin recarga completa de página.
   ═══════════════════════════════════════════════════════════════ */
'use strict';

const NativeNav = (() => {
  const TRANSITION_NAME = 'native-page-transition';
  const SWIPE_THRESHOLD = 80;
  let _isTransitioning = false;
  let _history = [];
  let _currentPath = window.location.pathname;

  /* ── Interceptar clicks en links internos ── */
  function _interceptClicks() {
    document.addEventListener('click', e => {
      const link = e.target.closest('a[href]');
      if (!link) return;

      const href = link.getAttribute('href');
      if (!href) return;

      // Solo links internos (mismo dominio o relativos)
      if (href.startsWith('http') && !href.includes(window.location.hostname)) return;
      // Ignorar anchors, javascript:, mailto:, tel:
      if (href.startsWith('#') || href.startsWith('javascript:') || href.startsWith('mailto:') || href.startsWith('tel:')) return;
      // Ignorar links con target="_blank"
      if (link.target === '_blank') return;
      // Ignorar si tiene data-no-transition
      if (link.hasAttribute('data-no-transition')) return;

      e.preventDefault();
      _navigateTo(href);
    });
  }

  /* ── Navegar a una nueva página ── */
  async function _navigateTo(path) {
    if (_isTransitioning) return;
    if (path === _currentPath) return;

    _isTransitioning = true;

    // Push state
    window.history.pushState({ path }, '', path);
    _history.push(_currentPath);
    _currentPath = path;

    // Fetch el nuevo contenido
    try {
      const response = await fetch(path);
      if (!response.ok) throw new Error('Fetch failed');

      const html = await response.text();
      const parser = new DOMParser();
      const newDoc = parser.parseFromString(html, 'text/html');

      // Obtener el contenido principal
      const newContent = newDoc.querySelector('main') || newDoc.querySelector('body');
      const currentContent = document.querySelector('main') || document.querySelector('body');

      if (!newContent || !currentContent) {
        window.location.href = path;
        return;
      }

      // View Transitions API
      if (document.startViewTransition) {
        document.startViewTransition(() => {
          _updateDOM(newDoc, currentContent);
        }).finished.then(() => {
          _isTransitioning = false;
          _onPageReady();
        }).catch(() => {
          _isTransitioning = false;
        });
      } else {
        // Fallback sin transición
        _updateDOM(newDoc, currentContent);
        _isTransitioning = false;
        _onPageReady();
      }
    } catch (err) {
      // Fallback: recarga completa
      window.location.href = path;
    }
  }

  /* ── Actualizar DOM ── */
  function _updateDOM(newDoc, currentContent) {
    // Actualizar título
    document.title = newDoc.title || 'CodeHub';

    // Actualizar meta tags
    const newMeta = newDoc.querySelectorAll('meta[name="description"], meta[property^="og:"]');
    newMeta.forEach(meta => {
      const existing = document.querySelector(`meta[name="${meta.name}"], meta[property="${meta.getAttribute('property')}"]`);
      if (existing) {
        existing.setAttribute('content', meta.getAttribute('content'));
      }
    });

    // Actualizar contenido principal
    currentContent.innerHTML = newDoc.querySelector('main')?.innerHTML || newDoc.body.innerHTML;

    // Scroll al top
    window.scrollTo(0, 0);

    // Re-ejecutar scripts inline
    _reExecuteScripts();

    // Actualizar navegación activa
    _updateActiveNav();
  }

  /* ── Re-ejecutar scripts inline después de la transición ── */
  function _reExecuteScripts() {
    const scripts = document.querySelectorAll('script:not([src])');
    scripts.forEach(script => {
      const newScript = document.createElement('script');
      newScript.textContent = script.textContent;
      script.parentNode?.replaceChild(newScript, script);
    });
  }

  /* ── Actualizar navegación activa ── */
  function _updateActiveNav() {
    const path = window.location.pathname;
    document.querySelectorAll('#mobile-nav-bar a').forEach(a => {
      const href = a.getAttribute('href');
      a.classList.toggle('active', href === path || (path === '/' && href === '/'));
    });
  }

  /* ── Página lista post-transición ── */
  function _onPageReady() {
    // Disparar evento personalizado
    document.dispatchEvent(new CustomEvent('ch:pageshow', { detail: { path: _currentPath } }));

    // Re-inicializar componentes que dependen del DOM
    if (window.CodeHubTheme) {
      const saved = localStorage.getItem('theme') || 'dark';
      window.CodeHubTheme.apply(saved, false);
    }
  }

  /* ── Swipe back gesture ── */
  function _initSwipeBack() {
    let startX = 0;
    let startY = 0;
    let isDragging = false;

    document.addEventListener('touchstart', e => {
      if (e.touches.length !== 1) return;
      startX = e.touches[0].clientX;
      startY = e.touches[0].clientY;
      isDragging = startX < 30; // Solo desde el borde izquierdo
    }, { passive: true });

    document.addEventListener('touchmove', e => {
      if (!isDragging) return;
      const deltaX = e.touches[0].clientX - startX;
      const deltaY = Math.abs(e.touches[0].clientY - startY);

      // Si se mueve más horizontal que vertical, es swipe
      if (deltaY > deltaX) {
        isDragging = false;
        return;
      }
    }, { passive: true });

    document.addEventListener('touchend', e => {
      if (!isDragging) return;
      isDragging = false;

      const deltaX = e.changedTouches[0].clientX - startX;
      if (deltaX > SWIPE_THRESHOLD && _history.length > 0) {
        // Swipe back
        _goBack();
      }
    }, { passive: true });
  }

  /* ── Volver a la página anterior ── */
  function _goBack() {
    if (_history.length === 0) return;
    const prevPath = _history.pop();
    _currentPath = prevPath;
    window.history.back();
    // Recargar contenido
    _navigateTo(prevPath);
  }

  /* ── Handle popstate ── */
  function _handlePopState() {
    window.addEventListener('popstate', e => {
      const path = window.location.pathname;
      if (path !== _currentPath) {
        _currentPath = path;
        _navigateTo(path);
      }
    });
  }

  /* ── Init ── */
  function init() {
    // Solo activar en standalone o si el navegador soporta View Transitions
    const isStandalone = window.matchMedia('(display-mode: standalone)').matches;
    const hasViewTransitions = 'startViewTransition' in document;

    if (!isStandalone && !hasViewTransitions) return;

    _interceptClicks();
    _initSwipeBack();
    _handlePopState();
    _updateActiveNav();

    // Marcar como app nativa
    document.documentElement.classList.add('native-app');
  }

  return { init, navigateTo: _navigateTo, goBack: _goBack };
})();

// Auto-init
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => NativeNav.init());
} else {
  NativeNav.init();
}
