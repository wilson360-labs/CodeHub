package com.codehub.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * State of a user-initiated APK transfer. No DownloadManager or OS download
 * notification is used; NativeStoreDownloadService renders the in-process
 * notification from this flow.
 */
sealed interface ApkTransferState {
    data object Idle : ApkTransferState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ApkTransferState
    data object Verifying : ApkTransferState
    data object ReadyToInstall : ApkTransferState
    data class AwaitingUserConfirmation(val detail: String = "") : ApkTransferState
    data object Installed : ApkTransferState
    data class Failed(val message: String) : ApkTransferState
    data object Cancelled : ApkTransferState
}

/**
 * In-app APK download and Android installer handoff used by Store and My Apps.
 *
 * Context-agnostic so downloads keep running while the activity is closed:
 * NativeStoreDownloadService observes [transfers], shows the progress
 * notification and, when the user is not in the app, offers "Listo para
 * instalar" through [pendingApkFile] + [launchUserInstall].
 */
object NativeStoreApkInstaller {
    const val SELF_UPDATE_ID = AppUpdateChecker.SELF_UPDATE_ID

    interface Listener {
        fun onProgress(downloadedBytes: Long, totalBytes: Long)
        fun onFinished(status: Status, message: String = "")
    }

    enum class Status { INSTALLED, INSTALLER_OPENED, READY_BACKGROUND, SIGNATURE_MISMATCH, FAILED, CANCELLED }

    /** APKs verified and ready for the user-confirmation installer path. */
    private val pendingApks = ConcurrentHashMap<String, File>()
    private val displayNames = ConcurrentHashMap<String, String>()

    /** Human name per transfer, read by the download service for notifications. */
    fun displayName(appId: String): String = displayNames[appId] ?: appId

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val installerFileMaxAgeMs = 7L * 24 * 60 * 60 * 1000
    private val trustedHosts = setOf(
        "codehub-98s6.onrender.com", "github.com", "githubusercontent.com",
        "release-assets.githubusercontent.com", "github.io", "jsdelivr.net",
    )
    private val mutableTransfers = MutableStateFlow<Map<String, ApkTransferState>>(emptyMap())

    /** Observable UI state; cards can collect this flow without owning download implementation. */
    val transfers: StateFlow<Map<String, ApkTransferState>> = mutableTransfers.asStateFlow()

