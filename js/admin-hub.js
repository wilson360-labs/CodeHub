/* ═══════════════════════════════════════
   admin-hub — Scripts
   CodeHub by Wilson.E
═══════════════════════════════════════ */

const BACKEND   = 'https://codehub-98s6.onrender.com';
// ⚠️  La contraseña se valida contra el BACKEND (variable ADMIN_KEY en Railway)
// NO la guardes aquí en producción — este campo solo sirve para enviarla al servidor
let ADMIN_KEY = '';

// ── AUTH ──────────────────────────────────────────────────────
async function checkLogin() {
  const pwd = document.getElementById('pwd-input').value.trim();
  if (!pwd) return;
  // Verificar Turnstile
  const tsToken = document.querySelector('#ts-admin input[name="cf-turnstile-response"]')?.value
               || document.querySelector('input[name="cf-turnstile-response"]')?.value || '';
  const tsLoaded = document.querySelector('#ts-admin iframe');
  if (tsLoaded && !tsToken) {
    document.getElementById('login-err').textContent = '⚠️ Completa la verificación de seguridad';
    return;
  }
  const btn = document.querySelector('.login-btn');
  btn.disabled = true; btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Verificando...';
  try {
    // Verificar contra el backend real
    const res = await fetch(`${BACKEND}/api/admin/apps`, {
      headers: { 'x-admin-key': pwd }
    });
    if (res.ok) {
      ADMIN_KEY = pwd;
      document.getElementById('login-screen').style.display = 'none';
      const data = await res.json();
      initAdmin(data.apps);
    } else {
      document.getElementById('login-err').textContent = '❌ Contraseña incorrecta';
      document.getElementById('pwd-input').value = '';
    }
  } catch {
    // Si falla la red, intentar login local de emergencia
    document.getElementById('login-err').textContent = '❌ Error de conexión con el servidor';
  }
  btn.disabled = false; btn.innerHTML = '<i class="fas fa-unlock"></i> Ingresar';
}
document.getElementById('pwd-input').addEventListener('keydown', e => { if (e.key === 'Enter') checkLogin(); });

function logout() {
  ADMIN_KEY = '';
  document.getElementById('login-screen').style.display = 'flex';
  document.getElementById('pwd-input').value = '';
  document.getElementById('login-err').textContent = '';
}

// ── TABS ──────────────────────────────────────────────────────
function switchTab(id, btn) {
  document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
  document.querySelectorAll('.admin-tab').forEach(b => b.classList.remove('active'));
  document.getElementById('tab-' + id).classList.add('active');
  btn.classList.add('active');
  if (id === 'requests') loadAdminRequests();
  if (id === 'stats')    loadAdminRatings();
  if (id === 'add')      renderAddForm();
  if (id === 'visitors') loadVisitors();
}

// ── INIT ──────────────────────────────────────────────────────
let appsData = [];

function initAdmin(apps) {
  appsData = apps || [];
  renderApps();
  loadAdminStats();
}

