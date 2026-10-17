package dev.brahmkshatriya.echo.extension.streaming

import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.RealNewPipeVideoFormatsEndpoint
import dev.brahmkshatriya.echo.extension.utils.RetryUtils
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit


class YouTubeStreamResolver(
    private val api: YoutubeiApi,
    private val settings: Settings,
    private val videoEndpoint: dev.brahmkshatriya.echo.extension.endpoints.EchoVideoEndpoint,
    private val visitorEndpoint: dev.brahmkshatriya.echo.extension.endpoints.EchoVisitorEndpoint
) {
    private fun getRealNewPipe() = RealNewPipeVideoFormatsEndpoint()
    
    private val ytmKtEndpoint by lazy {
        dev.brahmkshatriya.echo.extension.YtmKtVideoFormatsEndpoint(api)
    }
    
    private val maxVideoQuality: Int
        get() = settings.getString("video_quality")?.toIntOrNull() ?: 480  

    // ------------------------------------------------------------------
    // CodeBeat: verificacion previa de URLs (pre-flight)
    //
    // YouTube devuelve HTTP 403 en la reproduccion cuando la URL no esta
    // autorizada (sesion/anti-bot). Antes de entregar las fuentes al
    // reproductor se comprueba cada URL; se descartan las que dan 401/403
    // y, si una ruta completa falla, se pasa a la siguiente.
    // ------------------------------------------------------------------

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        private const val REFERER = "https://music.youtube.com/"
        private const val PROBE_LIMIT = 8
        private const val PROBE_TIMEOUT_MS = 15_000

        private val probeClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    /**
     * Cabeceras que se envian tanto en la comprobacion como en la reproduccion.
     *
     * `Range: bytes=0-` es imprescindible: `DefaultHttpDataSource` solo calcula
     * su propio Range si `position > 0 || length != UNSET` (`HttpUtil.buildRangeRequestHeader`
     * devuelve null con `0`/`UNSET`), es decir que en la primera peticion el
     * reproductor NO envia Range y YouTube responde 403. Al mandarlo aqui, Media3
     * lo respeta y solo lo pisa en los seeks (donde es el correcto).
     */
    private fun mediaHeaders(): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to REFERER,
            "Origin" to "https://music.youtube.com",
            "Range" to "bytes=0-"
        )
        runCatching {
            api.userAuthState?.headers?.forEach { name, values ->
                if (values.isNotEmpty()) headers[name] = values.joinToString("; ")
            }
        }
        return headers
    }

    private fun sourceOf(url: String, bitrateKbps: Int, title: String): Streamable.Source.Http =
        Streamable.Source.Http(
            request = NetworkRequest(url = url, headers = mediaHeaders()),
            type = Streamable.SourceType.Progressive,
            quality = bitrateKbps,
            title = title
        )

    /**
     * Replica EXACTA de la peticion que hace el reproductor:
     * `HttpURLConnection` (no OkHttp) con las mismas cabeceras, incluido el
     * `Range` que envia `DefaultHttpDataSource`. Si esto pasa, el reproductor
     * puede reproducir; si falla, fallara igual.
     */
    private fun probePlayerRequest(url: String, headers: Map<String, String>): Int {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = PROBE_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            return connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun probeOkHttp(url: String, headers: Map<String, String>): Int {
        val builder = okhttp3.Request.Builder().url(url)
        headers.forEach { (key, value) -> builder.header(key, value) }
        return probeClient.newCall(builder.build()).execute().use { it.code }
    }

    /** Devuelve (fuentes ok, motivos de fallo). Solo 401/403 cuentan como fallo. */
    private fun probeSources(
        sources: List<Streamable.Source.Http>
    ): Pair<List<Streamable.Source.Http>, List<String>> {
        val headers = mediaHeaders()
        val working = mutableListOf<Streamable.Source.Http>()
        val failed = mutableListOf<String>()
        for (source in sources.take(PROBE_LIMIT)) {
            val url = source.request.url
            val result = runCatching { probePlayerRequest(url, headers) }
            val code = result.getOrNull()
            when {
                code == null -> failed.add("red: ${result.exceptionOrNull()?.message ?: "error"}")
                code == 401 || code == 403 -> {
                    // Diagnostico: si OkHttp acepta la misma URL, el problema no es la
                    // URL sino la pila HTTP (h2 de OkHttp frente a h1.1 de HttpURLConnection).
                    val alt = runCatching { probeOkHttp(url, headers) }.getOrNull()
                    val altTxt = if (alt != null && alt < 400) " [okhttp=$alt]" else ""
                    failed.add("HTTP $code$altTxt")
                }
                else -> working.add(source)
            }
        }
        return working to failed
    }

    private suspend fun finalize(
        sources: List<Streamable.Source.Http>,
        label: String
    ): ExtractionResult {
        if (sources.isEmpty()) return ExtractionResult.Error("$label: sin fuentes")
        val (working, failed) = withContext(Dispatchers.IO) { probeSources(sources) }
        return if (working.isNotEmpty()) {
            println("CodeBeat: $label OK -> ${working.size}/${sources.size} URLs reproducibles")
            ExtractionResult.Success(Streamable.Media.Server(working, merged = false))
        } else {
            val detail = failed.distinct().joinToString("; ").ifEmpty { "sin detalle" }
            println("CodeBeat: $label FALLO -> $detail")
            ExtractionResult.Error("$label: URL bloqueada ($detail)")
        }
    }

    suspend fun resolveStreamable(videoId: String, preferVideos: Boolean): Streamable.Media {
        println("Loading streamable media for video ID: $videoId")
        
        return RetryUtils.retryWithBackoff(
            maxRetries = 3,
            initialDelay = 1000L,
            maxDelay = 10000L
        ) {
            resolveStreamableInternal(videoId, preferVideos)
        }
    }
    
   //Three-tier fallback strategy
   //CodeBeat: si el usuario inicio sesion con Google, se intentan PRIMERO las rutas
   //autenticadas (YtmKt y YouTube Music API) porque YouTube exige PO token/anti-bot y
   //la sesion autenticada reduce muchisimo los errores 403. Luego se conserva el orden
   //original (RealNewPipe -> YtmKt -> YouTube Music API) como respaldo.

    private suspend fun resolveStreamableInternal(videoId: String, preferVideos: Boolean): Streamable.Media {
        val errorReasons = mutableListOf<String>()
        val isLoggedIn = api.userAuthState != null

        if (isLoggedIn) {
            println("CodeBeat: sesion autenticada detectada, probando YtmKt primero para video ID: $videoId")
            val ytmKtAuthResult = tryYtmKtEndpoint(videoId, preferVideos)
            if (ytmKtAuthResult is ExtractionResult.Success) {
                println("CodeBeat: exito con YtmKt (autenticado)")
                return ytmKtAuthResult.media
            } else if (ytmKtAuthResult is ExtractionResult.Error) {
                errorReasons.add("YtmKt (auth): ${ytmKtAuthResult.reason}")
            }

            println("CodeBeat: YtmKt autenticado fallo, probando YouTube Music API (autenticado) para video ID: $videoId")
            val youtubeMusicAuthResult = tryYouTubeMusicApi(videoId, preferVideos)
            if (youtubeMusicAuthResult is ExtractionResult.Success) {
                println("CodeBeat: exito con YouTube Music API (autenticado)")
                return youtubeMusicAuthResult.media
            } else if (youtubeMusicAuthResult is ExtractionResult.Error) {
                errorReasons.add("YouTube Music API (auth): ${youtubeMusicAuthResult.reason}")
            }
        }

        //RealNewPipe
        println("RealNewPipe extractor for video ID: $videoId, preferVideos: $preferVideos")
        val realNewPipeResult = tryRealNewPipeExtractor(videoId, preferVideos)
        if (realNewPipeResult is ExtractionResult.Success) {
            println("Successfully loaded streamable media using RealNewPipe extractor")
            return realNewPipeResult.media
        } else if (realNewPipeResult is ExtractionResult.Error) {
            errorReasons.add("RealNewPipe: ${realNewPipeResult.reason}")
        }
        
        //MultipleVideoFormatsEndpoint
        println("RealNewPipe failed, trying YtmKt MultipleVideoFormatsEndpoint for video ID: $videoId")
        val ytmKtResult = tryYtmKtEndpoint(videoId, preferVideos)
        if (ytmKtResult is ExtractionResult.Success) {
            println("Successfully loaded streamable media using YtmKt library")
            return ytmKtResult.media
        } else if (ytmKtResult is ExtractionResult.Error) {
            errorReasons.add("YtmKt: ${ytmKtResult.reason}")
        }
        
        //YouTube Music API 
        println("YtmKt failed, trying YouTube Music API for video ID: $videoId")
        val youtubeMusicResult = tryYouTubeMusicApi(videoId, preferVideos)
        if (youtubeMusicResult is ExtractionResult.Success) {
            println("Successfully loaded streamable media using YouTube Music API")
            return youtubeMusicResult.media
        } else if (youtubeMusicResult is ExtractionResult.Error) {
            errorReasons.add("YouTube Music API: ${youtubeMusicResult.reason}")
        }
        
        // All fallbacks failed
        val detailedError = if (errorReasons.isNotEmpty()) {
            "Failed to resolve streaming URLs for video $videoId:\n" + errorReasons.joinToString("\n")
        } else {
            "Failed to resolve streaming URLs for video $videoId - all extraction methods failed"
        }
        throw Exception(detailedError)
    }
    
    private sealed class ExtractionResult {
        data class Success(val media: Streamable.Media) : ExtractionResult()
        data class Error(val reason: String) : ExtractionResult()
        object Failed : ExtractionResult()
    }
    

    private suspend fun tryRealNewPipeExtractor(videoId: String, preferVideos: Boolean): ExtractionResult {
        return try {
            if (preferVideos) {
                println("RealNewPipe Attempting to get muxed video+audio streams for: $videoId (max quality: ${maxVideoQuality}p)")
                val muxedResult = getRealNewPipe().getMuxedVideoStreams(videoId)

                if (muxedResult.isSuccess) {
                    val allMuxedFormats = muxedResult.getOrThrow()
                    println("RealNewPipe Got ${allMuxedFormats.size} total muxed video+audio streams")

                    val muxedFormats = if (maxVideoQuality < Int.MAX_VALUE) {
                        allMuxedFormats.filter { format ->
                            val heightMatch = Regex("(\\d+)p").find(format.mimeType)
                            val height = heightMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            height <= maxVideoQuality
                        }.also {
                            println("RealNewPipe Filtered to ${it.size} streams <= ${maxVideoQuality}p")
                        }
                    } else {
                        println("RealNewPipe Using all available qualities (Best Available)")
                        allMuxedFormats
                    }

                    if (muxedFormats.isEmpty()) {
                        println("RealNewPipe No muxed streams match quality setting ${maxVideoQuality}p, trying audio-only fallback")
                    } else {
                        val muxedSources = muxedFormats.map { format ->
                            val qualityKbps = if (format.bitrate > 0) format.bitrate / 1000 else 0
                            sourceOf(format.url!!, qualityKbps, format.mimeType)
                        }

                        println("RealNewPipe Returning ${muxedSources.size} muxed video+audio sources (instant playback, perfect sync)")
                        return finalize(muxedSources, "RealNewPipe (muxed)")
                    }
                } else {
                    println("RealNewPipe Muxed streams failed: ${muxedResult.exceptionOrNull()?.message}")
                }
            }

            println("RealNewPipe Attempting to get audio streams for: $videoId")
            val streamsResult = getRealNewPipe().getSeparateStreams(
                videoId = videoId,
                maxQuality = null
            )
            
            if (streamsResult.isFailure) {
                println("RealNewPipe Failed to get audio streams: ${streamsResult.exceptionOrNull()?.message}")
                return ExtractionResult.Failed
            }
            
            val (_, audioFormats) = streamsResult.getOrThrow()
            println("RealNewPipe Got ${audioFormats.size} audio streams")
            
            if (audioFormats.isEmpty()) {
                println("RealNewPipe No audio streams available")
                return ExtractionResult.Failed
            }
            
            val audioSources = audioFormats.map { format ->
                val bitrateKbps = if (format.bitrate > 0) format.bitrate / 1000 else 0
                sourceOf(format.url!!, bitrateKbps, "Audio - ${format.mimeType} - ${bitrateKbps}kbps")
            }
            
            println("RealNewPipe Returning ${audioSources.size} audio-only sources (instant playback)")
            return finalize(audioSources, "RealNewPipe")
        } catch (e: Exception) {
            println("RealNewPipe Exception: ${e.message}")
            e.printStackTrace()
            val errorMsg = e.message ?: "Unknown error"
            return when {
                errorMsg.contains("age", ignoreCase = true) || errorMsg.contains("restricted", ignoreCase = true) -> 
                    ExtractionResult.Error("Age-restricted content (Not playable at the moment)")
                errorMsg.contains("geo", ignoreCase = true) || errorMsg.contains("region", ignoreCase = true) || errorMsg.contains("blocked", ignoreCase = true) -> 
                    ExtractionResult.Error("Geo-blocked (Seems not available in your region)")
                errorMsg.contains("unavailable", ignoreCase = true) -> 
                    ExtractionResult.Error("Video unavailable")
                else -> ExtractionResult.Error(errorMsg)
            }
        }
    }
    
    private suspend fun tryYtmKtEndpoint(videoId: String, preferVideos: Boolean): ExtractionResult {
        return try {
            println("YtmKt Attempting to get formats for: $videoId, preferVideos: $preferVideos")
            
            if (preferVideos) {
                val muxedResult = ytmKtEndpoint.getMuxedVideoStreams(videoId)
                
                if (muxedResult.isSuccess) {
                    val allMuxedFormats = muxedResult.getOrThrow()
                    println("YtmKt: Got ${allMuxedFormats.size} total muxed video+audio streams")
                    
                    val muxedFormats = if (maxVideoQuality < Int.MAX_VALUE) {
                        allMuxedFormats.filter { format ->
                            val heightMatch = Regex("(\\d+)p").find(format.mimeType)
                            val height = heightMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            height <= maxVideoQuality
                        }.also {
                            println("YtmKt Filtered to ${it.size} streams <= ${maxVideoQuality}p")
                        }
                    } else {
                        allMuxedFormats
                    }
                    
                    if (muxedFormats.isNotEmpty()) {
                        val muxedSources = muxedFormats.map { format ->
                            val qualityKbps = if (format.bitrate > 0) format.bitrate / 1000 else 0
                            sourceOf(format.url!!, qualityKbps, format.mimeType)
                        }
                        
                        println("YtmKt: Returning ${muxedSources.size} muxed video+audio sources")
                        val muxedFinal = finalize(muxedSources, "YtmKt (muxed)")
                        if (muxedFinal is ExtractionResult.Success) return muxedFinal
                    }
                }
                
                println("YtmKt Muxed streams failed or empty, falling back to audio-only")
            }
            
            val audioResult = ytmKtEndpoint.getAudioFormats(videoId)
            
            if (audioResult.isFailure) {
                val error = audioResult.exceptionOrNull()
                println("YtmKt: Failed to get audio formats: ${error?.message}")
                return ExtractionResult.Error(error?.message ?: "Failed to get audio formats")
            }
            
            val audioFormats = audioResult.getOrThrow()
            println("YtmKt Got ${audioFormats.size} audio streams")
            
            if (audioFormats.isEmpty()) {
                println("YtmKt No audio streams available")
                return ExtractionResult.Error("No audio streams available")
            }
            
            val audioSources = audioFormats.map { format ->
                val bitrateKbps = if (format.bitrate > 0) format.bitrate / 1000 else 0
                sourceOf(format.url!!, bitrateKbps, "Audio - ${format.mimeType} - ${bitrateKbps}kbps")
            }
            
            println("YtmKt Returning ${audioSources.size} audio-only sources")
            return finalize(audioSources, "YtmKt")
        } catch (e: Exception) {
            println("YtmKt: Exception: ${e.message}")
            e.printStackTrace()
            val errorMsg = e.message ?: "Unknown error"
            return ExtractionResult.Error(errorMsg)
        }
    }
    
    private suspend fun tryYouTubeMusicApi(videoId: String, preferVideos: Boolean): ExtractionResult {
        return try {
            println("Falling back to YouTube Music API for video ID: $videoId")
            
            try {
                if (api.visitorId == null) {
                    println("Visitor ID is null, trying to get a new one...")
                    api.visitorId = visitorEndpoint.getVisitorId()
                    println("Successfully set visitor ID: ${api.visitorId}")
                } else {
                    println("Using existing visitor ID: ${api.visitorId}")
                }
            } catch (e: Exception) {
                println("Exception ensuring visitor ID: ${e.message}")
            }
            
            println("YouTube Music API: Fetching formats for: $videoId")
            val (video, _) = videoEndpoint.getVideo(true, videoId)
            
            if (video.streamingData == null) {
                val errorMsg = "No streaming data available from YouTube Music API. The video may be restricted, age-gated, or unavailable."
                println("YouTube Music API: $errorMsg")
                return ExtractionResult.Error(errorMsg)
            }
            
            val streamingData = video.streamingData
            val adaptiveFormats = streamingData.adaptiveFormats
            if (adaptiveFormats.isEmpty()) {
                println("YouTube Music API No adaptive formats available")
                return ExtractionResult.Error("No audio sources found in YouTube Music API response")
            }
            
            println("YouTube Music API Got ${adaptiveFormats.size} adaptive formats")
            
            val audioFormats = adaptiveFormats
                .filter { it.mimeType.lowercase().contains("audio/") && it.url != null }
            
            println("YouTube Music API: ${audioFormats.size} audio formats found")
            
            if (audioFormats.isEmpty()) {
                println("YouTube Music API: No audio formats available")
                return ExtractionResult.Error("No audio sources found in YouTube Music API response")
            }
            
            val audioSources = audioFormats.map { format ->
                val bitrateKbps = if (format.bitrate > 0) format.bitrate / 1000 else 128
                sourceOf(
                    format.url!!,
                    bitrateKbps,
                    "Audio - ${format.mimeType}${if (format.bitrate > 0) " - ${bitrateKbps}kbps" else ""}"
                )
            }
            
            println("Successfully loaded streamable media using YouTube Music API with ${audioSources.size} quality options")
            return finalize(audioSources, "YouTube Music API")
        } catch (e: Exception) {
            println("YouTube Music API also failed: ${e.message}")
            e.printStackTrace()
            val errorMsg = e.message ?: "Unknown error"
            return ExtractionResult.Error(errorMsg)
        }
    }
}
