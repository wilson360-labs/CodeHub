package com.codehub.app

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class RunningApp(
    val packageName: String,
    val label: String,
    val importance: Int
)

data class KillResult(
    val stopped: List<String>,
    val failed: List<String>
)

/**
 * Núcleo de mantenimiento privilegiado vía Shizuku/Sui.
 *
 * Archivo Kotlin nuevo sobre el proyecto Java existente; reutiliza las
 * convenciones de ShizukuInstaller (patrón de permiso y newProcess por
 * reflexión) y añade dos operaciones de sistema que la API pública de
 * Android no permite:
 *
 *  - borrarCacheTurbo(): limpia la caché GLOBAL del sistema (equivalente
 *    a `pm trim-caches <tamaño>`), conectando por Binder al servicio
 *    'package' (PackageManagerService).
 *  - killAppsPro(list): force-stop masivo de paquetes (equivalente a
 *    `am force-stop`), conectando por Binder al servicio 'activity'
 *    (ActivityManagerService).
 *
 * Local-First: TODAS las operaciones son `suspend` y ejecutan en
 * Dispatchers.IO, nunca en el hilo principal → el WebView no se congela.
 */
object SystemCleaner {

    private const val USER_ID_CURRENT = 0
    private const val SKIP_ON_FORCE_STOP = "moe.shizuku.privileged.api"

    // ── Estado de Shizuku ───────────────────────────────────────────

    enum class Status { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, READY, DISCONNECTED }

    fun refreshStatus(): Status {
        return try {
            if (Shizuku.isPreV11()) Status.NOT_INSTALLED
            else if (!Shizuku.pingBinder()) Status.NOT_RUNNING
            else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                Status.NEEDS_PERMISSION
            else Status.READY
        } catch (t: Throwable) {
            Status.DISCONNECTED
        }
    }

    /** Privilegio real bajo el que corre Shizuku: 0 = root, 2000 = ADB. */
    fun backendDescription(): String = try {
        when (Shizuku.getUid()) {
            0 -> "root"
            2000 -> "ADB (shell)"
            else -> "uid ${Shizuku.getUid()}"
        }
    } catch (t: Throwable) { "desconocido" }

