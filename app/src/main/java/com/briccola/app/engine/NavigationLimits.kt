package com.briccola.app.engine

import android.content.Context

/**
 * Limiti legali di distanza dalla costa in mare aperto, secondo la normativa italiana sulla
 * navigazione da diporto: la distanza massima consentita è il MINIMO tra quanto permette la
 * patente nautica e quanto permettono le dotazioni di sicurezza a bordo — impostabili in
 * Impostazioni > Profilo Barca (spinner "Patente Nautica"/"Dotazioni di Sicurezza").
 */
object NavigationLimits {
    private const val PREFS_NAME = "laguna_prefs"
    private const val KEY_LICENSE_IDX = "boat_license_idx"
    private const val KEY_EQUIPMENT_IDX = "boat_equipment_idx"

    // Metri equivalenti alle miglia nautiche degli spinner in Settings (license_*/equip_* in
    // strings.xml), nello stesso ordine delle voci. Double.MAX_VALUE = nessun limite.
    private val LICENSE_LIMITS_M   = doubleArrayOf(11_112.0, 22_224.0, Double.MAX_VALUE)
    private val EQUIPMENT_LIMITS_M = doubleArrayOf(1_852.0, 5_556.0, 11_112.0, 22_224.0, 92_600.0, Double.MAX_VALUE)

    fun getLicenseIndex(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_LICENSE_IDX, 0)

    fun setLicenseIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_LICENSE_IDX, index).apply()
    }

    fun getEquipmentIndex(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_EQUIPMENT_IDX, 0)

    fun setEquipmentIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(KEY_EQUIPMENT_IDX, index).apply()
    }

    /** Distanza massima dalla costa consentita, in metri: il minimo tra patente e dotazioni. */
    fun maxDistanceMeters(context: Context): Double {
        val license   = LICENSE_LIMITS_M.getOrElse(getLicenseIndex(context)) { LICENSE_LIMITS_M[0] }
        val equipment = EQUIPMENT_LIMITS_M.getOrElse(getEquipmentIndex(context)) { EQUIPMENT_LIMITS_M[0] }
        return minOf(license, equipment)
    }
}
