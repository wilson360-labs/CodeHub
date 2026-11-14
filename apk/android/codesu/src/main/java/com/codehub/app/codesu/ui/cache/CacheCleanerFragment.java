package com.codehub.app.codesu.ui.cache;

import android.content.Intent;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
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
        loadCacheApps();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
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
                        if (!app.protectedApp && app.cacheBytes > CacheCleaner.DEEP_CLEAN_MIN_CACHE_BYTES) {
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
                            ? "Turbo Shizuku · hasta 3 apps en paralelo. Recomendable: caché >1 MB; no recomendable: servicios protegidos. No desinstala apps ni borra datos o sesiones."
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
        if (!CacheCleaner.isShizukuReady()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Se necesita autorización de Shizuku")
                    .setMessage("Inicia Shizuku y concede permiso a CodeSu en la pestaña Ajustes. Después vuelve aquí y toca la escoba.")
                    .setPositiveButton("Entendido", null)
                    .show();
            return;
        }
        List<CacheAppInfo> apps = adapter.getApps();
        int targets = 0;
        long estimate = 0L;
        for (CacheAppInfo app : apps) {
            if (app.cacheBytes > CacheCleaner.DEEP_CLEAN_MIN_CACHE_BYTES && !app.protectedApp
                    && !CacheCleaner.isProtected(app.packageName)) {
                targets++;
                estimate += app.cacheBytes;
            }
        }
        if (targets == 0) {
            Toast.makeText(requireContext(), "Ninguna app no protegida supera 1 MB de caché.", Toast.LENGTH_SHORT).show();
            return;
        }
        String message = "Se volverá a medir la caché antes de limpiar las apps que superen 1 MB. Estimación actual: "
                + CacheAppAdapter.formatBytes(estimate) + " en " + targets
                + " apps. Se omiten las apps protegidas y se conservan los datos y las sesiones.";
        new AlertDialog.Builder(requireContext())
                .setTitle("Limpieza profunda de caché")
                .setMessage(message)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Medir y limpiar", (dialog, which) -> runGlobalCacheClear())
                .show();
    }

    private void runGlobalCacheClear() {
        if (binding == null || cleaning) return;
        cleaning = true;
        binding.codesuCacheCleanButton.setEnabled(false);
        binding.codesuCacheRefresh.setEnabled(false);
        binding.codesuCacheRefresh.setAlpha(0.55f);
        binding.codesuCacheProgress.setIndeterminate(false);
        binding.codesuCacheProgress.setProgress(0);
        binding.codesuCacheProgress.setVisibility(View.VISIBLE);
        binding.codesuCacheProgressText.setVisibility(View.VISIBLE);
        binding.codesuCacheResult.setVisibility(View.GONE);
        binding.codesuCacheProgressText.setText("Preparando limpieza segura · 0%");
        binding.codesuCacheCleanButton.animate().rotationBy(360f).setDuration(700)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
        try {
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CacheCleanerWorker.class)
                    .addTag(CacheCleanerWorker.TAG).build();
            requireContext().getSharedPreferences(CLEANER_PREFS, Context.MODE_PRIVATE).edit()
                    .putString(LAST_WORK_ID, request.getId().toString()).apply();
            WorkManager.getInstance(requireContext()).enqueue(request);
            observeCleanupWork();
        } catch (RuntimeException e) {
            cleaning = false;
            binding.codesuCacheCleanButton.setEnabled(true);
            binding.codesuCacheRefresh.setEnabled(true);
            binding.codesuCacheRefresh.setAlpha(1f);
            binding.codesuCacheProgress.setVisibility(View.GONE);
            binding.codesuCacheProgressText.setVisibility(View.GONE);
            Toast.makeText(requireContext(), "No se pudo iniciar la limpieza en segundo plano: "
                    + (e.getMessage() == null ? "error del sistema" : e.getMessage()), Toast.LENGTH_LONG).show();
        }
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
        binding.codesuCacheCleanButton.setEnabled(true);
        binding.codesuCacheRefresh.setEnabled(true);
        binding.codesuCacheRefresh.setAlpha(1f);
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
        loadCacheApps();
    }
}
