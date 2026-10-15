# CODEHUB â€” Documento Estructural
> **Para uso de IA asistente.** Lee este archivo ANTES de modificar cualquier archivo del proyecto.  
> Ãšltima actualizaciÃ³n: Agosto 2026 Â· Autor: Wilson.E Â· wilson.e360labs@gmail.com

---

## 1. Resumen del proyecto

**CodeHub** es un portfolio personal + plataforma de herramientas web, operado por Wilson.E (Guatemala ðŸ‡¬ðŸ‡¹).  
Dominio en producciÃ³n: `https://wilson360-labs.vercel.app`  
Repositorio: GitHub â†’ desplegado automÃ¡ticamente en **Vercel** (frontend) y **Render** (backend Node.js).

---

## 2. Stack tecnolÃ³gico

| Capa | TecnologÃ­a |
|------|-----------|
| Frontend | HTML5, CSS3, JavaScript ES2025 vanilla (sin frameworks) |
| Backend | Node.js + Express.js â†’ `backend/server.js` |
| Base de datos | MongoDB Atlas (mongoose) + Supabase (eventos/stats) |
| Hosting frontend | Vercel (rama main = auto-deploy) |
| Hosting backend | Render (`https://codehub-98s6.onrender.com`) |
| IA chatbot | Groq (Llama), Google Gemini, Anthropic Claude, OpenRouter |
| Formulario contacto | EmailJS |
| Anti-bot | Cloudflare Turnstile |
| Analytics | Google Analytics 4 (G-205F26ETCC) + GTM (GTM-PFVCPFJJ) |
| Publicidad | Google AdSense (ca-pub-3780093322926832) |
| PWA | Service Worker (`sw.js`) + `manifest.json` |
| Blog | Blogger JSONP feed pÃºblico |
| Almacenamiento APKs | Supabase Storage (bucket: `codehub-apks`) |
| CDN imÃ¡genes | Cloudinary |
| Push notifications | Web Push API + VAPID keys en backend |

---

## 3. Ãrbol de archivos completo

