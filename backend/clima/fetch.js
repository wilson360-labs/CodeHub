// ─────────────────────────────────────────────
//  Clima — fetch de datos (Open-Meteo)
//  CodeHub by Wilson.E
// ─────────────────────────────────────────────

// Consulta el estado actual + pronóstico a Open-Meteo y devuelve un
// objeto "current" enriquecido con slots del día, horizonte horario y
// el índice UV REAL de la hora actual (uv_current), para poder detectar
// cambios a lo largo del día (estilo Samsung Weather / Windows Weather).
async function fetchWeatherFor(lat, lon) {
  // timeformat=unixtime: las horas llegan en epoch UTC, sin ambigüedad de zona
  // horaria. Antes se pedía timezone=auto y se parseaba "2026-10-22T16:00" con
  // 'Z' (UTC) → el "ahora" quedaba desfasado por el offset local (p. ej. 6-7 h
  // en Guatemala): UV, probabilidad de lluvia y horizonte salían de OTRA hora.
  const url = 'https://api.open-meteo.com/v1/forecast?latitude=' + lat + '&longitude=' + lon +
    '&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_gusts_10m,surface_pressure,precipitation,is_day' +
    '&minutely_15=precipitation,weather_code' +
    '&hourly=temperature_2m,precipitation_probability,precipitation,uv_index,weather_code,wind_speed_10m,wind_gusts_10m,cape' +
    '&daily=sunrise,sunset,weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max' +
    '&forecast_days=3&forecast_minutely_15=12&past_minutely_15=1&wind_speed_unit=kmh&timezone=auto&timeformat=unixtime';
  const r = await fetch(url, { signal: AbortSignal.timeout(10000) });
  if (!r.ok) throw new Error('open-meteo ' + r.status);
  const data = await r.json();
  const hourly = data.hourly || {};
  const daily = data.daily || {};
  const current = data.current || {};
  const m15 = data.minutely_15 || {};
  const offsetMs = (Number(data.utc_offset_seconds) || 0) * 1000;
  const nowMs = Date.now();
  // Hora local del usuario (0-23) a partir del epoch UTC + offset de su zona
  const localHour = ms => new Date(ms + offsetMs).getUTCHours();

  // Tiempos en epoch ms (la API devuelve segundos)
  const times = (hourly.time || []).map(t => Number(t) * 1000);
  const rainProbArr = (hourly.precipitation_probability || []).map(v => Number(v || 0));
  const precipArr = (hourly.precipitation || []).map(v => Number(v || 0));
  const weatherCodeArr = hourly.weather_code || [];
  const tempArr = (hourly.temperature_2m || []).map(v => Number(v || 0));
  const windArr = (hourly.wind_speed_10m || []).map(v => Number(v || 0));
  const gustArr = (hourly.wind_gusts_10m || []).map(v => Number(v || 0));
  const capeArr = (hourly.cape || []).map(v => Number(v || 0));
  const uvArr = (hourly.uv_index || []).map(v => Number(v || 0));

  // Índice de la hora en curso (último bucket cuyo inicio ≤ ahora)
  let nowIdx = -1;
  for (let i = 0; i < times.length; i++) { if (times[i] <= nowMs) nowIdx = i; else break; }
  if (nowIdx < 0) nowIdx = 0;

  // Horizonte: desde la próxima hora hasta las 02:00 locales del día siguiente
  const horizon = [];
  const localNow = new Date(nowMs + offsetMs);
  const untilLocal = Date.UTC(localNow.getUTCFullYear(), localNow.getUTCMonth(), localNow.getUTCDate() + 1, 2, 0, 0);
  const untilMs = untilLocal - offsetMs;
  for (let i = nowIdx + 1; i < times.length; i++) {
    if (times[i] > untilMs) break;
    horizon.push({
      time: times[i],
      h: localHour(times[i]),
      temp: tempArr[i],
      wcode: weatherCodeArr[i] != null ? weatherCodeArr[i] : current.weather_code,
      rain: rainProbArr[i] != null ? rainProbArr[i] : 0,
      wind: windArr[i] != null ? windArr[i] : 0,
      uv: uvArr[i] != null ? uvArr[i] : 0,
    });
  }

  // ── Slots del día (mañana / tarde / noche) ──
  const slot = { morning: [], afternoon: [], evening: [], night: [] };
  for (const h of horizon) {
    if (h.h >= 5 && h.h < 12) slot.morning.push(h);
    else if (h.h >= 12 && h.h < 18) slot.afternoon.push(h);
    else if (h.h >= 18 && h.h < 23) slot.evening.push(h);
    else slot.night.push(h);
  }
  function slotSummary(list) {
    if (!list.length) return null;
    return {
      tMax: Math.max(...list.map(x => x.temp)),
      tMin: Math.min(...list.map(x => x.temp)),
      maxRainPct: Math.max(...list.map(x => x.rain)),
      wcode: Math.max(...list.map(x => x.wcode)),
      windMax: Math.max(...list.map(x => x.wind)),
    };
  }
  const slots = {
    morning: slotSummary(slot.morning),
    afternoon: slotSummary(slot.afternoon),
    evening: slotSummary(slot.evening),
    night: slotSummary(slot.night),
  };

  const sunriseMs = (daily.sunrise || [])[0] != null ? Number(daily.sunrise[0]) * 1000 : null;
  const sunsetMs = (daily.sunset || [])[0] != null ? Number(daily.sunset[0]) * 1000 : null;
  const isDay = Number(current.is_day) === 1;

  // Valores de la hora EN CURSO (no del máximo del día, no de otra hora)
  const rainProbCurrent = rainProbArr[nowIdx] != null ? rainProbArr[nowIdx] : 0;
  const uvCurrent = uvArr[nowIdx] != null ? uvArr[nowIdx] : Number(current.uv_index || 0);
  const capeNow = capeArr[nowIdx] != null ? capeArr[nowIdx] : 0;

  // Ráfaga máxima prevista en las próximas 3 h
  const gustNext3h = Math.max(Number(current.wind_gusts_10m || 0),
    ...gustArr.slice(nowIdx, nowIdx + 4));

  // ── NOWCAST (15 min): ¿llueve ya? ¿en cuántos minutos empieza? ──
  // precipitation de cada bucket = mm acumulados en esos 15 min.
  const m15t = (m15.time || []).map(t => Number(t) * 1000);
  const m15p = (m15.precipitation || []).map(v => Number(v || 0));
  const RAIN_BUCKET_MM = 0.2; // ≥0.2 mm/15 min ≈ lluvia real
  let rainInMin = null;       // null = sin lluvia en las próximas 3 h
  let rainRateMmH = 0;        // intensidad pico próxima (mm/h)
  let raining = false;
  for (let i = 0; i < m15t.length; i++) {
    const start = m15t[i], end = start + 15 * 60 * 1000;
    if (end <= nowMs) continue;                 // bucket ya pasado
    if (end - nowMs > 3 * 3600 * 1000) break;
    const mm = m15p[i];
    if (mm >= RAIN_BUCKET_MM) {
      const inMin = Math.max(0, Math.round((start - nowMs) / 60000));
      if (rainInMin == null) { rainInMin = inMin; raining = start <= nowMs; }
      rainRateMmH = Math.max(rainRateMmH, mm * 4);
    }
  }
  if (Number(current.precipitation || 0) >= 0.2) { raining = true; rainInMin = 0; }

  return {
    ...current,
    is_day: isDay,
    utc_offset_ms: offsetMs,
    local_hour: localHour(nowMs),
    sunrise: sunriseMs,              // epoch ms (antes: string ISO local)
    sunset: sunsetMs,
    sunrise_hour: sunriseMs != null ? localHour(sunriseMs) : null,
    sunset_hour: sunsetMs != null ? localHour(sunsetMs) : null,
    precipitation_probability: rainProbCurrent,
    uv_current: uvCurrent,           // valor real de la hora actual
    uv_index: uvArr.length ? Math.max(...uvArr) : 0, // máximo del día (reserva)
    cape: capeNow,
    gust_next3h: gustNext3h,
    raining,
    rain_in_min: rainInMin,          // minutos hasta que empiece (0 = ya llueve)
    rain_rate_mmh: rainRateMmH,
    slots,
    horizon,
  };
}

module.exports = { fetchWeatherFor };
