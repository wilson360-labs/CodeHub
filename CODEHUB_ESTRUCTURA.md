# CODEHUB — Documento Estructural
> **Para uso de IA asistente.** Lee este archivo ANTES de modificar cualquier archivo del proyecto.  
> Última actualización: Agosto 2026 · Autor: Wilson Enríquez · wilson.e360labs@gmail.com

---

## 1. Resumen del proyecto

**CodeHub** es un portfolio personal + plataforma de herramientas web, operado por Wilson Enríquez (Guatemala 🇬🇹).  
Dominio en producción: `https://wilson360-labs.vercel.app`  
Blog externo: `https://codehub-labs.blogspot.com`  
Repositorio: GitHub → desplegado automáticamente en **Vercel** (frontend) y **Render/Railway** (backend Node.js).

---

## 2. Stack tecnológico

| Capa | Tecnología |
|------|-----------|
| Frontend | HTML5, CSS3, JavaScript ES2025 vanilla (sin frameworks) |
| Backend | Node.js + Express.js → `backend/server.js` |
| Base de datos | MongoDB Atlas (mongoose) + Supabase (eventos/stats) |
| Hosting frontend | Vercel (rama main = auto-deploy) |
| Hosting backend | Render (`https://codehub-98s6.onrender.com`) / Railway |
| IA chatbot | Groq (Llama), Google Gemini, Anthropic Claude, OpenRouter |
| Formulario contacto | EmailJS |
| Anti-bot | Cloudflare Turnstile |
| Analytics | Google Analytics 4 (G-205F26ETCC) + GTM (GTM-PFVCPFJJ) |
| Publicidad | Google AdSense (ca-pub-3780093322926832) |
| PWA | Service Worker (`sw.js`) + `manifest.json` |
| Blog | Blogger JSONP feed público |
| Almacenamiento APKs | Supabase Storage (bucket: `codehub-apks`) |
| CDN imágenes | Cloudinary |
| Push notifications | Web Push API + VAPID keys en backend |

---

## 3. Árbol de archivos completo

