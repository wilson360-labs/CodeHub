package dev.brahmkshatriya.echo

import android.content.Context
import coil3.SingletonImageLoader
import dev.brahmkshatriya.echo.di.DI
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
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
     *
     * Además reproduce los efectos de `MainApplication.onCreate` que sí afectan
     * al reproductor y que se perdían al ser CodeHubApp la Application real:
     *  - registra el loader de imágenes de Echo (SingletonImageLoader),
     *  - aplica el idioma persistido de Echo (mismo `applyLocale`).
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
            // Sin esto Coil usa el loader por defecto con hardware bitmaps y
            // el palette/GIF de Echo puede fallar.
            runCatching { SingletonImageLoader.setSafe { ctx -> EchoImageLoader.build(ctx) } }
            // Idioma elegido en Echo (por defecto "system" = sin override).
            runCatching { MainApplication.applyLocale(context.getSettings()) }
            ready = true
        }
    }
}
