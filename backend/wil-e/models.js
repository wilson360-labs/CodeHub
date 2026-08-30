// wil-e/models.js — Modelos de datos de la capa de inteligencia de Wil.E.
// Memoria entrenable por usuario + base de conocimiento con embeddings.
// Los campos privados (content) se guardan CIFRADOS (E2E en reposo).
const mongoose = require('mongoose');
const { encrypt, decrypt } = require('./crypto');

// Memoria de largo plazo de un usuario: hechos que Wil.E aprende y recuerda.
const AIMemorySchema = new mongoose.Schema({
  userId:   { type: String, index: true },          // req.authUser?.id o 'anon:_ip'
  scope:    { type: String, enum: ['global','user','session'], default: 'user' },
  kind:     { type: String, enum: ['fact','preference','skill','note'], default: 'fact' },
  key:      { type: String, index: true },          // clave semántica (ej. "nombre_usuario")
  content:  { type: String, default: '' },          // CIFRADO
  source:   { type: String, default: 'chat' },      // cómo se aprendió
  confidence: { type: Number, default: 1, min: 0, max: 1 },
  tags:     [String],
  meta:     { type: mongoose.Schema.Types.Mixed, default: {} },
  updatedAt: { type: Date, default: Date.now },
  createdAt: { type: Date, default: Date.now },
});
AIMemorySchema.index({ userId: 1, key: 1 });

AIMemorySchema.methods.decrypted = function () {
  if (!this.content) return this.content;
  const d = decrypt(this.content);
  return d === null ? this.content : d;
};

const AIMemory = mongoose.model('AIMemory', AIMemorySchema);

// Base de conocimiento: documentos troceados + embeddings para RAG.
const KnowledgeSchema = new mongoose.Schema({
  ownerId:   { type: String, index: true },         // dueño del conocimiento (admin/user)
  category:  { type: String, default: 'general' },  // guia, proyecto, pdf, nota...
  title:     { type: String, default: '' },
  chunk:     { type: String, default: '' },         // CIFRADO
  // Embedding en Float32Array (guardado como Buffer). Opcional: si no hay
  // servicio de embeddings, se usan metadatos (tags/keywords) para RAG léxico.
  embedding: { type: Buffer, default: null },
  keywords:  [String],
  meta:      { type: mongoose.Schema.Types.Mixed, default: {} },
  createdAt: { type: Date, default: Date.now },
  updatedAt: { type: Date, default: Date.now },
});
KnowledgeSchema.index({ ownerId: 1, category: 1 });
KnowledgeSchema.index({ ownerId: 1, keywords: 1 });

KnowledgeSchema.methods.decrypted = function () {
  if (!this.chunk) return this.chunk;
  const d = decrypt(this.chunk);
  return d === null ? this.chunk : d;
};

const Knowledge = mongoose.model('Knowledge', KnowledgeSchema);

module.exports = { AIMemory, Knowledge };
