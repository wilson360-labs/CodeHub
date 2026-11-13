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
    }

    @Override
    public void onResume() {
        super.onResume();
        loadCacheApps();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        adapter = null;
    }

    private void loadCacheApps() {
        if (binding == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showAccessGate("La medición de caché requiere Android 8.0 o posterior.", false);
            return;
        }
        if (!CacheCleaner.hasUsageAccess(requireContext())) {
            showAccessGate("Activa el acceso al uso para consultar el tamaño de caché de cada app.", true);
            return;
        }

        binding.codesuUsageAccessButton.setVisibility(View.GONE);
        binding.codesuCacheApps.setVisibility(View.VISIBLE);
        binding.codesuCacheEmpty.setVisibility(View.GONE);
        binding.codesuCacheCleanButton.setVisibility(View.GONE);
        binding.codesuCacheProgress.setVisibility(View.VISIBLE);
        binding.codesuCacheProgress.setIndeterminate(true);
        binding.codesuCacheProgressText.setVisibility(View.VISIBLE);
        binding.codesuCacheProgressText.setText("Calculando tamaños de caché…");
        binding.codesuCacheTotal.setText("Leyendo aplicaciones instaladas");

        Context appContext = requireContext().getApplicationContext();
        App.executorService.execute(() -> {
            try {
                List<CacheAppInfo> apps = CacheCleaner.loadInstalledApps(appContext);
                App.handler.post(() -> {
                    if (binding == null) return;
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
                    binding.codesuCacheProgress.setVisibility(View.GONE);
                    binding.codesuCacheProgressText.setVisibility(View.GONE);
                    binding.codesuCacheEmpty.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
                    binding.codesuCacheEmpty.setText(apps.isEmpty()
                            ? "No hay aplicaciones instaladas disponibles para medir su caché." : "");
                    binding.codesuCacheCleanButton.setVisibility(appsWithCache > 0 ? View.VISIBLE : View.GONE);
                    binding.codesuCacheCleanButton.setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
                    binding.codesuCacheSafetyNote.setText(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            ? "Apps de usuario y del sistema, ordenadas por tamaño. Solo se limpian las que superan 1 MB; las apps protegidas se omiten. No se borran datos ni sesiones."
                            : "La limpieza selectiva con exclusión de apps protegidas requiere Android 11+. Puedes consultar los tamaños.");
                });
            } catch (SecurityException e) {
                App.handler.post(() -> {
                    if (binding != null) showAccessGate("El acceso al uso está desactivado. Actívalo y vuelve para actualizar la lista.", true);
                });
            } catch (Exception e) {
                App.handler.post(() -> {
                    if (binding == null) return;
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
        if (binding == null || cleaning) return;
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
        binding.codesuCacheProgress.setIndeterminate(true);
        binding.codesuCacheProgress.setProgress(0);
        binding.codesuCacheProgress.setVisibility(View.VISIBLE);
        binding.codesuCacheProgressText.setVisibility(View.VISIBLE);
        binding.codesuCacheResult.setVisibility(View.GONE);
        binding.codesuCacheProgressText.setText("Midiendo la caché antes de limpiar…");
        binding.codesuCacheCleanButton.animate().rotationBy(360f).setDuration(700)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();

        Context appContext = requireContext().getApplicationContext();
        App.executorService.execute(() -> {
            try {
                List<CacheAppInfo> refreshedApps = CacheCleaner.recalculateCacheSizes(appContext, apps);
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
                final int eligibleCount = eligible;
                App.handler.post(() -> {
                    if (binding == null) return;
                    binding.codesuCacheProgress.setIndeterminate(false);
                    binding.codesuCacheProgressText.setText("Limpiando " + eligibleCount + " apps…");
                });
                CacheCleaner.ClearResult result = CacheCleaner.clearEligibleCaches(
                        appContext, refreshedApps,
                        (completed, total, pkg, cleared, verifiedFreed, unmeasured) -> App.handler.post(() -> {
                            if (binding == null) return;
                            int percent = total == 0 ? 100 : (int) ((completed * 100L) / total);
                            binding.codesuCacheProgress.setProgress(percent);
                            binding.codesuCacheProgressText.setText(percent + "% · " + completed + "/" + total
                                    + " apps · " + CacheAppAdapter.formatBytes(verifiedFreed) + " liberados"
                                    + (unmeasured > 0 ? " · " + unmeasured + " sin medir" : ""));
                        }));
                App.handler.post(() -> {
                    if (binding == null) return;
                    cleaning = false;
                    binding.codesuCacheCleanButton.setEnabled(true);
                    binding.codesuCacheProgress.setProgress(100);
                    binding.codesuCacheProgressText.setText("100% · Actualizando tamaños…");
                    String resultMessage = "Liberación verificada: " + result.cleared + " apps · "
                            + CacheAppAdapter.formatBytes(result.verifiedFreedBytes) + " liberados"
                            + (result.unmeasuredApps > 0 ? " · medición pendiente en " + result.unmeasuredApps + " apps" : "")
                            + (result.failed > 0 ? " · " + result.failed + " omitidas o sin permiso" : "");
                    binding.codesuCacheResult.setText(resultMessage);
                    binding.codesuCacheResult.setVisibility(View.VISIBLE);
                    Toast.makeText(requireContext(), "Limpieza terminada · " + result.cleared
                            + " apps · " + CacheAppAdapter.formatBytes(result.verifiedFreedBytes) + " liberados",
                            Toast.LENGTH_LONG).show();
                    loadCacheApps();
                });
            } catch (Exception e) {
                App.handler.post(() -> {
                    if (binding == null) return;
                    cleaning = false;
                    binding.codesuCacheCleanButton.setEnabled(true);
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
