package com.briccola.app.engine

import android.content.Context
import org.json.JSONArray
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Un estremale di marea (massimo o minimo), istante + valore in metri sullo zero mareografico. */
data class TideExtreme(val timeMs: Long, val valueM: Double, val isMax: Boolean)

data class TideData(
    val nowM: Double,
    val curve: List<Pair<Long, Double>>,   // (istante, valore in metri) per il grafico di oggi
    val extremes: List<TideExtreme>,       // estremali di oggi, per le etichette del grafico
    val isOffline: Boolean,
    val updatedAt: Long
)

/**
 * Livello di marea per la laguna di Venezia, riferito allo Zero Mareografico di Punta della
 * Salute (lo stesso riferimento usato dalla rete di monitoraggio del Comune di Venezia).
 *
 * Fonte online: dati.venezia.it (Centro Previsioni e Segnalazioni Maree), gratuita e senza
 * chiave API — previsione.json per gli estremali, livello.json per il valore in tempo reale
 * alla stazione di Punta Salute. Se non c'è connessione si usa il fallback offline
 * (marea_astronomica.json, precalcolato e incluso nell'app), meno preciso perché è la sola
 * marea astronomica (non tiene conto di vento/pressione) ma sempre disponibile.
 */
object TideEngine {
    private const val STAZIONE_RIFERIMENTO = "PSalute"
    private val UTC_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY).apply {
        timeZone = TimeZone.getTimeZone("Europe/Rome")
    }

    suspend fun fetch(context: Context, dayStartMs: Long? = null): TideData? {
        val now = System.currentTimeMillis()
        val targetDayStart = dayStartMs ?: todayBounds().first
        
        // Se è oggi, proviamo online. Altrimenti (o se fallisce) usiamo l'astronomica.
        val data = if (isSameDay(targetDayStart, now)) {
            fetchOnline(context) ?: fetchOffline(context, targetDayStart)
        } else {
            fetchOffline(context, targetDayStart)
        }

        // Se il giorno è oggi, mostriamo solo i dati da "adesso" in poi (con un piccolo margine)
        if (data != null && isSameDay(targetDayStart, now)) {
            val margin = 10 * 60_000L // 10 minuti di margine per vedere il punto attuale
            return data.copy(
                curve = data.curve.filter { it.first >= now - margin },
                extremes = data.extremes.filter { it.timeMs >= now - margin }
            )
        }

        return data
    }

    private fun isSameDay(t1: Long, t2: Long): Boolean {
        val cal1 = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome")).apply { timeInMillis = t1 }
        val cal2 = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome")).apply { timeInMillis = t2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun fetchOnline(context: Context): TideData? {
        return try {
            val previsioneJson = JSONArray(httpGet("https://dati.venezia.it/sites/default/files/dataset/opendata/previsione.json"))
            val extremesOnline = mutableListOf<TideExtreme>()
            for (i in 0 until previsioneJson.length()) {
                val o = previsioneJson.getJSONObject(i)
                val t = parseTime(o.getString("DATA_ESTREMALE")) ?: continue
                val v = o.getString("VALORE").toDouble() / 100.0
                extremesOnline += TideExtreme(t, v, o.getString("TIPO_ESTREMALE") == "max")
            }
            if (extremesOnline.isEmpty()) return null

            // Per la giornata di oggi, usiamo l'astronomica come base (tutto il giorno)
            // e sovrapponiamo i valori reali per la parte futura.
            val bounds = todayBounds()
            val astronomicalExtremes = loadOfflineExtremes(context) ?: emptyList()
            
            val livelloJson = JSONArray(httpGet("https://dati.venezia.it/sites/default/files/dataset/opendata/livello.json"))
            var nowM: Double? = null
            for (i in 0 until livelloJson.length()) {
                val o = livelloJson.getJSONObject(i)
                if (o.getString("nome_abbr") == STAZIONE_RIFERIMENTO) {
                    nowM = o.getString("valore").replace(" m", "").trim().toDoubleOrNull()
                    break
                }
            }

            // Invece di spezzare il grafico, prendiamo l'estremale astronomico più vicino a "ora"
            // e applichiamo uno shift (offset) a tutta la curva astronomica di oggi per farla
            // coincidere col valore reale di Punta Salute. È meno "scientifico" ma molto più
            // leggibile e fluido per l'utente.
            buildTideDataHybrid(astronomicalExtremes, extremesOnline, nowM, bounds.first, bounds.second)
        } catch (_: Exception) {
            null
        }
    }

    fun fetchOffline(context: Context, dayStartMs: Long? = null): TideData? {
        val extremesAll = loadOfflineExtremes(context) ?: return null
        val bounds = dayStartMs?.let { it to it + 24 * 3600_000L } ?: todayBounds()
        return buildTideData(extremesAll, nowM = null, isOffline = true, bounds.first, bounds.second)
    }

    /** Costruisce la curva di OGGI partendo da ADESSO usando solo dati reali (Sensore + Previsione). */
    private fun buildTideDataHybrid(
        astronomical: List<TideExtreme>,
        online: List<TideExtreme>,
        realTimeValue: Double?,
        @Suppress("UNUSED_PARAMETER") start: Long,
        end: Long
    ): TideData? {
        val now = System.currentTimeMillis()
        
        // Se non abbiamo dati online, usiamo l'astronomica come unico "punto reale" disponibile
        val sourceExtremes = if (online.isNotEmpty()) online else astronomical
        val sortedSource = sourceExtremes.sortedBy { it.timeMs }
        
        // 1. Calcoliamo il livello attuale e l'offset rispetto alla previsione
        val predictedNow = interpolateAt(sortedSource, now) ?: sortedSource.first().valueM
        val currentLevel = realTimeValue ?: predictedNow
        val offset = currentLevel - predictedNow
        
        // 2. Prepariamo i punti della curva: partiamo da ADESSO (reale) + i picchi FUTURI (traslati)
        val startPoint = TideExtreme(now, currentLevel, false)
        val futureExtremes = sortedSource.filter { it.timeMs > now }.map { 
            it.copy(valueM = it.valueM + offset) 
        }
        val curvePoints = (listOf(startPoint) + futureExtremes).sortedBy { it.timeMs }
        
        // 3. Generiamo la curva partendo esattamente da ADESSO
        val curve = mutableListOf<Pair<Long, Double>>()
        var t = now
        while (t <= end) {
            interpolateAt(curvePoints, t)?.let { v ->
                curve += t to v
            }
            t += 10 * 60_000L
        }
        if (curve.isEmpty()) curve += now to currentLevel

        // 4. Estremali per le etichette (solo quelli futuri di oggi)
        val todaysExtremes = futureExtremes.filter { it.timeMs <= end }
        
        return TideData(
            nowM = currentLevel,
            curve = curve,
            extremes = todaysExtremes,
            isOffline = online.isEmpty(),
            updatedAt = now
        )
    }

    private fun buildTideData(
        extremesAll: List<TideExtreme>,
        nowM: Double?,
        isOffline: Boolean,
        start: Long,
        end: Long
    ): TideData? {
        val now = System.currentTimeMillis()
        val sorted = extremesAll.sortedBy { it.timeMs }
        val curve = interpolateCurve(sorted, start, end)
        if (curve.isEmpty()) return null

        val todaysExtremes = sorted.filter { it.timeMs in start..end }
        val currentValue = nowM ?: interpolateAt(sorted, now) ?: 0.0

        return TideData(nowM = currentValue, curve = curve, extremes = todaysExtremes, isOffline = isOffline, updatedAt = now)
    }

    private fun todayBounds(): Pair<Long, Long> {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome"))
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        return start to (start + 24 * 3600_000L)
    }

    private fun interpolateAt(sorted: List<TideExtreme>, t: Long): Double? {
        val before = sorted.lastOrNull { it.timeMs <= t } ?: return null
        val after = sorted.firstOrNull { it.timeMs > t } ?: return null
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
            val root = org.json.JSONObject(jsonString)
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

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection()
        conn.setRequestProperty("User-Agent", "Briccola/1.0")
        conn.connectTimeout = 6000; conn.readTimeout = 6000
        return conn.getInputStream().bufferedReader().readText()
    }
}
