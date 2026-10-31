package com.codehub.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.Choreographer
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Calendar

/** Native port of CodeHub's web splash, shown only by the compiled Android app. */
class NativeSplashView(context: Context) : FrameLayout(context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startedAt = SystemClock.elapsedRealtime()
    private val choreographer = Choreographer.getInstance()
    private var dismissed = false
    private var dismissRequested = false
    private var dismissStartedAt = 0L
    private var framePosted = false
    private lateinit var logoView: View
    private lateinit var orbitalRings: OrbitalRingsView
    private val progressBar: ProgressBar
    private val progressLabel: TextView
    private val statusLabel: TextView

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.rgb(6, 6, 15))

        addView(AuroraBackground(context), LayoutParams(-1, -1))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val logoSize = dp(194)
        val logoFrame = FrameLayout(context)
        orbitalRings = OrbitalRingsView(context)
        logoFrame.addView(orbitalRings, LayoutParams(logoSize, logoSize, Gravity.CENTER))

        val logo = ImageView(context).apply {
            setImageResource(R.drawable.codehub_splash)
            contentDescription = "CodeHub"
            scaleType = ImageView.ScaleType.FIT_CENTER
            elevation = dp(8).toFloat()
        }
        logoView = logo
        logoFrame.addView(logo, LayoutParams(dp(132), dp(132), Gravity.CENTER))
        content.addView(logoFrame, LinearLayout.LayoutParams(logoSize, logoSize))

        val greeting = TextView(context).apply {
            text = greetingText()
            textSize = 10f
            letterSpacing = .16f
            setTextColor(Color.rgb(0, 198, 255))
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        content.addView(greeting, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(15)
        })

        val brand = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        brand.addView(TextView(context).apply {
            text = "Code"
            textSize = 34f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = .035f
        })
        brand.addView(TextView(context).apply {
            text = "Hub"
            textSize = 34f
            setTextColor(Color.rgb(0, 198, 255))
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = .035f
        })
        content.addView(brand, LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = dp(3)
        })

        content.addView(TextView(context).apply {
            text = "by Wilson.E  ·  GUATEMALA"
            textSize = 9f
            letterSpacing = .12f
            setTextColor(Color.rgb(118, 120, 147))
            typeface = Typeface.create("monospace", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })

        val progressSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val progressTop = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusLabel = TextView(context).apply {
            text = "INICIANDO…"
            textSize = 9f
            letterSpacing = .07f
            setTextColor(Color.rgb(119, 126, 157))
            typeface = Typeface.create("monospace", Typeface.NORMAL)
        }
        progressLabel = TextView(context).apply {
            text = "0%"
            textSize = 10f
            setTextColor(Color.rgb(0, 198, 255))
            typeface = Typeface.create("monospace", Typeface.BOLD)
        }
        progressTop.addView(statusLabel, LinearLayout.LayoutParams(0, -2, 1f))
        progressTop.addView(progressLabel)
        progressSection.addView(progressTop)

        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(0, 198, 255))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(37, 39, 59))
        }
        progressSection.addView(progressBar, LinearLayout.LayoutParams(-1, dp(3)).apply {
            topMargin = dp(8)
        })
        content.addView(progressSection, LinearLayout.LayoutParams(dp(220), -2).apply {
            topMargin = dp(28)
        })

        addView(content, LayoutParams(-2, -2, Gravity.CENTER))
    }

    /** Lets a visible splash reflect WebView progress when a web route is opened during startup. */
    fun setLoadProgress(value: Int) {
        if (dismissRequested || dismissed) return
        val percent = value.coerceIn(0, 100)
        progressBar.setProgress(percent, false)
        progressLabel.text = "$percent%"
        statusLabel.text = when {
            percent < 30 -> "INICIANDO…"
            percent < 60 -> "CARGANDO RECURSOS…"
            percent < 90 -> "CASI LISTO…"
            else -> "PREPARANDO CODEHUB…"
        }
    }

    /** Keeps the splash visible briefly for a polished transition, even on fast networks. */
    fun dismiss() {
        if (dismissRequested || dismissed) return
        dismissRequested = true
        val remaining = (900L - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L)
        mainHandler.postDelayed({
            if (dismissed) return@postDelayed
            dismissStartedAt = SystemClock.elapsedRealtime()
        }, remaining)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postAnimationFrame()
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacksAndMessages(null)
        if (framePosted) choreographer.removeFrameCallback(frameCallback)
        framePosted = false
        super.onDetachedFromWindow()
    }

    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        framePosted = false
        if (!isAttachedToWindow || dismissed) return@FrameCallback

        val now = SystemClock.elapsedRealtime()
        val elapsed = now - startedAt
        val entrance = (elapsed / 720f).coerceIn(0f, 1f)
        val easedEntrance = 1f - (1f - entrance) * (1f - entrance) * (1f - entrance)
        alpha = (elapsed / 450f).coerceIn(0f, 1f)
        logoView.scaleX = .78f + .22f * easedEntrance
        logoView.scaleY = .78f + .22f * easedEntrance
        val floatPhase = ((elapsed - 720L).coerceAtLeast(0L) % 3_000L) / 3_000.0
        logoView.translationY = (-dp(5) * ((1.0 - kotlin.math.cos(floatPhase * Math.PI * 2.0)) / 2.0)).toFloat()
        orbitalRings.setRotationDegrees((elapsed % 7_000L) * 360f / 7_000f)

        if (dismissStartedAt > 0L) {
            val exit = ((now - dismissStartedAt) / 420f).coerceIn(0f, 1f)
            alpha = 1f - exit
            scaleX = 1f + .025f * exit
            scaleY = 1f + .025f * exit
            if (exit >= 1f) {
                dismissed = true
                visibility = View.GONE
                return@FrameCallback
            }
        }
        postAnimationFrame()
    }

    private fun postAnimationFrame() {
        if (!framePosted && isAttachedToWindow && !dismissed) {
            framePosted = true
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private fun greetingText(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeGreeting = when {
            hour in 5..11 -> "BUENOS DÍAS"
            hour in 12..18 -> "BUENAS TARDES"
            else -> "BUENAS NOCHES"
        }
        return "BIENVENIDO  ·  $timeGreeting"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
}

/** Lightweight Canvas equivalent of the web splash's colored rotating orbit rings. */
private class OrbitalRingsView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private var rotation = 0f

    fun setRotationDegrees(value: Float) {
        rotation = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = width / 2f - 5f * density
        bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f * density
        paint.shader = SweepGradient(
            cx, cy,
            intArrayOf(0xFF2F80ED.toInt(), 0xFF22C55E.toInt(), 0xFF6A11FF.toInt(), 0xFF00C6FF.toInt(), 0xFF2F80ED.toInt()),
            null,
        )
        canvas.save()
        canvas.rotate(rotation, cx, cy)
        canvas.drawArc(bounds, 0f, 330f, false, paint)
        canvas.drawCircle(cx, cy - radius, 4f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2F80ED.toInt()
            setShadowLayer(9f * density, 0f, 0f, color)
        })
        canvas.restore()

        paint.shader = null
        paint.color = 0x886A11FF.toInt()
        paint.strokeWidth = 1.4f * density
        val inner = radius - 10f * density
        bounds.set(cx - inner, cy - inner, cx + inner, cy + inner)
        canvas.save()
        canvas.rotate(-rotation * .78f, cx, cy)
        canvas.drawArc(bounds, 205f, 270f, false, paint)
        canvas.restore()
        paint.style = Paint.Style.FILL
    }

}

/** Static star field and soft color blooms matching the original web splash palette. */
private class AuroraBackground(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stars = Array(42) { index ->
        val x = ((index * 73 + 19) % 997) / 997f
        val y = ((index * 137 + 41) % 991) / 991f
        val radius = if (index % 7 == 0) 1.5f else 1f
        Triple(x, y, radius)
    }

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = maxOf(w, h) * .62f
        paint.shader = android.graphics.RadialGradient(
            w * .5f, h * .18f, radius,
            intArrayOf(0x2B6A11FF, 0x0A6A11FF, Color.TRANSPARENT), null, android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = android.graphics.RadialGradient(
            w * .82f, h * .68f, radius * .68f,
            intArrayOf(0x1900C6FF, Color.TRANSPARENT), null, android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        stars.forEachIndexed { index, (x, y, starRadius) ->
            paint.color = when (index % 4) {
                0 -> 0xBFFFFFFF.toInt()
                1 -> 0xCC00C6FF.toInt()
                2 -> 0xB37C4DFF.toInt()
                else -> 0xB3E8C978.toInt()
            }
            canvas.drawCircle(x * w, y * h, starRadius * resources.displayMetrics.density, paint)
        }
    }
}
