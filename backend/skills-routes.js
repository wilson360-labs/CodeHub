// ═══════════════════════════════════════════════════════════════
// SKILLS — Archivo: backend/skills-routes.js
// Pega este bloque en server.js justo antes de la línea:
//   app.post('/api/generate-image', ...)
// ═══════════════════════════════════════════════════════════════

const fs   = require('fs');
const path = require('path');

// ── GET /api/skills — Catálogo de skills disponibles ──────────
app.get('/api/skills', (req, res) => {
  try {
    const indexPath = path.join(__dirname, '../skills/index.json');
    if (!fs.existsSync(indexPath)) return res.json({ skills: [] });
    const index = JSON.parse(fs.readFileSync(indexPath, 'utf8'));

    // Enriquecer cada skill con su skill.json individual
    const enriched = (index.skills || [])
      .filter(s => s.active)
      .map(s => {
        try {
          const skillPath = path.join(__dirname, '../skills', s.id, 'skill.json');
          if (fs.existsSync(skillPath)) {
            const detail = JSON.parse(fs.readFileSync(skillPath, 'utf8'));
            return { ...s, presets: detail.presets, ui: detail.ui, examples: detail.examples, sizes: detail.sizes };
          }
        } catch (_) {}
        return s;
      });

    res.json({ skills: enriched, total: enriched.length });
  } catch (e) {
    res.status(500).json({ error: 'Error cargando skills', detail: e.message });
  }
});

// ── GET /api/skills/:id — Detalle de una skill específica ──────
app.get('/api/skills/:id', (req, res) => {
  try {
    const skillPath = path.join(__dirname, '../skills', req.params.id, 'skill.json');
    if (!fs.existsSync(skillPath)) return res.status(404).json({ error: 'Skill no encontrada' });
    const skill = JSON.parse(fs.readFileSync(skillPath, 'utf8'));
    res.json(skill);
  } catch (e) {
    res.status(500).json({ error: 'Error leyendo skill', detail: e.message });
  }
});

