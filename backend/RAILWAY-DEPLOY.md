# 🚀 Guía de Deploy en Railway — CodeHub Backend

## Paso 1 — Subir el backend a GitHub

En Termux, dentro de la carpeta `backend/`:

```bash
git init
git add server.js package.json railway.json railway-setup.sh .gitignore
# ⚠️  NUNCA hagas git add .env
git commit -m "CodeHub backend v1 — Railway deploy"
git remote add origin https://github.com/TU-USUARIO/codehub-backend.git
git push -u origin main
```

> Crea el repo en github.com primero (sin README, vacío).

---

## Paso 2 — Crear proyecto en Railway

1. Ve a **railway.app** e inicia sesión con GitHub
2. Click en **"New Project"**
3. Selecciona **"Deploy from GitHub repo"**
4. Elige tu repo `codehub-backend`
5. Railway detecta automáticamente Node.js y empieza a construir

---

## Paso 3 — Agregar las variables de entorno (TUS KEYS)

En Railway → Tu proyecto → **Variables** → Add Variable:

| Variable          | Valor                              |
|-------------------|------------------------------------|
| `OPENAI_API_KEY`  | sk-...tu-key-nueva                 |
| `MONGODB_URI`     | mongodb+srv://...tu-atlas-uri      |
| `NODE_ENV`        | production                         |
| `RATE_LIMIT_MAX`  | 20                                 |
| `FRONTEND_URL`    | https://tu-usuario.github.io       |

> Las keys **nunca** van en el código — solo en Variables de Railway.
> Railway las encripta y nadie más puede verlas.

---

## Paso 4 — Obtener la URL pública

Después del deploy exitoso:
Railway → Tu proyecto → **Settings** → **Domains** → Generate Domain

Obtendrás algo como:
```
https://codehub-backend-production.up.railway.app
```

---

## Paso 5 — Actualizar el frontend

En `index.html`, busca esta línea:

```js
return 'http://localhost:3001'; // ← reemplaza con tu URL real
```

Cámbiala por:
```js
return 'https://codehub-backend-production.up.railway.app';
```

Sube el `index.html` actualizado a GitHub Pages. ¡Listo!

---

## Verificar que funciona

```bash
# Health check
curl https://tu-proyecto.up.railway.app/api/health

# Respuesta esperada:
# {"status":"ok","mongo":"connected","openai":"configured","uptime":"10s"}

# Prueba del chat
curl -X POST https://tu-proyecto.up.railway.app/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"Hola","sessionId":"test"}'
```

---

## ¿Cuánto cuesta Railway?

**Free tier:** $5 de crédito gratis al mes.
Con el tráfico de un portfolio personal (chatbot con ~600 tokens/respuesta),
los $5 alcanzan para aproximadamente **800–1200 conversaciones al mes**.
Si se acaba, el servidor simplemente pausa hasta el siguiente mes.

Para uso intensivo puedes agregar una tarjeta — el costo real es
menos de $1/mes con tráfico normal de portfolio.
