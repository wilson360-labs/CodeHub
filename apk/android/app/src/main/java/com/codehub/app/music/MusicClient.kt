package com.codehub.app.music

import dev.brahmkshatriya.echo.common.Extension
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Helpers para invocar clientes de una extensión Echo, replicando la
 * semántica de `dev.brahmkshatriya.echo.extensions.ExtensionUtils`
 * (los fallos se devuelven como [Result], con [ClientException.NotSupported]
 * si la extensión no implementa el cliente pedido).
 */
object MusicClient {

    suspend fun <R> Extension<*>.get(
        block: suspend ExtensionClient.() -> R
    ): Result<R> = runCatching {
        withContext(Dispatchers.IO) {
            @Suppress("UNCHECKED_CAST")
            (instance.value().getOrThrow() as ExtensionClient).block()
        }
    }

    suspend inline fun <reified C, R> Extension<*>.getAs(
        crossinline block: suspend C.() -> R
    ): Result<R> = get<R> {
        val client = this as? C
            ?: throw ClientException.NotSupported(C::class.simpleName ?: "Unknown Class")
        block(client)
    }

    suspend inline fun <reified C, R> Extension<*>.getIf(
        crossinline block: suspend C.() -> R
    ): Result<R?> = get<R?> {
        val client = this as? C
        client?.let { block(it) }
    }
}