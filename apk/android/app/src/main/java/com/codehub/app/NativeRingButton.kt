package com.codehub.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.min

/**
 * Botón circular estilo Orion: anillo de progreso alrededor del indicador de
 * descarga. Estados: idle (descarga disponible), progreso (arco verde + %) y
 * listo (check). 100% programático, sin recursos extras ni Compose, para que
 * compile en el pipeline CI de forma determinista.
 */
class NativeRingButton(context: Context) : View(context) {

    enum class State { IDLE, PROGRESS, DONE }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
    }

    private val arcRect = RectF()
    private var sweep: SweepGradient? = null
    private var sweepDiameter = Float.MAX_VALUE

    var state: State = State.IDLE
        private set
    private var progress = 0f
    private var textSizePx = 0f

    var ringColor = Color.parseColor("#38EF7D")
    var trackColor = Color.parseColor("#2A3444")
    var idleColor = Color.parseColor("#7BD9F5")
    var doneColor = Color.parseColor("#68F0A9")

    var clickAction: (() -> Unit)? = null

    init {
        setOnClickListener { clickAction?.invoke() }
    }

    /** Define tamaño en dp (ancho = alto). Llamar justo tras crear el botón. */
    fun sizeDp(dp: (Int) -> Int) {
        val diameter = dp(64)
        textSizePx = dp(20).toFloat()
        trackPaint.strokeWidth = dp(4).toFloat()
        progressPaint.strokeWidth = dp(4).toFloat()
        minimumWidth = diameter
        minimumHeight = diameter
    }

    fun setIdle() {
        state = State.IDLE
        progress = 0f
        contentDescription = "Instalar"
        invalidate()
    }

    /** progress 0..1; -1 significa indeterminado (tamaño de archivo desconocido). */
    fun setProgress(value: Float) {
        state = State.PROGRESS
        progress = if (value in 0f..1f) value else -1f
        contentDescription = if (value < 0) "Descargando" else "Descargando " + (progress * 100).toInt() + "%"
        invalidate()
    }

    fun setDone() {
        state = State.DONE
        progress = 1f
        contentDescription = "Instalación lista"
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.55f
        invalidate()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val d = min(width, height)
        sweepDiameter = d * 3f
        sweep = SweepGradient(d / 2f, d / 2f, intArrayOf(ringColor, idleColor, ringColor), null)
        arcRect.set(
            progressPaint.strokeWidth / 2f + 1f,
            progressPaint.strokeWidth / 2f + 1f,
            d - progressPaint.strokeWidth / 2f - 1f,
            d - progressPaint.strokeWidth / 2f - 1f,
        )
        iconPaint.textSize = textSizePx
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = min(width, height)
        val cx = width / 2f
        val cy = height / 2f
        trackPaint.color = trackColor
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)

        when (state) {
            State.IDLE -> {
                // Triángulo de descarga apuntando hacia abajo.
                iconPaint.color = idleColor
                val size = d * 0.18f
                val path = android.graphics.Path().apply {
                    moveTo(cx - size, cy - size * 0.8f)
                    lineTo(cx + size, cy - size * 0.8f)
                    lineTo(cx, cy + size)
                    close()
                }
                canvas.drawPath(path, iconPaint)
            }
            State.PROGRESS -> {
                progressPaint.shader = sweep
                if (progress < 0) {
                    canvas.drawArc(arcRect, 0f, 200f, false, progressPaint)
                } else {
                    canvas.drawArc(arcRect, -90f, 360f * progress, false, progressPaint)
                }
                iconPaint.color = Color.WHITE
                canvas.drawText(
                    if (progress < 0) "·" else "${(progress * 100).toInt()}" + "%",
                    cx, cy + textSizePx * 0.36f, iconPaint,
                )
            }
            State.DONE -> {
                progressPaint.color = doneColor
                canvas.drawArc(arcRect, -90f, 360f, false, progressPaint)
                iconPaint.color = doneColor
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeWidth = d * 0.045f
                    color = doneColor
                }
                canvas.drawLine(cx - d * 0.14f, cy, cx - d * 0.04f, cy + d * 0.10f, stroke)
                canvas.drawLine(cx - d * 0.04f, cy + d * 0.10f, cx + d * 0.16f, cy - d * 0.12f, stroke)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val d = min(
            resolveSize(minimumWidth, widthMeasureSpec),
            resolveSize(minimumHeight, heightMeasureSpec),
        )
        setMeasuredDimension(d, d)
    }
}
