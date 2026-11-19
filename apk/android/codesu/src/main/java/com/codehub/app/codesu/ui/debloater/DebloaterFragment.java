package com.codehub.app.codesu.ui.debloater;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.codehub.app.codesu.R;
import com.codehub.app.codesu.databinding.CodesuFragmentDebloaterBinding;
import com.codehub.app.codesu.util.ShizukuPlusUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/** Canta-style system app manager: search, filters, badges, selection and batch actions. */
public final class DebloaterFragment extends Fragment {
    private static final String PREFS = "codesu_debloater";
    private static final String KEY_REMOVED = "removed_system_packages";
    private static final int REQ_SHIZUKU = 2107;

    private static final String FILTER_ALL = "all";
    private static final String FILTER_RECOMMENDED = DebloaterCatalog.RECOMMENDED;
    private static final String FILTER_ADVANCED = DebloaterCatalog.ADVANCED;
    private static final String FILTER_EXPERT = DebloaterCatalog.EXPERT;
    private static final String FILTER_UNCLASSIFIED = DebloaterCatalog.UNCLASSIFIED;
    private static final String FILTER_DISABLED = "disabled";
    private static final String FILTER_REMOVED = "removed";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<DebloaterAppInfo> allApps = new ArrayList<>();
    private final List<DebloaterAppInfo> visibleApps = new ArrayList<>();
    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQ_SHIZUKU || !isAdded()) return;
                requireActivity().runOnUiThread(() -> {
                    updateShizukuState();
                    if (grantResult == PackageManager.PERMISSION_GRANTED) loadApps();
                });
            };

    private CodesuFragmentDebloaterBinding binding;
    private DebloaterAdapter adapter;
    private String activeFilter = FILTER_ALL;
    private boolean loading;
    private int loadGeneration;

    @Nullable @Override
    public android.view.View onCreateView(@NonNull LayoutInflater inflater,
                                          @Nullable ViewGroup container,
                                          @Nullable Bundle savedInstanceState) {
        binding = CodesuFragmentDebloaterBinding.inflate(inflater, container, false);
        Context context = requireContext();

        adapter = new DebloaterAdapter(new DebloaterAdapter.Listener() {
            @Override
            public void onSelectionChanged(DebloaterAppInfo app) {
                updateSelectionUi();
            }

            @Override
            public void onStateAction(DebloaterAppInfo app) {
                if (app.removed) {
                    confirmAction(3, Collections.singletonList(app));
                } else {
                    confirmAction(app.isEnabledApp() ? 0 : 1, Collections.singletonList(app));
                }
            }

            @Override
            public void onRemoveAction(DebloaterAppInfo app) {
                confirmAction(app.removed ? 3 : 2, Collections.singletonList(app));
            }

            @Override
            public void onInfoAction(DebloaterAppInfo app) {
                showAppInfo(app);
            }
        });

        binding.codesuDebloaterRecycler.setLayoutManager(new LinearLayoutManager(context));
        binding.codesuDebloaterRecycler.setAdapter(adapter);
        binding.codesuDebloaterRecycler.setHasFixedSize(true);
        setupUi();
        updateShizukuState();
        if (hasShizuku()) loadApps();
        else applyFilters();
        return binding.getRoot();
    }

    @Override
    public void onStart() {
        super.onStart();
        Shizuku.addRequestPermissionResultListener(permissionListener);
    }

    @Override
    public void onStop() {
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        super.onStop();
    }

    private void setupUi() {
        binding.codesuDebloaterSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { applyFilters(); }
            @Override public void afterTextChanged(Editable s) { }
        });

        binding.codesuFilterAll.setOnClickListener(v -> setFilter(FILTER_ALL));
        binding.codesuFilterRecommended.setOnClickListener(v -> setFilter(FILTER_RECOMMENDED));
        binding.codesuFilterAdvanced.setOnClickListener(v -> setFilter(FILTER_ADVANCED));
        binding.codesuFilterExpert.setOnClickListener(v -> setFilter(FILTER_EXPERT));
        binding.codesuFilterUnclassified.setOnClickListener(v -> setFilter(FILTER_UNCLASSIFIED));
        binding.codesuFilterDisabled.setOnClickListener(v -> setFilter(FILTER_DISABLED));
        binding.codesuFilterRemoved.setOnClickListener(v -> setFilter(FILTER_REMOVED));
        binding.codesuDebloaterFilters.check(R.id.codesu_filter_all);
        activeFilter = FILTER_ALL;

        binding.codesuDebloaterRefresh.setOnClickListener(v -> loadApps());
        binding.codesuDebloaterConnect.setOnClickListener(v -> connectShizuku());
        binding.codesuDebloaterBadgesInfo.setOnClickListener(v -> showBadgesInfo());
        binding.codesuDebloaterSelectAll.setOnClickListener(v -> selectAllVisible());
        binding.codesuDebloaterClearSelection.setOnClickListener(v -> clearSelection());
        binding.codesuDebloaterBatchDisable.setOnClickListener(v -> requestBatch(0));
        binding.codesuDebloaterBatchEnable.setOnClickListener(v -> requestBatch(1));
        binding.codesuDebloaterBatchRemove.setOnClickListener(v -> requestBatch(2));
        binding.codesuDebloaterBatchRestore.setOnClickListener(v -> requestBatch(3));
    }

    private boolean hasShizuku() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void updateShizukuState() {
        if (binding == null) return;
        boolean running;
        try { running = Shizuku.pingBinder(); } catch (RuntimeException e) { running = false; }
        boolean authorized = hasShizuku();
        binding.codesuDebloaterShizukuState.setText(!running ? "Shizuku detenido"
                : authorized ? "Shizuku conectado" : "Shizuku sin permiso");
        binding.codesuDebloaterShizukuState.setTextColor(ContextCompat.getColor(requireContext(),
                authorized ? R.color.codesu_badge_recommended : R.color.codesu_badge_advanced));
        binding.codesuDebloaterConnect.setText(!running ? "Abrir Shizuku"
                : authorized ? "Shizuku listo" : "Dar permiso");
    }

    private void connectShizuku() {
        boolean running;
        try { running = Shizuku.pingBinder(); } catch (RuntimeException e) { running = false; }
        if (!running) {
            Intent launch = requireContext().getPackageManager()
                    .getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
            } else {
                Toast.makeText(requireContext(), "Instala e inicia Shizuku; después vuelve a CodeSu.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (hasShizuku()) {
            Toast.makeText(requireContext(), "Shizuku ya está autorizado.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (RuntimeException e) {
            Toast.makeText(requireContext(), "No se pudo pedir el permiso de Shizuku.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void loadApps() {
        if (binding == null) return;
        updateShizukuState();
        if (!hasShizuku()) {
            allApps.clear();
            applyFilters();
            Toast.makeText(requireContext(), "Shizuku no está activo o CodeSu no tiene permiso.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        int generation = ++loadGeneration;
        loading = true;
        binding.codesuDebloaterLoading.setVisibility(View.VISIBLE);
        binding.codesuDebloaterStatus.setText("Cargando apps del sistema…");
        Context appContext = requireContext().getApplicationContext();
        Set<String> selectedPackages = new HashSet<>();
        for (DebloaterAppInfo app : allApps) if (app.selected) selectedPackages.add(app.packageName);

        executor.execute(() -> {
            List<DebloaterAppInfo> loaded = new ArrayList<>();
            try {
                PackageManager pm = appContext.getPackageManager();
                Set<String> removed = removedSet(appContext);
                List<ApplicationInfo> installed = pm.getInstalledApplications(
                        PackageManager.MATCH_DISABLED_COMPONENTS);
                for (ApplicationInfo app : installed) {
                    if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;
                    if (isProtected(app.packageName) || removed.contains(app.packageName)) continue;
                    loaded.add(toInfo(app, false, selectedPackages, pm));
                }
                for (String pkg : removed) {
                    if (isProtected(pkg)) continue;
                    try {
                        ApplicationInfo app = pm.getApplicationInfo(pkg,
                                PackageManager.MATCH_UNINSTALLED_PACKAGES
                                        | PackageManager.MATCH_DISABLED_COMPONENTS);
                        if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;
                        loaded.add(toInfo(app, true, selectedPackages, pm));
                    } catch (PackageManager.NameNotFoundException ignored) { }
                }
                Collections.sort(loaded, (left, right) -> left.label.compareToIgnoreCase(right.label));
            } catch (Exception e) {
                loaded.clear();
            }
            if (!isAdded() || generation != loadGeneration) return;
            requireActivity().runOnUiThread(() -> {
                loading = false;
                if (generation != loadGeneration || binding == null) return;
                binding.codesuDebloaterLoading.setVisibility(View.GONE);
                allApps.clear();
                allApps.addAll(loaded);
                applyFilters();
            });
        });
    }

    private DebloaterAppInfo toInfo(ApplicationInfo app, boolean removed,
                                    Set<String> selectedPackages, PackageManager pm) {
        String label = String.valueOf(pm.getApplicationLabel(app));
        if (label.trim().isEmpty()) label = app.packageName;
        DebloaterAppInfo info = new DebloaterAppInfo(app, label,
                DebloaterCatalog.categoryFor(app.packageName),
                DebloaterCatalog.descriptionFor(app.packageName), removed);
        info.selected = selectedPackages.contains(app.packageName);
        return info;
    }

    private void setFilter(String filter) {
        activeFilter = filter;
        applyFilters();
    }

    private void applyFilters() {
        if (binding == null || adapter == null) return;
        visibleApps.clear();
        String query = binding.codesuDebloaterSearch.getText() == null ? ""
                : binding.codesuDebloaterSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        for (DebloaterAppInfo app : allApps) {
            if (!matchesFilter(app)) continue;
            if (!query.isEmpty() && !app.label.toLowerCase(Locale.ROOT).contains(query)
                    && !app.packageName.toLowerCase(Locale.ROOT).contains(query)
                    && !app.description.toLowerCase(Locale.ROOT).contains(query)) continue;
            visibleApps.add(app);
        }
        adapter.submit(visibleApps);
        binding.codesuDebloaterEmpty.setVisibility(visibleApps.isEmpty() ? View.VISIBLE : View.GONE);
        binding.codesuDebloaterEmpty.setText(loading ? "Cargando…"
                : "No hay apps para este filtro.");

        int active = 0, disabled = 0, removed = 0;
        for (DebloaterAppInfo app : allApps) {
            if (app.removed) removed++;
            else if (app.isEnabledApp()) active++;
            else disabled++;
        }
        binding.codesuDebloaterStatus.setText("Mostrando " + visibleApps.size() + " de " + allApps.size()
                + " · activas " + active + " · desactivadas " + disabled + " · retiradas " + removed);
        updateSelectionUi();
    }

    private boolean matchesFilter(DebloaterAppInfo app) {
        if (FILTER_ALL.equals(activeFilter)) return true;
        if (FILTER_REMOVED.equals(activeFilter)) return app.removed;
        if (app.removed) return false;
        if (FILTER_DISABLED.equals(activeFilter)) return app.isDisabledApp();
        return activeFilter.equals(app.category);
    }

    private void selectAllVisible() {
        for (DebloaterAppInfo app : visibleApps) app.selected = true;
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    private void clearSelection() {
        for (DebloaterAppInfo app : allApps) app.selected = false;
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        if (binding == null) return;
        int count = 0, enabled = 0, disabled = 0, removed = 0;
        for (DebloaterAppInfo app : allApps) {
            if (!app.selected) continue;
            count++;
            if (app.removed) removed++;
            else if (app.isEnabledApp()) enabled++;
            else disabled++;
        }
        binding.codesuDebloaterSelectionBar.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
        binding.codesuDebloaterSelectionCount.setText(count + " seleccionadas");
        binding.codesuDebloaterBatchDisable.setEnabled(enabled > 0);
        binding.codesuDebloaterBatchEnable.setEnabled(disabled > 0);
        binding.codesuDebloaterBatchRemove.setEnabled(enabled + disabled > 0);
        binding.codesuDebloaterBatchRestore.setEnabled(removed > 0);
    }

    private List<DebloaterAppInfo> selectedApps() {
        List<DebloaterAppInfo> selected = new ArrayList<>();
        for (DebloaterAppInfo app : allApps) if (app.selected) selected.add(app);
        return selected;
    }

    private void requestBatch(int action) {
        if (!hasShizuku()) {
            Toast.makeText(requireContext(), "Shizuku no está disponible. No se hizo ningún cambio.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        List<DebloaterAppInfo> targets = selectedApps();
        if (targets.isEmpty()) return;
        confirmAction(action, targets);
    }

    private void confirmAction(int action, List<DebloaterAppInfo> targets) {
        if (!hasShizuku()) {
            Toast.makeText(requireContext(), "Shizuku no está disponible. No se hizo ningún cambio.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String title;
        String message;
        String positive;
        if (action == 0) {
            title = "Deshabilitar apps";
            message = "Se deshabilitarán para este usuario. Los APK permanecen instalados y se pueden reactivar.";
            positive = "Deshabilitar";
        } else if (action == 1) {
            title = "Reactivar apps";
            message = "Se volverán a habilitar para este usuario.";
            positive = "Reactivar";
        } else if (action == 2) {
            title = "Retirar apps del usuario";
            message = "Se desinstalarán solo para este usuario. Podrás restaurarlas desde “Retiradas”. "
                    + "Algunas funciones del fabricante pueden dejar de estar disponibles.";
            positive = "Retirar";
        } else {
            title = "Restaurar apps";
            message = "Se reinstalarán para este usuario desde el paquete del sistema.";
            positive = "Restaurar";
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(title)
                .setMessage(message + "\n\n" + formatNames(targets))
                .setNegativeButton("Cancelar", null)
                .setPositiveButton(positive, (dialog, which) -> applyBatch(action, targets))
                .show();
    }

    private String formatNames(List<DebloaterAppInfo> targets) {
        StringBuilder builder = new StringBuilder();
        int limit = Math.min(5, targets.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) builder.append("\n");
            builder.append("• ").append(targets.get(i).label);
        }
        if (targets.size() > limit) builder.append("\n… y ").append(targets.size() - limit).append(" más");
        return builder.toString();
    }

    private void applyBatch(int action, List<DebloaterAppInfo> targets) {
        if (binding == null) return;
        Context appContext = requireContext().getApplicationContext();
        binding.codesuDebloaterBatchProgress.setMax(targets.size());
        binding.codesuDebloaterBatchProgress.setProgress(0);
        binding.codesuDebloaterBatchProgress.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            Set<String> removed = removedSet(appContext);
            int ok = 0;
            int failed = 0;
            List<String> errors = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                DebloaterAppInfo target = targets.get(i);
                if (isProtected(target.packageName)) {
                    failed++;
                    if (errors.size() < 3) errors.add(target.label + ": protegida");
                } else {
                    String result = ShizukuPlusUtils.manageSystemApp(target.packageName, action);
                    if (result != null && result.startsWith("OK:")) {
                        ok++;
                        if (action == 2) removed.add(target.packageName);
                        if (action == 3) removed.remove(target.packageName);
                    } else {
                        failed++;
                        if (errors.size() < 3) errors.add(target.label + ": " + result);
                    }
                }
                final int done = i + 1;
                if (isAdded()) {
                    requireActivity().runOnUiThread(() -> {
                        if (binding != null) binding.codesuDebloaterBatchProgress.setProgress(done);
                    });
                }
            }
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putStringSet(KEY_REMOVED, removed).apply();
            final int okCount = ok;
            final int failedCount = failed;
            final List<String> errorCopy = new ArrayList<>(errors);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (binding == null) return;
                binding.codesuDebloaterBatchProgress.setVisibility(View.GONE);
                String summary = "Correctas: " + okCount + " · fallidas: " + failedCount;
                if (!errorCopy.isEmpty()) summary += "\n" + android.text.TextUtils.join("\n", errorCopy);
                Toast.makeText(requireContext(), summary, Toast.LENGTH_LONG).show();
                clearSelection();
                loadApps();
            });
        });
    }

    private void showAppInfo(DebloaterAppInfo app) {
        String state = app.removed ? "Retirada para este usuario"
                : app.isEnabledApp() ? "Activa" : "Desactivada";
        String message = "Paquete: " + app.packageName
                + "\nEstado: " + state
                + "\nNivel: " + DebloaterCatalog.label(app.category)
                + "\n\n" + app.description;
        new AlertDialog.Builder(requireContext())
                .setTitle(app.label)
                .setMessage(message)
                .setNegativeButton(app.removed ? "Restaurar"
                        : app.isEnabledApp() ? "Deshabilitar" : "Reactivar",
                        (dialog, which) -> confirmAction(app.removed ? 3
                                : app.isEnabledApp() ? 0 : 1, Collections.singletonList(app)))
                .setNeutralButton(app.removed ? "Reinstalar" : "Retirar",
                        (dialog, which) -> confirmAction(app.removed ? 3 : 2, Collections.singletonList(app)))
                .setPositiveButton("Cerrar", null)
                .show();
    }

    private void showBadgesInfo() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Niveles y seguridad")
                .setMessage("SISTEMA: app preinstalada.\n\n"
                        + "RECOMENDADA: pago o reemplazable por una alternativa.\n\n"
                        + "AVANZADA: puede quitar funciones secundarias del fabricante.\n\n"
                        + "EXPERTA: puede afectar funciones importantes. Revisa antes de aplicar.\n\n"
                        + "RETIRADA: desinstalada solo para este usuario; se puede restaurar.\n\n"
                        + "Las sugerencias son locales y orientativas. CodeHub no deshabilita nada automáticamente: "
                        + "cada acción se confirma y pasa por las protecciones del servicio Shizuku.")
                .setPositiveButton("Entendido", null)
                .show();
    }

    private Set<String> removedSet(Context context) {
        return new HashSet<>(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_REMOVED, Collections.emptySet()));
    }

    private boolean isProtected(String pkg) {
        if (pkg == null) return true;
        return pkg.equals("com.codehub.app") || pkg.equals("android")
                || pkg.equals("com.android.systemui") || pkg.equals("com.android.settings")
                || pkg.equals("com.android.shell") || pkg.equals("com.android.phone")
                || pkg.equals("com.android.bluetooth") || pkg.equals("com.android.nfc")
                || pkg.equals("com.android.media") || pkg.equals("com.android.sharedstoragebackup")
                || pkg.equals("com.android.permissioncontroller") || pkg.equals("com.android.packageinstaller")
                || pkg.equals("com.google.android.gms") || pkg.equals("com.google.android.gsf")
                || pkg.equals("com.google.android.vending") || pkg.equals("moe.shizuku.privileged.api")
                || pkg.equals("com.android.launcher3") || pkg.equals("com.sec.android.app.launcher")
                || pkg.equals("com.google.android.apps.nexuslauncher") || pkg.equals("com.miui.home")
                || pkg.equals("com.oppo.launcher") || pkg.equals("com.huawei.android.launcher")
                || pkg.equals("com.motorola.launcher3") || pkg.equals("com.google.android.inputmethod.latin")
                || pkg.equals("com.samsung.android.honeyboard")
                || pkg.startsWith("com.android.providers.") || pkg.startsWith("com.google.android.providers.")
                || pkg.startsWith("com.android.system.") || pkg.startsWith("com.google.android.webview")
                || pkg.startsWith("com.android.webview") || pkg.startsWith("com.google.android.trichromelibrary")
                || pkg.startsWith("com.android.launcher") || pkg.startsWith("com.google.android.inputmethod");
    }

    @Override
    public void onDestroyView() {
        binding = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
