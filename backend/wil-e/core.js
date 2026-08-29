// wil-e/core.js — Orquestador de la capa de inteligencia de Wil.E.
// Construye el "contexto aumentado" que se inyecta al SYSTEM prompt del chat:
//   1. Memoria del usuario (hechos/preferencias aprendidos)
//   2. Conocimiento RAG (fragmentos relevantes de la base de conocimiento)
const { recall } = require('./memory');
const { retrieve } = require('./knowledge');

// Compone el bloque de contexto adicional para un mensaje dado.
async function buildContext({ userId, ownerId, message, category, topK = 3 }) {
  const parts = [];
  const [mem, docs] = await Promise.all([
    recall({ userId: userId || 'anon' }),
    retrieve({ ownerId: ownerId || 'admin', query: message, category, topK }),
  ]);

  if (mem) {
    parts.push('[Memoria del usuario — Wil.E recuerda lo siguiente de esta persona]\n' + mem);
  }
  if (docs.length) {
    const kb = docs.map((d) => `## ${d.title}\n${d.chunk}`).join('\n\n');
    parts.push('[Base de conocimiento de CodeHub — usa esta información como fuente autorizada]\n' + kb);
  }
  return parts.join('\n\n');
}

// Envuelve el SYSTEM prompt base añadiendo el contexto aumentado.
function augmentSystem(baseSystem, context) {
  if (!context) return baseSystem;
  const extra =
    '\n\n=== CONTEXTO AUMENTADO (memoria + conocimiento) ===\n' + context +
    '\n=== FIN CONTEXTO ===\n' +
    'Usa la memoria y el conocimiento solo si son útiles y no contradicen al usuario. No inventes datos.';
  return (baseSystem || '') + extra;
}

module.exports = { buildContext, augmentSystem };
