/* ═══════════════════════════════════════════════════════════════
   permissions-setup.js — Setup automático de permisos PWA
   CodeHub by Wilson.E
   
   Se ejecuta al cargar la app. Si es la primera vez, solicita
   permisos automáticamente. Después del setup, muestra un panel
   de ajustes accesible desde el menú.
   ═══════════════════════════════════════════════════════════════ */
'use strict';

const PermissionsSetup = (() => {
  const STORAGE_KEY = 'ch_perms_setup';
  const PERMS_KEY = 'ch_perms_state';

  const PERM_DEFS = [
    {
      id: 'notifications',
      name: 'Notificaciones',
      icon: '🔔',
      desc: 'Alertas de clima, novedades y actualizaciones',
      request: async () => {
        if (!('Notification' in window)) return 'unsupported';
        if (Notification.permission === 'granted') return 'granted';
        if (Notification.permission === 'denied') return 'denied';
        const r = await Notification.requestPermission();
        return r;
      },
      check: async () => {
        if (!('Notification' in window)) return 'unsupported';
        return Notification.permission;
      }
    },
    {
      id: 'geolocation',
      name: 'Ubicación',
      icon: '📍',
      desc: 'Clima local y contenido personalizado',
      request: () => new Promise(resolve => {
        if (!('geolocation' in navigator)) return resolve('unsupported');
        navigator.geolocation.getCurrentPosition(
          () => resolve('granted'),
          e => resolve(e.code === 1 ? 'denied' : 'error'),
          { enableHighAccuracy: true, timeout: 8000, maximumAge: 60000 }
        );
      }),
      check: async () => {
        if (!('geolocation' in navigator)) return 'unsupported';
        try {
          const r = await navigator.permissions.query({ name: 'geolocation' });
          return r.state;
        } catch { return 'prompt'; }
      }
    },
    {
      id: 'microphone',
      name: 'Micrófono',
      icon: '🎤',
      desc: 'Comandos de voz con EMI',
      request: async () => {
        if (!navigator.mediaDevices?.getUserMedia) return 'unsupported';
        try {
          const s = await navigator.mediaDevices.getUserMedia({ audio: true });
          s.getTracks().forEach(t => t.stop());
          return 'granted';
        } catch (e) {
          return e.name === 'NotAllowedError' ? 'denied' : 'error';
        }
      },
      check: async () => {
        if (!navigator.mediaDevices?.getUserMedia) return 'unsupported';
        try {
          const r = await navigator.permissions.query({ name: 'microphone' });
          return r.state;
        } catch { return 'prompt'; }
      }
    },
    {
      id: 'clipboard-read',
      name: 'Portapapeles',
      icon: '📋',
      desc: 'Leer URLs del portapapeles en el Downloader',
      request: async () => {
        if (!navigator.clipboard?.readText) return 'unsupported';
        try {
          await navigator.clipboard.readText();
          return 'granted';
        } catch (e) {
          return e.name === 'NotAllowedError' ? 'denied' : 'error';
        }
      },
      check: async () => {
        if (!navigator.clipboard?.readText) return 'unsupported';
        try {
          const r = await navigator.permissions.query({ name: 'clipboard-read' });
          return r.state;
        } catch { return 'prompt'; }
      }
    },
    {
      id: 'persistent-storage',
      name: 'Almacenamiento',
      icon: '💾',
      desc: 'Mantener datos offline y caché de la app',
      request: async () => {
        if (!navigator.storage?.persist) return 'unsupported';
        const r = await navigator.storage.persist();
        return r ? 'granted' : 'denied';
      },
      check: async () => {
        if (!navigator.storage?.persist) return 'unsupported';
        try {
          const r = await navigator.permissions.query({ name: 'persistent-storage' });
          return r.state;
        } catch { return 'prompt'; }
      }
    }
  ];

  function _isSetupDone() {
    try { return localStorage.getItem(STORAGE_KEY) === 'done'; } catch { return false; }
  }

  function _markSetupDone() {
    try { localStorage.setItem(STORAGE_KEY, 'done'); } catch {}
  }

  function _savePermState(states) {
    try { localStorage.setItem(PERMS_KEY, JSON.stringify(states)); } catch {}
  }

  function _loadPermState() {
    try { return JSON.parse(localStorage.getItem(PERMS_KEY) || '{}'); } catch { return {}; }
  }

  /* ── Overlay de setup ── */
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
          <p>Habilita los permisos para una experiencia completa</p>
        </div>
        <div class="perms-card-list">
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
          Puedes cambiar estos ajustes después desde el menú
        </div>
      </div>`;

    document.body.appendChild(overlay);
    requestAnimationFrame(() => overlay.classList.add('active'));

    const states = {};
    let processing = false;

    async function _requestPerm(permDef) {
      const statusEl = document.getElementById(`perms-status-${permDef.id}`);
      if (statusEl) {
        statusEl.textContent = 'Solicitando...';
        statusEl.className = 'perms-item-status pending';
      }
      try {
        const result = await permDef.request();
        states[permDef.id] = result;
        if (statusEl) {
          if (result === 'granted') {
            statusEl.textContent = '✅ Activado';
            statusEl.className = 'perms-item-status granted';
          } else if (result === 'denied') {
            statusEl.textContent = '❌ Denegado';
            statusEl.className = 'perms-item-status denied';
          } else if (result === 'unsupported') {
            statusEl.textContent = '⚠️ No disponible';
            statusEl.className = 'perms-item-status unsupported';
          } else {
            statusEl.textContent = '⚠️ Error';
            statusEl.className = 'perms-item-status error';
          }
        }
      } catch {
        states[permDef.id] = 'error';
        if (statusEl) {
          statusEl.textContent = '⚠️ Error';
          statusEl.className = 'perms-item-status error';
        }
      }
    }

    async function _requestAll() {
      if (processing) return;
      processing = true;
      const btn = document.getElementById('perms-enable-all');
      if (btn) { btn.disabled = true; btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Solicitando...'; }
      for (const perm of PERM_DEFS) {
        await _requestPerm(perm);
        await new Promise(r => setTimeout(r, 300));
      }
      _savePermState(states);
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
        if (onComplete) onComplete(states);
      }, 1200);
    }

    function _skip() {
      _savePermState(states);
      _markSetupDone();
      overlay.classList.remove('active');
      setTimeout(() => overlay.remove(), 400);
      if (onComplete) onComplete(states);
    }

    document.getElementById('perms-enable-all')?.addEventListener('click', _requestAll);
    document.getElementById('perms-skip')?.addEventListener('click', _skip);
  }

  /* ── Panel de ajustes (menú) ── */
  async function showSettingsPanel() {
    const existing = document.getElementById('perms-settings-overlay');
    if (existing) { existing.classList.add('active'); return; }

    const states = {};
    for (const perm of PERM_DEFS) {
      states[perm.id] = await perm.check();
    }

    const overlay = document.createElement('div');
    overlay.id = 'perms-settings-overlay';
    overlay.className = 'perms-overlay';
    overlay.innerHTML = `
      <div class="perms-card perms-card-settings">
        <div class="perms-card-header">
          <div class="perms-card-icon">⚙️</div>
          <h2>Ajustes y Permisos</h2>
          <p>Administra los accesos de la app</p>
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
          <button class="perms-btn perms-btn-secondary" id="perms-open-system-settings">
            <i class="fas fa-gear"></i> Ajustes del sistema
          </button>
          <button class="perms-btn perms-btn-secondary" id="perms-close-settings">
            Cerrar
          </button>
        </div>
      </div>`;

    document.body.appendChild(overlay);
    requestAnimationFrame(() => overlay.classList.add('active'));

    document.getElementById('perms-close-settings')?.addEventListener('click', () => {
      overlay.classList.remove('active');
      setTimeout(() => overlay.remove(), 400);
    });

    document.getElementById('perms-open-system-settings')?.addEventListener('click', () => {
      const isAndroid = /android/i.test(navigator.userAgent);
      const isChrome = /chrome/i.test(navigator.userAgent) && !/edg/i.test(navigator.userAgent);
      if (isAndroid && isChrome) {
        const origin = encodeURIComponent(window.location.origin);
        window.location.href = `intent://settings/#Intent;package=com.android.chrome;scheme=chrome;S=${origin};end`;
      } else {
        if (typeof toast === 'function') toast('Ve a Ajustes del navegador → Permisos del sitio', 'info', 4000);
      }
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

  /* ── Init ── */
  function init() {
    if (!_isSetupDone()) {
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', () => _showSetupOverlay());
      } else {
        _showSetupOverlay();
      }
    }
  }

  return { init, showSettingsPanel, setBadge, clearBadge, PERM_DEFS };
})();

PermissionsSetup.init();
