package com.codehub.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
    interface Listener {
        fun onOpenStore()
        fun onOpenMyApps()
        fun onOpenCodeBeat()
        fun onOpenCodeStream()
        fun onOpenCopilot()
        fun onOpenProfile()
        fun onOpenCodeSu()
        fun onOpenWebsite(path: String)
    }

    private val muted = 0xFFA7A9BC.toInt()
    private val indigo = 0xFF818CF8.toInt()
    private val cyan = 0xFF22D3EE.toInt()
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
    private lateinit var weatherCard: View
    private lateinit var toolsCard: View
    private lateinit var optimizerCard: View
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
        setBackgroundColor(0xFF080B11.toInt())

        addView(buildHeader(), LayoutParams(-1, dp(64)))

        homeScroll = ScrollView(context).apply {
            clipToPadding = false
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(24))
        }
        addEntranceTarget(content, sectionTitle("Acceso rápido", "Tus apps principales, listas en Android"),
            LayoutParams(-1, -2).apply { topMargin = dp(4) })

        storeCard = actionCard(R.drawable.ic_home_store, "CodeHub Store", "Apps open source para Android", indigo) {
            listener.onOpenStore()
        }
        appsCard = actionCard(R.drawable.ic_home_apps, "Mis aplicaciones", "Instaladas y actualizaciones", cyan) {
            listener.onOpenMyApps()
        }
        addEntranceTarget(content, actionPair(storeCard, appsCard), LayoutParams(-1, -2).apply { topMargin = dp(12) })
        activityCard = buildActivityCard()
        addEntranceTarget(content, activityCard, LayoutParams(-1, -2).apply { topMargin = dp(12) })
        heroCard = buildHero()
        addEntranceTarget(content, heroCard, LayoutParams(-1, -2).apply { topMargin = dp(16) })

        codeBeatCard = actionCard(R.drawable.ic_home_music, "CodeBeat", "Música y extensiones", cyan) {
            listener.onOpenCodeBeat()
        }
        codeStreamCard = actionCard(R.drawable.ic_codestream, "CodeStream", "Videos, música y Shorts", cyan, tintIcon = false) {
            listener.onOpenCodeStream()
        }
        addEntranceTarget(content, actionPair(codeBeatCard, codeStreamCard), LayoutParams(-1, -2).apply { topMargin = dp(10) })

        copilotCard = featureCard(R.drawable.ic_home_copilot, "WIL.E COPILOT", "Asistente IA nativo", indigo) {
            listener.onOpenCopilot()
        }
        addEntranceTarget(content, copilotCard, LayoutParams(-1, -2).apply { topMargin = dp(10) })

        weatherCard = actionCard(R.drawable.ic_home_weather, "Clima", "Pronóstico y alertas", cyan) {
            listener.onOpenWebsite("/#weather-section")
        }
        toolsCard = actionCard(R.drawable.ic_home_tools, "Herramientas", "Utilidades de CodeHub", indigo) {
            listener.onOpenWebsite("/#services")
        }
        addEntranceTarget(content, actionPair(weatherCard, toolsCard), LayoutParams(-1, -2).apply { topMargin = dp(10) })

        optimizerCard = featureCard(R.drawable.ic_home_boost, "CodeSu", "Optimización Android con Shizuku", indigo) {
            listener.onOpenCodeSu()
        }
        addEntranceTarget(content, optimizerCard, LayoutParams(-1, -2).apply { topMargin = dp(10) })

        websiteCard = buildWebsiteCard()
        addEntranceTarget(content, websiteCard, LayoutParams(-1, -2).apply { topMargin = dp(18) })
        content.addView(TextView(context).apply {
            text = "CodeHub · Guatemala"
            textSize = 11f
            setTextColor(0xFF67697D.toInt())
            gravity = Gravity.CENTER
        }, LayoutParams(-1, -2).apply { topMargin = dp(22) })

        homeScroll.addView(content)
        addView(homeScroll, LayoutParams(-1, 0, 1f))
    }

    /** Starts a short staggered reveal after the native splash has faded away. */
    fun playEntrance() {
        if (entrancePlayed) return
        entrancePlayed = true
        refreshActivitySummary()
        entranceTargets.forEachIndexed { index, view ->
            val visibleBounds = Rect()
            if (!view.getGlobalVisibleRect(visibleBounds)) return@forEachIndexed
            view.alpha = 0f
            view.translationY = dp(14).toFloat()
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * 55L)
                .setDuration(360L)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
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
        tourSteps += Triple(copilotCard, "WIL.E COPILOT", "Asistente IA nativo para depurar, revisar código y generar README.")
        tourSteps += Triple(weatherCard, "Clima", "Pronóstico y alertas para tu ubicación.")
        tourSteps += Triple(toolsCard, "Herramientas", "Utilidades y servicios de CodeHub.")
        tourSteps += Triple(optimizerCard, "CodeSu", "Revisa apps activas y detén solo las que elijas. Requiere Shizuku autorizado; no borra datos ni promete aumentar la batería.")
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
            // Efecto "bombeo" breve sobre la sección marcada.
            target.animate().cancel()
            target.animate().scaleX(1.08f).scaleY(1.08f).setDuration(180).withEndAction {
                target.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }.start()

            val loc = IntArray(2); target.getLocationInWindow(loc)
            val host = IntArray(2); this@NativeHomeView.getLocationInWindow(host)
            val targetLeft = loc[0] - host[0]
            val targetTop = loc[1] - host[1]
            val screenW = this@NativeHomeView.width
            val screenH = this@NativeHomeView.height

            val bubble = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = GradientDrawable().apply {
                    setColor(0xFF111827.toInt())
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), 0xFF4B5D8A.toInt())
                }
                elevation = dp(8).toFloat()
            }
            bubble.addView(TextView(context).apply {
                text = title
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(0xFFFFFFFF.toInt())
            })
            bubble.addView(TextView(context).apply {
                text = desc
                textSize = 12f
                setTextColor(0xFFCBD5E1.toInt())
                setPadding(0, dp(6), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            val skip = Button(context).apply {
                text = "Saltar"
                textSize = 12f
                isAllCaps = false
                setTextColor(0xFF9AAFC7.toInt())
                background = null
                setOnClickListener { tourPrefs.edit().putBoolean("tour_done", true).apply(); dismissTour() }
            }
            val next = Button(context).apply {
                text = if (tourStepIndex == tourSteps.lastIndex) "Listo" else "Siguiente"
                textSize = 12f
                isAllCaps = false
                setTextColor(0xFFFFFFFF.toInt())
                background = roundRectLocal(0xFF5968E8.toInt(), 0, dp(12))
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
            val tCy = targetTop + target.height / 2
            val belowTop = targetTop + target.height + dp(18)
            val bubbleTop = if (belowTop + bubbleH <= screenH - dp(12)) belowTop else (targetTop - bubbleH - dp(18)).coerceAtLeast(dp(12))
            val bubbleLeft = (tCx - bubbleW / 2).coerceIn(dp(12), (screenW - bubbleW - dp(12)).coerceAtLeast(dp(12)))

            val bubbleParams = FrameLayout.LayoutParams(bubbleW, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = bubbleLeft
                topMargin = bubbleTop
            }

            // Línea conectora + flecha hacia la sección marcada.
            val bCx = bubbleLeft + bubbleW / 2
            val bCy = bubbleTop + bubbleH / 2
            val dx = (tCx - bCx).toFloat()
            val dy = (tCy - bCy).toFloat()
            val length = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toInt().coerceAtLeast(dp(8))
            val angle = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
            val line = View(context).apply {
                setBackgroundColor(0xFF818CF8.toInt())
            }
            root.addView(SpotlightScrim(context, RectF(
                (targetLeft - dp(5)).toFloat(), (targetTop - dp(5)).toFloat(),
                (targetLeft + target.width + dp(5)).toFloat(), (targetTop + target.height + dp(5)).toFloat(),
            )), FrameLayout.LayoutParams(-1, -1))
            root.addView(line, FrameLayout.LayoutParams(length, dp(2)).apply {
                leftMargin = bCx
                topMargin = bCy - dp(1)
            })
            line.pivotX = 0f
            line.pivotY = dp(1).toFloat() / 2f
            line.rotation = angle

            val arrow = TextView(context).apply {
                text = "➤"
                textSize = 14f
                setTextColor(0xFF818CF8.toInt())
                gravity = Gravity.CENTER
            }
            root.addView(arrow, FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                leftMargin = tCx - dp(10)
                topMargin = tCy - dp(10)
            })

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
        private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC050813.toInt() }
        private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        init { setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
            canvas.drawRoundRect(hole, dp(18).toFloat(), dp(18).toFloat(), clear)
            canvas.restoreToCount(layer)
        }
    }

    private fun roundRectLocal(fill: Int, stroke: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(fill); cornerRadius = radius.toFloat(); if (stroke > 0) setStroke(dp(1), stroke) }

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

    private fun buildActivityCard(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(16), dp(13), dp(16), dp(13))
        background = rippleSurface(0xFF101622.toInt(), dp(18), 0xFF2A3447.toInt())
        isClickable = true
        isFocusable = true
        contentDescription = "Tu actividad en CodeHub. Abrir Mis aplicaciones."
        setOnClickListener { listener.onOpenMyApps() }
        addView(label("TU ACTIVIDAD", 10f, cyan, true))
        val metrics = LinearLayout(context).apply {
            orientation = if (resources.configuration.screenWidthDp < 350 || resources.configuration.fontScale >= 1.2f) VERTICAL else HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        installedMetric = activityMetric(metrics, "Apps instaladas")
        downloadsMetric = activityMetric(metrics, "Historial CodeHub")
        addView(metrics, LayoutParams(-1, -2).apply { topMargin = dp(8) })
        activeDownloadsLabel = label("Comprobando descargas…", 11f, muted, false)
        addView(activeDownloadsLabel, LayoutParams(-1, -2).apply { topMargin = dp(7) })
    }

    private fun activityMetric(parent: LinearLayout, caption: String): TextView {
        val block = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val value = label("—", 20f, Color.WHITE, true)
        block.addView(value, LayoutParams(-2, -2))
        block.addView(label(caption, 11f, muted, false), LayoutParams(-2, -2).apply { marginStart = dp(8) })
        val params = if (parent.orientation == HORIZONTAL) LayoutParams(0, -2, 1f)
        else LayoutParams(-1, -2).apply { topMargin = dp(5) }
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
                addView(second, weightedCard().apply { marginStart = dp(10) })
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
            setPadding(dp(10), 0, 0, 0)
            addView(label("CodeHub", 17f, Color.WHITE, true))
            addView(label("ANDROID · OPEN SOURCE", 9f, muted, false))
        }
        addView(wordmark, LayoutParams(0, -2, 1f))
        guideButton = TextView(context).apply {
            text = "?"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(cyan)
            contentDescription = "Ver guía de CodeHub"
            background = rippleSurface(0xFF171C2B.toInt(), dp(22), 0xFF333B55.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { startTour() }
        }
        addView(guideButton, LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })
        profilePill = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(12), 0)
            contentDescription = "Abrir mi perfil"
            background = rippleSurface(0xFF171C2B.toInt(), dp(22), 0xFF333B55.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { listener.onOpenProfile() }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_home_profile)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(20), dp(20)))
            addView(label("Mi perfil", 10f, indigo, true), LayoutParams(-2, -2).apply { marginStart = dp(5) })
        }
        addView(profilePill, LayoutParams(-2, dp(40)).apply { marginStart = dp(8) })
    }

    private fun buildHero(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(18), dp(15), dp(18), dp(14))
        elevation = dp(4).toFloat()
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF1A1B36.toInt(), 0xFF111A2B.toInt(), 0xFF0C2029.toInt()),
        ).apply { cornerRadius = dp(26).toFloat(); setStroke(dp(1), 0xFF343652.toInt()) }

        addView(TextView(context).apply {
            text = "CODEHUB  ·  GUATEMALA"
            textSize = 9f
            letterSpacing = .1f
            setTextColor(cyan)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(0xFF102631.toInt(), dp(20))
        }, LayoutParams(-2, -2))

        addView(label("CodeHub desarrollado por Wilson.E", 22f, Color.WHITE, true), LayoutParams(-1, -2).apply {
            topMargin = dp(10)
        })
        addView(label(
            "Tu tienda y herramientas favoritas, reunidas en CodeHub para Android.",
            13f, 0xFFC0C2D1.toInt(), false,
        ).apply { maxLines = 2; setLineSpacing(dp(3).toFloat(), 1f) }, LayoutParams(-1, -2).apply {
            topMargin = dp(5)
        })
        addView(button("Ver perfil y proyectos  ↗", 0xFF25284A.toInt(), 0xFFE4E7FF.toInt()) {
            listener.onOpenWebsite("/cv")
        }, LayoutParams(-1, dp(42)).apply { topMargin = dp(10) })
    }

    private fun sectionTitle(title: String, subtitle: String): View = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(label(title, 20f, Color.WHITE, true))
        addView(label(subtitle, 12f, muted, false), LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    private fun actionCard(icon: Int, title: String, description: String, accent: Int, tintIcon: Boolean = true, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(14), dp(12), dp(14))
            background = rippleSurface(0xFF111521.toInt(), dp(18), 0xFF292D43.toInt())
            isClickable = true
            isFocusable = true
            elevation = dp(2).toFloat()
            contentDescription = "$title. $description"
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageResource(icon)
                if (tintIcon) imageTintList = ColorStateList.valueOf(accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(28), dp(28)))
            addView(label(title, 14f, Color.WHITE, true).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(-1, -2).apply { topMargin = dp(9) })
            addView(label(description, 11f, muted, false).apply {
                maxLines = 2
                setLineSpacing(dp(2).toFloat(), 1f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }

    private fun featureCard(icon: Int, title: String, description: String, accent: Int, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rippleSurface(0xFF131A29.toInt(), dp(18), 0xFF343B5A.toInt())
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $description"
            setOnClickListener { onClick() }
            addView(ImageView(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(dp(32), dp(32)))
            val copy = LinearLayout(context).apply {
                orientation = VERTICAL
                addView(label(title, 14f, Color.WHITE, true))
                addView(label(description, 11f, muted, false), LayoutParams(-1, -2).apply { topMargin = dp(3) })
            }
            addView(copy, LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
            addView(label("›", 24f, accent, true))
        }

    private fun buildWebsiteCard(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(14), dp(14))
        background = rippleSurface(0xFF121724.toInt(), dp(18), 0xFF2E354B.toInt())
        val copy = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(label("Explora todo CodeHub", 14f, Color.WHITE, true))
            addView(label("Comunidad, proyectos, clima y más", 11f, muted, false),
                LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        addView(copy, LayoutParams(0, -2, 1f))
        addView(button("Abrir", 0xFF20243A.toInt(), cyan) {
            listener.onOpenWebsite("/")
        }, LayoutParams(dp(74), dp(40)))
    }

    private fun button(text: String, backgroundColor: Int, foregroundColor: Int, onClick: () -> Unit): View =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(foregroundColor)
            background = rippleSurface(backgroundColor, dp(13), 0xFF3A3D56.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun weightedCard() = LayoutParams(0, -2, 1f)

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun rippleSurface(color: Int, radius: Int, stroke: Int) = RippleDrawable(
        ColorStateList.valueOf(0x24FFFFFF),
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), stroke)
        },
        null,
    )

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
}
