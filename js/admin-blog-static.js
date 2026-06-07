/* ══════════════════════════════════════════════════════════════
   CODEHUB ADMIN — Blog Estático (Static Blog Manager)
   
   INTEGRACIÓN en admin-hub.html:
   1. Reemplazar el contenido del <div id="tab-blog"> con el HTML
      que está en la sección HTML_TEMPLATE al final de este archivo.
   2. Reemplazar el bloque de funciones del blog (busca "BLOG TAB")
      con este script.
   3. Asegúrate de que el backend tiene las rutas /api/blog/posts
      (ver comentarios de rutas de backend al final).
══════════════════════════════════════════════════════════════ */

/* ════════════════════════════════════════════════════════════
   CONFIGURACIÓN
════════════════════════════════════════════════════════════ */
const BLOG_API   = (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1')
  ? 'http://localhost:3001/api/blog'
  : 'https://codehub-98s6.onrender.com/api/blog';

const BLOG_TOKEN_KEY = 'ch_admin_token'; // mismo token que usa admin-hub

/* ════════════════════════════════════════════════════════════
   ESTADO
════════════════════════════════════════════════════════════ */
let staticBlogPosts = [];
let editingPostId   = null;
let staticPreviewOpen = false;

/* ════════════════════════════════════════════════════════════
   HELPERS
════════════════════════════════════════════════════════════ */
function sbToken() {
  return localStorage.getItem(BLOG_TOKEN_KEY) || sessionStorage.getItem(BLOG_TOKEN_KEY) || '';
}

function sbAuthHeaders() {
  return {
    'Content-Type': 'application/json',
    'Authorization': 'Bearer ' + sbToken(),
    'x-admin-token': sbToken(),
  };
}

function sbToast(msg, type = 'ok') {
  if (typeof showToast === 'function') { showToast(msg); return; }
  const t = document.getElementById('toast');
  if (!t) return;
  t.textContent = msg;
  t.className = 'show' + (type === 'error' ? ' error' : '');
  clearTimeout(t._t);
  t._t = setTimeout(() => t.className = '', 3000);
}

function sbFmtDate(iso) {
  return new Date(iso).toLocaleDateString('es-GT', { year:'numeric', month:'short', day:'numeric' });
}

function sbSlugify(str) {
  return str.toLowerCase()
    .normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9\s-]/g, '')
    .replace(/\s+/g, '-')
    .replace(/-+/g, '-')
    .trim().replace(/^-|-$/g, '');
}

function sbReadTime(text) {
  const words = text.replace(/<[^>]+>/g, '').split(/\s+/).length;
  return Math.max(1, Math.ceil(words / 200));
}

/* ════════════════════════════════════════════════════════════
   CARGAR POSTS
════════════════════════════════════════════════════════════ */
async function sbLoadPosts() {
  const list = document.getElementById('sb-posts-list');
  if (!list) return;
  list.innerHTML = '<div class="blog-empty"><i class="fas fa-spinner fa-spin" style="display:block;font-size:1.5rem;opacity:.3;margin-bottom:.5rem"></i>Cargando posts…</div>';

  // Actualizar KPIs desde el índice público
  try {
    const r    = await fetch('/blog/index.json');
    const data = await r.json();
    staticBlogPosts = data.posts || [];
    sbRenderKPIs(staticBlogPosts);
    sbRenderList(staticBlogPosts);
  } catch {
    list.innerHTML = '<div class="blog-empty"><i class="fas fa-triangle-exclamation" style="display:block;font-size:1.5rem;opacity:.3;margin-bottom:.5rem"></i>No se pudo cargar el índice. Asegúrate de que /blog/index.json existe.</div>';
  }
}

function sbRenderKPIs(posts) {
  const pub  = posts.filter(p => p.status === 'published');
  const tags  = new Set(pub.flatMap(p => p.tags || []));
  const last  = pub.sort((a,b) => new Date(b.date)-new Date(a.date))[0];
  const el = (id, val) => { const e = document.getElementById(id); if(e) e.textContent = val; };
  el('sb-total', pub.length);
  el('sb-cats',  tags.size || '—');
  el('sb-last',  last ? sbFmtDate(last.date) : '—');
}

