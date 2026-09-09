# CODEHUB — Roadmap de Evolución a Plataforma Web de Alta Calidad

> **Propósito:** evolucionar CodeHub de "portfolio + herramientas" a una **web-producto**
> de calidad, manteniendo el stack vanilla (HTML/CSS/JS) por razones de SEO, PWA y costo.
> Estado: plan aprobado (Septiembre 2026) · Sin código aún — este documento es la hoja de ruta.
> Autor: Wilson.E / Asistente IA (modo señor programador).

---

## 0. Diagnóstico honesto (punto de partida)

CodeHub **ya no es una webview**: tiene backend Node/Express (~80 endpoints), PWA,
auth Google/local, chat EMI en streaming, generación de imágenes, TTS, push (clima/sismos),
admin dashboard (con control de GitHub), catálogo Open Source, ratings, analytics y 34 tools.
**Sus vulnerabilidades reales:**

| Área | Problema concreto |
|------|-------------------|
| Monolito frontend | `index.html` = 8.094 líneas; ~27 JS sueltos cargados en el index |
| Deuda duplicada | `js/index-chat.js` marcado "legacy" (chat real inline); otros duplicados latentes |
| Sin build | No hay bundler, `package-lock.json` ni hashing automático → `?v=` a mano |
| Sin CI de calidad | 11 workflows (APK/catálogo/deploy) pero **ninguno de lint/test/Lighthouse**; `tests/e2e/` vacío |
| Cuenta incompleta | Backend tiene `/api/auth/*` (register/login/google/refresh/session), el frontend no lo usa como producto |
| Monetización | AdSense pendiente de revisión; lección aprendida: AppsHub eliminado por políticas |

---

## 1. Principios de diseño (no negociables)

1. **Mantener MPA vanilla** — no convertir a SPA/React/Vue: SEO, PWA y simplicidad dependen de ello.
2. **Cada mejora debe ser verificable** sin navegador: scripts de auditoría (audit-refs, check-inline, sintaxis).
3. **No romper la cascada CSS** — `index.css → components.css → index-responsive.css → site-tour.css → viewport-guard.css` (AGENTS.md).
4. **No agregar features sueltas** sin consolidar deuda primero.
5. **Todo asset nuevo** respetar cache immutable de Vercel (`?v=YYYYMMDDx`) y PRECACHE de `sw.js`.
6. **Rendimiento como feature**: budgets de LCP/CLS/TBT; móvil 380–720px y escritorio, claro y oscuro.

---

## 2. Fase 0 — Cimientos (prioridad máxima)

Racional: cualquier feature construida sobre un monolito sin build/CI agranda el problema.

### 2.1 Build ligero con esbuild (sin framework)
- **Qué:** bundle + minify de los JS del index (y páginas que se beneficien) con esbuild, salida con hash por contenido.
- **Beneficios:** elimina el versionado manual `?v=`; baja bytes en producción; prepara tree-shaking futuro.
- **Restricciones:** el CRÍTICO es no romper el orden de carga inline ni el CSP; el output de esbuild debe cumplir `script-src 'self'` (sin inline). Evaluar por página: index solo con `js/script.js`-style modular; el chat inline NO se toca en Fase 0.
- **Definición de hecho:** `npm run build` produce `dist/` con JS minificados; `index.html` referencia solo minificados con hash; audiencias (audit-refs) 0 errores; run local carga igual que hoy.

### 2.2 CI gate de calidad en PRs
- **Qué:** workflow `.github/workflows/quality.yml` en PRs a `main`:
  1. `node --check` de todos los `*.js` (frontend + backend).
  2. Ejecutar `scripts` de auditoría existentes (audit-refs, check-inline) sobre todos los HTML.
  3. Lighthouse CI (movil/desktop) con budgets LCP<2.5s, CLS<0.1, TBT<200ms.
  4. Tests Playwright mínimo recuperados en `tests/e2e/` (home, tools, opensource).
- **Definición de hecho:** PR con roto visual/sintaxis falla; reporte Lighthouse en artifact.

### 2.3 Purgar duplicados legacy
- **Qué:** eliminar `js/index-chat.js` (legacy confirmado) y cualquier duplicado que el audit-refs marque como muerto; mover data JSON a `/data`.
- **Definición de hecho:** audit-refs 0 referencias a muertos; check-inline 0 errores.

