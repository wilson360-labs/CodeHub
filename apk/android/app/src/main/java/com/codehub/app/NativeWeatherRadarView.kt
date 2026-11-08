package com.codehub.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Compact native precipitation-map player. Tomorrow.io keys stay on the CodeHub backend. */
class NativeWeatherRadarView(context: Context, private val latitude: Double, private val longitude: Double) : LinearLayout(context) {
    companion object {
        private const val BACKEND = "https://codehub-98s6.onrender.com/api/weather/radar"
        private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
        private const val SOURCE_ID = "codehub-radar-source"
        private const val LAYER_ID = "codehub-radar-layer"
        private const val PLAY_INTERVAL_MS = 1_100L
    }

    private val muted = 0xFFAAB5C9.toInt()
    private val accent = 0xFF72E7F2.toInt()
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    private val handler = Handler(Looper.getMainLooper())
    private val frames = mutableListOf<String>()
    private var mapView: MapView? = null
    private var map: org.maplibre.android.maps.MapLibreMap? = null
    private var mapStyle: Style? = null
    private var frameIndex = 0
    private var wantsPlayback = true
    private var hostStarted = false
    private var hostResumed = false
    private var viewStarted = false
    private var viewResumed = false
    private var disposed = false

    private val status = TextView(context).apply { text = "Preparando radar…"; textSize = 10f; setTextColor(Color.WHITE); setPadding(dp(9), dp(5), dp(9), dp(5)); background = pill(0xC9141C2A.toInt()) }
    private val frameLabel = TextView(context).apply { text = "--:--"; textSize = 11f; setTextColor(muted); gravity = Gravity.CENTER }
    private val playButton = TextView(context).apply {
        text = "Ⅱ"; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        background = pill(0xFF263750.toInt()); isEnabled = false; alpha = .55f; contentDescription = "Pausar radar"
    }
    private val seek = SeekBar(context).apply {
        max = 0; progress = 0
        progressTintList = android.content.res.ColorStateList.valueOf(accent)
        thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        isEnabled = false
    }

    private val playback = object : Runnable {
        override fun run() {
            if (!hostResumed || !wantsPlayback || frames.size < 2) return
            selectFrame((frameIndex + 1) % frames.size)
            handler.postDelayed(this, PLAY_INTERVAL_MS)
        }
    }