```
CodeHub/
â”‚
â”œâ”€â”€ index.html                  â† PÃ¡gina principal (Ãºnica SPA-like, ~5260 lÃ­neas)
â”œâ”€â”€ 404.html                    â† PÃ¡gina de error personalizada
â”œâ”€â”€ offline.html                â† PÃ¡gina offline PWA
â”œâ”€â”€ manifest.json               â† PWA manifest
â”œâ”€â”€ sw.js                       â† Service Worker (cachÃ© PWA)
â”œâ”€â”€ robots.txt                  â† ConfiguraciÃ³n para crawlers
â”œâ”€â”€ sitemap.xml                 â† Sitemap para SEO
â”œâ”€â”€ ads.txt                     â† Archivo autorizaciÃ³n AdSense
â”œâ”€â”€ vercel.json                 â† âš ï¸ CRÃTICO â€” Rutas, rewrites, redirects, headers
â”œâ”€â”€ render.yaml                 â† Config despliegue Render (Blueprint)
â”œâ”€â”€ package.json                â† Dependencias frontend
â”œâ”€â”€ googlef6ae0fd15fc626ec.html â† VerificaciÃ³n Google Search Console
â”œâ”€â”€ apps_data.json              â† Seed inicial de apps (respaldo)
â”œâ”€â”€ privacy.html                â† PolÃ­tica de Privacidad (en raÃ­z, no en /pages/)
â”œâ”€â”€ terms.html                  â† TÃ©rminos de Uso (en raÃ­z, no en /pages/)
â”‚
â”œâ”€â”€ pages/                      â† PÃ¡ginas secundarias (todas ruteadas via vercel.json)
â”‚   â”œâ”€â”€ tools.html          â†’ ruta pÃºblica: /tools
â”‚   â”œâ”€â”€ opensource.html     â†’ ruta pÃºblica: /opensource
â”‚   â”œâ”€â”€ servicios.html          â†’ ruta pÃºblica: /servicios
â”‚   â”œâ”€â”€ downloader.html         â†’ ruta pÃºblica: /downloader
â”‚   â”œâ”€â”€ cv.html                 â†’ ruta pÃºblica: /cv
â”‚   â”œâ”€â”€ admin-hub.html          â†’ ruta pÃºblica: /admin-hub (noindex)
â”‚   â”œâ”€â”€ codehub-ultra.html      â†’ ruta pÃºblica: /codehub-ultra
â”‚   â””â”€â”€ analytics.html          â†’ ruta pÃºblica: /analytics
â”‚
â”œâ”€â”€ games/
â”‚   â”œâ”€â”€ snake.html              â†’ ruta pÃºblica: /snake
â”‚   â”œâ”€â”€ snake.js
â”‚   â”œâ”€â”€ tetris.html             â†’ ruta pÃºblica: /tetris
â”‚   â””â”€â”€ tetris.js
â”‚
â”œâ”€â”€ css/
â”‚   â”œâ”€â”€ index.css               â† Estilos principales de index.html
â”‚   â”œâ”€â”€ index-responsive.css    â† Media queries de index.html
â”‚   â”œâ”€â”€ components.css          â† Componentes reutilizables
â”‚   â”œâ”€â”€ tools.css               â† Estilos de /tools
â”‚   â”œâ”€â”€ opensource.css          â† Base styles del catÃ¡logo Open Source
â”‚   â”œâ”€â”€ servicios.css           â† Estilos de /servicios
â”‚   â”œâ”€â”€ downloader.css          â† Estilos de /downloader
â”‚   â”œâ”€â”€ admin-hub.css           â† Estilos extras del admin
â”‚   â”œâ”€â”€ skills-image-gen.css    â† Generador de imÃ¡genes IA
â”‚   â””â”€â”€ snake.css               â† Estilos del juego Snake
â”‚
â”œâ”€â”€ js/
â”‚   â”œâ”€â”€ (Purga Sep 2026: eliminados index-chat.js, index-emailjs.js e index-whats-new.js â€” duplicados legacy; el cÃ³digo real vive inline en index.html)
â”‚   â”œâ”€â”€ emi-voice.js            â† ðŸŽ™ï¸ EMI Voice (micrÃ³fono + auto-speak) â€” integrado en index.html
â”‚   â”œâ”€â”€ opensource.js           â† LÃ³gica de /opensource (catÃ¡logo, filtros, ratings)
â”‚   â”œâ”€â”€ tools.js                â† LÃ³gica de todas las 34 herramientas
â”‚   â”œâ”€â”€ admin-hub.js            â† LÃ³gica completa del panel admin
â”‚   â”œâ”€â”€ servicios.js            â† LÃ³gica de /servicios
â”‚   â”œâ”€â”€ downloader.js           â† LÃ³gica del video downloader
â”‚   â”œâ”€â”€ skills-image-gen.js     â† Generador de imÃ¡genes IA (Pollinations)
â”‚   â”œâ”€â”€ theme-switcher.js       â† Cambio de tema claro/oscuro
â”‚   â”œâ”€â”€ updater.js              â† DetecciÃ³n de updates PWA
â”‚   â”œâ”€â”€ ux-animations.js        â† Animaciones globales (Intersection Observer)
â”‚   â”œâ”€â”€ thinking-orb.js         â† Orb de pensamiento de EMI
â”‚   â”œâ”€â”€ device-detect.js        â† DetecciÃ³n mÃ³vil/escritorio
â”‚   â”œâ”€â”€ site-tour.js            â† Tour guiado del sitio
â”‚   â”œâ”€â”€ live-update-check.js    â† Check de actualizaciones en vivo
â”‚   â””â”€â”€ script.js               â† Utilidades globales
â”‚
â”œâ”€â”€ img/                        â† ImÃ¡genes de apps Android (thumbnails)
â”œâ”€â”€ splash/                     â† Logo e imÃ¡genes del splash screen
â”‚   â”œâ”€â”€ codehub.png
â”‚   â”œâ”€â”€ codehub-splash.png
â”‚   â””â”€â”€ codehub-splash.webp
â”‚
â”œâ”€â”€ ads/
â”‚   â”œâ”€â”€ slots.js                â† IDs de slots de AdSense
â”‚   â””â”€â”€ README.md
â”‚
â”œâ”€â”€ backend/                    â† Backend Node.js (desplegado en Render, NO en Vercel)
â”‚   â”œâ”€â”€ server.js               â† Servidor principal Express (>1800 lÃ­neas)
â”‚   â”œâ”€â”€ swagger.js              â† DocumentaciÃ³n API
â”‚   â”œâ”€â”€ package.json
â”‚   â”œâ”€â”€ render.yaml             â† Config Render
â”‚   â”œâ”€â”€ RENDER-DEPLOY.md        â† GuÃ­a de despliegue en Render
â”‚   â””â”€â”€ modules/
â”‚       â””â”€â”€ universal-resolver/ â† MÃ³dulo resolver de links
â”‚
â”œâ”€â”€ python/                     â† Scripts Python (no en producciÃ³n web)
â”‚   â”œâ”€â”€ codehub.py
â”‚   â”œâ”€â”€ telegram_bot.py
â”‚   â””â”€â”€ requirements.txt
â”‚
â”œâ”€â”€ tests/                      â† Tests E2E Playwright
â”‚   â””â”€â”€ e2e/
â”‚       â”œâ”€â”€ api.spec.js
â”‚       â”œâ”€â”€ home.spec.js
â”‚       â”œâ”€â”€ opensource.spec.js
â”‚       â””â”€â”€ tools.spec.js
â”‚
â”œâ”€â”€ skills/                     â† Definiciones de skills IA
â”‚   â”œâ”€â”€ index.json
â”‚   â”œâ”€â”€ image-gen/skill.json    â† Generador de imÃ¡genes educativas
â”‚   â”œâ”€â”€ pdf-ia/skill.json       â† PDF IA â€” chat con tu documento (extracciÃ³n 100% local)
â”‚   â””â”€â”€ ocr-ia/skill.json       â† OCR local con Tesseract.js (0 tokens)
â”‚
â””â”€â”€ docs/
    â””â”€â”€ CNAME                   â† Dominio personalizado (legacy)
```

