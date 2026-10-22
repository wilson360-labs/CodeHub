// ─────────────────────────────────────────────────────────────
//  Sismos — motor de alertas en TIEMPO REAL por ubicación
//  CodeHub by Wilson.E
//
//  Antes: sondeo a USGS all_day cada 10 min + radio fijo de 300 km o
//  "todo el país" + sin límite de antigüedad + FCM con TTL de 4 semanas
//  → alertas tardías (incluso de un día antes) y poco precisas.
//
//  Ahora:
//   · EMSC SeismicPortal por WebSocket (push, sin sondeo) + USGS all_hour
//     cada 20 s (+ EMSC FDSN como respaldo si el WebSocket cae).
//   · Fusión de eventos entre agencias (mismo sismo ≠ dos alertas).
//   · Relevancia por física: distancia hipocentral, intensidad estimada
//     y radio máximo recomendado según magnitud (no por país).
//   · Cuenta regresiva de la onda S (tiempo estimado de llegada).
//   · NUNCA alerta de un sismo con más de MAX_EVENT_AGE_MS de antigüedad.
//   · Envío al instante, primero a los dispositivos más cercanos,
//     FCM data-only de alta prioridad con TTL corto (si no llega en
//     minutos, se descarta en vez de entregarse tarde).
//
//  Uso desde server.js:
//    const sismos = require('./sismos')({ sendPush, sendFCM, fcmEnabled,
//                                         fcmListTokens, pushList });
//    sismos.start();
// ─────────────────────────────────────────────────────────────

const WebSocket = require('ws');

// ── Parámetros ajustables ────────────────────────────────────
const CFG = {
  EMSC_WS: 'wss://www.seismicportal.eu/standing_order/websocket',
  EMSC_FDSN: 'https://www.seismicportal.eu/fdsnws/event/1/query',
  USGS_HOUR: 'https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_hour.geojson',
  USGS_DAY: 'https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/all_day.geojson',

  USGS_POLL_MS: Number(process.env.SEISMIC_USGS_POLL_MS) || 20 * 1000,
  EMSC_FALLBACK_POLL_MS: 45 * 1000,
  DEVICE_REFRESH_MS: 45 * 1000,

  INGEST_MIN_MAG: 2.5,                      // se ignora todo bajo esto
  DEFAULT_MIN_MAG: 3.5,                     // umbral por defecto del usuario
  LEGACY_DEFAULT_MAG: 4.5,                  // valor que antes se guardaba por defecto
  MAX_EVENT_AGE_MS: 30 * 60 * 1000,         // jamás alertar de sismos más viejos
  RE_ALERT_MAG_DELTA: 0.5,                  // re-avisar si la magnitud sube ≥ esto
  ALERT_TTL_SEC: 180,                       // si FCM no entrega en 3 min, se descarta
  MIN_FELT_MMI: 3.0,                        // intensidad mínima "perceptible"
  ALARM_MMI: 5.0,                           // desde aquí: pantalla completa

  VS_KMS: 3.5,                              // velocidad onda S (km/s)
  VP_KMS: 6.0,                              // velocidad onda P (km/s)
  S_ETA_SAFETY_S: 1,                        // margen de proceso al mostrar la cuenta
  SEND_CHUNK: 100,
};

// Radio MÁXIMO recomendable de alerta según magnitud (km). Techo duro:
// aunque el usuario pida más, más allá de esto el sismo no se siente.
function maxRadiusKm(mag) {
  if (mag >= 7.5) return 1000;
  if (mag >= 7.0) return 800;
  if (mag >= 6.5) return 600;
  if (mag >= 6.0) return 450;
  if (mag >= 5.5) return 300;
  if (mag >= 5.0) return 200;
  if (mag >= 4.5) return 130;
  if (mag >= 4.0) return 80;
  if (mag >= 3.5) return 45;
  if (mag >= 3.0) return 25;
  return 12;
}

// Intensidad (Mercalli) estimada en el sitio. Relación empírica
// simplificada: sirve para CLASIFICAR (¿se siente? ¿es fuerte?), no
// reemplaza a ShakeMap. Constantes agrupadas para poder calibrarlas.
function estimateMMI(mag, rhypKm) {
  const r = Math.max(rhypKm, 5);
  const mmi = 2.0 * mag - 3.6 * Math.log10(r) - 0.003 * r + 1.2;
  return Math.max(1, Math.min(9, mmi));
}

