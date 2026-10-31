package com.codehub.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.Signature;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.security.MessageDigest;

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
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Runnable pendingTimeout;

    private static final Shizuku.OnRequestPermissionResultListener LISTENER =
        (requestCode, grantResult) -> {
            if (requestCode != PERMISSION_REQUEST_CODE) return;
            boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
            deliverPermissionResult(granted);
        };

    static {
        try { Shizuku.addRequestPermissionResultListener(LISTENER); } catch (Throwable ignored) {}
    }

    /** true si el servicio Shizuku está corriendo (app abierta al menos una vez y ADB/root activo). */
    public static boolean isBinderAlive() {
        try { return Shizuku.pingBinder(); } catch (Throwable t) { return false; }
    }

    public static boolean isManagerInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) { return false; }
        catch (Throwable ignored) { return false; }
    }

    public static Intent getManagerLaunchIntent(Context context) {
        try { return context.getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api"); }
        catch (Throwable ignored) { return null; }
    }

    /** Human-readable connection diagnostic for the store's installation flow. */
    public static String getInstallDiagnostic(Context context) {
        boolean manager = isManagerInstalled(context);
        boolean binder = isBinderAlive();
        String appIdentity = getAppIdentity(context);
        String state;
        if (!binder) {
            state = manager
                ? "Shizuku está instalado, pero su servicio no está conectado. Inícialo desde Shizuku y vuelve aquí."
                : "No se detectó Shizuku ni un servicio Sui activo. Instala Shizuku o inicia Sui para habilitar la instalación automática.";
        } else if (Shizuku.isPreV11()) state = "El servicio está conectado, pero su API es anterior a Shizuku 11 y no permite solicitar el permiso.";
        else if (!hasPermission()) state = "Servicio conectado · falta autorizar CodeHub en el diálogo de Shizuku.";
        else try {
            String mode = Shizuku.getUid() == 0 ? "root" : Shizuku.getUid() == 2000 ? "ADB / shell" : "UID " + Shizuku.getUid();
            state = "CodeHub está autorizado · servicio activo por " + mode + ".";
        } catch (Throwable ignored) { state = "CodeHub está autorizado · servicio activo."; }
        return appIdentity + "\n" + state;
    }

    private static String getAppIdentity(Context context) {
        String installer = "fuera de Play / origen no identificado";
        String fingerprint = "no disponible";
        try {
            PackageManager pm = context.getPackageManager();
            String source = Build.VERSION.SDK_INT >= 30
                ? pm.getInstallSourceInfo(context.getPackageName()).getInstallingPackageName()
                : pm.getInstallerPackageName(context.getPackageName());
            if ("com.android.vending".equals(source)) installer = "Google Play";
            else if (source != null && !source.isEmpty()) installer = source;
            int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo info = pm.getPackageInfo(context.getPackageName(), flags);
            Signature[] signers = Build.VERSION.SDK_INT >= 28
                ? info.signingInfo.getApkContentsSigners() : info.signatures;
            if (signers != null && signers.length > 0) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray());
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < Math.min(8, digest.length); i++) hex.append(String.format(java.util.Locale.ROOT, "%02X", digest[i]));
                fingerprint = hex.toString();
            }
        } catch (Throwable ignored) { }
        return "App " + context.getPackageName() + " · origen: " + installer + " · firma SHA-256: " + fingerprint;
    }

    /** true si además de estar activo, ya nos dio permiso explícito. */
    public static boolean hasPermission() {
        if (!isBinderAlive()) return false;
        try { return SystemCleaner.INSTANCE.isSelfGranted(); }
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
        if (pendingCallback != null) { cb.onResult(false); return; }
        pendingCallback = cb;
        pendingTimeout = () -> deliverPermissionResult(false);
        MAIN.postDelayed(pendingTimeout, 45_000L);
        Runnable request = () -> {
            try { Shizuku.requestPermission(PERMISSION_REQUEST_CODE); }
            catch (Throwable t) { deliverPermissionResult(false); }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) request.run(); else MAIN.post(request);
    }

    private static void deliverPermissionResult(boolean granted) {
        PermissionCallback callback = pendingCallback;
        pendingCallback = null;
        if (pendingTimeout != null) MAIN.removeCallbacks(pendingTimeout);
        pendingTimeout = null;
        if (callback != null) callback.onResult(granted);
    }

    /**
     * Ejecuta `pm install -r <apkPath>` con los privilegios de Shizuku.
     * Bloqueante — llamar siempre desde un background thread.
     * @return true si `pm install` reportó éxito.
     */
    public static boolean installSilently(String apkPath) throws Exception {
        if (!isReady()) throw new IllegalStateException("Shizuku no disponible o sin permiso");
        String safePath = apkPath.replace("\"", "\\\"");
        Process p = newProcessReflective(new String[]{"sh", "-c", "pm install -r \"" + safePath + "\""}, null, null);

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

    /**
     * Shizuku.newProcess(String[], String[], String) pasó de público a privado
     * en shizuku-api 13.1.x (sigue existiendo, solo cambió la visibilidad), así
     * que ya no se puede llamar directo o falla la compilación en release según
     * la versión que resuelva Gradle. Se invoca por reflexión como puente hasta
     * migrar a UserService + IPackageInstaller.
     */
    private static Process newProcessReflective(String[] cmd, String[] env, String dir) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, cmd, env, dir);
    }
}
