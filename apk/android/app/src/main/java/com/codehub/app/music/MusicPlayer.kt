package com.codehub.app.music

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.codehub.app.music.MusicMedia.decodeTrack
import com.codehub.app.music.MusicMedia.extensionIdOf
import com.codehub.app.music.MusicPlayerDuration.toClockTime
import dev.brahmkshatriya.echo.common.MusicExtension
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.WeakHashMap

/**
 * Codificación de la clave de reproducción de un [MediaItem]:
 * la URI real del source se sustituye por `codehub://<base64url>` con
 * `{t:trackId, e:extensionId, s:sourceIndex}`, y el [StreamableResolver]
 * lo resuelve al URL real + headers en tiempo de lectura (igual que
 * `MediaItemUtils.Key` en el ecosistema Echo).
 */
object MusicMedia {

    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    data class Key(val trackId: String, val sourceIndex: Int, val extensionId: String)

    fun key(trackId: String, sourceIndex: Int, extensionId: String): String {
        val body = buildString {
            append("{\"t\":\""); append(trackId.escapeJson())
            append("\",\"e\":\""); append(extensionId.escapeJson())
            append("\",\"s\":").append(sourceIndex).append("}")
        }
        return "codehub://" + Base64.encodeToString(
            body.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP
        )
    }

