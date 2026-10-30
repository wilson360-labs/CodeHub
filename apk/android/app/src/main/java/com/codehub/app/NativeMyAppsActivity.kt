package com.codehub.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/** Installed apps from CodeHub's Open Source catalog, rendered with Android UI. */
class NativeMyAppsActivity : Activity() {
    companion object {
        const val EXTRA_APK_URL = "native_store_apk_url"
        const val EXTRA_APP_ID = "native_store_app_id"
        const val EXTRA_PREFER_SILENT = "native_my_apps_prefer_silent"
        private const val CATALOG_API = "https://codehub-98s6.onrender.com/api/apps"
        private const val UPDATES_API = "https://codehub-98s6.onrender.com/api/app-updates"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val installed = mutableListOf<AppEntry>()
    private lateinit var list: LinearLayout
    private lateinit var filterRow: LinearLayout
    private lateinit var search: EditText
    private lateinit var status: TextView
    private var filter = Filter.ALL
    private var refreshing = false
    private var updateCheckFailed = false
    private var hasLoaded = false

    private enum class Filter { ALL, UPDATES }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        SystemBars.fit(this)
        buildScreen()
        refreshApps()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF080810.toInt())
            setPadding(dp(18), dp(12), dp(18), 0)
        }
        val title = TextView(this).apply {
            text = "Mis apps"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        root.addView(title)
        root.addView(label("Instaladas desde el catálogo Open Source", 13f, 0xFF9AA0B8.toInt()))

        val searchRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        search = EditText(this).apply {
            setSingleLine(true)
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF77798A.toInt())
            hint = "Buscar en tus apps..."
            setPadding(dp(15), 0, dp(15), 0)
            setBackground(roundRect(0xFF151522.toInt(), 0xFF272738.toInt(), dp(14)))
        }
        searchRow.addView(search, LinearLayout.LayoutParams(0, dp(50), 1f))
        val refresh = Button(this).apply {
            text = "⟳"
            textSize = 21f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { refreshApps() }
        }
        searchRow.addView(refresh, LinearLayout.LayoutParams(dp(52), dp(50)))
        LinearLayout.LayoutParams(-1, dp(50)).also { it.topMargin = dp(16); root.addView(searchRow, it) }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
            override fun afterTextChanged(editable: Editable?) = Unit
        })

        val filters = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        filters.addView(filterRow)
        root.addView(filters, LinearLayout.LayoutParams(-1, dp(50)))

        status = label("Leyendo las apps instaladas…", 13f, 0xFF9AA0B8.toInt()).apply { gravity = Gravity.CENTER_VERTICAL }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(34)))
        val scroll = ScrollView(this).apply { isFillViewport = true }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(20))
        }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        SystemBars.applyInsets(this)
    }

    private fun refreshApps() {
        if (refreshing) return
        refreshing = true
        status.text = "Buscando actualizaciones…"
        executor.execute {
            try {
                val all = fetchCatalog()
                val packages = packageManager
                val local = all.mapNotNull { app ->
                    val pkg = app.packageName ?: return@mapNotNull null
                    val version = try {
                        @Suppress("DEPRECATION")
                        packages.getPackageInfo(pkg, 0).versionName ?: "desconocida"
                    } catch (_: Exception) { null }
                    if (version == null) null else app.copy(installedVersion = version)
                }
                var failedUpdates = false
                val updates = try { fetchUpdates(local) } catch (_: Exception) {
                    failedUpdates = true
                    emptyMap()
                }
                local.forEach { app ->
                    val update = updates[app.id]
                    app.latestVersion = update?.first.orEmpty()
                    app.downloadUrl = update?.second.orEmpty()
                }
                runOnUiThread {
                    installed.clear(); installed.addAll(local)
                    updateCheckFailed = failedUpdates
                    hasLoaded = true
                    renderFilters(); render()
                    refreshing = false
                }
            } catch (_: Exception) {
                runOnUiThread {
                    status.text = if (installed.isEmpty()) "No se pudo cargar. Toca ⟳ para reintentar."
                    else "Sin conexión · mostrando los datos anteriores"
                    refreshing = false
                }
            }
        }
    }

    private fun fetchCatalog(): List<AppEntry> {
        val json = request(CATALOG_API)
        val rows = if (json.trimStart().startsWith("[")) JSONArray(json) else JSONObject(json).optJSONArray("apps")
        val result = mutableListOf<AppEntry>()
        for (i in 0 until (rows?.length() ?: 0)) {
            val row = rows?.optJSONObject(i) ?: continue
            if (row.optString("source_repo").isBlank() || row.optString("packageName").isBlank()) continue
            result.add(AppEntry.from(row))
        }
        return result
    }

    private fun fetchUpdates(apps: List<AppEntry>): Map<String, Pair<String, String>> {
        if (apps.isEmpty()) return emptyMap()
        val payload = JSONObject().put("apps", JSONArray().apply {
            apps.forEach { app ->
                put(JSONObject()
                    .put("appId", app.id)
                    .put("version", app.installedVersion)
                    .put("source_repo", app.repo))
            }
        })
        val response = postJson(UPDATES_API, payload.toString())
        val rows = JSONArray(response)
        val updates = mutableMapOf<String, Pair<String, String>>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            if (!row.optBoolean("hasUpdate")) continue
            val id = row.optString("appId")
            if (id.isNotBlank()) updates[id] = row.optString("latestVersion") to row.optString("downloadUrl")
        }
        return updates
    }

    private fun request(url: String): String = openConnection(url, null)

    private fun postJson(url: String, body: String): String = openConnection(url, body)

    private fun openConnection(url: String, body: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            return BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
        } finally { connection.disconnect() }
    }

    private fun renderFilters() {
        filterRow.removeAllViews()
        Filter.values().forEach { option ->
            val selected = filter == option
            val chip = label(if (option == Filter.ALL) "Instaladas (${installed.size})" else "Actualizaciones (${installed.count { it.hasUpdate }})",
                13f, if (selected) 0xFF080810.toInt() else 0xFFD7D7E4.toInt(), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(15), 0, dp(15), 0)
                setBackground(roundRect(if (selected) 0xFF38EF7D.toInt() else 0xFF151522.toInt(), 0, dp(22)))
                setOnClickListener { filter = option; renderFilters(); render() }
            }
            filterRow.addView(chip, LinearLayout.LayoutParams(-2, dp(34)).apply { rightMargin = dp(8); topMargin = dp(7) })
        }
    }

    private fun render() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val query = if (::search.isInitialized) search.text.toString().trim().lowercase(Locale.ROOT) else ""
        val visible = installed.filter { app ->
            (filter == Filter.ALL || app.hasUpdate) &&
                "${app.name} ${app.category} ${app.repo} ${app.packageName}".lowercase(Locale.ROOT).contains(query)
        }
        status.text = when {
            refreshing -> "Buscando actualizaciones…"
            updateCheckFailed -> "${installed.size} instaladas · no se pudieron verificar actualizaciones"
            else -> "${installed.size} instaladas · ${installed.count { it.hasUpdate }} con actualización"
        }
        if (visible.isEmpty()) {
            val message = when {
                installed.isEmpty() -> "No hay apps del catálogo instaladas en este dispositivo."
                filter == Filter.UPDATES && updateCheckFailed -> "No pudimos comprobar las actualizaciones. Toca ⟳ para reintentar."
                filter == Filter.UPDATES -> "Tus apps están al día."
                else -> "No encontramos apps con esa búsqueda."
            }
            list.addView(label(message, 14f, 0xFF9AA0B8.toInt()).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, dp(140)))
        } else visible.forEach { list.addView(appCard(it)) }
    }

    private fun appCard(app: AppEntry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setBackground(roundRect(0xFF12121E.toInt(), 0xFF242435.toInt(), dp(18)))
        }
        val icon = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = roundRect(0xFF1D2C2A.toInt(), 0, dp(14))
            clipToOutline = true
            try {
                setImageDrawable(packageManager.getApplicationIcon(app.packageName!!))
            } catch (_: Exception) {
                loadCodeHubImage(app.imageUrl)
            }
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(50), dp(50)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11), 0, dp(8), 0) }
        info.addView(label(app.name, 15f, 0xFFFFFFFF.toInt(), true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        val versionLine = if (app.hasUpdate) "v${app.installedVersion}  →  v${app.latestVersion}" else "v${app.installedVersion} · Al día"
        val shownVersion = if (updateCheckFailed && !app.hasUpdate) "v${app.installedVersion} · sin verificar" else versionLine
        info.addView(label(shownVersion, 12f, if (app.hasUpdate) 0xFFFFC857.toInt() else 0xFF38EF7D.toInt()))
        info.addView(label(app.category, 11f, 0xFF9AA0B8.toInt()))
        card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        card.setOnClickListener { showAppActions(app) }
        if (app.hasUpdate) {
            val hasDirectApk = app.downloadUrl.endsWith(".apk", true) || app.downloadUrl.contains(".apk?", true)
            val update = Button(this).apply {
                text = if (hasDirectApk) "Actualizar" else "Ver release"
                isAllCaps = false
                textSize = 12f
                setOnClickListener { if (hasDirectApk) confirmUpdate(app) else openRelease(app) }
            }
            card.addView(update, LinearLayout.LayoutParams(-2, dp(42)))
        }
        return card.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) } }
    }

    private fun confirmUpdate(app: AppEntry) {
        val url = app.downloadUrl
        if (url.isBlank() || url == "#") {
            Toast.makeText(this, "No hay un APK directo disponible para esta versión", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Actualizar ${app.name}")
            .setMessage("Versión instalada: ${app.installedVersion}\nNueva versión: ${app.latestVersion}")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar") { _, _ ->
                setResult(RESULT_OK, Intent().apply {
                    putExtra(EXTRA_APK_URL, url)
                    putExtra(EXTRA_APP_ID, app.id)
                    putExtra(EXTRA_PREFER_SILENT, ShizukuInstaller.isReady())
                })
                finish()
            }
            .show()
    }

    private fun openRelease(app: AppEntry) {
        val url = app.downloadUrl.ifBlank { "https://github.com/${app.repo}/releases/latest" }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo abrir el release", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppActions(app: AppEntry) {
        val pkg = app.packageName ?: return
        AlertDialog.Builder(this)
            .setTitle(app.name)
            .setItems(arrayOf("Abrir", "Desinstalar", "Ver código fuente")) { _, which ->
                when (which) {
                    0 -> {
                        val launch = packageManager.getLaunchIntentForPackage(pkg)
                        if (launch != null) startActivity(launch)
                        else Toast.makeText(this, "Esta app no tiene una pantalla de inicio", Toast.LENGTH_SHORT).show()
                    }
                    1 -> try {
                        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
                    } catch (_: Exception) {
                        Toast.makeText(this, "No se pudo abrir el desinstalador", Toast.LENGTH_SHORT).show()
                    }
                    2 -> openRepository(app)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun openRepository(app: AppEntry) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${app.repo}")))
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo abrir GitHub", Toast.LENGTH_SHORT).show()
        }
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun roundRect(color: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (hasLoaded) refreshApps()
    }

    private data class AppEntry(
        val id: String,
        val name: String,
        val description: String,
        val category: String,
        val repo: String,
        val imageUrl: String,
        val packageName: String?,
        val installedVersion: String? = null,
        var latestVersion: String = "",
        var downloadUrl: String = "",
    ) {
        val hasUpdate get() = latestVersion.isNotBlank()

        companion object {
            fun from(row: JSONObject) = AppEntry(
                id = row.optString("appId"),
                name = row.optString("nombre", row.optString("appId")),
                description = row.optString("descripcion"),
                category = row.optString("categoria", "Utilidades"),
                repo = row.optString("source_repo"),
                imageUrl = row.optString("imagen"),
                packageName = row.optString("packageName").takeIf { it.isNotBlank() },
            )
        }
    }
}
