package com.codehub.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
    private val bg = 0xFF090D16.toInt()
    private val panel = 0xFF121A2A.toInt()
    private val muted = 0xFFAAB5C9.toInt()
    private val cyan = 0xFF67E8F9.toInt()
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
    private var hostStarted = false
    private var hostResumed = false
    private var radarPlayer: NativeWeatherRadarView? = null

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
        disposeRadarPlayer()
        request?.cancel()
        locationAttemptId++
        locationToken?.cancel()
        locationToken = null
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        hostStarted = true
        radarPlayer?.onHostStart()
    }

    override fun onResume() {
        super.onResume()
        hostResumed = true
        radarPlayer?.onHostResume()
        if (returningFromLocationSettings) {
            returningFromLocationSettings = false
            if (locationServicesEnabled()) requestLocation() else renderNoLocation("La ubicación sigue desactivada. Actívala para detectar tu zona, o busca una ciudad manualmente.")
        }
    }

    override fun onPause() {
        hostResumed = false
        radarPlayer?.onHostPause()
        super.onPause()
    }

    override fun onStop() {
        hostStarted = false
        radarPlayer?.onHostStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        radarPlayer?.onHostLowMemory()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun buildScreen() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(18), dp(6))
        }
        header.addView(text("‹", 30f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; contentDescription = "Volver"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(46), dp(50)))
        header.addView(text("Clima", 19f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(text("⟳", 26f, cyan, true).apply {
            gravity = Gravity.CENTER; contentDescription = "Actualizar clima"; setOnClickListener { loadWeather() }
        }, LinearLayout.LayoutParams(dp(44), dp(48)))
        root.addView(header, LinearLayout.LayoutParams(-1, dp(58)))

        val scroll = ScrollView(this).apply { clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(28)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun renderNoLocation(message: String = "Elige una ciudad o comparte tu ubicación para ver el pronóstico.") {
        disposeRadarPlayer()
        content.removeAllViews()
        content.addView(card {
            addView(text("Tu clima, en CodeHub", 21f, Color.WHITE, true))
            addView(text(message, 13f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(button("Usar mi ubicación", cyan) { requestLocation() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })
            addView(button("Buscar ciudad", 0xFF293754.toInt()) { locationPicker() }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(8) })
            addView(citySearch(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        })
        addBackgroundControls()
    }

    private fun renderLoading() {
        disposeRadarPlayer()
        content.removeAllViews()
        content.addView(card {
            addView(text(city.ifBlank { "Consultando tu ubicación" }, 15f, Color.WHITE, true))
            addView(text("Cargando el pronóstico…", 13f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        })
        content.addView(button("Cambiar ubicación", cyan) { locationPicker() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        addBackgroundControls()
    }

    private fun loadWeather() {
        if (!validLocation()) { renderNoLocation(); return }
        renderLoading()
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day" +
            "&hourly=temperature_2m,precipitation_probability,weather_code,uv_index" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset&forecast_days=3&timezone=auto"
        request = http.newCall(Request.Builder().url(url).header("User-Agent", "CodeHub-Android").build())
        request?.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: java.io.IOException) = runOnUiThread {
                if (!isFinishing && !isDestroyed) renderNoLocation("No se pudo conectar para actualizar el pronóstico. Comprueba internet e inténtalo de nuevo.")
            }
            override fun onResponse(call: Call, response: okhttp3.Response) {
                val parsed = response.use { if (it.isSuccessful) runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull() else null }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (parsed == null || parsed.optJSONObject("current") == null) renderNoLocation("El servicio de clima no devolvió datos. Inténtalo de nuevo más tarde.")
                    else renderForecast(parsed)
                }
            }
        })
    }

    private fun renderForecast(data: JSONObject) {
        disposeRadarPlayer()
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
            setPadding(dp(15), dp(10), dp(12), dp(10)); background = rounded(panel, dp(24))
        }
        place.addView(text("⌖", 19f, cyan, true), LinearLayout.LayoutParams(dp(28), -2))
        place.addView(text(city.ifBlank { "Mi ubicación" }, 14f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        place.addView(text("Cambiar  ›", 11f, cyan, true).apply { setOnClickListener { locationPicker() } })
        content.addView(place)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = weatherHeroBackground(code, current.optInt("is_day", 1) == 1)
        }
        hero.addView(text("${weatherName(code)}  ·  ${current.optString("time").substringAfter('T', "Ahora")}", 14f, Color.WHITE, true))
        hero.addView(text(weatherEmoji(code), 60f, Color.WHITE), LinearLayout.LayoutParams(-1, dp(78)).apply { topMargin = dp(10) })
        hero.addView(text("${current.optInt("temperature_2m")}°", 76f, Color.WHITE, true))
        hero.addView(text("Sensación de ${current.optInt("apparent_temperature")}°", 14f, 0xFFE7F1FF.toInt()), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        hero.addView(text("Mín. ${minToday ?: "—"}°     Máx. ${maxToday ?: "—"}°", 14f, Color.WHITE, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        val sunrise = daily.optJSONArray("sunrise")?.optString(0)?.substringAfter('T', "")
        val sunset = daily.optJSONArray("sunset")?.optString(0)?.substringAfter('T', "")
        if (!sunrise.isNullOrBlank() || !sunset.isNullOrBlank()) {
            hero.addView(text("☀  ${sunrise.orEmpty()}     ◒  ${sunset.orEmpty()}", 12f, 0xFFE7F1FF.toInt()), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(13) })
        }
        content.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        radarPlayer = NativeWeatherRadarView(this, lat, lon)
        content.addView(radarPlayer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        if (hostStarted) radarPlayer?.onHostStart()
        if (hostResumed) radarPlayer?.onHostResume()

        content.addView(text("Ahora mismo", 17f, Color.WHITE, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22); bottomMargin = dp(9) })
        val metricRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metricRow1.addView(metricCard("Humedad", "${current.optInt("relative_humidity_2m")} %", "Gotas", cyan), LinearLayout.LayoutParams(0, dp(94), 1f).apply { marginEnd = dp(5) })
        metricRow1.addView(metricCard("Viento", "${current.optInt("wind_speed_10m")} km/h", "Ráfagas", 0xFFB9A5FF.toInt()), LinearLayout.LayoutParams(0, dp(94), 1f).apply { marginStart = dp(5) })
        content.addView(metricRow1)
        val metricRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val rain = hours.optJSONArray("precipitation_probability")?.optInt(currentHourIndex) ?: 0
        metricRow2.addView(metricCard("Lluvia", "$rain %", "Próxima hora", 0xFF88B9FF.toInt()), LinearLayout.LayoutParams(0, dp(94), 1f).apply { marginEnd = dp(5) })
        val uv = hours.optJSONArray("uv_index")?.optDouble(currentHourIndex)?.let { String.format(java.util.Locale.getDefault(), "%.1f", it) } ?: "—"
        metricRow2.addView(metricCard("Índice UV", uv, "Hoy", 0xFFFFCF70.toInt()), LinearLayout.LayoutParams(0, dp(94), 1f).apply { marginStart = dp(5) })
        content.addView(metricRow2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        content.addView(text("Pronóstico por hora", 17f, Color.WHITE, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22); bottomMargin = dp(9) })
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
                    setPadding(dp(8), dp(10), dp(8), dp(10)); background = rounded(panel, dp(20))
                    addView(text(time, 11f, muted, true))
                    addView(text(weatherEmoji(codes?.optInt(index) ?: 0), 22f, Color.WHITE), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
                    addView(text("${temps.optDouble(index).toInt()}°", 15f, Color.WHITE, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                    addView(text("${rains?.optInt(index) ?: 0}% lluvia", 9f, 0xFF9CD9FF.toInt()), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                    if (uvValues != null && index < uvValues.length()) addView(text("UV ${uvValues.optDouble(index).toInt()}", 9f, 0xFFFFCF70.toInt()), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                }
                hourlyRow.addView(hourCard, LinearLayout.LayoutParams(dp(90), dp(128)).apply { marginEnd = dp(8) })
            }
        }
        val horizontal = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(hourlyRow) }
        content.addView(horizontal)
        content.addView(text("Próximos días", 17f, Color.WHITE, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22); bottomMargin = dp(9) })
        val dates = daily.optJSONArray("time")
        val dayCodes = daily.optJSONArray("weather_code")
        val maxes = daily.optJSONArray("temperature_2m_max")
        val mins = daily.optJSONArray("temperature_2m_min")
        repeat(dates?.length() ?: 0) { i ->
            content.addView(card {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(text(if (i == 0) "Hoy" else formatDay(dates?.optString(i).orEmpty()), 14f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(weatherName(dayCodes?.optInt(i) ?: 0), 11f, muted), LinearLayout.LayoutParams(dp(110), -2))
                addView(text(weatherEmoji(dayCodes?.optInt(i) ?: 0), 22f, Color.WHITE), LinearLayout.LayoutParams(dp(42), -2))
                addView(text("${mins?.optDouble(i)?.toInt() ?: 0}°  ${maxes?.optDouble(i)?.toInt() ?: 0}°", 13f, Color.WHITE, true))
            }, LinearLayout.LayoutParams(-1, dp(66)).apply { bottomMargin = dp(8) })
        }
        addBackgroundControls()
        animateEntrance(content)
    }

    private fun citySearch(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply {
            hint = "Buscar ciudad"; setSingleLine(true); textSize = 14f; setTextColor(Color.WHITE); setHintTextColor(muted)
            setPadding(dp(12), 0, dp(8), 0); background = rounded(panel, dp(14))
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(7) })
        row.addView(button("Buscar", cyan) { searchCity(input.text.toString().trim()) }, LinearLayout.LayoutParams(dp(94), dp(48)))
        return row
    }

    private fun locationPicker() {
        val input = EditText(this).apply { hint = "Ciudad o municipio"; setSingleLine(true); setPadding(dp(14), 0, dp(14), 0) }
        AlertDialog.Builder(this).setTitle("Cambiar ubicación")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Buscar", null)
            .create().also { dialog ->
                dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { searchCity(input.text.toString().trim(), dialog) } }
                dialog.show()
            }
    }

    private fun searchCity(query: String, dialog: AlertDialog? = null) {
        if (query.length < 2) { Toast.makeText(this, "Escribe al menos dos letras.", Toast.LENGTH_SHORT).show(); return }
        val encoded = URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder().url("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=5&language=es&format=json").build()
        http.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: java.io.IOException) = runOnUiThread { Toast.makeText(this@NativeWeatherActivity, "No se pudo buscar la ciudad.", Toast.LENGTH_SHORT).show() }
            override fun onResponse(call: Call, response: okhttp3.Response) {
                val results = response.use { res -> if (res.isSuccessful) runCatching { JSONObject(res.body?.string().orEmpty()).optJSONArray("results") }.getOrNull() else null }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (results == null || results.length() == 0) { Toast.makeText(this@NativeWeatherActivity, "No encontramos esa ubicación.", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                    val names = (0 until results.length()).map { i ->
                        val item = results.optJSONObject(i) ?: JSONObject()
                        listOf(item.optString("name"), item.optString("admin1"), item.optString("country")).filter { it.isNotBlank() }.distinct().joinToString(", ")
                    }
                    AlertDialog.Builder(this@NativeWeatherActivity).setTitle("Elige una ubicación")
                        .setItems(names.toTypedArray()) { _, which ->
                            val item = results.optJSONObject(which) ?: return@setItems
                            saveLocation(item.optDouble("latitude"), item.optDouble("longitude"), item.optString("name", query))
                        }.show()
                    dialog?.dismiss()
                }
            }
        })
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

    private fun disposeRadarPlayer() {
        val old = radarPlayer ?: return
        (old.parent as? ViewGroup)?.removeView(old)
        old.dispose()
        radarPlayer = null
    }

    private fun addBackgroundControls() {
        content.addView(text("Servicios de fondo", 16f, Color.WHITE, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(7) })
        val widget = Switch(this).apply {
            text = "Alertas de clima y widget"; textSize = 13f; setTextColor(Color.WHITE)
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
            text = "Recibir alertas sísmicas"; textSize = 13f; setTextColor(Color.WHITE)
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
            text = "Detector local por sensor"; textSize = 13f; setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("quake_detector_enabled", true)
            setOnCheckedChangeListener { control, checked ->
                if (!setLocalQuakeDetector(checked)) control.isChecked = false
            }
        }
        content.addView(card {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                addView(button("Permitir notificaciones", cyan) {
                    pendingNotificationSwitch = widget
                    ActivityCompat.requestPermissions(this@NativeWeatherActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
                }, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(6) })
            }
            addView(widget)
            addView(quakes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(detector, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(text("El detector usa el acelerómetro local y requiere su notificación persistente. Las alertas cercanas se reciben por el servicio nativo de CodeHub; Android puede limitar el trabajo en segundo plano según batería y permisos.", 11f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
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

    private fun validLocation() = lat.isFinite() && lon.isFinite() && (lat != 0.0 || lon != 0.0)

    private fun metricCard(title: String, value: String, detail: String, accent: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(11), dp(12), dp(10))
        background = rounded(panel, dp(22))
        addView(text(title, 11f, muted, true))
        addView(text(value, 19f, Color.WHITE, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        addView(text(detail, 10f, accent), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    }

    private fun weatherHeroBackground(code: Int, day: Boolean): GradientDrawable {
        val colors = when {
            code >= 95 -> intArrayOf(0xFF43518B.toInt(), 0xFF171D36.toInt())
            code in 51..86 -> intArrayOf(0xFF2672A6.toInt(), 0xFF243454.toInt())
            !day -> intArrayOf(0xFF35477C.toInt(), 0xFF161D39.toInt())
            code in 1..3 -> intArrayOf(0xFF54A7D7.toInt(), 0xFF5570B5.toInt())
            else -> intArrayOf(0xFF56AEDD.toInt(), 0xFF6A78C4.toInt())
        }
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = dp(30).toFloat() }
    }

    private fun animateEntrance(parent: LinearLayout) {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            child.alpha = 0f
            child.translationY = dp(12).toFloat()
            child.animate().alpha(1f).translationY(0f).setStartDelay(index * 28L).setDuration(260L).start()
        }
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
    private fun weatherEmoji(code: Int) = when (code) {
        0 -> "☀️"; 1, 2 -> "🌤️"; 3 -> "☁️"; 45, 48 -> "🌫️"; in 51..67 -> "🌧️"
        in 71..77, 85, 86 -> "❄️"; in 80..82 -> "🌦️"; in 95..99 -> "⛈️"; else -> "🌡️"
    }

    private fun card(block: LinearLayout.() -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15)); background = rounded(panel, dp(20)); block()
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun button(title: String, tint: Int, onClick: () -> Unit) = Button(this).apply {
        text = title; textSize = 12f; isAllCaps = false; setTextColor(if (tint == cyan) bg else Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (tint == cyan) tint else panel)
        setOnClickListener { onClick() }
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
}
