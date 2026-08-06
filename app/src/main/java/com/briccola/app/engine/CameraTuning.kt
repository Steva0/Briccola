package com.briccola.app.engine

import android.content.Context
import kotlin.math.roundToLong

/**
 * Parametri regolabili a runtime dal pannello "Settaggi Dev" in Dev Tools, per trovare i valori
 * ideali testando dal vivo (es. guidando in auto) senza dover ricompilare ogni volta.
 * Persistiti in SharedPreferences: restano tra un riavvio e l'altro.
 */
object CameraTuning {
    private const val PREFS_NAME = "laguna_prefs"
    private const val KEY_RENDER_DELAY_MS      = "tune_render_delay_ms"
    private const val KEY_MIN_BEARING_DISP_M   = "tune_min_bearing_disp_m"
    private const val KEY_ICON_BEARING_LERP    = "tune_icon_bearing_lerp"
    private const val KEY_CAM_DEAD_ZONE_DEG    = "tune_cam_dead_zone_deg"
    private const val KEY_CAM_LERP             = "tune_cam_lerp"
    private const val KEY_FRAME_INTERVAL_MS    = "tune_frame_interval_ms"
    private const val KEY_HUD_INTERVAL_MS      = "tune_hud_interval_ms"
    private const val KEY_HUD_SPEED_LINKED     = "tune_hud_speed_linked"
    private const val KEY_CANAL_LABEL_THRESHOLD_M = "tune_canal_label_threshold_m"
    private const val KEY_RECENTER_ZOOM            = "tune_recenter_ideal_zoom"

    const val DEFAULT_RENDER_DELAY_MS          = 1200L
    const val DEFAULT_MIN_BEARING_DISP_M       = 1.5
    const val DEFAULT_ICON_BEARING_LERP        = 0.08
    const val DEFAULT_CAM_DEAD_ZONE_DEG        = 6.0    // per lato -> 12° totali
    const val DEFAULT_CAM_LERP                 = 0.04
    const val DEFAULT_FRAME_INTERVAL_MS        = 22L    // ~45 fps
    const val DEFAULT_HUD_INTERVAL_MS          = 200L   // 5 Hz per profondità/velocità/canale
    const val DEFAULT_HUD_SPEED_LINKED         = true
    const val DEFAULT_CANAL_LABEL_THRESHOLD_M  = 100.0  // oltre: HUD mostra "Fuori canale"
    // Bottone CENTRA "intelligente": se lo zoom attuale è già uguale o superiore a
    // RECENTER_ZOOM (= sei più vicino della distanza x), resta invariato e centra solo sulla
    // barca; se sei più lontano di x, zooma fino a x. Un solo valore fa sia da soglia che da
    // target — niente "zona morta" fra due valori separati.
    // Range utile in navigazione: sotto zoom ~10 si vede a malapena la laguna, sopra ~18 sei a
    // livello "singolo edificio".
    const val RECENTER_ZOOM_MIN     = 10.0
    const val RECENTER_ZOOM_MAX     = 18.0
    const val DEFAULT_RECENTER_ZOOM = 17.0

    var renderDelayMs: Long          = DEFAULT_RENDER_DELAY_MS
    var minBearingDisplacementM: Double = DEFAULT_MIN_BEARING_DISP_M
    var iconBearingLerp: Double      = DEFAULT_ICON_BEARING_LERP
    var camDeadZoneDeg: Double       = DEFAULT_CAM_DEAD_ZONE_DEG
    var camLerp: Double              = DEFAULT_CAM_LERP
    var frameIntervalMs: Long        = DEFAULT_FRAME_INTERVAL_MS
    var hudIntervalMs: Long          = DEFAULT_HUD_INTERVAL_MS
    var hudRefreshLinkedToSpeed: Boolean = DEFAULT_HUD_SPEED_LINKED
    var canalLabelThresholdM: Double = DEFAULT_CANAL_LABEL_THRESHOLD_M
    var recenterZoom: Double = DEFAULT_RECENTER_ZOOM

    // Curva (lineare) velocità -> refresh HUD, usata solo se hudRefreshLinkedToSpeed=true.
    // Sotto HUD_SPEED_MIN_KN: refresh minimo (poco interessa aggiornare tanto da fermi).
    // Sopra HUD_SPEED_MAX_KN: refresh massimo (ad alta velocità serve la lettura più fresca).
    const val HUD_SPEED_MIN_KN = 3.0
    const val HUD_SPEED_MAX_KN = 15.0
    const val HUD_REFRESH_MIN_HZ = 5.0
    const val HUD_REFRESH_MAX_HZ = 20.0

