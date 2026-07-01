package it.lagunav.openlagunamaps.ui

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import it.lagunav.openlagunamaps.R
import it.lagunav.openlagunamaps.databinding.FragmentMapBinding
import it.lagunav.openlagunamaps.engine.BathymetryEngine
import it.lagunav.openlagunamaps.engine.GnssPositionProvider
import it.lagunav.openlagunamaps.engine.PositionProvider
import it.lagunav.openlagunamaps.engine.RoutingEngine
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.sources.GeoJsonSource
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.charset.Charset
import java.util.Locale

private const val PREFS_NAME       = "laguna_prefs"
private const val KEY_NIGHT_MODE   = "night_mode"
private const val KEY_MOB_PINS     = "mob_pins"

private const val STYLE_DAY   = "https://tiles.openfreemap.org/styles/liberty"
private const val STYLE_NIGHT = "https://tiles.openfreemap.org/styles/dark"

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private lateinit var bathyEngine: BathymetryEngine
    private lateinit var routingEngine: RoutingEngine
    private lateinit var positionProvider: PositionProvider
    private lateinit var prefs: SharedPreferences

    private var mapLibre: MapLibreMap? = null
    private var lastGpsLocation: Location? = null
    private var overSpeedAlerted = false

    // Sorgente GeoJSON per il pallino GPS + pin MOB
    private val SOURCE_GPS  = "gps-position-source"
    private val SOURCE_PINS = "mob-pins-source"
    private val LAYER_GPS   = "gps-position-layer"
    private val LAYER_PINS  = "mob-pins-layer"

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

        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync { map ->
            mapLibre = map
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false

            val styleUrl = if (prefs.getBoolean(KEY_NIGHT_MODE, false)) STYLE_NIGHT else STYLE_DAY
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                setupLagunaLayers(style)
                setupGpsLayer(style)
                setupMobLayer(style)
            }

            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(45.433, 12.333))
                .zoom(13.0)
                .build()

            map.addOnCameraMoveListener {
                map.cameraPosition.target?.let { updateHud(it) }
            }
        }

        binding.fabMob.setOnClickListener { savePin() }

        requestGpsIfNeeded()
    }

    // =================================================================
    // GPS
    // =================================================================

    private fun requestGpsIfNeeded() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startGps()
        } else {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun startGps() {
        positionProvider.start { location -> onGpsFix(location) }
    }

    private fun onGpsFix(location: Location) {
        lastGpsLocation = location
        val pos = LatLng(location.latitude, location.longitude)

        // Aggiorna pallino GPS sulla mappa
        mapLibre?.getStyle { style ->
            val src = style.getSource(SOURCE_GPS) as? GeoJsonSource ?: return@getStyle
            src.setGeoJson(buildPointFeatureCollection(listOf(pos to "gps")))
        }

        updateHud(pos)
        checkSpeedAlert(location, pos)
    }

    // =================================================================
    // HUD e allarme velocità
    // =================================================================

    private fun updateHud(target: LatLng) {
        val isAtSea = routingEngine.isAtSea(target)
        val isNoGo  = routingEngine.isPointInNoGo(target)
        val fixedDepth = routingEngine.getFixedDepthAt(target)

        val depthText: String
        val color: Int
        when {
            isNoGo -> {
                depthText = if (isAtSea) "⚠️ Possibile Basso Fondale" else "Terraferma"
                color = resources.getColor(android.R.color.holo_red_dark, null)
            }
            fixedDepth != null -> {
                depthText = String.format(Locale.getDefault(), "Profondità: %.1f m", fixedDepth)
                color = resources.getColor(R.color.marine_blue_dark, null)
            }
            isAtSea -> {
                depthText = "Profondità: > 12 m"
                color = resources.getColor(R.color.marine_blue_dark, null)
            }
            else -> {
                val depth = bathyEngine.getDepthAt(target.latitude, target.longitude, routingEngine.getNoGoAreas())
                depthText = String.format(Locale.getDefault(), "Profondità: %.1f m", depth)
                color = if (depth in 0.1f..1.2f) resources.getColor(android.R.color.holo_red_dark, null)
                        else resources.getColor(R.color.marine_blue_dark, null)
            }
        }

        binding.tvHudDepth.text = depthText
        binding.tvHudCoords.text = String.format(Locale.getDefault(), "%.5f, %.5f", target.latitude, target.longitude)
        binding.cvHud.setCardBackgroundColor(color)
    }

    private fun checkSpeedAlert(location: Location, pos: LatLng) {
        if (!location.hasSpeed()) {
            binding.tvHudSpeed.visibility = View.GONE
            return
        }

        val gpsSpeedKn = location.speed * 3600f / 1852f
        val limitKn    = routingEngine.getMaxSpeedKnotsAt(pos)
        val distCanal  = routingEngine.distanceToNearestCanalMeters(pos)
        val distStr    = if (distCanal < Double.MAX_VALUE / 2) " | %.0fm canal".format(distCanal) else ""

        if (limitKn != null) {
            val over = gpsSpeedKn > limitKn
            binding.tvHudSpeed.visibility = View.VISIBLE
            binding.tvHudSpeed.text = String.format(
                Locale.getDefault(), "%.1f kn / Lim %.0f kn%s", gpsSpeedKn, limitKn, distStr
            )
            binding.tvHudSpeed.setTextColor(
                if (over) resources.getColor(android.R.color.holo_red_light, null)
                else      resources.getColor(R.color.sea_white, null)
            )
            if (over && !overSpeedAlerted) {
                overSpeedAlerted = true
                Snackbar.make(binding.root,
                    "⚠️ Velocità limite superata! (%.0f kn)".format(limitKn),
                    Snackbar.LENGTH_LONG).show()
            } else if (!over) {
                overSpeedAlerted = false
            }
        } else {
            binding.tvHudSpeed.visibility = View.VISIBLE
            binding.tvHudSpeed.text = String.format(
                Locale.getDefault(), "%.1f kn%s", gpsSpeedKn, distStr
            )
            binding.tvHudSpeed.setTextColor(resources.getColor(R.color.sea_white, null))
            overSpeedAlerted = false
        }
    }

    // =================================================================
    // Night mode
    // =================================================================

    private fun applyNightMode() {
        val night = prefs.getBoolean(KEY_NIGHT_MODE, false)
        mapLibre?.setStyle(Style.Builder().fromUri(if (night) STYLE_NIGHT else STYLE_DAY)) { style ->
            setupLagunaLayers(style)
            setupGpsLayer(style)
            setupMobLayer(style)
        }
    }

    // =================================================================
    // MOB — salva e mostra pin posizione
    // =================================================================

    private fun savePin() {
        // Se il GPS non è disponibile, usa il centro della mappa (il mirino)
        val pinPos: LatLng = lastGpsLocation?.let { LatLng(it.latitude, it.longitude) }
            ?: mapLibre?.cameraPosition?.target
            ?: run {
                Snackbar.make(binding.root, "Nessuna posizione disponibile", Snackbar.LENGTH_SHORT).show()
                return
            }
        val label = if (lastGpsLocation == null) "📍 Centro mappa" else "📍 GPS"
        val pins = loadPins().toMutableList()
        pins.add(pinPos)
        savePins(pins)
        refreshMobLayer()
        Snackbar.make(
            binding.root,
            "$label salvato (%.5f, %.5f)".format(pinPos.latitude, pinPos.longitude),
            Snackbar.LENGTH_SHORT
        ).show()
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
            val o = JsonObject().apply { addProperty("lat", p.latitude); addProperty("lon", p.longitude) }
            arr.add(o)
        }
        prefs.edit().putString(KEY_MOB_PINS, arr.toString()).apply()
    }

    private fun refreshMobLayer() {
        mapLibre?.getStyle { style ->
            val src = style.getSource(SOURCE_PINS) as? GeoJsonSource ?: return@getStyle
            val pins = loadPins().map { it to "mob" }
            src.setGeoJson(buildPointFeatureCollection(pins))
        }
    }

    // =================================================================
    // Setup layer mappa
    // =================================================================

    private fun buildPointFeatureCollection(points: List<Pair<LatLng, String>>): String {
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

    private fun setupGpsLayer(style: Style) {
        style.addSource(GeoJsonSource(SOURCE_GPS, buildPointFeatureCollection(emptyList())))
        style.addLayer(
            CircleLayer(LAYER_GPS, SOURCE_GPS)
                .withFilter(eq(get("type"), literal("gps")))
                .withProperties(
                    circleColor("#00AAFF"),
                    circleRadius(8f),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(2f)
                )
        )
    }

    private fun setupMobLayer(style: Style) {
        val pins = loadPins().map { it to "mob" }
        style.addSource(GeoJsonSource(SOURCE_PINS, buildPointFeatureCollection(pins)))
        style.addLayer(
            CircleLayer(LAYER_PINS, SOURCE_PINS)
                .withFilter(eq(get("type"), literal("mob")))
                .withProperties(
                    circleColor("#FF3300"),
                    circleRadius(10f),
                    circleStrokeColor("#FFFFFF"),
                    circleStrokeWidth(2f)
                )
        )
    }

    private fun setupLagunaLayers(style: Style) {
        try {
            val inputStream = requireContext().assets.open("laguna_vettoriale.json")
            val buffer = ByteArray(inputStream.available())
            inputStream.read(buffer); inputStream.close()
            val geoJsonData = String(buffer, Charset.forName("UTF-8"))

            style.addSource(GeoJsonSource("laguna-source", geoJsonData))

            style.addLayer(LineLayer("canals-casing", "laguna-source")
                .withFilter(eq(get("type"), literal("canal")))
                .withProperties(lineColor(resources.getColor(R.color.white, null)), lineWidth(6f), lineOpacity(0.5f)))

            style.addLayer(LineLayer("canals-layer", "laguna-source")
                .withFilter(eq(get("type"), literal("canal")))
                .withProperties(lineColor(resources.getColor(R.color.marine_blue, null)), lineWidth(3.5f)))

            style.addLayer(LineLayer("rocks-layer", "laguna-source")
                .withFilter(eq(get("type"), literal("rock")))
                .withProperties(lineColor(android.graphics.Color.parseColor("#8B4513")), lineWidth(4f)))

            style.addLayer(LineLayer("project-boundary-layer", "laguna-source")
                .withFilter(eq(get("special:nav:boundary"), literal("project")))
                .withProperties(lineColor(android.graphics.Color.parseColor("#90EE90")), lineWidth(8f), lineOpacity(0.4f)))

            val briccoleLayer = CircleLayer("briccole-layer", "laguna-source")
                .withFilter(eq(get("type"), literal("briccola")))
                .withProperties(
                    circleColor(resources.getColor(R.color.marine_blue_dark, null)),
                    circleRadius(4f),
                    circleStrokeColor(resources.getColor(R.color.white, null)),
                    circleStrokeWidth(1f)
                )
            briccoleLayer.minZoom = 13f
            style.addLayer(briccoleLayer)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // =================================================================
    // Lifecycle
    // =================================================================

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onResume() { super.onResume(); binding.mapView.onResume() }
    override fun onPause()  { super.onPause();  binding.mapView.onPause(); positionProvider.stop() }

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
