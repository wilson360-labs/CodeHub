package com.codehub.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** In-app APK transfer and install handoff for the native CodeHub Store. */
object NativeStoreApkInstaller {
    interface Listener {
        fun onProgress(downloadedBytes: Long, totalBytes: Long)
        fun onFinished(status: Status, message: String = "")
    }

    enum class Status { INSTALLED, INSTALLER_OPENED, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private val trustedHosts = setOf("codehub-98s6.onrender.com", "github.com", "githubusercontent.com", "github.io", "jsdelivr.net")

    fun install(activity: Activity, apkUrl: String, appId: String, expectedPackage: String?, preferSilent: Boolean, listener: Listener) {
        Thread {
            var file: File? = null
            try {
                val source = URL(apkUrl)
                requireTrusted(source)
                // These locations are exposed by the existing FileProvider paths.
                val dir = activity.getExternalFilesDir(null) ?: activity.filesDir
                val safeId = appId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(100)
                val destination = File(dir, "store_$safeId.apk")
                file = destination

                val connection = source.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                try {
                    connection.connect()
                    requireTrusted(connection.url)
                    if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode} descargando APK")
                    val total = connection.contentLengthLong
                    var lastReport = 0L
                    connection.inputStream.use { input -> destination.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var copied = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastReport >= 120 || (total > 0 && copied >= total)) {
                                lastReport = now
                                val reported = copied
                                main.post {
                                    if (!activity.isFinishing && !activity.isDestroyed) listener.onProgress(reported, total)
                                }
                            }
                        }
                    } }
                } finally { connection.disconnect() }

                @Suppress("DEPRECATION")
                val archive = activity.packageManager.getPackageArchiveInfo(destination.absolutePath, 0)
                if (archive?.packageName.isNullOrBlank()) error("El archivo descargado no es un APK válido")
                if (!expectedPackage.isNullOrBlank() && expectedPackage != archive?.packageName)
                    error("El paquete APK no coincide con la app seleccionada")

                if (preferSilent && ShizukuInstaller.isReady()) {
                    try {
                        ShizukuInstaller.installSilently(destination.absolutePath)
                        destination.delete()
                        main.post {
                            if (!activity.isFinishing && !activity.isDestroyed) listener.onFinished(Status.INSTALLED)
                        }
                        return@Thread
                    } catch (_: Exception) { /* Fall back to Android's confirmed installer. */ }
                }

                val apk = destination
                main.post {
                    try {
                        if (activity.isFinishing || activity.isDestroyed) {
                            apk.delete()
                            listener.onFinished(Status.FAILED, "La instalación se canceló al salir de la tienda.")
                            return@post
                        }
                        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", apk)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        activity.startActivity(intent)
                        main.postDelayed({ apk.delete() }, 30 * 60 * 1000L)
                        listener.onFinished(Status.INSTALLER_OPENED)
                    } catch (error: Exception) {
                        apk.delete()
                        listener.onFinished(Status.FAILED, error.message ?: "No se pudo abrir el instalador")
                    }
                }
            } catch (error: Exception) {
                file?.delete()
                main.post {
                    if (!activity.isFinishing && !activity.isDestroyed) listener.onFinished(Status.FAILED, error.message ?: "Falló la descarga")
                }
            }
        }.start()
    }

    private fun requireTrusted(url: URL) {
        require(url.protocol.equals("https", ignoreCase = true)) { "La descarga debe usar HTTPS" }
        val host = url.host.lowercase(Locale.ROOT)
        require(trustedHosts.any { host == it || host.endsWith(".$it") }) { "Origen de descarga no permitido" }
    }
}
