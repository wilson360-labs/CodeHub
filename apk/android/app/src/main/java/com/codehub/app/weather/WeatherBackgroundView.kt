package com.codehub.app.weather

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/** One UI 8.5 style dynamic perimeter gradient background. */
class WeatherBackgroundView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var palette: WeatherDesign.Palette = WeatherDesign.palette(WeatherCondition.CLEAR_DAY, true)
    private var anim: ValueAnimator? = null
    private var phase = 0f
    var animationsEnabled: Boolean = true
        set(value) { field = value; if (value) start() else stop() }

    fun setWeather(condition: WeatherCondition, dark: Boolean) {
        palette = WeatherDesign.palette(condition, dark)
        invalidate()
    }

    fun start() {
        if (!animationsEnabled || anim != null) { invalidate(); return }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 9000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun stop() { anim?.cancel(); anim = null }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); start() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        paint.shader = LinearGradient(0f, 0f, 0f, h, palette.bgTop, palette.bgBottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Perimeter gradients: top, left, right, bottom
        paint.shader = LinearGradient(0f, 0f, 0f, h * .28f, palette.edgeA, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h * .28f, paint)
        paint.shader = LinearGradient(0f, h * .72f, 0f, h, Color.TRANSPARENT, palette.edgeB, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, h * .72f, w, h, paint)
        paint.shader = LinearGradient(0f, 0f, w * .24f, 0f, palette.edgeB, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w * .24f, h, paint)
        paint.shader = LinearGradient(w * .76f, 0f, w, 0f, Color.TRANSPARENT, palette.edgeA, Shader.TileMode.CLAMP)
        canvas.drawRect(w * .76f, 0f, w, h, paint)
        paint.shader = null

        if (animationsEnabled) {
            val shift = (kotlin.math.sin(phase * Math.PI * 2).toFloat() + 1f) * .5f
            paint.color = palette.edgeA
            paint.alpha = 28
            canvas.drawCircle(w * (.18f + shift * .08f), h * (.16f + shift * .03f), min(w, h) * .34f, paint)
            paint.color = palette.edgeB
            paint.alpha = 22
            canvas.drawCircle(w * (.82f - shift * .06f), h * (.78f - shift * .04f), min(w, h) * .30f, paint)
        }
        paint.alpha = 255
    }
}
