// ─────────────────────────────────────────────
//  Clima — alertas + detección de CAMBIOS reales
//  CodeHub by Wilson.E
//  Inteligente al estilo Samsung Weather / Windows Weather:
//  la radiación UV se detecta POR HORA (sube al mediodía, baja al
//  atardecer) y solo se notifica cuando cambia de nivel, no todo el día.
// ─────────────────────────────────────────────

const { wxLabel, normalizeWeatherInterval } = require('./wx');

// Categorías de alerta rápida (umbrales). "critical" marca transiciones
// que se notifican de inmediato (inicio de lluvia/tormenta/viento fuerte).
const WX_ALERTS = [
  { cond: 'storm', critical: true, test: c => c.weather_code >= 95 || (c.precipitation > 8 && c.wind_speed_10m > 35),
    msg: c => '⛈️ Tormenta eléctrica en tu zona — evita zonas abiertas, desconecta aparatos y no te refugies bajo árboles' },
  { cond: 'rain',  critical: true, test: c => (c.weather_code >= 61 && c.weather_code <= 67) || Number(c.precipitation_probability || 0) >= 70,
    msg: c => '🌧️ Probabilidad alta de lluvia (' + Math.round(Number(c.precipitation_probability || 0)) + '%) — lleva paraguas y revisa el pronóstico por horas antes de salir' },
  { cond: 'wind',  critical: true, test: c => c.wind_speed_10m > 50,
    msg: c => '💨 Viento fuerte (' + Math.round(c.wind_speed_10m) + ' km/h) — precaución al manejar y asegura objetos sueltos' },
  { cond: 'radiation', critical: false, test: c => c.is_day && Number(c.uv_current || 0) >= 7,
    msg: c => '☀️ Radiación UV alta (' + Number(c.uv_current || 0).toFixed(1) + ') — usa bloqueador SPF 50+ y evita el sol de 11 a 15h' },
  { cond: 'heat',  critical: false, test: c => c.temperature_2m > 33 || c.apparent_temperature > 38,
    msg: c => '🌡️ Calor (' + Math.round(c.temperature_2m) + '°C, sensación ' + Math.round(c.apparent_temperature) + '°C) — hidrátate y evita el sol de 11 a 15h' },
  { cond: 'cold',  critical: false, test: c => c.temperature_2m < 0,
    msg: c => '🥶 Frío (' + Math.round(c.temperature_2m) + '°C) — abrígate bien' },
];

// Umbrales de nivel de radiación UV (como las apps de clima inteligentes):
// bajo <3, moderado 3-5, alto 6-7, muy alto 8-10, extremo 11+.
function uvLevel(v) {
  const n = Number(v || 0);
  if (n >= 11) return 'extremo';
  if (n >= 8)  return 'muy alto';
  if (n >= 6)  return 'alto';
  if (n >= 3)  return 'moderado';
  return 'bajo';
}

// Elige la alerta activa actual (máxima prioridad que se cumpla).
function detectAlert(current) {
  for (const a of WX_ALERTS) {
    try { if (a.test(current)) return { cond: a.cond, critical: !!a.critical, body: a.msg(current) }; } catch (e) {}
  }
  return null;
}

// ── Snapshot comparativo del clima ───────────────────────────
// Resume el estado actual en valores que permiten detectar CAMBIOS
// reales entre pasadas, en vez de repetir estados fijos.
function weatherSnapshot(current) {
  return {
    wcode: current.weather_code != null ? Number(current.weather_code) : -1,
    temp: current.temperature_2m != null ? Math.round(Number(current.temperature_2m)) : null,
    rainPct: current.precipitation_probability != null ? Math.round(Number(current.precipitation_probability)) : null,
    wind: current.wind_speed_10m != null ? Math.round(Number(current.wind_speed_10m)) : null,
    uv: current.uv_current != null ? Number(current.uv_current) : (current.is_day ? 0 : null),
    uvLevel: current.uv_current != null ? uvLevel(current.uv_current) : null,
    isDay: !!(current.is_day),
  };
}

// Umbrales de cambio "notable" que justifican una notificación.
const WX_CHANGE_THRESHOLDS = {
  wcode:   1,    // cambió el tipo de cielo/condición
  temp:    3,    // ±3°C respecto a la última vez que avisamos
  rainPct: 20,   // ±20 puntos en la probabilidad de lluvia
  wind:    15,   // ±15 km/h de viento
  uv:      2,    // ±2 en índice UV actual
  isDay:   0,    // cambio día/noche
};

