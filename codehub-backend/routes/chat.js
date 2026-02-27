// routes/chat.js
const express      = require('express');
const Anthropic    = require('@anthropic-ai/sdk');
const Conversation = require('../models/Conversation');
const rateLimit    = require('../middleware/rateLimit');

const router  = express.Router();
const claude  = new Anthropic({ apiKey: process.env.ANTHROPIC_API_KEY });

const SYSTEM_PROMPT = `Eres el asistente de CodeHub, el portfolio de Wilson.E, \
desarrollador guatemalteco. Eres conciso, técnico y amigable. Respondes en español.
CodeHub tiene: portfolio personal, 18 herramientas web (QR, contraseñas, hash SHA-256, \
regex tester, UUID, Pomodoro, convertidor de unidades, monedas, IMC, préstamos, velocidad \
de internet, Base64, paleta de colores, memes, compresor de imágenes), una tienda de apps \
Android y juegos (Snake, Tetris).
Cuando expliques código usa bloques cortos. Máximo 4 oraciones salvo que pidan detalle.`;

// POST /api/chat
router.post('/', rateLimit, async (req, res) => {
  const { message, sessionId } = req.body;

  // Validación básica
  if (!message || typeof message !== 'string' || message.trim().length === 0) {
    return res.status(400).json({ error: 'El mensaje no puede estar vacío.' });
  }
  if (message.length > 2000) {
    return res.status(400).json({ error: 'Mensaje demasiado largo (máx 2000 chars).' });
  }
  if (!sessionId || typeof sessionId !== 'string') {
    return res.status(400).json({ error: 'sessionId requerido.' });
  }

  try {
    // 1 · Buscar o crear conversación en MongoDB
    let convo = await Conversation.findOne({ sessionId });
    if (!convo) {
      convo = new Conversation({
        sessionId,
        ip: req.ip || 'unknown',
        messages: []
      });
    }

    // 2 · Construir historial para Claude (últimas 10 interacciones = 20 mensajes)
    const historyForClaude = convo.messages
      .slice(-20)
      .map(m => ({ role: m.role, content: m.content }));

    // Agregar el mensaje actual
    historyForClaude.push({ role: 'user', content: message.trim() });

    // 3 · Llamar a Claude
    const response = await claude.messages.create({
      model:      'claude-sonnet-4-6',
      max_tokens: 600,
      system:     SYSTEM_PROMPT,
      messages:   historyForClaude
    });

    const reply      = response.content[0].text;
    const inputTok   = response.usage?.input_tokens  || 0;
    const outputTok  = response.usage?.output_tokens || 0;
    const totalTok   = inputTok + outputTok;

    // 4 · Guardar en MongoDB
    convo.messages.push(
      { role: 'user',      content: message.trim(), tokens: inputTok  },
      { role: 'assistant', content: reply,           tokens: outputTok }
    );
    convo.totalTokens += totalTok;

    // Limitar a 100 mensajes guardados por sesión
    if (convo.messages.length > 100) {
      convo.messages = convo.messages.slice(-100);
    }

    await convo.save();

    // 5 · Responder al frontend (nunca exponer keys ni datos internos)
    res.json({
      reply,
      sessionId,
      usage: { total: totalTok }
    });

  } catch (err) {
    console.error('[chat] Error:', err.message);

    // Error de la API de Anthropic
    if (err.status === 401) {
      return res.status(500).json({ error: 'Error de autenticación con la IA.' });
    }
    if (err.status === 429) {
      return res.status(429).json({ error: 'Límite de la API alcanzado. Intenta en un momento.' });
    }

    res.status(500).json({ error: 'Error interno. Intenta de nuevo.' });
  }
});

// GET /api/chat/history/:sessionId  — para ver historial desde el panel admin
router.get('/history/:sessionId', async (req, res) => {
  try {
    const convo = await Conversation.findOne({ sessionId: req.params.sessionId });
    if (!convo) return res.json({ messages: [] });
    res.json({
      messages: convo.messages.map(m => ({ role: m.role, content: m.content })),
      totalTokens: convo.totalTokens
    });
  } catch (err) {
    res.status(500).json({ error: 'Error al obtener historial.' });
  }
});

module.exports = router;
