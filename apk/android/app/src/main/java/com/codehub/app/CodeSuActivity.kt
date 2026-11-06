package com.codehub.app

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** CodeSu shows only currently running apps and uses Shizuku for the native kill engine. */
class CodeSuActivity : Activity() {
    private val bg = 0xFF080B11.toInt()
    private val panel = 0xFF111827.toInt()
    private val muted = 0xFFA7A9BC.toInt()
    private val cyan = 0xFF22D3EE.toInt()
    private val prefs by lazy { getSharedPreferences("codesu_preferences", MODE_PRIVATE) }
    private val selectedPackages = linkedSetOf<String>()
    private val allApps = mutableListOf<Candidate>()
    private var sourceMode = SourceMode.USER
    private var selectionMode = SelectionMode.INCLUDE
    private var killMode = CodeSuUserService.LEVEL_FORCE_STOP
    private var hideCriticalApps = true
    private var query = ""

    private lateinit var statusText: TextView
    private lateinit var engineText: TextView
    private lateinit var connectionHelp: TextView
    private lateinit var infoText: TextView
    private lateinit var list: LinearLayout
    private lateinit var actionButton: Button
    private lateinit var connectButton: Button
    private lateinit var searchField: EditText
    private lateinit var sourceControls: LinearLayout
    private lateinit var selectionControls: LinearLayout
    private lateinit var killControls: LinearLayout
    private lateinit var killHelp: TextView
    private lateinit var hideCriticalCheck: CheckBox
    private lateinit var listRefreshButton: Button
    private lateinit var closeAllButton: Button
    private lateinit var autoControls: LinearLayout
    private lateinit var autoInfo: TextView
    private lateinit var listStyleControls: LinearLayout
    private lateinit var autoDelayControls: LinearLayout
    private lateinit var autoTimeButton: Button
    private var autoMode = AutoMode.OFF
    private var autoHour = 22
    private var autoMinute = 0
    private var screenOffDelayMinutes = 1
    private var compactRows = false
    private var listError: String? = null

