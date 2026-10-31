package com.codehub.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Native Android home for the APK; web-only sections remain available through the site action. */
class NativeHomeView(context: Context, private val listener: Listener) : LinearLayout(context) {
    interface Listener {
        fun onOpenStore()
        fun onOpenMyApps()
        fun onOpenWebsite(path: String)
    }

    private val muted = 0xFFA7A9BC.toInt()
    private val green = 0xFF38EF7D.toInt()
    private val blue = 0xFF43B7FF.toInt()
    private var homeScroll: ScrollView? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF080810.toInt())

        addView(buildHeader(), LayoutParams(-1, dp(64)))

        val scroll = ScrollView(context).apply {
            clipToPadding = false
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        homeScroll = scroll
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(24))
        }
        content.addView(buildHero(), LayoutParams(-1, -2))
        content.addView(sectionTitle("Tu CodeHub", "Tus espacios principales, listos en Android"),
            LayoutParams(-1, -2).apply { topMargin = dp(28) })

        val primaryRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        primaryRow.addView(actionCard("▦", "CodeHub Store", "Apps open source para Android", green) {
            listener.onOpenStore()
        }, weightedCard())
        primaryRow.addView(actionCard("⬇", "Mis aplicaciones", "Instaladas y actualizaciones", blue) {
            listener.onOpenMyApps()
        }, weightedCard().apply { marginStart = dp(10) })
        content.addView(primaryRow, LayoutParams(-1, -2).apply { topMargin = dp(14) })

        val exploreRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        exploreRow.addView(actionCard("☼", "Clima", "Pronóstico y alertas", 0xFF8D9DFF.toInt()) {
            listener.onOpenWebsite("/#weather-section")
        }, weightedCard())
        exploreRow.addView(actionCard("⌘", "Herramientas", "Utilidades de CodeHub", 0xFFFFC857.toInt()) {
            listener.onOpenWebsite("/#services")
        }, weightedCard().apply { marginStart = dp(10) })
        content.addView(exploreRow, LayoutParams(-1, -2).apply { topMargin = dp(10) })

        content.addView(buildWebsiteCard(), LayoutParams(-1, -2).apply { topMargin = dp(18) })
        content.addView(TextView(context).apply {
            text = "CodeHub · Guatemala"
            textSize = 11f
            setTextColor(0xFF67697D.toInt())
            gravity = Gravity.CENTER
        }, LayoutParams(-1, -2).apply { topMargin = dp(22) })

        scroll.addView(content)
        addView(scroll, LayoutParams(-1, 0, 1f))
        addView(buildDock(), LayoutParams(-1, dp(68)))
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
            text = "●  NATIVO"
            textSize = 9f
            letterSpacing = .04f
            setTextColor(green)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(0xFF153326.toInt(), dp(20))
        })
    }

    private fun buildHero(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(20), dp(22), dp(20), dp(20))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF171A30.toInt(), 0xFF101522.toInt(), 0xFF0E191B.toInt()),
        ).apply { cornerRadius = dp(24).toFloat(); setStroke(dp(1), 0xFF292D43.toInt()) }

        addView(TextView(context).apply {
            text = "●  DISPONIBLE PARA PROYECTOS  ·  GUATEMALA"
            textSize = 9f
            letterSpacing = .045f
            setTextColor(green)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(0xFF153326.toInt(), dp(20))
        }, LayoutParams(-2, -2))

        addView(label("Hola, soy", 18f, 0xFFE0E2EF.toInt(), false),
            LayoutParams(-1, -2).apply { topMargin = dp(18) })
        addView(label("Wilson.E", 39f, Color.WHITE, true), LayoutParams(-1, -2).apply {
            topMargin = dp(1)
        })
        addView(label(
            "Diseño y construyo experiencias web, herramientas útiles y automatizaciones. Ahora también llevo CodeHub a Android con funciones nativas.",
            14f, 0xFFC0C2D1.toInt(), false,
        ).apply { setLineSpacing(dp(4).toFloat(), 1f) }, LayoutParams(-1, -2).apply {
            topMargin = dp(10)
        })

        val badges = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf("Frontend", "Backend", "UI / UX").forEach { item ->
            addViewToBadges(badges, item)
        }
        addView(badges, LayoutParams(-1, -2).apply { topMargin = dp(15) })

        val buttons = LinearLayout(context).apply { orientation = HORIZONTAL }
        buttons.addView(button("Explorar tienda", green, 0xFF08110C.toInt()) {
            listener.onOpenStore()
        }, LayoutParams(0, dp(46), 1f))
        buttons.addView(button("Mi CV", 0xFF25283B.toInt(), Color.WHITE) {
            listener.onOpenWebsite("/cv")
        }, LayoutParams(dp(88), dp(46)).apply { marginStart = dp(10) })
        addView(buttons, LayoutParams(-1, -2).apply { topMargin = dp(19) })
    }

    private fun addViewToBadges(container: LinearLayout, text: String) {
        container.addView(TextView(context).apply {
            this.text = text
            textSize = 10f
            setTextColor(0xFFBCC4E3.toInt())
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = rounded(0xFF20243A.toInt(), dp(10))
        }, LayoutParams(-2, -2).apply { marginEnd = dp(7) })
    }

    private fun sectionTitle(title: String, subtitle: String): View = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(label(title, 20f, Color.WHITE, true))
        addView(label(subtitle, 12f, muted, false), LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    private fun actionCard(icon: String, title: String, description: String, accent: Int, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(14), dp(14), dp(12), dp(14))
            background = rounded(0xFF12121E.toInt(), dp(18)).apply {
                setStroke(dp(1), 0xFF28283A.toInt())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(TextView(context).apply {
                text = icon
                textSize = 24f
                setTextColor(accent)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(label(title, 14f, Color.WHITE, true), LayoutParams(-1, -2).apply { topMargin = dp(9) })
            addView(label(description, 11f, muted, false).apply {
                maxLines = 2
                setLineSpacing(dp(2).toFloat(), 1f)
            }, LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }

    private fun buildWebsiteCard(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(14), dp(14))
        background = rounded(0xFF111622.toInt(), dp(18)).apply {
            setStroke(dp(1), 0xFF283246.toInt())
        }
        val copy = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(label("Explora todo CodeHub", 14f, Color.WHITE, true))
            addView(label("Comunidad, proyectos, clima y más", 11f, muted, false),
                LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        addView(copy, LayoutParams(0, -2, 1f))
        addView(button("Abrir", 0xFF20243A.toInt(), blue) {
            listener.onOpenWebsite("/")
        }, LayoutParams(dp(74), dp(40)))
    }

    private fun buildDock(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = GradientDrawable().apply {
            setColor(0xFF0D0D17.toInt())
            setStroke(dp(1), 0xFF202030.toInt())
        }
        addView(dockItem("⌂", "Inicio", green) { homeScroll?.smoothScrollTo(0, 0) }, LayoutParams(0, -1, 1f))
        addView(dockItem("▦", "Tienda", muted) { listener.onOpenStore() }, LayoutParams(0, -1, 1f))
        addView(dockItem("⬇", "Mis apps", muted) { listener.onOpenMyApps() }, LayoutParams(0, -1, 1f))
        addView(dockItem("◎", "Web", muted) { listener.onOpenWebsite("/") }, LayoutParams(0, -1, 1f))
    }

    private fun dockItem(icon: String, title: String, color: Int, onClick: () -> Unit): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(TextView(context).apply {
                text = icon
                textSize = 20f
                setTextColor(color)
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = title
                textSize = 9f
                setTextColor(color)
                gravity = Gravity.CENTER
        }, LayoutParams(-2, -2).apply { topMargin = dp(2) })
        }

    private fun button(text: String, backgroundColor: Int, foregroundColor: Int, onClick: () -> Unit): View =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(foregroundColor)
            background = rounded(backgroundColor, dp(13))
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
}
