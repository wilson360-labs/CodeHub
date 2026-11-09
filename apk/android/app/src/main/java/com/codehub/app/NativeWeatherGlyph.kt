package com.codehub.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/** Small, consistent weather illustration drawn natively instead of platform emoji glyphs. */
internal class NativeWeatherGlyph(
    context: Context,
    private val code: Int,
    sizeDp: Int,
    private val animated: Boolean = false,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
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
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        val isFog = code == 45 || code == 48
        val isRain = code in 51..67 || code in 80..82
        val isSnow = code in 71..77 || code in 85..86
        val isStorm = code in 95..99
        val isCloudy = code != 0
        val isPartlyCloudy = code in 1..2

        if (!isCloudy || isPartlyCloudy) drawSun(canvas, if (isPartlyCloudy) 67f else 50f, if (isPartlyCloudy) 32f else 43f, effectPhase)
        if (isCloudy) drawCloud(canvas, if (isStorm) 0xFFE8F0FF.toInt() else 0xFFF1F7FF.toInt(), effectPhase)
        when {
            isFog -> drawFog(canvas, effectPhase)
            isRain -> drawRain(canvas, effectPhase)
            isSnow -> drawSnow(canvas, effectPhase)
            isStorm -> drawLightning(canvas, effectPhase)
        }
        canvas.restore()
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, phase: Float) {
        canvas.save()
        canvas.rotate(phase * 24f, cx, cy)
        paint.color = 0xFFFFD477.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        for (index in 0 until 8) {
            val angle = Math.PI * index / 4.0
            val dx = kotlin.math.cos(angle).toFloat()
            val dy = kotlin.math.sin(angle).toFloat()
            canvas.drawLine(cx + dx * 20f, cy + dy * 20f, cx + dx * 29f, cy + dy * 29f, paint)
        }
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, 15f, paint)
        canvas.restore()
    }

    private fun drawCloud(canvas: Canvas, color: Int, phase: Float) {
        canvas.save()
        canvas.translate(kotlin.math.sin(phase * Math.PI * 2.0).toFloat() * 1.6f, 0f)
        val cloud = Path().apply {
            moveTo(24f, 69f)
            cubicTo(14f, 69f, 9f, 62f, 9f, 53f)
            cubicTo(9f, 44f, 16f, 37f, 25f, 37f)
            cubicTo(28f, 23f, 40f, 15f, 53f, 17f)
            cubicTo(66f, 18f, 74f, 27f, 75f, 39f)
            cubicTo(87f, 36f, 94f, 44f, 94f, 54f)
            cubicTo(94f, 63f, 87f, 69f, 77f, 69f)
            close()
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = 0x3024D6EE
        canvas.drawPath(cloud, paint)
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawPath(cloud, paint)
        canvas.restore()
    }

    private fun drawRain(canvas: Canvas, phase: Float) {
        paint.color = 0xFF77DDF2.toInt()
        paint.strokeWidth = 5f
        paint.style = Paint.Style.STROKE
        for (index in 0..2) {
            val offset = (phase * 18f + index * 6f) % 18f
            val x = 34f + index * 21f
            canvas.drawLine(x, 73f + offset, x - 5f, 80f + offset, paint)
        }
    }

    private fun drawSnow(canvas: Canvas, phase: Float) {
        paint.color = 0xFFC2E8FF.toInt()
        paint.strokeWidth = 3.5f
        paint.style = Paint.Style.STROKE
        listOf(32f to 81f, 53f to 84f, 74f to 79f).forEachIndexed { index, (x, baseY) ->
            val y = baseY + (phase * 9f + index * 3f) % 9f
            canvas.drawLine(x - 5f, y, x + 5f, y, paint)
            canvas.drawLine(x, y - 5f, x, y + 5f, paint)
        }
    }

    private fun drawLightning(canvas: Canvas, phase: Float) {
        val alpha = if (phase < .08f || phase in .18f..0.23f) 255 else 150
        paint.color = (alpha shl 24) or 0x00FFD477
        paint.style = Paint.Style.FILL
        val bolt = Path().apply { moveTo(53f, 66f); lineTo(39f, 84f); lineTo(50f, 84f); lineTo(45f, 98f); lineTo(65f, 76f); lineTo(54f, 76f); close() }
        canvas.drawPath(bolt, paint)
    }

    private fun drawFog(canvas: Canvas, phase: Float) {
        paint.color = 0xFFB8D6E6.toInt()
        paint.strokeWidth = 4f
        paint.style = Paint.Style.STROKE
        val drift = kotlin.math.sin(phase * Math.PI * 2.0).toFloat() * 5f
        canvas.drawLine(21f + drift, 78f, 79f + drift, 78f, paint)
        canvas.drawLine(29f - drift, 88f, 71f - drift, 88f, paint)
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
