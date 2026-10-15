package com.codehub.app;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

/**
 * Barras del sistema de Android (estado + navegación) consistentes en todas
 * las activities de CodeHub.
 *
 * No se usa modo pantalla completa. En Android 14 o menos el sistema reserva
 * el espacio de las barras (setDecorFitsSystemWindows). Desde Android 15 con
 * targetSdk 35+ el edge-to-edge es obligatorio y setDecorFitsSystemWindows(true)
 * se ignora, por eso {@link #applyInsets(Activity)} añade ese espacio a mano.
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

    /**
     * Llamar DESPUÉS de setContentView(). En Android 15+ (SDK 35+) la app se
     * dibuja detrás de la barra de estado y la de navegación; aquí se aplica
     * como padding el área de barras + notch + teclado para que el contenido
     * (WebView, banner, listas) quede entre ambas barras, igual que en
     * Android 14. En versiones anteriores no hace nada (ya funciona).
     */
    public static void applyInsets(Activity activity) {
        if (Build.VERSION.SDK_INT < 35) return;
        final View content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(
                WindowInsets.Type.systemBars()
                    | WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.ime());
            v.setPadding(i.left, i.top, i.right, i.bottom);
            // Consumido: el WebView no vuelve a aplicar env(safe-area-inset-*).
            return WindowInsets.CONSUMED;
        });
        content.requestApplyInsets();
    }
}