    private val frameRefresh = object : Runnable {
        override fun run() {
            if (!hostResumed) return
            loadFrames()
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    init {
        orientation = VERTICAL
        setPadding(dp(15), dp(14), dp(15), dp(12))
        background = GradientDrawable().apply { setColor(0xFF121A2A.toInt()); cornerRadius = dp(24).toFloat() }

        val titleRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; orientation = HORIZONTAL }
        val titleStack = LinearLayout(context).apply { orientation = VERTICAL }
        titleStack.addView(TextView(context).apply { text = "Radar de lluvia"; textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD })
        titleStack.addView(TextView(context).apply { text = "Últimos 20 min · fotogramas cada 5 min"; textSize = 10f; setTextColor(muted); setPadding(0, dp(3), 0, 0) })
        titleRow.addView(titleStack, LayoutParams(0, -2, 1f))
        titleRow.addView(playButton, LayoutParams(dp(42), dp(38)))
        addView(titleRow, LayoutParams(-1, -2))

        val mapFrame = FrameLayout(context).apply {
            background = pill(0xFF18243A.toInt())
            clipToOutline = true
        }
        MapLibre.getInstance(context.applicationContext)
        val nativeMap = MapView(context).apply { onCreate(Bundle()) }
        mapView = nativeMap
        mapFrame.addView(nativeMap, FrameLayout.LayoutParams(-1, -1))
        mapFrame.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(9), dp(9), dp(9), dp(9)) })
        val marker = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFECFEFF.toInt())
                setStroke(dp(3), 0xFF0891B2.toInt())
            }
            elevation = dp(8).toFloat()
            contentDescription = "Ubicación del pronóstico"
        }
        mapFrame.addView(marker, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        marker.animate().scaleX(1.28f).scaleY(1.28f).alpha(.78f).setDuration(900).withEndAction {
            if (marker.isAttachedToWindow) marker.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(900).start()
        }.start()
        addView(mapFrame, LayoutParams(-1, dp(188)).apply { topMargin = dp(12) })

        val controls = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        controls.addView(seek, LayoutParams(0, dp(38), 1f))
        controls.addView(frameLabel, LayoutParams(dp(48), dp(38)))
        addView(controls, LayoutParams(-1, -2).apply { topMargin = dp(3) })
        val attribution = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
        attribution.addView(TextView(context).apply {
            text = "Datos de Tomorrow.io"; textSize = 9f; setTextColor(muted); contentDescription = "Atribución de datos de Tomorrow.io"
            setOnClickListener { openAttribution("https://www.tomorrow.io/weather-api/") }
        })
        attribution.addView(TextView(context).apply { text = "  ·  "; textSize = 9f; setTextColor(muted) })
        attribution.addView(TextView(context).apply {
            text = "© OpenStreetMap / OpenFreeMap"; textSize = 9f; setTextColor(muted); contentDescription = "Atribución del mapa OpenStreetMap y OpenFreeMap"
            setOnClickListener { openAttribution("https://www.openstreetmap.org/copyright") }
        })
        addView(attribution, LayoutParams(-1, -2))

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && progress in frames.indices) {
                    wantsPlayback = false
                    updatePlayButton()
                    selectFrame(progress)
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { pausePlayback() }
            override fun onStopTrackingTouch(bar: SeekBar?) { }
        })
        playButton.setOnClickListener {
            wantsPlayback = !wantsPlayback
            updatePlayButton()
            if (wantsPlayback) startPlayback() else pausePlayback()
        }

        nativeMap.getMapAsync { nativeMapController ->
            if (mapView !== nativeMap) return@getMapAsync
            map = nativeMapController
            nativeMapController.uiSettings.setScrollGesturesEnabled(false)
            nativeMapController.uiSettings.setZoomGesturesEnabled(false)
            nativeMapController.uiSettings.setRotateGesturesEnabled(false)
            nativeMapController.uiSettings.setTiltGesturesEnabled(false)
            nativeMapController.uiSettings.setCompassEnabled(false)
            nativeMapController.cameraPosition = CameraPosition.Builder()
                .target(LatLng(latitude, longitude)).zoom(7.0).build()
            nativeMapController.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                if (mapView !== nativeMap) return@setStyle
                mapStyle = style
                frames.getOrNull(frameIndex)?.let { installRadarFrame(style, it) }
                status.text = "Radar listo"
            }
        }
        if (hostStarted) nativeMap.onStart().also { viewStarted = true }
        if (hostResumed) nativeMap.onResume().also { viewResumed = true }
        loadFrames()
    }

    fun onHostStart() {
        hostStarted = true
        if (!viewStarted) mapView?.onStart().also { viewStarted = mapView != null }
    }

    fun onHostResume() {
        hostResumed = true
        if (!viewStarted) onHostStart()
        if (!viewResumed) mapView?.onResume().also { viewResumed = mapView != null }
        if (wantsPlayback) startPlayback()
        handler.removeCallbacks(frameRefresh)
        handler.postDelayed(frameRefresh, 5 * 60 * 1000L)
    }

    fun onHostPause() {
        hostResumed = false
        pausePlayback()
        handler.removeCallbacks(frameRefresh)
        if (viewResumed) mapView?.onPause()
        viewResumed = false
    }

    fun onHostStop() {
        hostStarted = false
        if (viewStarted) mapView?.onStop()
        viewStarted = false
    }

    fun onHostLowMemory() { mapView?.onLowMemory() }

    fun dispose() {
        disposed = true
        pausePlayback()
        if (viewResumed) mapView?.onPause()
        if (viewStarted) mapView?.onStop()
        mapView?.onDestroy()
        viewResumed = false
        viewStarted = false
        mapView = null
        map = null
        mapStyle = null
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private fun loadFrames() {
        if (frames.isEmpty()) status.text = "Conectando radar…"
        val request = Request.Builder().url("$BACKEND/frames").header("Accept", "application/json").build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: java.io.IOException) = postStatus("Radar temporalmente no disponible")
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val successful = response.isSuccessful
                val body = response.use { runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull() }
                val values = body?.optJSONArray("frames")
                if (!successful || values == null || values.length() < 2) {
                    val message = body?.optString("error")?.takeIf { it.isNotBlank() } ?: "Radar temporalmente no disponible"
                    postStatus(message)
                    return
                }
                val parsed = (0 until values.length()).map { values.optString(it) }.filter { it.isNotBlank() }
                post {
                    if (mapView == null || disposed) return@post
                    val initialLoad = frames.isEmpty()
                    val previousFrame = frames.getOrNull(frameIndex)
                    if (parsed != frames) {
                        frames.clear()
                        frames.addAll(parsed)
                        frameIndex = previousFrame?.let { previous ->
                            val previousTime = runCatching { java.time.Instant.parse(previous).toEpochMilli() }.getOrDefault(0L)
                            frames.indices.minByOrNull { index ->
                                val nextTime = runCatching { java.time.Instant.parse(frames[index]).toEpochMilli() }.getOrDefault(0L)
                                kotlin.math.abs(nextTime - previousTime)
                            }
                        } ?: 0
                        seek.max = frames.lastIndex.coerceAtLeast(0)
                        seek.isEnabled = frames.size > 1
                        playButton.isEnabled = frames.size > 1
                        playButton.alpha = if (playButton.isEnabled) 1f else .55f
                        selectFrame(frameIndex)
                    }
                    if (initialLoad) wantsPlayback = true
                    updatePlayButton()
                    status.text = if (initialLoad) "En vivo · ${frames.size} fotogramas" else "Radar actualizado"
                    startPlayback()
                }
            }
        })
    }

    private fun installRadarFrame(style: Style, frame: String) {
        val previousLayer = style.getLayer(LAYER_ID)
        if (previousLayer != null) style.removeLayer(LAYER_ID)
        if (style.getSource(SOURCE_ID) != null) style.removeSource(SOURCE_ID)
        val encodedTime = Uri.encode(frame)
        val tileUrl = "$BACKEND/tile/{z}/{x}/{y}?time=$encodedTime"
        style.addSource(RasterSource(SOURCE_ID, TileSet("2.2.0", tileUrl), 256))
        style.addLayer(RasterLayer(LAYER_ID, SOURCE_ID).apply { setProperties(rasterOpacity(.74f)) })
    }

    private fun selectFrame(index: Int) {
        if (frames.isEmpty()) return
        frameIndex = index.coerceIn(frames.indices)
        seek.progress = frameIndex
        frameLabel.text = runCatching {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date.from(java.time.Instant.parse(frames[frameIndex])))
        }.getOrDefault("--:--")
        mapStyle?.let { style -> installRadarFrame(style, frames[frameIndex]) }
    }

    private fun startPlayback() {
        handler.removeCallbacks(playback)
        if (hostResumed && wantsPlayback && frames.size > 1) handler.postDelayed(playback, PLAY_INTERVAL_MS)
    }

    private fun pausePlayback() { handler.removeCallbacks(playback) }

    private fun updatePlayButton() {
        playButton.text = if (wantsPlayback) "Ⅱ" else "▶"
        playButton.contentDescription = if (wantsPlayback) "Pausar radar" else "Reproducir radar"
    }

    private fun postStatus(message: String) {
        post { if (mapView != null && !disposed) status.text = message }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private fun openAttribution(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(14).toFloat() }
}
