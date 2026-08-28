// script.js — CodeHub
// Funciones auxiliares del sitio & puente universal Web/Nativo

// ── PUENTE NATIVO CROSS-PLATFORM (APK + WEB) ─────────────────
window.chShare = function (title, text, url) {
  title = title || 'CodeHub';
  text = text || '';
  url = url || window.location.href;
  if (window.CodeHubNative && typeof window.CodeHubNative.shareText === 'function') {
    window.CodeHubNative.shareText(title, text, url);
    return true;
  }
  if (navigator.share) {
    navigator.share({ title: title, text: text, url: url }).catch(() => {});
    return true;
  }
  // Fallback: copiar link
  window.chCopy(url);
  return false;
};

window.chVibrate = function (ms) {
  ms = ms || 40;
  if (window.CodeHubNative && typeof window.CodeHubNative.vibrate === 'function') {
    window.CodeHubNative.vibrate(ms);
    return true;
  }
  if (navigator.vibrate) {
    navigator.vibrate(ms);
    return true;
  }
  return false;
};

window.chCopy = function (text) {
  if (!text) return Promise.resolve(false);
  if (window.CodeHubNative && typeof window.CodeHubNative.copyToClipboard === 'function') {
    window.CodeHubNative.copyToClipboard(text);
    return Promise.resolve(true);
  }
  if (navigator.clipboard && navigator.clipboard.writeText) {
    return navigator.clipboard.writeText(text).then(() => true).catch(() => false);
  }
  try {
    const el = document.createElement('textarea');
    el.value = text;
    el.setAttribute('readonly', '');
    el.style.position = 'absolute';
    el.style.left = '-9999px';
    document.body.appendChild(el);
    el.select();
    const ok = document.execCommand('copy');
    document.body.removeChild(el);
    return Promise.resolve(ok);
  } catch (e) {
    return Promise.resolve(false);
  }
};

window.chClearCache = function () {
  if (window.CodeHubNative && typeof window.CodeHubNative.clearAppCache === 'function') {
    window.CodeHubNative.clearAppCache();
    return;
  }
  if ('caches' in window) {
    caches.keys().then(names => Promise.all(names.map(name => caches.delete(name)))).then(() => {
      localStorage.clear();
      sessionStorage.clear();
      window.location.reload(true);
    });
  } else {
    localStorage.clear();
    sessionStorage.clear();
    window.location.reload(true);
  }
};

// Floating menu (si existe en el DOM)
const floatingMenu = document.getElementById('floating-menu');
if (floatingMenu) {
  floatingMenu.addEventListener('click', () => {
    console.log('Menú flotante activado');
  });
  floatingMenu.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') floatingMenu.click();
  });
}

