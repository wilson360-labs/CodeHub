package com.codehub.app.music.parser

/**
 * Error de carga de una extensión Echo (nombre de clase + archivo fuente).
 * Port de `dev.brahmkshatriya.echo.extensions.exceptions.ExtensionLoaderException`.
 */
class ExtensionLoaderException(
    val clazz: String,
    val source: String,
    override val cause: Throwable
) : Exception()