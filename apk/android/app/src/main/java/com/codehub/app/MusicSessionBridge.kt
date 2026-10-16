package com.codehub.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.codehub.app.music.MusicPlaybackService
import com.codehub.app.music.MusicPlayer
import dev.brahmkshatriya.echo.EchoBoot
import dev.brahmkshatriya.echo.playback.PlayerService
import org.json.JSONObject
import java.util.concurrent.Executor

/**
 * Control de música flotante (solo dentro del APK).
 *
 * Enlaza la sesión de medios del reproductor activo (Echo Nightly y, si está
 * en uso, el player "slide" de CodeHub) con la WebView y empuja el estado a
 * `window.__chMusicOnState(...)`; los botones del control web vuelven por
 * `CodeHubNative.music*` (ver [CodeHubBridge]).
 *
 * Política de consumo: **solo se enlaza con evidencia** de que hay música
 * (el usuario abrió el reproductor desde CodeHub, o el sistema reporta audio
 * activo, o ya hay un controller en vivo). Así nunca se arranca el servicio de
 * Echo en vacío solo por navegar por la web.
 *
 * El enlace se mantiene mientras viva el proceso (no se crea/destruye
 * ExoPlayer en cada ida y vuelta a primer plano) y la WebView solo se usa
 * mientras está adjunta: el estado se deja de empujar en `onPause` para no
 * gastar batería.
 */
@OptIn(UnstableApi::class)
object MusicSessionBridge {

    private const val TAG = "MusicSessionBridge"

    private val main = Handler(Looper.getMainLooper())
    private val direct = Executor { task -> task.run() }

    private var app: Application? = null
    private var webView: WebView? = null
    private var userOpened = false

    private val controllers = ArrayList<MediaController>()
    private val bound = HashSet<String>()
    private val pushRunnable = Runnable { pushState() }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            schedulePush()
        }
    }

    /** La WebView vuelve a primer plano: vuelve a escuchar y refresca. */
    @JvmStatic
    fun attach(application: Application, view: WebView?) {
        main.post {
            app = application
            webView = view
            ensureConnected()
            schedulePush()
        }
    }

    /** La WebView sale de pantalla: no hay nada que empujar (batería). */
    @JvmStatic
    fun detach() {
        main.post {
            webView = null
            main.removeCallbacks(pushRunnable)
        }
    }

    /** El usuario abrió el reproductor desde CodeHub → evidencia para enlazar. */
    @JvmStatic
    fun onPlayerOpened() {
        main.post {
            userOpened = true
            ensureConnected()
            schedulePush()
        }
    }

    /** La página pide el estado actual (se cargó/recargó la WebView). */
    @JvmStatic
    fun requestState() {
        main.post {
            ensureConnected()
            schedulePush()
        }
    }

    @JvmStatic
    fun togglePlay() {
        perform { if (it.isPlaying) it.pause() else it.play() }
    }

    @JvmStatic
    fun next() {
        perform {
            if (it.hasNextMediaItem()) it.seekToNextMediaItem() else it.seekTo(0L)
        }
    }

    @JvmStatic
    fun prev() {
        perform {
            if (it.currentPosition > 3000L || !it.hasPreviousMediaItem()) it.seekTo(0L)
            else it.seekToPreviousMediaItem()
        }
    }

    // ── internos ───────────────────────────────────────────────────

    private fun perform(action: (MediaController) -> Unit) {
        main.post {
            val target = active()
            if (target != null) {
                try {
                    action(target)
                } catch (t: Throwable) {
                    Log.w(TAG, "control de reproducción fallido: $t")
                }
                schedulePush()
            }
        }
    }

    /** Enlaza solo los servicios con evidencia de reproducción. */
    private fun ensureConnected() {
        val ctx = app ?: return
        if (!userOpened && controllers.isEmpty() && !isMusicActive()) return
        for (target in targets(ctx)) {
            val key = target.flattenToString()
            if (bound.contains(key)) continue
            bound.add(key)
            connect(ctx, target, key)
        }
    }

    /** Echo Nightly siempre; el player slide solo si ya está en uso. */
    private fun targets(ctx: Application): List<ComponentName> {
        val list = ArrayList<ComponentName>()
        // Koin de Echo se enciende en CodeHubApp.onCreate; si falló, enlazar su
        // servicio daría un onCreate sin dependencias → no se enlaza nunca.
        if (EchoBoot.isReady()) {
            list.add(ComponentName(ctx, PlayerService::class.java))
        }
        if (MusicPlayer.peek() != null) {
            list.add(ComponentName(ctx, MusicPlaybackService::class.java))
        }
        return list
    }

    private fun connect(ctx: Application, target: ComponentName, key: String) {
        try {
            val token = SessionToken(ctx, target)
            val future = MediaController.Builder(ctx, token).buildAsync()
            future.addListener(Runnable {
                val ctrl = runCatching { future.get() }.getOrNull()
                if (ctrl == null) {
                    Log.w(TAG, "sin sesión de medios para $key")
                    main.post { bound.remove(key) }
                } else {
                    main.post { onConnected(ctrl) }
                }
            }, direct)
        } catch (t: Throwable) {
            Log.w(TAG, "no se pudo enlazar $key: $t")
            bound.remove(key)
        }
    }

    private fun onConnected(ctrl: MediaController) {
        if (controllers.any { it === ctrl }) return
        controllers.add(ctrl)
        ctrl.addListener(playerListener)
        Log.d(TAG, "sesión enlazada (${controllers.size})")
        schedulePush()
    }

    private fun isMusicActive(): Boolean {
        val ctx = app ?: return false
        return try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.isMusicActive == true
        } catch (t: Throwable) {
            false
        }
    }

    /** Reproductor con pista cargada (prefiere el que esté sonando). */
    private fun active(): MediaController? {
        val withTrack = controllers.filter { it.currentMediaItem != null }
        if (withTrack.isEmpty()) return null
        return withTrack.firstOrNull { it.isPlaying } ?: withTrack.first()
    }

    private fun schedulePush() {
        main.removeCallbacks(pushRunnable)
        main.post(pushRunnable)
    }

    private fun pushState() {
        val wv = webView ?: return
        val c = active()
        val item = c?.currentMediaItem
        if (c == null || item == null) {
            send(wv, false, false, "", "", 0L, -1L)
            return
        }
        val md = item.mediaMetadata
        val title = md.title?.toString().orEmpty()
        val artist = md.artist?.toString().orEmpty()
        val durRaw = c.duration
        val dur = if (durRaw < 0L) -1L else durRaw
        send(wv, true, c.isPlaying, title, artist, c.currentPosition, dur)
    }

    private fun send(
        wv: WebView,
        hasTrack: Boolean,
        playing: Boolean,
        title: String,
        artist: String,
        positionMs: Long,
        durationMs: Long
    ) {
        val json = try {
            JSONObject()
                .put("hasTrack", hasTrack)
                .put("playing", playing)
                .put("title", title)
                .put("artist", artist)
                .put("positionMs", positionMs)
                .put("durationMs", durationMs)
                .toString()
        } catch (t: Throwable) {
            return
        }
        val js = "try{window.__chMusicOnState&&window.__chMusicOnState($json)}catch(e){}"
        try {
            wv.evaluateJavascript(js, null)
        } catch (t: Throwable) {
            Log.w(TAG, "no se pudo empujar el estado: $t")
        }
    }
}
