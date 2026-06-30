package it.lagunav.openlagunamaps.engine

import android.content.Context
import kotlinx.serialization.json.*
import org.maplibre.android.geometry.LatLng
import java.util.*
import kotlin.math.*

data class Node(val id: String, val lat: Double, val lon: Double, val isGate: Boolean, val isSeaTip: Boolean = false)
data class Edge(val u: String, val v: String, val length: Double, val depth: Double, val speed: Double)
data class NoGoArea(val id: String, val polygon: List<LatLng>, val isRock: Boolean)
data class BypassRing(val nodes: List<LatLng>)
data class Segment(val p1: LatLng, val p2: LatLng)
data class FixedDepthArea(val depth: Float, val polygon: List<LatLng>)

class RoutingEngine(private val context: Context) {

    private val nodes = mutableMapOf<String, Node>()
    private val adj = mutableMapOf<String, MutableList<Edge>>()
    private val noGoAreas = mutableListOf<NoGoArea>()

    // Strutture per le linee guida di supporto (Bypass)
    private val bbNodes = mutableMapOf<String, Node>()
    private val bbAdj = mutableMapOf<String, MutableList<String>>()
    private val nodeToBypassType = mutableMapOf<String, String>() // "sea" o "rock"

    private val rockBypassRings = mutableListOf<BypassRing>()
    private val seaSegments = mutableListOf<Segment>()
    private val lagunaSegments = mutableListOf<Segment>()
    private val seaEntryNodes = mutableListOf<Node>()
    private val tipToGraphNodeCache = mutableMapOf<String, String>()

    private var projectBoundary: List<LatLng>? = null
    private val fixedDepthAreas = mutableListOf<FixedDepthArea>()
    private val json = Json { ignoreUnknownKeys = true }

    var userAverageSpeedKmH: Double = 30.0
    var lastRoutingError: String = ""

    init {
        loadGraph()
        loadMarkersAndBoundaries()
        precomputeTipGraphNodes()
    }

    private fun precomputeTipGraphNodes() {
        seaEntryNodes.forEach { tip ->
            nodes.values.minByOrNull { haversine(it.lat, it.lon, tip.lat, tip.lon) }?.let {
                tipToGraphNodeCache[tip.id] = it.id
            }
        }
    }