```
CodeHub-main/
│
├── index.html                  ← Página principal (única SPA-like, ~5260 líneas)
├── 404.html                    ← Página de error personalizada
├── offline.html                ← Página offline PWA
├── manifest.json               ← PWA manifest
├── sw.js                       ← Service Worker (caché PWA)
├── robots.txt                  ← Configuración para crawlers
├── sitemap.xml                 ← Sitemap para SEO
├── ads.txt                     ← Archivo autorización AdSense
├── vercel.json                 ← ⚠️ CRÍTICO — Rutas, rewrites, redirects, headers
├── railway.json                ← Config despliegue Railway (frontend legacy)
├── package.json                ← Dependencias frontend
├── googlef6ae0fd15fc626ec.html ← Verificación Google Search Console
├── apps_data.json              ← Seed inicial de apps (respaldo)
├── privacy.html                ← Política de Privacidad (en raíz, no en /pages/)
├── terms.html                  ← Términos de Uso (en raíz, no en /pages/)
│
├── pages/                      ← Páginas secundarias (todas ruteadas via vercel.json)
│   ├── tools.html          → ruta pública: /tools
│   ├── opensource.html     → ruta pública: /opensource
│   ├── servicios.html          → ruta pública: /servicios
│   ├── downloader.html         → ruta pública: /downloader
│   ├── cv.html                 → ruta pública: /cv
│   ├── admin-hub.html          → ruta pública: /admin-hub (noindex)
│   ├── codehub-ultra.html      → ruta pública: /codehub-ultra
│   └── analytics.html          → ruta pública: /analytics
│
├── games/
│   ├── snake.html              → ruta pública: /snake
│   ├── snake.js
│   ├── tetris.html             → ruta pública: /tetris
│   └── tetris.js
│
├── css/
│   ├── index.css               ← Estilos principales de index.html
│   ├── index-responsive.css    ← Media queries de index.html
│   ├── components.css          ← Componentes reutilizables
│   ├── tools.css               ← Estilos de /tools
│   ├── opensource.css          ← Base styles del catálogo Open Source
│   ├── servicios.css           ← Estilos de /servicios
│   ├── downloader.css          ← Estilos de /downloader
│   ├── admin-hub.css           ← Estilos extras del admin
│   ├── skills-image-gen.css    ← Generador de imágenes IA
│   ├── snake.css               ← Estilos del juego Snake
│   └── styles.css              ← Reset global / variables base
│
├── js/
│   ├── index-main.js           ← Lógica principal de index.html (canvas, scroll, etc.)
│   ├── index-chat.js           ← Chatbot EMI IA (⚠️ duplicado legacy; el código real del chat está inline en index.html)
│   ├── index-emailjs.js        ← Formulario de contacto
│   ├── index-whats-new.js      ← Panel "Qué hay de nuevo" en index
│   ├── emi-voice.js            ← 🎙️ EMI Voice (micrófono + auto-speak) — integrado en index.html
│   ├── opensource.js           ← Lógica de /opensource (catálogo, filtros, ratings)
│   ├── tools.js                ← Lógica de todas las 34 herramientas
│   ├── admin-hub.js            ← Lógica completa del panel admin
│   ├── servicios.js            ← Lógica de /servicios
│   ├── downloader.js           ← Lógica del video downloader
│   ├── skills-image-gen.js     ← Generador de imágenes IA (Pollinations)
│   ├── theme-switcher.js       ← Cambio de tema claro/oscuro
│   ├── updater.js              ← Detección de updates PWA
│   ├── ux-animations.js        ← Animaciones globales (Intersection Observer)
│   ├── thinking-orb.js         ← Orb de pensamiento de EMI
│   ├── device-detect.js        ← Detección móvil/escritorio
│   ├── site-tour.js            ← Tour guiado del sitio
│   ├── live-update-check.js    ← Check de actualizaciones en vivo
│   └── script.js               ← Utilidades globales
│
├── img/                        ← Imágenes de apps Android (thumbnails)
├── splash/                     ← Logo e imágenes del splash screen
│   ├── codehub.png
│   ├── codehub-splash.png
│   └── codehub-splash.webp
│
├── ads/
│   ├── slots.js                ← IDs de slots de AdSense
│   └── README.md
│
├── backend/                    ← Backend Node.js (desplegado en Render, NO en Vercel)
│   ├── server.js               ← Servidor principal Express (>1800 líneas)
│   ├── swagger.js              ← Documentación API
│   ├── skills-routes.js        ← Rutas de skills IA
│   ├── package.json
│   ├── render.yaml             ← Config Render
│   ├── railway.json            ← Config Railway
│   └── modules/
│       └── universal-resolver/ ← Módulo resolver de links
│
├── python/                     ← Scripts Python (no en producción web)
│   ├── codehub.py
│   ├── telegram_bot.py
│   └── requirements.txt
│
├── tests/                      ← Tests E2E Playwright
│   └── e2e/
│       ├── api.spec.js
│       ├── home.spec.js
│       ├── opensource.spec.js
│       └── tools.spec.js
│
├── skills/                     ← Definiciones de skills IA
│   ├── index.json
│   └── image-gen/skill.json
│
└── docs/
    └── CNAME                   ← Dominio personalizado (legacy)
```

---

## 4. Sistema de rutas — vercel.json ⚠️ CRÍTICO

**REGLA DE ORO:** Cada vez que se agrega una página nueva, hay que actualizar `vercel.json` con 3 entradas:
1. **rewrite**: `{ "source": "/nueva-pagina", "destination": "/pages/nueva-pagina.html" }`
2. **redirect .html → limpia**: `{ "source": "/nueva-pagina.html", "destination": "/nueva-pagina", "permanent": true }`
3. **redirect /pages/ → limpia**: `{ "source": "/pages/nueva-pagina", "destination": "/nueva-pagina", "permanent": true }`

### Rutas activas actualmente