    fun decodeKey(uri: String): Key? = runCatching {
        val b64 = uri.substringAfter("codehub://", "")
        if (b64.isEmpty()) return null
        val json = String(Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        val obj = MusicMedia.json.parseToJsonElement(json).jsonObject
        Key(
            trackId = obj["t"]!!.toString().trim('"'),
            sourceIndex = obj["s"]!!.toString().toInt(),
            extensionId = obj["e"]!!.toString().trim('"')
        )
    }?.getOrNull()

    private fun String.escapeJson() = replace("\\", "\\\\").replace("\"", "\\\"")

    const val EXTRA_STATE = "state"
    const val EXTRA_EXTENSION_ID = "extensionId"
    const val EXTRA_SERVER_INDEX = "serverIndex"
    const val EXTRA_SOURCE_INDEX = "sourceIndex"

    fun encodeTrack(track: Track): String = json.encodeToString(Track.serializer(), track)

    fun Bundle.trackOrNull(): Track? = getString(EXTRA_STATE)?.let {
        runCatching { json.decodeFromString(Track.serializer(), it) }.getOrNull()
    }

    fun MediaItem.decodeTrack(): Track? = mediaMetadata.extras?.trackOrNull()

    fun MediaItem.extensionIdOf(): String? = mediaMetadata.extras?.getString(EXTRA_EXTENSION_ID)

    fun MediaItem.serverIndex(): Int = mediaMetadata.extras?.getInt(EXTRA_SERVER_INDEX, -1) ?: -1

    fun MediaItem.sourceIndex(): Int = mediaMetadata.extras?.getInt(EXTRA_SOURCE_INDEX, -1) ?: -1
}

/**
 * Conversión de milisegundos a `mm:ss` / `h:mm:ss` (formato Echo).
 */
object MusicPlayerDuration {
    fun Long.toClockTime(): String {
        val seconds = this / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        return buildString {
            if (hours > 0) {
                append(if (hours < 10) "0" else "").append(hours).append(":")
            }
            append(if ((minutes % 60) < 10) "0" else "").append(minutes % 60).append(":")
            append(if ((seconds % 60) < 10) "0" else "").append(seconds % 60)
        }
    }
}

/**
 * Jugador de música del reproductor slide de CodeHub.
 *
 * Usa ExoPlayer/media3 con una [MediaSource] propia (MusicMediaSource)
 * que resuelve cada track del ecosistema Echo (HLS/DASH/progresivo) usando
 * la API de la extensión activa. Sin servicio foreground en esta versión:
 * la reproducción vive en la Activity.
 */
@OptIn(UnstableApi::class)
class MusicPlayer(
    private val context: Context,
    scope: CoroutineScope,
) {

    /** Servers cargados por trackId (resueltos por [MusicMediaSource]). */
    val servers = WeakHashMap<String, Result<Streamable.Media.Server>>()

    /** Extensión activa de la cola actual. */
    var extension: MusicExtension? = null

    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(MusicMediaSource.Factory(this, scope))
        .build()

    private val _nowPlaying = MutableStateFlow<Track?>(null)
    val nowPlaying: StateFlow<Track?> = _nowPlaying

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue

    val failures = MutableSharedFlow<String>()

    init {
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _nowPlaying.value = mediaItem?.decodeTrack()
            }

            override fun onPlayerError(error: PlaybackException) {
                val track = exo.currentMediaItem?.decodeTrack()
                failures.tryEmit(
                    if (track == null) (error.message ?: "Error de reproducción")
                    else error.message ?: "Error al reproducir ${track.title}"
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _isPlaying.value = (exo.playWhenReady && exo.playbackState == Player.STATE_READY)
                }
            }
        })
    }

    // ------------------------------------------------------------------
    // Cola
    // ------------------------------------------------------------------

    fun play(
        extension: MusicExtension,
        tracks: List<Track>,
        startIndex: Int,
        serverIndex: Int = -1,
        sourceIndex: Int = -1,
    ) {
        if (tracks.isEmpty()) return
        this.extension = extension
        val items = tracks.map { buildItem(it, serverIndex, sourceIndex) }
        _queue.value = tracks
        val index = startIndex.coerceIn(0, items.lastIndex)
        exo.setMediaItems(items, index, 0L)
        exo.prepare()
        exo.play()
    }

    fun playTrack(
        extension: MusicExtension,
        track: Track,
        contextItem: Track? = null,
    ) {
        val queue = if (contextItem != null && track.id != contextItem.id)
            mutableListOf(contextItem, track) else mutableListOf(track)
        play(extension, queue, if (contextItem != null && track.id != contextItem.id) 1 else 0)
    }

    fun toggle() {
        if (exo.playbackState == Player.STATE_IDLE) return
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    fun next() {
        if (exo.hasNextMediaItem()) exo.seekToNextMediaItem()
        else exo.seekTo(0L)
    }

    fun prev() {
        if (exo.currentPosition > 3000 || !exo.hasPreviousMediaItem()) exo.seekTo(0L)
        else exo.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        exo.seekTo(positionMs)
    }

    /** Re-encola el track actual con otra fuente (calidad) del mismo server. */
    fun selectSource(track: Track, sourceIndex: Int) {
        val ext = extension ?: return
        val index = exo.currentMediaItemIndex.coerceAtLeast(0)
        exo.setMediaItem(
            buildItem(track, serverIndex = -1, sourceIndex = sourceIndex),
            index
        )
        exo.prepare()
        exo.play()
    }

    fun position(): Long = exo.currentPosition

    fun duration(): Long = exo.duration

    fun release() {
        exo.release()
    }

    /** Construye un MediaItem con extras (track serializado + índices). */
    fun buildItem(
        track: Track,
        serverIndex: Int,
        sourceIndex: Int,
    ): MediaItem {
        val extensionId = extension?.id ?: ""
        val bundle = Bundle().apply {
            putString(MusicMedia.EXTRA_STATE, MusicMedia.encodeTrack(track))
            putString(MusicMedia.EXTRA_EXTENSION_ID, extensionId)
            putInt(MusicMedia.EXTRA_SERVER_INDEX, serverIndex)
            putInt(MusicMedia.EXTRA_SOURCE_INDEX, sourceIndex)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artists.joinToString(", ") { it.name })
            .setAlbumTitle(track.album?.title)
            .setIsPlayable(track.playable == Track.Playable.Yes)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(bundle)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(Uri.parse(track.id))
            .setMediaMetadata(metadata)
            .build()
    }
}