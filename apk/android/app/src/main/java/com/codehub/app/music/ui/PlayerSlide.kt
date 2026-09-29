package com.codehub.app.music.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.codehub.app.music.ArtworkLoader
import com.codehub.app.music.MusicPlayer
import com.codehub.app.music.MusicPlayerDuration.toClockTime
import com.codehub.app.music.MusicRegistry
import com.codehub.app.R
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track

/**
 * Slide a pantalla completa del reproductor: carátula, progreso, controles
 * y accesos a calidades/cola. Dialog de framework con animación slide_up.
 */
class PlayerSlide(
    context: Context,
    private val player: MusicPlayer,
    private val artwork: ArtworkLoader,
    private val registry: MusicRegistry,
) : Dialog(context) {

    private val main = Handler(Looper.getMainLooper())

    private lateinit var bg: LinearLayout
    private lateinit var artView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistsView: TextView
    private lateinit var currentLabel: TextView
    private lateinit var totalLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var playButton: TextView
    private lateinit var qualityButton: TextView

    private var trackId: String? = null
    private var dragging = false
    private val ticker: Runnable = object : Runnable {
        override fun run() {
            updateProgress()
            if (isShowing) main.postDelayed(this, 300)
        }
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCancelable(true)
        buildView()
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun tv(text: String, sizeSp: Float = 15f, bold: Boolean = false): TextView {
        val t = TextView(context)
        t.text = text
        t.textSize = sizeSp
        t.setTypeface(
            android.graphics.Typeface.DEFAULT,
            if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
        t.setTextColor(
            ThemeColors.of(context, android.R.attr.textColorPrimary,
                if (isDark()) Color.WHITE else 0xFF111111.toInt())
        )
        return t
    }

    private fun isDark(): Boolean {
        val mode = context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun secondaryColor(): Int =
        ThemeColors.of(context, android.R.attr.textColorSecondary, if (isDark()) 0xFFBBBBBB.toInt() else 0xFF666666.toInt())

    private fun buildView() {
        bg = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }

        val closeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleLeft = tv("", 13f)
        titleLeft.setTextColor(secondaryColor())
        titleLeft.text = "Reproducción"
        val close = tv("✕", 20f)
        close.setOnClickListener { dismiss() }
        closeRow.addView(titleLeft, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        closeRow.addView(close)

        artView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        titleView = tv("", 18f, bold = true)
        titleView.gravity = Gravity.CENTER_HORIZONTAL
        artistsView = tv("", 13f)
        artistsView.setTextColor(secondaryColor())
        artistsView.gravity = Gravity.CENTER_HORIZONTAL

        currentLabel = tv("0:00", 12f)
        currentLabel.setTextColor(secondaryColor())
        totalLabel = tv("0:00", 12f)
        totalLabel.setTextColor(secondaryColor())

        seek = SeekBar(context).apply {
            max = 0
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentLabel.text = (progress.toLong()).toClockTime()
                    totalLabel.text = player.duration().coerceAtLeast(0L).toClockTime()
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                dragging = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                dragging = false
                bar?.let { player.seekTo(it.progress.toLong()) }
            }
        })

        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        timeRow.addView(currentLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(totalLabel)

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prev = tv("⏮", 22f)
        val play = tv("▶", 40f)
        val next = tv("⏭", 22f)
        prev.setOnClickListener { player.prev() }
        play.setOnClickListener { player.toggle() }
        next.setOnClickListener { player.next() }
        playButton = play
        controls.addView(prev)
        controls.addView(play, LinearLayout.LayoutParams(dp(88), dp(72)))
        controls.addView(next)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        val queueBtn = tv("📋 Cola", 13f)
        queueBtn.setTextColor(secondaryColor())
        qualityButton = tv("🎚 Calidades", 13f)
        qualityButton.setTextColor(secondaryColor())
        val closeBt = tv("✕ Cerrar", 13f)
        closeBt.setTextColor(secondaryColor())
        queueBtn.setOnClickListener { queueDialog() }
        qualityButton.setOnClickListener { qualityDialog() }
        closeBt.setOnClickListener { dismiss() }
        actions.addView(queueBtn)
        actions.addView(qualityButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(closeBt)

        bg.addView(closeRow)
        bg.addView(artView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(260)
        ).apply { setMargins(0, dp(16), 0, dp(16)) })
        bg.addView(titleView)
        bg.addView(artistsView)
        bg.addView(seek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)
        ).apply { setMargins(dp(12), dp(8), dp(12), 0) })
        bg.addView(timeRow)
        bg.addView(controls)
        bg.addView(actions)
        setContentView(bg)
    }

    override fun show() {
        super.show()
        window ?: return
        window?.setBackgroundDrawable(ColorDrawable(
            ThemeColors.of(context, android.R.attr.colorBackground, if (isDark()) 0xFF1A1A1A.toInt() else Color.WHITE)
        ))
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.attributes?.windowAnimations = R.style.MusicSlideAnimation
        updated()
        main.post(ticker)
    }

    private fun updated() {
        val track = player.nowPlaying.value

        if (track != null) {
            if (track.id != trackId) {
                trackId = track.id
                titleView.text = track.title
                artistsView.text =
                    track.artists.joinToString(", ") { it.name }.ifBlank { track.album?.title ?: "" }
                artView.setImageDrawable(null)
                artwork.load(track.cover ?: track.background) { bmp ->
                    artView.setImageBitmap(bmp)
                }
            }
            playButton.text = if (player.isPlaying.value) "⏸" else "▶"
            updateProgress()
        }
    }

    private fun updateProgress() {
        val track = player.nowPlaying.value ?: return
        if (track.id != trackId) updated()
        val dur = player.duration()
        if (dur > 0) {
            if (!dragging) {
                seek.max = dur.toInt()
                val pos = player.position().coerceIn(0L, dur)
                seek.progress = pos.toInt()
                currentLabel.text = pos.toClockTime()
                totalLabel.text = dur.toClockTime()
            }
        }
        playButton.text = if (player.isPlaying.value) "⏸" else "▶"
    }

    private fun qualityDialog() {
        val track = player.nowPlaying.value ?: return
        val server = player.servers[track.id]?.getOrNull() ?: run {
            Toast.makeText(context, "Cargando fuentes…", Toast.LENGTH_SHORT).show()
            return
        }
        val sources = server.sources
        val labels = sources.mapIndexed { index, source ->
            val kind = when ((source as? Streamable.Source.Http)?.type) {
                Streamable.SourceType.HLS -> "HLS"
                Streamable.SourceType.DASH -> "DASH"
                else -> "Progresivo"
            }
            "Fuente ${index + 1} · $kind · calidad ${source.quality}${source.title?.let { " · $it" } ?: ""}"
        }
        AlertDialog.Builder(context)
            .setTitle("Calidades de ${track.title}")
            .setItems(labels.toTypedArray()) { _, which ->
                player.selectSource(track, which)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun queueDialog() {
        val queue = player.queue.value
        if (queue.isEmpty()) return
        val labels = queue.mapIndexed { index, t ->
            "${index + 1}. ${t.title} — ${t.artists.joinToString(", ") { it.name }}"
        }
        AlertDialog.Builder(context)
            .setTitle("Cola (${queue.size})")
            .setItems(labels.toTypedArray()) { _, which ->
                player.exo.seekToDefaultPosition(which)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }
}