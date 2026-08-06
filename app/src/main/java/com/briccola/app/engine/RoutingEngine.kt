package com.briccola.app.engine

import android.content.Context
import kotlinx.serialization.json.*
import org.maplibre.android.geometry.LatLng
import java.util.*
import kotlin.math.*

data class Node(val id: String, val lat: Double, val lon: Double)
data class Edge(val u: String, val v: String, val lengthM: Double, val depthM: Double, val speedKmh: Double)
data class NoGoArea(val id: String, val polygon: List<LatLng>, val isRock: Boolean)
data class Segment(val p1: LatLng, val p2: LatLng)
data class FixedDepthArea(val depth: Float, val polygon: List<LatLng>)
data class NamedSegment(val name: String, val a: LatLng, val b: LatLng)

/**
 * Motore di routing navale.
 *
 * Stato di avanzamento (vedi roadmap nel CLAUDE.md del progetto):
 * - LAGUNA -> LAGUNA: implementato (snap al canale più vicino + A* sul grafo canali).
 * - MARE -> MARE: implementato (grafo di visibilità sugli scogli + linee guida costiere).
 * - LAGUNA <-> MARE (misto): implementato (passa per la bocca di porto più conveniente).
 */
class RoutingEngine(private val context: Context) {

    private val nodes = mutableMapOf<String, Node>()
    private val edges = mutableListOf<Edge>()
    private val adj = mutableMapOf<String, MutableList<Edge>>()
    private val noGoAreas = mutableListOf<NoGoArea>()

    private val seaSegments = mutableListOf<Segment>()
    private val lagunaSegments = mutableListOf<Segment>()
    private val seaBypassLines = mutableListOf<List<LatLng>>()
    private var projectBoundary: List<LatLng>? = null
    private val fixedDepthAreas = mutableListOf<FixedDepthArea>()
    private val seaTips = mutableListOf<LatLng>()
    // Segmenti di canale CON NOME (da laguna_vettoriale.json, tag OSM "name"), separati dagli
    // archi del grafo di routing (graph.json) che non hanno nomi. Usati solo per l'HUD.
    private val namedCanalSegments = mutableListOf<NamedSegment>()
    // Griglia spaziale per namedCanalSegments (costruita qui in Kotlin, a differenza di
    // spatialGrid che arriva precalcolata dal grafo Python): senza indice, nearestCanalName
    // scansionava linearmente MIGLIAIA di segmenti ad ogni chiamata (~44ms per chiamata,
    // misurato con PerfMonitor — uno scatto pieno sul thread principale ad ogni refresh).
    private val namedCanalGrid = mutableMapOf<Long, MutableList<Int>>()  // cellKey -> indici in namedCanalSegments
    private val NAMED_CANAL_CELL_DEG = 0.003  // stessa scala della griglia di routing (~250-330m)

    private fun namedCanalCellKey(lat: Double, lon: Double): Long =
        (lat / NAMED_CANAL_CELL_DEG).toInt().toLong() * 100_000L + (lon / NAMED_CANAL_CELL_DEG).toInt().toLong()

    // Dati precalcolati dal build Python (precalcola_grafo.py).
    // Vengono caricati da graph.json insieme agli archi e nodi, quindi costo = solo lettura JSON.
    private val nodeComponent = mutableMapOf<String, Int>()   // nodo -> id componente connessa
    private val nodeTipBest   = mutableMapOf<String, Pair<Int, Int>>() // nodo -> (tip_idx, dist_s)
    private var gridCellDeg  = 0.003
    private var gridOriginLat = 0.0
    private var gridOriginLon = 0.0
    private val spatialGrid  = mutableMapOf<Long, MutableList<Int>>() // cellKey -> edge indices

    private val json = Json { ignoreUnknownKeys = true }

    var userAverageSpeedKmH: Double = 30.0
    var lastRoutingError: String = ""

    private val DEFAULT_SPEED_KNOTS = 12.0
    private val DEFAULT_SPEED_KMH = DEFAULT_SPEED_KNOTS * 1.852
    private var maxSpeedKmh = DEFAULT_SPEED_KMH

    init {
        loadGraph()
        loadMarkersAndBoundaries()
    }

    // =================================================================
    // CARICAMENTO DATI
    // =================================================================

