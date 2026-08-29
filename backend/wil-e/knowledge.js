// wil-e/knowledge.js — Base de conocimiento (RAG).
// Ingesta documentos (troceados), les calcula embeddings y los indexa.
// Recupera los fragmentos más relevantes a una consulta.
const { Knowledge } = require('./models');
const { encrypt } = require('./crypto');
const { embed, toBuffer, fromBuffer, cosine } = require('./embeddings');

const DEFAULT_CHUNK = 900;

// Trocea texto en fragmentos con solape, respetando saltos de línea.
function chunkText(text, size = DEFAULT_CHUNK, overlap = 90) {
  const clean = String(text || '').replace(/\r\n/g, '\n').trim();
  if (!clean) return [];
  if (clean.length <= size) return [clean];
  const parts = [];
  let i = 0;
  while (i < clean.length) {
    let end = Math.min(i + size, clean.length);
    if (end < clean.length) {
      const nl = clean.lastIndexOf('\n', end);
      if (nl > i + size / 2) end = nl;
    }
    parts.push(clean.slice(i, end).trim());
    i = end - overlap;
    if (i <= 0) i = end;
  }
  return parts.filter((p) => p.length > 20);
}

// Keywords simples (léxico) para el modo RAG sin embeddings.
function extractKeywords(text, limit = 8) {
  const stop = new Set(['que','para','como','con','una','los','las','del','de','el','la','en','y','a','es','se','su','por','el','lo','al','un','no','me','mi','cuando','esto','esta','este']);
  const words = String(text || '').toLowerCase().replace(/[^a-z0-9áéíóúñü\s]/g, ' ').split(/\s+/)
    .filter((w) => w.length > 3 && !stop.has(w));
  const freq = {};
  words.forEach((w) => (freq[w] = (freq[w] || 0) + 1));
  return Object.entries(freq).sort((a, b) => b[1] - a[1]).slice(0, limit).map(([w]) => w);
}

// Ingesta un texto como conocimiento RAG del owner. Devuelve nº de fragmentos.
async function ingest({ ownerId, category, title, text, keywords = [], meta = {} }) {
  if (!ownerId || !text) return 0;
  const chunks = chunkText(text);
  if (chunks.length === 0) return 0;

  const vecs = await embed(chunks);
  const ckw = keywords.length ? keywords : extractKeywords([title, text].join(' '));
  const docs = chunks.map((c, i) => {
    const d = {
      ownerId,
      category: category || 'general',
      title,
      chunk: encrypt(c),
      chunkNum: i,
      keywords: ckw,
      meta,
    };
    if (vecs && vecs[i]) d.embedding = toBuffer(vecs[i]);
    return d;
  });

  await Knowledge.insertMany(docs);
  return docs.length;
}

// Recupera los top K fragmentos relevantes para una consulta.
async function retrieve({ ownerId, query, category, topK = 3, minScore = 0.35 }) {
  if (!query) return [];
  const filter = { ownerId };
  if (category) filter.category = category;

  const candidates = await Knowledge.find(filter).limit(200).lean();
  if (candidates.length === 0) return [];

  const useVec = await embed([query]).then((v) => (v ? v[0] : null)).catch(() => null);
  const qkw = extractKeywords(query);

  let scored = candidates.map((c) => {
    let score = 0;
    if (useVec) {
      const cv = fromBuffer(c.embedding);
      score = cosine(useVec, cv);
    } else {
      // Modo léxico: solape de keywords.
      const ckw = c.keywords || [];
      const hit = qkw.filter((k) => ckw.includes(k)).length;
      score = qkw.length ? hit / qkw.length : 0;
    }
    return { c, score };
  });

  scored = scored.filter((s) => !isNaN(s.score)).sort((a, b) => b.score - a.score).slice(0, topK);
  const min = useVec ? minScore : 0.05; // en modo léxico la puntuación es más baja
  return scored.filter((s) => s.score >= min).map((s) => ({
    title: s.c.title,
    category: s.c.category,
    score: Math.round(s.score * 1000) / 1000,
    chunk: decodeSafe(s.c),
    source: s.c.meta?.source || null,
  }));
}

function decodeSafe(doc) {
  try {
    return doc.decrypted ? doc.decrypted() : decryptSafe(doc.chunk);
  } catch (e) {
    return '';
  }
}

function decryptSafe(boxed) {
  const { decrypt } = require('./crypto');
  const d = decrypt(boxed);
  return d === null ? boxed : d;
}

// Elimina todo el conocimiento de un owner (o de una categoría).
async function clear({ ownerId, category }) {
  const filter = { ownerId };
  if (category) filter.category = category;
  return Knowledge.deleteMany(filter);
}

module.exports = { ingest, retrieve, clear, chunkText, extractKeywords };
