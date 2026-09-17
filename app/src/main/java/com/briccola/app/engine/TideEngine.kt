package com.briccola.app.engine

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Un estremale di marea (massimo o minimo), istante + valore in metri sullo zero mareografico. */
data class TideExtreme(val timeMs: Long, val valueM: Double, val isMax: Boolean)

data class TideData(
    val nowM: Double,
    val curve: List<Pair<Long, Double>>,   // (istante, valore in metri) per il grafico
    val extremes: List<TideExtreme>,       // estremali, per le etichette del grafico
    val isOffline: Boolean,
    val updatedAt: Long
)

object TideEngine {
    private val UTC_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY).apply {
        timeZone = TimeZone.getTimeZone("Europe/Rome")
    }

    suspend fun fetch(context: Context, dayStartMs: Long? = null, fullDay: Boolean = false): TideData? {
        val now = System.currentTimeMillis()
        val targetDayStart = dayStartMs ?: todayBounds().first
        // Per il layer mappa (non fullDay), mostriamo le 24 ore successive a partire da "now" se è oggi
        val start = if (fullDay || !isSameDay(targetDayStart, now)) targetDayStart else now
        val end = if (fullDay || !isSameDay(targetDayStart, now)) targetDayStart + 24 * 3600_000L - 1000L else now + 24 * 3600_000L

        return fetchOffline(context, start, end)
    }

    private fun isSameDay(t1: Long, t2: Long): Boolean {
        val cal1 = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome")).apply { timeInMillis = t1 }
        val cal2 = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome")).apply { timeInMillis = t2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    fun fetchOffline(context: Context, start: Long, end: Long): TideData? {
        val extremesAll = loadOfflineExtremes(context) ?: return null
        val sorted = extremesAll.sortedBy { it.timeMs }
        val curve = interpolateCurve(sorted, start, end)
        if (curve.isEmpty()) return null

        val todaysExtremes = sorted.filter { it.timeMs in start..end }
        val now = System.currentTimeMillis()
        val currentValue = interpolateAt(sorted, now) ?: 0.0

        return TideData(nowM = currentValue, curve = curve, extremes = todaysExtremes, isOffline = true, updatedAt = now)
    }

    private fun todayBounds(): Pair<Long, Long> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome"))
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        return start to (start + 24 * 3600_000L)
    }

    private fun interpolateAt(sorted: List<TideExtreme>, t: Long): Double? {
        if (sorted.isEmpty()) return null
        val before = sorted.lastOrNull { it.timeMs <= t } ?: sorted.first()
        val after = sorted.firstOrNull { it.timeMs > t } ?: sorted.last()
        if (after.timeMs == before.timeMs) return before.valueM
        val frac = (t - before.timeMs).toDouble() / (after.timeMs - before.timeMs)
        return before.valueM + (after.valueM - before.valueM) / 2.0 * (1 - Math.cos(Math.PI * frac))
    }

    private fun interpolateCurve(sorted: List<TideExtreme>, fromMs: Long, toMs: Long): List<Pair<Long, Double>> {
        val curve = mutableListOf<Pair<Long, Double>>()
        var t = fromMs
        while (t <= toMs) {
            interpolateAt(sorted, t)?.let { curve += t to it }
            t += 10 * 60_000L
        }
        return curve
    }

    private fun parseTime(s: String): Long? = try { UTC_FORMAT.parse(s)?.time } catch (_: Exception) { null }

    private fun loadOfflineExtremes(context: Context): List<TideExtreme>? {
        return try {
            val jsonString = context.assets.open("marea_astronomica.json").bufferedReader().use { it.readText() }
            val root = JSONObject(jsonString)
            val array = root.getJSONArray("estremali")
            val result = mutableListOf<TideExtreme>()
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val t = parseTime(o.getString("DATA")) ?: continue
                val v = o.getString("VALORE").toDouble() / 100.0
                val isMax = o.getString("minmax") == "max"
                result += TideExtreme(t, v, isMax)
            }
            result
        } catch (e: Exception) {
            null
        }
    }
}