const ROMAN = ['', 'I', 'II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX'];
function mmiRoman(mmi) { return ROMAN[Math.max(1, Math.min(9, Math.round(mmi)))]; }

function haversineKm(lat1, lon1, lat2, lon2) {
  const R = 6371;
  const dLat = (lat2 - lat1) * Math.PI / 180;
  const dLon = (lon2 - lon1) * Math.PI / 180;
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) * Math.sin(dLon / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}

// Rumbo desde el usuario hacia el epicentro (N, NE, E…)
function bearingDir(lat1, lon1, lat2, lon2) {
  const toRad = d => d * Math.PI / 180;
  const y = Math.sin(toRad(lon2 - lon1)) * Math.cos(toRad(lat2));
  const x = Math.cos(toRad(lat1)) * Math.sin(toRad(lat2)) -
    Math.sin(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.cos(toRad(lon2 - lon1));
  const deg = (Math.atan2(y, x) * 180 / Math.PI + 360) % 360;
  return ['N', 'NE', 'E', 'SE', 'S', 'SO', 'O', 'NO'][Math.round(deg / 45) % 8];
}

function validCoords(lat, lon) {
  lat = Number(lat); lon = Number(lon);
  if (!Number.isFinite(lat) || !Number.isFinite(lon)) return false;
  if (Math.abs(lat) > 90 || Math.abs(lon) > 180) return false;
  if (lat === 0 && lon === 0) return false; // "sin ubicación" que manda la app
  return true;
}

// Evalúa un evento contra un punto. Función pura → testeable.
function assessEvent(ev, lat, lon, opts) {
  const o = opts || {};
  const depth = Number.isFinite(ev.depth) ? Math.max(0, ev.depth) : 10;
  const epi = haversineKm(lat, lon, ev.lat, ev.lon);
  const rhyp = Math.sqrt(epi * epi + depth * depth);
  const mmi = estimateMMI(ev.mag, rhyp);
  const cap = Math.min(maxRadiusKm(ev.mag), o.maxRadiusKm || Infinity);
  const minMag = o.minMag || CFG.DEFAULT_MIN_MAG;
  const relevant = ev.mag >= minMag && epi <= cap && mmi >= CFG.MIN_FELT_MMI;
  const level = !relevant ? 'none' : (mmi >= CFG.ALARM_MMI ? 'alarm' : 'info');
  const originMs = ev.time;
  const sArrival = originMs + (rhyp / CFG.VS_KMS) * 1000;
  const pArrival = originMs + (rhyp / CFG.VP_KMS) * 1000;
  return {
    relevant, level, epiKm: epi, rhypKm: rhyp, mmi,
    dir: bearingDir(lat, lon, ev.lat, ev.lon),
    sArrival, pArrival,
  };
}

// Mínimo de magnitud efectivo del usuario (migra el antiguo 4.5 por defecto)
function effectiveMinMag(v) {
  const n = Number(v);
  if (!Number.isFinite(n) || n <= 0) return CFG.DEFAULT_MIN_MAG;
  if (n === CFG.LEGACY_DEFAULT_MAG) return CFG.DEFAULT_MIN_MAG;
  return Math.max(CFG.INGEST_MIN_MAG, n);
}

// ── Normalización de fuentes ─────────────────────────────────
function fromUsgsFeature(f) {
  try {
    const p = f.properties || {}, g = (f.geometry || {}).coordinates || [];
    if (typeof p.mag !== 'number' || !p.time || g.length < 2) return null;
    return {
      src: 'usgs', srcId: f.id || p.code || String(p.time),
      mag: p.mag, lat: g[1], lon: g[0], depth: Number.isFinite(g[2]) ? g[2] : 10,
      time: p.time, place: p.place || '', updated: p.updated || p.time,
      url: p.url || '', tsunami: !!p.tsunami,
    };
  } catch (e) { return null; }
}

function fromEmscFeature(f) {
  try {
    const p = f.properties || {}, g = (f.geometry || {}).coordinates || [];
    const mag = Number(p.mag);
    const time = Date.parse(p.time);
    const lat = Number.isFinite(+p.lat) ? +p.lat : g[1];
    const lon = Number.isFinite(+p.lon) ? +p.lon : g[0];
    if (!Number.isFinite(mag) || !Number.isFinite(time) || !Number.isFinite(lat) || !Number.isFinite(lon)) return null;
    const depth = Number.isFinite(+p.depth) ? Math.abs(+p.depth) : (Number.isFinite(g[2]) ? Math.abs(g[2]) : 10);
    return {
      src: 'emsc', srcId: p.unid || f.id || String(time),
      mag, lat, lon, depth, time,
      place: p.flynn_region ? titleCase(p.flynn_region) : '',
      updated: p.lastupdate ? Date.parse(p.lastupdate) || time : time,
      url: p.unid ? 'https://www.emsc-csem.org/Earthquake_information/earthquake.php?id=' + p.unid : '',
    };
  } catch (e) { return null; }
}

function titleCase(s) {
  return String(s).toLowerCase().replace(/\b\w/g, c => c.toUpperCase());
}

function compactSafety(level, arrivedS) {
  if (level === 'alarm') {
    return arrivedS
      ? 'Agáchate, cúbrete y sujétate bajo un mueble firme. Aléjate de ventanas. Espera réplicas.'
      : 'Agáchate, cúbrete y sujétate. Aléjate de ventanas y objetos que caigan.';
  }
  return 'Sismo perceptible. Mantén la calma y asegura objetos que puedan caer.';
}

module.exports = function createSeismicEngine(deps) {
  const {
    sendPush = async () => ({ ok: false }),
    sendFCM = async () => ({ ok: false }),
    fcmEnabled = false,
    fcmListTokens = async () => [],
    pushList = async () => [],
    log = console,
  } = deps || {};

  // ── Estado ──
  let events = [];                       // eventos fusionados (últimas 6 h)
  let devices = [];                      // caché de dispositivos
  let devicesAt = 0;
  const notified = new Map();            // `${deviceId}|${evKey}` → { mag, level, at }
  const stats = {
    startedAt: Date.now(), emscConnected: false, emscLastMsgAt: 0, emscReconnects: 0,
    usgsLastOkAt: 0, usgsErrors: 0, lastEventAt: 0, lastLagMs: null, lastDispatchMs: null,
    alertsSent: 0, devices: 0,
  };
  let ws = null, wsTimer = null, wsWatchdog = null, wsBackoff = 1000;
  let timers = [];
  let stopped = false;
  let emscFallbackTimer = null;

  // ── Dispositivos ──
  async function refreshDevices(force) {
    if (!force && Date.now() - devicesAt < CFG.DEVICE_REFRESH_MS && devices.length) return devices;
    const out = [];
    try {
      const subs = await pushList();
      for (const s of subs || []) {
        if (!s || s.alerts === false || s.seismic_alerts === false) continue;
        if (!validCoords(s.lat, s.lon)) continue;
        out.push({
          id: 'w:' + s.endpoint, kind: 'web', rec: s,
          lat: +s.lat, lon: +s.lon,
          minMag: effectiveMinMag(s.seismic_mag),
          maxRadiusKm: Number(s.seismic_radius) > 0 ? Number(s.seismic_radius) : null,
          city: s.city || '',
        });
      }
    } catch (e) { log.warn('⚠️  Sismos: pushList falló —', e.message); }
    if (fcmEnabled) {
      try {
        for (const t of await fcmListTokens() || []) {
          if (!t || !t.token || t.seismic_alerts === false) continue;
          if (!validCoords(t.lat, t.lon)) continue;
          out.push({
            id: 'f:' + t.token, kind: 'fcm', rec: t,
            lat: +t.lat, lon: +t.lon,
            minMag: effectiveMinMag(t.seismic_mag),
            maxRadiusKm: Number(t.seismic_radius) > 0 ? Number(t.seismic_radius) : null,
            city: t.city || '',
          });
        }
      } catch (e) { log.warn('⚠️  Sismos: fcmListTokens falló —', e.message); }
    }
    // Si la BD falla y ya teníamos caché, conservarla (no quedarse sin alertas)
    if (out.length || !devices.length) devices = out;
    devicesAt = Date.now();
    stats.devices = devices.length;
    return devices;
  }

  // ── Fusión de eventos entre agencias ──
  function findMatch(ev) {
    for (const e of events) {
      if (Math.abs(e.time - ev.time) <= 120 * 1000 &&
          haversineKm(e.lat, e.lon, ev.lat, ev.lon) <= 150) return e;
    }
    return null;
  }

  // Devuelve el evento fusionado y si hubo cambio relevante
  function merge(ev) {
    const now = Date.now();
    events = events.filter(e => now - e.time < 6 * 3600 * 1000);
    const m = findMatch(ev);
    if (!m) {
      const created = { ...ev, key: 'EQ' + Math.floor(ev.time / 60000) + '_' + ev.srcId, sources: [ev.src], firstSeenAt: now, srcIds: { [ev.src]: ev.srcId } };
      events.push(created);
      return { event: created, isNew: true };
    }
    const before = m.mag;
    // El dato más reciente de cada fuente gana; USGS/EMSC refinan magnitud/posición.
    if ((ev.updated || 0) >= (m.updated || 0) || ev.src === 'usgs') {
      m.mag = ev.mag; m.lat = ev.lat; m.lon = ev.lon; m.depth = ev.depth;
      m.time = ev.time; m.updated = ev.updated || m.updated;
      if (ev.place) m.place = ev.place;
      if (ev.url && (!m.url || ev.src === 'usgs')) m.url = ev.url;
    }
    if (!m.sources.includes(ev.src)) m.sources.push(ev.src);
    m.srcIds[ev.src] = ev.srcId;
    return { event: m, isNew: false, magChanged: m.mag !== before };
  }

  // ── Ingesta de un evento (cualquier fuente) ──
  async function ingest(ev, via) {
    if (!ev || !Number.isFinite(ev.mag) || ev.mag < CFG.INGEST_MIN_MAG) return;
    const age = Date.now() - ev.time;
    // Anti-"alerta de ayer": jamás procesar sismos viejos. Se siguen
    // fusionando para el listado, pero no disparan avisos.
    const { event } = merge(ev);
    stats.lastEventAt = Date.now();
    stats.lastLagMs = age;
    if (Date.now() - event.time > CFG.MAX_EVENT_AGE_MS) return;
    try { await dispatch(event, via); }
    catch (e) { log.warn('⚠️  Sismos: dispatch falló —', e.message); }
  }

  // ── Selección de destinatarios y envío ──
  async function dispatch(event, via) {
    const t0 = Date.now();
    await refreshDevices(false);
    const targets = [];
    for (const d of devices) {
      const a = assessEvent(event, d.lat, d.lon, { minMag: d.minMag, maxRadiusKm: d.maxRadiusKm });
      if (!a.relevant) continue;
      const k = d.id + '|' + event.key;
      const prev = notified.get(k);
      if (prev) {
        const magUp = event.mag - prev.mag >= CFG.RE_ALERT_MAG_DELTA;
        const escalated = prev.level === 'info' && a.level === 'alarm';
        if (!magUp && !escalated) continue;
      }
      targets.push({ d, a, k });
    }
    if (!targets.length) return;
    // Más cercanos primero: son los que tienen menos tiempo
    targets.sort((x, y) => x.a.sArrival - y.a.sArrival);

    // Marcar ANTES de enviar: evita duplicados si llegan dos fuentes casi a la vez
    for (const t of targets) notified.set(t.k, { mag: event.mag, level: t.a.level, at: Date.now() });

    let ok = 0;
    for (let i = 0; i < targets.length; i += CFG.SEND_CHUNK) {
      const chunk = targets.slice(i, i + CFG.SEND_CHUNK);
      const res = await Promise.allSettled(chunk.map(t => sendOne(event, t.d, t.a)));
      res.forEach((r, idx) => {
        if (r.status === 'fulfilled' && r.value && r.value.ok) ok++;
        else notified.delete(chunk[idx].k); // permitir reintento en la próxima actualización
      });
    }
    stats.alertsSent += ok;
    stats.lastDispatchMs = Date.now() - t0;
    log.log('🌋 Sismo M' + event.mag.toFixed(1) + ' ' + (event.place || '') + ' [' + via + '] → ' +
      ok + '/' + targets.length + ' avisos en ' + stats.lastDispatchMs + ' ms (detección +' +
      Math.round((t0 - event.time) / 1000) + ' s del origen)');
  }

  function buildMessage(event, d, a) {
    const now = Date.now();
    const sLeft = Math.round((a.sArrival - now) / 1000) - CFG.S_ETA_SAFETY_S;
    const magS = 'M' + event.mag.toFixed(1);
    const distS = Math.round(a.epiKm) + ' km al ' + a.dir;
    const ageS = Math.max(0, Math.round((now - event.time) / 1000));
    const where = event.place ? ' · ' + event.place : '';
    let title, body;
    if (sLeft > 0 && a.level === 'alarm') {
      title = '⚠️ SISMO ' + magS + ' — onda S en ~' + sLeft + ' s';
      body = distS + ' de ti' + where + '\n' + compactSafety('alarm', false);
    } else if (a.level === 'alarm') {
      title = '⚠️ SISMO ' + magS + ' a ' + distS;
      body = 'Intensidad estimada ' + mmiRoman(a.mmi) + ' · hace ' + (ageS < 90 ? ageS + ' s' : Math.round(ageS / 60) + ' min') + where +
        '\n' + compactSafety('alarm', true);
    } else {
      title = '📳 Sismo ' + magS + ' a ' + distS;
      body = 'Intensidad estimada ' + mmiRoman(a.mmi) + ' · hace ' + (ageS < 90 ? ageS + ' s' : Math.round(ageS / 60) + ' min') + where;
    }
    return { title, body, sLeft };
  }

  async function sendOne(event, d, a) {
    const m = buildMessage(event, d, a);
    if (d.kind === 'fcm') {
      return sendFCM(d.rec.token, {
        title: m.title, body: m.body,
        type: a.level === 'alarm' ? 'quake_alert' : 'quake_info',
        url: '/#sismos-section',
        mag: event.mag, dist: Math.round(a.epiKm), dir: a.dir,
        place: event.place || '', time: event.time,
        eta: a.sArrival, depth: Math.round(event.depth), mmi: a.mmi.toFixed(1),
        evId: event.key, alertType: 'earthquake',
        ttl: CFG.ALERT_TTL_SEC, dataOnly: true, priority: 'high',
        collapseKey: event.key,
      });
    }
    return sendPush(d.rec, {
      title: m.title, body: m.body,
      type: 'seismic', icon: '/splash/codehub.png', url: '/#sismos-section',
      tag: 'codehub-seismic-' + event.key, level: a.level,
      ttl: CFG.ALERT_TTL_SEC, urgency: 'high',
    });
  }

  // ── Fuente 1: EMSC WebSocket (push) ──
  function connectEmsc() {
    if (stopped) return;
    try { if (ws) { ws.removeAllListeners(); ws.terminate(); } } catch (e) {}
    try {
      ws = new WebSocket(CFG.EMSC_WS, { handshakeTimeout: 10000 });
    } catch (e) { return scheduleReconnect(); }

    ws.on('open', () => {
      stats.emscConnected = true; stats.emscLastMsgAt = Date.now(); wsBackoff = 1000;
      log.log('🌋 Sismos: EMSC WebSocket conectado');
    });
    ws.on('message', buf => {
      stats.emscLastMsgAt = Date.now();
      try {
        const msg = JSON.parse(buf.toString());
        if (!msg || !msg.data) return;
        const ev = fromEmscFeature(msg.data);
        if (ev) ingest(ev, 'emsc-ws');
      } catch (e) {}
    });
    ws.on('pong', () => { stats.emscLastMsgAt = Date.now(); });
    ws.on('close', () => { stats.emscConnected = false; scheduleReconnect(); });
    ws.on('error', e => { stats.emscConnected = false; log.warn('⚠️  Sismos: EMSC WS —', e.message); });
  }
  function scheduleReconnect() {
    if (stopped) return;
    stats.emscReconnects++;
    clearTimeout(wsTimer);
    wsTimer = setTimeout(connectEmsc, wsBackoff);
    wsBackoff = Math.min(wsBackoff * 2, 30000);
  }

  // ── Fuente 2: USGS (sondeo corto) ──
  async function pollUsgs(feed) {
    try {
      const r = await fetch(feed || CFG.USGS_HOUR, {
        headers: { 'User-Agent': 'CodeHub-Seismic' },
        signal: AbortSignal.timeout(8000),
      });
      if (!r.ok) throw new Error('USGS ' + r.status);
      const data = await r.json();
      stats.usgsLastOkAt = Date.now(); stats.usgsErrors = 0;
      const list = (data.features || []).map(fromUsgsFeature).filter(Boolean)
        .sort((a, b) => a.time - b.time);
      for (const ev of list) await ingest(ev, 'usgs');
      return list.length;
    } catch (e) {
      stats.usgsErrors++;
      if (stats.usgsErrors % 15 === 1) log.warn('⚠️  Sismos: USGS —', e.message);
      return 0;
    }
  }

  // ── Fuente 3: EMSC FDSN (solo si el WebSocket está caído) ──
  async function pollEmscFdsn() {
    if (stats.emscConnected) return 0;
    try {
      const start = new Date(Date.now() - 60 * 60 * 1000).toISOString().slice(0, 19);
      const url = CFG.EMSC_FDSN + '?format=json&orderby=time&limit=100&minmag=' + CFG.INGEST_MIN_MAG + '&start=' + start;
      const r = await fetch(url, { signal: AbortSignal.timeout(8000) });
      if (!r.ok) throw new Error('EMSC ' + r.status);
      const data = await r.json();
      const list = (data.features || []).map(fromEmscFeature).filter(Boolean).sort((a, b) => a.time - b.time);
      for (const ev of list) await ingest(ev, 'emsc-fdsn');
      return list.length;
    } catch (e) { return 0; }
  }

  // ── Ciclo de vida ──
  function start() {
    stopped = false;
    refreshDevices(true).catch(() => {});
    connectEmsc();
    // Vigilante: el WS de EMSC emite eventos global y continuamente;
    // 4 min de silencio = conexión zombi → reconectar.
    wsWatchdog = setInterval(() => {
      if (ws && ws.readyState === WebSocket.OPEN) {
        try { ws.ping(); } catch (e) {}
        if (Date.now() - stats.emscLastMsgAt > 4 * 60 * 1000) {
          log.warn('⚠️  Sismos: EMSC WS sin datos 4 min — reconectando');
          try { ws.terminate(); } catch (e) {}
        }
      }
    }, 30 * 1000);
    // Primer barrido: all_day para recuperar lo ocurrido mientras el
    // servidor dormía (Render free). ingest() descarta lo >30 min.
    pollUsgs(CFG.USGS_DAY).finally(() => {
      timers.push(setInterval(() => { pollUsgs(CFG.USGS_HOUR); }, CFG.USGS_POLL_MS));
    });
    emscFallbackTimer = setInterval(() => { pollEmscFdsn(); }, CFG.EMSC_FALLBACK_POLL_MS);
    timers.push(emscFallbackTimer);
    timers.push(setInterval(() => refreshDevices(true).catch(() => {}), CFG.DEVICE_REFRESH_MS));
    // Limpieza de claves antiguas
    timers.push(setInterval(() => {
      const cut = Date.now() - 6 * 3600 * 1000;
      for (const [k, v] of notified) if (v.at < cut) notified.delete(k);
    }, 30 * 60 * 1000));
    log.log('   Sismos:     ✅ tiempo real (EMSC WS + USGS cada ' + Math.round(CFG.USGS_POLL_MS / 1000) + ' s)');
  }

  function stop() {
    stopped = true;
    clearTimeout(wsTimer); clearInterval(wsWatchdog);
    timers.forEach(clearInterval); timers = [];
    try { if (ws) ws.terminate(); } catch (e) {}
  }

  // Disparo manual (endpoint /api/push/seismic/check): una pasada inmediata
  async function passOnce() {
    await refreshDevices(true);
    const before = stats.alertsSent;
    const n = await pollUsgs(CFG.USGS_HOUR);
    return { earthquakes: stats.alertsSent - before, scanned: n };
  }

  // Listado reciente fusionado (para el frontend)
  function recent(minMag, limit) {
    const mm = Number.isFinite(+minMag) ? +minMag : 0;
    const lim = Math.min(Math.max(parseInt(limit, 10) || 80, 1), 200);
    return events.filter(e => e.mag >= mm).sort((a, b) => b.time - a.time).slice(0, lim)
      .map(e => ({
        id: e.srcIds.usgs || e.srcIds.emsc || e.key, mag: e.mag, place: e.place || '',
        lat: e.lat, lon: e.lon, depth: e.depth, time: e.time, url: e.url || '',
        updated: e.updated, sources: e.sources,
      }));
  }

  function health() {
    return {
      ...stats,
      uptimeS: Math.round((Date.now() - stats.startedAt) / 1000),
      eventsCached: events.length,
      emscSilentS: stats.emscLastMsgAt ? Math.round((Date.now() - stats.emscLastMsgAt) / 1000) : null,
    };
  }

  return {
    start, stop, passOnce, recent, health, refreshDevices,
    // expuestos para pruebas
    _ingest: ingest, _assess: assessEvent, _merge: merge, _CFG: CFG,
    _helpers: { maxRadiusKm, estimateMMI, effectiveMinMag, validCoords, bearingDir, fromEmscFeature, fromUsgsFeature },
  };
};

module.exports.assessEvent = assessEvent;
module.exports.maxRadiusKm = maxRadiusKm;
module.exports.estimateMMI = estimateMMI;
module.exports.effectiveMinMag = effectiveMinMag;
module.exports.validCoords = validCoords;
