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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import it.lagunav.openlagunamaps.R
import it.lagunav.openlagunamaps.databinding.FragmentMapBinding
import it.lagunav.openlagunamaps.engine.BathymetryEngine
import it.lagunav.openlagunamaps.engine.CameraTuning
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

// Camera/icona: pipeline "solo GPS" (niente giroscopio, niente predizione in avanti).
// Mostriamo sempre la scena a "adesso meno CameraTuning.renderDelayMs", interpolata tra due
// fix GPS REALI — mai una posizione stimata. Il bearing si calcola dallo spostamento tra quei
// due stessi fix (non dal campo Location.bearing, rumoroso a bassa velocità).
// I valori di default sono in CameraTuning; regolabili a runtime da Dev Tools > Impostazioni Camera.

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

    // Buffer dei fix GPS reali (posizione + istante). Nessun sensore esterno: solo posizione.
    private data class Fix(val t: Long, val lat: Double, val lon: Double)
    private val fixBuffer = ArrayDeque<Fix>()

    // Ultimo bearing "buono" noto: aggiornato solo quando lo spostamento tra due fix è
    // sufficiente a fidarsene (vedi MIN_BEARING_DISPLACEMENT_M). Sotto soglia (fermi, GPS
    // che sballa, girata sul posto) resta invariato: niente rotazioni a caso.
    private var lastGoodBearing = 0.0

    // HUD (profondità/velocità/canale) e navigazione: aggiornati dal loop camera al ritmo
    // regolabile CameraTuning.hudIntervalMs, indipendente dai 30-45fps di camera/icona (i calcoli
    // di canale/profondità sono più pesanti e non serve rifarli ad ogni frame).
    private var lastHudUpdateMs = 0L

    // Icona barca: insegue lastGoodBearing con un lerp leggero, per ammorbidire il gradino
    // che si vedrebbe altrimenti a ogni cambio di fix (1 aggiornamento al secondo).
    private var smoothedIconBearing = 0.0

    // Camera: insegue l'icona solo fuori dal cono morto di ±CAM_DEAD_ZONE_DEG.
    private var smoothedCamBearing  = 0.0

    private val cameraHandler = Handler(Looper.getMainLooper())
    private var cameraRunnable: Runnable? = null

    // Follow mode: la camera segue la barca
    private var followMode = false

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
        CameraTuning.load(requireContext())

        setupMap(savedInstanceState)
        setupSearch()
        setupButtons()
        setFollowMode(true)  // di default la visuale segue la barca (anche alla prima apertura)
        binding.tvBuildTag.text = "v${it.lagunav.openlagunamaps.BuildConfig.VERSION_NAME} (${it.lagunav.openlagunamaps.BuildConfig.VERSION_CODE})"
    }

    /**
     * Il fragment resta vivo ma nascosto quando si cambia voce di menu (vedi MainActivity),
     * per evitare di ricreare la MapView ogni volta. Mettiamo in pausa GPS e loop camera solo
     * mentre è nascosto, per non consumare batteria/GPS inutilmente in background.
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // FragmentManager.hide() imposta la view a GONE: per la MapView (superficie OpenGL)
        // questo può forzare la distruzione/ricreazione del contesto grafico quando si torna
        // visibili, causando lo scatto percepito al rientro in Mappa. INVISIBLE mantiene la
        // superficie viva — il rientro resta fluido come le altre schermate.
        view?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE

        if (!isResumed) return  // evita doppio start/stop rispetto a onResume/onPause
        if (hidden) {
            // La Mappa vera (debugMode=false) continua a calcolare la posizione GPS reale anche
            // in background, pure mentre sei su un'altra schermata (es. Dev Tools): così quando
            // torni su Mappa non c'è da riavviare il GPS (la parte lenta, vedi GnssPositionProvider)
            // e il cambio schermata resta istantaneo. Costa un po' di batteria/GPS in più, ma
            // l'app è pensata per stare quasi sempre in modalità Mappa. In Dev Tools invece
            // (debugMode=true, posizione di solito simulata) non c'è motivo di tenerlo vivo.
            if (debugMode) stopPositionTracking()
            stopCameraLoop()
        } else {
            if (debugMode) startPositionTracking()
            startCameraLoop()
            setFollowMode(true)  // ad ogni cambio schermata la visuale torna centrata sulla barca
        }
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

            // Disabiliamo la bussola built-in di MapLibre e usiamo la nostra (card_compass):
            // così possiamo gestire il tap per uscire da follow mode + reset nord.
            map.uiSettings.isCompassEnabled = false

            val styleUrl = if (prefs.getBoolean(KEY_NIGHT_MODE, false)) STYLE_NIGHT else STYLE_DAY
            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                setupAllLayers(style)
            }

            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(45.433, 12.333))
                .zoom(14.0)
                .build()

            // Scroll manuale: stacca il follow e mostra il pulsante CENTRA
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && followMode) {
                    setFollowMode(false)
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

    // Di default: debugMode=true (MapFragment incorporata in Dev Tools) -> posizione simulata;
    // debugMode=false (voce di menu "Mappa") -> GPS reale. Non deve mai dipendere da
    // SimulatorHub.isActive, altrimenti se Dev Tools è stato aperto almeno una volta (e resta
    // vivo in background, vedi MainActivity) la Mappa mostrerebbe la posizione simulata invece
    // di quella reale.
    // In Dev Tools questo default può però essere sovrascritto a runtime dal toggle
    // "Posizione: simulata/reale", per poter testare i valori di CameraTuning col telefono vero
    // (es. guidando in auto) restando comunque sulla schermata Dev Tools.
    private var simulatedPositionOverride: Boolean? = null
    private val useSimulatedPosition: Boolean get() = simulatedPositionOverride ?: debugMode

    /** Esposto per DevToolsFragment: forza posizione simulata o reale a runtime. */
    fun setUseSimulatedPosition(simulated: Boolean) {
        if (simulatedPositionOverride == simulated) return
        val wasTracking = _binding != null && isResumed && !isHidden
        if (wasTracking) stopPositionTracking()  // ferma la sorgente VECCHIA
        simulatedPositionOverride = simulated
        fixBuffer.clear()  // niente fix misti tra due sorgenti diverse: eviterebbe salti assurdi
        if (wasTracking) startPositionTracking()  // riparte con la sorgente NUOVA
    }

    private fun startPositionTracking() {
        if (useSimulatedPosition) {
            SimulatorHub.addListener(simCallback)
        } else {
            startGnss()
        }
    }

    private fun stopPositionTracking() {
        if (useSimulatedPosition) {
            SimulatorHub.removeListener(simCallback)
        } else {
            gnssProvider?.stop()
            gnssProvider = null
        }
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
        val t = System.currentTimeMillis()
        fixBuffer.addLast(Fix(t, location.latitude, location.longitude))
        while (fixBuffer.size > 1 && t - fixBuffer.first().t > CameraTuning.fixBufferMaxMs) fixBuffer.removeFirst()
        // HUD (profondità/velocità/canale) e navigazione sono aggiornati dal loop camera a
        // CameraTuning.hudIntervalMs, non qui: legato al fix GPS sarebbero fermi a 1Hz.
    }

    // =================================================================
    // CAMERA FLUIDA — solo GPS, ritardo fisso, interpolazione tra fix reali
    // =================================================================

    /**
     * Trova i due fix che racchiudono [renderTime] e la frazione di interpolazione tra loro.
     * Mai estrapolazione: se il buffer non copre ancora [renderTime] (avvio app) o ha un solo
     * fix, restituisce quel fix così com'è.
     */
    private fun bracketFixes(renderTime: Long): Triple<Fix, Fix, Double>? {
        if (fixBuffer.isEmpty()) return null
        if (fixBuffer.size == 1) { val f = fixBuffer.first(); return Triple(f, f, 0.0) }
        if (renderTime <= fixBuffer.first().t) { val f = fixBuffer.first(); return Triple(f, f, 0.0) }
        var prev = fixBuffer.first()
        for (f in fixBuffer) {
            if (f.t >= renderTime) {
                val span = (f.t - prev.t).toDouble()
                val frac = if (span <= 0.0) 0.0 else (renderTime - prev.t) / span
                return Triple(prev, f, frac.coerceIn(0.0, 1.0))
            }
            prev = f
        }
        val last = fixBuffer.last(); return Triple(last, last, 0.0)
    }

    private fun startCameraLoop() {
        val r = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                val bracket = bracketFixes(now - CameraTuning.renderDelayMs)
                if (bracket != null) {
                    val (fixA, fixB, frac) = bracket
                    val interpLat = fixA.lat + (fixB.lat - fixA.lat) * frac
                    val interpLon = fixA.lon + (fixB.lon - fixA.lon) * frac
                    val interpPos = LatLng(interpLat, interpLon)

                    // HUD (profondità/velocità/canale) + navigazione, a ritmo indipendente da
                    // camera/icona. Se collegato alla velocità (CameraTuning.hudRefreshLinkedToSpeed),
                    // il refresh sale con la velocità reale della barca; altrimenti usa il valore
                    // fisso dello slider.
                    val speedKn = (lastGpsLocation?.speed ?: 0f) * 3600.0 / 1852.0
                    val hudInterval = CameraTuning.hudIntervalMsForSpeed(speedKn)
                    if (now - lastHudUpdateMs >= hudInterval) {
                        lastHudUpdateMs = now
                        updateHud(interpPos)
                        lastGpsLocation?.let { checkSpeedHud(it, interpPos) }
                        updateNavigation(interpPos)
                    }

                    // Bearing: solo se lo spostamento tra i due fix è sufficiente a fidarsene.
                    // Sotto soglia (fermi, GPS rumoroso, girata sul posto) non tocchiamo il bearing.
                    val posA = LatLng(fixA.lat, fixA.lon)
                    val posB = LatLng(fixB.lat, fixB.lon)
                    if (haversineLocal(posA, posB) >= CameraTuning.minBearingDisplacementM) {
                        lastGoodBearing = bearingTo(posA, posB).toDouble()
                    }

                    // Icona: insegue il bearing buono con un lerp leggero, ammorbidisce il gradino
                    // che si vedrebbe ogni cambio di fix (1 volta al secondo).
                    smoothedIconBearing = lerpBearing(smoothedIconBearing, lastGoodBearing, CameraTuning.iconBearingLerp)

                    // Camera: segue l'icona solo fuori dal cono morto (± CameraTuning.camDeadZoneDeg).
                    // Meno ottimale ma molto più fluida — niente inseguimento di ogni micro-variazione.
                    val camDiff = Math.abs(((smoothedIconBearing - smoothedCamBearing + 540) % 360) - 180)
                    if (camDiff > CameraTuning.camDeadZoneDeg) {
                        smoothedCamBearing = lerpBearing(smoothedCamBearing, smoothedIconBearing, CameraTuning.camLerp)
                    }

                    val map = mapLibre
                    if (map != null) {
                        // 1. Camera (solo follow mode)
                        if (followMode) {
                            val zoom = map.cameraPosition.zoom.coerceAtLeast(14.0)
                            map.moveCamera(CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder().target(interpPos).zoom(zoom).bearing(smoothedCamBearing).build()
                            ))
                        }

                        // 2. Bussola: usa il bearing REALE della camera (non il bearing barca)
                        val actualCamBearing = if (followMode) smoothedCamBearing
                                               else map.cameraPosition.bearing
                        val showCompass = Math.abs(actualCamBearing % 360) > 2.0
                        _binding?.let { b ->
                            b.cardCompass.visibility = if (showCompass) View.VISIBLE else View.GONE
                            b.cardCompass.rotation   = (-actualCamBearing).toFloat()
                        }

                        // 3. Icona barca + split percorso a 30fps, sulla posizione interpolata
                        map.getStyle { style ->
                            (style.getSource(SOURCE_GPS) as? GeoJsonSource)
                                ?.setGeoJson(buildBoatGeoJson(interpLat, interpLon, smoothedIconBearing.toFloat()))

                            val route = activeRoute
                            if (route != null && currentWaypointIdx > 0 && currentWaypointIdx < route.size) {
                                val head = closestPointOnRouteSegment(
                                    interpPos, route[currentWaypointIdx - 1], route[currentWaypointIdx]
                                )
                                drawRouteSplit(style, route, currentWaypointIdx, head)
                            }
                        }
                    }
                }
                cameraHandler.postDelayed(this, CameraTuning.frameIntervalMs)
            }
        }
        cameraRunnable = r
        cameraHandler.post(r)
    }

    /** Interpolazione lineare del bearing che gestisce il wrap 0°/360°. */
    private fun lerpBearing(from: Double, to: Double, t: Double): Double {
        val diff = ((to - from + 540.0) % 360.0) - 180.0
        return (from + diff * t + 360.0) % 360.0
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

        // Riga in alto (grande): "dove sono" — nome canale, Mare, o l'avviso della zona no-go.
        // Riga in basso (piccola): profondità. Prima era il contrario (profondità sopra, lat/lon
        // sotto): il nome/luogo è l'informazione più utile a colpo d'occhio mentre si naviga.
        val locationText: String; val depthText: String; val hudColor: Int
        when {
            isNoGo -> {
                locationText = if (isAtSea) "Possibile Basso Fondale" else "Terraferma"
                depthText    = "—"
                hudColor     = resources.getColor(android.R.color.holo_red_dark, null)
            }
            fixedDepth != null -> {
                locationText = if (isAtSea) "Mare" else canalLocationLabel(pos)
                depthText    = "Profondita': %.1f m".format(fixedDepth)
                hudColor     = resources.getColor(R.color.marine_blue_dark, null)
            }
            isAtSea -> {
                locationText = "Mare"
                depthText    = "Profondita': > 12 m"
                hudColor     = resources.getColor(R.color.marine_blue_dark, null)
            }
            else -> {
                val d = bathyEngine.getDepthAt(pos.latitude, pos.longitude, routingEngine.getNoGoAreas())
                locationText = canalLocationLabel(pos)
                depthText    = "Profondita': %.1f m".format(d)
                hudColor     = if (d in 0.1f..1.2f) resources.getColor(android.R.color.holo_red_dark, null)
                               else resources.getColor(R.color.marine_blue_dark, null)
            }
        }
        binding.tvHudDepth.text = locationText
        binding.tvHudCoords.text = depthText
        binding.cvHud.setCardBackgroundColor(hudColor)
    }

    /** "Fuori canale" se troppo lontani da qualsiasi canale; altrimenti il nome, o "Canale
     *  sconosciuto" se il canale più vicino non ha un tag nome nei dati OSM. */
    private fun canalLocationLabel(pos: LatLng): String {
        val distCanal = routingEngine.distanceToNearestCanalMeters(pos)
        if (distCanal > CameraTuning.canalLabelThresholdM) return "Fuori canale"
        return routingEngine.nearestCanalName(pos, CameraTuning.canalLabelThresholdM) ?: "Canale sconosciuto"
    }

    private fun checkSpeedHud(location: Location, pos: LatLng) {
        if (!location.hasSpeed()) { binding.tvHudSpeed.visibility = View.GONE; return }
        val speedKn   = location.speed * 3600f / 1852f
        val speedKmh  = location.speed * 3.6f
        val limitKn   = routingEngine.getMaxSpeedKnotsAt(pos)
        val distCanal = routingEngine.distanceToNearestCanalMeters(pos)
        val distStr   = if (distCanal < Double.MAX_VALUE / 2) " | %.0fm canal".format(distCanal) else ""

        binding.tvHudSpeed.visibility = View.VISIBLE
        if (limitKn != null) {
            val over = speedKn > limitKn
            binding.tvHudSpeed.text = "%.1f kn (%.0f km/h) / Lim %.0f kn%s".format(speedKn, speedKmh, limitKn, distStr)
            binding.tvHudSpeed.setTextColor(
                if (over) resources.getColor(android.R.color.holo_red_light, null)
                else resources.getColor(R.color.sea_white, null)
            )
        } else {
            binding.tvHudSpeed.text = "%.1f kn (%.0f km/h)%s".format(speedKn, speedKmh, distStr)
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

        val startPos = lastGpsLocation?.let { LatLng(it.latitude, it.longitude) } ?: return

        val route = routingEngine.findRoute(startPos, dest)
        if (route == null) return  // errore silenzioso — lo stato viene mostrato nell'HUD
        activeRoute = route
        currentWaypointIdx = 0
        setFollowMode(true)


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
        setFollowMode(false)
        binding.cardNavBanner.visibility = View.GONE
        binding.cardSearch.visibility    = View.VISIBLE
        mapLibre?.getStyle { style ->
            (style.getSource(SOURCE_ROUTE)      as? GeoJsonSource)?.setGeoJson(emptyFc())
            (style.getSource(SOURCE_ROUTE_DONE) as? GeoJsonSource)?.setGeoJson(emptyFc())
            (style.getSource(SOURCE_DEST)       as? GeoJsonSource)?.setGeoJson(emptyFc())
        }
    }

    private fun onRouteFinished() {
        // Nessun avviso popup — il banner scomparirà e l'utente vedrà la barca alla destinazione
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
    // BOTTONE CENTRA e gestione follow mode
    // =================================================================

    /** Riattiva il follow mode (camera centrata sulla barca). Esposto per DevToolsFragment,
     *  che lo richiama quando l'utente rientra sulla schermata Dev Tools. */
    fun recenterFollow() = setFollowMode(true)

    /** Imposta il follow mode e aggiorna la visibilità del pulsante CENTRA. */
    private fun setFollowMode(active: Boolean) {
        val reentering = active && !followMode
        followMode = active
        // CENTRA: visibile solo quando NON stai seguendo la barca
        _binding?.layoutCentra?.visibility = if (active) View.GONE else View.VISIBLE

        // Rientro in follow mode (tasto CENTRA o nuova rotta): transizione dolce invece di uno
        // scatto istantaneo verso la posizione/bearing della barca.
        if (reentering) {
            val map = mapLibre
            val lastFix = fixBuffer.lastOrNull()
            if (map != null && lastFix != null) {
                val zoom = map.cameraPosition.zoom.coerceAtLeast(14.0)
                map.animateCamera(CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(lastFix.lat, lastFix.lon)).zoom(zoom).bearing(smoothedCamBearing).build()
                ), 500)
            }
        }
    }

    private fun setupButtons() {
        // CENTRA: riattiva follow mode → pulsante sparisce
        binding.fabRecentra.setOnClickListener {
            setFollowMode(true)
        }

        // Bussola custom: tap → esci da follow mode e resetta la mappa verso nord
        binding.cardCompass.setOnClickListener {
            setFollowMode(false)
            mapLibre?.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().bearing(0.0).build()
                ), 500
            )
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
