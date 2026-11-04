package com.codehub.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Local, key-less APK inspection: signature digest, dangerous permissions in
 * human language, version codes, min/target SDK and SHA-256. No VirusTotal
 * (that needs a paid API key) per the locked scope. Results are cached per
 * app so the "Seguridad" tab still shows something if the APK was deleted.
 *
 * Also keeps a per-app download history (app, version, size, timestamp).
 */
object NativeApkInsights {
    data class Insight(
        val appId: String,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val minSdk: Int,
        val targetSdk: Int,
        val permissions: List<String>,
        val dangerousPermissions: List<String>,
        val sha256: String,
        val sizeBytes: Long,
        val signerDigest: String,
        val analyzedAt: Long,
    )

    data class DownloadRecord(
        val appId: String,
        val version: String,
        val sizeBytes: Long,
        val at: Long,
        val appName: String = appId,
        val packageName: String = "",
    )

    private const val PREFS = "codehub_insights"
    private const val KEY_INSIGHTS = "insights"
    private const val KEY_HISTORY = "downloads"

    private val dangerous = mapOf(
        "android.permission.CAMERA" to "Cámara",
        "android.permission.RECORD_AUDIO" to "Micrófono",
        "android.permission.ACCESS_FINE_LOCATION" to "Ubicación precisa",
        "android.permission.ACCESS_COARSE_LOCATION" to "Ubicación aproximada",
        "android.permission.ACCESS_BACKGROUND_LOCATION" to "Ubicación en segundo plano",
        "android.permission.READ_CONTACTS" to "Contactos",
        "android.permission.READ_CALL_LOG" to "Historial de llamadas",
        "android.permission.READ_SMS" to "SMS",
        "android.permission.SEND_SMS" to "Envío de SMS",
        "android.permission.READ_EXTERNAL_STORAGE" to "Almacenamiento",
        "android.permission.WRITE_EXTERNAL_STORAGE" to "Escritura en almacenamiento",
        "android.permission.READ_PHONE_STATE" to "Estado del teléfono",
        "android.permission.BODY_SENSORS" to "Sensores corporales",
        "android.permission.ACTIVITY_RECOGNITION" to "Actividad física",
        "android.permission.SYSTEM_ALERT_WINDOW" to "Ventanas sobre otras apps",
        "android.permission.REQUEST_INSTALL_PACKAGES" to "Instalación de paquetes",
    )

    fun analyze(context: Context, appId: String, file: File): Insight? = runCatching {
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return null
        val permissions = info.requestedPermissions?.toList() ?: emptyList()
        Insight(
            appId = appId,
            packageName = info.packageName ?: "",
            versionName = info.versionName ?: "",
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong(),
            minSdk = info.applicationInfo?.minSdkVersion ?: 0,
            targetSdk = info.applicationInfo?.targetSdkVersion ?: 0,
            permissions = permissions,
            dangerousPermissions = permissions.mapNotNull { dangerous[it] }.distinct(),
            sha256 = sha256File(file),
            sizeBytes = file.length(),
            signerDigest = runCatching {
                val sigs = info.signatures ?: emptyArray()
                sigs.firstOrNull()?.toByteArray()?.let { sha256Bytes(it) } ?: ""
            }.getOrDefault(""),
            analyzedAt = System.currentTimeMillis(),
        )
    }.getOrNull()

    fun cache(context: Context, appId: String): Insight? = runCatching {
        val root = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_INSIGHTS, "{}").orEmpty())
        val obj = root.optJSONObject(appId) ?: return null
        Insight(
            appId = appId,
            packageName = obj.optString("packageName"),
            versionName = obj.optString("versionName"),
            versionCode = obj.optLong("versionCode"),
            minSdk = obj.optInt("minSdk"),
            targetSdk = obj.optInt("targetSdk"),
            permissions = obj.optJSONArray("permissions")?.let { (0 until it.length()).map { i -> it.optString(i) } } ?: emptyList(),
            dangerousPermissions = obj.optJSONArray("dangerous")?.let { (0 until it.length()).map { i -> it.optString(i) } } ?: emptyList(),
            sha256 = obj.optString("sha256"),
            sizeBytes = obj.optLong("sizeBytes"),
            signerDigest = obj.optString("signerDigest"),
            analyzedAt = obj.optLong("analyzedAt"),
        )
    }.getOrNull()

    fun cacheInsight(context: Context, insight: Insight) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val root = JSONObject(prefs.getString(KEY_INSIGHTS, "{}").orEmpty())
            root.put(insight.appId, JSONObject().apply {
                put("packageName", insight.packageName)
                put("versionName", insight.versionName)
                put("versionCode", insight.versionCode)
                put("minSdk", insight.minSdk)
                put("targetSdk", insight.targetSdk)
                put("permissions", JSONArray(insight.permissions))
                put("dangerous", JSONArray(insight.dangerousPermissions))
                put("sha256", insight.sha256)
                put("sizeBytes", insight.sizeBytes)
                put("signerDigest", insight.signerDigest)
                put("analyzedAt", insight.analyzedAt)
            })
            prefs.edit().putString(KEY_INSIGHTS, root.toString()).apply()
        }
    }

    /** Removes only the cached APK inspection when the related app is uninstalled. */
    fun removeInsight(context: Context, appId: String) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val root = JSONObject(prefs.getString(KEY_INSIGHTS, "{}").orEmpty())
            if (root.has(appId)) {
                root.remove(appId)
                prefs.edit().putString(KEY_INSIGHTS, root.toString()).apply()
            }
        }
    }

    fun recordDownload(
        context: Context,
        appId: String,
        version: String,
        sizeBytes: Long,
        appName: String = appId,
        packageName: String = "",
    ) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val array = JSONArray(prefs.getString(KEY_HISTORY, "[]").orEmpty())
            val entry = JSONObject().apply {
                put("appId", appId)
                put("version", version)
                put("sizeBytes", sizeBytes)
                put("at", System.currentTimeMillis())
                put("appName", appName)
                put("packageName", packageName)
            }
            val next = JSONArray()
            next.put(entry)
            for (i in 0 until minOf(array.length(), 49)) {
                val old = array.optJSONObject(i) ?: continue
                if (old.optString("appId") == appId && old.optString("version") == version) continue
                next.put(old)
            }
            prefs.edit().putString(KEY_HISTORY, next.toString()).apply()
        }
    }

    fun downloads(context: Context, appId: String? = null): List<DownloadRecord> = runCatching {
        val array = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, "[]").orEmpty())
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val id = obj.optString("appId")
            if (appId != null && id != appId) return@mapNotNull null
            DownloadRecord(
                appId = id,
                version = obj.optString("version"),
                sizeBytes = obj.optLong("sizeBytes"),
                at = obj.optLong("at"),
                appName = obj.optString("appName", id).ifBlank { id },
                packageName = obj.optString("packageName"),
            )
        }
    }.getOrDefault(emptyList())

    private fun sha256File(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun sha256Bytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