    private fun isSafeHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT)
        return trustedHosts.any { normalized == it || normalized.endsWith(".$it") }
    }

    /**
     * Starts (or replaces) the APK transfer for [appId].
     *
     * @param context   any Context (engine keeps no Activity reference).
     * @param activity  visible Activity if the user is in the app; used only to
     *   open the system installer immediately. When null or finishing, the
     *   service-side notification takes over.
     */
    @JvmStatic
    fun start(
        context: Context,
        apkUrl: String,
        appId: String,
        expectedPackage: String?,
        preferSilent: Boolean,
        appName: String = appId,
        activity: Activity? = null,
        listener: Listener? = null,
        fallbackUrls: List<String> = emptyList(),
        fallbackRepo: String? = null,
        relaunchAfterInstall: Boolean = false,
        expectedSha256: String? = null,
    ) {
        if (jobs[appId]?.isActive == true) {
            listener?.onFinished(Status.FAILED, "La descarga de esta app ya está en curso.")
            return
        }
        displayNames[appId] = appName.ifBlank { appId }

        NativeStoreDownloadService.ensureStarted(context)

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val safeId = appId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
            val directory = context.getExternalFilesDir(null) ?: context.filesDir
            val destination = File(directory, "store_$safeId.apk")
            val partial = File(directory, "store_$safeId.apk.part")
            try {
                setState(appId, ApkTransferState.Downloading(0, -1))
                downloadWithFallback(listOf(apkUrl) + fallbackUrls, partial, fallbackRepo) { downloaded, total ->
                    setState(appId, ApkTransferState.Downloading(downloaded, total))
                    main.post { listener?.onProgress(downloaded, total) }
                }
                setState(appId, ApkTransferState.Verifying)
                if (destination.exists() && !destination.delete()) throw IOException("No se pudo reemplazar el APK anterior")
                if (!partial.renameTo(destination)) throw IOException("No se pudo preparar el APK descargado")

                if (appId == SELF_UPDATE_ID) {
                    val expected = expectedSha256.orEmpty().trim().removePrefix("sha256:").lowercase(Locale.ROOT)
                    if (!expected.matches(Regex("[0-9a-f]{64}"))) {
                        error("GitHub no publicó un SHA-256 válido para esta versión. CodeHub no instalará un APK sin verificar.")
                    }
                    val actual = sha256File(destination)
                    if (!actual.equals(expected, ignoreCase = true)) {
                        error("El SHA-256 del APK descargado no coincide con la release. Se canceló la instalación.")
                    }
                }

                val archive = withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageArchiveInfo(destination.absolutePath,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                        else android.content.pm.PackageManager.GET_SIGNATURES)
                }
                if (archive?.packageName.isNullOrBlank()) error("El archivo descargado no es un APK válido")
                if (!expectedPackage.isNullOrBlank() && expectedPackage != archive?.packageName) {
                    error("El paquete APK no coincide con la app seleccionada")
                }
                if (!expectedPackage.isNullOrBlank() && hasDifferentSigningCertificate(context, archive!!, expectedPackage)) {
                    if (relaunchAfterInstall || appId == SELF_UPDATE_ID) {
                        error("La firma de CodeHub no coincide con la instalada. No se desinstaló CodeHub ni se modificaron sus datos; instala una compilación firmada con el certificado original.")
                    }
                    pendingApks[appId] = destination
                    val detail = "La firma digital de ${appName.ifBlank { appId }} cambió. Android bloquea la actualización para proteger la app y sus datos. Si aceptas desinstalarla y reinstalarla, perderás sus datos locales y sesiones. CodeHub intentará hacerlo con Shizuku; Android puede pedir confirmación según la versión y sus políticas."
                    setState(appId, ApkTransferState.AwaitingUserConfirmation(detail))
                    main.post { listener?.onFinished(Status.SIGNATURE_MISMATCH, detail) }
                    return@launch
                }
                if (relaunchAfterInstall) {
                    val current = context.packageManager.getPackageInfo(context.packageName, 0)
                    val downloadedCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        archive!!.longVersionCode else archive!!.versionCode.toLong()
                    val installedCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        current.longVersionCode else current.versionCode.toLong()
                    if (downloadedCode <= installedCode) error("El APK no es más reciente que CodeHub instalado")
                }

                // Insights + history while the APK is still on disk (silent install
                // deletes it afterwards). Local, key-less (no VirusTotal).
                NativeApkInsights.analyze(context, appId, destination)
                    ?.let { NativeApkInsights.cacheInsight(context, it) }
                NativeApkInsights.recordDownload(
                    context,
                    appId,
                    archive?.versionName ?: "",
                    destination.length(),
                    appName,
                    archive?.packageName.orEmpty(),
                )

                setState(appId, ApkTransferState.ReadyToInstall)
                var silentInstallFailure = ""
                if (preferSilent && ShizukuInstaller.isReady()) {
                    try {
                        withContext(Dispatchers.IO) {
                            if (relaunchAfterInstall) {
                                val component = context.packageManager.getLaunchIntentForPackage(context.packageName)
                                    ?.component?.flattenToShortString()
                                    ?: error("No se encontró la pantalla de inicio de CodeHub")
                                ShizukuInstaller.installSilentlyAndRelaunch(context, destination.absolutePath, component)
                            } else {
                                ShizukuInstaller.installSilently(context, destination.absolutePath)
                            }
                        }
                        destination.delete()
                        setState(appId, ApkTransferState.Installed)
                        main.post { listener?.onFinished(Status.INSTALLED) }
                        return@launch
                    } catch (error: Exception) {
                        // Keep the real Package Manager reason visible when we fall back.
                        silentInstallFailure = error.message?.take(240)
                            ?.takeIf { it.isNotBlank() } ?: "Shizuku no devolvió un resultado válido."
                        if (isSignatureMismatch(silentInstallFailure)) {
                            if (relaunchAfterInstall || appId == SELF_UPDATE_ID) {
                                throw IOException("Android rechazó la actualización de CodeHub por una firma distinta. No se desinstaló la app ni se borraron sus datos. Instala una versión firmada con el certificado original.")
                            }
                            pendingApks[appId] = destination
                            val detail = "La firma digital de ${appName.ifBlank { appId }} cambió. Android bloquea la actualización para proteger la app y sus datos. Si aceptas desinstalarla y reinstalarla, se borrarán sus datos locales y sesiones; la nueva versión se instalará con Shizuku si Android lo permite."
                            setState(appId, ApkTransferState.AwaitingUserConfirmation(detail))
                            main.post { listener?.onFinished(Status.SIGNATURE_MISMATCH, detail) }
                            return@launch
                        }
                    }
                } else if (preferSilent) {
                    silentInstallFailure = "Shizuku no está activo o CodeHub no tiene permiso."
                }

                val alive = activity != null && !activity.isFinishing && !activity.isDestroyed
                val launched = alive && runCatching {
                    openPackageInstaller(context, destination)
                }.isSuccess
                if (launched) {
                    // Keep it long enough for Package Installer and clean stale APKs later.
                    scheduleApkCleanup(appId, destination)
                    val detail = silentInstallFailure.takeIf { it.isNotBlank() }
                        ?.let { "Shizuku no completó la instalación automática: $it" }.orEmpty()
                    setState(appId, ApkTransferState.AwaitingUserConfirmation(detail))
                    main.post { listener?.onFinished(Status.INSTALLER_OPENED, detail) }
                } else {
                    // App in background: the download service shows "Listo para
                    // instalar". Background activity starts are restricted, so
                    // the finish happens when the user taps the notification.
                    pendingApks[appId] = destination
                    scheduleApkCleanup(appId, destination, installerFileMaxAgeMs)
                    val detail = silentInstallFailure.takeIf { it.isNotBlank() }
                        ?.let { "Shizuku no completó la instalación automática: $it" }.orEmpty()
                    setState(appId, ApkTransferState.AwaitingUserConfirmation(detail))
                    main.post { listener?.onFinished(Status.READY_BACKGROUND, detail) }
                }
            } catch (cancelled: CancellationException) {
                partial.delete()
                destination.delete()
                setState(appId, ApkTransferState.Cancelled)
                main.post { listener?.onFinished(Status.CANCELLED, "Descarga cancelada.") }
                throw cancelled
            } catch (error: Exception) {
                partial.delete()
                destination.delete()
                val message = error.message ?: "Falló la descarga"
                setState(appId, ApkTransferState.Failed(message))
                main.post { listener?.onFinished(Status.FAILED, message) }
            } finally {
                jobs.remove(appId)
            }
        }
        jobs[appId] = job
        job.start()
    }

    fun cancel(appId: String) {
        jobs[appId]?.cancel()
    }

    fun clearState(appId: String) {
        mutableTransfers.update { it - appId }
        pendingApks.remove(appId)
    }

    /** Drops only the flow state; keeps the verified APK for later launch. */
    fun clearUiStateOnly(appId: String) {
        mutableTransfers.update { it - appId }
    }

    /** File verified and ready for user confirmation, or null for this app. */
    fun pendingApkFile(appId: String): File? = pendingApks[appId]

    /** Opens Android's package installer over a verified, ready APK. */
    @JvmStatic
    fun launchUserInstall(context: Context, appId: String): Boolean {
        val file = pendingApks[appId] ?: return false
        return runCatching {
            openPackageInstaller(context, file)
            scheduleApkCleanup(appId, file)
        }.isSuccess
    }

    /** Called only after an explicit warning/consent; app data is lost by uninstall. */
    @JvmStatic
    fun reinstallAfterSignatureChange(
        context: Context,
        appId: String,
        packageName: String,
        activity: Activity?,
        listener: Listener,
    ) {
        val file = pendingApks[appId]
        if (file == null || !file.isFile) {
            main.post { listener.onFinished(Status.FAILED, "El APK verificado ya no está disponible. Descárgalo de nuevo.") }
            return
        }
        if (appId == SELF_UPDATE_ID || packageName == context.packageName) {
            main.post { listener.onFinished(Status.FAILED, "CodeHub no puede desinstalarse a sí misma. Instala una compilación con la firma original.") }
            return
        }
        if (!ShizukuInstaller.isReady()) {
            main.post { listener.onFinished(Status.FAILED, "Para reinstalar sin el instalador, Shizuku debe estar activo y autorizado.") }
            return
        }
        scope.launch {
            var previousVersionRemoved = false
            try {
                withContext(Dispatchers.IO) {
                    ShizukuInstaller.uninstallSilently(packageName)
                    previousVersionRemoved = true
                    ShizukuInstaller.installSilently(context, file.absolutePath)
                }
                file.delete()
                pendingApks.remove(appId)
                setState(appId, ApkTransferState.Installed)
                main.post { listener.onFinished(Status.INSTALLED, "App reinstalada. Android eliminó sus datos locales junto con la versión anterior.") }
            } catch (error: Exception) {
                if (!previousVersionRemoved) {
                    val detail = "Shizuku no pudo desinstalar la versión anterior. No se borraron sus datos. ${error.message ?: "Revisa los permisos e inténtalo otra vez."}"
                    setState(appId, ApkTransferState.Failed(detail))
                    main.post { listener.onFinished(Status.FAILED, detail) }
                    return@launch
                }
                val detail = "La versión anterior se desinstaló, pero Android no completó la instalación automática: ${error.message ?: "error del Package Manager"}"
                val opened = activity != null && !activity.isFinishing && !activity.isDestroyed && runCatching {
                    openPackageInstaller(context, file)
                }.isSuccess
                if (opened) {
                    scheduleApkCleanup(appId, file)
                    setState(appId, ApkTransferState.AwaitingUserConfirmation(detail))
                    main.post { listener.onFinished(Status.INSTALLER_OPENED, detail) }
                } else {
                    pendingApks[appId] = file
                    setState(appId, ApkTransferState.AwaitingUserConfirmation(detail))
                    main.post { listener.onFinished(Status.READY_BACKGROUND, detail) }
                }
            }
        }
    }

    private fun isSignatureMismatch(message: String): Boolean {
        val value = message.lowercase(Locale.ROOT)
        return value.contains("install_failed_update_incompatible") ||
            value.contains("signatures do not match") || value.contains("signatures are inconsistent")
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun hasDifferentSigningCertificate(context: Context, archive: android.content.pm.PackageInfo, packageName: String): Boolean {
        return runCatching {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
            else android.content.pm.PackageManager.GET_SIGNATURES
            val installed = context.packageManager.getPackageInfo(packageName, flags)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val oldInfo = installed.signingInfo ?: return@runCatching false
                val newInfo = archive.signingInfo ?: return@runCatching false
                val oldCurrent = oldInfo.apkContentsSigners.orEmpty().toSet()
                val newCurrent = newInfo.apkContentsSigners.orEmpty().toSet()
                if (oldCurrent.isEmpty() || newCurrent.isEmpty()) return@runCatching false
                if (oldInfo.hasMultipleSigners() || newInfo.hasMultipleSigners()) {
                    oldCurrent != newCurrent
                } else {
                    val oldLineage = oldInfo.signingCertificateHistory.orEmpty().toSet().ifEmpty { oldCurrent }
                    val newLineage = newInfo.signingCertificateHistory.orEmpty().toSet().ifEmpty { newCurrent }
                    oldLineage.intersect(newLineage).isEmpty()
                }
            } else {
                val old = installed.signatures.orEmpty().toSet()
                val fresh = archive.signatures.orEmpty().toSet()
                fresh.isNotEmpty() && fresh != old
            }
        }.getOrDefault(false)
    }

    /** Removes only this app's installer APK and partial file, never app/user data. */
    @JvmStatic
    fun cleanupAppFiles(context: Context, appId: String): Long {
        val safeId = appId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
        val directories = listOfNotNull(context.getExternalFilesDir(null), context.filesDir).distinct()
        var removedBytes = 0L
        directories.forEach { directory ->
            listOf(File(directory, "store_$safeId.apk"), File(directory, "store_$safeId.apk.part")).forEach { file ->
                if (file.isFile) {
                    val size = file.length()
                    if (file.delete()) removedBytes += size
                }
            }
        }
        pendingApks.remove(appId)
        mutableTransfers.update { it - appId }
        NativeApkInsights.removeInsight(context, appId)
        return removedBytes
    }

    /** Cleans only expired CodeHub installer files; user downloads/media remain untouched. */
    @JvmStatic
    fun cleanupStaleFiles(context: Context) {
        val now = System.currentTimeMillis()
        val activeIds = jobs.filterValues { it.isActive }.keys
            .map { it.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100) }
            .toSet()
        listOfNotNull(context.getExternalFilesDir(null), context.filesDir).distinct().forEach { directory ->
            directory.listFiles()?.forEach fileLoop@ { file ->
                if (!file.isFile || !file.name.startsWith("store_")) return@fileLoop
                val safeId = file.name.removePrefix("store_").removeSuffix(".apk.part").removeSuffix(".apk")
                if (safeId in activeIds) return@fileLoop
                val maxAge = if (file.name.endsWith(".part")) 24L * 60 * 60 * 1000 else installerFileMaxAgeMs
                if (now - file.lastModified() > maxAge) file.delete()
            }
        }
    }

    private fun scheduleApkCleanup(appId: String, file: File, delayMs: Long = 24L * 60 * 60 * 1000) {
        main.postDelayed({
            file.delete()
            if (pendingApks[appId] == file) pendingApks.remove(appId)
        }, delayMs)
    }

    private fun openPackageInstaller(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return intent
    }

    private fun setState(appId: String, state: ApkTransferState) {
        mutableTransfers.update { it + (appId to state) }
    }

    /**
     * Descarga con reintentos y URLs de respaldo. El host principal de la
     * tienda (Render) puede devolver HTTP 502/503 cuando se duerme; en ese
     * caso se reintenta una vez y luego se cae al APK publicado en GitHub.
     */
    private suspend fun downloadWithFallback(
        urls: List<String>,
        destination: File,
        fallbackRepo: String?,
        onProgress: (Long, Long) -> Unit,
    ) {
        var lastError: Exception? = null
        val candidates = urls.distinct().filter { it.isNotBlank() }.toMutableList()
        // Último recurso: el APK del release más reciente en GitHub (el host
        // de la tienda devuelve HTTP 502 cuando se duerme).
        fallbackRepo?.let { repo ->
            resolveGithubReleaseApk(repo)?.let { candidates += it }
        }
        for (url in candidates) {
            var attempt = 0
            while (attempt < 3) {
                try {
                    download(url, destination, onProgress)
                    return
                } catch (error: Exception) {
                    lastError = error
                    if (!isTransient(error) || attempt >= 2) break
                    // El host de la tienda puede estar "dormido" (Render free):
                    // un par de esperas cortas levantan el servicio.
                    delay(if (attempt == 0) 6_000 else 12_000)
                    attempt++
                }
            }
        }
        throw lastError ?: IOException("No se pudo descargar el APK")
    }

    /** APK del último release público del repo (respaldo del host principal). */
    private suspend fun resolveGithubReleaseApk(repo: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            if (!repo.matches(Regex("^[\\w.-]+/[\\w.-]+$"))) return@runCatching null
            val connection = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                if (connection.responseCode !in 200..299) return@runCatching null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val assets = org.json.JSONObject(body).optJSONArray("assets") ?: org.json.JSONArray()
                var preferred: String? = null
                var any: String? = null
                for (index in 0 until assets.length()) {
                    val item = assets.optJSONObject(index) ?: continue
                    val name = item.optString("name")
                    if (!name.endsWith(".apk", true)) continue
                    val url = item.optString("browser_download_url")
                    if (!url.startsWith("https://")) continue
                    if (any == null) any = url
                    if (preferred == null && name.contains("release", true)) preferred = url
                }
                preferred ?: any
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    /** Errores de red o de servidor que valen la pena reintentar. */
    private fun isTransient(error: Exception): Boolean {
        val message = error.message.orEmpty()
        if (error is SocketTimeoutException || error is UnknownHostException) return true
        return listOf("HTTP 429", "HTTP 502", "HTTP 503", "HTTP 504").any { message.contains(it) }
    }

    private suspend fun download(url: String, destination: File, onProgress: (Long, Long) -> Unit) =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val source = URL(url)
                requireTrusted(source)
                connection = source.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.connect()
                requireTrusted(connection.url)
                if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode} descargando APK")
                val total = connection.contentLengthLong
                var copied = 0L
                var lastReport = 0L
                connection.inputStream.use { input ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastReport >= 120 || (total > 0 && copied >= total)) {
                                lastReport = now
                                onProgress(copied, total)
                            }
                        }
                    }
                }
                if (total > 0 && copied != total) throw IOException("Descarga incompleta: $copied de $total bytes")
                onProgress(copied, total)
            } finally {
                connection?.disconnect()
            }
        }

    private fun requireTrusted(url: URL) {
        require(url.protocol.equals("https", ignoreCase = true)) { "La descarga debe usar HTTPS" }
        val host = url.host.lowercase(Locale.ROOT)
        require(trustedHosts.any { host == it || host.endsWith(".$it") }) { "Origen de descarga no permitido" }
    }
}
