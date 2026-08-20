/* ═══════════════════════════════════════════════════════════════
   permissions-setup.js — Sistema de permisos 100% nativo
   CodeHub by Wilson.E
   
   NO depende de Chrome ni de ningún navegador.
   CodeHub gestiona sus permisos internamente con su propio
   tracking en localStorage y su propia UI para solicitarlos.
   ═══════════════════════════════════════════════════════════════ */
'use strict';

const PermissionsSetup = (() => {
  const STORAGE_KEY = 'ch_perms_setup_v2';
  const PERMS_KEY = 'ch_perms_state_v2';
  const DENIED_LOG_KEY = 'ch_perms_denied_log';

  // Estado interno de CodeHub (sin consultar al navegador)
  const _internalState = {};

  /* ── ¿Corremos dentro de la APK nativa? ── */
  function _isNativeApp() {
    return !!(window.__apkNative && window.CodeHubNative &&
      typeof window.CodeHubNative.requestRuntimePermissions === 'function');
  }

  /* ── Definiciones de permisos ── */
  const PERM_DEFS = [
    {
      id: 'notifications',
      name: 'Notificaciones',
      icon: '🔔',
      desc: 'Alertas de clima, novedades y actualizaciones en tiempo real',
      nativeCheck: async () => {
        if (_isNativeApp()) {
          const ap = window.__apkPermissions || {};
          return ap.notifications ? 'granted' : 'prompt';
        }
        if (!('Notification' in window)) return 'unsupported';
        return Notification.permission;
      },
      nativeRequest: async () => {
        if (!('Notification' in window)) return 'unsupported';
        if (Notification.permission === 'granted') return 'granted';
        if (Notification.permission === 'denied') return 'denied';
        const r = await Notification.requestPermission();
        return r;
      }
    },
    {
      id: 'geolocation',
      name: 'Ubicación',
      icon: '📍',
      desc: 'Clima local y contenido personalizado',
      nativeCheck: async () => {
        if (_isNativeApp()) {
          const ap = window.__apkPermissions || {};
          return ap.location ? 'granted' : 'prompt';
        }
        if (!('geolocation' in navigator)) return 'unsupported';
        // Intentar obtener ubicación silenciosamente para verificar
        return new Promise(resolve => {
          navigator.geolocation.getCurrentPosition(
            () => resolve('granted'),
            e => resolve(e.code === 1 ? 'denied' : 'prompt'),
            { enableHighAccuracy: false, timeout: 1000, maximumAge: Infinity }
          );
        });
      },
      nativeRequest: () => new Promise(resolve => {
        if (!('geolocation' in navigator)) return resolve('unsupported');
        navigator.geolocation.getCurrentPosition(
          () => resolve('granted'),
          e => resolve(e.code === 1 ? 'denied' : 'error'),
          { enableHighAccuracy: true, timeout: 8000, maximumAge: 60000 }
        );
      })
    },
    {
      id: 'microphone',
      name: 'Micrófono',
      icon: '🎤',
      desc: 'Comandos de voz con EMI (asistente IA)',
      nativeCheck: async () => {
        if (_isNativeApp()) {
          const ap = window.__apkPermissions || {};
          return ap.microphone ? 'granted' : 'prompt';
        }
        if (!navigator.mediaDevices?.getUserMedia) return 'unsupported';
        // Verificar si ya tenemos permiso accediendo al micrófono
        return 'prompt'; // Por defecto asumimos pendiente
      },
      nativeRequest: async () => {
        if (!navigator.mediaDevices?.getUserMedia) return 'unsupported';
        try {
          const s = await navigator.mediaDevices.getUserMedia({ audio: true });
          s.getTracks().forEach(t => t.stop());
          return 'granted';
        } catch (e) {
          return e.name === 'NotAllowedError' ? 'denied' : 'error';
        }
      }
    },
    {
      id: 'clipboard-read',
      name: 'Portapapeles',
      icon: '📋',
      desc: 'Leer URLs del portapapeles en el Downloader',
      nativeCheck: async () => {
        if (!navigator.clipboard?.readText) return 'unsupported';
        return 'prompt';
      },
      nativeRequest: async () => {
        if (!navigator.clipboard?.readText) return 'unsupported';
        try {
          await navigator.clipboard.readText();
          return 'granted';
        } catch (e) {
          return e.name === 'NotAllowedError' ? 'denied' : 'error';
        }
      }
    },
    {
      id: 'persistent-storage',
      name: 'Almacenamiento',
      icon: '💾',
      desc: 'Mantener datos offline y caché de la app',
      nativeCheck: async () => {
        if (!navigator.storage?.persist) return 'unsupported';
        try {
          const p = await navigator.storage.persisted();
          return p ? 'granted' : 'prompt';
        } catch { return 'prompt'; }
      },
      nativeRequest: async () => {
        if (!navigator.storage?.persist) return 'unsupported';
        const r = await navigator.storage.persist();
        return r ? 'granted' : 'denied';
      }
    }
  ];

  /* ── Tracking interno de CodeHub ── */
  function _getInternalState() {
    try { return JSON.parse(localStorage.getItem(PERMS_KEY) || '{}'); } catch { return {}; }
  }

  function _setInternalState(id, status) {
    _internalState[id] = status;
    try {
      const state = _getInternalState();
      state[id] = { status, timestamp: Date.now() };
      localStorage.setItem(PERMS_KEY, JSON.stringify(state));
    } catch {}
  }

  function _isSetupDone() {
    try { return localStorage.getItem(STORAGE_KEY) === 'done_v2'; } catch { return false; }
  }

  function _markSetupDone() {
    try { localStorage.setItem(STORAGE_KEY, 'done_v2'); } catch {}
  }

  function _logDenied(id) {
    try {
      const log = JSON.parse(localStorage.getItem(DENIED_LOG_KEY) || '{}');
      log[id] = { denied: true, timestamp: Date.now(), count: (log[id]?.count || 0) + 1 };
      localStorage.setItem(DENIED_LOG_KEY, JSON.stringify(log));
    } catch {}
  }

  function _isPermanentlyDenied(id) {
    try {
      const log = JSON.parse(localStorage.getItem(DENIED_LOG_KEY) || '{}');
      return log[id]?.denied && log[id]?.count >= 2;
    } catch { return false; }
  }

  /* ── UI: Overlay de setup ── */
  function _showSetupOverlay(onComplete) {
    const overlay = document.createElement('div');
    overlay.id = 'perms-setup-overlay';
    overlay.className = 'perms-overlay';
    overlay.innerHTML = `
      <div class="perms-card">
        <div class="perms-card-glow"></div>
        <div class="perms-card-header">
          <div class="perms-card-icon">✨</div>
          <h2>Configura CodeHub</h2>
          <p>Habilita los permisos para la mejor experiencia</p>
        </div>
        <div class="perms-card-list" id="perms-setup-list">
          ${PERM_DEFS.map(p => `
            <div class="perms-item" data-perm="${p.id}">
              <div class="perms-item-left">
                <span class="perms-item-icon">${p.icon}</span>
                <div>
                  <div class="perms-item-name">${p.name}</div>
                  <div class="perms-item-desc">${p.desc}</div>
                </div>
              </div>
              <div class="perms-item-status" id="perms-status-${p.id}">Pendiente</div>
            </div>
          `).join('')}
        </div>
        <div class="perms-card-actions">
          <button class="perms-btn perms-btn-primary" id="perms-enable-all">
            <i class="fas fa-check-double"></i> Habilitar todo
          </button>
          <button class="perms-btn perms-btn-secondary" id="perms-skip">
            Ahora no
          </button>
        </div>
        <div class="perms-card-footer">
          Los permisos se gestionan desde la app — sin depender de ningún navegador
        </div>
      </div>`;

    document.body.appendChild(overlay);
    requestAnimationFrame(() => overlay.classList.add('active'));

    let processing = false;

    async function _requestPermSequentially(permDef) {
      const statusEl = document.getElementById(`perms-status-${permDef.id}`);
      if (statusEl) {
        statusEl.textContent = 'Solicitando...';
        statusEl.className = 'perms-item-status pending';
      }

      // Verificar si el usuario ya denegó este permiso antes
      if (_isPermanentlyDenied(permDef.id)) {
        _setInternalState(permDef.id, 'denied');
        if (statusEl) {
          statusEl.textContent = '❌ Denegado';
          statusEl.className = 'perms-item-status denied';
        }
        return;
      }

      try {
        const result = await permDef.nativeRequest();
        _setInternalState(permDef.id, result);

        if (result === 'denied') {
          _logDenied(permDef.id);
        }

        if (statusEl) {
          if (result === 'granted') {
            statusEl.textContent = '✅ Activado';
            statusEl.className = 'perms-item-status granted';
          } else if (result === 'denied') {
            statusEl.textContent = '❌ Denegado';
            statusEl.className = 'perms-item-status denied';
            // Mostrar dialog de CodeHub explicando cómo habilitar
            _showDeniedDialog(permDef);
          } else if (result === 'unsupported') {
            statusEl.textContent = '⚠️ No disponible';
            statusEl.className = 'perms-item-status unsupported';
          } else {
            statusEl.textContent = '⚠️ Error';
            statusEl.className = 'perms-item-status error';
          }
        }
      } catch {
        _setInternalState(permDef.id, 'error');
        if (statusEl) {
          statusEl.textContent = '⚠️ Error';
          statusEl.className = 'perms-item-status error';
        }
      }
    }

    // Espera a que Android resuelva los diálogos nativos (o hasta 8s de
    // margen) tras CodeHubNative.requestRuntimePermissions().
    function _waitForNativePermsResult() {
      return new Promise(resolve => {
        let done = false;
        const finish = () => { if (done) return; done = true; window.__onApkPermsUpdated = null; resolve(); };
        window.__onApkPermsUpdated = finish;
        setTimeout(finish, 8000);
      });
    }

    async function _requestAll() {
      if (processing) return;
      processing = true;
      const btn = document.getElementById('perms-enable-all');
      if (btn) { btn.disabled = true; btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Solicitando...'; }

      if (_isNativeApp()) {
        // En la APK, un solo llamado nativo dispara los diálogos del
        // sistema operativo en secuencia (notificaciones, ubicación,
        // cámara, micrófono). El usuario ya vio el porqué en esta tarjeta.
        const waitResult = _waitForNativePermsResult();
        try { window.CodeHubNative.requestRuntimePermissions(); } catch {}
        await waitResult;
        for (const perm of PERM_DEFS) {
          const statusEl = document.getElementById(`perms-status-${perm.id}`);
          const result = await perm.nativeCheck();
          _setInternalState(perm.id, result);
          if (statusEl) {
            if (result === 'granted') { statusEl.textContent = '✅ Activado'; statusEl.className = 'perms-item-status granted'; }
            else if (result === 'unsupported') { statusEl.textContent = '⚠️ No disponible'; statusEl.className = 'perms-item-status unsupported'; }
            else { statusEl.textContent = '⚠️ Pendiente'; statusEl.className = 'perms-item-status pending'; }
          }
        }
      } else {
        for (const perm of PERM_DEFS) {
          await _requestPermSequentially(perm);
          await new Promise(r => setTimeout(r, 400));
        }
      }

      _markSetupDone();
      const skipBtn = document.getElementById('perms-skip');
      if (skipBtn) skipBtn.style.display = 'none';
      if (btn) {
        btn.innerHTML = '<i class="fas fa-check"></i> ¡Listo!';
        btn.className = 'perms-btn perms-btn-success';
      }
      setTimeout(() => {
        overlay.classList.remove('active');
        setTimeout(() => overlay.remove(), 400);
        if (onComplete) onComplete(_internalState);
      }, 1200);
    }

    function _skip() {
      // Marcar como pendientes en el estado interno
      for (const perm of PERM_DEFS) {
        if (!_internalState[perm.id]) _setInternalState(perm.id, 'prompt');
      }
      _markSetupDone();
      overlay.classList.remove('active');
      setTimeout(() => overlay.remove(), 400);
      if (onComplete) onComplete(_internalState);
    }

    document.getElementById('perms-enable-all')?.addEventListener('click', _requestAll);
    document.getElementById('perms-skip')?.addEventListener('click', _skip);
  }

  /* ── UI: Dialog de permiso denegado (100% CodeHub) ── */
  function _showDeniedDialog(permDef) {
    const dialog = document.createElement('div');
    dialog.className = 'perms-overlay';
    dialog.innerHTML = `
      <div class="perms-card perms-card-denied">
        <div class="perms-card-header">
          <div class="perms-card-icon">${permDef.icon}</div>
          <h2>${permDef.name} — Permiso denegado</h2>
          <p>${permDef.desc}</p>
        </div>
        <div class="perms-denied-steps">
          <div class="perms-denied-step">
            <span class="perms-step-num">1</span>
            <span>Ve a la configuración de tu dispositivo</span>
          </div>
          <div class="perms-denied-step">
            <span class="perms-step-num">2</span>
            <span>Busca "CodeHub" en aplicaciones</span>
          </div>
          <div class="perms-denied-step">
            <span class="perms-step-num">3</span>
            <span>Activa el permiso de <strong>${permDef.name.toLowerCase()}</strong></span>
          </div>
          <div class="perms-denied-step">
            <span class="perms-step-num">4</span>
            <span>Vuelve a abrir CodeHub</span>
          </div>
        </div>
        <div class="perms-card-actions">
          <button class="perms-btn perms-btn-primary" onclick="this.closest('.perms-overlay').remove()">
            <i class="fas fa-check"></i> Entendido
          </button>
        </div>
      </div>`;

    document.body.appendChild(dialog);
    requestAnimationFrame(() => dialog.classList.add('active'));
    dialog.addEventListener('click', e => {
      if (e.target === dialog) dialog.remove();
    });
  }

  /* ── UI: Panel de ajustes (menú) ── */
  async function showSettingsPanel() {
    const existing = document.getElementById('perms-settings-overlay');
    if (existing) { existing.classList.add('active'); return; }

    const states = {};
    for (const perm of PERM_DEFS) {
      try {
        const s = await perm.nativeCheck();
        _setInternalState(perm.id, s);
        states[perm.id] = s;
      } catch {
        states[perm.id] = 'error';
      }
    }

    const overlay = document.createElement('div');
    overlay.id = 'perms-settings-overlay';
    overlay.className = 'perms-overlay';
    overlay.innerHTML = `
      <div class="perms-card perms-card-settings">
        <div class="perms-card-header">
          <div class="perms-card-icon">⚙️</div>
          <h2>Ajustes y Permisos</h2>
          <p>Gestiona los accesos de CodeHub</p>
        </div>
        <div class="perms-card-list">
          ${PERM_DEFS.map(p => {
            const st = states[p.id] || 'unknown';
            const label = st === 'granted' ? '✅ Activado' : st === 'denied' ? '❌ Denegado' : st === 'unsupported' ? '⚠️ No disponible' : '⚠️ Pendiente';
            return `
            <div class="perms-item">
              <div class="perms-item-left">
                <span class="perms-item-icon">${p.icon}</span>
                <div>
                  <div class="perms-item-name">${p.name}</div>
                  <div class="perms-item-desc">${p.desc}</div>
                </div>
              </div>
              <div class="perms-item-status ${st}">${label}</div>
            </div>`;
          }).join('')}
        </div>
        <div class="perms-card-actions">
          <button class="perms-btn perms-btn-primary" id="perms-retry-all">
            <i class="fas fa-rotate"></i> Solicitar de nuevo
          </button>
          <button class="perms-btn perms-btn-secondary" id="perms-close-settings">
            Cerrar
          </button>
        </div>
        <div class="perms-card-footer">
          CodeHub gestiona sus permisos sin depender de ningún navegador
        </div>
      </div>`;

    document.body.appendChild(overlay);
    requestAnimationFrame(() => overlay.classList.add('active'));

    document.getElementById('perms-close-settings')?.addEventListener('click', () => {
      overlay.classList.remove('active');
      setTimeout(() => overlay.remove(), 400);
    });

    document.getElementById('perms-retry-all')?.addEventListener('click', async () => {
      // Resetear denied log para permitir reintentos
      try { localStorage.removeItem(DENIED_LOG_KEY); } catch {}
      const btn = document.getElementById('perms-retry-all');
      if (btn) { btn.disabled = true; btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Solicitando...'; }
      for (const perm of PERM_DEFS) {
        try {
          const s = await perm.nativeRequest();
          _setInternalState(perm.id, s);
          // Actualizar UI del item
          const statusEl = overlay.querySelector(`[data-perm="${perm.id}"] .perms-item-status`);
          if (statusEl) {
            const label = s === 'granted' ? '✅ Activado' : s === 'denied' ? '❌ Denegado' : s === 'unsupported' ? '⚠️ No disponible' : '⚠️ Error';
            statusEl.textContent = label;
            statusEl.className = `perms-item-status ${s}`;
          }
          if (s === 'denied') _showDeniedDialog(perm);
        } catch {}
        await new Promise(r => setTimeout(r, 300));
      }
      if (btn) { btn.innerHTML = '<i class="fas fa-check"></i> ¡Actualizado!'; btn.disabled = false; }
      setTimeout(() => { if (btn) btn.innerHTML = '<i class="fas fa-rotate"></i> Solicitar de nuevo'; }, 1500);
    });

    overlay.addEventListener('click', e => {
      if (e.target === overlay) {
        overlay.classList.remove('active');
        setTimeout(() => overlay.remove(), 400);
      }
    });
  }

  /* ── Badging API ── */
  function setBadge(count) {
    if ('setAppBadge' in navigator) {
      navigator.setAppBadge(count).catch(() => {});
    }
  }

  function clearBadge() {
    if ('clearAppBadge' in navigator) {
      navigator.clearAppBadge().catch(() => {});
    }
  }

  /* ── API pública ── */
  function getPermissionStatus(id) {
    return _internalState[id] || _getInternalState()[id]?.status || 'prompt';
  }

  function isPermissionGranted(id) {
    return getPermissionStatus(id) === 'granted';
  }

  /* ── Init ── */
  function init() {
    // Cargar estado interno del localStorage
    const saved = _getInternalState();
    Object.keys(saved).forEach(k => { _internalState[k] = saved[k]?.status || 'prompt'; });

    if (!_isSetupDone()) {
      // Esperar a que el splash termine (3-5s) para no bloquear la app
      function _tryShow() {
        const splash = document.getElementById('ch-splash');
        if (splash && splash.style.display !== 'none') {
          // El splash sigue visible, esperar 1s más
          setTimeout(_tryShow, 1000);
          return;
        }
        _showSetupOverlay();
      }
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => setTimeout(_tryShow, 500));
      } else {
        setTimeout(_tryShow, 500);
      }
    }
  }

  return {
    init,
    showSettingsPanel,
    setBadge,
    clearBadge,
    getPermissionStatus,
    isPermissionGranted,
    PERM_DEFS
  };
})();

PermissionsSetup.init();
