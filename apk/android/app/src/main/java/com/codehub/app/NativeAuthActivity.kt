package com.codehub.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.Call
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import android.util.Base64

/** Native email authentication. Turnstile and Google OAuth use the system browser, never an embedded WebView. */
class NativeAuthActivity : Activity() {
    companion object {
        const val RESULT_AUTHENTICATED = Activity.RESULT_OK
        private const val API = WileChatRepository.BASE_URL
        private const val TURNSTILE_URL = "https://wilson360-labs.vercel.app/native-auth-check"
    }

    private enum class Action { LOGIN, REGISTER, GOOGLE }
    private var action: Action? = null
    private var pendingState = ""
    private var pkceVerifier = ""
    private var challengeVerifier = ""
    private var activeCall: Call? = null
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var confirmInput: EditText
    private lateinit var confirmLabel: TextView
    private lateinit var status: TextView
    private lateinit var submit: TextView
    private lateinit var root: LinearLayout
    private val repository by lazy { WileChatRepository("") }
    private var registerMode = false

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
        buildUi()
        SystemBars.applyInsets(this)
        if (savedInstanceState != null && savedInstanceState.getString("auth_action") == Action.GOOGLE.name) {
            action = Action.GOOGLE
            pendingState = savedInstanceState.getString("auth_state").orEmpty()
            pkceVerifier = savedInstanceState.getString("auth_verifier").orEmpty()
        }
        consumeCallback(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (action == Action.GOOGLE && pendingState.isNotBlank() && pkceVerifier.isNotBlank()) {
            outState.putString("auth_action", Action.GOOGLE.name)
            outState.putString("auth_state", pendingState)
            outState.putString("auth_verifier", pkceVerifier)
        }
        super.onSaveInstanceState(outState)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF090D16.toInt())
            setPadding(dp(22), dp(12), dp(22), dp(20))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("‹", 30f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(42), dp(48)))
        top.addView(text("Cuenta CodeHub", 18f, Color.WHITE, true))
        root.addView(top)

        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(20), 0, dp(12)) }
        form.addView(text("Tu cuenta, en todos tus espacios", 28f, Color.WHITE, true),
            LinearLayout.LayoutParams(-1, -2))
        form.addView(text("Inicia sesión para sincronizar tu perfil y progreso de CodeHub.", 14f, 0xFFAAB3C6.toInt()).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(0xFF151B29.toInt(), dp(15), 0xFF303A50.toInt())
        }
        val loginTab = text("Iniciar sesión", 13f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { setMode(false) }
        }
        val registerTab = text("Crear cuenta", 13f, 0xFFAAB3C6.toInt(), true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { setMode(true) }
        }
        tabs.addView(loginTab, LinearLayout.LayoutParams(0, dp(42), 1f))
        tabs.addView(registerTab, LinearLayout.LayoutParams(0, dp(42), 1f))
        form.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })

        form.addView(fieldLabel("Correo electrónico"), topMargin(22))
        emailInput = input("tu@correo.com", false).apply { inputType = 0x00000021; imeOptions = EditorInfo.IME_ACTION_NEXT }
        form.addView(emailInput, fieldParams())
        form.addView(fieldLabel("Contraseña"), topMargin(15))
        passwordInput = input("Mínimo 8 caracteres", true).apply { imeOptions = EditorInfo.IME_ACTION_DONE }
        form.addView(passwordInput, fieldParams())
        confirmLabel = fieldLabel("Repite tu contraseña").apply { visibility = View.GONE }
        confirmInput = input("Confirma tu contraseña", true).apply { visibility = View.GONE }
        form.addView(confirmLabel, topMargin(15))
        form.addView(confirmInput, fieldParams())

        submit = actionButton("Iniciar sesión") { submitCredentials() }
        form.addView(submit, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(22) })
        form.addView(text("o continúa con", 12f, 0xFF8995AA.toInt()).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        form.addView(actionButton("G  Continuar con Google") { beginGoogle() },
            LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
        status = text("La verificación anti bots se abrirá de forma segura en tu navegador.", 12f, 0xFF8995AA.toInt()).apply {
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        form.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        scroll.addView(form)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        setMode(false)
    }

    private fun setMode(register: Boolean) {
        registerMode = register
        confirmLabel.visibility = if (register) View.VISIBLE else View.GONE
        confirmInput.visibility = if (register) View.VISIBLE else View.GONE
        submit.text = if (register) "Crear mi perfil CodeHub" else "Iniciar sesión"
        status.text = if (register) "Tu perfil personal se crea al registrarte." else "La verificación anti bots se abrirá de forma segura en tu navegador."
        (submit.parent as? View)?.invalidate()
    }

    private fun submitCredentials() {
        val email = emailInput.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val password = passwordInput.text?.toString().orEmpty()
        val confirm = confirmInput.text?.toString().orEmpty()
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) { status.text = "Escribe un correo válido."; return }
        if (password.length < 8) { status.text = "La contraseña debe tener al menos 8 caracteres."; return }
        if (registerMode && password != confirm) { status.text = "Las contraseñas no coinciden."; return }
        action = if (registerMode) Action.REGISTER else Action.LOGIN
        pendingState = randomUrlSafe(24)
        challengeVerifier = randomUrlSafe(32)
        val challenge = sha256UrlSafe(challengeVerifier)
        status.text = "Preparando una verificación segura…"
        activeCall = repository.post("$API/api/auth/mobile-challenge/start",
            JSONObject().put("state", pendingState).put("challenge", challenge)) { json, error -> runOnUiThread {
            activeCall = null
            if (json?.optBoolean("ok") != true) {
                status.text = error ?: json?.optString("error").orEmpty().ifBlank { "No se pudo iniciar la verificación." }
                return@runOnUiThread
            }
            status.text = "Completa la comprobación en el navegador para continuar…"
            val challengeUrl = Uri.parse(TURNSTILE_URL).buildUpon().appendQueryParameter("state", pendingState).build()
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, challengeUrl)) }
                .onFailure { status.text = "No encontré un navegador para verificar la solicitud." }
        } }
    }

    private fun beginGoogle() {
        action = Action.GOOGLE
        pendingState = randomUrlSafe(24)
        pkceVerifier = randomUrlSafe(32)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(pkceVerifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        status.text = "Continuando con Google en el navegador…"
        val url = Uri.parse("$API/api/auth/google").buildUpon()
            .appendQueryParameter("native_state", pendingState)
            .appendQueryParameter("native_challenge", challenge)
            .build()
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
            .onFailure { status.text = "No encontré un navegador para iniciar sesión con Google." }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeCallback(intent)
    }

    private fun consumeCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "codehub" || uri.host != "auth") return
        val returnedState = uri.getQueryParameter("state").orEmpty()
        if (pendingState.isBlank() || returnedState != pendingState) {
            status.text = "La verificación expiró o no coincide. Intenta de nuevo."
            return
        }
        when (uri.path) {
            "/turnstile" -> {
                val code = uri.getQueryParameter("code").orEmpty()
                if (code.isBlank()) { status.text = "La verificación no se completó. ${uri.getQueryParameter("error").orEmpty()}"; return }
                val pending = action
                if (pending != Action.LOGIN && pending != Action.REGISTER) return
                exchangeTurnstileCode(pending, code)
            }
            "/google" -> {
                val oneTimeCode = uri.getQueryParameter("token").orEmpty()
                if (oneTimeCode.isBlank()) { status.text = "Google no completó el inicio de sesión. ${uri.getQueryParameter("error").orEmpty()}"; return }
                exchangeGoogleCode(oneTimeCode)
            }
        }
    }

    private fun exchangeTurnstileCode(pending: Action, code: String) {
        setBusy(true, "Confirmando la verificación…")
        val body = JSONObject().put("state", pendingState).put("code", code).put("verifier", challengeVerifier)
        activeCall = repository.post("$API/api/auth/mobile-challenge/exchange", body) { json, error -> runOnUiThread {
            activeCall = null
            val turnstileToken = json?.optString("turnstileToken").orEmpty()
            if (json?.optBoolean("ok") != true || turnstileToken.isBlank()) {
                setBusy(false, "")
                status.text = error ?: json?.optString("error").orEmpty().ifBlank { "La verificación expiró. Inténtalo de nuevo." }
                challengeVerifier = ""
                pendingState = ""
                return@runOnUiThread
            }
            challengeVerifier = ""
            sendCredentials(pending, turnstileToken)
        } }
    }

    private fun sendCredentials(currentAction: Action, turnstileToken: String) {
        setBusy(true, "Verificando tu cuenta…")
        val payload = JSONObject()
            .put("email", emailInput.text?.toString()?.trim()?.lowercase(Locale.ROOT))
            .put("password", passwordInput.text?.toString())
            .put("turnstileToken", turnstileToken)
        val path = if (currentAction == Action.REGISTER) "/api/auth/register" else "/api/auth/login"
        activeCall = repository.post("$API$path", payload) { json, error -> runOnUiThread {
            activeCall = null
            setBusy(false, "")
            if (json == null || json.optBoolean("ok") != true) {
                status.text = error ?: json?.optString("error").orEmpty().ifBlank { "No se pudo completar la autenticación." }
                return@runOnUiThread
            }
            if (json.optBoolean("needsConfirmation")) {
                action = null
                pendingState = ""
                passwordInput.setText("")
                confirmInput.setText("")
                status.text = "Cuenta creada. Confirma el correo y después inicia sesión."
                return@runOnUiThread
            }
            val session = json.optJSONObject("session")
            if (session == null || session.optString("access_token").isBlank()) {
                status.text = "La cuenta se creó; confirma el correo para iniciar sesión."
                return@runOnUiThread
            }
            finishAuthenticated(session, json.optJSONObject("user"))
        } }
    }

    private fun exchangeGoogleCode(code: String) {
        setBusy(true, "Conectando con tu cuenta…")
        val body = JSONObject().put("token", code).put("code_verifier", pkceVerifier)
        activeCall = repository.post("$API/api/auth/google/session", body) { json, error -> runOnUiThread {
            activeCall = null
            setBusy(false, "")
            val session = json?.optJSONObject("session")
            if (session == null || session.optString("access_token").isBlank()) {
                status.text = error ?: json?.optString("error").orEmpty().ifBlank { "No se pudo completar el inicio de sesión con Google." }
            } else finishAuthenticated(session, json?.optJSONObject("user"))
        } }
    }

    private fun finishAuthenticated(session: JSONObject, user: JSONObject?) {
        val email = user?.optString("email").orEmpty()
        val normalizedUser = JSONObject()
            .put("id", user?.optString("id").orEmpty())
            .put("email", email)
            .put("name", user?.optString("name").orEmpty().ifBlank { email.substringBefore('@').ifBlank { "Usuario" } })
        NativeAuthSessionStore.save(this, session, normalizedUser)
        action = null
        pendingState = ""
        pkceVerifier = ""
        challengeVerifier = ""
        setResult(RESULT_OK)
        finish()
    }

    private fun setBusy(busy: Boolean, message: String) {
        submit.isEnabled = !busy
        submit.alpha = if (busy) .65f else 1f
        if (message.isNotBlank()) status.text = message
    }

    private fun input(hintText: String, password: Boolean) = EditText(this).apply {
        hint = hintText
        textSize = 15f
        setTextColor(Color.WHITE)
        setHintTextColor(0xFF78849A.toInt())
        setPadding(dp(15), 0, dp(15), 0)
        background = rounded(0xFF141B29.toInt(), dp(14), 0xFF303A50.toInt())
        inputType = if (password) 0x00000081 else 0x00000021
        isSingleLine = true
    }

    private fun fieldLabel(label: String) = text(label, 12f, 0xFFD9E0ED.toInt(), true)
    private fun fieldParams() = LinearLayout.LayoutParams(-1, dp(50))
    private fun topMargin(value: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(value) }

    private fun actionButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
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

    private fun randomUrlSafe(bytes: Int): String = Base64.encodeToString(
        ByteArray(bytes).also { SecureRandom().nextBytes(it) },
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun sha256UrlSafe(value: String): String = Base64.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII)),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )

    override fun onDestroy() {
        activeCall?.cancel()
        super.onDestroy()
    }
}
