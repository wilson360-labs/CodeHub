package com.codehub.app.music.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codehub.app.R
import com.codehub.app.music.ArtworkLoader
import com.codehub.app.music.MusicClient.getAs
import com.codehub.app.music.MusicPlayer
import com.codehub.app.music.MusicPlayerDuration.toClockTime
import com.codehub.app.music.MusicRegistry
import com.codehub.app.music.MusicWebViewClient
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Shelf.Category
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reproductor "slide" de CodeHub: el player nativo que corre extensiones
 * Echo (common 1.0.0). Activity de framework, UI programática.
 */
class MusicPlayerActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var registry: MusicRegistry
    private lateinit var player: MusicPlayer
    private val artwork: ArtworkLoader by lazy { ArtworkLoader(this) }

    private var activeExtension: MusicExtension? = null

    // Vista
    private lateinit var adapter: FeedAdapter
    private lateinit var list: RecyclerView
    private lateinit var extLabel: TextView
    private lateinit var searchField: EditText
    private lateinit var tabHome: TextView
    private lateinit var tabLibrary: TextView

    // Mini player
    private lateinit var miniBar: LinearLayout
    private lateinit var miniCover: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlay: ImageView
    private lateinit var miniProgress: SeekBar
    private lateinit var miniCurr: TextView
    private lateinit var miniTot: TextView

    private var slide: PlayerSlide? = null

    // Estado de feeds
    private var homeFeed: Feed<Shelf>? = null
    private var libraryFeed: Feed<Shelf>? = null
    private var extensionTabs: List<Tab> = emptyList()
    private var selectedTabIndex = 0
    private val ticker: Runnable = object : Runnable {
        override fun run() {
            updateMiniProgress()
            mainHandler.postDelayed(this, 300)
        }
    }
    private var currentTrack: Track? = null
    private val REQ_PICK_APK = 4201

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window.let { it.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
        com.codehub.app.SystemBars.fit(this)
        registry = MusicRegistry(this)
        (registry.webViewClient as? MusicWebViewClient)?.attach(this)
        // Instancia única por proceso: la mantiene MusicPlaybackService para
        // que la música siga sonando al cerrar la Activity.
        player = MusicPlayer.shared(this)
        requestNotificationPermission()
        setContentView(buildUi())
        collectPlayer()
        collectMessages()
        boot()
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 5200)
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(ticker)
        (registry.webViewClient as? MusicWebViewClient)?.detach(this)
        scope.coroutineContext[Job]?.cancel()
        // Sin player.release(): la reproducción debe continuar en segundo
        // plano (MusicPlaybackService). MusicPlayer.releaseShared() solo la
        // llama el servicio al apagar de verdad.
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Arranque
    // ------------------------------------------------------------------

    private fun boot() {
        scope.launch {
            val exts = registry.music()
            val current = registry.currentId()?.let { id -> exts.firstOrNull { it.id == id } }
            activeExtension = current ?: exts.firstOrNull()
            if (activeExtension == null) {
                val rows = mutableListOf<Row>()
                rows += Row.Header(
                    "Sin fuentes de música",
                    "Instala una extensión Echo (APK): Spotify, Deezer, YouTube Music y más. Al instalarla podrás iniciar sesión y reproducir dentro de esta app."
                )
                rows += Row.Header("＋ Importar un APK", null, "Elegir archivo ›") { pickApk() }
                rows += Row.Header("＋ Importar desde URL", null, "Pegar enlace ›") { askUrl() }
                registry.lastErrors.firstOrNull()?.let {
                    rows += Row.Info("Extensión inválida ignorada: $it")
                }
                extLabel.text = "Música"
                adapter.submit(rows)
                return@launch
            }
            registry.select(activeExtension!!.id)
            extLabel.text = activeExtension?.metadata?.name ?: "Música"
            refreshHome()
        }
    }

    private fun refreshHome() {
        val ext = activeExtension ?: return
        scope.launch {
            adapter.submit(listOf(Row.Loading))
            val home = runCatching { ext.getAs<HomeFeedClient, Feed<Shelf>> { loadHomeFeed() }.getOrThrow() }
            if (home.isFailure) {
                adapter.submit(listOf(Row.Info(
                    "El home no está disponible: " +
                            (home.exceptionOrNull()?.let { (it.cause ?: it).message } ?: "error desconocido")
                )))
                return@launch
            }
            homeFeed = home.getOrNull()
            extensionTabs = homeFeed?.notSortTabs ?: emptyList()
            val tab = extensionTabs.getOrNull(selectedTabIndex)
            val tabsRow = if (extensionTabs.isNotEmpty())
                listOf(Row.Tabs(extensionTabs, selectedTabIndex)) else emptyList()
            adapter.submit(tabsRow + MusicFeed.shelves(ext, homeFeed!!, tab) {
                openMore(it)
            })
        }
    }

    private fun refreshLibrary() {
        val ext = activeExtension ?: return
        scope.launch {
            adapter.submit(listOf(Row.Loading))
            val lib = runCatching { ext.getAs<LibraryFeedClient, Feed<Shelf>> { loadLibraryFeed() }.getOrThrow() }
            if (lib.isFailure) {
                adapter.submit(listOf(Row.Info("La biblioteca no está disponible")))
                return@launch
            }
            libraryFeed = lib.getOrNull()
            adapter.submit(MusicFeed.shelves(ext, libraryFeed!!) { openMore(it) })
        }
    }

    private fun openMore(feed: Feed<Shelf>) {
        val ext = activeExtension ?: return
        scope.launch {
            adapter.submit(listOf(Row.Loading))
            adapter.submit(MusicFeed.shelves(ext, feed) { openMore(it) })
        }
    }

    // ------------------------------------------------------------------
    // Navegación
    // ------------------------------------------------------------------

    private fun openList(media: EchoMediaItem) {
        val ext = activeExtension ?: return
        when (media) {
            is Track -> playTrack(media)
            is Album, is Playlist -> openTracks(media)
            is dev.brahmkshatriya.echo.common.models.Radio -> {
                scope.launch {
                    adapter.submit(listOf(Row.Loading))
                    val feed = ext.getAs<RadioClient, Feed<Track>> { loadTracks(media) }.getOrNull()
                    if (feed == null) {
                        adapter.submit(listOf(Row.Info("No se pudo cargar la radio")))
                    } else {
                        adapter.submit(MusicFeed.tracks(ext, feed))
                    }
                }
            }

            is dev.brahmkshatriya.echo.common.models.Artist -> {
                scope.launch {
                    adapter.submit(listOf(Row.Loading))
                    val feed = ext.getAs<ArtistClient, Feed<Shelf>> { loadFeed(media) }.getOrNull()
                    if (feed == null) {
                        adapter.submit(listOf(Row.Info("No se pudo cargar el artista")))
                    } else {
                        adapter.submit(MusicFeed.shelves(ext, feed) { openMore(it) })
                    }
                }
            }
        }
    }

    private fun openTracks(media: EchoMediaItem) {
        val ext = activeExtension ?: return
        scope.launch {
            adapter.submit(listOf(Row.Loading))
            var tracks: Feed<Track>? = null
            when (media) {
                is Playlist -> {
                    tracks = ext.getAs<PlaylistClient, Feed<Track>> { loadTracks(media) }.getOrNull()
                }
                is Album -> {
                    tracks = ext.getAs<AlbumClient, Feed<Track>?> { loadTracks(media) }.getOrNull()
                }
                else -> tracks = null
            }
            val feed = tracks ?: run {
                adapter.submit(listOf(Row.Info("No se pudieron cargar los tracks")))
                return@launch
            }
            val rows = mutableListOf<Row>()
            rows += Row.Header(media.title, MusicFeed.uiItem(media).subtitle)
            rows += Row.Header("▶ Reproducir todo", null, "Play ›") { playAll(feed) }
            rows += MusicFeed.tracks(ext, feed)
            adapter.submit(rows)
        }
    }

    private fun playAll(tracks: Feed<Track>) {
        val ext = activeExtension ?: return
        scope.launch {
            val list = tracks.loadAll()
            if (list.isNotEmpty()) player.play(ext, list, 0)
        }
    }

    private fun playTrack(track: Track) {
        val ext = activeExtension ?: return
        player.playTrack(ext, track)
    }

    // ------------------------------------------------------------------
    // Búsqueda
    // ------------------------------------------------------------------

    private fun performSearch(query: String) {
        val ext = activeExtension ?: return
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            adapter.submit(listOf(Row.Loading))
            val feed = ext.getAs<SearchFeedClient, Feed<Shelf>> { loadSearchFeed(trimmed) }.getOrNull()
            if (feed == null) {
                adapter.submit(listOf(Row.Info("La extensión no soporta búsqueda")))
            } else {
                adapter.submit(MusicFeed.shelves(ext, feed) { openMore(it) })
            }
        }
    }

    private fun quickSearch(query: String) {
        val ext = activeExtension ?: return
        if (query.trim().length < 3) return
        scope.launch {
            val items = ext.getAs<QuickSearchClient, List<QuickSearchItem>> { quickSearch(query) }.getOrNull()
                ?: return@launch
            val rows = items.map { item ->
                when (item) {
                    is QuickSearchItem.Query -> Row.Header("Buscar: ${item.query}", null) {
                        performSearch(item.query)
                    }
                    is QuickSearchItem.Media -> Row.Card(MusicFeed.uiItem(item.media))
                }
            }
            if (rows.isNotEmpty()) adapter.submit(rows)
        }
    }

    // ------------------------------------------------------------------
    // Tickets → UI del player
    // ------------------------------------------------------------------

    private fun seekToggled() {
        if (player.exo.isPlaying) player.exo.pause() else player.exo.play()
    }

    private fun collectPlayer() {
        scope.launch {
            player.nowPlaying.collect { track ->
                runOnUiThread {
                    currentTrack = track
                    bindMini(track)
                }
            }
        }
        scope.launch {
            player.isPlaying.collect { playing ->
                runOnUiThread {
                    miniPlay.setImageResource(
                        if (playing) R.drawable.ic_music_pause else R.drawable.ic_music_play
                    )
                    if (playing) startTicker() else stopTicker()
                }
            }
        }
        scope.launch {
            player.failures.collect { msg ->
                runOnUiThread { Toast.makeText(this@MusicPlayerActivity, msg, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun bindMini(track: Track?) {
        if (track == null) {
            miniBar.visibility = View.GONE
            return
        }
        miniBar.visibility = View.VISIBLE
        miniTitle.text = track.title
        miniArtist.text = track.artists.joinToString(", ") { it.name }.ifBlank { track.album?.title ?: "" }
        artwork.load(track.cover ?: track.background) { bmp ->
            miniCover.setImageBitmap(bmp)
        }
    }

    private fun startTicker() {
        mainHandler.removeCallbacks(ticker)
        mainHandler.post(ticker)
    }

    private fun stopTicker() {
        mainHandler.removeCallbacks(ticker)
        updateMiniProgress()
    }

    private fun updateMiniProgress() {
        val dur = player.duration()
        val pos = player.position().coerceIn(0L, if (dur <= 0L) 0L else dur)
        if (dur > 0) {
            miniProgress.max = dur.toInt()
            miniProgress.progress = pos.toInt()
        }
        miniCurr.text = pos.toClockTime()
        miniTot.text = if (dur > 0) dur.toClockTime() else "0:00"
    }

    private fun collectMessages() {
        scope.launch {
            registry.messageFlow.collect { message ->
                runOnUiThread {
                    val builder = AlertDialog.Builder(this@MusicPlayerActivity)
                    builder.setTitle("Música")
                    builder.setMessage(message.message)
                    message.action?.let { action ->
                        builder.setPositiveButton(action.name) { _, _ -> action.handler() }
                    }
                    builder.setNegativeButton("Cerrar", null)
                    builder.show()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Diálogos
    // ------------------------------------------------------------------

    private fun chooseExtension() {
        scope.launch {
            val exts = registry.music()
            val labels = exts.map { "${it.metadata.name} · v${it.metadata.version}" }.toMutableList()
            labels += "＋ Importar un APK…"
            labels += "＋ Importar desde URL…"
            if (exts.isNotEmpty()) labels += "Quitar extensión…"
            AlertDialog.Builder(this@MusicPlayerActivity)
                .setTitle("Fuentes de música")
                .setItems(labels.toTypedArray()) { _, which ->
                    when {
                        which < exts.size -> {
                            val ext = exts[which]
                            activeExtension = ext
                            registry.select(ext.id)
                            extLabel.text = ext.metadata.name
                            selectedTabIndex = 0
                            refreshHome()
                        }
                        which == exts.size -> pickApk()
                        which == exts.size + 1 -> askUrl()
                        else -> chooseRemove(exts)
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun chooseRemove(exts: List<MusicExtension>) {
        AlertDialog.Builder(this)
            .setTitle("Quitar extensión")
            .setItems(exts.map { it.metadata.name }.toTypedArray()) { _, which ->
                val ext = exts[which]
                if (registry.remove(ext)) {
                    if (activeExtension?.id == ext.id) activeExtension = null
                    Toast.makeText(this, "Extensión quitada", Toast.LENGTH_SHORT).show()
                    boot()
                } else {
                    Toast.makeText(this,
                        "Es un paquete instalado: desinstálalo desde Android",
                        Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pickApk() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/vnd.android.package-archive", "application/octet-stream"
            ))
        }
        try { startActivityForResult(i, REQ_PICK_APK) }
        catch (e: Exception) { Toast.makeText(this, "No hay selector de archivos", Toast.LENGTH_LONG).show() }
    }

    private fun askUrl() {
        val input = EditText(this).apply {
            hint = "https://…/extension.apk"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Importar desde URL")
            .setView(input)
            .setPositiveButton("Instalar") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) installExtension { registry.importUrl(url) }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_APK || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        installExtension { registry.importUri(uri) }
    }

    private fun installExtension(block: suspend () -> Result<dev.brahmkshatriya.echo.common.models.Metadata>) {
        Toast.makeText(this, "Instalando extensión…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val r = block()
            r.onSuccess { meta ->
                Toast.makeText(this@MusicPlayerActivity, "Instalada: ${meta.name}", Toast.LENGTH_SHORT).show()
                registry.select(meta.id)
                boot()
            }.onFailure { e ->
                AlertDialog.Builder(this@MusicPlayerActivity)
                    .setTitle("No se pudo instalar")
                    .setMessage(installError(e))
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun installError(e: Throwable): String {
        val cause = e.cause ?: e
        val msg = cause.message ?: cause.toString()
        val hint = when {
            msg.contains("HTTP", ignoreCase = true) -> "\n\n· Verifica que el enlace responda (HTTP 200)."
            msg.contains("APK válido", ignoreCase = true) ->
                "\n\n· Copia el enlace directo de descarga del .apk, no la página del navegador."
            msg.contains("vacío", ignoreCase = true) -> "\n\n· El archivo descargado no tiene contenido."
            msg.contains("MUSIC", ignoreCase = true) ->
                "\n\n· El APK debe ser una extensión de música de Echo (feature dev.brahmkshatriya.echo.MUSIC)."
            else -> "\n\n· Si la URL es de GitHub, usa la descarga directa de la versión o el .apk en bruto."
        }
        return msg + hint
    }

    private fun chooseQuality() {
        val prefs = registry.globalPrefs
        val current = prefs.getString("stream_quality", "highest")
        val options = listOf("Alta calidad", "Calidad media", "Baja calidad")
        val values = listOf("highest", "medium", "lowest")
        AlertDialog.Builder(this)
            .setTitle("Calidad del stream")
            .setSingleChoiceItems(options.toTypedArray(), values.indexOf(current).coerceAtLeast(0)) {
                    _, which -> prefs.edit().putString("stream_quality", values[which]).apply()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun chooseHome() = refreshHome()
    private fun chooseLibrary() = refreshLibrary()

    private fun styleTab(tab: TextView, selected: Boolean) {
        tab.setTypeface(
            android.graphics.Typeface.DEFAULT,
            if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
        tab.setTextColor(if (selected) MusicTheme.BG else MusicTheme.MUTED)
        tab.background = if (selected)
            MusicTheme.ripple(this, MusicTheme.rounded(this, MusicTheme.ACCENT, 11))
        else
            MusicTheme.ripple(this, MusicTheme.rounded(this, MusicTheme.SURFACE, 11))
    }

    private fun selectHomeTab() {
        if (selectedTabIndex != 0) selectedTabIndex = 0
        styleTab(tabHome, true)
        styleTab(tabLibrary, false)
        refreshHome()
    }

    private fun selectLibraryTab() {
        styleTab(tabHome, false)
        styleTab(tabLibrary, true)
        refreshLibrary()
    }

    private fun openSlide() {
        if (slide == null) {
            slide = PlayerSlide(
                this, player, artwork, registry,
                extName = activeExtension?.metadata?.name
            )
        }
        slide?.show()
    }

    // ------------------------------------------------------------------
    // Construcción de la UI
    // ------------------------------------------------------------------

    private fun tv(
        text: String,
        sizeSp: Float = 14f,
        bold: Boolean = false,
        secondary: Boolean = false,
        hover: Boolean = true,
    ): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = sizeSp
        t.setTypeface(android.graphics.Typeface.DEFAULT,
            if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        if (hover) t.isClickable = true
        return t
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val ctx = this@MusicPlayerActivity
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MusicTheme.BG)
            setPadding(MusicTheme.dp(ctx, 14), MusicTheme.dp(ctx, 16), MusicTheme.dp(ctx, 14), 0)
        }

        // Cabecera
        extLabel = MusicTheme.tv(ctx, "Música", 24f, bold = true)
        val sub = MusicTheme.tv(ctx, "Reproductor de extensiones Echo · CodeHub", 12f, color = MusicTheme.MUTED)

        // Navegación segmentada (Inicio · Biblioteca)
        val navRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = MusicTheme.rounded(ctx, MusicTheme.SURFACE, 13)
        }
        tabHome = TextView(ctx).apply {
            text = "Inicio"
            textSize = 13f
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { selectHomeTab() }
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
        }
        tabLibrary = TextView(ctx).apply {
            text = "Biblioteca"
            textSize = 13f
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { selectLibraryTab() }
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
        }
        navRow.addView(tabHome)
        navRow.addView(tabLibrary)
        styleTab(tabHome, true)
        styleTab(tabLibrary, false)

        // Chips de acciones: fuentes · calidad · actualizar
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val btnExt = MusicTheme.pill(ctx, "＋ Añadir extensión")
            val btnQual = MusicTheme.pill(ctx, "Calidad")
            val btnRefresh = MusicTheme.pill(ctx, "↻")
            btnExt.setOnClickListener { chooseExtension() }
            btnQual.setOnClickListener { chooseQuality() }
            btnRefresh.setOnClickListener { chooseHome() }
            addView(btnExt)
            addView(btnQual)
            addView(btnRefresh)
            listOf(btnExt, btnQual).forEach { b ->
                val lp = b.layoutParams
                if (lp is ViewGroup.MarginLayoutParams) lp.rightMargin = MusicTheme.dp(ctx, 6)
            }
        }

        // Busqueda redondeada
        searchField = EditText(ctx).apply {
            hint = "Buscar canciones, artistas, playlists…"
            textSize = 14f
            setSingleLine(true)
            setTextColor(MusicTheme.TEXT)
            setHintTextColor(MusicTheme.MUTED)
            setPadding(MusicTheme.dp(ctx, 14), 0, MusicTheme.dp(ctx, 14), 0)
            background = MusicTheme.rounded(ctx, MusicTheme.SURFACE_2, 14)
        }
        searchField.setOnEditorActionListener { _, _, _ ->
            performSearch(searchField.text.toString())
            false
        }
        val btnSearch = MusicTheme.pill(ctx, "Buscar")
        btnSearch.setTextColor(MusicTheme.ACCENT)
        btnSearch.setOnClickListener { performSearch(searchField.text.toString()) }
        val searchRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(searchField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = MusicTheme.dp(ctx, 8) })
            addView(btnSearch)
        }

        adapter = FeedAdapter(this, artwork)
        adapter.onCard = { openList(it) }
        adapter.onTrackClick = { _, track -> playTrack(track) }
        adapter.onCategory = { openCategory(it) }
        adapter.onTabSelected = fun(index: Int) {
            selectedTabIndex = index
            val ext = activeExtension ?: return
            scope.launch {
                val feed = homeFeed ?: return@launch
                val tab = extensionTabs.getOrNull(index)
                val tabsRow = if (extensionTabs.isNotEmpty())
                    listOf(Row.Tabs(extensionTabs, index)) else emptyList()
                adapter.submit(tabsRow + MusicFeed.shelves(ext, feed, tab) { openMore(it) })
            }
        }
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MusicPlayerActivity)
            adapter = this@MusicPlayerActivity.adapter
            setBackgroundColor(MusicTheme.BG)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        // Mini reproductor: tarjeta flotante redondeada con carátula,
        // título/artista, controles (prev/play/next) y progreso con tiempos.
        miniBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(MusicTheme.dp(ctx, 12), MusicTheme.dp(ctx, 10), MusicTheme.dp(ctx, 12), MusicTheme.dp(ctx, 10))
            background = MusicTheme.rounded(ctx, MusicTheme.SURFACE, 18)
            elevation = MusicTheme.dp(ctx, 8).toFloat()
            setOnClickListener { openSlide() }
        }
        miniCover = ImageView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(MusicTheme.dp(ctx, 48), MusicTheme.dp(ctx, 48))
            scaleType = ImageView.ScaleType.CENTER_CROP
            MusicTheme.roundImage(ctx, this, 12)
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(MusicTheme.dp(ctx, 10), 0, 0, 0)
        }
        miniTitle = MusicTheme.tv(ctx, "", 13f, bold = true)
        miniArtist = MusicTheme.tv(ctx, "", 11f, color = MusicTheme.MUTED)
        col.addView(miniTitle)
        col.addView(miniArtist)

        val prevMini = MusicTheme.iconCircle(ctx, R.drawable.ic_music_prev, 24)
        prevMini.setOnClickListener { player.prev() }
        miniPlay = MusicTheme.iconCircle(
            ctx, R.drawable.ic_music_play, 28, bg = MusicTheme.ACCENT, tint = Color.WHITE
        )
        miniPlay.setOnClickListener { seekToggled() }
        val nextMini = MusicTheme.iconCircle(ctx, R.drawable.ic_music_next, 24)
        nextMini.setOnClickListener { player.next() }
        val closeMini = MusicTheme.iconCircle(ctx, R.drawable.ic_music_close, 20, tint = MusicTheme.MUTED)
        closeMini.setOnClickListener { player.stop() }
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(prevMini)
            addView(miniPlay)
            addView(nextMini)
            addView(closeMini)
        }
        listOf(prevMini, miniPlay).forEach { b ->
            val lp = b.layoutParams
            if (lp is ViewGroup.MarginLayoutParams) lp.rightMargin = MusicTheme.dp(ctx, 6)
        }

        miniCurr = MusicTheme.tv(ctx, "0:00", 10f, color = MusicTheme.MUTED)
        miniTot = MusicTheme.tv(ctx, "0:00", 10f, color = MusicTheme.MUTED)
        miniTot.gravity = Gravity.END
        val timeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(miniCurr, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(miniTot)
        }
        miniProgress = SeekBar(ctx).apply {
            max = 0
            MusicTheme.tintSeek(this)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, MusicTheme.dp(ctx, 26))
        }
        miniProgress.setOnClickListener { openSlide() }

        val miniTop = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(miniCover)
            addView(col)
            addView(controls)
        }
        miniBar.addView(miniTop)
        miniBar.addView(timeRow)
        miniBar.addView(miniProgress)

        root.addView(extLabel)
        root.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = MusicTheme.dp(ctx, 2); bottomMargin = MusicTheme.dp(ctx, 10) })
        root.addView(navRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = MusicTheme.dp(ctx, 8) })
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = MusicTheme.dp(ctx, 12) })
        root.addView(searchRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = MusicTheme.dp(ctx, 4) })
        root.addView(list)
        root.addView(miniBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = MusicTheme.dp(ctx, 8); bottomMargin = MusicTheme.dp(ctx, 12) })

        return root
    }

    private fun openCategory(category: Category) {
        val ext = activeExtension ?: return
        val feed = category.feed ?: return
        scope.launch {
            adapter.submit(listOf(Row.Loading))
            adapter.submit(MusicFeed.shelves(ext, feed) { openMore(it) })
        }
    }
}