---

## 4. Sistema de rutas â€” vercel.json âš ï¸ CRÃTICO

**REGLA DE ORO:** Cada vez que se agrega una pÃ¡gina nueva, hay que actualizar `vercel.json` con 3 entradas:
1. **rewrite**: `{ "source": "/nueva-pagina", "destination": "/pages/nueva-pagina.html" }`
2. **redirect .html â†’ limpia**: `{ "source": "/nueva-pagina.html", "destination": "/nueva-pagina", "permanent": true }`
3. **redirect /pages/ â†’ limpia**: `{ "source": "/pages/nueva-pagina", "destination": "/nueva-pagina", "permanent": true }`

### Rutas activas actualmente

| URL pÃºblica | Archivo fÃ­sico | Tipo |
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

### PÃ¡ginas que NO van en /pages/ (van en la raÃ­z)
- `privacy.html` â†’ `/privacy`
- `terms.html` â†’ `/terms`
- `404.html`, `offline.html`, `index.html`, `googlef6ae0fd15fc626ec.html`

### Checklist para agregar una nueva pÃ¡gina

```
1. Crear el archivo: pages/mi-pagina.html
2. En vercel.json â†’ "rewrites": agregar
   { "source": "/mi-pagina", "destination": "/pages/mi-pagina.html" }
3. En vercel.json â†’ "redirects": agregar
   { "source": "/mi-pagina.html", "destination": "/mi-pagina", "permanent": true }
   { "source": "/pages/mi-pagina", "destination": "/mi-pagina", "permanent": true }
4. En sitemap.xml: agregar <url> con la URL pÃºblica /mi-pagina
5. Si es pÃ¡gina pÃºblica: aÃ±adir link en el footer de index.html y en la navegaciÃ³n
6. Si es privada/admin: agregar a robots.txt en Disallow
```

---

## 5. URLs del backend (Render)

```javascript
const BACKEND = 'https://codehub-98s6.onrender.com';
```

### Endpoints disponibles

