package com.codehub.app.music.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView

/**
 * Paleta oscura fija del reproductor "slide" de CodeHub. La web retiro el
 * tema claro en v1.4, asi que el player mantiene la identidad oscura en
 * cualquier dispositivo (igual que MainActivity/DownloadsActivity) sin
 * depender del `uiMode` del sistema ni de ThemeColors.
 *
 * Incluye builders de vistas programaticas (pill/circle/ripple) reutilizados
 * por MusicPlayerActivity, PlayerSlide y FeedAdapter.
 */
object MusicTheme {

    const val BG = 0xFF080810.toInt()
    const val SURFACE = 0xFF13131C.toInt()
    const val SURFACE_2 = 0xFF1E1E2A.toInt()
    const val TEXT = 0xFFF1F2F6.toInt()
    const val MUTED = 0xFF9AA0B8.toInt()
    const val ACCENT = 0xFF6C9CFF.toInt()
    const val RIPPLE = 0x1FFFFFFF.toInt()

    fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    fun rounded(
        context: Context,
        color: Int,
        radiusDp: Int,
        strokeDp: Int = 0,
        strokeColor: Int = 0,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(context, radiusDp).toFloat()
        setColor(color)
        if (strokeDp > 0) setStroke(dp(context, strokeDp), strokeColor)
    }

    fun oval(context: Context, color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    fun ripple(context: Context, content: Drawable, rippleColor: Int = RIPPLE): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, null)

    fun tv(
        context: Context,
        text: String,
        sizeSp: Float = 15f,
        bold: Boolean = false,
        color: Int = TEXT,
        center: Boolean = false,
    ): TextView = TextView(context).apply {
        this.text = text
        this.textSize = sizeSp
        setTypeface(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(color)
        if (center) gravity = Gravity.CENTER
    }

    /** Boton tipo pill (fondo redondeado + ripple). Use para chips de accion. */
    fun pill(
        context: Context,
        text: String,
        sizeSp: Float = 13f,
        bg: Int = SURFACE_2,
        color: Int = TEXT,
    ): TextView {
        val t = tv(context, text, sizeSp, color = color, center = true)
        t.setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
        t.background = ripple(context, rounded(context, bg, 20))
        return t
    }

    /** Boton circular (glifo emoji/simbolo) con ripple. */
    fun circle(
        context: Context,
        glyph: String,
        sizeDp: Int,
        bg: Int = SURFACE_2,
        color: Int = TEXT,
        sizeSp: Float = 22f,
    ): TextView {
        val t = tv(context, glyph, sizeSp, color = color, center = true)
        t.background = ripple(context, oval(context, bg))
        t.layoutParams = ViewGroup.LayoutParams(dp(context, sizeDp), dp(context, sizeDp))
        return t
    }

    /** Imagen con esquinas redondeadas: fondo SURFACE_2 + clip al outline. */
    fun roundImage(context: Context, iv: ImageView, radiusDp: Int) {
        iv.background = rounded(context, SURFACE_2, radiusDp)
        iv.clipToOutline = true
        iv.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    /** ProgressBar/SeekBar teñido con el acento de CodeHub. */
    fun tintSeek(bar: SeekBar, color: Int = ACCENT) {
        val list = ColorStateList.valueOf(color)
        bar.progressTintList = list
        bar.thumbTintList = list
    }
}