    private fun parseSpeedKmh(el: JsonElement?): Double {
        if (el == null || el is JsonNull) return DEFAULT_SPEED_KMH
        val content = el.jsonPrimitive.contentOrNull ?: return DEFAULT_SPEED_KMH
        // Il campo "s" nel dato sorgente è incoerente: a volte un numero puro ("11"),
        // a volte testo con unità ("5 knots"). Estraiamo sempre la prima cifra trovata.
        val knots = Regex("[0-9]+(\\.[0-9]+)?").find(content)?.value?.toDoubleOrNull() ?: return DEFAULT_SPEED_KMH
        return knots * 1.852
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
                    lon = obj["lon"]?.jsonPrimitive?.double ?: 0.0
                )
                obj["c"]?.jsonPrimitive?.intOrNull?.let { nodeComponent[id] = it }
                obj["tip_best"]?.jsonArray?.let { arr ->
                    val ti = arr[0].jsonPrimitive.int
                    val ds = arr[1].jsonPrimitive.int
                    if (ti >= 0) nodeTipBest[id] = ti to ds
                }
            }

            root["edges"]?.jsonArray?.forEach { element ->
                val obj = element.jsonObject
                val u = obj["u"]?.jsonPrimitive?.content ?: return@forEach
                val v = obj["v"]?.jsonPrimitive?.content ?: return@forEach
                val length = obj["l"]?.jsonPrimitive?.double ?: 0.0
                val depth = obj["d"]?.jsonPrimitive?.double ?: 0.0
                val speedKmh = parseSpeedKmh(obj["s"])

                val edge = Edge(u, v, length, depth, speedKmh)
                edges.add(edge)
                adj.getOrPut(u) { mutableListOf() }.add(edge)
                adj.getOrPut(v) { mutableListOf() }.add(edge)
                if (speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh
            }

            root["spatial_index"]?.jsonObject?.let { si ->
                gridCellDeg  = si["cell_deg"]?.jsonPrimitive?.double ?: gridCellDeg
                gridOriginLat = si["origin_lat"]?.jsonPrimitive?.double ?: 0.0
                gridOriginLon = si["origin_lon"]?.jsonPrimitive?.double ?: 0.0
                si["cells"]?.jsonObject?.forEach { (key, arr) ->
                    val parts = key.split(',')
                    val row = parts[0].toLongOrNull() ?: return@forEach
                    val col = parts[1].toLongOrNull() ?: return@forEach
                    val cellKey = row * 100_000L + col
                    spatialGrid[cellKey] = arr.jsonArray.mapTo(mutableListOf()) { it.jsonPrimitive.int }
                }
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
                if (isSeaMarker) for (i in 0 until lls.size - 1) seaSegments.add(Segment(lls[i], lls[i + 1]))
                if (isLagunaMarker) for (i in 0 until lls.size - 1) lagunaSegments.add(Segment(lls[i], lls[i + 1]))

                if (props["special:nav:bypass"]?.jsonPrimitive?.content == "sea" && type == "LineString") {
                    seaBypassLines.add(lls)
                }

                if (props["special:gate"]?.jsonPrimitive?.content == "sea_tip" && type == "Point" && lls[0] !in seaTips) {
                    seaTips.add(lls[0])
                }

                val canalName = props["name"]?.jsonPrimitive?.contentOrNull
                if (type == "LineString" && props["type"]?.jsonPrimitive?.contentOrNull == "canal" && canalName != null) {
                    for (i in 0 until lls.size - 1) {
                        val idx = namedCanalSegments.size
                        namedCanalSegments.add(NamedSegment(canalName, lls[i], lls[i + 1]))
                        val midLat = (lls[i].latitude + lls[i + 1].latitude) / 2.0
                        val midLon = (lls[i].longitude + lls[i + 1].longitude) / 2.0
                        namedCanalGrid.getOrPut(namedCanalCellKey(midLat, midLon)) { mutableListOf() }.add(idx)
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

    // minDepth = 0.0 (default) = nessun filtro profondità.
    // Il dato batimetrico è disponibile ma usato per informazione, non come blocco.
    // In futuro si potrà passare il pescaggio reale dell'utente.
    fun findRoute(start: LatLng, end: LatLng, minDepth: Double = 0.0): List<LatLng>? {
        lastRoutingError = ""
        val safeStart = escapeFromNoGo(start)
        val safeEnd = escapeFromNoGo(end)
        val sSea = isAtSea(safeStart)
        val eSea = isAtSea(safeEnd)

        return when {
            !sSea && !eSea -> solveLagunaToLaguna(safeStart, safeEnd, minDepth)
            sSea && eSea -> solveSeaToSea(safeStart, safeEnd)
            else -> solveMixed(safeStart, safeEnd, minDepth)
        }
    }

    /**
     * Se il punto cade dentro una zona no-go, lo sposta sul punto del bordo
     * più vicino e poi lo offset leggermente verso l'esterno.
     * Se è già in acqua libera, ritorna il punto invariato.
     */
    private fun escapeFromNoGo(p: LatLng): LatLng {
        val area = noGoAreas.find { containsPoint(it.polygon, p) } ?: return p
        var minDist = Double.MAX_VALUE
        var closest = p
        val poly = area.polygon
        for (i in 0 until poly.size - 1) {
            val pt = closestPointOnSegment(p, poly[i], poly[i + 1])
            val d = haversine(p.latitude, p.longitude, pt.latitude, pt.longitude)
            if (d < minDist) { minDist = d; closest = pt }
        }
        val dLat = closest.latitude - p.latitude
        val dLon = closest.longitude - p.longitude
        val len = sqrt(dLat * dLat + dLon * dLon)
        if (len == 0.0) return LatLng(closest.latitude + 0.0002, closest.longitude + 0.0002)
        val offset = 0.0002 // ~20 m
        return LatLng(closest.latitude + (dLat / len) * offset, closest.longitude + (dLon / len) * offset)
    }

    // =================================================================
    // CASO 1: LAGUNA -> LAGUNA (implementato)
    //
    // Strategia: si proietta il punto di partenza sul canale (arco del grafo)
    // più vicino, idem per l'arrivo, poi si naviga col solo grafo dei canali
    // (A*, costo = tempo) dal punto di innesto di partenza a quello di arrivo.
    // =================================================================

    private data class EdgeSnap(val edge: Edge, val point: LatLng)

    private fun solveLagunaToLaguna(start: LatLng, end: LatLng, minDepth: Double): List<LatLng>? {
        val snapStart = snapToNearestEdge(start) ?: run { lastRoutingError = "Nessun canale trovato vicino al punto di partenza"; return null }
        val snapEnd = snapToNearestEdge(end) ?: run { lastRoutingError = "Nessun canale trovato vicino al punto di arrivo"; return null }

        // Fail-fast: se i due archi sono in componenti connesse diverse, nessun A* può aiutare.
        if (nodeComponent.isNotEmpty()) {
            val cStart = nodeComponent[snapStart.edge.u]
            val cEnd   = nodeComponent[snapEnd.edge.u]
            if (cStart != null && cEnd != null && cStart != cEnd) {
                lastRoutingError = "I due punti si trovano in reti di canali separate (non collegate)"
                return null
            }
        }

        // Caso degenere: partenza e arrivo si agganciano allo stesso canale, nessun A* necessario.
        if (snapStart.edge === snapEnd.edge) {
            if (minDepth > 0.0 && snapStart.edge.depthM in 0.1..<minDepth) {
                lastRoutingError = "Canale non navigabile (profondità minima richiesta: ${minDepth}m)"
                return null
            }
            return listOf(start, snapStart.point, snapEnd.point, end)
        }

        val startId = "__snap_start__"
        val endId = "__snap_end__"
        val virtualPositions = mapOf(startId to snapStart.point, endId to snapEnd.point)
        val extra = mutableMapOf<String, MutableList<Edge>>()

        fun addVirtualEdge(from: String, edgeSnap: EdgeSnap) {
            val uNode = nodes[edgeSnap.edge.u] ?: return
            val vNode = nodes[edgeSnap.edge.v] ?: return
            val lenToU = haversine(edgeSnap.point.latitude, edgeSnap.point.longitude, uNode.lat, uNode.lon)
            val lenToV = haversine(edgeSnap.point.latitude, edgeSnap.point.longitude, vNode.lat, vNode.lon)
            val eToU = Edge(from, edgeSnap.edge.u, lenToU, edgeSnap.edge.depthM, edgeSnap.edge.speedKmh)
            val eToV = Edge(from, edgeSnap.edge.v, lenToV, edgeSnap.edge.depthM, edgeSnap.edge.speedKmh)
            extra.getOrPut(from) { mutableListOf() }.addAll(listOf(eToU, eToV))
            extra.getOrPut(edgeSnap.edge.u) { mutableListOf() }.add(eToU)
            extra.getOrPut(edgeSnap.edge.v) { mutableListOf() }.add(eToV)
        }
        addVirtualEdge(startId, snapStart)
        addVirtualEdge(endId, snapEnd)

        fun neighbors(id: String): List<Edge> = (adj[id].orEmpty()) + (extra[id].orEmpty())
        fun position(id: String): LatLng? = virtualPositions[id] ?: nodes[id]?.let { LatLng(it.lat, it.lon) }

        val pathIds = runCanalAStar(startId, endId, minDepth, ::neighbors, ::position)
            ?: run { lastRoutingError = "Nessun canale praticabile (profondità minima: ${minDepth}m)"; return null }

        val path = mutableListOf<LatLng>()
        path.add(start)
        pathIds.forEach { id -> position(id)?.let { path.add(it) } }
        path.add(end)
        return path
    }

    // Se la ricerca a raggio 2 celle (~600m) non trova nulla ma il punto è dentro l'area di
    // progetto, si allarga la ricerca raddoppiando il raggio fino a questo massimo (celle),
    // prima di arrendersi. A GRID_CELL_DEG≈0.003° (~300m/cella) sono ~12km: più che sufficiente
    // a coprire un "buco" di canali dentro la laguna, restando comunque molto più leggero della
    // forza bruta su tutti gli archi.
    private val MAX_SNAP_SEARCH_RADIUS_CELLS = 40

    /**
     * Snap al canale più vicino usando la griglia spaziale precalcolata.
     * Ricerca nelle celle vicine al punto (raggio ~2 celle) invece di scorrere
     * tutti i 15k+ archi — da O(N) a O(~30-50 archi), speedup ~300-500x.
     *
     * Se la griglia NON è caricata affatto (bug di parsing di graph.json), fallback a forza
     * bruta su tutti gli archi — rete di sicurezza per un caso che non dovrebbe capitare.
     * Se il raggio iniziale non trova nulla ma il punto è dentro l'area di progetto (la laguna),
     * allarga progressivamente il raggio (vedi MAX_SNAP_SEARCH_RADIUS_CELLS): un punto valido in
     * laguna ma in una zona con pochi canali vicini non deve risultare "nessun canale trovato"
     * solo perché il canale più vicino è a più di ~600m. Se invece il punto è fuori dall'area di
     * progetto (es. stai testando da casa/ufficio) NON si allarga né si fa forza bruta: misurato
     * con PerfMonitor, una scansione di 15k archi costa ~40ms, e se il punto resta fuori
     * copertura viene ripetuta ad ogni chiamata (fino a 20/s con l'HUD) — "Nessun canale vicino"
     * è la risposta corretta in quel caso.
     */
    private fun snapToNearestEdge(p: LatLng): EdgeSnap? {
        val candidateEdgeIndices = mutableSetOf<Int>()
        if (spatialGrid.isNotEmpty()) {
            val rowCenter = ((p.latitude  - gridOriginLat) / gridCellDeg).toInt()
            val colCenter = ((p.longitude - gridOriginLon) / gridCellDeg).toInt()

            fun collectWithinRadius(radius: Int) {
                for (dr in -radius..radius) {
                    for (dc in -radius..radius) {
                        val key = (rowCenter + dr).toLong() * 100_000L + (colCenter + dc).toLong()
                        spatialGrid[key]?.forEach { candidateEdgeIndices.add(it) }
                    }
                }
            }

            collectWithinRadius(2)
            if (candidateEdgeIndices.isEmpty() && isInsideProject(p)) {
                var radius = 4
                while (candidateEdgeIndices.isEmpty() && radius <= MAX_SNAP_SEARCH_RADIUS_CELLS) {
                    collectWithinRadius(radius)
                    radius *= 2
                }
            }
        }
        val candidates = when {
            candidateEdgeIndices.isNotEmpty() -> candidateEdgeIndices.map { edges[it] }
            spatialGrid.isEmpty() -> edges   // griglia non caricata: unico caso con fallback a forza bruta
            else -> return null              // griglia caricata ma punto fuori copertura: nessun canale vicino
        }

        var best: EdgeSnap? = null
        var bestDist = Double.MAX_VALUE
        candidates.forEach { e ->
            val uNode = nodes[e.u] ?: return@forEach
            val vNode = nodes[e.v] ?: return@forEach
            val proj = closestPointOnSegment(p, LatLng(uNode.lat, uNode.lon), LatLng(vNode.lat, vNode.lon))
            val dist = haversine(p.latitude, p.longitude, proj.latitude, proj.longitude)
            if (dist < bestDist) {
                bestDist = dist
                best = EdgeSnap(e, proj)
            }
        }
        return best
    }

    private fun edgeTimeSeconds(e: Edge): Double {
        val speed = if (e.speedKmh > 0.0) e.speedKmh else DEFAULT_SPEED_KMH
        return (e.lengthM / 1000.0) / speed * 3600.0
    }

    private fun runCanalAStar(
        startId: String,
        endId: String,
        minDepth: Double,
        neighbors: (String) -> List<Edge>,
        position: (String) -> LatLng?
    ): List<String>? {
        if (startId == endId) return listOf(startId)

        fun heuristicSeconds(fromId: String, toId: String): Double {
            val a = position(fromId) ?: return 0.0
            val b = position(toId) ?: return 0.0
            return haversine(a.latitude, a.longitude, b.latitude, b.longitude) / 1000.0 / maxSpeedKmh * 3600.0
        }

        val times = mutableMapOf<String, Double>()
        val prev = mutableMapOf<String, String?>()
        val pq = PriorityQueue<Triple<String, Double, Double>>(compareBy { it.second + it.third })

        times[startId] = 0.0
        pq.add(Triple(startId, 0.0, heuristicSeconds(startId, endId)))

        while (pq.isNotEmpty()) {
            val (u, g, _) = pq.poll()!!
            if (u == endId) break
            if (g > (times[u] ?: Double.MAX_VALUE)) continue

            neighbors(u).forEach { e ->
                if (minDepth > 0.0 && e.depthM in 0.1..<minDepth) return@forEach
                val v = if (e.u == u) e.v else e.u
                val newTime = g + edgeTimeSeconds(e)
                if (newTime < (times[v] ?: Double.MAX_VALUE)) {
                    times[v] = newTime
                    prev[v] = u
                    pq.add(Triple(v, newTime, heuristicSeconds(v, endId)))
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

    // =================================================================
    // CASO 2: MARE -> MARE (implementato)
    //
    // Strategia: grafo di visibilità. Se il segmento diretto partenza->arrivo
    // non attraversa nessuna zona no-go (scogli/bassifondi), il percorso è la
    // linea retta. Altrimenti si costruisce un grafo con partenza, arrivo e
    // waypoint candidati; due nodi sono collegati se il segmento fra loro non
    // attraversa nessuna zona no-go; il percorso più breve su questo grafo
    // (Dijkstra, costo = distanza) rasenta naturalmente gli ostacoli.
    //
    // I waypoint candidati NON sono "tutti i vertici di ogni zona no-go": le
    // zone no-go costiere (tag area=no_go) sono sagome lunghe e frastagliate
    // (fino a ~60 vertici) pensate solo per il test di blocco, non per essere
    // usate come grafo di visibilità — altrimenti il percorso zigzaga tra i
    // loro vertici invece di seguire un profilo morbido. Per la costa si usano
    // invece i punti delle linee guida pre-tracciate (special:nav:bypass=sea).
    // Per gli scogli isolati (tag obstacle=rock), che sono sagome compatte,
    // i vertici del poligono stesso vanno benissimo come waypoint.
    // =================================================================

    private fun solveSeaToSea(start: LatLng, end: LatLng): List<LatLng>? {
        val obstacles = relevantObstacles(start, end)

        if (obstacles.none { isSegmentBlocked(start, end, it.polygon) }) {
            return listOf(start, end)
        }

        val vPoints = mutableListOf(start, end)
        obstacles.filter { it.isRock }.forEach { area -> vPoints.addAll(area.polygon.dropLast(1)) }
        vPoints.addAll(relevantGuidePoints(start, end))
        val n = vPoints.size

        val dist = DoubleArray(n) { Double.MAX_VALUE }
        val prev = IntArray(n) { -1 }
        val visited = BooleanArray(n)
        dist[0] = 0.0
        val pq = PriorityQueue<Pair<Int, Double>>(compareBy { it.second })
        pq.add(0 to 0.0)

        while (pq.isNotEmpty()) {
            val (u, d) = pq.poll()!!
            if (visited[u]) continue
            visited[u] = true
            if (u == 1) break

            for (v in 0 until n) {
                if (v == u || visited[v]) continue
                if (obstacles.any { isSegmentBlocked(vPoints[u], vPoints[v], it.polygon) }) continue
                val nd = d + haversine(vPoints[u].latitude, vPoints[u].longitude, vPoints[v].latitude, vPoints[v].longitude)
                if (nd < dist[v]) {
                    dist[v] = nd
                    prev[v] = u
                    pq.add(v to nd)
                }
            }
        }

        if (dist[1] == Double.MAX_VALUE) {
            lastRoutingError = "Nessun percorso libero da ostacoli trovato in mare aperto"
            return null
        }

        val path = mutableListOf<LatLng>()
        var cur = 1
        while (cur != -1) {
            path.add(0, vPoints[cur])
            cur = prev[cur]
        }
        return path
    }

    /** Limita gli ostacoli da considerare a quelli la cui bounding box è vicina al segmento partenza-arrivo. */
    private fun relevantObstacles(start: LatLng, end: LatLng): List<NoGoArea> {
        val (minLat, maxLat, minLon, maxLon) = boundingBoxWithMargin(start, end)
        return noGoAreas.filter { area ->
            area.polygon.any { it.latitude in minLat..maxLat && it.longitude in minLon..maxLon }
        }
    }

    /** Punti delle linee guida costiere vicini al segmento partenza-arrivo, candidati come waypoint. */
    private fun relevantGuidePoints(start: LatLng, end: LatLng): List<LatLng> {
        val (minLat, maxLat, minLon, maxLon) = boundingBoxWithMargin(start, end)
        return seaBypassLines.flatten().filter { it.latitude in minLat..maxLat && it.longitude in minLon..maxLon }
    }

    private data class BBox(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double)

    private fun boundingBoxWithMargin(start: LatLng, end: LatLng): BBox {
        val marginDeg = 0.02 // ~2 km a queste latitudini
        return BBox(
            min(start.latitude, end.latitude) - marginDeg,
            max(start.latitude, end.latitude) + marginDeg,
            min(start.longitude, end.longitude) - marginDeg,
            max(start.longitude, end.longitude) + marginDeg
        )
    }

    /** Vero se il segmento a-b attraversa l'interno del poligono (bordi/vertici condivisi non contano). */
    private fun isSegmentBlocked(a: LatLng, b: LatLng, poly: List<LatLng>): Boolean {
        for (i in 0 until poly.size - 1) {
            val p1 = poly[i]; val p2 = poly[i + 1]
            if (p1 == a || p1 == b || p2 == a || p2 == b) continue
            if (segmentsIntersect(a, b, p1, p2)) return true
        }
        for (step in 1..9) {
            val f = step / 10.0
            val p = LatLng(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f)
            if (containsPoint(poly, p)) return true
        }
        return false
    }

    private fun segmentsIntersect(p1: LatLng, q1: LatLng, p2: LatLng, q2: LatLng): Boolean {
        fun orientation(p: LatLng, q: LatLng, r: LatLng): Int {
            val v = (q.latitude - p.latitude) * (r.longitude - q.longitude) - (q.longitude - p.longitude) * (r.latitude - q.latitude)
            if (abs(v) < 1e-15) return 0
            return if (v > 0) 1 else 2
        }
        fun onSegment(p: LatLng, a: LatLng, b: LatLng): Boolean =
            p.longitude <= max(a.longitude, b.longitude) && p.longitude >= min(a.longitude, b.longitude) &&
                    p.latitude <= max(a.latitude, b.latitude) && p.latitude >= min(a.latitude, b.latitude)

        val o1 = orientation(p1, q1, p2); val o2 = orientation(p1, q1, q2)
        val o3 = orientation(p2, q2, p1); val o4 = orientation(p2, q2, q1)
        if (o1 != o2 && o3 != o4) return true
        if (o1 == 0 && onSegment(p2, p1, q1)) return true
        if (o2 == 0 && onSegment(q2, p1, q1)) return true
        if (o3 == 0 && onSegment(p1, p2, q2)) return true
        if (o4 == 0 && onSegment(q1, p2, q2)) return true
        return false
    }

    // =================================================================
    // CASO 3: LAGUNA <-> MARE, MISTO (implementato)
    //
    // Strategia: usa il Dijkstra precalcolato da ogni tip (caricato da graph.json).
    // Lo snap del punto laguna ci dà il nodo più vicino del grafo; da lì un lookup O(1)
    // in nodeTipBest dice immediatamente quale bocca di porto è raggiungibile più in
    // fretta via canale — zero A* necessario per la scelta del tip. Si calcola poi
    // solo il percorso reale verso quel tip (laguna parte + mare parte).
    // Fallback alla classifica in linea d'aria se i dati precalcolati non sono disponibili.
    // =================================================================

    private fun solveMixed(start: LatLng, end: LatLng, minDepth: Double): List<LatLng>? {
        if (seaTips.isEmpty()) {
            lastRoutingError = "Nessuna bocca di porto (tip) disponibile nel dataset"
            return null
        }

        val startIsSea = isAtSea(start)
        val lagunaPoint = if (startIsSea) end else start
        val seaPoint    = if (startIsSea) start else end

        // Snap del punto laguna: da qui leggiamo nodeTipBest per scegliere il tip ottimale.
        val snapLaguna = snapToNearestEdge(lagunaPoint)
        val tipOrder: List<LatLng> = if (snapLaguna != null && nodeTipBest.isNotEmpty()) {
            // Lookup O(1): i due nodi dell'arco snap -> prendi il best tip del nodo più vicino.
            val bestFromU = nodeTipBest[snapLaguna.edge.u]
            val bestFromV = nodeTipBest[snapLaguna.edge.v]
            val bestTipIdx = when {
                bestFromU == null -> bestFromV?.first
                bestFromV == null -> bestFromU.first
                bestFromU.second <= bestFromV.second -> bestFromU.first
                else -> bestFromV.first
            }
            if (bestTipIdx != null && bestTipIdx in seaTips.indices) {
                // Metti il tip migliore prima, gli altri dopo come fallback in ordine di linea d'aria
                val best = seaTips[bestTipIdx]
                val rest = seaTips.filterIndexed { i, _ -> i != bestTipIdx }
                    .sortedBy { haversine(lagunaPoint.latitude, lagunaPoint.longitude, it.latitude, it.longitude) + haversine(seaPoint.latitude, seaPoint.longitude, it.latitude, it.longitude) }
                listOf(best) + rest
            } else {
                seaTips.sortedBy { haversine(lagunaPoint.latitude, lagunaPoint.longitude, it.latitude, it.longitude) + haversine(seaPoint.latitude, seaPoint.longitude, it.latitude, it.longitude) }
            }
        } else {
            seaTips.sortedBy { haversine(lagunaPoint.latitude, lagunaPoint.longitude, it.latitude, it.longitude) + haversine(seaPoint.latitude, seaPoint.longitude, it.latitude, it.longitude) }
        }

        var best: List<LatLng>? = null
        var bestTimeSec = Double.MAX_VALUE
        var evaluated = 0

        for (tip in tipOrder) {
            evaluated++
            val lagunaPart = solveLagunaToLaguna(lagunaPoint, tip, minDepth)
            val seaPart    = solveSeaToSea(seaPoint, tip)
            if (lagunaPart != null && seaPart != null) {
                val combined = if (startIsSea) seaPart + lagunaPart.reversed().drop(1)
                               else lagunaPart + seaPart.reversed().drop(1)
                val t = calculateTotalTimeSeconds(combined)
                if (t < bestTimeSec) { bestTimeSec = t; best = combined }
            }
            // Il primo tip è già il migliore per via canale; prova il secondo solo come
            // fallback in caso di pescaggio troppo basso o ostacoli insormontabili.
            if (best != null && evaluated >= 2) break
        }

        if (best == null) {
            lastRoutingError = "Nessuna bocca di porto raggiungibile (pescaggio insufficiente o ostacoli)"
        }
        return best
    }

    // =================================================================
    // QUERY DI SUPPORTO (usate da UI / HUD, indipendenti dal routing)
    // =================================================================

    /** Restituisce il limite di velocità in nodi sull'arco di canale più vicino al punto, null se non impostato. */
    fun getMaxSpeedKnotsAt(p: LatLng): Double? {
        val snap = snapToNearestEdge(p) ?: return null
        if (snap.edge.speedKmh <= 0) return null
        return snap.edge.speedKmh / 1.852
    }

    /**
     * Distanza in metri dal punto al canale (arco del grafo) più vicino.
     * Usa la griglia spaziale precalcolata: O(~30-50 confronti).
     * Utile per "allarme fuori canale" e per il pannello debug del simulatore.
     */
    fun distanceToNearestCanalMeters(p: LatLng): Double {
        val snap = snapToNearestEdge(p) ?: return Double.MAX_VALUE
        return haversine(p.latitude, p.longitude, snap.point.latitude, snap.point.longitude)
    }

    /**
     * Nome del canale (con nome noto) più vicino al punto, entro [maxDistanceM]. Solo per HUD.
     * Usa namedCanalGrid per limitare il confronto ai segmenti nelle celle vicine (±2 celle,
     * ~1.3km di lato — abbondante rispetto ai 100-300m tipici della soglia), invece di
     * scansionare tutti i segmenti con nome della laguna.
     */
    fun nearestCanalName(p: LatLng, maxDistanceM: Double = 40.0): String? {
        var bestName: String? = null
        var bestDist = Double.MAX_VALUE
        val rowCenter = (p.latitude / NAMED_CANAL_CELL_DEG).toInt()
        val colCenter = (p.longitude / NAMED_CANAL_CELL_DEG).toInt()
        val seen = HashSet<Int>()
        for (dr in -2..2) {
            for (dc in -2..2) {
                val key = (rowCenter + dr).toLong() * 100_000L + (colCenter + dc).toLong()
                val bucket = namedCanalGrid[key] ?: continue
                for (idx in bucket) {
                    if (!seen.add(idx)) continue
                    val seg = namedCanalSegments[idx]
                    val closest = closestPointOnSegment(p, seg.a, seg.b)
                    val d = haversine(p.latitude, p.longitude, closest.latitude, closest.longitude)
                    if (d < bestDist) { bestDist = d; bestName = seg.name }
                }
            }
        }
        return if (bestDist <= maxDistanceM) bestName else null
    }

    fun getFixedDepthAt(p: LatLng): Float? = fixedDepthAreas.find { containsPoint(it.polygon, p) }?.depth
    fun isPointInNoGo(p: LatLng): Boolean = noGoAreas.any { containsPoint(it.polygon, p) }
    fun isInsideProject(p: LatLng): Boolean = projectBoundary?.let { containsPoint(it, p) } ?: true

    /** Bounding box del perimetro di progetto (special:nav:boundary=project) allargato di
     *  [marginMeters] su ogni lato — usato per definire la regione della mappa da scaricare
     *  offline (laguna + margine di mare aperto). null se il perimetro non è ancora caricato. */
    fun getProjectBoundsWithMargin(marginMeters: Double): Pair<LatLng, LatLng>? {
        val boundary = projectBoundary?.takeIf { it.isNotEmpty() } ?: return null
        val minLat = boundary.minOf { it.latitude }
        val maxLat = boundary.maxOf { it.latitude }
        val minLon = boundary.minOf { it.longitude }
        val maxLon = boundary.maxOf { it.longitude }
        val midLat = (minLat + maxLat) / 2.0
        val latMarginDeg = marginMeters / 111_320.0
        val lonMarginDeg = marginMeters / (111_320.0 * Math.cos(Math.toRadians(midLat)))
        val southWest = LatLng(minLat - latMarginDeg, minLon - lonMarginDeg)
        val northEast = LatLng(maxLat + latMarginDeg, maxLon + lonMarginDeg)
        return southWest to northEast
    }
    fun getNoGoAreas(): List<NoGoArea> = noGoAreas
    fun getSeaTips(): List<LatLng> = seaTips

    data class TipResult(val tip: LatLng, val path: List<LatLng>?, val error: String, val estimatedSeconds: Double = 0.0)

    /**
     * Calcola il percorso dalla posizione corrente verso ciascuna delle 6 bocche di porto.
     * DEVE essere chiamato su Dispatchers.Default (non sul main thread): ogni chiamata
     * può eseguire A* sul grafo canali o il grafo di visibilità mare.
     */
    fun pathsToTips(point: LatLng): List<TipResult> {
        return seaTips.map { tip ->
            val path = findRoute(point, tip)
            TipResult(tip, path, lastRoutingError, if (path != null) calculateTotalTimeSeconds(path) else 0.0)
        }
    }

    fun calculateEstimatedTimeMinutes(p: List<LatLng>): Int {
        if (p.size < 2) return 0
        return (calculateTotalTimeSeconds(p) / 60.0).toInt()
    }

    // Approssimazione: le velocità reali per-arco usate internamente da A* per la
    // laguna si perdono una volta che il risultato è appiattito in una lista di punti,
    // quindi qui si ricade sulla velocità di default per i tratti in laguna. I tratti
    // in mare aperto usano invece la velocità di crociera configurata dall'utente.
    fun calculateTotalTimeSeconds(path: List<LatLng>): Double {
        var totalSec = 0.0
        for (i in 0 until path.size - 1) {
            val dist = haversine(path[i].latitude, path[i].longitude, path[i + 1].latitude, path[i + 1].longitude)
            val isAtSeaSegment = isAtSea(path[i]) && isAtSea(path[i + 1])
            val speed = if (isAtSeaSegment) userAverageSpeedKmH else DEFAULT_SPEED_KMH
            totalSec += (dist / 1000.0) / speed * 3600.0
        }
        return totalSec
    }

    fun calculateTotalDistance(path: List<LatLng>): Double {
        var totalDist = 0.0
        for (i in 0 until path.size - 1) {
            totalDist += haversine(path[i].latitude, path[i].longitude, path[i + 1].latitude, path[i + 1].longitude)
        }
        return totalDist
    }

    fun isAtSea(p: LatLng): Boolean {
        if (fixedDepthAreas.any { containsPoint(it.polygon, p) }) return false
        var maxSeaLon = -180.0
        var maxLagunaLon = -180.0
        seaSegments.forEach { s -> getHInt(p.latitude, s.p1, s.p2)?.let { if (it <= p.longitude) maxSeaLon = max(maxSeaLon, it) } }
        lagunaSegments.forEach { s -> getHInt(p.latitude, s.p1, s.p2)?.let { if (it <= p.longitude) maxLagunaLon = max(maxLagunaLon, it) } }
        return if (maxSeaLon == -180.0 && maxLagunaLon == -180.0) false else maxSeaLon > maxLagunaLon
    }

    /** Distanza in metri dal confine laguna/mare aperto (seaSegments — la linea che separa la
     *  laguna dal mare, usata anche da isAtSea): usata come "distanza dalla costa" per i limiti
     *  di navigazione in mare aperto (patente/dotazioni di sicurezza, vedi CameraTuning-style
     *  impostazioni in Settings). seaSegments è una linea di demarcazione, non l'intera costa,
     *  ma per un punto già in mare aperto è una buona approssimazione di quanto ci si è
     *  allontanati dalla laguna/costa. Nessun indice spaziale: pochi segmenti, chiamata solo
     *  al ritmo dell'HUD (non ad ogni fotogramma).
     */
    fun distanceFromSeaBoundaryMeters(p: LatLng): Double {
        var best = Double.MAX_VALUE
        seaSegments.forEach { s ->
            val closest = closestPointOnSegment(p, s.p1, s.p2)
            val d = haversine(p.latitude, p.longitude, closest.latitude, closest.longitude)
            if (d < best) best = d
        }
        return best
    }

    private fun getHInt(lat: Double, p1: LatLng, p2: LatLng): Double? {
        if ((p1.latitude <= lat && p2.latitude > lat) || (p2.latitude <= lat && p1.latitude > lat)) {
            return p1.longitude + (lat - p1.latitude) / (p2.latitude - p1.latitude) * (p2.longitude - p1.longitude)
        }
        return null
    }

    private fun closestPointOnSegment(p: LatLng, a: LatLng, b: LatLng): LatLng {
        val l2 = (b.latitude - a.latitude).pow(2) + (b.longitude - a.longitude).pow(2)
        if (l2 == 0.0) return a
        var t = ((p.latitude - a.latitude) * (b.latitude - a.latitude) + (p.longitude - a.longitude) * (b.longitude - a.longitude)) / l2
        t = max(0.0, min(1.0, t))
        return LatLng(a.latitude + t * (b.latitude - a.latitude), a.longitude + t * (b.longitude - a.longitude))
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
