package com.codehub.app;

import android.app.Activity;
import android.os.Build;
import android.view.View;

/**
 * Barras del sistema de Android (estado + navegación) consistentes en todas
 * las activities de CodeHub.
 *
 * Con targetSdk >= 35 (API 35/36) Android 15+ FUERZA el modo edge-to-edge:
 * el contenido se dibuja bajo la barra de estado y la barra de navegación,
 * por lo que la parte superior e inferior de cada pantalla quedan ocultas
 * tras los recortes del sistema. Aqui se opta de forma explicita por NO
 * dibujar bajo las barras (window.decorFitsSystemWindows = true) y se fija
 * el color oscuro de ambas barras en todo el rango soportado (minSdk 24+).
 */
public final class SystemBars {

    private SystemBars() {}

    public static void fit(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: devuelve el layout a "fits system windows" (los
            // sistema de ventanas excluyen las barras del area de contenido).
            // Es el mecanismo oficial para optar fuera del edge-to-edge
            // forzado por Android 15+ sin tener que consumir insets.
            activity.getWindow().setDecorFitsSystemWindows(true);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.getWindow().setStatusBarColor(0xFF080810);
            activity.getWindow().setNavigationBarColor(0xFF080810);
        }
    }
}