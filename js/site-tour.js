/* ═══════════════════════════════════════════════════════════════
   SITE TOUR — guía interactiva reutilizable de funciones ocultas.
   Uso:
     window.startTour('index', [
       { selector: '#cfg-btn', icon: 'fa-solid fa-gear', title: 'Configuración',
         text: 'Personaliza el sitio: animaciones, efectos y más.' },
       ...
     ], { helpBtn: { title: '¿Necesitas ayuda?', sub: 'Ver guía rápida' } });

   - Se muestra automáticamente una sola vez por `tourId` (localStorage).
   - Deja un botón flotante "¿Necesitas ayuda?" para volver a verla.
   - Debajo de 720px de ancho no dibuja línea guía: usa una hoja
     inferior fija y resalta el elemento señalado con un halo.
   ═══════════════════════════════════════════════════════════════ */
(function (window, document) {
  'use strict';

  function SiteTour(tourId, steps, opts) {
    this.tourId = tourId;
    this.steps = steps || [];
    this.opts = opts || {};
    this.index = 0;
    this.layer = null;
    this.card = null;
    this.connector = null;
    this.highlighted = null;
    this._onResize = this._place.bind(this);
    this._onKey = this._onKeyDown.bind(this);
    this._touchStartX = 0;
    this._touchStartY = 0;
    this._onTouchStart = this._handleTouchStart.bind(this);
    this._onTouchEnd = this._handleTouchEnd.bind(this);
  }

  SiteTour.prototype._storeKey = function () {
    return 'ch_tour_seen_' + this.tourId;
  };

  SiteTour.prototype.hasBeenSeen = function () {
    return !!localStorage.getItem(this._storeKey());
  };

  SiteTour.prototype.markSeen = function () {
    localStorage.setItem(this._storeKey(), '1');
  };

  SiteTour.prototype.start = function (force) {
    if (!this.steps.length) return;
    if (!force && this.hasBeenSeen()) return;
    this.index = 0;
    this._ensureLayer();
    document.addEventListener('keydown', this._onKey);
    this._renderStep();
  };

  SiteTour.prototype._ensureLayer = function () {
    if (this.layer) return;
    var layer = document.createElement('div');
    layer.id = 'st-layer-' + this.tourId;
    layer.className = 'st-layer-instance';
    document.body.appendChild(layer);
    this.layer = layer;
  };

  SiteTour.prototype._onKeyDown = function (e) {
    if (e.key === 'Escape') this.close();
    if (e.key === 'ArrowRight') this.next();
    if (e.key === 'ArrowLeft') this.prev();
  };

  SiteTour.prototype._clearHighlight = function () {
    if (this.highlighted) {
      this.highlighted.classList.remove('st-highlight');
      if (this.highlighted.dataset.stAddedRelative) {
        this.highlighted.classList.remove('st-highlight-relative');
        delete this.highlighted.dataset.stAddedRelative;
      }
      this.highlighted = null;
    }
  };

  SiteTour.prototype._renderStep = function () {
    var step = this.steps[this.index];
    if (!step) { this.close(true); return; }
    var target = document.querySelector(step.selector);
    if (!target) {
      if (step.optional) { this.next(); return; }
      this.close(true); return;
    }

    target.scrollIntoView({ behavior: 'smooth', block: 'center' });

    this._clearHighlight();
    target.classList.add('st-highlight');
    // Si el elemento es position:static, necesita position:relative para
    // que el z-index del resplandor funcione. Si ya es fixed/absolute/sticky
    // (como el botón "EXPERIMENTAL"), NO tocamos su position: hacerlo lo
    // saca de su sitio en pantalla (bug: "el botón salta hasta arriba").
    if (window.getComputedStyle(target).position === 'static') {
      target.classList.add('st-highlight-relative');
      target.dataset.stAddedRelative = '1';
    }
    this.highlighted = target;

    if (this.card) this.card.remove();
    if (this.connector) this.connector.remove();

    var card = document.createElement('div');
    card.className = 'st-card';
    var iconHtml = step.icon ? '<i class="' + step.icon + '"></i>' : (step.emoji ? '<span class="st-emoji">' + step.emoji + '</span>' : '');
    card.innerHTML =
      '<div class="st-card-title">' + iconHtml + '<span>' + step.title + '</span></div>' +
      '<div class="st-card-text">' + step.text + '</div>' +
      '<div class="st-card-foot">' +
        '<div class="st-dots"></div>' +
        '<div class="st-nav">' +
          '<button type="button" class="st-btn st-btn-skip" data-act="skip">Saltar</button>' +
          '<button type="button" class="st-btn st-btn-prev" data-act="prev"><i class="fa-solid fa-arrow-left"></i></button>' +
          '<button type="button" class="st-btn st-btn-next" data-act="next"></button>' +
        '</div>' +
      '</div>';
    document.body.appendChild(card);
    this.card = card;

    var dotsWrap = card.querySelector('.st-dots');
    this.steps.forEach(function (_, i) {
      var d = document.createElement('span');
      d.className = 'st-dot' + (i === this.index ? ' active' : '');
      dotsWrap.appendChild(d);
    }, this);

    var isLast = this.index === this.steps.length - 1;
    var prevBtn = card.querySelector('[data-act="prev"]');
    var nextBtn = card.querySelector('[data-act="next"]');
    prevBtn.disabled = this.index === 0;
    prevBtn.setAttribute('aria-label', 'Anterior');
    nextBtn.innerHTML = isLast
      ? 'Finalizar <i class="fa-solid fa-check"></i>'
      : 'Siguiente <i class="fa-solid fa-arrow-right"></i>';

    var self = this;
    card.querySelector('[data-act="prev"]').addEventListener('click', function () { self.prev(); });
    card.querySelector('[data-act="next"]').addEventListener('click', function () { self.next(); });
    card.querySelector('[data-act="skip"]').addEventListener('click', function () { self.close(true); });

    if (window.innerWidth > 720) {
      var connector = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      connector.setAttribute('class', 'st-connector');
      document.body.appendChild(connector);
      this.connector = connector;
    } else {
      this.connector = null;
    }

    // Espera a que el scroll suave termine de ubicar el elemento
    // antes de calcular la posición de la tarjeta/línea guía.
    setTimeout(function () {
      self._place();
      card.classList.add('show');
    }, 380);

    window.addEventListener('resize', this._onResize);
  };

  SiteTour.prototype._place = function () {
    if (!this.card || !this.highlighted) return;
    var r = this.highlighted.getBoundingClientRect();
    var card = this.card;
    var cw = card.offsetWidth || 280;
    var ch = card.offsetHeight || 140;
    var vw = window.innerWidth, vh = window.innerHeight;

    if (vw <= 720) {
      // Hoja inferior/superior: el ancho y el resto lo resuelve el CSS
      // (@media), pero elegimos arriba o abajo según dónde esté el
      // elemento resaltado, para que la tarjeta NUNCA cubra la
      // función/logo/texto que está describiendo.
      var targetMidY = r.top + r.height / 2;
      if (targetMidY > vh / 2) {
        // El elemento está en la mitad inferior → tarjeta arriba.
        card.classList.add('st-card-top');
      } else {
        // El elemento está en la mitad superior (o el elemento es muy
        // grande y ocupa casi toda la pantalla) → tarjeta abajo.
        card.classList.remove('st-card-top');
      }
      return;
    }

    // Elegir lado con más espacio: derecha, izquierda, abajo o arriba.
    var spaceRight = vw - r.right, spaceLeft = r.left, spaceBelow = vh - r.bottom, spaceAbove = r.top;
    var top, left, anchor;

    if (spaceRight >= cw + 40) {
      left = r.right + 32; top = Math.min(Math.max(r.top - 10, 12), vh - ch - 12); anchor = 'right';
    } else if (spaceLeft >= cw + 40) {
      left = r.left - cw - 32; top = Math.min(Math.max(r.top - 10, 12), vh - ch - 12); anchor = 'left';
    } else if (spaceBelow >= ch + 40) {
      top = r.bottom + 28; left = Math.min(Math.max(r.left + r.width / 2 - cw / 2, 12), vw - cw - 12); anchor = 'bottom';
    } else {
      top = Math.max(r.top - ch - 28, 12); left = Math.min(Math.max(r.left + r.width / 2 - cw / 2, 12), vw - cw - 12); anchor = 'top';
    }

    card.style.top = top + 'px';
    card.style.left = left + 'px';

    if (this.connector) {
      var tx = r.left + r.width / 2, ty = r.top + r.height / 2;
      var cx, cy;
      if (anchor === 'right') { cx = left; cy = top + Math.min(30, ch / 2); }
      else if (anchor === 'left') { cx = left + cw; cy = top + Math.min(30, ch / 2); }
      else if (anchor === 'bottom') { cx = left + cw / 2; cy = top; }
      else { cx = left + cw / 2; cy = top + ch; }

      this.connector.setAttribute('width', vw);
      this.connector.setAttribute('height', vh);
      this.connector.style.left = '0px';
      this.connector.style.top = '0px';
      var midX = (tx + cx) / 2;
      this.connector.innerHTML =
        '<path d="M' + tx + ',' + ty + ' Q' + midX + ',' + ty + ' ' + cx + ',' + cy + '" ' +
          'fill="none" stroke="var(--st-line)" stroke-width="2" stroke-dasharray="5 5"/>' +
        '<circle cx="' + tx + '" cy="' + ty + '" r="4"/>' +
        '<circle cx="' + cx + '" cy="' + cy + '" r="4"/>';
    }
  };

  SiteTour.prototype.next = function () {
    if (this.index >= this.steps.length - 1) { this.close(true); return; }
    this.index++;
    this._renderStep();
  };

  SiteTour.prototype.prev = function () {
    if (this.index <= 0) return;
    this.index--;
    this._renderStep();
  };

  SiteTour.prototype.close = function (markSeen) {
    this._clearHighlight();
    if (this.card) { this.card.remove(); this.card = null; }
    if (this.connector) { this.connector.remove(); this.connector = null; }
    if (this.layer) { this.layer.remove(); this.layer = null; }
    window.removeEventListener('resize', this._onResize);
    document.removeEventListener('keydown', this._onKey);
    if (markSeen) this.markSeen();
  };

  // ── Registro de tours activos + botón flotante "¿Necesitas ayuda?" ──
  var registry = {};

  function ensureHelpButton(tourId, opts) {
    var btnId = 'st-help-' + tourId;
    if (document.getElementById(btnId)) return;
    var cfg = (opts && opts.helpBtn) || {};
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.id = btnId;
    btn.className = 'st-help-btn';
    btn.innerHTML =
      '<span class="st-help-ico"><i class="fa-solid fa-circle-question"></i></span>' +
      '<span class="st-help-copy">' +
        '<span class="st-help-title">' + (cfg.title || '¿Necesitas ayuda?') + '</span>' +
        '<span class="st-help-sub">' + (cfg.sub || 'Ver guía rápida') + '</span>' +
      '</span>';
    document.body.appendChild(btn);
    requestAnimationFrame(function () { btn.classList.add('show'); });
    btn.addEventListener('click', function () {
      var tour = registry[tourId];
      if (tour) tour.start(true);
    });
  }

  window.startTour = function (tourId, steps, opts) {
    var tour = new SiteTour(tourId, steps, opts);
    registry[tourId] = tour;
    ensureHelpButton(tourId, opts);
    var delay = (opts && opts.delay) || 1200;
    // Si waitForInteraction está activo, el tour NO se auto-abre: espera la
    // primera interacción real del usuario (scroll, mousemove, touch o tecla)
    // para no tapar el header/logo ni interrumpir la entrada a la página.
    // Solo se dispara una vez; si el tour ya fue visto, start() lo ignora.
    if (opts && opts.waitForInteraction) {
      var fired = false;
      var launch = function () {
        if (fired) return;
        fired = true;
        ['scroll', 'mousemove', 'touchstart', 'keydown'].forEach(function (evt) {
          document.removeEventListener(evt, launch, { capture: true });
        });
        setTimeout(function () { tour.start(false); }, 260);
      };
      ['scroll', 'mousemove', 'touchstart', 'keydown'].forEach(function (evt) {
        document.addEventListener(evt, launch, { capture: true, once: true });
      });
    } else {
      // Pequeño retraso para no competir con animaciones de carga inicial.
      setTimeout(function () { tour.start(false); }, delay);
    }
    return tour;
  };

  window.replayTour = function (tourId) {
    var tour = registry[tourId];
    if (tour) tour.start(true);
  };
})(window, document);
