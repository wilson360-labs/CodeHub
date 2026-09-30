package com.codehub.app.music.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
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
 *  - fondo con artwork difuso + scrim en gradiente dinamico del artwork
 *  - colores dinamicos derivados del artwork (PlayerColors accent)
 *  - cover grande, texto alineado a la izquierda, play en circulo blanco,
 *    barra inferior de acciones (estilo Now Playing de Spotify)
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

    private lateinit var dlIcon: ImageView
    private lateinit var dlLabel: TextView
    private var dlState: Boolean? = null

    private var trackId: String? = null
    private var dragging = false
    private var sleepAt = 0L
    private val sleepRunnable = Runnable {
        player.exo.pause()
        Toast.makeText(context, "Temporizador: reproducción en pausa", Toast.LENGTH_SHORT).show()
    }
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudness: LoudnessEnhancer? = null

    private val eqPrefs = context.getSharedPreferences("music_global", Context.MODE_PRIVATE)

    private lateinit var scrimDrawable: GradientDrawable

    private val ticker: Runnable = object : Runnable {
        override fun run() {
            updateProgress()
            refreshDownloadChip()
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
        // Fondo dinámico (artwork difuso, sin Ken Burns como Spotify)
        bgImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        // Scrim estilo Spotify: color derivado del artwork arriba -> negro abajo
        scrimDrawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x80101018.toInt(), 0x40111118.toInt(), 0xF2080810.toInt())
        )
        val scrim = View(context).apply {
            background = scrimDrawable
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(10), dp(24), dp(18))
        }

        // Grabber (pill superior como bottom-sheet de Spotify)
        val grabber = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(2f).toFloat()
                setColor(0x33FFFFFF)
            }
        }
        root.addView(grabber, LinearLayout.LayoutParams(dp(40), dp(4)).apply {
            topMargin = dp(2)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        // Cabecera: like a la izquierda, fuente centrada, cerrar a la derecha
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        likeButton = MusicTheme.iconCircle(context, R.drawable.ic_music_like_border, 26)
        likeButton.setOnClickListener { toggleLike() }
        val extCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        extCol.addView(tv("Reproduciendo desde", 11f, color = MusicTheme.MUTED))
        extView = tv(extName ?: "Extensión", 13f, bold = true, color = MusicTheme.ACCENT)
        extCol.addView(extView)
        val close = MusicTheme.iconCircle(context, R.drawable.ic_music_close, 24)
        close.setOnClickListener { dismiss() }

        header.addView(likeButton, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(extCol)
        header.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))

        // Cover (grande, casi a todo el ancho, esquinas suaves como Spotify)
        val placeholder = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(10f).toFloat()
            setColor(0xFF202027.toInt())
        }
        artView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = placeholder
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            elevation = dpF(8f).toFloat()
            translationZ = dpF(6f).toFloat()
        }
        val cover = minOf(
            dm.widthPixels - dp(48),
            (dm.heightPixels * 0.40f).toInt(),
            (dm.heightPixels - dp(280)).coerceAtLeast(0),
        ).coerceAtLeast(1)
        val coverLp = LinearLayout.LayoutParams(cover, cover)
        coverLp.topMargin = dp(8)
        coverLp.bottomMargin = dp(12)

        // Título / artista: alineados a la izquierda como el reproductor de Spotify
        titleView = tv("", 19f, bold = true, color = MusicTheme.TEXT)
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        titleView.isSelected = true
        titleView.marqueeRepeatLimit = -1
        val titleLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        artistsView = tv("", 14f, color = MusicTheme.MUTED)
        artistsView.maxLines = 2
        artistsView.ellipsize = android.text.TextUtils.TruncateAt.END
        val artistsLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) }

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

        // Controles: shuffle | prev | play | next | repeat (play en círculo blanco como Spotify)
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        shuffleButton = MusicTheme.iconCircle(
            context, R.drawable.ic_music_shuffle, 24, tint = 0x80FFFFFF.toInt()
        )
        shuffleButton.setOnClickListener {
            player.toggleShuffle()
            updateModes()
        }
        val prev = MusicTheme.icon(context, R.drawable.ic_music_prev, 42, Color.WHITE)
        prev.setOnClickListener { player.prev() }
        playButton = MusicTheme.iconCircle(
            context, R.drawable.ic_music_play, 56,
            bg = Color.WHITE, tint = Color.BLACK
        )
        playButton.setOnClickListener { player.toggle() }
        val next = MusicTheme.icon(context, R.drawable.ic_music_next, 42, Color.WHITE)
        next.setOnClickListener { player.next() }
        repeatButton = MusicTheme.iconCircle(
            context, R.drawable.ic_music_repeat, 24, tint = 0x80FFFFFF.toInt()
        )
        repeatButton.setOnClickListener {
            player.cycleRepeat()
            updateModes()
        }

        val order = listOf<Pair<View, Int>>(
            shuffleButton to 52,
            prev to 56,
            playButton to 78,
            next to 56,
            repeatButton to 52,
        )
        order.forEach { (view, w) ->
            controls.addView(view, LinearLayout.LayoutParams(dp(w), dp(w)))
        }

        // Subtítulo: calidad del stream actual
        subtitle = tv("", 12f, color = MusicTheme.MUTED)
        subtitle.gravity = Gravity.CENTER_HORIZONTAL
        subtitle.maxLines = 2
        subtitle.ellipsize = android.text.TextUtils.TruncateAt.END

        // Barra inferior estilo Spotify: icono + etiqueta (chips)
        val actions = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        sleepLabel = tv("", 11f, color = MusicTheme.MUTED)
        sleepLabel.gravity = Gravity.CENTER_HORIZONTAL
        sleepLabel.visibility = View.GONE
        val delim = View(context).apply {
            background = ColorDrawable(0x22FFFFFF)
        }
        val back10 = actionChip("-10s", R.drawable.ic_music_prev)
        back10.setOnClickListener { player.seekBy(-10_000) }
        val fwd10 = actionChip("+10s", R.drawable.ic_music_next)
        fwd10.setOnClickListener { player.seekBy(10_000) }
        val dl = buildDownloadChip()
        val cola = actionChip("Cola", R.drawable.ic_music_queue)
        cola.setOnClickListener { queueDialog() }
        val eq = actionChip("Efectos", R.drawable.ic_music_eq)
        eq.setOnClickListener { effectsDialog() }
        val sleep = actionChip("Dormir", R.drawable.ic_music_sleep)
        sleep.setOnClickListener { sleepDialog() }
        val cal = actionChip("Calidad", R.drawable.ic_music_quality)
        cal.setOnClickListener { qualityDialog() }
        listOf(back10, fwd10, dl, cola, eq, sleep, cal).forEach { b ->
            actionsRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        actions.addView(actionsRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(artView, coverLp)
        root.addView(titleView, titleLp)
        root.addView(artistsView, artistsLp)
        root.addView(fill)

        root.addView(progressBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(30)
        ).apply { topMargin = dp(4) })
        root.addView(timeRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(controls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        root.addView(subtitle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        root.addView(sleepLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(2)
        })
        root.addView(delim, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(12) })
        root.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val frame = FrameLayout(context).apply {
            addView(bgImage)
            addView(scrim)
            addView(root, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }
        setContentView(frame)
    }

    private fun actionChip(text: String, res: Int): LinearLayout {
        val btn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val ic = icon(res, 18, MusicTheme.MUTED)
            addView(ic)
            val label = tv(text, 11f, color = MusicTheme.MUTED)
            label.setPadding(0, dp(4), 0, 0)
            addView(label)
            setPadding(0, dp(4), 0, dp(2))
            background = MusicTheme.ripple(context, ColorDrawable(Color.TRANSPARENT))
        }
        return btn
    }

    /** Chip de descarga mutable: guarda/actualiza su propio icono y etiqueta. */
    private fun buildDownloadChip(): LinearLayout {
        val btn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, dp(2))
            background = MusicTheme.ripple(context, ColorDrawable(Color.TRANSPARENT))
            setOnClickListener { toggleDownload() }
        }
        dlIcon = MusicTheme.icon(context, R.drawable.ic_music_download, 18, MusicTheme.MUTED)
        dlLabel = tv("Descargar", 11f, color = MusicTheme.MUTED).apply {
            setPadding(0, dp(4), 0, 0)
        }
        btn.addView(dlIcon)
        btn.addView(dlLabel)
        dlState = null
        return btn
    }

    private fun toggleDownload() {
        val track = player.nowPlaying.value ?: return
        val dl = player.downloads
        if (dl == null) {
            Toast.makeText(context, "Descargas no disponibles en este dispositivo", Toast.LENGTH_SHORT).show()
            return
        }
        if (dl.lookup(track.id) != null) {
            dl.remove(track.id)
            Toast.makeText(context, "Descarga eliminada", Toast.LENGTH_SHORT).show()
        } else {
            val ext = player.extension
            if (ext == null) {
                Toast.makeText(context, "Sin extensión activa", Toast.LENGTH_SHORT).show()
                return
            }
            dl.enqueue(track, ext)
            Toast.makeText(context, "Descargando…", Toast.LENGTH_SHORT).show()
        }
        refreshDownloadChip()
    }

    /** Refleja en el chip si el track actual está descargado (offline). */
    private fun refreshDownloadChip() {
        val track = player.nowPlaying.value ?: return
        val present = player.downloads?.lookup(track.id) != null
        if (dlState == present) return
        dlState = present
        val res = if (present) R.drawable.ic_music_download_done else R.drawable.ic_music_download
        val color = if (present) MusicTheme.ACCENT else MusicTheme.MUTED
        runCatching {
            dlIcon.setImageResource(res)
            dlIcon.setColorFilter(color)
            dlLabel.text = if (present) "Offline" else "Descargar"
            dlLabel.setTextColor(color)
        }
    }

    override fun show() {
        super.show()
        window ?: return
        window?.setBackgroundDrawable(ColorDrawable(0xFF080810.toInt()))
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setWindowAnimations(R.style.MusicSlideAnimation)
        updated()
        main.post(ticker)
    }

    override fun dismiss() {
        main.removeCallbacks(ticker)
        main.removeCallbacks(sleepRunnable)
        releaseEffects()
        super.dismiss()
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
        shuffleButton.setColorFilter(
            if (player.shuffle.value) MusicTheme.ACCENT else 0x80FFFFFF.toInt()
        )
        repeatButton.setImageResource(
            if (player.repeat.value == androidx.media3.common.Player.REPEAT_MODE_ONE)
                R.drawable.ic_music_repeat_one else R.drawable.ic_music_repeat
        )
        repeatButton.setColorFilter(
            if (player.repeat.value != androidx.media3.common.Player.REPEAT_MODE_OFF)
                MusicTheme.ACCENT else 0x80FFFFFF.toInt()
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
        subtitle.text = label
    }

    private fun updateSleepLabel() {
        val left = sleepAt - System.currentTimeMillis()
        if (left > 0) {
            sleepLabel.text = "Dormir en ${((left / 1000 + 59) / 60).toInt()} min"
            sleepLabel.visibility = View.VISIBLE
        } else {
            sleepLabel.text = ""
            sleepLabel.visibility = View.GONE
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
        bgImage.setImageBitmap(bmp)
        bgImage.setColorFilter(blend(accent, Color.BLACK, 0.82f))
        if (::scrimDrawable.isInitialized) {
            scrimDrawable.setColors(
                intArrayOf(
                    blend(accent, 0xFF101018.toInt(), 0.40f),
                    blend(accent, 0xFF101018.toInt(), 0.80f),
                    0xF2080810.toInt(),
                )
            )
        }
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

        lateinit var dlg: AlertDialog

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6))
        }

        val header = tv("Cola de reproducción", 15f, bold = true, color = MusicTheme.TEXT)
        val sub = tv("${queue.size} · Toca para reproducir", 12f, color = MusicTheme.MUTED).apply {
            setPadding(0, dp(2), 0, dp(6))
        }
        content.addView(header)
        content.addView(sub)

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        queue.forEachIndexed { index, t ->
            val isCurrent = index == player.exo.currentMediaItemIndex
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
                background = MusicTheme.ripple(context, ColorDrawable(Color.TRANSPARENT))
                setOnClickListener {
                    dlg.dismiss()
                    player.jumpTo(index)
                    if (!player.isPlaying.value) player.exo.play()
                    queueDialog()
                }
            }
            val num = tv(if (isCurrent) "▶" else "${index + 1}", 13f, bold = true,
                color = if (isCurrent) MusicTheme.ACCENT else MusicTheme.MUTED).apply {
                gravity = Gravity.CENTER_VERTICAL
                width = dp(34)
            }
            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), 0, dp(6), 0)
            }
            val titleLine = tv(t.title, 13f, color = MusicTheme.TEXT).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val subLine = tv(
                t.artists.joinToString(", ") { it.name }.ifBlank { t.album?.title ?: "" },
                11f, color = MusicTheme.MUTED
            ).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            col.addView(titleLine)
            col.addView(subLine)
            val remove = tv("Quitar", 12f, color = MusicTheme.MUTED).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(4), dp(4))
                setOnClickListener {
                    dlg.dismiss()
                    player.removeFromQueue(index)
                    updated()
                    if (player.queue.value.isEmpty()) {
                        Toast.makeText(context, "Cola vacía", Toast.LENGTH_SHORT).show()
                    } else {
                        queueDialog()
                    }
                }
            }
            row.addView(num)
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(remove)
            rows.addView(row)
            if (index < queue.size - 1) {
                rows.addView(View(context).apply { background = ColorDrawable(0x14FFFFFF) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            }
        }
        scroll.addView(rows)

        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        val clear = tv("Vaciar cola", 13f, bold = true, color = 0xFFE06666.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            background = MusicTheme.ripple(context, ColorDrawable(Color.TRANSPARENT))
            setOnClickListener {
                    dlg.dismiss()
                    player.clearQueue()
                    updated()
                    Toast.makeText(context, "Cola vacía", Toast.LENGTH_SHORT).show()
                }
        }
        val close = tv("Listo", 13f, bold = true, color = MusicTheme.ACCENT).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            background = MusicTheme.ripple(context, ColorDrawable(Color.TRANSPARENT))
        }
        actionsRow.addView(clear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actionsRow.addView(close, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        content.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })
        content.addView(actionsRow)

        close.setOnClickListener { dlg.dismiss() }
        dlg = AlertDialog.Builder(context)
            .setView(content)
            .setOnDismissListener { }
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
            Toast.makeText(context, "Inicia una reproducción para usar los efectos de audio", Toast.LENGTH_SHORT).show()
            return
        }
        val eq = runCatching { Equalizer(0, session) }.getOrNull()
        if (eq == null) {
            Toast.makeText(context, "Ecualizador no disponible en este dispositivo", Toast.LENGTH_SHORT).show()
            return
        }
        releaseEffects()
        equalizer = eq
        runCatching { eq.enabled = true }
        bassBoost = runCatching { BassBoost(0, session) }.getOrNull()
        virtualizer = runCatching { Virtualizer(0, session) }.getOrNull()
        loudness = runCatching { LoudnessEnhancer(session) }.getOrNull()

        val bands = runCatching { eq.numberOfBands.toInt() }.getOrElse { 0 }
        val range = runCatching { eq.bandLevelRange }.getOrNull()
        val eqMin = range?.get(0)?.toInt() ?: -1000
        val eqMax = range?.get(1)?.toInt() ?: 1000
        val savedGains = loadBands()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Bandas del ecualizador (sliders -1000..+1000 millibeles)
        val bandsLabel = tv("Ecualizador", 14f, bold = true, color = MusicTheme.TEXT)
        rows.addView(bandsLabel)
        for (i in 0 until bands) {
            val index = i
            val initial = savedGains.getOrNull(i)?.coerceIn(eqMin, eqMax) ?: 0
            val row = sliderRow(
                bandLabel(eq, index),
                eqMin, eqMax, initial,
                { value ->
                    runCatching { eq.setBandLevel(index.toShort(), value.toShort()) }
                    saveBands(bands)
                }
            )
            rows.addView(row)
        }

        // Bajos (BassBoost 0..1000)
        if (bassBoost != null) {
            val bassRow = sliderRow(
                "Bajos", 0, 1000, eqPrefs.getInt("eq_bass", 0),
                { value ->
                    runCatching {
                        bassBoost?.setStrength(value.toShort())
                        bassBoost?.enabled = value > 0
                    }
                    eqPrefs.edit().putInt("eq_bass", value).apply()
                }
            )
            rows.addView(bassRow)
        }

        // Virtualizer (0..1000)
        if (virtualizer != null) {
            val virtRow = sliderRow(
                "Sonido envolvente", 0, 1000, eqPrefs.getInt("eq_virtual", 0),
                { value ->
                    runCatching {
                        virtualizer?.setStrength(value.toShort())
                        virtualizer?.enabled = value > 0
                    }
                    eqPrefs.edit().putInt("eq_virtual", value).apply()
                }
            )
            rows.addView(virtRow)
        }

        // Loudness enhancer (switch)
        if (loudness != null) {
            val loudRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14), 0, dp(2))
                addView(tv("Potenciador de volumen", 14f),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val sw = Switch(context).apply {
                    isChecked = eqPrefs.getBoolean("eq_loudness", false)
                    setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
                        runCatching {
                            loudness?.setTargetGain(if (on) 1500 else 0)
                            loudness?.enabled = on
                        }
                        eqPrefs.edit().putBoolean("eq_loudness", on).apply()
                    }
                }
                addView(sw)
            }
            rows.addView(loudRow)
        }

        // Aplicar prefijos guardados (bandas + bass + virtual + loudness)
        runCatching {
            for (i in 0 until bands) {
                eq.setBandLevel(i.toShort(), (savedGains.getOrNull(i)?.coerceIn(eqMin, eqMax) ?: 0).toShort())
            }
            val bass = eqPrefs.getInt("eq_bass", 0)
            if (bass > 0) {
                bassBoost?.setStrength(bass.toShort())
                bassBoost?.enabled = true
            }
            val virt = eqPrefs.getInt("eq_virtual", 0)
            if (virt > 0) {
                virtualizer?.setStrength(virt.toShort())
                virtualizer?.enabled = true
            }
            if (loudness != null && eqPrefs.getBoolean("eq_loudness", false)) {
                loudness?.setTargetGain(1500)
                loudness?.enabled = true
            }
        }

        scroll.addView(rows)
        content.addView(scroll)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            val reset = MusicTheme.pill(context, "Restablecer", bg = MusicTheme.SURFACE_2)
            reset.setOnClickListener {
                resetEffects(eq, rows, bands)
            }
            val ok = MusicTheme.pill(context, "Listo", bg = MusicTheme.ACCENT, color = MusicTheme.BG)
            ok.setOnClickListener { dialogRef?.dismiss() }
            addView(reset, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ok)
        }
        content.addView(actions, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        val dlg = Dialog(context)
        dialogRef = dlg
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(content)
        dlg.window?.setBackgroundDrawable(ColorDrawable(0xFF101018.toInt()))
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.setOnDismissListener { releaseEffects() }
        dlg.show()
    }

    private var dialogRef: Dialog? = null

    private fun releaseEffects() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { loudness?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudness = null
    }

    /** Fila label + SeekBar con valor en millibeles/intensidad. */
    private fun sliderRow(
        label: String,
        min: Int,
        max: Int,
        initial: Int,
        onChanged: (Int) -> Unit,
    ): LinearLayout {
        val valueLabel = tv("$label  $initial", 13f, color = MusicTheme.MUTED)
        val normalized = initial.coerceIn(min, max) - min
        val bar = SeekBar(context).apply {
            this.max = max - min
            progress = normalized
            MusicTheme.tintSeek(this)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val value = min + p
                    valueLabel.text = "$label  $value"
                    if (fromUser) onChanged(value)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(2))
            addView(valueLabel)
            addView(bar)
        }
    }

    private fun bandLabel(eq: Equalizer, index: Int): String {
        val freq = runCatching { eq.getCenterFreq(index.toShort()) }.getOrDefault(0)
        if (freq <= 0) return "Banda ${index + 1}"
        val khz = freq / 1000f
        return if (khz >= 10f) "${khz.toInt()} kHz" else "%.1f kHz".format(khz)
    }

    private fun resetEffects(eq: Equalizer, rows: LinearLayout, bands: Int) {
        eqPrefs.edit()
            .putInt("eq_bass", 0)
            .putInt("eq_virtual", 0)
            .putBoolean("eq_loudness", false)
            .putString("eq_gains", "")
            .apply()
        runCatching {
            if (bassBoost != null) { bassBoost!!.enabled = false; bassBoost!!.setStrength(0.toShort()) }
            if (virtualizer != null) { virtualizer!!.enabled = false; virtualizer!!.setStrength(0.toShort()) }
            if (loudness != null) { loudness!!.enabled = false; loudness!!.setTargetGain(0) }
            for (i in 0 until bands) eq.setBandLevel(i.toShort(), 0)
        }
        // Releer: recrear filas no trivial; reiniciar texto de la primera fila basta
        Toast.makeText(context, "Efectos restablecidos", Toast.LENGTH_SHORT).show()
    }

    private fun loadBands(): List<Int> {
        val raw = eqPrefs.getString("eq_gains", "") ?: ""
        return raw.split(",").mapNotNull { it.toIntOrNull() }
    }

    private fun saveBands(total: Int) {
        val eq = equalizer ?: return
        val values = (0 until total).map { i ->
            runCatching { eq.getBandLevel(i.toShort()).toInt() }.getOrDefault(0)
        }
        eqPrefs.edit().putString("eq_gains", values.joinToString(",")).apply()
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