| MÃ©todo | Ruta | DescripciÃ³n | Auth |
|--------|------|-------------|------|
| GET | `/api/health` | Estado de todos los servicios | â€” |
| GET | `/api/skills` | CatÃ¡logo de skills IA (con presets/UI) | â€” |
| GET | `/api/skills/:id` | Detalle de una skill (p.ej. `pdf-ia`) | â€” |
| GET | `/api/apps` | Lista de apps Android | â€” |
| GET | `/api/stats/supabase` | Stats de Supabase | â€” |
| GET | `/api/stats/live` | Stats en tiempo real (WebSocket) | â€” |
| POST | `/api/visit` | Registrar visita | â€” |
| POST | `/api/chat` | Chatbot EMI IA | rate-limit |
| POST | `/api/contact` | Formulario de contacto | turnstile |
| GET | `/api/ratings` | Ratings de apps | â€” |
| POST | `/api/ratings` | Guardar rating | â€” |
| GET | `/api/requests` | Solicitudes de apps | â€” |
| POST | `/api/requests` | Nueva solicitud de app | â€” |
| GET | `/api/download/:fileName` | Descarga de APK desde Supabase | â€” |
| POST | `/api/generate-image` | Generar imagen con IA | rate-limit |
| POST | `/api/check-link` | Verificar link/URL | rate-limit |
| POST | `/api/check-file` | Verificar archivo | rate-limit |
| GET | `/api/image-search` | BÃºsqueda de imÃ¡genes | rate-limit |
| GET | `/api/admin/apps` | Listar apps (admin) | x-admin-key |
| POST | `/api/admin/apps` | Crear app (admin) | x-admin-key |
| PUT | `/api/admin/apps/:id` | Editar app (admin) | x-admin-key |
| DELETE | `/api/admin/apps/:id` | Eliminar app (admin) | x-admin-key |
| POST | `/api/admin/apps/:id/upload` | Subir APK (admin) | x-admin-key |
| POST | `/api/admin/seed` | Seed inicial desde JSON | x-admin-key |
| GET | `/api/admin/visitors` | Registro de visitantes | x-admin-key |
| POST | `/api/push/subscribe` | Registrar suscripciÃ³n + ubicaciÃ³n del usuario | â€” |
| POST | `/api/push/unsubscribe` | Eliminar suscripciÃ³n | â€” |
| POST | `/api/push/settings` | Actualizar ubicaciÃ³n/preferencias de alertas | â€” |
| POST | `/api/push/notify` | Enviar push notification a una suscripciÃ³n | â€” |
| GET | `/api/push/weather/check` | Revisar clima y avisar por push si cambiÃ³ | â€” |
| GET | `/api/docs` | DocumentaciÃ³n Swagger UI | â€” |

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
ADMIN_KEY=...              # ContraseÃ±a del admin-hub
SUPABASE_URL=...           # URL de Supabase
SUPABASE_KEY=...           # Service role key de Supabase
FRONTEND_URL=https://wilson360-labs.vercel.app
RATE_LIMIT_MAX=...         # MÃ¡x req por minuto (default 20)
REDIS_URL=...              # Opcional â€” Redis cachÃ© addon (Upstash / Redis Cloud)
VAPID_PUBLIC_KEY=...       # Web Push notificaciones
VAPID_PRIVATE_KEY=...
TELEGRAM_BOT_TOKEN=...     # Bot Telegram (storage APKs + alertas)
TELEGRAM_CHAT_ID=...       # Chat del admin
TG_ALERTS_ENABLED=true     # Activa alertas Telegram (opcional, default true)
TG_BURST_MS=4000           # Agrupar eventos repetidos (ms, opcional)
TG_STATUS_HOURS=6          # Resumen periÃ³dico cada N horas (opcional)
```

### Telegram Alerts (backend/server.js)

El backend empuja en tiempo real al chat del admin (`TELEGRAM_CHAT_ID`) eventos de **seguridad** y **actividad**:

| Evento | Tipo (`tgAlert`) | CuÃ¡ndo |
|--------|------------------|--------|
| ðŸš¨ Rate limit superado | `ratelimit:<ruta>` | Alguien excede un limiter (`chatLimiter`/`adminLimiter`) |
| ðŸ” Admin key invÃ¡lida | `adminfail` | Key incorrecta en un endpoint admin |
| ðŸ’¬ Chat con EMI | `chat` | Cada chat exitoso (agrupado cada 30s) |
| âš ï¸ Error `/api/chat` | `chatfail` | Fallo del proveedor IA (agrupado cada 30s) |
| ðŸ“© Contacto | `contact` | Nuevo formulario de contacto (cada 30s) |
| â­ Rating | `rating` | Nueva valoraciÃ³n de app (cada 30s) |
| ðŸ™‹ Solicitud de app | `appreq` | Nueva solicitud en el tab Requests (cada 30s) |
| â¬‡ï¸ Descarga | `download` | Descarga de APK (cada 15s) |
| âž• App publicada | `adminapp` | Nueva app creada desde el admin-hub (cada 30s) |
| ðŸ“Š Resumen de estado | â€” | Cada `TG_STATUS_HOURS` (6h) con uptime, visitas y stats del dÃ­a |

- Eventos repetidos se **agrupan en un solo mensaje** (`ðŸ” xN`) durante la ventana `TG_BURST_MS` para no spamear.
- El envÃ­o usa `https` nativo (sin dependencias) y nunca bloquea las peticiones (fire-and-forget).
- El resumen periÃ³dico no envÃ­a nada hasta 20s despuÃ©s de iniciar el servidor, para no duplicar el de un reinicio.

### Push Notifications â€” alertas de clima (backend/server.js)

El usuario se suscribe desde `index.html` con su **ubicaciÃ³n** (lat/lon/ciudad/Ã¡rea/huso) y el servidor le avisa por **Web Push** al dispositivo (panel del telÃ©fono) **solo cuando cambia la condiciÃ³n del clima** â€” sin spam:

- **SuscripciÃ³n:** el frontend hace `POST /api/push/subscribe` con `{ subscription, location, prefs }`. Los datos del usuario (endpoint, keys, lat/lon, ciudad, paÃ­s, timezone, user-agent, `alerts`) se guardan en la tabla Supabase `push_subs` (o en memoria si Supabase no estÃ¡ configurado).
- **UbicaciÃ³n:** se detecta por GPS (`requestGeoLocation` + Nominatim con distrito/regiÃ³n), por IP (`ipapi.co` â†’ `ipwho.is` â†’ `ip-api.com`) o bÃºsqueda manual, y se sincroniza con `POST /api/push/settings` (se guarda tambiÃ©n en `localStorage`: `ch_user_lat/lon/city/country`).
- **Scheduler:** cada 25 min el backend agrupa suscripciones por coordenadas (redondeadas a 0.1Â°) y consulta Open-Meteo. Si hay alerta nueva (lluvia ðŸŒ§, tormenta â›ˆ, viento ðŸ’¨ >50 km/h, calor ðŸŒ¡ >35Â°C, frÃ­o ðŸ¥¶ <0Â°C) envÃ­a el push con la **recomendaciÃ³n/prevenciÃ³n**. Cuando la condiciÃ³n se supera, resetea el estado para volver a avisar en el prÃ³ximo cambio.
- **Prefs del usuario:** el toggle "Alertas de clima en mi dispositivo" (secciÃ³n Clima) enciende/apaga `alerts` vÃ­a `/api/push/settings`. El estado queda en `localStorage` (`ch_weather_alerts`) y en el backend.
- **Tabla Supabase:** `backend/push_subs.sql` (crear a mano en el SQL Editor, o el backend intenta crearla sola al arrancar vÃ­a `exec_sql`).
- **VAPID:** `VAPID_PUBLIC_KEY` / `VAPID_PRIVATE_KEY` (si faltan usa el par de ejemplo de web-push que coincide con el public key del frontend). Puedes generar tu propio par con `npx web-push generate-vapid-keys`.
- **sw.js** muestra la notificaciÃ³n (tipo `weather`, acciones "Ver clima", tag `codehub-weather`) y al hacer clic abre `/#weather-section`.

