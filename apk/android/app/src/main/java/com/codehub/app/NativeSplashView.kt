package com.codehub.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Static native startup screen using the official CodeHub logo. */
class NativeSplashView(context: Context) : FrameLayout(context) {
    fun interface OnDismissedListener {
        fun onDismissed()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val startedAt = SystemClock.elapsedRealtime()
    private var dismissRequested = false
    private var dismissed = false
    private var onDismissed: OnDismissedListener? = null

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.rgb(8, 11, 17))

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        content.addView(ImageView(context).apply {
            setImageResource(R.drawable.codehub_splash)
            contentDescription = "Logotipo oficial de CodeHub"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(148), dp(148)))
        content.addView(TextView(context).apply {
            text = "CodeHub"
            textSize = 28f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        content.addView(TextView(context).apply {
            text = "by Wilson.E · GUATEMALA"
            textSize = 10f
            letterSpacing = .08f
            setTextColor(Color.rgb(150, 163, 183))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
        addView(content, LayoutParams(-2, -2, Gravity.CENTER))
    }

    /** Kept for MainActivity compatibility; the static splash has no progress animation. */
    fun setLoadProgress(value: Int) = Unit

    fun setOnDismissed(callback: OnDismissedListener) {
        onDismissed = callback
    }

    /** Holds the static logo briefly so it does not flash past during startup. */
    fun dismiss() {
        if (dismissRequested || dismissed) return
        dismissRequested = true
        val remaining = (1_200L - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(0L)
        mainHandler.postDelayed({
            if (dismissed) return@postDelayed
            dismissed = true
            visibility = View.GONE
            onDismissed?.onDismissed()
            onDismissed = null
        }, remaining)
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
}
