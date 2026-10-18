package dev.brahmkshatriya.echo.extension.streaming

import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.RealNewPipeVideoFormatsEndpoint
import dev.brahmkshatriya.echo.extension.utils.RetryUtils
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
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
        // UA por cliente (Eco-403-fix): googlevideo responde 403 si la User-Agent no
        // coincide con el cliente que genero la URL. El cliente viaja en el parametro
        // "c=" de la propia URL (verificado: &c=ANDROID en las URLs del cliente ANDROID).
        private const val IOS_UA =
            "com.google.ios.youtube/19.34.2 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"
        private const val ANDROID_UA =
            "com.google.android.youtube/19.44.38 (Linux; U; Android 11) gzip"
        private const val ANDROID_VR_UA =
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; " +
                    "eureka-user Build/SQ3A.220605.009.A1) gzip"
        private const val WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val PROBE_LIMIT = 8
        private const val PROBE_TIMEOUT_MS = 15_000

        /**
         * Ruta principal CodeBeat: InnerTube `clientName=ANDROID`.
         *
         * Desde 2026 YouTube exige un **PO token GVS** para `web_music`, `ios`, `mweb`,
         * `tv_simply`, etc.: por eso esas rutas devuelven 403 al reproducir o
         * `400 Precondition check failed` al llamar a `/player`. El cliente `ANDROID`
         * **no exige PO token** y entrega `streamingData` con URLs **sin signatureCipher**,
         * asi que no hace falta descifrar firmas.
         * Verificado en vivo (oct-2026): `status=OK` + `GET Range` -> 206.
         */
        private const val INNERTUBE_PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        private const val ANDROID_CLIENT_VERSION = "20.10.38"
        private const val ANDROID_USER_AGENT =
            "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip"

        private val probeClient by lazy {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    /**
     * Cabeceras por URL (base Eco-403-fix + aportes CodeBeat).
     *
     * 1. **UA segun el cliente** (`c=` de la URL): si no coincide con el cliente que
     *    genero la URL, googlevideo responde 403.
     * 2. **`Range: bytes=0-`**: `DefaultHttpDataSource` no envia Range en la primera
     *    carga (`HttpUtil.buildRangeRequestHeader` devuelve null con `0`/`UNSET`).
     *    Media3 solo lo pisa en los seeks, asi que no rompe el avance.
     * 3. **Cookies de la sesion Google** solo para clientes web (los nativos IOS /
     *    ANDROID no soportan cookies de cuenta y llegarian sucias).
     */
    private fun headersFor(url: String): Map<String, String> {
        val client = Regex("[?&]c=([A-Z_0-9]+)").find(url)?.groupValues?.get(1)
        val nativeClient = client == "IOS" || client == "ANDROID" ||
                client == "ANDROID_MUSIC" || client == "ANDROID_VR"

        val headers = when (client) {
            "IOS" -> mutableMapOf("User-Agent" to IOS_UA)
            "ANDROID_VR" -> mutableMapOf("User-Agent" to ANDROID_VR_UA)
            "ANDROID", "ANDROID_MUSIC" -> mutableMapOf("User-Agent" to ANDROID_UA)
            else -> mutableMapOf(
                "User-Agent" to WEB_UA,
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/"
            )
        }
        headers["Range"] = "bytes=0-"

        if (!nativeClient) {
            runCatching {
                api.userAuthState?.headers?.forEach { name, values ->
                    if (values.isNotEmpty()) headers[name] = values.joinToString("; ")
                }
            }
        }
        return headers
    }

    private fun streamRequest(url: String) = NetworkRequest(url = url, headers = headersFor(url))

    private fun sourceOf(url: String, bitrateKbps: Int, title: String): Streamable.Source.Http =
        Streamable.Source.Http(
            request = streamRequest(url),
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
        val working = mutableListOf<Streamable.Source.Http>()
        val failed = mutableListOf<String>()
        for (source in sources.take(PROBE_LIMIT)) {
            val url = source.request.url
            val headers = source.request.headers
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

    /**
     * De Eco-403-fix: descarta la ruta si el CDN rechaza la primera fuente (403),
     * pasando a la siguiente en vez de devolver un error directo.
     *
     * Nota: `finalize()` ya sondea TODAS las fuentes y filtra; este es el guardia
     * adicional de Eco-403-fix aplicado a las tres rutas originales.
     */
    private suspend fun isPlayable(media: Streamable.Media): Boolean {
        val source = (media as? Streamable.Media.Server)?.sources?.firstOrNull()
                as? Streamable.Source.Http ?: return true
        return withContext(Dispatchers.IO) {
            try {
                val code = probePlayerRequest(source.request.url, source.request.headers)
                println("Probe $code for ${source.request.url.take(80)}")
                code in 200..299
            } catch (e: Exception) {
                println("Probe error: ${e.message}")
                true // fallo de red puntual: no se descarta una ruta valida
            }
        }
    }

    /**
     * POST a InnerTube con `clientName=ANDROID` (sin PO token, URLs sin cifrar).
     * Se usa `HttpURLConnection` para no depender de la API de OkHttp.
     */
    private fun postAndroidPlayer(videoId: String): String {
        val payload = JSONObject().apply {
            put("context", JSONObject().put("client", JSONObject().apply {
                put("clientName", "ANDROID")
                put("clientVersion", ANDROID_CLIENT_VERSION)
                put("androidSdkVersion", 30)
                put("osName", "Android")
                put("osVersion", "11")
                put("hl", "en")
                put("gl", "US")
            }))
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }.toString().toByteArray(Charsets.UTF_8)

        val connection = java.net.URL(INNERTUBE_PLAYER_URL)
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = PROBE_TIMEOUT_MS
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setFixedLengthStreamingMode(payload.size)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", ANDROID_USER_AGENT)
            connection.setRequestProperty("X-Goog-Api-Format-Version", "1")
            connection.setRequestProperty("X-Youtube-Client-Name", "3")
            connection.setRequestProperty("X-Youtube-Client-Version", ANDROID_CLIENT_VERSION)
            connection.setRequestProperty("Origin", "https://www.youtube.com")
            connection.setRequestProperty("Referer", "https://www.youtube.com/")
            connection.outputStream.use { it.write(payload) }

            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw Exception("HTTP $code: ${text.take(300)}")
            return text
        } finally {
            connection.disconnect()
        }
    }

    /** Extrae fuentes reproducibles de un JSONArray de `streamingData` (solo URLs sin cifrar). */
    private fun readFormats(array: JSONArray?, audio: Boolean): List<Streamable.Source.Http> {
        if (array == null) return emptyList()
        val sources = mutableListOf<Streamable.Source.Http>()
        for (i in 0 until array.length()) {
            val format = array.optJSONObject(i) ?: continue
            val url = format.optString("url") ?: ""
            if (url.isEmpty()) continue // signatureCipher: el cliente ANDROID no deberia darlo
            val mime = (format.optString("mimeType") ?: "").substringBefore(";").trim()
            val isAudio = mime.startsWith("audio")
            if (isAudio != audio) continue

            val quality = if (audio) {
                val bps = maxOf(format.optInt("averageBitrate"), format.optInt("bitrate"))
                if (bps > 0) bps / 1000 else 128
            } else {
                format.optInt("height")
            }
            val title = if (audio) {
                "Audio - $mime - ${quality}kbps"
            } else {
                "Video - $mime - ${quality}p"
            }
            sources.add(sourceOf(url, quality, title))
        }
        return sources
    }

    private suspend fun tryAndroidClient(videoId: String, preferVideos: Boolean): ExtractionResult {
        return try {
            println("CodeBeat: InnerTube ANDROID para video ID: $videoId (preferVideos=$preferVideos)")
            val text = withContext(Dispatchers.IO) { postAndroidPlayer(videoId) }
            val root = JSONObject(text)

            val playability = root.optJSONObject("playabilityStatus")
            val status = playability?.optString("status") ?: ""
            if (status.isNotEmpty() && status != "OK") {
                val reason = (playability?.optString("reason") ?: "").ifEmpty { status }
                return ExtractionResult.Error("status=$status ($reason)")
            }

            val streaming = root.optJSONObject("streamingData")
                ?: return ExtractionResult.Error("sin streamingData (status=${status.ifEmpty {"?"}})")

            if (preferVideos) {
                val muxed = readFormats(streaming.optJSONArray("formats"), audio = false)
                if (muxed.isNotEmpty()) return finalize(muxed, "ANDROID (muxed)")
                println("CodeBeat: ANDROID sin formatos muxed, usando audio")
            }

            val audio = readFormats(streaming.optJSONArray("adaptiveFormats"), audio = true)
            if (audio.isEmpty()) return ExtractionResult.Error("0 fuentes de audio en streamingData")
            finalize(audio, "ANDROID")
        } catch (e: Exception) {
            println("CodeBeat: ANDROID fallo: ${e.message}")
            ExtractionResult.Error(e.message ?: "error inesperado")
        }
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
    
   //Fallback strategy CodeBeat
   //CodeBeat: PRIMERO la ruta `InnerTube ANDROID`, que no exige PO token GVS y entrega
   //URLs sin cifrar (las demas rutas dan 403/400 desde 2026). Si el usuario inicio
   //sesion con Google, despues se intentan las rutas autenticadas (YtmKt y YouTube
   //Music API). Al final se conserva el orden original (RealNewPipe -> YtmKt ->
   //YouTube Music API) como ultimo respaldo.

    private suspend fun resolveStreamableInternal(videoId: String, preferVideos: Boolean): Streamable.Media {
        val errorReasons = mutableListOf<String>()
        val isLoggedIn = api.userAuthState != null

        // CodeBeat: ruta principal (no necesita sesion ni PO token)
        println("CodeBeat: probando InnerTube ANDROID para video ID: $videoId")
        val androidResult = tryAndroidClient(videoId, preferVideos)
        if (androidResult is ExtractionResult.Success) {
            println("CodeBeat: exito con InnerTube ANDROID")
            return androidResult.media
        } else if (androidResult is ExtractionResult.Error) {
            errorReasons.add("InnerTube ANDROID: ${androidResult.reason}")
        }

        if (isLoggedIn) {
            println("CodeBeat: sesion autenticada detectada, probando rutas autenticadas para video ID: $videoId")
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

        // ---- Base Eco-403-fix: tres rutas originales + guardia `isPlayable` ----

        //RealNewPipe
        println("RealNewPipe extractor for video ID: $videoId, preferVideos: $preferVideos")
        val realNewPipeResult = tryRealNewPipeExtractor(videoId, preferVideos)
        if (realNewPipeResult is ExtractionResult.Success && isPlayable(realNewPipeResult.media)) {
            println("Successfully loaded streamable media using RealNewPipe extractor")
            return realNewPipeResult.media
        } else if (realNewPipeResult is ExtractionResult.Success) {
            errorReasons.add("RealNewPipe: CDN rechazo el stream (403)")
        } else if (realNewPipeResult is ExtractionResult.Error) {
            errorReasons.add("RealNewPipe: ${realNewPipeResult.reason}")
        }
        
        //MultipleVideoFormatsEndpoint
        println("RealNewPipe failed, trying YtmKt MultipleVideoFormatsEndpoint for video ID: $videoId")
        val ytmKtResult = tryYtmKtEndpoint(videoId, preferVideos)
        if (ytmKtResult is ExtractionResult.Success && isPlayable(ytmKtResult.media)) {
            println("Successfully loaded streamable media using YtmKt library")
            return ytmKtResult.media
        } else if (ytmKtResult is ExtractionResult.Success) {
            errorReasons.add("YtmKt: CDN rechazo el stream (403)")
        } else if (ytmKtResult is ExtractionResult.Error) {
            errorReasons.add("YtmKt: ${ytmKtResult.reason}")
        }
        
        //YouTube Music API 
        println("YtmKt failed, trying YouTube Music API for video ID: $videoId")
        val youtubeMusicResult = tryYouTubeMusicApi(videoId, preferVideos)
        if (youtubeMusicResult is ExtractionResult.Success && isPlayable(youtubeMusicResult.media)) {
            println("Successfully loaded streamable media using YouTube Music API")
            return youtubeMusicResult.media
        } else if (youtubeMusicResult is ExtractionResult.Success) {
            errorReasons.add("YouTube Music API: CDN rechazo el stream (403)")
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
                val detail = streamsResult.exceptionOrNull()?.message ?: "sin detalle"
                println("RealNewPipe Failed to get audio streams: $detail")
                // Se reporta (antes era ExtractionResult.Failed y desaparecia del error)
                return ExtractionResult.Error("extraccion: $detail")
            }

            val (_, audioFormats) = streamsResult.getOrThrow()
            println("RealNewPipe Got ${audioFormats.size} audio streams")

            if (audioFormats.isEmpty()) {
                println("RealNewPipe No audio streams available")
                return ExtractionResult.Error("extraccion: 0 fuentes de audio en la pagina")
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