    @Volatile private var remoteService: ICodeSuRemoteService? = null
    @Volatile private var bindLatch: CountDownLatch? = null
    @Volatile private var userServiceBound = false
    private val remoteLock = Any()
    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, CodeSuUserService::class.java))
            .daemon(false)
            .processNameSuffix("codesu")
            .debuggable(false)
            .version(2)
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            remoteService = ICodeSuRemoteService.Stub.asInterface(service)
            bindLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remoteService = null
        }
    }

    private enum class SourceMode(val title: String) { USER("Usuario"), LAUNCHABLE("Con icono"), SYSTEM("Sistema") }
    private enum class SelectionMode(val title: String) { INCLUDE("Incluir"), EXCLUDE("Excluir") }
    private enum class AutoMode(val title: String) { OFF("Desactivado"), SCREEN_OFF("Al apagar pantalla"), DAILY("Diario") }

    private data class Candidate(
        val packageName: String,
        val label: String,
        val icon: Drawable,
        val isSystem: Boolean,
        val hasLauncher: Boolean,
        val isEssential: Boolean,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBars.fit(this)
        selectedPackages.addAll(prefs.getStringSet("selected_packages", emptySet()).orEmpty())
        sourceMode = SourceMode.entries.getOrElse(prefs.getInt("source_mode", 0)) { SourceMode.USER }
        selectionMode = SelectionMode.entries.getOrElse(prefs.getInt("selection_mode", 0)) { SelectionMode.INCLUDE }
        killMode = prefs.getInt("kill_mode", CodeSuUserService.LEVEL_FORCE_STOP)
        hideCriticalApps = prefs.getBoolean("hide_critical_apps", prefs.getBoolean("protect_essential_apps", true))
        autoMode = AutoMode.entries.getOrElse(prefs.getInt("auto_mode", 0)) { AutoMode.OFF }
        autoHour = prefs.getInt("auto_hour", 22).coerceIn(0, 23)
        autoMinute = prefs.getInt("auto_minute", 0).coerceIn(0, 59)
        screenOffDelayMinutes = prefs.getInt("screen_off_delay_minutes", 1).coerceIn(0, 15)
        compactRows = prefs.getBoolean("compact_rows", false)
        buildUi()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        // Re-check the Shizuku binder and refresh the live process list on return.
        refreshStatus()
        renderAutoControls()
        loadInstalledApps()
    }

    override fun onDestroy() {
        if (userServiceBound) {
            try { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true) } catch (_: Throwable) {}
            userServiceBound = false
            remoteService = null
        }
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(8))
        }
        header.addView(TextView(this).apply {
            text = "‹"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Volver"
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("CodeSu", 20f, Color.WHITE, true))
        titles.addView(label("OPTIMIZACIÓN ANDROID", 9f, cyan, true))
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        header.addView(label("SHIZUKU", 9f, cyan, true).apply {
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(0xFF102631.toInt(), dp(20))
        })
        root.addView(header)

        val scroll = ScrollView(this).apply { clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(18))
        }

        content.addView(card {
            addView(label("Elige qué aplicaciones detener", 17f, Color.WHITE, true))
            addView(label("CodeSu muestra solo apps con procesos activos y respeta el origen, la selección y la protección crítica.", 12f, muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            addView(label("Forzar detención puede cerrar tareas sin guardar. Android puede volver a iniciar una app cuando otro servicio la necesite.", 11f, 0xFFE5C890.toInt()),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        })

        content.addView(card(dp(10)) {
            statusText = label("Comprobando Shizuku…", 13f, Color.WHITE, true)
            addView(statusText)
            engineText = label("Killer engine · Shizuku", 12f, muted, true)
            addView(engineText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            connectionHelp = label("Shizuku funciona por ADB inalámbrico o root. CodeSu no necesita root si Shizuku está activo.", 11f, muted)
            addView(connectionHelp, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            connectButton = Button(this@CodeSuActivity).apply {
                text = "Conectar Shizuku"
                isAllCaps = false
                setOnClickListener { connectShizuku() }
            }
            addView(connectButton, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(9) })
        })

        content.addView(card(dp(10)) {
            addView(label("Source mode · apps activas", 13f, Color.WHITE, true))
            sourceControls = LinearLayout(this@CodeSuActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addView(sourceControls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

            addView(label("Selection mode", 13f, Color.WHITE, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            selectionControls = LinearLayout(this@CodeSuActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addView(selectionControls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })

            addView(label("Kill level", 13f, Color.WHITE, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            killControls = LinearLayout(this@CodeSuActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addView(killControls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            killHelp = label("", 11f, muted)
            addView(killHelp, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

            hideCriticalCheck = CheckBox(this@CodeSuActivity).apply {
                text = "Hide critical apps (recomendado)"
                textSize = 12f
                setTextColor(Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(cyan)
                isChecked = hideCriticalApps
                setOnCheckedChangeListener { _, checked ->
                    if (!checked && hideCriticalApps) {
                        isChecked = true
                        AlertDialog.Builder(this@CodeSuActivity)
                            .setTitle("Mostrar apps críticas")
                            .setMessage("Desactivar Hide critical apps permite mostrar y cerrar apps esenciales como el launcher, teclado, llamadas y servicios del sistema. Una detención puede dejar funciones del teléfono temporalmente indisponibles.")
                            .setNegativeButton("Mantener ocultas", null)
                            .setPositiveButton("Entiendo") { _, _ ->
                                hideCriticalApps = false
                                hideCriticalCheck.isChecked = false
                                persistPreferences()
                                renderControls()
                                renderApps()
                            }
                            .show()
                    } else if (checked != hideCriticalApps) {
                        hideCriticalApps = checked
                        persistPreferences()
                        renderApps()
                    }
                }
            }
            addView(hideCriticalCheck, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

            addView(label("List UI", 13f, Color.WHITE, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            listStyleControls = LinearLayout(this@CodeSuActivity).apply { orientation = LinearLayout.HORIZONTAL }
            addView(listStyleControls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })

            addView(label("Auto Killer", 13f, Color.WHITE, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            autoControls = LinearLayout(this@CodeSuActivity).apply { orientation = LinearLayout.HORIZONTAL }
            addView(autoControls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            autoInfo = label("Desactivado · solo cerrará apps cuando ejecutes una acción manual.", 11f, muted)
            addView(autoInfo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            autoTimeButton = Button(this@CodeSuActivity).apply {
                isAllCaps = false
                setOnClickListener { showDailyTimePicker() }
            }
            addView(autoTimeButton, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(5) })
            addView(label("Retraso al apagar pantalla", 11f, muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            autoDelayControls = LinearLayout(this@CodeSuActivity).apply { orientation = LinearLayout.HORIZONTAL }
            addView(autoDelayControls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

            searchField = EditText(this@CodeSuActivity).apply {
                hint = "Buscar aplicación o paquete"
                textSize = 14f
                setSingleLine(true)
                setTextColor(Color.WHITE)
                setHintTextColor(muted)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = rounded(0xFF0B111D.toInt(), dp(12)).apply { setStroke(dp(1), 0xFF293449.toInt()) }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        query = s?.toString()?.trim().orEmpty()
                        renderApps()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            addView(searchField, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })

            val selectRow = LinearLayout(this@CodeSuActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            selectRow.addView(button("Seleccionar todas", 0xFF1A2940.toInt(), cyan) { updateVisibleSelection(true) },
                LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(5) })
            selectRow.addView(button("Limpiar visibles", 0xFF1A2940.toInt(), muted) { updateVisibleSelection(false) },
                LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(5) })
            addView(selectRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        })

        val listHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@CodeSuActivity).apply {
                text = "Aplicaciones"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            listRefreshButton = Button(this@CodeSuActivity).apply {
                text = "Actualizar lista"
                textSize = 11f
                isAllCaps = false
                setOnClickListener { loadInstalledApps() }
            }
            addView(listRefreshButton, LinearLayout.LayoutParams(-2, dp(40)))
        }
        content.addView(listHeader, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        infoText = label("Cargando aplicaciones…", 12f, muted)
        content.addView(infoText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(8))
        }
        actionButton = Button(this).apply {
            text = "Cerrar seleccionadas (0)"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { confirmStopTargets() }
        }
        footer.addView(actionButton, LinearLayout.LayoutParams(0, dp(50), 1.15f).apply { marginEnd = dp(6) })
        closeAllButton = Button(this).apply {
            text = "Cerrar todo (0)"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { confirmStopAllVisible() }
        }
        footer.addView(closeAllButton, LinearLayout.LayoutParams(0, dp(50), 0.85f).apply { marginStart = dp(6) })
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        SystemBars.applyInsets(this)
        renderControls()
    }

    private fun refreshStatus() {
        val status = SystemCleaner.refreshStatus()
        statusText.text = when (status) {
            SystemCleaner.Status.READY -> "Service running"
            SystemCleaner.Status.NEEDS_PERMISSION -> "Shizuku activo · falta autorizar CodeHub"
            SystemCleaner.Status.NOT_RUNNING -> "Shizuku instalado pero el servicio está apagado"
            SystemCleaner.Status.NOT_INSTALLED -> "Shizuku no está disponible o su API es antigua"
            SystemCleaner.Status.DISCONNECTED -> "No se pudo verificar la conexión de Shizuku"
        }
        val ready = status == SystemCleaner.Status.READY
        statusText.setTextColor(if (ready) 0xFF4ADE80.toInt() else 0xFFFBBF24.toInt())
        engineText.text = if (ready) "Killer engine · Shizuku / ${SystemCleaner.backendDescription()}" else "Killer engine · Shizuku sin conexión"
        engineText.setTextColor(if (ready) 0xFF4ADE80.toInt() else muted)
        connectButton.text = when (status) {
            SystemCleaner.Status.NEEDS_PERMISSION -> "Autorizar CodeHub"
            SystemCleaner.Status.NOT_RUNNING -> "Abrir Shizuku"
            SystemCleaner.Status.NOT_INSTALLED -> "Instalar o actualizar Shizuku"
            else -> "Volver a comprobar"
        }
        connectButton.isEnabled = true
        if (status != SystemCleaner.Status.READY) {
            connectionHelp.text = when (status) {
                SystemCleaner.Status.NEEDS_PERMISSION -> "Pulsa Autorizar CodeHub y acepta el permiso en Shizuku."
                SystemCleaner.Status.NOT_RUNNING -> "Inicia Shizuku desde su app; luego vuelve aquí y comprueba la conexión."
                SystemCleaner.Status.NOT_INSTALLED -> "CodeSu usa Shizuku o Sui para ejecutar cierres con identidad ADB/root."
                else -> "Comprueba que Shizuku esté iniciado y vuelve a autorizar CodeHub si es necesario."
            }
        } else {
            connectionHelp.text = "El inventario activo se consulta con el UserService de Shizuku."
        }
        updateActionButton()
    }

    private fun connectShizuku() {
        when (SystemCleaner.refreshStatus()) {
            SystemCleaner.Status.READY -> {
                refreshStatus()
                loadInstalledApps()
            }
            SystemCleaner.Status.NEEDS_PERMISSION -> requestShizukuPermission()
            SystemCleaner.Status.NOT_RUNNING -> {
                val manager = ShizukuInstaller.getManagerLaunchIntent(this)
                if (manager != null) runCatching { startActivity(manager) }.onFailure { openShizukuDownload() }
                else openShizukuDownload()
            }
            SystemCleaner.Status.NOT_INSTALLED -> openShizukuDownload()
            SystemCleaner.Status.DISCONNECTED -> {
                statusText.text = "Shizuku está desconectado. Abre su app y vuelve a comprobar."
            }
        }
    }

    private fun requestShizukuPermission() {
        connectButton.isEnabled = false
        statusText.text = "Esperando autorización de Shizuku…"
        Thread({
            val granted = runCatching { SystemCleaner.requestPermissionAsyncCompat() }.getOrDefault(false)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (granted) {
                    refreshStatus()
                    loadInstalledApps()
                } else {
                    statusText.text = "No se confirmó el permiso. Revisa el diálogo de Shizuku y vuelve a intentar."
                    connectButton.isEnabled = true
                }
            }
        }, "CodeSu-request-shizuku").start()
    }

    private fun openShizukuDownload() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) }
            .onFailure { statusText.text = "No se pudo abrir la descarga de Shizuku." }
    }

    private fun loadInstalledApps() {
        listRefreshButton.isEnabled = false
        val status = SystemCleaner.refreshStatus()
        if (status != SystemCleaner.Status.READY) {
            allApps.clear()
            listError = "Conecta y autoriza Shizuku para listar solo apps en ejecución."
            listRefreshButton.isEnabled = true
            infoText.text = listError
            renderApps()
            return
        }
        infoText.text = "Consultando apps en ejecución con Shizuku…"
        Thread({
            val result = runCatching {
                val activePackages = bindUserService().runningPackages().orEmpty().toSet()
                val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val launcherPackages = packageManager.queryIntentActivities(launchIntent, 0)
                    .mapNotNull { it.activityInfo?.packageName }
                    .toSet()
                val essential = essentialPackages()
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .asSequence()
                    .filter { it.packageName in activePackages }
                    .filter { it.packageName != packageName && it.packageName != "moe.shizuku.privileged.api" }
                    .map { appInfo ->
                        val label = runCatching { packageManager.getApplicationLabel(appInfo).toString() }
                            .getOrDefault(appInfo.packageName)
                        val icon = runCatching { packageManager.getApplicationIcon(appInfo) }
                            .getOrElse { packageManager.defaultActivityIcon }
                        Candidate(
                            packageName = appInfo.packageName,
                            label = label,
                            icon = icon,
                            isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                            hasLauncher = appInfo.packageName in launcherPackages,
                            isEssential = appInfo.packageName in essential,
                        )
                    }
                    .sortedBy { it.label.lowercase(java.util.Locale.ROOT) }
                    .toList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                listRefreshButton.isEnabled = true
                result.onSuccess { loaded ->
                    listError = null
                    allApps.clear()
                    allApps.addAll(loaded)
                    infoText.text = "${allApps.size} apps con procesos activos detectados."
                    renderApps()
                }.onFailure { error ->
                    allApps.clear()
                    listError = error.message ?: "No se pudieron leer los procesos activos."
                    renderApps()
                    infoText.text = listError
                }
            }
        }, "CodeSu-package-scan").start()
    }

    private fun essentialPackages(): Set<String> = CodeSuAppPolicy.criticalPackages(this)

    private fun visibleCandidates(): List<Candidate> {
        val needle = query.lowercase(java.util.Locale.ROOT)
        return allApps.filter { app ->
            val sourceMatches = when (sourceMode) {
                SourceMode.USER -> !app.isSystem
                SourceMode.LAUNCHABLE -> app.hasLauncher
                SourceMode.SYSTEM -> app.isSystem
            }
            sourceMatches && (!hideCriticalApps || !app.isEssential) && (needle.isEmpty()
                || app.label.lowercase(java.util.Locale.ROOT).contains(needle)
                || app.packageName.lowercase(java.util.Locale.ROOT).contains(needle))
        }
    }

    private fun targetCandidates(): List<Candidate> {
        val visible = visibleCandidates()
        return when (selectionMode) {
            SelectionMode.INCLUDE -> visible.filter { it.packageName in selectedPackages }
            SelectionMode.EXCLUDE -> visible.filterNot { it.packageName in selectedPackages }
        }
    }

    private fun renderControls() {
        if (!::sourceControls.isInitialized) return
        sourceControls.removeAllViews()
        SourceMode.entries.forEach { mode ->
            sourceControls.addView(choiceButton(mode.title, sourceMode == mode) {
                sourceMode = mode
                prefs.edit().putInt("source_mode", mode.ordinal).apply()
                renderControls()
                renderApps()
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                if (mode != SourceMode.USER) marginStart = dp(5)
                if (mode != SourceMode.SYSTEM) marginEnd = dp(5)
            })
        }

        selectionControls.removeAllViews()
        SelectionMode.entries.forEach { mode ->
            selectionControls.addView(choiceButton(mode.title, selectionMode == mode) {
                selectionMode = mode
                prefs.edit().putInt("selection_mode", mode.ordinal).apply()
                renderControls()
                updateActionButton()
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                if (mode == SelectionMode.INCLUDE) marginEnd = dp(5) else marginStart = dp(5)
            })
        }

        killControls.removeAllViews()
        listOf(
            "Soft" to CodeSuUserService.LEVEL_BACKGROUND,
            "Deep" to CodeSuUserService.LEVEL_FORCE_STOP,
            "Killer" to CodeSuUserService.LEVEL_KILLER,
        ).forEach { (title, level) ->
            killControls.addView(choiceButton(title, killMode == level) {
                killMode = level
                prefs.edit().putInt("kill_mode", killMode).apply()
                renderControls()
                updateActionButton()
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                if (level != CodeSuUserService.LEVEL_BACKGROUND) marginStart = dp(3)
                if (level != CodeSuUserService.LEVEL_KILLER) marginEnd = dp(3)
            })
        }
        hideCriticalCheck.isChecked = hideCriticalApps
        killHelp.text = when (killMode) {
            CodeSuUserService.LEVEL_BACKGROUND -> "Soft · am kill; Android decide qué proceso de fondo es seguro cerrar."
            CodeSuUserService.LEVEL_KILLER -> "Killer · fuerza el cierre; usa SIGKILL adicional con motor root."
            else -> "Deep · am force-stop; detiene el paquete hasta que vuelva a abrirse."
        }
        renderListStyleControls()
        renderAutoControls()
    }

    private fun renderListStyleControls() {
        if (!::listStyleControls.isInitialized) return
        listStyleControls.removeAllViews()
        listStyleControls.addView(choiceButton("Cómoda", !compactRows) {
            compactRows = false
            prefs.edit().putBoolean("compact_rows", false).apply()
            renderListStyleControls(); renderApps()
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(5) })
        listStyleControls.addView(choiceButton("Compacta", compactRows) {
            compactRows = true
            prefs.edit().putBoolean("compact_rows", true).apply()
            renderListStyleControls(); renderApps()
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(5) })
    }

    private fun renderAutoControls() {
        if (!::autoControls.isInitialized) return
        autoControls.removeAllViews()
        AutoMode.entries.forEach { mode ->
            autoControls.addView(choiceButton(mode.title, autoMode == mode) {
                val activate = {
                    autoMode = mode
                    persistPreferences()
                    configureAutoKiller()
                    renderAutoControls()
                }
                if (mode != AutoMode.OFF && autoMode == AutoMode.OFF) {
                    AlertDialog.Builder(this)
                        .setTitle("Activar Auto Killer")
                        .setMessage("CodeSu detendrá en segundo plano las apps activas que coincidan con Source mode, Selection mode, Hide critical apps y Kill level. En modo Excluir, una lista vacía incluye todas las apps permitidas por el filtro.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Activar") { _, _ -> activate() }
                        .show()
                } else activate()
            }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                if (mode != AutoMode.OFF) marginStart = dp(3)
                if (mode != AutoMode.DAILY) marginEnd = dp(3)
            })
        }
        autoTimeButton.visibility = if (autoMode == AutoMode.DAILY) View.VISIBLE else View.GONE
        autoTimeButton.text = String.format(java.util.Locale.ROOT, "Hora diaria · %02d:%02d", autoHour, autoMinute)
        autoDelayControls.visibility = if (autoMode == AutoMode.SCREEN_OFF) View.VISIBLE else View.GONE
        autoDelayControls.removeAllViews()
        listOf(0, 1, 5, 15).forEach { delay ->
            autoDelayControls.addView(choiceButton("${delay}m", screenOffDelayMinutes == delay) {
                screenOffDelayMinutes = delay
                prefs.edit().putInt("screen_off_delay_minutes", delay).apply()
                renderAutoControls()
            }, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        autoInfo.text = when (autoMode) {
            AutoMode.OFF -> "Desactivado · CodeSu solo cerrará apps por acción manual."
            AutoMode.SCREEN_OFF -> "Se ejecuta al apagar la pantalla si CodeHub sigue activo; usa el filtro y la selección actuales."
            AutoMode.DAILY -> "Ejecución diaria aproximada a las ${String.format(java.util.Locale.ROOT, "%02d:%02d", autoHour, autoMinute)}; Android puede retrasarla por ahorro de batería."
        }
        prefs.getString("last_auto_result", null)?.let { autoInfo.append("\n$it") }
    }

    private fun showDailyTimePicker() {
        TimePickerDialog(this, { _, hour, minute ->
            autoHour = hour
            autoMinute = minute
            persistPreferences()
            configureAutoKiller()
            renderAutoControls()
        }, autoHour, autoMinute, true).show()
    }

    private fun configureAutoKiller() {
        CodeSuAutoKillerScheduler.configure(this, autoMode.ordinal, autoHour, autoMinute)
    }

    private fun choiceButton(title: String, selected: Boolean, onClick: () -> Unit): Button = Button(this).apply {
        text = title
        textSize = 11f
        isAllCaps = false
        setTextColor(if (selected) Color.WHITE else muted)
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (selected) 0xFF343E69.toInt() else 0xFF1A2030.toInt())
        setOnClickListener { onClick() }
    }

    private fun updateVisibleSelection(select: Boolean) {
        val visible = visibleCandidates().map { it.packageName }
        selectionMode = SelectionMode.INCLUDE
        if (select) selectedPackages.addAll(visible) else selectedPackages.removeAll(visible.toSet())
        persistPreferences()
        renderControls()
        renderApps()
    }

    private fun renderApps() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val visible = visibleCandidates()
        if (visible.isEmpty()) {
            val emptyMessage = listError ?: when {
                SystemCleaner.refreshStatus() != SystemCleaner.Status.READY -> "Conecta Shizuku para consultar los procesos activos."
                allApps.isEmpty() -> "No hay aplicaciones con procesos activos ahora."
                else -> "No hay resultados para este filtro."
            }
            list.addView(label(emptyMessage, 12f, muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        visible.forEach { app ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(if (compactRows) 5 else 9), dp(8), dp(if (compactRows) 5 else 9))
                background = rounded(panel, dp(16)).apply { setStroke(dp(1), 0xFF293449.toInt()) }
            }
            row.addView(ImageView(this).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(if (compactRows) 30 else 38), dp(if (compactRows) 30 else 38)))
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(app.label, 13f, Color.WHITE, true).apply { maxLines = 1 })
                addView(label(app.packageName, 10f, muted).apply { maxLines = 1 })
            }
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            val check = CheckBox(this).apply {
                isChecked = app.packageName in selectedPackages
                contentDescription = if (selectionMode == SelectionMode.INCLUDE)
                    "Incluir ${app.label} en el cierre" else "Excluir ${app.label} del cierre"
                buttonTintList = android.content.res.ColorStateList.valueOf(cyan)
                isEnabled = !hideCriticalApps || !app.isEssential
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedPackages.add(app.packageName) else selectedPackages.remove(app.packageName)
                    persistPreferences()
                    updateActionButton()
                }
            }
            row.addView(check)
            row.setOnClickListener { if (check.isEnabled) check.isChecked = !check.isChecked }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(if (compactRows) 4 else 7) })
        }
        infoText.text = when {
            visible.isEmpty() && allApps.isEmpty() -> "No hay apps activas detectadas. Actualiza la lista o revisa el estado de Shizuku."
            visible.isEmpty() -> "No hay resultados para el filtro o la búsqueda."
            else -> "${visible.size} visibles · ${targetCandidates().size} objetivo(s) según tu selección."
        }
        updateActionButton()
    }

    private fun updateActionButton() {
        if (!::actionButton.isInitialized) return
        val targetCount = targetCandidates().size
        if (::infoText.isInitialized && allApps.isNotEmpty()) {
            infoText.text = "${visibleCandidates().size} visibles · $targetCount objetivo(s) según tu selección."
        }
        actionButton.text = "Cerrar seleccionadas ($targetCount)"
        actionButton.isEnabled = targetCount > 0 && SystemCleaner.refreshStatus() == SystemCleaner.Status.READY
        val allCount = visibleCandidates().size
        closeAllButton.text = "Cerrar todo ($allCount)"
        closeAllButton.isEnabled = allCount > 0 && SystemCleaner.refreshStatus() == SystemCleaner.Status.READY
    }

    private fun confirmStopTargets() {
        val targets = targetCandidates()
        if (targets.isEmpty()) return
        val names = targets.take(5).joinToString(", ") { it.label }
        val extra = if (targets.size > 5) " y ${targets.size - 5} más" else ""
        val action = when (killMode) {
            CodeSuUserService.LEVEL_BACKGROUND -> "Soft · cerrar en segundo plano"
            CodeSuUserService.LEVEL_KILLER -> "Killer · cierre máximo"
            else -> "Deep · forzar detención"
        }
        val systemNote = if (sourceMode == SourceMode.SYSTEM) "\n\nEstás actuando sobre apps del sistema. Algunas sostienen funciones del teléfono." else ""
        AlertDialog.Builder(this)
            .setTitle("$action · ${targets.size} apps")
            .setMessage("${names}${extra}.\n\nUna app detenida puede perder trabajo sin guardar. Android puede volver a iniciarla.$systemNote")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar") { _, _ -> stopPackages(targets) }
            .show()
    }

    private fun confirmStopAllVisible() {
        val targets = visibleCandidates()
        if (targets.isEmpty()) return
        val names = targets.take(5).joinToString(", ") { it.label }
        val extra = if (targets.size > 5) " y ${targets.size - 5} más" else ""
        val systemNote = if (sourceMode == SourceMode.SYSTEM) "\n\nEl filtro incluye apps del sistema." else ""
        AlertDialog.Builder(this)
            .setTitle("Cerrar todas las visibles · ${targets.size}")
            .setMessage("Se aplicará ${killLevelName()} a ${names}${extra}. Respeta Source mode y Hide critical apps, pero ignora la selección individual. Las apps pueden perder trabajo sin guardar.$systemNote")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Cerrar todas") { _, _ -> stopPackages(targets) }
            .show()
    }

    private fun killLevelName(): String = when (killMode) {
        CodeSuUserService.LEVEL_BACKGROUND -> "Soft"
        CodeSuUserService.LEVEL_KILLER -> "Killer"
        else -> "Deep"
    }

    private fun stopPackages(targets: List<Candidate>) {
        actionButton.isEnabled = false
        actionButton.text = "Conectando con Shizuku…"
        Thread({
            val response = runCatching {
                val service = bindUserService()
                val identity = service.diagnostics()
                val result = service.stopPackages(targets.map { it.packageName }.toTypedArray(), killMode)
                Triple(
                    result.getStringArrayList("stopped").orEmpty(),
                    result.getStringArrayList("failed").orEmpty(),
                    result.getString("identity") ?: identity,
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                response.onSuccess { (stopped, failed, identity) ->
                    val failedNames = failed.mapNotNull { pkg -> allApps.firstOrNull { it.packageName == pkg }?.label ?: pkg }
                    val message = buildString {
                        append("Detenidas: ${stopped.size} · Fallidas: ${failed.size}\n")
                        append("Motor: UserService Shizuku ($identity)")
                        if (failedNames.isNotEmpty()) append("\nNo se completó: ${failedNames.joinToString()}")
                        if (killMode == CodeSuUserService.LEVEL_BACKGROUND) append("\nSoft solo cierra procesos que Android considera seguros de terminar.")
                        if (killMode == CodeSuUserService.LEVEL_KILLER && !identity.contains("uid=0")) append("\nCon Shizuku ADB, Killer aplica force-stop; SIGKILL directo requiere root.")
                    }
                    AlertDialog.Builder(this).setTitle("CodeSu").setMessage(message).setPositiveButton("Listo", null).show()
                    loadInstalledApps()
                }.onFailure { error ->
                    AlertDialog.Builder(this)
                        .setTitle("Shizuku no pudo detener las apps")
                        .setMessage(error.message ?: error.javaClass.simpleName)
                        .setPositiveButton("Entendido", null)
                        .show()
                    refreshStatus()
                }
            }
        }, "CodeSu-stop-apps").start()
    }

    private fun bindUserService(): ICodeSuRemoteService {
        synchronized(remoteLock) {
            remoteService?.let { return it }
            if (SystemCleaner.refreshStatus() != SystemCleaner.Status.READY) {
                throw SecurityException("Activa Shizuku y concede permiso a CodeHub antes de continuar.")
            }
            val latch = CountDownLatch(1)
            bindLatch = latch
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            userServiceBound = true
            if (!latch.await(15, TimeUnit.SECONDS)) {
                try { Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true) } catch (_: Throwable) {}
                userServiceBound = false
                throw IllegalStateException("Shizuku no inició el UserService dentro del tiempo esperado.")
            }
            return remoteService ?: throw IllegalStateException("Shizuku enlazó el servicio sin entregar su conexión.")
        }
    }

    private fun persistPreferences() {
        prefs.edit()
            .putStringSet("selected_packages", selectedPackages.toSet())
            .putInt("source_mode", sourceMode.ordinal)
            .putInt("selection_mode", selectionMode.ordinal)
            .putInt("kill_mode", killMode)
            .putBoolean("hide_critical_apps", hideCriticalApps)
            .putInt("auto_mode", autoMode.ordinal)
            .putInt("auto_hour", autoHour)
            .putInt("auto_minute", autoMinute)
            .putInt("screen_off_delay_minutes", screenOffDelayMinutes)
            .putBoolean("compact_rows", compactRows)
            .apply()
    }

    private fun card(top: Int = dp(0), content: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(15), dp(14), dp(15), dp(14))
        background = rounded(panel, dp(18)).apply { setStroke(dp(1), 0xFF293449.toInt()) }
        content()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = top }
    }

    private fun button(text: String, backgroundColor: Int, foregroundColor: Int, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 11f
        isAllCaps = false
        setTextColor(foregroundColor)
        backgroundTintList = android.content.res.ColorStateList.valueOf(backgroundColor)
        setOnClickListener { onClick() }
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
}
