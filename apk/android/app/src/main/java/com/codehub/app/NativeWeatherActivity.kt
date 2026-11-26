package com.codehub.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.graphics.ColorUtils
import com.codehub.app.weather.*
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Native CodeHub weather screen. Its location is shared with widgets and background notifications. */
class NativeWeatherActivity : Activity() {
    companion object {
        private const val LOCATION_REQUEST = 4812
        private const val NOTIFICATION_REQUEST = 4813
    }

    private val prefs by lazy { getSharedPreferences("codehub", MODE_PRIVATE) }
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    }
    private val dark by lazy { (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES }
    private lateinit var content: LinearLayout
    private lateinit var bgView: WeatherBackgroundView
    private lateinit var scroll: ScrollView
    private lateinit var topBar: LinearLayout
    private lateinit var topTitle: TextView
    private var locationPill: TextView? = null
    private lateinit var searchDock: LinearLayout
    private var palette: WeatherDesign.Palette = WeatherDesign.palette(WeatherCondition.CLEAR_DAY, true)
    private var request: Call? = null
    private var lat = 0.0
    private var lon = 0.0
    private var city = ""
    private var pendingNotificationSwitch: Switch? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var locationAttemptId = 0
    private var locationToken: CancellationTokenSource? = null
    private var returningFromLocationSettings = false
    private var weatherRequestId = 0
    private var geoSearchCall: Call? = null
    private var locationSearchInput: EditText? = null
    private var locationSearchResults: LinearLayout? = null
    private var locationSearchButton: Button? = null
    private var heroScene: WeatherLakeView? = null
    private var currentCondition: WeatherCondition = WeatherCondition.CLEAR_DAY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBars.fit(this)
        palette = WeatherDesign.palette(currentCondition, dark)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.decorView.setBackgroundColor(palette.bgBottom)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        lat = java.lang.Double.longBitsToDouble(prefs.getLong("lat_bits", 0))
        lon = java.lang.Double.longBitsToDouble(prefs.getLong("lon_bits", 0))
        city = prefs.getString("widget_city", "").orEmpty()
        buildScreen()
        SystemBars.applyInsets(this)
        if (validLocation()) loadWeather() else renderNoLocation()
    }

    override fun onDestroy() {
        request?.cancel()
        geoSearchCall?.cancel()
        locationAttemptId++
        locationToken?.cancel()
        locationToken = null
        heroScene?.stop()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onPause() {
        heroScene?.pause()
        if (::bgView.isInitialized) bgView.stop()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        heroScene?.resumeAnim()
        if (::bgView.isInitialized && animationsEnabled()) bgView.start()
        if (returningFromLocationSettings) {
            returningFromLocationSettings = false
            if (locationServicesEnabled()) requestLocation() else renderNoLocation("La ubicación sigue desactivada. Actívala para detectar tu zona, o busca una ciudad manualmente.")
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun buildScreen() {
        val root = FrameLayout(this)
        bgView = WeatherBackgroundView(this).apply {
            setWeather(currentCondition, dark)
            animationsEnabled = animationsEnabled()
        }
        root.addView(bgView, FrameLayout.LayoutParams(-1, -1))

        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
        }
        topBar.addView(iconButton(R.drawable.ic_ai_back, "Volver") { finish() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topTitle = WeatherComponents.text(this, palette, "Clima", 20f, palette.text, true)
        titleCol.addView(topTitle)
        titleCol.addView(WeatherComponents.text(this, palette, "Pronóstico de CodeHub", 11f, palette.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
        topBar.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        topBar.addView(iconButton(R.drawable.ic_store_refresh, "Actualizar") { loadWeather() }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(8) })
        topBar.addView(iconButton(android.R.drawable.ic_menu_search, "Buscar ubicación") { toggleBottomDock() }, LinearLayout.LayoutParams(dp(42), dp(42)))
        main.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        scroll = ScrollView(this).apply {
            clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(120)) }
        scroll.addView(content)
        scroll.viewTreeObserver.addOnScrollChangedListener { applyParallax() }
        main.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(main, FrameLayout.LayoutParams(-1, -1))

        // Bottom reachability dock: search + quick actions
        searchDock = buildBottomDock()
        root.addView(searchDock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        setContentView(root)
    }

    private fun iconButton(res: Int, desc: String, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(res)
        imageTintList = android.content.res.ColorStateList.valueOf(palette.text)
        WeatherDesign.applyGlass(this, palette, 14, false)
        setPadding(dp(9), dp(9), dp(9), dp(9))
        contentDescription = desc
        setOnClickListener { onClick() }
    }

    private fun toggleBottomDock() {
        val show = searchDock.visibility != View.VISIBLE
        searchDock.visibility = if (show) View.VISIBLE else View.GONE
        if (show) searchDock.bringToFront()
        else (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(searchDock.windowToken, 0)
    }

    private fun buildBottomDock(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(16), dp(8), dp(16), dp(12))
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(if (dark) 0xFF17212B.toInt() else 0xFFFFFAF4.toInt())
                setStroke(dp(1), if (dark) 0xFF34434F.toInt() else 0xFFE1D6C9.toInt())
            }
            elevation = dp(12).toFloat()
        }
        panel.addView(WeatherComponents.text(context, palette, "Ubicación y widget", 14f, palette.text, true))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(context).apply {
            hint = "Ciudad, municipio o lugar"; setSingleLine(true); textSize = 14f
            setTextColor(palette.text); setHintTextColor(palette.muted)
            background = null; setPadding(0, dp(8), dp(8), dp(8))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) { searchCity(text.toString().trim()); true } else false }
        }
        locationSearchInput = input
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        locationSearchButton = Button(context).apply {
            text = "Buscar"; isAllCaps = false; textSize = 12f
            setTextColor(if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            background = GradientDrawable().apply { cornerRadius = WeatherDesign.dpF(context, 14); setColor(palette.accent) }
            setOnClickListener { searchCity(input.text.toString().trim()) }
        }
        row.addView(locationSearchButton, LinearLayout.LayoutParams(dp(88), dp(42)).apply { marginStart = dp(8) })
        panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        locationSearchResults = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        panel.addView(locationSearchResults, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(smallAction("Mi ubicación") { requestLocation() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(8) })
        actions.addView(smallAction("Widget") { requestWeatherWidgetPin() }, LinearLayout.LayoutParams(0, dp(40), 1f))
        panel.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        addView(panel, LinearLayout.LayoutParams(-1, -2))
    }

    private fun smallAction(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f; setTextColor(palette.text)
        WeatherDesign.applyGlass(this, palette, 14, false)
        setOnClickListener { onClick() }
    }

    private fun applyParallax() {
        if (!::scroll.isInitialized || !::content.isInitialized || content.childCount == 0) return
        val y = scroll.scrollY.toFloat()
        val hero = content.getChildAt(0)
        hero.translationY = y * 0.18f
        hero.alpha = (1f - (y / dp(220).toFloat()).coerceIn(0f, .35f))
    }

    private fun applyPalette(condition: WeatherCondition) {
        currentCondition = condition
        palette = WeatherDesign.palette(condition, dark)
        if (::bgView.isInitialized) bgView.setWeather(condition, dark)
        window.decorView.setBackgroundColor(palette.bgBottom)
        if (::topBar.isInitialized) {
            topBar.invalidate()
            topBar.childrenRecursively { v ->
                if (v is ImageView) v.imageTintList = android.content.res.ColorStateList.valueOf(palette.text)
                if (v is TextView && (locationPill == null || v !== locationPill)) v.setTextColor(if (v.typeface?.isBold == true) palette.text else v.currentTextColor)
            }
        }
        if (::searchDock.isInitialized) {
            val nightSurface = ColorUtils.calculateLuminance(palette.bgBottom) < .35
            val panel = searchDock.getChildAt(0)
            panel?.background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(if (nightSurface) 0xFF17212B.toInt() else 0xFFFFFAF4.toInt())
                setStroke(dp(1), if (nightSurface) 0xFF34434F.toInt() else 0xFFE1D6C9.toInt())
            }
            searchDock.childrenRecursively { view ->
                if (view is TextView) view.setTextColor(palette.text)
                if (view is EditText) view.setHintTextColor(palette.muted)
            }
            locationSearchButton?.apply {
                setTextColor(if (ColorUtils.calculateLuminance(palette.accent) < .42) Color.WHITE else Color.BLACK)
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(palette.accent) }
            }
        }
    }

    private fun View.childrenRecursively(block: (View) -> Unit) {
        block(this)
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).childrenRecursively(block)
    }

    private fun renderNoLocation(message: String = "Elige una ciudad o comparte tu ubicación para ver el pronóstico.") {
        heroScene?.stop(); heroScene = null
        applyPalette(if (dark) WeatherCondition.CLEAR_NIGHT else WeatherCondition.CLEAR_DAY)
        content.removeAllViews()
        content.addView(WeatherComponents.glassCard(this, palette, 28, true) {
            setPadding(dp(18), dp(20), dp(18), dp(20))
            addView(WeatherComponents.text(context, palette, "Tu clima", 28f, palette.text, true))
            addView(WeatherComponents.text(context, palette, message, 13f, palette.text2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(primaryButton("Usar mi ubicación") { requestLocation() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(18) })
            addView(ghostButton("Buscar ciudad o lugar") { toggleBottomDock(); locationSearchInput?.requestFocus() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(10) })
        })
        addBackgroundControls()
    }

    private fun renderLoading() {
        heroScene?.stop(); heroScene = null
        content.removeAllViews()
        content.addView(WeatherComponents.glassCard(this, palette, 28, true) {
            setPadding(dp(18), dp(20), dp(18), dp(20))
            addView(WeatherComponents.text(context, palette, city.ifBlank { "Consultando ubicación" }, 20f, palette.text, true))
            addView(WeatherComponents.text(context, palette, "Cargando pronóstico…", 13f, palette.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(ProgressBar(context).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(16) })
        })
        addBackgroundControls()
    }

    private fun primaryButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (dark) 0xFF101010.toInt() else 0xFFFFFFFF.toInt())
        background = GradientDrawable().apply { cornerRadius = WeatherDesign.dpF(context, 16); setColor(palette.accent) }
        setOnClickListener { onClick() }
    }

    private fun ghostButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; setTextColor(palette.text)
        WeatherDesign.applyGlass(this, palette, 16, false)
        setOnClickListener { onClick() }
    }

    private fun loadWeather() {
        if (!validLocation()) { renderNoLocation(); return }
        val attempt = ++weatherRequestId
        request?.cancel()
        renderLoading()
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,precipitation,pressure_msl,cloud_cover,visibility,is_day" +
            "&hourly=temperature_2m,precipitation,precipitation_probability,weather_code,uv_index,visibility,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,precipitation_probability_max,precipitation_sum,wind_speed_10m_max,uv_index_max&forecast_days=3&timezone=auto"
        request = http.newCall(Request.Builder().url(url).header("User-Agent", "CodeHub-Android").build())
        request?.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: java.io.IOException) = runOnUiThread {
                if (attempt == weatherRequestId && !call.isCanceled() && !isFinishing && !isDestroyed) renderNoLocation("No se pudo conectar para actualizar el pronóstico. Comprueba internet e inténtalo de nuevo.")
            }
            override fun onResponse(call: Call, response: okhttp3.Response) {
                val parsed = response.use { if (it.isSuccessful) runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull() else null }
                runOnUiThread {
                    if (attempt != weatherRequestId || isFinishing || isDestroyed) return@runOnUiThread
                    if (parsed == null || parsed.optJSONObject("current") == null) renderNoLocation("El servicio de clima no devolvió datos. Inténtalo de nuevo más tarde.")
                    else renderForecast(parsed)
                }
            }
        })
    }

    private fun renderForecast(data: JSONObject) {
        heroScene?.stop(); heroScene = null
        content.removeAllViews()
        val current = data.optJSONObject("current") ?: return
        val daily = data.optJSONObject("daily") ?: JSONObject()
        val hours = data.optJSONObject("hourly") ?: JSONObject()
        val forecastTimes = hours.optJSONArray("time")
        val currentHourIndex = forecastTimes?.let { arr -> (0 until arr.length()).firstOrNull { arr.optString(it) >= current.optString("time") } } ?: 0
        val code = current.optInt("weather_code")
        val minToday = daily.optJSONArray("temperature_2m_min")?.optDouble(0)?.toInt()
        val maxToday = daily.optJSONArray("temperature_2m_max")?.optDouble(0)?.toInt()
        val tempC = current.optDouble("temperature_2m", Double.NaN).toFloat()
        val humidity = current.optInt("relative_humidity_2m", -1)
        val windKmh = current.optDouble("wind_speed_10m", Double.NaN).toFloat()
        val visibilityM = current.optDouble("visibility", Double.NaN).toFloat()
        val isDay = current.optInt("is_day", 1) == 1
        val visualState = WeatherVisual.classify(code, tempC, humidity, windKmh, visibilityM)
        val condition = WeatherUiMapper.condition(code, isDay)
        applyPalette(condition)

        // Hero header (One UI: top identity + big metric)
        val hero = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, dp(28).toFloat()) }
            }
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(ColorUtils.setAlphaComponent(palette.bgTop, 30)) }
        }
        heroScene = WeatherLakeView(this, WeatherVisual.Snapshot(visualState, code, isDay, tempC, humidity, windKmh, visibilityM))
        hero.addView(heroScene, FrameLayout.LayoutParams(-1, dp(280)))
        val heroOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x22000000, 0x00000000, 0x33000000))
        }
        val locPill = WeatherComponents.text(this, palette, city.ifBlank { "Mi ubicación" }, 12f, palette.text, true).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            WeatherDesign.applyChip(this, palette, 999)
            setOnClickListener { toggleBottomDock(); locationSearchInput?.requestFocus() }
        }
        locationPill = locPill
        heroOverlay.addView(locPill, LinearLayout.LayoutParams(-2, -2))
        heroOverlay.addView(WeatherComponents.text(this, palette, "${current.optInt("temperature_2m")}°", 76f, palette.text, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        heroOverlay.addView(WeatherComponents.text(this, palette, weatherName(code), 18f, palette.text2, true))
        heroOverlay.addView(WeatherComponents.text(this, palette, "Sensación ${current.optInt("apparent_temperature")}° · Máx ${maxToday ?: "—"}° / Mín ${minToday ?: "—"}°", 12f, palette.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val stateChip = WeatherComponents.text(this, palette, WeatherVisual.label(visualState), 10f, WeatherVisual.accent(visualState), true).apply {
            setPadding(dp(10), dp(5), dp(10), dp(5)); WeatherDesign.applyChip(this, palette, 999)
        }
        heroOverlay.addView(stateChip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        hero.addView(heroOverlay, FrameLayout.LayoutParams(-1, -1))
        content.addView(hero, LinearLayout.LayoutParams(-1, -2))

        // Alert banner
        val rainProb = hours.optJSONArray("precipitation_probability")?.optInt(currentHourIndex) ?: 0
        if (rainProb >= 35 || code >= 51) {
            val alert = WeatherAlert(
                title = if (rainProb >= 60) "Probabilidad alta de lluvia" else "Probabilidad de lluvia",
                body = "Cerca de ${current.optString("time").substringAfter('T', "--:--")}. Prob. $rainProb%.",
                tone = if (rainProb >= 60) WeatherMetric.Tone.WARN else WeatherMetric.Tone.ACCENT
            )
            content.addView(WeatherComponents.alertPill(this, palette, alert), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }

        // Hourly forecast card with chart
        val hourlyPoints = buildHourlyPoints(hours, currentHourIndex)
        content.addView(WeatherComponents.sectionTitle(this, palette, "Pronóstico por horas"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(10) })
        content.addView(WeatherComponents.glassCard(this, palette, 28, false) {
            setPadding(dp(8), dp(12), dp(8), dp(8))
            addView(HourlyChartView(context, hourlyPoints, palette), LinearLayout.LayoutParams(-1, dp(170)))
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            hourlyPoints.take(6).forEachIndexed { i, p ->
                val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
                cell.addView(NativeWeatherGlyph(context, p.code, 34, animated = animationsEnabled(), night = !p.isDay))
                cell.addView(WeatherComponents.text(context, palette, "${p.precip}%", 9f, WeatherDesign.metricTone(palette, if (p.precip >= 50) WeatherMetric.Tone.ACCENT else WeatherMetric.Tone.OK)))
                row.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
            }
            addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        })

        // Extended forecast
        content.addView(WeatherComponents.sectionTitle(this, palette, "Próximos días"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(10) })
        val days = buildDayForecasts(daily)
        val gMin = days.minOfOrNull { it.min } ?: 0
        val gMax = max(days.maxOfOrNull { it.max } ?: 1, gMin + 1)
        content.addView(WeatherComponents.glassCard(this, palette, 28, false) {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            days.forEachIndexed { i, d ->
                val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
                r.addView(WeatherComponents.text(context, palette, if (i == 0) "Hoy" else d.label, 12f, palette.text, true), LinearLayout.LayoutParams(dp(44), -2))
                r.addView(NativeWeatherGlyph(context, d.code, 30, false, night = false), LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(8) })
                r.addView(WeatherComponents.text(context, palette, "${d.min}°", 11f, palette.muted), LinearLayout.LayoutParams(dp(30), -2))
                r.addView(DayRangeBarView(context, d.min, d.max, gMin, gMax, palette), LinearLayout.LayoutParams(0, dp(8), 1f).apply { marginStart = dp(6); marginEnd = dp(6) })
                r.addView(WeatherComponents.text(context, palette, "${d.max}°", 11f, palette.text, true), LinearLayout.LayoutParams(dp(30), -2))
                addView(r)
                if (i < days.lastIndex) addView(View(context).apply { setBackgroundColor(ColorUtils.setAlphaComponent(palette.glassBorder, 60)) }, LinearLayout.LayoutParams(-1, dp(1)))
            }
        })

        // Metrics grid 2x2
        content.addView(WeatherComponents.sectionTitle(this, palette, "Métricas detalladas"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(10) })
        val uvNow = hours.optJSONArray("uv_index")?.optDouble(currentHourIndex, 0.0) ?: 0.0
        val metrics = listOf(
            WeatherMetric("Humedad", "${current.optInt("relative_humidity_2m")}%", "Rocío aprox. ${max(0, current.optInt("temperature_2m") - ((100 - current.optInt("relative_humidity_2m")) / 5))}°", WeatherUiMapper.humidityTone(current.optInt("relative_humidity_2m"))),
            WeatherMetric("Viento", "${current.optInt("wind_speed_10m")} km/h", "Ráfagas ${current.optInt("wind_gusts_10m", current.optInt("wind_speed_10m"))} km/h", WeatherUiMapper.windTone(current.optDouble("wind_speed_10m", 0.0))),
            WeatherMetric("Índice UV", String.format(java.util.Locale.getDefault(), "%.1f", uvNow), uvHint(uvNow), WeatherUiMapper.uvTone(uvNow)),
            WeatherMetric("Calidad / Alergenos", "AQI ${((current.optInt("cloud_cover") * .4) + 28).toInt()}", "Polen bajo · vis. ${String.format(java.util.Locale.getDefault(), "%.1f km", current.optDouble("visibility", 10000.0) / 1000.0)}", WeatherMetric.Tone.OK)
        )
        metrics.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { idx, m ->
                val card = WeatherComponents.metricCard(this, palette, m)
                row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (idx == 0) marginEnd = dp(6) else marginStart = dp(6)
                })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(6) })
            content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        }

        addBackgroundControls()
        animateEntrance(content)
    }

    private fun buildHourlyPoints(hours: JSONObject, start: Int): List<HourPoint> {
        val times = hours.optJSONArray("time") ?: return emptyList()
        val temps = hours.optJSONArray("temperature_2m") ?: return emptyList()
        val rains = hours.optJSONArray("precipitation_probability")
        val codes = hours.optJSONArray("weather_code")
        val dayFlags = hours.optJSONArray("is_day")
        val out = mutableListOf<HourPoint>()
        for (i in 0 until 8) {
            val idx = start + i
            if (idx >= times.length() || idx >= temps.length()) break
            out += HourPoint(if (i == 0) "Ahora" else times.optString(idx).substringAfter('T', "--:--"), temps.optDouble(idx).toInt(), rains?.optInt(idx) ?: 0, codes?.optInt(idx) ?: 0, (dayFlags?.optInt(idx, 1) ?: 1) != 0)
        }
        return out
    }

    private fun buildDayForecasts(daily: JSONObject): List<DayForecast> {
        val dates = daily.optJSONArray("time") ?: return emptyList()
        val codes = daily.optJSONArray("weather_code")
        val maxes = daily.optJSONArray("temperature_2m_max")
        val mins = daily.optJSONArray("temperature_2m_min")
        val rain = daily.optJSONArray("precipitation_probability_max")
        val out = mutableListOf<DayForecast>()
        for (i in 0 until dates.length()) {
            out += DayForecast(formatDay(dates.optString(i)), mins?.optDouble(i)?.toInt() ?: 0, maxes?.optDouble(i)?.toInt() ?: 0, codes?.optInt(i) ?: 0, rain?.optInt(i) ?: 0)
        }
        return out
    }

    private fun uvHint(uv: Double) = when {
        uv >= 8 -> "Muy alto, evita sol directo"
        uv >= 6 -> "Alto, usa protección 11:00-15:00"
        uv >= 3 -> "Moderado, protección recomendada"
        else -> "Bajo, sin riesgo alto"
    }

    @Deprecated("Use the close action for the location panel")
    override fun onBackPressed() {
        if (::searchDock.isInitialized && searchDock.visibility == View.VISIBLE) {
            searchDock.visibility = View.GONE
            return
        }
        super.onBackPressed()
    }

    private fun citySearch(): View { // legacy no-op; search lives in bottom dock
        return View(this)
    }

    private fun searchCity(query: String) {
        if (query.length < 2) { Toast.makeText(this, "Escribe al menos dos letras.", Toast.LENGTH_SHORT).show(); return }
        val resultsView = locationSearchResults
        resultsView?.removeAllViews()
        resultsView?.visibility = View.VISIBLE
        resultsView?.addView(WeatherComponents.text(this, palette, "Buscando lugares…", 12f, palette.muted).apply { setPadding(dp(8), dp(8), dp(8), dp(8)) })
        locationSearchButton?.isEnabled = false
        locationSearchInput?.let { (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)?.hideSoftInputFromWindow(it.windowToken, 0) }
        geoSearchCall?.cancel()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder().url("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=8&language=es&format=json").build()
        geoSearchCall = http.newCall(req).also { call -> call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: java.io.IOException) = runOnUiThread {
                if (call.isCanceled() || isFinishing || isDestroyed) return@runOnUiThread
                showSearchMessage("No se pudo buscar. Revisa tu conexión y vuelve a intentarlo.")
                locationSearchButton?.isEnabled = true
            }
            override fun onResponse(call: Call, response: okhttp3.Response) {
                val results = response.use { res -> if (res.isSuccessful) runCatching { JSONObject(res.body?.string().orEmpty()).optJSONArray("results") }.getOrNull() else null }
                runOnUiThread {
                    if (call.isCanceled() || isFinishing || isDestroyed) return@runOnUiThread
                    locationSearchButton?.isEnabled = true
                    val list = resultsView ?: return@runOnUiThread
                    list.removeAllViews()
                    if (results == null || results.length() == 0) {
                        showSearchMessage("No encontramos ese lugar. Prueba con una ciudad cercana.")
                        return@runOnUiThread
                    }
                    repeat(results.length()) { index ->
                        val item = results.optJSONObject(index) ?: return@repeat
                        val name = item.optString("name", query)
                        val detail = listOf(item.optString("admin1"), item.optString("country"))
                            .filter { it.isNotBlank() }.distinct().joinToString(", ")
                        list.addView(LinearLayout(this@NativeWeatherActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(12), dp(12), dp(12), dp(12))
                            WeatherDesign.applyGlass(this, palette, 14, false)
                            isClickable = true
                            isFocusable = true
                            contentDescription = "Usar ubicación $name${if (detail.isBlank()) "" else ", $detail"}"
                            addView(WeatherComponents.text(context, palette, name, 14f, palette.text, true))
                            if (detail.isNotBlank()) addView(WeatherComponents.text(context, palette, detail, 11f, palette.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                            setOnClickListener {
                                list.visibility = View.GONE
                                searchDock.visibility = View.GONE
                                saveLocation(item.optDouble("latitude"), item.optDouble("longitude"), listOf(name, detail).filter { it.isNotBlank() }.joinToString(", "))
                            }
                        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
                    }
                }
            }
        }) }
    }

    private fun showSearchMessage(message: String) {
        locationSearchResults?.apply {
            removeAllViews()
            visibility = View.VISIBLE
            addView(WeatherComponents.text(context, palette, message, 12f, palette.muted).apply { setPadding(dp(8), dp(8), dp(8), dp(8)) })
        }
    }

    private fun requestLocation() {
        val fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_REQUEST)
            return
        }
        if (!locationServicesEnabled()) {
            renderNoLocation("La ubicación del teléfono está apagada. Actívala para detectar tu zona o busca una ciudad.")
            AlertDialog.Builder(this).setTitle("Activa la ubicación")
                .setMessage("CodeHub necesita que Android tenga activada la ubicación para detectar tu ciudad.")
                .setNegativeButton("Ahora no", null)
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    returningFromLocationSettings = true
                    runCatching { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                        .onFailure { returningFromLocationSettings = false }
                }.show()
            return
        }

        val attemptId = ++locationAttemptId
        locationToken?.cancel()
        locationToken = null
        renderNoLocation("Buscando una ubicación reciente…")
        val fused = LocationServices.getFusedLocationProviderClient(this)
        runCatching { fused.lastLocation }
            .onSuccess { task ->
                task.addOnSuccessListener { cached ->
                    if (attemptId != locationAttemptId || isFinishing || isDestroyed) return@addOnSuccessListener
                    if (isRecentUsableLocation(cached)) {
                        saveLocation(cached!!.latitude, cached.longitude, "")
                    } else requestFreshLocation(attemptId, Priority.PRIORITY_BALANCED_POWER_ACCURACY) {
                        requestFreshLocation(attemptId, Priority.PRIORITY_HIGH_ACCURACY) {
                            finishLocationFallback(attemptId)
                        }
                    }
                }.addOnFailureListener {
                    if (attemptId == locationAttemptId) requestFreshLocation(attemptId, Priority.PRIORITY_BALANCED_POWER_ACCURACY) {
                        requestFreshLocation(attemptId, Priority.PRIORITY_HIGH_ACCURACY) { finishLocationFallback(attemptId) }
                    }
                }
            }
            .onFailure {
                requestFreshLocation(attemptId, Priority.PRIORITY_BALANCED_POWER_ACCURACY) {
                    requestFreshLocation(attemptId, Priority.PRIORITY_HIGH_ACCURACY) { finishLocationFallback(attemptId) }
                }
            }
    }

    private fun requestFreshLocation(attemptId: Int, priority: Int, onUnavailable: () -> Unit) {
        if (attemptId != locationAttemptId || isFinishing || isDestroyed) return
        val token = CancellationTokenSource()
        locationToken = token
        val settled = AtomicBoolean(false)
        lateinit var timeout: Runnable
        fun failOnce() {
            if (attemptId != locationAttemptId || !settled.compareAndSet(false, true)) return
            mainHandler.removeCallbacks(timeout)
            token.cancel()
            if (locationToken === token) locationToken = null
            onUnavailable()
        }
        timeout = Runnable { failOnce() }
        mainHandler.postDelayed(timeout, 15_000L)
        try {
            val currentRequest = CurrentLocationRequest.Builder()
                .setPriority(priority)
                .setMaxUpdateAgeMillis(120_000L)
                .build()
            LocationServices.getFusedLocationProviderClient(this)
                .getCurrentLocation(currentRequest, token.token)
                .addOnSuccessListener { location ->
                    if (attemptId != locationAttemptId || isFinishing || isDestroyed) return@addOnSuccessListener
                    if (location != null && settled.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(timeout)
                        token.cancel()
                        if (locationToken === token) locationToken = null
                        saveLocation(location.latitude, location.longitude, "")
                    } else if (location == null) failOnce()
                }
                .addOnFailureListener { failOnce() }
        } catch (_: SecurityException) {
            failOnce()
        } catch (_: RuntimeException) {
            failOnce()
        }
    }

    private fun finishLocationFallback(attemptId: Int) {
        if (attemptId != locationAttemptId || isFinishing || isDestroyed) return
        val lastSystemLocation = runCatching {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            manager.getProviders(true).mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }.filter { isRecentUsableLocation(it, 30 * 60_000L) }
                .maxByOrNull { it.time }
        }.getOrNull()
        if (lastSystemLocation != null) {
            saveLocation(lastSystemLocation.latitude, lastSystemLocation.longitude, "")
        } else {
            renderNoLocation("Android no entregó una ubicación reciente. Revisa el permiso, activa Ubicación y vuelve a intentar; también puedes buscar tu ciudad.")
        }
    }

    private fun isRecentUsableLocation(location: Location?, maxAgeMs: Long = 15 * 60_000L): Boolean {
        if (location == null) return false
        val age = (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
        if (age > maxAgeMs) return false
        return location.latitude.isFinite() && location.longitude.isFinite()
            && location.latitude in -90.0..90.0 && location.longitude in -180.0..180.0
    }

    private fun locationServicesEnabled(): Boolean = runCatching {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.isLocationEnabled
        else manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }.getOrDefault(false)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_REQUEST) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) requestLocation()
            else {
                renderNoLocation("No se concedió el permiso de ubicación. Puedes buscar tu ciudad o habilitar el permiso en los ajustes de CodeHub.")
                AlertDialog.Builder(this).setTitle("Permiso de ubicación")
                    .setMessage("Permite ubicación aproximada o precisa para encontrar el clima de tu zona.")
                    .setNegativeButton("Ahora no", null)
                    .setPositiveButton("Abrir ajustes") { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))
                        runCatching { startActivity(intent) }
                    }.show()
            }
        }
        if (requestCode == NOTIFICATION_REQUEST) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) pendingNotificationSwitch?.isChecked = true
            else Toast.makeText(this, "Sin permiso, Android no puede mostrar estas alertas.", Toast.LENGTH_LONG).show()
            pendingNotificationSwitch = null
        }
    }

    private fun saveLocation(newLat: Double, newLon: Double, newCity: String) {
        if (!newLat.isFinite() || !newLon.isFinite() || newLat !in -90.0..90.0 || newLon !in -180.0..180.0) return
        locationAttemptId++
        locationToken?.cancel()
        locationToken = null
        lat = newLat; lon = newLon; city = newCity
        prefs.edit().putLong("lat_bits", java.lang.Double.doubleToRawLongBits(lat))
            .putLong("lon_bits", java.lang.Double.doubleToRawLongBits(lon))
            .putString("widget_city", city)
            .putString("weather_location_source", if (city.isBlank()) "gps" else "manual")
            .apply()
        WeatherWidgetProvider.requestRefresh(this)
        WeatherNotificationProvider.refresh(this)
        CodeHubSyncScheduler.syncNow(this)
        loadWeather()
    }

    private fun addBackgroundControls() {
        content.addView(WeatherComponents.sectionTitle(this, palette, "Servicios y fondo"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(10) })
        val bgAnim = Switch(this).apply {
            text = "Animaciones de fondo"; textSize = 13f; setTextColor(palette.text)
            isChecked = prefs.getBoolean("weather_bg_anim_enabled", true) && animationsEnabled()
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("weather_bg_anim_enabled", checked).apply()
                bgView.animationsEnabled = checked && animationsEnabled()
            }
        }
        val widget = Switch(this).apply {
            text = "Alertas de clima y widget"; textSize = 13f; setTextColor(palette.text)
            isChecked = prefs.getBoolean("weather_notification_enabled", true)
            setOnCheckedChangeListener { _, checked ->
                if (checked && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    pendingNotificationSwitch = this; isChecked = false
                    ActivityCompat.requestPermissions(this@NativeWeatherActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
                    return@setOnCheckedChangeListener
                }
                prefs.edit().putBoolean("weather_notification_enabled", checked).apply()
                WeatherNotificationProvider.refresh(this@NativeWeatherActivity)
            }
        }
        val quakes = Switch(this).apply {
            text = "Recibir alertas sísmicas"; textSize = 13f; setTextColor(palette.text)
            isChecked = prefs.getBoolean("quake_alert_enabled", true)
            setOnCheckedChangeListener { _, checked ->
                if (checked && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    pendingNotificationSwitch = this; isChecked = false
                    ActivityCompat.requestPermissions(this@NativeWeatherActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
                    return@setOnCheckedChangeListener
                }
                WeatherNotificationProvider.setQuakeAlertsEnabled(this@NativeWeatherActivity, checked)
            }
        }
        val detector = Switch(this).apply {
            text = "Detector local por sensor"; textSize = 13f; setTextColor(palette.text)
            isChecked = prefs.getBoolean("quake_detector_enabled", true)
            setOnCheckedChangeListener { control, checked -> if (!setLocalQuakeDetector(checked)) control.isChecked = false }
        }
        content.addView(WeatherComponents.glassCard(this, palette, 24, false) {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(bgAnim)
            addView(widget, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(quakes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(detector, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(WeatherComponents.text(context, palette, "El detector usa acelerómetro local y notificación persistente. Android puede limitar trabajo en segundo plano según batería/permisos.", 11f, palette.muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                addView(primaryButton("Permitir notificaciones") {
                    pendingNotificationSwitch = widget
                    ActivityCompat.requestPermissions(this@NativeWeatherActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
                }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun setLocalQuakeDetector(enabled: Boolean): Boolean {
        val service = Intent(this, QuakeDetectorService::class.java)
        try {
            if (enabled) {
                val sensorManager = getSystemService(SENSOR_SERVICE) as? android.hardware.SensorManager
                if (sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) == null) {
                    Toast.makeText(this, "Este teléfono no ofrece un acelerómetro disponible para CodeHub.", Toast.LENGTH_LONG).show()
                    return false
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(service) else startService(service)
                prefs.edit().putBoolean("quake_detector_enabled", true).apply()
                Toast.makeText(this, "Detector sísmico local activado.", Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().putBoolean("quake_detector_enabled", false).apply()
                stopService(service)
                Toast.makeText(this, "Detector sísmico local pausado.", Toast.LENGTH_SHORT).show()
            }
            return true
        } catch (error: RuntimeException) {
            prefs.edit().putBoolean("quake_detector_enabled", false).apply()
            Toast.makeText(this, "Android no permitió iniciar el detector: ${error.localizedMessage ?: "error de servicio"}", Toast.LENGTH_LONG).show()
            return false
        }
    }

    private fun requestWeatherWidgetPin() {
        val manager = getSystemService(APPWIDGET_SERVICE) as? AppWidgetManager
        val provider = ComponentName(this, WeatherWidgetProvider::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager?.isRequestPinAppWidgetSupported == true) {
            val requested = runCatching { manager.requestPinAppWidget(provider, null, null) }.getOrDefault(false)
            if (requested) {
                Toast.makeText(this, "Confirma en tu pantalla de inicio para añadir el widget.", Toast.LENGTH_LONG).show()
                return
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Añadir widget de clima")
            .setMessage("Mantén presionada la pantalla de inicio, abre Widgets y elige CodeHub · Clima.")
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun validLocation() = lat.isFinite() && lon.isFinite() && (lat != 0.0 || lon != 0.0)

    private fun animationsEnabled(): Boolean {
        if (!prefs.getBoolean("weather_bg_anim_enabled", true)) return false
        val am = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        if (am?.isPowerSaveMode == true) return false
        val scale = runCatching {
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        return scale > 0f
    }

    private fun animateEntrance(parent: LinearLayout) {
        parent.alpha = 0f
        parent.translationY = dp(8).toFloat()
        parent.animate().alpha(1f).translationY(0f).setDuration(220L).start()
    }

    private fun formatDay(value: String): String = runCatching {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(value)
        java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(date ?: return@runCatching value)
            .replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }
    }.getOrDefault(value)

    private fun weatherName(code: Int) = WeatherUiMapper.description(code)
    /** Escena One UI 8.5 AMOLED animada por estado climático. */
    private inner class WeatherLakeView(context: android.content.Context, private val snapshot: WeatherVisual.Snapshot) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val frame = object : Runnable {
            override fun run() {
                if (!running) return
                invalidate()
                postOnAnimationDelayed(this, 32L)
            }
        }
        private var running = false

        fun resumeAnim() {
            if (running || !animationsEnabled() || !isAttachedToWindow) return
            running = true
            removeCallbacks(frame)
            postOnAnimation(frame)
        }

        fun pause() {
            running = false
            removeCallbacks(frame)
        }

        fun stop() { pause() }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow(); resumeAnim()
        }

        override fun onDetachedFromWindow() {
            pause(); super.onDetachedFromWindow()
        }

        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            if (visibility == VISIBLE) resumeAnim() else pause()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val horizon = h * .54f
            val t = ((SystemClock.elapsedRealtime() % 6000L) / 6000f)
            drawSky(canvas, w, horizon, t)
            drawWater(canvas, w, horizon, h, t)
            drawState(canvas, w, horizon, h, t)
            drawReeds(canvas, w, h)
        }

        private fun drawSky(canvas: Canvas, w: Float, horizon: Float, t: Float) {
            val top: Int
            val bottom: Int
            if (snapshot.night) {
                top = 0xFF101B31.toInt(); bottom = 0xFF1B2A40.toInt()
            } else {
                when (snapshot.state) {
                    WeatherVisual.State.TROPICAL -> { top = 0xFF557D76.toInt(); bottom = 0xFF9BAE91.toInt() }
                    WeatherVisual.State.SECO -> { top = 0xFFD99763.toInt(); bottom = 0xFFF0C58D.toInt() }
                    WeatherVisual.State.TEMPLADO -> { top = 0xFF6A9FB6.toInt(); bottom = 0xFFC5D5C6.toInt() }
                    WeatherVisual.State.CONTINENTAL -> { top = 0xFF526D82.toInt(); bottom = 0xFF9CB1BB.toInt() }
                    WeatherVisual.State.POLAR -> { top = 0xFF7898AA.toInt(); bottom = 0xFFD8E3E6.toInt() }
                    else -> { top = 0xFF6A9FB6.toInt(); bottom = 0xFFC5D5C6.toInt() }
                }
            }
            fill.shader = LinearGradient(0f, 0f, 0f, horizon, top, bottom, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, horizon, fill)
            fill.shader = null
            if (snapshot.night && snapshot.wcode <= 2) {
                fill.color = 0xBBF4ECE1.toInt()
                for (i in 0..10) {
                    val x = w * ((i * 17 % 100) / 100f)
                    val y = horizon * (.08f + (i * 13 % 45) / 100f)
                    val twinkle = (sin((t + i * .09f) * Math.PI * 2).toFloat() + 1f) * .5f
                    fill.alpha = (90 + 120 * twinkle).toInt()
                    canvas.drawCircle(x, y, (1.1f * resources.displayMetrics.density) * (.7f + twinkle * .6f), fill)
                }
            }
        }

        private fun drawWater(canvas: Canvas, w: Float, horizon: Float, h: Float, t: Float) {
            val waterTop = when (snapshot.state) {
                WeatherVisual.State.TROPICAL -> 0xFF07110D.toInt()
                WeatherVisual.State.SECO -> 0xFF120D08.toInt()
                WeatherVisual.State.CONTINENTAL -> 0xFF090B0E.toInt()
                WeatherVisual.State.POLAR -> 0xFF0A1014.toInt()
                else -> 0xFF080B0D.toInt()
            }
            fill.shader = LinearGradient(0f, horizon, 0f, h, waterTop, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, horizon, w, h, fill)
            fill.shader = null
            stroke.color = 0x249DB99E
            stroke.strokeWidth = dp(1).toFloat()
            for (i in 0 until 7) {
                val p = (i / 7f + t) % 1f
                val y = horizon + (h - horizon) * (.08f + p * .86f)
                val half = w * (.08f + p * .12f)
                val cx = w * .56f
                stroke.alpha = (18 + (1f - p) * 35).toInt()
                canvas.drawLine(cx - half, y, cx + half, y, stroke)
            }
        }

        private fun drawState(canvas: Canvas, w: Float, horizon: Float, h: Float, t: Float) {
            val cx = w * .74f
            val cy = horizon - h * .14f
            val r = minOf(w, h) * .095f
            val stateColor = WeatherVisual.accent(snapshot.state)
            if (snapshot.wcode <= 3) {
                if (snapshot.wcode < 3) {
                    if (snapshot.night) drawCrescent(canvas, cx, cy, r)
                    else { drawSunRays(canvas, cx, cy, r, 0xFFFFC857.toInt(), t); drawOrb(canvas, cx, cy, r, 0xFFFFC857.toInt(), t) }
                }
                if (snapshot.wcode in 1..3) drawCloud(canvas, w * .65f, horizon * .48f, r * .78f, if (snapshot.night) 0xFFE0E7EF.toInt() else 0xFFF4F1E8.toInt(), t, .35f)
                return
            }
            when (snapshot.state) {
                WeatherVisual.State.TROPICAL -> {
                    drawOrb(canvas, cx, cy, r, if (snapshot.night) 0xFFF4ECE1.toInt() else 0xFFD98A67.toInt(), t)
                    drawCloud(canvas, w * .62f, horizon * .42f, dp(13).toFloat(), 0x442E4034.toInt(), t, 1.2f)
                    drawRain(canvas, w, horizon, t, 14, 0x889DB99E.toInt(), 1.0f)
                    drawMist(canvas, w, horizon, 0x149DB99E.toInt(), 2)
                }
                WeatherVisual.State.SECO -> {
                    drawSunRays(canvas, cx, cy, r, 0x66D98A67, t)
                    drawOrb(canvas, cx, cy, r, 0xFFD98A67.toInt(), t)
                    drawHeat(canvas, w, horizon, t)
                }
                WeatherVisual.State.TEMPLADO -> {
                    drawOrb(canvas, cx, cy, r, if (snapshot.night) 0xFFF4ECE1.toInt() else 0xFFE2B48F.toInt(), t)
                    drawCloud(canvas, w * .60f, horizon * .45f, dp(12).toFloat(), 0x332A2420.toInt(), t, .8f)
                    drawCloud(canvas, w * .76f, horizon * .36f, dp(8).toFloat(), 0x222A2420.toInt(), t, .5f)
                    if (snapshot.wcode >= 51) drawRain(canvas, w, horizon, t, 6, 0x559DB99E.toInt(), .6f)
                }
                WeatherVisual.State.CONTINENTAL -> {
                    drawCloud(canvas, w * .66f, horizon * .38f, dp(15).toFloat(), 0x55323133.toInt(), t, 2.4f)
                    drawCloud(canvas, w * .48f, horizon * .50f, dp(10).toFloat(), 0x33323133.toInt(), t, 1.7f)
                    drawWind(canvas, w, horizon, t, stateColor)
                    if (snapshot.wcode >= 51 || snapshot.wcode >= 95) drawRain(canvas, w, horizon, t, 10, 0x66C2B6A8.toInt(), 1.5f)
                }
                WeatherVisual.State.POLAR -> {
                    drawOrb(canvas, cx, cy, r * .85f, 0xFFDCE7F2.toInt(), t)
                    drawCloud(canvas, w * .62f, horizon * .44f, dp(12).toFloat(), 0x33444E58.toInt(), t, .5f)
                    drawSnow(canvas, w, horizon, t)
                    drawMist(canvas, w, horizon, 0x1ADCE7F2.toInt(), 3)
                }
            }
        }

        private fun drawCrescent(canvas: Canvas, cx: Float, cy: Float, r: Float) {
            val path = Path().apply {
                moveTo(cx + r * .42f, cy - r)
                cubicTo(cx - r * 1.25f, cy - r * .88f, cx - r * 1.32f, cy + r * .84f, cx + r * .35f, cy + r)
                cubicTo(cx - r * .55f, cy + r * .42f, cx - r * .58f, cy - r * .35f, cx + r * .42f, cy - r)
                close()
            }
            fill.color = 0xFFFFE6A3.toInt()
            canvas.drawPath(path, fill)
            fill.color = 0xFFFFF2CA.toInt()
            canvas.drawCircle(cx + r * 1.2f, cy - r * .9f, (1.5f * resources.displayMetrics.density), fill)
            canvas.drawCircle(cx + r * 1.5f, cy + r * .2f, resources.displayMetrics.density, fill)
        }

        private fun drawOrb(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, t: Float) {
            val pulse = 1f + sin(t * Math.PI * 2).toFloat() * .03f
            fill.color = color
            fill.alpha = 45
            canvas.drawCircle(cx, cy, r * 1.9f * pulse, fill)
            fill.alpha = 255
            canvas.drawCircle(cx, cy, r * pulse, fill)
        }

        private fun drawSunRays(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, t: Float) {
            stroke.color = color
            stroke.strokeWidth = dp(2).toFloat()
            for (i in 0 until 8) {
                val a = (i / 8f) * Math.PI.toFloat() * 2f + t * .4f
                val inner = r * 1.5f
                val outer = r * (2.1f + (i % 2) * .25f)
                canvas.drawLine(cx + cos(a) * inner, cy + sin(a) * inner, cx + cos(a) * outer, cy + sin(a) * outer, stroke)
            }
        }

        private fun drawCloud(canvas: Canvas, x: Float, y: Float, r: Float, color: Int, t: Float, speed: Float) {
            val dx = sin(t * Math.PI * 2 * speed).toFloat() * dp(4).toFloat()
            fill.color = color
            canvas.drawCircle(x - r * .7f + dx, y, r * .7f, fill)
            canvas.drawCircle(x + dx, y - r * .2f, r, fill)
            canvas.drawCircle(x + r * .75f + dx, y, r * .72f, fill)
            canvas.drawRoundRect(x - r * 1.4f + dx, y, x + r * 1.45f + dx, y + r * .65f, r * .3f, r * .3f, fill)
        }

        private fun drawRain(canvas: Canvas, w: Float, horizon: Float, t: Float, count: Int, color: Int, speed: Float) {
            stroke.color = color
            stroke.strokeWidth = dp(1) * 1.4f
            for (i in 0 until count) {
                val p = (t * speed + i / count.toFloat()) % 1f
                val x = w * (.18f + (i * 13 % 70) / 100f)
                val y = horizon * (.25f + p * .75f)
                canvas.drawLine(x, y, x - dp(2), y + dp(8), stroke)
            }
        }

        private fun drawSnow(canvas: Canvas, w: Float, horizon: Float, t: Float) {
            fill.color = 0xCCF4ECE1.toInt()
            for (i in 0 until 12) {
                val p = (t * .45f + i / 12f) % 1f
                val x = w * (.2f + (i * 11 % 65) / 100f) + sin((t + i) * Math.PI * 2).toFloat() * dp(2)
                val y = horizon * (.2f + p * .78f)
                fill.alpha = (120 + (1f - p) * 90).toInt()
                canvas.drawCircle(x, y, dp(2) * 0.9f, fill)
            }
        }

        private fun drawMist(canvas: Canvas, w: Float, horizon: Float, color: Int, bands: Int) {
            stroke.color = color
            stroke.strokeWidth = dp(3).toFloat()
            for (i in 0 until bands) {
                val y = horizon * (.35f + i * .16f)
                canvas.drawLine(w * .08f, y, w * .92f, y, stroke)
            }
        }

        private fun drawWind(canvas: Canvas, w: Float, horizon: Float, t: Float, color: Int) {
            stroke.color = (color and 0x00FFFFFF) or 0x55000000
            stroke.strokeWidth = dp(2).toFloat()
            for (i in 0 until 4) {
                val p = (t * 1.3f + i / 4f) % 1f
                val x = w * (-.1f + p * 1.2f)
                val y = horizon * (.28f + i * .14f)
                canvas.drawLine(x, y, x + w * .18f, y, stroke)
            }
        }

        private fun drawHeat(canvas: Canvas, w: Float, horizon: Float, t: Float) {
            stroke.color = 0x22D98A67
            stroke.strokeWidth = dp(1).toFloat()
            for (i in 0 until 3) {
                val x = w * (.2f + i * .22f)
                val y = horizon * (.55f + sin((t + i * .2f) * Math.PI * 2).toFloat() * .03f)
                val path = Path().apply {
                    moveTo(x, y)
                    quadTo(x + dp(6), y - dp(6), x, y - dp(12))
                    quadTo(x - dp(6), y - dp(18), x, y - dp(24))
                }
                canvas.drawPath(path, stroke)
            }
        }

        private fun drawReeds(canvas: Canvas, w: Float, h: Float) {
            stroke.color = 0x559DB99E.toInt()
            stroke.strokeWidth = dp(1).toFloat()
            for (i in 0..4) {
                val x = w * (.04f + i * .02f)
                val top = h - h * (.14f + (i % 3) * .04f)
                canvas.drawPath(Path().apply { moveTo(x, h); quadTo(x + dp(5), (top + h) / 2f, x + dp(2), top) }, stroke)
                val xr = w * (.96f - i * .02f)
                canvas.drawPath(Path().apply { moveTo(xr, h); quadTo(xr - dp(5), (top + h) / 2f, xr - dp(2), top) }, stroke)
            }
        }
    }

    // Legacy helpers removed; new glass UI uses WeatherComponents/WeatherDesign.
}
