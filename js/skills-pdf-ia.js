// ═══════════════════════════════════════════════════════════════
// ARCHIVO: js/skills-pdf-ia.js
// Skill: PDF IA — Chat con tu documento (extracción 100% local)
// Agrégalo con <script src="../js/skills-pdf-ia.js"></script> en
// tools.html justo antes de </body> (después de skills-image-gen.js).
// Renderiza los presets de la skill en #skill-pdf-ia-root.
// ═══════════════════════════════════════════════════════════════

(function () {
  'use strict';

  const SKILL_ID = 'pdf-ia';

  function apiUrl() {
    const base = (typeof BACKEND !== 'undefined' && BACKEND)
      ? BACKEND
      : 'https://codehub-98s6.onrender.com';
    return base + '/api/skills/' + SKILL_ID;
  }

  function esc(s) {
    return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  async function init() {
    try {
      const res = await fetch(apiUrl());
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const skill = await res.json();
      render(skill);
    } catch (e) {
      console.warn('[SkillPdfIa] No se pudo cargar la skill:', e.message);
      const root = document.getElementById('skill-pdf-ia-root');
      if (root) root.innerHTML = '<div style="font-size:.72rem;color:var(--muted);font-family:var(--mono)">Skill no disponible en este momento.</div>';
    }
  }

  function render(skill) {
    const root = document.getElementById('skill-pdf-ia-root');
    if (!root) return;

    const presets = skill.presets || [];
    const ui      = skill.ui      || {};

    root.innerHTML = `
      <div style="display:flex;gap:.4rem;flex-wrap:wrap;margin-bottom:.7rem" id="pdfia-presets">
        ${presets.map(function (p) {
          return '<button class="img-chip pdfia-preset" data-prompt="' + esc(p.prompt_suffix) + '" title="' + esc(p.description) + '" onclick="selectPdfIaPreset(this)">' + p.icon + ' ' + esc(p.label) + '</button>';
        }).join('')}
      </div>
      <div id="pdfia-hint" style="font-family:var(--mono);font-size:.74rem;color:var(--p);background:rgba(47,128,237,.07);border-left:3px solid var(--p);padding:.45rem .75rem;border-radius:0 8px 8px 0;margin-bottom:.7rem">${esc(ui.hint || 'Adjunta el PDF en el chat (botón 📎) y hazme preguntas. El texto se lee en tu navegador.')}</div>
      <button class="tb primary" onclick="openPdfIaChat()"><i class="fas fa-comment-dots"></i> ${esc(ui.cta || 'Chatear con el PDF')}</button>
      <div style="font-size:.66rem;color:var(--muted);font-family:var(--mono);margin-top:.6rem">⚡ 0 tokens de extracción: pdf.js lee el documento en tu navegador. Solo la respuesta usa IA.</div>
    `;
  }

  window.selectPdfIaPreset = function (btn) {
    document.querySelectorAll('.pdfia-preset').forEach(function (b) { b.classList.remove('active-edu'); });
    btn.classList.add('active-edu');
    const hint = document.getElementById('pdfia-hint');
    if (hint) hint.textContent = btn.dataset.prompt || '';
  };

  window.openPdfIaChat = function () {
    // El chat EMI (con botón 📎 para adjuntar PDF) vive en la página principal.
    window.open('../index.html', '_blank');
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
