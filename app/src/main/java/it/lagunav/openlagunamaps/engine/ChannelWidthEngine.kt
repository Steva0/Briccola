package it.lagunav.openlagunamaps.engine

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sqrt

data class ChannelWidthPoint(val lat: Double, val lon: Double, val leftM: Float, val rightM: Float)
private data class Channel(val id: String, val points: List<ChannelWidthPoint>)

/**
 * Larghezza reale d'acqua disponibile per lato lungo ogni canale (precalcolata dalla pipeline
 * Python campionando la batimetria perpendicolarmente, vedi calcola_larghezza_canali.py — troppo
 * costoso da fare a runtime). L'app applica solo min(datoPrecalcolato, capCorrente) per costruire
 * il poligono "a nastro" del canale, così il cap è regolabile dallo slider Dev Tools senza dover
 * rigenerare l'asset.
 */
object ChannelWidthEngine {
    private var channels: List<Channel> = emptyList()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val text = context.assets.open("canali_larghi.json").bufferedReader().readText()
            val json = JSONObject(text)
            val arr = json.getJSONArray("canali")
            val list = ArrayList<Channel>(arr.length())
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val pointsArr = c.getJSONArray("points")
                val points = ArrayList<ChannelWidthPoint>(pointsArr.length())
                for (j in 0 until pointsArr.length()) {
                    val p = pointsArr.getJSONObject(j)
                    points += ChannelWidthPoint(
                        lat = p.getDouble("lat"), lon = p.getDouble("lon"),
                        leftM = p.getDouble("left_m").toFloat(), rightM = p.getDouble("right_m").toFloat()
                    )
                }
                list += Channel(c.getString("id"), points)
            }
            channels = list
        } catch (_: Exception) {
            channels = emptyList()
        }
    }

    /** GeoJSON (stringa) pronto per una GeoJsonSource: un poligono per canale, largo per lato
     *  min(larghezza reale precalcolata, maxWidthM) ma non meno di minWidthM — senza un minimo,
     *  i tratti dove la batimetria non dà spazio (es. rii stretti tra due rive, o dati mancanti)
     *  collasserebbero a una linea invisibile invece che restare un canale sottile ma visibile. */
    fun buildRibbonPolygons(maxWidthM: Float, minWidthM: Float): String {
        val features = JsonArray()
        channels.forEach { channel ->
            if (channel.points.size < 2) return@forEach
            val ring = buildRing(channel.points, maxWidthM, minWidthM)
            val coords = JsonArray()
            ring.forEach { (lon, lat) ->
                coords.add(JsonArray().apply { add(lon); add(lat) })
            }
            val polygonCoords = JsonArray().apply { add(coords) }
            val geometry = JsonObject().apply {
                addProperty("type", "Polygon")
                add("coordinates", polygonCoords)
            }
            val feature = JsonObject().apply {
                addProperty("type", "Feature")
                add("properties", JsonObject())
                add("geometry", geometry)
            }
            features.add(feature)
        }
        return JsonObject().apply {
            addProperty("type", "FeatureCollection")
            add("features", features)
        }.toString()
    }

    /** Costruisce l'anello (lon,lat) del poligono a nastro: bordo sinistro in avanti + bordo
     *  destro invertito all'indietro, chiuso sul primo punto sinistro. Tangente/perpendicolare
     *  calcolate in un piano metrico locale approssimato (equirettangolare, accettabile alla
     *  scala della laguna e con punti già ravvicinati dal ricampionamento a passo fisso). */
    private fun buildRing(points: List<ChannelWidthPoint>, maxWidthM: Float, minWidthM: Float): List<Pair<Double, Double>> {
        val lat0 = points[points.size / 2].lat
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat0))

        val localX = DoubleArray(points.size) { points[it].lon * mPerDegLon }
        val localY = DoubleArray(points.size) { points[it].lat * mPerDegLat }

        val left = ArrayList<Pair<Double, Double>>(points.size)
        val right = ArrayList<Pair<Double, Double>>(points.size)

        for (i in points.indices) {
            val px = localX[i]; val py = localY[i]
            val (tx, ty) = when (i) {
                0 -> (localX[i + 1] - px) to (localY[i + 1] - py)
                points.size - 1 -> (px - localX[i - 1]) to (py - localY[i - 1])
                else -> (localX[i + 1] - localX[i - 1]) to (localY[i + 1] - localY[i - 1])
            }
            val norm = sqrt(tx * tx + ty * ty).takeIf { it > 0.0 } ?: 1.0
            val ux = tx / norm; val uy = ty / norm
            val perpLx = -uy; val perpLy = ux   // perpendicolare sinistra
            val perpRx = uy; val perpRy = -ux    // perpendicolare destra

            val leftEff = min(points[i].leftM, maxWidthM).coerceAtLeast(minWidthM).toDouble()
            val rightEff = min(points[i].rightM, maxWidthM).coerceAtLeast(minWidthM).toDouble()

            val lx = px + perpLx * leftEff; val ly = py + perpLy * leftEff
            val rx = px + perpRx * rightEff; val ry = py + perpRy * rightEff

            left += (lx / mPerDegLon) to (ly / mPerDegLat)   // (lon, lat)
            right += (rx / mPerDegLon) to (ry / mPerDegLat)
        }

        val ring = ArrayList<Pair<Double, Double>>(left.size * 2 + 1)
        ring.addAll(left)
        ring.addAll(right.asReversed())
        ring.add(left.first())
        return ring
    }
}
