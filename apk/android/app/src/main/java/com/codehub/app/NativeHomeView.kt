package com.codehub.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
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
        fun onOpenWebsite(path: String)
    }

    private val muted = 0xFFA7A9BC.toInt()
    private val indigo = 0xFF818CF8.toInt()
    private val cyan = 0xFF22D3EE.toInt()
    private val entranceTargets = mutableListOf<View>()
    private var entrancePlayed = false
    private lateinit var installedMetric: TextView
    private lateinit var downloadsMetric: TextView
    private lateinit var activeDownloadsLabel: TextView
    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF080B11.toInt())

        addView(buildHeader(), LayoutParams(-1, dp(64)))

        val scroll = ScrollView(context).apply {
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

        val storeCard = actionCard(R.drawable.ic_home_store, "CodeHub Store", "Apps open source para Android", indigo) {
            listener.onOpenStore()
        }
        val appsCard = actionCard(R.drawable.ic_home_apps, "Mis aplicaciones", "Instaladas y actualizaciones", cyan) {
            listener.onOpenMyApps()
        }
        addEntranceTarget(content, actionPair(storeCard, appsCard), LayoutParams(-1, -2).apply { topMargin = dp(12) })
        addEntranceTarget(content, buildActivityCard(), LayoutParams(-1, -2).apply { topMargin = dp(12) })
        addEntranceTarget(content, buildHero(), LayoutParams(-1, -2).apply { topMargin = dp(16) })

        val codeBeatCard = actionCard(R.drawable.ic_home_music, "CodeBeat", "Música y extensiones", cyan) {
            listener.onOpenCodeBeat()
        }
        val codeStreamCard = actionCard(R.drawable.ic_codestream, "CodeStream", "Videos, música y Shorts", cyan, tintIcon = false) {
            listener.onOpenCodeStream()
        }
        addEntranceTarget(content, actionPair(codeBeatCard, codeStreamCard), LayoutParams(-1, -2).apply { topMargin = dp(10) })

        addEntranceTarget(content, featureCard(R.drawable.ic_home_copilot, "WIL.E COPILOT", "Asistente IA nativo", indigo) {
            listener.onOpenCopilot()
        }, LayoutParams(-1, -2).apply { topMargin = dp(10) })

        val weatherCard = actionCard(R.drawable.ic_home_weather, "Clima", "Pronóstico y alertas", cyan) {
            listener.onOpenWebsite("/#weather-section")
        }
        val toolsCard = actionCard(R.drawable.ic_home_tools, "Herramientas", "Utilidades de CodeHub", indigo) {
            listener.onOpenWebsite("/#services")
        }
        addEntranceTarget(content, actionPair(weatherCard, toolsCard), LayoutParams(-1, -2).apply { topMargin = dp(10) })

        addEntranceTarget(content, featureCard(R.drawable.ic_home_boost, "OPTIMIZACIÓN DEL SISTEMA", "Ram, batería, caché y bloatware con Shizuku", indigo) {
            listener.onOpenWebsite("/pages/tools.html#opt-card")
        }, LayoutParams(-1, -2).apply { topMargin = dp(10) })

        addEntranceTarget(content, buildWebsiteCard(), LayoutParams(-1, -2).apply { topMargin = dp(18) })
        content.addView(TextView(context).apply {
            text = "CodeHub · Guatemala"
            textSize = 11f
            setTextColor(0xFF67697D.toInt())
            gravity = Gravity.CENTER
        }, LayoutParams(-1, -2).apply { topMargin = dp(22) })

        scroll.addView(content)
        addView(scroll, LayoutParams(-1, 0, 1f))
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
        addView(TextView(context).apply {
            text = "APK NATIVA"
            textSize = 9f
            letterSpacing = .04f
            setTextColor(indigo)
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = rounded(0xFF1B1C36.toInt(), dp(20))
        })
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_home_profile)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            contentDescription = "Abrir perfil de usuario"
            background = rippleSurface(0xFF171C2B.toInt(), dp(22), 0xFF333B55.toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { listener.onOpenProfile() }
        }, LayoutParams(dp(42), dp(42)).apply { marginStart = dp(10) })
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

        addView(label("Hola, soy Wilson.E", 26f, Color.WHITE, true), LayoutParams(-1, -2).apply {
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
