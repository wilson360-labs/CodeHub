package com.codehub.app.codesu.ui.cache;

import android.content.Intent;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.Data;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.List;

import com.codehub.app.codesu.App;
import com.codehub.app.codesu.databinding.CodesuCacheCleanerBinding;
import com.codehub.app.codesu.model.CacheAppInfo;
import com.codehub.app.codesu.util.CacheCleaner;
import com.codehub.app.codesu.service.CacheCleanerAccessibilityService;
import com.codehub.app.codesu.worker.CacheCleanerWorker;
import java.util.UUID;

public class CacheCleanerFragment extends Fragment {

    private CodesuCacheCleanerBinding binding;
    private CacheAppAdapter adapter;
    private boolean cleaning;
    private boolean loading;
    private int loadGeneration;
    private static final String CLEANER_PREFS = "codesu_cache_cleaner";
    private static final String LAST_WORK_ID = "last_cleanup_work_id";
    private LiveData<WorkInfo> cleanupWorkLiveData;
    private Observer<WorkInfo> cleanupWorkObserver;
    private final Handler accessibilityProgressHandler = new Handler(Looper.getMainLooper());
    private final Runnable accessibilityProgressPoll = new Runnable() {
        @Override public void run() {
            if (binding == null || !isAdded()) return;
            android.content.SharedPreferences prefs = requireContext().getSharedPreferences(
                    CacheCleanerAccessibilityService.PREFS, Context.MODE_PRIVATE);
            if (prefs.getBoolean(CacheCleanerAccessibilityService.KEY_RUNNING, false)) {
                renderAccessibilityProgress(prefs);
                accessibilityProgressHandler.postDelayed(this, 500L);
            } else if (prefs.getBoolean(CacheCleanerAccessibilityService.KEY_RESULT_PENDING, false)) {
                renderAccessibilityResult(prefs);
            }
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = CodesuCacheCleanerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        adapter = new CacheAppAdapter();
        binding.codesuCacheApps.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.codesuCacheApps.setAdapter(adapter);
        binding.codesuUsageAccessButton.setOnClickListener(v -> openUsageAccessSettings());
        binding.codesuCacheCleanButton.setOnClickListener(v -> confirmGlobalCacheClear());
        android.content.SharedPreferences settings = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
        binding.codesuCacheKeepScreenOn.setChecked(settings.getBoolean(CacheCleanerWorker.KEEP_SCREEN_ON, true));
        binding.codesuCacheKeepScreenOn.setOnCheckedChangeListener((button, checked) ->
                settings.edit().putBoolean(CacheCleanerWorker.KEEP_SCREEN_ON, checked).apply());
        binding.codesuCacheRefresh.setOnClickListener(v -> {
            binding.codesuCacheRefreshIcon.animate().rotationBy(360f).setDuration(650)
                    .setInterpolator(new AccelerateDecelerateInterpolator()).start();
            loadCacheApps();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        observeCleanupWork();
        android.content.SharedPreferences accessibility = requireContext().getSharedPreferences(
                CacheCleanerAccessibilityService.PREFS, Context.MODE_PRIVATE);
        if (accessibility.getBoolean(CacheCleanerAccessibilityService.KEY_RUNNING, false)) {
            renderAccessibilityProgress(accessibility);
            accessibilityProgressHandler.removeCallbacks(accessibilityProgressPoll);
            accessibilityProgressHandler.post(accessibilityProgressPoll);
        } else if (accessibility.getBoolean(CacheCleanerAccessibilityService.KEY_RESULT_PENDING, false)) {
            renderAccessibilityResult(accessibility);
        } else {
            loadCacheApps();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        accessibilityProgressHandler.removeCallbacks(accessibilityProgressPoll);
        loadGeneration++;
        loading = false;
        cleaning = false;
        if (cleanupWorkLiveData != null && cleanupWorkObserver != null)
            cleanupWorkLiveData.removeObserver(cleanupWorkObserver);
        cleanupWorkLiveData = null;
        cleanupWorkObserver = null;
        binding = null;
        adapter = null;
    }

    private void loadCacheApps() {
        if (binding == null || cleaning || loading) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showAccessGate("La medición de caché requiere Android 8.0 o posterior.", false);
            return;
        }
        if (!CacheCleaner.hasUsageAccess(requireContext())) {
            showAccessGate("Activa el acceso al uso para consultar el tamaño de caché de cada app.", true);
            return;
        }

        binding.codesuUsageAccessButton.setVisibility(View.GONE);
        loading = true;
        int requestGeneration = ++loadGeneration;
        binding.codesuCacheRefresh.setEnabled(false);
        binding.codesuCacheRefresh.setAlpha(0.55f);
        binding.codesuCacheApps.setVisibility(View.VISIBLE);
        binding.codesuCacheEmpty.setVisibility(View.GONE);
        binding.codesuCacheCleanButton.setVisibility(View.GONE);
        binding.codesuCacheProgress.setVisibility(View.VISIBLE);
        binding.codesuCacheProgress.setIndeterminate(false);
        binding.codesuCacheProgress.setProgress(0);
        binding.codesuCacheProgressText.setVisibility(View.VISIBLE);
        binding.codesuCacheProgressText.setText("Midiendo aplicaciones · 0%");
        binding.codesuCacheTotal.setText("Leyendo aplicaciones instaladas");

        Context appContext = requireContext().getApplicationContext();
        App.executorService.execute(() -> {
            try {
                List<CacheAppInfo> apps = CacheCleaner.loadInstalledApps(appContext,
                        (completed, total, packageName) -> App.handler.post(() -> {
                            if (binding == null || requestGeneration != loadGeneration) return;
                            int percent = total == 0 ? 100 : (int) (completed * 100L / total);
                            binding.codesuCacheProgress.setProgress(percent);
                            binding.codesuCacheProgressText.setText("Midiendo caché · " + percent + "% · "
                                    + completed + "/" + total + " apps");
                        }));
                App.handler.post(() -> {
                    if (binding == null || requestGeneration != loadGeneration) return;
                    loading = false;
                    binding.codesuCacheRefresh.setEnabled(true);
                    binding.codesuCacheRefresh.setAlpha(1f);
                    adapter.submit(apps);
                    long totalBytes = 0L;
                    int appsWithCache = 0;
                    for (CacheAppInfo app : apps) {
                        if (!app.protectedApp && !CacheCleaner.isProtected(app.packageName)
                                && app.cacheBytes > CacheCleaner.DEEP_CLEAN_MIN_CACHE_BYTES) {
                            totalBytes += app.cacheBytes;
                            appsWithCache++;
                        }
                    }
                    binding.codesuCacheTotal.setText("Limpieza profunda disponible · "
                            + CacheAppAdapter.formatBytes(totalBytes) + " en " + appsWithCache + " apps (>1 MB)");
                    android.content.SharedPreferences stats = requireContext().getSharedPreferences(CLEANER_PREFS, Context.MODE_PRIVATE);
                    if (stats.contains("last_success_rate")) {
                        int rate = stats.getInt("last_success_rate", 0);
                        int spaceRate = stats.getInt("last_space_rate", 0);
                        binding.codesuCacheSuccessRate.setText("Última limpieza · " + rate + "% apps OK · " + spaceRate + "% del objetivo liberado · "
                                + CacheAppAdapter.formatBytes(stats.getLong("last_freed_bytes", 0L)) + " verificados");
                        binding.codesuCacheSuccessRate.setTextColor(rate >= 80 ? 0xFF41C99A : 0xFFE7838B);
                    } else {
                        binding.codesuCacheSuccessRate.setText("Tasa de éxito: disponible después de la primera limpieza");
                    }
                    binding.codesuCacheProgress.setVisibility(View.GONE);
                    binding.codesuCacheProgressText.setVisibility(View.GONE);
                    binding.codesuCacheEmpty.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
                    binding.codesuCacheEmpty.setText(apps.isEmpty()
                            ? "No hay aplicaciones instaladas disponibles para medir su caché." : "");
                    binding.codesuCacheCleanButton.setVisibility(appsWithCache > 0 ? View.VISIBLE : View.GONE);
                    binding.codesuCacheCleanButton.setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
                    binding.codesuCacheSafetyNote.setText(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            ? "Turbo Shizuku · limpieza secuencial estable. Recomendable: caché >1 MB; no recomendable: servicios protegidos. No desinstala apps ni borra datos o sesiones."
                            : "La limpieza selectiva con exclusión de apps protegidas requiere Android 11+. Puedes consultar los tamaños.");
                });
            } catch (SecurityException e) {
                App.handler.post(() -> {
                    if (binding != null && requestGeneration == loadGeneration) {
                        loading = false;
                        showAccessGate("El acceso al uso está desactivado. Actívalo y vuelve para actualizar la lista.", true);
                    }
                });
            } catch (Exception e) {
                App.handler.post(() -> {
                    if (binding == null || requestGeneration != loadGeneration) return;
                    loading = false;
                    binding.codesuCacheRefresh.setEnabled(true);
                    binding.codesuCacheRefresh.setAlpha(1f);
                    binding.codesuCacheProgress.setVisibility(View.GONE);
                    binding.codesuCacheProgressText.setVisibility(View.GONE);
                    binding.codesuCacheTotal.setText("No se pudieron leer los tamaños de caché");
                    binding.codesuCacheEmpty.setVisibility(View.VISIBLE);
                    binding.codesuCacheEmpty.setText(e.getMessage() == null
                            ? "Las estadísticas de almacenamiento no están disponibles en este dispositivo." : e.getMessage());
                });
            }
        });
    }

    private void showAccessGate(String message, boolean showUsageSettings) {
        if (binding == null) return;
        loading = false;
        binding.codesuCacheRefresh.setEnabled(true);
        binding.codesuCacheRefresh.setAlpha(1f);
        binding.codesuCacheApps.setVisibility(View.GONE);
        binding.codesuCacheCleanButton.setVisibility(View.GONE);
        binding.codesuCacheTotal.setText("Se necesita acceso para medir la caché");
        binding.codesuCacheEmpty.setText(message);
        binding.codesuCacheEmpty.setVisibility(View.VISIBLE);
        binding.codesuUsageAccessButton.setVisibility(showUsageSettings ? View.VISIBLE : View.GONE);
        binding.codesuCacheProgress.setVisibility(View.GONE);
        binding.codesuCacheProgressText.setVisibility(View.GONE);
    }

    private void openUsageAccessSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            intent.putExtra(Intent.EXTRA_PACKAGE_NAME, requireContext().getPackageName());
            startActivity(intent);
        } catch (RuntimeException e) {
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }
    }

    private void confirmGlobalCacheClear() {
        if (binding == null || cleaning || loading) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Toast.makeText(requireContext(), "La limpieza selectiva protegida requiere Android 11 o posterior.", Toast.LENGTH_LONG).show();
            return;
        }
        List<CacheAppInfo> apps = adapter.getApps();
        List<CacheAppInfo> targets = new java.util.ArrayList<>();
        long estimate = 0L;
        for (CacheAppInfo app : apps) {
            if (app.cacheBytes > CacheCleaner.DEEP_CLEAN_MIN_CACHE_BYTES && !app.protectedApp
                    && !CacheCleaner.isProtected(app.packageName)) {
                targets.add(app);
                estimate += app.cacheBytes;
            }
        }
        if (targets.isEmpty()) {
            Toast.makeText(requireContext(), "Ninguna app no protegida supera 1 MB de caché.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!CacheCleaner.isShizukuReady()) {
            showAccessibilityCleaner(targets, estimate);
            return;
        }
        final List<CacheAppInfo> cleanupTargets = new java.util.ArrayList<>(targets);
        final long estimatedBytes = estimate;
        new AlertDialog.Builder(requireContext())
                .setTitle("Elige el modo Turbo")
                .setMessage("Se usará el tamaño ya medido en esta lista; no volveremos a escanear antes de limpiar. Apps seguras >1 MB: "
                        + cleanupTargets.size() + " · " + CacheAppAdapter.formatBytes(estimatedBytes) + ".")
                .setItems(new String[]{"Turbo Shizuku · ejecución en paralelo", "Turbo Accesibilidad · automatizar Ajustes"},
                        (dialog, which) -> {
                            if (which == 0) confirmShizukuCleaner(cleanupTargets, estimatedBytes);
                            else showAccessibilityCleaner(cleanupTargets, estimatedBytes);
                        })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmShizukuCleaner(List<CacheAppInfo> targets, long estimate) {
        String message = "Turbo Shizuku limpiará las " + targets.size() + " apps seguras sobre 1 MB usando la medición visible ("
                + CacheAppAdapter.formatBytes(estimate) + "). No se volverá a medir antes del borrado. Los datos y sesiones se conservan.";
        new AlertDialog.Builder(requireContext()).setTitle("Turbo Shizuku")
                .setMessage(message).setNegativeButton("Cancelar", null)
                .setPositiveButton("Limpiar ahora", (dialog, which) -> runGlobalCacheClear(targets))
                .show();
    }

    private void showAccessibilityCleaner(List<CacheAppInfo> targets, long estimate) {
        if (!CacheCleanerAccessibilityService.isEnabled(requireContext())) {
            new AlertDialog.Builder(requireContext()).setTitle("Activar Turbo Accesibilidad")
                    .setMessage("CodeSu necesita tu autorización explícita para abrir la ficha de cada app y tocar solo “Almacenamiento” y “Borrar caché”. No tocará “Borrar datos”. Android mantendrá la pantalla encendida durante el proceso si el interruptor está activo.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Abrir Accesibilidad", (dialog, which) -> {
                        try { CacheCleanerAccessibilityService.openAccessibilitySettings(requireContext()); }
                        catch (RuntimeException e) { Toast.makeText(requireContext(), "No se pudieron abrir los ajustes de Accesibilidad.", Toast.LENGTH_LONG).show(); }
                    }).show();
            return;
        }
        new AlertDialog.Builder(requireContext()).setTitle("Turbo Accesibilidad")
                .setMessage("Abrirá Ajustes de Android para las " + targets.size() + " apps seguras sobre 1 MB ("
                        + CacheAppAdapter.formatBytes(estimate) + "). Se automatizan los botones de almacenamiento y caché; Android debe permanecer desbloqueado. No se miden de nuevo los tamaños antes de empezar.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Iniciar limpieza", (dialog, which) -> startAccessibilityCleaner(targets))
                .show();
    }

    private void runGlobalCacheClear(List<CacheAppInfo> targets) {
        if (binding == null || cleaning) return;
        cleaning = true;
        setCleanupUi("Turbo Shizuku · usando medición visible", 0);
        binding.codesuCacheCleanButton.animate().rotationBy(360f).setDuration(450)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
        String snapshotId = null;
        try {
            snapshotId = CacheCleaner.saveCleanupSnapshot(requireContext(), targets);
            Data input = new Data.Builder().putString(CacheCleanerWorker.KEY_SNAPSHOT_ID, snapshotId).build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CacheCleanerWorker.class)
                    .setInputData(input).addTag(CacheCleanerWorker.TAG).build();
            requireContext().getSharedPreferences(CLEANER_PREFS, Context.MODE_PRIVATE).edit()
                    .putString(LAST_WORK_ID, request.getId().toString()).apply();
            WorkManager.getInstance(requireContext()).enqueue(request);
            observeCleanupWork();
        } catch (Exception e) {
            if (snapshotId != null) CacheCleaner.removeCleanupSnapshot(requireContext(), snapshotId);
            resetCleanupUi();
            Toast.makeText(requireContext(), "No se pudo iniciar Turbo Shizuku: "
                    + (e.getMessage() == null ? "error del sistema" : e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void startAccessibilityCleaner(List<CacheAppInfo> targets) {
        if (binding == null || cleaning) return;
        cleaning = true;
        setCleanupUi("Turbo Accesibilidad · iniciando Ajustes", 0);
        String snapshotId = null;
        try {
            snapshotId = CacheCleaner.saveCleanupSnapshot(requireContext(), targets);
            CacheCleanerAccessibilityService.begin(requireContext(), snapshotId);
            accessibilityProgressHandler.removeCallbacks(accessibilityProgressPoll);
            accessibilityProgressHandler.postDelayed(accessibilityProgressPoll, 500L);
        } catch (Exception e) {
            if (snapshotId != null) CacheCleaner.removeCleanupSnapshot(requireContext(), snapshotId);
            requireContext().getSharedPreferences(CacheCleanerAccessibilityService.PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(CacheCleanerAccessibilityService.KEY_RUNNING, false).apply();
            resetCleanupUi();
            Toast.makeText(requireContext(), "No se pudo iniciar Turbo Accesibilidad: "
                    + (e.getMessage() == null ? "error del sistema" : e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void setCleanupUi(String label, int percent) {
        binding.codesuCacheCleanButton.setEnabled(false);
        binding.codesuCacheRefresh.setEnabled(false);
        binding.codesuCacheRefresh.setAlpha(0.55f);
        binding.codesuCacheProgress.setIndeterminate(false);
        binding.codesuCacheProgress.setProgress(percent);
        binding.codesuCacheProgress.setVisibility(View.VISIBLE);
        binding.codesuCacheProgressText.setVisibility(View.VISIBLE);
        binding.codesuCacheResult.setVisibility(View.GONE);
        binding.codesuCacheProgressText.setText(label + " · " + percent + "%");
    }

    private void renderAccessibilityProgress(android.content.SharedPreferences prefs) {
        if (binding == null) return;
        cleaning = true;
        int total = Math.max(1, prefs.getInt("total", 1));
        int current = prefs.getInt(CacheCleanerAccessibilityService.KEY_INDEX, 0);
        int percent = Math.min(99, current * 100 / total);
        String phase = prefs.getString("phase", "Turbo Accesibilidad");
        binding.codesuCacheCleanButton.setEnabled(false);
        binding.codesuCacheRefresh.setEnabled(false);
        binding.codesuCacheRefresh.setAlpha(0.55f);
        binding.codesuCacheProgress.setIndeterminate(false);
        binding.codesuCacheProgress.setProgress(percent);
        binding.codesuCacheProgress.setVisibility(View.VISIBLE);
        binding.codesuCacheProgressText.setVisibility(View.VISIBLE);
        binding.codesuCacheProgressText.setText(phase + " · " + percent + "% · " + current + "/" + total + " apps");
        binding.codesuCacheTotal.setText("Automatización visible de Ajustes · no apagues ni bloquees el teléfono");
    }

    private void renderAccessibilityResult(android.content.SharedPreferences prefs) {
        if (binding == null) return;
        cleaning = false;
        int done = prefs.getInt(CacheCleanerAccessibilityService.KEY_DONE, 0);
        int failed = prefs.getInt(CacheCleanerAccessibilityService.KEY_FAILED, 0);
        long freed = prefs.getLong(CacheCleanerAccessibilityService.KEY_FREED, 0L);
        String error = prefs.getString(CacheCleanerAccessibilityService.KEY_ERROR, "");
        String result = "Turbo Accesibilidad terminado · " + done + " apps confirmadas · "
                + CacheAppAdapter.formatBytes(freed) + " liberados verificados · " + failed + " fallidas";
        binding.codesuCacheProgress.setProgress(100);
        binding.codesuCacheProgressText.setText("100% · Automatización terminada");
        binding.codesuCacheProgress.setVisibility(View.GONE);
        binding.codesuCacheProgressText.setVisibility(View.GONE);
        binding.codesuCacheCleanButton.setEnabled(false);
        binding.codesuCacheRefresh.setEnabled(true);
        binding.codesuCacheRefresh.setAlpha(1f);
        binding.codesuCacheTotal.setText("Medición anterior · toca Actualizar para ver los tamaños actuales antes de otra limpieza.");
        binding.codesuCacheResult.setText(result + (error == null || error.trim().isEmpty() ? "" : "\n" + error));
        binding.codesuCacheResult.setVisibility(View.VISIBLE);
        int attempted = done + failed;
        int rate = attempted == 0 ? 0 : Math.round(done * 100f / attempted);
        long target = prefs.getLong(CacheCleanerAccessibilityService.KEY_TARGET, 0L);
        int spaceRate = target <= 0L ? 0 : Math.min(100, Math.round(freed * 100f / target));
        android.content.SharedPreferences stats = requireContext().getSharedPreferences(CLEANER_PREFS, Context.MODE_PRIVATE);
        stats.edit().putInt("last_success_rate", rate).putInt("last_space_rate", spaceRate)
                .putLong("last_freed_bytes", freed).apply();
        prefs.edit().putBoolean(CacheCleanerAccessibilityService.KEY_RESULT_PENDING, false).apply();
        Toast.makeText(requireContext(), result, Toast.LENGTH_LONG).show();
    }

    private void resetCleanupUi() {
        cleaning = false;
        if (binding == null) return;
        binding.codesuCacheCleanButton.setEnabled(true);
        binding.codesuCacheRefresh.setEnabled(true);
        binding.codesuCacheRefresh.setAlpha(1f);
        binding.codesuCacheProgress.setVisibility(View.GONE);
        binding.codesuCacheProgressText.setVisibility(View.GONE);
    }
    private void observeCleanupWork() {
        if (binding == null) return;
        if (cleanupWorkLiveData != null && cleanupWorkObserver != null)
            cleanupWorkLiveData.removeObserver(cleanupWorkObserver);
        String savedId = requireContext().getSharedPreferences(CLEANER_PREFS, Context.MODE_PRIVATE)
                .getString(LAST_WORK_ID, "");
        if (savedId == null || savedId.isEmpty()) return;
        try {
            UUID workId = UUID.fromString(savedId);
            cleanupWorkLiveData = WorkManager.getInstance(requireContext()).getWorkInfoByIdLiveData(workId);
            cleanupWorkObserver = info -> renderCleanupWork(info);
            cleanupWorkLiveData.observe(getViewLifecycleOwner(), cleanupWorkObserver);
        } catch (IllegalArgumentException ignored) {
            requireContext().getSharedPreferences(CLEANER_PREFS, Context.MODE_PRIVATE).edit().remove(LAST_WORK_ID).apply();
        }
    }

    private void renderCleanupWork(WorkInfo info) {
        if (binding == null || info == null) return;
        WorkInfo.State state = info.getState();
        boolean active = state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.RUNNING
                || state == WorkInfo.State.BLOCKED;
        if (active) {
            cleaning = true;
            loading = false;
            binding.codesuCacheCleanButton.setEnabled(false);
            binding.codesuCacheRefresh.setEnabled(false);
            binding.codesuCacheRefresh.setAlpha(0.55f);
            binding.codesuCacheProgress.setIndeterminate(false);
            binding.codesuCacheProgress.setVisibility(View.VISIBLE);
            binding.codesuCacheProgressText.setVisibility(View.VISIBLE);
            Data progress = info.getProgress();
            int percent = progress.getInt(CacheCleanerWorker.KEY_PERCENT, 0);
            String phase = progress.getString(CacheCleanerWorker.KEY_PHASE);
            String current = progress.getString(CacheCleanerWorker.KEY_CURRENT);
            binding.codesuCacheProgress.setProgress(percent);
            binding.codesuCacheProgressText.setText((phase == null ? "Limpieza en segundo plano" : phase)
                    + " · " + percent + "%" + (current == null || current.isEmpty() ? "" : " · " + current));
            binding.codesuCacheTotal.setText("La limpieza continúa aunque salgas de CodeSu");
            return;
        }
        cleaning = false;
        binding.codesuCacheCleanButton.setEnabled(false);
        binding.codesuCacheRefresh.setEnabled(true);
        binding.codesuCacheRefresh.setAlpha(1f);
        binding.codesuCacheTotal.setText("Medición anterior · toca Actualizar para ver los tamaños actuales antes de otra limpieza.");
        if (!state.isFinished()) return;
        String workId = info.getId().toString();
        String lastShown = requireContext().getSharedPreferences(CLEANER_PREFS, Context.MODE_PRIVATE)
                .getString("last_cleanup_shown_id", "");
        if (workId.equals(lastShown)) return;
        Data output = info.getOutputData();
        int cleared = output.getInt(CacheCleanerWorker.KEY_CLEARED, 0);
        int failed = output.getInt(CacheCleanerWorker.KEY_FAILED, 0);
        long freed = output.getLong(CacheCleanerWorker.KEY_FREED, 0L);
        String error = output.getString(CacheCleanerWorker.KEY_ERROR);
        String result = state == WorkInfo.State.SUCCEEDED && failed == 0
                ? "Limpieza completada · " + cleared + " apps confirmadas · " + CacheAppAdapter.formatBytes(freed) + " liberados"
                : state == WorkInfo.State.SUCCEEDED
                ? "Limpieza parcial · " + cleared + " confirmadas · " + CacheAppAdapter.formatBytes(freed) + " liberados"
                : state == WorkInfo.State.CANCELLED ? "Limpieza cancelada por el usuario"
                : "Limpieza no completada · " + (error == null ? "Shizuku no confirmó el borrado." : error);
        binding.codesuCacheProgress.setProgress(state == WorkInfo.State.SUCCEEDED ? 100 : 0);
        binding.codesuCacheProgressText.setText(state == WorkInfo.State.SUCCEEDED ? "100% · Tarea finalizada" : "Limpieza detenida");
        binding.codesuCacheResult.setText(result + (failed > 0 ? " · " + failed + " sin confirmar" + (error == null || error.trim().isEmpty() ? "" : "\n" + error) : ""));
        binding.codesuCacheResult.setVisibility(View.VISIBLE);
        binding.codesuCacheProgress.setVisibility(View.GONE);
        binding.codesuCacheProgressText.setVisibility(View.GONE);
        requireContext().getSharedPreferences(CLEANER_PREFS, Context.MODE_PRIVATE).edit()
                .putString("last_cleanup_shown_id", workId).apply();
        Toast.makeText(requireContext(), result, Toast.LENGTH_LONG).show();
    }
}
