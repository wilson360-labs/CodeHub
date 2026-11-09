package com.codehub.app

import android.app.Activity
import android.app.AlertDialog
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.ScaleAnimation
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TextSwitcher
import android.widget.Toast
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Native WIL.E COPILOT client. AI routing, tools, limits and memory remain server-owned. */
class NativeAiChatActivity : Activity(), TextToSpeech.OnInitListener {
    companion object {
        const val EXTRA_AUTH_TOKEN = "native_wile_auth_token"
        const val EXTRA_AUTH_ACCOUNT_ID = "native_wile_auth_account_id"
        private const val API = WileChatRepository.BASE_URL
        private const val PICK_ATTACHMENT = 7101
        private const val SPEECH_INPUT = 7102
        private const val PREFS = "native_wile_chat"
        private const val MIGRATION_PREFS = "native_wile_chat_meta_v2"
        private const val LEGACY_CLEARED_KEY = "legacy_unscoped_history_cleared"
        private const val SESSION_KEY = "session_id"
        private const val HISTORY_KEY = "history"
        private const val MODE_KEY = "mode"
        private const val MAX_PDF_PAGES = 5

        @JvmStatic
        fun clearHistoryForAccount(context: Context, accountId: String) {
            if (accountId.isBlank()) return
            context.getSharedPreferences(conversationPreferencesName(accountId), Context.MODE_PRIVATE)
                .edit().clear().apply()
        }

        private fun conversationPreferencesName(accountId: String): String {
            if (accountId.isBlank()) return "native_wile_chat_v2_guest"
            val digest = MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            val key = buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(hex[value ushr 4])
                    append(hex[value and 0x0f])
                }
            }
            return "native_wile_chat_v2_$key"
        }
    }

    private data class ChatMessage(val role: String, var text: String, val mediaUrl: String? = null)
    private enum class Mode(val id: String, val label: String, val prefix: String = "") {
        AUTO("auto", "Auto"),
        DEV("dev", "Código", "[MODO CÓDIGO] Actúa como ingeniero de software senior. Diagnostica antes de proponer cambios, entrega soluciones verificables, señala riesgos y pide datos solo cuando bloqueen una respuesta correcta. Explica con claridad y no inventes APIs ni resultados de pruebas.\n\n"),
        IMAGE("img", "Imagen", "[MODO IMAGEN] Ayuda a crear, analizar o editar imágenes con instrucciones visuales concretas. Si la petición requiere generación, usa la herramienta de imagen disponible y explica cualquier límite.\n\n"),
        DOC("doc", "Documentos", "[MODO DOCUMENTOS] Basa las respuestas en el documento adjunto, cita o identifica sus secciones cuando sea posible, y separa los datos explícitos de las inferencias.\n\n"),
        TRANSLATE("translate", "Traductor", "[MODO TRADUCTOR] Traduce con precisión entre idiomas, conserva el tono y el formato, y aclara los matices que no tengan una equivalencia directa.\n\n"),
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val messages = mutableListOf<ChatMessage>()
    private var sessionId = ""
    private var activeMode = Mode.AUTO
    private var pendingAttachment: JSONObject? = null
    private var pendingAttachmentLabel = ""
    private var activeCall: Call? = null
    private var readRepliesAloud = false
    private var handsFreeEnabled = false
    private val authToken by lazy {
        intent.getStringExtra(EXTRA_AUTH_TOKEN).orEmpty().ifBlank { NativeAuthSessionStore.accessToken(this) }
    }
    private val accountId by lazy {
        intent.getStringExtra(EXTRA_AUTH_ACCOUNT_ID).orEmpty()
            .ifBlank { NativeAuthSessionStore.accountId(this) }
            .ifBlank { NativeAuthSessionStore.accountIdForToken(authToken) }
    }
    private val chatPreferences by lazy {
        getSharedPreferences(conversationPreferencesName(accountId), MODE_PRIVATE)
    }
    private val repository by lazy { WileChatRepository(authToken) }
    private var thinkingView: View? = null
    private var thinkingLabelView: TextView? = null
    private val thinkingAnimations = mutableListOf<ObjectAnimator>()
    private var streamingBodyView: TextView? = null
    private lateinit var messageList: LinearLayout
    private lateinit var messageScroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: ImageView
    private lateinit var status: TextView
    private lateinit var attachLabel: TextView
    private lateinit var modeRow: LinearLayout
    private lateinit var emiVoiceControls: LinearLayout
    private lateinit var quickActionsContainer: View
    private lateinit var micButton: ImageView
    private lateinit var micHalo: View
    private lateinit var clearChatButton: ImageView
    private var brandMorphSwitcher: TextSwitcher? = null
    private var brandMorphIndex = 0
    private var sendButtonBusy = false
    private val brandMorphPhrases = listOf(
        "CodeHub",
        "Developer",
        "Full Stack",
        "Freelancer",
        "Guatemala"
    )
    private val brandMorphTick = object : Runnable {
        override fun run() {
            val switcher = brandMorphSwitcher ?: return
            brandMorphIndex = (brandMorphIndex + 1) % brandMorphPhrases.size
            switcher.setText(brandMorphPhrases[brandMorphIndex])
            switcher.postDelayed(this, 3000L)
        }
    }
    private lateinit var tts: TextToSpeech
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        clearLegacyUnscopedHistoryOnce()
        SystemBars.fit(this)
        window.statusBarColor = 0xFF090D16.toInt()
        window.navigationBarColor = 0xFF090D16.toInt()
        window.decorView.setBackgroundColor(0xFF090D16.toInt())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        sessionId = chatPreferences.getString(SESSION_KEY, null)
            ?: "android-${UUID.randomUUID()}".also {
                chatPreferences.edit().putString(SESSION_KEY, it).apply()
            }
        activeMode = Mode.entries.firstOrNull { it.id == chatPreferences.getString(MODE_KEY, "auto") } ?: Mode.AUTO
        readRepliesAloud = chatPreferences.getBoolean("emi_auto_speak", chatPreferences.getBoolean("read_replies", false))
        handsFreeEnabled = chatPreferences.getBoolean("emi_e2e_voice", false)
        if (handsFreeEnabled) readRepliesAloud = true
        loadHistory()
        buildUi()
        renderHistory()
        tts = TextToSpeech(this, this)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF090D16.toInt())
            setPadding(dp(14), dp(8), dp(14), dp(10))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_ai_back)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = rippleActionBackground(0xFF111A27.toInt(), 0xFF2B3A50.toInt())
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "Volver"
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
            installPressFeedback(this)
        }, LinearLayout.LayoutParams(dp(42), dp(48)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val morphLine = TextSwitcher(this).apply {
            setFactory {
                text(brandMorphPhrases.first(), 18f, Color.WHITE, true).apply {
                    letterSpacing = .025f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
            }
            setCurrentText(brandMorphPhrases.first())
            setInAnimation(morphAnimation(true))
            setOutAnimation(morphAnimation(false))
            contentDescription = "Marca CodeHub con animación de texto"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            brandMorphSwitcher = this
        }
        brand.addView(morphLine, LinearLayout.LayoutParams(-1, -2))
        brand.addView(text("WIL.E COPILOT · IA personal", 10.5f, 0xFF98A8BF.toInt()))
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        clearChatButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_ai_trash)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFB8C5D8.toInt())
            scaleType = ImageView.ScaleType.CENTER
            background = rippleActionBackground(0xFF151D2B.toInt(), 0xFF354258.toInt())
            contentDescription = "Borrar conversación"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                animate().cancel()
                animate().rotation(-12f).setDuration(90L).withEndAction {
                    animate().rotation(0f).setDuration(150L).withEndAction { confirmClearChat() }.start()
                }.start()
            }
            installPressFeedback(this)
        }
        top.addView(clearChatButton, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(4) })
        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_ai_more)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFCAD4E8.toInt())
            scaleType = ImageView.ScaleType.CENTER
            background = rippleActionBackground(0xFF111A27.toInt(), 0xFF2B3A50.toInt())
            contentDescription = "Opciones del chat"
            isClickable = true
            isFocusable = true
            setOnClickListener { showMenu() }
            installPressFeedback(this)
        }, LinearLayout.LayoutParams(dp(42), dp(48)))
        root.addView(top)

        status = text("WIL.E · enrutamiento y herramientas de CodeHub", 11f, 0xFF8E9BB0.toInt()).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(0xFF111A27.toInt(), dp(12), 0xFF222E40.toInt())
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val modeScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(modeRow)
        }
        root.addView(modeScroll, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
        renderModes()

        emiVoiceControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = rounded(0xFF101A27.toInt(), dp(16), 0xFF26384A.toInt())
        }
        root.addView(emiVoiceControls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        renderEmiVoiceControls()

        messageScroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        messageList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(1), dp(10), dp(1), dp(18))
        }
        messageScroll.addView(messageList)
        root.addView(messageScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(5) })

        val quick = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Depurar", "Code review", "Explicar", "README").forEachIndexed { index, title ->
            quick.addView(chip(title) {
                val prompts = listOf("Ayúdame a depurar este problema: ", "Haz un code review de este código: ", "Explícame esto paso a paso: ", "Genera un README profesional para este proyecto")
                input.setText(prompts[index])
                input.setSelection(input.text.length)
                input.requestFocus()
            }, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(7) })
        }
        quickActionsContainer = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            addView(quick)
        }
        root.addView(quickActionsContainer, LinearLayout.LayoutParams(-1, dp(38)).apply { bottomMargin = dp(7) })

        attachLabel = text("", 11f, 0xFF8CCBFF.toInt()).apply {
            visibility = View.GONE
            setOnClickListener { clearAttachment() }
        }
        root.addView(attachLabel, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(9), dp(9), dp(8))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF182334.toInt(), 0xFF111824.toInt())).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0xFF3A4A61.toInt())
            }
        }
        input = EditText(this).apply {
            hint = "Escribe tu pregunta o pega código…"
            setHintTextColor(0xFF8492A8.toInt())
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(2), dp(5), dp(2), dp(5))
            setBackgroundColor(Color.TRANSPARENT)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setHorizontallyScrolling(false)
            minLines = 2
            maxLines = 6
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEND || (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && !event.isShiftPressed)) {
                    sendMessage(); true
                } else false
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(-1, -2))
        val composeActions = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        composeActions.addView(smallIconAction(R.drawable.ic_ai_attach, "Adjuntar imagen o PDF") { chooseAttachment() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        micHalo = View(this).apply {
            background = actionIconBackground(Color.TRANSPARENT, 0xFF55DFC1.toInt())
            alpha = 0f
        }
        micButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_ai_mic)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFFE5FFF8.toInt())
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF277D75.toInt(), 0xFF16504F.toInt())).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), 0xFF62DCC2.toInt())
            }
            elevation = dp(2).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val micAction = FrameLayout(this).apply {
            contentDescription = "Dictar mensaje"
            isClickable = true
            isFocusable = true
            clipChildren = false
            clipToPadding = false
            foreground = rippleForeground(0x55B8FFF0.toInt())
            setOnClickListener { animateMicPulse(); startSpeechInput() }
            addView(micHalo, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
            addView(micButton, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
            installPressFeedback(this)
        }
        composeActions.addView(micAction, LinearLayout.LayoutParams(dp(50), dp(50)))
        composeActions.addView(text("Enter enviar · Shift+Enter nueva línea", 10f, 0xFF7F8DA3.toInt()).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), 0, dp(4), 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        sendButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_ai_send)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            foreground = rippleForeground(0x66FFFFFF)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF7D8AFF.toInt(), 0xFF5868E8.toInt())).apply {
                cornerRadius = dp(21).toFloat()
                setStroke(dp(1), 0xFFB0B9FF.toInt())
            }
            scaleType = ImageView.ScaleType.CENTER
            elevation = dp(3).toFloat()
            contentDescription = "Enviar mensaje"
            isClickable = true
            setOnClickListener { if (activeCall != null) cancelRequest() else sendMessage() }
            installPressFeedback(this)
        }
        composeActions.addView(sendButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(3) })
        composer.addView(composeActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        root.addView(composer)

        setContentView(root)
        SystemBars.applyInsets(this)
        renderHistory()
    }

    private fun renderModes() {
        modeRow.removeAllViews()
        Mode.entries.forEach { mode ->
            val on = mode == activeMode
            val button = chip(mode.label, on) { activeMode = mode; chatPreferences.edit().putString(MODE_KEY, mode.id).apply(); renderModes() }
            modeRow.addView(button, LinearLayout.LayoutParams(-2, dp(36)).apply { marginEnd = dp(7) })
        }
    }

    private fun morphAnimation(entering: Boolean) = AnimationSet(true).apply {
        addAnimation(AlphaAnimation(if (entering) 0f else 1f, if (entering) 1f else 0f))
        val fromScale = if (entering) .90f else 1f
        val toScale = if (entering) 1f else .90f
        addAnimation(ScaleAnimation(fromScale, toScale, fromScale, toScale, 1, .5f, 1, .5f))
        duration = if (entering) 360L else 220L
        interpolator = DecelerateInterpolator()
    }

    private fun actionIconBackground(fill: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        shape = GradientDrawable.OVAL
        setStroke(dp(1), stroke)
    }

    private fun rippleForeground(color: Int) = RippleDrawable(
        android.content.res.ColorStateList.valueOf(color),
        null,
        GradientDrawable().apply { setColor(Color.WHITE); shape = GradientDrawable.OVAL },
    )

    private fun rippleActionBackground(fill: Int, stroke: Int) = RippleDrawable(
        android.content.res.ColorStateList.valueOf(0x55DCE8FF.toInt()),
        actionIconBackground(fill, stroke),
        GradientDrawable().apply { setColor(Color.WHITE); shape = GradientDrawable.OVAL },
    )

    private fun animateMicPulse() {
        if (!::micButton.isInitialized || !::micHalo.isInitialized) return
        micButton.animate().cancel()
        micButton.animate().rotation(-7f).scaleX(.92f).scaleY(.92f).setDuration(100L).withEndAction {
            if (!isFinishing && !isDestroyed) {
                micButton.animate().rotation(0f).scaleX(1f).scaleY(1f).setDuration(220L)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }.start()
        micHalo.animate().cancel()
        micHalo.scaleX = .78f
        micHalo.scaleY = .78f
        micHalo.alpha = .78f
        micHalo.animate().scaleX(1.16f).scaleY(1.16f).alpha(0f).setDuration(560L)
            .setInterpolator(DecelerateInterpolator()).withEndAction {
                micHalo.scaleX = 1f
                micHalo.scaleY = 1f
            }.start()
    }

    private fun renderEmiVoiceControls() {
        if (!::emiVoiceControls.isInitialized) return
        emiVoiceControls.removeAllViews()
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val indicator = View(this).apply {
            background = GradientDrawable().apply {
                setColor(if (handsFreeEnabled || readRepliesAloud) 0xFF61D8B5.toInt() else 0xFF64748B.toInt())
                shape = GradientDrawable.OVAL
            }
        }
        heading.addView(indicator, LinearLayout.LayoutParams(dp(7), dp(7)))
        heading.addView(text("EMI.VOICE", 11f, 0xFFE2EAF5.toInt(), true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(7) })
        heading.addView(text("· voz nativa", 11f, 0xFF8999AE.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(text(if (handsFreeEnabled) "MODO ACTIVO" else if (readRepliesAloud) "ACTIVA" else "DESACTIVADA", 9f,
            if (handsFreeEnabled || readRepliesAloud) 0xFF77E4C2.toInt() else 0xFF75839A.toInt(), true))
        emiVoiceControls.addView(heading)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(voiceToggle(
            R.drawable.ic_voice_speaker, "Respuesta por voz", "WIL.E lee la respuesta", readRepliesAloud
        ) { toggleAutoSpeak() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(5); topMargin = dp(7) })
        actions.addView(voiceToggle(
            R.drawable.ic_voice_handsfree, "Manos libres", "Dicta y envía", handsFreeEnabled
        ) { toggleHandsFree() }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(5); topMargin = dp(7) })
        emiVoiceControls.addView(actions)
    }

    private fun voiceToggle(icon: Int, title: String, subtitle: String, enabled: Boolean, action: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(5), dp(7), dp(5))
            background = rounded(if (enabled) 0xFF18372F.toInt() else 0xFF151F2D.toInt(), dp(13),
                if (enabled) 0xFF3A8D78.toInt() else 0xFF2C3A4D.toInt())
            isClickable = true
            isFocusable = true
            contentDescription = "$title, ${if (enabled) "activado" else "desactivado"}"
            setOnClickListener { action() }
            addView(ImageView(this@NativeAiChatActivity).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(if (enabled) 0xFF8BE7C9.toInt() else 0xFFB6C4D8.toInt())
                scaleType = ImageView.ScaleType.CENTER
            }, LinearLayout.LayoutParams(dp(24), dp(24)))
            val labels = LinearLayout(this@NativeAiChatActivity).apply { orientation = LinearLayout.VERTICAL }
            labels.addView(text(title, 10f, if (enabled) 0xFFE4FFF6.toInt() else 0xFFE0E8F3.toInt(), true))
            labels.addView(text(subtitle, 9f, if (enabled) 0xFF8DCCB7.toInt() else 0xFF8997AB.toInt()))
            addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
            installPressFeedback(this)
        }

    private fun persistVoicePreferences() {
        chatPreferences.edit()
            .putBoolean("emi_auto_speak", readRepliesAloud)
            .putBoolean("read_replies", readRepliesAloud)
            .putBoolean("emi_e2e_voice", handsFreeEnabled)
            .apply()
        renderEmiVoiceControls()
    }

    private fun toggleAutoSpeak() {
        readRepliesAloud = !readRepliesAloud
        if (!readRepliesAloud) handsFreeEnabled = false
        persistVoicePreferences()
    }

    private fun toggleHandsFree() {
        handsFreeEnabled = !handsFreeEnabled
        if (handsFreeEnabled) {
            readRepliesAloud = true
            persistVoicePreferences()
            startSpeechInput()
        } else {
            persistVoicePreferences()
        }
    }

    private fun sendMessage() {
        val raw = input.text?.toString()?.trim().orEmpty()
        if (activeCall != null) return
        if (raw.isEmpty() && pendingAttachment == null) return
        if (raw.length > 1000 || (!raw.startsWith("/") && raw.length + activeMode.prefix.length > 1000)) {
            val limit = (1000 - if (raw.startsWith("/")) 0 else activeMode.prefix.length).coerceAtLeast(0)
            Toast.makeText(this, "Este modo admite hasta $limit caracteres por mensaje.", Toast.LENGTH_LONG).show()
            return
        }
        input.setText("")
        if (raw.startsWith("/")) {
            handleCommand(raw)
            return
        }
        val textValue = raw.ifBlank {
            when {
                pendingAttachment?.has("pdfText") == true -> "Lee el documento adjunto y resume sus ideas principales."
                pendingAttachment?.has("images") == true -> "Describe y analiza el archivo adjunto."
                else -> "Describe y analiza la imagen adjunta."
            }
        }
        if (pendingAttachment == null && isImageGenerationRequest(raw)) {
            messages.add(ChatMessage("user", raw))
            persistHistory(); renderHistory()
            generateImage(raw)
            return
        }
        val userText = textValue + if (pendingAttachmentLabel.isNotBlank()) "\n\nAdjunto: $pendingAttachmentLabel" else ""
        messages.add(ChatMessage("user", userText))
        val request = JSONObject().put("message", activeMode.prefix + textValue).put("sessionId", sessionId).put("memory", true)
        pendingAttachment?.let { attachment ->
            if (attachment.has("image")) request.put("image", attachment.getString("image"))
            if (attachment.has("images")) request.put("images", attachment.getJSONArray("images"))
            if (attachment.has("pdfText")) request.put("pdfText", attachment.getString("pdfText"))
        }
        clearAttachment()
        val assistant = ChatMessage("assistant", "")
        messages.add(assistant)
        persistHistory()
        renderHistory()
        showThinking("WIL.E está preparando una respuesta")
        scrollToBottom()
        status.text = "Conectando con los proveedores de IA…"
        setSendButtonState(true)
        streamChat(request, assistant)
    }

    private fun streamChat(payload: JSONObject, assistant: ChatMessage) {
        var streamError: String? = null
        var providerName = ""
        var emiStatus = ""
        activeCall = repository.streamChat(
            "$API/api/chat/stream",
            payload,
            onEvent = { event, data ->
                when (event) {
                    "chunk" -> data.optString("text").takeIf { it.isNotEmpty() }?.let { chunk -> runOnUiThread {
                        finishThinking()
                        if (assistant.text.isBlank()) status.text = "WIL.E · generando respuesta en vivo…"
                        assistant.text += chunk
                        val body = streamingBodyView ?: createStreamingBody().also { streamingBodyView = it }
                        body.append(chunk)
                        scrollToBottom()
                    } }
                    "error" -> streamError = data.optString("error").ifBlank { "WIL.E no pudo responder." }
                    "done" -> {
                        providerName = data.optString("model").substringAfterLast('/').takeIf { it.isNotBlank() }.orEmpty()
                        data.optJSONObject("emi")?.let { emi -> emiStatus = "${emi.optInt("used")} / ${emi.optInt("limit")} hoy" }
                    }
                }
            },
            onFinished = { networkError -> runOnUiThread {
                val error = networkError ?: streamError
                finishThinking()
                if (assistant.text.isBlank()) assistant.text = error ?: "No llegó una respuesta. Intenta de nuevo."
                if (error != null) status.text = error
                else status.text = listOf(
                    "WIL.E",
                    providerName.takeIf { it.isNotBlank() }?.let { "modelo $it" },
                    emiStatus.takeIf { it.isNotBlank() }?.let { "$it mensajes" },
                ).filterNotNull().joinToString(" · ")
                activeCall = null
                setSendButtonState(false)
                persistHistory(); renderHistory()
                streamingBodyView = null
                if (error == null && readRepliesAloud && ttsReady && assistant.text.isNotBlank()) {
                    tts.speak(assistant.text, TextToSpeech.QUEUE_FLUSH, null, "wile-reply")
                }
            } },
        )
    }

    private fun cancelRequest() {
        activeCall?.cancel()
        activeCall = null
        finishThinking()
        setSendButtonState(false)
        status.text = "Respuesta detenida"
        if (messages.lastOrNull()?.role == "assistant" && messages.last().text.isBlank()) messages.removeLast()
        persistHistory(); renderHistory()
        streamingBodyView = null
    }

    private fun showThinking(label: String) {
        if (!::messageList.isInitialized) return
        thinkingLabelView?.text = label
        if (thinkingView != null) return

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(10), dp(14), dp(10))
            background = rounded(0xFF171F2D.toInt(), dp(16), 0xFF2A4654.toInt())
        }
        val spinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFF67D8E8.toInt())
            contentDescription = "WIL.E está generando una respuesta"
        }
        row.addView(spinner, LinearLayout.LayoutParams(dp(18), dp(18)))
        val labelView = text(label, 12f, 0xFFD4E2EE.toInt(), true)
        thinkingLabelView = labelView
        row.addView(labelView, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(9) })

        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        repeat(3) { index ->
            val dot = text("•", 16f, 0xFF67D8E8.toInt(), true).apply { gravity = Gravity.CENTER }
            dots.addView(dot, LinearLayout.LayoutParams(dp(9), dp(18)))
            listOf(
                ObjectAnimator.ofFloat(dot, View.TRANSLATION_Y, 0f, -dp(4).toFloat(), 0f),
                ObjectAnimator.ofFloat(dot, View.ALPHA, .35f, 1f, .35f),
            ).forEach { animator ->
                animator.duration = 720L
                animator.startDelay = index * 140L
                animator.repeatCount = ValueAnimator.INFINITE
                animator.start()
                thinkingAnimations.add(animator)
            }
        }
        row.addView(dots, LinearLayout.LayoutParams(-2, dp(20)).apply { marginStart = dp(5) })
        thinkingView = row
        messageList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
        scrollToBottom()
    }

    private fun finishThinking() {
        thinkingAnimations.forEach { it.cancel() }
        thinkingAnimations.clear()
        thinkingView?.let { if (::messageList.isInitialized) messageList.removeView(it) }
        thinkingView = null
        thinkingLabelView = null
    }

    private fun createStreamingBody(): TextView {
        val wrapper = messageList.getChildAt(messageList.childCount - 1) as? LinearLayout
            ?: LinearLayout(this).also { messageList.addView(it) }
        return TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFE6EBF3.toInt())
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextIsSelectable(true)
            movementMethod = LinkMovementMethod.getInstance()
            autoLinkMask = android.text.util.Linkify.WEB_URLS
            wrapper.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        }
    }

    private fun handleCommand(raw: String) {
        val cmd = raw.substringBefore(' ').lowercase(Locale.ROOT)
        val arg = raw.substringAfter(' ', "").trim()
        when (cmd) {
            "/help" -> addAssistant("Comandos disponibles: /debug, /review, /readme, /translate, /explain, /test, /resumen, /img, /enhance-img, /edit-img, /search, /sismo, /clima, /skills, /memoria, /entrenar, /model y /clear.")
            "/clear", "/new" -> clearChat()
            "/model" -> addAssistant("WIL.E COPILOT · enrutamiento multi-proveedor desde el backend de CodeHub.")
            "/jarvis" -> {
                readRepliesAloud = arg.equals("on", true)
                if (!readRepliesAloud) handsFreeEnabled = false
                persistVoicePreferences()
                addAssistant(if (readRepliesAloud) "Lectura en voz alta activada. Usa /jarvis off para desactivarla." else "Lectura automática desactivada.")
            }
            "/skills" -> loadSkills()
            "/debug" -> submitPrompt("Depura este código o problema y explica los errores: $arg")
            "/review" -> submitPrompt("Haz un code review de este código y señala bugs, riesgos y mejoras: $arg")
            "/readme" -> submitPrompt("Genera un README profesional para este proyecto con descripción, instalación, uso y licencia. $arg")
            "/translate" -> submitPrompt("Traduce con precisión y preserva el tono: $arg")
            "/explain" -> submitPrompt("Explica esto como para una persona principiante, paso a paso: $arg")
            "/test" -> submitPrompt("Genera pruebas unitarias apropiadas para este código: $arg")
            "/resumen" -> submitPrompt("Resume este contenido en sus puntos clave: $arg")
            "/img" -> if (arg.isBlank()) addAssistant("Usa /img seguido de la descripción de la imagen.") else generateImage(arg)
            "/enhance-img" -> transformImage("/api/enhance-image", arg.ifBlank { "Mejora la calidad de esta imagen con un acabado profesional." })
            "/edit-img" -> if (arg.isBlank()) addAssistant("Usa /edit-img seguido de los cambios que quieres hacer a la imagen adjunta.") else transformImage("/api/edit-image", arg)
            "/search" -> if (arg.isBlank()) addAssistant("Usa /search seguido de lo que quieres encontrar.") else searchWeb(arg)
            "/sismo" -> loadQuakes(arg)
            "/clima" -> if (arg.isBlank()) addAssistant("Usa /clima seguido del nombre de una ciudad.") else loadWeather(arg)
            "/memoria" -> loadMemory()
            "/entrenar" -> saveMemoryNote(arg)
            else -> addAssistant("Comando desconocido: $cmd. Escribe /help para ver opciones.")
        }
    }

    private fun submitPrompt(prompt: String) { input.setText(prompt); sendMessage() }

    private fun loadSkills() = simpleGet("$API/api/skills") { json ->
        val rows = json.optJSONArray("skills") ?: JSONArray()
        val text = (0 until rows.length()).joinToString("\n\n") { i ->
            val item = rows.optJSONObject(i) ?: JSONObject()
            "• ${item.optString("name", item.optString("id"))}\n${item.optString("description")}"
        }
        addAssistant(if (text.isBlank()) "No hay skills activas." else "Skills disponibles:\n\n$text")
    }

    private fun searchWeb(query: String) = simpleGet("$API/api/search/tavily?q=${enc(query)}") { json ->
        val out = StringBuilder("Resultados web para: $query\n\n")
        json.optString("answer").takeIf { it.isNotBlank() }?.let { out.append(it).append("\n\n") }
        val items = json.optJSONArray("results") ?: JSONArray()
        for (i in 0 until minOf(items.length(), 5)) {
            val item = items.optJSONObject(i) ?: continue
            out.append("• ").append(item.optString("title")).append('\n')
                .append(item.optString("content", item.optString("snippet"))).append('\n')
                .append(item.optString("url")).append("\n\n")
        }
        addAssistant(out.toString().trim())
    }

    private fun loadQuakes(magnitude: String) {
        val min = magnitude.toDoubleOrNull()?.takeIf { it > 0 } ?: 4.0
        simpleGet("$API/api/sismos?minMag=$min&limit=8") { json ->
            val quakes = json.optJSONArray("quakes") ?: JSONArray()
            val result = if (quakes.length() == 0) "No hay sismos recientes con magnitud ≥ $min." else buildString {
                append("Sismos recientes (M ≥ $min):\n\n")
                for (i in 0 until quakes.length()) {
                    val q = quakes.optJSONObject(i) ?: continue
                    append("• M").append(q.optDouble("mag")).append(" · ").append(q.optString("place")).append('\n')
                }
            }
            addAssistant(result)
        }
    }

    private fun loadWeather(city: String) {
        setBusy(true, "Consultando clima…")
        val geo = Request.Builder().url("https://geocoding-api.open-meteo.com/v1/search?name=${enc(city)}&count=1&language=es&format=json").build()
        http.newCall(geo).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: java.io.IOException) = runOnUiThread { setBusy(false); addAssistant("No pude consultar el clima ahora mismo.") }
            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use { r ->
                    val location = runCatching { JSONObject(r.body?.string().orEmpty()).optJSONArray("results")?.optJSONObject(0) }.getOrNull()
                    if (location == null) return@use runOnUiThread { setBusy(false); addAssistant("No encontré esa ciudad.") }
                    val weatherReq = Request.Builder().url("https://api.open-meteo.com/v1/forecast?latitude=${location.optDouble("latitude")}&longitude=${location.optDouble("longitude")}&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min&timezone=auto").build()
                    http.newCall(weatherReq).enqueue(object : okhttp3.Callback {
                        override fun onFailure(call: Call, e: java.io.IOException) = runOnUiThread { setBusy(false); addAssistant("No pude consultar el clima ahora mismo.") }
                        override fun onResponse(call: Call, response: okhttp3.Response) {
                            response.use { wr ->
                                val data = runCatching { JSONObject(wr.body?.string().orEmpty()) }.getOrNull()
                                val current = data?.optJSONObject("current")
                                val name = location.optString("name", city)
                                val answer = if (current == null) "No pude leer el pronóstico de $name." else "Clima en $name${location.optString("country").let { if (it.isBlank()) "" else ", $it" }}\n\n" +
                                    "Temperatura: ${current.optDouble("temperature_2m").toInt()} °C · sensación ${current.optDouble("apparent_temperature").toInt()} °C\n" +
                                    "Humedad: ${current.optInt("relative_humidity_2m")}% · viento ${current.optDouble("wind_speed_10m").toInt()} km/h"
                                runOnUiThread { setBusy(false); addAssistant(answer) }
                            }
                        }
                    })
                }
            }
        })
    }

    private fun generateImage(prompt: String) {
        if (messages.lastOrNull()?.role != "user" || messages.lastOrNull()?.text != prompt) messages.add(ChatMessage("user", prompt))
        persistHistory(); renderHistory()
        setBusy(true, "Generando imagen…")
        val body = JSONObject().put("prompt", prompt).put("width", 768).put("height", 768).put("provider", "auto")
        activeCall = repository.post("$API/api/generate-image", body) { json, error -> runOnUiThread {
            activeCall = null; setBusy(false)
            val image = json?.optString("image").orEmpty().ifBlank { json?.optString("url").orEmpty() }
            if (image.isBlank()) addAssistant(error ?: json?.optString("error").orEmpty().ifBlank { "No se pudo generar la imagen." })
            else { addAssistant("Imagen generada: $prompt", image); status.text = "Imagen generada · ${json?.optString("provider", "WIL.E")}" }
        } }
    }

    private fun transformImage(path: String, prompt: String) {
        val image = pendingAttachment?.optString("image").orEmpty()
        if (image.isBlank()) { addAssistant("Adjunta una imagen y luego usa $path para editarla."); return }
        setBusy(true, "Procesando imagen…")
        val body = JSONObject().put("image", image).put("prompt", prompt)
        activeCall = repository.post(API + path, body) { json, error -> runOnUiThread {
            activeCall = null; setBusy(false)
            val result = json?.optString("image").orEmpty()
            if (result.isBlank()) addAssistant(error ?: json?.optString("error")?.takeIf { it.isNotBlank() }
                ?: json?.optString("note")?.takeIf { it.isNotBlank() } ?: "No se pudo procesar la imagen.")
            else addAssistant("Imagen procesada · ${json?.optString("provider", "WIL.E")}", result)
        } }
    }

    private fun loadMemory() = simpleGet("$API/api/wil-e/memory") { json ->
        if (json.has("error")) {
            addAssistant("La memoria personal requiere una sesión activa de CodeHub. Abre tu cuenta en la web dentro de la app y vuelve a WIL.E.")
            return@simpleGet
        }
        val facts = json.optJSONArray("facts") ?: JSONArray()
        val text = (0 until facts.length()).joinToString("\n") { i -> "• ${facts.optJSONObject(i)?.optString("value").orEmpty()}" }
        addAssistant(if (text.isBlank()) "Tu memoria está vacía." else "Recuerdos guardados:\n\n$text")
    }

    private fun saveMemoryNote(note: String) {
        if (note.isBlank()) { addAssistant("Usa /entrenar seguido del dato que quieras guardar en tu memoria personal."); return }
        val body = JSONObject().put("text", note)
        setBusy(true, "Guardando en memoria…")
        activeCall = repository.post("$API/api/wil-e/memory/note", body) { json, error -> runOnUiThread {
            activeCall = null; setBusy(false)
            addAssistant(if (json?.optBoolean("ok") == true) "Listo, guardé ese dato en tu memoria."
            else error ?: json?.optString("error")?.takeIf { it.isNotBlank() }
                ?: "No se pudo guardar. Inicia sesión en CodeHub y vuelve a intentarlo.")
        } }
    }

    private fun simpleGet(url: String, action: (JSONObject) -> Unit) {
        setBusy(true, "Consultando…")
        activeCall = repository.get(url) { json, error -> runOnUiThread {
            activeCall = null; setBusy(false)
            if (json == null) addAssistant(error ?: "No se pudo completar la solicitud.") else action(json)
        } }
    }

    private fun addAssistant(text: String, mediaUrl: String? = null) {
        messages.add(ChatMessage("assistant", text, mediaUrl))
        persistHistory(); renderHistory(); scrollToBottom()
        if (readRepliesAloud && ttsReady && text.isNotBlank()) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "emi-voice-reply")
    }

    private fun clearChat() {
        messages.clear()
        sessionId = "android-${UUID.randomUUID()}"
        chatPreferences.edit().putString(SESSION_KEY, sessionId).remove(HISTORY_KEY).apply()
        renderHistory()
        addAssistant("Chat nuevo. ¿En qué te ayudo?")
    }

    private fun confirmClearChat() {
        AlertDialog.Builder(this)
            .setTitle("Borrar conversación")
            .setMessage("Se eliminará este chat de este dispositivo y empezará una conversación nueva. Esta acción no tiene deshacer.")
            .setPositiveButton("Borrar") { _, _ ->
                clearChat()
                Toast.makeText(this, "Conversación borrada", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showMenu() {
        val options = arrayOf("Nuevo chat", "Exportar conversación", "Copiar última respuesta", "Skills", "Ayuda", "Leer última respuesta")
        AlertDialog.Builder(this).setTitle("WIL.E COPILOT").setItems(options) { _, which ->
            when (which) {
                0 -> clearChat()
                1 -> exportConversation()
                2 -> copyLastAnswer()
                3 -> loadSkills()
                4 -> addAssistant("Prueba /help para ver comandos. Adjunta imagen o PDF, cambia de modo o usa el micrófono para dictar. /jarvis on activa la lectura en voz alta.")
                5 -> messages.lastOrNull { it.role == "assistant" }?.text?.takeIf { it.isNotBlank() }?.let { if (ttsReady) tts.speak(it, TextToSpeech.QUEUE_FLUSH, null, "wile-last") }
            }
        }.show()
    }

    private fun exportConversation() {
        val content = buildString {
            append("# Conversación con WIL.E COPILOT\n\n")
            messages.forEach { append(if (it.role == "user") "## Tú\n\n" else "## WIL.E\n\n"); append(it.text).append("\n\n") }
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_SUBJECT, "Chat con WIL.E").putExtra(Intent.EXTRA_TEXT, content)
        startActivity(Intent.createChooser(send, "Exportar conversación"))
    }

    private fun copyLastAnswer() {
        val answer = messages.lastOrNull { it.role == "assistant" }?.text
        if (answer.isNullOrBlank()) { Toast.makeText(this, "Todavía no hay una respuesta", Toast.LENGTH_SHORT).show(); return }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("WIL.E", answer))
        Toast.makeText(this, "Respuesta copiada", Toast.LENGTH_SHORT).show()
    }

    private fun chooseAttachment() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, PICK_ATTACHMENT)
    }

    @Deprecated("Use the platform document and speech pickers")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SPEECH_INPUT && resultCode == RESULT_OK) {
            val phrase = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (phrase.isNotBlank()) {
                input.setText(if (input.text.isNullOrBlank()) phrase else "${input.text} $phrase")
                input.setSelection(input.text.length)
                if (handsFreeEnabled) sendMessage()
            }
        }
        if (requestCode != PICK_ATTACHMENT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val mime = contentResolver.getType(uri).orEmpty()
        val name = displayName(uri)
        setBusy(true, "Preparando archivo…")
        Thread {
            val result = runCatching {
                if (mime == "application/pdf" || name.lowercase().endsWith(".pdf")) pdfAttachment(uri, name)
                else imageAttachment(uri, name)
            }
            runOnUiThread {
                setBusy(false)
                result.onSuccess { pendingAttachment = it.first; pendingAttachmentLabel = it.second; attachLabel.text = "ADJUNTO · ${it.second} · toca para quitar"; attachLabel.visibility = View.VISIBLE }
                    .onFailure { Toast.makeText(this, "No se pudo abrir el archivo: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun imageAttachment(uri: Uri, name: String): Pair<JSONObject, String> {
        val fileSize = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
        require(fileSize <= 20L * 1024L * 1024L) { "Imagen demasiado grande (máximo 20 MB)" }
        val bitmap = contentResolver.openInputStream(uri).use { stream -> decodeScaled(stream ?: error("No se pudo leer el archivo")) }
        val bytes = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output); output.toByteArray() }
        bitmap.recycle()
        require(bytes.size < 3_500_000) { "La imagen excede el tamaño permitido" }
        return JSONObject().put("image", "data:image/jpeg;base64,${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)}") to name
    }

    private fun pdfAttachment(uri: Uri, name: String): Pair<JSONObject, String> {
        val fileSize = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
        require(fileSize <= 25L * 1024L * 1024L) { "PDF demasiado grande (máximo 25 MB)" }
        PDFBoxResourceLoader.init(applicationContext)
        val extractedText = contentResolver.openInputStream(uri).use { input ->
            PDDocument.load(input ?: error("No se pudo leer el PDF")).use { document ->
                PDFTextStripper().getText(document).trim().take(40_000)
            }
        }
        if (extractedText.isNotBlank()) {
            return JSONObject().put("pdfText", extractedText) to "$name · texto extraído"
        }
        val fd: ParcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r") ?: error("No se pudo abrir el PDF")
        val images = JSONArray()
        var pages = 0
        var totalPages = 0
        PdfRenderer(fd).use { renderer ->
            totalPages = renderer.pageCount
            pages = minOf(renderer.pageCount, MAX_PDF_PAGES)
            for (i in 0 until pages) renderer.openPage(i).use { page ->
                val scale = minOf(900f / page.width, 1200f / page.height, 1f)
                val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 45, out); out.toByteArray() }
                bitmap.recycle()
                images.put("data:image/jpeg;base64,${android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)}")
            }
        }
        fd.close()
        val suffix = if (pages < totalPages) " · primeras $pages páginas de $totalPages" else " · $pages páginas"
        return JSONObject().put("images", images) to (name + suffix + " · análisis visual")
    }

    private fun decodeScaled(input: InputStream): Bitmap {
        val bytes = input.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Formato de imagen no compatible")
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else uri.lastPathSegment.orEmpty()
        }.orEmpty()
    }.getOrDefault(uri.lastPathSegment.orEmpty()).ifBlank { "Archivo adjunto" }

    private fun clearAttachment() { pendingAttachment = null; pendingAttachmentLabel = ""; attachLabel.visibility = View.GONE }

    private fun startSpeechInput() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), SPEECH_INPUT)
            return
        }
        launchSpeechInput()
    }

    private fun launchSpeechInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-GT")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla con WIL.E")
        }
        runCatching { startActivityForResult(intent, SPEECH_INPUT) }.onFailure { Toast.makeText(this, "El dictado por voz no está disponible en este dispositivo.", Toast.LENGTH_SHORT).show() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SPEECH_INPUT) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) launchSpeechInput()
            else {
                if (handsFreeEnabled) { handsFreeEnabled = false; persistVoicePreferences() }
                Toast.makeText(this, "Permite el micrófono para dictar mensajes.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderHistory() {
        if (!::messageList.isInitialized) return
        if (::quickActionsContainer.isInitialized) quickActionsContainer.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
        messageList.removeAllViews()
        if (messages.isEmpty()) {
            val welcome = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(34), dp(24), dp(30))
                background = rounded(0xFF131D2C.toInt(), dp(24), 0xFF283850.toInt())
            }
            welcome.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_wile_spark)
                imageTintList = android.content.res.ColorStateList.valueOf(0xFFB8C3FF.toInt())
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF344274.toInt(), 0xFF202A49.toInt())).apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(1), 0xFF5869AA.toInt())
                }
                setPadding(dp(9), dp(9), dp(9), dp(9))
                contentDescription = "WIL.E"
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            welcome.addView(text("Hola, soy WIL.E", 24f, Color.WHITE, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            welcome.addView(text("Tu copiloto de CodeHub. Pregunta, comparte una imagen o trabaja con un PDF.", 14f, 0xFFB2BED0.toInt()).apply { gravity = Gravity.CENTER; setLineSpacing(dp(3).toFloat(), 1f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            messageList.addView(welcome)
        }
        messages.forEach { message -> addMessageCard(message) }
        scrollToBottom()
    }

    private fun addMessageCard(message: ChatMessage) {
        val isUser = message.role == "user"
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = rounded(if (isUser) 0xFF252C50.toInt() else 0xFF141C29.toInt(), dp(18), if (isUser) 0xFF414B7A.toInt() else 0xFF283449.toInt())
        }
        wrapper.addView(text(if (isUser) "TÚ" else "WIL.E", 10f, if (isUser) 0xFFAEB8FF.toInt() else 0xFF82D9E8.toInt(), true))
        if (message.text.isNotBlank()) {
            val body = TextView(this).apply {
                text = message.text
                textSize = 14f
                setTextColor(0xFFE6EBF3.toInt())
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
                autoLinkMask = android.text.util.Linkify.WEB_URLS
            }
            wrapper.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        }
        message.mediaUrl?.let { media ->
            if (media.startsWith("data:image/")) {
                val b64 = media.substringAfter(',')
                runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull()?.let { bytes ->
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) wrapper.addView(ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(-1, dp(260)).apply { topMargin = dp(8) })
                }
            } else if (media.startsWith("http")) wrapper.addView(text("Ver imagen: $media", 12f, 0xFF91CFFF.toInt()).apply { setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(media))) } } })
        }
        if (!isUser && message.text.isNotBlank()) {
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            actions.addView(text("Copiar", 11f, 0xFF9AAAC0.toInt()).apply { setPadding(dp(10), dp(9), dp(5), dp(2)); setOnClickListener { copy(message.text) } })
            actions.addView(text("Leer", 11f, 0xFF9AAAC0.toInt()).apply { setPadding(dp(10), dp(9), dp(5), dp(2)); setOnClickListener { if (ttsReady) tts.speak(message.text, TextToSpeech.QUEUE_FLUSH, null, "wile-message") } })
            wrapper.addView(actions)
        }
        val params = LinearLayout.LayoutParams(if (isUser) (resources.displayMetrics.widthPixels * .86f).toInt() else -1, -2).apply {
            bottomMargin = dp(9)
            if (isUser) gravity = Gravity.END
        }
        messageList.addView(wrapper, params)
    }

    private fun persistHistory() {
        val array = JSONArray()
        messages.takeLast(80).forEach { msg ->
            val row = JSONObject().put("role", msg.role).put("text", msg.text)
            // Persist remote image links, but avoid copying large data URLs into preferences.
            if (msg.mediaUrl?.startsWith("http") == true) row.put("media", msg.mediaUrl)
            array.put(row)
        }
        chatPreferences.edit().putString(HISTORY_KEY, array.toString()).apply()
    }

    private fun loadHistory() {
        val array = runCatching { JSONArray(chatPreferences.getString(HISTORY_KEY, "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            messages.add(ChatMessage(row.optString("role"), row.optString("text"), row.optString("media").takeIf { it.isNotBlank() }))
        }
    }

    private fun scrollToBottom() { if (::messageScroll.isInitialized) messageScroll.post { messageScroll.fullScroll(View.FOCUS_DOWN) } }
    private fun setBusy(busy: Boolean, label: String = "") {
        if (::status.isInitialized && label.isNotBlank()) status.text = label
        if (busy) showThinking(label.ifBlank { "WIL.E está trabajando" }) else finishThinking()
        if (::sendButton.isInitialized) setSendButtonState(busy)
    }
    private fun copy(value: String) { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("WIL.E", value)); Toast.makeText(this, "Copiado", Toast.LENGTH_SHORT).show() }
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun clearLegacyUnscopedHistoryOnce() {
        val migration = getSharedPreferences(MIGRATION_PREFS, MODE_PRIVATE)
        if (migration.getBoolean(LEGACY_CLEARED_KEY, false)) return
        // The old history had no owner id, so it cannot be safely assigned to an account.
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
        migration.edit().putBoolean(LEGACY_CLEARED_KEY, true).apply()
    }
    private fun isImageGenerationRequest(value: String) = Regex("(?i)^(🎨|/img\\s)|\\b(genera|crea|diseña|dibuja|haz|generate|create)\\b.{0,30}\\b(imagen|foto|ilustración|logo|banner|avatar|icono|portada|thumbnail|arte|dibujo|image|picture|drawing)\\b").containsMatchIn(value)
    private fun rounded(fill: Int, radius: Int, stroke: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = radius.toFloat(); setStroke(dp(1), stroke) }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply { text = value; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD }
    private fun chip(label: String, active: Boolean = false, action: () -> Unit): View = TextView(this).apply { text = label; gravity = Gravity.CENTER; textSize = 11f; setTextColor(if (active) Color.WHITE else 0xFFA9B5C9.toInt()); setPadding(dp(13), 0, dp(13), 0); background = rounded(if (active) 0xFF36427C.toInt() else 0xFF151D2A.toInt(), dp(18), if (active) 0xFF6576D7.toInt() else 0xFF2C3749.toInt()); isClickable = true; isFocusable = true; isSelected = active; setOnClickListener { action() }; installPressFeedback(this) }
    private fun smallIconAction(icon: Int, description: String, action: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        imageTintList = android.content.res.ColorStateList.valueOf(0xFFDCE6F5.toInt())
        background = rippleActionBackground(0xFF182334.toInt(), 0xFF34445A.toInt())
        contentDescription = description
        scaleType = ImageView.ScaleType.CENTER
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        installPressFeedback(this)
    }

    private fun setSendButtonState(busy: Boolean) {
        if (!::sendButton.isInitialized) return
        val changed = sendButtonBusy != busy
        sendButtonBusy = busy
        val applyState = {
            sendButton.setImageResource(if (busy) R.drawable.ic_ai_stop else R.drawable.ic_ai_send)
            sendButton.contentDescription = if (busy) "Detener respuesta" else "Enviar mensaje"
            val colors = if (busy) intArrayOf(0xFFE16A76.toInt(), 0xFFB94254.toInt())
                else intArrayOf(0xFF7D8AFF.toInt(), 0xFF5868E8.toInt())
            val stroke = if (busy) 0xFFFFA2A9.toInt() else 0xFFB0B9FF.toInt()
            sendButton.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), stroke)
            }
        }
        if (!changed) { applyState(); return }
        sendButton.animate().cancel()
        sendButton.animate().scaleX(.72f).scaleY(.72f).alpha(.35f).setDuration(90L).withEndAction {
            if (isFinishing || isDestroyed) return@withEndAction
            applyState()
            sendButton.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(190L)
                .setInterpolator(DecelerateInterpolator()).start()
        }.start()
    }

    private fun installPressFeedback(view: View) {
        view.setOnTouchListener { touched, event ->
            val pressed = event.actionMasked == android.view.MotionEvent.ACTION_DOWN
            val scale = if (pressed) .90f else 1f
            touched.animate().scaleX(scale).scaleY(scale).alpha(if (pressed) .78f else 1f)
                .setDuration(if (pressed) 95L else 180L)
                .setInterpolator(DecelerateInterpolator()).start()
            false
        }
    }

    override fun onInit(statusCode: Int) { ttsReady = statusCode == TextToSpeech.SUCCESS; if (ttsReady) tts.language = Locale("es", "GT") }
    override fun onResume() {
        super.onResume()
        brandMorphSwitcher?.removeCallbacks(brandMorphTick)
        brandMorphSwitcher?.postDelayed(brandMorphTick, 3000L)
    }
    override fun onPause() {
        brandMorphSwitcher?.removeCallbacks(brandMorphTick)
        super.onPause()
    }
    override fun onDestroy() {
        brandMorphSwitcher?.removeCallbacks(brandMorphTick)
        brandMorphSwitcher = null
        activeCall?.cancel()
        finishThinking()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
        super.onDestroy()
    }

    private fun Boolean?.orFalse() = this ?: false
}
