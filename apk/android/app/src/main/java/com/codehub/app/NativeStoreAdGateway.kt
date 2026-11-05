package com.codehub.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Pasarela de Unity Ads para el flujo de descarga del CodeHub Store nativo.
 * Cumple docs/ADS-COMPLIANCE.md (§2 y §3) y la política del centro de
 * anuncios del dev (docs/ADS-CENTER.md):
 *  - ANTES de cualquier anuncio debe existir consentimiento UMP
 *    (ConsentManager.canRequestAds()) y el SDK ya inicializado por
 *    MainActivity (UnityAds.initialize corre una vez por proceso).
 *  - REWARDED: opt-in ligado a la descarga ("apoyo al dev"). El video NUNCA
 *    bloquea la descarga: si el usuario lo cierra, falla o el SDK no
 *    responde, la descarga arranca igual.
 *  - INTERSTITIAL: solo en pausa natural CUANDO el usuario NO ha salido de la
 *    app (instalación silenciosa terminada), con cooldown, tope por sesión,
 *    single-flight y una ventana de aviso para que el usuario lo omita; si no
 *    hay relleno, se precarga y sigue.
 *
 * El usuario nunca queda obligado: ni a ver el video, ni a cerrar el anuncio
 * de una forma concreta, ni a abrir la Google Play Store.
 */
object NativeStoreAdGateway {
    private const val TAG = "StoreAdGateway"

    // ── CENTRO DE ANUNCIOS (administrado por el dev, no por el usuario) ──
    // Estos valores son la política de anuncios de CodeHub. Se cambian aquí
    // (código) y en el panel de Unity (docs/ADS-CENTER.md).
    /** Pausa mínima entre dos interstitials del mismo proceso. */
    const val INTERSTITIAL_COOLDOWN_MS = 3 * 60_000L
    /** Máximo de interstitials por sesión de la app. */
    const val INTERSTITIAL_SESSION_CAP = 4
    /** Cada cuántas descargas completadas se ofrece el interstitial. */
    const val INTERSTITIAL_EVERY_DOWNLOADS = 1
    /** Segundos de aviso antes del interstitial: el usuario puede omitirlo. */
    const val SKIP_WINDOW_SECONDS = 4
    /** Si el SDK no responde, la descarga continúa igual (nunca se bloquea). */
    const val REWARDED_TIMEOUT_MS = 20_000L
    /** Clave interna: descargas completadas desde el último interstitial. */
    private const val KEY_DOWNLOADS_SINCE_AD = "ads_downloads_since_ad"
    /** Clave histórica: el usuario pidió no volver a ver la oferta de video. */
    private const val PREF_NO_REWARDED_SHEET = "ch_ads/store_rewarded_skip"

    private val main = Handler(Looper.getMainLooper())
    private var shownThisSession = 0
    private var lastInterstitialAt = 0L
    private var interstitialInFlight = false
    private var lastRewardedAt = 0L
    /** Evita mostrar la oferta rewarded dos veces en la misma descarga. */
    @Volatile private var rewardedSheetOpen = false

    /** Precarga rewarded + interstitial si el consentimiento ya está listo. */
    @JvmStatic
    fun preload() {
        runCatching {
            if (!ConsentManager.canRequestAds()) return
            if (!UnityAdsManager.isInitialized()) return
            UnityAdsManager.loadRewarded()
            UnityAdsManager.loadInterstitial()
        }.onFailure { android.util.Log.w(TAG, "preload", it) }
    }

    /** Close del opt-in rewarded cuando la app se cierra (evita sheets zombis). */
    @JvmStatic
    fun release() {
        rewardedSheetOpen = false
    }

    data class RewardOfferResult(val watched: Boolean, val priority: Boolean)

