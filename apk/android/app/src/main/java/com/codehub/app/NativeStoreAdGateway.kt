package com.codehub.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Pasarela de Unity Ads para el flujo de descarga del CodeHub Store nativo.
 * Cumple docs/ADS-COMPLIANCE.md (§2 y §3):
 *  - ANTES de cualquier anuncio debe existir consentimiento UMP
 *    (ConsentManager.canRequestAds()) y el SDK ya inicializado por
 *    MainActivity (UnityAds.initialize corre una vez por proceso).
 *  - REWARDED: opt-in ligado a la descarga ("instalación prioritaria").
 *  - INTERSTITIAL: solo en pausa natural CUANDO el usuario NO ha salido de la
 *    app (instalación silenciosa terminada), con cooldown de 3 min, tope de 4
 *    por sesión y single-flight; si no hay relleno, se precarga y sigue.
 */
object NativeStoreAdGateway {
    private const val TAG = "StoreAdGateway"
    private const val COOLDOWN_MS = 3 * 60_000L
    private const val SESSION_CAP = 4
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
     * usuario no quiere video, o no hay relleno, sigue con descarga normal.
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
                text = "CODEHUB STORE  ·  INSTALACIÓN PRIORITARIA"
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
                text = "Descarga con prioridad"
                textSize = 21f
                setTextColor(0xFFFFFFFF.toInt())
                typeface = Typeface.DEFAULT_BOLD
            })
            panel.addView(TextView(activity).apply {
                text = "Mira un video corto y la descarga de $appName irá prioritaria en CodeHub. O continúa ahora con descarga normal."
                textSize = 14f
                setTextColor(0xFFD0D0DE.toInt())
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(0, dp(9), 0, dp(14))
            })

            val watch = TextView(activity).apply {
                text = "▶  Ver video y descargar con prioridad"
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
                text = "Continuar con descarga normal"
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
                .create()
            dialog.window?.let { window ->
                val dim = GradientDrawable().apply { setColor(0xFF080B11.toInt()) }
                window.setBackgroundDrawable(dim)
            }
            dialog.setOnDismissListener { rewardedSheetOpen = false }

            fun finish(watchAd: Boolean) {
                if (!activity.isFinishing && !activity.isDestroyed) dialog.dismiss()
                if (watchAd) {
                    lastRewardedAt = System.currentTimeMillis()
                    val timeout = Runnable {
                        runCatching { onProceed(RewardOfferResult(false, false)) }
                    }
                    main.postDelayed(timeout, 20_000)
                    UnityAdsManager.showRewarded(activity) { earned, _, _ ->
                        main.removeCallbacks(timeout)
                        main.post {
                            runCatching {
                                if (earned) UnityAdsManager.loadRewarded()
                                onProceed(RewardOfferResult(earned, earned))
                            }.onFailure { android.util.Log.w(TAG, "reward callback", it) }
                        }
                    }
                } else {
                    onProceed(RewardOfferResult(false, false))
                }
            }

            watch.setOnClickListener { finish(true) }
            skip.setOnClickListener { finish(false) }
            warn.setOnClickListener {
                prefs.edit().putBoolean(PREF_NO_REWARDED_SHEET, true).apply()
                Toast.makeText(activity, "No volveremos a ofrecer el video prioritario.", Toast.LENGTH_SHORT).show()
                finish(false)
            }
            dialog.show()
        }.onFailure { error ->
            android.util.Log.w(TAG, "requestDownloadReward", error)
            rewardedSheetOpen = false
            onProceed(RewardOfferResult(false, false))
        }
    }

    /**
     * Interstitial en pausa natural (fin de instalación IN-APP). Aplica
     * cooldown + tope por sesión + single-flight. Solo se llama cuando el
     * usuario permaneció en la app (la instalación silenciosa de Shizuku
     * completó sin abrir el instalador del sistema).
     */
    @JvmStatic
    fun showCompletionInterstitial(activity: Activity) {
        runCatching {
            if (interstitialInFlight) return
            val now = System.currentTimeMillis()
            if (now - lastInterstitialAt < COOLDOWN_MS) return
            if (shownThisSession >= SESSION_CAP) return
            if (!ConsentManager.canRequestAds() || !UnityAdsManager.isInitialized()) return
            if (!UnityAdsManager.isInterstitialReady()) {
                UnityAdsManager.loadInterstitial()
                return
            }
            interstitialInFlight = true
            UnityAdsManager.showInterstitial(activity) { shown ->
                main.post {
                    interstitialInFlight = false
                    if (shown) {
                        shownThisSession++
                        lastInterstitialAt = System.currentTimeMillis()
                    }
                    UnityAdsManager.loadInterstitial()
                }
            }
        }.onFailure { android.util.Log.w(TAG, "showCompletionInterstitial", it) }
    }
}
