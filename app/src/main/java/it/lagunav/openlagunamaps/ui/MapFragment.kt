package it.lagunav.openlagunamaps.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import it.lagunav.openlagunamaps.R
import it.lagunav.openlagunamaps.databinding.FragmentMapBinding
import it.lagunav.openlagunamaps.engine.BathymetryEngine
import it.lagunav.openlagunamaps.engine.RoutingEngine
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.sources.GeoJsonSource
import java.nio.charset.Charset
import java.util.Locale

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    
    private lateinit var bathyEngine: BathymetryEngine
    private lateinit var routingEngine: RoutingEngine

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        bathyEngine = BathymetryEngine(requireContext())
        routingEngine = RoutingEngine(requireContext())
        
        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync { map ->
            // Nascondiamo logo e attribuzione per pulire la UI
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isLogoEnabled = false

            val styleUrl = "https://tiles.openfreemap.org/styles/liberty"
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                setupLagunaLayers(style)
            }
            
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(45.433, 12.333))
                .zoom(13.0)
                .build()

            map.addOnCameraMoveListener {
                map.cameraPosition.target?.let { updateHud(it) }
            }
        }
    }

    private fun updateHud(target: LatLng) {
        val isAtSea = routingEngine.isAtSea(target)
        val isNoGo = routingEngine.isPointInNoGo(target)
        val fixedDepth = routingEngine.getFixedDepthAt(target)
        
        val depthText: String
        val color: Int

        when {
            isNoGo -> {
                depthText = if (isAtSea) "⚠️ ATTENZIONE: Possibile Basso Fondale" else "0.0 m (Terraferma)"
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

    private fun setupLagunaLayers(style: Style) {
        try {
            val inputStream = requireContext().assets.open("laguna_vettoriale.json")
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            val geoJsonData = String(buffer, Charset.forName("UTF-8"))
            
            val source = GeoJsonSource("laguna-source", geoJsonData)
            style.addSource(source)

            val canalsCasing = LineLayer("canals-casing", "laguna-source")
            canalsCasing.setProperties(
                lineColor(resources.getColor(R.color.white, null)),
                lineWidth(6f),
                lineOpacity(0.5f)
            )
            canalsCasing.setFilter(eq(get("type"), literal("canal")))
            style.addLayer(canalsCasing)

            val canalsLayer = LineLayer("canals-layer", "laguna-source")
            canalsLayer.setProperties(
                lineColor(resources.getColor(R.color.marine_blue, null)),
                lineWidth(3.5f),
                lineOpacity(1.0f)
            )
            canalsLayer.setFilter(eq(get("type"), literal("canal")))
            style.addLayer(canalsLayer)

            // Layer per le rocce e frangiflutti (Rosso scuro/Marrone)
            val rocksLayer = LineLayer("rocks-layer", "laguna-source")
            rocksLayer.setProperties(
                lineColor(android.graphics.Color.parseColor("#8B4513")),
                lineWidth(4f)
            )
            rocksLayer.setFilter(eq(get("type"), literal("rock")))
            style.addLayer(rocksLayer)

            // Layer per il confine del progetto (Verdino trasparente)
            val boundaryLayer = LineLayer("project-boundary-layer", "laguna-source")
            boundaryLayer.setProperties(
                lineColor(android.graphics.Color.parseColor("#90EE90")), // LightGreen
                lineWidth(8f),
                lineOpacity(0.4f)
            )
            boundaryLayer.setFilter(eq(get("special:nav:boundary"), literal("project")))
            style.addLayer(boundaryLayer)

            val briccoleLayer = CircleLayer("briccole-layer", "laguna-source")
            briccoleLayer.setProperties(
                circleColor(resources.getColor(R.color.marine_blue_dark, null)),
                circleRadius(4f),
                circleStrokeColor(resources.getColor(R.color.white, null)),
                circleStrokeWidth(1f)
            )
            briccoleLayer.setFilter(eq(get("type"), literal("briccola")))
            briccoleLayer.minZoom = 13f
            style.addLayer(briccoleLayer)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStart() { super.onStart(); binding.mapView.onStart() }
    override fun onResume() { super.onResume(); binding.mapView.onResume() }
    override fun onPause() { super.onPause(); binding.mapView.onPause() }
    override fun onStop() { super.onStop(); binding.mapView.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); binding.mapView.onSaveInstanceState(outState) }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }
    override fun onDestroyView() {
        super.onDestroyView()
        binding.mapView.onDestroy()
        _binding = null
    }
}
