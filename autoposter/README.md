# 🤖 CodeHub Blogger AutoPoster

Auto-publicador de posts en [codehub-labs.blogspot.com](https://codehub-labs.blogspot.com) usando IA (Groq) y la Blogger API v3.

## ¿Qué hace?

- Genera artículos técnicos completos sobre las secciones de CodeHub usando Groq (LLaMA 3.3 70B)
- Los publica automáticamente en Blogger con formato HTML estilizado, tags y CTA
- Recuerda qué topics ya publicó para nunca repetir (hasta agotar los 12 temas, luego reinicia)
- Guarda copia local de cada post como respaldo
- Funciona en modo manual, dry-run, o automatizado vía GitHub Actions

## Topics incluidos (12 artículos)

| # | Slug | Tema |
|---|------|------|
| 1 | `emi-copilot-ia` | EMI COPILOT: el asistente IA de CodeHub |
| 2 | `herramientas-web-codehub` | Las 23 herramientas web de /tools |
| 3 | `splash-screen-canvas-js` | El splash screen con Canvas API |
| 4 | `red-neuronal-canvas` | La red neuronal animada del fondo |
| 5 | `sistema-clima-openmeteo` | Clima en tiempo real con Open-Meteo |
| 6 | `pwa-service-worker` | PWA: Service Worker y push notifications |
| 7 | `backend-node-render` | Backend Node.js en Render con MongoDB |
| 8 | `easter-eggs-ux` | Los 7 easter eggs del portfolio |
| 9 | `multiidioma-i18n` | Sistema i18n ES/EN en Vanilla JS |
| 10 | `contact-form-emailjs-turnstile` | Formulario con EmailJS + Turnstile |
| 11 | `liquid-letters-animation` | Efecto de letras líquidas |
| 12 | `seo-adsense-portfolio` | SEO técnico y preparación para AdSense |

---

## Setup paso a paso

### 1. Crear proyecto en Google Cloud Console

1. Ir a [console.cloud.google.com](https://console.cloud.google.com)
2. **Crear proyecto** → dale un nombre (ej: `CodeHub Blogger`)
3. Ir a **APIs & Services → Library** → buscar **"Blogger API v3"** → **Enable**
4. Ir a **Credentials → Create Credentials → OAuth 2.0 Client IDs**
5. Application type: **Desktop App**
6. Copiar el `Client ID` y `Client Secret`

### 2. Configurar .env

```bash
cp .env.example .env
```

Llena los valores:
```
GROQ_API_KEY=gsk_...          # Tu clave Groq (ya la tienes)
BLOGGER_BLOG_ID=4932034987684289893
BLOGGER_CLIENT_ID=...
BLOGGER_CLIENT_SECRET=...
```

### 3. Obtener tokens OAuth2 (una sola vez)

```bash
npm install
node get-token.js
```

Se abrirá el navegador. Acepta los permisos de Blogger. El script imprimirá los tokens en la terminal — cópialos al `.env`:

```
BLOGGER_ACCESS_TOKEN=ya29.xxx
BLOGGER_REFRESH_TOKEN=1//xxx
```

> ⚠️ El `REFRESH_TOKEN` solo aparece la primera vez. Guárdalo bien.

### 4. Probar en dry-run

```bash
npm run dry-run
```

Genera el post y lo guarda en `generated-posts/` sin publicar en Blogger. Ábrelo en el navegador para ver el resultado.

### 5. Publicar

```bash
npm start
```

Publica el siguiente topic en cola. Repite cada vez que quieras publicar uno nuevo.

---

## Comandos disponibles

```bash
# Publicar el siguiente topic en cola
node autoposter.js

# Modo dry-run (genera pero no publica)
node autoposter.js --dry-run

# Publicar un topic específico
node autoposter.js --topic "emi-copilot"
node autoposter.js --topic "herramientas"

# Ver todos los topics y su estado
node autoposter.js --list

# Modo automático (publica cada 3 días)
node autoposter.js --schedule

# Renovar access_token (si expiró)
node refresh-token.js
```

---

## GitHub Actions (automatización total)

Para que se publique solo cada 3 días:

1. Coloca el archivo `autoposter-workflow.yml` en `.github/workflows/` de tu repositorio

2. Agrega estos **Repository Secrets** en Settings → Secrets → Actions:

| Secret | Valor |
|--------|-------|
| `GROQ_API_KEY` | Tu clave Groq |
| `BLOGGER_BLOG_ID` | `4932034987684289893` |
| `BLOGGER_CLIENT_ID` | De Google Cloud |
| `BLOGGER_CLIENT_SECRET` | De Google Cloud |
| `BLOGGER_REFRESH_TOKEN` | Del paso 3 |

3. El workflow se ejecuta automáticamente cada 3 días a las 10:00 AM Guatemala. También puedes dispararla manualmente desde la UI de GitHub Actions con topic específico o dry-run.

> ℹ️ El `ACCESS_TOKEN` se renueva automáticamente en cada ejecución usando el `REFRESH_TOKEN`.

---

## Estructura de archivos

```
autoposter/
├── autoposter.js          # Script principal
├── get-token.js           # Obtener tokens OAuth2 (primera vez)
├── refresh-token.js       # Renovar access token
├── package.json
├── .env.example
├── .env                   # (no commitar)
├── .autoposter-state.json # Estado: qué topics ya se publicaron
└── generated-posts/       # Copias locales de posts generados
    └── 2026-06-07-emi-copilot-ia.html
```

---

## Cómo agregar más topics

Edita el array `TOPICS` en `autoposter.js`. Cada topic tiene:

```javascript
{
  slug:   'nombre-unico',
  title:  'Título del post en Blogger',
  prompt: `Instrucciones detalladas para Groq...`,
  tags:   ['tag1', 'tag2', 'tag3'],
}
```

El prompt puede ser tan detallado como necesites — más detalle = mejor contenido generado.

---

## Consideraciones para AdSense

- Publicar **1 post cada 2-3 días** es lo ideal para Google AdSense
- Evitar publicar múltiples posts el mismo día (señal de spam)
- Cada post tiene al menos 800 palabras de contenido técnico real
- Los tags ayudan a la indexación en Blogger
- El footer con links internos mejora la señal de sitio relacionado

---

**Wilson.E** — wilson.e360labs@gmail.com  
[wilson360-labs.vercel.app](https://wilson360-labs.vercel.app)
