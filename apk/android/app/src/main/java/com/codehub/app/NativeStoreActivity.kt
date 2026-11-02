package com.codehub.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject

/** Native Open Source catalog for the Android app. The web catalog remains the source of truth. */
class NativeStoreActivity : Activity() {
    companion object {
        const val EXTRA_APK_URL = "native_store_apk_url"
        const val EXTRA_APP_ID = "native_store_app_id"
        const val EXTRA_PACKAGE_NAME = "native_store_package_name"
        private const val API = "https://codehub-98s6.onrender.com/api/apps"
        private const val RATINGS_API = "https://codehub-98s6.onrender.com/api/ratings"
        private val WINDOWS_APIS = listOf(
            "https://raw.githubusercontent.com/RookieEnough/Orion-Data/main/apps.json",
            "https://cdn.jsdelivr.net/gh/RookieEnough/Orion-Data@main/apps.json",
            "https://raw.githack.com/RookieEnough/Orion-Data/main/apps.json",
        )
        private const val MY_APPS_REQUEST = 301
    }

    private enum class StoreSort(val label: String) { HOME("Inicio"), NAME("A–Z"), RATING("Mejor valoradas"), UPDATED("Novedades") }

    private val executor = Executors.newSingleThreadExecutor()
    private val apps = mutableListOf<AppEntry>()
    private val activeTransfers = mutableMapOf<String, TransferProgress>()
    private val transferViews = mutableMapOf<String, Pair<ProgressBar, TextView>>()
    private val heroHandler = Handler(Looper.getMainLooper())
    private lateinit var appList: LinearLayout
    private lateinit var chips: LinearLayout
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var platformDock: LinearLayout
    private lateinit var subtitle: TextView
    private lateinit var favoritesButton: Button
    private lateinit var sortButton: Button
    private var category = "Todas"
    private var selectedPlatform = "Android"
    private var sort = StoreSort.HOME
    private var favoritesOnly = false
    private val favoriteIds = mutableSetOf<String>()
    private var windowsCatalogFailed = false
    private var windowsCatalogLoading = true
    private var catalogLoaded = false
    private var catalogFailed = false
    private var detailDialog: android.app.Dialog? = null
    private var detailRing: NativeRingButton? = null
    private var detailAppId: String? = null
    private var detailCta: TextView? = null
    private var heroFrame: FrameLayout? = null
    private var heroApps: List<AppEntry> = emptyList()
    private var heroIndex = 0

    private val heroRotator = object : Runnable {
        override fun run() {
            val frame = heroFrame ?: return
            if (heroApps.size < 2 || isFinishing || isDestroyed) return
            heroIndex = (heroIndex + 1) % heroApps.size
            val next = featuredCard(heroApps[heroIndex]).apply { alpha = 0f; translationY = dp(6).toFloat() }
            frame.removeAllViews()
            frame.addView(next, FrameLayout.LayoutParams(-1, -2))
            next.animate().alpha(1f).translationY(0f).setDuration(320).start()
            heroHandler.postDelayed(this, 6_000)
        }
    }

