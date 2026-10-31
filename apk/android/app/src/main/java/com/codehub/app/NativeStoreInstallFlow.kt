package com.codehub.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Shared Shizuku diagnosis and install decision for all native store/library flows. */
object NativeStoreInstallFlow {
    fun interface Decision { fun onContinue(preferSilent: Boolean) }

    @JvmStatic
    fun request(activity: Activity, onContinue: Decision) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (ShizukuInstaller.isReady()) {
            onContinue.onContinue(true)
            return
        }

        val dp = { value: Int -> (value * activity.resources.displayMetrics.density + .5f).toInt() }
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
        }
        val badge = TextView(activity).apply {
            text = "CODEHUB STORE  ·  INSTALACIÓN SEGURA"
            textSize = 11f
            setTextColor(0xFF68F0A9.toInt())
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(13), dp(8), dp(13), dp(8))
            background = GradientDrawable().apply {
                setColor(0xFF18372C.toInt()); cornerRadius = dp(18).toFloat()
            }
        }
        panel.addView(badge, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(14) })
        val heading = TextView(activity).apply {
            text = "Instalación automática"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        panel.addView(heading)
        val diagnostic = TextView(activity).apply {
            textSize = 14f
            setTextColor(0xFFD0D0DE.toInt())
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(8), 0, dp(12))
        }
        panel.addView(diagnostic, LinearLayout.LayoutParams(-1, -2))
        val details = TextView(activity).apply {
            textSize = 12f
            setTextColor(0xFF9AA0B8.toInt())
            setPadding(dp(13), dp(11), dp(13), dp(11))
            background = GradientDrawable().apply {
                setColor(0xFF151522.toInt()); setStroke(dp(1), 0xFF29293B.toInt()); cornerRadius = dp(14).toFloat()
            }
        }
        panel.addView(details, LinearLayout.LayoutParams(-1, -2))
        val copyDiagnostic = TextView(activity).apply {
            text = "Copiar diagnóstico"
            textSize = 13f
            setTextColor(0xFF68F0A9.toInt())
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(11), dp(4), dp(6))
            setOnClickListener {
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("CodeHub · diagnóstico Shizuku", ShizukuInstaller.getInstallDiagnostic(activity)))
                Toast.makeText(activity, "Diagnóstico copiado", Toast.LENGTH_SHORT).show()
            }
        }
        panel.addView(copyDiagnostic, LinearLayout.LayoutParams(-1, dp(42)))

        val dialog = AlertDialog.Builder(activity)
            .setTitle("CodeHub Store")
            .setView(panel)
            .setPositiveButton("Revisar conexión", null)
            .setNeutralButton("Abrir Shizuku", null)
            .setNegativeButton("Instalador Android", null)
            .create()
        val handler = Handler(Looper.getMainLooper())
        var requestingPermission = false
        var finished = false

        fun finish(preferSilent: Boolean) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            dialog.dismiss()
            onContinue.onContinue(preferSilent)
        }

        fun refreshDiagnostic() {
            if (!dialog.isShowing || finished || activity.isFinishing || activity.isDestroyed) return
            val installed = ShizukuInstaller.isManagerInstalled(activity)
            val connected = ShizukuInstaller.isBinderAlive()
            val granted = connected && ShizukuInstaller.hasPermission()
            diagnostic.text = when {
                granted -> "CodeHub ya tiene permiso para instalar y actualizar apps sin repetir la confirmación de Android."
                connected -> "Shizuku está activo. Autoriza CodeHub en la solicitud del sistema para continuar."
                installed -> "Shizuku está instalado, pero su servicio no está activo. Ábrelo, inicia el servicio y vuelve a CodeHub."
                else -> "No se detectó Shizuku. Puedes instalarlo y activar su servicio; la tienda volverá a comprobarlo automáticamente."
            }
            details.text = "DIAGNÓSTICO\n${ShizukuInstaller.getInstallDiagnostic(activity)}"
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.text = if (connected) "Solicitar permiso" else "Revisar conexión"
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.text = if (installed) "Abrir Shizuku" else "Obtener Shizuku"
        }

        fun requestPermission() {
            if (finished || requestingPermission) return
            if (!ShizukuInstaller.isBinderAlive()) {
                refreshDiagnostic()
                Toast.makeText(activity, "Aún no se conecta el servicio de Shizuku.", Toast.LENGTH_SHORT).show()
                return
            }
            if (ShizukuInstaller.isReady()) {
                finish(true)
                return
            }
            requestingPermission = true
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
            ShizukuInstaller.requestPermission { granted ->
                activity.runOnUiThread {
                    requestingPermission = false
                    if (granted) finish(true) else {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                        refreshDiagnostic()
                        Toast.makeText(activity, "No se concedió el permiso. Puedes intentarlo de nuevo o usar Android.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        val poll = object : Runnable {
            override fun run() {
                if (finished || !dialog.isShowing) return
                if (ShizukuInstaller.isBinderAlive()) {
                    refreshDiagnostic()
                    if (ShizukuInstaller.isReady()) finish(true) else requestPermission()
                } else {
                    refreshDiagnostic()
                    handler.postDelayed(this, 1_000)
                }
            }
        }

        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            refreshDiagnostic()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (ShizukuInstaller.isBinderAlive()) requestPermission()
                else {
                    refreshDiagnostic()
                    handler.removeCallbacks(poll)
                    handler.post(poll)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                try {
                    val launch = ShizukuInstaller.getManagerLaunchIntent(activity)
                    if (launch != null) activity.startActivity(launch)
                    else activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                    handler.removeCallbacks(poll)
                    handler.post(poll)
                } catch (_: Exception) {
                    Toast.makeText(activity, "No se pudo abrir Shizuku ni su página de descarga.", Toast.LENGTH_LONG).show()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { finish(false) }
        }
        dialog.setOnCancelListener {
            finished = true
            handler.removeCallbacksAndMessages(null)
        }
        dialog.show()
    }
}
