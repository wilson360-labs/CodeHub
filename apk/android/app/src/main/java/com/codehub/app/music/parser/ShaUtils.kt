package com.codehub.app.music.parser

import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 de un fichero (APK de extensión), usado para evitar reparsear
 * extensiones sin cambios. Port de `dev.brahmkshatriya.echo.utils.ShaUtils`.
 */
object ShaUtils {

    fun getSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inputStream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}