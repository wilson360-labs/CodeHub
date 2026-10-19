/* ═══════════════════════════════════════════════════════════════
   NOTICIAS — widget extraído de index.html
   ═══════════════════════════════════════════════════════════════
   Antes vivía como ~160 líneas de script inline dentro del <script>
   gigante de index.html, mezcladas con el chat Wil.E/EMI y con los
   widgets de clima, sismos y seguridad. Está encapsulado en una IIFE
   para que no exponga nada global excepto toggleNewsCollapse, que
   necesita acceso desde el onclick del botón que genera.

   fetchWithTimeout se reimplementa aquí localmente (con prefijo) en
   vez de importarse: en index.html esa función es compartida con el
   widget de roadmap, y duplicarla en el mismo ámbito habría producido
   un conflicto de nombres.
   ═══════════════════════════════════════════════════════════════ */
(function () {
  'use strict';

  // Fuente por defecto: BBC Mundo. Primero se intenta vía backend propio
  // (más fiable, sin depender de un proxy CORS público de terceros); si el
  // backend no responde, se cae al proxy público como respaldo.
  var NEWS_RSS_URL = 'https://feeds.bbci.co.uk/mundo/rss.xml';
  var NEWS_PROXY = 'https://api.allorigins.win/raw?url=';
  var NEWS_BACKEND_URL = (typeof _CH_BACKEND !== 'undefined'
    ? _CH_BACKEND
    : 'https://codehub-98s6.onrender.com') + '/api/news';

  function fetchWithTimeoutNT(url, opts, ms) {
    var controller = new AbortController();
    var t = setTimeout(function () { controller.abort(); }, ms || 8000);
    return fetch(url, Object.assign({}, opts, { signal: controller.signal }))
      .finally(function () { clearTimeout(t); });
  }

  // ── PAÍS DEL USUARIO (IP) ────────────────────────────────────
  // Mismo patrón que el widget de clima: intenta ipapi.co y cae a
  // ip-api.com. Se guarda en sesión para no golpear estos servicios
  // en cada recarga.
  function detectNewsCountry() {
    var cached = sessionStorage.getItem('ch_news_country');
    if (cached) return Promise.resolve(cached);

    return fetchWithTimeoutNT('https://ipapi.co/json/', {}, 4000)
      .then(function (r) { return r.json(); })
      .then(function (g1) {
        if (g1.country_code && !g1.error) {
          sessionStorage.setItem('ch_news_country', g1.country_code);
          return g1.country_code;
        }
        throw new Error('sin país en ipapi');
      })
      .catch(function () {
        return fetchWithTimeoutNT('https://ip-api.com/json/?fields=countryCode,status', {}, 4000)
          .then(function (r) { return r.json(); })
          .then(function (g2) {
            if (g2.status === 'success' && g2.countryCode) {
              sessionStorage.setItem('ch_news_country', g2.countryCode);
              return g2.countryCode;
            }
            return null;
          })
          .catch(function () { return null; });
      });
  }

  function relativeTime(pub) {
    if (!pub) return '';
    var diffMin = Math.floor((Date.now() - new Date(pub).getTime()) / 60000);
    if (diffMin < 1) return 'justo ahora';
    if (diffMin < 60) return 'hace ' + diffMin + ' min';
    var diffH = Math.floor(diffMin / 60);
    if (diffH < 24) return 'hace ' + diffH + ' h';
    return 'hace ' + Math.floor(diffH / 24) + ' d';
  }

  // Sanitiza texto antes de inyectarlo en las tarjetas: las noticias
  // no deben renderizar HTML ni código fuente crudo.
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  // ── BADGE DE FUENTE (dinámico) ───────────────────────────────
  // Refleja la fuente real que respondió (Google News del país
  // detectado, o BBC Mundo si Google News no estuvo disponible).
  function updateNewsSourceBadge(source, country) {
    var link = document.getElementById('news-source-link');
    var text = document.getElementById('news-source-text');
    if (!link || !text) return;
    if (!source) { link.style.display = 'none'; return; }
    var isGoogle = source.indexOf('Google News') === 0;
    text.textContent = isGoogle
      ? 'Fuente: Google News' + (country ? ' · ' + country : '')
      : 'Fuente: ' + source;
    link.href = isGoogle
      ? 'https://news.google.com/' + (country
          ? '?hl=es-419&gl=' + encodeURIComponent(country) +
            '&ceid=' + encodeURIComponent(country) + ':es-419'
          : '')
      : NEWS_RSS_URL;
    link.style.display = 'inline-flex';
  }

  function renderRealNews(items, fetchedAt, sourceLabel) {
    var out = document.getElementById('news-real-out');
    var freshLabel = document.getElementById('news-fresh-label');
    if (!out) return;
    if (freshLabel) {
      freshLabel.textContent = fetchedAt ? 'Actualizado ' + relativeTime(fetchedAt) : '';
    }

    var cardsHtml = items.map(function (n) {
      return '' +
        '<a href="' + esc(n.url) + '" target="_blank" rel="noopener noreferrer" class="blogger-card">' +
          '<div class="blogger-card-thumb">' +
            (n.image
              ? '<img src="' + esc(n.image) + '" alt="' + esc(n.title) + '" loading="lazy" ' +
                'onerror="this.remove();if(this.parentElement)this.parentElement.innerHTML=' +
                "'<i class=&quot;fas fa-earth-americas&quot;></i>'" + '">'
              : '<i class="fas fa-earth-americas"></i>') +
          '</div>' +
          '<div class="blogger-card-body">' +
            '<div class="blogger-card-label">// ' + esc(n.source || sourceLabel || 'Noticias') + '</div>' +
            '<div class="blogger-card-title">' + esc(n.title) + '</div>' +
            '<div class="blogger-card-desc">' + esc(n.desc) + '</div>' +
            '<div class="blogger-card-meta">' +
              '<i class="fas fa-calendar-days"></i>' + esc(n.date) +
              (n.pub
                ? '<span class="blogger-card-relative"><i class="fas fa-clock"></i>' +
                  relativeTime(n.pub) + '</span>'
                : '') +
            '</div>' +
          '</div>' +
        '</a>';
    }).join('');

    // El grid arranca colapsado: la clase solo tiene efecto visual en
    // móvil (ver .news-toggle-wrap dentro del @media en el CSS).
    var toggleBtn = items.length > 3
      ? '<div class="news-toggle-wrap">' +
          '<button type="button" class="news-toggle-btn" data-nt-toggle>' +
            '<i class="fas fa-chevron-down"></i> Ver más noticias' +
          '</button>' +
        '</div>'
      : '';

    out.innerHTML = '<div class="blogger-grid is-collapsed">' + cardsHtml + '</div>' + toggleBtn;

    // Se enlaza por data-attribute en vez de onclick inline: el botón se
    // genera en tiempo de ejecución y un onclick= expondría la función
    // en el ámbito global.
    var btn = out.querySelector('[data-nt-toggle]');
    if (btn) btn.addEventListener('click', function () { toggleNewsCollapse(btn); });
  }

  function toggleNewsCollapse(btn) {
    var grid = document.querySelector('#news-real-out .blogger-grid');
    if (!grid) return;
    var willExpand = grid.classList.contains('is-collapsed');
    grid.classList.toggle('is-collapsed', !willExpand);
    btn.classList.toggle('expanded', willExpand);
    btn.innerHTML = willExpand
      ? '<i class="fas fa-chevron-up"></i> Ver menos'
      : '<i class="fas fa-chevron-down"></i> Ver más noticias';
    if (!willExpand) grid.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  function loadRealNews() {
    var out = document.getElementById('news-real-out');
    if (!out) return Promise.resolve();

    // 1) Intento primario: backend propio (ya parseado y geolocalizado)
    return detectNewsCountry()
      .then(function (country) {
        var url = country
          ? NEWS_BACKEND_URL + '?country=' + encodeURIComponent(country)
          : NEWS_BACKEND_URL;
        return fetchWithTimeoutNT(url, {}, 8000)
          .then(function (res) {
            if (!res.ok) throw new Error('backend no OK');
            return res.json();
          })
          .then(function (data) {
            if (!data.items || !data.items.length) throw new Error('backend sin items');
            updateNewsSourceBadge(data.source, data.country);
            renderRealNews(data.items, data.fetchedAt, data.source);
          });
      })
      // 2) Respaldo: proxy CORS público + parseo en el navegador
      .catch(function () {
        return fetchWithTimeoutNT(NEWS_PROXY + encodeURIComponent(NEWS_RSS_URL), {}, 8000)
          .then(function (res) {
            if (!res.ok) throw new Error('fetch fallido');
            return res.text();
          })
          .then(function (xmlText) {
            var xml = new DOMParser().parseFromString(xmlText, 'text/xml');
            var items = Array.prototype.slice
              .call(xml.querySelectorAll('item'), 0, 9)
              .map(function (item) {
                var title = (item.querySelector('title') || {}).textContent || 'Sin título';
                var url = (item.querySelector('link') || {}).textContent || NEWS_RSS_URL;
                var pub = (item.querySelector('pubDate') || {}).textContent;
                var date = pub
                  ? new Date(pub).toLocaleDateString('es-GT',
                      { day: 'numeric', month: 'short', year: 'numeric' })
                  : '';
                var desc = ((item.querySelector('description') || {}).textContent || '')
                  .replace(/<[^>]+>/g, '').slice(0, 130);
                // Imagen real: el RSS de BBC trae media:thumbnail /
                // media:content / enclosure.
                var image = (item.getElementsByTagName('media:thumbnail')[0] || {}).getAttribute?.call(
                              item.getElementsByTagName('media:thumbnail')[0], 'url')
                  || (item.getElementsByTagName('media:content')[0] || {}).getAttribute?.call(
                              item.getElementsByTagName('media:content')[0], 'url')
                  || ((item.querySelector('enclosure[type^="image"]') || {}).getAttribute?.call(
                              item.querySelector('enclosure[type^="image"]'), 'url'))
                  || '';
                return { title: title, url: url, date: date, desc: desc, image: image, pub: pub };
              });

            if (!items.length) throw new Error('sin items');
            updateNewsSourceBadge('BBC Mundo', null);
            renderRealNews(items, new Date().toISOString(), 'BBC Mundo');
          });
      })
      .catch(function () {
        out.innerHTML = '<div class="blogger-error">' +
          '<i class="fas fa-triangle-exclamation"></i>' +
          'No se pudieron cargar las noticias en este momento.<br>' +
          '<small>Intenta recargar la página.</small></div>';
      });
  }

  // ── Botón "Actualizar" ───────────────────────────────────────
  // Recarga los titulares sin recargar la página. Se deshabilita mientras
  // la petición está en vuelo para que no se puedan lanzar varias en
  // paralelo (los proveedores externos limitan peticiones y un doble clic
  // provocaba un error 429 visible para el usuario).
  function wireRefreshButton() {
    var btn = document.getElementById('news-refresh-btn');
    if (!btn || btn.dataset.wired === '1') return;
    btn.dataset.wired = '1';
    btn.addEventListener('click', function () {
      btn.disabled = true;
      btn.innerHTML = '<i class="fas fa-rotate-right"></i> Cargando…';
      // Se invalida el país cacheado para forzar una detección fresca.
      try { sessionStorage.removeItem('ch_news_country'); } catch (e) {}
      loadRealNews().then(function () {
        btn.disabled = false;
        btn.innerHTML = '<i class="fas fa-rotate-right"></i> Actualizar';
      });
    });
  }

  function boot() {
    wireRefreshButton();
    loadRealNews();
  }

  if (document.readyState === 'complete') boot();
  else window.addEventListener('load', boot);

  // Marca el contenedor como no ocupado cuando el render termina.
  // Se hace observando el final de loadRealNews mediante el propio
  // innerHTML: si hay tarjetas o mensaje de error, ya no está cargando.
  var outEl = document.getElementById('news-real-out');
  if (outEl && 'MutationObserver' in window) {
    var mo = new MutationObserver(function () {
      if (outEl.querySelector('.blogger-card:not(.skeleton-card), .blogger-error')) {
        outEl.setAttribute('aria-busy', 'false');
        mo.disconnect();
      }
    });
    mo.observe(outEl, { childList: true, subtree: true });
  }
})();
