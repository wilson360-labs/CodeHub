package dev.brahmkshatriya.echo

import android.content.Context
import dev.brahmkshatriya.echo.di.DI
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.dsl.KoinConfiguration
import org.koin.dsl.koinConfiguration

/**
 * Puente de arranque de Echo dentro de CodeHub.
 *
 * Echo enciende su contenedor Koin en [MainApplication] implementando
 * `KoinStartup` (autodescubierto por `koin-androidx-startup`). Como la
 * Application de CodeHub es `com.codehub.app.CodeHubApp` (Java), ese hook
 * nunca se dispararía; aquí exponemos exactamente la misma configuración
 * para que `CodeHubApp` la devuelva desde `onKoinStartup()`.
 */
object EchoBoot {

    @JvmStatic
    fun config(context: Context): KoinConfiguration = koinConfiguration {
        androidContext(context.applicationContext)
        modules(DI.appModule)
        workManagerFactory()
    }
}
