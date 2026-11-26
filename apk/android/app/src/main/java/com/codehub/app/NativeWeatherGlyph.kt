package com.codehub.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/** Small, consistent weather illustration drawn natively instead of platform emoji glyphs. */
internal class NativeWeatherGlyph(
    context: Context,
    private val code: Int,
    sizeDp: Int,
    private val animated: Boolean = false,
    private val night: Boolean = false,
) : View(context) {
    private val density = resources.displayMetrics.density
    private var floatOffset = 0f
    private var effectPhase = 0f
    private var animator: ValueAnimator? = null
    private val sizePx = (sizeDp * density + .5f).toInt()

    init {
        layoutParams = android.view.ViewGroup.LayoutParams(sizePx, sizePx)
        contentDescription = weatherLabel(code)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val unit = minOf(width, height).toFloat()
        if (unit <= 0f) return
        val ox = (width - unit) / 2f
        val oy = (height - unit) / 2f + floatOffset
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(unit / 100f, unit / 100f)
        WeatherIconPainter.draw(canvas, code, night, effectPhase)
        canvas.restore()
    }

    private fun startFloat() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                effectPhase = it.animatedValue as Float
                floatOffset = kotlin.math.sin(effectPhase * Math.PI * 2.0).toFloat() * 2.4f * density
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animated) startFloat()
    }

    private fun weatherLabel(code: Int): String = when {
        code == 0 -> "Despejado"
        code in 1..3 -> "Nublado parcialmente"
        code == 45 || code == 48 -> "Neblina"
        code in 51..67 || code in 80..82 -> "Lluvia"
        code in 71..77 || code in 85..86 -> "Nieve"
        code in 95..99 -> "Tormenta"
        else -> "Estado del tiempo"
    }
}