| URL pública | Archivo físico | Tipo |
|-------------|---------------|------|
| `/` | `index.html` | directo |
| `/tools` | `pages/tools.html` | rewrite |
| `/opensource` | `pages/opensource.html` | rewrite |
| `/servicios` | `pages/servicios.html` | rewrite |
| `/downloader` | `pages/downloader.html` | rewrite |
| `/cv` | `pages/cv.html` | rewrite |
| `/admin-hub` | `pages/admin-hub.html` | rewrite |
| `/codehub-ultra` | `pages/codehub-ultra.html` | rewrite |
| `/analytics` | `pages/analytics.html` | rewrite |
| `/flexbox-labs` | `pages/flexbox-labs.html` | rewrite |
| `/snake` | `games/snake.html` | rewrite |
| `/tetris` | `games/tetris.html` | rewrite |
| `/privacy` | `privacy.html` | rewrite |
| `/terms` | `terms.html` | rewrite |
| `/404` | `404.html` | directo |
| `/offline` | `offline.html` | directo |

### Páginas que NO van en /pages/ (van en la raíz)
- `privacy.html` → `/privacy`
- `terms.html` → `/terms`
- `404.html`, `offline.html`, `index.html`, `googlef6ae0fd15fc626ec.html`

### Checklist para agregar una nueva página

```
1. Crear el archivo: pages/mi-pagina.html
2. En vercel.json → "rewrites": agregar
   { "source": "/mi-pagina", "destination": "/pages/mi-pagina.html" }
3. En vercel.json → "redirects": agregar
   { "source": "/mi-pagina.html", "destination": "/mi-pagina", "permanent": true }
   { "source": "/pages/mi-pagina", "destination": "/mi-pagina", "permanent": true }
4. En sitemap.xml: agregar <url> con la URL pública /mi-pagina
5. Si es página pública: añadir link en el footer de index.html y en la navegación
6. Si es privada/admin: agregar a robots.txt en Disallow
```

---

## 5. URLs del backend (Render)

```javascript
const BACKEND = 'https://codehub-98s6.onrender.com';
```

### Endpoints disponibles

| Método | Ruta | Descripción | Auth |
|--------|------|-------------|------|
| GET | `/api/health` | Estado de todos los servicios | — |
| GET | `/api/apps` | Lista de apps Android | — |
| GET | `/api/stats/supabase` | Stats de Supabase | — |
| GET | `/api/stats/live` | Stats en tiempo real (WebSocket) | — |
| POST | `/api/visit` | Registrar visita | — |
| POST | `/api/chat` | Chatbot EMI IA | rate-limit |
| POST | `/api/contact` | Formulario de contacto | turnstile |
| GET | `/api/ratings` | Ratings de apps | — |
| POST | `/api/ratings` | Guardar rating | — |
| GET | `/api/requests` | Solicitudes de apps | — |
| POST | `/api/requests` | Nueva solicitud de app | — |
| GET | `/api/download/:fileName` | Descarga de APK desde Supabase | — |
| POST | `/api/generate-image` | Generar imagen con IA | rate-limit |
| POST | `/api/check-link` | Verificar link/URL | rate-limit |
| POST | `/api/check-file` | Verificar archivo | rate-limit |
| GET | `/api/image-search` | Búsqueda de imágenes | rate-limit |
| GET | `/api/admin/apps` | Listar apps (admin) | x-admin-key |
| POST | `/api/admin/apps` | Crear app (admin) | x-admin-key |
| PUT | `/api/admin/apps/:id` | Editar app (admin) | x-admin-key |
| DELETE | `/api/admin/apps/:id` | Eliminar app (admin) | x-admin-key |
| POST | `/api/admin/apps/:id/upload` | Subir APK (admin) | x-admin-key |
| POST | `/api/admin/seed` | Seed inicial desde JSON | x-admin-key |
| GET | `/api/admin/visitors` | Registro de visitantes | x-admin-key |
| POST | `/api/push/notify` | Enviar push notification | x-admin-key |
| GET | `/api/docs` | Documentación Swagger UI | — |

---

## 6. Variables de entorno del backend (Render)

