package com.codehub.app.music

import android.content.Context
import android.content.SharedPreferences
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Playlists locales del reproductor de CodeHub (port ligero del sistema de
 * playlists de echo-nightly, SIN Room): se persisten como un documento JSON en
 * `SharedPreferences("music_playlists")`.
 *
 * Cada entrada guarda el [Track] serializado + su `extensionId` para poder
 * reproducirla sin depender de la pantalla que la creó. Las listas locales se
 * exponen a la UI como un [Playlist] de Echo con id `local:<id>` (ver [echo]),
 * de modo que el feed las pinta con las mismas cards que las remotas.
 *
 * La lista reservada [LIKED_ID] ("Me gusta") se mantiene sincronizada con el
 * corazón del reproductor ([com.codehub.app.music.ui.PlayerSlide.toggleLike]).
 */
class MusicPlaylists private constructor(context: Context) {

    /** Pista guardada dentro de una lista local. */
    data class Item(
        val trackId: String,
        val extensionId: String,
        val trackJson: String,
    ) {
        fun track(): Track? = runCatching {
            MusicMedia.json.decodeFromString(Track.serializer(), trackJson)
        }.getOrNull()

        fun toJson(): JSONObject = JSONObject().apply {
            put("trackId", trackId)
            put("extensionId", extensionId)
            put("trackJson", trackJson)
        }

        companion object {
            fun fromJson(o: JSONObject): Item = Item(
                trackId = o.getString("trackId"),
                extensionId = o.optString("extensionId", ""),
                trackJson = o.getString("trackJson"),
            )
        }
    }

    /** Una lista local: metadatos + pistas guardadas. */
    data class Entry(
        val id: String,
        val title: String,
        val description: String?,
        val createdAt: Long,
        val tracks: List<Item>,
    ) {
        /** Vista de la lista para el feed/cards (id marcado como local). */
        fun echo(): Playlist = Playlist(
            id = MusicPlaylists.LOCAL_PREFIX + id,
            title = title,
            isEditable = true,
            description = description,
            trackCount = tracks.size.toLong(),
        )
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("music_playlists", Context.MODE_PRIVATE)

    private val _playlists = MutableStateFlow(load())

    /** Lista observable de playlists locales. */
    val playlists: StateFlow<List<Entry>> = _playlists

    fun byId(id: String): Entry? = _playlists.value.firstOrNull { it.id == id }

    /** Crea una lista vacía y la devuelve. */
    fun create(title: String, description: String? = null): Entry {
        val entry = Entry(
            id = "p" + System.currentTimeMillis().toString(36) +
                    System.nanoTime().toString(36),
            title = title.trim().ifBlank { "Mi lista" },
            description = description,
            createdAt = System.currentTimeMillis(),
            tracks = emptyList(),
        )
        mutate { it.add(entry) }
        return entry
    }

    fun rename(id: String, title: String, description: String? = null) {
        mutate { list ->
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0) list[i] = list[i].copy(title = title, description = description)
        }
    }

    fun delete(id: String) {
        mutate { list -> list.removeAll { it.id == id } }
    }

    /** Añade [track] al final; devuelve false si ya estaba. */
    fun add(playlistId: String, track: Track, extensionId: String): Boolean {
        var added = false
        mutate { list ->
            val i = list.indexOfFirst { it.id == playlistId }
            if (i < 0) return@mutate
            val entry = list[i]
            if (entry.tracks.any { it.trackId == track.id }) return@mutate
            val item = Item(
                trackId = track.id,
                extensionId = extensionId,
                trackJson = MusicMedia.json.encodeToString(Track.serializer(), track),
            )
            list[i] = entry.copy(tracks = entry.tracks + item)
            added = true
        }
        return added
    }

    fun remove(playlistId: String, trackId: String) {
        mutate { list ->
            val i = list.indexOfFirst { it.id == playlistId }
            if (i < 0) return@mutate
            list[i] = list[i].copy(tracks = list[i].tracks.filterNot { it.trackId == trackId })
        }
    }

    /** Reordena una pista dentro de la lista. */
    fun move(playlistId: String, from: Int, to: Int) {
        mutate { list ->
            val i = list.indexOfFirst { it.id == playlistId }
            if (i < 0) return@mutate
            val tracks = list[i].tracks.toMutableList()
            if (from !in tracks.indices) return@mutate
            val target = to.coerceIn(0, tracks.lastIndex)
            if (target == from) return@mutate
            val item = tracks.removeAt(from)
            tracks.add(target, item)
            list[i] = list[i].copy(tracks = tracks)
        }
    }

    /** Pistas reproducibles de una lista (las que se pudieron deserializar). */
    fun tracks(entry: Entry): List<Track> = entry.tracks.mapNotNull { it.track() }

    /** Sincroniza la lista reservada "Me gusta" con el corazón del player. */
    fun setLiked(track: Track, liked: Boolean) {
        if (!liked) {
            remove(LIKED_ID, track.id)
            return
        }
        mutate { list ->
            val i = list.indexOfFirst { it.id == LIKED_ID }
            val existing = if (i >= 0) list[i] else Entry(
                id = LIKED_ID,
                title = "Me gusta",
                description = "Tus canciones favoritas",
                createdAt = System.currentTimeMillis(),
                tracks = emptyList(),
            )
            if (existing.tracks.any { it.trackId == track.id }) return@mutate
            val item = Item(track.id, "", MusicMedia.json.encodeToString(Track.serializer(), track))
            val updated = existing.copy(tracks = existing.tracks + item)
            if (i >= 0) list[i] = updated else list.add(updated)
        }
    }

    private fun mutate(block: (MutableList<Entry>) -> Unit) {
        val list = _playlists.value.toMutableList()
        block(list)
        _playlists.value = list
        save(list)
    }

    private fun load(): List<Entry> {
        val raw = prefs.getString("data", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val tracksArr = o.optJSONArray("tracks") ?: JSONArray()
                Entry(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    description = if (o.has("description"))
                        o.optString("description").ifEmpty { null } else null,
                    createdAt = o.optLong("createdAt"),
                    tracks = (0 until tracksArr.length())
                        .map { tracksArr.getJSONObject(it) }
                        .map(Item::fromJson),
                )
            }
        }.getOrElse { emptyList() }
    }

    private fun save(list: List<Entry>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { e ->
                val o = JSONObject().apply {
                    put("id", e.id)
                    put("title", e.title)
                    if (!e.description.isNullOrEmpty()) put("description", e.description)
                    put("createdAt", e.createdAt)
                    val t = JSONArray()
                    e.tracks.forEach { t.put(it.toJson()) }
                    put("tracks", t)
                }
                arr.put(o)
            }
            prefs.edit().putString("data", arr.toString()).apply()
        }
    }

    companion object {
        /** id reservado de la lista "Me gusta". */
        const val LIKED_ID = "liked"

        /** prefijo del id de una playlist local en el feed de Echo. */
        const val LOCAL_PREFIX = "local:"

        @Volatile
        private var instance: MusicPlaylists? = null

        /** Instancia única por proceso (mismo ciclo que MusicPlayer/MusicDownloads). */
        fun shared(context: Context): MusicPlaylists {
            instance?.let { return it }
            synchronized(this) {
                return instance ?: MusicPlaylists(context.applicationContext).also { instance = it }
            }
        }
    }
}