---

## 7. PÃ¡ginas y sus responsabilidades

### `index.html` â€” PÃ¡gina principal
La pÃ¡gina mÃ¡s grande del proyecto (~5260 lÃ­neas). Contiene todo en un solo archivo.

**Secciones en orden:**
1. `#hero` â€” PresentaciÃ³n + foto de perfil con anillo animado + partÃ­culas canvas
2. `#open-to-work` â€” Banner disponibilidad freelance
3. `#mi-pueblo` â€” SecciÃ³n "Sobre mÃ­" con info personal
4. `#stats` â€” EstadÃ­sticas animadas (proyectos, herramientas, aÃ±os)
5. `#skills` â€” Grid de tecnologÃ­as dominadas (tabs: Core / 2025 / Tools)

**TAB Core (lenguajes, con barras animadas):** HTML5 90%, CSS3 85%, JavaScript 75%, Python 80%, Git & GitHub 70%, Node.js 65%, TypeScript 65%, SQL 55%, Shell/Bash 50%, Java/Kotlin 45% (Agosto 2026).
6. `#services` â€” Cards de servicios freelance
7. `#weather-section` â€” Clima en tiempo real (Open-Meteo API)
8. `#news-section` â€” Posts recientes del blog (Blogger JSONP feed)
9. `#why-me` â€” Razones para contratarme
10. `#open-to-work` â€” "Â¿Buscas un desarrollador?" con formulario de contacto unificado (EmailJS + Turnstile) â€” el antiguo `#contact` se unificÃ³ aquÃ­
11. `#game` â€” Mini-juego Snake integrado
12. `#sobre-codehub` â€” SecciÃ³n SEO con texto indexable para Google

