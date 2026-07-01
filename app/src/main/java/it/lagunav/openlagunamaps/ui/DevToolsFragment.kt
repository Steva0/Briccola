package it.lagunav.openlagunamaps.ui

import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import it.lagunav.openlagunamaps.databinding.FragmentDevtoolsBinding
import it.lagunav.openlagunamaps.engine.RoutingEngine
import it.lagunav.openlagunamaps.engine.SimulatedPositionProvider
import it.lagunav.openlagunamaps.engine.SimulatorHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Dev Tools = MapFragment con debugMode=true (layer extra + HUD esteso)
 * + questo overlay sottile con spinner modalita', stato simulatore e joystick.
 *
 * Nessuna mappa separata, nessun MapLibre proprio: la mappa sotto e' lo stesso
 * MapFragment usato in Mappa, embedded come child fragment.
 */
class DevToolsFragment : Fragment() {

    private var _binding: FragmentDevtoolsBinding? = null
    private val binding get() = _binding!!

    private var simProvider: SimulatedPositionProvider? = null
    private var childMap: MapFragment? = null

    // Accesso al routingEngine del MapFragment child per i calcoli di stato
    private val routingEngine: RoutingEngine? get() = childMap?.routingEngine

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDevtoolsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Embedded MapFragment con debugMode=true
        if (savedInstanceState == null) {
            val map = MapFragment().apply { debugMode = true }
            childMap = map
            childFragmentManager.beginTransaction()
                .replace(binding.mapContainerDev.id, map)
                .commit()
        } else {
            childMap = childFragmentManager.findFragmentById(binding.mapContainerDev.id) as? MapFragment
        }

        setupSpinner()
    }

    // =================================================================
    // SPINNER MODALITA'
    // =================================================================

    private fun setupSpinner() {
        val modes = arrayOf(
            "Calcolo Percorso",        // 0: usa MapFragment normalmente
            "Test Punte (Tips)",       // 1: mostra percorsi verso i tip
            "Zone Mare/Laguna",        // 2: overlay zone attivo in MapFragment
            "Simulatore Barca"         // 3: joystick + sim
        )
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, modes)
        binding.spinnerDevMode.adapter = adapter

        binding.spinnerDevMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    0 -> { stopSimulator(); binding.tvDevStatus.text = "Tap lungo sulla mappa per impostare destinazione" }
                    1 -> { stopSimulator(); testTips() }
                    2 -> { stopSimulator(); binding.tvDevStatus.text = "Layer zone attivo: blu=mare, giallo=laguna (visibile in debug)" }
                    3 -> startSimulator()
                }
                binding.cardSim.visibility = if (position == 3) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // =================================================================
    // SIMULATORE BARCA
    // =================================================================

    private fun startSimulator() {
        val map = childMap ?: return
        val sim = SimulatedPositionProvider().apply {
            // Punto di partenza: ultima posizione nota o centro Venezia
            map.lastGpsLocation?.let { setPosition(it.latitude, it.longitude) }
                ?: setPosition(45.4337, 12.3350)
        }
        simProvider = sim
        SimulatorHub.provider = sim

        sim.start { location ->
            SimulatorHub.notify(location)
            onSimFix(location)
        }

        binding.joystick.onMove = { normX, normY, magnitude ->
            val joyBearing = Math.toDegrees(atan2(normX.toDouble(), -normY.toDouble())).toFloat()
            // Compensa il bearing della camera (se in course-up la mappa è ruotata)
            val camBearing = map.mapLibreMap()?.cameraPosition?.bearing?.toFloat() ?: 0f
            val absBearing = (joyBearing + camBearing + 360f) % 360f
            sim.setMovement(absBearing, magnitude * 25f)
        }

        binding.tvDevStatus.text = "Simulatore attivo — Joystick per muovere la barca"
    }

    private fun stopSimulator() {
        simProvider?.stop()
        SimulatorHub.provider = null
        simProvider = null
        binding.joystick.onMove = null
    }

    private fun onSimFix(location: Location) {
        val pos      = LatLng(location.latitude, location.longitude)
        val engine   = routingEngine ?: return
        val speedKn  = location.speed * 3600f / 1852f
        val limitKn  = engine.getMaxSpeedKnotsAt(pos)
        val limitStr = if (limitKn != null) "Lim %.0f kn".format(limitKn) else "nessun limite"
        val isAtSea  = engine.isAtSea(pos)
        val dist     = engine.distanceToNearestCanalMeters(pos)
        val distStr  = if (dist < Double.MAX_VALUE / 2) "%.0f m canal".format(dist) else "--"
        val overLim  = limitKn != null && speedKn > limitKn

        binding.tvDevStatus.text = "%.6f, %.6f  [${if (isAtSea) "MARE" else "LAGUNA"}]\n%.1f kn  %s  heading %d°  %s".format(
            location.latitude, location.longitude,
            speedKn, limitStr,
            location.bearing.roundToInt(), distStr
        )
        binding.tvDevStatus.setTextColor(
            if (overLim) android.graphics.Color.parseColor("#FF4444")
            else android.graphics.Color.WHITE
        )

        Log.d("SimDebug", "pos=${location.latitude},${location.longitude} speed=${speedKn}kn bearing=${location.bearing} atSea=$isAtSea distCanal=${dist.toInt()}m")
    }

    // =================================================================
    // TEST TIPS
    // =================================================================

    private fun testTips() {
        val map    = childMap ?: return
        val engine = routingEngine ?: return
        val pos    = map.lastGpsLocation?.let { LatLng(it.latitude, it.longitude) } ?: run {
            binding.tvDevStatus.text = "Avvia simulatore prima per avere una posizione"
            return
        }
        binding.tvDevStatus.text = "Calcolo tips in corso..."

        // Eseguito su Dispatchers.Default: pathsToTips può fare A* sul grafo canali (lento
        // sul main thread → ANR crash). Su background thread è sicuro e non blocca la UI.
        viewLifecycleOwner.lifecycleScope.launch {
            val results = withContext(Dispatchers.Default) { engine.pathsToTips(pos) }
            val sb = StringBuilder("Tips dal punto corrente:\n")
            results.forEachIndexed { i, r ->
                if (r.path != null) {
                    val d = engine.calculateTotalDistance(r.path)
                    sb.append("Tip ${i+1}: %.2f km | %d min [PERCORSO REALE]\n".format(
                        d/1000.0, engine.calculateEstimatedTimeMinutes(r.path)
                    ))
                } else if (r.estimatedSeconds > 0) {
                    sb.append("Tip ${i+1}: ~${(r.estimatedSeconds/60).toInt()} min [stima, non ottimale]\n")
                } else {
                    sb.append("Tip ${i+1}: ${r.error}\n")
                }
            }
            binding.tvDevStatus.text = sb.toString()
        }
    }

    // =================================================================
    // LIFECYCLE
    // =================================================================

    override fun onPause() {
        super.onPause()
        simProvider?.stop()
    }

    override fun onResume() {
        super.onResume()
        if (simProvider != null) simProvider?.start { location ->
            SimulatorHub.notify(location)
            onSimFix(location)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopSimulator()
        _binding = null
    }
}
