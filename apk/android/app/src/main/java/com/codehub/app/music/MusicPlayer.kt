package com.codehub.app.music

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
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
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
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

    // ------------------------------------------------------------------
    // Portada (`codehub-art://`): el ImageHolder se serializa en la query
    // y lo resuelve [SessionArtworkLoader] para la notificación, la pantalla
    // de bloqueo y las salidas de sistema (espejo de `toUriWithJson` de
    // Echo + su `PlayerBitmapLoader`).
    // ------------------------------------------------------------------

    const val ART_SCHEME = "codehub-art"

    fun ImageHolder.toArtworkUri(): Uri {
        val jsonText = json.encodeToString(ImageHolder.serializer(), this)
        return Uri.parse("$ART_SCHEME://cover")
            .buildUpon()
            .appendQueryParameter("actual_data", jsonText)
            .build()
    }

    fun Uri.artworkHolder(): ImageHolder? = runCatching {
        if (scheme != ART_SCHEME) return@runCatching null
        val raw = getQueryParameter("actual_data") ?: return@runCatching null
        json.decodeFromString(ImageHolder.serializer(), raw)
    }.getOrNull()
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
 * la API de la extensión activa. La reproducción vive en [MusicPlaybackService]
 * (foreground) y reusa la misma instancia singleton del proceso.
 */
