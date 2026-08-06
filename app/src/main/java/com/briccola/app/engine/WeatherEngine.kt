package com.briccola.app.engine

import org.json.JSONObject
import java.net.URL

/** Condizioni meteo attuali per la laguna di Venezia (Open-Meteo: gratuito, senza chiave API). */
data class WeatherData(
    val tempC: Double,
    val weatherCode: Int,
    val windSpeedKmh: Double,
    val windDirectionDeg: Double,
    val precipitationMm: Double,
    val waveHeightM: Double?,   // null se il servizio marino non risponde (non blocca il resto)
    val updatedAt: Long
)

/** Previsione di UN giorno (oggi + i prossimi), per la selezione giorno per giorno in pagina Meteo. */
data class DailyWeather(
    val dateIso: String,        // "yyyy-MM-dd"
    val weatherCode: Int,
    val tempMaxC: Double,
    val tempMinC: Double,
    val windSpeedMaxKmh: Double,
    val windDirectionDeg: Double,
    val precipitationSumMm: Double,
    val waveHeightMaxM: Double?  // null se il servizio marino non risponde
)

/**
 * Meteo tramite Open-Meteo (open-meteo.com): completamente gratuito, nessuna chiave API,
 * pensato apposta per un uso come questo. Niente fallback offline per scelta esplicita — se non
 * c'è connessione l'app mostra solo un avviso (vedi WeatherFragment).
 */
object WeatherEngine {
    // Centro della laguna di Venezia: la pagina Meteo non è legata alla posizione GPS della
    // barca, mostra le condizioni generali della zona.
    private const val LAT = 45.4371
    private const val LON = 12.3345

    suspend fun fetch(): WeatherData? {
        val current = fetchCurrentWeather() ?: return null
        val wave = try { fetchWaveHeight() } catch (_: Exception) { null }
        return current.copy(waveHeightM = wave)
    }

    private fun fetchCurrentWeather(): WeatherData? {
        return try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
                    "&current=temperature_2m,precipitation,weather_code,wind_speed_10m,wind_direction_10m" +
                    "&timezone=auto"
            val json = JSONObject(httpGet(url))
            val current = json.getJSONObject("current")
            WeatherData(
                tempC = current.getDouble("temperature_2m"),
                weatherCode = current.getInt("weather_code"),
                windSpeedKmh = current.getDouble("wind_speed_10m"),
                windDirectionDeg = current.getDouble("wind_direction_10m"),
                precipitationMm = current.optDouble("precipitation", 0.0),
                waveHeightM = null,
                updatedAt = System.currentTimeMillis()
            )
        } catch (_: Exception) { null }
    }

    /** Previsione giorno per giorno (oggi + prossimi 6): usa i parametri "daily" di Open-Meteo,
     *  che restituisce già massimi/minimi/somme aggregati per giorno (non serve calcolarli noi
     *  da dati orari). Le onde vengono dall'API marina separata, come per fetch(): se fallisce
     *  quella parte, il resto della previsione resta comunque disponibile (waveHeightMaxM = null). */
    suspend fun fetchDaily(): List<DailyWeather>? {
        val days = try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,wind_speed_10m_max," +
                    "wind_direction_10m_dominant,precipitation_sum&timezone=auto&forecast_days=7"
            val daily = JSONObject(httpGet(url)).getJSONObject("daily")
            val dates = daily.getJSONArray("time")
            val codes = daily.getJSONArray("weather_code")
            val tMax  = daily.getJSONArray("temperature_2m_max")
            val tMin  = daily.getJSONArray("temperature_2m_min")
            val wind  = daily.getJSONArray("wind_speed_10m_max")
            val windDir = daily.getJSONArray("wind_direction_10m_dominant")
            val precip  = daily.getJSONArray("precipitation_sum")
            (0 until dates.length()).map { i ->
                DailyWeather(
                    dateIso = dates.getString(i),
                    weatherCode = codes.getInt(i),
                    tempMaxC = tMax.getDouble(i),
                    tempMinC = tMin.getDouble(i),
                    windSpeedMaxKmh = wind.getDouble(i),
                    windDirectionDeg = windDir.getDouble(i),
                    precipitationSumMm = precip.getDouble(i),
                    waveHeightMaxM = null
                )
            }
        } catch (_: Exception) { null } ?: return null

        val waves = try { fetchDailyWaveHeights(days.map { it.dateIso }) } catch (_: Exception) { null }
        return if (waves == null) days else days.map { d -> d.copy(waveHeightMaxM = waves[d.dateIso]) }
    }

    private fun fetchDailyWaveHeights(dates: List<String>): Map<String, Double>? {
        val url = "https://marine-api.open-meteo.com/v1/marine?latitude=$LAT&longitude=$LON" +
                "&daily=wave_height_max&timezone=auto&forecast_days=${dates.size}"
        val daily = JSONObject(httpGet(url)).getJSONObject("daily")
        val d = daily.getJSONArray("time")
        val h = daily.getJSONArray("wave_height_max")
        return (0 until d.length()).associate { i -> d.getString(i) to h.getDouble(i) }
    }

    private fun fetchWaveHeight(): Double? {
        val url = "https://marine-api.open-meteo.com/v1/marine?latitude=$LAT&longitude=$LON&current=wave_height"
        val json = JSONObject(httpGet(url))
        val value = json.optJSONObject("current")?.optDouble("wave_height") ?: return null
        return value.takeIf { !it.isNaN() }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection()
        conn.setRequestProperty("User-Agent", "Briccola/1.0")
        conn.connectTimeout = 6000; conn.readTimeout = 6000
        return conn.getInputStream().bufferedReader().readText()
    }

    /** Emoji + descrizione in italiano per i codici meteo WMO usati da Open-Meteo. */
    fun describeWeatherCode(code: Int): Pair<String, String> = when (code) {
        0 -> "☀️" to "Cielo sereno"
        1 -> "🌤️" to "Prevalentemente sereno"
        2 -> "⛅" to "Parzialmente nuvoloso"
        3 -> "☁️" to "Nuvoloso"
        45, 48 -> "🌫️" to "Nebbia"
        51, 53, 55 -> "🌦️" to "Pioviggine"
        56, 57 -> "🌧️" to "Pioviggine gelata"
        61, 63, 65 -> "🌧️" to "Pioggia"
        66, 67 -> "🌧️" to "Pioggia gelata"
        71, 73, 75, 77 -> "❄️" to "Neve"
        80, 81, 82 -> "🌦️" to "Rovesci di pioggia"
        85, 86 -> "🌨️" to "Rovesci di neve"
        95 -> "⛈️" to "Temporale"
        96, 99 -> "⛈️" to "Temporale con grandine"
        else -> "🌡️" to "Condizioni sconosciute"
    }

    /** Punto cardinale (N/NE/E/...) da un angolo in gradi, per mostrare la direzione del vento. */
    fun windDirectionLabel(deg: Double): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        val idx = (((deg % 360) + 360) % 360 / 45.0).let { Math.round(it).toInt() % 8 }
        return dirs[idx]
    }
}
