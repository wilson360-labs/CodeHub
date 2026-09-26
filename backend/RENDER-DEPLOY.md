# 🚀 Guía de Deploy en Render — CodeHub Backend

## 1. Configuración del Repositorio

El backend de CodeHub está preparado para desplegarse en Render automáticamente usando el archivo `render.yaml` (Blueprint) o configurándolo como Web Service manual.

- **Directorio raíz del backend:** `backend/`
- **Build Command:** `npm install`
- **Start Command:** `node server.js`
- **Node Version:** `20.11.0`
- **Health Check Path:** `/api/health`

---

## 2. Variables de Entorno en Render

En el panel de **Render** → Tu Servicio (**codehub**) → **Environment**:

| Variable | Descripción |
|---|---|
| `NODE_ENV` | `production` |
| `FRONTEND_URL` | `https://wilson360-labs.vercel.app` |
| `MONGODB_URI` | `mongodb+srv://...` (MongoDB Atlas) |
| `ADMIN_KEY` | Clave de acceso para el panel admin |
| `SUPABASE_URL` | URL de tu proyecto Supabase |
| `SUPABASE_KEY` | Service role key de Supabase (bucket `CodeHub`) |
| `GROQ_API_KEY` | API Key de Groq (LLaMA 3.3 70B) |
| `GEMINI_API_KEY` | API Key de Google Gemini (fallback) |
| `OPENROUTER_API_KEY` | API Key de OpenRouter (opcional) |
| `VAPID_PUBLIC_KEY` | Clave pública VAPID para Web Push |
| `VAPID_PRIVATE_KEY`| Clave privada VAPID para Web Push |
| `FIREBASE_SERVICE_ACCOUNT` | JSON de la cuenta de servicio de Firebase (FCM) |
| `GITHUB_WEBHOOK_SECRET` | Secret del webhook de GitHub para releases |
| `RENDER_EXTERNAL_URL` | `https://codehub-98s6.onrender.com` |

---

## 2b. Configuración de Supabase (una sola vez)

El backend usa Supabase para: estadísticas diarias (`daily_stats`), registro de
visitas/servicios, push (`push_subs`, `fcm_tokens`) y storage de APKs (bucket
`CodeHub`). Pasos:

1. **Render** → servicio `codehub` → **Environment** → añadir `SUPABASE_URL`
   (`https://<project-ref>.supabase.co`) y `SUPABASE_KEY` (la **service role**
   key, no la anon/`publishable`; está en Supabase → Settings → API) y guardar
   (se redepliega).
2. **Supabase → SQL Editor** → ejecutar una vez `backend/bootstrap_exec_sql.sql`
   (crea la RPC `exec_sql`). Sin ella, el backend no puede auto-crear sus
   tablas ni el admin-hub correr el DB Runner contra Supabase.
3. **Supabase → Storage** → crear un bucket **público** llamado **`CodeHub`**
   (el backend genera URLs públicas tipo `getPublicUrl`).

En el siguiente reinicio el backend crea solas las tablas (`ensure*Table` al
arrancar) y el reporte periódico de Telegram ya muestra "Stats diarias" reales.
Se verifica con el health check (clave admin): `storage: 'supabase'`.

---

## 3. Verificación de Funcionamiento

```bash
# Health check
curl https://codehub-98s6.onrender.com/api/health

# Respuesta esperada:
# {"status":"ok","mongo":"connected","uptime":"..."}
```

---

## 4. Tip: Mantener el Backend Activo (Evitar Sleep)

En el plan gratuito de Render, el servicio se suspende tras 15 minutos de inactividad.  
Para evitar que se duerma, configura un monitor gratuito (como [UptimeRobot](https://uptimerobot.com/) o [Cron-Job.org](https://cron-job.org/)):

- **URL:** `https://codehub-98s6.onrender.com/api/health`
- **Intervalo:** Cada 10 o 14 minutos
- **Método:** `GET`