function sbRenderList(posts) {
  const list = document.getElementById('sb-posts-list');
  if (!list) return;
  if (!posts.length) {
    list.innerHTML = '<div class="blog-empty"><i class="fas fa-pen-nib" style="display:block;font-size:2rem;opacity:.2;margin-bottom:.75rem"></i>Aún no hay posts. ¡Escribe el primero!</div>';
    return;
  }
  const sorted = [...posts].sort((a,b) => new Date(b.date)-new Date(a.date));
  list.innerHTML = sorted.map(p => `
    <div class="sb-post-row" id="sbrow-${p.id}">
      <div class="sb-post-meta">
        <span class="sb-status-dot ${p.status === 'published' ? 'pub' : 'draft'}"></span>
        <span class="sb-post-date">${sbFmtDate(p.date)}</span>
      </div>
      <div class="sb-post-title">${p.title}</div>
      <div class="sb-post-tags">${(p.tags||[]).slice(0,3).map(t=>`<span class="blog-tag">${t}</span>`).join('')}</div>
      <div class="sb-post-actions">
        <button class="blog-post-btn view" onclick="sbViewPost('${p.slug}')"><i class="fas fa-eye"></i> Ver</button>
        <button class="blog-post-btn edit" onclick="sbEditPost('${p.id}')"><i class="fas fa-pen"></i> Editar</button>
        <button class="blog-post-btn" style="background:rgba(255,69,0,.08);border-color:rgba(255,69,0,.25);color:var(--p)"
          onclick="sbDeletePost('${p.id}')"><i class="fas fa-trash"></i> Borrar</button>
      </div>
    </div>
  `).join('');
}

/* ════════════════════════════════════════════════════════════
   FORMULARIO — CONTADORES Y AUTO-SLUG
════════════════════════════════════════════════════════════ */
function sbUpdateCounters() {
  const titulo = document.getElementById('sb-titulo');
  const body   = document.getElementById('sb-body');
  if (titulo) {
    const l = titulo.value.length;
    const el = document.getElementById('sb-titulo-cnt');
    if (el) { el.textContent = `${l} / 70`; el.style.color = l > 70 ? '#ff6b6b' : 'var(--muted)'; }
  }
  if (body) {
    const l = body.value.length;
    const el = document.getElementById('sb-body-cnt');
    if (el) { el.textContent = `${l} caracteres · ~${sbReadTime(body.value)} min de lectura`; }
  }
}

function sbAutoSlug() {
  const titulo = document.getElementById('sb-titulo');
  const slug   = document.getElementById('sb-slug');
  if (titulo && slug && !slug.dataset.manual) {
    slug.value = sbSlugify(titulo.value);
  }
}

function sbRenderTagChips() {
  const tagsInput = document.getElementById('sb-tags');
  const chips = document.getElementById('sb-tag-chips');
  if (!tagsInput || !chips) return;
  const tags = tagsInput.value.split(',').map(t=>t.trim()).filter(Boolean);
  chips.innerHTML = tags.map(t => `<span class="blog-tag">${t} <span onclick="sbRemoveTag('${t}')" style="cursor:pointer;opacity:.6">×</span></span>`).join('');
}

function sbAddTag(tag) {
  const el = document.getElementById('sb-tags');
  if (!el) return;
  const tags = el.value.split(',').map(t=>t.trim()).filter(Boolean);
  if (!tags.includes(tag)) { tags.push(tag); el.value = tags.join(', '); }
  sbRenderTagChips();
}

function sbRemoveTag(tag) {
  const el = document.getElementById('sb-tags');
  if (!el) return;
  const tags = el.value.split(',').map(t=>t.trim()).filter(t=>t&&t!==tag);
  el.value = tags.join(', ');
  sbRenderTagChips();
}

/* ════════════════════════════════════════════════════════════
   PREVIEW
════════════════════════════════════════════════════════════ */
function sbTogglePreview() {
  const box  = document.getElementById('sb-preview-box');
  const tit  = document.getElementById('sb-titulo')?.value || 'Sin título';
  const body = document.getElementById('sb-body')?.value   || '';
  if (!box) return;
  staticPreviewOpen = !staticPreviewOpen;
  box.style.display = staticPreviewOpen ? 'block' : 'none';
  if (staticPreviewOpen) {
    document.getElementById('sb-preview-title').textContent = tit;
    document.getElementById('sb-preview-body').innerHTML = body;
  }
}

