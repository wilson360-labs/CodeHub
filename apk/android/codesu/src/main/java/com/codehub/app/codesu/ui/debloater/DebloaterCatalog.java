package com.codehub.app.codesu.ui.debloater;

import java.util.Locale;

/**
 * Local, offline suggestions only. Nothing is removed automatically: the user
 * still confirms every action and all writes go through the protected Shizuku service.
 */
public final class DebloaterCatalog {
    public static final String RECOMMENDED = "recommended";
    public static final String ADVANCED = "advanced";
    public static final String EXPERT = "expert";
    public static final String UNCLASSIFIED = "unclassified";

    private static final String[][] SUGGESTIONS = {
            {RECOMMENDED, "com.samsung.android.app.spage", "Samsung Free / pagina de noticias."},
            {RECOMMENDED, "com.samsung.android.bixby.agent", "Asistente Bixby."},
            {RECOMMENDED, "com.samsung.android.bixby.service", "Servicio de Bixby."},
            {RECOMMENDED, "com.samsung.android.bixbyvision.framework", "Vision de Bixby."},
            {RECOMMENDED, "com.samsung.android.app.samsungapps", "Tienda Samsung; se puede reinstalar."},
            {RECOMMENDED, "com.miui.msa.global", "Servicios de anuncios de MIUI."},
            {RECOMMENDED, "com.miui.systemAdSolution", "Solucion de anuncios del sistema MIUI."},
            {RECOMMENDED, "com.miui.analytics", "Analitica del sistema MIUI."},
            {RECOMMENDED, "com.xiaomi.ad", "Anuncios de Xiaomi."},
            {RECOMMENDED, "com.heytap.ad", "Anuncios de HelloTap/Oppo."},
            {RECOMMENDED, "com.coloros.ad", "Anuncios de ColorOS."},
            {RECOMMENDED, "com.oppo.ad", "Anuncios de Oppo."},
            {RECOMMENDED, "com.facebook.system", "Servicios de Facebook preinstalados."},
            {RECOMMENDED, "com.facebook.appmanager", "Administrador de apps de Facebook."},
            {RECOMMENDED, "com.google.android.apps.magazines", "Google News preinstalado."},

            {ADVANCED, "com.samsung.android.game.gamehome", "Samsung Game Launcher."},
            {ADVANCED, "com.samsung.android.gametuner.thin", "Samsung Game Tuner."},
            {ADVANCED, "com.samsung.android.email.provider", "Cliente de correo de Samsung."},
            {ADVANCED, "com.google.android.apps.tachyon", "Google Duo / Meet preinstalado."},
            {ADVANCED, "com.google.android.googlequicksearchbox", "Busqueda de Google y Asistente."},
            {ADVANCED, "com.google.android.apps.maps", "Google Maps preinstalado."},
            {ADVANCED, "com.google.android.videos", "Google TV / Peliculas."},
            {ADVANCED, "com.google.android.music", "Google Play Musica."},
            {ADVANCED, "com.android.printspooler", "Servicio de impresion del sistema."},

            {EXPERT, "com.samsung.android.samsungpass", "Autenticacion biometrica de Samsung."},
            {EXPERT, "com.samsung.android.knox", "Componentes Knox de Samsung."},
            {EXPERT, "com.samsung.android.securitylogagent", "Registro de seguridad de Samsung."},
            {EXPERT, "com.miui.securitycenter", "Centro de seguridad de MIUI."},
            {EXPERT, "com.google.android.gms", "Servicios de Google Play."},
            {EXPERT, "com.google.android.gsf", "Google Services Framework."}
    };

    private DebloaterCatalog() { }

    public static String categoryFor(String packageName) {
        if (packageName == null) return UNCLASSIFIED;
        String pkg = packageName.toLowerCase(Locale.ROOT);
        for (String[] entry : SUGGESTIONS) {
            if (pkg.equals(entry[1]) || pkg.startsWith(entry[1] + ".")) return entry[0];
        }
        if (pkg.contains(".bixby") || pkg.contains("systemad") || pkg.contains("adservice")
                || pkg.contains(".analytics") || pkg.contains(".ads."))
            return RECOMMENDED;
        return UNCLASSIFIED;
    }

    public static String descriptionFor(String packageName) {
        if (packageName == null) return "";
        String pkg = packageName.toLowerCase(Locale.ROOT);
        for (String[] entry : SUGGESTIONS) {
            if (pkg.equals(entry[1]) || pkg.startsWith(entry[1] + ".")) return entry[2];
        }
        if (pkg.contains(".bixby")) return "Componente de asistente del fabricante.";
        if (pkg.contains("systemad") || pkg.contains("adservice") || pkg.contains(".ads."))
            return "Componente relacionado con anuncios del sistema.";
        if (pkg.contains(".analytics")) return "Componente de analitica del fabricante.";
        return "App del sistema sin clasificacion local. Revisa antes de deshabilitarla.";
    }

    public static String label(String category) {
        if (RECOMMENDED.equals(category)) return "RECOMENDADA";
        if (ADVANCED.equals(category)) return "AVANZADA";
        if (EXPERT.equals(category)) return "EXPERTA";
        return "SIN CLASIFICAR";
    }
}
