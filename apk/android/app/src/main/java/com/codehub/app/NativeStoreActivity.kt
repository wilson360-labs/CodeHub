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
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

/** Native Open Source catalog for the Android app. The web catalog remains the source of truth. */
class NativeStoreActivity : Activity() {
    companion object {
        const val EXTRA_APK_URL = "native_store_apk_url"
        const val EXTRA_APP_ID = "native_store_app_id"
        private const val API = "https://codehub-98s6.onrender.com/api/apps"
        private val WINDOWS_APIS = listOf(
            "https://raw.githubusercontent.com/RookieEnough/Orion-Data/main/apps.json",
            "https://cdn.jsdelivr.net/gh/RookieEnough/Orion-Data@main/apps.json",
            "https://raw.githack.com/RookieEnough/Orion-Data/main/apps.json",
        )
        private const val MY_APPS_REQUEST = 301
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val apps = mutableListOf<AppEntry>()
    private lateinit var appList: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var platformDock: LinearLayout
    private lateinit var subtitle: TextView
    private var category = "Todas"
    private var selectedPlatform = "Android"
    private var windowsCatalogFailed = false
    private var windowsCatalogLoading = true

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        SystemBars.fit(this)
        buildScreen()
        loadCatalog()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF080810.toInt())
            setPadding(dp(18), dp(12), dp(18), 0)
        }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(label("CodeHub Store", 21f, 0xFFFFFFFF.toInt(), true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val myApps = Button(this).apply {
            text = "Mis apps"
            textSize = 12f
            isAllCaps = false
            setOnClickListener { startActivityForResult(Intent(this@NativeStoreActivity, NativeMyAppsActivity::class.java), MY_APPS_REQUEST) }
        }
        top.addView(myApps, LinearLayout.LayoutParams(-2, dp(44)))
        val downloads = Button(this).apply {
            text = "↓"
            textSize = 20f
            isAllCaps = false
            contentDescription = "Descargas"
            setOnClickListener { startActivity(Intent(this@NativeStoreActivity, DownloadsActivity::class.java)) }
        }
        top.addView(downloads, LinearLayout.LayoutParams(dp(44), dp(44)))
        val refresh = Button(this).apply {
            text = "⟳"
            textSize = 22f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0x00000000)
            setOnClickListener { loadCatalog() }
        }
        top.addView(refresh, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(top)
        subtitle = label("Apps Open Source para Android", 13f, 0xFF9AA0B8.toInt())
        root.addView(subtitle)

        search = EditText(this).apply {
            setSingleLine(true)
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF77798A.toInt())
            hint = "Buscar apps, categorías..."
            setPadding(dp(16), 0, dp(16), 0)
            setBackground(roundRect(0xFF151522.toInt(), 0xFF272738.toInt(), dp(14)))
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(18) })
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
            override fun afterTextChanged(editable: Editable?) = Unit
        })

        val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipScroll.addView(chips)
        root.addView(chipScroll, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(8) })

        status = label("Conectando al catálogo…", 13f, 0xFF9AA0B8.toInt()).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(34)))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        appList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(20))
        }
        scroll.addView(appList)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(buildPlatformDock(), LinearLayout.LayoutParams(-1, dp(68)).apply {
            topMargin = dp(8)
            bottomMargin = dp(8)
        })
        setContentView(root)
        SystemBars.applyInsets(this)
    }

    private fun buildPlatformDock(): View {
        platformDock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setBackground(roundRect(0xFF151522.toInt(), 0xFF29293B.toInt(), dp(24)))
        }
        renderPlatformDock()
        return platformDock
    }

    private fun renderPlatformDock() {
        platformDock.removeAllViews()
        listOf("Android" to "🤖", "Windows" to "🪟").forEach { (platform, icon) ->
            val active = selectedPlatform == platform
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                setBackground(roundRect(if (active) 0xFF244E3D.toInt() else 0x00151522, 0, dp(19)))
                contentDescription = "Mostrar apps para $platform"
                setOnClickListener { selectPlatform(platform) }
            }
            item.addView(label(icon, 19f, 0xFFFFFFFF.toInt()), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            item.addView(label(platform, 14f, if (active) 0xFF68F0A9.toInt() else 0xFFD7D7E4.toInt(), true))
            platformDock.addView(item, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
    }

    private fun selectPlatform(platform: String) {
        if (selectedPlatform == platform) return
        selectedPlatform = platform
        category = "Todas"
        if (::search.isInitialized) search.setText("")
        subtitle.text = if (platform == "Android") "Apps Open Source para Android" else "Aplicaciones para Windows · requieren una PC"
        renderPlatformDock()
        renderChips()
        render()
    }

    private fun loadCatalog() {
        status.text = "Actualizando catálogo…"
        windowsCatalogLoading = true
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(API).openConnection() as HttpURLConnection
                connection.connectTimeout = 12_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                if (code !in 200..299) error("HTTP $code")
                val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
                val json = body.trim()
                val rows: JSONArray? = if (json.startsWith("[")) JSONArray(json)
                    else JSONObject(json).optJSONArray("apps")
                val loaded = mutableListOf<AppEntry>()
                for (index in 0 until (rows?.length() ?: 0)) {
                    val row = rows?.optJSONObject(index) ?: continue
                    if (row.optString("source_repo").isEmpty()) continue
                    val app = AppEntry.from(row)
                    if (app.id.isNotEmpty()) loaded.add(app)
                }
                loaded.sortBy { it.name.lowercase(Locale.ROOT) }
                runOnUiThread {
                    apps.clear()
                    apps.addAll(loaded)
                    if (apps.none { it.category == category } && category != "Todas") category = "Todas"
                    renderChips()
                    render()
                }
                val windows = fetchWindowsCatalog()
                windowsCatalogFailed = windows == null
                windowsCatalogLoading = false
                runOnUiThread {
                    if (windows != null) {
                        apps.removeAll { it.platform == "Windows" }
                        apps.addAll(windows)
                        if (selectedPlatform == "Windows") renderChips()
                    }
                    render()
                }
            } catch (error: Exception) {
                windowsCatalogLoading = false
                runOnUiThread {
                    status.text = if (apps.isEmpty()) "No se pudo conectar. Toca ⟳ para reintentar."
                    else "No se pudo actualizar · mostrando el catálogo anterior"
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    /** Windows entries are browse-only on Android; the source catalog stays owned by Orion Data. */
    private fun fetchWindowsCatalog(): List<AppEntry>? {
        WINDOWS_APIS.forEach { endpoint ->
            var connection: HttpURLConnection? = null
            try {
                connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 12_000
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode !in 200..299) return@forEach
                val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
                val rows = JSONArray(body)
                val entries = mutableListOf<AppEntry>()
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    if (!row.optString("platform").equals("PC", ignoreCase = true)) continue
                    AppEntry.fromWindows(row)?.let(entries::add)
                }
                return entries
            } catch (_: Exception) {
                // Try Orion Data's CDN mirrors when raw GitHub is unavailable.
            } finally {
                connection?.disconnect()
            }
        }
        return null
    }

    private fun renderChips() {
        chips.removeAllViews()
        val categories = linkedSetOf("Todas")
        apps.filter { it.platform == selectedPlatform }.forEach { categories.add(it.category) }
        categories.forEach { value ->
            val chip = label(value, 13f, if (category == value) 0xFF080810.toInt() else 0xFFD7D7E4.toInt(), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(15), 0, dp(15), 0)
                setBackground(roundRect(if (category == value) 0xFF38EF7D.toInt() else 0xFF151522.toInt(), 0, dp(22)))
                setOnClickListener {
                    category = value
                    renderChips()
                    render()
                }
            }
            chips.addView(chip, LinearLayout.LayoutParams(-2, dp(36)).apply {
                rightMargin = dp(8)
                topMargin = dp(8)
            })
        }
    }

    private fun render() {
        if (!::appList.isInitialized) return
        appList.removeAllViews()
        val query = if (::search.isInitialized) search.text.toString().trim().lowercase(Locale.ROOT) else ""
        val filtered = apps.filter { app ->
            app.platform == selectedPlatform && (category == "Todas" || app.category == category) &&
                ("${app.name} ${app.description} ${app.category} ${app.repo}".lowercase(Locale.ROOT).contains(query))
        }
        status.text = when {
            apps.none { it.platform == selectedPlatform } && selectedPlatform == "Windows" && windowsCatalogLoading -> "Cargando catálogo de Windows…"
            apps.none { it.platform == selectedPlatform } && selectedPlatform == "Windows" && windowsCatalogFailed -> "No se pudo cargar el catálogo de Windows. Toca ↻ para reintentar."
            apps.none { it.platform == selectedPlatform } -> status.text
            else -> "${filtered.size} aplicaciones disponibles"
        }
        filtered.forEach { appList.addView(appCard(it)) }
        if (filtered.isEmpty() && selectedPlatform == "Windows" && apps.none { it.platform == "Windows" }) {
            val message = when {
                windowsCatalogLoading -> "Cargando catálogo de Windows…"
                windowsCatalogFailed -> "No se pudo cargar el catálogo de Windows. Toca ↻ para reintentar."
                else -> "No hay aplicaciones de Windows disponibles."
            }
            appList.addView(label(message, 14f, 0xFF9AA0B8.toInt()).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, dp(120)))
            return
        }
        if (filtered.isEmpty() && apps.isNotEmpty()) {
            appList.addView(label("No encontramos apps con esa búsqueda.", 14f, 0xFF9AA0B8.toInt()).apply {
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, dp(120)))
        }
    }

    private fun appCard(app: AppEntry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackground(roundRect(0xFF12121E.toInt(), 0xFF242435.toInt(), dp(18)))
            setOnClickListener { showDetails(app) }
        }
        val icon = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = roundRect(0xFF1D2C2A.toInt(), 0, dp(14))
            clipToOutline = true
            loadCodeHubImage(app.imageUrl)
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(54), dp(54)))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        info.addView(label(app.name, 16f, 0xFFFFFFFF.toInt(), true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(label(app.category + if (app.version.isEmpty()) "" else " · ${app.version}", 12f, 0xFF38EF7D.toInt()).apply {
            maxLines = 1
        })
        info.addView(label(app.description.ifEmpty { app.repo }, 12f, 0xFF9AA0B8.toInt()).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(label("›", 28f, 0xFF9AA0B8.toInt()))
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }
    }

    private fun showDetails(app: AppEntry) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val icon = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = roundRect(0xFF1D2C2A.toInt(), 0, dp(18))
            clipToOutline = true
            loadCodeHubImage(app.imageUrl)
        }
        content.addView(icon, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(12) })
        content.addView(label(app.category.uppercase(Locale.ROOT), 12f, 0xFF38EF7D.toInt(), true))
        content.addView(label(app.name, 23f, 0xFFFFFFFF.toInt(), true).apply { setPadding(0, dp(7), 0, dp(8)) })
        content.addView(label(app.description.ifEmpty { "Aplicación de código abierto." }, 14f, 0xFFD0D0DE.toInt()).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        content.addView(label("Versión ${app.version.ifEmpty { "disponible" }}\n${app.repo}", 12f, 0xFF9AA0B8.toInt()).apply {
            setPadding(0, dp(12), 0, 0)
        })
        if (app.changelog.isNotBlank()) {
            content.addView(label("Novedades", 14f, 0xFFFFFFFF.toInt(), true).apply { setPadding(0, dp(14), 0, dp(5)) })
            content.addView(label(app.changelog, 13f, 0xFFB7B8C8.toInt()).apply {
                maxLines = 6
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        content.addView(label("Open Source · ${if (app.verified) "verificado" else "Android"}", 12f, 0xFF38EF7D.toInt()).apply {
            setPadding(0, dp(12), 0, 0)
        })

        if (app.platform == "Windows") {
            content.addView(label("Ficha informativa: abre el enlace desde una PC con Windows para instalar la aplicación.", 12f, 0xFFFFC857.toInt()).apply {
                setPadding(0, dp(10), 0, 0)
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .setNegativeButton("Cerrar", null)
            .setNeutralButton("Ver código", null)
            .setPositiveButton("Instalar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { startInstall(app) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${app.repo}")))
                } catch (_: Exception) {
                    Toast.makeText(this, "No se pudo abrir GitHub", Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (app.platform == "Windows") {
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Sitio oficial", null)
            dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Abrir enlace", null)
            dialog.setOnShowListener {
                val downloadTarget = app.downloadUrl.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: app.repo
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { openExternal(downloadTarget) }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { openExternal(app.repo) }
            }
        }
        dialog.show()
    }

    private fun startInstall(app: AppEntry) {
        if (app.platform != "Android") return
        try {
            val url = "https://codehub-98s6.onrender.com/api/apps/${URLEncoder.encode(app.id, "UTF-8")}/download"
            setResult(RESULT_OK, Intent().apply {
                putExtra(EXTRA_APK_URL, url)
                putExtra(EXTRA_APP_ID, app.id)
            })
            finish()
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo preparar la instalación", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openExternal(rawUrl: String) {
        val target = rawUrl.trim()
        if (!target.startsWith("https://") && !target.startsWith("http://")) {
            Toast.makeText(this, "Esta app no tiene un enlace disponible", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        } catch (_: Exception) {
            Toast.makeText(this, "No se pudo abrir el enlace", Toast.LENGTH_SHORT).show()
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

    @Deprecated("Activity result is relayed to MainActivity for the shared APK installer")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MY_APPS_REQUEST && resultCode == RESULT_OK && data != null) {
            setResult(RESULT_OK, data)
            finish()
        }
    }

    private data class AppEntry(
        val id: String,
        val name: String,
        val description: String,
        val category: String,
        val version: String,
        val repo: String,
        val imageUrl: String,
        val changelog: String,
        val verified: Boolean,
        val platform: String,
        val downloadUrl: String,
    ) {
        companion object {
            fun from(row: JSONObject) = AppEntry(
                id = row.optString("appId"),
                name = row.optString("nombre", row.optString("appId")),
                description = row.optString("descripcion"),
                category = row.optString("categoria", "Utilidades"),
                version = row.optString("version"),
                repo = row.optString("source_repo"),
                imageUrl = row.optString("imagen"),
                changelog = row.optString("changelog"),
                verified = row.optBoolean("verified", true),
                platform = "Android",
                downloadUrl = row.optString("enlace"),
            )

            fun fromWindows(row: JSONObject): AppEntry? {
                val name = row.optString("name").trim()
                if (name.isBlank()) return null
                val repository = row.optString("repoUrl").ifBlank {
                    val repo = row.optString("githubRepo")
                    if (repo.contains("://")) repo else if (repo.isNotBlank()) "https://github.com/$repo" else row.optString("officialSite")
                }
                return AppEntry(
                    id = row.optString("id").ifBlank { name },
                    name = name,
                    description = row.optString("description"),
                    category = row.optString("category", "Utilidades"),
                    version = row.optString("latestVersion").ifBlank { row.optString("version", "Latest") },
                    repo = repository,
                    imageUrl = row.optString("icon"),
                    changelog = "",
                    verified = false,
                    platform = "Windows",
                    downloadUrl = row.optString("downloadUrl"),
                )
            }
        }
    }
}
