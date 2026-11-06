package com.codehub.app

import android.app.Activity
import android.app.AlertDialog
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
import android.provider.AlarmClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.provider.Telephony
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

/** Native app manager. The installed-app catalogue is local; Shizuku is only needed to stop apps. */
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
    private var protectEssentialApps = true
    private var query = ""

    private lateinit var statusText: TextView
    private lateinit var connectionHelp: TextView
    private lateinit var infoText: TextView
    private lateinit var list: LinearLayout
    private lateinit var actionButton: Button
    private lateinit var connectButton: Button
    private lateinit var searchField: EditText
    private lateinit var sourceControls: LinearLayout
    private lateinit var selectionControls: LinearLayout
    private lateinit var killControls: LinearLayout
    private lateinit var protectCheck: CheckBox
    private lateinit var listRefreshButton: Button

    @Volatile private var remoteService: ICodeSuRemoteService? = null
    @Volatile private var bindLatch: CountDownLatch? = null
    @Volatile private var userServiceBound = false
    private val remoteLock = Any()
    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(this, CodeSuUserService::class.java))
            .daemon(false)
            .processNameSuffix("codesu")
            .debuggable(false)
            .version(1)
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
        protectEssentialApps = prefs.getBoolean("protect_essential_apps", true)
        buildUi()
        refreshStatus()
        loadInstalledApps()
    }

    override fun onResume() {
        super.onResume()
        // After returning from Shizuku, refresh its state without rescanning package labels/icons.
        refreshStatus()
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
            addView(label("CodeSu muestra las apps instaladas según tus filtros. La lista funciona sin Shizuku; el cierre requiere conectar y autorizar Shizuku.", 12f, muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            addView(label("Forzar detención puede cerrar tareas sin guardar. Android puede volver a iniciar una app cuando otro servicio la necesite.", 11f, 0xFFE5C890.toInt()),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
        })

        content.addView(card(dp(10)) {
            statusText = label("Comprobando Shizuku…", 13f, Color.WHITE, true)
            addView(statusText)
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
            addView(label("Tipo de aplicaciones", 13f, Color.WHITE, true))
            sourceControls = LinearLayout(this@CodeSuActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addView(sourceControls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

            addView(label("Qué hacer con las marcadas", 13f, Color.WHITE, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            selectionControls = LinearLayout(this@CodeSuActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addView(selectionControls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })

            addView(label("Modo de cierre", 13f, Color.WHITE, true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            killControls = LinearLayout(this@CodeSuActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            addView(killControls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })

            protectCheck = CheckBox(this@CodeSuActivity).apply {
                text = "Proteger apps esenciales (recomendado)"
                textSize = 12f
                setTextColor(Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(cyan)
                isChecked = protectEssentialApps
                setOnCheckedChangeListener { _, checked ->
                    if (!checked && protectEssentialApps) {
                        isChecked = true
                        AlertDialog.Builder(this@CodeSuActivity)
                            .setTitle("Mostrar apps esenciales")
                            .setMessage("Esto permite incluir en la lista componentes del sistema, como el lanzador, llamadas y servicios centrales. Un cierre forzado puede interrumpir funciones del teléfono.")
                            .setNegativeButton("Mantener protegidas", null)
                            .setPositiveButton("Entiendo") { _, _ ->
                                protectEssentialApps = false
                                protectCheck.isChecked = false
                                persistPreferences()
                                renderControls()
                                renderApps()
                            }
                            .show()
                    } else if (checked != protectEssentialApps) {
                        protectEssentialApps = checked
                        persistPreferences()
                        renderApps()
                    }
                }
            }
            addView(protectCheck, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

            searchField = EditText(this@CodeSuActivity).apply {
                hint = "Buscar aplicación o paquete"
                textSize = 14f
                singleLine = true
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
            selectRow.addView(button("Marcar visibles", 0xFF1A2940.toInt(), cyan) { updateVisibleSelection(true) },
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

        actionButton = Button(this).apply {
            text = "Forzar cierre (0)"
            isAllCaps = false
            isEnabled = false
            setOnClickListener { confirmStopTargets() }
        }
        root.addView(actionButton, LinearLayout.LayoutParams(-1, dp(52)).apply {
            setMargins(dp(18), dp(8), dp(18), dp(10))
        })
        setContentView(root)
        SystemBars.applyInsets(this)
        renderControls()
    }

    private fun refreshStatus() {
        val status = SystemCleaner.refreshStatus()
        statusText.text = when (status) {
            SystemCleaner.Status.READY -> "Shizuku conectado · ${SystemCleaner.backendDescription()}"
            SystemCleaner.Status.NEEDS_PERMISSION -> "Shizuku activo · falta autorizar CodeHub"
            SystemCleaner.Status.NOT_RUNNING -> "Shizuku instalado pero el servicio está apagado"
            SystemCleaner.Status.NOT_INSTALLED -> "Shizuku no está disponible o su API es antigua"
            SystemCleaner.Status.DISCONNECTED -> "No se pudo verificar la conexión de Shizuku"
        }
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
            connectionHelp.text = "La lista se obtiene de Android. El cierre corre dentro del UserService de Shizuku."
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
                if (granted) refreshStatus() else {
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
        infoText.text = "Leyendo aplicaciones instaladas…"
        Thread({
            val result = runCatching {
                val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val launcherPackages = packageManager.queryIntentActivities(launchIntent, 0)
                    .mapNotNull { it.activityInfo?.packageName }
                    .toSet()
                val essential = essentialPackages()
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .asSequence()
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
                    allApps.clear()
                    allApps.addAll(loaded)
                    infoText.text = "${visibleCandidates().size} visibles · ${allApps.size} instaladas según la protección actual."
                    renderApps()
                }.onFailure { error ->
                    allApps.clear()
                    renderApps()
                    infoText.text = error.message ?: "Android no pudo leer las aplicaciones instaladas."
                }
            }
        }, "CodeSu-package-scan").start()
    }

    private fun essentialPackages(): Set<String> = buildSet {
        addAll(setOf(
            "android", "com.android.systemui", "com.android.settings", "com.android.phone",
            "com.android.server.telecom", "com.android.providers.settings", "com.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.gms", "com.google.android.gsf"
        ))
        runCatching {
            packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        }.getOrNull()?.let(::add)
        runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
        }.getOrNull()?.let(::add)
        runCatching {
            (getSystemService(TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
        }.getOrNull()?.let(::add)
        runCatching { Telephony.Sms.getDefaultSmsPackage(this@CodeSuActivity) }.getOrNull()?.let(::add)
        runCatching {
            packageManager.resolveActivity(Intent(AlarmClock.ACTION_SET_ALARM), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()?.let(::add)
    }

    private fun visibleCandidates(): List<Candidate> {
        val needle = query.lowercase(java.util.Locale.ROOT)
        return allApps.filter { app ->
            val sourceMatches = when (sourceMode) {
                SourceMode.USER -> !app.isSystem
                SourceMode.LAUNCHABLE -> app.hasLauncher
                SourceMode.SYSTEM -> app.isSystem
            }
            sourceMatches && (needle.isEmpty()
                || app.label.lowercase(java.util.Locale.ROOT).contains(needle)
                || app.packageName.lowercase(java.util.Locale.ROOT).contains(needle))
        }
    }

    private fun targetCandidates(): List<Candidate> {
        val visible = visibleCandidates().filterNot { protectEssentialApps && it.isEssential }
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
        killControls.addView(choiceButton("Cerrar segundo plano", killMode == CodeSuUserService.LEVEL_BACKGROUND) {
            killMode = CodeSuUserService.LEVEL_BACKGROUND
            prefs.edit().putInt("kill_mode", killMode).apply()
            renderControls()
            updateActionButton()
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(5) })
        killControls.addView(choiceButton("Forzar detención", killMode == CodeSuUserService.LEVEL_FORCE_STOP) {
            killMode = CodeSuUserService.LEVEL_FORCE_STOP
            prefs.edit().putInt("kill_mode", killMode).apply()
            renderControls()
            updateActionButton()
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(5) })
        protectCheck.isChecked = protectEssentialApps
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
        val visible = visibleCandidates().filterNot { protectEssentialApps && it.isEssential }.map { it.packageName }
        if (selectionMode == SelectionMode.INCLUDE) {
            if (select) selectedPackages.addAll(visible) else selectedPackages.removeAll(visible.toSet())
        } else {
            if (select) selectedPackages.removeAll(visible.toSet()) else selectedPackages.addAll(visible)
        }
        persistPreferences()
        renderApps()
    }

    private fun renderApps() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val visible = visibleCandidates()
        if (visible.isEmpty()) {
            list.addView(label(if (allApps.isEmpty()) "No se cargaron aplicaciones." else "No hay resultados para este filtro.", 12f, muted),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        visible.forEach { app ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(8), dp(9))
                background = rounded(panel, dp(16)).apply { setStroke(dp(1), 0xFF293449.toInt()) }
            }
            row.addView(ImageView(this).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(38), dp(38)))
            val copy = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(app.label, 13f, Color.WHITE, true).apply { maxLines = 1 })
                addView(label(app.packageName, 10f, muted).apply { maxLines = 1 })
            }
            row.addView(copy, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            if (protectEssentialApps && app.isEssential) {
                copy.addView(label("Protegida", 9f, cyan))
            }
            val check = CheckBox(this).apply {
                isChecked = app.packageName in selectedPackages
                contentDescription = if (selectionMode == SelectionMode.INCLUDE)
                    "Incluir ${app.label} en el cierre" else "Excluir ${app.label} del cierre"
                buttonTintList = android.content.res.ColorStateList.valueOf(cyan)
                isEnabled = !protectEssentialApps || !app.isEssential
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedPackages.add(app.packageName) else selectedPackages.remove(app.packageName)
                    persistPreferences()
                    updateActionButton()
                }
            }
            row.addView(check)
            row.setOnClickListener { if (check.isEnabled) check.isChecked = !check.isChecked }
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        infoText.text = when {
            visible.isEmpty() && allApps.isEmpty() -> "No se cargaron aplicaciones. Toca Actualizar lista para volver a intentarlo."
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
        actionButton.text = when (killMode) {
            CodeSuUserService.LEVEL_BACKGROUND -> "Cerrar segundo plano ($targetCount)"
            else -> "Forzar detención ($targetCount)"
        }
        actionButton.isEnabled = targetCount > 0 && SystemCleaner.refreshStatus() == SystemCleaner.Status.READY
    }

    private fun confirmStopTargets() {
        val targets = targetCandidates()
        if (targets.isEmpty()) return
        val names = targets.take(5).joinToString(", ") { it.label }
        val extra = if (targets.size > 5) " y ${targets.size - 5} más" else ""
        val action = if (killMode == CodeSuUserService.LEVEL_BACKGROUND) "Cerrar procesos en segundo plano" else "Forzar detención"
        val systemNote = if (sourceMode == SourceMode.SYSTEM) "\n\nEstás actuando sobre apps del sistema. Algunas sostienen funciones del teléfono." else ""
        AlertDialog.Builder(this)
            .setTitle("$action · ${targets.size} apps")
            .setMessage("${names}${extra}.\n\nUna app detenida puede perder trabajo sin guardar. Android puede volver a iniciarla.$systemNote")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar") { _, _ -> stopPackages(targets) }
            .show()
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
                    if (selectionMode == SelectionMode.INCLUDE) selectedPackages.removeAll(stopped.toSet())
                    else selectedPackages.addAll(stopped)
                    persistPreferences()
                    val failedNames = failed.mapNotNull { pkg -> allApps.firstOrNull { it.packageName == pkg }?.label ?: pkg }
                    val message = buildString {
                        append("Detenidas: ${stopped.size} · Fallidas: ${failed.size}\n")
                        append("Motor: UserService Shizuku ($identity)")
                        if (failedNames.isNotEmpty()) append("\nNo se completó: ${failedNames.joinToString()}")
                        if (killMode == CodeSuUserService.LEVEL_BACKGROUND) append("\nEl modo suave solo cierra procesos en segundo plano; Android puede mantener servicios activos.")
                    }
                    AlertDialog.Builder(this).setTitle("CodeSu").setMessage(message).setPositiveButton("Listo", null).show()
                    renderApps()
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
            .putBoolean("protect_essential_apps", protectEssentialApps)
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
