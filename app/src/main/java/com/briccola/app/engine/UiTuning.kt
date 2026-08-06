package com.briccola.app.engine

import android.content.Context

/**
 * Posizione/dimensione di alcuni elementi UI della Mappa, regolabili a runtime dal pannello
 * "Settaggi Dev" in Dev Tools (stesso principio di CameraTuning). Persistiti in SharedPreferences.
 */
object UiTuning {
    private const val PREFS_NAME = "laguna_prefs"
    private const val KEY_GAUGE_SCALE         = "ui_gauge_scale"
    private const val KEY_GAUGE_OFFSET_Y      = "ui_gauge_offset_y_dp"
    private const val KEY_FOLLOW_BTN_OFFSET_Y = "ui_follow_btn_offset_y_dp"
    private const val KEY_FOLLOW_BTN_OFFSET_X = "ui_follow_btn_offset_x_dp"
    private const val KEY_FOLLOW_BTN_SCALE    = "ui_follow_btn_scale"
    private const val KEY_MAP_OBJECT_SCALE    = "ui_map_object_scale"
    private const val KEY_HUD_OFFSET_Y        = "ui_hud_offset_y_dp"
    private const val KEY_GAUGE_STACK_OFFSET  = "ui_gauge_stack_offset_dp"
    private const val KEY_SAVED_PLACE_SCALE   = "ui_saved_place_scale"
    private const val KEY_SAVE_PLACE_BTN_SCALE = "ui_save_place_btn_scale"
    private const val KEY_SAVE_PLACE_TEXT_SCALE = "ui_save_place_text_scale"
    private const val KEY_DELETE_PLACE_BTN_SCALE = "ui_delete_place_btn_scale"
    private const val KEY_FOLLOW_BOAT_SCREEN_Y   = "ui_follow_boat_screen_y_fraction"
    private const val KEY_BRICCOLE_COLOR         = "ui_briccole_color"
    private const val KEY_COMPASS_SCALE          = "ui_compass_scale"

    const val DEFAULT_GAUGE_SCALE         = 0.72f  // tachimetro/altimetro un po' più piccoli
    const val DEFAULT_GAUGE_OFFSET_Y      = -78f   // e un po' più in alto (negativo = su)
    const val DEFAULT_FOLLOW_BTN_OFFSET_Y = -103f
    const val DEFAULT_FOLLOW_BTN_OFFSET_X = -21f
    const val DEFAULT_FOLLOW_BTN_SCALE    = 1.53f
    const val DEFAULT_MAP_OBJECT_SCALE    = 1.4f   // dimensione icona barca sulla mappa
    const val DEFAULT_HUD_OFFSET_Y        = 30f
    const val DEFAULT_GAUGE_STACK_OFFSET  = 88f    // distanza altimetro sopra il tachimetro
    const val DEFAULT_SAVED_PLACE_SCALE   = 1.3f   // dimensione pallini luoghi salvati sulla mappa
    const val DEFAULT_SAVE_PLACE_BTN_SCALE = 1.0f
    const val DEFAULT_SAVE_PLACE_TEXT_SCALE = 1.0f
    // Scala del pulsante Elimina, SEPARATA da savePlaceBtnScale (che riguarda Itinerari/Salva):
    // il pulsante Elimina è circolare (FAB) e spesso serve tararlo diverso dagli altri due.
    const val DEFAULT_DELETE_PLACE_BTN_SCALE = 0.8f
    // Posizione verticale della barca sullo schermo in modalità Segui, come frazione dall'alto:
    // non il centro esatto (0.5) — lascia più mappa visibile davanti alla direzione di marcia
    // rispetto a quella alle spalle. Valore confermato dall'utente come corretto.
    const val DEFAULT_FOLLOW_BOAT_SCREEN_Y_FRACTION = 0.7f
    // Colore delle briccole, regolabile da Dev Tools > Colori Mappa.
    val DEFAULT_BRICCOLE_COLOR: Int       = android.graphics.Color.parseColor("#003366")
    const val DEFAULT_COMPASS_SCALE       = 0.6f

