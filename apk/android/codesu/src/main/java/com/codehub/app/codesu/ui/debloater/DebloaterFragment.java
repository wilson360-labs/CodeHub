package com.codehub.app.codesu.ui.debloater;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import com.codehub.app.codesu.R;
import com.codehub.app.codesu.util.ShizukuPlusUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

/** Safely disable or restore system apps for the current Android user via Shizuku. */
public final class DebloaterFragment extends Fragment {
    private static final String PREFS = "codesu_debloater";
    private static final String KEY_REMOVED = "removed_system_packages";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout rows;
    private TextView status;
    private int filterMode;

    @Nullable @Override
    public android.view.View onCreateView(@NonNull android.view.LayoutInflater inflater,
            @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Context context = requireContext();
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(8));
        root.setBackgroundColor(ContextCompat.getColor(context, R.color.codesu_background));
        TextView eyebrow = text("CODESU · SISTEMA", 12, true);
        eyebrow.setTextColor(ContextCompat.getColor(context, R.color.codesu_accent_text));
        root.addView(eyebrow);
        TextView title = text("Debloater", 24, true);
        title.setTextColor(ContextCompat.getColor(context, R.color.codesu_text_primary));
        root.addView(title);
        TextView description = text("Deshabilita apps de fábrica para este usuario y reactívalas cuando las necesites. No se elimina ningún APK.", 13, false);
        description.setTextColor(ContextCompat.getColor(context, R.color.codesu_text_secondary));
        root.addView(description, matchWrap());

        LinearLayout controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        Button refresh = new Button(context);
        refresh.setText("Actualizar");
        refresh.setOnClickListener(v -> loadApps());
        Button filter = new Button(context);
        filter.setText("Ver deshabilitadas");
        filter.setOnClickListener(v -> {
            filterMode = (filterMode + 1) % 3;
            filter.setText(filterMode == 0 ? "Ver deshabilitadas"
                    : filterMode == 1 ? "Ver retiradas" : "Ver activas");
            loadApps();
        });
        controls.addView(refresh, new LinearLayout.LayoutParams(0, dp(48), 1));
        controls.addView(filter, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(controls);
        status = text("Conecta Shizuku y concede permiso a CodeHub para cargar la lista.", 12, false);
        status.setTextColor(ContextCompat.getColor(context, R.color.codesu_text_secondary));
        root.addView(status, matchWrap());
        ScrollView scroll = new ScrollView(context);
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (hasShizuku()) loadApps();
        return root;
    }