// ── RENDER APPS ───────────────────────────────────────────────
function renderApps() {
  const list = document.getElementById('apps-list');
  const verified = appsData.filter(a => a.verified).length;
  document.getElementById('st-apps').textContent     = appsData.length;
  document.getElementById('st-verified').textContent = verified;

  if (!appsData.length) {
    list.innerHTML = '<div style="padding:2rem;text-align:center;font-family:var(--mono);font-size:.78rem;color:var(--muted)">No hay apps. Usa "Seed desde JSON" para importar o agrega una manualmente.</div>';
    return;
  }

  list.innerHTML = appsData.map((app, i) => {
    const enlace      = app.b2_url || app.enlace || '#';
    const pluginEnl   = app.b2_plugin_url || app.plugin_enlace || '';
    const hasB2       = !!app.b2_url;
    const hasB2Plugin = !!app.b2_plugin_url;
    return `
    <div class="app-row" id="row-${app.appId}">
      <div class="app-name-cell">
        ${app.nombre}
        <small>${app.categoria || ''} · ${app.appId}</small>
        ${hasB2 ? '<small style="color:var(--g)">☁️ APK en R2</small>' : ''}
      </div>
      <div>
        <input class="ver-input" type="text" value="${app.version || ''}" id="ver-${app.appId}" placeholder="1.0.0">
      </div>
      <div>
        <span class="badge-verified ${app.verified ? 'yes' : 'no'}" id="vbadge-${app.appId}"
              onclick="toggleVerified('${app.appId}')">
          ${app.verified ? '✅ Sí' : '○ No'}
        </span>
      </div>
      <div>
        <select style="background:rgba(255,255,255,.05);border:1px solid var(--border);border-radius:7px;padding:.25rem .4rem;color:var(--text);font-family:var(--mono);font-size:.65rem;outline:none" id="badge-${app.appId}">
          <option value="🆕" ${app.tag==='🆕'?'selected':''}>🆕 Nuevo</option>
          <option value="🔄 Actualizada" ${app.tag==='🔄 Actualizada'?'selected':''}>🔄 Actualizada</option>
          <option value="🔥 Popular" ${app.tag==='🔥 Popular'?'selected':''}>🔥 Popular</option>
          <option value="⚡ Beta" ${app.tag==='⚡ Beta'?'selected':''}>⚡ Beta</option>
          <option value="⭐" ${app.tag==='⭐'?'selected':''}>⭐ Destacada</option>
        </select>
      </div>
      <div style="display:flex;flex-direction:column;gap:.4rem">
        <textarea class="changelog-input" rows="2" id="cl-${app.appId}">${app.changelog || ''}</textarea>
        <!-- Link manual -->
        <input class="ver-input" type="url" value="${enlace === '#' ? '' : enlace}" 
               id="link-${app.appId}" placeholder="https://... (o sube APK directo)">
        <input class="ver-input" type="url" value="${pluginEnl}" id="plugin-${app.appId}" placeholder="🧩 Plugin URL (opcional)" style="display:${pluginEnl?'block':'block'}">
        <input class="ver-input" type="url" value="${app.tutorial_url||''}" id="tutorial-${app.appId}" placeholder="🎬 Tutorial YouTube (opcional)" style="border-color:rgba(255,0,80,.2)">
      </div>
      <div style="display:flex;flex-direction:column;gap:.4rem">
        <button class="save-row-btn" id="sbtn-${app.appId}" onclick="saveRow('${app.appId}')">
          <i class="fas fa-save"></i> Guardar
        </button>
        <!-- Upload APK a B2 -->
        <label style="cursor:pointer">
          <span style="display:flex;align-items:center;gap:.4rem;padding:.3rem .6rem;border-radius:7px;background:rgba(0,229,255,.08);border:1px solid rgba(0,229,255,.2);color:var(--c);font-family:var(--mono);font-size:.62rem;font-weight:700;white-space:nowrap">
            <i class="fas fa-cloud-arrow-up"></i> APK → R2
          </span>
          <input type="file" accept=".apk" style="display:none"
                 onchange="uploadAPK('${app.appId}', 'main', this)">
        </label>
        <button onclick="deleteApp('${app.appId}', '${app.nombre.replace(/'/g,'')}')"
                style="padding:.28rem .5rem;border-radius:7px;background:rgba(255,107,107,.08);border:1px solid rgba(255,107,107,.2);color:#ff6b6b;font-family:var(--mono);font-size:.62rem;cursor:pointer;white-space:nowrap">
          <i class="fas fa-trash"></i> Eliminar
        </button>
      </div>
    </div>`;
  }).join('');
}

// ── TOGGLE VERIFIED ───────────────────────────────────────────
function toggleVerified(appId) {
  const app = appsData.find(a => a.appId === appId);
  if (!app) return;
  app.verified = !app.verified;
  const badge = document.getElementById('vbadge-' + appId);
  badge.className = 'badge-verified ' + (app.verified ? 'yes' : 'no');
  badge.textContent = app.verified ? '✅ Sí' : '○ No';
}