**Scripts externos cargados:**
- Google Analytics + GTM
- Font Awesome 6.7.2
- EmailJS
- Cloudflare Turnstile
- Google AdSense (Auto ads)
- js/script.js, index-emailjs.js, index-whats-new.js, ux-animations.js, device-detect.js, thinking-orb.js, site-tour.js, emi-voice.js (defer)

**EMI Voice (ðŸŽ™ï¸, integrado Agosto 2026):**
- BotÃ³n micrÃ³fono `#emi-mic-btn` en `.ai-input-row` â†’ voz a texto (Web Speech API).
- Toggle "Voz EMI" inyectado en `.ai-bottom-bar` â†’ lee las respuestas en voz alta (auto-speak).
- Idioma automÃ¡tico: `es-GT` / `en-US` segÃºn `ch_lang`; se sincroniza en `applyLang()` vÃ­a evento `ch:langchange`.
- âš ï¸ Requiere `microphone=(self)` en la `Permissions-Policy` de `vercel.json`.

**Layout flexbox (barra lateral deslizable, Agosto 2026):**
- `body` es `display:flex; flex-direction:column; min-height:100dvh` + `main{flex:1}` â†’ el pie de pÃ¡gina queda siempre abajo.
- `#side-nav` â€” sidebar deslizante (drawer a pantalla completa) sobre el header: perfil, navegaciÃ³n por secciones, pÃ¡ginas, juegos y redes.
- `#side-scrim` â€” overlay oscurecido para cerrar; se cierra con el botÃ³n âœ•, el scrim, `Escape`, clic en un enlace o al hacer scroll.
- El botÃ³n burger del header (`#burger-btn`) estÃ¡ visible en todas las pantallas y controla `toggleMobileNav()` (definido en el inline JS y en `js/index-chat.js`).
- ReemplazÃ³ al antiguo dropdown `.mobile-nav` (eliminado).

**Elementos flotantes globales:**
- `#ch-splash` â€” Splash screen inicial con canvas de partÃ­culas
- `#config-panel` â€” Panel de configuraciÃ³n (fuente, animaciones, idioma)
- `#ai-panel` â€” Chatbot EMI IA (slide-up panel)
- `#ai-fab` â€” BotÃ³n flotante para abrir el chatbot
- `#hub-dock` â€” Barra de accesos rÃ¡pidos flotante
- `#ch-panel-actividad` â€” Panel lateral de actividad reciente
- `#command-overlay` â€” Paleta de comandos (Ctrl+K)
- `#pwa-install` â€” Prompt de instalaciÃ³n PWA
- `#mobile-nav` â€” NavegaciÃ³n mÃ³vil inferior
- `#update-notification` â€” NotificaciÃ³n de nueva versiÃ³n disponible

**Constantes importantes en index.html:**
```javascript
// AdSense publisher: ca-pub-3780093322926832
// GA4: G-205F26ETCC
// GTM: GTM-PFVCPFJJ
```

---

### `pages/tools.html` â€” Herramientas web
34 herramientas organizadas en cards con filtros por categorÃ­a.

**Herramientas disponibles:**
1. Generador de ContraseÃ±as
2. Verificador de Fuerza de ContraseÃ±a
3. Generador de QR
4. Mi IP y UbicaciÃ³n
5. Info del Dispositivo
6. Convertidor de Unidades
7. Conversor de Monedas
8. Calculadora de IMC
9. Calculadora de PrÃ©stamo
10. Temporizador Pomodoro
11. Paleta de Colores
12. Generador de Memes
13. Compresor de ImÃ¡genes
14. Test de Velocidad
15. Generador de Hash (SHA-256/512)
16. Base64 Encode / Decode
17. Regex Tester
18. Generador de UUID
19. Resolver de Links
20. (+ 14 herramientas mÃ¡s en el panel expandible)

**CSS cargado:** `../css/tools.css` + `../css/skills-image-gen.css`  
**JS cargado:** `../js/tools.js`  
**LibrerÃ­as:** qrcode.min.js (cdnjs), puter.js  
**Backend usado:** `/api/check-link`, `/api/image-search`, `/api/generate-image`

---

### `pages/opensource.html` â€” CatÃ¡logo Open Source
Listado de apps Android de cÃ³digo abierto, cargadas desde el backend MongoDB
(solo apps con `source_repo` configurado, verificadas contra GitHub Releases).

