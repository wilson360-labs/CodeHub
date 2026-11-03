package com.codehub.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import okhttp3.Call
import org.json.JSONObject
import java.util.Locale

/** Account-scoped profile and real usage summary for the native Android app. */
class NativeProfileActivity : Activity() {
    companion object {
        private const val LOGIN_REQUESTED = Activity.RESULT_FIRST_USER
        const val RESULT_LOGGED_OUT = Activity.RESULT_FIRST_USER + 1
    }

    private val token by lazy {
        intent.getStringExtra(NativeAiChatActivity.EXTRA_AUTH_TOKEN).orEmpty()
            .ifBlank { NativeAuthSessionStore.accessToken(this) }
    }
    private val repository by lazy { WileChatRepository(token) }
    private var activeCall: Call? = null
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private val muted = 0xFFAAB3C6.toInt()
    private val cyan = 0xFF50D7E8.toInt()
    private val indigo = 0xFF929BFF.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBars.fit(this)
        window.statusBarColor = 0xFF090D16.toInt()
        window.navigationBarColor = 0xFF090D16.toInt()
        window.decorView.setBackgroundColor(0xFF090D16.toInt())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        buildScreen()
        SystemBars.applyInsets(this)
        if (token.isBlank()) showSignedOut() else loadProfile()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun buildScreen() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF090D16.toInt())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        header.addView(text("‹", 30f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            contentDescription = "Volver"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(46), dp(48)))
        header.addView(text("Mi perfil", 18f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(58)))

        val scroll = ScrollView(this).apply { clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(28))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun loadProfile() {
        content.removeAllViews()
        content.addView(panel("Cargando tu perfil…"))
        activeCall = repository.get("${WileChatRepository.BASE_URL}/api/user/profile") { json, error ->
            runOnUiThread {
                activeCall = null
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (json == null || json.optBoolean("ok") != true) showError(error ?: json?.optString("error").orEmpty())
                else renderProfile(json.optJSONObject("profile") ?: JSONObject())
            }
        }
    }

    private fun renderProfile(profile: JSONObject) {
        content.removeAllViews()
        val displayName = profile.optString("name").ifBlank { "Usuario CodeHub" }
        val email = profile.optString("email").ifBlank { "Cuenta CodeHub" }
        val initials = displayName.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.toString() }
            .joinToString("").uppercase(Locale.ROOT).ifBlank { "C" }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(22))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF252448.toInt(), 0xFF17233A.toInt(), 0xFF10282F.toInt())).apply {
                cornerRadius = dp(25).toFloat()
                setStroke(dp(1), 0xFF3B4963.toInt())
            }
        }
        hero.addView(TextView(this).apply {
            text = initials
            gravity = Gravity.CENTER
            textSize = 25f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF525A9D.toInt()) }
        }, LinearLayout.LayoutParams(dp(72), dp(72)))
        hero.addView(text(displayName, 23f, Color.WHITE, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        hero.addView(text(email, 13f, 0xFFC0C9D9.toInt()).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        hero.addView(text("PERFIL PERSONAL · ANDROID", 9f, cyan, true).apply {
            gravity = Gravity.CENTER
            letterSpacing = .08f
            setPadding(dp(11), dp(7), dp(11), dp(7))
            background = rounded(0xFF123039.toInt(), dp(16), 0xFF27606A.toInt())
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        content.addView(hero, LinearLayout.LayoutParams(-1, -2))

        content.addView(sectionTitle("Tu progreso", "Actividad vinculada a tu cuenta"),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        val used = profile.optInt("aiMessagesToday").coerceAtLeast(0)
        val limit = profile.optInt("aiDailyLimit").coerceAtLeast(1)
        val usage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(0xFF151B29.toInt(), dp(18), 0xFF303A50.toInt())
        }
        val usageHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        usageHeader.addView(text("✦", 23f, indigo, true), LinearLayout.LayoutParams(dp(38), -2))
        usageHeader.addView(text("WIL.E hoy", 14f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        usageHeader.addView(text("$used / $limit", 13f, cyan, true))
        usage.addView(usageHeader)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = limit
            progress = used.coerceAtMost(limit)
            progressTintList = android.content.res.ColorStateList.valueOf(cyan)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF30384A.toInt())
        }
        usage.addView(bar, LinearLayout.LayoutParams(-1, dp(7)).apply { topMargin = dp(12) })
        usage.addView(text("Consultas usadas de tu límite diario", 11f, muted),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        content.addView(usage, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val toolsCount = profile.optInt("savedTools").coerceAtLeast(0)
        content.addView(statCard("⌘", "Herramientas guardadas", toolsCount.toString(), "Sincronizadas con tu cuenta"),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val createdAt = profile.optString("createdAt").take(10).ifBlank { "Disponible al crear tu cuenta" }
        content.addView(statCard("◷", "Miembro desde", createdAt, "Tu perfil se crea al registrarte"),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        content.addView(text("Las métricas de WIL.E se reinician cada día. Tus datos pertenecen a esta cuenta y no se comparten con otros usuarios.", 11f, muted).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        content.addView(action("Cerrar sesión") {
            activeCall = repository.post("${WileChatRepository.BASE_URL}/api/auth/logout", JSONObject()) { _, _ ->
                runOnUiThread {
                    activeCall = null
                    NativeAuthSessionStore.clear(this)
                    setResult(RESULT_LOGGED_OUT)
                    showSignedOut()
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(20) })
    }

    private fun showSignedOut() {
        content.removeAllViews()
        content.addView(panel("Tu perfil aparece cuando inicias sesión en CodeHub. Cada cuenta conserva sus propios datos y progreso."))
        content.addView(action("Iniciar sesión en CodeHub") {
            setResult(LOGIN_REQUESTED)
            finish()
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(14) })
    }

    private fun showError(message: String) {
        content.removeAllViews()
        content.addView(panel(message.ifBlank { "No se pudo cargar tu perfil. Revisa tu conexión e inténtalo de nuevo." }))
        content.addView(action("Reintentar") { loadProfile() }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(14) })
    }

    private fun sectionTitle(title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 18f, Color.WHITE, true))
        addView(text(subtitle, 12f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    private fun statCard(icon: String, title: String, value: String, detail: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(15), dp(14), dp(15), dp(14))
        background = rounded(0xFF151B29.toInt(), dp(17), 0xFF303A50.toInt())
        addView(text(icon, 22f, cyan, true), LinearLayout.LayoutParams(dp(42), -2))
        val labels = LinearLayout(this@NativeProfileActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 13f, Color.WHITE, true))
            addView(text(detail, 10f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(value, 14f, Color.WHITE, true))
    }

    private fun panel(message: String) = TextView(this).apply {
        text = message
        textSize = 14f
        setTextColor(0xFFD6DEEC.toInt())
        setLineSpacing(dp(4).toFloat(), 1f)
        setPadding(dp(17), dp(18), dp(17), dp(18))
        background = rounded(0xFF151B29.toInt(), dp(18), 0xFF303A50.toInt())
    }

    private fun action(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = rounded(0xFF4653A4.toInt(), dp(15), 0xFF7483E0.toInt())
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        setStroke(dp(1), stroke)
    }

    override fun onDestroy() {
        activeCall?.cancel()
        super.onDestroy()
    }
}
