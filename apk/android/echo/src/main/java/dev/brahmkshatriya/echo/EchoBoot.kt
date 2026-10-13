package dev.brahmkshatriya.echo

import android.content.Context
import dev.brahmkshatriya.echo.di.DI
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

/**
 * Puente de arranque de Echo dentro de CodeHub.
 *
 * Echo enciende su contenedor Koin en `MainApplication` implementando
 * `KoinStartup` (autodescubierto por `koin-androidx-startup`). Aquí NO usamos
 * ese mecanismo: `koin-androidx-startup` aborta el proceso si la Application no
 * implementa la interfaz, y no queremos que un fallo del reproductor tumbe el
 * resto de CodeHub. En su lugar arrancamos Koin de forma explícita, idempotente
 * y defensiva, y [isReady] permite caer al reproductor nativo si algo falla.
 *
 * `workManagerFactory()` deja WorkManager inicializado con el KoinWorkerFactory
 * (Echo elimina el `WorkManagerInitializer` por defecto en su manifiesto).
 */
object EchoBoot {

    private val lock = Any()

    @Volatile
    private var ready = false

    /** ¿Koin (y el WorkManager factory) del reproductor quedó operativo? */
    @JvmStatic
    fun isReady(): Boolean = ready

    /**
     * Enciende Koin para el reproductor Echo. Idempotente y seguro de llamar
     * varias veces. Si falla, lanza; el llamador (CodeHubApp) lo captura para
     * no tumbar CodeHub.
     */
    @JvmStatic
    fun start(context: Context) {
        if (ready) return
        synchronized(lock) {
            if (ready) return
            startKoin {
                androidContext(context.applicationContext)
                modules(DI.appModule)
                workManagerFactory()
            }
            ready = true
        }
    }
}