```env
GROQ_API_KEY=...           # IA chatbot (Llama)
GEMINI_API_KEY=...         # IA chatbot (Google)
TOGETHER_API_KEY=...       # IA extra
OPENROUTER_API_KEY=...     # IA router
MISTRAL_API_KEY=...        # IA extra
COHERE_API_KEY=...         # IA extra
MONGODB_URI=...            # MongoDB Atlas connection string
ADMIN_KEY=...              # Contraseña del admin-hub
SUPABASE_URL=...           # URL de Supabase
SUPABASE_KEY=...           # Service role key de Supabase
FRONTEND_URL=https://wilson360-labs.vercel.app
RATE_LIMIT_MAX=...         # Máx req por minuto (default 20)
REDIS_URL=...              # Opcional — Railway Redis addon
VAPID_PUBLIC_KEY=...       # Web Push notificaciones
VAPID_PRIVATE_KEY=...
TELEGRAM_BOT_TOKEN=...     # Bot Telegram (storage APKs + alertas)
TELEGRAM_CHAT_ID=...       # Chat del admin
TG_ALERTS_ENABLED=true     # Activa alertas Telegram (opcional, default true)
TG_BURST_MS=4000           # Agrupar eventos repetidos (ms, opcional)
TG_STATUS_HOURS=6          # Resumen periódico cada N horas (opcional)
```

### Telegram Alerts (backend/server.js)

El backend empuja en tiempo real al chat del admin (`TELEGRAM_CHAT_ID`) eventos de **seguridad** y **actividad**:

| Evento | Tipo (`tgAlert`) | Cuándo |
|--------|------------------|--------|
| 🚨 Rate limit superado | `ratelimit:<ruta>` | Alguien excede un limiter (`chatLimiter`/`adminLimiter`) |
| 🔐 Admin key inválida | `adminfail` | Key incorrecta en un endpoint admin |
| 💬 Chat con EMI | `chat` | Cada chat exitoso (agrupado cada 30s) |
| ⚠️ Error `/api/chat` | `chatfail` | Fallo del proveedor IA (agrupado cada 30s) |
| 📩 Contacto | `contact` | Nuevo formulario de contacto (cada 30s) |
| ⭐ Rating | `rating` | Nueva valoración de app (cada 30s) |
| 🙋 Solicitud de app | `appreq` | Nueva solicitud en el tab Requests (cada 30s) |
| ⬇️ Descarga | `download` | Descarga de APK (cada 15s) |
| ➕ App publicada | `adminapp` | Nueva app creada desde el admin-hub (cada 30s) |
| 📊 Resumen de estado | — | Cada `TG_STATUS_HOURS` (6h) con uptime, visitas y stats del día |

- Eventos repetidos se **agrupan en un solo mensaje** (`🔁 xN`) durante la ventana `TG_BURST_MS` para no spamear.
- El envío usa `https` nativo (sin dependencias) y nunca bloquea las peticiones (fire-and-forget).
- El resumen periódico no envía nada hasta 20s después de iniciar el servidor, para no duplicar el de un reinicio.

---

## 7. Páginas y sus responsabilidades

### `index.html` — Página principal
La página más grande del proyecto (~5260 líneas). Contiene todo en un solo archivo.

**Secciones en orden:**
1. `#hero` — Presentación + foto de perfil con anillo animado + partículas canvas
2. `#open-to-work` — Banner disponibilidad freelance
3. `#mi-pueblo` — Sección "Sobre mí" con info personal
4. `#stats` — Estadísticas animadas (proyectos, herramientas, años)
5. `#skills` — Grid de tecnologías dominadas (tabs: Core / 2025 / Tools)

**TAB Core (lenguajes, con barras animadas):** HTML5 90%, CSS3 85%, JavaScript 75%, Python 80%, Git & GitHub 70%, Node.js 65%, TypeScript 65%, SQL 55%, Shell/Bash 50%, Java/Kotlin 45% (Agosto 2026).
6. `#services` — Cards de servicios freelance
7. `#weather-section` — Clima en tiempo real (Open-Meteo API)
8. `#news-section` — Posts recientes del blog (Blogger JSONP feed)
9. `#why-me` — Razones para contratarme
10. `#contact` — Formulario de contacto (EmailJS + Turnstile)
11. `#game` — Mini-juego Snake integrado
12. `#sobre-codehub` — Sección SEO con texto indexable para Google

**Scripts externos cargados:**
- Google Analytics + GTM
- Font Awesome 6.7.2
- EmailJS
- Cloudflare Turnstile
- Google AdSense (Auto ads)
- js/script.js, index-main.js, index-emailjs.js, index-whats-new.js, ux-animations.js, device-detect.js, thinking-orb.js, site-tour.js, emi-voice.js (defer)

