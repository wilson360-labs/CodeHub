package com.codehub.app.music

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.codehub.app.R

/**
 * Widget (4x1) del reproductor de CodeHub: controles prev/play-next sobre el
 * [MusicPlayer] singleton, con título y artista del track actual. El refresco
 * se dispara desde [MusicPlayer] (track cambio / play-pausa) y desde aquí
 * cuando se pulsa un botón.
 */
class MusicWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_TOGGLE = "com.codehub.app.music.WIDGET_TOGGLE"
        private const val ACTION_PREV = "com.codehub.app.music.WIDGET_PREV"
        private const val ACTION_NEXT = "com.codehub.app.music.WIDGET_NEXT"

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val provider = ComponentName(context, MusicWidget::class.java)
            val ids = manager.getAppWidgetIds(provider)
            if (ids.isEmpty()) return
            val player = runCatching { MusicPlayer.shared(context) }.getOrNull() ?: return

            val views = RemoteViews(context.packageName, R.layout.music_widget)
            val track = player.nowPlaying.value
            val playing = player.isPlaying.value
            views.setTextViewText(R.id.w_title, track?.title ?: "CodeHub Music")
            val sub = track?.let {
                it.artists.joinToString(", ") { a -> a.name }.ifBlank { it.album?.title ?: "" }
            } ?: "Sin reproducción"
            views.setTextViewText(R.id.w_subtitle, sub)
            views.setImageViewResource(
                R.id.w_play,
                if (playing) R.drawable.ic_music_pause else R.drawable.ic_music_play
            )
            views.setOnClickPendingIntent(R.id.w_prev, pending(context, ACTION_PREV, 1))
            views.setOnClickPendingIntent(R.id.w_play, pending(context, ACTION_TOGGLE, 2))
            views.setOnClickPendingIntent(R.id.w_next, pending(context, ACTION_NEXT, 3))
            manager.updateAppWidget(ids, views)
        }

        private fun pending(context: Context, action: String, code: Int): PendingIntent {
            val intent = Intent(context, MusicWidget::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        val player = runCatching { MusicPlayer.shared(context) }.getOrNull() ?: return
        when (action) {
            ACTION_TOGGLE -> player.toggle()
            ACTION_PREV -> player.prev()
            ACTION_NEXT -> player.next()
        }
        refresh(context)
    }
}