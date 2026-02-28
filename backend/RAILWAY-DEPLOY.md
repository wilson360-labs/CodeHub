# CodeHub Backend — Railway Deploy Guide

Tu backend ya está desplegado en:
**https://codehub-production-729d.up.railway.app**

---

## Variables en Railway Dashboard

Railway Dashboard → Tu proyecto → Variables. Estas son las que necesitas:

| Variable          | Descripción                                      |
|-------------------|--------------------------------------------------|
| `OPENAI_API_KEY`  | sk-proj-... (platform.openai.com/api-keys)       |
| `MONGODB_URI`     | mongodb+srv://... (Atlas → Connect → Drivers)    |
| `FRONTEND_URL`    | https://TU_USUARIO.github.io                     |
| `RATE_LIMIT_MAX`  | 20 (requests por IP cada 15 min)                 |
| `NODE_ENV`        | production                                       |

> ⚠️ Las credenciales NUNCA van en el código. Solo en Railway Variables.
> El .env local (si lo usas) está en .gitignore y nunca llega a GitHub.

---

## Verificar que todo funciona

```bash
# Health check
curl https://codehub-production-729d.up.railway.app/api/health

# Respuesta esperada:
# {
#   "status": "ok",
#   "openai": "✅ configurado",
#   "mongodb": "✅ conectado",
#   "sessions": 0,
#   "uptime": "10s"
# }

# Prueba del chat
curl -X POST https://codehub-production-729d.up.railway.app/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "Hola, qué herramientas tiene CodeHub?", "sessionId": "test123"}'
```

---

## Cómo actualizar el backend en Railway

```bash
# Desde la carpeta backend/
git add server.js package.json railway.json
git commit -m "fix: descripción del cambio"
git push origin main
# Railway detecta el push y redespliega automáticamente en ~30 segundos
```

> ⚠️ Nunca hagas `git add .env` ni `git add .`

---

## Flujo completo del sistema

```
Usuario (GitHub Pages)
    ↓ fetch POST /api/chat
Railway Backend (server.js)
    ↓ valida CORS, rate limit
OpenAI GPT-4o-mini
    ↓ respuesta
MongoDB Atlas (guarda historial 7 días)
    ↓
Usuario recibe la respuesta
```

---

## Si el chatbot no responde

1. Verifica `OPENAI_API_KEY` en Railway Variables — que empiece con `sk-proj-`
2. Verifica créditos en platform.openai.com/usage
3. Revisa Railway → Logs para ver el error exacto
4. Llama a `/api/health` para diagnóstico rápido