    // Tachimetro e altimetro sono specchiati (stessa dimensione/posizione, solo lato opposto):
    // un solo slider per ciascuno basta per entrambi.
    var gaugeScale: Float          = DEFAULT_GAUGE_SCALE
    var gaugeOffsetYDp: Float      = DEFAULT_GAUGE_OFFSET_Y
    var followBtnOffsetYDp: Float  = DEFAULT_FOLLOW_BTN_OFFSET_Y
    var followBtnOffsetXDp: Float  = DEFAULT_FOLLOW_BTN_OFFSET_X
    var followBtnScale: Float      = DEFAULT_FOLLOW_BTN_SCALE
    var mapObjectScale: Float      = DEFAULT_MAP_OBJECT_SCALE
    var hudOffsetYDp: Float        = DEFAULT_HUD_OFFSET_Y
    // Altimetro impilato SOPRA il tachimetro (stessa X): quanto più in alto rispetto ad esso.
    var gaugeStackOffsetDp: Float  = DEFAULT_GAUGE_STACK_OFFSET
    var savedPlaceScale: Float     = DEFAULT_SAVED_PLACE_SCALE
    var savePlaceBtnScale: Float   = DEFAULT_SAVE_PLACE_BTN_SCALE
    var savePlaceTextScale: Float  = DEFAULT_SAVE_PLACE_TEXT_SCALE
    var deletePlaceBtnScale: Float = DEFAULT_DELETE_PLACE_BTN_SCALE
    var followBoatScreenYFraction: Float = DEFAULT_FOLLOW_BOAT_SCREEN_Y_FRACTION
    var briccoleColor: Int = DEFAULT_BRICCOLE_COLOR
    var compassScale: Float = DEFAULT_COMPASS_SCALE

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        gaugeScale         = p.getFloat(KEY_GAUGE_SCALE, gaugeScale)
        gaugeOffsetYDp     = p.getFloat(KEY_GAUGE_OFFSET_Y, gaugeOffsetYDp)
        followBtnOffsetYDp = p.getFloat(KEY_FOLLOW_BTN_OFFSET_Y, followBtnOffsetYDp)
        followBtnOffsetXDp = p.getFloat(KEY_FOLLOW_BTN_OFFSET_X, followBtnOffsetXDp)
        followBtnScale     = p.getFloat(KEY_FOLLOW_BTN_SCALE, followBtnScale)
        mapObjectScale     = p.getFloat(KEY_MAP_OBJECT_SCALE, mapObjectScale)
        hudOffsetYDp       = p.getFloat(KEY_HUD_OFFSET_Y, hudOffsetYDp)
        gaugeStackOffsetDp = p.getFloat(KEY_GAUGE_STACK_OFFSET, gaugeStackOffsetDp)
        savedPlaceScale    = p.getFloat(KEY_SAVED_PLACE_SCALE, savedPlaceScale)
        savePlaceBtnScale  = p.getFloat(KEY_SAVE_PLACE_BTN_SCALE, savePlaceBtnScale)
        savePlaceTextScale = p.getFloat(KEY_SAVE_PLACE_TEXT_SCALE, savePlaceTextScale)
        deletePlaceBtnScale = p.getFloat(KEY_DELETE_PLACE_BTN_SCALE, deletePlaceBtnScale)
        followBoatScreenYFraction = p.getFloat(KEY_FOLLOW_BOAT_SCREEN_Y, followBoatScreenYFraction)
        briccoleColor = p.getInt(KEY_BRICCOLE_COLOR, briccoleColor)
        compassScale = p.getFloat(KEY_COMPASS_SCALE, compassScale)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putFloat(KEY_GAUGE_SCALE, gaugeScale)
            putFloat(KEY_GAUGE_OFFSET_Y, gaugeOffsetYDp)
            putFloat(KEY_FOLLOW_BTN_OFFSET_Y, followBtnOffsetYDp)
            putFloat(KEY_FOLLOW_BTN_OFFSET_X, followBtnOffsetXDp)
            putFloat(KEY_FOLLOW_BTN_SCALE, followBtnScale)
            putFloat(KEY_MAP_OBJECT_SCALE, mapObjectScale)
            putFloat(KEY_HUD_OFFSET_Y, hudOffsetYDp)
            putFloat(KEY_GAUGE_STACK_OFFSET, gaugeStackOffsetDp)
            putFloat(KEY_SAVED_PLACE_SCALE, savedPlaceScale)
            putFloat(KEY_SAVE_PLACE_BTN_SCALE, savePlaceBtnScale)
            putFloat(KEY_SAVE_PLACE_TEXT_SCALE, savePlaceTextScale)
            putFloat(KEY_DELETE_PLACE_BTN_SCALE, deletePlaceBtnScale)
            putFloat(KEY_FOLLOW_BOAT_SCREEN_Y, followBoatScreenYFraction)
            putInt(KEY_BRICCOLE_COLOR, briccoleColor)
            putFloat(KEY_COMPASS_SCALE, compassScale)
            apply()
        }
    }

    fun resetToDefaults(context: Context) {
        gaugeScale         = DEFAULT_GAUGE_SCALE
        gaugeOffsetYDp     = DEFAULT_GAUGE_OFFSET_Y
        followBtnOffsetYDp = DEFAULT_FOLLOW_BTN_OFFSET_Y
        followBtnOffsetXDp = DEFAULT_FOLLOW_BTN_OFFSET_X
        followBtnScale     = DEFAULT_FOLLOW_BTN_SCALE
        mapObjectScale     = DEFAULT_MAP_OBJECT_SCALE
        hudOffsetYDp       = DEFAULT_HUD_OFFSET_Y
        gaugeStackOffsetDp = DEFAULT_GAUGE_STACK_OFFSET
        savedPlaceScale    = DEFAULT_SAVED_PLACE_SCALE
        savePlaceBtnScale  = DEFAULT_SAVE_PLACE_BTN_SCALE
        savePlaceTextScale = DEFAULT_SAVE_PLACE_TEXT_SCALE
        deletePlaceBtnScale = DEFAULT_DELETE_PLACE_BTN_SCALE
        followBoatScreenYFraction = DEFAULT_FOLLOW_BOAT_SCREEN_Y_FRACTION
        briccoleColor = DEFAULT_BRICCOLE_COLOR
        compassScale = DEFAULT_COMPASS_SCALE
        save(context)
    }
}
