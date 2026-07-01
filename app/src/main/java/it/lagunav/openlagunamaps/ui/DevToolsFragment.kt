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
import it.lagunav.openlagunamaps.engine.SimulatedPositionProvider
import it.lagunav.openlagunamaps.engine.SimulatorHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * Dev Tools = MapFragment (debugMode=true) embedded + overlay debug.
 *
 * Differenze rispetto a Mappa:
 * - Posizione sempre simulata (joystick sempre visibile, nessun GPS reale)
 * - Layer extra visibili: no-go (rosso), bypass (arancio/viola), gate (verde)
 * - Pannello debug con spinner modalità e bottoni specifici per tool
 * - Long-tap sulla mappa + [Imposta Fine] per testare percorsi da qualunque punto
 */
class DevToolsFragment : Fragment() {

    private var _binding: FragmentDevtoolsBinding? = null
    private val binding get() = _binding!!

    private var simProvider: SimulatedPositionProvider? = null
    private var childMap: MapFragment? = null

    // Modalità "Simula A→B": due punti indipendenti dalla posizione della barca
    private var simAbStart: LatLng? = null
    private var simAbEnd: LatLng? = null

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
        setupButtons()

        // Il simulatore parte automaticamente: il joystick è sempre visibile in Dev Tools
        startSimulator()
    }

    // =================================================================
    // SPINNER MODALITÀ
    // =================================================================

    private fun setupSpinner() {
        val modes = arrayOf(
            "Calcolo Percorso",
            "Test Punte (Tips)",
            "Simula Percorso A→B"
        )
        binding.spinnerDevMode.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, modes)

        binding.spinnerDevMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                binding.groupRouteButtons.visibility = if (position == 0) View.VISIBLE else View.GONE
                binding.btnTestTips.visibility       = if (position == 1) View.VISIBLE else View.GONE
                binding.groupSimAb.visibility        = if (position == 2) View.VISIBLE else View.GONE
                if (position != 2) { simAbStart = null; simAbEnd = null }
                if (position == 0) binding.tvDevStatus.text = "Simulatore pronto — joystick per muovere"
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // =================================================================
    // BOTTONI SPECIFICI PER MODALITÀ
    // =================================================================

    private fun setupButtons() {
        // Modalità Percorso: imposta la destinazione al centro camera attuale
        binding.btnSetEnd.setOnClickListener {
            val center = childMap?.cameraCenter() ?: run {
                binding.tvDevStatus.text = "Mappa non ancora pronta"
                return@setOnClickListener
            }
            childMap?.setDestinationAndRoute(center)
            binding.tvDevStatus.text = "Destinazione: %.5f, %.5f".format(center.latitude, center.longitude)
        }

        // Cancella percorso attivo
        binding.btnCancelRouteDev.setOnClickListener {
            childMap?.cancelRoute()
            binding.tvDevStatus.text = "Percorso cancellato"
        }

        // Modalità "Simula A→B": segna A (centro camera), segna B (centro camera), calcola
        binding.btnSetA.setOnClickListener {
            simAbStart = childMap?.cameraCenter() ?: return@setOnClickListener
            updateAbStatus()
        }
        binding.btnSetB.setOnClickListener {
            simAbEnd = childMap?.cameraCenter() ?: return@setOnClickListener
            updateAbStatus()
        }
        binding.btnCalcAb.setOnClickListener { calcAbRoute() }

        // Test Tips: calcola percorsi verso le 6 bocche di porto — BACKGROUND THREAD
        binding.btnTestTips.setOnClickListener {
            val engine = childMap?.routingEngine ?: return@setOnClickListener
            val pos    = childMap?.lastGpsLocation?.let { LatLng(it.latitude, it.longitude) } ?: run {
                binding.tvDevStatus.text = "Posizione non disponibile (muovi la barca)"
                return@setOnClickListener
            }
            binding.tvDevStatus.text = "Calcolo tips..."
            viewLifecycleOwner.lifecycleScope.launch {
                val results = withContext(Dispatchers.Default) { engine.pathsToTips(pos) }
                val sb = StringBuilder("Tips dalla posizione corrente:\n")
                results.forEachIndexed { i, r ->
                    if (r.path != null) {
                        val d = engine.calculateTotalDistance(r.path)
                        sb.append("Tip ${i+1}: %.2f km | %d min [PERCORSO REALE]\n".format(
                            d / 1000.0, engine.calculateEstimatedTimeMinutes(r.path)
                        ))
                    } else if (r.estimatedSeconds > 0) {
                        sb.append("Tip ${i+1}: ~${(r.estimatedSeconds / 60).toInt()} min [stima]\n")
                    } else {
                        sb.append("Tip ${i+1}: ${r.error}\n")
                    }
                }
                binding.tvDevStatus.text = sb.toString()
            }
        }
    }

    // =================================================================
    // MODALITÀ "SIMULA PERCORSO A→B"
    // =================================================================

    private fun updateAbStatus() {
        val a = simAbStart; val b = simAbEnd
        val aStr = if (a != null) "A: %.5f, %.5f".format(a.latitude, a.longitude) else "A: non impostato"
        val bStr = if (b != null) "B: %.5f, %.5f".format(b.latitude, b.longitude) else "B: non impostato"
        binding.tvAbResult.text = "$aStr\n$bStr"
        binding.tvAbResult.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
    }

    private fun calcAbRoute() {
        val engine = childMap?.routingEngine ?: return
        val start  = simAbStart ?: run { binding.tvAbResult.text = "Segna prima il punto A"; return }
        val end    = simAbEnd   ?: run { binding.tvAbResult.text = "Segna prima il punto B"; return }
        binding.tvAbResult.text = "Calcolo in corso..."
        binding.tvAbResult.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))

        viewLifecycleOwner.lifecycleScope.launch {
            val route = withContext(Dispatchers.Default) {
                // Calcola da A a B — non dalla posizione della barca
                engine.findRoute(start, end)
            }
            if (route == null) {
                binding.tvAbResult.text = "Nessun percorso: ${engine.lastRoutingError}"
                binding.tvAbResult.setTextColor(android.graphics.Color.parseColor("#FF4444"))
                childMap?.showPreviewRoute(null)
            } else {
                val distKm = engine.calculateTotalDistance(route) / 1000.0
                val etaMin = engine.calculateEstimatedTimeMinutes(route)
                val zoneA  = if (engine.isAtSea(start)) "MARE" else "LAGUNA"
                val zoneB  = if (engine.isAtSea(end))   "MARE" else "LAGUNA"
                binding.tvAbResult.text = "A [$zoneA] → B [$zoneB]\n%.2f km | %d min | %d punti".format(distKm, etaMin, route.size)
                binding.tvAbResult.setTextColor(android.graphics.Color.parseColor("#88FF88"))
                // Mostra come preview visivo (linea verde tratteggiata) — non avvia la navigazione
                childMap?.showPreviewRoute(route)
            }
        }
    }

    // =================================================================
    // SIMULATORE — SEMPRE ATTIVO IN DEV TOOLS
    // =================================================================

    private fun startSimulator() {
        val map = childMap
        val sim = SimulatedPositionProvider().apply {
            // Punto di partenza: ultima posizione nota oppure centro di Venezia
            map?.lastGpsLocation?.let { setPosition(it.latitude, it.longitude) }
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
            val camBearing = map?.mapLibreMap()?.cameraPosition?.bearing?.toFloat() ?: 0f
            val absBearing = (joyBearing + camBearing + 360f) % 360f
            sim.setMovement(absBearing, magnitude * 25f)
        }
    }

    private fun stopSimulator() {
        simProvider?.stop()
        SimulatorHub.provider = null
        simProvider = null
        binding.joystick.onMove = null
    }

    private fun onSimFix(location: Location) {
        val engine  = childMap?.routingEngine ?: return
        val pos     = LatLng(location.latitude, location.longitude)
        val speedKn = location.speed * 3600f / 1852f
        val limitKn = engine.getMaxSpeedKnotsAt(pos)
        val limitStr = if (limitKn != null) "Lim %.0f kn".format(limitKn) else "—"
        val isAtSea  = engine.isAtSea(pos)
        val dist     = engine.distanceToNearestCanalMeters(pos)
        val distStr  = if (dist < Double.MAX_VALUE / 2) "%.0f m canal".format(dist) else "—"
        val overLim  = limitKn != null && speedKn > limitKn

        val line = "%.5f, %.5f  [${if (isAtSea) "MARE" else "LAGUNA"}]\n%.1f kn  $limitStr  ${"%d°".format(location.bearing.roundToInt())}  $distStr".format(
            location.latitude, location.longitude, speedKn
        )
        // Aggiorna status solo nella modalità "Percorso" (non sovrascrivere info Tips/Zone)
        if (binding.spinnerDevMode.selectedItemPosition == 0) {
            binding.tvDevStatus.text = line
            binding.tvDevStatus.setTextColor(
                if (overLim) android.graphics.Color.parseColor("#FF4444")
                else android.graphics.Color.parseColor("#FF00FF")
            )
        }

        Log.d("SimDebug", "pos=${location.latitude},${location.longitude} " +
                "spd=${speedKn}kn brg=${location.bearing.roundToInt()}° " +
                "atSea=$isAtSea distCanal=${dist.toInt()}m overLimit=$overLim")
    }

    // =================================================================
    // LIFECYCLE
    // =================================================================

    override fun onPause() {
        super.onPause()
        // Teniamo il simulatore attivo via SimulatorHub anche se DevTools è in background
        // (MapFragment in Mappa lo ascolta via SimulatorHub.addListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopSimulator()
        _binding = null
    }
}
