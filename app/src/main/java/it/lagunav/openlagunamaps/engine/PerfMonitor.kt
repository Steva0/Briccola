package it.lagunav.openlagunamaps.engine

import android.util.Log

/**
 * Profiler manuale leggero: misura quanto tempo impiegano i blocchi "caldi" (chiamati spesso,
 * es. dentro il loop camera/HUD sul main thread) e stampa un riepilogo in Logcat ogni
 * REPORT_INTERVAL_MS, ordinato dal più costoso al meno costoso.
 *
 * Uso: PerfMonitor.trace("nomeSezione") { ...codice... }
 *
 * In Logcat, filtra per tag "PERF": ogni pochi secondi vedi quali sezioni si mangiano il tempo
 * e quanta memoria heap è in uso. Questo è il punto di partenza per capire, in futuro, COSA
 * rallenta l'app dopo altre modifiche — invece di dover indovinare o ristrumentare da zero,
 * si aggiungono nuovi PerfMonitor.trace(...) attorno ai sospetti e si guarda il riepilogo.
 */
object PerfMonitor {
    private const val TAG = "PERF"
    private const val REPORT_INTERVAL_MS = 3000L
    private const val SLOW_CALL_MS = 8.0  // singola chiamata più lenta di questo: warning immediato

    private class Stats {
        var count = 0
        var totalMs = 0.0
        var maxMs = 0.0
    }

    private val stats = mutableMapOf<String, Stats>()
    private var lastReport = 0L

    inline fun <T> trace(tag: String, block: () -> T): T {
        val start = System.nanoTime()
        val result = block()
        val ms = (System.nanoTime() - start) / 1_000_000.0
        record(tag, ms)
        return result
    }

    fun record(tag: String, ms: Double) {
        synchronized(stats) {
            val s = stats.getOrPut(tag) { Stats() }
            s.count++
            s.totalMs += ms
            if (ms > s.maxMs) s.maxMs = ms
        }
        if (ms > SLOW_CALL_MS) Log.w(TAG, "$tag: chiamata lenta %.1fms".format(ms))
        maybeReport()
    }

    private fun maybeReport() {
        val now = System.currentTimeMillis()
        if (now - lastReport < REPORT_INTERVAL_MS) return
        lastReport = now
        val snapshot: List<Pair<String, Stats>>
        synchronized(stats) {
            if (stats.isEmpty()) return
            snapshot = stats.entries.map { it.key to it.value }
            stats.clear()
        }
        val usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_048_576.0
        Log.i(TAG, "--- riepilogo ~${REPORT_INTERVAL_MS / 1000}s --- heap usata=%.1fMB".format(usedMb))
        snapshot.sortedByDescending { it.second.totalMs }.forEach { (tag, s) ->
            Log.i(TAG, "%-28s calls=%-5d avg=%6.2fms max=%6.2fms tot=%7.0fms".format(
                tag, s.count, s.totalMs / s.count, s.maxMs, s.totalMs
            ))
        }
    }
}
