package com.codehub.app.codesu.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lista blanca de juegos para CodeEngine.
 *
 * <p>Patrón tomado de Uperf Game Turbo ({@code magisk/config/perapp_powermode.txt}):
 * solo los paquetes listados reciben perfil de rendimiento y solo ellos se
 * excluyen del cierre en segundo plano. A diferencia de Uperf no se tocan
 * CPU/GPU ni sysfs: aquí la lista decide únicamente qué app dispara el perfil
 * y cuál debe sobrevivir al cierre con Shizuku.</p>
 */
public final class GameList {

    public static final String PREFS = "settings";
    public static final String PREF_GAMES = "codeEngineGames";
    public static final String PREF_SEEDED = "codeEngineGamesSeeded";

    /** Juegos semilla: lista de Uperf + Free Fire (paquetes globales e indios). */
    private static final String[] DEFAULT_GAMES = {
            "com.dts.freefireth",
            "com.dts.freefiremax",
            "com.dts.freefire",
            "com.tencent.ig",
            "com.tencent.tmgp.pubgmhd",
            "com.tencent.tmgp.sgame",
            "com.tencent.lolm",
            "com.tencent.tmgp.speedmobile",
            "com.tencent.KiHan",
            "com.miHoYo.Yuanshen",
            "com.miHoYo.bh3.bilibili",
            "com.miHoYo.enterprise.NGHSoD",
            "com.miHoYo.ys.bilibili",
            "com.netease.wotb",
            "com.garena.game.kgtw",
            "com.supercell.brawlstars",
            "com.supercell.clashofclans",
            "com.supercell.clashroyale",
            "com.mobile.legends",
            "com.activision.callofduty.shooter",
            "fi.twomenandadog.zombiecatchers"
    };

    private GameList() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Siembra la lista una sola vez; respeta lo que el usuario haya editado después. */
    public static Set<String> games(Context context) {
        if (context == null) return Collections.emptySet();
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(PREF_SEEDED, false)) {
            Set<String> seed = new LinkedHashSet<>(Arrays.asList(DEFAULT_GAMES));
            prefs.edit().putStringSet(PREF_GAMES, seed).putBoolean(PREF_SEEDED, true).apply();
            return seed;
        }
        Set<String> stored = prefs.getStringSet(PREF_GAMES, null);
        return stored == null ? Collections.emptySet() : new LinkedHashSet<>(stored);
    }

    public static boolean isGame(Context context, String packageName) {
        if (context == null || packageName == null || packageName.isEmpty()) return false;
        return games(context).contains(packageName);
    }

    public static boolean add(Context context, String packageName) {
        if (context == null || !validPackage(packageName)) return false;
        Set<String> current = new LinkedHashSet<>(games(context));
        boolean changed = current.add(packageName.trim());
        prefs(context).edit().putStringSet(PREF_GAMES, current)
                .putBoolean(PREF_SEEDED, true).apply();
        return changed;
    }

    public static boolean remove(Context context, String packageName) {
        if (context == null || packageName == null) return false;
        Set<String> current = new LinkedHashSet<>(games(context));
        boolean changed = current.remove(packageName);
        prefs(context).edit().putStringSet(PREF_GAMES, current)
                .putBoolean(PREF_SEEDED, true).apply();
        return changed;
    }

    public static void reset(Context context) {
        if (context == null) return;
        Set<String> seed = new LinkedHashSet<>(Arrays.asList(DEFAULT_GAMES));
        prefs(context).edit().putStringSet(PREF_GAMES, seed)
                .putBoolean(PREF_SEEDED, true).apply();
    }

    public static List<String> sorted(Context context) {
        List<String> list = new ArrayList<>(games(context));
        Collections.sort(list);
        return list;
    }

    public static boolean validPackage(String packageName) {
        if (packageName == null) return false;
        String clean = packageName.trim();
        return clean.length() >= 4 && clean.matches("[A-Za-z0-9._]+")
                && clean.contains(".") && clean.indexOf('.') > 0
                && !clean.endsWith(".");
    }
}