// ── SAVE ROW ──────────────────────────────────────────────────
async function saveRow(appId) {
  const app = appsData.find(a => a.appId === appId);
  if (!app) return;
  const btn = document.getElementById('sbtn-' + appId);
  btn.disabled = true; btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i>';

  const body = {
    version:      document.getElementById('ver-' + appId)?.value.trim(),
    changelog:    document.getElementById('cl-' + appId)?.value.trim(),
    tag:          document.getElementById('badge-' + appId)?.value,
    verified:     app.verified,
    enlace:       document.getElementById('link-' + appId)?.value.trim() || app.enlace,
    plugin_enlace: document.getElementById('plugin-' + appId)?.value.trim()   || null,
    tutorial_url:  document.getElementById('tutorial-' + appId)?.value.trim() || null,
  };

  try {
    const res = await fetch(`${BACKEND}/api/admin/apps/${appId}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', 'x-admin-key': ADMIN_KEY },
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new Error((await res.json()).error);
    const d = await res.json();
    // Actualizar local
    Object.assign(app, d.app);
    btn.classList.add('saved'); btn.innerHTML = '<i class="fas fa-check"></i> OK';
    toast('✅ ' + app.nombre + ' actualizada');
  } catch (e) {
    toast('❌ Error: ' + e.message);
    btn.innerHTML = '<i class="fas fa-save"></i> Guardar';
  }
  setTimeout(() => {
    btn.disabled = false; btn.classList.remove('saved');
    btn.innerHTML = '<i class="fas fa-save"></i> Guardar';
  }, 2500);
}

// ── UPLOAD APK A BACKBLAZE B2 ─────────────────────────────────
async function uploadAPK(appId, slot, input) {
  const file = input.files[0];
  if (!file) return;
  const app = appsData.find(a => a.appId === appId);
  toast(`⬆️ Subiendo ${file.name} (${(file.size/1024/1024).toFixed(1)} MB)...`);

  const label = input.previousElementSibling;
  label.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Subiendo...';
  label.style.color = 'var(--a)';

  const formData = new FormData();
  formData.append('apk', file);
  formData.append('slot', slot);

  try {
    const res = await fetch(`${BACKEND}/api/admin/apps/${appId}/upload`, {
      method: 'POST',
      headers: { 'x-admin-key': ADMIN_KEY },
      body: formData,
    });
    if (!res.ok) throw new Error((await res.json()).error);
    const d = await res.json();
    toast(`✅ APK subido a B2 · ${d.sizeMB} MB · ${d.downloadUrl.slice(0,50)}...`);
    // Actualizar el link en el formulario
    const linkInput = document.getElementById('link-' + appId);
    if (linkInput) linkInput.value = d.downloadUrl;
    if (app) app.b2_url = d.downloadUrl;
    label.innerHTML = '<i class="fas fa-check"></i> Subido ✅';
    label.style.color = 'var(--g)';
    // Refrescar datos
    await refreshApps();
  } catch (e) {
    toast('❌ Error subiendo: ' + e.message);
    label.innerHTML = '<i class="fas fa-cloud-arrow-up"></i> APK → R2';
    label.style.color = 'var(--c)';
  }
  input.value = '';
}

// ── ELIMINAR APP ──────────────────────────────────────────────
async function deleteApp(appId, nombre) {
  if (!confirm(`¿Eliminar "${nombre}" de la tienda? Esta acción no se puede deshacer.`)) return;
  try {
    const res = await fetch(`${BACKEND}/api/admin/apps/${appId}`, {
      method: 'DELETE', headers: { 'x-admin-key': ADMIN_KEY }
    });
    if (!res.ok) throw new Error((await res.json()).error);
    appsData = appsData.filter(a => a.appId !== appId);
    renderApps();
    toast('🗑️ App eliminada: ' + nombre);
  } catch (e) { toast('❌ Error: ' + e.message); }
}

// ── REFRESH APPS DESDE EL SERVIDOR ───────────────────────────
async function refreshApps() {
  try {
    const res = await fetch(`${BACKEND}/api/admin/apps`, {
      headers: { 'x-admin-key': ADMIN_KEY }
    });
    if (!res.ok) return;
    const d = await res.json();
    appsData = d.apps;
    renderApps();
  } catch {}
}

// ── AGREGAR APP NUEVA ─────────────────────────────────────────
function renderAddForm() {
  const panel = document.getElementById('tab-add');
  if (!panel) return;
  const nextId = 'app-' + (appsData.length + 1);
  panel.innerHTML = `
    <div class="section-title"><i class="fas fa-plus-circle"></i> Agregar Nueva App</div>
    <div style="background:var(--card);border:1px solid var(--border);border-radius:16px;padding:1.5rem;max-width:600px">
      <div style="display:grid;grid-template-columns:1fr 1fr;gap:1rem;margin-bottom:1rem">
        <div>
          <label style="font-family:var(--mono);font-size:.65rem;color:var(--muted);display:block;margin-bottom:.3rem">APP ID</label>
          <input class="ver-input" id="new-appId" value="${nextId}" style="width:100%" placeholder="app-14">
        </div>
        <div>
          <label style="font-family:var(--mono);font-size:.65rem;color:var(--muted);display:block;margin-bottom:.3rem">NOMBRE</label>
          <input class="ver-input" id="new-nombre" style="width:100%" placeholder="Spotify Premium">
        </div>
        <div>
          <label style="font-family:var(--mono);font-size:.65rem;color:var(--muted);display:block;margin-bottom:.3rem">VERSIÓN</label>
          <input class="ver-input" id="new-version" style="width:100%" placeholder="1.0.0">
        </div>
        <div>
          <label style="font-family:var(--mono);font-size:.65rem;color:var(--muted);display:block;margin-bottom:.3rem">CATEGORÍA</label>
          <select class="ver-input" id="new-cat" style="width:100%">
            <option>Música</option><option>Video</option><option>Foto</option>
            <option>Util</option><option>Seguridad</option><option>Juegos</option>
          </select>
        </div>
        <div style="grid-column:1/-1">
          <label style="font-family:var(--mono);font-size:.65rem;color:var(--muted);display:block;margin-bottom:.3rem">DESCRIPCIÓN</label>
          <input class="ver-input" id="new-desc" style="width:100%" placeholder="Disfruta de...">
        </div>
        <div style="grid-column:1/-1">
          <label style="font-family:var(--mono);font-size:.65rem;color:var(--muted);display:block;margin-bottom:.3rem">LINK DESCARGA (o sube APK después)</label>
          <input class="ver-input" id="new-enlace" style="width:100%" placeholder="https://...">
        </div>
        <div style="grid-column:1/-1">
          <label style="font-family:var(--mono);font-size:.65rem;color:var(--muted);display:block;margin-bottom:.3rem">IMAGEN (ruta)</label>
          <input class="ver-input" id="new-imagen" style="width:100%" placeholder="img/NombreApp.png">
        </div>
      </div>
      <button class="publish-btn" style="background:linear-gradient(135deg,var(--p),#ff6b35)" onclick="createApp()">
        <i class="fas fa-plus"></i> Crear App
      </button>
    </div>`;
}

async function createApp() {
  const body = {
    appId:       document.getElementById('new-appId').value.trim(),
    nombre:      document.getElementById('new-nombre').value.trim(),
    version:     document.getElementById('new-version').value.trim(),
    categoria:   document.getElementById('new-cat').value,
    descripcion: document.getElementById('new-desc').value.trim(),
    enlace:      document.getElementById('new-enlace').value.trim() || '#',
    imagen:      document.getElementById('new-imagen').value.trim(),
    tag:         '🆕',
    verified:    true,
  };
  if (!body.appId || !body.nombre) return toast('❌ AppId y Nombre son obligatorios');
  try {
    const res = await fetch(`${BACKEND}/api/admin/apps`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-admin-key': ADMIN_KEY },
      body: JSON.stringify(body),
    });
    if (!res.ok) throw new Error((await res.json()).error);
    toast('✅ App creada: ' + body.nombre);
    await refreshApps();
    // Volver al tab de apps
    document.querySelectorAll('.admin-tab')[0].click();
  } catch (e) { toast('❌ ' + e.message); }
}

// ── SEED DESDE apps_data.json ─────────────────────────────────
async function seedFromJSON() {
  if (!confirm('Esto importará las 13 apps a MongoDB. ¿Continuar?')) return;
  try {
    // Datos embebidos directamente — sin fetch para evitar problemas de caché/CORS
    const mapped = [{"appId": "app-1", "nombre": "Spotify Premium", "descripcion": "Música sin anuncios, calidad máxima, descargas y saltos ilimitados.", "enlace": "https://dl.dropbox.com/scl/fi/5lal2itfo5wizqrxm2i95/Spotify-premium.apk?rlkey=qkcaenco96uri0lpmm8rq4qv9&dl=1", "plugin_enlace": null, "version": "8.9.94.598", "tag": "🆕 Nuevo", "changelog": "v8.9.94 — Corrección de bugs de reproducción · Mejor compatibilidad Android 14", "imagen": "img/Spotify.png", "categoria": "Música", "verified": true}, {"appId": "app-2", "nombre": "Spotify Lite Premium", "descripcion": "Versión ligera de Spotify con funciones premium activadas.", "enlace": "https://dl.dropbox.com/scl/fi/89dofz2hu13o1d5selu02/Spotify-Lite.apk?rlkey=g39dfjlzbq8nbpdip432lfcp8&dl=1", "plugin_enlace": null, "version": "1.9.0.178", "tag": "🆕 Nuevo", "changelog": "v1.9.0 — Modo offline mejorado · Reducción de consumo de batería", "imagen": "img/SpoLite.png", "categoria": "Música", "verified": true}, {"appId": "app-3", "nombre": "YouTube ReVanced", "descripcion": "YouTube sin anuncios, SponsorBlock integrado y gestor de descargas.", "enlace": "https://www.mediafire.com/file/f92xvb9yraadljc/YouTube_RVX.apk/file", "plugin_enlace": "https://drive.google.com/uc?export=download&id=13XQmA_U4kxODWdaqeu4lBFfVuhdDGCn3", "version": "19.47.43", "tag": "🔄 Actualizada", "changelog": "v19.47 — SponsorBlock actualizado · Nuevo gestor de descargas", "imagen": "img/YouTube.jpeg", "categoria": "Video", "verified": true}, {"appId": "app-4", "nombre": "YT Music ReVanced", "descripcion": "YouTube Music con reproducción en segundo plano sin restricciones.", "enlace": "https://www.mediafire.com/file/kyje7lv8hvcik41/TY_Music_RVX.apk/file", "plugin_enlace": "https://drive.google.com/uc?export=download&id=13XQmA_U4kxODWdaqeu4lBFfVuhdDGCn3", "version": "7.25.51", "tag": "🔄 Actualizada", "changelog": "v7.25 — Reproducción en segundo plano mejorada · Fix de crashes", "imagen": "img/YTMusic.png", "categoria": "Música", "verified": true}, {"appId": "app-5", "nombre": "TikTok Premium", "descripcion": "TikTok sin anuncios, sin marca de agua en descargas y región desbloqueada.", "enlace": "https://www.mediafire.com/file/tiktok_mod.apk/file", "plugin_enlace": null, "version": "38.5.6", "tag": "🆕 Nuevo", "changelog": "v38.5 — Nuevos filtros · Eliminación de anuncios actualizada", "imagen": "img/TikTok.svg", "categoria": "Video", "verified": true}, {"appId": "app-6", "nombre": "Flicks Remix Netflix", "descripcion": "Cliente alternativo de Netflix con calidad 4K desbloqueada.", "enlace": "#", "plugin_enlace": null, "version": "3.2.1", "tag": "🆕 Nuevo", "changelog": "v3.2.1 — Calidad 4K desbloqueada · Subtítulos mejorados", "imagen": "img/Netflix.png", "categoria": "Video", "verified": false}, {"appId": "app-7", "nombre": "Terabox Premium", "descripcion": "Almacenamiento en la nube premium con transferencias más rápidas.", "enlace": "#", "plugin_enlace": null, "version": "4.5.2", "tag": "🔄 Actualizada", "changelog": "v4.5.2 — Transferencia más rápida · Fix de login", "imagen": "img/Terabox.png", "categoria": "Utilidad", "verified": true}, {"appId": "app-8", "nombre": "MX Player Pro", "descripcion": "Reproductor de video con soporte para todos los formatos y AV1.", "enlace": "#", "plugin_enlace": null, "version": "1.3.9", "tag": "🆕 Nuevo", "changelog": "v1.3.9 — Nuevo decodificador de hardware · Soporte AV1", "imagen": "img/Player.jpg", "categoria": "Video", "verified": true}, {"appId": "app-9", "nombre": "PicsArt Premium", "descripcion": "Editor de fotos con todas las herramientas IA desbloqueadas.", "enlace": "#", "plugin_enlace": null, "version": "24.8", "tag": "🔄 Actualizada", "changelog": "v24.8 — Nuevas herramientas IA · Paquetes de stickers", "imagen": "img/Picsart.jpg", "categoria": "Foto", "verified": true}, {"appId": "app-10", "nombre": "Remini Pro", "descripcion": "Mejora la calidad de fotos antiguas o borrosas con IA avanzada.", "enlace": "#", "plugin_enlace": null, "version": "5.6.0", "tag": "🔄 Actualizada", "changelog": "v5.6.0 — Motor IA actualizado · Mejor rendimiento", "imagen": "img/Remini.png", "categoria": "Foto", "verified": true}, {"appId": "app-11", "nombre": "Magic Eraser", "descripcion": "Elimina objetos, personas o fondos de tus fotos con un toque.", "enlace": "#", "plugin_enlace": null, "version": "7.5.1", "tag": "🆕 Nuevo", "changelog": "v7.5.1 — Nuevos fondos · Fix de exportación", "imagen": "img/Eraser.jpg", "categoria": "Foto", "verified": false}, {"appId": "app-12", "nombre": "CamScanner Premium", "descripcion": "Escáner de documentos con OCR preciso y múltiples formatos de exportación.", "enlace": "#", "plugin_enlace": null, "version": "2.12.0", "tag": "🔄 Actualizada", "changelog": "v2.12.0 — Reconocimiento OCR mejorado · Nuevos formatos", "imagen": "img/CamScanner.png", "categoria": "Utilidad", "verified": true}, {"appId": "app-13", "nombre": "DNS AdGuard Pro", "descripcion": "Bloquea anuncios y rastreadores a nivel DNS en todo el dispositivo.", "enlace": "#", "plugin_enlace": null, "version": "4.3.1", "tag": "🆕 Nuevo", "changelog": "v4.3.1 — DNS over HTTPS · Lista de filtros actualizada", "imagen": "img/dnspro.png", "categoria": "Seguridad", "verified": true}];

    const seedRes = await fetch(`${BACKEND}/api/admin/seed`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-admin-key': ADMIN_KEY },
      body: JSON.stringify({ apps: mapped }),
    });
    if (!seedRes.ok) throw new Error((await seedRes.json()).error);
    const d = await seedRes.json();
    toast(`✅ Seed completado: ${d.created} creadas, ${d.updated} actualizadas`);
    await refreshApps();
  } catch (e) { toast('❌ Error en seed: ' + e.message); }
}

// ── SOLICITUDES ───────────────────────────────────────────────
async function loadAdminRequests() {
  const list = document.getElementById('req-list-admin');
  list.innerHTML = '<div style="padding:1.5rem;text-align:center;font-family:var(--mono);font-size:.75rem;color:var(--muted)">Cargando...</div>';
  try {
    const res = await fetch(`${BACKEND}/api/requests`);
    const d   = await res.json();
    document.getElementById('st-requests').textContent = d.requests?.length || 0;
    if (!d.requests?.length) {
      list.innerHTML = '<div style="padding:1.5rem;text-align:center;font-family:var(--mono);font-size:.75rem;color:var(--muted)">No hay solicitudes pendientes</div>';
      return;
    }
    list.innerHTML = d.requests.map(r => `
      <div class="req-row">
        <div class="req-name">${r.appName} <small style="font-family:var(--mono);font-size:.6rem;color:var(--muted)">${r.reason || ''}</small></div>
        <div class="req-votes">+${r.votes} votos</div>
        <button class="req-action req-done" onclick="markRequest('${r._id}','done')"><i class="fas fa-check"></i> Agregar</button>
        <button class="req-action req-reject" onclick="markRequest('${r._id}','rejected')"><i class="fas fa-times"></i> Rechazar</button>
      </div>`).join('');
  } catch {
    list.innerHTML = '<div style="padding:1.5rem;text-align:center;font-family:var(--mono);font-size:.75rem;color:#ff6b6b">Error conectando al backend</div>';
  }
}

async function markRequest(id, status) {
  try {
    await fetch(`${BACKEND}/api/requests/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', 'x-admin-key': ADMIN_KEY },
      body: JSON.stringify({ status }),
    });
    toast(status === 'done' ? '✅ Marcada como agregada' : '🗑️ Rechazada');
    loadAdminRequests();
  } catch { toast('❌ Error'); }
}