// ── POST /api/generate-image — MODIFICADO con soporte de skill preset ──
// Reemplaza tu app.post('/api/generate-image', ...) existente con este:
app.post('/api/generate-image', chatLimiter, async (req, res) => {
  const { prompt, width = 512, height = 512, provider = 'auto', preset_id = null, skill_id = null } = req.body;
  if (!prompt || typeof prompt !== 'string' || prompt.trim().length < 2) {
    return res.status(400).json({ error: 'Prompt requerido' });
  }

  let basePrompt = prompt.trim().slice(0, 500);
  let w = Math.min(Math.max(parseInt(width)  || 512, 256), 1024);
  let h = Math.min(Math.max(parseInt(height) || 512, 256), 1024);

  // ── Inyectar prompt_suffix del preset si viene skill + preset ─
  if (skill_id && preset_id) {
    try {
      const skillPath = path.join(__dirname, '../skills', skill_id, 'skill.json');
      if (fs.existsSync(skillPath)) {
        const skillData = JSON.parse(fs.readFileSync(skillPath, 'utf8'));
        const preset = (skillData.presets || []).find(p => p.id === preset_id);
        if (preset) {
          basePrompt = `${basePrompt}, ${preset.prompt_suffix}`.slice(0, 700);
          // Aplicar tamaño recomendado del preset si no se especificó manualmente
          if (preset.recommended_size && !req.body.width) {
            const [pw, ph] = preset.recommended_size.split('x').map(Number);
            w = pw; h = ph;
          }
        }
      }
    } catch (_) { /* si falla la skill, continúa con el prompt base */ }
  }

  const p      = basePrompt;
  const errors = [];

  // ── 1. Together AI — FLUX.1 Schnell ───────────────────────
  if (process.env.TOGETHER_API_KEY && (provider === 'auto' || provider === 'together')) {
    try {
      const r = await fetch('https://api.together.xyz/v1/images/generations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${process.env.TOGETHER_API_KEY}` },
        body: JSON.stringify({ model: 'black-forest-labs/FLUX.1-schnell-Free', prompt: p, width: w, height: h, steps: 4, n: 1 })
      });
      if (r.ok) {
        const d = await r.json();
        const b64 = d.data?.[0]?.b64_json;
        const url = d.data?.[0]?.url;
        if (b64) return res.json({ ok: true, provider: 'together', model: 'FLUX.1-schnell', image: `data:image/png;base64,${b64}`, preset: preset_id });
        if (url) return res.json({ ok: true, provider: 'together', model: 'FLUX.1-schnell', url, preset: preset_id });
      } else {
        const e = await r.json().catch(() => ({}));
        errors.push(`Together: ${e.error?.message || r.status}`);
      }
    } catch (e) { errors.push(`Together: ${e.message}`); }
  }

  // ── 2. Gemini — Imagen 3 Fast ─────────────────────────────
  if (process.env.GEMINI_API_KEY && (provider === 'auto' || provider === 'gemini')) {
    try {
      const r = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/imagen-3.0-fast-generate-001:predict?key=${process.env.GEMINI_API_KEY}`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            instances: [{ prompt: p }],
            parameters: { sampleCount: 1, aspectRatio: w > h ? '16:9' : w === h ? '1:1' : '9:16' }
          })
        }
      );
      if (r.ok) {
        const d = await r.json();
        const b64 = d.predictions?.[0]?.bytesBase64Encoded;
        if (b64) return res.json({ ok: true, provider: 'gemini', model: 'Imagen 3 Fast', image: `data:image/png;base64,${b64}`, preset: preset_id });
      } else {
        const e = await r.json().catch(() => ({}));
        errors.push(`Gemini: ${e.error?.message || r.status}`);
      }
    } catch (e) { errors.push(`Gemini: ${e.message}`); }
  }

  // ── 3. MiniMax — image-01 ─────────────────────────────────
  if (process.env.MINIMAX_API_KEY && (provider === 'auto' || provider === 'minimax')) {
    try {
      const aspectRatio = w > h ? '16:9' : w < h ? '9:16' : '1:1';
      const r = await fetch('https://api.minimax.io/v1/image_generation', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${process.env.MINIMAX_API_KEY}` },
        body: JSON.stringify({ model: 'image-01', prompt: p, aspect_ratio: aspectRatio, response_format: 'base64', n: 1 })
      });
      if (r.ok) {
        const d = await r.json();
        const b64 = d.data?.base64?.[0] || d.data?.images?.[0]?.base64 || d.data?.image_base64?.[0];
        const url = d.data?.image_urls?.[0];
        if (b64) return res.json({ ok: true, provider: 'minimax', model: 'image-01', image: `data:image/png;base64,${b64}`, preset: preset_id });
        if (url) return res.json({ ok: true, provider: 'minimax', model: 'image-01', url, preset: preset_id });
        errors.push('MiniMax: respuesta sin imagen');
      } else {
        const e = await r.json().catch(() => ({}));
        errors.push(`MiniMax: ${e.base_resp?.status_msg || e.message || r.status}`);
      }
    } catch (e) { errors.push(`MiniMax: ${e.message}`); }
  }

  // ── 4. Pollinations — Flux ────────────────────────────────
  if (provider === 'auto' || provider === 'pollinations') {
    try {
      const seed = Math.floor(Math.random() * 99999);
      const polUrl = `https://image.pollinations.ai/prompt/${encodeURIComponent(p)}?width=${w}&height=${h}&seed=${seed}&model=flux&nologo=true`;
      const r = await fetch(polUrl, { signal: AbortSignal.timeout(25000) });
      if (r.ok) {
        const buf = await r.arrayBuffer();
        const b64 = Buffer.from(buf).toString('base64');
        return res.json({ ok: true, provider: 'pollinations', model: 'Flux', image: `data:image/jpeg;base64,${b64}`, preset: preset_id });
      } else { errors.push(`Pollinations: ${r.status}`); }
    } catch (e) { errors.push(`Pollinations: ${e.message}`); }
  }

  // ── 5. Pollinations Turbo (fallback final) ────────────────
  try {
    const seed2 = Math.floor(Math.random() * 99999);
    const url2  = `https://image.pollinations.ai/prompt/${encodeURIComponent(p)}?width=512&height=512&seed=${seed2}&model=turbo&nologo=true`;
    const r2 = await fetch(url2, { signal: AbortSignal.timeout(20000) });
    if (r2.ok) {
      const buf = await r2.arrayBuffer();
      const b64 = Buffer.from(buf).toString('base64');
      return res.json({ ok: true, provider: 'pollinations-turbo', model: 'Turbo', image: `data:image/jpeg;base64,${b64}`, preset: preset_id });
    }
    errors.push(`Pollinations Turbo: ${r2.status}`);
  } catch (e) { errors.push(`Pollinations Turbo: ${e.message}`); }

  res.status(503).json({ ok: false, error: 'Todos los proveedores fallaron', details: errors });
});