// Genera la lista de cambios notables entre el estado previo y el actual.
// Devuelve array de strings legibles (p.ej. "temperatura 24→27°C").
function diffWeatherChange(prev, cur) {
  if (!prev) return null;
  const parts = [];
  const T = WX_CHANGE_THRESHOLDS;

  if (cur.isDay !== prev.isDay) {
    parts.push(cur.isDay ? '🌞 amaneció' : '🌙 anocheció');
  }
  if (cur.wcode !== prev.wcode && Math.abs(cur.wcode - prev.wcode) >= T.wcode) {
    if (wxLabel(cur.wcode, cur.isDay) !== wxLabel(prev.wcode, prev.isDay)) {
      parts.push('☁️ ' + wxLabel(prev.wcode, prev.isDay) + ' → ' + wxLabel(cur.wcode, cur.isDay));
    }
  }
  if (cur.temp != null && prev.temp != null) {
    const d = cur.temp - prev.temp;
    if (Math.abs(d) >= T.temp) parts.push('🌡️ ' + prev.temp + '→' + cur.temp + '°C ' + (d > 0 ? '↑' : '↓'));
  }
  if (cur.rainPct != null && prev.rainPct != null) {
    const d = cur.rainPct - prev.rainPct;
    if (Math.abs(d) >= T.rainPct) parts.push('🌧️ lluvia ' + prev.rainPct + '→' + cur.rainPct + '% ' + (d > 0 ? '↑' : '↓'));
  }
  if (cur.wind != null && prev.wind != null) {
    const d = cur.wind - prev.wind;
    if (Math.abs(d) >= T.wind) parts.push('💨 viento ' + prev.wind + '→' + cur.wind + ' km/h ' + (d > 0 ? '↑' : '↓'));
  }
  // Radiación UV: solo cuando cambia de NIVEL (bajo→alto etc.) o sube/baja
  // ≥2 en el índice, para no repetir "UV alto" cada 30 min durante todo el día.
  if (cur.uv != null && prev.uv != null) {
    const d = cur.uv - prev.uv;
    if (cur.uvLevel && prev.uvLevel && cur.uvLevel !== prev.uvLevel && Math.abs(d) >= 0.5) {
      parts.push('☀️ UV ' + prev.uv.toFixed(1) + '(' + prev.uvLevel + ')' + ' → ' + cur.uv.toFixed(1) + '(' + cur.uvLevel + ')');
    } else if (Math.abs(d) >= T.uv && (cur.uv >= 7 || prev.uv >= 7)) {
      parts.push('☀️ UV ' + prev.uv.toFixed(1) + '→' + cur.uv.toFixed(1));
    }
  }
  return parts.length ? parts : null;
}

// ── Resumen diario inteligente del clima ────────────────────
function dailyBriefing(current) {
  const slots = current.slots;
  const nowH = new Date().getHours();
  const parts = [];

  const period = nowH < 6 ? 'madrugada' : nowH < 12 ? 'mañana' : nowH < 18 ? 'tarde' : 'noche';
  const dayPart = current.is_day ? 'de día' : 'de noche';
  parts.push(`Ahora (${period}, ${dayPart}): ${Math.round(current.temperature_2m)}°C · ${wxLabel(current.weather_code, current.is_day)}`);

  const order = [['morning', 'Mañana'], ['afternoon', 'Mediodía / tarde'], ['evening', 'Atardecer / noche'], ['night', 'Madrugada']];
  for (const [key, label] of order) {
    const s = slots[key];
    if (!s) continue;
    const isFuture = (key === 'morning' && nowH < 5) || (key === 'afternoon' && nowH < 12) ||
                     (key === 'evening' && nowH < 18) || (key === 'night');
    if (!isFuture) continue;
    let line = `• ${label}: ${Math.round(s.tMax)}°/${Math.round(s.tMin)}° · ${wxLabel(s.wcode, key !== 'night')}`;
    if (s.maxRainPct >= 50) line += ` · ${Math.round(s.maxRainPct)}% lluvia`;
    if (s.windMax > 40) line += ` · viento ${Math.round(s.windMax)} km/h`;
    parts.push(line);
  }

  // Detalle horario resumido (próximas 4-6 horas)
  if (current.horizon && current.horizon.length) {
    const nextHours = current.horizon.slice(0, Math.min(6, current.horizon.length));
    const timelines = nextHours
      .map(h => {
        const hh = String(h.h).padStart(2, '0');
        return `${hh}:00 · ${Math.round(h.temp)}°C · ${wxLabel(h.wcode, h.h >= 6 && h.h < 18)}${h.rain >= 50 ? `, ${h.rain}% lluvia` : ''}`;
      })
      .join('\n');
    parts.push('Próximas horas:\n' + timelines);
  }

  // Amanecer / atardecer
  if (current.sunrise && current.sunset) {
    parts.push(`🌅 Amanecer ${String(new Date(current.sunrise + 'Z').getHours()).padStart(2, '0')}:00 · 🌇 Atardecer ${String(new Date(current.sunset + 'Z').getHours()).padStart(2, '0')}:00`);
  }

  return parts.join('\n');
}

module.exports = {
  WX_ALERTS,
  detectAlert,
  weatherSnapshot,
  diffWeatherChange,
  dailyBriefing,
  uvLevel,
  normalizeWeatherInterval,
};