    private data class TransferProgress(val downloaded: Long, val total: Long)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        SystemBars.fit(this)
        favoriteIds.addAll(getSharedPreferences("codehub_store", MODE_PRIVATE).getStringSet("favorites", emptySet()).orEmpty())
        buildScreen()
        loadCatalog()
        NativeStoreAdGateway.preload()
    }

    override fun onResume() {
        super.onResume()
        NativeStorePresence.visible = true
        consumePendingInstallTap()
    }

    override fun onPause() {
        super.onPause()
        NativeStorePresence.visible = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumePendingInstallTap()
    }

    /** Notification "Listo para instalar": completes the APK user-confirmation flow. */
    private fun consumePendingInstallTap() {
        val appId = intent?.getStringExtra(NativeStoreDownloadService.EXTRA_TAP_APP_ID).orEmpty()
        if (appId.isBlank()) return
        if (NativeStoreApkInstaller.pendingApkFile(appId) == null) {
            Toast.makeText(this, "El APK debe descargarse de nuevo: reinicia la instalación desde la ficha.",
                Toast.LENGTH_LONG).show()
            intent.removeExtra(NativeStoreDownloadService.EXTRA_TAP_APP_ID)
            return
        }
        if (NativeStoreApkInstaller.launchUserInstall(this, appId)) {
            NativeStoreApkInstaller.clearState(appId)
            Toast.makeText(this, "Instalación de ${NativeStoreApkInstaller.displayName(appId)} · confirma en Android.",
                Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "No se pudo preparar la instalación", Toast.LENGTH_SHORT).show()
        }
        intent.removeExtra(NativeStoreDownloadService.EXTRA_TAP_APP_ID)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF070A12.toInt())
            setPadding(dp(18), dp(14), dp(18), 0)
        }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBlock.addView(label("CODEHUB  ·  OPEN SOURCE", 10f, 0xFF7C9BFF.toInt(), true))
        titleBlock.addView(label("Store", 25f, 0xFFF5F7FF.toInt(), true).apply { setPadding(0, dp(2), 0, 0) })
        top.addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val myApps = Button(this).apply {
            text = "Mis apps"
            textSize = 12f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            background = roundRect(0xFF5968E8.toInt(), 0, dp(14))
            elevation = dp(2).toFloat()
            setOnClickListener { startActivityForResult(Intent(this@NativeStoreActivity, NativeMyAppsActivity::class.java), MY_APPS_REQUEST) }
        }
        top.addView(myApps, LinearLayout.LayoutParams(-2, dp(42)).apply { marginEnd = dp(6) })
        val downloads = Button(this).apply {
            text = "↓"
            textSize = 21f
            isAllCaps = false
            setTextColor(0xFFE6EBFF.toInt())
            background = roundRect(0xFF111827.toInt(), 0xFF26324A.toInt(), dp(14))
            contentDescription = "Descargas"
            setOnClickListener { startActivity(Intent(this@NativeStoreActivity, DownloadsActivity::class.java)) }
        }
        top.addView(downloads, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(5) })
        val refresh = Button(this).apply {
            text = "⟳"
            textSize = 22f
            isAllCaps = false
            setTextColor(0xFFB7C5FF.toInt())
            background = roundRect(0xFF111827.toInt(), 0xFF26324A.toInt(), dp(14))
            setOnClickListener { loadCatalog() }
        }
        top.addView(refresh, LinearLayout.LayoutParams(dp(42), dp(42)))
        refresh.contentDescription = "Actualizar catálogo"
        root.addView(top)
        subtitle = label("Apps Android, abiertas y verificadas", 13f, 0xFF9AAFC7.toInt()).apply {
            setPadding(0, dp(3), 0, 0)
        }
        root.addView(subtitle)

        search = EditText(this).apply {
            setSingleLine(true)
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF77798A.toInt())
            hint = "Buscar apps, categorías..."
            setPadding(dp(16), 0, dp(16), 0)
            setBackground(roundRect(0xFF101624.toInt(), 0xFF26324A.toInt(), dp(16)))
        }
        search.setOnFocusChangeListener { _, hasFocus ->
            search.setBackground(roundRect(0xFF101624.toInt(), if (hasFocus) 0xFF7186FF.toInt() else 0xFF26324A.toInt(), dp(16)))
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16) })
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
            override fun afterTextChanged(editable: Editable?) = Unit
        })

        val browseControls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        favoritesButton = Button(this).apply {
            isAllCaps = false
            setTextColor(0xFFDDE4F7.toInt())
            background = roundRect(0xFF111827.toInt(), 0xFF26324A.toInt(), dp(14))
            setOnClickListener { favoritesOnly = !favoritesOnly; renderControls(); render() }
        }
        browseControls.addView(favoritesButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
        sortButton = Button(this).apply {
            isAllCaps = false
            setTextColor(0xFFDDE4F7.toInt())
            background = roundRect(0xFF111827.toInt(), 0xFF26324A.toInt(), dp(14))
            setOnClickListener { showSortOptions() }
        }
        browseControls.addView(sortButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        root.addView(browseControls, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
        renderControls()

        val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chipScroll.addView(chips)
        root.addView(chipScroll, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(8) })

        status = label("Conectando al catálogo…", 13f, 0xFF9AA0B8.toInt()).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(status, LinearLayout.LayoutParams(-1, dp(34)))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        var downY = 0f
        scroll.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> downY = event.y
                android.view.MotionEvent.ACTION_UP -> {
                    if (view.scrollY == 0 && event.y - downY > dp(88)) {
                        loadCatalog()
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }
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
            setBackground(roundRect(0xFF101624.toInt(), 0xFF26324A.toInt(), dp(24)))
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
                setBackground(roundRect(if (active) 0xFF24365B.toInt() else 0x00151522, 0, dp(19)))
                contentDescription = "Mostrar apps para $platform"
                setOnClickListener { selectPlatform(platform) }
            }
            item.addView(label(icon, 19f, 0xFFFFFFFF.toInt()), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
            item.addView(label(platform, 14f, if (active) 0xFF9EB2FF.toInt() else 0xFFC0C8DA.toInt(), true))
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
        catalogFailed = false
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
                val ratings = fetchRatings()
                loaded.replaceAll { app -> ratings[app.id]?.let { app.copy(ratingAverage = it.first, ratingCount = it.second) } ?: app }
                loaded.sortBy { it.name.lowercase(Locale.ROOT) }
                runOnUiThread {
                    apps.clear()
                    apps.addAll(loaded)
                    catalogLoaded = true
                    renderControls()
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
                catalogFailed = true
                runOnUiThread {
                    status.text = if (apps.isEmpty()) "No se pudo conectar. Toca ⟳ para reintentar."
                    else "No se pudo actualizar · mostrando el catálogo anterior"
                    render()
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

    private fun fetchRatings(): Map<String, Pair<Double, Int>> = runCatching {
        val connection = URL(RATINGS_API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            if (connection.responseCode !in 200..299) return@runCatching emptyMap()
            val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
            val source = JSONObject(body).optJSONObject("ratings") ?: return@runCatching emptyMap()
            buildMap {
                val keys = source.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val rating = source.optJSONObject(id) ?: continue
                    put(id, rating.optDouble("avg") to rating.optInt("count"))
                }
            }
        } finally { connection.disconnect() }
    }.getOrDefault(emptyMap())

    private fun renderChips() {
        chips.removeAllViews()
        val categories = linkedSetOf("Todas")
        apps.filter { it.platform == selectedPlatform }.forEach { categories.add(it.category) }
        categories.forEach { value ->
            val count = apps.count { it.platform == selectedPlatform && (value == "Todas" || it.category == value) }
            val chip = label("$value · $count", 13f, if (category == value) 0xFF080B11.toInt() else 0xFFD7D7E4.toInt(), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(15), 0, dp(15), 0)
                setBackground(roundRect(if (category == value) 0xFF9CB0FF.toInt() else 0xFF101624.toInt(), if (category == value) 0 else 0xFF26324A.toInt(), dp(22)))
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

    private fun renderControls() {
        if (!::favoritesButton.isInitialized) return
        val count = apps.count { it.platform == selectedPlatform && it.id in favoriteIds }
        favoritesButton.text = (if (favoritesOnly) "♥" else "♡") + " Favoritos ($count)"
        sortButton.text = "Ordenar · ${sort.label}"
    }

    private fun showSortOptions() {
        val options = StoreSort.values()
        AlertDialog.Builder(this)
            .setTitle("Ordenar catálogo")
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), options.indexOf(sort)) { dialog, which ->
                sort = options[which]
                dialog.dismiss()
                renderControls()
                render()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun toggleFavorite(app: AppEntry) {
        if (!favoriteIds.add(app.id)) favoriteIds.remove(app.id)
        getSharedPreferences("codehub_store", MODE_PRIVATE).edit().putStringSet("favorites", favoriteIds.toSet()).apply()
        renderControls()
        render()
    }

    private fun render() {
        if (!::appList.isInitialized) return
        heroHandler.removeCallbacks(heroRotator)
        heroFrame = null
        transferViews.clear()
        appList.removeAllViews()
        val query = if (::search.isInitialized) search.text.toString().trim().lowercase(Locale.ROOT) else ""
        val matches = apps.filter { app ->
            app.platform == selectedPlatform && (category == "Todas" || app.category == category) &&
                (!favoritesOnly || app.id in favoriteIds) &&
                ("${app.name} ${app.description} ${app.category} ${app.repo}".lowercase(Locale.ROOT).contains(query))
        }
        val filtered = when (sort) {
            StoreSort.HOME -> matches
            StoreSort.NAME -> matches.sortedBy { it.name.lowercase(Locale.ROOT) }
            StoreSort.RATING -> matches.sortedWith(compareByDescending<AppEntry> { it.ratingAverage }.thenByDescending { it.ratingCount }.thenBy { it.name })
            StoreSort.UPDATED -> matches.sortedWith(compareByDescending<AppEntry> { it.tag.contains("Actualizada", true) }.thenByDescending { it.updatedAt })
        }
        status.text = when {
            apps.none { it.platform == selectedPlatform } && selectedPlatform == "Windows" && windowsCatalogLoading -> "Cargando catálogo de Windows…"
            apps.none { it.platform == selectedPlatform } && selectedPlatform == "Windows" && windowsCatalogFailed -> "No se pudo cargar el catálogo de Windows. Toca ↻ para reintentar."
            catalogFailed && selectedPlatform == "Android" && apps.any { it.platform == "Android" } -> "Sin conexión · mostrando ${apps.count { it.platform == "Android" }} apps del catálogo"
            apps.none { it.platform == selectedPlatform } -> status.text
            else -> "${filtered.size} aplicaciones disponibles"
        }
        val updatedApps = filtered.filter { it.platform == "Android" && it.tag.contains("Actualizada", ignoreCase = true) }
        val home = selectedPlatform == "Android" && category == "Todas" && query.isBlank() && !favoritesOnly && sort == StoreSort.HOME
        val topRated = filtered.filter { it.ratingCount > 0 }
            .sortedWith(compareByDescending<AppEntry> { it.ratingAverage }.thenByDescending { it.ratingCount }).take(8)
        val showcasedIds = mutableSetOf<String>()
        if (home && filtered.isNotEmpty()) {
            heroApps = (topRated.ifEmpty { filtered }).take(5)
            showcasedIds.addAll(heroApps.map { it.id })
            heroIndex %= heroApps.size
            val frame = FrameLayout(this)
            heroFrame = frame
            frame.addView(featuredCard(heroApps[heroIndex]), FrameLayout.LayoutParams(-1, -2))
            appList.addView(frame, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            heroHandler.postDelayed(heroRotator, 6_000)
        }
        val ratedToShow = if (home) topRated.filterNot { it.id in showcasedIds } else emptyList()
        if (home && ratedToShow.isNotEmpty()) {
            appList.addView(label("Mejor valoradas", 18f, 0xFFFFFFFF.toInt(), true).apply { setPadding(0, dp(8), 0, dp(10)) })
            val rail = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            ratedToShow.forEach { row.addView(updateCard(it, true), LinearLayout.LayoutParams(dp(230), -2).apply { rightMargin = dp(10) }) }
            rail.addView(row)
            appList.addView(rail, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            showcasedIds.addAll(ratedToShow.map { it.id })
        }
        val updatesToShow = if (home) updatedApps.filterNot { it.id in showcasedIds } else emptyList()
        if (home && updatesToShow.isNotEmpty()) {
            appList.addView(label("Con nueva versión", 18f, 0xFFFFFFFF.toInt(), true).apply {
                setPadding(0, dp(8), 0, dp(10))
            })
            val rail = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            updatesToShow.forEach { row.addView(updateCard(it), LinearLayout.LayoutParams(dp(210), -2).apply { rightMargin = dp(10) }) }
            rail.addView(row)
            appList.addView(rail, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            showcasedIds.addAll(updatesToShow.map { it.id })
        }
        val catalogApps = if (home) filtered.filterNot { it.id in showcasedIds } else filtered
        if (home && catalogApps.isNotEmpty()) {
            appList.addView(label("Explorar catálogo", 18f, 0xFFFFFFFF.toInt(), true).apply {
                setPadding(0, dp(8), 0, dp(10))
            })
        }
        val useGrid = resources.configuration.smallestScreenWidthDp >= 600
        if (useGrid) {
            catalogApps.chunked(2).forEach { pair ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
                pair.forEach { item ->
                    row.addView(appCard(item), LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(6) })
                }
                if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(6) })
                appList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
            }
        } else catalogApps.forEach { appList.addView(appCard(it)) }
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
        if (filtered.isEmpty() && selectedPlatform == "Android") {
            val title: String
            val detail: String
            when {
                !catalogLoaded && !catalogFailed -> {
                    title = "Cargando tienda…"
                    detail = "Conectando con el catálogo Open Source de CodeHub."
                }
                catalogFailed && apps.none { it.platform == "Android" } -> {
                    title = "No pudimos cargar la tienda"
                    detail = "Revisa tu conexión e inténtalo de nuevo."
                }
                apps.none { it.platform == "Android" } -> {
                    title = "El catálogo está vacío"
                    detail = "Vuelve a actualizar para buscar aplicaciones disponibles."
                }
                favoritesOnly && favoriteIds.none { id -> apps.any { it.id == id && it.platform == "Android" } } -> {
                    title = "Aún no guardaste favoritos"
                    detail = "Toca el corazón en una app para tenerla a mano."
                }
                else -> {
                    title = "No encontramos resultados"
                    detail = "Prueba otra búsqueda o limpia los filtros."
                }
            }
            val empty = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(20), dp(24), dp(20))
                setBackground(roundRect(0xFF111827.toInt(), 0xFF202B40.toInt(), dp(18)))
            }
            empty.addView(label(title, 17f, 0xFFFFFFFF.toInt(), true).apply { gravity = Gravity.CENTER })
            empty.addView(label(detail, 13f, 0xFF9AA0B8.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(7), 0, dp(10))
            })
            if (title != "Cargando tienda…") {
                val hasAndroidApps = apps.any { it.platform == "Android" }
                val hasActiveFilters = query.isNotBlank() || category != "Todas" || favoritesOnly || sort != StoreSort.HOME
                val actionText = if (hasAndroidApps && hasActiveFilters) "Limpiar filtros" else "Actualizar catálogo"
                empty.addView(Button(this).apply {
                    text = actionText
                    isAllCaps = false
                    setOnClickListener { if (actionText == "Limpiar filtros") clearFilters() else loadCatalog() }
                })
            }
            appList.addView(empty, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
    }

    private fun clearFilters() {
        category = "Todas"
        favoritesOnly = false
        sort = StoreSort.HOME
        if (::search.isInitialized) search.setText("")
        renderControls()
        renderChips()
        render()
    }

    private fun featuredCard(app: AppEntry): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF202B52.toInt(), 0xFF111827.toInt()),
            ).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0xFF465D9D.toInt()) }
            elevation = dp(4).toFloat()
            contentDescription = "Aplicación destacada: ${app.name}"
            setOnClickListener { showDetails(app) }
        }
        card.addView(label("SELECCIÓN CODEHUB  ·  OPEN SOURCE", 10f, 0xFFB7C5FF.toInt(), true))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(10))
        }
        val icon = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            background = roundRect(0xFF18213A.toInt(), 0xFF394B78.toInt(), dp(16))
            clipToOutline = true
            loadCodeHubImage(app.imageUrl)
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(64), dp(64)))
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        info.addView(label(app.name, 18f, 0xFFFFFFFF.toInt(), true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        info.addView(label(app.category + if (app.ratingCount > 0) "  ·  ★ ${"%.1f".format(Locale.ROOT, app.ratingAverage)}" else "", 12f, 0xFFB7C5FF.toInt()))
        info.addView(label(app.description.ifBlank { "Descubre esta aplicación de código abierto." }, 12f, 0xFFCBD5E1.toInt()).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(row)
        card.addView(label("Ver aplicación  ›", 12f, 0xFFB7C5FF.toInt(), true).apply { gravity = Gravity.END })
        if (heroApps.size > 1) {
            val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
            heroApps.indices.forEach { index ->
                val dot = View(this).apply {
                    background = roundRect(if (index == heroIndex) 0xFF9EB2FF.toInt() else 0xFF475569.toInt(), 0, dp(8))
                    setOnClickListener {
                        heroIndex = index
                        heroHandler.removeCallbacks(heroRotator)
                        val frame = heroFrame ?: return@setOnClickListener
                        val next = featuredCard(heroApps[index]).apply { alpha = 0f; translationY = dp(6).toFloat() }
                        frame.removeAllViews()
                        frame.addView(next, FrameLayout.LayoutParams(-1, -2))
                        next.animate().alpha(1f).translationY(0f).setDuration(280).start()
                        heroHandler.postDelayed(heroRotator, 6_000)
                    }
                }
                dots.addView(dot, LinearLayout.LayoutParams(if (index == heroIndex) dp(18) else dp(6), dp(6)).apply { marginEnd = dp(5) })
            }
            card.addView(dots)
        }
        return card
    }

    private fun updateCard(app: AppEntry, showRating: Boolean = false): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackground(roundRect(0xFF111827.toInt(), 0xFF26324A.toInt(), dp(18)))
            setOnClickListener { showDetails(app) }
        }
        val icon = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            background = roundRect(0xFF111827.toInt(), 0, dp(13))
            clipToOutline = true
            loadCodeHubImage(app.imageUrl)
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        info.addView(label(app.name, 14f, 0xFFFFFFFF.toInt(), true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val supporting = if (showRating) "★ ${"%.1f".format(Locale.ROOT, app.ratingAverage)} · ${app.ratingCount} reseñas" else "Nueva versión · ${app.version}"
        info.addView(label(supporting, 11f, 0xFFB7C5FF.toInt()).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(favoriteControl(app))
        return card
    }

    private fun appCard(app: AppEntry): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(11))
            setBackground(roundRect(0xFF101521.toInt(), 0xFF202B40.toInt(), dp(20)))
            elevation = dp(3).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { showDetails(app) }
        }
        val icon = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            background = roundRect(0xFF18213A.toInt(), 0xFF394B78.toInt(), dp(14))
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
        info.addView(label(app.category + if (app.version.isEmpty()) "" else " · ${app.version}", 12f, 0xFF9EB2FF.toInt()).apply {
            maxLines = 1
        })
        info.addView(label(app.description.ifEmpty { app.repo }, 12f, 0xFF9AA0B8.toInt()).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(favoriteControl(app))
        val transfer = activeTransfers[app.id]
        card.addView(label(if (transfer == null) "›" else "↓", 24f, if (transfer == null) 0xFF9AA0B8.toInt() else 0xFF9EB2FF.toInt(), transfer != null))
        container.addView(card)
        if (transfer != null) {
            val percent = if (transfer.total > 0) (transfer.downloaded * 100 / transfer.total).toInt().coerceIn(0, 100) else 0
            val progressText = label(
                if (transfer.total > 0) "DESCARGANDO  ·  $percent%" else "DESCARGANDO  ·  ${android.text.format.Formatter.formatShortFileSize(this, transfer.downloaded)}",
                10f, 0xFF9EB2FF.toInt(), true,
            )
            val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                isIndeterminate = transfer.total <= 0
                progressTintList = android.content.res.ColorStateList.valueOf(0xFF9EB2FF.toInt())
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF26372F.toInt())
                if (transfer.total > 0) setProgress(percent, false)
            }
            container.addView(progressText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            container.addView(progress, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(5) })
            transferViews[app.id] = progress to progressText
        }
        return container.apply {
            alpha = 0f
            translationY = dp(12).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(260).setStartDelay(40).start()
        }
    }

    private fun favoriteControl(app: AppEntry): TextView = label(if (app.id in favoriteIds) "♥" else "♡", 23f, 0xFFFF6B9A.toInt(), true).apply {
        contentDescription = if (app.id in favoriteIds) "Quitar de favoritos" else "Añadir a favoritos"
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { toggleFavorite(app) }
    }

    private fun showDetails(app: AppEntry) {
        if (app.platform != "Android") {
            showDetailsWindows(app)
            return
        }
        detailAppId = app.id
        detailRing = null
        detailCta = null

        val sheetContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(12))
        }

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            background = roundRect(0xFF111827.toInt(), 0, dp(18))
            clipToOutline = true
            loadCodeHubImage(app.imageUrl)
        }
        hero.addView(icon, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(14) })
        hero.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(app.category.uppercase(Locale.ROOT), 11f, 0xFF7C90F7.toInt(), true))
            addView(label(app.name, 21f, 0xFFFFFFFF.toInt(), true).apply { setPadding(0, dp(4), 0, dp(3)) })
            addView(label(if (app.ratingCount > 0) "★ ${"%.1f".format(Locale.ROOT, app.ratingAverage)} · ${app.ratingCount} valoraciones" else "Aún sin valoraciones", 13f, 0xFFFFC857.toInt(), true))
            addView(label(if (app.verified) "Verificado por CodeHub" else "Open Source", 11f, 0xFF9EB2FF.toInt()).apply { setPadding(0, dp(3), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        sheetContent.addView(hero, LinearLayout.LayoutParams(-1, -2))

        // CTA estilo Orion: anillo de progreso + estado bajo el botón.
        val ctaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(2))
        }
        val ring = NativeRingButton(this).apply {
            ringColor = 0xFF7C90F7.toInt()
            doneColor = 0xFF9EB2FF.toInt()
            idleColor = 0xFF9EB2FF.toInt()
            sizeDp { value -> dp(value) }
            clickAction = { startInstall(app) }
            if (app.id in activeTransfers) setProgress(-1f) else setIdle()
        }
        detailRing = ring
        ctaRow.addView(ring, LinearLayout.LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(14) })
        ctaRow.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Instalar en Android", 17f, 0xFFFFFFFF.toInt(), true))
            val phase = label(installPhaseText(app), 12f, 0xFF9AA0B8.toInt()).apply {
                setPadding(0, dp(3), 0, 0)
            }
            detailCta = phase
            addView(phase)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        sheetContent.addView(ctaRow, LinearLayout.LayoutParams(-1, -2))

        // Tab strip.
        val panels = LinkedHashMap<String, LinearLayout>()
        val tabButtons = LinkedHashMap<String, TextView>()
        val tabStrip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabLabels = listOf("Descripción", "Versiones", "Seguridad", "Reseñas")
        fun selectTab(key: String) {
            panels.forEach { (k, p) -> p.visibility = if (k == key) android.view.View.VISIBLE else android.view.View.GONE }
            tabButtons.forEach { (k, b) ->
                b.setTextColor(if (k == key) 0xFF080B11.toInt() else 0xFFD7D7E4.toInt())
                b.background = roundRect(if (k == key) 0xFF7C90F7.toInt() else 0xFF080B11.toInt(), 0, dp(16))
            }
        }
        tabLabels.forEach { key ->
            val btn = label(key, 12f, 0xFFD7D7E4.toInt(), true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setOnClickListener { selectTab(key) }
            }
            tabButtons[key] = btn
            tabStrip.addView(btn, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(4) })
        }
        sheetContent.addView(tabStrip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // --- Panel: Descripción ---
        val descPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        descPanel.addView(label(app.description.ifEmpty { "Aplicación de código abierto." }, 14f, 0xFFD0D0DE.toInt()).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(14), 0, dp(4))
        })
        descPanel.addView(label("Versión ${app.version.ifEmpty { "disponible" }}  ·  ${app.repo}", 12f, 0xFF9AA0B8.toInt()).apply {
            setPadding(0, dp(2), 0, 0)
        })
        if (app.changelog.isNotBlank()) {
            descPanel.addView(label("Novedades", 14f, 0xFFFFFFFF.toInt(), true).apply { setPadding(0, dp(14), 0, dp(5)) })
            descPanel.addView(label(app.changelog, 13f, 0xFFB7B8C8.toInt()).apply {
                maxLines = 6
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        val chipsRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        chipsRow.addView(pill("★ Reseñas y valorar", 0xFF111827.toInt(), 0xFFB7C5FF.toInt()) { showReviews(app) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        chipsRow.addView(pill("</> Ver código", 0xFF111827.toInt(), 0xFF9AA0B8.toInt()) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${app.repo}")))
            } catch (_: Exception) {
                Toast.makeText(this, "No se pudo abrir GitHub", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        chipsRow.addView(pill(if (app.id in favoriteIds) "♥" else "♡", 0xFF111827.toInt(), 0xFFFF6B9A.toInt()) {
            toggleFavorite(app); detailDialog?.dismiss()
        }, LinearLayout.LayoutParams(-2, -2))
        descPanel.addView(chipsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        panels["Descripción"] = descPanel
        sheetContent.addView(descPanel, LinearLayout.LayoutParams(-1, -2))

        // --- Panel: Versiones ---
        val versionsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val releaseStatus = label("Consultando versiones y variantes…", 13f, 0xFF9AA0B8.toInt()).apply {
            setPadding(0, dp(12), 0, 0)
        }
        versionsPanel.addView(releaseStatus)
        val releaseImages = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val imageScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(releaseImages) }
        versionsPanel.addView(imageScroll, LinearLayout.LayoutParams(-1, dp(132)).apply { topMargin = dp(10) })
        val releaseActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        versionsPanel.addView(releaseActions)
        panels["Versiones"] = versionsPanel
        versionsPanel.visibility = android.view.View.GONE
        sheetContent.addView(versionsPanel, LinearLayout.LayoutParams(-1, -2))

        // --- Panel: Seguridad ---
        val securityPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val securityStatus = label("Analizando la app descargada…", 13f, 0xFF9AA0B8.toInt()).apply { setPadding(0, dp(12), 0, 0) }
        securityPanel.addView(securityStatus)
        val securityBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        securityPanel.addView(securityBody, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        panels["Seguridad"] = securityPanel
        securityPanel.visibility = android.view.View.GONE
        sheetContent.addView(securityPanel, LinearLayout.LayoutParams(-1, -2))
        loadSecurityPanel(app, securityStatus, securityBody)

        // --- Panel: Reseñas ---
        val reviewsPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panels["Reseñas"] = reviewsPanel
        reviewsPanel.visibility = android.view.View.GONE
        sheetContent.addView(reviewsPanel, LinearLayout.LayoutParams(-1, -2))
        loadReviewsInto(app, reviewsPanel)

        loadReleaseSection(app, releaseStatus, releaseImages, releaseActions)

        selectTab("Descripción")

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF080B11.toInt())
                setCornerRadii(floatArrayOf(dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), dp(26).toFloat(), 0f, 0f, 0f, 0f))
            }
            setPadding(dp(16), dp(18), dp(16), dp(22))
        }
        sheet.addView(sheetContent)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            clipToOutline = true
            addView(sheet)
        }
        val dialog = android.app.Dialog(this)
        detailDialog = dialog
        dialog.setContentView(scroll)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { detailDialog = null }
        dialog.setOnDismissListener { detailDialog = null; detailRing = null; detailCta = null }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.86f).toInt())
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.55f)
        }
        scroll.translationY = dp(48).toFloat()
        scroll.alpha = 0.35f
        scroll.postDelayed({
            scroll.animate().translationY(0f).alpha(1f).setDuration(280).start()
        }, 40)
    }

    private fun loadSecurityPanel(app: AppEntry, status: TextView, body: LinearLayout) {
        body.removeAllViews()
        val insight = NativeApkInsights.cache(this, app.id)
            ?: NativeStoreApkInstaller.pendingApkFile(app.id)?.let { file ->
                NativeApkInsights.analyze(this, app.id, file)?.also { NativeApkInsights.cacheInsight(this, it) }
            }
        if (insight != null) {
            status.text = "Seguridad · análisis local (sin claves externas)"
            body.addView(label("Paquete: ${insight.packageName}", 13f, 0xFFFFFFFF.toInt()))
            body.addView(label("Versión: ${insight.versionName} (${insight.versionCode})", 13f, 0xFFD0D0DE.toInt()).apply { setPadding(0, dp(3), 0, 0) })
            body.addView(label("minSdk ${insight.minSdk} · targetSdk ${insight.targetSdk}", 12f, 0xFF9AA0B8.toInt()).apply { setPadding(0, dp(3), 0, 0) })
            body.addView(label("SHA-256: ${insight.sha256.take(16)}…", 11f, 0xFF9AA0B8.toInt()).apply { setPadding(0, dp(3), 0, 0) })
            body.addView(label("Firma: ${insight.signerDigest.take(16)}…", 11f, 0xFF9AA0B8.toInt()).apply { setPadding(0, dp(3), 0, 0) })
            if (insight.dangerousPermissions.isNotEmpty()) {
                body.addView(label("Permisos sensibles:", 13f, 0xFFFFC857.toInt(), true).apply { setPadding(0, dp(10), 0, dp(4)) })
                insight.dangerousPermissions.forEach { body.addView(label("• $it", 12f, 0xFFD0D0DE.toInt()).apply { setPadding(dp(6), dp(2), 0, dp(2)) }) }
            } else {
                body.addView(label("Sin permisos sensibles declarados.", 12f, 0xFF9EB2FF.toInt()).apply { setPadding(0, dp(8), 0, 0) })
            }
            body.addView(label("Permisos totales: ${insight.permissions.size}", 11f, 0xFF9AA0B8.toInt()).apply { setPadding(0, dp(6), 0, 0) })
        } else {
            status.text = "Seguridad · descarga primero la app"
            body.addView(label("Descarga la app para analizar su firma, permisos y hash SHA-256 aquí.", 13f, 0xFFD0D0DE.toInt()))
        }
        // Download history.
        val history = NativeApkInsights.downloads(this, app.id)
        if (history.isNotEmpty()) {
            body.addView(label("Historial de descargas", 13f, 0xFFFFFFFF.toInt(), true).apply { setPadding(0, dp(12), 0, dp(4)) })
            history.take(5).forEach { record ->
                val whenText = android.text.format.DateUtils.getRelativeTimeSpanString(record.at).toString()
                body.addView(label("v${record.version} · ${android.text.format.Formatter.formatShortFileSize(this, record.sizeBytes)} · $whenText", 12f, 0xFF9AA0B8.toInt()).apply { setPadding(dp(6), dp(2), 0, dp(2)) })
            }
        }
        // Mis apps shortcut.
        body.addView(pill("Ir a Mis apps →", 0xFF111827.toInt(), 0xFF9EB2FF.toInt()) {
            try { startActivity(Intent(this, NativeMyAppsActivity::class.java)) } catch (_: Exception) { }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
    }

    private fun loadReviewsInto(app: AppEntry, container: LinearLayout) {
        container.removeAllViews()
        container.addView(label("Cargando reseñas…", 13f, 0xFF9AA0B8.toInt()))
        executor.execute {
            val reviews = fetchReviews(app)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                container.removeAllViews()
                if (reviews.length() == 0) container.addView(label("Todavía no hay reseñas. Puedes ser la primera persona en valorar esta app.", 14f, 0xFFB7B8C8.toInt()))
                for (i in 0 until reviews.length()) {
                    val review = reviews.optJSONObject(i) ?: continue
                    container.addView(label("${"★".repeat(review.optInt("stars").coerceIn(1, 5))}  ${review.optString("autor", "Anónimo")}", 13f, 0xFFFFC857.toInt(), true).apply { setPadding(0, dp(12), 0, dp(4)) })
                    container.addView(label(review.optString("texto"), 13f, 0xFFD0D0DE.toInt()))
                }
                container.addView(pill("Valorar", 0xFF111827.toInt(), 0xFFB7C5FF.toInt()) { showRatingDialog(app) }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            }
        }
    }

    private fun fetchReviews(app: AppEntry): JSONArray = runCatching {
        val url = "https://codehub-98s6.onrender.com/api/apps/${URLEncoder.encode(app.id, "UTF-8")}/reviews"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            val body = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
            JSONObject(body).optJSONArray("reviews") ?: JSONArray()
        } finally { connection.disconnect() }
    }.getOrDefault(JSONArray())


    private fun installPhaseText(app: AppEntry): String = when {
        app.id in activeTransfers -> "Descargando… deja la ficha abierta para ver la prioridad."
        else -> "Instalación full-offline · descarga + instalador de Android."
    }

    private fun pill(text: String, bg: Int, fg: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            setText(text)
            textSize = 12f
            setTextColor(fg)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(11), dp(10), dp(11))
            background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(16).toFloat() }
            setOnClickListener { onClick() }
        }
    }

    /** Página de ficha para apps Windows (flujo previo, sin cambios de contrato). */
    private fun showDetailsWindows(app: AppEntry) {
        detailAppId = null
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        val icon = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            background = roundRect(0xFF111827.toInt(), 0, dp(18))
            clipToOutline = true
            loadCodeHubImage(app.imageUrl)
        }
        content.addView(icon, LinearLayout.LayoutParams(dp(72), dp(72)).apply { bottomMargin = dp(12) })
        content.addView(label(app.category.uppercase(Locale.ROOT), 12f, 0xFF7C90F7.toInt(), true))
        content.addView(label(app.name, 23f, 0xFFFFFFFF.toInt(), true).apply { setPadding(0, dp(7), 0, dp(8)) })
        content.addView(label(if (app.ratingCount > 0) "★ ${"%.1f".format(Locale.ROOT, app.ratingAverage)} · ${app.ratingCount} valoraciones" else "Aún sin valoraciones", 13f, 0xFFFFC857.toInt(), true))
        content.addView(Button(this).apply {
            text = "Ver reseñas y valorar"
            isAllCaps = false
            setOnClickListener { showReviews(app) }
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })
        content.addView(label(app.description.ifEmpty { "Aplicación de código abierto." }, 14f, 0xFFD0D0DE.toInt()).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        })
        content.addView(label("Versión ${app.version.ifEmpty { "disponible" }}\n${app.repo}", 12f, 0xFF9AA0B8.toInt()).apply {
            setPadding(0, dp(12), 0, 0)
        })
        val releaseStatus = label("Consultando versiones y variantes…", 13f, 0xFF9AA0B8.toInt()).apply {
            setPadding(0, dp(12), 0, 0)
        }
        content.addView(releaseStatus)
        val releaseImages = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val imageScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(releaseImages) }
        content.addView(imageScroll, LinearLayout.LayoutParams(-1, dp(150)).apply { topMargin = dp(10) })
        val releaseActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(releaseActions)
        if (app.changelog.isNotBlank()) {
            content.addView(label("Novedades", 14f, 0xFFFFFFFF.toInt(), true).apply { setPadding(0, dp(14), 0, dp(5)) })
            content.addView(label(app.changelog, 13f, 0xFFB7B8C8.toInt()).apply {
                maxLines = 6
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }
        content.addView(label("Catálogo Windows", 12f, 0xFF7C90F7.toInt()).apply {
            setPadding(0, dp(12), 0, 0)
        })
        content.addView(label("Ficha informativa: abre el enlace desde una PC con Windows para instalar la aplicación.", 12f, 0xFFFFC857.toInt()).apply {
            setPadding(0, dp(10), 0, 0)
        })
        val dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { isFillViewport = true; addView(content) })
            .setNegativeButton("Cerrar", null)
            .setNeutralButton("Sitio oficial", null)
            .setPositiveButton("Abrir enlace", null)
            .create()
        detailDialog = dialog
        dialog.setOnShowListener {
            val downloadTarget = app.downloadUrl.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: app.repo
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { openExternal(downloadTarget) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { openExternal(app.repo) }
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        loadReleaseSection(app, releaseStatus, releaseImages, releaseActions)
    }

    /** Carga async de releases de GitHub (compartida por las dos fichas). */
    private fun loadReleaseSection(app: AppEntry, releaseStatus: TextView, releaseImages: LinearLayout, releaseActions: LinearLayout) {
        if (app.platform != "Android" || !app.repo.matches(Regex("^[\\w.-]+/[\\w.-]+$"))) return
        executor.execute {
            val releases = fetchReleases(app.repo)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                releaseActions.removeAllViews()
                releaseImages.removeAllViews()
                if (releases.isEmpty()) {
                    releaseStatus.text = "No hay releases públicos con APKs disponibles."
                } else {
                    releaseStatus.text = "Versiones y variantes APK · elige una versión para instalar"
                    releases.forEach { release ->
                        val apkAssets = release.assets.filter { it.name.endsWith(".apk", true) }
                        val imageAssets = release.assets.filter { it.name.matches(Regex(".*\\.(png|jpe?g|webp)$", RegexOption.IGNORE_CASE)) }.take(5)
                        imageAssets.forEach { asset ->
                            val image = android.widget.ImageView(this).apply {
                                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                                background = roundRect(0xFF111827.toInt(), 0, dp(12))
                                clipToOutline = true
                                loadCodeHubImage(asset.url)
                                contentDescription = "Captura de ${app.name} · ${release.name}"
                            }
                            releaseImages.addView(image, LinearLayout.LayoutParams(dp(220), dp(132)).apply { marginEnd = dp(10) })
                        }
                        val button = Button(this).apply {
                            text = "${release.name}${if (release.prerelease) " · Beta" else " · Estable"}  (${apkAssets.size} variantes APK)"
                            isAllCaps = false
                            setOnClickListener {
                                if (apkAssets.isEmpty()) openExternal(release.url)
                                else showReleaseAssets(app, release, apkAssets)
                            }
                        }
                        releaseActions.addView(button, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })
                        if (release.notes.isNotBlank()) {
                            releaseActions.addView(label(release.notes, 12f, 0xFFB7B8C8.toInt()).apply {
                                maxLines = 4
                                ellipsize = android.text.TextUtils.TruncateAt.END
                                setPadding(dp(8), dp(3), dp(8), dp(5))
                            })
                        }
                    }
                }
            }
        }
    }

    private data class ReleaseAsset(val name: String, val url: String)
    private data class AppRelease(val name: String, val url: String, val prerelease: Boolean, val notes: String, val assets: List<ReleaseAsset>)

    private fun fetchReleases(repo: String): List<AppRelease> = runCatching {
        val connection = URL("https://api.github.com/repos/$repo/releases?per_page=10").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (connection.responseCode !in 200..299) return@runCatching emptyList()
            val rows = JSONArray(BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() })
            (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val assetsJson = row.optJSONArray("assets") ?: JSONArray()
                val assets = (0 until assetsJson.length()).mapNotNull { assetIndex ->
                    val item = assetsJson.optJSONObject(assetIndex) ?: return@mapNotNull null
                    val url = item.optString("browser_download_url")
                    if (url.startsWith("https://")) ReleaseAsset(item.optString("name"), url) else null
                }
                AppRelease(row.optString("name").ifBlank { row.optString("tag_name") }, row.optString("html_url"), row.optBoolean("prerelease"), row.optString("body").trim(), assets)
            }
        } finally { connection.disconnect() }
    }.getOrDefault(emptyList())

    private fun showReleaseAssets(app: AppEntry, release: AppRelease, assets: List<ReleaseAsset>) {
        AlertDialog.Builder(this)
            .setTitle("${app.name} · ${release.name}")
            .setItems(assets.map { it.name }.toTypedArray()) { _, which -> startInstall(app, assets[which].url) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startInstall(app: AppEntry, assetUrl: String? = null) {
        if (app.platform != "Android") return
        NativeStoreAdGateway.requestDownloadReward(this, app.name) {
            runOnUiThread { proceedInstall(app, assetUrl) }
        }
    }

    private fun proceedInstall(app: AppEntry, assetUrl: String?) {
        NativeStoreInstallFlow.request(this) { preferSilent ->
            try {
                val url = assetUrl ?: "https://codehub-98s6.onrender.com/api/apps/${URLEncoder.encode(app.id, "UTF-8")}/download"
                if (app.id in activeTransfers) {
                    Toast.makeText(this, "La descarga de ${app.name} ya está en curso.", Toast.LENGTH_SHORT).show()
                    return@request
                }
                activeTransfers[app.id] = TransferProgress(0, -1)
                detailRing?.setProgress(-1f)
                detailCta?.text = "Descargando… \uD83D\uDE80 prioridad activa."
                render()
                NativeStoreApkInstaller.start(applicationContext, url, app.id, app.packageName, preferSilent, app.name, this,
                    object : NativeStoreApkInstaller.Listener {
                        override fun onProgress(downloadedBytes: Long, totalBytes: Long) {
                            if (isFinishing || isDestroyed) return
                            activeTransfers[app.id] = TransferProgress(downloadedBytes, totalBytes)
                            transferViews[app.id]?.let { (bar, text) ->
                                val percent = if (totalBytes > 0) (downloadedBytes * 100 / totalBytes).toInt().coerceIn(0, 100) else 0
                                bar.isIndeterminate = totalBytes <= 0
                                if (totalBytes > 0) bar.setProgress(percent, true)
                                text.text = if (totalBytes > 0) "DESCARGANDO  ·  $percent%" else "DESCARGANDO  ·  ${android.text.format.Formatter.formatShortFileSize(this@NativeStoreActivity, downloadedBytes)}"
                            }
                            if (detailAppId == app.id) {
                                val ratio = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else -1f
                                detailRing?.setProgress(ratio)
                            }
                        }

                        override fun onFinished(status: NativeStoreApkInstaller.Status, message: String) {
                            activeTransfers.remove(app.id)
                            if (isFinishing || isDestroyed) return
                            render()
                            when (status) {
                                NativeStoreApkInstaller.Status.INSTALLED -> {
                                    detailRing?.setDone()
                                    detailCta?.text = "${app.name} instalada." 
                                    // ADS-COMPLIANCE: interstitial solo en pausa natural con
                                    // el usuario dentro de la app (no tras salir de ella).
                                    if (NativeStorePresence.visible) {
                                        NativeStoreAdGateway.showCompletionInterstitial(this@NativeStoreActivity)
                                    }
                                    Toast.makeText(this@NativeStoreActivity, "${app.name} se instaló correctamente.", Toast.LENGTH_LONG).show()
                                }
                                NativeStoreApkInstaller.Status.INSTALLER_OPENED -> {
                                    detailRing?.setDone()
                                    detailCta?.text = "Descarga lista · confirma la instalación de ${app.name}."
                                    Toast.makeText(this@NativeStoreActivity, "Descarga lista · confirma la instalación de ${app.name}.", Toast.LENGTH_LONG).show()
                                }
                                NativeStoreApkInstaller.Status.READY_BACKGROUND -> {
                                    detailRing?.setDone()
                                    detailCta?.text = "Descarga lista en segundo plano · toca la notificación para instalar."
                                }
                                NativeStoreApkInstaller.Status.CANCELLED -> {
                                    detailRing?.setIdle()
                                    detailCta?.text = "Descarga cancelada · toca para reintentar."
                                }
                                NativeStoreApkInstaller.Status.FAILED -> {
                                    detailRing?.setIdle()
                                    detailCta?.text = "No se pudo completar · cierra este aviso y reintentar."
                                    AlertDialog.Builder(this@NativeStoreActivity)
                                        .setTitle("No se pudo instalar ${app.name}")
                                        .setMessage(message)
                                        .setPositiveButton("Entendido", null)
                                        .show()
                                }
                            }
                        }
                    })
            } catch (_: Exception) {
                Toast.makeText(this, "No se pudo preparar la instalación", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showReviews(app: AppEntry) {
        val loading = AlertDialog.Builder(this).setMessage("Cargando reseñas…").setNegativeButton("Cerrar", null).create()
        loading.show()
        executor.execute {
            val reviews = fetchReviews(app)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                loading.dismiss()
                val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10)) }
                if (reviews.length() == 0) content.addView(label("Todavía no hay reseñas. Puedes ser la primera persona en valorar esta app.", 14f, 0xFFB7B8C8.toInt()))
                for (i in 0 until reviews.length()) {
                    val review = reviews.optJSONObject(i) ?: continue
                    content.addView(label("${"★".repeat(review.optInt("stars").coerceIn(1, 5))}  ${review.optString("autor", "Anónimo")}", 13f, 0xFFFFC857.toInt(), true).apply { setPadding(0, dp(12), 0, dp(4)) })
                    content.addView(label(review.optString("texto"), 13f, 0xFFD0D0DE.toInt()))
                }
                val scroll = ScrollView(this).apply { addView(content) }
                AlertDialog.Builder(this)
                    .setTitle("Reseñas · ${app.name}")
                    .setView(scroll)
                    .setNeutralButton("Valorar") { _, _ -> showRatingDialog(app) }
                    .setPositiveButton("Listo", null)
                    .show()
            }
        }
    }

    private fun showRatingDialog(app: AppEntry) {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(8), dp(22), 0) }
        val stars = android.widget.NumberPicker(this).apply { minValue = 1; maxValue = 5; value = 5; wrapSelectorWheel = false }
        content.addView(label("Puntuación (1–5 estrellas)", 13f, 0xFFB7B8C8.toInt()))
        content.addView(stars, LinearLayout.LayoutParams(-1, dp(76)))
        val author = EditText(this).apply { hint = "Nombre (opcional)"; setSingleLine(true) }
        content.addView(author, LinearLayout.LayoutParams(-1, dp(48)))
        val text = EditText(this).apply { hint = "Cuéntale a la comunidad (opcional)"; minLines = 3; maxLines = 5; gravity = Gravity.TOP; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE; filters = arrayOf(android.text.InputFilter.LengthFilter(600)) }
        content.addView(text, LinearLayout.LayoutParams(-1, dp(112)))
        AlertDialog.Builder(this)
            .setTitle("Valorar ${app.name}")
            .setView(content)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Enviar", null)
            .create().also { dialog ->
                dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    submitRating(app, stars.value, author.text.toString().trim(), text.text.toString().trim(), dialog)
                } }
                dialog.show()
            }
    }

    private fun submitRating(app: AppEntry, stars: Int, author: String, text: String, dialog: AlertDialog) {
        executor.execute {
            val result = runCatching {
                val connection = URL(RATINGS_API).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 12_000
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val payload = JSONObject().put("appId", app.id).put("appName", app.name).put("stars", stars).put("autor", author).put("texto", text)
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
                    val body = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                    connection.responseCode to JSONObject(body)
                } finally { connection.disconnect() }
            }
            runOnUiThread {
                val (code, body) = result.getOrElse { 0 to JSONObject() }
                if (code in 200..299) {
                    val updated = app.copy(ratingAverage = body.optDouble("avg", app.ratingAverage), ratingCount = body.optInt("count", app.ratingCount))
                    val index = apps.indexOfFirst { it.id == app.id }
                    if (index >= 0) apps[index] = updated
                    render()
                    dialog.dismiss()
                    Toast.makeText(this, "Gracias por valorar ${app.name}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, body.optString("error", "No se pudo guardar la valoración"), Toast.LENGTH_LONG).show()
                }
            }
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
        heroHandler.removeCallbacksAndMessages(null)
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
        val tag: String,
        val platform: String,
        val downloadUrl: String,
        val packageName: String? = null,
        val updatedAt: String = "",
        val ratingAverage: Double = 0.0,
        val ratingCount: Int = 0,
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
                tag = row.optString("tag"),
                platform = "Android",
                downloadUrl = row.optString("enlace"),
                packageName = row.optString("packageName").takeIf { it.isNotBlank() },
                updatedAt = row.optString("updatedAt"),
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
                    tag = "",
                    platform = "Windows",
                    downloadUrl = row.optString("downloadUrl"),
                )
            }
        }
    }
}