/* ════════════════════════════════════════════════════════════
   PUBLICAR / GUARDAR POST
════════════════════════════════════════════════════════════ */
async function sbPublish(status = 'published') {
  const titulo  = document.getElementById('sb-titulo')?.value.trim();
  const slug    = document.getElementById('sb-slug')?.value.trim()   || sbSlugify(titulo||'');
  const tags    = document.getElementById('sb-tags')?.value.split(',').map(t=>t.trim()).filter(Boolean);
  const body    = document.getElementById('sb-body')?.value.trim();
  const cover   = document.getElementById('sb-cover')?.value.trim()  || '';
  const excerpt = document.getElementById('sb-excerpt')?.value.trim()|| body.replace(/<[^>]+>/g,'').slice(0,180)+'…';

  if (!titulo) { sbToast('⚠️ El título es obligatorio', 'error'); return; }
  if (!body)   { sbToast('⚠️ El cuerpo no puede estar vacío', 'error'); return; }

  const btn = document.getElementById('sb-publish-btn');
  if (btn) { btn.disabled = true; btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Guardando…'; }

  const payload = {
    id    : editingPostId || null,
    slug,
    title : titulo,
    excerpt,
    author: 'Wilson.E',
    date  : new Date().toISOString().slice(0,10),
    tags,
    cover,
    status,
    read_time: sbReadTime(body),
    body,
  };

  try {
    const method = editingPostId ? 'PUT' : 'POST';
    const url    = editingPostId ? `${BLOG_API}/posts/${editingPostId}` : `${BLOG_API}/posts`;
    const r = await fetch(url, {
      method,
      headers: sbAuthHeaders(),
      body: JSON.stringify(payload),
    });
    if (!r.ok) {
      const err = await r.json().catch(() => ({ error: r.statusText }));
      throw new Error(err.error || 'Error del servidor');
    }
    sbToast(status === 'published' ? '✅ Post publicado en /blog' : '💾 Borrador guardado');
    sbClearForm();
    sbLoadPosts();
  } catch (err) {
    sbToast('❌ ' + err.message, 'error');
  } finally {
    if (btn) { btn.disabled = false; btn.innerHTML = '<i class="fas fa-rocket"></i> Publicar'; }
  }
}

async function sbSaveDraft() {
  await sbPublish('draft');
}

/* ════════════════════════════════════════════════════════════
   EDITAR POST
════════════════════════════════════════════════════════════ */
async function sbEditPost(id) {
  const meta = staticBlogPosts.find(p => p.id === id);
  if (!meta) return;
  try {
    const r    = await fetch(`/blog/posts/${meta.file || id + '.json'}`);
    const post = await r.json();
    editingPostId = id;
    document.getElementById('sb-titulo').value  = meta.title;
    document.getElementById('sb-slug').value    = meta.slug;
    document.getElementById('sb-slug').dataset.manual = 'true';
    document.getElementById('sb-tags').value    = (meta.tags||[]).join(', ');
    document.getElementById('sb-cover').value   = meta.cover || '';
    document.getElementById('sb-excerpt').value = meta.excerpt || '';
    document.getElementById('sb-body').value    = post.body || '';
    sbUpdateCounters();
    sbRenderTagChips();
    document.getElementById('sb-form-title').textContent = '✏️ Editando post';
    document.getElementById('sb-publish-btn').innerHTML = '<i class="fas fa-save"></i> Guardar cambios';
    document.querySelector('.sb-form')?.scrollIntoView({ behavior: 'smooth' });
  } catch {
    sbToast('❌ No se pudo cargar el post para editar', 'error');
  }
}

/* ════════════════════════════════════════════════════════════
   BORRAR POST
════════════════════════════════════════════════════════════ */
async function sbDeletePost(id) {
  const meta = staticBlogPosts.find(p => p.id === id);
  if (!meta) return;
  if (!confirm(`¿Borrar el post "${meta.title}"? Esta acción no se puede deshacer.`)) return;

  try {
    const r = await fetch(`${BLOG_API}/posts/${id}`, {
      method: 'DELETE',
      headers: sbAuthHeaders(),
    });
    if (!r.ok) throw new Error((await r.json()).error || 'Error');
    sbToast('🗑️ Post eliminado');
    sbLoadPosts();
  } catch (err) {
    sbToast('❌ ' + err.message, 'error');
  }
}

/* ════════════════════════════════════════════════════════════
   VER POST EN NUEVA PESTAÑA
════════════════════════════════════════════════════════════ */
function sbViewPost(slug) {
  window.open('/blog/' + slug, '_blank', 'noopener');
}

/* ════════════════════════════════════════════════════════════
   LIMPIAR FORMULARIO
════════════════════════════════════════════════════════════ */
function sbClearForm() {
  editingPostId = null;
  ['sb-titulo','sb-slug','sb-tags','sb-cover','sb-excerpt','sb-body'].forEach(id => {
    const el = document.getElementById(id);
    if (el) { el.value = ''; delete el.dataset.manual; }
  });
  const chips = document.getElementById('sb-tag-chips');
  if (chips) chips.innerHTML = '';
  const box = document.getElementById('sb-preview-box');
  if (box) box.style.display = 'none';
  staticPreviewOpen = false;
  sbUpdateCounters();
  const ft = document.getElementById('sb-form-title');
  if (ft) ft.textContent = '✦ Nuevo post';
  const pb = document.getElementById('sb-publish-btn');
  if (pb) pb.innerHTML = '<i class="fas fa-rocket"></i> Publicar';
}

/* ════════════════════════════════════════════════════════════
   SLUG MANUAL: marcar que no se auto-genera más
════════════════════════════════════════════════════════════ */
function sbSlugManual() {
  const el = document.getElementById('sb-slug');
  if (el) el.dataset.manual = 'true';
}

/* ════════════════════════════════════════════════════════════
   INIT cuando se abre el tab blog (llama a esto desde switchTab)
════════════════════════════════════════════════════════════ */
function sbInit() {
  if (!document.getElementById('sb-posts-list')) return;
  sbLoadPosts();
}

/* ══════════════════════════════════════════════════════════
   HTML_TEMPLATE — Contenido para <div id="tab-blog">
   PEGAR directamente en admin-hub.html reemplazando el
   contenido actual del tab-blog
══════════════════════════════════════════════════════════

<div class="blog-kpi-grid">
  <div class="kpi-card" style="--kc:var(--a)"><i class="fas fa-file-lines kpi-ico"></i><div class="kpi-num" id="sb-total">—</div><div class="kpi-lbl">Posts publicados</div></div>
  <div class="kpi-card" style="--kc:var(--c)"><i class="fas fa-tag kpi-ico"></i><div class="kpi-num" id="sb-cats">—</div><div class="kpi-lbl">Tags únicos</div></div>
  <div class="kpi-card" style="--kc:var(--g)"><i class="fas fa-clock kpi-ico"></i><div class="kpi-num" id="sb-last">—</div><div class="kpi-lbl">Último post</div></div>
</div>

<div class="blog-form sb-form">
  <div class="blog-form-title" id="sb-form-title">✦ Nuevo post</div>

  <div class="blog-tip">
    <i class="fas fa-circle-info"></i>
    <span>El post se guarda directamente en <code>/blog/posts/</code> y aparece en <strong>/blog</strong> sin necesidad de Blogger.</span>
  </div>

  <div style="margin-bottom:.75rem">
    <div class="blog-label">Título del post *</div>
    <input id="sb-titulo" class="blog-input" type="text" placeholder="Ej: Cómo construí mi portfolio con HTML puro"
      oninput="sbUpdateCounters();sbAutoSlug()">
    <div class="blog-char-counter" id="sb-titulo-cnt">0 / 70</div>
  </div>

  <div class="blog-grid-2">
    <div>
      <div class="blog-label">Slug (URL del post)</div>
      <input id="sb-slug" class="blog-input" type="text" placeholder="como-construi-mi-portfolio"
        oninput="sbSlugManual()">
    </div>
    <div>
      <div class="blog-label">URL de imagen de portada</div>
      <input id="sb-cover" class="blog-input" type="url" placeholder="https://…/imagen.jpg">
    </div>
  </div>

  <div style="margin-bottom:.75rem">
    <div class="blog-label">Extracto (descripción breve)</div>
    <input id="sb-excerpt" class="blog-input" type="text" placeholder="Resumen del post para la card…">
  </div>

  <div style="margin-bottom:.75rem">
    <div class="blog-label">Etiquetas (separadas por coma)</div>
    <input id="sb-tags" class="blog-input" type="text" placeholder="html, css, tutorial" oninput="sbRenderTagChips()">
    <div class="blog-tags-wrap" id="sb-tag-chips" style="margin-top:.4rem"></div>
  </div>

  <div class="blog-label" style="margin-bottom:.35rem">Etiquetas sugeridas</div>
  <div class="blog-tags-wrap" style="margin-bottom:.75rem">
    <span class="blog-tag" onclick="sbAddTag('html')">html</span>
    <span class="blog-tag" onclick="sbAddTag('css')">css</span>
    <span class="blog-tag" onclick="sbAddTag('javascript')">javascript</span>
    <span class="blog-tag" onclick="sbAddTag('python')">python</span>
    <span class="blog-tag" onclick="sbAddTag('tutorial')">tutorial</span>
    <span class="blog-tag" onclick="sbAddTag('portfolio')">portfolio</span>
    <span class="blog-tag" onclick="sbAddTag('ia')">ia</span>
    <span class="blog-tag" onclick="sbAddTag('nodejs')">nodejs</span>
    <span class="blog-tag" onclick="sbAddTag('Guatemala')">Guatemala</span>
    <span class="blog-tag" onclick="sbAddTag('freelance')">freelance</span>
    <span class="blog-tag" onclick="sbAddTag('api')">api</span>
  </div>

  <div style="margin-bottom:.75rem">
    <div class="blog-label">Cuerpo del post * — HTML básico permitido (&lt;h2&gt;, &lt;p&gt;, &lt;ul&gt;, &lt;pre&gt;&lt;code&gt;, &lt;a&gt;, &lt;strong&gt;)</div>
    <textarea id="sb-body" class="blog-textarea" placeholder="Escribe aquí el contenido del post…" oninput="sbUpdateCounters()"></textarea>
    <div class="blog-char-counter" id="sb-body-cnt">0 caracteres</div>
  </div>

  <div class="blog-actions">
    <button class="blog-btn-primary" id="sb-publish-btn" onclick="sbPublish('published')">
      <i class="fas fa-rocket"></i> Publicar
    </button>
    <button class="blog-btn-sec" onclick="sbSaveDraft()"><i class="fas fa-save"></i> Borrador</button>
    <button class="blog-btn-sec" onclick="sbTogglePreview()"><i class="fas fa-eye"></i> Vista previa</button>
    <button class="blog-btn-sec" onclick="sbClearForm()"><i class="fas fa-eraser"></i> Limpiar</button>
  </div>

  <div class="blog-preview-box" id="sb-preview-box" style="display:none">
    <div class="blog-preview-title" id="sb-preview-title">Sin título</div>
    <hr style="border:none;border-top:1px solid var(--border);margin:.6rem 0">
    <div class="blog-preview-body" id="sb-preview-body">El cuerpo aparecerá aquí…</div>
  </div>
</div>

<div class="section-title" style="margin-top:1.5rem"><i class="fas fa-file-lines"></i> Posts del blog estático</div>
<div style="display:flex;gap:.5rem;margin-bottom:.75rem;flex-wrap:wrap">
  <button class="vt-btn" onclick="sbLoadPosts()" style="background:rgba(255,189,105,.1);border-color:rgba(255,189,105,.3);color:var(--a)">
    <i class="fas fa-rotate-right"></i> Recargar
  </button>
  <a href="/blog" target="_blank" rel="noopener"
    style="display:flex;align-items:center;gap:.38rem;padding:.52rem .95rem;border-radius:10px;border:1px solid var(--border);background:rgba(255,255,255,.04);color:var(--muted);font-family:var(--mono);font-size:.7rem;font-weight:700;text-decoration:none;transition:all .2s"
    onmouseover="this.style.color='var(--text)';this.style.borderColor='var(--border2)'"
    onmouseout="this.style.color='var(--muted)';this.style.borderColor='var(--border)'">
    <i class="fas fa-arrow-up-right-from-square"></i> Ver blog público
  </a>
</div>
<div id="sb-posts-list" class="blog-posts-list">
  <div class="blog-empty">Haz clic en "Recargar" para cargar los posts</div>
</div>

═══════════════════════════════════════════════════════════
   CSS ADICIONAL — agregar al bloque <style> de admin-hub.html
   (junto a los estilos de .blog-post-btn existentes)
═══════════════════════════════════════════════════════════

.sb-post-row {
  display: flex; align-items: center; gap: .75rem;
  padding: .75rem 1rem; border-bottom: 1px solid var(--border);
  flex-wrap: wrap;
}
.sb-post-row:last-child { border-bottom: none; }
.sb-post-meta { display: flex; align-items: center; gap: .4rem; flex-shrink: 0; }
.sb-status-dot {
  width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0;
}
.sb-status-dot.pub   { background: var(--g); box-shadow: 0 0 6px rgba(0,230,118,.5); }
.sb-status-dot.draft { background: var(--muted); }
.sb-post-date { font-family: var(--mono); font-size: .58rem; color: var(--muted); white-space: nowrap; }
.sb-post-title { font-size: .82rem; font-weight: 600; flex: 1; min-width: 160px; }
.sb-post-tags { display: flex; gap: .3rem; flex-wrap: wrap; }
.sb-post-actions { display: flex; gap: .35rem; margin-left: auto; flex-shrink: 0; }

══════════════════════════════════════════════════════════
   BACKEND ROUTES NEEDED — agregar en backend/server.js
   (o en un archivo separado backend/blog-routes.js)
══════════════════════════════════════════════════════════

// Requiere: npm install fs-extra  (ya incluido si usas Node 18+, usa fs/promises)
// Y: ADMIN_TOKEN en variables de entorno de Render/Railway

const fs   = require('fs').promises;
const path = require('path');

// Ruta base del blog (relativa al servidor desplegado en Vercel/raíz del repo)
// En Render el CWD es la raíz del backend, así que ajusta según tu setup.
// Lo más sencillo: el backend hace fetch al repo de GitHub vía API de Vercel
// para actualizar los JSON. O alternativamente:

// OPCIÓN A — Backend en la misma raíz que el frontend (Vercel serverless)
// No aplica: Vercel no permite escribir archivos en runtime.

// OPCIÓN B — GitHub API (recomendada para Vercel frontend)
// El backend llama a la API de GitHub para hacer un commit con los JSON actualizados.
// Requiere GITHUB_TOKEN en env vars con permisos de repo:contents.

const { Octokit } = require('@octokit/rest'); // npm install @octokit/rest

const GITHUB_OWNER = 'wilson360-labs';
const GITHUB_REPO  = 'CodeHub';
const GITHUB_BRANCH = 'main';
const octokit = new Octokit({ auth: process.env.GITHUB_TOKEN });

async function ghUpdateFile(filePath, content, message) {
  // Obtener SHA actual del archivo
  let sha;
  try {
    const { data } = await octokit.repos.getContent({
      owner: GITHUB_OWNER, repo: GITHUB_REPO, path: filePath, ref: GITHUB_BRANCH,
    });
    sha = data.sha;
  } catch { /* archivo nuevo, sin sha */ }

  await octokit.repos.createOrUpdateFileContents({
    owner: GITHUB_OWNER, repo: GITHUB_REPO, path: filePath,
    message, content: Buffer.from(content).toString('base64'),
    branch: GITHUB_BRANCH, ...(sha ? { sha } : {}),
  });
}

