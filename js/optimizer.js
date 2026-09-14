/* ════════════════════════════════════════════════════════════════════
   optimizer.js — Panel "Optimización del Sistema" (solo APK/WebView)
   CodeHub by Wilson.E
   - Se comunica con el bridge nativo (CodeHubNative/CodeHubBridge.java)
     que a su vez usa SystemOptimizer.kt (Shizuku + libsu).
   - En navegador normal (sin CodeHubNative) la card se muestra bloqueada,
     explicando que solo corre dentro de la app CodeHub.
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
    const fns = { diag: renderDiag, limpiar: renderLimpiar, procesos: renderProcesos, bloat: renderBloat, scripts: renderScripts, impulso: renderImpulso };
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

    // Anillo de RAM (visualización destacada)
    html += '<div class="opt-tile">' +
      '<div class="opt-ring" style="--p:' + ramPct + '%;--c:' + ramBarColor + '">' +
      '<div class="opt-ring-in"><b>' + ramPct + '%</b><span>RAM</span></div></div>' +
      '<div class="opt-tile-h" style="justify-content:center;margin-top:.4rem"><i class="fas fa-microchip"></i> Memoria en uso</div>' +
      '<div class="opt-tile-v" style="text-align:center">' + fmtB(ram.mbUsed * 1048576) + ' <span>/ ' + fmtB(ram.mbTotal * 1048576) + '</span></div>' +
      (ram.kbAvailable ? '<div class="opt-tile-f" style="text-align:center;color:var(--green)">' + fmtB(ram.kbAvailable * 1024) + ' libres</div>' : '') +
      '</div>';

    // Anillo de batería + temperatura
    const batPct = Number(bat.level) || 0;
    const batColor = bat.charging ? 'var(--green)' : batPct < 20 ? '#ff6b6b' : '#ffb454';
    html += '<div class="opt-tile">' +
      '<div class="opt-ring" style="--p:' + batPct + '%;--c:' + batColor + '">' +
      '<div class="opt-ring-in"><b>' + batPct + '%</b><span>' + (bat.charging ? '⚡' : 'Bat') + '</span></div></div>' +
      '<div class="opt-tile-h" style="justify-content:center;margin-top:.4rem"><i class="fas fa-temperature-half"></i> Temperatura</div>' +
      '<div class="opt-tile-v" style="text-align:center;color:' + tempColor + '">' + (th.maxC || '—') + '°C</div>' +
      '<div class="opt-tile-f" style="text-align:center">' + esc(bat.status || '—') + (bat.tempF ? ' · ' + bat.tempF + '°F' : '') + '</div>' +
      '</div>';

    html += '<div class="opt-tile"><div class="opt-tile-h"><i class="fas fa-heart-circle-check"></i> Salud de la batería</div>' +
      '<div class="opt-tile-v">' + esc(bat.health || '—') + '</div>' +
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

  // ── Scripts (consola segura) ─────────────────────────────────────
  const SCRIPT_ALLOW = ['df', 'du', 'cat', 'echo', 'dumpsys', 'pm', 'am', 'ps', 'top', 'free',
    'uptime', 'uname', 'getprop', 'date', 'wc', 'ls', 'head', 'tail', 'grep', 'id', 'whoami', 'env'];
  const SCRIPT_DENY = ['su ', 'sudo', 'reboot', 'shutdown', 'rm ', 'chmod', 'chown', 'mkfs',
    'dd ', 'mount', 'umount', 'svc ', 'settings put', 'wipe', 'fastboot', 'wpa_supplicant',
    'iptables', 'ifconfig', 'getenforce'];
  const PRESETS = [
    { name: 'Estado del equipo', script:
      '# Memoria' + '\n' + 'dumpsys meminfo | head -25' + '\n' + 'echo ----' + '\n' +
      '# Almacenamiento' + '\n' + 'df -h | head -10' + '\n' + 'echo ----' + '\n' +
      '# Uptime' + '\n' + 'uptime' },
    { name: 'Procesos top', script: 'top -b -n 1 | head -30' },
    { name: 'Batería', script: '# Batería y estado de carga' + '\n' + 'dumpsys battery' },
    { name: 'Apps en ejecución', script: 'ps -A | head -40' },
    { name: 'Propiedades (build)', script: 'getprop ro.build.version.release' + '\n' + 'getprop ro.product.model' + '\n' + 'getprop ro.product.brand' }
  ];

  let scriptDraft = PRESETS[0].script;

  async function renderScripts() {
    let html = '<div class="opt-note"><i class="fas fa-terminal"></i> Ejecuta comandos de ' +
      'diagnóstico con la identidad de Shizuku. <strong>Permitidos:</strong> ' +
      SCRIPT_ALLOW.join(', ') + '. Comandos destructivos (su, rm, reboot, mount, wipe…) están bloqueados.</div>';
    html += '<div style="display:flex;gap:.4rem;flex-wrap:wrap;margin:.55rem 0">' +
      PRESETS.map((p, i) => '<button class="btn bg" data-haptic="game" onclick="window.__optPreset(' + i + ')">' + esc(p.name) + '</button>').join('') +
      '</div>';
    html += '<textarea id="opt-script-in" rows="7" spellcheck="false" placeholder="# Escribe tu script — una línea de comando por línea">️</textarea>';
    html += '<div style="display:flex;gap:.45rem;flex-wrap:wrap;margin-top:.45rem">' +
      '<button class="btn bp" data-haptic="game" onclick="window.__optRunScript()"><i class="fas fa-play"></i> Ejecutar</button>' +
      '<button class="btn bg" onclick="window.__optClearScript()"><i class="fas fa-eraser"></i> Limpiar</button></div>';
    html += '<div id="opt-script-out"></div>';
    els.panel.innerHTML = html;
    const tx = document.getElementById('opt-script-in');
    if (tx) {
      tx.value = scriptDraft;
      tx.addEventListener('input', () => { scriptDraft = tx.value; });
    }
  }

  function clientScriptError(script) {
    const lines = script.split('\n').map(l => l.trim()).filter(l => l && l[0] !== '#');
    if (!lines.length) return 'Escribe al menos una línea.';
    if (lines.some(l => SCRIPT_DENY.some(t => l.toLowerCase().indexOf(t) !== -1))) {
      return 'Contiene comandos no permitidos (su, rm, reboot, mount, wipe…).';
    }
    for (let i = 0; i < lines.length; i++) {
      const bin = lines[i].split(/\s+/)[0].split('/').pop();
      if (bin && SCRIPT_ALLOW.indexOf(bin) === -1) return 'Binario no permitido: ' + bin;
    }
    return null;
  }

  async function __optPreset(i) {
    if (PRESETS[i]) scriptDraft = PRESETS[i].script;
    renderTab();
  }

  function __optClearScript() {
    scriptDraft = '';
    const tx = document.getElementById('opt-script-in');
    if (tx) tx.value = '';
  }

  async function __optRunScript() {
    const tx = document.getElementById('opt-script-in');
    if (tx) scriptDraft = tx.value;
    const err = clientScriptError(scriptDraft);
    if (err) { toast(err, false); return; }
    const out = document.getElementById('opt-script-out');
    if (out) out.innerHTML = '<div class="opt-note" style="text-align:center;padding:.8rem">⏳ Ejecutando…</div>';
    const r = await nativeCall('optimizerRunScript', scriptDraft);
    if (out) {
      if (r && r.error) {
        out.innerHTML = '<div class="opt-note err">' + esc(r.error) + '</div>';
        toast('Script rechazado', false);
      } else if (r && r.ok) {
        const body = Array.isArray(r.lines) ? r.lines.join('\n') : '';
        out.innerHTML = '<div class="opt-script-out"><pre>' +
          (body ? esc(body) : '<span style="color:var(--muted)">(sin salida)</span>') +
          '</pre><div style="font-size:.64rem;color:var(--muted);font-family:var(--mono);margin-top:.3rem">exit ' + (r.code || 0) + '</div></div>';
        toast('Script ejecutado · exit ' + (r.code || 0), r.code === 0);
      }
    }
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

  // ── Impulso IA (plan inteligente) ────────────────────────────────
  async function renderImpulso() {
    const p = await nativeCall('optimizerBoostPlan');
    if (p && p.error) {
      els.panel.innerHTML = '<div class="opt-note err">' + esc(p.error) + '</div>';
      return;
    }
    const score = Math.max(0, Math.min(100, Number(p.score) || 0));
    const scoreColor = score >= 75 ? 'var(--green)' : score >= 50 ? '#ffb454' : '#ff6b6b';

    const axes = [
      { k: 'ramScore', label: 'Memoria', icon: 'fa-microchip' },
      { k: 'storageScore', label: 'Almacenamiento', icon: 'fa-database' },
      { k: 'batteryScore', label: 'Batería', icon: 'fa-battery-three-quarters' },
      { k: 'thermalScore', label: 'Térmico', icon: 'fa-temperature-half' },
      { k: 'procsScore', label: 'Segundo plano', icon: 'fa-diagram-project' }
    ];

    // Escala de 0..100 → barra: los scores YA son "mejor = más alto".
    let html = '<div class="opt-note"><i class="fas fa-wand-magic-sparkles"></i> Análisis inteligente ' +
      'local (sin enviar datos): puntúa RAM, almacenamiento, batería, temperatura y procesos ' +
      'en segundo plano reales, y sugiere solo acciones que liberan memoria/espacio de verdad.</div>';

    html += '<div class="opt-tile" style="align-items:center;margin-top:.6rem">' +
      '<div class="opt-ring" style="--p:' + score + '%;--c:' + scoreColor + '">' +
      '<div class="opt-ring-in" style="width:86px;height:86px"><b>' + score + '/100</b><span>salud</span></div></div>' +
      '<div class="opt-tile-v" style="font-size:.95rem">' +
      (score >= 75 ? 'En buen estado' : score >= 50 ? 'Aceptable, hay margen' : 'Requiere atención') + '</div></div>';

    html += '<div class="opt-grid" style="grid-template-columns:repeat(2,1fr);margin-top:.55rem">';
    axes.forEach(a => {
      const v = Math.max(0, Math.min(100, Number(p[a.k] !== undefined ? p[a.k] : null) || 0));
      const c = v >= 75 ? 'var(--green)' : v >= 50 ? '#ffb454' : '#ff6b6b';
      html += '<div class="opt-tile"><div class="opt-tile-h"><i class="fas ' + a.icon + '"></i> ' + a.label + '</div>' +
        '<div class="opt-tile-v"><span>' + v + '%</span></div>' +
        '<div class="opt-bar-w"><div class="opt-bar" style="width:' + v + '%;background:' + c + '"></div></div></div>';
    });
    html += '</div>';

    const recs = Array.isArray(p.recs) ? p.recs.filter(r => r && r.title) : [];
    if (recs.length) {
      html += '<div class="opt-sec-h"><i class="fas fa-list-check"></i> Plan sugerido</div><div class="opt-list">';
      recs.forEach((r, i) => {
        const isInfo = r.kind === 'info';
        const isOk = r.kind === 'ok';
        html += '<div class="opt-row" style="align-items:flex-start">' +
          '<div class="opt-row-l" style="white-space:normal">' +
          '<i class="fas ' + (isInfo ? 'fa-circle-info' : isOk ? 'fa-circle-check' : 'fa-wand-magic-sparkles') +
          '" style="color:var(--a);margin-right:.3rem"></i>' + esc(r.title) +
          (r.detail ? '<div class="opt-row-sub" style="white-space:normal">' + esc(r.detail) + '</div>' : '') +
          '</div>' +
          (isInfo || isOk ? '' :
            '<button class="tb ' + (i === 0 ? 'primary' : '') + '" data-haptic="game" onclick="window.__optApply(\'' + esc(r.kind) + '\')">Aplicar</button>') +
          '</div>';
      });
      html += '</div>';
      html += '<div style="display:flex;gap:.45rem;flex-wrap:wrap;margin-top:.6rem">' +
        '<button class="btn bp" onclick="window.__optApplyAll()" data-haptic="game"><i class="fas fa-bolt"></i> Optimizar ahora</button>' +
        '<button class="btn bg" onclick="window.__optRefreshImpulso()"><i class="fas fa-rotate"></i> Reanalizar</button></div>';
    }

    if (p.recoverableKb) {
      html += '<div style="font-size:.66rem;color:var(--muted);font-family:var(--mono);margin-top:.5rem">' +
        'RAM recuperable en segundo plano: ~' + fmtB(Number(p.recoverableKb) * 1024) +
        (p.backend ? ' · vía ' + esc(p.backend) : '') + '</div>';
    }
    els.panel.innerHTML = html;
  }

  async function __optApply(kind) {
    if (kind === 'kill') return __optKill();
    if (kind === 'trim') return __optTrim();
    if (kind === 'cache') return __optClearAllCaches();
    toast('Acción no disponible', false);
  }

  async function __optApplyAll() {
    const plan = await nativeCall('optimizerBoostPlan');
    const recs = plan && Array.isArray(plan.recs) ? plan.recs : [];
    const actions = recs.filter(r => r && r.kind && r.kind !== 'info' && r.kind !== 'ok');
    if (!actions.length) { toast('Nada que optimizar', false); return; }
    toast('Optimizando…');
    let ok = true;
    for (const r of actions) {
      if (r.kind === 'kill') { const k = await nativeCall('optimizerKillCached'); if (k && k.failed && k.failed.length) ok = false; }
      else if (r.kind === 'trim') {
        try { await __optTrim(); } catch (e) { ok = false; }
      } else if (r.kind === 'cache') {
        const res = await nativeCall('optimizerCacheStats');
        const apps = (res && res.apps) ? res.apps.slice(0, 6) : [];
        for (const a of apps) { try { await nativeCall('optimizerClearAppCache', a.pkg); } catch (e) { ok = false; } }
      }
    }
    toast(ok ? 'Optimización completada' : 'Terminado con algunos fallos', ok);
    renderTab();
  }

  async function __optClearAllCaches() {
    const res = await nativeCall('optimizerCacheStats');
    if (!res || res.error) { toast((res && res.error) || 'No se pudo analizar la caché', false); return; }
    const apps = (res && res.apps) ? res.apps.slice(0, 6) : [];
    if (!apps.length) { toast('Sin caché por app limpiable', false); return; }
    toast('Limpiando caché de ' + apps.length + ' apps…');
    let bad = 0;
    for (const a of apps) {
      const r = await nativeCall('optimizerClearAppCache', a.pkg);
      if (!r || !r.ok) bad++;
    }
    toast(bad ? 'Caché limpiada (' + bad + ' fallos)' : 'Caché de apps limpiada', !bad);
    window.__optRefreshCache && window.__optRefreshCache();
  }

  // ── estado / init ────────────────────────────────────────────────
  async function refreshStatus(andRender) {
    const s = await nativeCall('optimizerStatus');
    applyStatus(s);
    const ready = state.status === 'READY';
    if (els.goBtn) { els.goBtn.style.display = ready ? 'block' : 'none'; }
    if (els.dead) {
      els.dead.style.display = state.status === 'READY' ? 'none' : 'block';
    }
    if (andRender) renderTab();
  }

  // Estado entrante (respuesta puntual o push en vivo de Shizuku).
  function applyStatus(s) {
    if (!s || !s.status) return;
    const prev = state.status;
    state.status = s.status;
    state.backend = s.backend || state.backend;
    state.root = !!s.root;
    setStatusUI();
    const ready = state.status === 'READY';
    if (els.goBtn) { els.goBtn.style.display = ready ? 'block' : 'none'; }
    if (els.dead) { els.dead.style.display = state.status === 'READY' ? 'none' : 'block'; }
    if (prev !== state.status && els.panel) renderTab();
  }

  // Push en vivo: el nativo avisa cuando el binder/permiso cambia (abrir
  // Shizuku, conceder el permiso, volver a la app). Esto arregla el caso en
  // el que el panel se quedaba en "pendiente" tras conceder el permiso.
  window.__optOnState = json => {
    let s;
    try { s = typeof json === 'string' ? JSON.parse(json) : (json || {}); } catch (e) { return; }
    applyStatus(s);
  };

  function init() {
    const card = document.getElementById('opt-card');
    if (!card) return;

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
    window.__optPreset = __optPreset;
    window.__optRunScript = __optRunScript;
    window.__optClearScript = __optClearScript;
    window.__optTab = onTab;
    window.__optApply = __optApply;
    window.__optApplyAll = __optApplyAll;
    window.__optRefreshImpulso = renderTab;

    card.style.display = '';

    // Sin bridge = web o navegador normal. Antes la tarjeta desaparecía y
    // nadie entendía por qué; ahora se muestra bloqueada explicando que el
    // motor Shizuku solo existe dentro de la app CodeHub.
    if (!NATIVE) {
      if (els.status) {
        els.status.textContent = 'Solo en la app';
        els.status.style.color = '#8ab4f8';
        els.status.style.borderColor = '#8ab4f8';
      }
      if (els.dead) {
        els.dead.style.display = 'block';
        els.dead.innerHTML = '<i class="fas fa-mobile-screen"></i> ' +
          'La optimización con Shizuku solo corre <strong>dentro de la app CodeHub</strong> (Android): ' +
          'Herramientas → Optimización del Sistema. Un navegador no tiene privilegios de sistema ' +
          'y no puede usar Shizuku.';
      }
      if (els.goBtn) els.goBtn.style.display = 'none';
      return;
    }

    // APK instalado sin el motor Shizuku (versión vieja): mostrar la tarjeta
    // con aviso claro en vez de esconderla silenciosamente.
    if (!isNative) {
      if (els.status) {
        els.status.textContent = 'App desactualizada';
        els.status.style.color = '#ffb454';
        els.status.style.borderColor = '#ffb454';
      }
      if (els.dead) {
        els.dead.style.display = 'block';
        els.dead.innerHTML = '<i class="fas fa-download"></i> Esta versión de la app no incluye el motor Shizuku. ' +
          'Actualiza CodeHub y vuelve a esta tarjeta para activar ' +
          '<strong>Diagnóstico, Limpiador, Procesos, Bloatware y Scripts</strong>.';
      }
      if (els.goBtn) els.goBtn.style.display = 'none';
      return;
    }

    refreshStatus();

    // Suscripción al estado en vivo de Shizuku (APKs nuevos). En APKs
    // antiguos el método no existe y solo funciona la consulta puntual.
    try {
      if (typeof NATIVE.shizukuSubscribe === 'function') {
        NATIVE.shizukuSubscribe('__optOnState');
      }
    } catch (e) { /* noop */ }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();