    /**
     * Oferta rewarded ligada a la descarga (opt-in). Nunca bloquea: si el
     * usuario no quiere video, o no hay relleno, o el SDK falla, la descarga
     * sigue normal. Al cerrar el video la descarga arranca sola.
     */
    @JvmStatic
    fun requestDownloadReward(activity: Activity, appName: String, onProceed: (RewardOfferResult) -> Unit) {
        runCatching {
            if (activity.isFinishing || activity.isDestroyed) {
                onProceed(RewardOfferResult(false, false))
                return
            }
            val prefs = activity.getSharedPreferences("ch_ads", Activity.MODE_PRIVATE)
            val skipSheet = prefs.getBoolean(PREF_NO_REWARDED_SHEET, false)
            val ready = ConsentManager.canRequestAds() &&
                UnityAdsManager.isInitialized() &&
                UnityAdsManager.isRewardedReady()

            if (!ready || skipSheet || rewardedSheetOpen) {
                onProceed(RewardOfferResult(false, false))
                return
            }
            rewardedSheetOpen = true

            val dp = { value: Int -> (value * activity.resources.displayMetrics.density + .5f).toInt() }
            val panel = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(22), dp(18), dp(22), dp(10))
            }

            val badge = TextView(activity).apply {
                text = "CODEHUB STORE  ·  APOYA AL DEV"
                textSize = 11f
                setTextColor(0xFF06B6D4.toInt())
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = GradientDrawable().apply {
                    setColor(0xFF18372C.toInt()); cornerRadius = dp(16).toFloat()
                }
            }
            badge.gravity = Gravity.CENTER
            panel.addView(badge, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

            panel.addView(TextView(activity).apply {
                text = "Apoya a CodeHub"
                textSize = 21f
                setTextColor(0xFFFFFFFF.toInt())
                typeface = Typeface.DEFAULT_BOLD
            })
            panel.addView(TextView(activity).apply {
                text = "Mira un video corto y apoya al proyecto. Al cerrarlo, la descarga de $appName arranca sola. " +
                    "Si prefieres, sigue ahora sin ver nada: nunca te obligamos ni te llevamos a la Play Store."
                textSize = 14f
                setTextColor(0xFFD0D0DE.toInt())
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(9), 0, dp(14))
            })

