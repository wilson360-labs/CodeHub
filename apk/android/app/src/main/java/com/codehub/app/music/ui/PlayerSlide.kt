package com.codehub.app.music.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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
import com.google.android.material.progressindicator.LinearProgressIndicator
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track

/**
 * Slide a pantalla completa del reproductor, estilo Echo Player: port de la
 * estetica de echo-nightly (fragment_player + item_player_controls) en UI
 * programatica sin dependencias nuevas:
 *  - fondo con artwork difuso + pan lento (Ken Burns) + scrim en gradiente
 *  - colores dinamicos derivados del artwork (PlayerColors: bg/accent)
 *  - cover grande redondeada, titulo marquee, like persistente,
 *  - seekbar con buffer, shuffle/repeat, sleep timer y ecualizador.
 */
class PlayerSlide(
    context: Context,
    private val player: MusicPlayer,
    private val artwork: ArtworkLoader,
    private val registry: MusicRegistry,
    private val extName: String? = null,
) : Dialog(context) {

    private val main = Handler(Looper.getMainLooper())
    private val dm = context.resources.displayMetrics

    private lateinit var root: LinearLayout
    private lateinit var bgImage: ImageView
    private lateinit var artView: ImageView
    private lateinit var titleView: TextView
    private lateinit var artistsView: TextView
    private lateinit var extView: TextView
    private lateinit var currentLabel: TextView
    private lateinit var totalLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var bufferBar: LinearProgressIndicator
    private lateinit var playButton: ImageView
    private lateinit var likeButton: ImageView
    private lateinit var shuffleButton: ImageView
    private lateinit var repeatButton: ImageView
    private lateinit var subtitle: TextView
    private lateinit var sleepLabel: TextView

    private var trackId: String? = null
    private var dragging = false
    private var sleepAt = 0L
    private val sleepRunnable = Runnable {
        player.exo.pause()
        Toast.makeText(context, "Temporizador: reproducción en pausa", Toast.LENGTH_SHORT).show()
    }
    private var equalizer: Equalizer? = null

    private var panScale: ObjectAnimator? = null
    private var panPan: ObjectAnimator? = null
    private var panScaleY: ObjectAnimator? = null

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

    private fun dp(v: Int) = (v * dm.density).toInt()
    private fun dpF(v: Float) = (v * dm.density).toInt()

    private fun tv(
        text: String,
        sizeSp: Float = 15f,
        bold: Boolean = false,
        color: Int = MusicTheme.TEXT,
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = sizeSp
        setTypeface(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(color)
    }

    private fun icon(res: Int, sizeDp: Int, tint: Int): ImageView =
        MusicTheme.icon(context, res, sizeDp, tint)

    private fun buildView() {
        // Fondo dinámico
        bgImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val scrim = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x99000000.toInt(), 0xE6080808.toInt())
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(14), dp(20), dp(26))
        }

        // Cabecera: "Reproduciendo desde <ext>" + like + cerrar
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val extCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        extCol.addView(tv("Reproduciendo desde", 12f, color = MusicTheme.MUTED))
        extView = tv(extName ?: "Extensión", 13f, bold = true, color = MusicTheme.ACCENT)
        extCol.addView(extView)

        likeButton = MusicTheme.iconCircle(context, R.drawable.ic_music_like_border, 28)
        likeButton.setOnClickListener { toggleLike() }
        val close = MusicTheme.iconCircle(context, R.drawable.ic_music_close, 24)
        close.setOnClickListener { dismiss() }

        header.addView(extCol)
        header.addView(likeButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))

        // Cover
        val placeholder = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(24f).toFloat()
            setColor(0xFF202027.toInt())
        }
        artView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = placeholder
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = dpF(12f).toFloat()
            translationZ = dpF(10f).toFloat()
        }
        val cover = minOf(
            dm.widthPixels - dp(40),
            (dm.heightPixels * 0.36f).toInt(),
            (dm.heightPixels - dp(230)).coerceAtLeast(0),
        ).coerceAtLeast(1)

        // Título / artista
        titleView = tv("", 21f, bold = true)
        titleView.gravity = Gravity.CENTER_HORIZONTAL
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        titleView.isSelected = true
        titleView.marqueeRepeatLimit = -1
        artistsView = tv("", 14f, color = MusicTheme.MUTED)
        artistsView.gravity = Gravity.CENTER_HORIZONTAL
        artistsView.maxLines = 2
        artistsView.ellipsize = android.text.TextUtils.TruncateAt.END

        val fill = View(context)
        fill.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )

        // Progreso: buffer + seek + tiempos
        val progressBox = FrameLayout(context)
        bufferBar = LinearProgressIndicator(context).apply {
            max = 1000
            progress = 0
            trackThickness = dp(2)
            setIndicatorColor(0x73000000)
            setTrackColor(android.graphics.Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(2)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(18)
                marginEnd = dp(18)
            }
        }
        seek = SeekBar(context).apply {
            max = 0
            progressTintList = android.content.res.ColorStateList.valueOf(MusicTheme.ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(MusicTheme.ACCENT)
            progressBackgroundTintList =
                android.content.res.ColorStateList.valueOf(MusicTheme.SURFACE_2)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
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
        progressBox.addView(bufferBar)
        progressBox.addView(seek)

        currentLabel = tv("0:00", 11f, color = MusicTheme.MUTED)
        totalLabel = tv("0:00", 11f, color = MusicTheme.MUTED)
        totalLabel.gravity = Gravity.END
        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timeRow.addView(currentLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeRow.addView(totalLabel)

        // Controles: shuffle | prev | play | next | repeat
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        shuffleButton = MusicTheme.iconCircle(
            context, R.drawable.ic_music_shuffle, 26, tint = MusicTheme.MUTED
        )
        shuffleButton.setOnClickListener {
            player.toggleShuffle()
            updateModes()
        }
        val prev = MusicTheme.icon(context, R.drawable.ic_music_prev, 40, MusicTheme.TEXT)
        prev.setOnClickListener { player.prev() }
        playButton = MusicTheme.iconCircle(
            context, R.drawable.ic_music_play, 52,
            bg = MusicTheme.ACCENT, tint = Color.WHITE
        )
        playButton.setOnClickListener { player.toggle() }
        val next = MusicTheme.icon(context, R.drawable.ic_music_next, 40, MusicTheme.TEXT)
        next.setOnClickListener { player.next() }
        repeatButton = MusicTheme.iconCircle(
            context, R.drawable.ic_music_repeat, 26, tint = MusicTheme.MUTED
        )
        repeatButton.setOnClickListener {
            player.cycleRepeat()
            updateModes()
        }

        val order = listOf<Pair<View, Int>>(
            shuffleButton to 52,
            prev to 56,
            playButton to 76,
            next to 56,
            repeatButton to 52,
        )
        order.forEachIndexed { index, (view, w) ->
            controls.addView(view, LinearLayout.LayoutParams(dp(w), dp(w)))
            if (index == 2) { /* play más separado */ }
        }

        // Subtítulo: calidad del stream actual
        subtitle = tv("", 12f, color = MusicTheme.MUTED)
        subtitle.gravity = Gravity.CENTER_HORIZONTAL
        subtitle.maxLines = 2
        subtitle.ellipsize = android.text.TextUtils.TruncateAt.END

        // Acciones: Cola · Efectos · Temporizador · Calidades
        val actions = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        sleepLabel = tv("", 12f, color = MusicTheme.MUTED)
        val cola = actionPill("Cola", R.drawable.ic_music_queue)
        cola.setOnClickListener { queueDialog() }
        val eq = actionPill("Efectos", R.drawable.ic_music_eq)
        eq.setOnClickListener { effectsDialog() }
        val sleep = actionPill("Dormir", R.drawable.ic_music_sleep)
        sleep.setOnClickListener { sleepDialog() }
        val cal = actionPill("Calidad", R.drawable.ic_music_quality)
        cal.setOnClickListener { qualityDialog() }
        listOf(cola, eq, sleep, cal).forEach { b ->
            actionsRow.addView(b)
            val lp = b.layoutParams as? LinearLayout.LayoutParams
            lp?.rightMargin = dp(8)
        }
        actions.addView(actionsRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val artLp = LinearLayout.LayoutParams(cover, cover)
        artLp.topMargin = dp(18)
        artLp.bottomMargin = dp(10)
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(artView, artLp)
        root.addView(titleView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(artistsView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })
        root.addView(fill)

        root.addView(progressBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(30)
        ).apply { topMargin = dp(8) })
        root.addView(timeRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(controls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
        root.addView(subtitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        root.addView(sleepLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })
        root.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        val frame = FrameLayout(context).apply {
            addView(bgImage)
            addView(scrim)
            addView(root, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        setContentView(frame)
    }

    private fun actionPill(text: String, res: Int): LinearLayout {
        val btn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val ic = icon(res, 16, MusicTheme.MUTED)
            addView(ic)
            val label = tv(text, 12f, color = MusicTheme.MUTED)
            label.setPadding(dp(4), 0, 0, 0)
            addView(label)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = MusicTheme.ripple(
                context, MusicTheme.rounded(context, MusicTheme.SURFACE_2, 20)
            )
        }
        return btn
    }

    override fun show() {
        super.show()
        window ?: return
        window?.setBackgroundDrawable(ColorDrawable(0xFF080810.toInt()))
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setWindowAnimations(R.style.MusicSlideAnimation)
        updated()
        startPan()
        main.post(ticker)
    }

    override fun dismiss() {
        stopPan()
        main.removeCallbacks(ticker)
        main.removeCallbacks(sleepRunnable)
        equalizer?.release()
        equalizer = null
        super.dismiss()
    }

    private fun startPan() {
        stopPan()
        panScale = ObjectAnimator.ofFloat(bgImage, View.SCALE_X, 1f, 1.18f).apply {
            duration = 24000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        panScaleY = ObjectAnimator.ofFloat(bgImage, View.SCALE_Y, 1f, 1.18f).apply {
            duration = 24000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        panPan = ObjectAnimator.ofFloat(bgImage, View.TRANSLATION_X, 0f, dpF(-24f).toFloat()).apply {
            duration = 32000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopPan() {
        panScale?.cancel()
        panScale = null
        panScaleY?.cancel()
        panScaleY = null
        panPan?.cancel()
        panPan = null
    }

    private fun updated() {
        val track = player.nowPlaying.value
        sleepAt = registry.globalPrefs.getLong("sleep_at", 0L)
        if (track != null) {
            if (track.id != trackId) {
                trackId = track.id
                titleView.text = track.title
                artistsView.text =
                    track.artists.joinToString(", ") { it.name }.ifBlank { track.album?.title ?: "" }
                artView.setImageDrawable(null)
                artwork.load(track.cover ?: track.background) { bmp ->
                    if (trackId == track.id && bmp != null) {
                        artView.setImageBitmap(bmp)
                        applyPalette(bmp)
                    }
                }
            }
            updatePlayIcon()
            updateLike()
            updateModes()
            updateSubtitle(track)
            updateSleepLabel()
            updateProgress()
        }
    }

    private fun updatePlayIcon() {
        val playing = player.isPlaying.value
        playButton.setImageResource(if (playing) R.drawable.ic_music_pause else R.drawable.ic_music_play)
    }

    private fun updateLike() {
        val liked = isLiked()
        likeButton.setImageResource(
            if (liked) R.drawable.ic_music_like else R.drawable.ic_music_like_border
        )
        likeButton.setColorFilter(
            if (liked) MusicTheme.ACCENT else MusicTheme.MUTED
        )
    }

    private fun isLiked(): Boolean {
        val id = trackId ?: return false
        val likes = registry.globalPrefs.getStringSet("liked_tracks", emptySet()) ?: return false
        return likes.contains(id)
    }

    private fun toggleLike() {
        val id = trackId ?: return
        val prefs = registry.globalPrefs
        val set = (prefs.getStringSet("liked_tracks", emptySet()) ?: emptySet()).toMutableSet()
        if (set.contains(id)) set.remove(id) else set.add(id)
        prefs.edit().putStringSet("liked_tracks", set).apply()
        updateLike()
    }

    private fun updateModes() {
        shuffleButton.setColorFilter(if (player.shuffle.value) MusicTheme.ACCENT else MusicTheme.MUTED)
        repeatButton.setImageResource(
            if (player.repeat.value == androidx.media3.common.Player.REPEAT_MODE_ONE)
                R.drawable.ic_music_repeat_one else R.drawable.ic_music_repeat
        )
        repeatButton.setColorFilter(
            if (player.repeat.value != androidx.media3.common.Player.REPEAT_MODE_OFF)
                MusicTheme.ACCENT else MusicTheme.MUTED
        )
    }

    private fun updateSubtitle(track: Track) {
        val server = player.servers[track.id]?.getOrNull()
        val label = when (val source = server?.sources?.firstOrNull()) {
            is Streamable.Source.Http -> {
                val kind = when (source.type) {
                    Streamable.SourceType.HLS -> "HLS"
                    Streamable.SourceType.DASH -> "DASH"
                    else -> "Progresivo"
                }
                "$kind · calidad ${source.quality}"
            }
            else -> extName ?: "Reproduciendo"
        }
        subtitle.text = "$label · ${track.title}"
    }

    private fun updateSleepLabel() {
        val left = sleepAt - System.currentTimeMillis()
        sleepLabel.text = if (left > 0) "Dormir en ${
            ((left / 1000 + 59) / 60).toInt()
        } min" else ""
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
                val buffered = player.exo.bufferedPosition
                bufferBar.progress = if (dur > 0) (1000.0 * buffered.coerceIn(0L, dur) / dur).toInt() else 0
            }
        }
        bufferBar.visibility = if (player.isBuffering.value) View.VISIBLE else View.GONE
        updatePlayIcon()
    }

    private fun applyPalette(bitmap: Bitmap) {
        val accent = vibrantColor(bitmap)
        val bmp = Blur.blur(context, bitmap)
        titleView.setTextColor(blend(accent, Color.WHITE, 0.72f))
        bgImage.setImageBitmap(bmp)
        bgImage.setColorFilter(blend(accent, Color.BLACK, 0.86f))
    }

    /** Mezcla [c1] con [c2] por [t] (0=100% c1, 1=100% c2). */
    private fun blend(c1: Int, c2: Int, t: Float): Int {
        val r = (Color.red(c1) * (1f - t) + Color.red(c2) * t).toInt()
        val g = (Color.green(c1) * (1f - t) + Color.green(c2) * t).toInt()
        val b = (Color.blue(c1) * (1f - t) + Color.blue(c2) * t).toInt()
        return Color.rgb(r.coerceAtMost(255), g.coerceAtMost(255), b.coerceAtMost(255))
    }

    // ------------------------------------------------------------------
    // Diálogos
    // ------------------------------------------------------------------

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

    private fun sleepDialog() {
        val options = listOf<Pair<String, Long>>(
            "Apagar temporizador" to 0L,
            "10 minutos" to 10 * 60_000L,
            "15 minutos" to 15 * 60_000L,
            "30 minutos" to 30 * 60_000L,
            "45 minutos" to 45 * 60_000L,
        )
        val labels = options.map { it.first }
        AlertDialog.Builder(context)
            .setTitle("Temporizador para dormir")
            .setItems(labels.toTypedArray()) { _, which ->
                val minutes = options[which].second
                main.removeCallbacks(sleepRunnable)
                if (minutes > 0) {
                    sleepAt = System.currentTimeMillis() + minutes
                    main.postDelayed(sleepRunnable, minutes)
                } else {
                    sleepAt = 0L
                }
                registry.globalPrefs.edit().putLong("sleep_at", sleepAt).apply()
                updateSleepLabel()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun effectsDialog() {
        val session = player.audioSessionId
        if (session == 0) {
            Toast.makeText(context, "Inicia una reproducción para usar el ecualizador", Toast.LENGTH_SHORT).show()
            return
        }
        val eq = runCatching { Equalizer(0, session) }.getOrNull()
        if (eq == null) {
            Toast.makeText(context, "Ecualizador no disponible en este dispositivo", Toast.LENGTH_SHORT).show()
            return
        }
        equalizer?.release()
        equalizer = eq
        val names = mutableListOf<String>()
        val count = runCatching { eq.numberOfPresets.toInt() }.getOrDefault(0)
        for (i in 0 until count) {
            runCatching { names += eq.getPresetName(i.toShort()) }
        }
        val labels = (listOf("Plano (sin efectos)") + names).toTypedArray()
        AlertDialog.Builder(context)
            .setTitle("Efectos de audio")
            .setItems(labels) { _, which ->
                runCatching {
                    if (which == 0) {
                        eq.enabled = false
                    } else {
                        eq.enabled = true
                        eq.usePreset((which - 1).toShort())
                    }
                }
            }
            .setOnDismissListener { eq.release(); equalizer = null }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Paleta (port de PlayerColors: background oscuro + accent vibrante)
    // ------------------------------------------------------------------

    private fun vibrantColor(bitmap: Bitmap): Int {
        val scaled = runCatching { Bitmap.createScaledBitmap(bitmap, 24, 24, true) }
            .getOrNull() ?: return MusicTheme.ACCENT
        var best = MusicTheme.ACCENT
        var bestScore = -1f
        for (x in 0 until 24) {
            for (y in 0 until 24) {
                val c = scaled.getPixel(x, y)
                val r = Color.red(c) / 255f
                val g = Color.green(c) / 255f
                val b = Color.blue(c) / 255f
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                val l = (max + min) / 2f
                val s = if (max == min) 0f else (max - min) / (1f - kotlin.math.abs(2f * l - 1f))
                val score = s * l
                if (score > bestScore) {
                    bestScore = score
                    best = c
                }
            }
        }
        return best
    }
}

/** Blur barato por downscale/upscale (sin RenderScript/coil3). */
object Blur {
    fun blur(context: Context, src: Bitmap): Bitmap {
        val maxW = 96f
        val scale = maxOf(1f, src.width / maxW)
        val w = (src.width / scale).toInt().coerceAtLeast(1)
        val h = (src.height / scale).toInt().coerceAtLeast(1)
        runCatching { return Bitmap.createScaledBitmap(src, w, h, true) }
        return src
    }
}