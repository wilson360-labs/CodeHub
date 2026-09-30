package com.codehub.app.music

import android.content.Context
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gestor de descargas offline del player de CodeHub (port del sistema de
 * descargas de echo-nightly SIN Room ni WorkManager):
 *
 *  - Cada track se guarda como audio crudo en
 *    `filesDir/music_downloads/<extensionId>/<trackId>.dat` (+ portada JPG).
 *  - El índice vive en `filesDir/music_downloads/index.json` (serializable,
 *    sin base de datos). La fuente local se resuelve en lectura: si el
 *    [MusicMediaSource] encuentra una descarga, reproduce el archivo y no
 *    contacta la API de la extensión.
 *  - `remove()` borra archivo + entrada; `clearAll()` limpia todo.
 */
class MusicDownloads private constructor(context: Context) {

    private val ctx: Context = context.applicationContext
    private val root: File = File(ctx.filesDir, "music_downloads").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Serializable
    data class Entry(
        val trackId: String,
        val extensionId: String,
        val trackJson: String,
        val filePath: String,
        val coverPath: String? = null,
        val size: Long = 0L,
        val timestamp: Long = 0L,
    ) {
        fun track(): Track? = runCatching {
            MusicMedia.json.decodeFromString(Track.serializer(), trackJson)
        }.getOrNull()
    }

    @Serializable
    data class Index(
        val version: Int = 1,
        val entries: MutableList<Entry> = mutableListOf(),
    )

    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Progreso en % por trackId (0..100; -1 = fallo). */
    val progress: StateFlow<Map<String, Int>> = _progress

    private val _entries = MutableStateFlow(loadIndex().entries)
    val entries: StateFlow<List<Entry>> = _entries

    private val _failures = MutableSharedFlow<String>()
    val failures: MutableSharedFlow<String> = _failures

    fun lookup(trackId: String): Entry? =
        _entries.value.firstOrNull { it.trackId == trackId }

    /** Encola la descarga del mejor server/source del track (SIEMPRE vía la
     *  API real de la extensión, igual que al reproducir). */
    fun enqueue(track: Track, extension: MusicExtension) {
        val trackId = track.id
        if (lookup(trackId) != null) return
        scope.launch {
            try {
                report(trackId, 1)
                val trackClient = extension.getAs<TrackClient, TrackClient> { this }.getOrThrow()
                val loaded = trackClient.loadTrack(track, false)
                val streamable = loaded.servers.maxByOrNull { it.quality }
                    ?: throw Exception("Server no disponible")
                val media = trackClient.loadStreamableMedia(streamable, false)
                val server = media as? Streamable.Media.Server
                    ?: throw Exception("Formato no soportado para descarga")
                val source = server.sources.maxByOrNull { it.quality }
                    ?: throw Exception("Sin fuentes")
                download(track, extension.id, source)
            } catch (e: Exception) {
                _failures.tryEmit("Descarga fallida: ${e.message ?: "error"}")
                report(trackId, -1)
            }
        }
    }

    /** Descarga el [source]; lo guarda y actualiza el índice. */
    private suspend fun download(track: Track, extensionId: String, source: Streamable.Source) {
        val trackId = track.id
        val dir = File(root, extensionId).apply { mkdirs() }
        val target = File(dir, "$trackId.dat")
        val tmp = File(dir, "$trackId.part")

        tmp.outputStream().use { out ->
            val conn = URL(source.url()).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 40_000
            source.headers().forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("User-Agent", "CodeHub/1.0 (music)")
            if (conn.responseCode !in 200..299) {
                throw Exception("HTTP ${conn.responseCode}")
            }
            val length = conn.contentLengthLong
            val input = conn.inputStream.buffered()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                total += read
                if (length > 0) {
                    report(trackId, ((total * 100) / length).toInt().coerceIn(0, 99))
                }
            }
        }
        if (!tmp.renameTo(target) && !target.exists()) {
            throw Exception("No se pudo guardar la descarga")
        }

        val cover = runCatching {
            track.cover?.let { fetchCover(trackId, extensionId, it) }
        }.getOrNull()

        mutateIndex {
            it.entries.removeAll { e -> e.trackId == trackId }
            it.entries.add(
                Entry(
                    trackId = trackId,
                    extensionId = extensionId,
                    trackJson = MusicMedia.json.encodeToString(Track.serializer(), track),
                    filePath = target.absolutePath,
                    coverPath = cover,
                    size = target.length(),
                    timestamp = System.currentTimeMillis(),
                )
            )
        }
        report(trackId, 100)
    }

    private fun fetchCover(trackId: String, extensionId: String, url: String): String? {
        val file = File(File(root, extensionId), "$trackId.jpg")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "CodeHub/1.0 (music)")
        if (conn.responseCode !in 200..299) return null
        conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file.absolutePath
    }

    fun remove(trackId: String) {
        val entry = lookup(trackId) ?: return
        mutateIndex { it.entries.removeAll { e -> e.trackId == trackId } }
        runCatching { File(entry.filePath).delete() }
        entry.coverPath?.let { runCatching { File(it).delete() } }
        runCatching { File(File(root, entry.extensionId), "$trackId.part").delete() }
    }

    /** Elimina la carpeta completa de descargas (offline). */
    fun clearAll() {
        mutateIndex { it.entries.clear() }
        runCatching { root.deleteRecursively(); root.mkdirs() }
    }

    private fun report(trackId: String, percent: Int) {
        _progress.value = _progress.value + (trackId to percent)
    }

    private fun mutateIndex(block: (Index) -> Unit) {
        val index = loadIndex()
        block(index)
        saveIndex(index)
        _entries.value = index.entries.toList()
    }

    private fun loadIndex(): Index {
        val file = File(root, "index.json")
        if (!file.exists()) return Index()
        return runCatching {
            MusicMedia.json.decodeFromString(Index.serializer(), file.readText())
        }.getOrElse { Index() }
    }

    private fun saveIndex(index: Index) {
        runCatching {
            File(root, "index.json").writeText(
                MusicMedia.json.encodeToString(Index.serializer(), index)
            )
        }
    }

    companion object {
        @Volatile
        private var instance: MusicDownloads? = null

        /** Instancia única por proceso (mismo ciclo que [MusicPlayer.shared]). */
        fun shared(context: Context): MusicDownloads {
            instance?.let { return it }
            synchronized(this) {
                return instance ?: MusicDownloads(context.applicationContext).also { instance = it }
            }
        }
    }
}

/** Lectura url/headers de un source, replicando los accessors de MusicMediaSource. */
private fun Streamable.Source.url(): String = when (this) {
    is Streamable.Source.Http -> request.url
    is Streamable.Source.Raw -> id
}

private fun Streamable.Source.headers(): Map<String, String> = when (this) {
    is Streamable.Source.Http -> request.headers
    is Streamable.Source.Raw -> emptyMap()
}