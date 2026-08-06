package com.briccola.app.engine

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maplibre.android.geometry.LatLng
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Motore ottimizzato per la lettura della batimetria.
 */
class BathymetryEngine(private val context: Context) {

    private var metaWidth: Int = 0
    private var metaHeight: Int = 0
    private var minLon: Double = 0.0
    private var maxLat: Double = 0.0
    private var resLon: Double = 0.0
    private var resLat: Double = 0.0

    private var bathyData: ByteBuffer? = null

    init {
        loadMetadata()
        loadBinaryData()
    }

    private fun loadMetadata() {
        try {
            val jsonString = context.assets.open("bathymetry_meta.json").bufferedReader().use { it.readText() }
            val json = Json.parseToJsonElement(jsonString).jsonObject
            
            metaWidth = json["width"]?.jsonPrimitive?.content?.toInt() ?: 0
            metaHeight = json["height"]?.jsonPrimitive?.content?.toInt() ?: 0
            minLon = json["min_lon"]?.jsonPrimitive?.content?.toDouble() ?: 0.0
            maxLat = json["max_lat"]?.jsonPrimitive?.content?.toDouble() ?: 0.0
            resLon = json["res_lon"]?.jsonPrimitive?.content?.toDouble() ?: 0.0
            resLat = json["res_lat"]?.jsonPrimitive?.content?.toDouble() ?: 0.0
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadBinaryData() {
        try {
            context.assets.open("bathymetry.bin").use { input ->
                val bytes = input.readBytes()
                bathyData = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Restituisce la profondità in metri.
     * Logica:
     * - Valore reale dal TIF
     * - 0.0m se fuori dai limiti o in zona terraferma
     */
    fun getDepthAt(lat: Double, lon: Double, noGoAreas: List<NoGoArea>): Float {
        val p = LatLng(lat, lon)
        
        // 1. Controllo zone proibite: se siamo "sopra" un molo o isola, l'acqua è 0
        if (noGoAreas.any { containsPoint(it.polygon, p) }) return 0f

        if (metaWidth == 0 || metaHeight == 0 || bathyData == null) return 0f

        val x = ((lon - minLon) / resLon).toInt()
        val y = ((lat - maxLat) / resLat).toInt()

        if (x !in 0 until metaWidth || y !in 0 until metaHeight) return 0f

        val position = (y * metaWidth + x) * 2
        
        return try {
            val depthCm = bathyData!!.getShort(position)
            val depth = depthCm.toFloat() / 100f
            if (depth <= 0.05f) 0f else depth
        } catch (e: Exception) {
            0f
        }
    }

    private fun containsPoint(poly: List<LatLng>, p: LatLng): Boolean {
        var res = false; var j = poly.size - 1
        for (i in poly.indices) {
            if (((poly[i].latitude > p.latitude) != (poly[j].latitude > p.latitude)) &&
                (p.longitude < (poly[j].longitude - poly[i].longitude) * (p.latitude - poly[i].latitude) / (poly[j].latitude - poly[i].latitude) + poly[i].longitude)) res = !res
            j = i
        }
        return res
    }
}