// POST /api/blog/posts — crear post
// PUT  /api/blog/posts/:id — editar post
// DELETE /api/blog/posts/:id — borrar post
// GET  /api/blog/posts — listar (proxy del index.json público)

router.post('/api/blog/posts', requireAdmin, async (req, res) => {
  try {
    const { id: reqId, slug, title, excerpt, author, date, tags, cover, status, read_time, body } = req.body;
    const id = reqId || 'post-' + Date.now();
    const file = id + '.json';

    const postData = { id, slug, title, excerpt, author, date, tags, cover, status, read_time, body };
    const indexMeta = { id, slug, title, excerpt, author, date, tags, cover, status, read_time, file };

    // Actualizar index.json
    const idxRes  = await fetch('https://wilson360-labs.vercel.app/blog/index.json');
    const idxData = await idxRes.json();
    idxData.posts.push(indexMeta);
    idxData._meta.total = idxData.posts.length;
    idxData._meta.last_updated = new Date().toISOString();

    await ghUpdateFile('blog/posts/' + file, JSON.stringify(postData, null, 2), `blog: add post "${title}"`);
    await ghUpdateFile('blog/index.json', JSON.stringify(idxData, null, 2), `blog: update index after "${title}"`);

    res.json({ ok: true, id, slug });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

═══════════════════════════════════════════════════════════
   TAMBIÉN AGREGAR en switchTab() de admin-hub.js:
   
   if (name === 'blog') sbInit();
═══════════════════════════════════════════════════════════ */