@OptIn(UnstableApi::class)
class MusicPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    /** Servers cargados por trackId (resueltos por [MusicMediaSource]). */
    val servers = WeakHashMap<String, Result<Streamable.Media.Server>>()

    /** Extensión activa de la cola actual. */
    var extension: MusicExtension? = null

    /** Descargas offline (null si el player no se ha vinculado a MusicDownloads). */
    var downloads: MusicDownloads? = null

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null

    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(MusicMediaSource.Factory(
            this,
            scope,
            context.getSharedPreferences("music_global", Context.MODE_PRIVATE)
        ))
        .setAudioAttributes(AudioAttributes.DEFAULT, true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _nowPlaying = MutableStateFlow<Track?>(null)
    val nowPlaying: StateFlow<Track?> = _nowPlaying

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle

    private val _repeat = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeat: StateFlow<Int> = _repeat

    val failures = MutableSharedFlow<String>()

    /** Avisos puntuales (p. ej. fin del temporizador de dormir) para la UI. */
    val events = MutableSharedFlow<String>()

    /** Velocidad de reproducción actual (0.5×–2.0×), persistida en prefs. */
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> get() = _speed

    // ------------------------------------------------------------------
    // Temporizador de dormir (vive en el player, no en el slide: sigue
    // contando aunque se cierre la UI, y se restaura tras reiniciar).
    // ------------------------------------------------------------------

    private val handler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences =
        context.getSharedPreferences("music_global", Context.MODE_PRIVATE)

    /** Instante (epoch ms) en que se apagará la reproducción; 0 = sin timer. */
    var sleepAt: Long = 0L
        private set

    private val sleepRunnable = Runnable {
        sleepAt = 0L
        prefs.edit().remove("sleep_at").apply()
        if (exo.isPlaying) exo.pause()
        scope.launch { events.emit("Temporizador: reproducción en pausa") }
    }

    /** Programa (durationMs > 0) o cancela (<= 0) el temporizador de dormir. */
    fun setSleepTimer(durationMs: Long) {
        handler.removeCallbacks(sleepRunnable)
        if (durationMs <= 0L) {
            sleepAt = 0L
            prefs.edit().remove("sleep_at").apply()
            return
        }
        sleepAt = System.currentTimeMillis() + durationMs
        prefs.edit().putLong("sleep_at", sleepAt).apply()
        handler.postDelayed(sleepRunnable, durationMs)
    }

    private fun restoreSleepTimer() {
        val at = prefs.getLong("sleep_at", 0L)
        if (at <= 0L) return
        val left = at - System.currentTimeMillis()
        if (left <= 0L) {
            sleepAt = 0L
            prefs.edit().remove("sleep_at").apply()
            return
        }
        sleepAt = at
        handler.postDelayed(sleepRunnable, left)
    }

    init {
        restoreSleepTimer()
        val savedSpeed = prefs.getFloat("speed", 1f)
        _speed.value = savedSpeed
        if (savedSpeed != 1f) runCatching { exo.setPlaybackSpeed(savedSpeed) }
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                MusicWidget.refresh(context)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                _nowPlaying.value = mediaItem?.decodeTrack()
                MusicWidget.refresh(context)
            }

            override fun onPlayerError(error: PlaybackException) {
                val track = exo.currentMediaItem?.decodeTrack()
                failures.tryEmit(
                    if (track == null) (error.message ?: "Error de reproducción")
                    else error.message ?: "Error al reproducir ${track.title}"
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _isBuffering.value = (playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_READY) {
                    _isPlaying.value = (exo.playWhenReady && exo.playbackState == Player.STATE_READY)
                }
            }
        })
    }

    val audioSessionId: Int
        get() = exo.audioSessionId

    fun toggleShuffle() {
        exo.shuffleModeEnabled = !exo.shuffleModeEnabled
        _shuffle.value = exo.shuffleModeEnabled
    }

    /** Cambia la velocidad de reproducción (0.5×–2.0×) y la recuerda. */
    fun setSpeed(value: Float) {
        val v = value.coerceIn(0.5f, 2.0f)
        _speed.value = v
        runCatching { exo.setPlaybackSpeed(v) }
        prefs.edit().putFloat("speed", v).apply()
    }

    fun cycleRepeat() {
        val next = when (exo.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        exo.repeatMode = next
        _repeat.value = next
    }

    /** Detiene la reproducción actual y deja la cola vacía (botón cerrar). */
    fun stop() {
        abandonFocus()
        exo.stop()
        exo.clearMediaItems()
        _isBuffering.value = false
        _queue.value = emptyList()
        _nowPlaying.value = null
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
        ensureService()
        requestFocus()
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
        if (exo.isPlaying) {
            exo.pause()
        } else {
            ensureService()
            requestFocus()
            exo.play()
        }
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

    /** Adelanta/atrasa la reproducción sin salir de la cola (±10s). */
    fun seekBy(deltaMs: Long) {
        val dur = exo.duration.coerceAtLeast(0L)
        val target = (exo.currentPosition + deltaMs).coerceIn(0L, dur)
        exo.seekTo(target)
    }

    /** Salta a la posición [index] de la cola (sin detener el play). */
    fun jumpTo(index: Int) {
        if (index < 0 || index >= exo.mediaItemCount) return
        exo.seekToDefaultPosition(index)
    }

    /** Quita un elemento de la cola; al quitar el actual pasa al siguiente. */
    fun removeFromQueue(index: Int) {
        if (index < 0 || index >= exo.mediaItemCount) return
        exo.removeMediaItem(index)
        rebuildQueue()
    }

    /** Añade [track] al final de la cola (o lo reproduce si no hay nada). */
    fun addToQueue(track: Track) = addAllToQueue(listOf(track))

    /** Añade [track] justo después del elemento actual (siguiente en sonar). */
    fun playNext(track: Track) = addAllToQueue(listOf(track), next = true)

    /**
     * Añade [tracks] a la cola. Con [next] = true se insertan justo después del
     * track actual; si la cola está vacía se comporta como [play].
     */
    fun addAllToQueue(tracks: List<Track>, next: Boolean = false) {
        if (tracks.isEmpty()) return
        val ext = extension ?: return
        if (exo.mediaItemCount == 0) {
            play(ext, tracks, 0)
            return
        }
        val items = tracks.map { buildItem(it, -1, -1) }
        val at = if (next) (exo.currentMediaItemIndex + 1).coerceIn(0, exo.mediaItemCount)
        else exo.mediaItemCount
        exo.addMediaItems(at, items)
        rebuildQueue()
        events.tryEmit(
            if (tracks.size == 1) "Añadido a la cola: ${tracks.first().title}"
            else "${tracks.size} canciones añadidas a la cola"
        )
    }

    /** Mueve un elemento de la cola de [from] a [to] (reordenar). */
    fun moveInQueue(from: Int, to: Int) {
        if (from == to) return
        if (from < 0 || from >= exo.mediaItemCount) return
        val target = to.coerceIn(0, exo.mediaItemCount - 1)
        if (target == from) return
        exo.moveMediaItem(from, target)
        rebuildQueue()
    }

    /** Reconstruye [_queue] a partir de la timeline real de ExoPlayer. */
    private fun rebuildQueue() {
        val list = ArrayList<Track>(exo.mediaItemCount)
        for (i in 0 until exo.mediaItemCount) {
            exo.getMediaItemAt(i).decodeTrack()?.let { list += it }
        }
        _queue.value = list
    }

    /** Vacía la cola por completo y detiene la reproducción. */
    fun clearQueue() = stop()

    /** Re-encola el track actual con otra fuente (calidad) del mismo server. */
    fun selectSource(track: Track, sourceIndex: Int) {
        val ext = extension ?: return
        val index = exo.currentMediaItemIndex.coerceAtLeast(0)
        ensureService()
        requestFocus()
        exo.setMediaItems(
            listOf(buildItem(track, serverIndex = -1, sourceIndex = sourceIndex)),
            index,
            0L
        )
        exo.prepare()
        exo.play()
    }

    fun position(): Long = exo.currentPosition

    fun duration(): Long = exo.duration

    // ------------------------------------------------------------------
    // Audio focus (port de AudioFocusListener de echo-nightly)
    // ------------------------------------------------------------------

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val listener = AudioManager.OnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        if (exo.isPlaying) exo.pause()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        runCatching { exo.volume = 0.15f }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        runCatching { exo.volume = 1f }
                        if (exo.playWhenReady && !exo.isPlaying) exo.play()
                    }
                }
            }
            focusListener = listener
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(listener)
                .build()
            focusRequest = req
            runCatching { audioManager.requestAudioFocus(req) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                audioManager.requestAudioFocus(
                    null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
                )
            }
        }
    }

    private fun abandonFocus() {
        focusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        focusRequest = null
        focusListener = null
    }

    private var serviceStarted = false

    /** Arranca [MusicPlaybackService] (foreground) la primera vez que se
     *  reproduce algo; así la notificación de medios solo existe al sonar. */
    fun ensureService() {
        if (serviceStarted) return
        serviceStarted = true
        runCatching {
            context.startForegroundService(Intent(context, MusicPlaybackService::class.java))
        }
    }

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
        val cover = track.cover ?: track.background
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artists.joinToString(", ") { it.name })
            .setAlbumTitle(track.album?.title)
            .setArtworkUri(cover?.let { with(MusicMedia) { it.toArtworkUri() } })
            .setIsPlayable(track.isPlayable == Track.Playable.Yes)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(bundle)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(Uri.parse(track.id))
            .setMediaMetadata(metadata)
            .build()
    }

    companion object {
        @Volatile
        private var sharedInstance: MusicPlayer? = null

        /** Instancia única por proceso; la mantiene [MusicPlaybackService]. */
        @Synchronized
        fun shared(context: Context): MusicPlayer {
            sharedInstance?.let { return it }
            val created = MusicPlayer(
                context.applicationContext,
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            )
            sharedInstance = created
            return created
        }

        /** Libera ExoPlayer y anula la singleton (solo el servicio lo llama). */
        @Synchronized
        fun releaseShared() {
            val player = sharedInstance ?: return
            player.abandonFocus()
            runCatching { player.exo.release() }
            sharedInstance = null
        }
    }
}