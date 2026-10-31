package com.codehub.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** State of a user-initiated APK transfer. No DownloadManager or OS download notification is used. */
sealed interface ApkTransferState {
    data object Idle : ApkTransferState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ApkTransferState
    data object Verifying : ApkTransferState
    data object ReadyToInstall : ApkTransferState
    data object AwaitingUserConfirmation : ApkTransferState
    data object Installed : ApkTransferState
    data class Failed(val message: String) : ApkTransferState
    data object Cancelled : ApkTransferState
}

/** In-app APK download and Android installer handoff used by Store and My Apps. */
object NativeStoreApkInstaller {
    interface Listener {
        fun onProgress(downloadedBytes: Long, totalBytes: Long)
        fun onFinished(status: Status, message: String = "")
    }

    enum class Status { INSTALLED, INSTALLER_OPENED, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val trustedHosts = setOf(
        "codehub-98s6.onrender.com", "github.com", "githubusercontent.com", "github.io", "jsdelivr.net",
    )
    private val mutableTransfers = MutableStateFlow<Map<String, ApkTransferState>>(emptyMap())

    /** Observable UI state; cards can collect this flow without owning download implementation. */
    val transfers: StateFlow<Map<String, ApkTransferState>> = mutableTransfers.asStateFlow()

    fun install(
        activity: Activity,
        apkUrl: String,
        appId: String,
        expectedPackage: String?,
        preferSilent: Boolean,
        listener: Listener,
    ) {
        if (jobs[appId]?.isActive == true) {
            listener.onFinished(Status.FAILED, "La descarga de esta app ya está en curso.")
            return
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            val safeId = appId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
            val directory = activity.getExternalFilesDir(null) ?: activity.filesDir
            val destination = File(directory, "store_$safeId.apk")
            val partial = File(directory, "store_$safeId.apk.part")
            try {
                setState(appId, ApkTransferState.Downloading(0, -1))
                download(apkUrl, partial) { downloaded, total ->
                    setState(appId, ApkTransferState.Downloading(downloaded, total))
                    main.post {
                        if (!activity.isFinishing && !activity.isDestroyed) listener.onProgress(downloaded, total)
                    }
                }
                setState(appId, ApkTransferState.Verifying)
                if (destination.exists() && !destination.delete()) throw IOException("No se pudo reemplazar el APK anterior")
                if (!partial.renameTo(destination)) throw IOException("No se pudo preparar el APK descargado")

                val archive = withContext(Dispatchers.IO) {
                    @Suppress("DEPRECATION")
                    activity.packageManager.getPackageArchiveInfo(destination.absolutePath, 0)
                }
                if (archive?.packageName.isNullOrBlank()) error("El archivo descargado no es un APK válido")
                if (!expectedPackage.isNullOrBlank() && expectedPackage != archive?.packageName) {
                    error("El paquete APK no coincide con la app seleccionada")
                }

                setState(appId, ApkTransferState.ReadyToInstall)
                if (preferSilent && ShizukuInstaller.isReady()) {
                    try {
                        withContext(Dispatchers.IO) { ShizukuInstaller.installSilently(destination.absolutePath) }
                        destination.delete()
                        setState(appId, ApkTransferState.Installed)
                        main.post {
                            if (!activity.isFinishing && !activity.isDestroyed) listener.onFinished(Status.INSTALLED)
                        }
                        return@launch
                    } catch (_: Exception) {
                        // Shizuku failure falls back to Android's explicit user confirmation.
                    }
                }

                main.post {
                    try {
                        if (activity.isFinishing || activity.isDestroyed) {
                            destination.delete()
                            listener.onFinished(Status.FAILED, "La instalación se canceló al salir de la tienda.")
                            return@post
                        }
                        setState(appId, ApkTransferState.AwaitingUserConfirmation)
                        val uri = FileProvider.getUriForFile(
                            activity, "${activity.packageName}.fileprovider", destination,
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        activity.startActivity(intent)
                        // Keep the file available while Package Installer reads it.
                        main.postDelayed({ destination.delete() }, 30 * 60 * 1000L)
                        listener.onFinished(Status.INSTALLER_OPENED)
                    } catch (error: Exception) {
                        destination.delete()
                        setState(appId, ApkTransferState.Failed(error.message ?: "No se pudo abrir el instalador"))
                        listener.onFinished(Status.FAILED, error.message ?: "No se pudo abrir el instalador")
                    }
                }
            } catch (cancelled: CancellationException) {
                partial.delete()
                destination.delete()
                setState(appId, ApkTransferState.Cancelled)
                throw cancelled
            } catch (error: Exception) {
                partial.delete()
                destination.delete()
                val message = error.message ?: "Falló la descarga"
                setState(appId, ApkTransferState.Failed(message))
                main.post {
                    if (!activity.isFinishing && !activity.isDestroyed) listener.onFinished(Status.FAILED, message)
                }
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
    }

    private fun setState(appId: String, state: ApkTransferState) {
        mutableTransfers.update { it + (appId to state) }
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
