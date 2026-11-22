package com.codehub.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Atajos del icono: cuatro destinos elegidos por el usuario, sin repo estático. */
public final class HomeShortcuts {

    public static final int MAX = 4;
    private static final String PREFS = "codehub_store";
    private static final String KEY = "dynamic_shortcut_ids";
    private static final String DEFAULT_IDS = "wile,clima,catalogo,herramientas";
    private static final String BASE_URL = "https://wilson360-labs.vercel.app";

    private HomeShortcuts() {}

    public static final class Option {
        public final String id;
        public final String shortLabel;
        public final String longLabel;
        public final int icon;
        public final String url;
        public final String nativeTarget;

        Option(String id, String shortLabel, String longLabel, int icon,
               String url, String nativeTarget) {
            this.id = id;
            this.shortLabel = shortLabel;
            this.longLabel = longLabel;
            this.icon = icon;
            this.url = url;
            this.nativeTarget = nativeTarget;
        }
    }

    public static List<Option> options() {
        return Arrays.asList(
            new Option("wile", "JARVIS", "Hablar con JARVIS",
                R.drawable.ic_shortcut_chat, BASE_URL + "/index.html#open-to-work", null),
            new Option("clima", "Clima", "Ver el clima y el mapa",
                R.drawable.ic_shortcut_weather, null, "weather"),
            new Option("apps", "Mis apps", "Instaladas y actualizaciones",
                R.drawable.ic_shortcut_apps, null, "myapps"),
            new Option("catalogo", "Apps", "Catálogo Open Source",
                R.drawable.ic_shortcut_catalog, BASE_URL + "/opensource", null),
            new Option("herramientas", "Herramientas", "Herramientas web",
                R.drawable.ic_shortcut_tools, BASE_URL + "/tools", null),
            new Option("codebeat", "CodeBeat", "Música y extensiones",
                R.drawable.ic_shortcut_codebeat, null, "codebeat"),
            new Option("codestream", "CodeStream", "Videos, música y Shorts",
                R.drawable.ic_shortcut_codestream, null, "codestream"),
            new Option("codesu", "CodeSu", "Optimización Android con Shizuku",
                R.drawable.ic_shortcut_codesu, null, "codesu"),
            new Option("novedades", "Novedades", "Releases e issues de CodeHub",
                R.drawable.ic_shortcut_releases, null, "releases"),
            new Option("perfil", "Perfil", "Mi cuenta CodeHub",
                R.drawable.ic_shortcut_profile, null, "profile")
        );
    }

    public static List<Option> selected(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY, DEFAULT_IDS);
        Set<String> ids = new LinkedHashSet<>(Arrays.asList(raw.split(",")));
        List<Option> result = new ArrayList<>();
        for (Option option : options()) {
            if (ids.contains(option.id)) result.add(option);
            if (result.size() == MAX) break;
        }
        if (result.isEmpty()) {
            for (Option option : options()) {
                if (DEFAULT_IDS.contains(option.id)) result.add(option);
                if (result.size() == MAX) break;
            }
        }
        return result;
    }

    public static void save(Context context, List<Option> chosen) {
        StringBuilder raw = new StringBuilder();
        for (Option option : chosen) {
            if (raw.length() > 0) raw.append(',');
            raw.append(option.id);
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, raw.toString()).apply();
    }

    public static void apply(Context context) {
        try {
            List<ShortcutInfoCompat> infos = new ArrayList<>();
            for (Option option : selected(context)) {
                infos.add(new ShortcutInfoCompat.Builder(context, option.id)
                    .setShortLabel(option.shortLabel)
                    .setLongLabel(option.longLabel)
                    .setIcon(IconCompat.createWithResource(context, option.icon))
                    .setIntent(intentFor(context, option))
                    .build());
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, infos);
        } catch (Exception ignored) {
            // Launchers que no soportan atajos siguen abriendo CodeHub desde el icono.
        }
    }

    public static Intent intentFor(Context context, Option option) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (option.nativeTarget != null) intent.putExtra("open_native", option.nativeTarget);
        else intent.putExtra("open_url", option.url);
        return intent;
    }
}
