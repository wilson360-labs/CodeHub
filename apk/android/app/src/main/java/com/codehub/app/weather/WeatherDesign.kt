package com.codehub.app.weather

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import kotlin.math.max

object WeatherDesign {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + .5f).toInt()
    fun dpF(ctx: Context, v: Int): Float = v * ctx.resources.displayMetrics.density

    data class Palette(
        val bgTop: Int,
        val bgBottom: Int,
        val edgeA: Int,
        val edgeB: Int,
        val edgeC: Int,
        val glass: Int,
        val glassStrong: Int,
        val glassBorder: Int,
        val glassHighlight: Int,
        val text: Int,
        val text2: Int,
        val muted: Int,
        val accent: Int,
        val ok: Int,
        val warn: Int,
        val danger: Int,
        val chip: Int,
    )

    fun palette(condition: WeatherCondition, dark: Boolean): Palette = when {
        condition == WeatherCondition.RAIN || condition == WeatherCondition.STORM -> if (dark) Palette(
            bgTop = 0xFF09131D.toInt(), bgBottom = 0xFF000000.toInt(),
            edgeA = 0x335C9EC7.toInt(), edgeB = 0x223D6D8A.toInt(), edgeC = 0x14000000.toInt(),
            glass = 0x1FFFFFFF, glassStrong = 0x29FFFFFF, glassBorder = 0x33FFFFFF, glassHighlight = 0x40FFFFFF,
            text = 0xFFF4ECE1.toInt(), text2 = 0xCCF4ECE1.toInt(), muted = 0x99C8BFB3.toInt(),
            accent = 0xFF8ECFE0.toInt(), ok = 0xFF9DB99E.toInt(), warn = 0xFFE2B48F.toInt(), danger = 0xFFD98A67.toInt(),
            chip = 0x33213A47.toInt()
        ) else Palette(
            bgTop = 0xFF8AAFC4.toInt(), bgBottom = 0xFFEAF2F6.toInt(),
            edgeA = 0x55DCEFF8.toInt(), edgeB = 0x334A7A94.toInt(), edgeC = 0x22FFFFFF.toInt(),
            glass = 0x52FFFFFF, glassStrong = 0x70FFFFFF, glassBorder = 0x66FFFFFF, glassHighlight = 0x80FFFFFF,
            text = 0xFF1E2D36.toInt(), text2 = 0xCC243744.toInt(), muted = 0x99556876.toInt(),
            accent = 0xFF3F88A8.toInt(), ok = 0xFF5E8A63.toInt(), warn = 0xFFB98542.toInt(), danger = 0xFFB85C3C.toInt(),
            chip = 0x55FFFFFF
        )
        condition == WeatherCondition.CLEAR_NIGHT || dark -> Palette(
            bgTop = 0xFF111726.toInt(), bgBottom = 0xFF000000.toInt(),
            edgeA = 0x334A74B8.toInt(), edgeB = 0x22D98A67.toInt(), edgeC = 0x14000000.toInt(),
            glass = 0x1AFFFFFF, glassStrong = 0x26FFFFFF, glassBorder = 0x2EFFFFFF, glassHighlight = 0x38FFFFFF,
            text = 0xFFF4ECE1.toInt(), text2 = 0xCCF4ECE1.toInt(), muted = 0x99C2B6A8.toInt(),
            accent = 0xFFD98A67.toInt(), ok = 0xFF9DB99E.toInt(), warn = 0xFFE2B48F.toInt(), danger = 0xFFFF8A80.toInt(),
            chip = 0x33202838.toInt()
        )
        condition == WeatherCondition.CLEAR_DAY || condition == WeatherCondition.SECOISH -> Palette(
            bgTop = 0xFFF0D6B8.toInt(), bgBottom = 0xFFF7F1E7.toInt(),
            edgeA = 0x66FFF4DE.toInt(), edgeB = 0x33D98A67.toInt(), edgeC = 0x22FFFFFF.toInt(),
            glass = 0x59FFFFFF, glassStrong = 0x73FFFFFF, glassBorder = 0x66FFFFFF, glassHighlight = 0x88FFFFFF,
            text = 0xFF2E241C.toInt(), text2 = 0xCC3A2E24.toInt(), muted = 0x99705D49.toInt(),
            accent = 0xFFB85C3C.toInt(), ok = 0xFF6F8F72.toInt(), warn = 0xFFC9892E.toInt(), danger = 0xFFB23A2E.toInt(),
            chip = 0x66FFFDFC.toInt()
        )
        else -> if (dark) Palette(
            bgTop = 0xFF16120E.toInt(), bgBottom = 0xFF000000.toInt(),
            edgeA = 0x33D98A67.toInt(), edgeB = 0x229DB99E.toInt(), edgeC = 0x14000000.toInt(),
            glass = 0x1CFFFFFF, glassStrong = 0x26FFFFFF, glassBorder = 0x30FFFFFF, glassHighlight = 0x3DFFFFFF,
            text = 0xFFF4ECE1.toInt(), text2 = 0xCCF4ECE1.toInt(), muted = 0x99B8AEA2.toInt(),
            accent = 0xFFD98A67.toInt(), ok = 0xFF9DB99E.toInt(), warn = 0xFFE2B48F.toInt(), danger = 0xFFFF8A80.toInt(),
            chip = 0x33211A16.toInt()
        ) else Palette(
            bgTop = 0xFFE8DDD0.toInt(), bgBottom = 0xFFF8F3EB.toInt(),
            edgeA = 0x55FFFFFF.toInt(), edgeB = 0x22D98A67.toInt(), edgeC = 0x18FFFFFF.toInt(),
            glass = 0x5CFFFFFF, glassStrong = 0x78FFFFFF, glassBorder = 0x66FFFFFF, glassHighlight = 0x80FFFFFF,
            text = 0xFF2E241C.toInt(), text2 = 0xCC3A2E24.toInt(), muted = 0x99705D49.toInt(),
            accent = 0xFFB85C3C.toInt(), ok = 0xFF6F8F72.toInt(), warn = 0xFFC9892E.toInt(), danger = 0xFFB23A2E.toInt(),
            chip = 0x66FFFDFC.toInt()
        )
    }

    fun glassBg(p: Palette, radiusDp: Int = 28, strong: Boolean = false): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp.toFloat()
        colors = intArrayOf(if (strong) p.glassStrong else p.glass, ColorUtils.setAlphaComponent(if (strong) p.glassStrong else p.glass, (Color.alpha(if (strong) p.glassStrong else p.glass) * .72f).toInt()))
        orientation = GradientDrawable.Orientation.TL_BR
        setStroke(1, p.glassBorder)
    }

    fun applyGlass(view: View, p: Palette, radiusDp: Int = 28, strong: Boolean = false) {
        val r = dpF(view.context, radiusDp)
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
            if (strong) p.glassStrong else p.glass,
            ColorUtils.setAlphaComponent(if (strong) p.glassStrong else p.glass, max(0, (Color.alpha(if (strong) p.glassStrong else p.glass) * .68f).toInt()))
        )).apply {
            cornerRadius = r
            setStroke(max(1, dp(view.context, 1)), p.glassBorder)
        }
        view.background = bg
        view.elevation = dpF(view.context, 6)
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, r)
            }
        }
    }

    fun applyChip(view: View, p: Palette, radiusDp: Int = 999) {
        val r = dpF(view.context, radiusDp)
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            setColor(p.chip)
            setStroke(max(1, dp(view.context, 1)), p.glassBorder)
        }
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) { outline.setRoundRect(0, 0, v.width, v.height, r) }
        }
    }

    fun metricTone(p: Palette, tone: WeatherMetric.Tone): Int = when (tone) {
        WeatherMetric.Tone.OK -> p.ok
        WeatherMetric.Tone.WARN -> p.warn
        WeatherMetric.Tone.DANGER -> p.danger
        WeatherMetric.Tone.ACCENT -> p.accent
    }
}
