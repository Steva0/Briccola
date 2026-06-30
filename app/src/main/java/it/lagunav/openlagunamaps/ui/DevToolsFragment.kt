package it.lagunav.openlagunamaps.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import it.lagunav.openlagunamaps.databinding.FragmentDevtoolsBinding
import it.lagunav.openlagunamaps.engine.BathymetryEngine
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
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.annotations.Polyline
import java.nio.charset.Charset
import java.util.Locale

class DevToolsFragment : Fragment() {

    private var _binding: FragmentDevtoolsBinding? = null
    private val binding get() = _binding!!

    private lateinit var bathyEngine: BathymetryEngine
    private lateinit var routingEngine: RoutingEngine
    private var mapLibre: MapLibreMap? = null

    private var startMarker: Marker? = null
    private var endMarker: Marker? = null
    private var routeLine: Polyline? = null
    private val candidateLines = mutableListOf<Polyline>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDevtoolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bathyEngine = BathymetryEngine(requireContext())
        routingEngine = RoutingEngine(requireContext())

        setupSpinner()

        binding.mapViewDev.onCreate(savedInstanceState)
        binding.mapViewDev.getMapAsync { map ->
            mapLibre = map
            // Nascondiamo logo e attribuzione
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false

            val styleUrl = "https://tiles.openfreemap.org/styles/liberty"
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                setupLagunaLayers(style)

                map.addOnCameraMoveListener {
                    map.cameraPosition.target?.let {
                        updateDevHud(it)
                        if (binding.cbShowZones.isChecked && binding.cbShowZones.visibility == View.VISIBLE) {
                            updateDebugZones(it, style)
                        } else {
                            removeDebugZones(style)
                        }
                    }
                }
            }

            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(45.433, 12.333))
                .zoom(13.0)
                .build()
        }

        binding.cbShowZones.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) mapLibre?.getStyle { removeDebugZones(it) }
        }

        binding.btnSetStart.setOnClickListener {
            mapLibre?.let { map ->
                val center = map.cameraPosition.target
                startMarker?.let { map.removeMarker(it) }
                startMarker = map.addMarker(MarkerOptions().position(center).title("Partenza"))
                calculateRouteIfPossible()
            }
        }

        binding.btnSetEnd.setOnClickListener {
            mapLibre?.let { map ->
                val center = map.cameraPosition.target
                endMarker?.let { map.removeMarker(it) }
                endMarker = map.addMarker(MarkerOptions().position(center).title("Arrivo"))
                calculateRouteIfPossible()
            }
        }

        binding.btnTestTips.setOnClickListener {
            mapLibre?.let { map ->
                val center = map.cameraPosition.target ?: return@let
                candidateLines.forEach { map.removePolyline(it) }
                candidateLines.clear()

                // TODO: Re-implementare visualizzazione dei percorsi verso i tips quando il pathfinding misto sarà pronto
                binding.tvDevStatus.text = "Test Tips temporaneamente disabilitato (In rifacimento)"
            }
        }

        binding.fabClearAll.setOnClickListener {
            mapLibre?.let { map ->
                startMarker?.let { map.removeMarker(it) }
                endMarker?.let { map.removeMarker(it) }
                routeLine?.let { map.removePolyline(it) }
                candidateLines.forEach { map.removePolyline(it) }
                startMarker = null
                endMarker = null
                routeLine = null
                candidateLines.clear()
                binding.tvDevRouting.text = "Inizio: No | Fine: No"
                binding.tvDevStatus.text = "MODALITÀ DEBUG"
            }
        }
    }

    private fun setupSpinner() {
        val modes = arrayOf("Calcolo Percorso", "Test Punte (Tips)", "Zone Mare/Laguna")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, modes)
        binding.spinnerDevMode.adapter = adapter

        binding.spinnerDevMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                binding.groupRoute.visibility = if (position == 0) View.VISIBLE else View.GONE
                binding.btnTestTips.visibility = if (position == 1) View.VISIBLE else View.GONE
                binding.cbShowZones.visibility = if (position == 2) View.VISIBLE else View.GONE

                mapLibre?.getStyle { style -> removeDebugZones(style) }
                candidateLines.forEach { mapLibre?.removePolyline(it) }
                candidateLines.clear()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateDevHud(target: LatLng) {
        val isAtSea = routingEngine.isAtSea(target)
        val isNoGo = routingEngine.isPointInNoGo(target)
        val fixedDepth = routingEngine.getFixedDepthAt(target)

        val depthText: String
        when {
            isNoGo -> depthText = if (isAtSea) "⚠️ ATTENZIONE: Possibile Basso Fondale" else "0.0 m (Terraferma)"
            fixedDepth != null -> depthText = String.format(Locale.getDefault(), "Profondità: %.1f m", fixedDepth)
            isAtSea -> depthText = "Profondità: > 12 m"
            else -> {
                val d = bathyEngine.getDepthAt(target.latitude, target.longitude, routingEngine.getNoGoAreas())
                depthText = String.format(Locale.getDefault(), "Profondità: %.1f m", d)
            }
        }

        binding.tvDevDepth.text = depthText
        val start = startMarker?.let { if (routingEngine.isAtSea(it.position)) "Sì (MARE)" else "Sì (LAGUNA)" } ?: "No"
        val end = endMarker?.let { if (routingEngine.isAtSea(it.position)) "Sì (MARE)" else "Sì (LAGUNA)" } ?: "No"
        binding.tvDevRouting.text = "Punti -> Start: $start | End: $end"
    }

    private fun updateDebugZones(center: LatLng, style: Style) {
        val step = 0.002
        val range = 8
        val features = com.google.gson.JsonArray()

        for (i in -range..range) {
            for (j in -range..range) {
                val lat = center.latitude + (i * step)
                val lon = center.longitude + (j * step)
                val isSea = routingEngine.isAtSea(LatLng(lat, lon))
                val f = com.google.gson.JsonObject()
                f.addProperty("type", "Feature")
                val p = com.google.gson.JsonObject()
                p.addProperty("color", if (isSea) "#0000FF" else "#FFFF00")
                f.add("properties", p)
                val g = com.google.gson.JsonObject()
                g.addProperty("type", "Point")
                val c = com.google.gson.JsonArray()
                c.add(lon); c.add(lat)
                g.add("coordinates", c)
                f.add("geometry", g)
                features.add(f)
            }
        }

        val fc = com.google.gson.JsonObject()
        fc.addProperty("type", "FeatureCollection")
        fc.add("features", features)

        removeDebugZones(style)
        style.addSource(GeoJsonSource("debug-zones-source", fc.toString()))
        style.addLayer(CircleLayer("debug-zones-layer", "debug-zones-source").withProperties(
            circleColor(get("color")), circleRadius(5f), circleOpacity(0.5f)
        ))
    }

    private fun removeDebugZones(style: Style) {
        style.removeLayer("debug-zones-layer")
        style.removeSource("debug-zones-source")
    }

    private fun calculateRouteIfPossible() {
        val start = startMarker?.position
        val end = endMarker?.position
        if (start != null && end != null) {
            val route = routingEngine.findRoute(start, end)
            mapLibre?.let { map ->
                routeLine?.let { map.removePolyline(it) }
                if (route != null) {
                    routeLine = map.addPolyline(PolylineOptions().addAll(route).color(android.graphics.Color.parseColor("#00008B")).width(6f))
                    val d = routingEngine.calculateTotalDistance(route)
                    val t = routingEngine.calculateEstimatedTimeMinutes(route)
                    binding.tvDevStatus.text = String.format(Locale.getDefault(), "ROUTING OK: %.2f km | %d min", d/1000.0, t)
                } else {
                    binding.tvDevStatus.text = "ERRORE: ${routingEngine.lastRoutingError}"
                }
            }
        }
    }

    private fun setupLagunaLayers(style: Style) {
        try {
            val inputStream = requireContext().assets.open("laguna_vettoriale.json")
            val buffer = ByteArray(inputStream.available())
            inputStream.read(buffer); inputStream.close()
            val geoJsonData = String(buffer, Charset.forName("UTF-8"))
            val source = GeoJsonSource("laguna-source-dev", geoJsonData)
            style.addSource(source)

            style.addLayer(LineLayer("obstacles-outline-dev", "laguna-source-dev").withFilter(any(eq(get("nav:area"), "no_go"), eq(get("nav:obstacle"), "rock"), eq(get("nav:obstade"), "rock"))).withProperties(lineColor("#FF0000"), lineWidth(3f)))
            style.addLayer(LineLayer("gates-layer-dev", "laguna-source-dev").withFilter(eq(get("nav:gate"), "sea")).withProperties(lineColor("#00FF00"), lineWidth(4f)))
            style.addLayer(LineLayer("bypass-sea-layer-dev", "laguna-source-dev").withFilter(eq(get("nav:bypass"), "sea")).withProperties(lineColor("#FFA500"), lineWidth(3f)))
            style.addLayer(LineLayer("bypass-rock-layer-dev", "laguna-source-dev").withFilter(eq(get("nav:bypass"), "rock")).withProperties(lineColor("#800080"), lineWidth(3f)))
            style.addLayer(LineLayer("canals-layer-dev", "laguna-source-dev").withFilter(eq(get("waterway"), "canal")).withProperties(lineColor("#FF00FF"), lineWidth(2f), lineOpacity(0.7f)))
            style.addLayer(LineLayer("mare-marker-dev", "laguna-source-dev").withFilter(eq(get("mare"), "yes")).withProperties(lineColor("#0000FF"), lineWidth(2f), lineDasharray(arrayOf(2f, 2f))))
            style.addLayer(LineLayer("laguna-marker-dev", "laguna-source-dev").withFilter(eq(get("laguna"), "yes")).withProperties(lineColor("#FFFF00"), lineWidth(2f), lineDasharray(arrayOf(2f, 2f))))
            style.addLayer(LineLayer("project-boundary-dev", "laguna-source-dev").withFilter(eq(get("nav:boundary"), "project")).withProperties(lineColor("#FFFF00"), lineWidth(4f), lineOpacity(0.6f)))
            style.addLayer(CircleLayer("briccole-layer-dev", "laguna-source-dev").withFilter(eq(geometryType(), literal("Point"))).withProperties(circleColor("#FFFF00"), circleRadius(3f)))
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onStart() { super.onStart(); binding.mapViewDev.onStart() }
    override fun onResume() { super.onResume(); binding.mapViewDev.onResume() }
    override fun onPause() { super.onPause(); binding.mapViewDev.onPause() }
    override fun onStop() { super.onStop(); binding.mapViewDev.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); binding.mapViewDev.onSaveInstanceState(outState) }
    override fun onLowMemory() { super.onLowMemory(); binding.mapViewDev.onLowMemory() }
    override fun onDestroyView() {
        super.onDestroyView()
        binding.mapViewDev.onDestroy()
        _binding = null
    }
}
