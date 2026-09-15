package com.codehub.app

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
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

/** Paquete con su memoria residente estimada (RSS en KB, sumada por UID→pkg). */
data class PkgMem(
    val packageName: String,
    val rssKb: Long
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

    /** Timeout por comando shell (10s) — evita colgar el WebView con procesos vivos. */
    private const val SHELL_TIMEOUT_MS = 10_000L

    /** Timeout del diálogo de permiso de Shizuku — evita colgar al llamante si el usuario no responde. */
    private const val PERMISSION_TIMEOUT_MS = 30_000L

    /** Primer UID de aplicaciones de usuario (sin contar servicios de sistema). */
    private const val APP_UID_MIN = 10_000

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

    /**
     * Solicitud formal de permiso de Shizuku. Suspend hasta la respuesta.
     *
     * Shizuku.requestPermission() DEBE correrse en el hilo principal (como
     * una requestPermissions normal de Android) o el diálogo no aparece y el
     * callback nunca llega → el WebView se quedaba "esperando confirmación"
     * para siempre. Aquí se publica al main looper y se espera la respuesta
     * con timeout duro (30s) para no congelar nunca al llamante.
     */
    suspend fun requestPermissionAsync(): Boolean = suspendCancellableCoroutine { cont ->
        try {
            if (!Shizuku.pingBinder()) { cont.resume(false); return@suspendCancellableCoroutine }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                cont.resume(true); return@suspendCancellableCoroutine
            }

            pendingPermission = { granted ->
                if (cont.isActive) { cont.resume(granted) }
            }

            Handler(Looper.getMainLooper()).post {
                try {
                    Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                } catch (t: Throwable) {
                    pendingPermission = null
                    if (cont.isActive) { cont.resume(false) }
                }
            }
            // Timeout: si el usuario nunca responde el diálogo (o el permiso ya
            // fue dado/revocado fuera), no dejar colgado al hilo del WebView.
            val watchdog = Runnable { pendingPermission = null; if (cont.isActive) { cont.resume(false) } }
            Handler(Looper.getMainLooper()).postDelayed(watchdog, PERMISSION_TIMEOUT_MS)
            cont.invokeOnCancellation { pendingPermission = null }
        } catch (t: Throwable) {
            pendingPermission = null
            if (cont.isActive) { cont.resume(false) }
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
        ensureBinderListeners()
    }

    // ── Estado en vivo para el WebView ──────────────────────────────

    /** Suscriptor al que se empuja el estado JSON cada vez que cambia el binder. */
    @Volatile private var statusPush: ((String) -> Unit)? = null
    @Volatile private var binderEventsOn = false

    /** El WebView deja su callback global para recibir el estado en vivo
     *  (se dispara al abrirse Shizuku, morir el binder o cambiar el permiso). */
    fun setStatusPushListener(cb: ((String) -> Unit)?) {
        statusPush = cb
        ensureBinderListeners()
    }

    /**
     * JSON rico de estado, para que el panel sepa separar "no instalado",
     * "Shizuku apagado", "sin permiso" y "listo con root/ADB".
     */
    fun statusJson(): String {
        var installed = false
        var running = false
        var granted = false
        var uid = -1
        try { installed = !Shizuku.isPreV11() } catch (_: Throwable) {}
        try { running = Shizuku.pingBinder() } catch (_: Throwable) {}
        if (running) {
            try { granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) {}
            try { uid = Shizuku.getUid() } catch (_: Throwable) {}
        }
        val status = refreshStatus()
        val backend = backendDescription()
        return "{\"status\":${quoteJs(status.name)},\"installed\":$installed,\"running\":$running," +
            "\"granted\":$granted,\"uid\":$uid,\"root\":${uid == 0}," +
            "\"backend\":${quoteJs(backend)}}"
    }

    private fun pushStatusNow() {
        try { statusPush?.invoke(statusJson()) } catch (_: Throwable) {}
    }

    /** Registra los listeners de binder una sola vez (idempotente). */
    private fun ensureBinderListeners() {
        if (binderEventsOn) return
        synchronized(this) {
            if (binderEventsOn) return
            binderEventsOn = true
            try { Shizuku.addBinderReceivedListener { pushStatusNow() } } catch (_: Throwable) {}
            try { Shizuku.addBinderDeadListener { pushStatusNow() } } catch (_: Throwable) {}
        }
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
     *
     * NOTA: `ActivityManager.runningAppProcesses` en Android 11+ solo expone
     * los procesos del propio llamante salvo que la app sea device-owner, así
     * que esta lista es un subconjunto conservador. Para el inventario real
     * (todas las apps de usuario) usar processesByMemory().
     */
    suspend fun findRunningApps(context: Context): List<RunningApp> {
        return withContext(Dispatchers.IO) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pm = context.packageManager
            val apps = arrayListOf<RunningApp>()
            try {
                am.runningAppProcesses?.forEach { info ->
                    val importance = info.importance
                    val foregroundImportance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                    val serviceImportance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE
                    // Cachados (segundo plano profundo) son lo que el usuario
                    // quiere matar; también mostramos services/surviving.
                    if (importance > foregroundImportance ||
                        importance == serviceImportance) {
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

    /** Paquetes vivos con RSS para el panel Procesos; síncrono para Java. */
    @JvmStatic
    fun processesByMemorySync(): String {
        val list = runBlocking { processesByMemory() }
        return list.joinToString(",", "[", "]") {
            "{\"pkg\":${quoteJs(it.packageName)},\"rssKb\":${it.rssKb}}"
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
     *
     * NOTA: lee stdout y stderr EN PARALELO. Leerlos en serie (stdout completo
     * y recién después stderr) causa deadlock cuando el proceso llena el pipe
     * de 64KB de la salida que no se está leyendo: el hijo se bloquea
     * escribiendo y stdout nunca llega a EOF. Con timeout duro para que un
     * comando vivo (p. ej. `top` interactivo) no cuelgue la app.
     */
    private fun runShell(vararg cmd: String): String {
        val (code, full) = runShellDrained(*cmd)
        if (code != 0 && !full.contains("Success")) {
            throw IllegalStateException("exit=$code: $full")
        }
        return full
    }

    /** Variante que devuelve (exitCode, salida combinada) sin lanzar — para lecturas tolerantes. */
    private fun runShellDrained(vararg cmd: String): Pair<Int, String> {
        val m: Method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val process = m.invoke(null, cmd, null, null) as Process

        // Drenaje paralelo: stdout y stderr en hilos independientes.
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()
        val tOut = Thread { drain(process.inputStream, outBuf) }
        val tErr = Thread { drain(process.errorStream, errBuf) }
        tOut.start(); tErr.start()

        val finished = try {
            process.waitFor(SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) { false }
        if (!finished) {
            try { process.destroyForcibly() } catch (_: Throwable) {}
            try { process.waitFor(2, TimeUnit.SECONDS) } catch (_: Throwable) {}
        }

        // Espera acotada; los hilos mueren al cerrarse los streams del proceso.
        try { tOut.join(500); tErr.join(500) } catch (_: Throwable) {}

        val code = try { process.exitValue() } catch (_: Throwable) { -1 }
        return code to (outBuf.toString() + errBuf.toString()).trim()
    }

    private fun drain(input: InputStream?, into: StringBuilder) {
        if (input == null) return
        try {
            val r = BufferedReader(InputStreamReader(input))
            val buf = CharArray(8192)
            while (true) {
                val n = r.read(buf)
                if (n < 0) break
                into.append(buf, 0, n)
            }
            r.close()
        } catch (_: Throwable) {
            try { input.close() } catch (_: Throwable) {}
        }
    }

    // ── Inventario real de procesos en segundo plano ─────────────────
    // `ActivityManager.runningAppProcesses` desde Android 11+ solo devuelve
    // los procesos del propio llamante, así que la "lista de apps corriendo"
    // quedaba vacía en equipos modernos pese a tener Shizuku. En su lugar se
    // enumera leyendo /proc/<pid>/status (Uid:+VmRSS:) con identidad de
    // Shizuku y se resuelve UID→paquete vía `pm list packages -U`. Es exacto
    // e idéntico en todo Android, sin depender del binario `ps` de cada ROM.

    /**
     * Tabla UID→packageName desde `pm list packages -U` (una sola pasada).
     * Resiliente entre versiones: en Android 8+ existe `cmd package`, y en
     * ROMs antiguas o recortadas la salida puede llegar sin columna uid o el
     * primer comando puede fallar → se prueba la variante siguiente.
     */
    private suspend fun uidToPackageMap(): Map<Int, String> = withContext(Dispatchers.IO) {
        val map = HashMap<Int, String>()
        val out = firstCommandWithOutput(
            arrayOf("pm", "list", "packages", "-U"),
            arrayOf("cmd", "package", "list", "packages", "-U")
        )
        for (line in out.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("package:")) continue
            val rest = t.removePrefix("package:")
            val idx = rest.indexOf(" uid:")
            val pkg = if (idx > 0) rest.substring(0, idx) else rest
            val uid = if (idx > 0) rest.substring(idx + 5).trim().toIntOrNull() else null
            if (uid != null && pkg.isNotEmpty()) map.putIfAbsent(uid, pkg)
        }
        map
    }

    /** Ejecuta la primera variante del comando que devuelva salida útil (package:). */
    private fun firstCommandWithOutput(vararg cmds: Array<String>): String {
        for (cmd in cmds) {
            try {
                val (code, out) = runShellDrained(*cmd)
                if (code == 0 && out.contains("package:")) return out
            } catch (_: Throwable) { /* probar la siguiente variante */ }
        }
        return ""
    }

    /**
     * RSS agregado por UID leyendo /proc directamente.
     *
     * A diferencia de `ps -o uid=,rss=`, EL FORMATO DE /proc ES IDÉNTICO EN
     * TODO Android: `Uid:` (12) y `VmRSS:` están en `/proc/<pid>/status` en
     * AOSP, MIUI, OneUI, ColorOS, EMUI y cualquier ROM, sin depender del
     * binario `ps` (toybox/toolbox/busybox varían columnas y flags entre
     * versiones y fabricantes → era una fuente real de incompatibilidad).
     * Se lee con identidad Shizuku (root/ADB) cubriendo todos los PIDs.
     */
    private fun scanProcRss(): HashMap<Int, Long> {
        val rssByUid = HashMap<Int, Long>()
        val pids = File("/proc").list() ?: return rssByUid
        for (pid in pids) {
            if (pid.isEmpty() || !pid[0].isDigit()) continue
            var uid = -1L
            var rss = 0L
            try {
                File("/proc/$pid/status").forEachLine { line ->
                    when {
                        line.startsWith("Uid:") -> uid = statusNum(line)
                        line.startsWith("VmRSS:") -> rss = statusNum(line)
                    }
                }
            } catch (_: Throwable) { continue }
            if (uid >= APP_UID_MIN.toLong()) rssByUid[uid.toInt()] = (rssByUid[uid.toInt()] ?: 0L) + rss
        }
        return rssByUid
    }

    /** Primer número de una línea de /proc status (p. ej. "Uid:\t11023\t…" o "VmRSS:\t  5421 kB"). */
    private fun statusNum(line: String): Long =
        line.substringAfter(':').trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L

    /**
     * Paquetes con procesos vivos y su RSS agregado (KB), ordenados por consumo.
     * Solo cuentas de usuario (uid ≥ 10000, incluye el uid de CodeHub que el
     * caller filtra a nivel acción). Devuelve los top-60.
     */
    suspend fun processesByMemory(): List<PkgMem> = withContext(Dispatchers.IO) {
        try {
            val uidToPkg = uidToPackageMap()
            val rssByUid = scanProcRss()
            if (rssByUid.isEmpty()) return@withContext emptyList()
            val perPkg = HashMap<String, Long>()
            for ((uid, kb) in rssByUid) {
                val pkg = uidToPkg[uid] ?: continue
                perPkg[pkg] = (perPkg[pkg] ?: 0L) + kb
            }
            perPkg.entries
                .map { PkgMem(it.key, it.value) }
                .sortedByDescending { it.rssKb }
                .take(60)
        } catch (_: Throwable) { emptyList() }
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