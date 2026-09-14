package com.codehub.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import com.topjohnwu.superuser.Shell
import java.lang.reflect.Method
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Núcleo de OPTIMIZACIÓN del sistema, vía Shizuku + libsu.
 *
 * Diferencias con SystemCleaner:
 *  - SystemCleaner usa un `Shizuku.newProcess` por comando (funcionaba, pero
 *    sin shell persistente ni exit codes fiables).
 *  - Aquí se crea UN SOLO proceso `sh` bajo la identidad de Shizuku y se
 *    envuelve en un [Shell] de libsu (topjohnwu): buffer correcto, exit code
 *    real por comando y sesión persistente reutilizable.
 *
 * El proceso `sh` se obtiene por reflexión porque Shizuku-API 13.1.x dejó
 * `Shizuku.newProcess` privado (el mismo puente que usa ShizukuInstaller).
 *
 * Operaciones (local-first, todas en Dispatchers.IO):
 *  - Diagnóstico del dispositivo: RAM (/proc/meminfo), almacenamiento (df),
 *    batería (dumpsys battery) y temperatura (thermal zones).
 *  - Limpieza: caché GLOBAL (pm trim-caches, reutilizando SystemCleaner) y
 *    caché POR APP con pre-visión de espacio (root).
 *  - Control: matar procesos en segundo plano (SystemCleaner) y deshabilitar
 *    bloatware (pm disable-user / pm enable).
 *
 * Root vs ADB: bajo root (uid 0) se puede leer/borrar la caché de otras apps;
 * bajo ADB (uid 2000) esas operaciones devuelven rootOnly=true y solo queda
 * la vía global.
 */
object SystemOptimizer {

    private const val SKIP_FORCE_STOP = "moe.shizuku.privileged.api"

    // Paquetes de sistema que NUNCA deben aparecer como deshabilitables.
    private val CRITICAL_PREFIXES = listOf(
        "com.android.", "com.google.android.gms", "com.google.android.gsf",
        "com.google.android.ims", "com.google.android.packageinstaller",
        "com.google.android.setupwizard", "com.google.android.settings.intelligence",
        "com.google.android.providers.", "com.google.android.inputmethod.latin"
    )
    private val CRITICAL_PACKAGES = setOf(
        "com.codehub.app", SKIP_FORCE_STOP
    )

    @Volatile private var shellInstance: Shell? = null
    @Volatile private var lastShellError: String? = null
    private val shellMutex = Mutex()

    // ── Primitiva de shell ──────────────────────────────────────────

    /** Crea el proceso `sh` bajo la identidad de Shizuku (reflexión). */
    private fun shizukuShProcess(cmd: Array<String>): java.lang.Process {
        val m: Method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as java.lang.Process
    }

    /** Shell libsu persistente (crea el proceso `sh` de Shizuku si hace falta). */
    private suspend fun shell(): Shell? = withContext(Dispatchers.IO) {
        val current = shellInstance
        if (current != null && current.isAlive) return@withContext current
        shellMutex.withLock {
            val again = shellInstance
            if (again != null && again.isAlive) return@withLock again
            try {
                val proc = shizukuShProcess(arrayOf("sh"))
                val built = Shell.Builder.create()
                    .setTimeout(30000)
                    .build(proc)
                shellInstance = built
                lastShellError = null
                shellInstance
            } catch (t: Throwable) {
                shellInstance = null
                lastShellError = t.message ?: t.toString()
                null
            }
        }
    }

    private fun closeShell() {
        try {
            val s = shellInstance
            shellInstance = null
            if (s != null && s.isAlive) s.close()
        } catch (_: Throwable) {}
    }

    /** Ejecuta una línea de comando en la shell Shizuku/libsu.
     *  Si el proceso murió a mitad, se reconstruye una vez y se reintenta. */
    private suspend fun runSh(line: String): Pair<Int, List<String>> {
        var sh = shell() ?: throw IllegalStateException(
            "Shell Shizuku no disponible" + lastShellError?.let { " ($it)" }.orEmpty()
        )
        try {
            val r = sh.newJob().add(line).exec()
            return r.code to r.out
        } catch (t: Throwable) {
            // Shell muerta/desincronizada: cierra, reconstruye y reintenta una vez.
            closeShell()
            sh = shell() ?: throw IllegalStateException(
                "Shell Shizuku no disponible" + lastShellError?.let { " ($it)" }.orEmpty()
            )
            val r = sh.newJob().add(line).exec()
            return r.code to r.out
        }
    }

