package it.lagunav.openlagunamaps.engine

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.json.JSONObject
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.operation.union.CascadedPolygonUnion
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
    private val geometryFactory = GeometryFactory()

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
     *  collasserebbero a una linea invisibile invece che restare un canale sottile ma visibile.
     *
     *  I poligoni dei canali vicini spesso si toccano/sovrappongono (incroci, rii paralleli):
     *  con un riempimento semi-trasparente, poligoni sovrapposti separati sommerebbero l'alpha
     *  proprio lì (effetto "evidenziatore" più scuro). Si fa quindi l'UNION geometrico di tutti
     *  i poligoni (JTS) prima di esportarli: il risultato copre la stessa area ma senza zone
     *  doppie, quindi la trasparenza resta uniforme ovunque. */
    fun buildRibbonPolygons(maxWidthM: Float, minWidthM: Float): String {
        val rawPolygons = ArrayList<Polygon>(channels.size)
        channels.forEach { channel ->
            if (channel.points.size < 2) return@forEach
            val ring = buildRing(channel.points, maxWidthM, minWidthM)
            buildValidPolygon(ring)?.let { flattenPolygons(it, rawPolygons) }
        }

        val unionedPolygons = ArrayList<Polygon>(rawPolygons.size)
        try {
            flattenPolygons(CascadedPolygonUnion(rawPolygons).union(), unionedPolygons)
        } catch (_: Exception) {
            // Se l'union fallisce per qualche geometria degenere, meglio mostrare i poligoni
            // separati (col difetto noto agli incroci) che non mostrare nulla.
            unionedPolygons.addAll(rawPolygons)
        }

        val features = JsonArray()
        unionedPolygons.forEach { features.add(polygonToFeature(it)) }
        return JsonObject().apply {
            addProperty("type", "FeatureCollection")
            add("features", features)
        }.toString()
    }

    /** Costruisce un Polygon JTS valido dall'anello: buffer(0) "sana" le auto-intersezioni minori
     *  (frequenti quando left/right collassano quasi a zero in un tratto strettissimo), tecnica
     *  standard in JTS invece di scartare la geometria. */
    private fun buildValidPolygon(ring: List<Pair<Double, Double>>): Geometry? {
        if (ring.size < 4) return null
        return try {
            val coords = ring.map { Coordinate(it.first, it.second) }.toMutableList()
            if (coords.first() != coords.last()) coords.add(Coordinate(coords.first()))
            val poly = geometryFactory.createPolygon(coords.toTypedArray())
            if (poly.isValid) poly else poly.buffer(0.0)
        } catch (_: Exception) {
            null
        }
    }

    private fun flattenPolygons(geom: Geometry, out: MutableList<Polygon>) {
        for (i in 0 until geom.numGeometries) {
            val g = geom.getGeometryN(i)
            if (g is Polygon) { if (!g.isEmpty) out.add(g) } else flattenPolygons(g, out)
        }
    }

    private fun polygonToFeature(poly: Polygon): JsonObject {
        fun ringToCoords(coords: Array<Coordinate>): JsonArray {
            val arr = JsonArray()
            coords.forEach { c -> arr.add(JsonArray().apply { add(c.x); add(c.y) }) }
            return arr
        }
        val rings = JsonArray().apply {
            add(ringToCoords(poly.exteriorRing.coordinates))
            for (i in 0 until poly.numInteriorRing) add(ringToCoords(poly.getInteriorRingN(i).coordinates))
        }
        val geometry = JsonObject().apply {
            addProperty("type", "Polygon")
            add("coordinates", rings)
        }
        return JsonObject().apply {
            addProperty("type", "Feature")
            add("properties", JsonObject())
            add("geometry", geometry)
        }
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
