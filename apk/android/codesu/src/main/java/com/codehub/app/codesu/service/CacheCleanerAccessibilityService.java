package com.codehub.app.codesu.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.codehub.app.codesu.model.CacheAppInfo;
import com.codehub.app.codesu.util.CacheCleaner;
import com.codehub.app.codesu.worker.CacheCleanerWorker;

/**
 * User-started Settings UI automation fallback for devices without Shizuku.
 *
 * <p>Motor basado en sondeo continuo de la ventana de Ajustes (estilo 1Tap Cleaner):
 * no depende de que lleguen eventos de contenido, reintenta la navegación, usa gestos
 * como respaldo cuando un botón no responde a ACTION_CLICK y no exige verificación por
 * StorageStats (que sin permiso de uso devuelve error y dejaba la tarea bloqueada).</p>
 */
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

    // Sondeo: la pantalla de Almacenamiento puede tardar y no siempre emite eventos.
    private static final long POLL_INTERVAL_MS = 450L;
    private static final long NUDGE_MIN_GAP_MS = 200L;
    private static final long STEP_TIMEOUT_MS = 45_000L;
    private static final long STORAGE_TIMEOUT_MS = 35_000L;
    private static final long STORAGE_RETRY_MS = 4_000L;
    private static final long LAUNCH_SETTLE_MS = 650L;
    private static final long INSTANT_CLEAR_GRACE_MS = 1_600L;
    private static final long VERIFY_GRACE_MS = 1_200L;
    private static final long SERVICE_CONNECT_GRACE_MS = 60_000L;
    private static final int MAX_STORAGE_CLICKS = 3;

    private static final String[] CLEAR_LABELS = {
            "clear cache", "borrar cache", "vaciar cache", "limpiar cache",
            "eliminar cache", "borrar memoria cache", "limpiar memoria cache", "limpar cache"
    };
    private static final String[] STORAGE_LABELS = {
            "storage & cache", "storage and cache", "storage cache", "storage",
            "almacenamiento y cache", "almacenamiento y memoria", "uso de almacenamiento",
            "espacio de almacenamiento", "almacenamiento", "memoria interna"
    };
    private static final String[] CONFIRM_LABELS = {
            "borrar cache", "vaciar cache", "limpiar cache", "clear cache", "eliminar cache"
    };
    private static final String[] GENERIC_CONFIRM_LABELS = {
            "borrar", "limpiar", "vaciar", "clear", "delete", "eliminar", "aceptar", "ok"
    };
    private static final String[] CANCEL_LABELS = {
            "cancel", "cancelar", "ahora no", "not now"
    };

    private static final String SETTINGS_PREFS = "settings";
    private static volatile CacheCleanerAccessibilityService activeInstance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<CacheAppInfo> targets = new ArrayList<>();
    private String loadedSnapshotId;
    private PowerManager.WakeLock screenWakeLock;
    private boolean stepInProgress;
    private long lastNudgeAt;

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_RUNNING, false)) {
                releaseScreenWakeLock();
                return;
            }
            acquireScreenWakeLockIfRequested();
            stepSafe();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

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
        List<CacheAppInfo> loaded = CacheCleaner.loadCleanupSnapshot(context, snapshotId);
        if (loaded.isEmpty()) throw new IllegalStateException("No hay apps seguras en la medición guardada.");
        long total = 0L;
        for (CacheAppInfo app : loaded) total += app.cacheBytes;
        long now = SystemClock.elapsedRealtime();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, true).putBoolean(KEY_RESULT_PENDING, false)
                .putString(KEY_SNAPSHOT_ID, snapshotId).putInt(KEY_INDEX, 0)
                .putInt(KEY_DONE, 0).putInt(KEY_FAILED, 0).putInt(KEY_SKIPPED, 0)
                .putLong(KEY_FREED, 0L).putLong(KEY_BASELINE, -1L)
                .putLong(KEY_TARGET, total).putString(KEY_ERROR, "")
                .putInt("total", loaded.size()).putString("phase", "APP_INFO")
                .putString(KEY_CURRENT_APP, loaded.get(0).label)
                .putLong("started_at", now)
                .putLong("step_deadline", now + STEP_TIMEOUT_MS)
                .putLong("launched_at", now)
                .putLong("storage_clicked_at", 0L)
                .putLong("clicked_at", 0L)
                .putInt("storage_clicks", 0)
                .apply();
        CacheCleanerAccessibilityService service = activeInstance;
        if (service != null) service.startPolling();
        try {
            openAppInfo(context, loaded.get(0).packageName);
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
        startPolling();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_RUNNING, false)) {
            releaseScreenWakeLock();
            return;
        }
        acquireScreenWakeLockIfRequested();
        CharSequence sourcePackage = event == null ? null : event.getPackageName();
        if (sourcePackage != null
                && sourcePackage.toString().toLowerCase(Locale.ROOT).contains("settings")) {
            nudge();
        }
    }

    private void nudge() {
        long now = SystemClock.uptimeMillis();
        if (now - lastNudgeAt < NUDGE_MIN_GAP_MS) return;
        lastNudgeAt = now;
        handler.postDelayed(this::stepSafe, 100L);
    }

    private void startPolling() {
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
    }

    private void stopPolling() {
        handler.removeCallbacks(pollRunnable);
    }

    private void stepSafe() {
        if (stepInProgress) return;
        stepInProgress = true;
        AccessibilityNodeInfo root = null;
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (!prefs.getBoolean(KEY_RUNNING, false)) {
                releaseScreenWakeLock();
                return;
            }
            if (!loadTargets(prefs)) {
                finishRun("No se pudo recuperar la lista guardada.");
                return;
            }
            int index = prefs.getInt(KEY_INDEX, 0);
            if (index < 0 || index >= targets.size()) {
                finishRun("");
                return;
            }
            long deadline = prefs.getLong("step_deadline", 0L);
            if (deadline > 0L && SystemClock.elapsedRealtime() > deadline) {
                failCurrent("Ajustes no mostró el control de limpieza a tiempo.");
                return;
            }
            root = findSettingsRoot();
            if (root == null) return;
            handleRoot(prefs, root, index);
        } catch (RuntimeException ignored) {
            // Nunca dejar que una vista reciclada o un OEM raro detenga el sondeo.
        } finally {
            if (root != null) root.recycle();
            stepInProgress = false;
        }
    }

    private void handleRoot(SharedPreferences prefs, AccessibilityNodeInfo root, int index) {
        CacheAppInfo app = targets.get(index);
        long now = SystemClock.elapsedRealtime();
        String phase = prefs.getString("phase", "APP_INFO");
        long clickedAt = prefs.getLong("clicked_at", 0L);

        if ("VERIFYING".equals(phase)) {
            if (clickedAt <= 0L || now - clickedAt >= VERIFY_GRACE_MS) countCleared(prefs, index, app);
            return;
        }

        if ("CACHE_CONFIRM_WAIT".equals(phase)) {
            if (isCacheDialog(root)) {
                AccessibilityNodeInfo confirm = findClickableAction(root, CONFIRM_LABELS);
                if (confirm == null) confirm = findClickableAction(root, GENERIC_CONFIRM_LABELS);
                if (confirm != null) {
                    boolean clicked;
                    try {
                        clicked = clickNode(confirm);
                    } finally {
                        confirm.recycle();
                    }
                    if (clicked) {
                        prefs.edit().putString("phase", "VERIFYING")
                                .putLong("clicked_at", SystemClock.elapsedRealtime()).apply();
                    }
                    return;
                }
            }
            // Sin diálogo: en la mayoría de dispositivos la caché se borra al instante.
            AccessibilityNodeInfo clear = findAction(root, CLEAR_LABELS);
            try {
                boolean goneOrDisabled = clear == null || !clear.isEnabled();
                if (goneOrDisabled || now - clickedAt >= INSTANT_CLEAR_GRACE_MS) {
                    prefs.edit().putString("phase", "VERIFYING").apply();
                }
            } finally {
                if (clear != null) clear.recycle();
            }
            return;
        }

        // Fase APP_INFO o STORAGE: buscar el botón de caché primero.
        AccessibilityNodeInfo clear = findAction(root, CLEAR_LABELS);
        if (clear != null) {
            try {
                if (!clear.isEnabled()) {
                    // Puede estar deshabilitado mientras carga la medición o porque ya no hay caché.
                    long reference = "STORAGE".equals(phase)
                            ? prefs.getLong("storage_clicked_at", 0L)
                            : prefs.getLong("launched_at", 0L);
                    long grace = "STORAGE".equals(phase) ? 2500L : 8000L;
                    if (reference > 0L && now - reference > grace) {
                        skipCurrent("Sin caché suficiente para borrar.");
                    }
                    return;
                }
                long launchedAt = prefs.getLong("launched_at", 0L);
                if ("APP_INFO".equals(phase) && launchedAt > 0L && now - launchedAt < LAUNCH_SETTLE_MS) {
                    return;
                }
                if (app.cacheBytes > 0L && app.cacheBytes < MIN_CLEAR_BYTES) {
                    skipCurrent("Sin caché suficiente para borrar.");
                    return;
                }
                if (clickNode(clear)) {
                    prefs.edit().putString("phase", "CACHE_CONFIRM_WAIT")
                            .putLong("clicked_at", now).putLong(KEY_BASELINE, app.cacheBytes).apply();
                }
                // Presente pero todavía sin responder: el sondeo vuelve a intentarlo.
            } finally {
                clear.recycle();
            }
            return;
        }

        if ("APP_INFO".equals(phase)) {
            AccessibilityNodeInfo storage = findAction(root, STORAGE_LABELS);
            if (storage != null) {
                try {
                    long launchedAt = prefs.getLong("launched_at", 0L);
                    if (launchedAt > 0L && now - launchedAt < LAUNCH_SETTLE_MS) return;
                    if (clickNode(storage)) {
                        prefs.edit().putString("phase", "STORAGE")
                                .putLong("storage_clicked_at", now)
                                .putInt("storage_clicks", prefs.getInt("storage_clicks", 0) + 1)
                                .putLong("step_deadline", now + STORAGE_TIMEOUT_MS).apply();
                    }
                } finally {
                    storage.recycle();
                }
                return;
            }
        }

        if ("STORAGE".equals(phase)) {
            long storageAt = prefs.getLong("storage_clicked_at", 0L);
            if (storageAt > 0L && now - storageAt > STORAGE_RETRY_MS
                    && prefs.getInt("storage_clicks", 0) < MAX_STORAGE_CLICKS) {
                AccessibilityNodeInfo storage = findAction(root, STORAGE_LABELS);
                if (storage != null) {
                    try {
                        if (clickNode(storage)) {
                            prefs.edit().putLong("storage_clicked_at", now)
                                    .putInt("storage_clicks", prefs.getInt("storage_clicks", 0) + 1).apply();
                        }
                    } finally {
                        storage.recycle();
                    }
                    return;
                }
            }
        }

        scrollForward(root);
    }

    private void stopOrphanedRun() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_RUNNING, false)) return;
        long startedAt = prefs.getLong("started_at", 0L);
        long age = SystemClock.elapsedRealtime() - startedAt;
        if (startedAt > 0L && age >= 0L && age <= SERVICE_CONNECT_GRACE_MS && loadTargets(prefs)) {
            int index = prefs.getInt(KEY_INDEX, 0);
            if (index >= 0 && index < targets.size()) {
                // La accesibilidad puede conectarse justo después de iniciar la tarea.
                long now = SystemClock.elapsedRealtime();
                prefs.edit().putString("phase", "APP_INFO")
                        .putString(KEY_CURRENT_APP, targets.get(index).label)
                        .putLong("step_deadline", now + STEP_TIMEOUT_MS)
                        .putLong("launched_at", 0L)
                        .putLong("storage_clicked_at", 0L)
                        .putLong("clicked_at", 0L)
                        .putInt("storage_clicks", 0).apply();
                return;
            }
        }
        // Nunca repetir una tarea vieja tras una reconexión posterior del servicio.
        finishRun("La tarea anterior se detuvo y no se reanudó automáticamente. Iníciala de nuevo desde Cache Cleaner.");
    }

    private boolean loadTargets(SharedPreferences prefs) {
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

    private void countCleared(SharedPreferences prefs, int index, CacheAppInfo app) {
        if (!prefs.getBoolean(KEY_RUNNING, false)) return;
        long freed = 0L;
        try {
            long remaining = CacheCleaner.queryCacheBytes(this, app.packageName);
            if (remaining >= 0L && app.cacheBytes > 0L) {
                freed = Math.max(0L, app.cacheBytes - remaining);
            }
        } catch (Exception ignored) {
            // Sin permiso de uso de datos no se puede medir; la limpieza ya se ejecutó.
        }
        prefs.edit().putInt(KEY_INDEX, index + 1)
                .putInt(KEY_DONE, prefs.getInt(KEY_DONE, 0) + 1)
                .putLong(KEY_FREED, prefs.getLong(KEY_FREED, 0L) + freed)
                .putString("phase", "APP_INFO").putLong(KEY_BASELINE, -1L)
                .putLong("clicked_at", 0L).apply();
        advanceOrFinish();
    }

    private void skipCurrent(String reason) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_RUNNING, false)) return;
        int index = prefs.getInt(KEY_INDEX, 0) + 1;
        prefs.edit().putInt(KEY_INDEX, index)
                .putInt(KEY_SKIPPED, prefs.getInt(KEY_SKIPPED, 0) + 1)
                .putString("phase", "APP_INFO").putLong(KEY_BASELINE, -1L)
                .putLong("clicked_at", 0L).apply();
        advanceOrFinish();
    }

    private void failCurrent(String reason) {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_RUNNING, false)) return;
        int index = prefs.getInt(KEY_INDEX, 0) + 1;
        String old = prefs.getString(KEY_ERROR, "");
        String label = index > 0 && index <= targets.size() ? targets.get(index - 1).label : "App";
        String failure = label + ": " + reason;
        if (old.length() < 800) old = old.isEmpty() ? failure : old + "\n" + failure;
        prefs.edit().putInt(KEY_INDEX, index)
                .putInt(KEY_FAILED, prefs.getInt(KEY_FAILED, 0) + 1)
                .putString(KEY_ERROR, old).putString("phase", "APP_INFO")
                .putLong(KEY_BASELINE, -1L).putLong("clicked_at", 0L).apply();
        advanceOrFinish();
    }

    private void advanceOrFinish() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int index = prefs.getInt(KEY_INDEX, 0);
        if (index >= targets.size()) {
            finishRun("");
            return;
        }
        handler.postDelayed(this::launchCurrentPackage, 400L);
    }

    private void launchCurrentPackage() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_RUNNING, false) || !loadTargets(prefs)) return;
        int index = prefs.getInt(KEY_INDEX, 0);
        if (index < 0 || index >= targets.size()) {
            finishRun("");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        prefs.edit().putString("phase", "APP_INFO")
                .putString(KEY_CURRENT_APP, targets.get(index).label)
                .putLong("launched_at", now)
                .putLong("step_deadline", now + STEP_TIMEOUT_MS)
                .putLong("storage_clicked_at", 0L)
                .putLong("clicked_at", 0L)
                .putInt("storage_clicks", 0).apply();
        try {
            openAppInfo(this, targets.get(index).packageName);
        } catch (RuntimeException e) {
            failCurrent("Android no pudo abrir la ficha de la app.");
        }
    }

    private void finishRun(String reason) {
        stopPolling();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String existing = prefs.getString(KEY_ERROR, "");
        if (reason != null && !reason.isEmpty()) {
            existing = existing.isEmpty() ? reason : existing + "\n" + reason;
        }
        int done = prefs.getInt(KEY_DONE, 0);
        int failed = prefs.getInt(KEY_FAILED, 0);
        int skipped = prefs.getInt(KEY_SKIPPED, 0);
        long freed = prefs.getLong(KEY_FREED, 0L);
        String snapshotId = prefs.getString(KEY_SNAPSHOT_ID, null);
        prefs.edit().putBoolean(KEY_RUNNING, false).putBoolean(KEY_RESULT_PENDING, true)
                .putString(KEY_ERROR, existing).putString("phase",
                        reason == null || reason.isEmpty() ? "Limpieza terminada" : "Tarea detenida")
                .apply();
        CacheCleaner.removeCleanupSnapshot(this, snapshotId);
        releaseScreenWakeLock();
        Toast.makeText(this, "Turbo Tasker · Accesibilidad · " + done + " limpiadas · "
                        + skipped + " sin caché · " + failed + " fallidas · "
                        + CacheAppInfoBytes.format(freed) + " verificados",
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
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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
            try {
                CacheCleaner.removeCleanupSnapshot(context, prefs.getString(KEY_SNAPSHOT_ID, null));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private boolean isCacheDialog(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo cancel = findClickableAction(root, CANCEL_LABELS);
        boolean cancelable = cancel != null;
        if (cancel != null) cancel.recycle();
        if (!cancelable) return false;
        // Solo aceptar diálogos que realmente hablen de caché: nunca "aceptar" avisos ajenos.
        if (hasCachePromptText(root)) return true;
        AccessibilityNodeInfo confirm = findClickableAction(root, CONFIRM_LABELS);
        if (confirm != null) {
            confirm.recycle();
            return true;
        }
        return false;
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

    private AccessibilityNodeInfo findClickableAction(AccessibilityNodeInfo root, String[] labels) {
        if (root == null) return null;
        CharSequence text = root.getText();
        CharSequence description = root.getContentDescription();
        if (root.isClickable() && (matchesExact(text, labels) || matchesExact(description, labels))) {
            return AccessibilityNodeInfo.obtain(root);
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo match = findClickableAction(child, labels);
            child.recycle();
            if (match != null) return match;
        }
        return null;
    }

    /** Acepta coincidencia exacta o etiqueta contenida, sin aceptar textos cortos como "Caché". */
    private boolean matchesExact(CharSequence value, String[] labels) {
        if (value == null) return false;
        String text = normalize(value.toString());
        if (text.isEmpty()) return false;
        for (String label : labels) {
            String needle = normalize(label);
            if (needle.isEmpty()) continue;
            if (text.equals(needle)) return true;
            if (text.startsWith(needle) && text.length() <= needle.length() + 12) return true;
            if (needle.length() >= 8 && text.contains(needle)) return true;
        }
        return false;
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    private AccessibilityNodeInfo findSettingsRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            if (isSettingsPackage(root.getPackageName())) return root;
            root.recycle();
        }
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) return null;
        for (AccessibilityWindowInfo window : windows) {
            if (window == null) continue;
            AccessibilityNodeInfo candidate = window.getRoot();
            if (candidate == null) continue;
            if (isSettingsPackage(candidate.getPackageName())) return candidate;
            candidate.recycle();
        }
        return null;
    }

    private boolean isSettingsPackage(CharSequence packageName) {
        if (packageName == null) return false;
        String value = packageName.toString().toLowerCase(Locale.ROOT);
        return value.contains("settings") || value.contains("securitycenter")
                || value.contains("permissioncontroller");
    }

    private AccessibilityNodeInfo findAction(AccessibilityNodeInfo root, String[] labels) {
        if (root == null) return null;
        CharSequence text = root.getText();
        CharSequence description = root.getContentDescription();
        if (matchesExact(text, labels) || matchesExact(description, labels)) {
            return AccessibilityNodeInfo.obtain(root);
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo match;
            try {
                match = findAction(child, labels);
            } finally {
                child.recycle();
            }
            if (match != null) return match;
        }
        return null;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        try {
            for (int depth = 0; current != null && depth < 6; depth++) {
                if (current.isEnabled() && current.isClickable()
                        && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true;
                }
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
        } finally {
            if (current != null) current.recycle();
        }
        // Respaldo tipo 1Tap Cleaner: toque por coordenadas si el botón no acepta ACTION_CLICK.
        return tapNode(node);
    }

    private boolean tapNode(AccessibilityNodeInfo node) {
        if (node == null) return false;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) return false;
        Path path = new Path();
        path.moveTo(bounds.exactCenterX(), bounds.exactCenterY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 60L))
                .build();
        try {
            return dispatchGesture(gesture, null, null);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private void scrollForward(AccessibilityNodeInfo node) {
        if (node == null) return;
        if (node.isScrollable()) {
            try {
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            } catch (Exception ignored) {
            }
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            boolean scrollable = child.isScrollable();
            if (scrollable) {
                try {
                    child.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                } catch (Exception ignored) {
                }
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

    @Override
    public void onInterrupt() {
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_RUNNING, false)) {
            finishRun("El servicio de Accesibilidad fue interrumpido.");
        }
    }

    @Override
    public void onDestroy() {
        stopPolling();
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_RUNNING, false)) {
            finishRun("El servicio de Accesibilidad se detuvo durante la limpieza.");
        }
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