// ── RATINGS ───────────────────────────────────────────────────
async function loadAdminRatings() {
  const list = document.getElementById('ratings-list');
  try {
    const res = await fetch(`${BACKEND}/api/ratings`);
    const d   = await res.json();
    const ratings = d.ratings || {};
    const total = Object.values(ratings).reduce((s, r) => s + r.count, 0);
    document.getElementById('st-ratings').textContent = total;
    if (!Object.keys(ratings).length) {
      list.innerHTML = '<div style="padding:1.5rem;text-align:center;font-family:var(--mono);font-size:.75rem;color:var(--muted)">Aún no hay ratings</div>';
      return;
    }
    list.innerHTML = Object.entries(ratings).map(([id, r]) => {
      const app = appsData.find(a => a.appId === id);
      return `<div class="req-row">
        <div class="req-name">${app?.nombre || id}</div>
        <div style="font-family:var(--mono);font-size:.72rem;color:var(--a)">⭐ ${r.avg} / 5</div>
        <div style="font-family:var(--mono);font-size:.65rem;color:var(--muted)">${r.count} votos</div>
        <div></div>
      </div>`;
    }).join('');
  } catch {
    list.innerHTML = '<div style="padding:1.5rem;text-align:center;font-family:var(--mono);font-size:.75rem;color:#ff6b6b">Error</div>';
  }
}