    private fun requireReady() {
        when (SystemCleaner.refreshStatus()) {
            SystemCleaner.Status.READY -> Unit
            SystemCleaner.Status.NOT_RUNNING -> throw IllegalStateException(
                "Shizuku/Sui no está corriendo. Actívalo con ADB inalámbrico o root."
            )
            SystemCleaner.Status.NEEDS_PERMISSION -> throw SecurityException(
                "Sin permiso de Shizuku. Concede el permiso antes de usar esta herramienta."
            )
            else -> throw IllegalStateException("Shizuku no está disponible.")
        }
    }

    private fun isRootBackend(): Boolean = try {
        Shizuku.getUid() == 0
    } catch (_: Throwable) {
        false
    }

    private fun quoteJs(s: String): String {
        val escaped = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", " ").replace("\r", " ")
        return "\"$escaped\""
    }

    // ── Entry points síncronos para CodeHubBridge (Thread de fondo) ──

    @JvmStatic
    fun statusSync(): String = runBlocking {
        ShellStatus()
    }

    /** Estado unificado (JSON rico de SystemCleaner + state interno de shell). */
    private fun ShellStatus(): String {
        val base = try { SystemCleaner.statusJson() } catch (t: Throwable) {
            "{\"status\":\"DISCONNECTED\",\"error\":${quoteJs(t.message ?: t.toString())}}"
        }
        val shellUp = shellInstance != null && shellInstance!!.isAlive
        val shellErr = lastShellError?.let { quoteJs(it) } ?: "null"
        // añade shellUp insertando antes del cierre }
        val enriched = if (base.endsWith("}")) base.dropLast(1) + ",\"shellUp\":$shellUp,\"shellError\":$shellErr}" else base
        return enriched
    }

    @JvmStatic
    fun runScriptSync(rawScript: String): String = runBlocking {
        try {
            requireReady()
            val script = (rawScript ?: "").trim()
            if (script.isEmpty()) return@runBlocking "{\"error\":\"script vacío\"}"
            val lines = script.replace("\r", "").split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            if (lines.isEmpty()) return@runBlocking "{\"error\":\"script vacío\"}"
            if (lines.any { l -> DENIED_TOKENS.any { l.lowercase().contains(it) } }) {
                return@runBlocking "{\"error\":\"contiene comandos no permitidos (su, rm, reboot, mount, settings put, wipe…)\"}"
            }
            lines.forEach { l ->
                val bin = l.substringBefore(' ').substringAfterLast('/').trim()
                if (bin.isNotEmpty() && bin !in ALLOWED_BINS) {
                    return@runBlocking "{\"error\":${quoteJs("binario no permitido: $bin")}}"
                }
            }
            val outputs = arrayListOf<String>()
            var highest = 0
            for (line in lines) {
                val (code, out) = runSh(line)
                highest = code
                if (out.isNotEmpty()) outputs.addAll(out)
                if (code != 0) outputs += "── exit $code"
                if (outputs.size > MAX_DIAG_LINES) {
                    outputs.add("… salida truncada")
                    break
                }
            }
            val arr = outputs.joinToString(",", "[", "]") { quoteJs(it) }
            "{\"ok\":true,\"code\":$highest,\"lines\":$arr}"
        } catch (t: Throwable) {
            "{\"error\":${quoteJs(t.message ?: t.toString())}}"
        }
    }

    // Allowlist de binarios seguros para el runner de scripts.
    private val ALLOWED_BINS = setOf(
        "df", "du", "cat", "echo", "dumpsys", "pm", "am", "ps", "top", "free",
        "uptime", "uname", "getprop", "date", "wc", "ls", "head", "tail",
        "grep", "id", "whoami", "env"
    )
    private val DENIED_TOKENS = listOf(
        "su ", "sudo", "reboot", "shutdown", "rm ", "chmod", "chown", "mkfs",
        "dd ", "mount", "umount", "svc ", "settings put", "wipe", "fastboot",
        "wpa_supplicant", "iptables", "ifconfig", "getenforce"
    )
    private const val MAX_DIAG_LINES = 400

