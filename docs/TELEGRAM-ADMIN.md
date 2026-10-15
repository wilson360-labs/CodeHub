# Bot de administración por Telegram

Wilson.E 2026 · Milestone 1 (monitoreo + despliegue)

CodeHub se puede gobernar desde el chat privado con el bot. El receptor vive
en el backend (`backend/telegram-bot.js`, montado en `server.js`) y **no
duplica lógica**: cada comando llama a los endpoints `/api/admin/*` que ya
existen, así que todo sigue pasando por las mismas validaciones, rate-limits
y auditoría que el panel web.

```
Telegram ──POST /api/telegram/webhook──▶ server.js ──HTTP interno──▶ /api/admin/*
```

---

## 1) Configuración (una sola vez)

En Render → servicio **codehub** → Environment, añade:

| Variable | Valor | Notas |
|---|---|---|
| `TELEGRAM_BOT_TOKEN` | token de @BotFather | **ya existe** (lo usa el resumen diario) |
| `TELEGRAM_CHAT_ID` | tu chat | **ya existe**. Si no lo sabes: `/getid` con `python telegram_bot.py --get-id` |
| `TELEGRAM_WEBHOOK_SECRET` | cadena larga aleatoria (ej. `openssl rand -hex 32`) | **nueva, obligatoria** |
| `TG_BOT_ENABLED` | `true` (o `false` para apagar) | kill-switch, opcional |
| `ADMIN_KEY` | tu clave admin | **ya existe** |

Generar el secreto:

```bash
openssl rand -hex 32
```

El backend registra el webhook solo al arrancar:

```
https://api.telegram.org/bot<TOKEN>/setWebhook
  url            = <BACKEND_URL>/api/telegram/webhook
  secret_token   = TELEGRAM_WEBHOOK_SECRET
  allowed_updates= ["message","callback_query"]
```

`BACKEND_URL` debe ser la URL pública real del servicio Render
(`https://codehub-98s6.onrender.com`), no `localhost`: el registro se hace
contra la API de Telegram.

Comprobarlo a mano:

```bash
curl -s "https://api.telegram.org/bot<TOKEN>/getWebhookInfo"
# debe salir: "url": ".../api/telegram/webhook",
#             "has_custom_certificate": false,
#             "pending_update_count": 0,
#             "last_error_message": ausente
```

---

## 2) Seguridad (todo fail-closed)

1. **Origen verificado.** Telegram manda el header
   `X-Telegram-Bot-Api-Secret-Token`; si no coincide con
   `TELEGRAM_WEBHOOK_SECRET` el endpoint responde `403`. Si la variable no
   está configurada responde `503` (no se acepta nada).
2. **Whitelist de chat.** Solo se atiende `message.chat.id === TELEGRAM_CHAT_ID`.
   Cualquier otro chat se ignora y se avisa al admin por Telegram.
3. **Sesión admin firmada.** `/login <ADMIN_KEY>` emite un token HMAC-SHA256
   con el mismo secreto y el mismo TTL (30 min) que usa el panel web, y se
   envía como `x-admin-session`. La comparación es de tiempo constante.
   Los comandos caducan solos.
4. **Confirmación en dos pasos.** `/desplegar` nunca ejecuta a la primera:
   muestra botones y exige pulsar «Sí, lanzar» (la confirmación caduca a los
   3 minutos).
5. **Solo workflows de una lista permitida.** `deploy-triggers.yml`,
   `rebuild-assets.yml`, `quality.yml`, `build-apk.yml`, `preview.yml`,
   `enrich-app-logos.yml`, `extensions-check.yml`, `dedupe-catalog.yml`,
   `seed-foss-catalog.yml`. Cualquier otro hay que lanzarlo desde el panel.
6. **Rate-limit por chat** (15 comandos/minuto) y **kill-switch**
   `TG_BOT_ENABLED=false`.
7. **Auditoría.** Cada comando se registra con `logAdmin(actor='telegram')`
   y se ve en `/api/admin/activity` (y por tanto en `/actividad`).

---

## 3) Comandos

| Comando | Qué hace | Endpoint que usa |
|---|---|---|
| `/login <clave>` | abre sesión de 30 min | — (firma local) |
| `/logout` | cierra sesión | — |
| `/ayuda` | menú | — |
| `/estado` | salud: backend, Mongo, caché, WS, uptime, visitas | `/api/health` + `/api/stats/live` |
| `/visitas` | últimas 200 visitas por día y país | `/api/admin/visitors` |
| `/apps [buscador]` | catálogo: versión, categoría, si tiene APK | `/api/admin/apps` |
| `/app <id>` | ficha completa | `/api/admin/apps` |
| `/runs` | último run de cada workflow de CI | `/api/admin/github/runs` |
| `/jobs` | crons definidos en `render.yaml` | `/api/admin/jobs` |
| `/actividad` | registro de acciones admin | `/api/admin/activity` |
| `/desplegar` | lanzar un workflow (con confirmación) | `/api/admin/github/workflows` + `/dispatch` |
| `/push ...` | notificación a todos (confirm) | `/api/admin/push/broadcast` |
| `/release ...` | publicar release + push | `/api/admin/releases` |
| `/solicitudes` | solicitudes pendientes | `/api/requests` |
| `/aprobar <id>` | aprobar solicitud | `/api/requests/:id` |
| `/rechazar <id>` | rechazar solicitud | `/api/requests/:id` |
| `/resenas <appId>` | reseñas recientes | `/api/apps/:id/reviews` |
| `/config` | ver/editar configuración | `/api/admin/config` |
| `/contenido <ruta>` | leer carpeta o archivo | `/api/admin/github/contents` |
| `/poner <ruta>` | edita archivo (siguiente msg) | `/api/admin/github/contents` |

Atajos: los botones de resultado incluyen «🔁 Ver runs» para no teclear.

---

## 4) Cómo se prueba

```bash
# 1) En local, levantar el backend
cd backend && node server.js

# 2) Simular un update sin salir a Telegram
#    Debe responder 200; si la clave no es ADMIN_KEY responde
#    "Clave incorrecta", y si falta el secreto responde 503.
curl -s -X POST http://127.0.0.1:3001/api/telegram/webhook \
  -H 'Content-Type: application/json' \
  -H 'X-Telegram-Bot-Api-Secret-Token: <TELEGRAM_WEBHOOK_SECRET>' \
  -d '{"message":{"chat":{"id":<TELEGRAM_CHAT_ID>},"text":"/login 1234"}}'
```

En producción: escribe `/login <ADMIN_KEY>` al bot y luego `/estado`.

---

## 5) Roadmap

- **Milestone 2**: subir un APK al chat y publicarlo (`/api/admin/apps` +
  `/api/admin/apps/:id/upload`), crear releases, `/push` a usuarios,
  responder solicitudes y reseñas, ver/editar archivos del repo.
- **Milestone 3**: alertas con umbrales configurables por comando (build
  rota 2 veces, caída de visitas, errores nuevos repetidos).

Restricciones que se mantienen: nunca se muestra el token del bot ni claves
en los mensajes, y el cron del resumen diario
(`python/telegram_bot.py`) sigue intacto y sin cambios.