**EMI Voice (🎙️, integrado Agosto 2026):**
- Botón micrófono `#emi-mic-btn` en `.ai-input-row` → voz a texto (Web Speech API).
- Toggle "Voz EMI" inyectado en `.ai-bottom-bar` → lee las respuestas en voz alta (auto-speak).
- Idioma automático: `es-GT` / `en-US` según `ch_lang`; se sincroniza en `applyLang()` vía evento `ch:langchange`.
- ⚠️ Requiere `microphone=(self)` en la `Permissions-Policy` de `vercel.json`.

**Layout flexbox (barra lateral deslizable, Agosto 2026):**
- `body` es `display:flex; flex-direction:column; min-height:100dvh` + `main{flex:1}` → el pie de página queda siempre abajo.
- `#side-nav` — sidebar deslizante (drawer a pantalla completa) sobre el header: perfil, navegación por secciones, páginas, juegos y redes.
- `#side-scrim` — overlay oscurecido para cerrar; se cierra con el botón ✕, el scrim, `Escape`, clic en un enlace o al hacer scroll.
- El botón burger del header (`#burger-btn`) está visible en todas las pantallas y controla `toggleMobileNav()` (definido en el inline JS y en `js/index-chat.js`).
- Reemplazó al antiguo dropdown `.mobile-nav` (eliminado).

**Elementos flotantes globales:**
- `#ch-splash` — Splash screen inicial con canvas de partículas
- `#config-panel` — Panel de configuración (fuente, animaciones, idioma)
- `#ai-panel` — Chatbot EMI IA (slide-up panel)
- `#ai-fab` — Botón flotante para abrir el chatbot
- `#hub-dock` — Barra de accesos rápidos flotante
- `#ch-panel-actividad` — Panel lateral de actividad reciente
- `#command-overlay` — Paleta de comandos (Ctrl+K)
- `#pwa-install` — Prompt de instalación PWA
- `#mobile-nav` — Navegación móvil inferior
- `#update-notification` — Notificación de nueva versión disponible

**Constantes importantes en index.html:**
```javascript
const BLOGGER_URL = 'https://codehub-labs.blogspot.com';
// AdSense publisher: ca-pub-3780093322926832
// GA4: G-205F26ETCC
// GTM: GTM-PFVCPFJJ
```

---

### `pages/tools.html` — Herramientas web
34 herramientas organizadas en cards con filtros por categoría.

**Herramientas disponibles:**
1. Generador de Contraseñas
2. Verificador de Fuerza de Contraseña
3. Generador de QR
4. Mi IP y Ubicación
5. Info del Dispositivo
6. Convertidor de Unidades
7. Conversor de Monedas
8. Calculadora de IMC
9. Calculadora de Préstamo
10. Temporizador Pomodoro
11. Paleta de Colores
12. Generador de Memes
13. Compresor de Imágenes
14. Test de Velocidad
15. Generador de Hash (SHA-256/512)
16. Base64 Encode / Decode
17. Regex Tester
18. Generador de UUID
19. Resolver de Links
20. (+ 14 herramientas más en el panel expandible)

**CSS cargado:** `../css/tools.css` + `../css/skills-image-gen.css`  
**JS cargado:** `../js/tools.js`  
**Librerías:** qrcode.min.js (cdnjs), puter.js  
**Backend usado:** `/api/check-link`, `/api/image-search`, `/api/generate-image`

---

### `pages/opensource.html` — Catálogo Open Source
Listado de apps Android de código abierto, cargadas desde el backend MongoDB
(solo apps con `source_repo` configurado, verificadas contra GitHub Releases).

**JS cargado:** `../js/opensource.js`  
**CSS cargado:** `../css/opensource.css`  
**Backend usado:** `GET /api/apps`, `GET /api/dl/:appId`  
**Constante:** `const BACKEND = 'https://codehub-98s6.onrender.com'`

> ⚠️ **AppsHub / novedades.html fue ELIMINADO (Agosto 2026)** por incumplir
> las políticas de AdSense (apps modificadas/premium desbloqueadas). El catálogo
> Open Source quedó como la única sección de apps. `/novedades` redirige a
> `/opensource` y está bloqueado en robots.txt.

---

