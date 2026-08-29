// wil-e/embeddings.js — Generación de embeddings para RAG.
// Usa la API de Google Gemini (modelo de embeddings) si GEMINI_API_KEY existe.
// Si no, devuelve null y el buscador cae al modo léxico por keywords.
const https = require('https');

const EMBED_MODEL = 'models/text-embedding-004';
const DIM = 768;

async function embed(texts) {
  const apiKey = process.env.GEMINI_API_KEY || process.env.GOOGLE_API_KEY;
  if (!apiKey || !Array.isArray(texts) || texts.length === 0) return null;
  try {
    const url = new URL(`https://generativelanguage.googleapis.com/v1beta/${EMBED_MODEL}:batchEmbedContents`);
    url.searchParams.set('key', apiKey);
    const body = JSON.stringify({
      requests: texts.map((t) => ({
        model: EMBED_MODEL,
        content: { parts: [{ text: String(t) }] },
      })),
    });
    const data = await postJson(url.toString(), body);
    const embeddings = (data.embeddings || []).map((e) => new Float32Array(e.values || []));
    return embeddings.length === texts.length ? embeddings : null;
  } catch (_) {
    return null;
  }
}

// Punto de entrada: siempre devuelve array de Float32Array o null.
async function embedOne(text) {
  const res = await embed([text]);
  return res ? res[0] : null;
}

function toBuffer(vec) {
  if (!vec) return null;
  return Buffer.from(vec.buffer, vec.byteOffset, vec.byteLength);
}

function fromBuffer(buf) {
  if (!buf) return null;
  return new Float32Array(buf.buffer, buf.byteOffset, buf.byteLength / 4);
}

function cosine(a, b) {
  if (!a || !b || a.length !== b.length) return 0;
  let dot = 0, na = 0, nb = 0;
  for (let i = 0; i < a.length; i++) {
    dot += a[i] * b[i];
    na += a[i] * a[i];
    nb += b[i] * b[i];
  }
  if (na === 0 || nb === 0) return 0;
  return dot / (Math.sqrt(na) * Math.sqrt(nb));
}

function postJson(url, body, headers = {}) {
  return new Promise((resolve, reject) => {
    const req = https.request(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...headers },
    }, (res) => {
      let raw = '';
      res.on('data', (c) => (raw += c));
      res.on('end', () => {
        try { resolve(JSON.parse(raw)); } catch (e) { reject(e); }
      });
    });
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

module.exports = { embed, embedOne, toBuffer, fromBuffer, cosine, EMBED_MODEL, DIM };
