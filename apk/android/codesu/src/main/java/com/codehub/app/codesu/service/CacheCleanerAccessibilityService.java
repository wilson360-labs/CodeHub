package com.codehub.app.codesu.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.text.Normalizer;

import com.codehub.app.codesu.model.CacheAppInfo;
import com.codehub.app.codesu.util.CacheCleaner;
import com.codehub.app.codesu.worker.CacheCleanerWorker;

/** User-started Settings UI automation fallback for devices without Shizuku. */
public final class CacheCleanerAccessibilityService extends AccessibilityService {
    public static final String PREFS = "codesu_cache_accessibility";
    public static final String KEY_RUNNING = "running";
    public static final String KEY_RESULT_PENDING = "result_pending";
    public static final String KEY_SNAPSHOT_ID = "snapshot_id";
    public static final String KEY_INDEX = "index";
    public static final String KEY_DONE = "done";
    public static final String KEY_FAILED = "failed";
    public static final String KEY_FREED = "freed";
    public static final String KEY_TARGET = "target";
    public static final String KEY_ERROR = "error";
    public static final String KEY_CURRENT_APP = "current_app";
    public static final String KEY_BASELINE = "baseline_bytes";
    public static final String KEY_SKIPPED = "skipped";
    private static final long MIN_CLEAR_BYTES = 64L * 1024L; // evitar tareas vacías (<64 KB)
    private static final long STEP_TIMEOUT_MS = 20_000L;
    private static final long SERVICE_CONNECT_GRACE_MS = 60_000L;
    private static final int MAX_CACHE_VERIFY_ATTEMPTS = 4;
    private static final long CACHE_VERIFY_RETRY_MS = 500L;
    private static final String SETTINGS_PREFS = "settings";
    private static volatile CacheCleanerAccessibilityService activeInstance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<CacheAppInfo> targets = new ArrayList<>();
    private String loadedSnapshotId;
    private Runnable stepTimeout;
    private Runnable cacheVerificationTask;
    private PowerManager.WakeLock screenWakeLock;