    /** Solicitud formal de permiso de Shizuku. Suspend hasta la respuesta. */
    suspend fun requestPermissionAsync(): Boolean = suspendCancellableCoroutine { cont ->
        try {
            if (!Shizuku.pingBinder()) { cont.resume(false); return@suspendCancellableCoroutine }
            pendingPermission = { granted -> if (cont.isActive) cont.resume(granted) }
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (t: Throwable) {
            cont.resume(false)
        }
    }

    /** Callback temporal que se entrega cuando Shizuku responde al diálogo. */
    var pendingPermission: ((Boolean) -> Unit)? = null

    /** Síncrono para CodeHubBridge: pide el permiso desde un Thread de fondo. */
    @JvmStatic
    fun requestPermissionAsyncCompat(): Boolean = runBlocking { requestPermissionAsync() }

    private const val PERMISSION_REQUEST_CODE = 9002

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == PERMISSION_REQUEST_CODE) {
            pendingPermission?.invoke(result == PackageManager.PERMISSION_GRANTED)
            pendingPermission = null
        }
    }

    init {
        // Un solo listener por proceso: ShizukuInstaller ya añade el suyo (9001).
        try { Shizuku.addRequestPermissionResultListener(permissionListener) } catch (t: Throwable) {}
    }

    // ── 1. borrarCacheTurbo ─────────────────────────────────────────

    /**
     * Función síncrona para ser llamada desde Java (CodeHubBridge) dentro
     * de un Thread/Executor de fondo. Local-First: el WebView nunca se bloquea.
     */
    @JvmStatic
    fun trimCacheSync(targetFreeBytes: Long): String = runBlocking { borrarCacheTurbo(targetFreeBytes) }

    /**
     * Limpia la caché global del sistema (equivalente a `pm trim-caches`).
     * @param targetFreeBytes bytes "libres" que se piden al sistema.
     * @return mensaje legible del resultado.
     */
    suspend fun borrarCacheTurbo(targetFreeBytes: Long = 512L * 1024 * 1024): String {
        return withContext(Dispatchers.IO) {
            requireReady()
            try {
                // Camino principal: Binder → IPackageManager.trimCaches(long, String)
                trimCachesViaBinder(targetFreeBytes)
                "OK · caché global recortada (meta ≥ ${fmt(targetFreeBytes)})"
            } catch (eBinder: Throwable) {
                // Fallback: mismo comando que `pm trim-caches <tamaño>` shell.
                try {
                    trimCachesViaShell(targetFreeBytes)
                    "OK · trim-caches vía shell (${fmt(targetFreeBytes)})"
                } catch (eShell: Throwable) {
                    "ERROR · $eBinder → shell: ${eShell.message}"
                }
            }
        }
    }

    private fun trimCachesViaBinder(targetFreeBytes: Long) {
        val pkgBinder = SystemServiceHelper.getSystemService("package")
            ?: throw IllegalStateException("Servicio 'package' no disponible")
        val pm = hiddenProxy("android.content.pm.IPackageManager", pkgBinder)
        val trimCaches = pm.javaClass.getMethod("trimCaches", Long::class.javaPrimitiveType, String::class.java)
        trimCaches.invoke(pm, targetFreeBytes, null)
    }

    private fun trimCachesViaShell(targetFreeBytes: Long) {
        runShell("pm", "trim-caches", targetFreeBytes.toString())
    }

    // ── 2. killAppsPro ──────────────────────────────────────────────

    /**
     * Lista de procesos corriendo (API pública de ActivityManager).
     * Se pide QUERY_ALL_PACKAGES en el manifest para etiquetas completas.
     */
    suspend fun findRunningApps(context: Context): List<RunningApp> {
        return withContext(Dispatchers.IO) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pm = context.packageManager
            val apps = arrayListOf<RunningApp>()
            try {
                am.runningAppProcesses?.forEach { info ->
                    val importance = info.importance
                    // Cachados (segundo plano profundo) son lo que el usuario
                    // quiere matar; también mostramos services/surviving.
                    if (importance > ActivityManager.IMPORTANCE_FOREGROUND ||
                        importance == ActivityManager.IMPORTANCE_SERVICE) {
                        info.pkgList.forEach { pkg ->
                            if (pkg != SKIP_ON_FORCE_STOP && !pkg.startsWith("com.android.")) {
                                val label = try {
                                    val aInfo = pm.getApplicationInfo(pkg, 0)
                                    pm.getApplicationLabel(aInfo).toString()
                                } catch (t: Throwable) { pkg }
                                apps.add(RunningApp(pkg, label, importance))
                            }
                        }
                    }
                }
            } catch (t: Throwable) { /* sin procesos visibles */ }
            // Unicidad por paquete (un proceso puede listar varios packages)
            apps.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
        }
    }

    /**
     * force-stop masivo de los paquetes seleccionados.
     * @return Stop listas. Cada paquete: Binder forceStopPackage, fallback `am force-stop`.
     */
    suspend fun killAppsPro(packages: List<String>): KillResult {
        return withContext(Dispatchers.IO) {
            requireReady()
            val stopped = arrayListOf<String>()
            val failed = arrayListOf<String>()
            for (pkg in packages) {
                try {
                    forceStopViaBinder(pkg)
                    stopped += pkg
                } catch (eBinder: Throwable) {
                    try {
                        forceStopViaShell(pkg)
                        stopped += pkg
                    } catch (eShell: Throwable) {
                        failed += pkg
                    }
                }
            }
            KillResult(stopped, failed)
        }
    }

    /** Síncrono para CodeHubBridge (Thread de fondo). Devuelve JSON a JS. */
    @JvmStatic
    fun killAppsSync(packages: List<String>): String {
        val r = runBlocking { killAppsPro(packages) }
        val stopped = r.stopped.joinToString(",", "[", "]") { quoteJs(it) }
        val failed = r.failed.joinToString(",", "[", "]") { quoteJs(it) }
        return "{\"stopped\":$stopped,\"failed\":$failed}"
    }

    /** Carga de procesos para el panel; síncrono para Java. */
    @JvmStatic
    fun runningAppsSync(context: Context): String {
        val apps = runBlocking { findRunningApps(context) }
        return apps.joinToString(",", "[", "]") {
            "{\"pkg\":${quoteJs(it.packageName)},\"label\":${quoteJs(it.label)},\"imp\":${it.importance}}"
        }
    }

    private fun quoteJs(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun forceStopViaBinder(pkg: String) {
        val amBinder = SystemServiceHelper.getSystemService("activity")
            ?: throw IllegalStateException("Servicio 'activity' no disponible")
        val am = hiddenProxy("android.app.IActivityManager", amBinder)
        val forceStop = am.javaClass.getMethod(
            "forceStopPackage", String::class.java, Int::class.javaPrimitiveType
        )
        forceStop.invoke(am, pkg, USER_ID_CURRENT)
    }

    private fun forceStopViaShell(pkg: String) {
        runShell("am", "force-stop", pkg)
    }

    // ── Primitivas interna ──────────────────────────────────────────

    private fun hiddenProxy(interfaceName: String, binder: IBinder): Any {
        val stub = Class.forName("$interfaceName\$Stub")
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        return asInterface.invoke(null, ShizukuBinderWrapper(binder))
            ?: throw IllegalStateException("asInterface($interfaceName) → null")
    }

    private fun requireReady() {
        when (refreshStatus()) {
            Status.READY -> Unit
            Status.NOT_RUNNING -> throw IllegalStateException(
                "Shizuku/Sui no está corriendo. Actívalo con ADB inalámbrico o root."
            )
            Status.NEEDS_PERMISSION -> throw SecurityException(
                "Sin permiso de Shizuku. Concede el permiso antes de usar esta herramienta."
            )
            else -> throw IllegalStateException("Shizuku no está disponible.")
        }
    }

    /**
     * Ejecuta un comando con identidad shell/root mediante Shizuku.newProcess
     * (reflexión, visibilidad privada desde shizuku-api 13.1.x — mismo puente
     * que usa ShizukuInstaller).
     */
    private fun runShell(vararg cmd: String): String {
        val m: Method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val process = m.invoke(null, cmd, null, null) as Process

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val out = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) out.appendLine(line)
        process.waitFor()
        reader.close()
        val full = out.toString().trim()
        if (process.exitValue() != 0 && !full.contains("Success")) {
            throw IllegalStateException("exit=${process.exitValue()}: $full")
        }
        return full
    }

    private fun fmt(bytes: Long): String {
        val mb = bytes / (1024 * 1024)
        return when {
            mb >= 1024 -> "${mb / 1024}GB"
            mb > 0 -> "${mb}MB"
            else -> "${bytes}B"
        }
    }
}