**JS cargado:** `../js/opensource.js`  
**CSS cargado:** `../css/opensource.css`  
**Backend usado:** `GET /api/apps`, `GET /api/dl/:appId`  
**Constante:** `const BACKEND = 'https://codehub-98s6.onrender.com'`

> âš ï¸ **AppsHub / novedades.html fue ELIMINADO (Agosto 2026)** por incumplir
> las polÃ­ticas de AdSense (apps modificadas/premium desbloqueadas). El catÃ¡logo
> Open Source quedÃ³ como la Ãºnica secciÃ³n de apps. `/novedades` redirige a
> `/opensource` y estÃ¡ bloqueado en robots.txt.

---

### `pages/admin-hub.html` â€” Panel de administraciÃ³n
Panel privado con login por contraseÃ±a (validado contra el backend).

**Tabs disponibles:**
- **Apps** â€” CRUD de apps Android (editar versiÃ³n, changelog, verificada, badge, links)
- **Visitantes** â€” Registro de visitor_logs de Supabase
- **Stats** â€” GrÃ¡ficas Chart.js con datos de Supabase
- **Solicitudes** â€” Solicitudes de apps de usuarios
- **Nueva App** â€” Formulario para aÃ±adir nueva app
- **Bulk** â€” Subida masiva de APKs
- **Status** â€” Estado de todos los servicios (health check)
- **Base de Datos** â€” Ejecutar sentencias SQL (dbrun)

**JS cargado:** `../js/admin-hub.js`  
**Auth:** header `x-admin-key: ADMIN_KEY` en cada request al backend  
**NOINDEX:** esta pÃ¡gina tiene `<meta name="robots" content="noindex, nofollow">`  
**Cloudflare Turnstile:** incluido para el login

---

### `pages/servicios.html` â€” Servicios freelance
PÃ¡gina de servicios con precios, paquetes y formulario de contacto freelance.

**JS cargado:** `../js/servicios.js`

---

### `pages/downloader.html` â€” Video Downloader
Herramienta para descargar videos de redes sociales.

**JS cargado:** `../js/downloader.js`  
**Backend usado:** `/api/check-link`

---

### `pages/cv.html` â€” CurrÃ­culum Vitae
CV interactivo descargable.

---

### `pages/codehub-ultra.html` â€” CodeHub Ultra
VersiÃ³n premium/extra del sitio con funciones adicionales.

---

### `pages/analytics.html` â€” Analytics
Dashboard de mÃ©tricas del sitio.

---

### `privacy.html` â€” PolÃ­tica de Privacidad âš ï¸ EN RAÃZ
PÃ¡gina legal requerida por Google AdSense. **Ubicada en la raÃ­z**, no en `/pages/`.  
Ruta pÃºblica: `/privacy`

---

### `terms.html` â€” TÃ©rminos de Uso âš ï¸ EN RAÃZ
PÃ¡gina legal requerida por Google AdSense. **Ubicada en la raÃ­z**, no en `/pages/`.  
Ruta pÃºblica: `/terms`

---

## 8. Noticias (secciÃ³n #news-section)

Feed RSS de BBC Mundo renderizado en `index.html`. Originalmente consumÃ­a Blogger RSS.
Clases CSS usan el prefijo `.blogger-*` por convenciÃ³n heredada.

> ðŸ—‘ï¸ **Blog estÃ¡tico y autoposter ELIMINADOS (Agosto 2026):** `pages/blog.html`, `blog/`,
> `js/admin-blog-static.js`, `autoposter/` y `autoposter-workflow.yml` fueron removidos.
> La secciÃ³n frontal fue reemplazada por `#news-section` (feed RSS de BBC Mundo).

---

## 9. PWA â€” Progressive Web App

**Service Worker:** `sw.js` (en raÃ­z)  
**Manifest:** `manifest.json` (en raÃ­z)  
**Shortcuts en PWA:** `/tools`, `/opensource`, `/servicios`, `/downloader`  
**Iconos:** Cloudinary CDN  
**Offline page:** `offline.html`  
**PRECACHE:** incluye los JS del chat (emi-voice.js, ux-animations.js, thinking-orb.js, site-tour.js) y css/components.css para que EMI funcione offline.

---

## 10. Google AdSense â€” Estado de implementaciÃ³n

> Actualizado: Agosto 2026 â€” AppsHub/novedades.html ELIMINADO por violar polÃ­ticas
> (contenido con apps modificadas/premium desbloqueadas). QuedÃ³ como Ãºnica secciÃ³n
> de apps el catÃ¡logo Open Source (sin riesgo de polÃ­tica). Pendiente re-solicitar
> revisiÃ³n en AdSense.

