package com.codehub.app;

import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import rikka.shizuku.Shizuku;

/**
 * Instalación silenciosa de APKs (catálogo Open Source) vía Shizuku —
 * evita el diálogo nativo "¿Instalar esta app?" ejecutando `pm install`
 * con los privilegios que el usuario le concedió a Shizuku (ADB
 * inalámbrico o root), el mismo enfoque que usan Obtainium/Aurora Store
 * cuando no hay integración a nivel de sistema.
 *
 * Requisitos que CodeHub NO controla, solo detecta:
 *  - El usuario tiene la app Shizuku instalada (está en el catálogo
 *    Open Source como "os-shizuku").
 *  - El servicio Shizuku está activo (ADB inalámbrico emparejado, o
 *    root). Si no lo está, isBinderAlive() = false y se debe caer al
 *    flujo normal de instalación con diálogo.
 *
 * Nota técnica: usa Shizuku.newProcess, marcado deprecated desde
 * shizuku-api 13.1.1 en favor de un UserService por AIDL (más robusto,
 * soporta streams binarios). Se mantiene por simplicidad — sigue
 * funcionando en las versiones actuales del servicio Shizuku. Si en el
 * futuro deja de andar tras una actualización de Shizuku, migrar a
 * UserService + IPackageInstaller es el camino que recomienda Rikka.
 */
public class ShizukuInstaller {

    private static final int PERMISSION_REQUEST_CODE = 9001;

    public interface PermissionCallback {
        void onResult(boolean granted);
    }

    private static PermissionCallback pendingCallback;

    private static final Shizuku.OnRequestPermissionResultListener LISTENER =
        (requestCode, grantResult) -> {
            if (requestCode != PERMISSION_REQUEST_CODE) return;
            boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
            if (pendingCallback != null) {
                pendingCallback.onResult(granted);
                pendingCallback = null;
            }
        };

    static {
        try { Shizuku.addRequestPermissionResultListener(LISTENER); } catch (Throwable ignored) {}
    }

    /** true si el servicio Shizuku está corriendo (app abierta al menos una vez y ADB/root activo). */
    public static boolean isBinderAlive() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    /** true si además de estar activo, ya nos dio permiso explícito. */
    public static boolean hasPermission() {
        if (!isBinderAlive()) return false;
        try { return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (Throwable t) { return false; }
    }

    /** Listo para instalar en silencio sin pedir nada más al usuario. */
    public static boolean isReady() {
        return isBinderAlive() && hasPermission();
    }

    /** Dispara el diálogo nativo de Shizuku pidiendo permiso. Llama a cb cuando el usuario responde. */
    public static void requestPermission(PermissionCallback cb) {
        if (!isBinderAlive()) { cb.onResult(false); return; }
        if (hasPermission()) { cb.onResult(true); return; }
        pendingCallback = cb;
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
        } catch (Throwable t) {
            pendingCallback = null;
            cb.onResult(false);
        }
    }

    /**
     * Ejecuta `pm install -r <apkPath>` con los privilegios de Shizuku.
     * Bloqueante — llamar siempre desde un background thread.
     * @return true si `pm install` reportó éxito.
     */
    public static boolean installSilently(String apkPath) throws Exception {
        if (!isReady()) throw new IllegalStateException("Shizuku no disponible o sin permiso");
        String safePath = apkPath.replace("\"", "\\\"");
        Process p = Shizuku.newProcess(new String[]{"sh", "-c", "pm install -r \"" + safePath + "\""}, null, null);

        StringBuilder out = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) out.append(line).append('\n');
        }
        StringBuilder err = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
            String line;
            while ((line = br.readLine()) != null) err.append(line).append('\n');
        }
        int code = p.waitFor();
        String output = (out.toString() + err.toString()).trim();
        if (code != 0 || output.toLowerCase().contains("failure")) {
            throw new Exception(output.isEmpty() ? ("pm install exit=" + code) : output);
        }
        return true;
    }
}
