package com.codehub.app.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.codehub.app.music.MusicMedia.artworkHolder
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

/**
 * Carga carátulas para media3 (notificación de reproducción, pantalla de
 * bloqueo y salidas de sistema) resolviendo los `artworkUri` con esquema
 * `codehub-art://` que emite [MusicMedia.toArtworkUri]. Es el equivalente de
 * `PlayerBitmapLoader` en echo-nightly: decodifica el [ImageHolder]
 * serializado en la query y lo resuelve con [ArtworkLoader].
 */
@OptIn(UnstableApi::class)
class SessionArtworkLoader(context: Context) : BitmapLoader {

    private val appContext: Context = context.applicationContext
    private val artwork = ArtworkLoader(appContext)

    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        val bitmap = runCatching { BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull()
        if (bitmap != null) future.set(bitmap)
        else future.setException(IllegalArgumentException("No se pudo decodificar la carátula"))
        return future
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        val holder = uri.artworkHolder()
        if (holder == null) {
            future.setException(IllegalArgumentException("Carátula no soportada: $uri"))
            return future
        }
        artwork.load(holder) { bitmap ->
            if (bitmap != null) future.set(bitmap)
            else future.setException(IllegalStateException("No se pudo cargar la carátula"))
        }
        return future
    }
}
