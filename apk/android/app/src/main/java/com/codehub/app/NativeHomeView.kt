package com.codehub.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/** Native Android home for the APK; web-only sections remain available through the site action. */
class NativeHomeView(context: Context, private val listener: Listener) : LinearLayout(context) {
    private val englishUi: Boolean get() = context.resources.configuration.locales[0].language == "en"
    interface Listener {
        fun onOpenStore()
        fun onOpenMyApps()
        fun onOpenCodeBeat()
        fun onOpenCodeStream()
        fun onOpenCopilot()
        fun onOpenProfile()
        fun onOpenWeather()
        fun onOpenCodeSu()
        fun onEditShortcuts()
        fun onManagePrivacyVpn(start: Boolean)
        fun onForceStopApp()
        fun onOpenWebsite(path: String)
    }

    // Nocturno AMOLED: negro puro y texto blanco hueso. Terracota y salvia se conservan.
    private val bg = Color.BLACK
    private val surfaceRaised = 0xE6171310.toInt()
    private val textPrimary = 0xFFF4ECE1.toInt()
    private val secondaryText = 0xFFC2B09B.toInt()
    private val muted = 0xFF94836F.toInt()
    private val border = 0x3DB85C3C.toInt()
    private val accent = 0xFFB85C3C.toInt()
    private val accentSecondary = 0xFF6F8F72.toInt()
    private val sageBright = 0xFFAFC9B2.toInt()
    private val accentSoft = 0x266F8F72.toInt()
    private val entranceTargets = mutableListOf<View>()
    private val tourPrefs: SharedPreferences by lazy { context.getSharedPreferences("codehub_store", Context.MODE_PRIVATE) }
    private var tourWindow: PopupWindow? = null
    private lateinit var homeScroll: ScrollView
    private lateinit var storeCard: View
    private lateinit var appsCard: View
    private lateinit var activityCard: View
    private lateinit var heroCard: View
    private lateinit var codeBeatCard: View
    private lateinit var codeStreamCard: View
    private lateinit var copilotCard: View
    private lateinit var cvCard: View
    private lateinit var weatherCard: View
    private lateinit var toolsCard: View
    private lateinit var optimizerCard: View
    private lateinit var privacyCard: View
    private lateinit var privacyStatus: TextView
    private lateinit var forceStopCard: View
    private lateinit var websiteCard: View
    private lateinit var profilePill: View
    private lateinit var guideButton: View
    private var tourStepIndex = 0
    private val tourSteps = mutableListOf<Triple<View, String, String>>()
    private var entrancePlayed = false
    private lateinit var installedMetric: TextView
    private lateinit var downloadsMetric: TextView
    private lateinit var activeDownloadsLabel: TextView
    init {
        orientation = VERTICAL
        setBackgroundColor(bg)

        // Escena única detrás de la cuadrícula. El desenfoque real se aplica solo
        // a esta capa, nunca al texto, y las tarjetas translúcidas lo atraviesan.
        val stage = FrameLayout(context)
        val backdrop = HomeBackdropView(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) applyBackdropBlur(backdrop)
        stage.addView(backdrop, FrameLayout.LayoutParams(-1, -1))

        val foreground = LinearLayout(context).apply { orientation = VERTICAL }
        foreground.addView(buildHeader(), LayoutParams(-1, dp(64)))

        homeScroll = ScrollView(context).apply {
            clipToPadding = false
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(24))
        }
        storeCard = actionCard(R.drawable.ic_home_store, "CodeHub Store", "Aplicaciones de código abierto para Android", accent) {
            listener.onOpenStore()
        }
        appsCard = actionCard(R.drawable.ic_home_apps, "Mis aplicaciones", "Instaladas y actualizaciones", accent) {
            listener.onOpenMyApps()
        }
        addEntranceTarget(content, actionPair(storeCard, appsCard), LayoutParams(-1, -2).apply { topMargin = dp(12) })
        activityCard = buildActivityCard()
        addEntranceTarget(content, activityCard, LayoutParams(-1, -2).apply { topMargin = dp(12) })
        heroCard = buildHero()
        addEntranceTarget(content, heroCard, LayoutParams(-1, -2).apply { topMargin = dp(16) })

