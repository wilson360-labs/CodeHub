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
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
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
    private val bg by lazy { if (dark) 0xFF17130F.toInt() else 0xFFF3EADD.toInt() }
    private val panel by lazy { if (dark) 0xFF201A14.toInt() else 0xFFFBF6ED.toInt() }
    private val elevated by lazy { if (dark) 0xFF2A221A.toInt() else 0xFFF6EEDF.toInt() }
    private val primary by lazy { if (dark) 0xFFF5EDE0.toInt() else 0xFF2E241C.toInt() }
    private val secondary by lazy { if (dark) 0xFFC2B09B.toInt() else 0xFF6E5D4B.toInt() }
    private val muted by lazy { if (dark) 0xFF94836F.toInt() else 0xFF9A8B78.toInt() }
    private val border by lazy { if (dark) 0x14F5EDE0.toInt() else 0x142E241C.toInt() }
    private val accent by lazy { if (dark) 0xFFD98A67.toInt() else 0xFFB85C3C.toInt() }
    private val sage by lazy { if (dark) 0xFF9DB99E.toInt() else 0xFF6F8F72.toInt() }
    private val accentSoft by lazy { if (dark) 0xFF3A2A20.toInt() else 0xFFEAD7C8.toInt() }
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
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
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
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_ai_back)
            imageTintList = android.content.res.ColorStateList.valueOf(primary)
            contentDescription = "Volver"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        header.addView(text("Clima", 19f, primary), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("⊞ Widget", 12f, primary).apply {
            gravity = Gravity.CENTER
            contentDescription = "Añadir widget de clima a la pantalla de inicio"
            setOnClickListener { requestWeatherWidgetPin() }
        }, LinearLayout.LayoutParams(dp(80), dp(40)).apply { marginEnd = dp(8) })
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_store_refresh)
            imageTintList = android.content.res.ColorStateList.valueOf(primary)
            contentDescription = "Actualizar clima"; setOnClickListener { loadWeather() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(56)))

        val scroll = ScrollView(this).apply {
            clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun renderNoLocation(message: String = "Elige una ciudad o comparte tu ubicación para ver el pronóstico.") {
        content.removeAllViews()
        content.addView(card {
            addView(text("Tu clima, en CodeHub", 21f, primary))
            addView(text(message, 13f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(button("Usar mi ubicación", accent) { requestLocation() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })
            addView(citySearch(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        })
        addBackgroundControls()
    }

    private fun renderLoading() {
        content.removeAllViews()
        content.addView(card {
            addView(text(city.ifBlank { "Consultando tu ubicación" }, 15f, primary))
            addView(text("Cargando el pronóstico…", 13f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        })
        content.addView(citySearch(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
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
        content.removeAllViews()
        val current = data.optJSONObject("current") ?: return
        val daily = data.optJSONObject("daily") ?: JSONObject()
        val hours = data.optJSONObject("hourly") ?: JSONObject()
        val forecastTimes = hours.optJSONArray("time")
        val currentHourIndex = forecastTimes?.let { arr -> (0 until arr.length()).firstOrNull { arr.optString(it) >= current.optString("time") } } ?: 0
        val code = current.optInt("weather_code")
        val minToday = daily.optJSONArray("temperature_2m_min")?.optDouble(0)?.toInt()
        val maxToday = daily.optJSONArray("temperature_2m_max")?.optDouble(0)?.toInt()
        val place = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8)); background = rounded(panel, dp(24))
        }
        place.addView(text("⌖", 19f, secondary), LinearLayout.LayoutParams(dp(28), -2))
        place.addView(text(city.ifBlank { "Mi ubicación" }, 14f, primary), LinearLayout.LayoutParams(0, -2, 1f))
        place.addView(text("Mi ubicación  ›", 11f, accent).apply { setOnClickListener { requestLocation() } })
        content.addView(place)
        content.addView(citySearch(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val conditionColor = if (code == 0 || code in 1..2) sage else secondary
        val hero = FrameLayout(this).apply {
            background = rounded(bg, dp(24))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(24).toFloat())
                }
            }
            clipToOutline = true
        }
        val lake = WeatherLakeView(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lake.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                dp(8).toFloat(), dp(8).toFloat(), Shader.TileMode.CLAMP))
        }
        hero.addView(lake, FrameLayout.LayoutParams(-1, -1))

        val heroContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GlassPanelDrawable()
        }
        heroContent.addView(text("${weatherName(code)}  ·  ${current.optString("time").substringAfter('T', "Ahora")}", 14f, conditionColor))
        heroContent.addView(NativeWeatherGlyph(this, code, 72, animated = true), LinearLayout.LayoutParams(dp(80), dp(80)).apply {
            topMargin = dp(8)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        heroContent.addView(text("${current.optInt("temperature_2m")}°", 56f, accent))
        val measure = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        measure.addView(View(this).apply { setBackgroundColor(accent) }, LinearLayout.LayoutParams(dp(40), dp(1)))
        measure.addView(View(this).apply {
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(2).toFloat() }
        }, LinearLayout.LayoutParams(dp(4), dp(4)).apply { marginStart = dp(4) })
        heroContent.addView(measure, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        heroContent.addView(text("Sensación de ${current.optInt("apparent_temperature")}°", 14f, secondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        heroContent.addView(text("Mín. ${minToday ?: "—"}°     Máx. ${maxToday ?: "—"}°", 14f, secondary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val sunrise = daily.optJSONArray("sunrise")?.optString(0)?.substringAfter('T', "")
        val sunset = daily.optJSONArray("sunset")?.optString(0)?.substringAfter('T', "")
        if (!sunrise.isNullOrBlank() || !sunset.isNullOrBlank()) {
            val dawn = sunrise.takeUnless { it.isNullOrBlank() } ?: "—"
            val dusk = sunset.takeUnless { it.isNullOrBlank() } ?: "—"
            heroContent.addView(text("Amanecer $dawn     Atardecer $dusk", 12f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        hero.addView(heroContent, FrameLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12); bottomMargin = dp(12); leftMargin = dp(12); rightMargin = dp(12)
        })
        content.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        content.addView(text("Ahora mismo", 17f, primary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(8) })
        val metricRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metricRow1.addView(metricCard("Humedad", "${current.optInt("relative_humidity_2m")} %", "Gotas", accent), LinearLayout.LayoutParams(0, dp(96), 1f).apply { marginEnd = dp(4) })
        val gusts = current.optInt("wind_gusts_10m", current.optInt("wind_speed_10m"))
        val windDirection = current.optInt("wind_direction_10m", -1).let { if (it < 0) "" else " · ${it}°" }
        metricRow1.addView(metricCard("Viento", "${current.optInt("wind_speed_10m")} km/h", "Ráfagas $gusts km/h$windDirection", accent), LinearLayout.LayoutParams(0, dp(96), 1f).apply { marginStart = dp(4) })
        content.addView(metricRow1)
        val metricRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rain = hours.optJSONArray("precipitation_probability")?.optInt(currentHourIndex) ?: 0
        val rainMm = hours.optJSONArray("precipitation")?.optDouble(currentHourIndex)?.let { String.format(java.util.Locale.getDefault(), "%.1f mm", it) } ?: "—"
        metricRow2.addView(metricCard("Lluvia", "$rain %", "$rainMm · próxima hora", accent), LinearLayout.LayoutParams(0, dp(96), 1f).apply { marginEnd = dp(4) })
        val uv = hours.optJSONArray("uv_index")?.optDouble(currentHourIndex)?.let { String.format(java.util.Locale.getDefault(), "%.1f", it) } ?: "—"
        metricRow2.addView(metricCard("Índice UV", uv, "Hoy", accent), LinearLayout.LayoutParams(0, dp(96), 1f).apply { marginStart = dp(4) })
        content.addView(metricRow2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val metricRow3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metricRow3.addView(metricCard("Nubosidad", "${current.optInt("cloud_cover")} %", "Cobertura del cielo", accent), LinearLayout.LayoutParams(0, dp(96), 1f).apply { marginEnd = dp(4) })
        val pressure = current.optDouble("pressure_msl", Double.NaN).takeIf { it.isFinite() }?.toInt()?.toString() ?: "—"
        metricRow3.addView(metricCard("Presión", "$pressure hPa", "A nivel del mar", accent), LinearLayout.LayoutParams(0, dp(96), 1f).apply { marginStart = dp(4) })
        content.addView(metricRow3, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val visibilityKm = current.optDouble("visibility", Double.NaN).takeIf { it.isFinite() }?.let { String.format(java.util.Locale.getDefault(), "%.1f km", it / 1000.0) } ?: "—"
        content.addView(metricCard("Visibilidad", visibilityKm, "Alcance estimado", accent), LinearLayout.LayoutParams(-1, dp(88)).apply { topMargin = dp(8) })

        content.addView(text("Pronóstico por hora", 17f, primary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(8) })
        val hourlyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val times = forecastTimes
        val temps = hours.optJSONArray("temperature_2m")
        val rains = hours.optJSONArray("precipitation_probability")
        val codes = hours.optJSONArray("weather_code")
        val uvValues = hours.optJSONArray("uv_index")
        val start = currentHourIndex
        repeat(10) { offset ->
            val index = start + offset
            if (times != null && temps != null && index < minOf(times.length(), temps.length())) {
                val time = times.optString(index).substringAfter('T', "--:--")
                val hourCard = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(dp(8), dp(8), dp(8), dp(8)); background = rounded(panel, dp(14))
                    addView(text(time, 11f, muted, true))
                    addView(NativeWeatherGlyph(this@NativeWeatherActivity, codes?.optInt(index) ?: 0, 28), LinearLayout.LayoutParams(-1, dp(32)).apply {
                        topMargin = dp(8)
                    })
                    addView(text("${temps.optDouble(index).toInt()}°", 15f, primary, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                    addView(text("${rains?.optInt(index) ?: 0}% lluvia", 9f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                    if (uvValues != null && index < uvValues.length()) addView(text("UV ${uvValues.optDouble(index).toInt()}", 9f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                }
                hourlyRow.addView(hourCard, LinearLayout.LayoutParams(dp(88), dp(128)).apply { marginEnd = dp(8) })
            }
        }
        val horizontal = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(hourlyRow) }
        content.addView(horizontal)
        content.addView(text("Próximos días", 17f, primary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(8) })
        val dates = daily.optJSONArray("time")
        val dayCodes = daily.optJSONArray("weather_code")
        val maxes = daily.optJSONArray("temperature_2m_max")
        val mins = daily.optJSONArray("temperature_2m_min")
        val rainChances = daily.optJSONArray("precipitation_probability_max")
        val rainTotals = daily.optJSONArray("precipitation_sum")
        repeat(dates?.length() ?: 0) { i ->
            content.addView(card {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(text(if (i == 0) "Hoy" else formatDay(dates?.optString(i).orEmpty()), 14f, primary), LinearLayout.LayoutParams(0, -2, 1f))
                val rainTotal = rainTotals?.optDouble(i, 0.0) ?: 0.0
                val rainSummary = "Lluvia ${rainChances?.optInt(i) ?: 0}% · ${String.format(java.util.Locale.getDefault(), "%.1f", rainTotal)} mm"
                val dayDescription = LinearLayout(this@NativeWeatherActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(weatherName(dayCodes?.optInt(i) ?: 0), 10f, muted, true), LinearLayout.LayoutParams(-1, -2))
                    addView(text(rainSummary, 9f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                }
                addView(dayDescription, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
                addView(NativeWeatherGlyph(this@NativeWeatherActivity, dayCodes?.optInt(i) ?: 0, 30), LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(text("${mins?.optDouble(i)?.toInt() ?: 0}°  ${maxes?.optDouble(i)?.toInt() ?: 0}°", 13f, primary, true))
            }, LinearLayout.LayoutParams(-1, dp(76)).apply { bottomMargin = dp(8) })
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
            setPadding(dp(12), 0, dp(8), 0); setBackgroundColor(Color.TRANSPARENT)
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
        content.addView(text("Servicios de fondo", 16f, primary), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24); bottomMargin = dp(8) })
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

    private fun metricCard(title: String, value: String, detail: String, accent: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = rounded(elevated, dp(14))
        addView(text(title, 11f, muted, true))
        addView(text(value, 19f, primary, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(text(detail, 10f, accent), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
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
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)); background = rounded(panel, dp(16)); block()
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun button(title: String, tint: Int, onClick: () -> Unit) = Button(this).apply {
        text = title; textSize = 12f; isAllCaps = false
        setTextColor(if (tint != accent) primary else if (dark) bg else 0xFFFFFFFF.toInt())
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (tint == accent) tint else panel)
        setOnClickListener { onClick() }
    }
    /** Lago al atardecer dibujado a mano: sin imagen, sin textura y sin movimiento. */
    private inner class WeatherLakeView(context: android.content.Context) : View(context) {
        private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val reflectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val reedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val horizon = h * 0.52f

            skyPaint.color = if (dark) 0xFF2A221A.toInt() else 0xFFE8C9A4.toInt()
            waterPaint.color = if (dark) 0xFF121A16.toInt() else 0xFF6E6A4E.toInt()
            canvas.drawRect(0f, 0f, w, horizon, skyPaint)
            canvas.drawRect(0f, horizon, w, h, waterPaint)

            waterPaint.color = if (dark) 0xFF0B100D.toInt() else 0xFF3E5140.toInt()
            for (i in 0 until 4) {
                val y = horizon + (h - horizon) * (0.18f + i * 0.19f)
                canvas.drawRect(0f, y, w, y + dp(2), waterPaint)
            }

            val sunX = w * 0.74f
            val sunY = horizon - h * 0.10f
            sunPaint.color = accent
            canvas.drawCircle(sunX, sunY, dp(10).toFloat(), sunPaint)

            ripplePaint.color = if (dark) 0x2A9DB99E.toInt() else 0x2A6F8F72.toInt()
            for (i in 0 until 6) {
                val y = horizon + (h - horizon) * (0.06f + i * 0.13f)
                val half = w * (0.06f + i * 0.015f)
                canvas.drawLine(sunX - half, y, sunX + half, y, ripplePaint)
            }
            reflectionPaint.color = if (dark) 0x4AD98A67.toInt() else 0x4AB85C3C.toInt()
            for (i in 0 until 9) {
                val y = horizon + (h - horizon) * (0.04f + i * 0.10f)
                val spread = dp(2 + (i % 3) * 3).toFloat()
                canvas.drawLine(sunX - spread, y, sunX + spread, y, reflectionPaint)
            }

            reedPaint.color = if (dark) 0x8C9DB99E.toInt() else 0x8C6F8F72.toInt()
            for (i in 0..5) {
                val x = w * (0.04f + i * 0.018f)
                val top = h - h * (0.16f + (i % 3) * 0.04f)
                canvas.drawPath(Path().apply {
                    moveTo(x, h)
                    quadTo(x + dp(6), (top + h) / 2f, x + dp(2), top)
                }, reedPaint)
            }
            for (i in 0..5) {
                val x = w * (0.96f - i * 0.018f)
                val top = h - h * (0.14f + (i % 3) * 0.05f)
                canvas.drawPath(Path().apply {
                    moveTo(x, h)
                    quadTo(x - dp(6), (top + h) / 2f, x - dp(2), top)
                }, reedPaint)
            }
        }
    }

    /** Velo translúcido con doble borde y brillo superior, igual que Inicio. */
    private inner class GlassPanelDrawable : GradientDrawable() {
        private val bronze = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x338C6A3F.toInt()
            strokeWidth = dp(1).toFloat()
        }
        private val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, dp(12).toFloat(),
                intArrayOf(
                    if (dark) 0x24F4ECE1.toInt() else 0x24FFFFFF.toInt(),
                    if (dark) 0x00F4ECE1.toInt() else 0x00FFFFFF.toInt(),
                ),
                null, Shader.TileMode.CLAMP,
            )
        }

        init {
            shape = RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(if (dark) 0xE0201A14.toInt() else 0xE6FBF6ED.toInt())
            setStroke(dp(1), border)
        }

        override fun draw(canvas: Canvas) {
            super.draw(canvas)
            val r = bounds
            val inset = dp(1).toFloat()
            canvas.drawRoundRect(
                r.left + inset, r.top + inset, r.right - inset, r.bottom - inset,
                (dp(12) - dp(1)).toFloat(), (dp(12) - dp(1)).toFloat(), bronze,
            )
            canvas.save()
            canvas.clipPath(Path().apply {
                addRoundRect(
                    r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(),
                    dp(12).toFloat(), dp(12).toFloat(), Path.Direction.CW,
                )
            })
            canvas.drawRect(
                r.left.toFloat(), r.top.toFloat(), r.right.toFloat(),
                r.top + dp(12).toFloat(), sheen,
            )
            canvas.restore()
        }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat(); setStroke(dp(1), border)
    }
}
