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
    private var animator: ValueAnimator? = null
    private val sizePx = (sizeDp * density + .5f).toInt()

    init {
        layoutParams = android.view.ViewGroup.LayoutParams(sizePx, sizePx)
        contentDescription = weatherLabel(code)
        if (animated) startFloat()
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

        if (!isCloudy || isPartlyCloudy) drawSun(canvas, if (isPartlyCloudy) 67f else 50f, if (isPartlyCloudy) 32f else 43f)
        if (isCloudy) drawCloud(canvas, if (isStorm) 0xFFE8F0FF.toInt() else 0xFFF1F7FF.toInt())
        when {
            isFog -> drawFog(canvas)
            isRain -> drawRain(canvas)
            isSnow -> drawSnow(canvas)
            isStorm -> drawLightning(canvas)
        }
        canvas.restore()
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float) {
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
    }

    private fun drawCloud(canvas: Canvas, color: Int) {
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
    }

    private fun drawRain(canvas: Canvas) {
        paint.color = 0xFF77DDF2.toInt()
        paint.strokeWidth = 5f
        paint.style = Paint.Style.STROKE
        canvas.drawLine(34f, 77f, 29f, 87f, paint)
        canvas.drawLine(55f, 77f, 50f, 87f, paint)
        canvas.drawLine(76f, 77f, 71f, 87f, paint)
    }

    private fun drawSnow(canvas: Canvas) {
        paint.color = 0xFFC2E8FF.toInt()
        paint.strokeWidth = 3.5f
        paint.style = Paint.Style.STROKE
        listOf(32f to 83f, 53f to 88f, 74f to 82f).forEach { (x, y) ->
            canvas.drawLine(x - 5f, y, x + 5f, y, paint)
            canvas.drawLine(x, y - 5f, x, y + 5f, paint)
        }
    }

    private fun drawLightning(canvas: Canvas) {
        paint.color = 0xFFFFD477.toInt()
        paint.style = Paint.Style.FILL
        val bolt = Path().apply { moveTo(53f, 66f); lineTo(39f, 84f); lineTo(50f, 84f); lineTo(45f, 98f); lineTo(65f, 76f); lineTo(54f, 76f); close() }
        canvas.drawPath(bolt, paint)
    }

    private fun drawFog(canvas: Canvas) {
        paint.color = 0xFFB8D6E6.toInt()
        paint.strokeWidth = 4f
        paint.style = Paint.Style.STROKE
        canvas.drawLine(21f, 78f, 79f, 78f, paint)
        canvas.drawLine(29f, 88f, 71f, 88f, paint)
    }

    private fun startFloat() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, -3f, 0f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { floatOffset = (it.animatedValue as Float) * density; invalidate() }
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
