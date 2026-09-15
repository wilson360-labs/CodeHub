package com.codehub.app

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Núcleo de OPTIMIZACIÓN del sistema, vía Shizuku.
 *
 * Ejecuta cada comando como un proceso `sh -c "..."` independiente bajo la
 * identidad de Shizuku. Sin libsu: ShizukuRemoteProcess usa streams Binder
 * que libsu 6.x no puede envolver con Shell.Builder.build(Process).
 * Se reutiliza el mismo patrón de ShizukuInstaller (reflexión sobre
 * Shizuku.newProcess privado).
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

    @Volatile private var lastShellError: String? = null

    /** Timeout por comando de diagnóstico (10s) — un comando vivo no cuelga la app. */
    private const val CMD_TIMEOUT_MS = 10_000L

    // ── Primitiva de shell ──────────────────────────────────────────

    /** Crea un proceso `sh` bajo la identidad de Shizuku (reflexión, patrón ShizukuInstaller). */
    private fun shizukuShProcess(cmd: Array<String>): java.lang.Process {
        val m: Method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        return m.invoke(null, cmd, null, null) as java.lang.Process
    }

    /**
     * Ejecuta un comando con shell Shizuku (un proceso `sh -c` por llamada).
     * Lee stdout + stderr EN PARALELO y espera el exit code con timeout.
     * (Leerlos en serie causaba deadlock real cuando el output superaba el
     * pipe de 64KB: el hijo se bloquea en stderr y stdout nunca cierra.)
     * Devuelve (código, líneas) o lanza si Shizuku no está disponible.
     */
    private suspend fun runSh(line: String): Pair<Int, List<String>> = withContext(Dispatchers.IO) {
        try {
            val proc = shizukuShProcess(arrayOf("sh", "-c", line))
            val outBuf = StringBuilder()
            val errBuf = StringBuilder()
            val tOut = Thread { drain(proc.inputStream, outBuf) }
            val tErr = Thread { drain(proc.errorStream, errBuf) }
            tOut.start(); tErr.start()

            val finished = try {
                proc.waitFor(CMD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) { false }
            if (!finished) {
                lastShellError = "timeout"
                try { proc.destroyForcibly() } catch (_: Throwable) {}
                try { proc.waitFor(2, TimeUnit.SECONDS) } catch (_: Throwable) {}
                (124 to emptyList())   // 124 = timeout (convención coreutils)
            } else {
                try { tOut.join(500); tErr.join(500) } catch (_: Throwable) {}
                val code = try { proc.exitValue() } catch (_: Throwable) { -1 }
                lastShellError = null
                val lines = (outBuf.toString() + errBuf.toString())
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                code to lines
            }
        } catch (t: Throwable) {
            lastShellError = t.message ?: t.toString()
            throw IllegalStateException(
                "Shell Shizuku no disponible" + lastShellError?.let { " ($it)" }.orEmpty()
            )
        }
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

    /** Estado unificado (JSON rico de SystemCleaner + último error de shell). */
    private fun ShellStatus(): String {
        val base = try { SystemCleaner.statusJson() } catch (t: Throwable) {
            "{\"status\":\"DISCONNECTED\",\"error\":${quoteJs(t.message ?: t.toString())}}"
        }
        val shellErr = lastShellError?.let { quoteJs(it) } ?: "null"
        return if (base.endsWith("}")) base.dropLast(1) + ",\"shellError\":$shellErr}" else base
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
            if (lines.any { l -> SENSITIVE_PATHS.any { l.contains(it) } }) {
                return@runBlocking "{\"error\":\"contiene rutas privadas de otras apps (databases, shared_prefs, /data/data)\"}"
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
    // Rutas con datos de otras apps/privados del sistema: nunca leerlas desde
    // el runner de scripts, incluso bajo root (el entorno no las necesita).
    private val SENSITIVE_PATHS = listOf(
        "/data/data", "/data/user/", "shared_prefs", "/databases/",
        "content://", "/proc/kcore", "/sys/kernel/debug", "/cache"
    )
    private const val MAX_DIAG_LINES = 400

    @JvmStatic
    fun diagnosticsSync(): String = runBlocking {
        try {
            requireReady()
            val snap = readSnapshot()
            buildJson {
                key("ram", objectFrom {
                    kv("kbTotal", snap.mem.total); kv("kbAvailable", snap.mem.available)
                    kv("kbUsed", snap.mem.total - snap.mem.available)
                    kv("pct", if (snap.mem.total > 0) (snap.mem.total - snap.mem.available) * 100 / snap.mem.total else 0)
                    kv("mbUsed", (snap.mem.total - snap.mem.available) / 1024)
                    kv("mbTotal", snap.mem.total / 1024)
                })
                keyJson("storage", snap.storageJson)
                key("battery", objectFrom {
                    kv("level", snap.battery.level); kv("status", quoteJs(snap.battery.statusLabel))
                    kv("charging", snap.battery.charging)
                    kv("tempC", snap.battery.tempC); kv("tempF", snap.battery.tempF)
                    kv("scale", snap.battery.scale); kv("health", snap.battery.health)
                })
                key("thermal", objectFrom { kv("maxC", snap.thermal.first); kv("zone", quoteJs(snap.thermal.second)) })
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
            // Inventario REAL (ps + pm) — la API pública solo muestra procesos
            // propios en Android 11+, así que esto es lo único exacto con Shizuku.
            val byMem = SystemCleaner.processesByMemory()
            val pkgs = byMem.map { it.packageName }
                .filter { it != SKIP_FORCE_STOP && !it.startsWith("com.android.") }
                .filter { it != "com.codehub.app" }
                .distinct()
            if (pkgs.isEmpty()) return@runBlocking "{\"stopped\":[],\"failed\":[],\"idle\":true}"
            val r = SystemCleaner.killAppsPro(pkgs)
            fun arr(l: List<String>) = l.joinToString(",", "[", "]") { quoteJs(it) }
            "{\"stopped\":${arr(r.stopped)},\"failed\":${arr(r.failed)}}"
        } catch (t: Throwable) {
            "{\"error\":${quoteJs(t.message ?: t.toString())}}"
        }
    }

    /** Top consumidores de RAM (RSS real agregado). Síncrono para el panel. */
    @JvmStatic
    fun topMemorySync(): String = runBlocking {
        try {
            requireReady()
            SystemCleaner.processesByMemorySync()
        } catch (t: Throwable) {
            "{\"error\":${quoteJs(t.message ?: t.toString())}}"
        }
    }

    /**
     * Plan de optimización inteligente: analiza RAM, almacenamiento, batería,
     * temperatura y procesos en segundo plano reales, calcula un score de
     * salud 0-100 y propone acciones priorizadas (las que de verdad liberan
     * memoria/espacio) con estimación de ahorro. 100% local, sin enviar datos.
     * El frontend decide si ejecutar; acá solo se analiza y se recomienda.
     */
    @JvmStatic
    fun boostPlanSync(): String = runBlocking {
        try {
            requireReady()
            val snap = readSnapshot()
            val mem = snap.mem
            val battery = snap.battery
            val thermal = snap.thermal
            val byMem = try { SystemCleaner.processesByMemory() } catch (_: Throwable) { emptyList() }

            // ── Ejes (cada uno 0..100, más alto = mejor) ──
            val ramPct = if (mem.total > 0) (mem.total - mem.available) * 100 / mem.total else 100
            val ramScore = (100 - ramPct).coerceIn(0, 100)

            val storageScore = try { storagePct() } catch (_: Throwable) { 60 }

            var batteryScore = battery.level.coerceIn(0, 100)
            if (battery.tempC >= 40) batteryScore = (batteryScore - 20).coerceAtLeast(0)

            var thermalScore = 100
            when {
                thermal.first >= 45 -> thermalScore = 30
                thermal.first >= 40 -> thermalScore = 55
                thermal.first >= 36 -> thermalScore = 80
            }

            // Procesos: cuánta RAM "recuperable" hay en segundo plano real.
            val recoverableKb = byMem.filter { it.packageName != "com.codehub.app" }.sumOf { it.rssKb }
            val procsScore = when {
                recoverableKb > 1_500_000 -> 30   // >1.5 GB en 2º plano
                recoverableKb > 800_000   -> 50
                recoverableKb > 300_000   -> 70
                else                       -> 90
            }

            val score = (ramScore * 35 + storageScore * 20 + batteryScore * 20 +
                thermalScore * 15 + procsScore * 10) / 100

            // ── Recomendaciones priorizadas ──
            val recs = arrayListOf<String>()
            var prio = 1

            if (recoverableKb > 150_000) {
                recs += recJson(
                    prio++, "kill",
                    "Cerrar " + byMem.size + " apps en segundo plano",
                    "Libera ~" + fmtKb(recoverableKb) + " de RAM",
                    quoteJs(byMem.take(8).joinToString(", ") { it.packageName })
                )
            }

            val cache = try { cacheStatsSync() } catch (_: Throwable) { "{}" }
            val cacheBytes = if (cache.contains("\"rootOnly\":false")) {
                cache.substringAfter("\"totalBytes\":", "0").substringBefore(",").toLongOrNull() ?: 0L
            } else 0L
            if (cacheBytes >= 20_000_000L) {
                recs += recJson(
                    prio++, "cache",
                    "Limpiar caché de apps",
                    "Recupera ~" + fmtKb(cacheBytes / 1024) + " de almacenamiento",
                    "null"
                )
            }

            if (ramPct >= 80) {
                recs += recJson(
                    prio++, "trim",
                    "Recortar caché global (pm trim-caches)",
                    "Acelera el arranque de apps pesadas",
                    "null"
                )
            }

            if (thermal.first >= 40) {
                recs += recJson(prio++, "info",
                    "El equipo está a " + thermal.first + "°C",
                    "Evita cargarlo o ejecutar tareas pesadas hasta que baje",
                    "null")
            }

            if (recs.isEmpty()) {
                recs += recJson(1, "ok",
                    "Sin acciones urgentes",
                    "El dispositivo está en buen estado",
                    "null")
            }

            // ── JSON final ──
            val recArr = recs.joinToString(",", "[", "]")
            "{\"score\":$score,\"ramPct\":$ramPct,\"backend\":${quoteJs(backendName())}," +
                "\"ramScore\":$ramScore,\"storageScore\":$storageScore," +
                "\"batteryScore\":$batteryScore,\"thermalScore\":$thermalScore," +
                "\"procsScore\":$procsScore,\"recoverableKb\":$recoverableKb," +
                "\"recs\":$recArr}"
        } catch (t: Throwable) {
            "{\"error\":${quoteJs(t.message ?: t.toString())}}"
        }
    }

    private fun recJson(prio: Int, kind: String, title: String, detail: String, targets: String): String =
        "{\"prio\":$prio,\"kind\":${quoteJs(kind)},\"title\":${quoteJs(title)}," +
            "\"detail\":${quoteJs(detail)},\"targets\":$targets}"

    private fun fmtKb(kb: Long): String {
        val mb = kb / 1024
        return when {
            mb >= 1024 -> (mb / 1024.0).let { if (it >= 10) (it.toLong()).toString() + " GB" else String.format("%.1f GB", it) }
            mb > 0 -> mb.toString() + " MB"
            else -> kb.toString() + " KB"
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

    /** Porcentaje usado de la partición de datos (0..100) — para el score. */
    private suspend fun storagePct(): Int {
        val (_, out) = runSh("df -k /data / 2>/dev/null")
        var pct = -1
        for (line in out) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("Filesystem")) continue
            val parts = t.split(Regex("\\s+"))
            if (parts.size < 6) continue
            val kbTotal = parts[1].toLongOrNull() ?: 0L
            val kbUsed = parts[2].toLongOrNull() ?: 0L
            if (kbTotal <= 0) continue
            pct = if (kbTotal > 0) (kbUsed * 100 / kbTotal).toInt() else -1
            break
        }
        return if (pct < 0) 60 else pct
    }

    // ── Snapshot batched ───────────────────────────────────────────
    // Diagnóstico e Impulso leen los mismos datos; antes eran 6-7 procesos
    // `sh` NUEVOS por llamada. Ahora se empaquetan en UN solo subproceso
    // Shizuku con secciones marcadas (==SECT:...) y los parsers operan sobre
    // sus líneas. Menos procesos → menos latencia y menos consumo.
    private const val SECT_MEMINFO = "MEMINFO"
    private const val SECT_DF = "DF"
    private const val SECT_BATTERY = "BATTERY"
    private const val SECT_THERMAL = "THERMAL"
    private const val SECT_THERMTYPES = "THERMTYPES"

    private val SNAPSHOT_SCRIPT: String = arrayOf(
        "echo '==SECT:$SECT_MEMINFO'", "cat /proc/meminfo",
        "echo '==SECT:$SECT_DF'", "df -k",
        "echo '==SECT:$SECT_BATTERY'", "dumpsys battery",
        "echo '==SECT:$SECT_THERMAL'", "for f in /sys/class/thermal/thermal_zone*/temp; do cat \"\$f\" 2>/dev/null; done",
        "echo '==SECT:$SECT_THERMTYPES'", "for f in /sys/class/thermal/thermal_zone*/type; do cat \"\$f\" 2>/dev/null; done"
    ).joinToString("; ")

    private data class Snapshot(
        val mem: MemInfo,
        val storageJson: String,
        val battery: BatteryInfo,
        val thermal: Pair<Int, String>
    )

    /** Un solo subproceso Shizuku para toda la lectura de diagnóstico. */
    private suspend fun readSnapshot(): Snapshot {
        val (_, out) = runSh(SNAPSHOT_SCRIPT)
        val sec = splitSections(out)
        val battery = parseBattery(sec[SECT_BATTERY])
        return Snapshot(
            parseMemInfo(sec[SECT_MEMINFO]),
            parseStorageDf(sec[SECT_DF]),
            battery,
            parseThermalZones(sec[SECT_THERMAL], sec[SECT_THERMTYPES], battery)
        )
    }

    private fun splitSections(lines: List<String>): Map<String, List<String>> {
        val sections = HashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (line in lines) {
            val head = line.trim()
            if (head.startsWith("==SECT:")) {
                current = sections.getOrPut(head.removePrefix("==SECT:")) { arrayListOf() }
            } else {
                current?.add(line)
            }
        }
        return sections
    }

    private fun parseMemInfo(lines: List<String>?): MemInfo {
        var total = 0L
        var available = 0L
        for (line in lines ?: emptyList()) {
            if (line.startsWith("MemTotal:"))
                total = line.substringAfter("MemTotal:").trim().substringBefore("kB").trim().toLongOrNull() ?: 0L
            else if (line.startsWith("MemAvailable:"))
                available = line.substringAfter("MemAvailable:").trim().substringBefore("kB").trim().toLongOrNull() ?: 0L
        }
        if (total <= 0) throw IllegalStateException("No se pudo leer /proc/meminfo")
        if (available <= 0) {
            // Fallback: MemFree + Buffers + Cached (lectura manual del kernel).
            var free = 0L; var buffers = 0L; var cached = 0L
            for (line in lines ?: emptyList()) {
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

    private fun parseStorageDf(lines: List<String>?): String {
        val rows = arrayListOf<String>()
        for (line in lines ?: emptyList()) {
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

    private fun parseBattery(lines: List<String>?): BatteryInfo {
        var level = 0; var scale = 0; var status = 0; var health = 0; var temp = 0
        var ac = false; var usb = false; var wireless = false
        for (line in lines ?: emptyList()) {
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

    private fun parseThermalZones(
        tempLines: List<String>?,
        typeLines: List<String>?,
        battery: BatteryInfo
    ): Pair<Int, String> {
        // thermal zones: temp en m°C. Se emparejan con los type por índice.
        val temps = tempLines?.mapNotNull { line -> line.trim().toLongOrNull()?.takeIf { it > 0 } }
            ?: emptyList()
        if (temps.isEmpty()) return battery.tempC to "batería"
        val types = typeLines?.map { it.trim() } ?: emptyList()
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