### `pages/admin-hub.html` — Panel de administración
Panel privado con login por contraseña (validado contra el backend).

**Tabs disponibles:**
- **Apps** — CRUD de apps Android (editar versión, changelog, verificada, badge, links)
- **Visitantes** — Registro de visitor_logs de Supabase
- **Stats** — Gráficas Chart.js con datos de Supabase
- **Solicitudes** — Solicitudes de apps de usuarios
- **Nueva App** — Formulario para añadir nueva app
- **Bulk** — Subida masiva de APKs
- **Status** — Estado de todos los servicios (health check)
- **Base de Datos** — Ejecutar sentencias SQL (dbrun)

**JS cargado:** `../js/admin-hub.js`  
**Auth:** header `x-admin-key: ADMIN_KEY` en cada request al backend  
**NOINDEX:** esta página tiene `<meta name="robots" content="noindex, nofollow">`  
**Cloudflare Turnstile:** incluido para el login

---

### `pages/servicios.html` — Servicios freelance
Página de servicios con precios, paquetes y formulario de contacto freelance.

**JS cargado:** `../js/servicios.js`

---

### `pages/downloader.html` — Video Downloader
Herramienta para descargar videos de redes sociales.

**JS cargado:** `../js/downloader.js`  
**Backend usado:** `/api/check-link`

---

### `pages/cv.html` — Currículum Vitae
CV interactivo descargable.

---

### `pages/codehub-ultra.html` — CodeHub Ultra
Versión premium/extra del sitio con funciones adicionales.

---

### `pages/analytics.html` — Analytics
Dashboard de métricas del sitio.

---

### `privacy.html` — Política de Privacidad ⚠️ EN RAÍZ
Página legal requerida por Google AdSense. **Ubicada en la raíz**, no en `/pages/`.  
Ruta pública: `/privacy`

---

### `terms.html` — Términos de Uso ⚠️ EN RAÍZ
Página legal requerida por Google AdSense. **Ubicada en la raíz**, no en `/pages/`.  
Ruta pública: `/terms`

---

## 8. Blog — Solo feed Blogger (Noticias)

**Blogger externo:** ID `4932034987684289893` · `https://codehub-labs.blogspot.com`
- Feed JSONP: `https://codehub-labs.blogspot.com/feeds/posts/default?alt=json-in-script&max-results=6&callback=_bloggerCallback`
- Se usa en `index.html` → sección `#news-section` (JSONP `_bloggerCallback`).

> 🗑️ **Blog estático ELIMINADO (Agosto 2026):** `pages/blog.html`, `blog/`,
> `js/admin-blog-static.js` y el tab "Blog" de admin-hub fueron removidos por
> completo. La sección frontal fue reemplazada por `#news-section` (feed de Blogger).
> `autoposter/` sigue disponible para generar posts para ese feed.

---

## 9. PWA — Progressive Web App

**Service Worker:** `sw.js` (en raíz)  
**Manifest:** `manifest.json` (en raíz)  
**Shortcuts en PWA:** `/tools`, `/opensource`, `/servicios`, `/downloader`  
**Iconos:** Cloudinary CDN  
**Offline page:** `offline.html`  
**PRECACHE:** incluye los JS del chat (emi-voice.js, ux-animations.js, thinking-orb.js, site-tour.js) y css/components.css para que EMI funcione offline.

---

## 10. Google AdSense — Estado de implementación

> Actualizado: Agosto 2026 — AppsHub/novedades.html ELIMINADO por violar políticas
> (contenido con apps modificadas/premium desbloqueadas). Quedó como única sección
> de apps el catálogo Open Source (sin riesgo de política). Pendiente re-solicitar
> revisión en AdSense.

