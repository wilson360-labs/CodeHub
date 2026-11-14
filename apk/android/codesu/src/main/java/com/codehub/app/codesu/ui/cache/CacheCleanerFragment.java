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
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.List;

import com.codehub.app.codesu.App;
import com.codehub.app.codesu.databinding.CodesuCacheCleanerBinding;
import com.codehub.app.codesu.model.CacheAppInfo;
import com.codehub.app.codesu.util.CacheCleaner;

public class CacheCleanerFragment extends Fragment {

    private CodesuCacheCleanerBinding binding;
    private CacheAppAdapter adapter;
    private boolean cleaning;
    private boolean loading;
    private int loadGeneration;
    private static final String CLEANER_PREFS = "codesu_cache_cleaner";

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
        loadCacheApps();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        loadGeneration++;
        loading = false;
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
                .setPositiveButton("Medir y limpiar", (dialog, which) -> runGlobalCacheClear(apps))
                .show();
    }

    private void runGlobalCacheClear(List<CacheAppInfo> apps) {
        if (binding == null) return;
        cleaning = true;
        binding.codesuCacheCleanButton.setEnabled(false);
        binding.codesuCacheRefresh.setEnabled(false);
        binding.codesuCacheRefresh.setAlpha(0.55f);
        binding.codesuCacheProgress.setIndeterminate(false);
        binding.codesuCacheProgress.setProgress(0);
        binding.codesuCacheProgress.setVisibility(View.VISIBLE);
        binding.codesuCacheProgressText.setVisibility(View.VISIBLE);
        binding.codesuCacheResult.setVisibility(View.GONE);
        binding.codesuCacheProgressText.setText("Preparando medición de caché · 0%");
        binding.codesuCacheCleanButton.animate().rotationBy(360f).setDuration(700)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();

        Context appContext = requireContext().getApplicationContext();
        App.executorService.execute(() -> {
            try {
                List<CacheAppInfo> refreshedApps = CacheCleaner.recalculateCacheSizes(appContext, apps,
                        (completed, total, packageName) -> App.handler.post(() -> {
                            if (binding == null) return;
                            int percent = total == 0 ? 35 : (int) (completed * 35L / total);
                            binding.codesuCacheProgress.setIndeterminate(false);
                            binding.codesuCacheProgress.setProgress(percent);
                            binding.codesuCacheProgressText.setText("Midiendo caché · " + percent + "% · "
                                    + completed + "/" + total + " apps");
                        }));
                int eligible = 0;
                for (CacheAppInfo app : refreshedApps) {
                    if (app.cacheBytes > CacheCleaner.DEEP_CLEAN_MIN_CACHE_BYTES && !app.protectedApp
                            && !CacheCleaner.isProtected(app.packageName)) eligible++;
                }
                if (eligible == 0) {
                    App.handler.post(() -> {
                        if (binding == null) return;
                        cleaning = false;
                        binding.codesuCacheCleanButton.setEnabled(true);
                        binding.codesuCacheProgress.setVisibility(View.GONE);
                        binding.codesuCacheProgressText.setVisibility(View.GONE);
                        binding.codesuCacheTotal.setText("Caché actualizada · ninguna app supera 1 MB");
                        Toast.makeText(requireContext(), "La caché está actualizada; ninguna app supera 1 MB.",
                                Toast.LENGTH_SHORT).show();
                        loadCacheApps();
                    });
                    return;
                }
                App.handler.post(() -> {
                    if (binding == null) return;
                    binding.codesuCacheProgress.setIndeterminate(false);
                    binding.codesuCacheProgress.setProgress(40);
                    binding.codesuCacheProgressText.setText("Caché medida · conectando con Shizuku · 40%");
                });
                CacheCleaner.ClearResult result = CacheCleaner.clearEligibleCaches(
                        appContext, refreshedApps,
                        (completed, total, pkg, cleared, verifiedFreed, unmeasured) -> App.handler.post(() -> {
                            if (binding == null) return;
                            int percent = total == 0 ? 95 : 40 + (int) ((completed * 55L) / total);
                            binding.codesuCacheProgress.setProgress(percent);
                            binding.codesuCacheProgressText.setText(percent + "% · limpiando " + pkg
                                    + " · " + completed + "/" + total + " apps · "
                                    + CacheAppAdapter.formatBytes(verifiedFreed) + " verificados"
                                    + (unmeasured > 0 ? " · " + unmeasured + " sin medir" : ""));
                        }));
                App.handler.post(() -> {
                    if (binding == null) return;
                    cleaning = false;
                    binding.codesuCacheCleanButton.setEnabled(true);
                    binding.codesuCacheRefresh.setEnabled(true);
                    binding.codesuCacheRefresh.setAlpha(1f);
                    binding.codesuCacheProgress.setProgress(100);
                    binding.codesuCacheProgressText.setText("100% · Actualizando tamaños…");
                    String outcome = result.failed == 0 ? "Limpieza completada"
                            : result.cleared == 0 ? "No se pudo ejecutar la limpieza" : "Limpieza parcial";
                    String resultMessage = outcome + " · " + result.cleared + " apps confirmadas · "
                            + CacheAppAdapter.formatBytes(result.verifiedFreedBytes) + " liberados"
                            + (result.unmeasuredApps > 0 ? " · medición pendiente en " + result.unmeasuredApps + " apps" : "")
                            + (result.failed > 0 ? " · " + result.failed + " omitidas o sin permiso" : "");
                    int attempted = result.cleared + result.failed;
                    int successRate = attempted == 0 ? 0 : Math.round(result.cleared * 100f / attempted);
                    int spaceRate = result.targetBytes <= 0 ? 0
                            : Math.min(100, Math.round(result.verifiedFreedBytes * 100f / result.targetBytes));
                    requireContext().getSharedPreferences(CLEANER_PREFS, Context.MODE_PRIVATE).edit()
                            .putInt("last_success_rate", successRate)
                            .putInt("last_space_rate", spaceRate)
                            .putLong("last_freed_bytes", result.verifiedFreedBytes)
                            .apply();
                    binding.codesuCacheSuccessRate.setText("Liberación confirmada · " + spaceRate + "% del objetivo · "
                            + successRate + "% apps OK · " + result.cleared + "/" + attempted + " · "
                            + CacheAppAdapter.formatBytes(result.verifiedFreedBytes) + " verificados");
                    binding.codesuCacheSuccessRate.setTextColor(successRate >= 80 ? 0xFF41C99A : 0xFFE7838B);
                    binding.codesuCacheResult.setText(resultMessage);
                    binding.codesuCacheResult.setVisibility(View.VISIBLE);
                    String toastMessage = result.cleared == 0
                            ? "No se eliminó caché. Revisa la autorización de Shizuku e inténtalo de nuevo."
                            : outcome + " · " + result.cleared + " apps · "
                                    + CacheAppAdapter.formatBytes(result.verifiedFreedBytes) + " liberados";
                    Toast.makeText(requireContext(), toastMessage, Toast.LENGTH_LONG).show();
                    loadCacheApps();
                });
            } catch (Exception e) {
                App.handler.post(() -> {
                    if (binding == null) return;
                    cleaning = false;
                    binding.codesuCacheCleanButton.setEnabled(true);
                    binding.codesuCacheRefresh.setEnabled(true);
                    binding.codesuCacheRefresh.setAlpha(1f);
                    binding.codesuCacheProgress.setVisibility(View.GONE);
                    binding.codesuCacheProgressText.setVisibility(View.GONE);
                    Toast.makeText(requireContext(), e.getMessage() == null
                            ? "No se pudo limpiar la caché. Revisa la autorización de Shizuku." : e.getMessage(), Toast.LENGTH_LONG).show();
                    loadCacheApps();
                });
            }
        });
    }
}