| Requisito | Estado | Archivo |
|-----------|--------|---------|
| Script AdSense en `<head>` | âœ… Implementado | index.html, tools.html, opensource.html, privacy.html, terms.html |
| ads.txt en raÃ­z | âœ… Existe | ads.txt |
| PolÃ­tica de Privacidad | âœ… Creada | privacy.html â†’ /privacy |
| TÃ©rminos de Uso | âœ… Creados | terms.html â†’ /terms |
| Links en footer a legal | âœ… Implementados | index.html footer |
| SecciÃ³n SEO estÃ¡tica (#sobre-codehub) | âœ… Implementada | index.html |
| robots.txt permite crawling | âœ… Correcto | robots.txt |
| sitemap.xml incluye /privacy y /terms | âœ… Incluidos | sitemap.xml |
| Anti-clic-derecho removido | âœ… Removido | index.html |
| beforeunload molesto removido | âœ… Removido | index.html |
| Contenido no apto (apps premium) | âœ… Removido | pages/novedades.html eliminado |
| Modalidad de anuncios | âœ… Auto ads | Sin unidades manuales; solo script en <head> |
| Solicitar revisiÃ³n en AdSense | â³ Pendiente | Panel AdSense |

**Publisher ID:** `ca-pub-3780093322926832`
**Modalidad:** Auto ads â€” el script `adsbygoogle.js` se carga en index.html, tools.html, opensource.html, privacy.html y terms.html. Google decide las ubicaciones (activar desde AdSense â†’ Anuncios â†’ Anuncios automÃ¡ticos).

---

## 11. SEO â€” ConfiguraciÃ³n actual

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

### Al agregar una nueva pÃ¡gina:
1. Crear `pages/nueva-pagina.html`
2. Agregar en `vercel.json` â†’ `rewrites` Y `redirects` (3 entradas)
3. Actualizar `sitemap.xml`
4. Si es pÃºblica: linkear desde footer/nav
5. Actualizar este documento (`CODEHUB_ESTRUCTURA.md`)

### Al agregar scripts JS externos:
- Revisar el `Content-Security-Policy` en `index.html` (lÃ­nea ~26) y en el archivo correspondiente
- El CSP define quÃ© dominios estÃ¡n permitidos para `script-src`, `connect-src`, `img-src`, etc.

### Al modificar el CSS:
- Cada pÃ¡gina tiene su propio archivo CSS en `/css/`
- Los estilos de `index.html` estÃ¡n mayormente **inline** dentro del `<style>` del mismo archivo
- `admin-hub.html` tambiÃ©n tiene estilos inline en `<style>` dentro del HTML

### Rutas relativas de assets en `/pages/`:
- CSS: `../css/archivo.css`
- JS: `../js/archivo.js`
- ImÃ¡genes: `../img/archivo.png`
- Splash: `../splash/codehub.png`
- Manifest: `../manifest.json`

### Rutas en raÃ­z (index.html, privacy.html, terms.html):
- CSS: `css/archivo.css`
- JS: `js/archivo.js`
- ImÃ¡genes: `img/archivo.png`

### âš ï¸ Errores comunes a evitar:
- **NO** poner `privacy.html` o `terms.html` dentro de `/pages/` â€” van en la raÃ­z
- **NO** agregar una pÃ¡gina sin actualizar `vercel.json` â€” la ruta limpia no funcionarÃ¡
- **NO** olvidar los 3 pares de entradas en vercel.json (rewrite + redirect .html + redirect /pages/)
- **NO** modificar el `Content-Security-Policy` sin agregar el nuevo dominio a todos los headers relevantes
- **NO** cambiar la estructura de `/pages/` ni `/js/` sin actualizar las referencias relativas `../`
- **NO** usar `localStorage` para datos sensibles â€” solo para preferencias UI (tema, fuente, configuraciÃ³n)

---

## 13. Contacto y servicios

- **Email:** wilson.e360labs@gmail.com
- **WhatsApp:** +502 3513 1808
- **LinkedIn / GitHub:** wilson.e360labs
- **Servicios:** Desarrollo web, landing pages, tiendas online, bots IA, automatizaciÃ³n Python

---

*Este documento debe actualizarse cada vez que se agregue una nueva pÃ¡gina, ruta, herramienta o integraciÃ³n al proyecto.*
