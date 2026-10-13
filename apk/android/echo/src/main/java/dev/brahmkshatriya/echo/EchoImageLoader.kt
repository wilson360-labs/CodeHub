package dev.brahmkshatriya.echo

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.allowHardware
import coil3.request.crossfade

/**
 * Configuración de imágenes (Coil 3) de Echo.
 *
 * El código original vivía en `MainApplication.newImageLoader` porque el
 * Application era `MainApplication`. En el injerto, la Application real es
 * CodeHubApp y `:app` no puede implementar `SingletonImageLoader.Factory`
 * (Coil es un detalle interno de `:echo`). Por eso el loader se registra desde
 * [EchoBoot] vía `SingletonImageLoader.setSafe`.
 *
 * `allowHardware(false)` NO es cosmético: con hardware bitmaps el palette y los
 * GIF animados de Echo fallan (no se puede leer el bitmap en CPU).
 */
object EchoImageLoader {

    fun build(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image-cache"))
                    .maxSizeBytes(1024 * 1024 * 100) // 100MB
                    .build()
            }
            .allowHardware(false)
            .crossfade(true)
            .build()
    }
}
