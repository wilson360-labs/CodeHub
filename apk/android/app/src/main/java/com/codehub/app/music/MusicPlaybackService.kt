package com.codehub.app.music

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.codehub.app.R
import com.codehub.app.music.ui.MusicPlayerActivity


/**
 * Servicio de reproducción en segundo plano del player de CodeHub (port de
 * echo-nightly `PlayerService`, con Android Auto vía MediaLibrarySession):
 *
 *  - MediaLibrarySession (media3-session) sobre el [MusicPlayer] singleton:
 *    notificación de medios, control desde pantalla de bloqueo, headset
 *    (MediaButtonReceiver) y Android Auto (browse de descargas).
 *  - La notificación se muestra automáticamente mientras haya reproducción
 *    y el servicio se mantiene en foreground (foregroundServiceType
 *    "mediaPlayback"); al quedarse idle se detiene solo.
 *  - El reproductor NO se libera cuando la Activity muere: la música sigue
 *    sonando en segundo plano hasta que el usuario la detenga.
 */
@OptIn(UnstableApi::class)
class MusicPlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val player = MusicPlayer.shared(applicationContext)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MusicPlayerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this).build()
        notificationProvider.setSmallIcon(R.drawable.ic_music_play)

        session = MediaLibrarySession.Builder(this, player.exo, MusicAutoCallback(this))
            .setSessionActivity(pendingIntent)
            .build()

        setMediaNotificationProvider(notificationProvider)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swipe-away de la tarea: se mantiene la reproducción en segundo
        // plano (comportamiento de apps de música); el usuario decide desde
        // la notificación.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        session?.release()
        session = null
        // Sin liberar MusicPlayer aquí: el singleton puede seguir vivo para
        // otras actividades. MusicPlayer.releaseShared() queda para futuras
        // gestiones de teardown (o reinicio del servicio).
        super.onDestroy()
    }
}