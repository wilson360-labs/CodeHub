// ═══════════════════════════════════════════════════════════════
// ARCHIVO: js/skills-image-gen.js
// Módulo autónomo — agrégalo con <script src="js/skills-image-gen.js"></script>
// en tools.html justo antes de </body>
// Requiere que BACKEND esté definido (ya lo está en tools.html)
// ═══════════════════════════════════════════════════════════════

(function () {
  'use strict';

  // ── Config ──────────────────────────────────────────────────
  const SKILL_ID   = 'image-gen';
  const MAX_REQS   = 10; // por sesión
  const SESS_KEY   = 'ch_skill_img_count';

  let skillData    = null;
  let activePreset = null;

  // ── Bootstrap ───────────────────────────────────────────────
  async function init() {
    try {
      const res  = await fetch(BACKEND + '/api/skills/' + SKILL_ID);
      if (!res.ok) throw new Error('Skill no disponible');
      skillData = await res.json();
      renderUI();
    } catch (e) {
      console.warn('[SkillImageGen] No se pudo cargar la skill:', e.message);
      // Si el backend no tiene el endpoint aún, no rompe nada
    }
  }

  // ── Render UI ───────────────────────────────────────────────
  function renderUI() {
    const container = document.getElementById('skill-image-gen-root');
    if (!container) return; // El HTML no incluye el contenedor aún

    const presets   = skillData.presets || [];
    const sizes     = skillData.sizes   || [];
    const examples  = skillData.examples|| [];
    const ui        = skillData.ui      || {};

    container.innerHTML = `
      <div class="sig-wrap">

        <!-- Header -->
        <div class="sig-header">
          <span class="sig-icon">🎨</span>
          <div>
            <h3 class="sig-title">Generador de Imágenes Educativas</h3>
            <p class="sig-sub">Powered by FLUX · Imagen 3 · MiniMax · Pollinations</p>
          </div>
          <span class="sig-badge">SKILL</span>
        </div>

        <!-- Presets -->
        <div class="sig-section-label">Tipo de imagen</div>
        <div class="sig-presets" id="sig-presets">
          ${presets.map(p => `
            <button class="sig-preset-btn" data-id="${p.id}" title="${p.description}">
              <span class="sig-preset-icon">${p.icon}</span>
              <span class="sig-preset-label">${p.label}</span>
            </button>
          `).join('')}
        </div>

        <!-- Prompt -->
        <div class="sig-section-label">Describe la imagen</div>
        <div class="sig-prompt-wrap">
          <textarea
            id="sig-prompt"
            class="sig-textarea"
            placeholder="${ui.placeholder || 'Describe qué quieres visualizar…'}"
            maxlength="500"
            rows="3"
          ></textarea>
          <span class="sig-chars"><span id="sig-char-count">0</span>/500</span>
        </div>

        <!-- Hint del preset activo -->
        <div class="sig-preset-hint" id="sig-preset-hint" style="display:none">
          <span id="sig-preset-hint-text"></span>
        </div>

        <!-- Ejemplos -->
        <div class="sig-section-label">Ejemplos rápidos</div>
        <div class="sig-examples" id="sig-examples">
          ${examples.map(e => `
            <button class="sig-example-btn" data-prompt="${escHtml(e.prompt)}" data-preset="${e.preset}">
              ${e.label}
            </button>
          `).join('')}
        </div>

        <!-- Tamaño -->
        <div class="sig-row">
          <div class="sig-section-label" style="margin:0;align-self:center">Tamaño</div>
          <select id="sig-size" class="sig-select">
            ${sizes.map(s => `<option value="${s.id}">${s.label} — ${s.use}</option>`).join('')}
          </select>
        </div>

        <!-- Botón generar -->
        <button class="sig-btn-generate" id="sig-generate-btn" onclick="SkillImageGen.generate()">
          🎨 ${ui.cta || 'Generar imagen'}
        </button>

        <!-- Progress -->
        <div class="sig-progress" id="sig-progress" style="display:none">
          <canvas class="sig-orb" id="sig-orb" width="48" height="48" aria-label="Generando imagen"></canvas>
          <div class="sig-progress-bar" id="sig-progress-bar"></div>
          <p class="sig-progress-msg" id="sig-progress-msg">Preparando…</p>
        </div>

        <!-- Resultado -->
        <div class="sig-result" id="sig-result" style="display:none">
          <img id="sig-result-img" src="" alt="Imagen generada" class="sig-result-img" />
          <div class="sig-result-meta" id="sig-result-meta"></div>
          <div class="sig-result-actions">
            <a id="sig-download-btn" class="sig-btn-action" download="imagen-educativa.png">⬇ Descargar</a>
            <button class="sig-btn-action" onclick="SkillImageGen.copyPrompt()">📋 Copiar prompt</button>
            <button class="sig-btn-action" onclick="SkillImageGen.generate()">🔄 Regenerar</button>
          </div>
        </div>

        <!-- Error -->
        <div class="sig-error" id="sig-error" style="display:none"></div>

      </div>
    `;

    bindEvents();
  }

  // ── Event binding ───────────────────────────────────────────
  function bindEvents() {
    // Presets
    document.querySelectorAll('.sig-preset-btn').forEach(btn => {
      btn.addEventListener('click', () => selectPreset(btn.dataset.id));
    });

    // Ejemplos
    document.querySelectorAll('.sig-example-btn').forEach(btn => {
      btn.addEventListener('click', () => {
        document.getElementById('sig-prompt').value = btn.dataset.prompt;
        updateCharCount();
        if (btn.dataset.preset) selectPreset(btn.dataset.preset);
      });
    });

    // Contador de caracteres
    document.getElementById('sig-prompt').addEventListener('input', updateCharCount);

    // Seleccionar el primer preset por defecto
    if (skillData.presets?.length) selectPreset(skillData.presets[0].id);
  }

  function selectPreset(id) {
    activePreset = id;
    document.querySelectorAll('.sig-preset-btn').forEach(btn => {
      btn.classList.toggle('active', btn.dataset.id === id);
    });
    const preset = (skillData.presets || []).find(p => p.id === id);
    const hintEl = document.getElementById('sig-preset-hint');
    if (preset && hintEl) {
      document.getElementById('sig-preset-hint-text').textContent = '✨ ' + preset.description;
      hintEl.style.display = 'block';
      // Aplicar tamaño recomendado
      if (preset.recommended_size) {
        const sel = document.getElementById('sig-size');
        if (sel) {
          const opt = Array.from(sel.options).find(o => o.value === preset.recommended_size);
          if (opt) sel.value = preset.recommended_size;
        }
      }
    }
  }

  function updateCharCount() {
    const t = document.getElementById('sig-prompt');
    const c = document.getElementById('sig-char-count');
    if (t && c) c.textContent = t.value.length;
  }

  // ── Generate ────────────────────────────────────────────────
  async function generate() {
    const promptEl = document.getElementById('sig-prompt');
    const sizeEl   = document.getElementById('sig-size');
    const prompt   = promptEl?.value.trim();

    if (!prompt) { showError('Escribe una descripción de la imagen primero.'); return; }

    // Anti-spam
    const count = parseInt(sessionStorage.getItem(SESS_KEY) || '0');
    if (count >= MAX_REQS) {
      showError('Límite de sesión alcanzado (10 imágenes). Recarga la página para continuar.');
      return;
    }
    sessionStorage.setItem(SESS_KEY, count + 1);

    const sizeVal = sizeEl?.value || '512x512';
    const [w, h]  = sizeVal.split('x').map(Number);

    hideError();
    hideResult();
    showProgress();

    const msgs = skillData?.ui?.loading_messages || ['Generando…'];
    let msgIdx = 0;
    const msgInterval = setInterval(() => {
      setProgressMsg(msgs[Math.min(msgIdx++, msgs.length - 1)]);
      setProgressPct(Math.min(20 + msgIdx * 20, 85));
    }, 4000);

    try {
      const body = { prompt, width: w, height: h, provider: 'auto', skill_id: SKILL_ID, preset_id: activePreset };
      const res  = await Promise.race([
        fetch(BACKEND + '/api/generate-image', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(body)
        }),
        new Promise((_, r) => setTimeout(() => r(new Error('Timeout')), 38000))
      ]);

      const data = await res.json();

      if (data.ok && (data.image || data.url)) {
        setProgressPct(100);
        showResult(data.image || data.url, data.provider, data.model, prompt);
      } else {
        throw new Error(data.error || 'Sin imagen en la respuesta');
      }
    } catch (e) {
      showError('No se pudo generar la imagen: ' + e.message + '. Intenta de nuevo.');
    } finally {
      clearInterval(msgInterval);
      hideProgress();
    }
  }

  // ── UI helpers ───────────────────────────────────────────────
  let orbInstance = null;
  function showProgress() {
    const el = document.getElementById('sig-progress');
    if (el) { el.style.display = 'block'; setProgressPct(10); setProgressMsg('Preparando prompt…'); }
    const btn = document.getElementById('sig-generate-btn');
    if (btn) { btn.disabled = true; btn.textContent = '⏳ Generando…'; }
    const orbEl = document.getElementById('sig-orb');
    if (orbEl && window.ThinkingOrb) {
      orbInstance = window.ThinkingOrb.create(orbEl, { state: 'working', size: 48, color: 'rgba(255,107,53,0.95)' });
    }
  }
  function hideProgress() {
    const el = document.getElementById('sig-progress');
    if (el) el.style.display = 'none';
    const btn = document.getElementById('sig-generate-btn');
    if (btn) { btn.disabled = false; btn.textContent = '🎨 ' + (skillData?.ui?.cta || 'Generar imagen'); }
    if (orbInstance) { orbInstance.destroy(); orbInstance = null; }
  }
  function setProgressMsg(msg) {
    const el = document.getElementById('sig-progress-msg');
    if (el) el.textContent = msg;
  }
  function setProgressPct(pct) {
    const el = document.getElementById('sig-progress-bar');
    if (el) el.style.width = pct + '%';
  }
  function showResult(src, provider, model, prompt) {
    const el    = document.getElementById('sig-result');
    const img   = document.getElementById('sig-result-img');
    const meta  = document.getElementById('sig-result-meta');
    const dlBtn = document.getElementById('sig-download-btn');
    if (!el || !img) return;
    img.src          = src;
    img.dataset.prompt = prompt;
    if (meta)  meta.innerHTML  = `✅ Generado con <strong>${provider}</strong> — ${model}`;
    if (dlBtn) dlBtn.href      = src;
    el.style.display = 'block';
    el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }
  function hideResult() {
    const el = document.getElementById('sig-result');
    if (el) el.style.display = 'none';
  }
  function showError(msg) {
    const el = document.getElementById('sig-error');
    if (el) { el.textContent = '❌ ' + msg; el.style.display = 'block'; }
  }
  function hideError() {
    const el = document.getElementById('sig-error');
    if (el) el.style.display = 'none';
  }
  function copyPrompt() {
    const img = document.getElementById('sig-result-img');
    if (img?.dataset.prompt) navigator.clipboard.writeText(img.dataset.prompt).catch(() => {});
  }
  function escHtml(s) {
    return s.replace(/&/g,'&amp;').replace(/"/g,'&quot;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  }

  // ── Public API ───────────────────────────────────────────────
  window.SkillImageGen = { init, generate, copyPrompt };

  // Auto-init cuando el DOM esté listo
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

})();
