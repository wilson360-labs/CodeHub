# CodeHub Backend — Guía para Termux

## Estructura de archivos

```
codehub-backend/
├── .env              ← TUS CREDENCIALES (nunca a GitHub)
├── .gitignore        ← protege el .env
├── package.json
├── server.js         ← punto de entrada
├── models/
│   └── Conversation.js   ← esquema MongoDB
├── routes/
│   └── chat.js           ← endpoint /api/chat
└── middleware/
    └── rateLimit.js      ← límite de mensajes por IP
```

---

## Paso 1 — Instalar dependencias en Termux

```bash
# En Termux, dentro de la carpeta del backend:
npm install
```

Instala: `@anthropic-ai/sdk`, `express`, `mongoose`, `cors`, `dotenv`, `express-rate-limit`

---

## Paso 2 — Configurar el .env

Abre el archivo `.env` y completa:

```env
ANTHROPIC_API_KEY=tu_clave_real_de_claude
MONGODB_URI=mongodb+srv://dg242181_db_user:vWhGvkW2T6cyAQb@cluster0.5kn9ehe.mongodb.net/miBaseDeDatos?retryWrites=true&w=majority
PORT=3001
ALLOWED_ORIGIN=*
```

### Obtener tu API key de Anthropic:
1. Ve a https://console.anthropic.com/settings/keys
2. Crea una cuenta (si no tienes)
3. Click en "Create Key" → copia el valor `sk-ant-...`
4. Pégalo en `ANTHROPIC_API_KEY=`

> ⚠️ Guarda el key en un lugar seguro — solo se muestra una vez.
> Los primeros $5 de crédito son gratuitos al crear la cuenta.

---

## Paso 3 — Si ya tienes un server.js existente

No reemplaces tu archivo. Solo **agrega** estas líneas al tuyo:

```js
// Al inicio del archivo
require('dotenv').config();
const cors     = require('cors');
const mongoose = require('mongoose');
const chatRoute = require('./routes/chat');

// Después de crear app = express()
app.use(cors({ origin: process.env.ALLOWED_ORIGIN || '*' }));
app.use(express.json({ limit: '10kb' }));

// Conectar MongoDB (si no lo tienes ya)
mongoose.connect(process.env.MONGODB_URI)
  .then(() => console.log('✅ MongoDB conectado'));

// Agregar la ruta del chat
app.use('/api/chat', chatRoute);
```

---

## Paso 4 — Arrancar el servidor

```bash
# En Termux:
node server.js

# Deberías ver:
# 🚀 CodeHub backend corriendo en puerto 3001
# ✅ MongoDB conectado
```

---

## Paso 5 — Conectar el frontend

En `index.html`, busca esta línea en el chatbot:

```js
const API_URL = 'http://localhost:3001/api/chat';
```

Cámbiala según dónde accedas al sitio:

| Situación | URL a usar |
|-----------|-----------|
| Abriendo index.html en el mismo móvil | `http://localhost:3001/api/chat` |
| Desde otro dispositivo en tu WiFi | `http://192.168.1.XX:3001/api/chat` |
| Dominio propio (producción) | `https://tudominio.com/api/chat` |

Para saber tu IP en Termux:
```bash
ip addr show | grep "inet " | grep -v 127
# Busca algo como 192.168.1.XX
```

---

## Paso 6 — Verificar que todo funciona

```bash
# Prueba el health check:
curl http://localhost:3001/health

# Respuesta esperada:
# {"status":"ok","mongo":"connected"}

# Prueba el chat:
curl -X POST http://localhost:3001/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message":"Hola","sessionId":"test123"}'

# Respuesta esperada:
# {"reply":"¡Hola!...","sessionId":"test123","usage":{"total":45}}
```

---

## Seguridad

- `.env` nunca va a GitHub — está en `.gitignore`
- El API key solo vive en el servidor, nunca llega al navegador
- Rate limit: 30 mensajes por IP cada 15 minutos
- El payload está limitado a 10KB
- CORS: en producción cambia `ALLOWED_ORIGIN=*` a tu dominio exacto

---

## MongoDB — Qué se guarda

Cada conversación se guarda en la colección `conversations`:

```json
{
  "sessionId": "sess_abc123",
  "ip": "192.168.1.5",
  "messages": [
    { "role": "user",      "content": "¿Qué es Base64?", "tokens": 12 },
    { "role": "assistant", "content": "Base64 es...",     "tokens": 48 }
  ],
  "totalTokens": 60,
  "createdAt": "2025-02-26T...",
  "updatedAt": "2025-02-26T..."
}
```

Puedes ver esto en MongoDB Atlas → Collections → codehub → conversations.
