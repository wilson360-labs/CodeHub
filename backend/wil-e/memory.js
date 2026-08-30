// wil-e/memory.js — Memoria entrenable de Wil.E.
// Extrae hechos/preferencias de los mensajes del usuario, los guarda cifrados
// y los recupera para enriquecer el contexto de futuras conversaciones.
const { AIMemory } = require('./models');
const { encrypt } = require('./crypto');

// Pequeño extractor heurístico de hechos. Se puede mejorar con un LLM.
// Detecta patrones comunes: "mi nombre es X", "me gusta X", "uso X", "soy X".
const PATTERNS = [
  { re: /\b(?:mi nombre es|me llamo|soy)\s+([A-Za-zÁÉÍÓÚÑáéíóúñ]{2,30})/i, kind: 'fact', key: 'nombre_usuario' },
  { re: /\b(?:me gusta|me encanta|prefiero|disfruto)\s+(.+?)[,.]/i, kind: 'preference', key: 'gustos' },
  { re: /\b(?:uso|utilizo|trabajo con)\s+(.+?)[,.]/i, kind: 'fact', key: 'herramientas' },
  { re: /\b(?:vivo en|soy de|estoy en)\s+([A-Za-zÁÉÍÓÚÑáéíóúñ].+?)[,.]/i, kind: 'fact', key: 'ubicacion' },
  { re: /\b(?:trabajo como|soy|estudio)\s+([a-záéíóúñ].+?)[,.]/i, kind: 'fact', key: 'rol' },
];

// Extrae los hechos presentes en un texto. Devuelve [{key,kind,value}].
function extractFacts(text) {
  const out = [];
  const t = String(text || '');
  if (!t) return out;
  for (const p of PATTERNS) {
    let m;
    while ((m = p.re.exec(t)) !== null) {
      const value = m[1].trim().replace(/[.,!?;]+$/, '');
      if (value) out.push({ key: p.key, kind: p.kind, value: value.slice(0, 120) });
      // evita bucles infinitos
      t = t.slice(m.index + 1);
    }
  }
  // dedup por (key,value)
  const seen = new Set();
  return out.filter((o) => {
    const k = o.key + '|' + o.value.toLowerCase();
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
}

// Aprende hechos de un mensaje de usuario y los persiste (upsert).
async function remember({ userId, scope = 'user', text }) {
  if (!userId || !text) return 0;
  const facts = extractFacts(text);
  let saved = 0;
  for (const f of facts) {
    try {
      const existing = await AIMemory.findOne({ userId, scope, key: f.key });
      if (existing) {
        existing.content = encrypt(f.value);
        existing.kind = f.kind;
        existing.tags = f.kind === 'preference' ? ['gusto'] : ['fact'];
        existing.updatedAt = new Date();
        await existing.save();
      } else {
        await AIMemory.create({
          userId, scope, kind: f.kind, key: f.key,
          content: encrypt(f.value), source: 'chat',
          tags: f.kind === 'preference' ? ['gusto'] : ['fact'],
        });
      }
      saved++;
    } catch (_) { /* ignora errores por usuario */ }
  }
  return saved;
}

// Guarda una nota de texto libre en la memoria del usuario (para /entrenar).
// A diferencia de remember(), NO hace upsert: permite guardar varias notas y
// Wil.E las recuerda como referencia/contexto cifrado.
async function rememberNote({ userId, scope = 'user', text, title = '' }) {
  if (!userId || !text) return 0;
  const clean = String(text).replace(/\s+/g, ' ').trim();
  if (clean.length < 4) return 0;
  try {
    await AIMemory.create({
      userId, scope, kind: 'note', key: 'nota_' + Date.now(),
      content: encrypt(clean.slice(0, 4000)),
      source: 'entrenar',
      tags: ['nota'],
      meta: { title: String(title).slice(0, 120) || undefined },
    });
    return 1;
  } catch (e) {
    console.warn('rememberNote error:', e.message);
    return 0;
  }
}

// Recupera la memoria relevante de un usuario como texto legible.
async function recall({ userId, scope = 'user', limit = 8 }) {
  if (!userId) return '';
  try {
    const filter = { userId };
    if (scope !== 'global') filter.scope = { $in: [scope, 'user', 'global'] };
    const mems = await AIMemory.find(filter).sort({ updatedAt: -1 }).limit(limit).lean();
    if (mems.length === 0) return '';
    const lines = mems.map((m) => {
      const d = m.content ? decryptSafe(m.content) : '';
      if (!d) return null;
      if (m.kind === 'note') {
        const t = (m.meta && m.meta.title) ? (' 📝 nota "' + m.meta.title + '": ') : ' 📝 nota: ';
        return t + d;
      }
      return `- ${m.key.replace(/_/g, ' ')}: ${d}`;
    }).filter(Boolean);
    return lines.length ? lines.join('\n') : '';
  } catch (_) {
    return '';
  }
}

function decryptSafe(boxed) {
  const { decrypt } = require('./crypto');
  const d = decrypt(boxed);
  return d === null ? boxed : d;
}

module.exports = { remember, rememberNote, recall, extractFacts };
