package dev.brahmkshatriya.echo.extension.streaming

import dev.brahmkshatriya.echo.common.models.NetworkRequest
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.RealNewPipeVideoFormatsEndpoint
import dev.brahmkshatriya.echo.extension.utils.RetryUtils
import dev.toastbits.ytmkt.impl.youtubei.YoutubeiApi


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

    // Three-tier fallback strategy
    // MEJORA: si hay sesion autenticada (Google login), se prueba YtmKt primero
    // porque los streams autenticados tienen menos probabilidad de 403.

    private suspend fun resolveStreamableInternal(videoId: String, preferVideos: Boolean): Streamable.Media {
        val errorReasons = mutableListOf<String>()
        val isLoggedIn = api.userAuthState != null

        if (isLoggedIn) {
            // --- Sesion autenticada: YtmKt primero ---
            println("Sesion autenticada. Probando YtmKt para: $videoId")
            val ytmKtResult = tryYtmKtEndpoint(videoId, preferVideos)
            if (ytmKtResult is ExtractionResult.Success) {
                println("Exito con YtmKt (autenticado)")
                return ytmKtResult.media
            } else if (ytmKtResult is ExtractionResult.Error) {
                errorReasons.add("YtmKt: ${ytmKtResult.reason}")
            }

            // YouTube Music API (autenticado)
            println("YtmKt fallo, probando YouTube Music API (autenticado) para: $videoId")
            val youtubeMusicResult = tryYouTubeMusicApi(videoId, preferVideos)
            if (youtubeMusicResult is ExtractionResult.Success) {
                println("Exito con YouTube Music API (autenticado)")
                return youtubeMusicResult.media
            } else if (youtubeMusicResult is ExtractionResult.Error) {
                errorReasons.add("YouTube Music API: ${youtubeMusicResult.reason}")
            }
        }

        // --- RealNewPipe (anonimo, fallback) ---
        println("Probando RealNewPipe extractor para: $videoId")
        val realNewPipeResult = tryRealNewPipeExtractor(videoId, preferVideos)
        if (realNewPipeResult is ExtractionResult.Success) {
            println("Exito con RealNewPipe")
            return realNewPipeResult.media
        } else if (realNewPipeResult is ExtractionResult.Error) {
            errorReasons.add("RealNewPipe: ${realNewPipeResult.reason}")
        }

        // Si no estaba autenticado, probar YtmKt y YouTube Music API anonimos
        if (!isLoggedIn) {
            println("Sin sesion. Probando YtmKt (anonimo) para: $videoId")
            val ytmKtResult = tryYtmKtEndpoint(videoId, preferVideos)
            if (ytmKtResult is ExtractionResult.Success) {
                println("Exito con YtmKt (anonimo)")
                return ytmKtResult.media
            } else if (ytmKtResult is ExtractionResult.Error) {
                errorReasons.add("YtmKt: ${ytmKtResult.reason}")
            }

            println("Probando YouTube Music API (anonimo) para: $videoId")
            val youtubeMusicResult = tryYouTubeMusicApi(videoId, preferVideos)
            if (youtubeMusicResult is ExtractionResult.Success) {
                println("Exito con YouTube Music API (anonimo)")
                return youtubeMusicResult.media
            } else if (youtubeMusicResult is ExtractionResult.Error) {
                errorReasons.add("YouTube Music API: ${youtubeMusicResult.reason}")
            }
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

    // ... (rest of the file: tryRealNewPipeExtractor, tryYtmKtEndpoint, tryYouTubeMusicApi)
    // Copy the rest from the original file unchanged.
}
