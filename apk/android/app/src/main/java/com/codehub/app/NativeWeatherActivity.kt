package com.codehub.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
import java.util.Locale
import java.util.concurrent.TimeUnit

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
        super.onDestroy()
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
        content.removeAllViews()
        content.addView(card {
            addView(text("Tu clima, en CodeHub", 21f, Color.WHITE, true))
            addView(text(message, 13f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(button("Usar mi ubicación", cyan) { requestLocation() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) })
            addView(citySearch(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        })
        addBackgroundControls()
    }

    private fun renderLoading() {
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
            "&hourly=temperature_2m,precipitation_probability,weather_code" +
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
        content.removeAllViews()
        val current = data.optJSONObject("current") ?: return
        val daily = data.optJSONObject("daily") ?: JSONObject()
        val hours = data.optJSONObject("hourly") ?: JSONObject()
        val name = city.ifBlank { "Ubicación guardada" }
        content.addView(card {
            addView(text("$name  ·  ${current.optString("time").substringAfter('T', "Ahora")}", 13f, muted, true))
            addView(text(weatherEmoji(current.optInt("weather_code")), 44f, Color.WHITE, true), LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(10) })
            addView(text("${current.optInt("temperature_2m")}°", 54f, Color.WHITE, true))
            addView(text(weatherName(current.optInt("weather_code")), 16f, cyan, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            val min = daily.optJSONArray("temperature_2m_min")?.optDouble(0)?.toInt()
            val max = daily.optJSONArray("temperature_2m_max")?.optDouble(0)?.toInt()
            addView(text("Sensación ${current.optInt("apparent_temperature")}°   ·   ${min ?: "—"}° / ${max ?: "—"}°", 12f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(text("Humedad ${current.optInt("relative_humidity_2m")}%   ·   Viento ${current.optInt("wind_speed_10m")} km/h", 12f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        })
        content.addView(button("Usar mi ubicación actual", cyan) { requestLocation() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(9) })
        content.addView(citySearch(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })

        content.addView(text("Próximas horas", 16f, Color.WHITE, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(8) })
        val hourlyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val times = hours.optJSONArray("time")
        val temps = hours.optJSONArray("temperature_2m")
        val rains = hours.optJSONArray("precipitation_probability")
        val codes = hours.optJSONArray("weather_code")
        val start = times?.let { arr -> (0 until arr.length()).firstOrNull { arr.optString(it) > current.optString("time") } } ?: 0
        repeat(8) { offset ->
            val index = start + offset
            if (times != null && temps != null && index < minOf(times.length(), temps.length())) {
                val time = times.optString(index).substringAfter('T', "--:--")
                hourlyRow.addView(card {
                    gravity = Gravity.CENTER
                    addView(text(time, 11f, muted, true))
                    addView(text(weatherEmoji(codes?.optInt(index) ?: 0), 20f, Color.WHITE), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
                    addView(text("${temps.optDouble(index).toInt()}°", 14f, Color.WHITE, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                    addView(text("${rains?.optInt(index) ?: 0}%", 10f, cyan), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                }, LinearLayout.LayoutParams(dp(78), dp(106)).apply { marginEnd = dp(7) })
            }
        }
        val horizontal = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(hourlyRow) }
        content.addView(horizontal)
        content.addView(text("Próximos días", 16f, Color.WHITE, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(8) })
        val dates = daily.optJSONArray("time")
        val dayCodes = daily.optJSONArray("weather_code")
        val maxes = daily.optJSONArray("temperature_2m_max")
        val mins = daily.optJSONArray("temperature_2m_min")
        repeat(dates?.length() ?: 0) { i ->
            content.addView(card {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(text(if (i == 0) "Hoy" else dates?.optString(i).orEmpty(), 13f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(weatherEmoji(dayCodes?.optInt(i) ?: 0), 20f, Color.WHITE), LinearLayout.LayoutParams(dp(38), -2))
                addView(text("${mins?.optDouble(i)?.toInt() ?: 0}° / ${maxes?.optDouble(i)?.toInt() ?: 0}°", 13f, muted, true))
            }, LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(7) })
        }
        addBackgroundControls()
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
        renderNoLocation("Obteniendo ubicación actual…")
        val request = CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY).setMaxUpdateAgeMillis(120_000).build()
        val token = CancellationTokenSource()
        LocationServices.getFusedLocationProviderClient(this).getCurrentLocation(request, token.token)
            .addOnSuccessListener { location ->
                if (location != null) saveLocation(location.latitude, location.longitude, "")
                else Toast.makeText(this, "No se pudo obtener la ubicación. Prueba buscar tu ciudad.", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { Toast.makeText(this, "No se pudo acceder a la ubicación.", Toast.LENGTH_SHORT).show() }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_REQUEST && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) requestLocation()
        if (requestCode == NOTIFICATION_REQUEST) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) pendingNotificationSwitch?.isChecked = true
            else Toast.makeText(this, "Sin permiso, Android no puede mostrar estas alertas.", Toast.LENGTH_LONG).show()
            pendingNotificationSwitch = null
        }
    }

    private fun saveLocation(newLat: Double, newLon: Double, newCity: String) {
        if (!newLat.isFinite() || !newLon.isFinite() || newLat !in -90.0..90.0 || newLon !in -180.0..180.0) return
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
            text = "Alertas de sismos"; textSize = 13f; setTextColor(Color.WHITE)
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
        content.addView(card {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                addView(button("Permitir notificaciones", cyan) {
                    pendingNotificationSwitch = widget
                    ActivityCompat.requestPermissions(this@NativeWeatherActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_REQUEST)
                }, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(6) })
            }
            addView(widget)
            addView(quakes, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(text("El clima y las alertas sísmicas usan servicios nativos. Se actualizan con la ubicación guardada y las políticas de batería/notificaciones de Android.", 11f, muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }

    private fun validLocation() = lat.isFinite() && lon.isFinite() && (lat != 0.0 || lon != 0.0)

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