async function loadAdminStats() {
  try {
    const [reqRes, ratRes] = await Promise.all([fetch(`${BACKEND}/api/requests`), fetch(`${BACKEND}/api/ratings`)]);
    const req = await reqRes.json();
    const rat = await ratRes.json();
    document.getElementById('st-requests').textContent = req.requests?.length || 0;
    document.getElementById('st-ratings').textContent  = Object.values(rat.ratings || {}).reduce((s,r) => s + r.count, 0);
  } catch {}
}

// ── TOAST ─────────────────────────────────────────────────────
function toast(m) {
  const t = document.getElementById('toast');
  t.textContent = m; t.classList.add('show');
  setTimeout(() => t.classList.remove('show'), 3500);
}

// ══════════════════════════════════════════════════════════════
//  VISITANTES — IPQuery
// ══════════════════════════════════════════════════════════════
let _allVisitors = [];

async function loadVisitors() {
  const body = document.getElementById('vt-body');
  if (!body) return;
  body.innerHTML = '<tr><td colspan="8" style="text-align:center;padding:2rem;font-family:var(--mono);color:var(--muted)">Cargando visitantes...</td></tr>';
  try {
    const res  = await fetch(`${BACKEND}/api/admin/visitors?limit=200`, {
      headers: { 'x-admin-key': ADMIN_KEY }
    });
    const data = await res.json();
    if (!res.ok || !data.ok) throw new Error(data.error || 'Error del servidor');
    _allVisitors = data.visitors || [];
    renderVisitors(_allVisitors);
    updateVisitorKPIs(_allVisitors);
  } catch (e) {
    body.innerHTML = `<tr><td colspan="8" style="text-align:center;padding:2rem;color:#ff6b6b;font-family:var(--mono)">${e.message}</td></tr>`;
  }
}

