package com.codehub.app.weather

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min

object WeatherComponents {
    fun dp(ctx: Context, v: Int): Int = WeatherDesign.dp(ctx, v)
    fun dpF(ctx: Context, v: Int): Float = WeatherDesign.dpF(ctx, v)

    fun glassCard(ctx: Context, p: WeatherDesign.Palette, radiusDp: Int = 28, strong: Boolean = false, block: LinearLayout.() -> Unit = {}): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            WeatherDesign.applyGlass(this, p, radiusDp, strong)
            block()
        }

    fun text(ctx: Context, p: WeatherDesign.Palette, value: String, size: Float, color: Int = p.text, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = value; textSize = size; setTextColor(color)
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }

    fun sectionTitle(ctx: Context, p: WeatherDesign.Palette, value: String): TextView =
        text(ctx, p, value, 15f, p.text, true).apply {
            setPadding(dp(ctx, 4), dp(ctx, 2), dp(ctx, 4), dp(ctx, 2))
        }

    fun metricCard(ctx: Context, p: WeatherDesign.Palette, m: WeatherMetric): View {
        val tone = WeatherDesign.metricTone(p, m.tone)
        return glassCard(ctx, p, 24, false) {
            setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
            minimumHeight = dp(ctx, 128)
            addView(text(ctx, p, m.label.uppercase(), 10f, p.muted, true).apply { letterSpacing = .05f })
            addView(text(ctx, p, m.value, 24f, p.text, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 10) })
            addView(View(ctx).apply { background = GradientDrawable().apply { cornerRadius = dpF(ctx, 2); setColor(ColorUtils.setAlphaComponent(tone, 120)) } },
                LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 3)).apply { topMargin = dp(ctx, 10) })
            addView(text(ctx, p, m.hint, 11f, tone), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 10) })
        }
    }

    fun alertPill(ctx: Context, p: WeatherDesign.Palette, alert: WeatherAlert): View {
        val tone = WeatherDesign.metricTone(p, alert.tone)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            background = GradientDrawable().apply {
                cornerRadius = dpF(ctx, 20)
                setColor(ColorUtils.setAlphaComponent(tone, 28))
                setStroke(dp(ctx, 1), ColorUtils.setAlphaComponent(tone, 70))
            }
        }
        root.addView(View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(tone) }
        }, LinearLayout.LayoutParams(dp(ctx, 8), dp(ctx, 8)).apply { marginEnd = dp(ctx, 10) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(ctx, p, alert.title, 12f, p.text, true))
        col.addView(text(ctx, p, alert.body, 11f, p.text2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 2) })
        root.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return root
    }
}

/** Smooth hourly chart: temp line + precip bars. */
class HourlyChartView(context: Context, private val hours: List<HourPoint>, private val p: WeatherDesign.Palette) : View(context) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = WeatherDesign.dpF(context, 3); strokeCap = Paint.Cap.ROUND; color = p.accent }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = p.text }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = ColorUtils.setAlphaComponent(p.accent, 55) }
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.muted; textSize = WeatherDesign.dpF(context, 10); textAlign = Paint.Align.CENTER }
    private val tempP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.text; textSize = WeatherDesign.dpF(context, 11); textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hours.isEmpty()) return
        val w = width.toFloat(); val h = height.toFloat()
        val padX = WeatherDesign.dpF(context, 14)
        val top = WeatherDesign.dpF(context, 18)
        val bottom = h - WeatherDesign.dpF(context, 18)
        val chartH = (bottom - top).coerceAtLeast(1f)
        val temps = hours.map { it.temp }
        val minT = (temps.minOrNull() ?: 0) - 1
        val maxT = (temps.maxOrNull() ?: 1) + 1
        val span = (maxT - minT).coerceAtLeast(1)
        fun x(i: Int) = padX + (w - padX * 2) * i / (hours.size - 1).coerceAtLeast(1)
        fun y(t: Int) = bottom - (t - minT).toFloat() / span * chartH

        // precip bars
        hours.forEachIndexed { i, pt ->
            val bh = (pt.precip.coerceIn(0, 100) / 100f) * chartH * .45f
            val cx = x(i)
            val bw = WeatherDesign.dpF(context, 10)
            canvas.drawRoundRect(cx - bw / 2, bottom - bh, cx + bw / 2, bottom, bw / 2, bw / 2, bar)
        }

        // line path
        val path = Path()
        hours.forEachIndexed { i, pt ->
            val px = x(i); val py = y(pt.temp)
            if (i == 0) path.moveTo(px, py) else {
                val prevX = x(i - 1); val prevY = y(hours[i - 1].temp)
                val mid = (prevX + px) / 2
                path.cubicTo(mid, prevY, mid, py, px, py)
            }
        }
        val fillPath = Path(path).apply { lineTo(x(hours.lastIndex), bottom); lineTo(x(0), bottom); close() }
        fill.shader = LinearGradient(0f, top, 0f, bottom, ColorUtils.setAlphaComponent(p.accent, 60), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawPath(fillPath, fill); fill.shader = null
        canvas.drawPath(path, line)

        hours.forEachIndexed { i, pt ->
            val px = x(i); val py = y(pt.temp)
            canvas.drawCircle(px, py, WeatherDesign.dpF(context, 4), dot)
            canvas.drawText("${pt.temp}°", px, py - WeatherDesign.dpF(context, 8), tempP)
            canvas.drawText(pt.label, px, h - WeatherDesign.dpF(context, 3), textP)
        }
    }
}

/** Extended day range bar. */
class DayRangeBarView(context: Context, private val min: Int, private val max: Int, private val gMin: Int, private val gMax: Int, private val p: WeatherDesign.Palette) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = WeatherDesign.dpF(context, 4); color = ColorUtils.setAlphaComponent(p.text, 30) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = WeatherDesign.dpF(context, 4) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = WeatherDesign.dpF(context, 2); val y = height / 2f
        val right = (width - pad).coerceAtLeast(pad + 1)
        canvas.drawLine(pad, y, right, y, track)
        val span = (gMax - gMin).coerceAtLeast(1)
        val s = pad + (right - pad) * ((min - gMin).toFloat() / span).coerceIn(0f, 1f)
        val e = pad + (right - pad) * ((max - gMin).toFloat() / span).coerceIn(0f, 1f)
        fill.shader = LinearGradient(s, y, max(e, s + 1), y, p.ok, p.accent, Shader.TileMode.CLAMP)
        canvas.drawLine(s, y, max(e, s + WeatherDesign.dpF(context, 4)), y, fill)
        fill.shader = null
    }
}
