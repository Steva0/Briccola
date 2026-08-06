package com.briccola.app.engine

import android.location.Location
import android.os.Handler
import android.os.Looper
import kotlin.math.*

/**
 * Provider GPS simulato per i Dev Tools.
 * Mantiene una posizione virtuale che si aggiorna ogni secondo in base
 * a bearing e velocità impostati dal joystick. Costruisce oggetti Location
 * identici a quelli del GnssPositionProvider reale: il resto dell'app
 * non distingue i due.
 */
class SimulatedPositionProvider : PositionProvider {

    var currentLat: Double = 45.4337
    var currentLon: Double = 12.3350
    var bearingDeg: Float = 0f
    var speedKnots: Float = 0f

    private val handler = Handler(Looper.getMainLooper())
    private var callback: ((Location) -> Unit)? = null
    private var running = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            updatePosition()
            callback?.invoke(buildLocation())
            handler.postDelayed(this, 1000L)
        }
    }

    override fun start(onFix: (Location) -> Unit) {
        callback = onFix
        // Idempotente: se già in esecuzione (es. onResume richiamato subito dopo onViewCreated,
        // che ha già avviato tutto tramite startSimulator()), non ripostare tickRunnable —
        // altrimenti finisce due volte in coda sullo stesso Handler, producendo un doppio tick
        // leggermente sfasato ogni secondo invece di uno pulito (lo scatto periodico della barca
        // alla primissima apertura di Dev Tools).
        if (running) return
        running = true
        handler.post(tickRunnable)
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(tickRunnable)
        callback = null
    }

    fun setMovement(bearing: Float, knots: Float) {
        bearingDeg = bearing
        speedKnots = knots
    }

    fun setPosition(lat: Double, lon: Double) {
        currentLat = lat
        currentLon = lon
    }

    /** Emette subito un fix con lo stato corrente, senza aspettare il prossimo tick (1Hz). */
    fun emitNow() {
        callback?.invoke(buildLocation())
    }

    private fun updatePosition() {
        if (speedKnots == 0f) return
        val speedMps  = speedKnots * 1852.0 / 3600.0
        val bearingRad = Math.toRadians(bearingDeg.toDouble())
        currentLat += speedMps * cos(bearingRad) / 111_111.0
        currentLon += speedMps * sin(bearingRad) / (111_111.0 * cos(Math.toRadians(currentLat)))
    }

    private fun buildLocation(): Location = Location("simulated").apply {
        latitude  = currentLat
        longitude = currentLon
        speed     = (speedKnots * 1852.0 / 3600.0).toFloat()
        bearing   = bearingDeg
        accuracy  = 3f
        time      = System.currentTimeMillis()
    }
}
