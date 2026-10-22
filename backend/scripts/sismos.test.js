const test = require('node:test');
const assert = require('node:assert/strict');
const create = require('../sismos');
const { assessEvent, maxRadiusKm, effectiveMinMag, validCoords } = create;

// Ciudad de Guatemala
const GT = { lat: 14.6349, lon: -90.5069 };
const now = () => Date.now();

test('maxRadiusKm crece con la magnitud', () => {
  assert.ok(maxRadiusKm(3.5) < maxRadiusKm(5) && maxRadiusKm(5) < maxRadiusKm(7));
});

test('sismo M5.5 cercano → alarma con onda S futura', () => {
  const ev = { mag: 5.5, lat: 14.2, lon: -90.9, depth: 40, time: now() - 5000 };
  const a = assessEvent(ev, GT.lat, GT.lon, {});
  assert.equal(a.relevant, true);
  assert.equal(a.level, 'alarm');
  assert.ok(a.epiKm > 50 && a.epiKm < 80);
  assert.ok(a.sArrival > ev.time);
});

test('sismo lejano M4 (500 km) NO es relevante (antes: "todo el país"/300 km)', () => {
  const ev = { mag: 4.0, lat: 18.5, lon: -88.3, depth: 10, time: now() };
  assert.equal(assessEvent(ev, GT.lat, GT.lon, {}).relevant, false);
});

test('M7 a 300 km sí es relevante; M7 a 1500 km no', () => {
  assert.equal(assessEvent({ mag: 7, lat: 12, lon: -92.5, depth: 30, time: now() }, GT.lat, GT.lon, {}).relevant, true);
  assert.equal(assessEvent({ mag: 7, lat: 3, lon: -100, depth: 30, time: now() }, GT.lat, GT.lon, {}).relevant, false);
});

test('radio máximo del usuario recorta el techo', () => {
  const ev = { mag: 5.5, lat: 14.2, lon: -90.9, depth: 40, time: now() };
  assert.equal(assessEvent(ev, GT.lat, GT.lon, { maxRadiusKm: 30 }).relevant, false);
});

test('umbral legado 4.5 se migra a 3.5; valores propios se respetan', () => {
  assert.equal(effectiveMinMag(4.5), 3.5);
  assert.equal(effectiveMinMag(null), 3.5);
  assert.equal(effectiveMinMag(5), 5);
});

test('coordenadas 0,0 / NaN no son ubicación válida', () => {
  assert.equal(validCoords(0, 0), false);
  assert.equal(validCoords('x', 1), false);
  assert.equal(validCoords(14.6, -90.5), true);
});

function mkEngine(devs, sent) {
  return create({
    pushList: async () => [],
    fcmEnabled: true,
    fcmListTokens: async () => devs,
    sendFCM: async (token, p) => { sent.push({ token, p }); return { ok: true }; },
    log: { log() {}, warn() {} },
  });
}

test('motor: alerta al instante, ordena por cercanía y no duplica entre fuentes', async () => {
  const sent = [];
  const eng = mkEngine([
    { token: 'far', lat: 14.9, lon: -90.0 },
    { token: 'near', lat: 14.3, lon: -90.8 },
    { token: 'nowhere', lat: 0, lon: 0 },
    { token: 'off', lat: 14.3, lon: -90.8, seismic_alerts: false },
  ], sent);
  const t = now() - 8000;
  await eng._ingest({ src: 'emsc', srcId: 'E1', mag: 5.2, lat: 14.1, lon: -90.9, depth: 35, time: t, place: 'Guatemala', updated: t }, 'emsc-ws');
  const n1 = sent.length;
  assert.equal(sent[0].token, 'near');
  assert.equal(sent[0].p.dataOnly, true);
  assert.equal(sent[0].p.ttl, 180);
  // La misma señal desde USGS (id y hora distintos) NO se vuelve a enviar
  await eng._ingest({ src: 'usgs', srcId: 'us1', mag: 5.3, lat: 14.12, lon: -90.88, depth: 33, time: t + 3000, place: '10 km S of X, Guatemala', updated: t + 60000 }, 'usgs');
  assert.equal(sent.length, n1);
  // Revisión de magnitud ≥ +0.5 sí re-avisa
  await eng._ingest({ src: 'usgs', srcId: 'us1', mag: 5.9, lat: 14.12, lon: -90.88, depth: 33, time: t + 3000, place: 'X', updated: t + 120000 }, 'usgs');
  assert.ok(sent.length > n1);
});

test('motor: sismo de hace 5 horas JAMÁS genera alerta', async () => {
  const sent = [];
  const eng = mkEngine([{ token: 'near', lat: 14.3, lon: -90.8 }], sent);
  const t = now() - 5 * 3600 * 1000;
  await eng._ingest({ src: 'usgs', srcId: 'old', mag: 6.0, lat: 14.1, lon: -90.9, depth: 35, time: t, updated: t }, 'usgs');
  assert.equal(sent.length, 0);
});
