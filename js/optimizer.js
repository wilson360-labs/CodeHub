/* ════════════════════════════════════════════════════════════════════
   optimizer.js — Panel "Optimización del Sistema" (solo APK/WebView)
   CodeHub by Wilson.E
   - Se comunica con el bridge nativo (CodeHubNative/CodeHubBridge.java)
     que a su vez usa SystemOptimizer.kt (Shizuku + libsu).
   - En navegador normal (sin CodeHubNative) la card se oculta.
   - 4 pestañas: Diagnóstico / Limpiador / Procesos / Bloatware.
   - Local-first: cada llamada nativa corre en Thread de fondo; el JS recibe
     JSON crudo y lo parsea. Nunca se bloquea el hilo del WebView.
   ════════════════════════════════════════════════════════════════════ */
(() => {
  'use strict';

  const NATIVE = window.CodeHubNative;
  const isNative = !!NATIVE && typeof NATIVE.optimizerStatus === 'function';

  const els = {};
  let state = { status: null, backend: '', root: false, tab: 'diag' };

  // ── utilidades ──────────────────────────────────────────────────
  function fmtB(n) {
    n = Number(n) || 0;
    if (n >= 1073741824) return (n / 1073741824).toFixed(2) + ' GB';
    if (n >= 1048576) return (n / 1048576).toFixed(1) + ' MB';
    if (n >= 1024) return (n / 1024).toFixed(0) + ' KB';
    return n + ' B';
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function toast(msg, ok) {
    const t = document.getElementById('toast');
    if (!t) return;
    t.style.color = ok ? 'var(--green)' : '#ff6b6b';
    t.textContent = msg;
    t.classList.add('on');
    clearTimeout(t.__toastT);
    t.__toastT = setTimeout(() => t.classList.remove('on'), 2400);
  }

  function setStatusUI() {
    if (!els.status) return;
    const st = state.status;
    const map = {
      READY: ['Listo', 'var(--green)'],
      NEEDS_PERMISSION: ['Permiso pendiente', '#ffb454'],
      NOT_RUNNING: ['Shizuku apagado', '#ff6b6b'],
      NOT_INSTALLED: ['Shizuku no instalado', '#ff6b6b'],
      DISCONNECTED: ['Desconectado', '#ff6b6b']
    };
    const [label, color] = map[st] || [st, 'var(--muted)'];
    els.status.textContent = label;
    els.status.style.color = color;
    els.status.style.borderColor = color;
    if (els.backend) els.backend.textContent = state.backend || '—';
    if (els.root) {
      els.root.textContent = state.root ? 'root' : 'ADB';
      els.root.style.color = state.root ? 'var(--green)' : 'var(--a)';
    }
    const needPerm = st === 'NEEDS_PERMISSION';
    if (els.permBtn) els.permBtn.style.display = needPerm ? 'inline-flex' : 'none';
    if (els.goBtn) els.goBtn.disabled = st !== 'READY';
  }

  function nativeCall(method, ...args) {
    return new Promise(resolve => {
      const name = '__opt_' + method + '_' + Date.now() + '_' + Math.random().toString(36).slice(2);
      window[name] = data => { try { resolve(typeof data === 'string' ? JSON.parse(data) : (data || {})); } catch (e) { resolve({ error: 'respuesta inválida' }); } finally { delete window[name]; } };
      try {
        NATIVE[method](name, ...args);
      } catch (e) {
        delete window[name];
        resolve({ error: e && e.message ? e.message : 'método no disponible' });
      }
    });
  }

  // ── pestañas ────────────────────────────────────────────────────
  function onTab(tab, btn) {
    state.tab = tab;
    (els.tabs || []).forEach(b => b.classList.remove('on'));
    if (btn) btn.classList.add('on');
    renderTab();
  }

  function renderTab() {
    if (!els.panel) return;
    if (state.status === 'NEEDS_PERMISSION') {
      els.panel.innerHTML =
        '<div class="opt-note" style="text-align:center;padding:1.2rem">' +
        'Concede el permiso de Shizuku para activar la optimización. ' +
        '<button class="btn bp" id="opt-perm" style="margin:0 auto" onclick="window.__optGrant()">' +
        '<i class="fas fa-bolt"></i> Activar permiso</button></div>';
      return;
    }
    if (state.status !== 'READY') {
      els.panel.innerHTML =
        '<div class="opt-note">La optimización necesita <strong>Shizuku</strong> activo ' +
        '(ADB inalámbrico o root). Instala Shizuku, empareja por ADB y vuelve aquí.</div>';
      return;
    }
    const fns = { diag: renderDiag, limpiar: renderLimpiar, procesos: renderProcesos, bloat: renderBloat };
    const fn = fns[state.tab] || renderDiag;
    els.panel.classList.remove('opt-loaded');
    els.panel.innerHTML = '<div class="opt-note" style="text-align:center;padding:1rem">⏳ Cargando…</div>';
    requestAnimationFrame(() => { fn(); els.panel.classList.add('opt-loaded'); });
  }

  // ── Diagnóstico ──────────────────────────────────────────────────
  async function renderDiag() {
    const d = await nativeCall('optimizerDiagnostics');
    els.panel.innerHTML = buildDiagHTML(d);
  }

  function buildDiagHTML(d) {
    if (d && d.error) return '<div class="opt-note err">' + esc(d.error) + '</div>';
    const ram = d.ram || {};
    const storage = Array.isArray(d.storage) ? d.storage : [];
    const bat = d.battery || {};
    const th = d.thermal || {};
    const ramPct = ram.pct || 0;
    const ramBarColor = ramPct > 90 ? '#ff6b6b' : ramPct > 70 ? '#ffb454' : 'var(--green)';
    const tempColor = (th.maxC || 0) > 45 ? '#ff6b6b' : (th.maxC || 0) > 38 ? '#ffb454' : 'var(--green)';

    let html = '<div class="opt-grid">';
    html += '<div class="opt-tile"><div class="opt-tile-h"><i class="fas fa-microchip"></i> RAM</div>' +
      '<div class="opt-tile-v">' + fmtB(ram.mbUsed * 1048576) + ' <span>/ ' + fmtB(ram.mbTotal * 1048576) + '</span></div>' +
      '<div class="opt-bar-w"><div class="opt-bar" style="width:' + ramPct + '%;background:' + ramBarColor + '"></div></div>' +
      '<div class="opt-tile-f">' + ramPct + '% en uso' + (ram.kbAvailable ? ' · ' + fmtB(ram.kbAvailable * 1024) + ' libres' : '') + '</div></div>';

    html += '<div class="opt-tile"><div class="opt-tile-h"><i class="fas fa-battery-three-quarters"></i> Batería</div>' +
      '<div class="opt-tile-v">' + (bat.level || '—') + '%</div>' +
      '<div class="opt-tile-f">' + esc(bat.status || '—') + (bat.charging ? ' ⚡' : '') + '</div>' +
      '<div class="opt-tile-f">' + (bat.tempC || '—') + '°C · ' + (bat.tempF || '—') + '°F</div></div>';

    html += '<div class="opt-tile"><div class="opt-tile-h"><i class="fas fa-temperature-half"></i> Temperatura</div>' +
      '<div class="opt-tile-v" style="color:' + tempColor + '">' + (th.maxC || '—') + '°C</div>' +
      '<div class="opt-tile-f">' + esc(th.zone || '—') + '</div></div>';
    html += '</div>';

    if (storage.length) {
      html += '<div class="opt-sec-h"><i class="fas fa-database"></i> Almacenamiento</div>';
      html += '<div class="opt-list">';
      storage.forEach(p => {
        const pc = p.pct || 0;
        const c = pc > 90 ? '#ff6b6b' : pc > 70 ? '#ffb454' : 'var(--green)';
        html += '<div class="opt-row"><div class="opt-row-l">' + esc(p.mounted) +
          '<div class="opt-bar-w"><div class="opt-bar" style="width:' + pc + '%;background:' + c + '"></div></div></div>' +
          '<div class="opt-row-r">' + fmtB(p.kbUsed * 1024) + ' / ' + fmtB(p.kbTotal * 1024) + ' <span>(' + pc + '%)</span></div></div>';
      });
      html += '</div>';
    }

    html += '<div style="display:flex;gap:.45rem;flex-wrap:wrap;margin-top:.6rem">' +
      '<button class="btn bg" onclick="window.__optRefresh()"><i class="fas fa-rotate"></i> Actualizar</button></div>';
    return html;
  }

  // ── Limpiador ────────────────────────────────────────────────────
  async function renderLimpiar() {
    const c = await nativeCall('optimizerCacheStats');
    let html = '';
    if (c && c.rootOnly) {
      html += '<div class="opt-note"><i class="fas fa-info-circle"></i> La pre-visión y limpieza por app requieren ' +
        '<strong>Shizuku con root</strong>. Sin root puedes usar la limpieza global.</div>';
    } else if (c && c.error) {
      html += '<div class="opt-note err">' + esc(c.error) + '</div>';
    } else if (c) {
      const apps = c.apps || [];
      html += '<div class="opt-note">Caché recuperable detectada: <strong>' + fmtB(c.totalBytes || 0) + '</strong>' +
        (state.root ? '' : ' (con root se amplía la pre-visión)') + '</div>';
      if (apps.length) {
        html += '<div class="opt-list">';
        apps.forEach(a => {
          html += '<div class="opt-row"><div class="opt-row-l" style="min-width:0">' + esc(a.pkg) +
            '<div class="opt-row-sub">' + fmtB(a.bytes || 0) + '</div></div>' +
            '<button class="tb bc" onclick="window.__optClear(\'' + esc(a.pkg) + '\')" data-haptic="game">Limpiar</button></div>';
        });
        html += '</div>';
      }
    }
    html += '<div style="display:flex;gap:.45rem;flex-wrap:wrap;margin-top:.6rem">' +
      '<button class="btn bp" onclick="window.__optTrim()" data-haptic="game"><i class="fas fa-broom"></i> Limpiar caché global</button>' +
      (state.root ? '' : '<button class="btn bg" onclick="window.__optRefreshCache()"><i class="fas fa-rotate"></i> Revisar</button>') +
      '</div>';
    if (state.root) {
      html += '<div style="display:flex;gap:.45rem;flex-wrap:wrap;margin-top:.45rem"><button class="btn bg" onclick="window.__optRefreshCache()"><i class="fas fa-rotate"></i> Reanalizar</button></div>';
    }
    els.panel.innerHTML = html;
  }

  // ── Procesos ─────────────────────────────────────────────────────
  async function renderProcesos() {
    let html = '<div class="opt-note"><i class="fas fa-bolt"></i> Cierra las apps que se quedan en segundo ' +
      'plano y libera RAM. Las apps esenciales de Android quedan protegidas.</div>';
    html += '<div style="display:flex;gap:.45rem;flex-wrap:wrap;margin-top:.5rem">' +
      '<button class="btn bp" onclick="window.__optKill()" data-haptic="game"><i class="fas fa-power-off"></i> Cerrar en segundo plano</button></div>' +
      '<div id="opt-kill-res"></div>';
    els.panel.innerHTML = html;
  }

  // ── Bloatware ────────────────────────────────────────────────────
  async function renderBloat() {
    const s = await nativeCall('optimizerSystemApps');
    if (s && s.error) {
      els.panel.innerHTML = '<div class="opt-note err">' + esc(s.error) + '</div>';
      return;
    }
    const apps = s && s.apps ? s.apps : [];
    let html = '<div class="opt-note"><i class="fas fa-shield-halved"></i> Deshabilita apps de sistema que no ' +
      'usas para liberar RAM y batería. Puedes reactivarlas cuando quieras.</div>';
    html += '<div style="display:flex;gap:.45rem;flex-wrap:wrap;margin:.5rem 0 .3rem">' +
      '<button class="btn bg" onclick="window.__optLoadBloat()"><i class="fas fa-rotate"></i> Cargar apps</button></div>';
    if (apps.length) {
      html += '<div class="opt-list">';
      apps.forEach(a => {
        const on = !!a.enabled;
        html += '<div class="opt-row"><div class="opt-row-l" style="min-width:0">' + esc(a.label) +
          '<div class="opt-row-sub">' + esc(a.pkg) + '</div></div>' +
          '<button class="tb ' + (on ? '' : 'primary') + '" onclick="window.__optToggle(\'' + esc(a.pkg) + '\',\'' + (on ? '0' : '1') + '\')" data-haptic="game">' +
          (on ? 'Deshabilitar' : 'Activar') + '</button></div>';
      });
      html += '</div>';
      html += '<div style="font-size:.66rem;color:var(--muted);font-family:var(--mono);margin-top:.3rem">' + apps.length + ' apps de sistema candidatas (no críticas).</div>';
    } else {
      html += '<div class="opt-note">Sin apps candidatas o permiso insuficiente.</div>';
    }
    els.panel.innerHTML = html;
  }

  // ── acciones ─────────────────────────────────────────────────────
  async function __optTrim() {
    toast('Limpiando caché global…');
    const cbName = '__opt_trim_' + Date.now() + '_' + Math.random().toString(36).slice(2);
    window[cbName] = (status, msg) => {
      delete window[cbName];
      const ok = String(status || '').indexOf('OK') === 0;
      toast(msg || (ok ? 'Caché limpiada' : 'Error'), ok);
      window.__optRefreshCache && window.__optRefreshCache();
    };
    try {
      NATIVE.shizukuTrimCache(cbName, 512);
    } catch (e) {
      delete window[cbName];
      toast('Error al limpiar caché', false);
    }
  }

  async function __optClear(pkg) {
    if (!window.confirm('¿Limpiar la caché de ' + pkg + '? Se cerrará la app.')) return;
    toast('Limpiando ' + pkg + '…');
    const r = await nativeCall('optimizerClearAppCache', pkg);
    if (r && r.ok) {
      toast('Caché de ' + pkg + ' liberada: ' + fmtB(r.freedBytes), true);
      window.__optRefreshCache && window.__optRefreshCache();
    } else {
      toast((r && r.error) || 'No se pudo limpiar', false);
    }
  }

  async function __optRefreshCache() {
    state.tab = 'limpiar';
    renderTab();
  }

  async function __optKill() {
    const btn = els.panel && els.panel.querySelector('#opt-kill-res');
    toast('Cerrando procesos…');
    const r = await nativeCall('optimizerKillCached');
    const stopped = r && r.stopped ? r.stopped.length : 0;
    const failed = r && r.failed ? r.failed.length : 0;
    toast('Procesos cerrados: ' + stopped + (failed ? ' · fallos: ' + failed : ''), !failed);
    if (btn) {
      btn.innerHTML = '<div class="opt-note">Detenidas: <strong>' + stopped + '</strong>' +
        (failed ? ' · con error: ' + failed : '') + '</div>';
    }
    if (r && r.stopped && r.stopped.length) toast('Rutinas cerradas: ' + stopped, true);
  }

  async function __optToggle(pkg, wantOn) {
    const on = wantOn === '1';
    const accion = on ? 'activar' : 'deshabilitar';
    if (!window.confirm('¿' + cap(accion) + ' ' + pkg + '?' + (on ? '' : ' Su app se cerrará.'))) return;
    toast((on ? 'Activando…' : 'Deshabilitando…') + ' ' + pkg);
    const r = await nativeCall('optimizerSetAppEnabled', pkg, on);
    if (r && r.ok) {
      toast((on ? 'Activada' : 'Deshabilitada') + ': ' + pkg, true);
      state.tab = 'bloat';
      renderTab();
    } else {
      toast((r && r.error) || 'Operación rechazada', false);
    }
  }

  async function __optRefresh() {
    renderTab();
  }

  async function __optLoadBloat() {
    state.tab = 'bloat';
    els.panel.innerHTML = '<div class="opt-note" style="text-align:center;padding:1rem">⏳ Cargando apps…</div>';
    renderBloat();
  }

  async function __optGrant() {
    toast('Esperando confirmación de Shizuku…');
    const cbName = '__opt_grant_' + Date.now() + '_' + Math.random().toString(36).slice(2);
    window[cbName] = granted => {
      delete window[cbName];
      toast(granted ? 'Permiso activado' : 'Permiso denegado', !!granted);
      refreshStatus(true);
    };
    try {
      NATIVE.shizukuRequestPermission(cbName);
    } catch (e) {
      delete window[cbName];
      toast('No se pudo pedir el permiso', false);
    }
  }

  function cap(s) { return s.charAt(0).toUpperCase() + s.slice(1); }

  // ── estado / init ────────────────────────────────────────────────
  async function refreshStatus(andRender) {
    const s = await nativeCall('optimizerStatus');
    state.status = s.status || 'DISCONNECTED';
    state.backend = s.backend || '';
    state.root = !!s.root;
    setStatusUI();
    const ready = state.status === 'READY';
    if (els.goBtn) { els.goBtn.style.display = ready ? 'block' : 'none'; }
    if (els.dead) {
      els.dead.style.display = state.status === 'READY' ? 'none' : 'block';
    }
    if (andRender) renderTab();
  }

  function init() {
    const card = document.getElementById('opt-card');
    if (!card) return;
    if (!isNative) { card.style.display = 'none'; return; }

    els.status = document.getElementById('opt-status');
    els.backend = document.getElementById('opt-backend');
    els.root = document.getElementById('opt-root');
    els.permBtn = document.getElementById('opt-perm-btn');
    els.goBtn = document.getElementById('opt-go');
    els.dead = document.getElementById('opt-dead');
    els.panel = document.getElementById('opt-panel');
    els.tabs = Array.prototype.slice.call(document.querySelectorAll('#opt-tabs .tab'));

    window.__optRefresh = __optRefresh;
    window.__optRefreshCache = __optRefreshCache;
    window.__optClear = __optClear;
    window.__optTrim = __optTrim;
    window.__optKill = __optKill;
    window.__optToggle = __optToggle;
    window.__optLoadBloat = __optLoadBloat;
    window.__optGrant = __optGrant;
    window.__optTab = onTab;

    card.style.display = '';
    refreshStatus();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();