package dev.brahmkshatriya.echo.extensions

import dev.brahmkshatriya.echo.BuildConfig
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.utils.Serializer.toData
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Catálogo privado de extensiones alojado por CodeHub.
 *
 * El dispositivo SOLO habla con el backend de CodeHub: es el servidor quien
 * resuelve y reenvía el `.eapk` real, así que la URL/repositorio de origen
 * nunca viaja al cliente. Aquí no se guarda ni se muestra ese enlace.
 */
object CodeHubExtensions {

    /** Ruta del proxy de descarga que sirve el backend. */
    const val PKG_PATH = "/api/extensions/pkg/"

    private const val TTL_MS = 30 * 60 * 1000L

    val backend: String = BuildConfig.CODEHUB_BACKEND.trimEnd('/')

    /** Lista de extensiones disponibles (el mismo JSON que ya consume Echo). */
    fun catalogUrl(): String = "$backend/api/extensions"

    /** ¿Esta URL la sirve el backend de CodeHub? */
    fun isProxyUrl(url: String): Boolean = url.contains(PKG_PATH)

    @Serializable
    data class Entry(
        val id: String,
        val name: String,
        val updateUrl: String,
    )

    @Volatile private var cache: Map<String, String>? = null
    @Volatile private var cacheAt: Long = 0L

    /** Mapa `id -> updateUrl` (proxy), cacheado en memoria. */
    suspend fun catalog(client: OkHttpClient, force: Boolean = false): Map<String, String> {
        val now = System.currentTimeMillis()
        val current = cache
        if (!force && current != null && now - cacheAt < TTL_MS) return current
        val fresh = runCatching {
            client.newCall(Request.Builder().url(catalogUrl()).build())
                .await().body.string()
                .toData<List<Entry>>().getOrThrow()
                .associate { it.id to it.updateUrl }
        }.getOrNull() ?: return current ?: emptyMap()
        cache = fresh
        cacheAt = now
        return fresh
    }

    /** URL proxy del `id`, o `null` si la extensión no está en el catálogo. */
    suspend fun updateUrlFor(id: String, client: OkHttpClient): String? = catalog(client)[id]
}