    /** Intervallo HUD effettivo: se collegato alla velocità, interpola linearmente tra
     *  HUD_REFRESH_MIN_HZ (sotto HUD_SPEED_MIN_KN) e HUD_REFRESH_MAX_HZ (sopra HUD_SPEED_MAX_KN);
     *  altrimenti usa il valore fisso impostato dallo slider (hudIntervalMs). */
    fun hudIntervalMsForSpeed(speedKn: Double): Long {
        if (!hudRefreshLinkedToSpeed) return hudIntervalMs
        val t = ((speedKn - HUD_SPEED_MIN_KN) / (HUD_SPEED_MAX_KN - HUD_SPEED_MIN_KN)).coerceIn(0.0, 1.0)
        val hz = HUD_REFRESH_MIN_HZ + (HUD_REFRESH_MAX_HZ - HUD_REFRESH_MIN_HZ) * t
        return (1000.0 / hz).roundToLong()
    }

    /** Il buffer dei fix deve coprire almeno il ritardo di rendering + un margine di sicurezza. */
    val fixBufferMaxMs: Long get() = renderDelayMs + 3000L

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        renderDelayMs           = p.getInt(KEY_RENDER_DELAY_MS, renderDelayMs.toInt()).toLong()
        minBearingDisplacementM = p.getFloat(KEY_MIN_BEARING_DISP_M, minBearingDisplacementM.toFloat()).toDouble()
        iconBearingLerp         = p.getFloat(KEY_ICON_BEARING_LERP, iconBearingLerp.toFloat()).toDouble()
        camDeadZoneDeg          = p.getFloat(KEY_CAM_DEAD_ZONE_DEG, camDeadZoneDeg.toFloat()).toDouble()
        camLerp                 = p.getFloat(KEY_CAM_LERP, camLerp.toFloat()).toDouble()
        frameIntervalMs         = p.getInt(KEY_FRAME_INTERVAL_MS, frameIntervalMs.toInt()).toLong()
        hudIntervalMs           = p.getInt(KEY_HUD_INTERVAL_MS, hudIntervalMs.toInt()).toLong()
        hudRefreshLinkedToSpeed = p.getBoolean(KEY_HUD_SPEED_LINKED, hudRefreshLinkedToSpeed)
        canalLabelThresholdM    = p.getFloat(KEY_CANAL_LABEL_THRESHOLD_M, canalLabelThresholdM.toFloat()).toDouble()
        recenterZoom            = p.getFloat(KEY_RECENTER_ZOOM, recenterZoom.toFloat()).toDouble()
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putInt(KEY_RENDER_DELAY_MS, renderDelayMs.toInt())
            putFloat(KEY_MIN_BEARING_DISP_M, minBearingDisplacementM.toFloat())
            putFloat(KEY_ICON_BEARING_LERP, iconBearingLerp.toFloat())
            putFloat(KEY_CAM_DEAD_ZONE_DEG, camDeadZoneDeg.toFloat())
            putFloat(KEY_CAM_LERP, camLerp.toFloat())
            putInt(KEY_FRAME_INTERVAL_MS, frameIntervalMs.toInt())
            putInt(KEY_HUD_INTERVAL_MS, hudIntervalMs.toInt())
            putBoolean(KEY_HUD_SPEED_LINKED, hudRefreshLinkedToSpeed)
            putFloat(KEY_CANAL_LABEL_THRESHOLD_M, canalLabelThresholdM.toFloat())
            putFloat(KEY_RECENTER_ZOOM, recenterZoom.toFloat())
            apply()
        }
    }

    fun resetToDefaults(context: Context) {
        renderDelayMs           = DEFAULT_RENDER_DELAY_MS
        minBearingDisplacementM = DEFAULT_MIN_BEARING_DISP_M
        iconBearingLerp         = DEFAULT_ICON_BEARING_LERP
        camDeadZoneDeg          = DEFAULT_CAM_DEAD_ZONE_DEG
        camLerp                 = DEFAULT_CAM_LERP
        frameIntervalMs         = DEFAULT_FRAME_INTERVAL_MS
        hudIntervalMs           = DEFAULT_HUD_INTERVAL_MS
        hudRefreshLinkedToSpeed = DEFAULT_HUD_SPEED_LINKED
        canalLabelThresholdM    = DEFAULT_CANAL_LABEL_THRESHOLD_M
        recenterZoom            = DEFAULT_RECENTER_ZOOM
        save(context)
    }
}
