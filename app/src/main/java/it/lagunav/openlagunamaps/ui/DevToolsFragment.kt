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
import it.lagunav.openlagunamaps.engine.CameraTuning
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
        setupCameraSettingsPanel()
        setupPositionSourceToggle()

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
            "Simula Percorso A→B",
            "Settaggi Dev",
            "Posizione"
        )
        binding.spinnerDevMode.adapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, modes)

        binding.spinnerDevMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                binding.groupRouteButtons.visibility   = if (position == 0) View.VISIBLE else View.GONE
                binding.btnTestTips.visibility         = if (position == 1) View.VISIBLE else View.GONE
                binding.groupSimAb.visibility          = if (position == 2) View.VISIBLE else View.GONE
                binding.groupCameraSettings.visibility = if (position == 3) View.VISIBLE else View.GONE
                binding.groupSetPosition.visibility    = if (position == 4) View.VISIBLE else View.GONE
                if (position != 2) { simAbStart = null; simAbEnd = null }
                if (position == 0) binding.tvDevStatus.text = "Simulatore pronto — joystick per muovere"
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // =================================================================
    // TOGGLE SORGENTE POSIZIONE — simulata (joystick) o GPS reale del telefono
    // =================================================================

    /**
     * Permette di testare i valori di CameraTuning col GPS vero (es. guidando in auto) restando
     * su Dev Tools, invece di dover passare alla voce di menu "Mappa". Di default resta simulata:
     * il joystick continua a funzionare finché non si attiva il toggle.
     */
    private fun setupPositionSourceToggle() {
        binding.switchRealGps.setOnCheckedChangeListener { _, isChecked ->
            childMap?.setUseSimulatedPosition(!isChecked)
            binding.tvPositionSourceLabel.text =
                if (isChecked) "Posizione: reale (GPS telefono)" else "Posizione: simulata (joystick)"
            // Col GPS reale il joystick non serve (non controlla nulla): lo nascondiamo.
            binding.cardSim.visibility = if (isChecked) View.GONE else View.VISIBLE
        }
    }

    // =================================================================
    // IMPOSTAZIONI CAMERA — slider per tarare CameraTuning a runtime
    // =================================================================

    private fun setupCameraSettingsPanel() {
        CameraTuning.load(requireContext())

        fun refreshLabels() {
            binding.tvTuneRenderDelay.text = "Ritardo render: ${CameraTuning.renderDelayMs} ms"
            binding.tvTuneMinDisp.text     = "Distanza minima bearing: %.1f m".format(CameraTuning.minBearingDisplacementM)
            binding.tvTuneIconLerp.text    = "Lerp icona: %.2f".format(CameraTuning.iconBearingLerp)
            binding.tvTuneDeadZone.text    = "Zona morta camera: ±%.0f° (%.0f° totali)".format(
                CameraTuning.camDeadZoneDeg, CameraTuning.camDeadZoneDeg * 2
            )
            binding.tvTuneCamLerp.text     = "Lerp camera: %.2f".format(CameraTuning.camLerp)
            binding.tvTuneFps.text         = "FPS barca/camera: ${(1000.0 / CameraTuning.frameIntervalMs).roundToInt()}"
            binding.tvTuneHudRefresh.text  = "Refresh HUD (profondità/velocità/canale): ${(1000.0 / CameraTuning.hudIntervalMs).roundToInt()} Hz"
            binding.tvTuneCanalThreshold.text = "Soglia \"Fuori canale\": %.0f m".format(CameraTuning.canalLabelThresholdM)
        }

        fun syncHudSpeedLinkedUi() {
            binding.switchHudSpeedLinked.isChecked = CameraTuning.hudRefreshLinkedToSpeed
            // Lo slider manuale è ignorato mentre è collegato alla velocità: lo disabilitiamo
            // per non far credere che stia facendo qualcosa.
            binding.seekHudRefresh.isEnabled = !CameraTuning.hudRefreshLinkedToSpeed
            binding.seekHudRefresh.alpha     = if (CameraTuning.hudRefreshLinkedToSpeed) 0.4f else 1f
        }

        fun syncSeekBarsFromTuning() {
            binding.seekRenderDelay.progress = (CameraTuning.renderDelayMs / 50L).toInt().coerceIn(0, 60)
            binding.seekMinDisp.progress     = (CameraTuning.minBearingDisplacementM * 10).roundToInt().coerceIn(0, 150)
            binding.seekIconLerp.progress    = (CameraTuning.iconBearingLerp * 100).roundToInt().coerceIn(1, 50)
            binding.seekDeadZone.progress    = CameraTuning.camDeadZoneDeg.roundToInt().coerceIn(0, 30)
            binding.seekCamLerp.progress     = (CameraTuning.camLerp * 100).roundToInt().coerceIn(1, 30)
            binding.seekFps.progress         = (1000.0 / CameraTuning.frameIntervalMs).roundToInt().coerceIn(1, 60)
            binding.seekHudRefresh.progress  = (1000.0 / CameraTuning.hudIntervalMs).roundToInt().coerceIn(1, 20)
            binding.seekCanalThreshold.progress = CameraTuning.canalLabelThresholdM.roundToInt().coerceIn(0, 300)
            syncHudSpeedLinkedUi()
        }

        fun onChange(seek: android.widget.SeekBar, update: (Int) -> Unit) {
            seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) { update(progress); refreshLabels() }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) { CameraTuning.save(requireContext()) }
            })
        }

        syncSeekBarsFromTuning()
        refreshLabels()

        onChange(binding.seekRenderDelay) { CameraTuning.renderDelayMs = it * 50L }
        onChange(binding.seekMinDisp)     { CameraTuning.minBearingDisplacementM = it / 10.0 }
        onChange(binding.seekIconLerp)    { CameraTuning.iconBearingLerp = it.coerceAtLeast(1) / 100.0 }
        onChange(binding.seekDeadZone)    { CameraTuning.camDeadZoneDeg = it.toDouble() }
        onChange(binding.seekCamLerp)     { CameraTuning.camLerp = it.coerceAtLeast(1) / 100.0 }
        onChange(binding.seekFps)         { CameraTuning.frameIntervalMs = (1000.0 / it.coerceAtLeast(1)).roundToInt().toLong() }
        onChange(binding.seekHudRefresh)  { CameraTuning.hudIntervalMs = (1000.0 / it.coerceAtLeast(1)).roundToInt().toLong() }
        onChange(binding.seekCanalThreshold) { CameraTuning.canalLabelThresholdM = it.toDouble() }

        binding.switchHudSpeedLinked.setOnCheckedChangeListener { _, isChecked ->
            CameraTuning.hudRefreshLinkedToSpeed = isChecked
            CameraTuning.save(requireContext())
            syncHudSpeedLinkedUi()
        }

        binding.btnResetTuning.setOnClickListener {
            CameraTuning.resetToDefaults(requireContext())
            syncSeekBarsFromTuning()
            refreshLabels()
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

        // Modalità "Posizione": teletrasporta la barca simulata al centro schermo e la ferma
        binding.btnSetBoatPosition.setOnClickListener {
            val center = childMap?.cameraCenter() ?: return@setOnClickListener
            val provider = simProvider ?: return@setOnClickListener
            provider.setPosition(center.latitude, center.longitude)
            provider.setMovement(provider.bearingDeg, 0f)
            provider.emitNow()
            binding.tvDevStatus.text = "Barca posizionata: %.5f, %.5f".format(center.latitude, center.longitude)
        }

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
            val provider = simProvider
            if (provider != null) {
                if (magnitude < 0.05f) {
                    // Joystick al centro: ferma la barca mantenendo il bearing corrente.
                    // Senza questo, atan2(0,0)=0 + offset camera può girare la barca di 180°.
                    provider.setMovement(provider.bearingDeg, 0f)
                } else {
                    val joyBearing = Math.toDegrees(atan2(normX.toDouble(), -normY.toDouble())).toFloat()
                    val camBearing = childMap?.mapLibreMap()?.cameraPosition?.bearing?.toFloat() ?: 0f
                    val absBearing = (joyBearing + camBearing + 360f) % 360f
                    provider.setMovement(absBearing, magnitude * 25f)
                }
            }
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

    /**
     * Come in MapFragment: FragmentManager.hide() imposta la view a GONE, che per la MapView
     * incorporata (superficie OpenGL) può forzare la ricreazione del contesto grafico al
     * ritorno, causando uno scatto. INVISIBLE mantiene la superficie viva.
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        view?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        // Come in MapFragment: ad ogni cambio schermata la visuale torna centrata sulla barca.
        if (!hidden) childMap?.recenterFollow()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopSimulator()
        _binding = null
    }
}