    public static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName expected = new ComponentName(context, CacheCleanerAccessibilityService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            ComponentName component = ComponentName.unflattenFromString(splitter.next());
            if (expected.equals(component)) return true;
        }
        return false;
    }

    public static void begin(Context context, String snapshotId) throws Exception {
        List<CacheAppInfo> targets = CacheCleaner.loadCleanupSnapshot(context, snapshotId);
        if (targets.isEmpty()) throw new IllegalStateException("No hay apps seguras en la medición guardada.");
        long total = 0L;
        for (CacheAppInfo app : targets) total += app.cacheBytes;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, true).putBoolean(KEY_RESULT_PENDING, false)
                .putString(KEY_SNAPSHOT_ID, snapshotId).putInt(KEY_INDEX, 0)
                .putInt(KEY_DONE, 0).putInt(KEY_FAILED, 0).putInt(KEY_SKIPPED, 0)
                .putLong(KEY_FREED, 0L).putLong(KEY_BASELINE, -1L)
                .putLong(KEY_TARGET, total).putString(KEY_ERROR, "")
                .putInt("total", targets.size()).putString("phase", "APP_INFO")
                .putString(KEY_CURRENT_APP, targets.get(0).label)
                .putLong("started_at", android.os.SystemClock.elapsedRealtime())
                .apply();
        try {
            CacheCleanerAccessibilityService service = activeInstance;
            if (service != null) service.handler.post(service::armStepTimeout);
            openAppInfo(context, targets.get(0).packageName);
        } catch (RuntimeException error) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_RUNNING, false).putBoolean(KEY_RESULT_PENDING, true)
                    .putString(KEY_ERROR, "Android no pudo abrir Ajustes para iniciar Turbo Tasker.")
                    .putString("phase", "Tarea detenida").apply();
            CacheCleaner.removeCleanupSnapshot(context, snapshotId);
            throw error;
        }
    }

    public static void openAccessibilitySettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static void openAppInfo(Context context, String packageName) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        activeInstance = this;
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 100;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            setServiceInfo(info);
        }
        stopOrphanedRun();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_RUNNING, false)) {
            releaseScreenWakeLock();
            return;
        }
        acquireScreenWakeLockIfRequested();
        CharSequence sourcePackage = event == null ? null : event.getPackageName();
        if (sourcePackage == null || !sourcePackage.toString().toLowerCase(Locale.ROOT).contains("settings")) return;
        if (!loadTargets(prefs)) {
            finishRun("No se pudo recuperar la lista guardada.");
            return;
        }
        int index = prefs.getInt(KEY_INDEX, 0);
        if (index < 0 || index >= targets.size()) {
            finishRun("");
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            String phase = prefs.getString("phase", "APP_INFO");
            if (phase.equals("CACHE_CONFIRM_WAIT")) {
                // Confirmación explícita de "borrar caché" (diálogo).
                if (hasCacheClearConfirmation(root)) {
                    AccessibilityNodeInfo positive = findClickableAction(root, new String[]{
                            "clear cache", "borrar cache", "vaciar cache", "limpiar cache",
                            "clear", "ok", "aceptar", "delete", "borrar", "limpiar",
                            "eliminar", "eliminar cache", "vaciar"
                    });
                    if (positive == null) {
                        finishRun("Android mostró una confirmación de caché no reconocida. La tarea se pausó sin aceptarla.");
                        return;
                    }
                    clearCacheVerificationTask();
                    boolean accepted;
                    try { accepted = clickNode(positive); }
                    finally { positive.recycle(); }
                    if (!accepted) {
                        finishRun("No se pudo aceptar la confirmación explícita de Borrar caché.");
                        return;
                    }
                    prefs.edit().putString("phase", "VERIFYING").putInt("verify_attempts", 0).apply();
                    scheduleCacheVerification(targets.get(index), 300L);
                    return;
                }
                // Sin diálogo: muchos dispositivos limpian al instante. Verificación rápida en cada evento.
                if (trySoftVerify(targets.get(index))) return;
                // Si hay botones de cancelar pero no reconocimos diálogo de caché, no abortar de inmediato;
                // seguir esperando (puede ser otra UI transitoria) hasta timeout/verificación.
            }
            if (phase.equals("VERIFYING")) {
                if (trySoftVerify(targets.get(index))) return;
            }
            AccessibilityNodeInfo clear = findAction(root, new String[]{
                    "clear cache", "borrar caché", "borrar cache", "vaciar caché", "vaciar cache",
                    "limpiar caché", "limpiar cache", "eliminar caché", "eliminar cache",
                    "borrar memoria caché", "borrar memoria cache", "limpar cache"
            });
            if (clear != null && phase.equals("STORAGE")) {
                try {
                    long baseline = resolveBaseline(targets.get(index));
                    if (baseline < MIN_CLEAR_BYTES) {
                        skipCurrent("Sin caché suficiente para borrar.");
                        return;
                    }
                    if (!clickNode(clear)) {
                        failCurrent("El botón Borrar caché no estaba disponible.");
                        return;
                    }
                    clearStepTimeout();
                    prefs.edit().putString("phase", "CACHE_CONFIRM_WAIT").putInt("verify_attempts", 0)
                            .putLong(KEY_BASELINE, baseline).apply();
                    scheduleCacheVerification(targets.get(index), 600L);
                    return;
                } finally { clear.recycle(); }
            }
            if (phase.equals("STORAGE")) {
                // Botón aún no visible o pantalla cargando: intentar desplazar y esperar.
                scrollForward(root);
                return;
            }
            if (phase.equals("APP_INFO")) {
                AccessibilityNodeInfo storage = findAction(root, new String[]{
                        "storage & cache", "storage and cache", "storage cache", "storage",
                        "almacenamiento y caché", "almacenamiento y cache", "almacenamiento y memoria",
                        "almacenamiento", "almacenament", "memoria", "espacio de almacenamiento"
                });
                if (storage != null) {
                    try {
                        if (clickNode(storage)) {
                            prefs.edit().putString("phase", "STORAGE").putLong(KEY_BASELINE, -1L).apply();
                            armStepTimeout();
                        }
                    } finally { storage.recycle(); }
                    return;
                }
                // En algunas versiones el botón "Borrar caché" aparece directo en info de app.
                AccessibilityNodeInfo directClear = findAction(root, new String[]{
                        "clear cache", "borrar caché", "borrar cache", "vaciar caché", "vaciar cache",
                        "limpiar caché", "limpiar cache", "eliminar caché", "eliminar cache"
                });
                if (directClear != null) {
                    try {
                        long baseline = resolveBaseline(targets.get(index));
                        if (baseline < MIN_CLEAR_BYTES) {
                            skipCurrent("Sin caché suficiente para borrar.");
                            return;
                        }
                        if (clickNode(directClear)) {
                            clearStepTimeout();
                            prefs.edit().putString("phase", "CACHE_CONFIRM_WAIT").putInt("verify_attempts", 0)
                                    .putLong(KEY_BASELINE, baseline).apply();
                            scheduleCacheVerification(targets.get(index), 600L);
                        }
                    } finally { directClear.recycle(); }
                    return;
                }
                scrollForward(root);
            }
        } finally {
            root.recycle();
        }
    }

    private void stopOrphanedRun() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_RUNNING, false)) return;
        long startedAt = prefs.getLong("started_at", 0L);
        long age = android.os.SystemClock.elapsedRealtime() - startedAt;
        if (startedAt > 0L && age >= 0L && age <= SERVICE_CONNECT_GRACE_MS && loadTargets(prefs)) {
            int index = prefs.getInt(KEY_INDEX, 0);
            if (index >= 0 && index < targets.size()) {
                // Accessibility can finish connecting just after the user starts the task.
                prefs.edit().putString("phase", "APP_INFO")
                        .putString(KEY_CURRENT_APP, targets.get(index).label).apply();
                armStepTimeout();
                return;
            }
        }
        // Never replay a stale run after a later service reconnection.
        finishRun("La tarea anterior se detuvo y no se reanudó automáticamente. Iníciala de nuevo desde Cache Cleaner.");
    }

    private boolean loadTargets(android.content.SharedPreferences prefs) {
        String snapshotId = prefs.getString(KEY_SNAPSHOT_ID, null);
        if (snapshotId == null) return false;
        if (snapshotId.equals(loadedSnapshotId) && !targets.isEmpty()) return true;
        try {
            targets = CacheCleaner.loadCleanupSnapshot(this, snapshotId);
            loadedSnapshotId = snapshotId;
            return !targets.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private void armStepTimeout() {
        clearStepTimeout();
        stepTimeout = () -> failCurrent("No se encontró el control de limpieza en Ajustes.");
        handler.postDelayed(stepTimeout, STEP_TIMEOUT_MS);
    }

    private void clearStepTimeout() {
        if (stepTimeout != null) {
            handler.removeCallbacks(stepTimeout);
            stepTimeout = null;
        }
        clearCacheVerificationTask();
    }

    private void scheduleCacheVerification(CacheAppInfo app, long delayMs) {
        clearCacheVerificationTask();
        cacheVerificationTask = () -> {
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_RUNNING, false)) return;
            prefs.edit().putString("phase", "VERIFYING").apply();
            verifyAndAdvance(app);
        };
        handler.postDelayed(cacheVerificationTask, delayMs);
    }

    private void clearCacheVerificationTask() {
        if (cacheVerificationTask != null) {
            handler.removeCallbacks(cacheVerificationTask);
            cacheVerificationTask = null;
        }
    }

    private boolean hasCacheClearConfirmation(AccessibilityNodeInfo root) {
        return hasCachePromptText(root)
                && hasClickableAction(root, new String[]{"cancel", "cancelar", "not now", "ahora no", "no"});
    }

    private boolean hasCachePromptText(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence label = node.getText();
        if (label != null && !node.isClickable()) {
            String text = normalize(label.toString());
            boolean namesCache = text.contains("cache")
                    && (text.contains("clear") || text.contains("borrar")
                    || text.contains("limpiar") || text.contains("vaciar") || text.contains("delete"));
            boolean mentionsData = text.contains("data") || text.contains("datos");
            if (namesCache && !mentionsData) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            boolean found = hasCachePromptText(child);
            child.recycle();
            if (found) return true;
        }
        return false;
    }

    private boolean hasClickableAction(AccessibilityNodeInfo root, String[] labels) {
        AccessibilityNodeInfo node = findClickableAction(root, labels);
        if (node == null) return false;
        node.recycle();
        return true;
    }

    private AccessibilityNodeInfo findClickableAction(AccessibilityNodeInfo root, String[] labels) {
        if (root == null) return null;
        CharSequence text = root.getText();
        CharSequence description = root.getContentDescription();
        if (root.isClickable() && (matchesExact(text, labels) || matchesExact(description, labels)))
            return AccessibilityNodeInfo.obtain(root);
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo match = findClickableAction(child, labels);
            child.recycle();
            if (match != null) return match;
        }
        return null;
    }

    private boolean matchesExact(CharSequence value, String[] labels) {
        if (value == null) return false;
        String normalized = normalize(value.toString());
        for (String label : labels) if (normalized.equals(normalize(label))) return true;
        return false;
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Verificación rápida oportunista: si la caché bajó respecto al baseline real, avanza. */
    private boolean trySoftVerify(CacheAppInfo app) {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_RUNNING, false) || app == null) return false;
        long baseline = prefs.getLong(KEY_BASELINE, app == null ? 0L : app.cacheBytes);
        if (baseline <= 0L) baseline = app.cacheBytes;
        long remaining;
        try {
            remaining = CacheCleaner.queryCacheBytes(this, app.packageName);
        } catch (Exception e) {
            return false;
        }
        long freed = Math.max(0L, baseline - remaining);
        // Confirmado si liberó bytes o si quedó por debajo del umbral mínimo.
        if (freed <= 0L && remaining >= MIN_CLEAR_BYTES) return false;
        clearCacheVerificationTask();
        clearStepTimeout();
        int index = prefs.getInt(KEY_INDEX, 0) + 1;
        prefs.edit().putInt(KEY_INDEX, index).putInt(KEY_DONE, prefs.getInt(KEY_DONE, 0) + 1)
                .putLong(KEY_FREED, prefs.getLong(KEY_FREED, 0L) + Math.max(0L, freed))
                .putString("phase", "APP_INFO").putLong(KEY_BASELINE, -1L).apply();
        advanceOrFinish();
        return true;
    }

    private void verifyAndAdvance(CacheAppInfo app) {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_RUNNING, false)) return;
        int attempts = prefs.getInt("verify_attempts", 0) + 1;
        long baseline = prefs.getLong(KEY_BASELINE, app.cacheBytes);
        if (baseline <= 0L) baseline = app.cacheBytes;
        long remaining;
        try {
            remaining = CacheCleaner.queryCacheBytes(this, app.packageName);
        } catch (Exception error) {
            retryOrFailVerification(app, attempts, "Android no permitió verificar el resultado de caché.");
            return;
        }
        long freed = Math.max(0L, baseline - remaining);
        boolean clearedEnough = freed > 0L || remaining < MIN_CLEAR_BYTES;
        if (!clearedEnough) {
            retryOrFailVerification(app, attempts, "Ajustes no confirmó espacio liberado para esta app.");
            return;
        }
        int index = prefs.getInt(KEY_INDEX, 0) + 1;
        prefs.edit().putInt(KEY_INDEX, index).putInt(KEY_DONE, prefs.getInt(KEY_DONE, 0) + 1)
                .putLong(KEY_FREED, prefs.getLong(KEY_FREED, 0L) + Math.max(0L, freed))
                .putString("phase", "APP_INFO").putLong(KEY_BASELINE, -1L).apply();
        advanceOrFinish();
    }

    private void retryOrFailVerification(CacheAppInfo app, int attempts, String reason) {
        if (attempts < MAX_CACHE_VERIFY_ATTEMPTS) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt("verify_attempts", attempts).putString("phase", "VERIFYING").apply();
            scheduleCacheVerification(app, CACHE_VERIFY_RETRY_MS);
            return;
        }
        failCurrent(reason + " (" + attempts + " intentos).");
    }

    private void skipCurrent(String reason) {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_RUNNING, false)) return;
        clearCacheVerificationTask();
        clearStepTimeout();
        int index = prefs.getInt(KEY_INDEX, 0) + 1;
        prefs.edit().putInt(KEY_INDEX, index).putInt(KEY_SKIPPED, prefs.getInt(KEY_SKIPPED, 0) + 1)
                .putString("phase", "APP_INFO").putLong(KEY_BASELINE, -1L).apply();
        advanceOrFinish();
    }

    private void failCurrent(String reason) {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_RUNNING, false)) return;
        clearCacheVerificationTask();
        clearStepTimeout();
        int index = prefs.getInt(KEY_INDEX, 0) + 1;
        String old = prefs.getString(KEY_ERROR, "");
        String label = index > 0 && index <= targets.size() ? targets.get(index - 1).label : "App";
        String failure = label + ": " + reason;
        if (old.length() < 800) old = old.isEmpty() ? failure : old + "\n" + failure;
        prefs.edit().putInt(KEY_INDEX, index).putInt(KEY_FAILED, prefs.getInt(KEY_FAILED, 0) + 1)
                .putString(KEY_ERROR, old).putString("phase", "APP_INFO").putLong(KEY_BASELINE, -1L).apply();
        advanceOrFinish();
    }

    private long resolveBaseline(CacheAppInfo app) {
        if (app == null) return 0L;
        try {
            long now = CacheCleaner.queryCacheBytes(this, app.packageName);
            if (now >= 0L) return now;
        } catch (Exception ignored) { }
        return app.cacheBytes;
    }

    private void advanceOrFinish() {
        clearStepTimeout();
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int index = prefs.getInt(KEY_INDEX, 0);
        if (index >= targets.size()) {
            finishRun("");
            return;
        }
        handler.postDelayed(this::launchCurrentPackage, 350L);
    }

    private void launchCurrentPackage() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_RUNNING, false) || !loadTargets(prefs)) return;
        int index = prefs.getInt(KEY_INDEX, 0);
        if (index < 0 || index >= targets.size()) {
            finishRun("");
            return;
        }
        prefs.edit().putString("phase", "APP_INFO").apply();
        prefs.edit().putString(KEY_CURRENT_APP, targets.get(index).label).apply();
        armStepTimeout();
        try { openAppInfo(this, targets.get(index).packageName); }
        catch (RuntimeException e) { failCurrent("Android no pudo abrir la ficha de la app."); }
    }

    private void finishRun(String reason) {
        clearStepTimeout();
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String existing = prefs.getString(KEY_ERROR, "");
        if (reason != null && !reason.isEmpty()) existing = existing.isEmpty() ? reason : existing + "\n" + reason;
        int done = prefs.getInt(KEY_DONE, 0);
        int failed = prefs.getInt(KEY_FAILED, 0);
        int skipped = prefs.getInt(KEY_SKIPPED, 0);
        long freed = prefs.getLong(KEY_FREED, 0L);
        String snapshotId = prefs.getString(KEY_SNAPSHOT_ID, null);
        prefs.edit().putBoolean(KEY_RUNNING, false).putBoolean(KEY_RESULT_PENDING, true)
                .putString(KEY_ERROR, existing).putString("phase",
                        reason == null || reason.isEmpty() ? "Limpieza terminada" : "Tarea detenida").apply();
        CacheCleaner.removeCleanupSnapshot(this, snapshotId);
        releaseScreenWakeLock();
        Toast.makeText(this, "Turbo Tasker · Accesibilidad · " + done + " limpiadas · "
                + skipped + " sin caché · " + failed + " fallidas · " + CacheAppInfoBytes.format(freed) + " verificados",
                Toast.LENGTH_LONG).show();
    }

    /** A foreground return to CodeHub is an explicit stop, never a trigger to replay Settings taps. */
    public static void stopWhenUserReturns(Context context) {
        stopRun(context, "La tarea se pausó al volver a CodeHub.");
    }

    public static void cancelByUser(Context context) {
        stopRun(context, "La tarea se canceló desde CodeSu.");
    }

    private static void stopRun(Context context, String reason) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_RUNNING, false)) return;
        CacheCleanerAccessibilityService service = activeInstance;
        if (service != null) {
            Runnable stop = () -> service.finishRun(reason);
            if (Looper.myLooper() == Looper.getMainLooper()) stop.run();
            else service.handler.post(stop);
        } else {
            prefs.edit().putBoolean(KEY_RUNNING, false).putBoolean(KEY_RESULT_PENDING, true)
                    .putString(KEY_ERROR, reason)
                    .putString("phase", "Tarea detenida").apply();
            try { CacheCleaner.removeCleanupSnapshot(context, prefs.getString(KEY_SNAPSHOT_ID, null)); }
            catch (RuntimeException ignored) { }
        }
    }

    private AccessibilityNodeInfo findAction(AccessibilityNodeInfo root, String[] labels) {
        if (root == null) return null;
        CharSequence text = root.getText();
        CharSequence description = root.getContentDescription();
        if (matches(text, labels) || matches(description, labels)) return AccessibilityNodeInfo.obtain(root);
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo match;
            try { match = findAction(child, labels); }
            finally { child.recycle(); }
            if (match != null) return match;
        }
        return null;
    }

    private boolean matches(CharSequence value, String[] labels) {
        if (value == null) return false;
        String text = normalize(value.toString());
        if (text.isEmpty()) return false;
        for (String label : labels) {
            String needle = normalize(label);
            if (needle.isEmpty()) continue;
            if (text.equals(needle)) return true;
            // Coincidencia flexible: contiene la etiqueta completa (para títulos largos/OEM).
            if (text.contains(needle) || needle.contains(text)) {
                // Evita falsos positivos muy cortos.
                if (Math.min(text.length(), needle.length()) >= 4) return true;
            }
        }
        return false;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 5; depth++) {
            if (current.isEnabled() && current.isClickable()) {
                boolean clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (current != node) current.recycle();
                return clicked;
            }
            AccessibilityNodeInfo parent = current.getParent();
            if (current != node) current.recycle();
            current = parent;
        }
        if (current != null && current != node) current.recycle();
        return false;
    }

    private void scrollForward(AccessibilityNodeInfo node) {
        if (node == null) return;
        if (node.isScrollable()) {
            try { node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); } catch (Exception ignored) {}
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            boolean scrollable = child.isScrollable();
            if (scrollable) {
                try { child.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); } catch (Exception ignored) {}
                child.recycle();
                return;
            }
            scrollForward(child);
            child.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    private void acquireScreenWakeLockIfRequested() {
        if (!getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getBoolean(CacheCleanerWorker.KEEP_SCREEN_ON, true)
                || (screenWakeLock != null && screenWakeLock.isHeld())) return;
        PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
        if (manager == null) return;
        screenWakeLock = manager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "CodeSu:AccessibilityCacheTurbo");
        screenWakeLock.setReferenceCounted(false);
        screenWakeLock.acquire();
    }

    private void releaseScreenWakeLock() {
        if (screenWakeLock != null && screenWakeLock.isHeld()) screenWakeLock.release();
        screenWakeLock = null;
    }

    @Override public void onInterrupt() {
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_RUNNING, false))
            finishRun("El servicio de Accesibilidad fue interrumpido.");
    }

    @Override public void onDestroy() {
        clearStepTimeout();
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_RUNNING, false))
            finishRun("El servicio de Accesibilidad se detuvo durante la limpieza.");
        releaseScreenWakeLock();
        if (activeInstance == this) activeInstance = null;
        super.onDestroy();
    }

    private static final class CacheAppInfoBytes {
        static String format(long bytes) {
            if (bytes < 1024L) return bytes + " B";
            if (bytes < 1024L * 1024L) return String.format(Locale.getDefault(), "%.0f KB", bytes / 1024f);
            return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f));
        }
    }
}
