package com.codehub.app;

import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.content.pm.Signature;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
 * APK installation uses Shizuku UserService/AIDL. Reflection remains limited
 * to package removal until that operation is migrated separately.
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

    /** Preferred installer: UserService avoids reflection into Shizuku.newProcess. */
    public static boolean installSilently(Context context, String apkPath) throws Exception {
        if (!isReady()) throw new IllegalStateException("Shizuku no disponible o sin permiso");
        File apk = new File(apkPath);
        if (!apk.isFile() || apk.length() <= 0L) throw new IllegalArgumentException("El APK no existe o está vacío");
        return installThroughUserService(context, apk, null);
    }

    /** Removes one explicitly confirmed third-party package before a signature-changing reinstall. */
    public static boolean uninstallSilently(String packageName) throws Exception {
        if (!isReady()) throw new IllegalStateException("Shizuku no disponible o sin permiso");
        if (packageName == null || !packageName.matches("[A-Za-z0-9_.]+")
                || "com.codehub.app".equals(packageName)) {
            throw new IllegalArgumentException("Paquete no válido para desinstalación");
        }
        Process process = newProcessReflective(new String[]{"sh", "-c",
                "pm uninstall --user current " + packageName + " 2>&1"}, null, null);
        String output;
        try (InputStream stream = process.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
            output = bytes.toString("UTF-8").trim();
        }
        int code = process.waitFor();
        if (code != 0 || !output.toLowerCase(java.util.Locale.ROOT).contains("success")) {
            throw new Exception(output.isEmpty() ? "Android no permitió desinstalar la app." : output);
        }
        return true;
    }

    /** Self-update path with package, version and signing-certificate checks. */
    public static boolean installSilentlyAndRelaunch(Context context, String apkPath,
                                                       String activityComponent) throws Exception {
        if (!isReady()) throw new IllegalStateException("Shizuku no disponible o sin permiso");
        if (activityComponent == null || !activityComponent.matches("[A-Za-z0-9._]+/[A-Za-z0-9._]+")) {
            throw new IllegalArgumentException("Componente de inicio no válido");
        }
        File apk = new File(apkPath);
        verifyCodeHubUpdate(context, apk);
        return installThroughUserService(context, apk, activityComponent);
    }

    private static boolean installThroughUserService(Context context, File apk,
                                                       String relaunchComponent) throws Exception {
        final Context appContext = context.getApplicationContext();
        final Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                new ComponentName(appContext.getPackageName(), ShizukuApkInstallerUserService.class.getName()))
                .daemon(false)
                .processNameSuffix("apk-installer")
                .debuggable(false)
                .version(1)
                .tag("codehub-apk-installer");
        final CountDownLatch connected = new CountDownLatch(1);
        final AtomicReference<ICodeHubApkInstaller> remote = new AtomicReference<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                remote.set(ICodeHubApkInstaller.Stub.asInterface(binder));
                connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                remote.set(null);
                connected.countDown();
            }
        };

        boolean bound = false;
        ICodeHubApkInstaller installer = null;
        try {
            Shizuku.bindUserService(args, connection);
            bound = true;
            if (!connected.await(20L, TimeUnit.SECONDS) || remote.get() == null) {
                throw new IllegalStateException("Shizuku no inició el servicio de instalación en 20 segundos.");
            }
            installer = remote.get();
            long expectedSize = apk.length();
            installer.beginInstall(expectedSize);
            try (InputStream input = new FileInputStream(apk)) {
                byte[] buffer = new byte[256 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (!isBinderAlive()) throw new IllegalStateException("Se perdió la conexión con Shizuku durante la transferencia.");
                    byte[] chunk = count == buffer.length ? buffer.clone() : java.util.Arrays.copyOf(buffer, count);
                    installer.appendInstallChunk(chunk);
                }
            }
            String result = installer.commitInstall(relaunchComponent);
            if (result == null || !result.startsWith("OK:")) {
                throw new Exception(result == null ? "Shizuku no devolvió el resultado de Package Manager." : result.replaceFirst("^ERROR:", ""));
            }
            return true;
        } catch (Exception error) {
            if (installer != null) {
                try { installer.abortInstall(); } catch (Throwable ignored) { }
            }
            throw error;
        } finally {
            if (bound) {
                try { Shizuku.unbindUserService(args, connection, true); } catch (Throwable ignored) { }
            }
        }
    }

    private static void verifyCodeHubUpdate(Context context, File apk) throws Exception {
        if (!apk.isFile() || apk.length() <= 0L) throw new IllegalArgumentException("El APK no existe o está vacío");
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = context.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (candidate == null || !context.getPackageName().equals(candidate.packageName)) {
            throw new SecurityException("El APK no pertenece a CodeHub.");
        }
        PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), flags);
        long candidateCode = Build.VERSION.SDK_INT >= 28 ? candidate.getLongVersionCode() : candidate.versionCode;
        long installedCode = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
        if (candidateCode <= installedCode) throw new SecurityException("El APK no tiene un versionCode más reciente.");
        Signature[] candidateSigners = Build.VERSION.SDK_INT >= 28
                ? (candidate.signingInfo == null ? null : candidate.signingInfo.getApkContentsSigners())
                : candidate.signatures;
        Signature[] installedSigners = Build.VERSION.SDK_INT >= 28
                ? (installed.signingInfo == null ? null : installed.signingInfo.getApkContentsSigners())
                : installed.signatures;
        if (candidateSigners == null || installedSigners == null || candidateSigners.length == 0
                || !java.util.Arrays.equals(candidateSigners, installedSigners)) {
            throw new SecurityException("La firma del APK no coincide con la versión instalada de CodeHub.");
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.trim().isEmpty()) return "error desconocido";
        return message.length() > 240 ? message.substring(0, 240) : message;
    }

    /** Shizuku.newProcess is retained only for the existing uninstall operation. */
    private static Process newProcessReflective(String[] cmd, String[] env, String dir) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
        m.setAccessible(true);
        return (Process) m.invoke(null, cmd, env, dir);
    }
}
