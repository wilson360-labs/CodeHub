# 🚀 Guía de integración — EMI Voz + Blog Estático
> CodeHub by Wilson.E · Junio 2026

---

## 1. 🎙️ EMI por Voz (`js/emi-voice.js`)

### Paso 1 — Copiar el archivo
```
js/emi-voice.js  →  ya está listo para copiar al repo
```

### Paso 2 — Agregar el `<script>` en `index.html`
Busca la línea donde termina el script de `index-chat.js` y agrega:
```html
<script src="js/emi-voice.js" defer></script>
```

### Paso 3 — Agregar el botón de micrófono en `index.html`
Busca `<div class="ai-input-row">` y agrega el botón entre el `<textarea>` y el `<button id="ai-send-btn">`:

```html
<button id="emi-mic-btn" type="button"
  aria-label="Hablar con EMI"
  title="Hablar con EMI (voz a texto)"
  onclick="emiVoice.toggle()">
  <i class="fas fa-microphone"></i>
</button>
```

### Paso 4 — Agregar el CSS en `index.html`
Busca el bloque `<style>` de `index.html`, localiza los estilos de `#ai-send-btn` y pega debajo el bloque CSS que está al final de `emi-voice.js` (todo lo que está entre los comentarios `CSS A AGREGAR`).

### Paso 5 — Actualizar `vercel.json` — Permissions-Policy
El header actual bloquea el micrófono. Cambia:
```json
"microphone=()"
```
por:
```json
"microphone=(self)"
```
Busca la línea `"Permissions-Policy"` en `vercel.json` y actualiza el value.

### Paso 6 — Actualizar el CSP de `index.html` (opcional)
La Web Speech API no requiere conexiones externas adicionales. No hay cambios de CSP necesarios.

### ¿Qué hace?
- **Botón micrófono** → activa escucha, transcribe voz al textarea de EMI
- **Animación roja pulsante** → mientras escucha
- **Botón "Voz EMI"** en la barra inferior → EMI te responde en voz alta (auto-speak)
- **Fallback** → si el navegador no soporta voz, el botón muestra un tooltip explicativo
- **Idiomas** → detecta automáticamente `ch_lang` (es/en) de localStorage

---

## 2. 📝 Blog Estático

### Archivos nuevos a agregar al repo:
```
pages/blog.html          ← Página pública del blog
blog/index.json          ← Registro central de posts
blog/posts/post-001.json ← Post de ejemplo
js/admin-blog-static.js  ← Lógica del gestor en admin-hub
```

### Paso 1 — Copiar los archivos
Copia todos los archivos de la carpeta `output/` a sus rutas correspondientes en el repo.

### Paso 2 — Actualizar `vercel.json`
Agrega en `rewrites`:
```json
{ "source": "/blog", "destination": "/pages/blog.html" },
{ "source": "/blog/:slug", "destination": "/pages/blog.html" }
```
Agrega en `redirects`:
```json
{ "source": "/blog.html", "destination": "/blog", "permanent": true },
{ "source": "/pages/blog", "destination": "/blog", "permanent": true }
```
Agrega en `headers` (para los JSON del blog):
```json
{
  "source": "/blog/(.*)\\.json",
  "headers": [
    { "key": "Cache-Control", "value": "public, max-age=60, stale-while-revalidate=300" },
    { "key": "Content-Type", "value": "application/json; charset=utf-8" }
  ]
}
```

### Paso 3 — Integrar el JS en admin-hub
En `pages/admin-hub.html`, antes del `</body>`:
```html
<script src="../js/admin-blog-static.js"></script>
```
Luego en la función `switchTab()` (en `js/admin-hub.js` o en el script inline):
```javascript
if (name === 'blog') sbInit();
```

### Paso 4 — Reemplazar el HTML del tab blog en admin-hub
En `pages/admin-hub.html`, busca `<div class="tab-panel" id="tab-blog">` y reemplaza todo su contenido interior con el HTML que está en el comentario `HTML_TEMPLATE` al final de `admin-blog-static.js`.

Agrega también el CSS del comentario `CSS ADICIONAL` al bloque `<style>` de `admin-hub.html`.

### Paso 5 — Backend para publicar (GitHub API)
El blog usa la **GitHub API** para que el backend haga commits con los JSON nuevos. 
Esto es necesario porque Vercel es serverless y no puede escribir archivos en disco.

Agrega en Render/Railway estas variables de entorno:
```
GITHUB_TOKEN=ghp_xxxxxxxxxxxx   ← Token con permisos repo:contents
```

Instala en el backend:
```bash
cd backend && npm install @octokit/rest
```

Agrega las rutas del backend que están en el comentario `BACKEND ROUTES NEEDED`
de `admin-blog-static.js` a `backend/server.js`.

### Paso 6 — Actualizar sitemap y estructura
En `sitemap.xml`, agrega:
```xml
<url>
  <loc>https://wilson360-labs.vercel.app/blog</loc>
  <changefreq>weekly</changefreq>
  <priority>0.88</priority>
</url>
```

En `CODEHUB_ESTRUCTURA.md`, agrega `/blog` a la lista de páginas y rutas.

---

## 3. Flujo de publicación de un post

```
Admin-hub → Tab Blog → Redactar título, cuerpo, tags
→ clic "Publicar"
→ backend llama GitHub API
→ crea blog/posts/{id}.json + actualiza blog/index.json
→ Vercel detecta el push y despliega en ~30 segundos
→ Post visible en https://wilson360-labs.vercel.app/blog
```

---

## 4. Verificar que todo funciona

| Test | Resultado esperado |
|------|-------------------|
| Abrir EMI → clic micrófono | Botón se pone rojo, el placeholder dice "Habla ahora…" |
| Hablar algo | El texto aparece en el textarea |
| Activar "Voz EMI" → enviar mensaje | EMI responde en voz alta |
| Abrir `/blog` | Se carga el grid con posts |
| Clic en un post | Se abre el overlay con el contenido |
| Admin → Tab Blog → Publicar | Post aparece en `/blog` tras el deploy |

---

*Actualiza `CODEHUB_ESTRUCTURA.md` después de integrar estos cambios.*
