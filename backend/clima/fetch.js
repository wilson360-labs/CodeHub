// ─────────────────────────────────────────────
//  Clima — fetch de datos (Open-Meteo)
//  CodeHub by Wilson.E
// ─────────────────────────────────────────────

// Consulta el estado actual + pronóstico a Open-Meteo y devuelve un
// objeto "current" enriquecido con slots del día, horizonte horario y
// el índice UV REAL de la hora actual (uv_current), para poder detectar
// cambios a lo largo del día (estilo Samsung Weather / Windows Weather).
async function fetchWeatherFor(lat, lon) {
  const url = 'https://api.open-meteo.com/v1/forecast?latitude=' + lat + '&longitude=' + lon +
    '&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_gusts_10m,surface_pressure,precipitation,is_day' +
    '&hourly=temperature_2m,precipitation_probability,uv_index,weather_code,wind_speed_10m' +
    '&daily=sunrise,sunset,weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max' +
    '&forecast_days=3&wind_speed_unit=kmh&timezone=auto';
  const r = await fetch(url);
  if (!r.ok) throw new Error('open-meteo ' + r.status);
  const data = await r.json();
  const hourly = data.hourly || {};
  const daily = data.daily || {};
  const current = data.current || {};
  const now = new Date();

  // ── Serie horaria completa para el resumen del día ──
  const times = hourly.time || [];
  const rainProbArr = (hourly.precipitation_probability || []).map(v => Number(v || 0));
  const weatherCodeArr = hourly.weather_code || [];
  const tempArr = (hourly.temperature_2m || []).map(v => Number(v || 0));
  const windArr = (hourly.wind_speed_10m || []).map(v => Number(v || 0));
  const uvArr = (hourly.uv_index || []).map(v => Number(v || 0));

  // Tomar solo las horas desde ahora hasta las 2:00 del día siguiente
  // (cubren: ahora, resto del día, y la madrugada/noche venidera).
  const horizon = [];
  for (let i = 0; i < times.length; i++) {
    const t = new Date(times[i] + 'Z');
    const until = new Date(now.getTime());
    until.setDate(until.getDate() + 1);
    until.setHours(2, 0, 0, 0);
    if (t <= now) continue;
    if (t > until) break;
    horizon.push({
      time: times[i],
      h: t.getHours(),
      temp: tempArr[i],
      wcode: weatherCodeArr[i] != null ? weatherCodeArr[i] : current.weather_code,
      rain: rainProbArr[i] != null ? rainProbArr[i] : 0,
      wind: windArr[i] != null ? windArr[i] : 0,
      uv: uvArr[i] != null ? uvArr[i] : 0,
    });
  }

  // ── Slots del día (mañana / tarde / noche) con la media de cada uno ──
  const slot = { morning: [], afternoon: [], evening: [], night: [] };
  for (const h of horizon) {
    if (h.h >= 5 && h.h < 12) slot.morning.push(h);
    else if (h.h >= 12 && h.h < 18) slot.afternoon.push(h);
    else if (h.h >= 18 && h.h < 23) slot.evening.push(h);
    else slot.night.push(h);
  }
  function slotSummary(list) {
    if (!list.length) return null;
    const maxRain = Math.max(...list.map(x => x.rain));
    const maxW = Math.max(...list.map(x => x.wcode));
    const maxWind = Math.max(...list.map(x => x.wind));
    return {
      tMax: Math.max(...list.map(x => x.temp)),
      tMin: Math.min(...list.map(x => x.temp)),
      maxRainPct: maxRain,
      wcode: maxW,
      windMax: maxWind,
    };
  }
  const slots = {
    morning: slotSummary(slot.morning),
    afternoon: slotSummary(slot.afternoon),
    evening: slotSummary(slot.evening),
    night: slotSummary(slot.night),
  };

  // Solar: sunrise/sunset de hoy para "día/noche"
  const sunriseStr = (daily.sunrise || [])[0];
  const sunsetStr = (daily.sunset || [])[0];
  const isDay = Number(current.is_day) === 1;

  const rainProbCurrent = (() => {
    const idx = times.findIndex(t => new Date(t + 'Z').getTime() >= now.getTime());
    if (idx >= 0 && rainProbArr[idx] != null) return rainProbArr[idx];
    return rainProbArr.length ? Math.max(...rainProbArr) : (Number(current.precipitation || 0) > 0 ? 70 : 0);
  })();

  // Índice UV REAL de la hora actual (no el máximo del día): así la
  // radiación sube al mediodía y baja al atardecer como en el clima real.
  const uvCurrent = (() => {
    const idx = times.findIndex(t => new Date(t + 'Z').getTime() >= now.getTime());
    if (idx >= 0 && uvArr[idx] != null) return uvArr[idx];
    return Number(current.uv_index || 0);
  })();

  return {
    ...current,
    is_day: isDay,
    sunrise: sunriseStr || null,
    sunset: sunsetStr || null,
    precipitation_probability: rainProbCurrent,
    uv_current: uvCurrent,           // valor real de la hora actual
    uv_index: uvArr.length ? Math.max(...uvArr) : 0, // máximo del día (reserva)
    slots,
    horizon,
  };
}

module.exports = { fetchWeatherFor };