| Requisito | Estado | Archivo |
|-----------|--------|---------|
| Script AdSense en `<head>` | ✅ Implementado | index.html, tools.html, opensource.html, privacy.html, terms.html |
| ads.txt en raíz | ✅ Existe | ads.txt |
| Política de Privacidad | ✅ Creada | privacy.html → /privacy |
| Términos de Uso | ✅ Creados | terms.html → /terms |
| Links en footer a legal | ✅ Implementados | index.html footer |
| Sección SEO estática (#sobre-codehub) | ✅ Implementada | index.html |
| robots.txt permite crawling | ✅ Correcto | robots.txt |
| sitemap.xml incluye /privacy y /terms | ✅ Incluidos | sitemap.xml |
| Anti-clic-derecho removido | ✅ Removido | index.html |
| beforeunload molesto removido | ✅ Removido | index.html |
| Contenido no apto (apps premium) | ✅ Removido | pages/novedades.html eliminado |
| Modalidad de anuncios | ✅ Auto ads | Sin unidades manuales; solo script en <head> |
| Blog con artículos reales | ⏳ Pendiente publicar | codehub-labs.blogspot.com |
| Solicitar revisión en AdSense | ⏳ Pendiente | Panel AdSense |

**Publisher ID:** `ca-pub-3780093322926832`
**Modalidad:** Auto ads — el script `adsbygoogle.js` se carga en index.html, tools.html, opensource.html, privacy.html y terms.html. Google decide las ubicaciones (activar desde AdSense → Anuncios → Anuncios automáticos).

---

## 11. SEO — Configuración actual

**robots.txt:**
```
User-agent: *
Allow: /
Allow: /privacy
Allow: /terms
Disallow: /backend/
Disallow: /python/
Disallow: /tests/
Disallow: /apps_data.json
Disallow: /admin-hub
Disallow: /novedades
Sitemap: https://wilson360-labs.vercel.app/sitemap.xml
```

**Sitemap URLs indexadas:**
- `/` (priority 1.0)
- `/privacy` (priority 0.6)
- `/terms` (priority 0.6)
- `/opensource` (priority 0.9)
- `/tools` (priority 0.9)
- `/servicios` (priority 0.85)
- `/downloader` (priority 0.8)
- `/cv` (priority 0.75)
- `/codehub-ultra` (priority 0.65)

---

## 12. Reglas para IA asistente

### Al agregar una nueva página:
1. Crear `pages/nueva-pagina.html`
2. Agregar en `vercel.json` → `rewrites` Y `redirects` (3 entradas)
3. Actualizar `sitemap.xml`
4. Si es pública: linkear desde footer/nav
5. Actualizar este documento (`CODEHUB_ESTRUCTURA.md`)

### Al agregar scripts JS externos:
- Revisar el `Content-Security-Policy` en `index.html` (línea ~26) y en el archivo correspondiente
- El CSP define qué dominios están permitidos para `script-src`, `connect-src`, `img-src`, etc.

### Al modificar el CSS:
- Cada página tiene su propio archivo CSS en `/css/`
- Los estilos de `index.html` están mayormente **inline** dentro del `<style>` del mismo archivo
- `admin-hub.html` también tiene estilos inline en `<style>` dentro del HTML

### Rutas relativas de assets en `/pages/`:
- CSS: `../css/archivo.css`
- JS: `../js/archivo.js`
- Imágenes: `../img/archivo.png`
- Splash: `../splash/codehub.png`
- Manifest: `../manifest.json`

### Rutas en raíz (index.html, privacy.html, terms.html):
- CSS: `css/archivo.css`
- JS: `js/archivo.js`
- Imágenes: `img/archivo.png`

### ⚠️ Errores comunes a evitar:
- **NO** poner `privacy.html` o `terms.html` dentro de `/pages/` — van en la raíz
- **NO** agregar una página sin actualizar `vercel.json` — la ruta limpia no funcionará
- **NO** olvidar los 3 pares de entradas en vercel.json (rewrite + redirect .html + redirect /pages/)
- **NO** modificar el `Content-Security-Policy` sin agregar el nuevo dominio a todos los headers relevantes
- **NO** cambiar la estructura de `/pages/` ni `/js/` sin actualizar las referencias relativas `../`
- **NO** usar `localStorage` para datos sensibles — solo para preferencias UI (tema, fuente, configuración)

---

## 13. Contacto y servicios

- **Email:** wilson.e360labs@gmail.com
- **WhatsApp:** +502 4146 8185
- **LinkedIn / GitHub:** wilson.e360labs
- **Servicios:** Desarrollo web, landing pages, tiendas online, bots IA, automatización Python

---

*Este documento debe actualizarse cada vez que se agregue una nueva página, ruta, herramienta o integración al proyecto.*
