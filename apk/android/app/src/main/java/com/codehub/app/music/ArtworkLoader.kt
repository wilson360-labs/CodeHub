package com.codehub.app.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import dev.brahmkshatriya.echo.common.models.ImageHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Cargador de artwork para cards del player. Descarga [ImageHolder]
 * (red/res/URI/color) en hilo de fondo y entrega el [Bitmap] en main.
 */
class ArtworkLoader(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val cache = LruCache<String, Bitmap>(96)

    fun load(holder: ImageHolder?, onResult: (Bitmap?) -> Unit) {
        if (holder == null) {
            main.post { onResult(null) }
            return
        }
        val useCache = holder is ImageHolder.NetworkRequestImageHolder
        val url = holder.let {
            when (it) {
                is ImageHolder.NetworkRequestImageHolder -> it.request.url
                is ImageHolder.ResourceUriImageHolder -> it.uri
                is ImageHolder.ResourceIdImageHolder -> "res://${it.resId}"
                is ImageHolder.HexColorImageHolder -> it.hex
            }
        }
        val cached = cache.get(url)
        if (cached != null) {
            main.post { onResult(cached) }
            return
        }
        scope.launch {
            val bitmap = runCatching { decode(holder) }.getOrNull()
            if (useCache && bitmap != null) cache.put(url, bitmap)
            main.post { onResult(bitmap) }
        }
    }

    private fun decode(holder: ImageHolder): Bitmap? = when (holder) {
        is ImageHolder.NetworkRequestImageHolder -> {
            val request = holder.request
            if (request.method != dev.brahmkshatriya.echo.common.models.NetworkRequest.Method.GET) {
                return null
            }
            val req = Request.Builder().url(request.url).apply {
                request.headers.forEach { (k, v) -> header(k, v) }
            }.build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }

        is ImageHolder.ResourceUriImageHolder -> {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(holder.uri))?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()
        }

        is ImageHolder.ResourceIdImageHolder ->
            BitmapFactory.decodeResource(context.resources, holder.resId)

        is ImageHolder.HexColorImageHolder -> {
            runCatching {
                val color = Color.parseColor(holder.hex)
                val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(color)
                bmp
            }.getOrNull()
        }
    }
}