// ═══════════════════════════════════════════════════════════
//  server.js — CodeHub Backend
//  INSTRUCCIONES: Si ya tienes un server.js, copia solo las
//  secciones marcadas con ── AGREGAR ── a tu archivo existente
// ═══════════════════════════════════════════════════════════

require('dotenv').config(); // ── AGREGAR: línea 1 de tu server.js

const express  = require('express');
const mongoose = require('mongoose'); // ── AGREGAR
const cors     = require('cors');     // ── AGREGAR
const chatRoute= require('./routes/chat'); // ── AGREGAR

const app  = express();
const PORT = process.env.PORT || 3001;

// ── AGREGAR: Configurar CORS ─────────────────────────────
// Solo permite requests desde tu dominio del frontend
const allowedOrigins = (process.env.ALLOWED_ORIGIN || '*').split(',');

app.use(cors({
  origin: function(origin, callback) {
    // Permitir requests sin origin (Postman, curl) en desarrollo
    if (!origin) return callback(null, true);
    if (allowedOrigins.includes('*') || allowedOrigins.includes(origin)) {
      return callback(null, true);
    }
    callback(new Error('CORS: origen no permitido'));
  },
  methods: ['GET', 'POST'],
  allowedHeaders: ['Content-Type']
}));
// ────────────────────────────────────────────────────────

app.use(express.json({ limit: '10kb' })); // ── AGREGAR: límite de payload

// ── AGREGAR: Conectar MongoDB ────────────────────────────
mongoose.connect(process.env.MONGODB_URI)
  .then(() => console.log('✅ MongoDB conectado'))
  .catch(err => console.error('❌ MongoDB error:', err.message));
// ────────────────────────────────────────────────────────

// ── TUS RUTAS EXISTENTES VAN AQUÍ ───────────────────────
// app.use('/api/algo', tuRutaExistente);

// ── AGREGAR: Ruta del chat ───────────────────────────────
app.use('/api/chat', chatRoute);
// ────────────────────────────────────────────────────────

// Health check — para verificar que el server corre
app.get('/health', (req, res) => {
  res.json({
    status: 'ok',
    mongo:  mongoose.connection.readyState === 1 ? 'connected' : 'disconnected'
  });
});

app.listen(PORT, () => {
  console.log(`🚀 CodeHub backend corriendo en puerto ${PORT}`);
});
