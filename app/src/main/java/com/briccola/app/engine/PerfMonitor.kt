package com.briccola.app.engine

/**
 * Profiler manuale leggero: misura quanto tempo impiegano i blocchi "caldi" (chiamati spesso,
 * es. dentro il loop camera/HUD sul main thread).
 */
object PerfMonitor {
    private const val REPORT_INTERVAL_MS = 3000L

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
        maybeReport()
    }

    private fun maybeReport() {
        val now = System.currentTimeMillis()
        if (now - lastReport < REPORT_INTERVAL_MS) return
        lastReport = now
        synchronized(stats) {
            if (stats.isEmpty()) return
            stats.clear()
        }
    }
}