### 2.4 Recuperar tests E2E (base mínima)
- **Qué:** `tests/e2e/home.spec.js`, `tools.spec.js`, `opensource.spec.js` básicos (Playwright, headless).
- **Definición de hecho:** pasan en CI Linux; cubren al menos navegación, 3 tools, catálogo.

---

## 3. Fase 1 — Producto: cuenta integrada + polish PWA

### 3.1 Cuenta de usuario real
- **Qué:** usar `/api/auth/*` existente (register/login/google/session/refresh/logout) para:
  - Perfil persistido (avatar, nombre, nickname).
  - Favoritos de tools y apps (sync en la nube, no solo localStorage).
  - Historial de uso de herramientas (recientes por dispositivo + cloud).
  - Settings cloud (tema, fuente, notificaciones, región sismos/clima).
- **Definición de hecho:** flujo de visita anónima → cuenta: datos migran; re-login restaura favoritos.

### 3.2 Polish app-like
- **Qué:** install prompt PWA propio (antes del nativo), offline shell real de `/tools` (copy, sin HTML), pull-to-refresh, gestos entre vistas, transición de páginas (ya existe `view-transitions.js` — integrar por página).
- **Definición de hecho:** instalable sin prompt nativo feo; `/tools` usable offline para herramientas que no requieren red.

---

## 4. Fase 2 — EMI 2.0 y App Hub

### 4.1 EMI 2.0 (asistente real, no chatbot suelto)
- **Qué:**
  - Hilos/memoria por usuario (backend: colección `threads`, conectada a `/api/chat`).
  - Herramientas conectadas al chat: clima, sismos, búsqueda Google/Tavily, resumen de URLs, imagen edit — endpoints ya existen en backend.
  - Voz extremo a extremo (STT → LLM → TTS) usando `/api/tts` existente.
  - Slash-commands (`/clima`, `/sismo`, `/search`, `/img`).
- **Definición de hecho:** usuario logueado retoma una conversación de otra sesión; comandos ejecutan herramientas reales.
- **Riesgo:** los endpoints de búsqueda/imagen ya tienen rate-limit — mantener los `chatLimiter`/`imageLimiter`.

### 4.2 App Hub / Workspace
- **Qué:** pantalla unificada de las 34 tools con: recientes, favoritas, búsqueda instantánea, categorías, atajos `Ctrl+K`.
- **Definición de hecho:** acceso a cualquier tool en ≤2 interacciones; favoritos sincronizados (Fase 1).

---

## 5. Fase 3 — Crecimiento y valor

### 5.1 SEO / contenido
- **Qué:** `/guias` como hub de contenido; `schema.org` (Person, WebSite, SoftwareApplication); OG/social cards; sitemap dinámico reflejando el catálogo; RSS del feed de noticias.
- **Definición de hecho:** rich results validados; sitemap automático en `vercel.json` (sin build previo).

### 5.2 Monetización limpia
- **Qué:** reactivar revisión AdSense (pre-requisitos del checklist ya cumplidos); `CodeHub Ultra` como tier (sin "premium desbloqueado" — lección AppsHub); donaciones sin recompensas.
- **Definición de hecho:** AdSense aprobado; Ultra detrás, no "desbloquea contenido".

### 5.3 Métricas accionables
- **Qué:** funnels de conversión en `/analytics` (visita→tool→favorito→cuenta), LCP/CLS por página en dashboard, alertas de regresión.
- **Definición de hecho:** dashboard responde "¿dónde se va la gente?".

---

## 6. Anti-objetivos (NO hacer)

- No reescribir en React/Vue/Svelte. (Rompe SEO + PWA + admin + alta deuda.)
- No agregar features sueltas fuera de esta hoja de ruta.
- No más unidades de anuncios manuales encima de Auto ads.
- No tocar el `CSP` sin agregar el dominio a todos los headers relevantes.
- No centralizar data sensible en `localStorage` (solo preferencias UI).

---

## 7. Cheatsheet de herramientas de auditoría existentes

- `scripts/` → utilidades de repo (referencia de rutas, refs de assets).
- `audit-refs.ps1` / `check-inline.js` (en temp del asistente, no versionados aún) → portar a `scripts/` y al CI (Fase 0/2.2).

---

*Este documento se actualiza tras cada fase completada y debe reflejar la realidad del repo
(si una fase queda descartada, se anota y se agrega el motivo).*