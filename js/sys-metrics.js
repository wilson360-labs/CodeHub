/* ════════════════════════════════════════════════════════════════════
   sys-metrics.js — Métricas en vivo del servidor (Centro de Operaciones)
   CodeHub by Wilson.E
   - Polling de /api/metrics cada 5 s con fetch() y AbortController.
   - BATERÍA (WebView): las peticiones se pausan con document.visibilityState
     cuando el usuario minimiza la app o cambia de pestaña, y solo gira
     cuando la sección es visible (IntersectionObserver). Cero CPU en
     segundo plano.
   - 60fps: las barras se animan por transform:scaleX en la GPU (nunca
     se toca style.width), con will-change declarado en el CSS.
   ════════════════════════════════════════════════════════════════════ */
(() => {
  'use strict';
  if (!window._CH_BACKEND) return;

  const API = window._CH_BACKEND + '/api/metrics';
  const EVERY_MS = 5000;
  const FETCH_TIMEOUT_MS = 4500;

  const els = {};
  let timer = null;
  let inFlight = false;
  let pageVisible = document.visibilityState === 'visible';
  let inView = false;

  function grab() {
    els.status = document.getElementById('metric-status');
    els.latency = document.getElementById('metric-latency');
    els.cpu = document.getElementById('metric-cpu');
    els.ram = document.getElementById('metric-ram');
    els.fillCpu = document.getElementById('fill-cpu');
    els.fillRam = document.getElementById('fill-ram');
    return !!(els.status && els.fillCpu && els.fillRam);
  }

  function setBar(el, pct) {
    const p = Math.max(0, Math.min(100, pct));
    el.style.transform = 'scaleX(' + (p / 100) + ')';
    el.setAttribute('aria-valuenow', String(Math.round(p)));
    el.classList.toggle('sys-fill-warn', p > 70 && p <= 90);
    el.classList.toggle('sys-fill-crit', p > 90);
  }

  function paint(d) {
    const online = d.status === 'online';
    els.status.textContent = online ? 'ONLINE' : 'OFFLINE';
    els.status.classList.toggle('sys-pill-off', !online);
    els.latency.textContent = Math.max(1, Math.round(d.latency || 0)) + ' ms';
    els.cpu.textContent = d.cpu + '%';
    els.ram.textContent = d.ram + '%';
    setBar(els.fillCpu, d.cpu);
    setBar(els.fillRam, d.ram);
  }

  async function poll() {
    if (!pageVisible || !inView || inFlight) return schedule();
    inFlight = true;
    const ctrl = new AbortController();
    const to = setTimeout(() => ctrl.abort(), FETCH_TIMEOUT_MS);
    try {
      const t0 = performance.now();
      const r = await fetch(API, { signal: ctrl.signal, headers: { Accept: 'application/json' }, cache: 'no-store' });
      if (!r.ok) throw new Error('http ' + r.status);
      const d = await r.json();
      if (d && d.ok && els.status) {
        if (!d.latency) d.latency = Math.ceil(performance.now() - t0);
        paint(d);
      }
    } catch (e) {
      // Solo marcar OFFLINE si seguimos visibles, para no parpadear
      // al volver de segundo plano con una petición cancelada.
      if (els.status && document.visibilityState === 'visible') {
        els.status.textContent = 'OFFLINE';
        els.status.classList.add('sys-pill-off');
      }
    } finally {
      clearTimeout(to);
      inFlight = false;
    }
    schedule();
  }

  function schedule() {
    if (timer) { clearTimeout(timer); timer = null; }
    if (pageVisible && inView) timer = setTimeout(poll, EVERY_MS);
  }

  function onVisibility() {
    pageVisible = document.visibilityState === 'visible' && !document.hidden;
    if (pageVisible) { schedule(); poll(); }
    else if (timer) { clearTimeout(timer); timer = null; }
  }

  function onView(entries) {
    inView = entries.some((e) => e.isIntersecting);
    if (inView) { schedule(); poll(); }
    else if (timer) { clearTimeout(timer); timer = null; }
  }

  function init() {
    if (!grab()) return;
    if (window.IntersectionObserver) {
      const sec = document.getElementById('security-section');
      if (sec) new IntersectionObserver(onView, { threshold: 0.1 }).observe(sec);
      else inView = true;
    } else {
      inView = true;
    }
    schedule();
    poll();
    document.addEventListener('visibilitychange', onVisibility, { passive: true });
    window.addEventListener('pagehide', onVisibility, { passive: true });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

  window.SysMetrics = {
    refresh: poll,
    pause: () => { pageVisible = false; schedule(); },
    resume: () => { pageVisible = true; schedule(); poll(); },
  };
})();