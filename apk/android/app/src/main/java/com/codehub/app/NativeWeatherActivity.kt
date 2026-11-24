package com.codehub.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import kotlin.math.cos
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
    private val bg by lazy { if (dark) 0xFF000000.toInt() else 0xFFF3EADD.toInt() }
    private val panel by lazy { if (dark) 0xFF0A0A0C.toInt() else 0xFFFBF6ED.toInt() }
    private val elevated by lazy { if (dark) 0xFF101013.toInt() else 0xFFF6EEDF.toInt() }
    private val primary by lazy { if (dark) 0xFFF4ECE1.toInt() else 0xFF2E241C.toInt() }
    private val secondary by lazy { if (dark) 0xFFC2B6A8.toInt() else 0xFF6E5D4B.toInt() }
    private val muted by lazy { if (dark) 0xFF8E8377.toInt() else 0xFF9A8B78.toInt() }
    private val border by lazy { if (dark) 0x14F4ECE1.toInt() else 0x142E241C.toInt() }
    private val accent by lazy { if (dark) 0xFFD98A67.toInt() else 0xFFB85C3C.toInt() }
    private val sage by lazy { if (dark) 0xFF9DB99E.toInt() else 0xFF6F8F72.toInt() }
    private val accentSoft by lazy { if (dark) 0xFF1A1210.toInt() else 0xFFEAD7C8.toInt() }
    private lateinit var content: LinearLayout
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemBars.fit(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.setBackgroundColor(bg)
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
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        heroScene?.resumeAnim()
        if (returningFromLocationSettings) {
            returningFromLocationSettings = false
            if (locationServicesEnabled()) requestLocation() else renderNoLocation("La ubicación sigue desactivada. Actívala para detectar tu zona, o busca una ciudad manualmente.")
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun buildScreen() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_ai_back)
            imageTintList = android.content.res.ColorStateList.valueOf(primary)
            contentDescription = "Volver"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Clima", 20f, primary, true))
            addView(text("Pronóstico de CodeHub", 11f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(4) })
        header.addView(text("Widget", 11f, accent, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(accentSoft, dp(12))
            contentDescription = "Añadir widget de clima a la pantalla de inicio"
            setOnClickListener { requestWeatherWidgetPin() }
        }, LinearLayout.LayoutParams(-2, dp(34)).apply { marginEnd = dp(8) })
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_store_refresh)
            imageTintList = android.content.res.ColorStateList.valueOf(primary)
            background = rounded(panel, dp(12))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Actualizar clima"; setOnClickListener { loadWeather() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(64)))

        val scroll = ScrollView(this).apply {
            clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(28)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun renderNoLocation(message: String = "Elige una ciudad o comparte tu ubicación para ver el pronóstico.") {
        heroScene?.stop(); heroScene = null
        content.removeAllViews()
        content.addView(card {
            addView(text("Tu clima", 24f, primary, true))
            addView(text(message, 13f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(button("Usar mi ubicación", accent) { requestLocation() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(18) })
            addView(citySearch(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        })
        addBackgroundControls()
    }

    private fun renderLoading() {
        heroScene?.stop(); heroScene = null
        content.removeAllViews()
        content.addView(card {
            addView(text(city.ifBlank { "Consultando tu ubicación" }, 16f, primary, true))
            addView(text("Cargando el pronóstico…", 13f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        })
        content.addView(citySearch(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        addBackgroundControls()
    }

    private fun loadWeather() {
        if (!validLocation()) { renderNoLocation(); return }
        val attempt = ++weatherRequestId
        request?.cancel()
        renderLoading()
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,precipitation,pressure_msl,cloud_cover,visibility,is_day" +
            "&hourly=temperature_2m,precipitation,precipitation_probability,weather_code,uv_index,visibility" +
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
        val stateAccent = WeatherVisual.accent(visualState)

        val place = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10)); background = rounded(panel, dp(20))
        }
        place.addView(text("⌖", 18f, accent, true), LinearLayout.LayoutParams(dp(26), -2))
        place.addView(text(city.ifBlank { "Mi ubicación" }, 14f, primary, true), LinearLayout.LayoutParams(0, -2, 1f))
        place.addView(text("Cambiar", 11f, accent, true).apply {
            setPadding(dp(8), dp(4), dp(2), dp(4)); setOnClickListener { requestLocation() }
        })
        content.addView(place)
        content.addView(citySearch(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // Hero One UI 8.5 AMOLED: escena animada por estado climático.
        val hero = FrameLayout(this).apply {
            background = rounded(bg, dp(28))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(28).toFloat())
                }
            }
            clipToOutline = true
        }
        heroScene?.stop()
        heroScene = WeatherLakeView(this, WeatherVisual.Snapshot(visualState, code, isDay, tempC, humidity, windKmh, visibilityM))
        hero.addView(heroScene, FrameLayout.LayoutParams(-1, -1))
        val heroContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(20), dp(14), dp(20)); background = GlassPanelDrawable()
        }
        val heroCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroCopy.addView(text("AHORA · ${current.optString("time").substringAfter('T', "--:--")}", 10f, accent, true).apply {
            setPadding(dp(10), dp(5), dp(10), dp(5)); background = rounded(accentSoft, dp(10))
        })
        heroCopy.addView(text("${current.optInt("temperature_2m")}°", 60f, primary, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        heroCopy.addView(text(weatherName(code), 14f, secondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
        heroCopy.addView(text("Sensación de ${current.optInt("apparent_temperature")}°", 11f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        heroCopy.addView(text(WeatherVisual.label(visualState), 10f, stateAccent, true).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(0x22111111.toInt(), dp(10))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        val extremes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        extremes.addView(text("↓ ${minToday ?: "—"}°", 12f, sage, true))
        extremes.addView(text("↑ ${maxToday ?: "—"}°", 12f, accent, true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        heroCopy.addView(extremes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        heroContent.addView(heroCopy, LinearLayout.LayoutParams(0, -2, 1f))
        heroContent.addView(NativeWeatherGlyph(this, code, 84, animated = animationsEnabled()), LinearLayout.LayoutParams(dp(88), dp(88)).apply { marginStart = dp(8) })
        hero.addView(heroContent, FrameLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(10); bottomMargin = dp(10); leftMargin = dp(10); rightMargin = dp(10)
        })
        content.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val sunrise = daily.optJSONArray("sunrise")?.optString(0)?.substringAfter('T', "").orEmpty()
        val sunset = daily.optJSONArray("sunset")?.optString(0)?.substringAfter('T', "").orEmpty()
        if (sunrise.isNotBlank() || sunset.isNotBlank()) {
            content.addView(card {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(detailCell("Amanecer", sunrise.ifBlank { "—" }), LinearLayout.LayoutParams(0, -2, 1f))
                addView(detailCell("Atardecer", sunset.ifBlank { "—" }), LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        content.addView(sectionHeading("Condiciones actuales"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(10) })
        val metricRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metricRow1.addView(metricCard("Humedad", "${current.optInt("relative_humidity_2m")} %", "Humedad relativa", accent), LinearLayout.LayoutParams(0, dp(92), 1f).apply { marginEnd = dp(6) })
        val gusts = current.optInt("wind_gusts_10m", current.optInt("wind_speed_10m"))
        val windDirection = current.optInt("wind_direction_10m", -1).let { if (it < 0) "" else " · ${it}°" }
        metricRow1.addView(metricCard("Viento", "${current.optInt("wind_speed_10m")} km/h", "Ráfagas $gusts km/h$windDirection", sage), LinearLayout.LayoutParams(0, dp(92), 1f).apply { marginStart = dp(6) })
        content.addView(metricRow1)
        val metricRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rain = hours.optJSONArray("precipitation_probability")?.optInt(currentHourIndex) ?: 0
        val rainMm = hours.optJSONArray("precipitation")?.optDouble(currentHourIndex)?.let { String.format(java.util.Locale.getDefault(), "%.1f mm", it) } ?: "—"
        metricRow2.addView(metricCard("Lluvia", "$rain %", "$rainMm · próxima hora", sage), LinearLayout.LayoutParams(0, dp(92), 1f).apply { marginEnd = dp(6) })
        val uv = hours.optJSONArray("uv_index")?.optDouble(currentHourIndex)?.let { String.format(java.util.Locale.getDefault(), "%.1f", it) } ?: "—"
        metricRow2.addView(metricCard("Índice UV", uv, "Ahora", accent), LinearLayout.LayoutParams(0, dp(92), 1f).apply { marginStart = dp(6) })
        content.addView(metricRow2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val pressure = current.optDouble("pressure_msl", Double.NaN).takeIf { it.isFinite() }?.toInt()?.toString() ?: "—"
        val visibilityKm = current.optDouble("visibility", Double.NaN).takeIf { it.isFinite() }?.let { String.format(java.util.Locale.getDefault(), "%.1f km", it / 1000.0) } ?: "—"
        content.addView(card {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(detailCell("Nubosidad", "${current.optInt("cloud_cover")} %"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(detailCell("Presión", "$pressure hPa"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(detailCell("Visibilidad", visibilityKm), LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        content.addView(sectionHeading("Próximas horas"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(10) })
        val hourlyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val times = forecastTimes
        val temps = hours.optJSONArray("temperature_2m")
        val rains = hours.optJSONArray("precipitation_probability")
        val codes = hours.optJSONArray("weather_code")
        val start = currentHourIndex
        repeat(4) { offset ->
            val index = start + offset
            if (times != null && temps != null && index < minOf(times.length(), temps.length())) {
                val time = if (offset == 0) "Ahora" else times.optString(index).substringAfter('T', "--:--")
                val hourCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(dp(6), dp(10), dp(6), dp(10)); background = rounded(elevated, dp(18))
                    addView(text(time, 10f, muted, true))
                    addView(NativeWeatherGlyph(this@NativeWeatherActivity, codes?.optInt(index) ?: 0, 26), LinearLayout.LayoutParams(-1, dp(30)).apply { topMargin = dp(4) })
                    addView(text("${temps.optDouble(index).toInt()}°", 15f, primary, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                    addView(text("${rains?.optInt(index) ?: 0}%", 9f, sage), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
                }
                hourlyRow.addView(hourCard, LinearLayout.LayoutParams(0, dp(104), 1f).apply { if (offset < 3) marginEnd = dp(6) })
            }
        }
        content.addView(hourlyRow)

        content.addView(sectionHeading("Próximos días"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(10) })
        val dates = daily.optJSONArray("time")
        val dayCodes = daily.optJSONArray("weather_code")
        val maxes = daily.optJSONArray("temperature_2m_max")
        val mins = daily.optJSONArray("temperature_2m_min")
        val rainChances = daily.optJSONArray("precipitation_probability_max")
        val rainTotals = daily.optJSONArray("precipitation_sum")
        val allMins = (0 until (mins?.length() ?: 0)).mapNotNull { mins?.optDouble(it)?.toInt() }
        val allMaxes = (0 until (maxes?.length() ?: 0)).mapNotNull { maxes?.optDouble(it)?.toInt() }
        val overallMin = allMins.minOrNull() ?: 0
        val overallMax = maxOf(allMaxes.maxOrNull() ?: overallMin + 1, overallMin + 1)
        repeat(dates?.length() ?: 0) { i ->
            content.addView(card {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(text(if (i == 0) "Hoy" else formatDay(dates?.optString(i).orEmpty()), 13f, primary, true), LinearLayout.LayoutParams(dp(46), -2))
                addView(NativeWeatherGlyph(this@NativeWeatherActivity, dayCodes?.optInt(i) ?: 0, 24), LinearLayout.LayoutParams(dp(28), dp(28)))
                val rainTotal = rainTotals?.optDouble(i, 0.0) ?: 0.0
                val rainSummary = "Lluvia ${rainChances?.optInt(i) ?: 0}% · ${String.format(java.util.Locale.getDefault(), "%.1f", rainTotal)} mm"
                val dayDescription = LinearLayout(this@NativeWeatherActivity).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                    addView(text(weatherName(dayCodes?.optInt(i) ?: 0), 11f, secondary, true), LinearLayout.LayoutParams(-1, -2))
                    addView(text(rainSummary, 9f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
                    addView(rangeBar(mins?.optDouble(i)?.toInt() ?: overallMin, maxes?.optDouble(i)?.toInt() ?: overallMax, overallMin, overallMax),
                        LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(6) })
                }
                addView(dayDescription, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
                addView(text("${mins?.optDouble(i)?.toInt() ?: 0}° / ${maxes?.optDouble(i)?.toInt() ?: 0}°", 12f, primary, true),
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            }, LinearLayout.LayoutParams(-1, dp(72)).apply { bottomMargin = dp(6) })
        }
        addBackgroundControls()
        animateEntrance(content)
    }

    private fun citySearch(): View {
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = rounded(panel, dp(16))
        }
        val input = EditText(this).apply {
            hint = "Ciudad, municipio o lugar"; setSingleLine(true); textSize = 14f; setTextColor(primary); setHintTextColor(muted)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(12), 0, dp(8), 0); setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    searchCity(text.toString().trim())
                    true
                } else false
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        val searchButton = button("Buscar", accent) { searchCity(input.text.toString().trim()) }
        row.addView(searchButton, LinearLayout.LayoutParams(dp(88), dp(44)))
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        section.addView(row)
        section.addView(results, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        locationSearchInput = input
        locationSearchResults = results
        locationSearchButton = searchButton
        return section
    }

    private fun searchCity(query: String) {
        if (query.length < 2) { Toast.makeText(this, "Escribe al menos dos letras.", Toast.LENGTH_SHORT).show(); return }
        val resultsView = locationSearchResults
        resultsView?.removeAllViews()
        resultsView?.visibility = View.VISIBLE
        resultsView?.addView(text("Buscando lugares…", 12f, muted).apply { setPadding(dp(8), dp(8), dp(8), dp(8)) })
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
                            background = rounded(accentSoft, dp(14))
                            isClickable = true
                            isFocusable = true
                            contentDescription = "Usar ubicación $name${if (detail.isBlank()) "" else ", $detail"}"
                            addView(text(name, 14f, primary))
                            if (detail.isNotBlank()) addView(text(detail, 11f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                            setOnClickListener {
                                list.visibility = View.GONE
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
            addView(text(message, 12f, muted).apply { setPadding(dp(8), dp(8), dp(8), dp(8)) })
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
        content.addView(text("Servicios de fondo", 16f, primary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(8) })
        val widget = Switch(this).apply {
            text = "Alertas de clima y widget"; textSize = 13f; setTextColor(primary)
            isChecked = prefs.getBoolean("weather_notification_enabled", true)
            setOnCheckedChangeListener { _, checked ->
                if (checked && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    pendingNotificationSwitch = this
                    isChecked = false
                    ActivityCompat.requestPermissions(this@NativeWeatherActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
                    return@setOnCheckedChangeListener
                }
                prefs.edit().putBoolean("weather_notification_enabled", checked).apply()
                WeatherNotificationProvider.refresh(this@NativeWeatherActivity)
            }
        }
        val quakes = Switch(this).apply {
            text = "Recibir alertas sísmicas"; textSize = 13f; setTextColor(primary)
            isChecked = prefs.getBoolean("quake_alert_enabled", true)
            setOnCheckedChangeListener { _, checked ->
                if (checked && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    pendingNotificationSwitch = this
                    isChecked = false
                    ActivityCompat.requestPermissions(this@NativeWeatherActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
                    return@setOnCheckedChangeListener
                }
                WeatherNotificationProvider.setQuakeAlertsEnabled(this@NativeWeatherActivity, checked)
            }
        }
        val detector = Switch(this).apply {
            text = "Detector local por sensor"; textSize = 13f; setTextColor(primary)
            isChecked = prefs.getBoolean("quake_detector_enabled", true)
            setOnCheckedChangeListener { control, checked ->
                if (!setLocalQuakeDetector(checked)) control.isChecked = false
            }
        }
        content.addView(card {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                addView(button("Permitir notificaciones", accent) {
                    pendingNotificationSwitch = widget
                    ActivityCompat.requestPermissions(this@NativeWeatherActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
                }, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(8) })
            }
            addView(widget)
            addView(quakes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(detector, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(text("El detector usa el acelerómetro local y requiere su notificación persistente. Las alertas cercanas se reciben por el servicio nativo de CodeHub; Android puede limitar el trabajo en segundo plano según batería y permisos.", 11f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
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
        val am = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        if (am?.isPowerSaveMode == true) return false
        val scale = runCatching {
            Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
        return scale > 0f
    }

    private fun metricCard(title: String, value: String, detail: String, accent: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(elevated, dp(14))
        addView(text(title, 10f, muted, true))
        addView(text(value, 18f, primary, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(text(detail, 9f, accent), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    private fun animateEntrance(parent: LinearLayout) {
        parent.alpha = 0f
        parent.translationY = dp(8).toFloat()
        parent.animate().alpha(1f).translationY(0f).setDuration(180L).start()
    }

    private fun formatDay(value: String): String = runCatching {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(value)
        java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(date ?: return@runCatching value)
            .replaceFirstChar { it.titlecase(java.util.Locale.getDefault()) }
    }.getOrDefault(value)

    private fun weatherName(code: Int) = when (code) {
        0 -> "Despejado"; 1 -> "Mayormente despejado"; 2 -> "Parcialmente nublado"; 3 -> "Nublado"
        45, 48 -> "Niebla"; in 51..57 -> "Llovizna"; in 61..67 -> "Lluvia"; in 71..77 -> "Nieve"
        in 80..82 -> "Chubascos"; in 85..86 -> "Nevadas"; in 95..99 -> "Tormenta eléctrica"; else -> "Condiciones variables"
    }
    private fun card(block: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); background = rounded(panel, dp(16)); block()
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun sectionHeading(title: String) = text(title, 16f, primary, true).apply {
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }
    private fun detailCell(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(label, 10f, muted, true))
        addView(text(value, 12f, primary, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
    }
    private fun button(title: String, tint: Int, onClick: () -> Unit) = Button(this).apply {
        text = title; textSize = 12f; isAllCaps = false
        setTextColor(if (tint != accent) primary else if (dark) bg else 0xFFFFFFFF.toInt())
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (tint == accent) tint else panel)
        setOnClickListener { onClick() }
    }
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
            when (snapshot.state) {
                WeatherVisual.State.TROPICAL -> { top = 0xFF040806.toInt(); bottom = 0xFF0A1410.toInt() }
                WeatherVisual.State.SECO -> { top = 0xFF080502.toInt(); bottom = 0xFF15100B.toInt() }
                WeatherVisual.State.TEMPLADO -> { top = 0xFF050608.toInt(); bottom = 0xFF0D1114.toInt() }
                WeatherVisual.State.CONTINENTAL -> { top = 0xFF040506.toInt(); bottom = 0xFF0D1012.toInt() }
                WeatherVisual.State.POLAR -> { top = 0xFF050708.toInt(); bottom = 0xFF101418.toInt() }
                else -> { top = 0xFF050608.toInt(); bottom = 0xFF0D1114.toInt() }
            }
            fill.shader = LinearGradient(0f, 0f, 0f, horizon, top, bottom, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, horizon, fill)
            fill.shader = null
            // subtle stars at night
            if (snapshot.night && snapshot.wcode <= 2) {
                fill.color = 0x55F4ECE1.toInt()
                for (i in 0..10) {
                    val x = w * ((i * 17 % 100) / 100f)
                    val y = horizon * (.08f + (i * 13 % 45) / 100f)
                    val a = ((sin((t + i * .09f) * Math.PI * 2).toFloat() + 1f) * .5f)
                    fill.alpha = (40 + 90 * a).toInt()
                    canvas.drawCircle(x, y, dp(1).toFloat() * (.7f + a * .6f), fill)
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
            val r = dp(11).toFloat()
            val stateColor = WeatherVisual.accent(snapshot.state)
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

    /** Velo AMOLED: negro translúcido con borde terracota tenue, sin brillo. */
    private inner class GlassPanelDrawable : GradientDrawable() {
        private val bronze = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x2BD98A67.toInt()
            strokeWidth = dp(1).toFloat()
        }

        init {
            shape = RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(if (dark) 0xE6000000.toInt() else 0xE6FBF6ED.toInt())
            setStroke(dp(1), border)
        }

        override fun draw(canvas: Canvas) {
            super.draw(canvas)
            val r = bounds
            val inset = dp(1).toFloat()
            canvas.drawRoundRect(
                r.left + inset, r.top + inset, r.right - inset, r.bottom - inset,
                dp(15).toFloat(), dp(15).toFloat(), bronze,
            )
        }
    }

    /** Barra de rango térmico: resalta el tramo del día dentro del mínimo y máximo globales. */
    private fun rangeBar(min: Int, max: Int, overallMin: Int, overallMax: Int) =
        TemperatureRangeView(this, min, max, overallMin, overallMax)

    private inner class TemperatureRangeView(
        context: android.content.Context,
        private val low: Int, private val high: Int,
        private val overallLow: Int, private val overallHigh: Int
    ) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(4).toFloat()
            strokeCap = Paint.Cap.ROUND; color = elevated
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(4).toFloat()
            strokeCap = Paint.Cap.ROUND; color = accent
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val pad = dp(2).toFloat()
            val y = height / 2f
            val widthPx = (width - pad * 2).coerceAtLeast(dp(4).toFloat())
            canvas.drawLine(pad, y, pad + widthPx, y, trackPaint)
            val span = (overallHigh - overallLow).coerceAtLeast(1)
            val start = pad + widthPx * ((low - overallLow).toFloat() / span).coerceIn(0f, 1f)
            val end = pad + widthPx * ((high - overallLow).toFloat() / span).coerceIn(0f, 1f)
            canvas.drawLine(start, y, maxOf(end, start + dp(4)), y, fillPaint)
        }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat(); setStroke(dp(1), border)
    }
}
