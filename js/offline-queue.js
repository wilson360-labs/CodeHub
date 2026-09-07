/* js/offline-queue.js — Cola de escritura offline con IndexedDB.
 *
 * Guarda operaciones de escritura (POST/DELETE) que el usuario hace estando
 * sin conexión (votos/ratings, memoria de Wil.E) y las reenvía cuando la
 * conexión regresa — de forma automática (evento `online`, carga de página,
 * mensaje del SW en un sync) o manual (window.ChQueue.flush()).
 *
 * API pública:
 *   window.ChQueue.add({ url, method, headers, body, kind })  → Promise<true>
 *   window.ChQueue.count()                                    → Promise<number>
 *   window.ChQueue.all()                                      → Promise<Array>
 *   window.ChQueue.remove(id)                                 → Promise
 *   window.ChQueue.flush()                                    → Promise<{sent,failed,kept}>
 *
 * Sin dependencias. Los headers Authorization se guardan tal cual: si el token
 * expira antes del reenvío, el endpoint devuelve 401 y el ítem se descarta.
 */
(function () {
  'use strict';

  var DB_NAME = 'codehub-offline';
  var DB_VER = 1;
  var STORE = 'writes';
  var dbPromise = null;
  var flushing = false;

  function openDB() {
    if (dbPromise) return dbPromise;
    if (!('indexedDB' in window)) {
      dbPromise = Promise.reject(new Error('IndexedDB no soportado'));
      return dbPromise;
    }
    dbPromise = new Promise(function (resolve, reject) {
      var req = indexedDB.open(DB_NAME, DB_VER);
      req.onupgradeneeded = function (e) {
        var db = e.target.result;
        if (!db.objectStoreNames.contains(STORE)) {
          var st = db.createObjectStore(STORE, { keyPath: 'id', autoIncrement: true });
          st.createIndex('ts', 'ts');
        }
      };
      req.onsuccess = function () { resolve(req.result); };
      req.onerror = function () { reject(req.error); };
    });
    return dbPromise;
  }

  function run(mode, fn) {
    return openDB().then(function (db) {
      return new Promise(function (resolve, reject) {
        var tx = db.transaction(STORE, mode);
        var s = tx.objectStore(STORE);
        var req = fn(s);
        var out;
        req.onsuccess = function () { out = req.result; };
        tx.oncomplete = function () { resolve(out); };
        tx.onerror = function () { reject(tx.error); };
        tx.onabort = function () { reject(tx.error); };
      });
    });
  }

  function cleanHeaders(h) {
    var out = {};
    if (!h || typeof h !== 'object') return out;
    Object.keys(h).forEach(function (k) {
      var lk = k.toLowerCase();
      if (lk === 'content-length' || lk === 'host' || lk === 'cookie') return;
      out[k] = h[k];
    });
    return out;
  }

  function askSwSync() {
    try {
      if (navigator.serviceWorker && navigator.serviceWorker.controller) {
        navigator.serviceWorker.controller.postMessage({ type: 'REGISTER_SYNC' });
      }
    } catch (e) { /* silencioso */ }
  }

  // Mini-toast autocontenido (no depende del resto de la app)
  var toastEl = null;
  function toast(msg, ms) {
    try {
      if (!toastEl) {
        toastEl = document.createElement('div');
        toastEl.style.cssText = 'position:fixed;bottom:2rem;left:50%;transform:translateX(-50%);background:rgba(15,15,30,.97);color:#e7ecff;border:1px solid rgba(56,189,248,.35);padding:.6rem 1.2rem;border-radius:12px;font-size:.8rem;font-family:ui-monospace,Menlo,Consolas,monospace;z-index:99999;box-shadow:0 8px 30px rgba(0,0,0,.4);opacity:0;transition:opacity .25s;pointer-events:none;max-width:88vw;text-align:center';
        (document.body || document.documentElement).appendChild(toastEl);
      }
      toastEl.textContent = msg;
      toastEl.style.opacity = '1';
      clearTimeout(toast._t);
      toast._t = setTimeout(function () { toastEl.style.opacity = '0'; }, ms || 2600);
    } catch (e) { /* silencioso */ }
  }

  function notify(detail) {
    try {
      window.dispatchEvent(new CustomEvent('ch:queuesync', { detail: detail }));
    } catch (e) { /* silencioso */ }
  }

  window.ChQueue = {
    add: function (item) {
      var rec = {
        url: String(item.url || ''),
        method: String(item.method || 'POST').toUpperCase(),
        headers: cleanHeaders(item.headers),
        body: item.body != null ? item.body : '',
        kind: item.kind || 'write',
        ts: Date.now()
      };
      if (!rec.url) return Promise.resolve(false);
      return run('readwrite', function (s) { return s.add(rec); })
        .then(function () { askSwSync(); return true; })
        .catch(function () { return false; });
    },

    count: function () {
      if (!('indexedDB' in window)) return Promise.resolve(0);
      return openDB().then(function (db) {
        return new Promise(function (resolve) {
          try {
            var t = db.transaction(STORE, 'readonly');
            var c = t.objectStore(STORE).count();
            c.onsuccess = function () { resolve(c.result || 0); };
            c.onerror = function () { resolve(0); };
          } catch (e) { resolve(0); }
        });
      }).catch(function () { return 0; });
    },

    all: function () {
      if (!('indexedDB' in window)) return Promise.resolve([]);
      return openDB().then(function (db) {
        return new Promise(function (resolve) {
          try {
            var t = db.transaction(STORE, 'readonly');
            var g = t.objectStore(STORE).getAll();
            g.onsuccess = function () { resolve(g.result || []); };
            g.onerror = function () { resolve([]); };
          } catch (e) { resolve([]); }
        });
      }).catch(function () { return []; });
    },

    remove: function (id) {
      if (!('indexedDB' in window)) return Promise.resolve();
      return run('readwrite', function (s) { return s.delete(id); }).catch(function () {});
    },

    toast: toast,

    flush: function () {
      if (flushing) return Promise.resolve({ sent: 0, failed: 0, kept: 0 });
      flushing = true;
      return this.all().then(function (items) {
        if (!items.length) { flushing = false; return { sent: 0, failed: 0, kept: 0 }; }
        if (!navigator.onLine) { flushing = false; return { sent: 0, failed: 0, kept: items.length }; }
        var sent = 0, failed = 0;
        return Promise.all(items.map(function (rec) {
          return fetch(rec.url, { method: rec.method, headers: rec.headers, body: rec.body })
            .then(function (r) {
              if (r.ok || r.status === 409 || r.status === 401 || r.status === 400 || r.status === 404) {
                // 409 = ya votado (dedupe por IP, procesado). 401/400/404 no
                // tienen cura en un reintento → descartar para no atascar la cola.
                return window.ChQueue.remove(rec.id).then(function () { sent++; });
              }
              failed++;
            })
            .catch(function () { failed++; });
        })).then(function () {
          flushing = false;
          var pending = items.length - sent;
          notify({ sent: sent, failed: failed, pending: pending });
          if (sent > 0 && pending === 0) toast('✅ ' + sent + ' acción' + (sent === 1 ? '' : 'es') + ' sincronizada' + (sent === 1 ? '' : 's'));
          else if (pending > 0 && failed > 0) toast('📡 No se pudieron sincronizar ' + pending + ' — se reintentará');
          return { sent: sent, failed: failed, kept: pending };
        });
      }).catch(function () {
        flushing = false;
        return { sent: 0, failed: 0, kept: 0 };
      });
    }
  };

  var flushSoon = (function () {
    var timer = null;
    return function () {
      if (timer) return;
      timer = setTimeout(function () {
        timer = null;
        if (navigator.onLine) window.ChQueue.flush();
      }, 1200);
    };
  })();

  // Disparadores automáticos de vaciado
  window.addEventListener('online', flushSoon);
  window.addEventListener('load', function () { setTimeout(flushSoon, 600); });
  if (document.readyState !== 'loading') setTimeout(flushSoon, 600);

  if (navigator.serviceWorker) {
    navigator.serviceWorker.addEventListener('message', function (e) {
      if (e.data && e.data.type === 'CH_QUEUE_FLUSH') flushSoon();
    });
  }

  window.ChQueue.count().then(function (n) {
    if (n > 0 && navigator.onLine) { flushSoon(); }
    else if (n > 0) { toast('📴 ' + n + ' acción' + (n === 1 ? '' : 'es') + ' pendiente' + (n === 1 ? '' : 's') + ' de sincronizar'); }
  });
})();