        codeBeatCard = actionCard(R.drawable.ic_home_music, "CodeBeat", "Música y extensiones", accent) {
            listener.onOpenCodeBeat()
        }
        codeStreamCard = actionCard(R.drawable.ic_codestream, "CodeStream", "Videos, música y Shorts", accent, tintIcon = false) {
            listener.onOpenCodeStream()
        }
        addEntranceTarget(content, actionPair(codeBeatCard, codeStreamCard), LayoutParams(-1, -2).apply { topMargin = dp(12) })

        copilotCard = featureCard(R.drawable.ic_jarvis_core, "JARVIS", "Asistente personal de voz y texto", accent, tintIcon = false) {
            listener.onOpenCopilot()
        }
        addEntranceTarget(content, copilotCard, LayoutParams(-1, -2).apply { topMargin = dp(12) })

        cvCard = featureCard(R.drawable.ic_home_cv, "Crear mi CV", "Generador guiado con IA", accent) {
            listener.onOpenWebsite("/cv-generador")
        }
        addEntranceTarget(content, cvCard, LayoutParams(-1, -2).apply { topMargin = dp(12) })

        weatherCard = actionCard(R.drawable.ic_home_weather, "Clima", "Pronóstico y alertas", accent) {
            listener.onOpenWeather()
        }
        toolsCard = actionCard(R.drawable.ic_home_tools, "Herramientas", "Utilidades de CodeHub", accent) {
            listener.onOpenWebsite("/#services")
        }
        addEntranceTarget(content, actionPair(weatherCard, toolsCard), LayoutParams(-1, -2).apply { topMargin = dp(12) })

        optimizerCard = featureCard(R.drawable.ic_home_boost, "CodeSu", "Optimización Android con Shizuku", accent) {
            listener.onOpenCodeSu()
        }
        addEntranceTarget(content, optimizerCard, LayoutParams(-1, -2).apply { topMargin = dp(12) })

        privacyCard = buildPrivacyCard()
        addEntranceTarget(content, privacyCard, LayoutParams(-1, -2).apply { topMargin = dp(12) })

        forceStopCard = featureCard(R.drawable.ic_home_power, "Forzar cierre de aplicación", "Abrir los ajustes Android para dormir CodeHub y sus servicios", accent) {
            listener.onForceStopApp()
        }
        addEntranceTarget(content, forceStopCard, LayoutParams(-1, -2).apply { topMargin = dp(12) })

        websiteCard = buildWebsiteCard()
        addEntranceTarget(content, websiteCard, LayoutParams(-1, -2).apply { topMargin = dp(18) })

        homeScroll.addView(content)
        foreground.addView(homeScroll, LayoutParams(-1, 0, 1f))

