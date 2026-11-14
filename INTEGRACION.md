# Integraciones — Estado actual
> CodeHub by Wilson.E · Actualizado: Agosto 2026

---

## 🎙️ JARVIS Voice (`js/jarvis-voice.js`) — ✅ INTEGRADO (commit `7d8f6aa`)

**Qué hace:**
- Botón micrófono `#jarvis-mic-btn` en `.ai-input-row` → voz a texto (Web Speech API).
- Toggle "Voz JARVIS" inyectado en `.ai-bottom-bar` → JARVIS lee las respuestas en voz alta (auto-speak).
- Idioma automático: `es-GT` / `en-US` según `ch_lang` de localStorage; sincronizado en vivo vía evento `ch:langchange` desde `applyLang()` en index.html.
- Fallback: si el navegador no soporta voz, el botón muestra un tooltip y un toast de aviso.

**Archivos tocados (ya aplicado):**
- `index.html` → botón micrófono + `<script src="js/jarvis-voice.js" defer>` + `applyLang()` dispatch.
- `css/components.css` → estilos `#jarvis-mic-btn`, ripple, toast (después de `#ai-send-btn`).
- `vercel.json` → `Permissions-Policy`: `microphone=(self)` (⚠️ si se bloquea con `microphone=()`, la voz NO funciona).
- `sw.js` → `js/jarvis-voice.js` y `css/components.css` en el PRECACHE para que JARVIS funcione offline.

**CSS embebido:** el bloque CSS vive al final de `js/jarvis-voice.js` en comentario, como referencia; la copia activa está en `css/components.css`.

---

## 🗑️ Blog estático y autoposter — ELIMINADOS (Agosto 2026)

`pages/blog.html`, `blog/`, `js/admin-blog-static.js`, `autoposter/` y
`autoposter-workflow.yml` fueron removidos. La sección frontal fue reemplazada
por `#news-section` en `index.html` (feed RSS de BBC Mundo).

---

## Verificación rápida

| Test | Resultado esperado |
|------|-------------------|
| Abrir JARVIS → clic micrófono | Botón rojo pulsante, placeholder "Habla ahora…" |
| Hablar algo | El texto aparece en el textarea |
| Activar "Voz JARVIS" → enviar mensaje | JARVIS responde en voz alta |
| Preguntar a JARVIS "¿qué lenguajes dominas?" | Lista actualizada (TypeScript, SQL, Shell/Bash, Java/Kotlin) |
| admin-hub → tabs | Ya NO aparece el tab "Blog" |
