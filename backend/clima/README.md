# backend/clima — Motor inteligente de clima (push)

Lógica climática separada del `server.js` monolítico, siguiendo el patrón de
`backend/wil-e` y `backend/modules/universal-resolver`.

## Qué hace

Consulta **Open-Meteo** y decide cuándo enviar notificaciones push al usuario.
El diseño es **inteligente, estilo Samsung Weather / Windows Weather**: avisa
solo cuando el clima **cambia de verdad**, no repite estados fijos.

### Radiación UV por hora (el bug que se arregló)
Antes se usaba el **índice UV máximo del día** (`Math.max`), así que decía
"Radiación UV alta (9.0)" durante TODO el día aunque a las 8 am el UV real
fuera 2. Ahora:
- Se usa **`uv_current`** = el índice **real de la hora actual** de Open-Meteo
  (`fetch.js` → `uvCurrent`).
- Sube al mediodía y baja al atardecer, como en la realidad.
- Solo notifica cuando **cambia de nivel** (bajo→moderado→alto→muy alto→extremo)
  o sube/baja ≥2 puntos, con cooldown. Así no spamea cada 30 min.

### Probabilidad de lluvia por cambio
`diffWeatherChange` detecta subidas/bajadas de ≥20 puntos en la probabilidad
de lluvia y avisa con el delta real (ej. "lluvia 20→60% ↑").

### Otros cambios notables detectados
- Temperatura ±3°C
- Viento ±15 km/h
- Cambio de condición de cielo (despejado → nublado → lluvia)
- Día ↔ noche

## Archivos

| Archivo | Contenido |
|---------|-----------|
| `wx.js` | Helpers puros: `wxLabel`, `hourlyLabel`, `normalizeWeatherInterval` |
| `fetch.js` | `fetchWeatherFor` — consulta a Open-Meteo + `uv_current` real |
| `alerts.js` | `WX_ALERTS`, `detectAlert`, `weatherSnapshot`, `diffWeatherChange`, `dailyBriefing`, `uvLevel` |
| `index.js` | Factory `createWeatherEngine(deps)` → `weatherPushPass`, `weatherEndpoint`, `startScheduler` |

## Integración con server.js

`server.js` **ya no contiene** la lógica de clima; solo inyecta dependencias:

```js
const climaEngine = require('./clima')({
  supabase, sendPush, sendFCM, fcmEnabled, fcmListTokens, pushList, pushSave,
});
function normalizeWeatherInterval(v) { return climaEngine.normalizeWeatherInterval(v); }
app.get('/api/push/weather/check', climaEngine.weatherEndpoint);
climaEngine.startScheduler(30 * 60 * 1000);
```

## Estado por usuario
- `last_weather_snapshot` (JSON) — guarda el último estado notificado para poder
  comparar y detectar el siguiente cambio.
- `last_alert_condition` / `last_alert_at` — condición de alerta activa y cooldown.
- `weather_interval` — intervalo de resúmenes (30/60/180/360 min; 0 = solo alertas).

## Endpoints
- `GET /api/push/weather/check` — fuerza una pasada manual (devuelve `{ok, sent}`).
