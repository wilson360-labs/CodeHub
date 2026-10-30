package com.codehub.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native Open Source catalog for the Android app. The web catalog remains the source of truth. */
public class NativeStoreActivity extends Activity {
    static final String EXTRA_APK_URL = "native_store_apk_url";
    static final String EXTRA_APP_ID = "native_store_app_id";
    private static final String API = "https://codehub-98s6.onrender.com/api/apps";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<AppEntry> apps = new ArrayList<>();
    private LinearLayout list;
    private LinearLayout chips;
    private EditText search;
    private TextView status;
    private String category = "Todas";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        SystemBars.fit(this);
        buildScreen();
        loadCatalog();
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF080810);
        root.setPadding(dp(18), dp(12), dp(18), 0);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("Orion eStore", 26, 0xFFFFFFFF, true);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button refresh = new Button(this);
        refresh.setText("↻"); refresh.setTextSize(22); refresh.setAllCaps(false);
        refresh.setTextColor(0xFFFFFFFF); refresh.setBackgroundColor(0x00000000);
        refresh.setOnClickListener(v -> loadCatalog());
        top.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(top);
        root.addView(label("Apps open source verificadas por su repositorio", 13, 0xFF9AA0B8, false));

        search = new EditText(this);
        search.setSingleLine(true); search.setTextSize(15); search.setTextColor(0xFFFFFFFF);
        search.setHintTextColor(0xFF77798A); search.setHint("Buscar apps, categorías...");
        search.setPadding(dp(16), 0, dp(16), 0);
        search.setBackground(roundRect(0xFF151522, 0xFF272738, dp(14)));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, dp(52));
        searchLp.topMargin = dp(18); root.addView(search, searchLp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int count, int after) {}
            public void onTextChanged(CharSequence s, int st, int before, int count) { render(); }
            public void afterTextChanged(Editable e) {}
        });

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(this); chips.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(chips);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-1, dp(54));
        chipLp.topMargin = dp(8); root.addView(chipScroll, chipLp);

        status = label("Conectando al catálogo…", 13, 0xFF9AA0B8, false);
        status.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(34)));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); list.setPadding(0, dp(4), 0, dp(20));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        SystemBars.applyInsets(this);
    }

    private void loadCatalog() {
        status.setText("Actualizando catálogo…");
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(API).openConnection();
                conn.setConnectTimeout(12000); conn.setReadTimeout(20000);
                conn.setRequestProperty("Accept", "application/json");
                if (conn.getResponseCode() < 200 || conn.getResponseCode() >= 300) throw new Exception("HTTP " + conn.getResponseCode());
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                    String line; while ((line = reader.readLine()) != null) body.append(line);
                }
                String json = body.toString().trim();
                JSONArray rows;
                if (json.startsWith("[")) rows = new JSONArray(json);
                else rows = new JSONObject(json).optJSONArray("apps");
                List<AppEntry> loaded = new ArrayList<>();
                if (rows != null) for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null || row.optString("source_repo").isEmpty()) continue;
                    AppEntry app = AppEntry.from(row);
                    if (!app.id.isEmpty()) loaded.add(app);
                }
                Collections.sort(loaded, Comparator.comparing(a -> a.name.toLowerCase(Locale.ROOT)));
                runOnUiThread(() -> {
                    apps.clear(); apps.addAll(loaded);
                    if (!containsCategory(category)) category = "Todas";
                    renderChips(); render();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (apps.isEmpty()) status.setText("No se pudo conectar. Toca ↻ para reintentar.");
                    else status.setText("No se pudo actualizar · mostrando el catálogo anterior");
                });
            } finally { if (conn != null) conn.disconnect(); }
        });
    }

    private boolean containsCategory(String value) {
        for (AppEntry app : apps) if (app.category.equals(value)) return true;
        return "Todas".equals(value);
    }

    private void renderChips() {
        chips.removeAllViews();
        Set<String> categories = new LinkedHashSet<>(); categories.add("Todas");
        for (AppEntry app : apps) categories.add(app.category);
        for (String value : categories) {
            TextView chip = label(value, 13, category.equals(value) ? 0xFF080810 : 0xFFD7D7E4, true);
            chip.setGravity(Gravity.CENTER); chip.setPadding(dp(15), 0, dp(15), 0);
            chip.setBackground(roundRect(category.equals(value) ? 0xFF38EF7D : 0xFF151522, 0x00000000, dp(22)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36)); lp.rightMargin = dp(8); lp.topMargin = dp(8);
            chips.addView(chip, lp);
            chip.setOnClickListener(v -> { category = value; renderChips(); render(); });
        }
    }

    private void render() {
        if (list == null) return;
        list.removeAllViews();
        String query = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int count = 0;
        for (AppEntry app : apps) {
            if (!"Todas".equals(category) && !app.category.equals(category)) continue;
            String haystack = (app.name + " " + app.description + " " + app.category + " " + app.repo).toLowerCase(Locale.ROOT);
            if (!haystack.contains(query)) continue;
            list.addView(appCard(app)); count++;
        }
        status.setText(apps.isEmpty() ? status.getText() : count + " aplicaciones disponibles");
        if (count == 0 && !apps.isEmpty()) {
            TextView empty = label("No encontramos apps con esa búsqueda.", 14, 0xFF9AA0B8, false);
            empty.setGravity(Gravity.CENTER); list.addView(empty, new LinearLayout.LayoutParams(-1, dp(120)));
        }
    }

    private View appCard(AppEntry app) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL); card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(roundRect(0xFF12121E, 0xFF242435, dp(18)));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2); cardLp.bottomMargin = dp(10);

        TextView icon = label("◈", 25, 0xFF38EF7D, true); icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundRect(0xFF1D2C2A, 0x00000000, dp(14)));
        card.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL); info.setPadding(dp(12), 0, dp(8), 0);
        TextView name = label(app.name, 16, 0xFFFFFFFF, true); name.setMaxLines(1); name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView meta = label(app.category + (app.version.isEmpty() ? "" : " · " + app.version), 12, 0xFF38EF7D, false); meta.setMaxLines(1);
        TextView desc = label(app.description.isEmpty() ? app.repo : app.description, 12, 0xFF9AA0B8, false);
        desc.setMaxLines(2); desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(name); info.addView(meta); info.addView(desc);
        card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = label("›", 28, 0xFF9AA0B8, false); card.addView(arrow);
        card.setOnClickListener(v -> showDetails(app));
        card.setLayoutParams(cardLp);
        return card;
    }

    private void showDetails(AppEntry app) {
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(24), dp(8), dp(24), dp(8));
        TextView category = label(app.category.toUpperCase(Locale.ROOT), 12, 0xFF38EF7D, true);
        TextView title = label(app.name, 23, 0xFFFFFFFF, true); title.setPadding(0, dp(7), 0, dp(8));
        TextView detail = label(app.description.isEmpty() ? "Aplicación de código abierto." : app.description, 14, 0xFFD0D0DE, false);
        detail.setLineSpacing(dp(3), 1f);
        TextView meta = label("Versión " + (app.version.isEmpty() ? "disponible" : app.version) + "\n" + app.repo, 12, 0xFF9AA0B8, false);
        meta.setPadding(0, dp(12), 0, 0);
        content.addView(category); content.addView(title); content.addView(detail); content.addView(meta);
        AlertDialog dialog = new AlertDialog.Builder(this).setView(content)
            .setNegativeButton("Cerrar", null)
            .setNeutralButton("Ver código", null)
            .setPositiveButton("Instalar", null).create();
        dialog.setOnShowListener(v -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> startInstall(app));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(b -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/" + app.repo))); }
                catch (Exception ignored) { Toast.makeText(this, "No se pudo abrir GitHub", Toast.LENGTH_SHORT).show(); }
            });
        });
        dialog.show();
    }

    private void startInstall(AppEntry app) {
        try {
            String url = "https://codehub-98s6.onrender.com/api/apps/" + URLEncoder.encode(app.id, "UTF-8") + "/download";
            Intent result = new Intent(); result.putExtra(EXTRA_APK_URL, url); result.putExtra(EXTRA_APP_ID, app.id);
            setResult(RESULT_OK, result); finish();
        } catch (Exception e) { Toast.makeText(this, "No se pudo preparar la instalación", Toast.LENGTH_SHORT).show(); }
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable roundRect(int color, int stroke, int radius) {
        GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(radius);
        if (stroke != 0) bg.setStroke(dp(1), stroke);
        return bg;
    }

    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }

    private static final class AppEntry {
        String id, name, description, category, version, repo;
        static AppEntry from(JSONObject row) {
            AppEntry app = new AppEntry();
            app.id = row.optString("appId"); app.name = row.optString("nombre", app.id);
            app.description = row.optString("descripcion"); app.category = row.optString("categoria", "Utilidades");
            app.version = row.optString("version"); app.repo = row.optString("source_repo");
            return app;
        }
    }
}
