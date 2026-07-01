package it.lagunav.openlagunamaps.ui

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import it.lagunav.openlagunamaps.R
import it.lagunav.openlagunamaps.databinding.FragmentMapBinding
import it.lagunav.openlagunamaps.engine.BathymetryEngine
import it.lagunav.openlagunamaps.engine.GnssPositionProvider
import it.lagunav.openlagunamaps.engine.PositionProvider
import it.lagunav.openlagunamaps.engine.RoutingEngine
import it.lagunav.openlagunamaps.engine.SimulatorHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val PREFS_NAME            = "laguna_prefs"
private const val KEY_NIGHT_MODE        = "night_mode"
private const val KEY_MOB_PINS          = "mob_pins"
private const val STYLE_DAY             = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_NIGHT           = "https://tiles.openfreemap.org/styles/dark"
private const val BOAT_ICON_ID          = "boat-nav-icon"
private const val OFF_CANAL_THRESHOLD_M  = 30.0
private const val WAYPOINT_ADVANCE_M     = 25.0

private const val BG_REROUTE_INTERVAL_MS = 5_000L  // frequenza del controllo percorso ottimale
private const val REROUTE_IMPROVEMENT_THRESHOLD = 0.90  // ricalcola se nuovo percorso è >10% più veloce

class MapFragment : Fragment() {

    /** Quando true: layer extra (no-go, bypass, zone) + HUD esteso. */
    var debugMode = false

    /** Espone la MapLibreMap istanza al parent DevToolsFragment (es. per leggere il bearing). */
    fun mapLibreMap() = mapLibre

    /** Centro attuale della camera — usato da DevTools per impostare la destinazione al volo. */
    fun cameraCenter(): LatLng? = mapLibre?.cameraPosition?.target

    /**
     * Mostra un percorso come preview visivo (linea verde tratteggiata) senza avviare navigazione.
     * Usato da DevTools "Simula A→B". Passa null per rimuovere il preview.
     */
    fun showPreviewRoute(route: List<LatLng>?) {
        val geoJson = if (route != null && route.size >= 2) {
            val coords = JsonArray().also { arr -> route.forEach { p -> arr.add(JsonArray().apply { add(p.longitude); add(p.latitude) }) } }
            val feat   = JsonObject().apply {
                addProperty("type","Feature"); add("properties", JsonObject())
                add("geometry", JsonObject().apply { addProperty("type","LineString"); add("coordinates", coords) })
            }
            JsonObject().apply { addProperty("type","FeatureCollection"); add("features", JsonArray().apply { add(feat) }) }.toString()
        } else emptyFc()

        mapLibre?.getStyle { style ->
            val existing = style.getSource(SOURCE_PREVIEW) as? GeoJsonSource
            if (existing != null) {
                existing.setGeoJson(geoJson)
            } else {
                style.addSource(GeoJsonSource(SOURCE_PREVIEW, geoJson))
                style.addLayer(LineLayer(LAYER_PREVIEW, SOURCE_PREVIEW).withProperties(
                    lineColor("#00CC44"),
                    lineWidth(4f),
                    lineOpacity(0.85f),
                    lineDasharray(arrayOf(8f, 4f)),
                    lineCap(Property.LINE_CAP_ROUND)
                ))
            }
        }
    }

    /** Ultima posizione GPS/sim ricevuta — accessibile da DevToolsFragment. */
    var lastGpsLocation: Location? = null
        private set

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private lateinit var bathyEngine: BathymetryEngine
    lateinit var routingEngine: RoutingEngine
        private set
    private lateinit var prefs: SharedPreferences
    private var mapLibre: MapLibreMap? = null

    private var gnssProvider: PositionProvider? = null
    private val SOURCE_PREVIEW = "preview-route-source"
    private val LAYER_PREVIEW  = "preview-route-layer"

    // Dead-reckoning per camera fluida a 30fps
    private var drFixTime  = 0L
    private var drLat      = 0.0
    private var drLon      = 0.0
    private var drSpeedMps = 0f
    private var drBearing  = 0f
    private val cameraHandler = Handler(Looper.getMainLooper())
    private var cameraRunnable: Runnable? = null

