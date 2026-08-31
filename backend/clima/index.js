// ─────────────────────────────────────────────
//  Clima — motor inteligente de notificaciones (factory)
//  CodeHub by Wilson.E
//
//  Uso desde server.js:
//    const clima = require('./clima')({
//      supabase, sendPush, sendFCM, fcmEnabled,
//      fcmListTokens, pushList, pushSave,
//    });
//    app.get('/api/push/weather/check', clima.weatherEndpoint);
//    clima.startScheduler(); // arranca el setInterval de 30 min
// ─────────────────────────────────────────────

const { fetchWeatherFor } = require('./fetch');
const {
  detectAlert,
  weatherSnapshot,
  diffWeatherChange,
  dailyBriefing,
  normalizeWeatherInterval,
} = require('./alerts');
const { wxLabel, hourlyLabel } = require('./wx');

// Cooldown mínimo entre notificaciones de ALERTA por usuario (ms).
// Evita avisar "lluvia↑" cada 30 min mientras dure una misma condición.
const ALERT_COOLDOWN_MS = 2 * 60 * 60 * 1000; // 2h entre alertas
// Cooldown mínimo entre resúmenes de CAMBIO (ms).
const CHANGE_COOLDOWN_MS = 30 * 60 * 1000;     // 30 min entre diffs

module.exports = function createWeatherEngine(deps) {
  const {
    supabase = null,
    sendPush = async () => ({ ok: false }),
    sendFCM = async () => ({ ok: false }),
    fcmEnabled = false,
    fcmListTokens = async () => [],
    pushList = async () => [],
    pushSave = async () => {},
  } = deps || {};

  async function weatherPushPass() {
    let subs;
    try { subs = await pushList(); } catch (e) { return { sent: 0 }; }
    const enabled = subs.filter(s => s.alerts && Number.isFinite(+s.lat) && Number.isFinite(+s.lon));
    if (!enabled.length) {
      if (!fcmEnabled) return { sent: 0 };
    }

    // Agrupar por coordenadas para no repetir llamadas a Open-Meteo
    const groups = new Map();
    for (const s of enabled) {
      const key = (+s.lat).toFixed(1) + ',' + (+s.lon).toFixed(1);
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(s);
    }

    // Tokens FCM (Android) por coordenadas
    const fcmGroups = new Map();
    if (fcmEnabled) {
      try {
        for (const t of await fcmListTokens()) {
          if (Number.isFinite(+t.lat) && Number.isFinite(+t.lon)) {
            const key = (+t.lat).toFixed(1) + ',' + (+t.lon).toFixed(1);
            if (!fcmGroups.has(key)) fcmGroups.set(key, []);
            fcmGroups.get(key).push(t);
          }
        }
      } catch (e) {}
    }
    for (const [key, tokens] of fcmGroups) {
      if (!groups.has(key)) groups.set(key, []);
      for (const t of tokens) groups.get(key).push({ ...t, _isFCM: true });
    }

    let sent = 0;
    for (const [key, group] of groups) {
      const parts = key.split(',');
      let current;
      try { current = await fetchWeatherFor(parts[0], parts[1]); } catch (e) { continue; }
      const alert = detectAlert(current);
      const curSnap = weatherSnapshot(current);

      for (const s of group) {
        const prevSnap = (() => {
          try { return s.last_weather_snapshot ? JSON.parse(s.last_weather_snapshot) : null; }
          catch (e) { return null; }
        })();
        const now = Date.now();
        const lastAlertAt = s.last_alert_at ? new Date(s.last_alert_at).getTime() : 0;

        // ── 1) ALERTAS (transiciones) ──
        // Se notifican cuando APARECEN (o cambian), no mientras se mantienen.
        // "critical" (lluvia/tormenta/viento) cruza cooldowns; radiación/calor
        // respeta el cooldown y se limpia al bajar la condición.
        if (alert) {
          const isNew = s.last_alert_condition !== alert.cond;
          const allowed = alert.critical ? true : (now - lastAlertAt) >= ALERT_COOLDOWN_MS;
          if (isNew && allowed) {
            const bodyExtra = alert.body;
            let r;
            if (s._isFCM) {
              r = await sendFCM(s.token, {
                title: 'CodeHub Clima · ' + (s.city || 'Tu zona'),
                body: bodyExtra,
                type: 'weather',
                url: '/#weather-section',
              });
            } else {
              r = await sendPush(s, {
                title: 'CodeHub Clima · ' + (s.city || 'Tu zona'),
                body: s.city ? bodyExtra + ' · ' + s.city : bodyExtra,
                type: 'weather',
                icon: '/splash/codehub.png',
                url: '/#weather-section',
              });
            }
            if (r.ok) {
              s.last_alert_condition = alert.cond;
              s.last_alert_at = new Date().toISOString();
              sent++;
            }
          }
        } else if (s.last_alert_condition) {
          // La condición volvió a la normalidad → limpiar estado,
          // así la próxima subida se vuelve a notificar (radiación, etc.)
          s.last_alert_condition = null;
          s.last_alert_at = null;
        }

        // ── 2) CAMBIOS REALES (diff de snapshot) ──
        // Solo avisa cuando algo cambió de forma notable respecto a la
        // última notificación: temperatura, lluvia, viento, radiación UV.
        const changes = diffWeatherChange(prevSnap, curSnap);
        const lastBriefAt = s.last_brief_at ? new Date(s.last_brief_at).getTime() : 0;
        if (changes && changes.length && (now - lastBriefAt) >= CHANGE_COOLDOWN_MS) {
          // No spoilear dos veces seguidas el mismo cambio ya notificado vía alerta
          const body = '🌦️ Tu clima cambió:\n' + changes.join('\n');
          let r;
          if (s._isFCM) {
            r = await sendFCM(s.token, {
              title: 'CodeHub Clima · ' + (s.city || 'Tu zona'),
              body: body,
              type: 'weather_change',
              url: '/#weather-section',
            });
          } else {
            r = await sendPush(s, {
              title: 'CodeHub Clima · ' + (s.city || 'Tu zona'),
              body: (s.city ? body + '\n📍 ' + s.city : body),
              type: 'weather_change',
              icon: '/splash/codehub.png',
              url: '/#weather-section',
            });
          }
          if (r.ok) {
            s.last_brief_at = new Date().toISOString();
            sent++;
          }
        }

        // ── 3) Guardar snapshot siempre (para el próximo diff) ──
        s.last_weather_snapshot = JSON.stringify(curSnap);
        if (!s._isFCM) await pushSave(s);
      }
    }
    return { sent };
  }

  async function weatherEndpoint(req, res) {
    try {
      const out = await weatherPushPass();
      res.json({ ok: true, ...out });
    } catch (e) {
      res.status(500).json({ ok: false, error: e.message });
    }
  }

  // Scheduler — revisa cada 30 min; solo envía cuando cambia la condición
  let timer = null;
  function startScheduler(ms) {
    const interval = Math.max(5 * 60 * 1000, Number(ms) || 30 * 60 * 1000);
    if (timer) clearInterval(timer);
    timer = setInterval(() => {
      weatherPushPass()
        .then(o => { if (o.sent) console.log('🌤️ Push clima enviado:', o.sent); })
        .catch(e => console.warn('⚠️  Push clima error:', e.message));
    }, interval);
    return timer;
  }

  return {
    weatherPushPass,
    weatherEndpoint,
    startScheduler,
    dailyBriefing,
    detectAlert,
    weatherSnapshot,
    normalizeWeatherInterval,
    wxLabel,
    hourlyLabel,
  };
};
