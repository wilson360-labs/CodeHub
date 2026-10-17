# YouTube Music Extension — Fix para 403 (PO token / anti-bot)

## Idea: usar autenticacion de Google (como Morphe)

El extension `abhishekkchoudharyy/Eco-Youtube_Music` ya implementa `LoginClient.WebView`
(inicio de sesion con Google). Cuando el usuario se autentica, el `YoutubeiApi` usa
la sesion autenticada, y los stream URLs que devuelve YtmKt funcionan mejor porque
estan ligados a la sesion del usuario (YouTube trata las peticiones autenticadas
de forma diferente a las anonimas).

## Cambio clave: reordenar el fallback en YouTubeStreamResolver.kt

Actualmente el orden es:
  1. RealNewPipe (anonimo)  -> siempre falla con 403
  2. YtmKt (anonimo o autenticado)
  3. YouTube Music API (anonimo o autenticado)

Nuevo orden (cuando hay sesion autenticada):
  1. YtmKt (usa sesion autenticada)  -> mejor probabilidad de exito
  2. YouTube Music API (usa sesion autenticada)
  3. RealNewPipe (fallback anonimo)

Cuando NO hay sesion:
  1. RealNewPipe (anonimo)
  2. YtmKt (anonimo)
  3. YouTube Music API (anonimo)

## Codigo modificado: resolveStreamableInternal

Reemplaza la funcion `resolveStreamableInternal` en:
  ext/src/main/java/dev/brahmkshatriya/echo/extension/streaming/YouTubeStreamResolver.kt

```kotlin
private suspend fun resolveStreamableInternal(videoId: String, preferVideos: Boolean): Streamable.Media {
    val errorReasons = mutableListOf<String>()
    val isLoggedIn = api.userAuthState != null

    if (isLoggedIn) {
        // --- Sesion autenticada: probar YtmKt primero ---
        println("Sesion autenticada detectada. Probando YtmKt primero para: $videoId")
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

    // Todos los fallbacks fallaron
    val detailedError = if (errorReasons.isNotEmpty()) {
        "Failed to resolve streaming URLs for video $videoId:\n" + errorReasons.joinToString("\n")
    } else {
        "Failed to resolve streaming URLs for video $videoId - all extraction methods failed"
    }
    throw Exception(detailedError)
}
```

## Pasos para aplicar

1. Forkea `abhishekkchoudharyy/Eco-Youtube_Music` en tu cuenta de GitHub.
2. Reemplaza la funcion `resolveStreamableInternal` en `YouTubeStreamResolver.kt` con el codigo de arriba.
3. Compila con el workflow `build-ytm-extension.yml` (pon tu fork y branch).
4. Hospeda el APK resultante (en el repo de CodeHub o en el fork).
5. Pasa la URL del APK y la añado al catalogo CodeBeat.

## Instrucciones para el usuario final

En CodeBeat, despues de instalar YouTube Music:
  1. Ve a Configuracion del extension.
  2. Inicia sesion con tu cuenta de Google.
  3. Los streams se extraeran con tu sesion autenticada (menos probabilidad de 403).

## Nota importante

Incluso con autenticacion, YouTube puede seguir bloqueando algunos videos. La autenticacion reduce la probabilidad pero no la elimina. Si el 403 persiste, la unica solucion real es esperar a que NewPipeExtractor actualice su soporte de PO tokens (que es lo que el autor original haria al actualizar la dependencia).

Para eso, tambien habria que actualizar en ext/build.gradle.kts:
  newpipe = "v0.26.5"  ->  newpipe = "v0.26.1" (o la ultima version disponible en JitPack)
Y recompilar.
