package com.codehub.app.music

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.codehub.app.music.MusicClient.getAs
import com.codehub.app.music.MusicMedia.EXTRA_EXTENSION_ID
import com.codehub.app.music.MusicMedia.EXTRA_SERVER_INDEX
import com.codehub.app.music.MusicMedia.EXTRA_SOURCE_INDEX
import com.codehub.app.music.MusicMedia.EXTRA_STATE
import com.codehub.app.music.MusicMedia.decodeTrack
import com.codehub.app.music.MusicMedia.encodeTrack
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.WeakHashMap

/**
 * MediaSource de CodeHub que reproduce un track Echo: carga el server/source
 * real con la extensión activa, construye un MediaItem con clave `codehub://`
 * y resuelve cada lectura HTTP mediante [StreamableResolver] (patrón
 * `StreamableMediaSource` + `StreamableLoader` del ecosistema Echo).
 */
@OptIn(UnstableApi::class)
class MusicMediaSource(
    private var mediaItem: MediaItem,
    private val player: MusicPlayer,
    private val scope: CoroutineScope,
    private val factories: Factories,
    qualityPref: String?,
) : CompositeMediaSource<Nothing>() {

    private val qualityPref: String? = qualityPref
    private var error: Throwable? = null

    override fun maybeThrowSourceInfoRefreshError() {
        error?.let { throw IOException(it) }
        super.maybeThrowSourceInfoRefreshError()
    }

    @UnstableApi
    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        val handler = Util.createHandlerForCurrentLooper()
        scope.launch {
            var new = mediaItem
            val serverResult = runCatching { MusicLoader.load(player, mediaItem) }
                .getOrElse {
                    error = it
                    return@launch
                }
            val trackId = mediaItem.decodeTrack()?.id ?: mediaItem.mediaId ?: ""
            player.servers[trackId] = serverResult
            val server = serverResult.getOrNull()
            val sources = server?.sources
            val actual = when (sources?.size) {
                0, null -> factories.create(new, -1, null)
                1 -> {
                    val source = sources.first()
                    factories.create(new, 0, source)
                }

                else -> {
                    if (server.merged) {
                        MergingMediaSource(
                            *sources.mapIndexed { index, source ->
                                factories.create(new, index, source)
                            }.toTypedArray()
                        )
                    } else {
                        val requested = mediaItem.sourceIndex()
                        val source = sources.getOrNull(requested)
                            ?: MusicQuality.select(sources, qualityPref)
                        val newIndex = sources.indexOf(source)
                        new = buildSourceItem(new, newIndex)
                        factories.create(new, newIndex, source)
                    }
                }
            }
            mediaItem = new
            handler.post {
                runCatching {
                    prepareChildSource(null, actual)
                }
            }
        }
    }

    @UnstableApi
    override fun onChildSourceInfoRefreshed(
        childSourceId: Nothing?,
        mediaSource: MediaSource,
        newTimeline: Timeline,
    ) {
        refreshSourceInfo(newTimeline)
    }

    override fun getMediaItem(): MediaItem = mediaItem

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod {
        val source = actualSource as MediaSource
        return source.createPeriod(id, allocator, startPositionUs)
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        val source = actualSource as MediaSource
        source.releasePeriod(mediaPeriod)
    }

    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean {
        if (mediaItem.mediaMetadata.extras?.getInt(EXTRA_SERVER_INDEX, -1) !=
            this.mediaItem.serverIndex()
        ) return false
        if (mediaItem.mediaMetadata.extras?.getInt(EXTRA_SOURCE_INDEX, -1) !=
            this.mediaItem.sourceIndex()
        ) return false
        return actualSource?.canUpdateMediaItem(mediaItem) ?: false
    }

    override fun updateMediaItem(mediaItem: MediaItem) {
        this.mediaItem = mediaItem
        actualSource?.updateMediaItem(mediaItem)
    }

    private var actualSource: MediaSource? = null

    /** Fabrica tipos de [MediaSource] con la misma fuente de datos resuelta. */
    data class Factories(
        val dash: Lazy<MediaSource.Factory>,
        val hls: Lazy<MediaSource.Factory>,
        val default: Lazy<MediaSource.Factory>,
    ) {

        @OptIn(UnstableApi::class)
        fun create(mediaItem: MediaItem, index: Int, source: Streamable.Source?): MediaSource {
            val type = (source as? Streamable.Source.Http)?.type
            val factory = when (type) {
                Streamable.SourceType.DASH -> dash
                Streamable.SourceType.HLS -> hls
                Streamable.SourceType.Progressive, null -> default
            }
            return factory.value.createMediaSource(buildKeyed(mediaItem, index, source))
        }
    }

    @OptIn(UnstableApi::class)
    class Factory(
        private val player: MusicPlayer,
        private val scope: CoroutineScope,
        private val globalPrefs: android.content.SharedPreferences,
    ) : MediaSource.Factory {

        private val qualityPref: String? =
            globalPrefs.getString("stream_quality", "highest")

        private val servers: WeakHashMap<String, Result<Streamable.Media.Server>> =
            player.servers

        private val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
        private val dataSource = ResolvingDataSource.Factory(
            dataSourceFactory,
            StreamableResolver(servers)
        )
        private val factories = createFactories(dataSource)

        private var drmSessionManagerProvider: DrmSessionManagerProvider? = null
        private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy? = null

        private fun createFactories(
            dataSource: ResolvingDataSource.Factory,
        ) = Factories(
            dash = lazily { DashMediaSource.Factory(dataSource) },
            hls = lazily { HlsMediaSource.Factory(dataSource) },
            default = lazily { DefaultMediaSourceFactory(dataSource) }
        )

        private fun lazily(factory: () -> MediaSource.Factory) = lazy {
            factory().apply {
                drmSessionManagerProvider?.let { setDrmSessionManagerProvider(it) }
                loadErrorHandlingPolicy?.let { setLoadErrorHandlingPolicy(it) }
            }
        }

        @OptIn(UnstableApi::class)
        override fun getSupportedTypes() = intArrayOf(
            C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_DASH
        )

        override fun setDrmSessionManagerProvider(
            drmSessionManagerProvider: DrmSessionManagerProvider,
        ): MediaSource.Factory {
            this.drmSessionManagerProvider = drmSessionManagerProvider
            return this
        }

        override fun setLoadErrorHandlingPolicy(
            loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
        ): MediaSource.Factory {
            this.loadErrorHandlingPolicy = loadErrorHandlingPolicy
            return this
        }

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            return MusicMediaSource(
                mediaItem, player, scope, factories, qualityPref
            )
        }
    }

    companion object {

        /** MediaItem con la clave `codehub://` (uri del source) + DRM. */
        private fun buildKeyed(
            mediaItem: MediaItem,
            index: Int,
            source: Streamable.Source?,
        ): MediaItem {
            val trackId = mediaItem.decodeTrack()?.id ?: ""
            val extensionId =
                mediaItem.mediaMetadata.extras?.getString(EXTRA_EXTENSION_ID) ?: ""
            val item = mediaItem.buildUpon()
                .setUri(Uri.parse(MusicMedia.key(trackId, index, extensionId)))
            val decryption = (source as? Streamable.Source.Http)?.decryption
            if (decryption is Streamable.Decryption.Widevine) {
                val license = decryption.license
                val config = MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(license.url)
                    .setMultiSession(decryption.isMultiSession)
                    .setLicenseRequestHeaders(license.headers)
                    .build()
                item.setDrmConfiguration(config)
            }
            return item.build()
        }

        private fun buildSourceItem(mediaItem: MediaItem, sourceIndex: Int): MediaItem {
            val bundle = mediaItem.mediaMetadata.extras ?: Bundle()
            val newBundle = Bundle().apply {
                putAll(bundle)
                putInt(EXTRA_SOURCE_INDEX, sourceIndex)
            }
            return mediaItem.buildUpon().setMediaMetadata(
                mediaItem.mediaMetadata.buildUpon().setExtras(newBundle).build()
            ).build()
        }
    }
}

