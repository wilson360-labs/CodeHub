package com.codehub.app.music.ui

import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.pagedDataOfFirst
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Radio
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track

/** Convertidor de feeds Echo a filas del adaptador del reproductor. */
object MusicFeed {

    suspend fun shelves(
        extension: MusicExtension,
        feed: Feed<Shelf>,
        tab: Tab? = null,
        onMore: (Feed<Shelf>) -> Unit = {},
    ): List<Row> = runCatching {
        val data = feed.getPagedData(tab)
        data.pagedData.loadAll().flatMap { shelfToRows(it, onMore) }
    }.getOrElse { listOf(Row.Info(it.message ?: "Error al cargar el feed")) }

    suspend fun tracks(
        extension: MusicExtension,
        feed: Feed<Track>,
        title: String? = null,
    ): List<Row> = runCatching {
        val rows = mutableListOf<Row>()
        if (title != null) rows += Row.Header(title, null)
        val all = feed.pagedDataOfFirst().loadAll()
        all.onEachIndexed { index, track ->
            rows += Row.TrackRow(index, track)
        }
        rows
    }.getOrElse { listOf(Row.Info(it.message ?: "No se pudieron cargar los tracks")) }

    fun trackRows(tracks: List<Track>, start: Int = 0): List<Row> =
        tracks.mapIndexed { index, track -> Row.TrackRow(start + index, track) }

    fun uiItem(media: EchoMediaItem): UiItem = UiItem(
        media = media,
        subtitle = media.subtitleWithE?.takeIf { it.isNotBlank() }
            ?: listsSubtitle(media as? EchoMediaItem.Lists),
        image = media.cover ?: media.background,
    )

    fun shelfToRows(
        shelf: Shelf,
        onMore: (Feed<Shelf>) -> Unit = {},
    ): List<Row> = when (shelf) {
        is Shelf.Item -> listOf(
            Row.Header(shelf.media.title, null),
            Row.Card(uiItem(shelf.media))
        )

        is Shelf.Category -> listOf(Row.Header(shelf.title, shelf.subtitle))

        is Shelf.Lists.Items -> {
            val head = shelfHeader(shelf.title, shelf.subtitle, shelf.more, onMore)
            val body = if (shelf.type == Shelf.Lists.Type.Grid)
                shelf.list.chunked(2).map { Row.Cards(it.map(::uiItem)) }
            else shelf.list.map { Row.Card(uiItem(it)) }
            head + body
        }

        is Shelf.Lists.Tracks -> {
            val head = shelfHeader(shelf.title, shelf.subtitle, shelf.more, onMore)
            val rows = shelf.list.mapIndexed { index, track -> Row.TrackRow(index, track) }
            head + rows
        }

        is Shelf.Lists.Categories -> {
            val head = shelfHeader(shelf.title, shelf.subtitle, shelf.more, onMore)
            head + shelf.list.map { Row.CardCat(it) }
        }
    }

    private fun shelfHeader(
        title: String,
        subtitle: String?,
        more: Feed<Shelf>?,
        onMore: (Feed<Shelf>) -> Unit,
    ): List<Row> {
        val header = Row.Header(title, subtitle)
        val moreRow = if (more != null) listOf<Row>(
            Row.Header("Ver todo ›", null) { onMore(more) }
        ) else emptyList()
        return listOf(header) + moreRow
    }

    private fun listsSubtitle(lists: EchoMediaItem.Lists?): String? {
        if (lists == null) return null
        val parts = mutableListOf<String>()
        when (lists) {
            is Album -> parts += typeText(lists.type)
            is Playlist -> parts += if (lists.isPrivate) "Lista privada" else "Lista"
            is Radio -> parts += "Radio"
            else -> Unit
        }
        lists.trackCount?.let {
            parts += if (it == 1L) "1 canción" else "$it canciones"
        }
        lists.duration?.let { parts += clock(it) }
        return parts.joinToString(" • ")
    }

    private fun typeText(type: Album.Type?): String = when (type) {
        null, Album.Type.LP -> "Álbum"
        Album.Type.PreRelease -> "Pre-lanzamiento"
        Album.Type.Single -> "Sencillo"
        Album.Type.EP -> "EP"
        Album.Type.Compilation -> "Recopilación"
        Album.Type.Show -> "Episodios"
        Album.Type.Book -> "Capítulos"
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        val m = s / 60
        val h = m / 60
        return if (h > 0) "%d:%02d:%02d".format(h, m % 60, s % 60)
        else "%d:%02d".format(m, s % 60)
    }
}