function countryFlag(code) {
  if (!code || code.length !== 2) return '🌐';
  return String.fromCodePoint(...[...code.toUpperCase()].map(c => 0x1F1E6 + c.charCodeAt(0) - 65));
}

function riskColor(score) {
  if (score >= 70) return '#ff5f56';
  if (score >= 30) return '#ffbd2e';
  return '#00e676';
}

function fmtDate(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleDateString('es-GT', { day: '2-digit', month: 'short', year: '2-digit' })
       + ' ' + d.toLocaleTimeString('es-GT', { hour: '2-digit', minute: '2-digit' });
}

function renderVisitors(list) {
  const body = document.getElementById('vt-body');
  if (!body) return;
  if (!list.length) {
    body.innerHTML = '<tr><td colspan="8" style="text-align:center;padding:2rem;font-family:var(--mono);color:var(--muted)">Sin visitas registradas aún</td></tr>';
    return;
  }
  body.innerHTML = list.map(v => `
    <tr style="cursor:pointer" onclick="showVisitorJSON(${JSON.stringify(JSON.stringify(v))})">
      <td style="font-family:var(--mono);font-size:.72rem;color:#00e5ff">${v.ip || '—'}</td>
      <td>${countryFlag(v.country_code)} ${v.country || '—'}</td>
      <td style="color:var(--muted)">${v.city || '—'}${v.region ? ', ' + v.region : ''}</td>
      <td style="max-width:160px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:.72rem" title="${v.isp||''}">${v.isp || v.org || '—'}</td>
      <td style="font-weight:700;color:${riskColor(v.risk_score||0)}">${v.risk_score||0}/100</td>
      <td>
        ${v.is_vpn   ? '<span style="background:rgba(255,189,46,.15);color:#ffbd2e;font-size:.6rem;padding:.15rem .4rem;border-radius:999px;font-family:var(--mono)">VPN</span> '   : ''}
        ${v.is_proxy ? '<span style="background:rgba(255,95,86,.15);color:#ff5f56;font-size:.6rem;padding:.15rem .4rem;border-radius:999px;font-family:var(--mono)">PROXY</span> ' : ''}
        ${v.is_bot   ? '<span style="background:rgba(168,85,247,.15);color:#a855f7;font-size:.6rem;padding:.15rem .4rem;border-radius:999px;font-family:var(--mono)">BOT</span>'   : ''}
        ${(!v.is_vpn && !v.is_proxy && !v.is_bot) ? '<span style="color:var(--muted);font-size:.7rem">—</span>' : ''}
      </td>
      <td style="font-family:var(--mono);font-size:.68rem;color:var(--muted)">${v.page || '/'}</td>
      <td style="font-family:var(--mono);font-size:.68rem;color:var(--muted);white-space:nowrap">${fmtDate(v.visited_at)}</td>
    </tr>
  `).join('');
}