    private fun loadGraph() {
        try {
            val jsonString = context.assets.open("graph.json").bufferedReader().use { it.readText() }
            val root = json.parseToJsonElement(jsonString).jsonObject

            root["nodes"]?.jsonObject?.forEach { (id, element) ->
                val obj = element.jsonObject
                nodes[id] = Node(
                    id = id,
                    lat = obj["lat"]?.jsonPrimitive?.double ?: 0.0,
                    lon = obj["lon"]?.jsonPrimitive?.double ?: 0.0,
                    isGate = obj["gate"]?.jsonPrimitive?.content == "sea"
                )
            }

            root["edges"]?.jsonArray?.forEach { element ->
                val obj = element.jsonObject
                val u = obj["u"]?.jsonPrimitive?.content ?: ""
                val v = obj["v"]?.jsonPrimitive?.content ?: ""
                val length = obj["l"]?.jsonPrimitive?.double ?: 0.0
                val depth = obj["d"]?.jsonPrimitive?.double ?: 0.0
                val speedTag = obj["s"]?.jsonPrimitive?.doubleOrNull ?: 12.0

                val edge = Edge(u, v, length, depth, speedTag * 1.852)
                adj.getOrPut(u) { mutableListOf() }.add(edge)
                adj.getOrPut(v) { mutableListOf() }.add(edge)
            }

            root["no_go_areas"]?.jsonArray?.forEach { element ->
                val obj = element.jsonObject
                val coords = obj["nodes"]?.jsonArray?.map {
                    val c = it.jsonArray
                    LatLng(c[0].jsonPrimitive.double, c[1].jsonPrimitive.double)
                } ?: emptyList()
                val isRock = obj["rock"]?.jsonPrimitive?.boolean ?: false
                if (coords.size > 2) {
                    val polygon = if (coords.first() != coords.last()) coords + coords.first() else coords
                    noGoAreas.add(NoGoArea(obj["id"]?.jsonPrimitive?.content ?: "", polygon, isRock))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadMarkersAndBoundaries() {
        try {
            val jsonString = context.assets.open("laguna_vettoriale.json").bufferedReader().use { it.readText() }
            val root = json.parseToJsonElement(jsonString).jsonObject
            root["features"]?.jsonArray?.forEach { feature ->
                val props = feature.jsonObject["properties"]?.jsonObject ?: return@forEach
                val geom = feature.jsonObject["geometry"]?.jsonObject ?: return@forEach
                val type = geom["type"]?.jsonPrimitive?.content ?: ""
                val coordsArray = geom["coordinates"]?.jsonArray ?: return@forEach

                val lls = when (type) {
                    "Polygon" -> coordsArray[0].jsonArray.map { LatLng(it.jsonArray[1].jsonPrimitive.double, it.jsonArray[0].jsonPrimitive.double) }
                    "LineString" -> coordsArray.map { LatLng(it.jsonArray[1].jsonPrimitive.double, it.jsonArray[0].jsonPrimitive.double) }
                    "Point" -> listOf(LatLng(coordsArray[1].jsonPrimitive.double, coordsArray[0].jsonPrimitive.double))
                    else -> emptyList()
                }
                if (lls.isEmpty()) return@forEach

                val gateValue = props["special:nav:gate"]?.jsonPrimitive?.content
                if (gateValue == "sea_tip" || (gateValue == "sea" && type == "Point")) {
                    val osmId = props["id"]?.jsonPrimitive?.content ?: "tip_${feature.hashCode()}"
                    seaEntryNodes.add(Node(osmId, lls[0].latitude, lls[0].longitude, false, true))
                }

                val isNoGo = props["special:nav:area"]?.jsonPrimitive?.content == "no_go"
                val isRock = props["special:nav:obstacle"]?.jsonPrimitive?.content == "rock" ||
                        props["special:nav:obstade"]?.jsonPrimitive?.content == "rock"

                if ((isNoGo || isRock) && noGoAreas.none { it.polygon.firstOrNull() == lls.firstOrNull() }) {
                    noGoAreas.add(NoGoArea("", lls, isRock))
                }

                if (props["special:nav:area"]?.jsonPrimitive?.content == "depth_fixed") {
                    fixedDepthAreas.add(FixedDepthArea(props["depth"]?.jsonPrimitive?.floatOrNull ?: 12f, lls))
                }

                if (props["special:nav:boundary"]?.jsonPrimitive?.content == "project") {
                    projectBoundary = lls
                }

                val isSeaMarker = props.containsKey("special:mare")
                val isLagunaMarker = props.containsKey("special:laguna")
                val isBypassSea = props["special:nav:bypass"]?.jsonPrimitive?.content == "sea"
                val isBypassRock = props["special:nav:bypass"]?.jsonPrimitive?.content == "rock"

                if (isBypassRock) rockBypassRings.add(BypassRing(lls))

                if (isSeaMarker) for(i in 0 until lls.size-1) seaSegments.add(Segment(lls[i], lls[i+1]))
                if (isLagunaMarker) for(i in 0 until lls.size-1) lagunaSegments.add(Segment(lls[i], lls[i+1]))

                if (isBypassSea || isBypassRock) {
                    val typeStr = if (isBypassRock) "rock" else "sea"
                    val isClosed = lls.size > 2 && lls.first() == lls.last()
                    val wayNodeIds = mutableListOf<String>()
                    lls.forEachIndexed { i, ll ->
                        if (i == lls.size - 1 && isClosed) return@forEachIndexed
                        val id = "bb_${feature.hashCode()}_$i"
                        bbNodes[id] = Node(id, ll.latitude, ll.longitude, false)
                        nodeToBypassType[id] = typeStr
                        wayNodeIds.add(id)
                    }
                    for (i in 0 until wayNodeIds.size - 1) {
                        bbAdj.getOrPut(wayNodeIds[i]) { mutableListOf() }.add(wayNodeIds[i+1])
                        bbAdj.getOrPut(wayNodeIds[i+1]) { mutableListOf() }.add(wayNodeIds[i])
                    }
                    if (isClosed && wayNodeIds.isNotEmpty()) {
                        bbAdj.getOrPut(wayNodeIds.last()) { mutableListOf() }.add(wayNodeIds.first())
                        bbAdj.getOrPut(wayNodeIds.first()) { mutableListOf() }.add(wayNodeIds.last())
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // =================================================================
    // ROUTING CORE INTERFACE
    // =================================================================

    fun findRoute(start: LatLng, end: LatLng, minDepth: Double = 0.5): List<LatLng>? {
        lastRoutingError = ""
        val sSea = isAtSea(start)
        val eSea = isAtSea(end)

        return when {
            // CASO 1: INTERNO LAGUNA
            !sSea && !eSea -> {
                val sId = findNearestGraphNode(start) ?: run { lastRoutingError = "Nodo di partenza non trovato nel grafo"; return null }
                val eId = findNearestGraphNode(end) ?: run { lastRoutingError = "Nodo di arrivo non trovato nel grafo"; return null }
                val pathIds = runCanalAStar(sId, eId, minDepth) ?: run { lastRoutingError = "Nessun canale praticabile (profondità minima: ${minDepth}m)"; return null }

                val path = mutableListOf<LatLng>()
                path.add(start)
                pathIds.forEach { id -> nodes[id]?.let { path.add(LatLng(it.lat, it.lon)) } }
                path.add(end)
                path
            }

            // CASO 2: MARE -> MARE
            sSea && eSea -> {
                solveSeaPath(start, end)
            }

            // CASO 3: MISTO (LAGUNA <-> MARE)
            else -> {
                val seaPoint = if (sSea) start else end
                val lagPoint = if (sSea) end else start
                val lagNodeId = findNearestGraphNode(lagPoint) ?: return null

                val bestTip = seaEntryNodes.minByOrNull { tip ->
                    haversine(seaPoint.latitude, seaPoint.longitude, tip.lat, tip.lon) +
                            haversine(tip.lat, tip.lon, lagPoint.latitude, lagPoint.longitude)
                } ?: return null

                val tipGraphId = tipToGraphNodeCache[bestTip.id] ?: return null

                val canalPathIds = if (sSea) runCanalAStar(tipGraphId, lagNodeId, minDepth)
                else runCanalAStar(lagNodeId, tipGraphId, minDepth)

                if (canalPathIds == null) return null

                val tipLl = LatLng(bestTip.lat, bestTip.lon)
                val seaPart = solveSeaPath(if (sSea) seaPoint else tipLl, if (sSea) tipLl else seaPoint)

                val combined = mutableListOf<LatLng>()
                if (sSea) {
                    combined.addAll(seaPart)
                    canalPathIds.drop(1).forEach { id -> nodes[id]?.let { combined.add(LatLng(it.lat, it.lon)) } }
                    combined.add(end)
                } else {
                    combined.add(start)
                    canalPathIds.forEach { id -> nodes[id]?.let { combined.add(LatLng(it.lat, it.lon)) } }
                    combined.addAll(seaPart.drop(1))
                }
                patchRocks(combined)
            }
        }
    }

    // =================================================================
    // ALGORITMI MARE-MARE STRUTTURATI (COSTA LINEARE VS ANTELLO SCOGLI)
    // =================================================================

    // Velocità massima ammissibile per l'euristica A* (12 nodi in km/h).
    // Deve essere >= della velocità più alta in qualsiasi canale del grafo.
    private val MAX_CANAL_SPEED_KMH = 12.0 * 1.852

    private fun edgeTimeSeconds(e: Edge): Double {
        val speedKmh = if (e.speed > 0.0) e.speed else MAX_CANAL_SPEED_KMH
        return (e.length / 1000.0) / speedKmh * 3600.0
    }

    private fun heuristicTimeSeconds(fromId: String, toId: String): Double {
        val n = nodes[fromId] ?: return 0.0
        val g = nodes[toId] ?: return 0.0
        return haversine(n.lat, n.lon, g.lat, g.lon) / 1000.0 / MAX_CANAL_SPEED_KMH * 3600.0
    }

    private fun runCanalAStar(startId: String, endId: String, minD: Double): List<String>? {
        if (startId == endId) return listOf(startId)

        val times = mutableMapOf<String, Double>().withDefault { Double.MAX_VALUE }
        val prev = mutableMapOf<String, String?>()
        // Triple: (nodeId, gCost_secondi, hCost_secondi)
        val pq = PriorityQueue<Triple<String, Double, Double>>(compareBy { it.second + it.third })

        times[startId] = 0.0
        pq.add(Triple(startId, 0.0, heuristicTimeSeconds(startId, endId)))

        while (pq.isNotEmpty()) {
            val (u, g, _) = pq.poll()!!
            if (u == endId) break
            if (g > (times[u] ?: Double.MAX_VALUE)) continue

            adj[u]?.forEach { e ->
                if (e.depth in 0.1..<minD) return@forEach
                val v = if (e.u == u) e.v else e.u
                val newTime = g + edgeTimeSeconds(e)
                if (newTime < (times[v] ?: Double.MAX_VALUE)) {
                    times[v] = newTime
                    prev[v] = u
                    pq.add(Triple(v, newTime, heuristicTimeSeconds(v, endId)))
                }
            }
        }

        val path = mutableListOf<String>()
        var curr: String? = endId
        while (curr != null) {
            path.add(0, curr)
            curr = prev[curr]
        }
        return if (path.size >= 2 && path.first() == startId) path else null
    }

    /**
     * Navigazione Mare-Mare con instradamento differenziato per coste aperte e scogli chiusi.
     */
    private fun solveSeaPath(start: LatLng, end: LatLng): List<LatLng> {
        val path = mutableListOf<LatLng>()

        val cleanStart = handleEscapeIfInsideNoGo(start)
        val cleanEnd = handleEscapeIfInsideNoGo(end)

        if (cleanStart != start) path.add(start)
        path.add(cleanStart)

        var currentPt = cleanStart
        val globalVisitedKeys = mutableSetOf<String>()
        var safetyCounter = 0

        while (safetyCounter < 40) {
            safetyCounter++

            // Caso ottimo: Meta visibile direttamente senza intersezioni
            if (!isPathBlocked(currentPt, cleanEnd)) {
                path.add(cleanEnd)
                if (cleanEnd != end) path.add(end)
                return pullStringBidirectional(path)
            }

            // Identificazione dell'area no-go d'ostacolo corrente
            val obstacle = noGoAreas.find { lineIntersectsPolygon(currentPt, cleanEnd, it.polygon) }
            if (obstacle == null) {
                path.add(cleanEnd)
                return pullStringBidirectional(path)
            }

            val nearestBBId = findNearestBBNode(currentPt)
            var successfullyBypassed = false

            if (nearestBBId != null) {
                val startBBNode = bbNodes[nearestBBId]!!
                val distToBB = haversine(currentPt.latitude, currentPt.longitude, startBBNode.lat, startBBNode.lon)

                // Accetta il supporto vettoriale solo se si trova entro un raggio di 400 metri
                if (distToBB < 400.0) {
                    val bypassType = nodeToBypassType[nearestBBId] ?: "sea"

                    if (bypassType == "rock") {
                        // CASO SCOGLIO / ANNELLO CHIUSO: Calcolo bidirezionale (orario/antiorario) del percorso minimo
                        val ringPath = findBestDirectionOnRockRing(nearestBBId, currentPt, cleanEnd)
                        if (ringPath != null && ringPath.isNotEmpty()) {
                            ringPath.forEach { pt ->
                                val k = "${pt.latitude},${pt.longitude}"
                                if (k !in globalVisitedKeys) {
                                    path.add(pt)
                                    globalVisitedKeys.add(k)
                                }
                            }
                            currentPt = path.last()
                            successfullyBypassed = true
                        }
                    } else {
                        // CASO COSTA / LINEA APERTA ("sea"): Scorrimento lineare guidato verso l'obiettivo
                        var currBBId: String? = nearestBBId
                        val lineVisited = mutableSetOf<String>()

                        while (currBBId != null && lineVisited.size < 20) {
                            lineVisited.add(currBBId)
                            val nObj = bbNodes[currBBId]!!
                            val ptLL = LatLng(nObj.lat, nObj.lon)

                            val k = "${ptLL.latitude},${ptLL.longitude}"
                            if (k !in globalVisitedKeys) {
                                path.add(ptLL)
                                globalVisitedKeys.add(k)
                            }
                            currentPt = ptLL

                            // Uscita anticipata se la vista verso il traguardo si libera
                            if (!isPathBlocked(currentPt, cleanEnd)) {
                                successfullyBypassed = true
                                break
                            }

                            // Sceglie il prossimo nodo della linea che riduce la distanza assoluta verso il traguardo
                            currBBId = bbAdj[currBBId]?.filter { it !in lineVisited }?.minByOrNull { id ->
                                haversine(bbNodes[id]!!.lat, bbNodes[id]!!.lon, cleanEnd.latitude, cleanEnd.longitude)
                            }
                        }
                        if (lineVisited.isNotEmpty()) successfullyBypassed = true
                    }
                }
            }

            // FALLBACK GEOMETRICO: Se mancano i vettori o siamo in un cul-de-sac, calcola la tangente di aggiramento
            if (!successfullyBypassed) {
                val candidates = getPolygonBypassCandidates(currentPt, cleanEnd, obstacle.polygon)
                val bestCandidate = listOf(candidates.first, candidates.second).minByOrNull { c ->
                    haversine(currentPt.latitude, currentPt.longitude, c.latitude, c.longitude) +
                            haversine(c.latitude, c.longitude, cleanEnd.latitude, cleanEnd.longitude)
                }!!

                val cKey = "${bestCandidate.latitude},${bestCandidate.longitude}"
                if (cKey in globalVisitedKeys) {
                    // Previene oscillazioni inserendo una leggera estrusione radiale di fuga
                    val escapePt = LatLng(bestCandidate.latitude + 0.0005, bestCandidate.longitude + 0.0005)
                    path.add(escapePt)
                    currentPt = escapePt
                } else {
                    path.add(bestCandidate)
                    globalVisitedKeys.add(cKey)
                    currentPt = bestCandidate
                }
            }
        }

        if (path.last() != end) path.add(end)
        return pullStringBidirectional(path)
    }

    /**
     * Calcola i due rami di scorrimento su un anello chiuso di scogli e restituisce la rotta più breve.
     */
    private fun findBestDirectionOnRockRing(startBBId: String, currentPt: LatLng, endPt: LatLng): List<LatLng>? {
        // Troviamo tutti i nodi appartenenti a questa specifica barriera/anello tramite BFS locale
        val componentNodes = mutableListOf<String>()
        val queue: Queue<String> = LinkedList()
        val visited = mutableSetOf<String>()

        queue.add(startBBId)
        visited.add(startBBId)

        while (queue.isNotEmpty()) {
            val curr = queue.poll()!!
            componentNodes.add(curr)
            bbAdj[curr]?.forEach { adjId ->
                if (adjId !in visited) {
                    visited.add(adjId)
                    queue.add(adjId)
                }
            }
        }

        // Troviamo i nodi dell'anello che hanno una linea di vista pulita verso la meta finale
        val exitNodes = componentNodes.filter { id ->
            val node = bbNodes[id]!!
            !isPathBlocked(LatLng(node.lat, node.lon), endPt)
        }

        if (exitNodes.isEmpty()) return null

        // Troviamo il nodo d'uscita dell'anello più vicino in linea d'aria alla meta finale
        val bestExitNodeId = exitNodes.minByOrNull { id ->
            haversine(bbNodes[id]!!.lat, bbNodes[id]!!.lon, endPt.latitude, endPt.longitude)
        } ?: return null

        // Calcoliamo i due cammini sull'anello (Braccio Destro vs Braccio Sinistro) usando una BFS standard
        fun shortPathOnRing(fromId: String, toId: String): List<LatLng> {
            val pDists = mutableMapOf<String, Double>().withDefault { Double.MAX_VALUE }
            val pPrev = mutableMapOf<String, String?>()
            val pPq = PriorityQueue<Pair<String, Double>>(compareBy { it.second })

            pDists[fromId] = 0.0
            pPq.add(Pair(fromId, 0.0))

            while (pPq.isNotEmpty()) {
                val (u, d) = pPq.poll()!!
                if (u == toId) break
                if (d > (pDists[u] ?: Double.MAX_VALUE)) continue

                bbAdj[u]?.forEach { v ->
                    if (v in componentNodes) { // Rimaniamo tassativamente dentro questo anello
                        val dist = haversine(bbNodes[u]!!.lat, bbNodes[u]!!.lon, bbNodes[v]!!.lat, bbNodes[v]!!.lon)
                        val newD = d + dist
                        if (newD < (pDists[v] ?: Double.MAX_VALUE)) {
                            pDists[v] = newD
                            pPrev[v] = u
                            pPq.add(Pair(v, newD))
                        }
                    }
                }
            }

            val pathList = mutableListOf<LatLng>()
            var curr: String? = toId
            while (curr != null) {
                bbNodes[curr]?.let { pathList.add(0, LatLng(it.lat, it.lon)) }
                curr = pPrev[curr]
            }
            return pathList
        }

        return shortPathOnRing(startBBId, bestExitNodeId)
    }

    private fun handleEscapeIfInsideNoGo(p: LatLng): LatLng {
        val area = noGoAreas.find { containsPoint(it.polygon, p) } ?: return p
        var minDistance = Double.MAX_VALUE
        var closestPoint = p
        val poly = area.polygon

        for (i in 0 until poly.size - 1) {
            val pt = closestPointOnSegment(p, poly[i], poly[i+1])
            val dist = haversine(p.latitude, p.longitude, pt.latitude, pt.longitude)
            if (dist < minDistance) {
                minDistance = dist
                closestPoint = pt
            }
        }

        val latDiff = closestPoint.latitude - p.latitude
        val lonDiff = closestPoint.longitude - p.longitude
        val len = sqrt(latDiff.pow(2) + lonDiff.pow(2))

        if (len == 0.0) return LatLng(closestPoint.latitude + 0.0003, closestPoint.longitude + 0.0003)

        val offset = 0.00025 // Margine steso per tirarsi fuori da scogliere e dighe
        return LatLng(
            closestPoint.latitude + (latDiff / len) * offset,
            closestPoint.longitude + (lonDiff / len) * offset
        )
    }

    private fun getPolygonBypassCandidates(start: LatLng, end: LatLng, polygon: List<LatLng>): Pair<LatLng, LatLng> {
        var maxLeftVertex = polygon.first()
        var maxRightVertex = polygon.first()
        var maxLeftDist = -1.0
        var maxRightDist = -1.0

        val lat1 = start.latitude; val lon1 = start.longitude
        val lat2 = end.latitude; val lon2 = end.longitude

        polygon.distinct().forEach { vertex ->
            val crossProduct = (lat2 - lat1) * (vertex.longitude - lon1) - (lon2 - lon1) * (vertex.latitude - lat1)
            val distance = abs((lat2 - lat1) * (lon1 - vertex.longitude) - (lat1 - lat2) * (lat1 - vertex.latitude)) /
                    sqrt((lat2 - lat1).pow(2) + (lon2 - lon1).pow(2))

            if (crossProduct > 0) {
                if (distance > maxLeftDist) { maxLeftDist = distance; maxLeftVertex = vertex }
            } else {
                if (distance > maxRightDist) { maxRightDist = distance; maxRightVertex = vertex }
            }
        }

        val buffer = 0.0004 // Distanza di sicurezza dagli spigoli vivi delle barriere (~45 metri stabili)

        val leftBypass = LatLng(
            maxLeftVertex.latitude + if (maxLeftVertex.latitude >= start.latitude) buffer else -buffer,
            maxLeftVertex.longitude + if (maxLeftVertex.longitude >= start.longitude) buffer else -buffer
        )
        val rightBypass = LatLng(
            maxRightVertex.latitude + if (maxRightVertex.latitude >= start.latitude) buffer else -buffer,
            maxRightVertex.longitude + if (maxRightVertex.longitude >= start.longitude) buffer else -buffer
        )

        return Pair(leftBypass, rightBypass)
    }

    private fun pullStringBidirectional(path: List<LatLng>): List<LatLng> {
        if (path.size < 3) return path

        val fwd = mutableListOf<LatLng>()
        fwd.add(path.first())
        var i = 0
        while (i < path.size - 1) {
            var furthestVisible = i + 1
            for (j in path.size - 1 downTo i + 2) {
                if (!isPathBlocked(path[i], path[j])) {
                    furthestVisible = j
                    break
                }
            }
            fwd.add(path[furthestVisible])
            i = furthestVisible
        }

        val bwd = mutableListOf<LatLng>()
        bwd.add(fwd.last())
        var k = fwd.size - 1
        while (k > 0) {
            var furthestVisible = k - 1
            for (j in 0 until k - 1) {
                if (!isPathBlocked(fwd[k], fwd[j])) {
                    furthestVisible = j
                    break
                }
            }
            bwd.add(0, fwd[furthestVisible])
            k = furthestVisible
        }

        return bwd.distinct()
    }

    private fun closestPointOnSegment(p: LatLng, a: LatLng, b: LatLng): LatLng {
        val l2 = (b.latitude - a.latitude).pow(2) + (b.longitude - a.longitude).pow(2)
        if (l2 == 0.0) return a
        var t = ((p.latitude - a.latitude) * (b.latitude - a.latitude) + (p.longitude - a.longitude) * (b.longitude - a.longitude)) / l2
        t = max(0.0, min(1.0, t))
        return LatLng(a.latitude + t * (b.latitude - a.latitude), a.longitude + t * (b.longitude - a.longitude))
    }

    private fun distanceToPolygon(p: LatLng, poly: List<LatLng>): Double {
        var minDist = Double.MAX_VALUE
        for (i in 0 until poly.size - 1) {
            val cp = closestPointOnSegment(p, poly[i], poly[i+1])
            val d = haversine(p.latitude, p.longitude, cp.latitude, cp.longitude)
            if (d < minDist) minDist = d
        }
        return minDist
    }

    fun getSeaToTipsPaths(seaPoint: LatLng): List<List<LatLng>> = seaEntryNodes.map { solveSeaPath(seaPoint, LatLng(it.lat, it.lon)) }

    fun getLagunaToTipsPaths(lagPoint: LatLng): List<List<LatLng>> {
        val paths = mutableListOf<List<LatLng>>()
        val startId = findNearestGraphNode(lagPoint) ?: return paths
        for (tip in seaEntryNodes) {
            val tipGraphId = tipToGraphNodeCache[tip.id] ?: continue
            val ids = runCanalAStar(startId, tipGraphId, 0.0)
            if (ids != null) {
                val p = mutableListOf<LatLng>()
                p.add(lagPoint)
                ids.forEach { id -> nodes[id]?.let { n -> p.add(LatLng(n.lat, n.lon)) } }
                p.add(LatLng(tip.lat, tip.lon))
                paths.add(p)
            }
        }
        return paths
    }

    fun getFixedDepthAt(p: LatLng): Float? = fixedDepthAreas.find { containsPoint(it.polygon, p) }?.depth
    fun isPointInNoGo(p: LatLng): Boolean = noGoAreas.any { containsPoint(it.polygon, p) }
    fun isInsideProject(p: LatLng): Boolean = projectBoundary?.let { containsPoint(it, p) } ?: true
    fun getNoGoAreas(): List<NoGoArea> = noGoAreas

    fun calculateEstimatedTimeMinutes(p: List<LatLng>): Int {
        if (p.size < 2) return 0
        return (calculateTotalTimeSeconds(p) / 60.0).toInt()
    }

    fun calculateTotalTimeSeconds(path: List<LatLng>): Double {
        var totalSec = 0.0
        for (i in 0 until path.size - 1) {
            val dist = haversine(path[i].latitude, path[i].longitude, path[i+1].latitude, path[i+1].longitude)
            val isAtSeaSegment = isAtSea(path[i]) && isAtSea(path[i+1])
            val speed = if (isAtSeaSegment) userAverageSpeedKmH else 12.0 * 1.852
            totalSec += (dist / 1000.0) / speed * 3600.0
        }
        return totalSec
    }

    fun calculateTotalDistance(path: List<LatLng>): Double {
        var totalDist = 0.0
        for (i in 0 until path.size - 1) {
            totalDist += haversine(path[i].latitude, path[i].longitude, path[i+1].latitude, path[i+1].longitude)
        }
        return totalDist
    }

    fun patchRocks(path: List<LatLng>): List<LatLng> {
        return pullStringBidirectional(path)
    }

    fun isAtSea(p: LatLng): Boolean {
        if (fixedDepthAreas.any { containsPoint(it.polygon, p) }) return false
        var maxSeaLon = -180.0
        var maxLagunaLon = -180.0
        seaSegments.forEach { s -> getHInt(p.latitude, s.p1, s.p2)?.let { if (it <= p.longitude) maxSeaLon = max(maxSeaLon, it) } }
        lagunaSegments.forEach { s -> getHInt(p.latitude, s.p1, s.p2)?.let { if (it <= p.longitude) maxLagunaLon = max(maxLagunaLon, it) } }
        return if (maxSeaLon == -180.0 && maxLagunaLon == -180.0) false else maxSeaLon > maxLagunaLon
    }

    private fun getHInt(lat: Double, p1: LatLng, p2: LatLng): Double? {
        if ((p1.latitude <= lat && p2.latitude > lat) || (p2.latitude <= lat && p1.latitude > lat)) {
            return p1.longitude + (lat - p1.latitude) / (p2.latitude - p1.latitude) * (p2.longitude - p1.longitude)
        }
        return null
    }

    private fun findNearestBBNode(p: LatLng) = bbNodes.values.minByOrNull { haversine(p.latitude, p.longitude, it.lat, it.lon) }?.id
    private fun findNearestGraphNode(p: LatLng) = nodes.values.minByOrNull { haversine(p.latitude, p.longitude, it.lat, it.lon) }?.id

    private fun isPathBlocked(a: LatLng, b: LatLng): Boolean = noGoAreas.any { area ->
        lineIntersectsPolygon(a, b, area.polygon)
    }

    private fun lineIntersectsPolygon(a: LatLng, b: LatLng, poly: List<LatLng>): Boolean {
        for (i in 0 until poly.size - 1) if (intersect(a, b, poly[i], poly[i+1])) return true
        for (step in 1..9) {
            val f = step / 10.0
            val p = LatLng(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
            if (containsPoint(poly, p)) return true
        }
        return false
    }

    private fun intersect(p1: LatLng, q1: LatLng, p2: LatLng, q2: LatLng): Boolean {
        fun orientation(p: LatLng, q: LatLng, r: LatLng): Int {
            val v = (q.latitude - p.latitude) * (r.longitude - q.longitude) - (q.longitude - p.longitude) * (r.latitude - q.latitude)
            if (abs(v) < 1e-15) return 0
            return if (v > 0) 1 else 2
        }
        fun onSegment(p: LatLng, a: LatLng, b: LatLng): Boolean = p.longitude <= max(a.longitude, b.longitude) && p.longitude >= min(a.longitude, b.longitude) && p.latitude <= max(a.latitude, b.latitude) && p.latitude >= min(a.latitude, b.latitude)
        val o1 = orientation(p1, q1, p2); val o2 = orientation(p1, q1, q2); val o3 = orientation(p2, q2, p1); val o4 = orientation(p2, q2, q1)
        if (o1 != o2 && o3 != o4) return true
        if (o1 == 0 && onSegment(p2, p1, q1)) return true
        if (o2 == 0 && onSegment(q2, p1, q1)) return true
        if (o3 == 0 && onSegment(p1, p2, q2)) return true
        if (o4 == 0 && onSegment(q1, p2, q2)) return true
        return false
    }

    private fun containsPoint(poly: List<LatLng>, p: LatLng): Boolean {
        var res = false
        var j = poly.size - 1
        for (i in poly.indices) {
            if (((poly[i].latitude > p.latitude) != (poly[j].latitude > p.latitude)) &&
                (p.longitude < (poly[j].longitude - poly[i].longitude) * (p.latitude - poly[i].latitude) / (poly[j].latitude - poly[i].latitude) + poly[i].longitude)) {
                res = !res
            }
            j = i
        }
        return res
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}