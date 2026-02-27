// middleware/rateLimit.js
const requests = new Map(); // ip → { count, resetAt }

const WINDOW_MS  = 15 * 60 * 1000; // 15 minutos
const MAX_REQ    = 30;              // máx mensajes por ventana

module.exports = function rateLimit(req, res, next) {
  const ip  = req.ip || req.connection.remoteAddress || 'unknown';
  const now = Date.now();
  const rec = requests.get(ip);

  if (!rec || now > rec.resetAt) {
    // Nueva ventana
    requests.set(ip, { count: 1, resetAt: now + WINDOW_MS });
    return next();
  }

  if (rec.count >= MAX_REQ) {
    const waitMin = Math.ceil((rec.resetAt - now) / 60000);
    return res.status(429).json({
      error: `Demasiadas solicitudes. Espera ${waitMin} min.`
    });
  }

  rec.count++;
  next();
};
