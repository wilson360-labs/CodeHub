/* ══════════════════════════════════════════════════════════════
   CODEHUB ADMIN — Blog Estático (Static Blog Manager)

   Ya integrado: el HTML del tab-blog vive en pages/admin-hub.html
   y las rutas /api/blog/posts ya existen en backend/server.js.
   Este archivo solo contiene las funciones de front-end del panel.
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