/**
 * Resuelve un DataSpec de media3 con clave `codehub://` al URL real del
 * source (URL + headers del request). Port de `StreamableResolver`.
 */
@OptIn(UnstableApi::class)
class StreamableResolver(
    private val servers: WeakHashMap<String, Result<Streamable.Media.Server>>,
) : ResolvingDataSource.Resolver {

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val key = MusicMedia.decodeKey(dataSpec.uri.toString()) ?: return dataSpec
        val server = servers[key.trackId]?.getOrNull() ?: return dataSpec
        val source = server.sources.getOrNull(key.sourceIndex)
            ?: server.sources.maxByOrNull { it.quality }
            ?: return dataSpec
        return dataSpec.buildUpon()
            .setUri(Uri.parse(source.url))
            .setHttpRequestHeaders(source.requestHeaders)
            .setCustomData(source)
            .build()
    }
}

/**
 * Carga del MediaItem al server real mediante la extensión activa.
 * Port de `StreamableLoader` (sin descargas ni cache v1).
 */
object MusicLoader {

    suspend fun load(
        player: MusicPlayer,
        mediaItem: MediaItem,
    ): Result<Streamable.Media.Server> = withContext(Dispatchers.IO) {
        runCatching {
            val extension = player.extension ?: error("Sin extensión activa")
            val unloaded = mediaItem.decodeTrack() ?: error("Track inválido")
            playableLabel(unloaded)?.let { throw Exception(it) }
            val trackClient = extension.getAs<TrackClient> { this }.getOrThrow()
            val track = trackClient.loadTrack(unloaded, false)
            val servers = track.servers
            val serverIndex = mediaItem.serverIndex()
            val streamable = servers.getOrNull(serverIndex)
                ?: servers.maxByOrNull { it.quality }
                ?: throw Exception("Server no disponible")
            val media = trackClient.loadStreamableMedia(streamable, false)
            media as? Streamable.Media.Server
                ?: throw Exception("Formato de streaming no soportado")
        }
    }

    private fun playableLabel(track: Track): String? = when (track.playable) {
        Track.Playable.Yes -> null
        Track.Playable.RegionLocked -> "Track no disponible en tu región"
        Track.Playable.Unreleased -> "Track aún no publicado"
        is Track.Playable.No -> track.playable.reason.ifBlank { "Track no reproducible" }
    }
}

/** Selección de calidad del source según la preferencia global. */
object MusicQuality {

    fun select(
        sources: List<Streamable.Source>,
        pref: String?,
    ): Streamable.Source {
        if (sources.isEmpty()) error("Sin fuentes")
        return when (pref) {
            "lowest" -> sources.minByOrNull { it.quality }
            "medium" -> sources.sortedBy { it.quality }.let { it[it.size / 2] }
            else -> sources.maxByOrNull { it.quality }
        } ?: sources.first()
    }
}

/** Acceso a campos comunes de Streamable.Source para el resolver. */
private val Streamable.Source.url: String
    get() = when (this) {
        is Streamable.Source.Http -> request.url
        is Streamable.Source.Raw -> id
    }

private val Streamable.Source.requestHeaders: Map<String, String>
    get() = when (this) {
        is Streamable.Source.Http -> request.headers
        is Streamable.Source.Raw -> emptyMap()
    }