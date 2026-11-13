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
            showAccessGate("Cache estimates require Android 8.0 or newer.", false);
            return;
        }
        if (!CacheCleaner.hasUsageAccess(requireContext())) {
            showAccessGate("Grant Usage access so Android can report each app’s cache size.", true);
            return;
        }

        binding.codesuUsageAccessButton.setVisibility(View.GONE);
        binding.codesuCacheApps.setVisibility(View.VISIBLE);
        binding.codesuCacheEmpty.setVisibility(View.GONE);
        binding.codesuCacheCleanButton.setVisibility(View.GONE);
        binding.codesuCacheProgress.setVisibility(View.VISIBLE);
        binding.codesuCacheProgress.setIndeterminate(true);
        binding.codesuCacheProgressText.setVisibility(View.VISIBLE);
        binding.codesuCacheProgressText.setText("Calculating cache estimates…");
        binding.codesuCacheTotal.setText("Reading installed apps");

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
                        if (!app.protectedApp && app.cacheBytes > 0) {
                            totalBytes += app.cacheBytes;
                            appsWithCache++;
                        }
                    }
                    binding.codesuCacheTotal.setText("Estimated clearable cache · " + CacheAppAdapter.formatBytes(totalBytes)
                            + " across " + appsWithCache + " apps");
                    binding.codesuCacheProgress.setVisibility(View.GONE);
                    binding.codesuCacheProgressText.setVisibility(View.GONE);
                    binding.codesuCacheEmpty.setVisibility(apps.isEmpty() ? View.VISIBLE : View.GONE);
                    binding.codesuCacheEmpty.setText(apps.isEmpty()
                            ? "No installed apps are available for cache reporting." : "");
                    binding.codesuCacheCleanButton.setVisibility(appsWithCache > 0 ? View.VISIBLE : View.GONE);
                    binding.codesuCacheCleanButton.setEnabled(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R);
                    binding.codesuCacheSafetyNote.setText(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            ? "User and system apps are listed largest first. Protected apps remain visible but are skipped. Only cache is removed; app data and sign-ins stay intact."
                            : "Selective cleaning with protected-app exclusions needs Android 11+. Cache estimates are shown for review.");
                });
            } catch (SecurityException e) {
                App.handler.post(() -> {
                    if (binding != null) showAccessGate("Usage access is off. Turn it on and return to refresh the list.", true);
                });
            } catch (Exception e) {
                App.handler.post(() -> {
                    if (binding == null) return;
                    binding.codesuCacheProgress.setVisibility(View.GONE);
                    binding.codesuCacheProgressText.setVisibility(View.GONE);
                    binding.codesuCacheTotal.setText("Could not read app cache sizes");
                    binding.codesuCacheEmpty.setVisibility(View.VISIBLE);
                    binding.codesuCacheEmpty.setText(e.getMessage() == null
                            ? "Storage statistics are unavailable on this device." : e.getMessage());
                });
            }
        });
    }

    private void showAccessGate(String message, boolean showUsageSettings) {
        if (binding == null) return;
        binding.codesuCacheApps.setVisibility(View.GONE);
        binding.codesuCacheCleanButton.setVisibility(View.GONE);
        binding.codesuCacheTotal.setText("Cache estimates need access");
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
            Toast.makeText(requireContext(), "Protected selective cache cleaning requires Android 11+.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!CacheCleaner.isShizukuReady()) {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Shizuku authorization needed")
                    .setMessage("Start Shizuku and grant CodeSu permission in the Settings tab. Then return here and tap the broom.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        List<CacheAppInfo> apps = adapter.getApps();
        int targets = 0;
        long estimate = 0L;
        for (CacheAppInfo app : apps) {
            if (app.cacheBytes > 0 && !app.protectedApp && !CacheCleaner.isProtected(app.packageName)) {
                targets++;
                estimate += app.cacheBytes;
            }
        }
        if (targets == 0) {
            Toast.makeText(requireContext(), "No eligible app cache to clear.", Toast.LENGTH_SHORT).show();
            return;
        }
        String message = "Clear estimated " + CacheAppAdapter.formatBytes(estimate) + " from " + targets
                + " apps? Protected system apps are excluded. App data and sign-ins are kept; cached media may need to download again.";
        new AlertDialog.Builder(requireContext())
                .setTitle("Clean app caches")
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clean cache", (dialog, which) -> runGlobalCacheClear(apps))
                .show();
    }

    private void runGlobalCacheClear(List<CacheAppInfo> apps) {
        if (binding == null) return;
        cleaning = true;
        binding.codesuCacheCleanButton.setEnabled(false);
        binding.codesuCacheProgress.setIndeterminate(false);
        binding.codesuCacheProgress.setProgress(0);
        binding.codesuCacheProgress.setVisibility(View.VISIBLE);
        binding.codesuCacheProgressText.setVisibility(View.VISIBLE);
        binding.codesuCacheProgressText.setText("Preparing safe cache cleanup…");
        binding.codesuCacheCleanButton.animate().rotationBy(360f).setDuration(700)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();

        Context appContext = requireContext().getApplicationContext();
        App.executorService.execute(() -> {
            try {
                CacheCleaner.ClearResult result = CacheCleaner.clearEligibleCaches(
                        appContext, apps,
                        (completed, total, pkg, cleared) -> App.handler.post(() -> {
                            if (binding == null) return;
                            int percent = total == 0 ? 100 : (int) ((completed * 100L) / total);
                            binding.codesuCacheProgress.setProgress(percent);
                            binding.codesuCacheProgressText.setText(percent + "% · " + completed + "/" + total
                                    + " · " + pkg);
                        }));
                App.handler.post(() -> {
                    if (binding == null) return;
                    cleaning = false;
                    binding.codesuCacheCleanButton.setEnabled(true);
                    binding.codesuCacheProgress.setProgress(100);
                    binding.codesuCacheProgressText.setText("100% complete · refreshing estimates…");
                    binding.codesuCacheTotal.setText("Cleared " + result.cleared + " apps · estimated "
                            + CacheAppAdapter.formatBytes(result.estimatedFreedBytes) + " released · "
                            + result.failed + " skipped or denied");
                    Toast.makeText(requireContext(), "Cache cleanup complete · " + result.cleared
                            + " apps · ~" + CacheAppAdapter.formatBytes(result.estimatedFreedBytes) + " released",
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
                            ? "Cache cleanup failed. Check Shizuku authorization." : e.getMessage(), Toast.LENGTH_LONG).show();
                    loadCacheApps();
                });
            }
        });
    }
}
