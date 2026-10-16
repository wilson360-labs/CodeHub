package com.codehub.app.music.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codehub.app.R
import com.codehub.app.music.ArtworkLoader
import com.codehub.app.music.MusicClient.getAs
import com.codehub.app.music.MusicDownloads
import com.codehub.app.music.MusicPlayer
import com.codehub.app.music.MusicPlayerDuration.toClockTime
import com.codehub.app.music.MusicPlaylists
import com.codehub.app.music.MusicRegistry
import com.codehub.app.music.MusicWebViewClient
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LyricsClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.PlaylistEditClient
import dev.brahmkshatriya.echo.common.clients.QuickSearchClient
import dev.brahmkshatriya.echo.common.clients.RadioClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Shelf.Category
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.loadAll
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.Lyrics
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.QuickSearchItem
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reproductor "slide" de CodeHub: el player nativo que corre extensiones
 * Echo (common 1.0.0). Activity de framework, UI programática.
 */
class MusicPlayerActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var registry: MusicRegistry
    private lateinit var player: MusicPlayer
    private lateinit var downloads: MusicDownloads
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

    /** Playlists locales (incluye la lista "Me gusta" del corazón). */
    private lateinit var playlists: MusicPlaylists

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

    // Navegación con historial (Inicio/Biblioteca → Ver todo/álbum/búsqueda…)
    private class Screen(val localId: String?, val render: () -> Unit)

    private val navStack = ArrayDeque<Screen>()
    private var currentScreen: Screen? = null

    /** id de la playlist local que se está mostrando (para "quitar de la lista"). */
    private var currentLocalId: String? = null
    private var lastRows: List<Row> = emptyList()
    private var quickSearchJob: Runnable? = null

    /** Envía filas al adaptador y recuerda el último feed (para la cola
     *  contextual al tocar una canción). */
    private fun submit(rows: List<Row>) {
        lastRows = rows
        adapter.submit(rows)
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        window.let { it.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
        com.codehub.app.SystemBars.fit(this)
        registry = MusicRegistry(this)
        (registry.webViewClient as? MusicWebViewClient)?.attach(this)
        // Instancia única por proceso: la mantiene MusicPlaybackService para
        // que la música siga sonando al cerrar la Activity.
        player = MusicPlayer.shared(this)
        downloads = MusicDownloads.shared(this)
        playlists = MusicPlaylists.shared(this)
        player.downloads = downloads
        requestNotificationPermission()
        setContentView(buildUi())
        com.codehub.app.SystemBars.applyInsets(this)
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
                    "Instala una extensión CodeBeat (APK): Spotify, Deezer, YouTube Music y más. Al instalarla podrás iniciar sesión y reproducir dentro de esta app."
                )
                rows += Row.Header("＋ Importar un APK", null, "Elegir archivo ›") { pickApk() }
                rows += Row.Header("＋ Importar desde URL", null, "Pegar enlace ›") { askUrl() }
                registry.lastErrors.firstOrNull()?.let {
                    rows += Row.Info("Extensión inválida ignorada: $it")
                }
                extLabel.text = "Música"
                submit(rows)
                return@launch
            }
            registry.select(activeExtension!!.id)
            extLabel.text = activeExtension?.metadata?.name ?: "Música"
            home()
        }
    }

    private fun refreshHome() {
        val ext = activeExtension ?: return
        scope.launch {
            submit(listOf(Row.Loading))
            val home = runCatching { ext.getAs<HomeFeedClient, Feed<Shelf>> { loadHomeFeed() }.getOrThrow() }
            if (home.isFailure) {
                submit(listOf(Row.Info(
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
            submit(downloadsRows() + playlistsRows() + tabsRow + MusicFeed.shelves(ext, homeFeed!!, tab) {
                openMore(it)
            })
        }
    }

    private fun refreshLibrary() {
        val ext = activeExtension ?: return
        scope.launch {
            submit(listOf(Row.Loading))
            val lib = runCatching { ext.getAs<LibraryFeedClient, Feed<Shelf>> { loadLibraryFeed() }.getOrThrow() }
            if (lib.isFailure) {
                submit(listOf(Row.Info("La biblioteca no está disponible")))
                return@launch
            }
            libraryFeed = lib.getOrNull()
            submit(downloadsRows() + playlistsRows() + MusicFeed.shelves(ext, libraryFeed!!) { openMore(it) })
        }
    }

    /** Sección "Descargas · sin conexión" del feed (tracks offline). */
    private fun downloadsRows(): List<Row> {
        val tracks = downloads.entries.value.mapNotNull { it.track() }
        if (tracks.isEmpty()) return emptyList()
        val rows = mutableListOf<Row>()
        rows += Row.Header("Descargas · sin conexión", "${tracks.size} canción(es)", "Gestionar ›") {
            openDownloadsDialog()
        }
        rows += Row.Header("▶ Reproducir todas", null, "Play ›") {
            val ext = activeExtension
            if (ext != null && tracks.isNotEmpty()) player.play(ext, tracks, 0)
        }
        tracks.forEachIndexed { index, t -> rows += Row.TrackRow(index, t) }
        return rows
    }

    /** Sección "Mis listas" del feed (playlists locales, incluida Me gusta). */
    private fun playlistsRows(): List<Row> {
        val all = playlists.playlists.value
        val rows = mutableListOf<Row>()
        rows += Row.Header(
            "Mis listas",
            if (all.isEmpty()) null else "${all.size} lista(s)",
            "＋ Nueva ›",
        ) { createPlaylistDialog() }
        if (all.isEmpty()) {
            rows += Row.Info("Crea una lista y añade canciones con el menú ⋮ de cada canción.")
            return rows
        }
        // "Me gusta" primero, luego el resto por fecha de creación.
        val ordered = all.sortedWith(
            compareByDescending<MusicPlaylists.Entry> { it.id == MusicPlaylists.LIKED_ID }
                .thenByDescending { it.createdAt }
        )
        ordered.forEach { entry ->
            rows += Row.Card(
                UiItem(
                    media = entry.echo(),
                    subtitle = "${entry.tracks.size} canción(es)",
                    image = null,
                )
            )
        }
        return rows
    }

    private fun openMore(feed: Feed<Shelf>) = go { showMore(feed) }

    private fun showMore(feed: Feed<Shelf>) {
        val ext = activeExtension ?: return
        scope.launch {
            submit(listOf(Row.Loading))
            submit(listOf(backRow()) + MusicFeed.shelves(ext, feed) { openMore(it) })
        }
    }

    // ------------------------------------------------------------------
    // Navegación con historial
    // ------------------------------------------------------------------

    /** Abre una pantalla nueva guardando la actual en la pila.
     *  [localId] marca la playlist local que se muestra (si aplica). */
    private fun go(localId: String? = null, render: () -> Unit) {
        currentScreen?.let { navStack.addLast(it) }
        val screen = Screen(localId, render)
        currentScreen = screen
        currentLocalId = localId
        render()
    }

    /** Vuelve a la pantalla anterior; false si ya estamos en la raíz. */
    private fun goBack(): Boolean {
        if (navStack.isEmpty()) return false
        val prev = navStack.removeLast()
        currentScreen = prev
        currentLocalId = prev.localId
        prev.render()
        return true
    }

    /** Re-renderiza la pantalla actual (tras editar una lista). */
    private fun refreshCurrent() {
        currentScreen?.render()
    }

    override fun onBackPressed() {
        if (!goBack()) super.onBackPressed()
    }

    private fun backRow(): Row = Row.Header("‹ Atrás", null, "") { goBack() }

    private fun home() {
        navStack.clear()
        currentScreen = Screen(null) { refreshHome() }
        currentLocalId = null
        refreshHome()
    }

    private fun library() {
        navStack.clear()
        currentScreen = Screen(null) { refreshLibrary() }
        currentLocalId = null
        refreshLibrary()
    }

    private fun openList(media: EchoMediaItem) {
        when (media) {
            is Track -> playTrack(media)
            is Playlist ->
                if (media.id.startsWith(MusicPlaylists.LOCAL_PREFIX)) {
                    val id = media.id.removePrefix(MusicPlaylists.LOCAL_PREFIX)
                    go(localId = id) { showLocalPlaylist(id) }
                } else go { showTracks(media) }
            is Album -> go { showTracks(media) }
            is Radio -> go { showRadio(media) }
            is Artist -> go { showArtist(media) }
        }
    }

    private fun showTracks(media: EchoMediaItem) {
        val ext = activeExtension ?: return
        scope.launch {
            submit(listOf(Row.Loading))
            val feed: Feed<Track>? = when (media) {
                is Playlist -> ext.getAs<PlaylistClient, Feed<Track>> { loadTracks(media) }.getOrNull()
                is Album -> ext.getAs<AlbumClient, Feed<Track>?> { loadTracks(media) }.getOrNull()
                else -> null
            }
            if (feed == null) {
                submit(listOf(backRow(), Row.Info("No se pudieron cargar los tracks")))
                return@launch
            }
            val rows = mutableListOf<Row>()
            rows += backRow()
            rows += Row.Header(media.title, MusicFeed.uiItem(media).subtitle)
            rows += Row.Header("▶ Reproducir todo", null, "Play ›") { playAll(feed) }
            rows += MusicFeed.tracks(ext, feed)
            submit(rows)
        }
    }

    private fun showRadio(media: EchoMediaItem) {
        val ext = activeExtension ?: return
        scope.launch {
            submit(listOf(Row.Loading))
            val feed = ext.getAs<RadioClient, Feed<Track>> { loadTracks(media as Radio) }.getOrNull()
            if (feed == null) {
                submit(listOf(backRow(), Row.Info("No se pudo cargar la radio")))
            } else {
                submit(listOf(backRow()) + MusicFeed.tracks(ext, feed))
            }
        }
    }

    private fun showArtist(media: EchoMediaItem) {
        val ext = activeExtension ?: return
        scope.launch {
            submit(listOf(Row.Loading))
            val feed = ext.getAs<ArtistClient, Feed<Shelf>> { loadFeed(media as Artist) }.getOrNull()
            if (feed == null) {
                submit(listOf(backRow(), Row.Info("No se pudo cargar el artista")))
            } else {
                submit(listOf(backRow()) + MusicFeed.shelves(ext, feed) { openMore(it) })
            }
        }
    }

    private fun playAll(tracks: Feed<Track>) {
        val ext = activeExtension ?: return
        scope.launch {
            // La carga de páginas del feed hace red: fuera del hilo principal.
            val list = runCatching { withContext(Dispatchers.IO) { tracks.loadAll() } }
                .getOrNull().orEmpty()
            if (list.isNotEmpty()) player.play(ext, list, 0)
        }
    }

    private fun playTrack(track: Track) {
        val ext = activeExtension ?: return
        player.playTrack(ext, track)
    }

    /** Click en una canción de una lista: encola el contexto (álbum, lista o
     *  descargas) en vez de reproducir solo ese track. */
    private fun playFromList(number: Int, track: Track) {
        // En una lista local, resolvemos la extensión de origen de las pistas.
        val localId = currentLocalId
        if (localId != null) {
            val entry = playlists.byId(localId)
            if (entry != null) {
                playLocal(entry, track)
                return
            }
        }
        val ext = activeExtension ?: return
        val group = trackGroup(number, track)
        val index = group.indexOfFirst { it.id == track.id }
        if (group.size > 1 && index >= 0) player.play(ext, group, index)
        else player.playTrack(ext, track)
    }

    /** Reconstruye el grupo contiguo de tracks (una lista) que contiene el
     *  click, a partir de las filas mostradas. */
    private fun trackGroup(number: Int, track: Track): List<Track> {
        val groups = mutableListOf<MutableList<Track>>()
        var current: MutableList<Track>? = null
        for (row in lastRows) {
            if (row is Row.TrackRow) {
                val g = current ?: mutableListOf<Track>().also { groups += it; current = it }
                g += row.track
            } else {
                current = null
            }
        }
        return groups.firstOrNull { it.getOrNull(number)?.id == track.id } ?: listOf(track)
    }

    /** Añade a la cola garantizando que el player conozca la extensión activa
     *  (necesario si aún no se ha reproducido nada). */
    private fun queueAdd(tracks: List<Track>, next: Boolean = false) {
        if (tracks.isEmpty()) return
        if (player.extension == null) player.extension = activeExtension
        player.addAllToQueue(tracks, next)
    }

    // ------------------------------------------------------------------
    // Búsqueda
    // ------------------------------------------------------------------

    private fun performSearch(query: String) = go { showSearch(query) }

    private fun showSearch(query: String) {
        val ext = activeExtension ?: return
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            submit(listOf(Row.Loading))
            val feed = ext.getAs<SearchFeedClient, Feed<Shelf>> { loadSearchFeed(trimmed) }.getOrNull()
            if (feed == null) {
                submit(listOf(backRow(), Row.Info("La extensión no soporta búsqueda")))
            } else {
                submit(listOf(backRow()) + MusicFeed.shelves(ext, feed) { openMore(it) })
            }
        }
    }

    /** Sugerencias inmediatas mientras se escribe (con retardo para no
     *  golpear la extensión en cada tecla). */
    private fun scheduleQuickSearch(query: String) {
        quickSearchJob?.let { mainHandler.removeCallbacks(it) }
        if (query.trim().length < 3) return
        val job = Runnable { quickSearch(query) }
        quickSearchJob = job
        mainHandler.postDelayed(job, 300)
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
            if (rows.isNotEmpty()) submit(rows)
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
        scope.launch {
            player.events.collect { msg ->
                runOnUiThread { Toast.makeText(this@MusicPlayerActivity, msg, Toast.LENGTH_SHORT).show() }
            }
        }
        scope.launch {
            downloads.failures.collect { msg ->
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
                            selectHomeTab()
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
                "\n\n· El APK debe ser una extensión de música de CodeBeat (feature dev.brahmkshatriya.echo.MUSIC)."
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

    private fun chooseHome() = home()
    private fun chooseLibrary() = library()

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
        home()
    }

    private fun selectLibraryTab() {
        styleTab(tabHome, false)
        styleTab(tabLibrary, true)
        library()
    }

    private fun openSlide() {
        if (slide == null) {
            slide = PlayerSlide(
                this, player, artwork, registry,
                extName = activeExtension?.metadata?.name,
                onLyrics = { showLyrics(player.nowPlaying.value) },
                onAddToPlaylist = { addToPlaylistDialog(it) },
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
        val sub = MusicTheme.tv(ctx, "CodeBeat 🔥 · Reproductor en streaming", 12f, color = MusicTheme.MUTED)

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
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                scheduleQuickSearch(s?.toString().orEmpty())
            }
        })
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
        adapter.onTrackClick = { index, track -> playFromList(index, track) }
        adapter.onTrackLongClick = { index, track -> showTrackMenu(index, track) }
        adapter.onCategory = { openCategory(it) }
        adapter.onTabSelected = fun(index: Int) {
            selectedTabIndex = index
            val ext = activeExtension ?: return
            scope.launch {
                val feed = homeFeed ?: return@launch
                val tab = extensionTabs.getOrNull(index)
                val tabsRow = if (extensionTabs.isNotEmpty())
                    listOf(Row.Tabs(extensionTabs, index)) else emptyList()
                submit(tabsRow + MusicFeed.shelves(ext, feed, tab) { openMore(it) })
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
        val feed = category.feed ?: return
        go { showCategory(feed, category.title) }
    }

    private fun showCategory(feed: Feed<Shelf>, title: String) {
        val ext = activeExtension ?: return
        scope.launch {
            submit(listOf(Row.Loading))
            val rows = mutableListOf<Row>()
            rows += Row.Header("‹ Atrás", title, "") { goBack() }
            rows += MusicFeed.shelves(ext, feed) { openMore(it) }
            submit(rows)
        }
    }

    // ------------------------------------------------------------------
    // Gestión de descargas
    // ------------------------------------------------------------------

    private fun openDownloadsDialog() {
        val entries = downloads.entries.value
        if (entries.isEmpty()) {
            Toast.makeText(this, "No hay descargas", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = entries.map { e ->
            val t = e.track()
            val mb = e.size / (1024L * 1024L)
            val artists = t?.artists?.joinToString(", ") { it.name }.orEmpty()
            listOf(t?.title ?: e.trackId, mb.toString() + " MB")
                .joinToString(" · ") + if (artists.isBlank()) "" else " · $artists"
        }.toMutableList()
        labels += "Vaciar todas las descargas"
        AlertDialog.Builder(this)
            .setTitle("Descargas (${entries.size})")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < entries.size) {
                    downloads.remove(entries[which].trackId)
                    Toast.makeText(this, "Descarga eliminada", Toast.LENGTH_SHORT).show()
                    refreshCurrent()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("Vaciar descargas")
                        .setMessage("¿Eliminar las ${entries.size} canciones sin conexión?")
                        .setPositiveButton("Vaciar") { _, _ ->
                            downloads.clearAll()
                            Toast.makeText(this, "Descargas eliminadas", Toast.LENGTH_SHORT).show()
                            refreshCurrent()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Playlists locales
    // ------------------------------------------------------------------

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** Pantalla de una lista local: cabecera + pistas guardadas. */
    private fun showLocalPlaylist(id: String) {
        val entry = playlists.byId(id)
        if (entry == null) {
            submit(listOf(backRow(), Row.Info("Esta lista ya no existe")))
            return
        }
        val tracks = playlists.tracks(entry)
        val rows = mutableListOf<Row>()
        rows += backRow()
        rows += Row.Header(entry.title, "${tracks.size} canción(es)", "Editar ›") {
            localPlaylistMenu(entry)
        }
        if (tracks.isNotEmpty()) {
            rows += Row.Header("▶ Reproducir todo", null, "Play ›") { playLocal(entry, null) }
            rows += Row.Header("＋ Añadir todo a la cola", null, "Cola ›") {
                queueAdd(playlists.tracks(entry))
            }
            rows += MusicFeed.trackRows(tracks)
        } else {
            rows += Row.Info("Lista vacía. Añade canciones con el botón ⋮ de cada canción.")
        }
        submit(rows)
    }

    private fun localPlaylistMenu(entry: MusicPlaylists.Entry) {
        val labels = arrayOf(
            "▶  Reproducir todo",
            "⏭  Reproducir a continuación",
            "＋  Añadir todo a la cola",
            "✎  Renombrar la lista",
            "🗑  Eliminar la lista",
        )
        AlertDialog.Builder(this)
            .setTitle(entry.title)
            .setItems(labels) { _, which ->
                val tracks = playlists.tracks(entry)
                when (which) {
                    0 -> playLocal(entry, null)
                    1 -> queueAdd(tracks, next = true)
                    2 -> queueAdd(tracks)
                    3 -> renamePlaylistDialog(entry)
                    4 -> confirmDeletePlaylist(entry)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Reproduce una lista local resolviendo su extensión de origen. */
    private fun playLocal(entry: MusicPlaylists.Entry, startTrack: Track?) {
        val all = playlists.tracks(entry)
        if (all.isEmpty()) {
            toast("La lista está vacía")
            return
        }
        val seed = startTrack ?: all.first()
        val wanted = entry.tracks.firstOrNull { it.trackId == seed.id }?.extensionId
        scope.launch {
            val exts = registry.music()
            val ext = (if (wanted.isNullOrBlank()) null else exts.firstOrNull { it.id == wanted })
                ?: activeExtension
            if (ext == null) {
                toast("Extensión no disponible")
                return@launch
            }
            val playable = if (wanted.isNullOrBlank()) all
            else entry.tracks.filter { it.extensionId == ext.id }.mapNotNull { it.track() }
            val skipped = entry.tracks.size - playable.size
            if (skipped > 0) toast("$skipped canción(es) de otra extensión omitidas")
            if (playable.isEmpty()) {
                toast("No hay canciones reproducibles")
                return@launch
            }
            val index = playable.indexOfFirst { it.id == seed.id }.coerceAtLeast(0)
            player.play(ext, playable, index)
        }
    }

    private fun createPlaylistDialog(after: ((MusicPlaylists.Entry) -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "Nombre de la lista"
            setSingleLine(true)
            setTextColor(MusicTheme.TEXT)
            setHintTextColor(MusicTheme.MUTED)
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("Nueva lista")
            .setView(box)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Mi lista" }
                val created = playlists.create(name, null)
                toast("Lista creada: ${created.title}")
                if (after != null) after(created) else refreshCurrent()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun renamePlaylistDialog(entry: MusicPlaylists.Entry) {
        val input = EditText(this).apply {
            setText(entry.title)
            setSingleLine(true)
            setTextColor(MusicTheme.TEXT)
            setHintTextColor(MusicTheme.MUTED)
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("Renombrar la lista")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                val name = input.text.toString().trim().ifBlank { entry.title }
                playlists.rename(entry.id, name, entry.description)
                toast("Lista renombrada")
                refreshCurrent()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDeletePlaylist(entry: MusicPlaylists.Entry) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar lista")
            .setMessage("¿Eliminar «${entry.title}” y sus ${entry.tracks.size} canciones?")
            .setPositiveButton("Eliminar") { _, _ ->
                playlists.delete(entry.id)
                toast("Lista eliminada")
                if (!goBack()) refreshCurrent()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Hoja "Añadir a lista": listas locales + playlists editables de la extensión. */
    private fun addToPlaylistDialog(track: Track) {
        val ext = activeExtension
        scope.launch {
            val loading = AlertDialog.Builder(this@MusicPlayerActivity)
                .setMessage("Cargando listas…")
                .setCancelable(true)
                .show()
            val locals = playlists.playlists.value
            val remote = ext?.let { e ->
                e.getAs<PlaylistEditClient, List<Pair<Playlist, Boolean>>> {
                    listEditablePlaylists(track)
                }.getOrNull()
            }.orEmpty()
            loading.dismiss()

            val labels = mutableListOf<String>()
            val actions = mutableListOf<() -> Unit>()

            locals.forEach { entry ->
                val has = entry.tracks.any { it.trackId == track.id }
                labels += (if (has) "✓  " else "＋  ") + entry.title +
                        "  ·  ${entry.tracks.size} canción(es)"
                actions += {
                    if (has) {
                        playlists.remove(entry.id, track.id)
                        toast("Quitada de ${entry.title}")
                    } else {
                        playlists.add(entry.id, track, ext?.id ?: "")
                        toast("Añadida a ${entry.title}")
                    }
                    refreshCurrent()
                }
            }
            labels += "＋  Nueva lista local…"
            actions += {
                createPlaylistDialog { created ->
                    playlists.add(created.id, track, ext?.id ?: "")
                    toast("Añadida a ${created.title}")
                    refreshCurrent()
                }
            }
            if (ext != null) {
                labels += "＋  Nueva lista en ${ext.metadata.name}…"
                actions += { createRemotePlaylistAndAdd(track) }
            }
            if (remote.isNotEmpty()) {
                labels += "—  En tu cuenta  —"
                actions += { }
                remote.forEach { (playlist, has) ->
                    labels += (if (has) "✓  " else "＋  ") + playlist.title
                    actions += { toggleRemotePlaylist(playlist, track, has) }
                }
            }

            AlertDialog.Builder(this@MusicPlayerActivity)
                .setTitle("Añadir a lista")
                .setItems(labels.toTypedArray()) { _, which -> actions.getOrNull(which)?.invoke() }
                .setNegativeButton("Cerrar", null)
                .show()
        }
    }

    private fun createRemotePlaylistAndAdd(track: Track) {
        val ext = activeExtension ?: return
        val input = EditText(this).apply {
            hint = "Nombre de la lista"
            setSingleLine(true)
            setTextColor(MusicTheme.TEXT)
            setHintTextColor(MusicTheme.MUTED)
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("Nueva lista en ${ext.metadata.name}")
            .setView(box)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "Mi lista" }
                scope.launch {
                    val created = ext.getAs<PlaylistEditClient, Playlist> {
                        createPlaylist(name, null)
                    }.getOrNull()
                    if (created == null) {
                        toast("La extensión no permite crear listas")
                        return@launch
                    }
                    val ok = ext.getAs<PlaylistEditClient, Unit> {
                        addTracksToPlaylist(created, emptyList(), 0, listOf(track))
                    }.isSuccess
                    toast(if (ok) "Añadida a ${created.title}" else "No se pudo añadir a la lista")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toggleRemotePlaylist(playlist: Playlist, track: Track, has: Boolean) {
        val ext = activeExtension ?: return
        scope.launch {
            val result = ext.getAs<PlaylistEditClient, Unit> {
                if (has) removeTracksFromPlaylist(playlist, listOf(track), listOf(0))
                else addTracksToPlaylist(playlist, emptyList(), 0, listOf(track))
            }
            toast(
                when {
                    result.isFailure -> "No se pudo editar la lista"
                    has -> "Quitada de ${playlist.title}"
                    else -> "Añadida a ${playlist.title}"
                }
            )
        }
    }

    // ------------------------------------------------------------------
    // Menú contextual de una canción
    // ------------------------------------------------------------------

    private fun showTrackMenu(number: Int, track: Track) {
        val group = trackGroup(number, track)
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        fun option(label: String, action: () -> Unit) {
            labels += label
            actions += action
        }

        option("▶  Reproducir ahora") { playFromList(number, track) }
        option("⏭  Reproducir a continuación") { queueAdd(listOf(track), next = true) }
        option("＋  Añadir a la cola") { queueAdd(listOf(track)) }
        if (group.size > 1) {
            option("＋  Añadir las ${group.size} canciones") { queueAdd(group) }
        }
        option("＋  Añadir a lista…") { addToPlaylistDialog(track) }
        option("♪  Ver letras") { showLyrics(track) }
        if (track.isRadioSupported) {
            option("📻  Radio desde esta canción") { startRadio(track) }
        }

        val localId = currentLocalId
        val localEntry = localId?.let { playlists.byId(it) }
        if (localId != null && localEntry != null) {
            val idx = localEntry.tracks.indexOfFirst { it.trackId == track.id }
            if (idx >= 0) {
                if (idx > 0) {
                    option("▲  Subir en la lista") {
                        playlists.move(localId, idx, idx - 1)
                        refreshCurrent()
                    }
                }
                if (idx < localEntry.tracks.size - 1) {
                    option("▼  Bajar en la lista") {
                        playlists.move(localId, idx, idx + 1)
                        refreshCurrent()
                    }
                }
                option("✕  Quitar de esta lista") {
                    playlists.remove(localId, track.id)
                    toast("Quitada de la lista")
                    refreshCurrent()
                }
            }
        }

        val ext = activeExtension
        if (ext != null) {
            if (downloads.lookup(track.id) != null) {
                option("🗑  Quitar la descarga") {
                    downloads.remove(track.id)
                    toast("Descarga eliminada")
                    refreshCurrent()
                }
            } else {
                option("⤓  Descargar para offline") {
                    downloads.enqueue(track, ext)
                    toast("Descargando…")
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle(track.title)
            .setItems(labels.toTypedArray()) { _, which -> actions.getOrNull(which)?.invoke() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Inicia la "radio" de la extensión a partir de una canción. */
    private fun startRadio(track: Track) {
        val ext = activeExtension ?: return
        scope.launch {
            val loading = AlertDialog.Builder(this@MusicPlayerActivity)
                .setMessage("Cargando radio…")
                .setCancelable(true)
                .show()
            val tracks = runCatching {
                withContext(Dispatchers.IO) {
                    val base = ext.getAs<RadioClient, Radio> { radio(track, null) }.getOrNull()
                        ?: return@withContext emptyList<Track>()
                    val loaded = ext.getAs<RadioClient, Radio> { loadRadio(base) }.getOrNull() ?: base
                    val feed = ext.getAs<RadioClient, Feed<Track>> { loadTracks(loaded) }.getOrNull()
                    feed?.loadAll().orEmpty()
                }
            }.getOrNull().orEmpty()
            loading.dismiss()
            if (tracks.isEmpty()) {
                toast("Esta canción no tiene radio disponible")
                return@launch
            }
            player.play(ext, tracks, 0)
        }
    }

    // ------------------------------------------------------------------
    // Letras
    // ------------------------------------------------------------------

    private fun showLyrics(track: Track?) {
        val t = track ?: player.nowPlaying.value
        if (t == null) {
            toast("Nada en reproducción")
            return
        }
        val ext = activeExtension ?: return
        scope.launch {
            val loading = AlertDialog.Builder(this@MusicPlayerActivity)
                .setMessage("Buscando letras…")
                .setCancelable(true)
                .show()
            val lyrics = loadLyrics(ext, t)
            loading.dismiss()
            if (lyrics == null) {
                AlertDialog.Builder(this@MusicPlayerActivity)
                    .setTitle(t.title)
                    .setMessage("No hay letras disponibles para esta canción.")
                    .setPositiveButton("Cerrar", null)
                    .show()
                return@launch
            }
            presentLyrics(t, lyrics)
        }
    }

    private suspend fun loadLyrics(ext: MusicExtension, track: Track): Lyrics? {
        val feed = ext.getAs<LyricsClient, Feed<Lyrics>> {
            searchTrackLyrics(ext.id, track)
        }.getOrNull() ?: return null
        val candidates = runCatching {
            withContext(Dispatchers.IO) { feed.pagedDataOfFirst().loadAll() }
        }.getOrNull().orEmpty()
        val first = candidates.firstOrNull() ?: return null
        return if (first.lyrics != null) first
        else ext.getAs<LyricsClient, Lyrics> { loadLyrics(first) }.getOrNull() ?: first
    }

    private fun presentLyrics(track: Track, lyrics: Lyrics) {
        val timed = lyrics.lyrics as? Lyrics.Timed
        val plain = lyrics.lyrics as? Lyrics.Simple
        val wordByWord = lyrics.lyrics as? Lyrics.WordByWord

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        content.addView(tv("Letras", 17f, bold = true))
        content.addView(tv(lyrics.title, 12f).apply {
            setTextColor(MusicTheme.MUTED)
            setPadding(0, dp(2), 0, dp(8))
        })

        if (timed == null && plain == null && wordByWord == null) {
            content.addView(tv("La extensión no devolvió el texto de la letra.", 14f).apply {
                setTextColor(MusicTheme.MUTED)
                setPadding(0, dp(8), 0, dp(8))
            })
            AlertDialog.Builder(this).setView(content).setPositiveButton("Cerrar", null).show()
            return
        }

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val lineViews = mutableListOf<TextView>()
        val lineTimes = mutableListOf<Long>()

        when {
            timed != null -> timed.list.forEach { item ->
                val v = tv(item.text.ifEmpty { "♪" }, 15f)
                v.setPadding(0, dp(6), 0, dp(6))
                body.addView(v)
                lineViews += v
                lineTimes += item.startTime
            }

            // WordByWord: cada grupo interno (palabra) se aplana a una línea.
            wordByWord != null -> wordByWord.list.forEach { words ->
                val v = tv(words.joinToString(" ") { it.text }.ifEmpty { "♪" }, 15f)
                v.setPadding(0, dp(6), 0, dp(6))
                body.addView(v)
                lineViews += v
                lineTimes += words.firstOrNull()?.startTime ?: 0L
            }

            plain != null -> plain.text.split('\n').forEach { line ->
                val v = tv(line.ifEmpty { " " }, 15f)
                v.setPadding(0, dp(4), 0, dp(4))
                body.addView(v)
            }
        }

        val maxHeight = (resources.displayMetrics.heightPixels * 0.55f).toInt()
            .coerceAtLeast(dp(160))
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(body)
        }
        scroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, maxHeight
        )
        content.addView(scroll)

        // Resaltado en vivo si la letra es de la canción que suena.
        if (lineTimes.isNotEmpty() && player.nowPlaying.value?.id == track.id) {
            val handler = Handler(Looper.getMainLooper())
            var lastIndex = -1
            val tick = object : Runnable {
                override fun run() {
                    val pos = player.position()
                    var idx = -1
                    for (i in lineTimes.indices) {
                        if (lineTimes[i] <= pos) idx = i else break
                    }
                    if (idx != lastIndex) {
                        lineViews.forEachIndexed { i, v ->
                            val active = i == idx
                            v.setTextColor(if (active) MusicTheme.ACCENT else MusicTheme.TEXT)
                            v.setTypeface(
                                android.graphics.Typeface.DEFAULT,
                                if (active) android.graphics.Typeface.BOLD
                                else android.graphics.Typeface.NORMAL
                            )
                        }
                        if (idx in lineViews.indices) {
                            val target = (lineViews[idx].top - scroll.height / 3).coerceAtLeast(0)
                            scroll.post { runCatching { scroll.smoothScrollTo(0, target) } }
                        }
                        lastIndex = idx
                    }
                    handler.postDelayed(this, 400)
                }
            }
            val dialog = AlertDialog.Builder(this).setView(content)
                .withLyricsActions(track, lyrics)
                .setPositiveButton("Cerrar", null)
                .create()
            dialog.setOnDismissListener { handler.removeCallbacks(tick) }
            dialog.show()
            handler.post(tick)
            return
        }

        AlertDialog.Builder(this).setView(content)
            .withLyricsActions(track, lyrics)
            .setPositiveButton("Cerrar", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Letras: texto plano, copiar y compartir
    // ------------------------------------------------------------------

    private fun lyricsPlainText(lyrics: Lyrics): String = when (val l = lyrics.lyrics) {
        is Lyrics.Simple -> l.text
        is Lyrics.Timed -> l.list.joinToString("\n") { it.text }
        is Lyrics.WordByWord -> l.list.joinToString("\n") { line -> line.joinToString(" ") { it.text } }
        null -> ""
    }.trim()

    private fun AlertDialog.Builder.withLyricsActions(track: Track, lyrics: Lyrics): AlertDialog.Builder {
        val text = lyricsPlainText(lyrics)
        if (text.isBlank()) return this
        val artists = track.artists.joinToString(", ") { it.name }
        val heading = if (artists.isBlank()) track.title else "${track.title} — $artists"
        setNeutralButton("Copiar") { _, _ -> copyLyrics(text) }
        setNegativeButton("Compartir") { _, _ -> shareLyrics(heading, "$heading\n\n$text") }
        return this
    }

    private fun copyLyrics(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Letras", text))
        toast("Letras copiadas")
    }

    private fun shareLyrics(subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(send, "Compartir letras")) }
            .onFailure { toast("No hay apps para compartir") }
    }
}