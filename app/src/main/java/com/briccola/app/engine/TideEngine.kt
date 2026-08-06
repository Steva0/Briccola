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

    suspend fun fetch(context: Context): TideData? {
        return fetchOnline(context) ?: fetchOffline(context)
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

            // previsione.json contiene solo gli estremali FUTURI dal momento della richiesta
            // (nessuno di quelli già passati oggi) — senza questo, il grafico di "oggi" partiva
            // a metà giornata invece che da mezzanotte. Colmiamo il buco iniziale con gli
            // estremali astronomici (bundlati offline, coprono l'intera giornata) solo per la
            // parte prima del primo estremale online, che resta comunque la fonte preferita.
            //
            // IMPORTANTE: le due porzioni vengono interpolate SEPARATAMENTE (non unite in un'unica
            // lista di estremali) — mescolare estremali di fonti diverse nella stessa interpolazione
            // creava un "gomito" innaturale esattamente al punto di cucitura (proprio dove cade
            // "adesso", il punto più importante del grafico). Un piccolo salto onesto fra le due
            // porzioni è preferibile a una falsa continuità: astronomia pura e osservato reale
            // possono davvero differire (vento/pressione), non è un errore di calcolo.
            val earliestOnlineMs = extremesOnline.minOf { it.timeMs }
            val backfill = loadOfflineExtremes(context)?.filter { it.timeMs < earliestOnlineMs } ?: emptyList()

            val livelloJson = JSONArray(httpGet("https://dati.venezia.it/sites/default/files/dataset/opendata/livello.json"))
            var nowM: Double? = null
            for (i in 0 until livelloJson.length()) {
                val o = livelloJson.getJSONObject(i)
                if (o.getString("nome_abbr") == STAZIONE_RIFERIMENTO) {
                    nowM = o.getString("valore").replace(" m", "").trim().toDoubleOrNull()
                    break
                }
            }

            buildTideDataTwoSegments(backfill, extremesOnline, earliestOnlineMs, nowM, isOffline = false)
        } catch (_: Exception) {
            null
        }
    }

    fun fetchOffline(context: Context): TideData? {
        val extremesAll = loadOfflineExtremes(context) ?: return null
        return buildTideData(extremesAll, nowM = null, isOffline = true)
    }

    private fun loadOfflineExtremes(context: Context): List<TideExtreme>? {
        return try {
            val text = context.assets.open("marea_astronomica.json").bufferedReader().readText()
            val json = org.json.JSONObject(text)
            val arr = json.getJSONArray("estremali")
            val extremes = mutableListOf<TideExtreme>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = parseTime(o.getString("DATA")) ?: continue
                val v = o.getString("VALORE").toDouble() / 100.0
                extremes += TideExtreme(t, v, o.getString("minmax") == "max")
            }
            extremes.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** Costruisce curva+estremali di oggi interpolando con una semi-cosinusoide fra estremali
     *  consecutivi (tecnica standard per disegnare la marea quando si hanno solo i picchi),
     *  tutti dalla STESSA fonte (usata per il fallback offline, dove non c'è cucitura). */
    private fun buildTideData(extremesAll: List<TideExtreme>, nowM: Double?, isOffline: Boolean): TideData? {
        val cal = todayBounds()
        val todayStart = cal.first; val todayEnd = cal.second
        val now = System.currentTimeMillis()

        val sorted = extremesAll.sortedBy { it.timeMs }
        val curve = interpolateCurve(sorted, todayStart, todayEnd)
        if (curve.isEmpty()) return null

        val todaysExtremes = sorted.filter { it.timeMs in todayStart..todayEnd }
        val currentValue = nowM ?: interpolateAt(sorted, now) ?: curve.minByOrNull { Math.abs(it.first - now) }!!.second

        return TideData(nowM = currentValue, curve = curve, extremes = todaysExtremes, isOffline = isOffline, updatedAt = now)
    }

    /** Come buildTideData, ma per la fonte online: interpola i estremali "passati" (backfill
     *  offline) e "futuri" (online) SEPARATAMENTE, senza mai accoppiare un estremale di una
     *  fonte con uno dell'altra nella stessa interpolazione (vedi commento in fetchOnline). */
    private fun buildTideDataTwoSegments(
        pastExtremes: List<TideExtreme>, futureExtremes: List<TideExtreme>,
        boundaryMs: Long, nowM: Double?, isOffline: Boolean
    ): TideData? {
        val cal = todayBounds()
        val todayStart = cal.first; val todayEnd = cal.second
        val now = System.currentTimeMillis()

        val pastSorted = pastExtremes.sortedBy { it.timeMs }
        val futureSorted = futureExtremes.sortedBy { it.timeMs }
        val curve = interpolateCurve(pastSorted, todayStart, boundaryMs) +
                interpolateCurve(futureSorted, boundaryMs, todayEnd)
        if (curve.isEmpty()) return null

        val allExtremes = (pastSorted + futureSorted).filter { it.timeMs in todayStart..todayEnd }
        val currentValue = nowM ?: curve.minByOrNull { Math.abs(it.first - now) }!!.second

        return TideData(nowM = currentValue, curve = curve, extremes = allExtremes, isOffline = isOffline, updatedAt = now)
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

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection()
        conn.setRequestProperty("User-Agent", "Briccola/1.0")
        conn.connectTimeout = 6000; conn.readTimeout = 6000
        return conn.getInputStream().bufferedReader().readText()
    }
}