function showVisitorJSON(jsonStr) {
  const data = JSON.parse(jsonStr);
  const overlay = document.getElementById('vt-json-overlay');
  const pre     = document.getElementById('vt-json-content');
  pre.textContent = JSON.stringify(data, null, 2);
  overlay.style.display = 'flex';
}

function closeVisitorJSON() {
  document.getElementById('vt-json-overlay').style.display = 'none';
}

function updateVisitorKPIs(list) {
  const today     = new Date().toDateString();
  const todayN    = list.filter(v => new Date(v.visited_at).toDateString() === today).length;
  const countries = new Set(list.map(v => v.country_code).filter(Boolean)).size;
  const vpnProxy  = list.filter(v => v.is_vpn || v.is_proxy).length;
  const set = (id, val) => { const el = document.getElementById(id); if (el) el.textContent = val; };
  set('vt-total',     list.length);
  set('vt-today',     todayN);
  set('vt-countries', countries);
  set('vt-vpn',       vpnProxy);
}

function filterVisitors() {
  const q = (document.getElementById('vt-search')?.value || '').toLowerCase();
  const filtered = q
    ? _allVisitors.filter(v => [v.ip, v.country, v.city, v.region, v.isp, v.org, v.page]
        .some(f => (f || '').toLowerCase().includes(q)))
    : _allVisitors;
  renderVisitors(filtered);
}

function exportVisitors() {
  if (!_allVisitors.length) { toast('No hay datos para exportar'); return; }
  const headers = ['IP','País','Código','Ciudad','Región','ISP','Org','VPN','Proxy','Bot','Riesgo','Página','User-Agent','Fecha'];
  const rows = _allVisitors.map(v => [
    v.ip, v.country, v.country_code, v.city, v.region,
    v.isp, v.org, v.is_vpn, v.is_proxy, v.is_bot,
    v.risk_score, v.page, v.ua, v.visited_at
  ].map(x => `"${String(x ?? '').replace(/"/g,'""')}"`).join(','));
  const csv  = [headers.join(','), ...rows].join('\n');
  const blob = new Blob([csv], { type: 'text/csv' });
  const a    = Object.assign(document.createElement('a'), {
    href: URL.createObjectURL(blob),
    download: `visitantes_${new Date().toISOString().slice(0,10)}.csv`
  });
  a.click(); toast('✅ CSV exportado');
}
