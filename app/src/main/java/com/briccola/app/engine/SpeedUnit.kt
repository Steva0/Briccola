package com.briccola.app.engine

import android.content.Context

/** Unità di velocità mostrata in HUD/tachimetro, scelta dall'utente nelle Impostazioni. */
enum class SpeedUnit(val label: String, private val factorFromMps: Double, val gaugeMax: Float) {
    KMH("km/h", 3.6, 70f),
    KNOTS("nodi", 1.9438445, 38f),
    MPH("mph", 2.2369363, 45f);

    fun fromMps(speedMps: Double): Double = speedMps * factorFromMps

    /** getMaxSpeedKnotsAt() del motore di routing restituisce sempre nodi: conversione comoda. */
    fun fromKnots(knots: Double): Double = fromMps(knots * 0.5144444)

    companion object {
        private const val PREFS_NAME = "laguna_prefs"
        private const val KEY_SPEED_UNIT = "speed_unit"

        fun get(context: Context): SpeedUnit {
            val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SPEED_UNIT, KMH.name)
            return runCatching { valueOf(name ?: KMH.name) }.getOrDefault(KMH)
        }

        fun set(context: Context, unit: SpeedUnit) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_SPEED_UNIT, unit.name).apply()
        }
    }
}
