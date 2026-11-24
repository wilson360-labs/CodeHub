package com.codehub.app.weather

import kotlin.math.roundToInt

enum class WeatherCondition { CLEAR_DAY, CLEAR_NIGHT, RAIN, CLOUDY, SNOW, STORM, FOG }

data class HourPoint(
    val label: String,
    val temp: Int,
    val precip: Int,
    val code: Int,
    val isDay: Boolean,
)

data class DayForecast(
    val label: String,
    val min: Int,
    val max: Int,
    val code: Int,
    val precip: Int,
)

data class WeatherMetric(
    val label: String,
    val value: String,
    val hint: String,
    val tone: Tone = Tone.ACCENT,
) {
    enum class Tone { ACCENT, OK, WARN, DANGER }
}

data class WeatherAlert(
    val title: String,
    val body: String,
    val tone: WeatherMetric.Tone = WeatherMetric.Tone.ACCENT,
)

data class WeatherUiState(
    val location: String,
    val temp: Int,
    val feelsLike: Int,
    val description: String,
    val condition: WeatherCondition,
    val high: Int?,
    val low: Int?,
    val hourly: List<HourPoint>,
    val days: List<DayForecast>,
    val metrics: List<WeatherMetric>,
    val alert: WeatherAlert?,
    val backgroundAnimationEnabled: Boolean,
)

object WeatherUiMapper {
    fun condition(code: Int, isDay: Boolean): WeatherCondition = when {
        code >= 95 -> WeatherCondition.STORM
        code in 71..77 || code in 85..86 -> WeatherCondition.SNOW
        code in 45..48 -> WeatherCondition.FOG
        (code in 51..67) || (code in 80..82) -> WeatherCondition.RAIN
        code >= 3 -> WeatherCondition.CLOUDY
        !isDay -> WeatherCondition.CLEAR_NIGHT
        else -> WeatherCondition.CLEAR_DAY
    }

    fun description(code: Int): String = when (code) {
        0 -> "Despejado"
        1 -> "Mayormente despejado"
        2 -> "Parcialmente nublado"
        3 -> "Nublado"
        45, 48 -> "Niebla"
        in 51..57 -> "Llovizna"
        in 61..67 -> "Lluvia"
        in 71..77 -> "Nieve"
        in 80..82 -> "Chubascos"
        in 85..86 -> "Nevadas"
        in 95..99 -> "Tormenta eléctrica"
        else -> "Condiciones variables"
    }

    fun mock(location: String = "Ciudad de Guatemala"): WeatherUiState = WeatherUiState(
        location = location,
        temp = 24,
        feelsLike = 26,
        description = "Parcialmente nublado",
        condition = WeatherCondition.CLEAR_DAY,
        high = 27,
        low = 18,
        hourly = listOf(
            HourPoint("Ahora", 24, 10, 2, true),
            HourPoint("14:00", 25, 15, 2, true),
            HourPoint("15:00", 26, 20, 1, true),
            HourPoint("16:00", 25, 45, 80, true),
            HourPoint("17:00", 23, 60, 61, true),
            HourPoint("18:00", 22, 40, 80, false),
            HourPoint("19:00", 21, 20, 3, false),
            HourPoint("20:00", 20, 10, 2, false),
        ),
        days = listOf(
            DayForecast("Hoy", 18, 27, 2, 45),
            DayForecast("Mié", 17, 26, 80, 60),
            DayForecast("Jue", 17, 25, 61, 70),
            DayForecast("Vie", 16, 27, 1, 20),
            DayForecast("Sáb", 18, 28, 0, 5),
            DayForecast("Dom", 19, 29, 0, 0),
        ),
        metrics = listOf(
            WeatherMetric("Humedad", "68%", "Punto de rocío 17°", WeatherMetric.Tone.OK),
            WeatherMetric("Viento", "12 km/h", "Ráfagas 22 km/h NE", WeatherMetric.Tone.ACCENT),
            WeatherMetric("Índice UV", "6 Alto", "Protección 11:00-15:00", WeatherMetric.Tone.WARN),
            WeatherMetric("Calidad / Alérgenos", "Buena", "AQI 42 · polen bajo", WeatherMetric.Tone.OK),
        ),
        alert = WeatherAlert(
            "Probabilidad de lluvia",
            "Lluvia ligera cerca de 17:00. Lleva paraguas.",
            WeatherMetric.Tone.ACCENT
        ),
        backgroundAnimationEnabled = true,
    )

    fun uvTone(uv: Double): WeatherMetric.Tone = when {
        uv >= 8 -> WeatherMetric.Tone.DANGER
        uv >= 6 -> WeatherMetric.Tone.WARN
        uv >= 3 -> WeatherMetric.Tone.ACCENT
        else -> WeatherMetric.Tone.OK
    }

    fun humidityTone(h: Int): WeatherMetric.Tone = when {
        h >= 85 -> WeatherMetric.Tone.WARN
        h in 35..70 -> WeatherMetric.Tone.OK
        else -> WeatherMetric.Tone.ACCENT
    }

    fun windTone(kmh: Double): WeatherMetric.Tone = when {
        kmh >= 45 -> WeatherMetric.Tone.DANGER
        kmh >= 28 -> WeatherMetric.Tone.WARN
        else -> WeatherMetric.Tone.ACCENT
    }

    fun fmtTemp(v: Double?): Int? = v?.roundToInt()
}
