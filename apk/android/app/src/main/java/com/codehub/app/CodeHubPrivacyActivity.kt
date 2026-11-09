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
                text = "Usa una VPN local para filtrar consultas DNS con AdGuard DNS y bloquear dominios conocidos de anuncios y rastreadores. La VPN no envía el tráfico web general por CodeHub."
                textSize = 13f; setTextColor(0xFFA7A9BC.toInt()); setLineSpacing(dp(3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        })
        status = TextView(this).apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF4ADE80.toInt()) }
        body.addView(panel {
            addView(status)
            blocked = TextView(this@CodeHubPrivacyActivity).apply { textSize = 12f; setTextColor(0xFFA7A9BC.toInt()) }
            addView(blocked, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        toggle = Button(this).apply { isAllCaps = false; setOnClickListener { toggleVpn() } }
        body.addView(toggle, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })

        body.addView(panel {
            addView(TextView(this@CodeHubPrivacyActivity).apply {
                text = "Transparencia y límites"; textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@CodeHubPrivacyActivity).apply {
                text = "• Android mostrará su aviso y requiere que aceptes la conexión VPN. Solo puede estar activa una VPN por perfil.\n• El filtro se limita a DNS IPv4; DNS privado estricto, ciertas apps con DNS propio y anuncios servidos desde el mismo dominio pueden omitir el filtro.\n• Las consultas se envían cifradas por DNS-over-TLS a AdGuard DNS público. El proveedor DNS puede procesar los dominios consultados.\n• Puedes detener la protección en cualquier momento desde aquí o en los ajustes de VPN de Android."
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

    override fun onResume() { super.onResume(); if (::toggle.isInitialized) renderState() }

    private fun toggleVpn() {
        if (isVpnActive()) {
            stopService(Intent(this, CodeHubDnsVpnService::class.java))
            prefs.edit().putBoolean(CodeHubDnsVpnService.KEY_ACTIVE, false).apply()
            renderState()
            return
        }
        startVpnIfNeeded()
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
        blocked.text = "Dominios bloqueados localmente · ${prefs.getInt(CodeHubDnsVpnService.KEY_BLOCKED_COUNT, 0)}\nResolución protegida por AdGuard DNS público"
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
