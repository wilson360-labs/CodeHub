package com.codehub.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Consent and controls for CodeHub's DNS-only ad/tracker filter. */
class CodeHubPrivacyActivity : Activity() {
    private val prefs by lazy { getSharedPreferences(CodeHubDnsVpnService.PREFS, MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var blocked: TextView
    private lateinit var toggle: Button
    private lateinit var modeFast: Button
    private lateinit var modeEncrypted: Button
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBars.fit(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF080B11.toInt()) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(10)) }
        header.addView(TextView(this).apply {
            text = "‹"; textSize = 32f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); contentDescription = "Volver"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(TextView(this).apply { text = "CodeHub Shield"; textSize = 20f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        root.addView(header)

        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(20)) }
        body.addView(panel {
            addView(TextView(this@CodeHubPrivacyActivity).apply { text = "Protección DNS del dispositivo"; textSize = 21f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
            addView(TextView(this@CodeHubPrivacyActivity).apply {
                text = "CodeHub Shield conserva el tráfico normal de las apps y filtra sus consultas DNS. Descarga adsblock.txt desde Supabase, la guarda en el almacenamiento privado de CodeHub y bloquea localmente los dominios de anuncios y rastreo."
                textSize = 13f; setTextColor(0xFFA7A9BC.toInt()); setLineSpacing(dp(3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        })
        status = TextView(this).apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF4ADE80.toInt()) }
        body.addView(panel {
            addView(status)
            blocked = TextView(this@CodeHubPrivacyActivity).apply { textSize = 12f; setTextColor(0xFFA7A9BC.toInt()) }
            addView(blocked, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        body.addView(panel {
            addView(TextView(this@CodeHubPrivacyActivity).apply {
                text = "Modo de protección"; textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@CodeHubPrivacyActivity).apply {
                text = "El mismo bloqueo de anuncios y rastreadores, con la velocidad de resolución que prefieras."
                textSize = 12f; setTextColor(0xFFA7A9BC.toInt()); setLineSpacing(dp(3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            val row = LinearLayout(this@CodeHubPrivacyActivity).apply { orientation = LinearLayout.HORIZONTAL }
            modeFast = Button(this@CodeHubPrivacyActivity).apply {
                isAllCaps = false; text = "Rápido"
                setOnClickListener { selectMode(CodeHubDnsVpnService.MODE_FAST) }
            }
            modeEncrypted = Button(this@CodeHubPrivacyActivity).apply {
                isAllCaps = false; text = "Cifrado"
                setOnClickListener { selectMode(CodeHubDnsVpnService.MODE_ENCRYPTED) }
            }
            row.addView(modeFast, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
            row.addView(modeEncrypted, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })
            addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(TextView(this@CodeHubPrivacyActivity).apply {
                text = "• Cifrado: cada consulta sale por DNS-over-TLS de AdGuard (trafico cifrado) en una conexión TLS reutilizada.\n• Rápido: consulta a AdGuard sin cifrar; si la red la bloquea, salta al DNS de la red sin filtro."
                textSize = 11f; setTextColor(0xFF8B93A7.toInt()); setLineSpacing(dp(3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        toggle = Button(this).apply { isAllCaps = false; setOnClickListener { toggleVpn() } }
        body.addView(toggle, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })

        body.addView(panel {
            addView(TextView(this@CodeHubPrivacyActivity).apply {
                text = "Transparencia y límites"; textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@CodeHubPrivacyActivity).apply {
                text = "• Android requiere que aceptes el permiso VPN; CodeHub no necesita root. Solo puede estar activa una VPN por perfil.\n• La VPN enruta únicamente DNS IPv4; no funciona como firewall ni detiene conexiones web de las apps.\n• El archivo privado acepta dominios, hosts y reglas `||dominio^`; reglas cosméticas o por ruta no se pueden aplicar en un filtro DNS.\n• DNS privado estricto, apps con DNS propio y anuncios servidos desde el mismo dominio pueden evadir el bloqueo.\n• La conexión cifrada con AdGuard (DNS-over-TLS) se reutiliza entre consultas: un solo handshake en vez de uno por consulta.\n• El modo Cifrado va primero por TLS y cae a AdGuard sin cifrar y luego al DNS de la red; el modo Rápido salta directo a AdGuard sin cifrar. El respaldo de red puede no filtrar anuncios y puede enviar consultas sin cifrar."
                textSize = 12f; setTextColor(0xFFA7A9BC.toInt()); setLineSpacing(dp(3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val scroll = ScrollView(this).apply { addView(body); clipToPadding = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        SystemBars.applyInsets(this)
        renderState()
        if (intent.getBooleanExtra(EXTRA_START, false)) startVpnIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (::toggle.isInitialized) {
            renderState()
            handler.postDelayed(refreshRunnable, 1_500)
        }
    }

    override fun onPause() { handler.removeCallbacks(refreshRunnable); super.onPause() }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                renderState()
                handler.postDelayed(this, 1_500)
            }
        }
    }

    private fun toggleVpn() {
        if (isVpnActive()) {
            stopService(Intent(this, CodeHubDnsVpnService::class.java))
            prefs.edit().putBoolean(CodeHubDnsVpnService.KEY_ACTIVE, false).apply()
            renderState()
            return
        }
        startVpnIfNeeded()
    }

    /** Guarda el modo elegido; si el Shield está activo lo reinicia para aplicar la nueva cadena de DNS. */
    private fun selectMode(mode: String) {
        val current = prefs.getString(CodeHubDnsVpnService.KEY_FILTER_MODE, CodeHubDnsVpnService.MODE_ENCRYPTED)
        if (current == mode) return
        prefs.edit().putString(CodeHubDnsVpnService.KEY_FILTER_MODE, mode).apply()
        renderState()
        if (isVpnActive()) {
            status.text = "Modo cambiado · reiniciando protección…"
            status.setTextColor(0xFFFBBF24.toInt())
            stopService(Intent(this, CodeHubDnsVpnService::class.java))
            prefs.edit().putBoolean(CodeHubDnsVpnService.KEY_ACTIVE, false).apply()
            // Espera a que el túnel anterior cierre antes de re-establecer la VPN.
            handler.postDelayed({ if (!isFinishing && !isDestroyed) startVpnService() }, 700)
        }
    }

    private fun startVpnIfNeeded() {
        if (isVpnActive()) { renderState(); return }
        val consent = VpnService.prepare(this)
        if (consent != null) startActivityForResult(consent, REQUEST_VPN_CONSENT)
        else startVpnService()
    }

    @Deprecated("Android VPN consent callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_CONSENT) {
            if (resultCode == RESULT_OK) startVpnService()
            else { status.text = "Permiso VPN no concedido"; status.setTextColor(0xFFFBBF24.toInt()) }
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, CodeHubDnsVpnService::class.java).setAction(CodeHubDnsVpnService.ACTION_START)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }.onFailure {
            status.text = "No se pudo iniciar la VPN: ${it.localizedMessage ?: "error del sistema"}"
            status.setTextColor(0xFFFCA5A5.toInt())
        }
        renderState()
        var attempts = 0
        val confirmConnection = object : Runnable {
            override fun run() {
                renderState()
                if (!isVpnActive() && attempts++ < 10) handler.postDelayed(this, 500)
            }
        }
        handler.postDelayed(confirmConnection, 300)
    }

    private fun renderState() {
        val active = isVpnActive()
        status.text = if (active) "● PROTECCIÓN CONECTADA" else "○ PROTECCIÓN DESCONECTADA"
        status.setTextColor(if (active) 0xFF4ADE80.toInt() else 0xFFA7A9BC.toInt())
        val encryptedMode = prefs.getString(CodeHubDnsVpnService.KEY_FILTER_MODE, CodeHubDnsVpnService.MODE_ENCRYPTED) != CodeHubDnsVpnService.MODE_FAST
        modeEncrypted.backgroundTintList = android.content.res.ColorStateList.valueOf(if (encryptedMode) 0xFF315A55.toInt() else 0xFF1F2937.toInt())
        modeFast.backgroundTintList = android.content.res.ColorStateList.valueOf(if (encryptedMode) 0xFF1F2937.toInt() else 0xFF315A55.toInt())
        val resolverMode = prefs.getString(CodeHubDnsVpnService.KEY_RESOLVER_MODE, "Esperando consultas DNS")
        val listStatus = prefs.getString(CodeHubDnsVpnService.KEY_LIST_STATUS, "Lista pendiente de descarga")
        val modeLabel = if (encryptedMode) "Cifrado (DNS-over-TLS)" else "Rápido (sin cifrado)"
        blocked.text = "Bloqueos locales · ${prefs.getInt(CodeHubDnsVpnService.KEY_BLOCKED_COUNT, 0)}\nadsblock.txt privado · ${prefs.getInt(CodeHubDnsVpnService.KEY_RULE_COUNT, 0)} dominios\n$listStatus\nModo: $modeLabel\nResolución: $resolverMode"
        toggle.text = if (active) "Detener protección" else "Activar protección DNS"
        toggle.backgroundTintList = android.content.res.ColorStateList.valueOf(if (active) 0xFF4B2630.toInt() else 0xFF315A55.toInt())
    }

    private fun isVpnActive() = prefs.getBoolean(CodeHubDnsVpnService.KEY_ACTIVE, false)
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun panel(content: LinearLayout.() -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15)); content()
        background = GradientDrawable().apply { setColor(0xFF111827.toInt()); cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0xFF293449.toInt()) }
    }

    companion object {
        const val EXTRA_START = "start_vpn_on_open"
        private const val REQUEST_VPN_CONSENT = 7103
    }
}