    private boolean hasShizuku() {
        try { return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (RuntimeException e) { return false; }
    }

    private void loadApps() {
        if (rows == null) return;
        if (!hasShizuku()) {
            status.setText("Shizuku está desconectado o sin permiso. Autoriza CodeHub desde Settings de CodeSu.");
            rows.removeAllViews();
            return;
        }
        status.setText("Cargando apps del sistema…");
        Context context = requireContext().getApplicationContext();
        executor.execute(() -> {
            PackageManager pm = context.getPackageManager();
            List<ApplicationInfo> result = new ArrayList<>();
            if (filterMode == 2) {
                java.util.Set<String> removed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getStringSet(KEY_REMOVED, java.util.Collections.emptySet());
                for (String pkg : removed) {
                    try {
                        ApplicationInfo app = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
                        if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && !isProtected(pkg)) result.add(app);
                    } catch (PackageManager.NameNotFoundException ignored) { }
                }
            } else {
                List<ApplicationInfo> installed = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS);
                for (ApplicationInfo app : installed) {
                    if ((app.flags & ApplicationInfo.FLAG_SYSTEM) == 0 || isProtected(app.packageName)) continue;
                    if (filterMode == 1 ? !app.enabled : app.enabled) result.add(app);
                }
            }
            Collections.sort(result, (left, right) -> String.CASE_INSENSITIVE_ORDER.compare(
                    String.valueOf(pm.getApplicationLabel(left)), String.valueOf(pm.getApplicationLabel(right))));
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> renderApps(result));
        });
    }

    private void renderApps(List<ApplicationInfo> apps) {
        if (rows == null) return;
        rows.removeAllViews();
        status.setText(apps.size() + (filterMode == 1 ? " apps deshabilitadas"
                : filterMode == 2 ? " apps retiradas para este usuario" : " apps activas")
                + " · acciones reversibles para este usuario");
        PackageManager pm = requireContext().getPackageManager();
        for (ApplicationInfo app : apps) {
            LinearLayout card = new LinearLayout(requireContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(10), dp(14), dp(10));
            card.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.codesu_surface));
            LinearLayout.LayoutParams cardParams = matchWrap();
            cardParams.bottomMargin = dp(8);
            rows.addView(card, cardParams);
            TextView name = text(String.valueOf(pm.getApplicationLabel(app)), 15, true);
            name.setTextColor(ContextCompat.getColor(requireContext(), R.color.codesu_text_primary));
            card.addView(name);
            TextView packageName = text(app.packageName + (app.enabled ? " · activa" : " · deshabilitada"), 11, false);
            packageName.setTextColor(ContextCompat.getColor(requireContext(), R.color.codesu_text_secondary));
            card.addView(packageName);
            LinearLayout actions = new LinearLayout(requireContext());
            Button stateAction = new Button(requireContext());
            stateAction.setText(filterMode == 2 ? "Restaurar" : app.enabled ? "Deshabilitar" : "Reactivar");
            actions.addView(stateAction, new LinearLayout.LayoutParams(0, dp(48), 1));
            stateAction.setOnClickListener(v -> {
                if (filterMode == 2) confirmRestore(app);
                else confirm(app, !app.enabled);
            });
            if (filterMode != 2) {
                Button remove = new Button(requireContext());
                remove.setText("Retirar");
                actions.addView(remove, new LinearLayout.LayoutParams(0, dp(48), 1));
                remove.setOnClickListener(v -> confirmRemove(app));
            }
            card.addView(actions, matchWrap());
        }
        if (apps.isEmpty()) status.setText(filterMode == 1 ? "No hay apps deshabilitadas."
                : filterMode == 2 ? "No hay apps retiradas para este usuario."
                : "No hay apps del sistema disponibles para mostrar.");
    }

    private void confirm(ApplicationInfo app, boolean enable) {
        String name = String.valueOf(requireContext().getPackageManager().getApplicationLabel(app));
        new AlertDialog.Builder(requireContext())
                .setTitle(enable ? "Reactivar app" : "Deshabilitar app")
                .setMessage(enable ? "Se volverá a habilitar " + name + " para este usuario."
                        : "Se deshabilitará " + name + " para este usuario. El APK permanece instalado. Algunas funciones del fabricante pueden dejar de estar disponibles.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton(enable ? "Reactivar" : "Deshabilitar", (dialog, which) -> changeState(app, enable ? 1 : 0))
                .show();
    }

    private void confirmRemove(ApplicationInfo app) {
        String name = String.valueOf(requireContext().getPackageManager().getApplicationLabel(app));
        new AlertDialog.Builder(requireContext()).setTitle("Retirar del usuario")
                .setMessage("Se desinstalará " + name + " solo para este usuario. El paquete del sistema permanece en el dispositivo y podrás restaurarlo desde ‘Apps retiradas’. Algunas funciones del fabricante pueden dejar de estar disponibles.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Retirar", (dialog, which) -> changeState(app, 2)).show();
    }

    private void confirmRestore(ApplicationInfo app) {
        String name = String.valueOf(requireContext().getPackageManager().getApplicationLabel(app));
        new AlertDialog.Builder(requireContext()).setTitle("Restaurar app")
                .setMessage("Se volverá a instalar " + name + " para este usuario.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Restaurar", (dialog, which) -> changeState(app, 3)).show();
    }

    private void changeState(ApplicationInfo app, int action) {
        status.setText(action == 1 || action == 3 ? "Restaurando…" : action == 2 ? "Retirando…" : "Deshabilitando…");
        executor.execute(() -> {
            String result = ShizukuPlusUtils.manageSystemApp(app.packageName, action);
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (result != null && result.startsWith("OK:")) {
                    java.util.Set<String> removed = new java.util.HashSet<>(requireContext()
                            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .getStringSet(KEY_REMOVED, java.util.Collections.emptySet()));
                    if (action == 2) removed.add(app.packageName);
                    if (action == 3) removed.remove(app.packageName);
                    requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putStringSet(KEY_REMOVED, removed).apply();
                }
                android.widget.Toast.makeText(requireContext(), result, android.widget.Toast.LENGTH_LONG).show();
                loadApps();
            });
        });
    }

    private boolean isProtected(String pkg) {
        return pkg.equals("com.codehub.app") || pkg.equals("android")
                || pkg.equals("com.android.systemui") || pkg.equals("com.android.settings")
                || pkg.equals("com.android.shell") || pkg.equals("com.android.phone")
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

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(requireContext());
        view.setText(value);
        view.setTextSize(sp);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    @Override public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
