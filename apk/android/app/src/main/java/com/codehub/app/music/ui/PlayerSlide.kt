package com.codehub.app.music.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.codehub.app.R
import com.codehub.app.music.ArtworkLoader
import com.codehub.app.music.MusicPlayer
import com.codehub.app.music.MusicPlayerDuration.toClockTime
import com.codehub.app.music.MusicRegistry
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track

/**
 * Slide a pantalla completa del reproductor, estilo reproductor moderno:
 * carátula grande redondeada, fondo con degradado derivado del artwork,
 * progreso prominente y controles grandes. Dialog de framework con la
 * animación slide_up.
 */
class PlayerSlide(
    context: Context,
    private val player: MusicPlayer,
    private val artwork: ArtworkLoader,
    private val registry: MusicRegistry,
) : Dialog(context) {

    private val main = Handler(Looper.getMainLooper())
    private val dm = context.resources.displayMetrics

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
    private var artColor = 0

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
        applyBackground()
    }

    private fun dp(v: Int) = (v * dm.density).toInt()
    private fun dpF(v: Float) = (v * dm.density).toInt()

    private fun isDark(): Boolean {
        val mode = context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    private fun primary(): Int =
        ThemeColors.of(context, android.R.attr.textColorPrimary, if (isDark()) Color.WHITE else 0xFF111111.toInt())

    private fun secondary(): Int =
        ThemeColors.of(context, android.R.attr.textColorSecondary, if (isDark()) 0xFFBBBBBB.toInt() else 0xFF666666.toInt())

    private fun accent(): Int =
        ThemeColors.of(context, android.R.attr.colorAccent, 0xFF448AFF.toInt())

    private fun background(): Int =
        ThemeColors.of(context, android.R.attr.colorBackground, if (isDark()) 0xFF0F0F12.toInt() else Color.WHITE)

    private fun tv(
        text: String,
        sizeSp: Float = 15f,
        bold: Boolean = false,
        color: Int = primary(),
    ): TextView {
        val t = TextView(context)
        t.text = text
        t.textSize = sizeSp
        t.setTypeface(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        t.setTextColor(color)
        return t
    }

    private fun buildView() {
        bg = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(14), dp(20), dp(32))
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerTitle = tv("Reproducción", 13f, color = secondary())
        val close = tv("✕", 20f)
        close.setPadding(dp(8), dp(4), dp(4), dp(4))
        close.setOnClickListener { dismiss() }
        header.addView(headerTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close)

        val placeholder = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(22f).toFloat()
            setColor(if (isDark()) 0xFF202027.toInt() else 0xFFE3E3E8.toInt())
        }
        artView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = placeholder
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setImageDrawable(null)
        }
        val cover = minOf(dm.widthPixels - dp(40), (dm.heightPixels * 0.52f).toInt())

        titleView = tv("", 22f, bold = true)
        titleView.gravity = Gravity.CENTER_HORIZONTAL
        titleView.maxLines = 2
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        artistsView = tv("", 15f, color = secondary())
        artistsView.gravity = Gravity.CENTER_HORIZONTAL
        artistsView.maxLines = 2
        artistsView.ellipsize = android.text.TextUtils.TruncateAt.END

        val fill = View(context)
        fill.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )

        seek = SeekBar(context).apply {
            max = 0
            setPadding(dpF(4f), 0, dpF(4f), 0)
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

        currentLabel = tv("0:00", 12f, color = secondary())
        totalLabel = tv("0:00", 12f, color = secondary())
        totalLabel.gravity = Gravity.END

        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timeRow.addView(currentLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(totalLabel)

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prev = tv("⏮", 32f)
        prev.gravity = Gravity.CENTER
        prev.setPadding(dp(16), dp(12), dp(16), dp(12))
        prev.setOnClickListener { player.prev() }
        val play = tv("▶", 44f, color = Color.WHITE)
        play.gravity = Gravity.CENTER
        play.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(accent())
        }
        play.setOnClickListener { player.toggle() }
        val next = tv("⏭", 32f)
        next.gravity = Gravity.CENTER
        next.setPadding(dp(16), dp(12), dp(16), dp(12))
        next.setOnClickListener { player.next() }
        playButton = play
        controls.addView(prev)
        controls.addView(play, LinearLayout.LayoutParams(dp(78), dp(78)))
        controls.addView(next)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val queueBtn = tv("📋 Cola", 13f, color = secondary())
        queueBtn.setOnClickListener { queueDialog() }
        qualityButton = tv("🎚 Calidades", 13f, color = secondary())
        qualityButton.setOnClickListener { qualityDialog() }
        val closeBt = tv("✕ Cerrar", 13f, color = secondary())
        closeBt.setOnClickListener { dismiss() }
        actions.addView(queueBtn)
        actions.addView(qualityButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(closeBt)

        val artLp = LinearLayout.LayoutParams(cover, cover)
        artLp.topMargin = dp(20)
        artLp.bottomMargin = dp(14)
        bg.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        bg.addView(artView, artLp)
        val titleLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        titleLp.topMargin = dp(4)
        bg.addView(titleView, titleLp)
        val artistsLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        artistsLp.topMargin = dp(2)
        bg.addView(artistsView, artistsLp)
        bg.addView(fill)

        val seekLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        seekLp.topMargin = dp(6)
        bg.addView(seek, seekLp)
        bg.addView(timeRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val controlsLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        controlsLp.topMargin = dp(14)
        bg.addView(controls, controlsLp)

        val actionsLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        actionsLp.topMargin = dp(18)
        bg.addView(actions, actionsLp)

        setContentView(bg)
    }

    override fun show() {
        super.show()
        window ?: return
        window?.setBackgroundDrawable(ColorDrawable(background()))
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setWindowAnimations(R.style.MusicSlideAnimation)
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
                    artColor = bmp?.let(::dominantColor) ?: 0
                    applyBackground()
                    if (trackId == track.id) artView.setImageBitmap(bmp)
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

    private fun dominantColor(bitmap: Bitmap): Int {
        val scaled = runCatching { Bitmap.createScaledBitmap(bitmap, 12, 12, true) }
            .getOrNull() ?: return primary()
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (x in 0 until 12) {
            for (y in 0 until 12) {
                val c = scaled.getPixel(x, y)
                r += Color.red(c)
                g += Color.green(c)
                b += Color.blue(c)
                n++
            }
        }
        if (n > 0) {
            r /= n
            g /= n
            b /= n
        }
        return Color.rgb(r, g, b)
    }

    private fun darken(color: Int, f: Float): Int {
        fun ch(c: Int) = (c * (1f - f)).toInt()
        return Color.rgb(ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(c: Int, d: Int) = (c + (d - c) * t).toInt()
        return Color.rgb(
            ch(Color.red(a), Color.red(b)),
            ch(Color.green(a), Color.green(b)),
            ch(Color.blue(a), Color.blue(b))
        )
    }

    private fun applyBackground() {
        val base = background()
        val top = if (artColor == 0) base
        else blend(
            darken(artColor, if (isDark()) 0.7f else 0.35f),
            base,
            if (isDark()) 0.55f else 0.35f
        )
        bg.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, base)
        )
    }
}