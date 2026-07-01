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
import kotlinx.coroutines.Dispatchers
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
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val PREFS_NAME            = "laguna_prefs"
private const val KEY_NIGHT_MODE        = "night_mode"
private const val KEY_MOB_PINS          = "mob_pins"
private const val STYLE_DAY             = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_NIGHT           = "https://tiles.openfreemap.org/styles/dark"
private const val BOAT_ICON_ID          = "boat-nav-icon"
private const val OFF_CANAL_THRESHOLD_M = 30.0   // distanza oltre la quale il banner diventa rosso
private const val WAYPOINT_ADVANCE_M    = 25.0    // distanza per avanzare al waypoint successivo

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private lateinit var bathyEngine: BathymetryEngine
    private lateinit var routingEngine: RoutingEngine
    private lateinit var positionProvider: PositionProvider
    private lateinit var prefs: SharedPreferences
    private var mapLibre: MapLibreMap? = null

    // GPS
    private var lastGpsLocation: Location? = null
    private var overSpeedAlerted = false

    // Modalità mappa
    private var followMode = true
    private var courseUpMode = false

    // Navigazione attiva
    private var activeRoute: List<LatLng>? = null
    private var destination: LatLng? = null
    private var currentWaypointIdx = 0

    // Ricerca
    private var pendingSearchResult: LatLng? = null

    // Sorgenti e layer
    private val SOURCE_GPS    = "gps-position-source"
    private val SOURCE_PINS   = "mob-pins-source"
    private val SOURCE_ROUTE  = "route-source"
    private val SOURCE_DEST   = "destination-source"
    private val LAYER_GPS     = "gps-position-layer"
    private val LAYER_PINS    = "mob-pins-layer"
    private val LAYER_ROUTE   = "route-layer"
    private val LAYER_DEST    = "destination-layer"

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startGps() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_NIGHT_MODE) applyNightMode()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        bathyEngine = BathymetryEngine(requireContext())
        routingEngine = RoutingEngine(requireContext())
        positionProvider = GnssPositionProvider(requireContext())

        setupMap(savedInstanceState)
        setupSearch()
        setupButtons()
        requestGpsIfNeeded()
    }

    // =================================================================
    // MAPPA
    // =================================================================

    private fun setupMap(savedInstanceState: Bundle?) {
        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync { map ->
            mapLibre = map
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false

            val styleUrl = if (prefs.getBoolean(KEY_NIGHT_MODE, false)) STYLE_NIGHT else STYLE_DAY
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                setupAllLayers(style)
            }

            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(45.433, 12.333))
                .zoom(14.0)
                .build()

            map.addOnCameraMoveListener {
                map.cameraPosition.target?.let { pos ->
                    if (!followMode) updateHud(pos)
                }
            }

            // Tap lungo sulla mappa -> imposta destinazione
            map.addOnMapLongClickListener { point ->
                setDestinationAndRoute(point)
                true
            }
        }
    }

    private fun applyNightMode() {
        val night = prefs.getBoolean(KEY_NIGHT_MODE, false)
        mapLibre?.setStyle(Style.Builder().fromUri(if (night) STYLE_NIGHT else STYLE_DAY)) { style ->
            setupAllLayers(style)
            activeRoute?.let { drawRoute(style, it) }
            destination?.let { drawDestination(style, it) }
        }
    }

    // =================================================================
    // LAYER
    // =================================================================

    private fun setupAllLayers(style: Style) {
        setupBoatIcon(style)
        setupLagunaLayers(style)
        setupGpsLayer(style)
        setupMobLayer(style)
        setupRouteLayer(style)
        setupDestinationLayer(style)
    }

    private fun setupBoatIcon(style: Style) {
        // PLACEHOLDER_BOAT_ICON: icona triangolo/freccia in alto che ruota con il bearing GPS.
        // Sostituire il Bitmap generato qui con un'icona definitiva (barca vista dall'alto,
        // punta verso nord, sfondo trasparente, almeno 128x128px).
        // Colore attuale: azzurro marino #0066AA con bordo bianco.
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0066AA")
            this.style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val path = Path().apply {
            moveTo(size / 2f, 2f)
            lineTo(size - 6f, size - 6f)
            lineTo(size / 2f, size * 0.72f)
            lineTo(6f, size - 6f)
            close()
        }
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
        style.addImage(BOAT_ICON_ID, bmp)
    }

    private fun setupGpsLayer(style: Style) {
        style.addSource(GeoJsonSource(SOURCE_GPS, buildBoatGeoJson(45.433, 12.333, 0f)))
        style.addLayer(
            SymbolLayer(LAYER_GPS, SOURCE_GPS).withProperties(
                iconImage(BOAT_ICON_ID),
                iconRotate(get("bearing")),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconSize(1.4f)
            )
        )
    }

    private fun setupMobLayer(style: Style) {
        val pins = loadPins().map { it to "mob" }
        style.addSource(GeoJsonSource(SOURCE_PINS, buildPinsGeoJson(pins)))
        style.addLayer(
            CircleLayer(LAYER_PINS, SOURCE_PINS).withFilter(eq(get("type"), literal("mob")))
                .withProperties(
                    circleColor("#FF3300"),
                    circleRadius(10f),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(2f)
                )
        )
    }

    private fun setupRouteLayer(style: Style) {
        style.addSource(GeoJsonSource(SOURCE_ROUTE, emptyFeatureCollection()))
        style.addLayer(
            LineLayer(LAYER_ROUTE, SOURCE_ROUTE).withProperties(
                lineColor("#00008B"),
                lineWidth(5f),
                lineOpacity(0.9f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND)
            )
        )
    }

    private fun setupDestinationLayer(style: Style) {
        style.addSource(GeoJsonSource(SOURCE_DEST, emptyFeatureCollection()))
        style.addLayer(
            CircleLayer(LAYER_DEST, SOURCE_DEST).withProperties(
                circleColor("#CC0000"),
                circleRadius(12f),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(3f)
            )
        )
    }

    private fun setupLagunaLayers(style: Style) {
        try {
            val buf = requireContext().assets.open("laguna_vettoriale.json").readBytes()
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
                    circleRadius(3.5f),
                    circleStrokeColor(resources.getColor(R.color.white, null)),
                    circleStrokeWidth(1f)
                )
            briccole.minZoom = 13f
            style.addLayer(briccole)
        } catch (e: Exception) { e.printStackTrace() }
    }

    // =================================================================
    // GPS
    // =================================================================

    private fun requestGpsIfNeeded() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) startGps()
        else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun startGps() { positionProvider.start { onGpsFix(it) } }

    private fun onGpsFix(location: Location) {
        lastGpsLocation = location
        val pos = LatLng(location.latitude, location.longitude)
        val bearing = if (location.hasBearing()) location.bearing else 0f

        // Aggiorna icona barca
        mapLibre?.getStyle { style ->
            (style.getSource(SOURCE_GPS) as? GeoJsonSource)
                ?.setGeoJson(buildBoatGeoJson(location.latitude, location.longitude, bearing))
        }

        // Camera
        if (followMode) animateCameraToGps(pos, bearing)

        updateHud(pos)
        checkSpeedAlert(location, pos)
        updateNavigation(pos)
    }

    private fun animateCameraToGps(pos: LatLng, bearing: Float) {
        val map = mapLibre ?: return
        val builder = CameraPosition.Builder()
            .target(pos)
            .zoom(map.cameraPosition.zoom.coerceAtLeast(14.0))
        if (courseUpMode) builder.bearing(bearing.toDouble())
        map.animateCamera(CameraUpdateFactory.newCameraPosition(builder.build()), 800)
    }

    // =================================================================
    // HUD
    // =================================================================

    private fun updateHud(target: LatLng) {
        val isAtSea    = routingEngine.isAtSea(target)
        val isNoGo     = routingEngine.isPointInNoGo(target)
        val fixedDepth = routingEngine.getFixedDepthAt(target)

        val depthText: String
        val hudColor: Int
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
                val depth = bathyEngine.getDepthAt(target.latitude, target.longitude, routingEngine.getNoGoAreas())
                depthText = "Profondita': %.1f m".format(depth)
                hudColor  = if (depth in 0.1f..1.2f) resources.getColor(android.R.color.holo_red_dark, null)
                            else resources.getColor(R.color.marine_blue_dark, null)
            }
        }
        binding.tvHudDepth.text = depthText
        binding.tvHudCoords.text = "%.5f, %.5f".format(target.latitude, target.longitude)
        binding.cvHud.setCardBackgroundColor(hudColor)
    }

    private fun checkSpeedAlert(location: Location, pos: LatLng) {
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
            if (over && !overSpeedAlerted) {
                overSpeedAlerted = true
                Snackbar.make(binding.root, "Velocita' limite superata (%.0f kn)".format(limitKn), Snackbar.LENGTH_LONG).show()
            } else if (!over) overSpeedAlerted = false
        } else {
            binding.tvHudSpeed.text = "%.1f kn%s".format(speedKn, distStr)
            binding.tvHudSpeed.setTextColor(resources.getColor(R.color.sea_white, null))
            overSpeedAlerted = false
        }
    }

    // =================================================================
    // NAVIGAZIONE ATTIVA
    // =================================================================

    private fun setDestinationAndRoute(dest: LatLng) {
        destination = dest
        pendingSearchResult = null
        binding.cardSearchResult.visibility = View.GONE

        val startPos = lastGpsLocation?.let { LatLng(it.latitude, it.longitude) }
            ?: mapLibre?.cameraPosition?.target ?: return

        val route = routingEngine.findRoute(startPos, dest)
        if (route == null) {
            Snackbar.make(binding.root, "Percorso non trovato: ${routingEngine.lastRoutingError}", Snackbar.LENGTH_LONG).show()
            return
        }
        activeRoute = route
        currentWaypointIdx = 0

        mapLibre?.getStyle { style ->
            drawRoute(style, route)
            drawDestination(style, dest)
        }
        showNavBanner()
        binding.cardSearch.visibility = View.GONE
        binding.ivCrosshair.visibility = View.GONE
    }

    private fun updateNavigation(pos: LatLng) {
        val route = activeRoute ?: return
        if (currentWaypointIdx >= route.size) return

        // Avanza al waypoint successivo se siamo abbastanza vicini
        while (currentWaypointIdx < route.size - 1 &&
               haversineLocal(pos, route[currentWaypointIdx]) < WAYPOINT_ADVANCE_M) {
            currentWaypointIdx++
        }

        if (currentWaypointIdx >= route.size - 1) {
            onRouteFinished()
            return
        }

        val nextWp    = route[currentWaypointIdx]
        val distNext  = haversineLocal(pos, nextWp)
        val bearing   = bearingTo(pos, nextWp)
        val arrow     = bearingToArrow(bearing)
        val remaining = route.drop(currentWaypointIdx)
        val etaMin    = routingEngine.calculateEstimatedTimeMinutes(remaining)
        val distTotal = routingEngine.calculateTotalDistance(remaining)

        val distCanal  = routingEngine.distanceToNearestCanalMeters(pos)
        val offCanal   = activeRoute != null && distCanal > OFF_CANAL_THRESHOLD_M && !routingEngine.isAtSea(pos)

        if (offCanal) {
            binding.cardNavBanner.setCardBackgroundColor(Color.parseColor("#CC880000"))
            binding.tvNavInstruction.text = "Fuori canale! (%.0f m)".format(distCanal)
            binding.tvNavDetail.text      = "ETA: %d min | %.1f km rimanenti".format(etaMin, distTotal / 1000.0)
        } else {
            binding.cardNavBanner.setCardBackgroundColor(Color.parseColor("#CC003366"))
            binding.tvNavInstruction.text = "$arrow  %.0f m".format(distNext)
            binding.tvNavDetail.text      = "ETA: %d min | %.1f km rimanenti".format(etaMin, distTotal / 1000.0)
        }
    }

    private fun showNavBanner() {
        binding.cardNavBanner.visibility = View.VISIBLE
    }

    private fun cancelRoute() {
        activeRoute = null
        destination = null
        currentWaypointIdx = 0
        binding.cardNavBanner.visibility = View.GONE
        binding.cardSearch.visibility    = View.VISIBLE
        binding.ivCrosshair.visibility   = View.VISIBLE
        mapLibre?.getStyle { style ->
            (style.getSource(SOURCE_ROUTE) as? GeoJsonSource)?.setGeoJson(emptyFeatureCollection())
            (style.getSource(SOURCE_DEST)  as? GeoJsonSource)?.setGeoJson(emptyFeatureCollection())
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
        hideKeyboard()

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
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            // viewbox: lon_min,lat_max,lon_max,lat_min (laguna di Venezia + zone adiacenti)
            val url = "https://nominatim.openstreetmap.org/search?format=json&q=$encoded&bounded=1&viewbox=11.7,45.65,12.85,45.05&limit=1"
            val conn = URL(url).openConnection()
            conn.setRequestProperty("User-Agent", "LagunaNav/1.0")
            conn.connectTimeout = 5000
            conn.readTimeout    = 5000
            val json = conn.getInputStream().bufferedReader().readText()
            val arr  = JSONArray(json)
            if (arr.length() == 0) return null
            val obj  = arr.getJSONObject(0)
            val lat  = obj.getDouble("lat")
            val lon  = obj.getDouble("lon")
            val name = obj.getString("display_name").split(",").take(2).joinToString(", ")
            Pair(LatLng(lat, lon), name)
        } catch (_: Exception) { null }
    }

    // =================================================================
    // BOTTONI
    // =================================================================

    private fun setupButtons() {
        // Follow mode
        binding.fabFollow.setOnClickListener {
            followMode = !followMode
            binding.fabFollow.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (followMode) Color.parseColor("#CC006699") else Color.parseColor("#CC003366")
            )
            if (followMode) lastGpsLocation?.let { loc ->
                animateCameraToGps(LatLng(loc.latitude, loc.longitude), loc.bearing)
            }
        }

        // Course Up
        binding.fabCourseUp.setOnClickListener {
            courseUpMode = !courseUpMode
            binding.fabCourseUp.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (courseUpMode) Color.parseColor("#CC006699") else Color.parseColor("#88003366")
            )
            if (!courseUpMode) {
                mapLibre?.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(mapLibre!!.cameraPosition.target)
                            .bearing(0.0).build()
                    ), 500
                )
            }
        }

        // Cancella navigazione
        binding.btnCancelRoute.setOnClickListener { cancelRoute() }

        // MOB
        binding.fabMob.setOnClickListener { savePin() }
    }

    // =================================================================
    // MOB PIN
    // =================================================================

    private fun savePin() {
        val pinPos = lastGpsLocation?.let { LatLng(it.latitude, it.longitude) }
            ?: mapLibre?.cameraPosition?.target
            ?: run {
                Snackbar.make(binding.root, "Nessuna posizione disponibile", Snackbar.LENGTH_SHORT).show()
                return
            }
        val label = if (lastGpsLocation == null) "Centro mappa" else "GPS"
        savePins(loadPins().toMutableList().also { it.add(pinPos) })
        refreshMobLayer()
        Snackbar.make(binding.root, "$label salvato (%.5f, %.5f)".format(pinPos.latitude, pinPos.longitude), Snackbar.LENGTH_SHORT).show()
    }

    private fun loadPins(): List<LatLng> {
        val json = prefs.getString(KEY_MOB_PINS, "[]") ?: "[]"
        return try {
            val arr = com.google.gson.JsonParser.parseString(json).asJsonArray
            arr.map { el ->
                val o = el.asJsonObject
                LatLng(o["lat"].asDouble, o["lon"].asDouble)
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun savePins(pins: List<LatLng>) {
        val arr = JsonArray()
        pins.forEach { p ->
            arr.add(JsonObject().apply { addProperty("lat", p.latitude); addProperty("lon", p.longitude) })
        }
        prefs.edit().putString(KEY_MOB_PINS, arr.toString()).apply()
    }

    private fun refreshMobLayer() {
        mapLibre?.getStyle { style ->
            (style.getSource(SOURCE_PINS) as? GeoJsonSource)
                ?.setGeoJson(buildPinsGeoJson(loadPins().map { it to "mob" }))
        }
    }

    // =================================================================
    // HELPER GeoJSON
    // =================================================================

    private fun drawRoute(style: Style, route: List<LatLng>) {
        val coords = JsonArray()
        route.forEach { p -> coords.add(JsonArray().apply { add(p.longitude); add(p.latitude) }) }
        val geom = JsonObject().apply { addProperty("type", "LineString"); add("coordinates", coords) }
        val feat = JsonObject().apply {
            addProperty("type", "Feature")
            add("properties", JsonObject())
            add("geometry", geom)
        }
        val fc = JsonObject().apply {
            addProperty("type", "FeatureCollection")
            add("features", JsonArray().apply { add(feat) })
        }
        (style.getSource(SOURCE_ROUTE) as? GeoJsonSource)?.setGeoJson(fc.toString())
    }

    private fun drawDestination(style: Style, dest: LatLng) {
        val coords = JsonArray().apply { add(dest.longitude); add(dest.latitude) }
        val geom   = JsonObject().apply { addProperty("type", "Point"); add("coordinates", coords) }
        val feat   = JsonObject().apply {
            addProperty("type", "Feature")
            add("properties", JsonObject())
            add("geometry", geom)
        }
        val fc = JsonObject().apply {
            addProperty("type", "FeatureCollection")
            add("features", JsonArray().apply { add(feat) })
        }
        (style.getSource(SOURCE_DEST) as? GeoJsonSource)?.setGeoJson(fc.toString())
    }

    private fun buildBoatGeoJson(lat: Double, lon: Double, bearing: Float): String {
        val coords = JsonArray().apply { add(lon); add(lat) }
        val geom   = JsonObject().apply { addProperty("type", "Point"); add("coordinates", coords) }
        val props  = JsonObject().apply { addProperty("bearing", bearing.toDouble()) }
        val feat   = JsonObject().apply {
            addProperty("type", "Feature")
            add("properties", props)
            add("geometry", geom)
        }
        return JsonObject().apply {
            addProperty("type", "FeatureCollection")
            add("features", JsonArray().apply { add(feat) })
        }.toString()
    }

    private fun buildPinsGeoJson(points: List<Pair<LatLng, String>>): String {
        val features = JsonArray()
        points.forEach { (p, type) ->
            val feat = JsonObject().apply {
                addProperty("type", "Feature")
                add("properties", JsonObject().apply { addProperty("type", type) })
                add("geometry", JsonObject().apply {
                    addProperty("type", "Point")
                    add("coordinates", JsonArray().apply { add(p.longitude); add(p.latitude) })
                })
            }
            features.add(feat)
        }
        return JsonObject().apply {
            addProperty("type", "FeatureCollection")
            add("features", features)
        }.toString()
    }

    private fun emptyFeatureCollection() =
        """{"type":"FeatureCollection","features":[]}"""

    // =================================================================
    // GEOMETRIA LOCALE
    // =================================================================

    private fun haversineLocal(a: LatLng, b: LatLng): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val sinA = sin(dLat / 2)
        val sinB = sin(dLon / 2)
        val x = sinA * sinA + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sinB * sinB
        return 2 * r * atan2(sqrt(x), sqrt(1 - x))
    }

    private fun bearingTo(from: LatLng, to: LatLng): Float {
        val dLon = Math.toRadians(to.longitude - from.longitude)
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)).toFloat() + 360f) % 360f)
    }

    private fun bearingToArrow(bearing: Float): String {
        // Frecce Unicode — approvate dal design (nessuna emoji)
        val arrows = arrayOf("^", "^>", ">", "v>", "v", "v<", "<", "^<")
        return arrows[((bearing + 22.5f) / 45f).toInt() % 8]
    }

    // =================================================================
    // UTILITY
    // =================================================================

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
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
        requestGpsIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        positionProvider.stop()
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
