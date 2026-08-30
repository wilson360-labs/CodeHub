// wil-e/tts.js — Voz TTS neural premium (Jarvis) para Wil.E.
// Si ELEVENLABS_API_KEY está configurada, se usa ElevenLabs (voz premium real).
// Si no, el módulo responde { available: false } y el frontend cae a la voz
// del navegador (speechSynthesis) de forma transparente.
const express = require('express');

module.exports = function (opts) {
  const router = express.Router();
  const { authPayload } = opts || {};

  const key = process.env.ELEVENLABS_API_KEY || '';
  const VOICE_SPANISH = process.env.ELEVENLABS_VOICE_ES || 'N2lD1ixsuvnrwL7fM2Yv'; // voz "Ellie" es-ES (la más cercana a un asistente claro)
  const VOICE_DEEP = process.env.ELEVENLABS_VOICE_JARVIS || 'TxGEqnHWrfWFTfGW9XjX'; // voz grave masculina es

  // GET /api/tts/info — estado de disponibilidad de la voz premium
  router.get('/info', (req, res) => {
    res.json({ available: !!key, provider: key ? 'elevenlabs' : null, mode: key ? 'premium' : 'browser' });
  });

  // POST /api/tts — convierte texto a audio (data URL / base64)
  router.post('/', async (req, res) => {
    if (!key) return res.status(503).json({ ok: false, error: 'ELEVENLABS_API_KEY no configurada', available: false });
    const { text, voice } = req.body || {};
    const t = String(text || '').trim();
    if (!t) return res.status(400).json({ ok: false, error: 'Falta text' });
    if (t.length > 600) return res.status(400).json({ ok: false, error: 'Texto muy largo (máx 600 caracteres)' });

    const voiceId = String(voice || VOICE_SPANISH);
    try {
      const r = await fetch('https://api.elevenlabs.io/v1/text-to-speech/' + voiceId, {
        method: 'POST',
        headers: {
          'xi-api-key': key,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          text: t,
          model_id: process.env.ELEVENLABS_MODEL || 'eleven_multilingual_v2',
          voice_settings: { stability: 0.5, similarity_boost: 0.75, style: 0.3, use_speaker_boost: true },
        }),
        signal: AbortSignal.timeout(15000),
      });
      if (!r.ok) {
        const errText = await r.text().catch(() => '');
        console.warn('ElevenLabs TTS error:', r.status, errText.slice(0, 150));
        return res.status(r.status).json({ ok: false, error: 'TTS fallo (' + r.status + ')' });
      }
      const buf = Buffer.from(await r.arrayBuffer());
      const ct = r.headers.get('content-type') || 'audio/mpeg';
      res.json({ ok: true, audio: 'data:' + ct.split(';')[0] + ';base64,' + buf.toString('base64'), provider: 'elevenlabs' });
    } catch (e) {
      console.warn('TTS error:', e.message);
      res.status(500).json({ ok: false, error: 'Error generando voz' });
    }
  });

  return router;
};
