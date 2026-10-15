package com.codehub.app;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;

/**
 * Barras del sistema de Android (estado + navegación) consistentes en todas
 * las activities de CodeHub.
 *
 * No se usa modo pantalla completa: la ventana se adapta a la pantalla
 * normalmente, sin esconder la barra de notificaciones ni forzar edge-to-edge.
 */
public final class SystemBars {

    private SystemBars() {}

    public static void fit(Activity activity) {
        activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.getWindow().setDecorFitsSystemWindows(true);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            activity.getWindow().setStatusBarColor(0xFF080810);
            activity.getWindow().setNavigationBarColor(0xFF080810);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { activity.getWindow().setStatusBarContrastEnforced(true); } catch (Throwable ignored) {}
            try { activity.getWindow().setNavigationBarContrastEnforced(true); } catch (Throwable ignored) {}
        }
    }
}