    @JvmStatic
    fun diagnosticsSync(): String = runBlocking {
        try {
            requireReady()
            val mem = readMemInfo()
            val storage = readStorage()
            val battery = readBattery()
            val thermal = readThermal()
            buildJson {
                key("ram", objectFrom {
                    kv("kbTotal", mem.total); kv("kbAvailable", mem.available)
                    kv("kbUsed", mem.total - mem.available)
                    kv("pct", if (mem.total > 0) (mem.total - mem.available) * 100 / mem.total else 0)
                    kv("mbUsed", (mem.total - mem.available) / 1024)
                    kv("mbTotal", mem.total / 1024)
                })
                keyJson("storage", storage)
                key("battery", objectFrom {
                    kv("level", battery.level); kv("status", quoteJs(battery.statusLabel))
                    kv("charging", battery.charging)
                    kv("tempC", battery.tempC); kv("tempF", battery.tempF)
                    kv("scale", battery.scale); kv("health", battery.health)
                })
                key("thermal", objectFrom { kv("maxC", thermal.first); kv("zone", quoteJs(thermal.second)) })
                kv("backend", quoteJs(backendName()))
            }
        } catch (t: Throwable) {
            "{\"error\":${quoteJs(t.message ?: t.toString())}}"
        }
    }

    @JvmStatic
    fun cacheStatsSync(): String = runBlocking {
        try {
            requireReady()
            if (!isRootBackend()) {
                return@runBlocking "{\"rootOnly\":true,\"totalBytes\":0,\"apps\":[]}"
            }
            val (_, out) = runSh("du -sk /data/user/0/*/cache /data/user/0/*/code_cache 2>/dev/null")
            val perPkg = linkedMapOf<String, Long>()
            var total = 0L
            out.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                val kb = duKb(trimmed)
                if (kb <= 0) return@forEach
                val idx = trimmed.indexOf('\t')
                val path = if (idx > 0) trimmed.substring(idx + 1) else trimmed.substringAfterLast(' ')
                val parts = path.split("/")
                if (parts.size < 5) return@forEach
                val pkg = parts[4]
                if (pkg == SKIP_FORCE_STOP) return@forEach
                perPkg[pkg] = (perPkg[pkg] ?: 0L) + kb
            }
            val apps = perPkg.map { (pkg, kb) -> pkg to kb * 1024L }
                .filter { it.second > 0L }
                .sortedByDescending { it.second }
                .take(40)
            total = apps.sumOf { it.second }
            val json = apps.joinToString(",", "[", "]") { (pkg, bytes) ->
                "{\"pkg\":${quoteJs(pkg)},\"bytes\":${bytes}}"
            }
            "{\"rootOnly\":false,\"totalBytes\":$total,\"apps\":$json}"
        } catch (t: Throwable) {
            "{\"error\":${quoteJs(t.message ?: t.toString())}}"
        }
    }

    @JvmStatic
    fun clearAppCacheSync(pkg: String): String = runBlocking {
        try {
            requireReady()
            if (!isRootBackend()) {
                return@runBlocking "{\"error\":\"requiere Shizuku con root\",\"rootOnly\":true}"
            }
            val p = pkg.trim()
            if (p.isEmpty()) return@runBlocking "{\"error\":\"paquete vacío\"}"
            val cache = "/data/user/0/$p/cache"
            val codeCache = "/data/user/0/$p/code_cache"
            val (_, beforeLines) = runSh("du -sk $cache $codeCache 2>/dev/null")
            val beforeBytes = beforeLines.sumOf { line -> duKb(line.trim()) * 1024L }
            runSh("am force-stop $p")
            val lines = listOf(
                "find $cache -mindepth 1 -delete 2>/dev/null",
                "find $codeCache -mindepth 1 -delete 2>/dev/null"
            )
            lines.forEach { runSh(it) }
            "{\"ok\":true,\"freedBytes\":$beforeBytes,\"package\":${quoteJs(p)}}"
        } catch (t: Throwable) {
            "{\"error\":${quoteJs(t.message ?: t.toString())}}"
        }
    }

    @JvmStatic
    fun killCachedSync(context: Context): String = runBlocking {
        try {
            requireReady()
            val running = SystemCleaner.findRunningApps(context)
            val pkgs = running.map { it.packageName }
                .filter { it != SKIP_FORCE_STOP && !it.startsWith("com.android.") }
                .distinct()
            val r = SystemCleaner.killAppsPro(pkgs)
            fun arr(l: List<String>) = l.joinToString(",", "[", "]") { quoteJs(it) }
            "{\"stopped\":${arr(r.stopped)},\"failed\":${arr(r.failed)}}"
        } catch (t: Throwable) {
            "{\"error\":${quoteJs(t.message ?: t.toString())}}"
        }
    }

    @JvmStatic
    fun systemAppsSync(context: Context): String = runBlocking {
        try {
            requireReady()
            val pm = context.packageManager
            val apps = pm.getInstalledPackages(0)
                .mapNotNull { pi ->
                    val flags = pi.applicationInfo?.flags ?: 0
                    if (flags and ApplicationInfo.FLAG_SYSTEM == 0) return@mapNotNull null
                    val pkg = pi.packageName
                    if (pkg in CRITICAL_PACKAGES) return@mapNotNull null
                    if (CRITICAL_PREFIXES.any { pkg.startsWith(it) }) return@mapNotNull null
                    val label = try {
                        pi.applicationInfo?.run { pm.getApplicationLabel(this).toString() }
                            ?: pkg
                    } catch (t: Throwable) { pkg }
                    val enabled = try {
                        val s = pm.getApplicationEnabledSetting(pkg)
                        s != PackageManager.COMPONENT_ENABLED_STATE_DISABLED &&
                            s != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    } catch (t: Throwable) { true }
                    OptimizerApp(pkg, label, enabled)
                }
                .sortedBy { it.label.lowercase() }
            val json = apps.joinToString(",", "[", "]") {
                "{\"pkg\":${quoteJs(it.pkg)},\"label\":${quoteJs(it.label)},\"enabled\":${it.enabled}}"
            }
            "{\"apps\":$json}"
        } catch (t: Throwable) {
            "{\"error\":${quoteJs(t.message ?: t.toString())}}"
        }
    }

    @JvmStatic
    fun setAppEnabledSync(pkg: String, enabled: Boolean): String = runBlocking {
        try {
            requireReady()
            val p = pkg.trim()
            if (p.isEmpty()) return@runBlocking "{\"error\":\"paquete vacío\"}"
            val cmd = if (enabled) "pm enable --user 0 $p" else "pm disable-user --user 0 $p"
            val (code, out) = runSh(cmd)
            val msg = out.joinToString(" ").trim()
            if (code != 0 && !msg.contains("Success")) {
                return@runBlocking "{\"error\":${quoteJs(msg.ifEmpty { "exit=$code" })}}"
            }
            "{\"ok\":true,\"package\":${quoteJs(p)},\"enabled\":$enabled}"
        } catch (t: Throwable) {
            "{\"error\":${quoteJs(t.message ?: t.toString())}}"
        }
    }

    // ── Lecturas internas ───────────────────────────────────────────

    private data class MemInfo(val total: Long, val available: Long)
    private data class BatteryInfo(
        val level: Int,
        val statusLabel: String,
        val charging: Boolean,
        val tempC: Int,
        val tempF: Int,
        val scale: Int,
        val health: String
    )

    private suspend fun backendName(): String = try {
        SystemCleaner.backendDescription()
    } catch (_: Throwable) { "desconocido" }

    private suspend fun readMemInfo(): MemInfo {
        val (_, out) = runSh("cat /proc/meminfo")
        var total = 0L
        var available = 0L
        for (line in out) {
            if (line.startsWith("MemTotal:"))
                total = line.substringAfter("MemTotal:").trim().substringBefore("kB").trim().toLongOrNull() ?: 0L
            else if (line.startsWith("MemAvailable:"))
                available = line.substringAfter("MemAvailable:").trim().substringBefore("kB").trim().toLongOrNull() ?: 0L
        }
        if (total <= 0) throw IllegalStateException("No se pudo leer /proc/meminfo")
        if (available <= 0) {
            // Fallback: MemFree + Buffers + Cached (lectura manual del kernel).
            var free = 0L; var buffers = 0L; var cached = 0L
            for (line in out) {
                val v = line.substringAfterLast(':').trim().substringBefore("kB").trim().toLongOrNull() ?: 0L
                when {
                    line.startsWith("MemFree:") -> free = v
                    line.startsWith("Buffers:") -> buffers = v
                    line.startsWith("Cached:") -> cached = v
                }
            }
            available = free + buffers + cached
        }
        return MemInfo(total, available)
    }

    private suspend fun readStorage(): String {
        val (_, out) = runSh("df -k")
        val rows = arrayListOf<String>()
        for (line in out) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("Filesystem")) continue
            val parts = t.split(Regex("\\s+"))
            // Filesystem | 1K-blocks | Used | Available | Use% | Mounted on
            if (parts.size < 6) continue
            val mounted = parts.subList(5, parts.size).joinToString(" ")
            if (!mounted.startsWith("/")) continue
            val kbTotal = parts[1].toLongOrNull() ?: 0L
            val kbUsed = parts[2].toLongOrNull() ?: 0L
            val kbAvail = parts[3].toLongOrNull() ?: 0L
            if (kbTotal <= 0) continue
            rows += "{\"mounted\":${quoteJs(mounted)},\"filesystem\":${quoteJs(parts[0])}," +
                "\"kbTotal\":$kbTotal,\"kbUsed\":$kbUsed,\"kbAvail\":$kbAvail," +
                "\"pct\":${if (kbTotal > 0) kbUsed * 100 / kbTotal else 0}}"
            if (rows.size >= 4) break
        }
        return rows.joinToString(",", "[", "]")
    }

    private suspend fun readBattery(): BatteryInfo {
        val (_, out) = runSh("dumpsys battery")
        var level = 0; var scale = 0; var status = 0; var health = 0; var temp = 0
        var ac = false; var usb = false; var wireless = false
        for (line in out) {
            val key = line.trim()
            if (key.startsWith("level:")) level = num(key)
            else if (key.startsWith("scale:")) scale = num(key)
            else if (key.startsWith("status:")) status = num(key)
            else if (key.startsWith("health:")) health = num(key)
            else if (key.startsWith("temperature:")) temp = num(key)
            else if (key.startsWith("AC powered:")) ac = bool(key)
            else if (key.startsWith("USB powered:")) usb = bool(key)
            else if (key.startsWith("Wireless powered:")) wireless = bool(key)
        }
        val statusLabel = when (status) {
            2 -> "cargando"
            3 -> "descargando"
            4 -> "sin cargar"
            5 -> "llena"
            else -> "desconocido"
        }
        val healthLabel = when (health) {
            2 -> "bien"
            3 -> "sobrecalentada"
            4 -> "muerta"
            5 -> "voltage over"
            6 -> "falla especificada"
            7 -> "frio"
            else -> "desconocido"
        }
        val tempC = temp / 10
        val tempF = tempC * 9 / 5 + 32
        return BatteryInfo(level, statusLabel, ac || usb || wireless, tempC, tempF, scale, healthLabel)
    }

    private suspend fun readThermal(): Pair<Int, String> {
        // thermal zones: temp en m°C. Se emparejan con los type por índice.
        var (tCode, tOut) = runSh("for f in /sys/class/thermal/thermal_zone*/temp; do cat \"\$f\" 2>/dev/null; done")
        val temps = tOut.mapNotNull { line -> line.trim().toLongOrNull()?.takeIf { it > 0 } }
        var (_, nOut) = runSh("for f in /sys/class/thermal/thermal_zone*/type; do cat \"\$f\" 2>/dev/null; done")
        val types = nOut.map { it.trim() }
        if (temps.isEmpty()) {
            // Fallback: la temperatura de la batería (dumpsys battery).
            val battery = readBattery()
            return battery.tempC to "batería"
        }
        val maxIdx = temps.indices.maxByOrNull { temps[it] } ?: 0
        val maxC = (temps[maxIdx] / 1000L).toInt()
        val zone = if (maxIdx < types.size && types[maxIdx].isNotEmpty()) types[maxIdx] else "thermal_zone_$maxIdx"
        return maxC to zone
    }

    private fun num(line: String): Int =
        line.substringAfterLast(':').trim().takeWhile { it.isDigit() }.toIntOrNull() ?: 0

    private fun bool(line: String): Boolean = line.substringAfterLast(':').trim() == "true"

    private fun duKb(line: String): Long {
        if (line.isEmpty()) return 0L
        val idx = line.indexOf('\t')
        if (idx > 0) return line.substring(0, idx).toLongOrNull() ?: 0L
        return line.split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: 0L
    }

    // ── Builder JSON minimo (evita dependencia externa) ─────────────

    private class JsonBuilder {
        private val sb = StringBuilder("{")
        private var first = true
        fun kv(k: String, v: Any) {
            if (!first) sb.append(',')
            first = false
            sb.append(quoteJs(k)).append(':').append(v)
        }
        fun key(k: String, obj: JsonBuilder) {
            if (!first) sb.append(',')
            first = false
            sb.append(quoteJs(k)).append(':').append(obj.toString())
        }
        fun keyJson(k: String, jsonArray: String) {
            if (!first) sb.append(',')
            first = false
            sb.append(quoteJs(k)).append(':').append(jsonArray)
        }
        override fun toString(): String = sb.append('}').toString()
    }

    private inline fun buildJson(build: JsonBuilder.() -> Unit): String {
        val b = JsonBuilder()
        b.build()
        return b.toString()
    }

    private inline fun objectFrom(build: JsonBuilder.() -> Unit): JsonBuilder {
        val b = JsonBuilder()
        b.build()
        return b
    }

    private data class OptimizerApp(val pkg: String, val label: String, val enabled: Boolean)
}