/* ════════════════════════════════════════════════════════════════════
   security-scan.js — Auditoría de seguridad (Centro de Operaciones)
   CodeHub by Wilson.E
   - Llama a /api/security/scan?url=... (backend Express) que audita
     las cabeceras del sitio por el lado del servidor (HTTPS, HSTS, CSP,
     clickjacking, cookies) con guards anti-SSRF y timeout.
   - UI premium: estado de carga fluido (spinner CSS acelerado por GPU),
     insignia con puntuación 0-100, nivel de riesgo con color dinámico
     (VERDE=EXCELENTE, AMARILLO=MODERADO, ROJO=PELIGROSO) y lista de
     vulnerabilidades renderizada por DOM API (sin innerHTML con datos
     del servidor → sin riesgos de inyección).
   ════════════════════════════════════════════════════════════════════ */
(() => {
  'use strict';
  if (!window._CH_BACKEND) return;

  const API_BASE = window._CH_BACKEND;
  const SCAN_TIMEOUT_MS = 20000;
  const SEV_LABELS = { CRITICO: 'CRÍTICO', ALTO: 'ALTO', MEDIO: 'MEDIO', BAJO: 'BAJO' };

  let els = null;

  function grab() {
    els = {
      input: document.getElementById('sec-url'),
      btn: document.getElementById('sec-scan-btn'),
      loading: document.getElementById('sec-loading'),
      error: document.getElementById('sec-error'),
      report: document.getElementById('sec-report'),
      badge: document.getElementById('sec-badge'),
      score: document.getElementById('sec-score'),
      risk: document.getElementById('sec-risk'),
      site: document.getElementById('sec-site'),
      vulns: document.getElementById('sec-vulns'),
      clean: document.getElementById('sec-clean'),
    };
    return !!(els.input && els.btn && els.score);
  }

  function validateUrl(raw) {
    const u = String(raw || '').trim();
    if (!u) return { error: 'Ingresa una URL para auditar.' };
    if (!/^(?:https?:\/\/)?(?:[\w-]+\.)+[\w-]{2,}(?::\d+)?(?:\/\S*)?/i.test(u)) {
      return { error: 'Esa no parece una URL válida.' };
    }
    return { url: /^https?:\/\//i.test(u) ? u : 'https://' + u };
  }

  function riskClass(risk) {
    return risk === 'EXCELENTE' ? 'sys-ok' : risk === 'MODERADO' ? 'sys-warn' : 'sys-danger';
  }

  function setLoading(on) {
    els.btn.classList.toggle('sys-loading-on', on);
    els.btn.disabled = on;
    els.loading.hidden = !on;
    if (on) {
      els.error.hidden = true;
      els.report.hidden = true;
    }
  }

  function showError(msg) {
    els.error.textContent = msg;
    els.error.hidden = false;
  }

  function render(d) {
    els.score.textContent = d.score;
    els.risk.textContent = d.risk;
    els.site.textContent = d.finalUrl || d.url || '';

    const cls = riskClass(d.risk);
    els.badge.classList.remove('sys-ok', 'sys-warn', 'sys-danger');
    els.report.classList.remove('sys-ok', 'sys-warn', 'sys-danger');
    els.badge.classList.add(cls);
    els.report.classList.add(cls);

    els.vulns.innerHTML = '';
    const list = Array.isArray(d.vulnerabilities_found) ? d.vulnerabilities_found : [];
    for (const v of list) {
      const li = document.createElement('li');
      li.className = 'sys-vuln';

      const chip = document.createElement('span');
      chip.className = 'sys-sev sys-sev-' + (SEV_LABELS[v.severity] ? v.severity : 'MEDIO');
      chip.textContent = SEV_LABELS[v.severity] || v.severity || 'MEDIO';

      const body = document.createElement('div');
      const title = document.createElement('b');
      title.textContent = v.title || 'Sin título';
      const detail = document.createElement('span');
      detail.textContent = (v.detail || '') + (v.hint ? ' — ' + v.hint : '');

      body.appendChild(title);
      body.appendChild(detail);
      li.appendChild(chip);
      li.appendChild(body);
      els.vulns.appendChild(li);
    }
    els.clean.hidden = list.length > 0;
    els.report.hidden = false;
  }

  async function scan() {
    const v = validateUrl(els.input.value);
    if (v.error) {
      showError(v.error);
      els.input.focus();
      return;
    }

    setLoading(true);
    const url = API_BASE + '/api/security/scan?url=' + encodeURIComponent(v.url);
    const ctrl = new AbortController();
    const to = setTimeout(() => ctrl.abort(), SCAN_TIMEOUT_MS);

    try {
      const r = await fetch(url, { signal: ctrl.signal, headers: { Accept: 'application/json' } });
      const d = await r.json().catch(() => null);
      if (ctrl.signal.aborted) throw new Error('Se agotó el tiempo de espera. Prueba con otra URL.');
      if (!d) throw new Error('Respuesta inválida del servidor.');
      if (d.ok === false) throw new Error(d.error || 'No se pudo completar la auditoría.');
      if (typeof d.score !== 'number') throw new Error('Respuesta inesperada del servidor.');
      render(d);
    } catch (e) {
      showError(e.message || 'Error inesperado.');
    } finally {
      clearTimeout(to);
      setLoading(false);
    }
  }

  function init() {
    if (!grab()) return;
    els.btn.addEventListener('click', scan);
    els.input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') scan();
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

  window.SecurityScan = { scan };
})();