// ─────────────────────────────────────────────
//  Clima — helpers puros (etiquetas, horas, umbrales)
//  CodeHub by Wilson.E — futurista e inteligente
// ─────────────────────────────────────────────

// Intervalo de resumen climático del usuario (en minutos).
// Valores aceptados: 30, 60, 180, 360 o 0 = solo alertas bajo demanda.
function normalizeWeatherInterval(v) {
  const n = Number(v);
  if ([30, 60, 180, 360].includes(n)) return n;
  return 0; // solo alertas
}

// Descripción legible de un weather_code de Open-Meteo.
function wxLabel(code, isDay) {
  const map = {
    0: isDay ? '☀️ despejado' : '🌙 despejado (noche)',
    1: isDay ? '🌤️ mayormente despejado' : '🌙 claro (noche)',
    2: '⛅ parcialmente nublado',
    3: '☁️ nublado',
    45: '🌫️ niebla', 48: '🌫️ niebla helada',
    51: '🌦️ llovizna', 53: '🌦️ llovizna', 55: '🌦️ llovizna',
    56: '🌧️ llovizna helada', 57: '🌧️ llovizna helada',
    61: '🌧️ lluvia ligera', 63: '🌧️ lluvia', 65: '🌧️ lluvia fuerte',
    66: '🌧️ lluvia helada', 67: '🌧️ lluvia helada',
    71: '🌨️ nieve ligera', 73: '🌨️ nieve', 75: '❄️ nieve fuerte',
    77: '❄️ granizo',
    80: '🌦️ chubascos', 81: '🌦️ chubascos', 82: '⛈️ chubascos intensos',
    85: '🌨️ chubascos de nieve', 86: '❄️ chubascos de nieve',
    95: '⛈️ tormenta eléctrica', 96: '⛈️ tormenta con granizo', 99: '⛈️ tormenta violenta',
  };
  return map[code] || '🌡️ variable';
}

// Etiqueta horaria para el detalle del día.
function hourlyLabel(h) {
  const hh = String(h.h).padStart(2, '0');
  const am = h.h < 5 ? 'madrugada' : h.h < 12 ? 'mañana' : h.h < 18 ? 'tarde' : 'noche';
  const rain = h.rain >= 50 ? `, ${h.rain}% lluvia` : '';
  return `${hh}:00 · ${Math.round(h.temp)}°C · ${wxLabel(h.wcode, h.h >= 6 && h.h < 18)}${rain}`;
}

module.exports = { normalizeWeatherInterval, wxLabel, hourlyLabel };
