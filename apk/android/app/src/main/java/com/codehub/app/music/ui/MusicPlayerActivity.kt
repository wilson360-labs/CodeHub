package com.codehub.app.music.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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
import com.codehub.app.music.ArtworkLoader
import com.codehub.app.music.MusicClient.getAs
import com.codehub.app.music.MusicPlayer
import com.codehub.app.music.MusicRegistry
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

    // Mini player
    private lateinit var miniBar: LinearLayout
    private lateinit var miniCover: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlay: TextView
    private lateinit var miniProgress: SeekBar

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

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window.let { it.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
        registry = MusicRegistry(this)
        player = MusicPlayer(this, scope)
        setContentView(buildUi())
        collectPlayer()
        collectMessages()
        boot()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(ticker)
        scope.coroutineContext[Job]?.cancel()
        player.release()
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
                adapter.submit(listOf(Row.Info(
                    "No hay extensiones de música. Instala un APK de " +
                            "extensión Echo desde la web."
                )))
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
                adapter.submit(listOf(Row.Info("El home no está disponible")));
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
            rows += Row.Header("▶ Reproducir todo", null) { playAll(feed) }
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
                    miniPlay.text = if (playing) "⏸" else "▶"
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
        val pos = player.position().coerceIn(0, if (dur <= 0) 0 else dur)
        if (dur > 0) {
            miniProgress.max = dur.toInt()
            miniProgress.progress = pos.toInt()
        }
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
            if (exts.isEmpty()) {
                Toast.makeText(this@MusicPlayerActivity, "No hay extensiones", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val names = exts.map { it.metadata.name }
            AlertDialog.Builder(this@MusicPlayerActivity)
                .setTitle("Extensión de música")
                .setItems(names.toTypedArray()) { _, which ->
                    val ext = exts[which]
                    activeExtension = ext
                    registry.select(ext.id)
                    extLabel.text = ext.metadata.name
                    refreshHome()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
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

    private fun openSlide() {
        if (slide == null) {
            slide = PlayerSlide(this, player, artwork, registry)
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ThemeColors.of(this@MusicPlayerActivity, android.R.attr.colorBackground, Color.WHITE))
            setPadding(dp(8), dp(12), dp(8), 0)
        }

        extLabel = tv("🎵 Música", 22f, bold = true)
        val sub = tv("", 12f, secondary = true)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val btnExt = tv("📦 Extensión", 13f, hover = true)
            val btnLib = tv("📚 Biblioteca", 13f, hover = true)
            val btnQual = tv("⚙ Calidad", 13f, hover = true)
            val btnRefresh = tv("⟳", 18f, hover = true)
            btnExt.setOnClickListener { chooseExtension() }
            btnLib.setOnClickListener { chooseLibrary() }
            btnQual.setOnClickListener { chooseQuality() }
            btnRefresh.setOnClickListener { chooseHome() }
            addView(btnExt)
            addView(btnLib)
            addView(btnQual)
            addView(btnRefresh)
        }

        searchField = EditText(this).apply {
            hint = "Buscar canciones, artistas…"
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
        }
        searchField.setOnEditorActionListener { _, _, _ ->
            performSearch(searchField.text.toString())
            false
        }
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val btnSearch = tv("🔎 Buscar", 13f, hover = true)
            btnSearch.setOnClickListener { performSearch(searchField.text.toString()) }
            addView(searchField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
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
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        list.setBackgroundColor(ThemeColors.of(this, android.R.attr.colorBackground, Color.WHITE))

        // Mini barra de reproducción
        miniBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(ThemeColors.of(this@MusicPlayerActivity, android.R.attr.colorBackground, Color.WHITE))
            setOnClickListener { openSlide() }
        }
        miniCover = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(dp(44), dp(44))
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(10), 0, dp(10), 0)
        }
        miniTitle = tv("", 13f, bold = true, hover = false)
        miniArtist = tv("", 11f, secondary = true, hover = false)
        col.addView(miniTitle)
        col.addView(miniArtist)
        miniProgress = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10)
            )
            max = 0
        }
        miniProgress.setOnClickListener { openSlide() }
        val progressCol = LinearLayout(this)
        progressCol.orientation = LinearLayout.VERTICAL
        progressCol.addView(miniProgress)

        miniPlay = tv("▶", 26f, hover = true)
        miniPlay.setOnClickListener { seekToggled() }
        val nextBtn = tv("⏭", 20f, hover = true)
        nextBtn.setOnClickListener { player.next() }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(miniPlay)
            addView(nextBtn)
        }

        val bottomRow = LinearLayout(this)
        bottomRow.orientation = LinearLayout.HORIZONTAL
        bottomRow.gravity = Gravity.CENTER_VERTICAL
        bottomRow.addView(progressCol, LinearLayout.LayoutParams(dp(0), ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottomRow.addView(controls)

        miniBar.addView(miniCover)
        miniBar.addView(col)
        miniBar.addView(bottomRow)
        miniBar.setOnClickListener { openSlide() }

        root.addView(extLabel)
        root.addView(sub)
        root.addView(actions)
        root.addView(searchRow)
        root.addView(list)
        root.addView(miniBar)

        sub.text = "extensiones Echo · slide de CodeHub"
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