    // Follow mode e rientro automatico
    private var followMode     = false  // vero solo quando c'è una rotta attiva

    // Navigazione attiva
    private var activeRoute: List<LatLng>? = null
    private var destination: LatLng? = null
    private var currentWaypointIdx = 0
    private var bgRerouteJob: Job? = null

    // Ricerca
    private var pendingSearchResult: LatLng? = null

    // Layer IDs
    private val SOURCE_GPS        = "gps-position-source"
    private val SOURCE_ROUTE_DONE = "route-done-source"    // tratto percorso
    private val SOURCE_ROUTE      = "route-source"         // tratto rimanente
    private val SOURCE_DEST       = "destination-source"
    private val LAYER_GPS         = "gps-position-layer"
    private val LAYER_ROUTE_DONE  = "route-done-layer"
    private val LAYER_ROUTE       = "route-layer"
    private val LAYER_DEST        = "destination-layer"

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startGnss() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_NIGHT_MODE) applyNightMode()
    }

    // Callback registrato su SimulatorHub
    private val simCallback: (Location) -> Unit = { onGpsFix(it) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        bathyEngine  = BathymetryEngine(requireContext())
        routingEngine = RoutingEngine(requireContext())

        setupMap(savedInstanceState)
        setupSearch()
        setupButtons()
    }

    // =================================================================
    // MAPPA
    // =================================================================

    private fun setupMap(savedInstanceState: Bundle?) {
        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync { map ->
            mapLibre = map
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled        = false

            // Bussola nativa MapLibre: top-right, sotto la search bar.
            // Appare quando la mappa è ruotata, tap → ritorna a nord.
            map.uiSettings.isCompassEnabled = true
            map.uiSettings.setCompassGravity(Gravity.TOP or Gravity.END)
            map.uiSettings.setCompassMargins(0, 88.dpToPx(requireContext()), 12.dpToPx(requireContext()), 0)

            val styleUrl = if (prefs.getBoolean(KEY_NIGHT_MODE, false)) STYLE_NIGHT else STYLE_DAY
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                setupAllLayers(style)
            }

            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(45.433, 12.333))
                .zoom(14.0)
                .build()

            // Scroll manuale: disattiva follow. Riattivare con RICENTRA.
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && followMode) {
                    followMode = false
                }
            }

            map.addOnMapLongClickListener { point ->
                setDestinationAndRoute(point)
                true
            }
        }
    }

    private fun Int.dpToPx(ctx: Context): Int =
        (this * ctx.resources.displayMetrics.density).toInt()

    private fun applyNightMode() {
        val night = prefs.getBoolean(KEY_NIGHT_MODE, false)
        mapLibre?.setStyle(Style.Builder().fromUri(if (night) STYLE_NIGHT else STYLE_DAY)) { style ->
            setupAllLayers(style)
        }
    }

    // =================================================================
    // LAYER
    // =================================================================

    private fun setupAllLayers(style: Style) {
        setupBoatIcon(style)
        setupLagunaLayers(style)
        if (debugMode) setupDebugLayers(style)
        setupGpsLayer(style)
        setupRouteLayer(style)
        setupDestinationLayer(style)
        activeRoute?.let { drawRouteSplit(style, it, currentWaypointIdx) }
        destination?.let { drawDestination(style, it) }
    }

    private fun setupDebugLayers(style: Style) {
        try {
            // Questi layer leggono dalla stessa sorgente laguna-source aggiunta da setupLagunaLayers
            style.addLayer(LineLayer("debug-nogo-layer", "laguna-source")
                .withFilter(any(eq(get("special:nav:area"), "no_go"), eq(get("special:nav:obstacle"), "rock")))
                .withProperties(lineColor("#FF0000"), lineWidth(2f), lineOpacity(0.8f)))
            style.addLayer(LineLayer("debug-bypass-sea-layer", "laguna-source")
                .withFilter(eq(get("special:nav:bypass"), "sea"))
                .withProperties(lineColor("#FFA500"), lineWidth(2.5f)))
            style.addLayer(LineLayer("debug-bypass-rock-layer", "laguna-source")
                .withFilter(eq(get("special:nav:bypass"), "rock"))
                .withProperties(lineColor("#800080"), lineWidth(2.5f)))
            style.addLayer(LineLayer("debug-gates-layer", "laguna-source")
                .withFilter(eq(get("special:nav:gate"), "sea"))
                .withProperties(lineColor("#00FF00"), lineWidth(3f)))
        } catch (_: Exception) {}
    }

    private fun setupBoatIcon(style: Style) {
        // PLACEHOLDER_BOAT_ICON: triangolo freccia puntato verso nord, ruota con il bearing GPS.
        // Sostituire il Bitmap generato qui con un'icona definitiva (barca vista dall'alto,
        // punta verso nord, sfondo trasparente, almeno 128x128px, colore a scelta).
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0066AA"); this.style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; this.style = Paint.Style.STROKE; strokeWidth = 4f }
        val path = Path().apply {
            moveTo(size / 2f, 2f); lineTo(size - 6f, size - 6f)
            lineTo(size / 2f, size * 0.72f); lineTo(6f, size - 6f); close()
        }
        canvas.drawPath(path, fill); canvas.drawPath(path, stroke)
        style.addImage(BOAT_ICON_ID, bmp)
    }

    private fun setupGpsLayer(style: Style) {
        style.addSource(GeoJsonSource(SOURCE_GPS, buildBoatGeoJson(45.433, 12.333, 0f)))
        style.addLayer(
            SymbolLayer(LAYER_GPS, SOURCE_GPS).withProperties(
                iconImage(BOAT_ICON_ID),
                iconRotate(get("bearing")),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconAllowOverlap(true), iconIgnorePlacement(true),
                iconSize(1.4f)
            )
        )
    }

    private fun setupRouteLayer(style: Style) {
        // Tratto percorso (grigio, semi-trasparente)
        style.addSource(GeoJsonSource(SOURCE_ROUTE_DONE, emptyFc()))
        style.addLayer(LineLayer(LAYER_ROUTE_DONE, SOURCE_ROUTE_DONE).withProperties(
            lineColor("#888888"), lineWidth(4f), lineOpacity(0.55f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        ))
        // Tratto rimanente (blu)
        style.addSource(GeoJsonSource(SOURCE_ROUTE, emptyFc()))
        style.addLayer(LineLayer(LAYER_ROUTE, SOURCE_ROUTE).withProperties(
            lineColor("#00008B"), lineWidth(5f), lineOpacity(0.9f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)
        ))
    }

    private fun setupDestinationLayer(style: Style) {
        style.addSource(GeoJsonSource(SOURCE_DEST, emptyFc()))
        style.addLayer(CircleLayer(LAYER_DEST, SOURCE_DEST).withProperties(
            circleColor("#CC0000"), circleRadius(12f),
            circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)
        ))
    }

    private fun setupLagunaLayers(style: Style) {
        try {
            val buf     = requireContext().assets.open("laguna_vettoriale.json").readBytes()
            val geoJson = String(buf, Charset.forName("UTF-8"))
            style.addSource(GeoJsonSource("laguna-source", geoJson))
            style.addLayer(LineLayer("canals-casing", "laguna-source")
                .withFilter(eq(get("type"), literal("canal")))
                .withProperties(lineColor(resources.getColor(R.color.white, null)), lineWidth(6f), lineOpacity(0.4f)))
            style.addLayer(LineLayer("canals-layer", "laguna-source")
                .withFilter(eq(get("type"), literal("canal")))
                .withProperties(lineColor(resources.getColor(R.color.marine_blue, null)), lineWidth(3.5f)))
            style.addLayer(LineLayer("rocks-layer", "laguna-source")
                .withFilter(eq(get("type"), literal("rock")))
                .withProperties(lineColor(Color.parseColor("#8B4513")), lineWidth(4f)))
            style.addLayer(LineLayer("boundary-layer", "laguna-source")
                .withFilter(eq(get("special:nav:boundary"), literal("project")))
                .withProperties(lineColor(Color.parseColor("#90EE90")), lineWidth(6f), lineOpacity(0.35f)))
            val briccole = CircleLayer("briccole-layer", "laguna-source")
                .withFilter(eq(get("type"), literal("briccola")))
                .withProperties(
                    circleColor(resources.getColor(R.color.marine_blue_dark, null)),
                    circleRadius(3.5f), circleStrokeColor(resources.getColor(R.color.white, null)), circleStrokeWidth(1f)
                )
            briccole.minZoom = 13f
            style.addLayer(briccole)
        } catch (e: Exception) { e.printStackTrace() }
    }

    // =================================================================
    // GPS / POSIZIONE
    // =================================================================

    private fun startPositionTracking() {
        if (SimulatorHub.isActive) {
            // Posizione simulata da DevTools: mi registro come listener
            SimulatorHub.addListener(simCallback)
        } else {
            startGnss()
        }
    }

    private fun stopPositionTracking() {
        SimulatorHub.removeListener(simCallback)
        gnssProvider?.stop()
        gnssProvider = null
    }

    private fun startGnss() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val p = GnssPositionProvider(requireContext())
            gnssProvider = p
            p.start { onGpsFix(it) }
        } else {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun onGpsFix(location: Location) {
        lastGpsLocation = location
        val bearing = if (location.hasBearing()) location.bearing else drBearing

        drFixTime  = System.currentTimeMillis()
        drLat      = location.latitude
        drLon      = location.longitude
        drSpeedMps = location.speed
        drBearing  = bearing

        mapLibre?.getStyle { style ->
            (style.getSource(SOURCE_GPS) as? GeoJsonSource)
                ?.setGeoJson(buildBoatGeoJson(location.latitude, location.longitude, bearing))
        }

        val pos = LatLng(location.latitude, location.longitude)
        updateHud(pos)
        checkSpeedHud(location, pos)
        updateNavigation(pos)
    }

    // =================================================================
    // CAMERA FLUIDA (30fps dead-reckoning)
    // =================================================================

    private fun startCameraLoop() {
        val r = object : Runnable {
            override fun run() {
                if (followMode && drFixTime > 0L) {
                    val elapsed    = (System.currentTimeMillis() - drFixTime) / 1000.0
                    val bearingRad = Math.toRadians(drBearing.toDouble())
                    val dist       = drSpeedMps * elapsed
                    val predLat    = drLat + dist * cos(bearingRad) / 111_111.0
                    val predLon    = drLon + dist * sin(bearingRad) / (111_111.0 * cos(Math.toRadians(drLat)))

                    val map = mapLibre
                    if (map != null) {
                        val zoom    = map.cameraPosition.zoom.coerceAtLeast(14.0)
                        // Course-up automatico: la mappa ruota con la barca solo quando si naviga
                        val bearing = if (activeRoute != null) drBearing.toDouble()
                                      else map.cameraPosition.bearing
                        map.moveCamera(CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder().target(LatLng(predLat, predLon)).zoom(zoom).bearing(bearing).build()
                        ))
                    }
                }
                cameraHandler.postDelayed(this, 33)
            }
        }
        cameraRunnable = r
        cameraHandler.post(r)
    }

    private fun stopCameraLoop() {
        cameraRunnable?.let { cameraHandler.removeCallbacks(it) }
        cameraRunnable = null
    }

    // =================================================================
    // HUD
    // =================================================================

    private fun updateHud(pos: LatLng) {
        val isAtSea    = routingEngine.isAtSea(pos)
        val isNoGo     = routingEngine.isPointInNoGo(pos)
        val fixedDepth = routingEngine.getFixedDepthAt(pos)

        val depthText: String; val hudColor: Int
        when {
            isNoGo -> {
                depthText = if (isAtSea) "Possibile Basso Fondale" else "Terraferma"
                hudColor  = resources.getColor(android.R.color.holo_red_dark, null)
            }
            fixedDepth != null -> {
                depthText = "Profondita': %.1f m".format(fixedDepth)
                hudColor  = resources.getColor(R.color.marine_blue_dark, null)
            }
            isAtSea -> {
                depthText = "Profondita': > 12 m"
                hudColor  = resources.getColor(R.color.marine_blue_dark, null)
            }
            else -> {
                val d = bathyEngine.getDepthAt(pos.latitude, pos.longitude, routingEngine.getNoGoAreas())
                depthText = "Profondita': %.1f m".format(d)
                hudColor  = if (d in 0.1f..1.2f) resources.getColor(android.R.color.holo_red_dark, null)
                            else resources.getColor(R.color.marine_blue_dark, null)
            }
        }
        binding.tvHudDepth.text = depthText
        binding.tvHudCoords.text = "%.5f, %.5f".format(pos.latitude, pos.longitude)
        binding.cvHud.setCardBackgroundColor(hudColor)
    }

    private fun checkSpeedHud(location: Location, pos: LatLng) {
        if (!location.hasSpeed()) { binding.tvHudSpeed.visibility = View.GONE; return }
        val speedKn   = location.speed * 3600f / 1852f
        val limitKn   = routingEngine.getMaxSpeedKnotsAt(pos)
        val distCanal = routingEngine.distanceToNearestCanalMeters(pos)
        val distStr   = if (distCanal < Double.MAX_VALUE / 2) " | %.0fm canal".format(distCanal) else ""

        binding.tvHudSpeed.visibility = View.VISIBLE
        if (limitKn != null) {
            val over = speedKn > limitKn
            binding.tvHudSpeed.text = "%.1f kn / Lim %.0f kn%s".format(speedKn, limitKn, distStr)
            binding.tvHudSpeed.setTextColor(
                if (over) resources.getColor(android.R.color.holo_red_light, null)
                else resources.getColor(R.color.sea_white, null)
            )
        } else {
            binding.tvHudSpeed.text = "%.1f kn%s".format(speedKn, distStr)
            binding.tvHudSpeed.setTextColor(resources.getColor(R.color.sea_white, null))
        }
    }

    // =================================================================
    // NAVIGAZIONE ATTIVA
    // =================================================================

    /** Imposta la destinazione e calcola il percorso. Esposto per DevToolsFragment. */
    fun setDestinationAndRoute(dest: LatLng) {
        destination = dest
        pendingSearchResult = null
        binding.cardSearchResult.visibility = View.GONE

        val startPos = lastGpsLocation?.let { LatLng(it.latitude, it.longitude) } ?: run {
            Snackbar.make(binding.root, "Attiva GPS o il simulatore in Dev Tools", Snackbar.LENGTH_LONG).show()
            return
        }

        val route = routingEngine.findRoute(startPos, dest)
        if (route == null) {
            Snackbar.make(binding.root, "Percorso non trovato: ${routingEngine.lastRoutingError}", Snackbar.LENGTH_LONG).show()
            return
        }
        activeRoute = route
        currentWaypointIdx = 0
        followMode = true
        

        mapLibre?.getStyle { style ->
            drawRouteSplit(style, route, 0)
            drawDestination(style, dest)
        }
        binding.cardNavBanner.visibility = View.VISIBLE
        binding.cardSearch.visibility    = View.GONE
        startBgReroute()
    }

    private fun updateNavigation(pos: LatLng) {
        val route = activeRoute ?: return
        if (currentWaypointIdx >= route.size) return

        val prevIdx = currentWaypointIdx

        // 1. Avanzamento standard: waypoint successivo entro 25m
        while (currentWaypointIdx < route.size - 1 &&
               haversineLocal(pos, route[currentWaypointIdx]) < WAYPOINT_ADVANCE_M) {
            currentWaypointIdx++
        }

        // 2. Snap al waypoint più vicino tra i prossimi 80 (gestisce "prendo larga e rientro"):
        //    se ci siamo ricongiunto al percorso più avanti, segna come percorso tutto il tratto
        //    che abbiamo saltato anche se non ci siamo passati sequenzialmente.
        val lookAheadLimit = minOf(currentWaypointIdx + 80, route.size - 1)
        var bestFwdIdx  = currentWaypointIdx
        var bestFwdDist = haversineLocal(pos, route[currentWaypointIdx])
        for (i in currentWaypointIdx + 1..lookAheadLimit) {
            val d = haversineLocal(pos, route[i])
            if (d < bestFwdDist && d < 150.0) { bestFwdDist = d; bestFwdIdx = i }
        }
        if (bestFwdIdx > currentWaypointIdx) currentWaypointIdx = bestFwdIdx

        if (currentWaypointIdx >= route.size - 1) { onRouteFinished(); return }

        // Proiezione del punto GPS sul segmento corrente → split fluido ad ogni fix (1Hz)
        val headPoint = if (currentWaypointIdx > 0) {
            closestPointOnRouteSegment(pos, route[currentWaypointIdx - 1], route[currentWaypointIdx])
        } else null
        mapLibre?.getStyle { style -> drawRouteSplit(style, route, currentWaypointIdx, headPoint) }

        val nextWp    = route[currentWaypointIdx]
        val distNext  = haversineLocal(pos, nextWp)
        val arrow     = bearingToArrow(bearingTo(pos, nextWp))
        val remaining = route.drop(currentWaypointIdx)
        val etaMin    = routingEngine.calculateEstimatedTimeMinutes(remaining)
        val distKm    = routingEngine.calculateTotalDistance(remaining) / 1000.0

        val distCanal = routingEngine.distanceToNearestCanalMeters(pos)
        val offCanal  = distCanal > OFF_CANAL_THRESHOLD_M && !routingEngine.isAtSea(pos)

        if (offCanal) {
            binding.cardNavBanner.setCardBackgroundColor(Color.parseColor("#CC880000"))
            binding.tvNavInstruction.text = "Fuori canale! (%.0f m)".format(distCanal)
            binding.tvNavDetail.text      = "ETA: %d min | %.1f km".format(etaMin, distKm)
            // Il ricalcolo è gestito dal background job ogni 5 secondi — nessun trigger qui
        } else {
            binding.cardNavBanner.setCardBackgroundColor(Color.parseColor("#CC003366"))
            binding.tvNavInstruction.text = "$arrow  %.0f m".format(distNext)
            binding.tvNavDetail.text      = "ETA: %d min | %.1f km".format(etaMin, distKm)
        }
    }

    /** Cancella il percorso attivo. Esposto per DevToolsFragment. */
    fun cancelRoute() {
        bgRerouteJob?.cancel()
        activeRoute = null; destination = null; currentWaypointIdx = 0
        followMode = false; 
        binding.cardNavBanner.visibility = View.GONE
        binding.cardSearch.visibility    = View.VISIBLE
        mapLibre?.getStyle { style ->
            (style.getSource(SOURCE_ROUTE)      as? GeoJsonSource)?.setGeoJson(emptyFc())
            (style.getSource(SOURCE_ROUTE_DONE) as? GeoJsonSource)?.setGeoJson(emptyFc())
            (style.getSource(SOURCE_DEST)       as? GeoJsonSource)?.setGeoJson(emptyFc())
        }
    }

    private fun onRouteFinished() {
        Snackbar.make(binding.root, "Sei arrivato a destinazione!", Snackbar.LENGTH_LONG).show()
        cancelRoute()
    }

    // =================================================================
    // RICERCA
    // =================================================================

    private fun setupSearch() {
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { performSearch(); true } else false
        }
        binding.btnSearch.setOnClickListener { performSearch() }
        binding.btnNavigateTo.setOnClickListener {
            pendingSearchResult?.let { setDestinationAndRoute(it) }
        }
    }

    private fun performSearch() {
        val query = binding.etSearch.text.toString().trim()
        if (query.isEmpty()) return
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { searchNominatim(query) } ?: return@launch
            pendingSearchResult = result.first
            binding.tvSearchResultName.text = result.second
            binding.cardSearchResult.visibility = View.VISIBLE
            mapLibre?.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(result.first).zoom(14.0).build()
                ), 1000
            )
        }
    }

    private suspend fun searchNominatim(query: String): Pair<LatLng, String>? {
        return try {
            val enc  = java.net.URLEncoder.encode(query, "UTF-8")
            val url  = "https://nominatim.openstreetmap.org/search?format=json&q=$enc&bounded=1&viewbox=11.7,45.65,12.85,45.05&limit=1"
            val conn = URL(url).openConnection()
            conn.setRequestProperty("User-Agent", "LagunaNav/1.0")
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val arr  = JSONArray(conn.getInputStream().bufferedReader().readText())
            if (arr.length() == 0) return null
            val obj  = arr.getJSONObject(0)
            val name = obj.getString("display_name").split(",").take(2).joinToString(", ")
            Pair(LatLng(obj.getDouble("lat"), obj.getDouble("lon")), name)
        } catch (_: Exception) { null }
    }

    // =================================================================
    // BOTTONE RICENTRA
    // =================================================================

    private fun setupButtons() {
        binding.fabRecentra.setOnClickListener {
            val loc = lastGpsLocation ?: return@setOnClickListener
            val pos = LatLng(loc.latitude, loc.longitude)
            // In navigazione: riattiva follow (la camera tornerà automaticamente con il loop)
            if (activeRoute != null) {
                followMode = true
                
            }
            // Snap immediato alla posizione barca
            mapLibre?.animateCamera(CameraUpdateFactory.newLatLng(pos), 500)
        }
        binding.btnCancelRoute.setOnClickListener { cancelRoute() }
    }

    // =================================================================
    // HELPER GEOJSON
    // =================================================================

    /** Disegna il percorso diviso in tratto percorso (grigio) e tratto rimanente (blu). */
    /**
     * Aggiorna la divisione grigio/blu del percorso.
     *
     * @param splitIdx indice del waypoint corrente (approssimazione per evento waypoint-advance)
     * @param headPoint se fornito, viene usato come "testa" della parte percorsa al posto
     *                  di route[splitIdx] — proiezione della posizione GPS sul segmento corrente
     *                  per uno split fluido che segue la barca metro per metro (aggiornato a 1Hz).
     */
    private fun drawRouteSplit(style: Style, route: List<LatLng>, splitIdx: Int, headPoint: LatLng? = null) {
        fun lineGeoJson(pts: List<LatLng>): String {
            val coords = JsonArray().also { arr -> pts.forEach { p -> arr.add(JsonArray().apply { add(p.longitude); add(p.latitude) }) } }
            val feat   = JsonObject().apply {
                addProperty("type","Feature"); add("properties", JsonObject())
                add("geometry", JsonObject().apply { addProperty("type","LineString"); add("coordinates", coords) })
            }
            return JsonObject().apply { addProperty("type","FeatureCollection"); add("features", JsonArray().apply { add(feat) }) }.toString()
        }
        val head = headPoint

        val done = if (head != null) {
            val pts = if (splitIdx > 0) route.subList(0, splitIdx).toMutableList() else mutableListOf()
            pts.add(head)
            pts
        } else {
            if (splitIdx > 0) route.subList(0, splitIdx + 1) else emptyList()
        }
        val remaining = if (head != null) {
            val pts = mutableListOf(head)
            if (splitIdx < route.size) pts.addAll(route.subList(splitIdx, route.size))
            pts
        } else {
            if (splitIdx < route.size) route.subList(splitIdx, route.size) else emptyList()
        }

        if (done.size >= 2)      (style.getSource(SOURCE_ROUTE_DONE) as? GeoJsonSource)?.setGeoJson(lineGeoJson(done))
        else                     (style.getSource(SOURCE_ROUTE_DONE) as? GeoJsonSource)?.setGeoJson(emptyFc())
        if (remaining.size >= 2) (style.getSource(SOURCE_ROUTE)      as? GeoJsonSource)?.setGeoJson(lineGeoJson(remaining))
        else                     (style.getSource(SOURCE_ROUTE)      as? GeoJsonSource)?.setGeoJson(emptyFc())
    }

    private fun closestPointOnRouteSegment(p: LatLng, a: LatLng, b: LatLng): LatLng {
        val l2 = (b.latitude - a.latitude) * (b.latitude - a.latitude) + (b.longitude - a.longitude) * (b.longitude - a.longitude)
        if (l2 == 0.0) return a
        var t = ((p.latitude - a.latitude) * (b.latitude - a.latitude) + (p.longitude - a.longitude) * (b.longitude - a.longitude)) / l2
        t = t.coerceIn(0.0, 1.0)
        return LatLng(a.latitude + t * (b.latitude - a.latitude), a.longitude + t * (b.longitude - a.longitude))
    }

    /**
     * Background job: ogni 5 secondi calcola il percorso ottimale dalla posizione corrente.
     * Se il nuovo percorso è significativamente più veloce dell'attuale tratto rimanente,
     * lo sostituisce silenziosamente (niente Snackbar invasivo, solo aggiornamento visivo).
     * Gestisce implicitamente anche i casi "fuori strada": il ricalcolo parte sempre dalla
     * posizione reale, quindi se la barca ha deviato trova automaticamente il percorso migliore.
     */
    private fun startBgReroute() {
        bgRerouteJob?.cancel()
        bgRerouteJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(BG_REROUTE_INTERVAL_MS)
                val pos  = lastGpsLocation?.let { LatLng(it.latitude, it.longitude) } ?: continue
                val dest = destination ?: continue
                val cur  = activeRoute ?: continue

                val newRoute = withContext(Dispatchers.Default) {
                    routingEngine.findRoute(pos, dest)
                } ?: continue

                val newTimeSec = routingEngine.calculateTotalTimeSeconds(newRoute)
                val curRemaining = if (currentWaypointIdx < cur.size) cur.drop(currentWaypointIdx) else emptyList()
                val curTimeSec = if (curRemaining.size >= 2) routingEngine.calculateTotalTimeSeconds(curRemaining) else Double.MAX_VALUE

                if (newTimeSec < curTimeSec * REROUTE_IMPROVEMENT_THRESHOLD) {
                    activeRoute = newRoute
                    currentWaypointIdx = 0
                    mapLibre?.getStyle { style -> drawRouteSplit(style, newRoute, 0) }
                }
            }
        }
    }

    private fun drawDestination(style: Style, dest: LatLng) {
        val feat = JsonObject().apply {
            addProperty("type","Feature"); add("properties", JsonObject())
            add("geometry", JsonObject().apply {
                addProperty("type","Point")
                add("coordinates", JsonArray().apply { add(dest.longitude); add(dest.latitude) })
            })
        }
        (style.getSource(SOURCE_DEST) as? GeoJsonSource)?.setGeoJson(
            JsonObject().apply { addProperty("type","FeatureCollection"); add("features", JsonArray().apply { add(feat) }) }.toString()
        )
    }

    private fun buildBoatGeoJson(lat: Double, lon: Double, bearing: Float): String =
        """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]},"properties":{"bearing":$bearing}}]}"""

    private fun emptyFc() = """{"type":"FeatureCollection","features":[]}"""

    // =================================================================
    // GEOMETRIA
    // =================================================================

    private fun haversineLocal(a: LatLng, b: LatLng): Double {
        val r    = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val x    = sin(dLat/2)*sin(dLat/2) + cos(Math.toRadians(a.latitude))*cos(Math.toRadians(b.latitude))*sin(dLon/2)*sin(dLon/2)
        return 2*r*atan2(sqrt(x), sqrt(1-x))
    }

    private fun bearingTo(from: LatLng, to: LatLng): Float {
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val lat1 = Math.toRadians(from.latitude); val lat2 = Math.toRadians(to.latitude)
        val y    = sin(dLon)*cos(lat2)
        val x    = cos(lat1)*sin(lat2) - sin(lat1)*cos(lat2)*cos(dLon)
        return ((Math.toDegrees(atan2(y, x)).toFloat() + 360f) % 360f)
    }

    private fun bearingToArrow(b: Float): String {
        val a = arrayOf("^","^>",">","v>","v","v<","<","^<")
        return a[((b + 22.5f)/45f).toInt() % 8]
    }

    // =================================================================
    // LIFECYCLE
    // =================================================================

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        startPositionTracking()
        startCameraLoop()
        // Riavvia il controllo percorso ottimale se c'è una navigazione attiva
        if (activeRoute != null && destination != null) startBgReroute()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        stopPositionTracking()
        stopCameraLoop()
        
        bgRerouteJob?.cancel()
    }

    override fun onStop() {
        super.onStop()
        binding.mapView.onStop()
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); binding.mapView.onSaveInstanceState(out) }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }
    override fun onDestroyView() {
        super.onDestroyView()
        binding.mapView.onDestroy()
        _binding = null
    }
}
