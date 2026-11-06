package com.codehub.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.runBlocking

/** Native Shizuku app manager. Actions are explicit and limited to user-selected packages. */
class CodeSuActivity : Activity() {
    private val bg = 0xFF080B11.toInt()
    private val panel = 0xFF111827.toInt()
    private val muted = 0xFFA7A9BC.toInt()
    private val cyan = 0xFF22D3EE.toInt()
    private val selectedPackages = linkedSetOf<String>()
    private val candidates = mutableListOf<Candidate>()
    private lateinit var statusText: TextView
    private lateinit var infoText: TextView
    private lateinit var list: LinearLayout
    private lateinit var actionButton: Button
    private lateinit var connectButton: Button
    private var firstResume = true

    private data class Candidate(val packageName: String, val label: String, val rssKb: Long, val icon: android.graphics.drawable.Drawable)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        buildUi()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (firstResume) firstResume = false else if (::statusText.isInitialized) refreshStatus()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(10))
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Volver"
            isClickable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("CodeSu", 20f, Color.WHITE, true))
        titles.addView(label("OPTIMIZACIÓN ANDROID", 9f, cyan, true))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        header.addView(TextView(this).apply {
            text = "SHIZUKU"
            textSize = 9f
            setTextColor(cyan)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(0xFF102631.toInt(), dp(20))
        })
        root.addView(header)

        val scroll = ScrollView(this).apply { clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(16))
        }
        content.addView(card(dp(10)) {
            addView(label("Controla tus apps en segundo plano", 17f, Color.WHITE, true))
            addView(label("CodeSu consulta los procesos activos con Shizuku. Elige manualmente qué aplicaciones detener.", 12f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            addView(label("Detener una app puede cerrar tareas sin guardar y no garantiza más batería o memoria libre. Android puede volver a iniciarla cuando sea necesario.", 11f, 0xFFE5C890.toInt()), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        })

        content.addView(card {
            statusText = label("Comprobando Shizuku…", 13f, Color.WHITE, true)
            addView(statusText)
            addView(label("El permiso se solicita solo cuando tocas el botón. CodeSu no necesita root si Shizuku está activo por ADB inalámbrico.", 11f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            connectButton = Button(this@CodeSuActivity).apply {
                text = "Conectar Shizuku"
                isAllCaps = false
                setOnClickListener { connectShizuku() }
            }
            addView(connectButton, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })
        })

        infoText = label("Conecta Shizuku para cargar las aplicaciones activas.", 12f, muted)
        content.addView(infoText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        actionButton = Button(this).apply {
            text = "Detener seleccionadas (0)"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { confirmStopSelected() }
        }
        root.addView(actionButton, LinearLayout.LayoutParams(-1, dp(52)).apply {
            setMargins(dp(18), dp(8), dp(18), dp(16))
        })
        setContentView(root)
    }

    private fun refreshStatus() {
        val status = SystemCleaner.refreshStatus()
        statusText.text = when (status) {
            SystemCleaner.Status.READY -> "Shizuku conectado · ${SystemCleaner.backendDescription()}"
            SystemCleaner.Status.NEEDS_PERMISSION -> "Shizuku está activo · falta autorizar CodeHub"
            SystemCleaner.Status.NOT_RUNNING -> "Shizuku está instalado pero no está activo"
            SystemCleaner.Status.NOT_INSTALLED -> "Shizuku no está disponible"
            SystemCleaner.Status.DISCONNECTED -> "No se pudo verificar Shizuku"
        }
        connectButton.text = when (status) {
            SystemCleaner.Status.NEEDS_PERMISSION -> "Autorizar CodeHub"
            SystemCleaner.Status.NOT_RUNNING -> "Abrir Shizuku"
            SystemCleaner.Status.NOT_INSTALLED -> "Instalar Shizuku"
            else -> "Volver a comprobar"
        }
        if (status == SystemCleaner.Status.READY) loadActiveApps() else {
            candidates.clear()
            selectedPackages.clear()
            renderApps()
            infoText.text = when (status) {
                SystemCleaner.Status.NEEDS_PERMISSION -> "Autoriza CodeHub desde el diálogo de Shizuku para continuar."
                SystemCleaner.Status.NOT_RUNNING -> "Inicia el servicio en Shizuku y vuelve a esta pantalla."
                SystemCleaner.Status.NOT_INSTALLED -> "Instala Shizuku o activa Sui en un dispositivo con root."
                else -> "Comprueba que el servicio Shizuku esté activo."
            }
        }
    }

    private fun connectShizuku() {
        when (SystemCleaner.refreshStatus()) {
            SystemCleaner.Status.READY -> refreshStatus()
            SystemCleaner.Status.NEEDS_PERMISSION -> ShizukuInstaller.requestPermission { granted ->
                runOnUiThread {
                    if (granted) refreshStatus() else statusText.text = "No se concedió el permiso. Puedes intentarlo otra vez."
                }
            }
            SystemCleaner.Status.NOT_RUNNING -> {
                val intent = ShizukuInstaller.getManagerLaunchIntent(this)
                if (intent != null) runCatching { startActivity(intent) }
                else openShizukuDownload()
            }
            SystemCleaner.Status.NOT_INSTALLED -> openShizukuDownload()
            SystemCleaner.Status.DISCONNECTED -> statusText.text = "Shizuku está desconectado. Abre su app y vuelve a comprobar."
        }
    }

    private fun openShizukuDownload() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) }
    }

    private fun loadActiveApps() {
        if (!actionButton.isEnabled) infoText.text = "Leyendo procesos activos…"
        Thread({
            val scan = runCatching {
                runBlocking { SystemCleaner.processesByMemoryStrict() }
                    .asSequence()
                    .filter { it.packageName != packageName && it.packageName != "moe.shizuku.privileged.api" }
                    .mapNotNull { row ->
                        @Suppress("DEPRECATION")
                        val appInfo = runCatching { packageManager.getApplicationInfo(row.packageName, 0) }.getOrNull() ?: return@mapNotNull null
                        if ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) return@mapNotNull null
                        val appLabel = runCatching { packageManager.getApplicationLabel(appInfo).toString() }.getOrDefault(row.packageName)
                        val icon = runCatching { packageManager.getApplicationIcon(appInfo) }.getOrElse { packageManager.defaultActivityIcon }
                        Candidate(row.packageName, appLabel, row.rssKb, icon)
                    }
                    .distinctBy { it.packageName }
                    .sortedByDescending { it.rssKb }
                    .take(60)
                    .toList()
            }
            val loaded = scan.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                candidates.clear()
                candidates.addAll(loaded)
                selectedPackages.retainAll(loaded.map { it.packageName }.toSet())
                infoText.text = when {
                    scan.isFailure -> "No se pudo leer la lista de apps. Comprueba el permiso de Shizuku y vuelve a intentarlo."
                    loaded.isEmpty() -> "No se encontraron procesos de apps de usuario activos."
                    else -> "${loaded.size} apps de usuario activas · selecciona las que quieras detener."
                }
                renderApps()
            }
        }, "CodeSu-process-scan").start()
    }

    private fun renderApps() {
        list.removeAllViews()
        candidates.forEach { app ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(10), dp(10))
                background = rounded(panel, dp(16)).apply { setStroke(dp(1), 0xFF293449.toInt()) }
            }
            row.addView(ImageView(this).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(app.label, 13f, Color.WHITE, true).apply { maxLines = 1 })
                addView(label("${formatMb(app.rssKb)} MB · ${app.packageName}", 10f, muted).apply { maxLines = 1 })
            }
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            val check = CheckBox(this).apply {
                isChecked = app.packageName in selectedPackages
                contentDescription = "Seleccionar ${app.label} para detener"
                buttonTintList = android.content.res.ColorStateList.valueOf(cyan)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedPackages.add(app.packageName) else selectedPackages.remove(app.packageName)
                    updateActionButton()
                }
            }
            row.addView(check)
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        updateActionButton()
    }

    private fun updateActionButton() {
        actionButton.text = "Detener seleccionadas (${selectedPackages.size})"
        actionButton.isEnabled = selectedPackages.isNotEmpty() && SystemCleaner.refreshStatus() == SystemCleaner.Status.READY
    }

    private fun confirmStopSelected() {
        val packages = selectedPackages.toList()
        if (packages.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Detener ${packages.size} aplicaciones?")
            .setMessage("Se cerrarán las apps seleccionadas mediante Shizuku. Podrías perder trabajo que no hayas guardado. Android puede reiniciarlas automáticamente.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Detener apps") { _, _ -> stopSelected(packages) }
            .show()
    }

    private fun stopSelected(packages: List<String>) {
        actionButton.isEnabled = false
        actionButton.text = "Deteniendo…"
        Thread({
            val result = runCatching { runBlocking { SystemCleaner.killAppsPro(packages) } }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { stopped ->
                    selectedPackages.removeAll(stopped.stopped.toSet())
                    val failedLabels = stopped.failed.mapNotNull { pkg -> candidates.firstOrNull { it.packageName == pkg }?.label }
                    val message = buildString {
                        append("Apps detenidas: ${stopped.stopped.size}")
                        if (failedLabels.isNotEmpty()) append("\nNo se pudieron detener: ${failedLabels.joinToString()}")
                    }
                    AlertDialog.Builder(this).setTitle("CodeSu").setMessage(message).setPositiveButton("Listo", null).show()
                    loadActiveApps()
                }.onFailure { error ->
                    AlertDialog.Builder(this).setTitle("No se completó la acción")
                        .setMessage(error.message ?: "Shizuku dejó de estar disponible.")
                        .setPositiveButton("Entendido", null).show()
                    refreshStatus()
                }
            }
        }, "CodeSu-stop-apps").start()
    }

    private fun card(top: Int = dp(0), content: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(14), dp(15), dp(14))
        background = rounded(panel, dp(18)).apply { setStroke(dp(1), 0xFF293449.toInt()) }
        content()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = top }
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun formatMb(kb: Long): String = String.format(java.util.Locale.ROOT, "%.0f", kb / 1024.0)
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}
