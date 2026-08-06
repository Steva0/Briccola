package com.briccola.app.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng

/** Categoria di un luogo salvato, con icona e nome per la UI (schermata "Salva luogo"). */
enum class PlaceType(val icon: String, val label: String) {
    BERTH("⚓", "Posto barca"),
    FAVORITE("♥", "Preferito"),
    TO_VISIT("🚩", "Da visitare"),
    GENERIC("📍", "Luogo")
}

data class SavedPlace(
    val name: String,
    val lat: Double,
    val lon: Double,
    val type: PlaceType = PlaceType.GENERIC,
    val timestamp: Long = 0L,
    val notes: String = ""
)

fun SavedPlace.toLatLng() = LatLng(lat, lon)

/**
 * Persistenza dei luoghi: quelli salvati manualmente dall'utente (con nome e categoria a icona —
 * ancora/cuore/bandiera/generico) e la cronologia automatica delle destinazioni navigate di
 * recente. Tutto in SharedPreferences come JSON — liste piccole, non serve altro.
 */
object PlacesStore {
    private const val PREFS_NAME = "laguna_prefs"
    private const val KEY_SAVED = "place_saved"
    private const val KEY_RECENTS = "place_recents"
    private const val MAX_RECENTS = 10

    // Due punti considerati "lo stesso posto" ai fini di deduplica.
    private const val SAME_PLACE_DEG = 0.0005  // ~50m

    fun getSaved(context: Context): List<SavedPlace> = readList(context, KEY_SAVED)

    /** Aggiunge o aggiorna (per posizione) un luogo salvato manualmente. */
    fun addSaved(context: Context, place: SavedPlace) {
        val list = getSaved(context).toMutableList()
        list.removeAll { samePlace(it, place) }
        list.add(0, place)
        writeList(context, KEY_SAVED, list)
    }

    fun removeSaved(context: Context, place: SavedPlace) {
        val list = getSaved(context).toMutableList()
        list.removeAll { samePlace(it, place) }
        writeList(context, KEY_SAVED, list)
    }

    fun getRecents(context: Context): List<SavedPlace> = readList(context, KEY_RECENTS)

    /** Da chiamare quando una navigazione viene DAVVERO avviata (non solo pianificata). */
    fun addRecent(context: Context, place: SavedPlace) {
        val list = getRecents(context).toMutableList()
        list.removeAll { samePlace(it, place) }
        list.add(0, place.copy(timestamp = System.currentTimeMillis()))
        writeList(context, KEY_RECENTS, list.take(MAX_RECENTS))
    }

    private fun samePlace(a: SavedPlace, b: SavedPlace): Boolean =
        Math.abs(a.lat - b.lat) < SAME_PLACE_DEG && Math.abs(a.lon - b.lon) < SAME_PLACE_DEG

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readList(context: Context, key: String): List<SavedPlace> {
        val json = prefs(context).getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun writeList(context: Context, key: String, list: List<SavedPlace>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        prefs(context).edit().putString(key, arr.toString()).apply()
    }

    private fun toJson(p: SavedPlace) = JSONObject().apply {
        put("name", p.name); put("lat", p.lat); put("lon", p.lon)
        put("type", p.type.name); put("timestamp", p.timestamp)
        put("notes", p.notes)
    }

    private fun fromJson(o: JSONObject) = SavedPlace(
        o.getString("name"), o.getDouble("lat"), o.getDouble("lon"),
        runCatching { 
            val raw = o.optString("type", "GENERIC")
            if (raw == "SPECIAL") PlaceType.TO_VISIT else PlaceType.valueOf(raw) 
        }.getOrDefault(PlaceType.GENERIC),
        o.optLong("timestamp", 0L),
        o.optString("notes", "")
    )
}
