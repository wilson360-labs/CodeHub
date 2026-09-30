package com.codehub.app.music

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.codehub.app.R
import com.codehub.app.music.ui.MusicPlayerActivity


/**
 * Servicio de reproducción en segundo plano del player de CodeHub (port de
 * echo-nightly `PlayerService`, sin Android Auto/Cache en v1):
 *
 *  - MediaSession (media3-session) sobre el [MusicPlayer] singleton, lo que
 *    da notificación de medios, control desde pantalla de bloqueo y el
 *    MediaButtonReceiver para botones/headset.
 *  - La notificación se muestra automáticamente mientras haya reproducción
 *    y el servicio se mantiene en foreground (foregroundServiceType
 *    "mediaPlayback"); al quedarse idle se detiene solo.
 *  - El reproductor NO se libera cuando la Activity muere: la música sigue
 *    sonando en segundo plano hasta que el usuario la detenga.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = MusicPlayer.shared(applicationContext)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MusicPlayerActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setSmallIconResourceId(R.drawable.ic_music_play)
            .setPauseIconResourceId(R.drawable.ic_music_pause)
            .setPlayIconResourceId(R.drawable.ic_music_play)
            .setNextIconResourceId(R.drawable.ic_music_next)
            .setPreviousIconResourceId(R.drawable.ic_music_prev)
            .setContentIntentProvider { pendingIntent }
            .build()

        session = MediaSession.Builder(this, player.exo)
            .setSessionActivity(pendingIntent)
            .build()

        setMediaNotificationProvider(notificationProvider)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
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