            val watch = TextView(activity).apply {
                text = "▶  Ver video y apoyar"
                textSize = 15f
                setTextColor(0xFF12261F.toInt())
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(13), dp(10), dp(13))
                background = GradientDrawable().apply {
                    setColor(0xFF6366F1.toInt()); cornerRadius = dp(18).toFloat()
                }
            }
            panel.addView(watch, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })

            val skip = TextView(activity).apply {
                text = "Descargar ahora, sin anuncio"
                textSize = 14f
                setTextColor(0xFF9AA0B8.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            panel.addView(skip, LinearLayout.LayoutParams(-1, -2))

            val warn = TextView(activity).apply {
                text = "No mostrar esta oferta de nuevo"
                textSize = 12f
                setTextColor(0xFF06B6D4.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(4))
            }
            panel.addView(warn, LinearLayout.LayoutParams(-1, -2))

            val dialog = AlertDialog.Builder(activity)
                .setView(panel)
                .setCancelable(true)
                .create()
            var proceeded = false
            /** Cuando el usuario elige el video, el cierre del aviso NO dispara
             *  la descarga: la descarga arranca al terminar el anuncio. */
            var deferUntilAd = false
            fun proceedOnce(result: RewardOfferResult) {
                if (proceeded) return
                proceeded = true
                rewardedSheetOpen = false
                proceedWhenVisible(activity) { onProceed(result) }
            }
            dialog.setOnCancelListener { proceedOnce(RewardOfferResult(false, false)) }
            dialog.setOnDismissListener {
                rewardedSheetOpen = false
                if (!deferUntilAd && !proceeded) proceedOnce(RewardOfferResult(false, false))
            }

            fun finish(watchAd: Boolean) {
                if (watchAd) deferUntilAd = true
                if (!activity.isFinishing && !activity.isDestroyed) dialog.dismiss()
                if (!watchAd) {
                    proceedOnce(RewardOfferResult(false, false))
                    return
                }
                lastRewardedAt = System.currentTimeMillis()
                // El video es apoyo, nunca una puerta: si el SDK no responde,
                // la descarga arranca igual.
                val timeout = Runnable {
                    scheduleRewardedReload()
                    proceedOnce(RewardOfferResult(false, false))
                }
                main.postDelayed(timeout, REWARDED_TIMEOUT_MS)
                UnityAdsManager.showRewarded(activity) { earned, _, _ ->
                    main.removeCallbacks(timeout)
                    main.post {
                        runCatching {
                            if (earned) UnityAdsManager.loadRewarded() else scheduleRewardedReload()
                            // Pequeña espera: el SDK todavía está quitando la vista
                            // del anuncio y un diálogo encima rompe la app.
                            main.postDelayed({ proceedOnce(RewardOfferResult(earned, earned)) }, 350)
                        }.onFailure { android.util.Log.w(TAG, "reward callback", it) }
                    }
                }
            }

            // Botón de cierre (X) en la esquina superior derecha: nunca obliga
            // al usuario a ver el anuncio ni a ir a la tienda.
            val close = TextView(activity).apply {
                text = "✕"
                textSize = 18f
                setTextColor(0xFF9AA0B8.toInt())
                gravity = Gravity.END
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener { finish(false) }
            }
            // Insertar el cierre encima del panel envolviendo en un contenedor.
            val wrapper = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(close, LinearLayout.LayoutParams(-1, -2))
                addView(panel, LinearLayout.LayoutParams(-1, -2))
            }
            dialog.setView(wrapper)

            watch.setOnClickListener { finish(true) }
            skip.setOnClickListener { finish(false) }
            warn.setOnClickListener {
                prefs.edit().putBoolean(PREF_NO_REWARDED_SHEET, true).apply()
                Toast.makeText(activity, "No volveremos a ofrecer el video de apoyo.", Toast.LENGTH_SHORT).show()
                finish(false)
            }
            dialog.show()
            // Estilo del aviso: fondo obsidian (se aplica con la ventana viva).
            dialog.window?.let { window ->
                val dim = GradientDrawable().apply { setColor(0xFF080B11.toInt()) }
                window.setBackgroundDrawable(dim)
            }
        }.onFailure { error ->
            android.util.Log.w(TAG, "requestDownloadReward", error)
            rewardedSheetOpen = false
            onProceed(RewardOfferResult(false, false))
        }
    }

    /**
     * Interstitial en pausa natural (fin de instalación IN-APP). Aplica
     * cooldown + tope por sesión + single-flight + frecuencia por descargas.
     * Antes de mostrarlo hay una ventana de aviso: el usuario puede OMITIRLO.
     * Solo se llama cuando el usuario permaneció en la app (la instalación
     * silenciosa de Shizuku completó sin abrir el instalador del sistema).
     */
    @JvmStatic
    fun showCompletionInterstitial(activity: Activity) {
        runCatching {
            if (interstitialInFlight) return
            val prefs = activity.getSharedPreferences("ch_ads", Activity.MODE_PRIVATE)
            val done = prefs.getInt(KEY_DOWNLOADS_SINCE_AD, 0) + 1
            prefs.edit().putInt(KEY_DOWNLOADS_SINCE_AD, done).apply()
            if (done % INTERSTITIAL_EVERY_DOWNLOADS != 0) return

            val now = System.currentTimeMillis()
            if (now - lastInterstitialAt < INTERSTITIAL_COOLDOWN_MS) return
            if (shownThisSession >= INTERSTITIAL_SESSION_CAP) return
            if (!ConsentManager.canRequestAds() || !UnityAdsManager.isInitialized()) return
            if (!UnityAdsManager.isInterstitialReady()) {
                UnityAdsManager.loadInterstitial()
                return
            }
            showInterstitialWithSkipWindow(activity)
        }.onFailure { android.util.Log.w(TAG, "showCompletionInterstitial", it) }
    }

    /**
     * Ventana de omitir antes del interstitial: el usuario decide si lo ve o
     * lo salta. Omitir no penaliza la descarga ni vuelve a insistir.
     */
    private fun showInterstitialWithSkipWindow(activity: Activity) {
        val dp = { value: Int -> (value * activity.resources.displayMetrics.density + .5f).toInt() }
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = GradientDrawable().apply {
                setColor(0xFF111827.toInt())
                setStroke(dp(1), 0xFF26324A.toInt())
                cornerRadius = dp(18).toFloat()
            }
        }
        panel.addView(TextView(activity).apply {
            text = "Descarga completada"
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })
        panel.addView(TextView(activity).apply {
            text = "Puedes ver un anuncio corto que apoya a CodeHub u omitirlo. " +
                "No es obligatorio y no te lleva a la Play Store."
            textSize = 13f
            setTextColor(0xFFD0D0DE.toInt())
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(8), 0, dp(10))
        })
        val countdown = TextView(activity).apply {
            text = "Empieza en $SKIP_WINDOW_SECONDS s"
            textSize = 13f
            setTextColor(0xFF06B6D4.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        panel.addView(countdown)

        var seconds = SKIP_WINDOW_SECONDS
        var decided = false
        var tick: Runnable? = null

        fun resetCounter() {
            activity.getSharedPreferences("ch_ads", Activity.MODE_PRIVATE)
                .edit().putInt(KEY_DOWNLOADS_SINCE_AD, 0).apply()
        }

        fun skipAd() {
            if (decided) return
            decided = true
            tick?.let { main.removeCallbacks(it) }
            resetCounter()
            dialog.dismiss()
            Toast.makeText(activity, "Anuncio omitido. Todo sigue igual de rápido.", Toast.LENGTH_SHORT).show()
        }

        fun launchAd() {
            if (decided) return
            decided = true
            tick?.let { main.removeCallbacks(it) }
            dialog.dismiss()
            interstitialInFlight = true
            UnityAdsManager.showInterstitial(activity) { shown ->
                main.post {
                    interstitialInFlight = false
                    if (shown) {
                        shownThisSession++
                        lastInterstitialAt = System.currentTimeMillis()
                    }
                    resetCounter()
                    UnityAdsManager.loadInterstitial()
                }
            }
        }

        tick = object : Runnable {
            override fun run() {
                if (decided) return
                seconds--
                if (seconds <= 0) {
                    countdown.text = "Empieza ahora"
                    launchAd()
                    return
                }
                countdown.text = "Empieza en $seconds s"
                main.postDelayed(this, 1_000)
            }
        }

        val buttons = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        buttons.addView(TextView(activity).apply {
            text = "Omitir"
            textSize = 14f
            setTextColor(0xFF9AA0B8.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(11), dp(10), dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener { skipAd() }
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        buttons.addView(TextView(activity).apply {
            text = "Ver anuncio"
            textSize = 14f
            setTextColor(0xFF12261F.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(11), dp(10), dp(11))
            background = GradientDrawable().apply {
                setColor(0xFF6366F1.toInt()); cornerRadius = dp(16).toFloat()
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { launchAd() }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(10) })
        panel.addView(buttons, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) })

        dialog.setContentView(panel)
        dialog.setCancelable(true)
        dialog.setOnCancelListener { skipAd() }
        dialog.show()
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.setGravity(Gravity.BOTTOM)
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply { dimAmount = 0.45f }
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        tick?.let { main.postDelayed(it, 1_000) }
    }

    /**
     * Recarga diferida del rewarded: si el video no se completó (error, cierre
     * temprano o CTA del anunciante), el siguiente intento vuelve a tener fill.
     */
    private fun scheduleRewardedReload() {
        main.postDelayed({
            runCatching {
                if (ConsentManager.canRequestAds() && UnityAdsManager.isInitialized()) UnityAdsManager.loadRewarded()
            }.onFailure { android.util.Log.w(TAG, "reload rewarded", it) }
        }, 15_000)
    }

    /**
     * Ejecuta la acción solo cuando la pantalla está visible. Si el usuario
     * salió a la Play Store desde el CTA del anuncio, la descarga arranca sola
     * en cuanto vuelve a CodeHub (sin dialogs sobre una Activity en segundo
     * plano, que romperían la app).
     *
     * OJO: no se usa hasWindowFocus() porque con el sheet de la app abierto
     * (Dialog encima) la Activity pierde el foco aunque siga visible; el
     * sheet se cuenta como visible mediante windowVisibility.
     */
    private fun proceedWhenVisible(activity: Activity, action: () -> Unit) {
        var attempts = 0
        val runner = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) return
                // isResumed = CodeHub está en primer plano. Con el anuncio
                // (Unity) o la Play Store encima la Activity está pausada:
                // se espera a volver (máx. 2 min) y no se muestra ningún
                // diálogo encima del anuncio.
                if (activity.isResumed || attempts++ >= 120) {
                    action()
                    return
                }
                main.postDelayed(this, 1_000)
            }
        }
        runner.run()
    }
}
