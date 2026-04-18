/**
 * ResolvedLink — Modelo Mongoose
 * Módulo: Universal Resolver · CodeHub v3
 * ─────────────────────────────────────────────
 * Almacena el resultado de cada resolución de URL corta.
 * El hash SHA-256 de la URL acortada actúa como clave primaria
 * para evitar duplicados y acelerar lecturas (O(1) por índice).
 */

const mongoose = require('mongoose');

const ResolvedLinkSchema = new mongoose.Schema(
  {
    // ── Identificador ──────────────────────────────────────────
    hash: {
      type:     String,
      required: true,
      unique:   true,
      index:    true,
      comment:  'SHA-256 hex de original_short_url — clave de búsqueda rápida',
    },

    // ── URLs ───────────────────────────────────────────────────
    original_short_url: {
      type:     String,
      required: true,
      trim:     true,
      maxlength: 2048,
    },
    final_resolved_url: {
      type:     String,
      required: true,
      trim:     true,
      maxlength: 4096,
    },

    // ── Metadata de resolución ─────────────────────────────────
    hops_count: {
      type:    Number,
      default: 0,
      min:     0,
      comment: 'Cantidad de saltos HTTP 3xx detectados en la cadena',
    },
    hops_chain: {
      type:    [String],
      default: [],
      comment: 'Cadena completa de URLs intermedias (máx. 20)',
    },
    resolution_method: {
      type:    String,
      enum:    ['http_redirect', 'meta_refresh', 'js_redirect', 'direct'],
      default: 'http_redirect',
      comment: 'Método usado para resolver la URL final',
    },
    status_code: {
      type:    Number,
      default: 200,
      comment: 'Código HTTP de la URL final resuelta',
    },

    // ── Temporalidad ───────────────────────────────────────────
    last_verified: {
      type:    Date,
      default: Date.now,
      index:   true,
      comment: 'Última vez que se verificó la URL (para invalidación de caché)',
    },

    // TTL automático: los registros expiran después de 7 días sin actividad.
    // Cada vez que se sirve desde caché, se actualiza last_verified para resetear el TTL.
    expires_at: {
      type:    Date,
      default: () => new Date(Date.now() + 7 * 24 * 60 * 60 * 1000),
      expires: 0, // MongoDB TTL index — elimina cuando expires_at < now()
    },

    // ── Seguridad ──────────────────────────────────────────────
    is_safe: {
      type:    Boolean,
      default: true,
      comment: 'Marcado como seguro tras validación SSRF',
    },
    resolved_by_ip: {
      type:    String,
      default: null,
      comment: 'IP del solicitante que disparó la primera resolución',
    },
  },
  {
    timestamps:  true,   // createdAt, updatedAt automáticos
    versionKey:  false,
    collection:  'resolved_links',
  }
);

// ── Índices compuestos ─────────────────────────────────────────
ResolvedLinkSchema.index({ original_short_url: 1 });
ResolvedLinkSchema.index({ last_verified: -1 });

// ── Método de instancia: refrescar TTL ────────────────────────
ResolvedLinkSchema.methods.touch = function () {
  const TTL_MS = 7 * 24 * 60 * 60 * 1000;
  this.last_verified = new Date();
  this.expires_at    = new Date(Date.now() + TTL_MS);
  return this.save();
};

// ── Método estático: buscar por hash ──────────────────────────
ResolvedLinkSchema.statics.findByHash = function (hash) {
  return this.findOne({ hash }).lean();
};

module.exports = mongoose.model('ResolvedLink', ResolvedLinkSchema);
