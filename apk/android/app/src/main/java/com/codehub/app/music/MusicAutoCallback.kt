package com.codehub.app.music

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.brahmkshatriya.echo.common.models.Track

/**
 * Callback de browse para Android Auto: expone la biblioteca de descargas
 * offline como árbol (offline-first, sin red). Al seleccionar un track, el
 * [MusicMediaSource] lo reproduce directamente del archivo local vía
 * `MusicPlayer.downloads`.
 */
@OptIn(UnstableApi::class)
class MusicAutoCallback(
    private val context: android.content.Context,
) : MediaLibrarySession.Callback {

    private companion object {
        const val ROOT_ID = "codehub_music_root"
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val root = MediaItem.Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Descargas")
                    .setSubtitle("Descargas sin conexión de CodeHub Music")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(root, params))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (parentId != ROOT_ID) {
            return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_NOT_SUPPORTED))
        }
        val entries = runCatching { MusicDownloads.shared(context).entries.value }
            .getOrElse { return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)) }

        val items = ImmutableList.Builder<MediaItem>()
        entries.forEach { entry ->
            val track = entry.track()
            if (track != null) items.add(childItem(track, entry.extensionId))
        }
        val list = items.build()
        if (list.isEmpty()) {
            return Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.of(), params)
            )
        }
        return Futures.immediateFuture(LibraryResult.ofItemList(list, params))
    }

    /** MediaItem reproducible por Auto (clave `codehub://` + extras del track). */
    private fun childItem(track: Track, extensionId: String): MediaItem {
        val bundle = Bundle().apply {
            putString(MusicMedia.EXTRA_STATE, MusicMedia.encodeTrack(track))
            putString(MusicMedia.EXTRA_EXTENSION_ID, extensionId)
            putInt(MusicMedia.EXTRA_SERVER_INDEX, -1)
            putInt(MusicMedia.EXTRA_SOURCE_INDEX, -1)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artists.joinToString(", ") { it.name })
            .setAlbumTitle(track.album?.title)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(bundle)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(Uri.parse(MusicMedia.key(track.id, 0, extensionId)))
            .setMediaMetadata(metadata)
            .build()
    }
}