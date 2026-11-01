package com.codehub.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Update-tracker state for the CodeHub Store, modelled after the Orion
 * AppTracker/IgnoredUpdate semantics but tailored to CodeHub:
 *
 *  - Version-bound ignores ("Ignorar esta versión"): hidden while the catalog
 *    offers the same latestVersion; resurface automatically when any newer
 *    version lands. Cheap and robust: pure string compare, no semantic parse.
 *  - Permanent ignores stay in the legacy prefs stringSet of pre-existing
 *    NativeMyAppsActivity ("codehub_store/ignored_updates"), untouched.
 *  - A capped history log of update-installs per app can be written by the
 *    UI rows (which app and what version at what time).
 *
 * Storage: org.json in one prefs file so the debug schema is inspectable.
 */
object NativeAppUpdateTracker {
    private const val PREFS = "codehub_tracker"
    private const val KEY_IGNORED = "ignored"      // JSON map appId -> {version, timestamp}
    private const val KEY_HISTORY = "history"      // JSON array of {appId, version, at}

    data class IgnoredUpdate(val version: String, val timestamp: Long)
    data class InstalledUpdate(val appId: String, val version: String, val at: Long)

    private var loaded = false
    private val ignoredCache = LinkedHashMap<String, IgnoredUpdate>()
    private val historyCache = mutableListOf<InstalledUpdate>()

    /** Semantic comparator: numeric segments when both parse, else flat compare. */
    fun isVersionNewer(candidate: String, reference: String): Boolean {
        val candidate = candidate.trim(); val reference = reference.trim()
        if (candidate.isBlank() || reference.isBlank()) return candidate.isNotBlank()
        val parts = candidate.split('.'); val expected = reference.split('.')
        val size = maxOf(parts.size, expected.size)
        repeat(size) { index ->
            val left = parts.getOrNull(index)?.trim()?.toLongOrNull()
            val right = expected.getOrNull(index)?.trim()?.toLongOrNull()
            if (left != null && right != null) {
                if (left != right) return left > right
            } else {
                val leftText = parts.getOrNull(index).orEmpty()
                val rightText = expected.getOrNull(index).orEmpty()
                if (leftText != rightText) return leftText > rightText
            }
        }
        return false
    }

    /** Loads (once) and caches the ignored map. */
    fun ignored(context: Context): Map<String, IgnoredUpdate> {
        ensure(context)
        return ignoredCache.toMap()
    }

    /** True when hidden by a version-bound ignore whose version still matches. */
    fun isSnoozed(context: Context, appId: String, latestVersion: String): Boolean {
        ensure(context)
        val entry = ignoredCache[appId] ?: return false
        return entry.version.isNotBlank() && entry.version == latestVersion.trim()
    }

    /** Hides this exact catalog version; a new release resurfaces updates. */
    @JvmStatic
    fun ignoreVersion(context: Context, appId: String, version: String) {
        ensure(context)
        ignoredCache[appId] = IgnoredUpdate(version.trim(), System.currentTimeMillis())
        persist(context)
    }

    /** Clears any version-bound ignore for the app. */
    @JvmStatic
    fun restoreIgnored(context: Context, appId: String) {
        ensure(context)
        if (ignoredCache.remove(appId) != null) persist(context)
    }

    /** Update history per app: recorded when the install completes or opens installer. */
    fun recordInstalled(context: Context, appId: String, version: String) {
        ensure(context)
        historyCache.add(0, InstalledUpdate(appId, version.trim(), System.currentTimeMillis()))
        while (historyCache.size > 30) historyCache.removeAt(historyCache.size - 1)
        persist(context)
    }

    fun history(context: Context): List<InstalledUpdate> {
        ensure(context)
        return historyCache.toList()
    }

    private fun ensure(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val ignored = JSONObject(prefs.getString(KEY_IGNORED, "{}").orEmpty())
            ignored.keys().forEach { key ->
                val value = ignored.optJSONObject(key) ?: return@forEach
                val version = value.optString("version").orEmpty()
                if (version.isNotBlank()) {
                    ignoredCache[key] = IgnoredUpdate(version, value.optLong("timestamp"))
                }
            }
            historyCache.clear()
            val history = prefs.optJSONArray(KEY_HISTORY) ?: JSONArray()
            repeat(history.length()) { at ->
                val row = history.optJSONObject(at) ?: return@repeat
                val appId = row.optString("appId").orEmpty()
                if (appId.isNotBlank()) {
                    historyCache.add(InstalledUpdate(appId, row.optString("version"), row.optLong("at")))
                }
            }
        }
    }

    private fun persist(context: Context) {
        runCatching {
            val root = JSONObject()
            ignoredCache.entries.forEach { (key, ignored) ->
                root.put(key, JSONObject().apply {
                    put("version", ignored.version)
                    put("timestamp", ignored.timestamp)
                })
            }
            val past = JSONArray()
            historyCache.forEach { stored ->
                past.put(JSONObject().apply {
                    put("appId", stored.appId)
                    put("version", stored.version)
                    put("at", stored.at)
                })
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_IGNORED, root.toString()).putString(KEY_HISTORY, past.toString()).apply()
        }
    }
}