        stage.addView(foreground, FrameLayout.LayoutParams(-1, -1))
        addView(stage, LayoutParams(-1, -1))
    }

    @SuppressLint("NewApi")
    private fun applyBackdropBlur(view: View) {
        view.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
            dp(18).toFloat(), dp(18).toFloat(), Shader.TileMode.CLAMP))
    }

    /** One short reveal for the whole content block after the native splash fades. */
    fun playEntrance() {
        if (entrancePlayed) return
        entrancePlayed = true
        refreshActivitySummary()
        homeScroll.alpha = 0f
        homeScroll.translationY = dp(6).toFloat()
        homeScroll.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .start()
        postDelayed({ maybeStartTour() }, 1100)
    }

    private fun maybeStartTour() {
        if (tourPrefs.getBoolean("tour_done", false)) return
        startTour()
    }

    /** The help control can replay this native tour at any time. */
    private fun startTour() {
        if (tourWindow != null) return
        tourSteps.clear()
        tourSteps += Triple(storeCard, "CodeHub Store", "Apps open source verificadas: descarga APKs, gestiona actualizaciones y favoritos.")
        tourSteps += Triple(appsCard, "Mis aplicaciones", "Ve lo instalado, actualizaciones pendientes e historial de descargas.")
        tourSteps += Triple(activityCard, "Tu actividad", "Consulta cuántas apps tienes instaladas y el historial de descargas de CodeHub. Toca para abrir Mis aplicaciones.")
        tourSteps += Triple(heroCard, "CodeHub y tu perfil", "Esta portada reúne tus accesos. El botón de la tarjeta abre tu perfil web y tus proyectos.")
        tourSteps += Triple(codeBeatCard, "CodeBeat", "Música y extensiones para tu flujo de trabajo.")
        tourSteps += Triple(codeStreamCard, "CodeStream", "Videos, música y Shorts dentro de la app.")
        tourSteps += Triple(copilotCard, "JARVIS", "Tu asistente personal de voz y texto para depurar, revisar código y trabajar con documentos.")
        tourSteps += Triple(cvCard, "Crear mi CV", "Abre el generador guiado con IA para construir y mejorar tu currículum.")
        tourSteps += Triple(weatherCard, "Clima", "Pronóstico y alertas para tu ubicación.")
        tourSteps += Triple(toolsCard, "Herramientas", "Utilidades y servicios de CodeHub.")
        tourSteps += Triple(optimizerCard, "CodeSu", "Revisa apps activas y detén solo las que elijas. Requiere Shizuku autorizado; no borra datos ni promete aumentar la batería.")
        tourSteps += Triple(privacyCard, "CodeHub Shield", "Activa un filtro DNS local contra dominios de anuncios y rastreadores. Requiere permiso VPN de Android y puedes detenerlo cuando quieras.")
        tourSteps += Triple(forceStopCard, "Dormir CodeHub", "Android controla el cierre forzado. Este acceso abre Información de la app, donde puedes detener sus servicios; al abrir CodeHub otra vez se reanudan.")
        tourSteps += Triple(websiteCard, "Explora CodeHub", "Abre la experiencia web para comunidad, proyectos, clima y otras secciones.")
        tourSteps += Triple(profilePill, "Tu perfil", "Accede a tu perfil y proyectos de CodeHub.")
        tourStepIndex = 0
        showTourStep()
    }

    private fun showTourStep() {
        if (tourStepIndex >= tourSteps.size) {
            tourPrefs.edit().putBoolean("tour_done", true).apply()
            dismissTour()
            return
        }
        val (target, title, desc) = tourSteps[tourStepIndex]
        target.post {
            val visible = Rect()
            if (!target.getGlobalVisibleRect(visible) || visible.width() < target.width || visible.height() < target.height) {
                val descendant = Rect(0, 0, target.width, target.height)
                homeScroll.offsetDescendantRectToMyCoords(target, descendant)
                homeScroll.smoothScrollTo(0, (descendant.top - dp(80)).coerceAtLeast(0))
                target.postDelayed({ showTourStep() }, 260)
                return@post
            }
            // Realce sutil del paso actual: solo un descenso breve de alpha.
            target.animate().cancel()
            target.alpha = .88f
            target.animate().alpha(1f).setDuration(180).start()

            val loc = IntArray(2); target.getLocationInWindow(loc)
            val host = IntArray(2); this@NativeHomeView.getLocationInWindow(host)
            val targetLeft = loc[0] - host[0]
            val targetTop = loc[1] - host[1]
            val screenW = this@NativeHomeView.width
            val screenH = this@NativeHomeView.height

            val bubble = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = glassSurface(dp(16))
                elevation = dp(4).toFloat()
            }
            bubble.addView(TextView(context).apply {
                text = title
                textSize = 14f
                setTypeface(null, Typeface.NORMAL)
                setTextColor(textPrimary)
            })
            bubble.addView(TextView(context).apply {
                text = desc
                textSize = 12f
                setTextColor(secondaryText)
                setPadding(0, dp(8), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            val skip = Button(context).apply {
                text = "Saltar"
                textSize = 12f
                isAllCaps = false
                setTextColor(muted)
                background = null
                setOnClickListener { tourPrefs.edit().putBoolean("tour_done", true).apply(); dismissTour() }
            }
            val next = Button(context).apply {
                text = if (tourStepIndex == tourSteps.lastIndex) "Listo" else "Siguiente"
                textSize = 12f
                isAllCaps = false
                setTextColor(textPrimary)
                background = rounded(accent, dp(10))
                setOnClickListener { tourStepIndex++; showTourStep() }
            }
            row.addView(skip, LinearLayout.LayoutParams(-2, dp(40)))
            row.addView(next, LinearLayout.LayoutParams(-2, dp(40)).apply { marginStart = dp(8) })
            bubble.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

            val root = FrameLayout(context).apply { isClickable = true }

            val bubbleW = (screenW - dp(32)).coerceAtMost(dp(340))
            bubble.measure(MeasureSpec.makeMeasureSpec(bubbleW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(screenH, MeasureSpec.AT_MOST))
            val bubbleH = bubble.measuredHeight.coerceAtLeast(dp(130))
            val tCx = targetLeft + target.width / 2
            val belowTop = targetTop + target.height + dp(18)
            val bubbleTop = if (belowTop + bubbleH <= screenH - dp(12)) belowTop else (targetTop - bubbleH - dp(18)).coerceAtLeast(dp(12))
            val bubbleLeft = (tCx - bubbleW / 2).coerceIn(dp(12), (screenW - bubbleW - dp(12)).coerceAtLeast(dp(12)))

            val bubbleParams = FrameLayout.LayoutParams(bubbleW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = bubbleLeft
                topMargin = bubbleTop
            }

            // La flecha nace del borde de la burbuja y termina en el borde
            // del elemento resaltado, con la punta orientada hacia él.
            val bubbleAboveTarget = bubbleTop < targetTop
            val startX = tCx.coerceIn(bubbleLeft + dp(22), bubbleLeft + bubbleW - dp(22)).toFloat()
            val startY = if (bubbleAboveTarget) (bubbleTop + bubbleH).toFloat() else bubbleTop.toFloat()
            val endX = tCx.toFloat()
            val endY = if (bubbleAboveTarget) (targetTop - dp(5)).toFloat() else (targetTop + target.height + dp(5)).toFloat()
            root.addView(SpotlightScrim(context, RectF(
                (targetLeft - dp(5)).toFloat(), (targetTop - dp(5)).toFloat(),
                (targetLeft + target.width + dp(5)).toFloat(), (targetTop + target.height + dp(5)).toFloat(),
            )), FrameLayout.LayoutParams(-1, -1))
            root.addView(object : View(context) {
                private val connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = accent
                    strokeWidth = dp(1).toFloat()
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    style = Paint.Style.STROKE
                }
                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    canvas.drawLine(startX, startY, endX, endY, connectorPaint)
                    val angle = kotlin.math.atan2((endY - startY).toDouble(), (endX - startX).toDouble())
                    val headLength = dp(8).toFloat()
                    val spread = Math.toRadians(28.0)
                    val head = Path().apply {
                        moveTo(endX, endY)
                        lineTo(endX - headLength * kotlin.math.cos(angle - spread).toFloat(), endY - headLength * kotlin.math.sin(angle - spread).toFloat())
                        moveTo(endX, endY)
                        lineTo(endX - headLength * kotlin.math.cos(angle + spread).toFloat(), endY - headLength * kotlin.math.sin(angle + spread).toFloat())
                    }
                    canvas.drawPath(head, connectorPaint)
                }
            }, FrameLayout.LayoutParams(-1, -1))

            root.addView(bubble, bubbleParams)

            dismissTour()
            val pw = PopupWindow(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, true)
            pw.isOutsideTouchable = false
            pw.isFocusable = true
            pw.showAtLocation(this@NativeHomeView, Gravity.TOP or Gravity.START, 0, 0)
            tourWindow = pw
        }
    }

    private fun dismissTour() {
        tourWindow?.dismiss()
        tourWindow = null
    }

    private inner class SpotlightScrim(context: Context, private val hole: RectF) : View(context) {
        private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
        private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        init { setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            canvas.drawRoundRect(hole, dp(14).toFloat(), dp(14).toFloat(), clear)
            canvas.restoreToCount(layer)
        }
    }

    /** Smoked glass: translucent fill, terracotta rim, bronze inner rim and a fine top sheen. */
    private fun glassSurface(radius: Int): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(0x2AB85C3C.toInt()),
        GlassDrawable(radius),
        null,
    )

    private inner class GlassDrawable(private val radius: Int) : GradientDrawable() {
        private val bronze = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x338C6A3F.toInt()
            strokeWidth = dp(1).toFloat()
        }
        private val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, dp(12).toFloat(),
                intArrayOf(0x24F4ECE1.toInt(), 0x00F4ECE1.toInt()),
                null, Shader.TileMode.CLAMP,
            )
        }

        init {
            shape = RECTANGLE
            orientation = Orientation.TOP_BOTTOM
            colors = intArrayOf(surfaceRaised, 0xC50B0908.toInt())
            cornerRadius = radius.toFloat()
            setStroke(dp(1), border)
        }

        override fun draw(canvas: Canvas) {
            super.draw(canvas)
            val r = bounds
            val inset = dp(1).toFloat()
            canvas.drawRoundRect(
                r.left + inset, r.top + inset, r.right - inset, r.bottom - inset,
                (radius - dp(1)).toFloat(), (radius - dp(1)).toFloat(), bronze,
            )
            canvas.save()
            canvas.clipPath(Path().apply {
                addRoundRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(),
                    radius.toFloat(), radius.toFloat(), Path.Direction.CW)
            })
            canvas.drawRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.top + dp(12).toFloat(), sheen)
            canvas.restore()
        }
    }

    /** One restrained night-greenhouse scene on pure black, kept near the edges. */
    private inner class HomeBackdropView(context: Context) : View(context) {
        private val topoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x354F6B3C.toInt()
            strokeWidth = dp(1).toFloat()
        }
        private val veinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x46D9A65C.toInt()
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val veinFinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x32B85C3C.toInt()
            strokeWidth = dp(1).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val dewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x8CE8C46A.toInt()
        }
        private val dewCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xAAFFF0C8.toInt()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(bg)
            drawContours(canvas)
            drawLeaf(canvas)
            drawDew(canvas)
        }

        private fun drawContours(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            for (i in 0 until 8) {
                val baseY = h * (0.62f + i * 0.055f)
                val path = Path().apply {
                    moveTo(-w * 0.05f, baseY)
                    quadTo(w * 0.18f, baseY - h * (0.05f + i * 0.004f), w * 0.38f, baseY + h * 0.012f)
                    quadTo(w * 0.62f, baseY + h * 0.05f, w * 0.82f, baseY - h * 0.018f)
                    quadTo(w * 1.02f, baseY - h * 0.06f, w * 1.08f, baseY - h * 0.02f)
                }
                canvas.drawPath(path, topoPaint)
            }
        }

        private fun drawLeaf(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            canvas.drawPath(Path().apply {
                moveTo(w * 1.08f, h * 1.02f)
                quadTo(w * 0.92f, h * 0.86f, w * 0.74f, h * 0.70f)
            }, veinPaint)
            for (i in 0..6) {
                val t = i / 6f
                val x = w * (1.08f - 0.34f * t)
                val y = h * (1.02f - 0.32f * t + 0.02f * kotlin.math.sin(t * 3f))
                val len = w * (0.12f + 0.06f * kotlin.math.sin(t * kotlin.math.PI.toFloat()))
                canvas.drawPath(Path().apply {
                    moveTo(x, y)
                    quadTo(x - len * 0.45f, y - len * 0.05f, x - len, y - len * 0.42f)
                }, veinFinePaint)
                canvas.drawPath(Path().apply {
                    moveTo(x, y)
                    quadTo(x - len * 0.2f, y + len * 0.25f, x - len * 0.55f, y + len * 0.5f)
                }, veinFinePaint)
            }
        }

        private fun drawDew(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return
            val density = resources.displayMetrics.density
            val drops = arrayOf(
                floatArrayOf(0.08f, 0.12f, 2.2f), floatArrayOf(0.91f, 0.18f, 1.6f),
                floatArrayOf(0.06f, 0.44f, 1.4f), floatArrayOf(0.95f, 0.52f, 2.4f),
                floatArrayOf(0.14f, 0.78f, 1.8f), floatArrayOf(0.86f, 0.92f, 1.5f),
                floatArrayOf(0.55f, 0.05f, 1.3f), floatArrayOf(0.42f, 0.96f, 1.7f),
            )
            drops.forEach { drop ->
                val x = w * drop[0]
                val y = h * drop[1]
                val r = drop[2] * density
                canvas.drawCircle(x, y, r, dewPaint)
                canvas.drawCircle(x - r * 0.25f, y - r * 0.25f, r * 0.35f, dewCorePaint)
            }
        }
    }

    /** Updates the home panel from on-device package and download history data. */
    fun refreshActivitySummary() {
        if (!entrancePlayed || !::installedMetric.isInitialized || !::downloadsMetric.isInitialized || !::activeDownloadsLabel.isInitialized) return
        Thread({
            val installedCount = runCatching {
                @Suppress("DEPRECATION")
                val userApps = context.packageManager.getInstalledApplications(0)
                userApps.count { app ->
                    app.packageName != context.packageName &&
                        (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
                }
            }.getOrDefault(0)
            val downloadCount = NativeApkInsights.downloads(context).size
            val activeDownloadCount = NativeStoreApkInstaller.transfers.value.count { (_, state) ->
                state is ApkTransferState.Downloading || state is ApkTransferState.Verifying
            }
            post {
                if (!isAttachedToWindow) return@post
                installedMetric.text = installedCount.toString()
                downloadsMetric.text = downloadCount.toString()
                activeDownloadsLabel.text = if (activeDownloadCount == 0) "Sin descargas en curso" else "$activeDownloadCount descarga${if (activeDownloadCount == 1) "" else "s"} en curso"
            }
        }, "CodeHub-home-summary").start()
    }

    fun refreshPrivacyStatus() {
        if (!::privacyStatus.isInitialized) return
        val active = context.getSharedPreferences(CodeHubDnsVpnService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(CodeHubDnsVpnService.KEY_ACTIVE, false)
        privacyStatus.text = if (active) "● VPN conectada · anuncios y rastreadores filtrados" else "○ VPN desconectada · protección DNS apagada"
        privacyStatus.setTextColor(if (active) accentSecondary else muted)
    }

    private fun buildPrivacyCard(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = glassSurface(dp(14))
        val heading = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_home_privacy)
            imageTintList = ColorStateList.valueOf(accentSecondary)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LayoutParams(dp(32), dp(32)))
        val copy = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(label("CodeHub Shield", 15f, textPrimary, true))
            addView(label("VPN DNS · privacidad móvil", 11f, muted, false), LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        heading.addView(copy, LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        addView(heading)
        privacyStatus = label("Comprobando protección…", 10f, muted, false)
        addView(privacyStatus, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
        actions.addView(button("Activar", accentSecondary, textPrimary) { listener.onManagePrivacyVpn(true) },
            LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8); topMargin = dp(8) })
        actions.addView(button("Detener", accentSoft, textPrimary) { listener.onManagePrivacyVpn(false) },
            LayoutParams(0, dp(40), 1f).apply { marginStart = dp(8); topMargin = dp(8) })
        addView(actions)
        refreshPrivacyStatus()
    }

    private fun buildActivityCard(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = glassSurface(dp(14))
        isClickable = true
        isFocusable = true
        contentDescription = "Tu actividad en CodeHub. Abrir Mis aplicaciones."
        setOnClickListener { listener.onOpenMyApps() }
        addView(label("TU ACTIVIDAD", 10f, textPrimary, true))
        val metrics = LinearLayout(context).apply {
            orientation = if (resources.configuration.screenWidthDp < 350 || resources.configuration.fontScale >= 1.2f) VERTICAL else HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        installedMetric = activityMetric(metrics, "Aplicaciones instaladas")
        downloadsMetric = activityMetric(metrics, "Historial CodeHub")
        addView(metrics, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        activeDownloadsLabel = label("Comprobando descargas…", 11f, muted, false)
        addView(activeDownloadsLabel, LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun activityMetric(parent: LinearLayout, caption: String): TextView {
        val block = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val value = label("—", 18f, textPrimary, false)
        block.addView(value, LayoutParams(-2, -2))
        block.addView(label(caption, 11f, muted, false), LayoutParams(-2, -2).apply { marginStart = dp(8) })
        val params = if (parent.orientation == HORIZONTAL) LayoutParams(0, -2, 1f)
        else LayoutParams(-1, -2).apply { topMargin = dp(4) }
        parent.addView(block, params)
        return value
    }

    private fun actionPair(first: View, second: View): View {
        val stack = resources.configuration.screenWidthDp < 350 || resources.configuration.fontScale >= 1.2f
        return LinearLayout(context).apply {
            orientation = if (stack) VERTICAL else HORIZONTAL
            if (stack) {
                addView(first, LayoutParams(-1, -2))
                addView(second, LayoutParams(-1, -2).apply { topMargin = dp(8) })
            } else {
                addView(first, weightedCard())
                addView(second, weightedCard().apply { marginStart = dp(12) })
            }
        }
    }

    private fun addEntranceTarget(parent: LinearLayout, view: View, params: LayoutParams) {
        parent.addView(view, params)
        entranceTargets.add(view)
    }

    private fun buildHeader(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), 0, dp(20), 0)
        val logo = ImageView(context).apply {
            setImageResource(R.drawable.ic_launcher_real)
            contentDescription = "CodeHub"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        addView(logo, LayoutParams(dp(38), dp(38)))
        val wordmark = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(8), 0, 0, 0)
            addView(label("CodeHub", 17f, textPrimary, false))
            addView(label("ANDROID · OPEN SOURCE", 9f, muted, false))
        }
        addView(wordmark, LayoutParams(0, -2, 1f))
        guideButton = TextView(context).apply {
            text = "?"
            textSize = 18f
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER
            setTextColor(accent)
            contentDescription = "Ver guía de CodeHub"
            background = glassSurface(dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { startTour() }
        }
        addView(guideButton, LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })
        profilePill = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
            contentDescription = "Abrir mi perfil"
            background = glassSurface(dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { listener.onOpenProfile() }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_home_profile)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(20), dp(20)))
            addView(label("Mi perfil", 10f, accent, true), LayoutParams(-2, -2).apply { marginStart = dp(4) })
        }
        addView(profilePill, LayoutParams(-2, dp(40)).apply { marginStart = dp(8) })
    }

    private fun buildHero(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = glassSurface(dp(18))

        addView(TextView(context).apply {
            text = "CODEHUB · GUATEMALA"
            textSize = 9f
            setTextColor(sageBright)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rounded(accentSoft, dp(10))
        }, LayoutParams(-2, -2))

        addView(label("CodeHub desarrollado por Wilson.E", 20f, textPrimary, false), LayoutParams(-1, -2).apply {
            topMargin = dp(16)
        })
        addView(label(
            "Tu tienda y herramientas favoritas, reunidas en CodeHub para Android.",
            13f, secondaryText, false,
        ).apply { maxLines = 2; setLineSpacing(dp(4).toFloat(), 1f) }, LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        })
        addView(button("Ver perfil y proyectos ↗", accent, textPrimary) {
            listener.onOpenWebsite("/cv")
        }, LayoutParams(-1, dp(44)).apply { topMargin = dp(16) })
    }

    private fun sectionTitle(title: String, subtitle: String,
                             actionLabel: String? = null, onAction: (() -> Unit)? = null): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(title, 18f, textPrimary, false), LayoutParams(0, -2, 1f))
                if (actionLabel != null && onAction != null) {
                    addView(label(actionLabel, 12f, accent, true).apply {
                        setPadding(dp(6), dp(4), dp(2), dp(4))
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { onAction() }
                    }, LayoutParams(-2, -2))
                }
            }, LayoutParams(-1, -2))
            addView(label(subtitle, 12f, muted, false), LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }

    private fun actionCard(icon: Int, title: String, description: String, accent: Int, tintIcon: Boolean = true, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = glassSurface(dp(14))
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $description"
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageResource(icon)
                if (tintIcon) imageTintList = ColorStateList.valueOf(accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(28), dp(28)))
            addView(label(title, 14f, textPrimary, true).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(label(description, 11f, muted, false).apply {
                maxLines = 2
                setLineSpacing(dp(4).toFloat(), 1f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }

    private fun featureCard(icon: Int, title: String, description: String, accent: Int,
                            tintIcon: Boolean = true, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = glassSurface(dp(14))
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $description"
            setOnClickListener { onClick() }
            if (tintIcon) {
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(accent)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(dp(32), dp(32)))
            } else {
                val iconFrame = FrameLayout(context).apply {
                    background = rounded(accentSoft, dp(10))
                    addView(ImageView(context).apply {
                        setImageResource(icon)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
                }
                addView(iconFrame, LayoutParams(dp(48), dp(48)))
            }
            val copy = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(label(title, 14f, textPrimary, true))
                addView(label(description, 11f, muted, false), LayoutParams(-1, -2).apply { topMargin = dp(4) })
            }
            addView(copy, LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            addView(label("›", 24f, accent, true))
        }

    private fun buildWebsiteCard(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = glassSurface(dp(14))
        val copy = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(label("Explora todo CodeHub", 14f, textPrimary, true))
            addView(label("Comunidad, proyectos, clima y más", 11f, muted, false),
                LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        addView(copy, LayoutParams(0, -2, 1f))
        addView(button("Abrir", accent, textPrimary) {
            listener.onOpenWebsite("/")
        }, LayoutParams(dp(72), dp(40)))
    }

    private fun button(text: String, backgroundColor: Int, foregroundColor: Int, onClick: () -> Unit): View =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(foregroundColor)
            background = rippleSurface(backgroundColor, dp(10), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        // Peso 500 solo en etiquetas pequeñas; los títulos grandes quedan en 400.
        if (bold && size <= 12f) typeface = Typeface.DEFAULT_BOLD
    }

    private fun weightedCard() = LayoutParams(0, -2, 1f)

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun rippleSurface(color: Int, radius: Int, stroke: Int) = RippleDrawable(
        ColorStateList.valueOf(border),
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), stroke)
        },
        null,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
}
