# Integraciones — Estado actual
> CodeHub by Wilson.E · Actualizado: Agosto 2026

---

## 1. 🎙️ EMI Voice (`js/emi-voice.js`) — ✅ INTEGRADO (commit `7d8f6aa`)

**Qué hace:**
- Botón micrófono `#emi-mic-btn` en `.ai-input-row` → voz a texto (Web Speech API).
- Toggle "Voz EMI" inyectado en `.ai-bottom-bar` → EMI lee las respuestas en voz alta (auto-speak).
- Idioma automático: `es-GT` / `en-US` según `ch_lang` de localStorage; sincronizado en vivo vía evento `ch:langchange` desde `applyLang()` en index.html.
- Fallback: si el navegador no soporta voz, el botón muestra un tooltip y un toast de aviso.

**Archivos tocados (ya aplicado):**
- `index.html` → botón micrófono + `<script src="js/emi-voice.js" defer>` + `applyLang()` dispatch.
- `css/components.css` → estilos `#emi-mic-btn`, ripple, toast (después de `#ai-send-btn`).
- `vercel.json` → `Permissions-Policy`: `microphone=(self)` (⚠️ si se bloquea con `microphone=()`, la voz NO funciona).

**CSS embebido:** el bloque CSS vive al final de `js/emi-voice.js` en comentario, como referencia; la copia activa está en `css/components.css`.

---

## 2. 📝 Blog Estático (`/blog`) — ⚠️ PENDIENTE RUTEO

**Hecho:**
- `pages/blog.html` renderiza desde `blog/index.json` + `blog/posts/*.json`.
- `js/admin-blog-static.js` en admin-hub (tab Blog) publica posts; el backend usa la GitHub API (`GITHUB_TOKEN`) para commitear los JSON.

**Pendiente para que la URL pública funcione:**
- `vercel.json` → rewrites: `/blog` → `/pages/blog.html` y `/blog/:slug` → `/pages/blog.html`.
- `vercel.json` → redirects: `/blog.html` y `/pages/blog` → `/blog`.
- `sitemap.xml` → agregar `/blog`.
- Actualizar `CODEHUB_ESTRUCTURA.md` (rutas) al hacerlo.

---

## 3. Verificación rápida

| Test | Resultado esperado |
|------|-------------------|
| Abrir EMI → clic micrófono | Botón rojo pulsante, placeholder "Habla ahora…" |
| Hablar algo | El texto aparece en el textarea |
| Activar "Voz EMI" → enviar mensaje | EMI responde en voz alta |
| Preguntar a EMI "¿qué lenguajes dominas?" | Lista actualizada (TypeScript, SQL, Shell/Bash, Java/Kotlin) |
