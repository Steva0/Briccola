package it.lagunav.openlagunamaps.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import it.lagunav.openlagunamaps.R
import it.lagunav.openlagunamaps.databinding.FragmentMapBinding
import it.lagunav.openlagunamaps.engine.BathymetryEngine
import it.lagunav.openlagunamaps.engine.CameraTuning
import it.lagunav.openlagunamaps.engine.GnssPositionProvider
import it.lagunav.openlagunamaps.engine.PerfMonitor
import it.lagunav.openlagunamaps.engine.PlaceType
import it.lagunav.openlagunamaps.engine.PlacesStore
import it.lagunav.openlagunamaps.engine.NavigationLimits
import it.lagunav.openlagunamaps.engine.PositionProvider
import it.lagunav.openlagunamaps.engine.RoutingEngine
import it.lagunav.openlagunamaps.engine.SavedPlace
import it.lagunav.openlagunamaps.engine.SimulatorHub
import it.lagunav.openlagunamaps.engine.SpeedUnit
import it.lagunav.openlagunamaps.engine.TideEngine
import it.lagunav.openlagunamaps.engine.UiTuning
import it.lagunav.openlagunamaps.engine.toLatLng
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
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import java.net.URL
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val PREFS_NAME            = "laguna_prefs"
private const val KEY_MOB_PINS          = "mob_pins"
// Non privato: serve anche a DevToolsFragment per definire la regione da scaricare offline
// con lo stesso identico stile usato dalla mappa (l'offline pack è specifico per styleURL).
const val STYLE_DAY                     = "https://tiles.openfreemap.org/styles/liberty"
private const val BOAT_ICON_ID          = "boat-nav-icon"
private const val OFF_CANAL_THRESHOLD_M  = 30.0
private const val SAVED_PLACE_TAP_RADIUS_DP = 28f  // tap entro questa distanza (in pixel schermo) da un pallino -> lo apre
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
                    lineWidth(5f),
                    lineOpacity(0.9f),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND)
                ))
            }
        }
    }

    /** Disegna (o rimuove, passando null) il rettangolo del confine della regione offline
     *  scaricata — a zoom basso i tile coprono aree enormi (l'intera area visibile può
     *  ricadere dentro tile che intersecano il bbox richiesto, anche se molto più grande della
     *  laguna), quindi lo sfondo nero da solo non basta a capire cosa è stato davvero incluso:
     *  questo contorno mostra il bbox esatto usato per il download, indipendentemente dallo zoom. */
    fun showOfflineRegionBoundary(bounds: Pair<LatLng, LatLng>?) {
        val geoJson = if (bounds != null) {
            val (sw, ne) = bounds
            val ring = listOf(
                sw, LatLng(sw.latitude, ne.longitude), ne, LatLng(ne.latitude, sw.longitude), sw
            )
            val coords = JsonArray().also { arr -> ring.forEach { p -> arr.add(JsonArray().apply { add(p.longitude); add(p.latitude) }) } }
            val feat = JsonObject().apply {
                addProperty("type", "Feature"); add("properties", JsonObject())
                add("geometry", JsonObject().apply { addProperty("type", "LineString"); add("coordinates", coords) })
            }
            JsonObject().apply { addProperty("type", "FeatureCollection"); add("features", JsonArray().apply { add(feat) }) }.toString()
        } else emptyFc()

        mapLibre?.getStyle { style ->
            val existing = style.getSource(SOURCE_OFFLINE_BOUNDARY) as? GeoJsonSource
            if (existing != null) {
                existing.setGeoJson(geoJson)
            } else {
                style.addSource(GeoJsonSource(SOURCE_OFFLINE_BOUNDARY, geoJson))
                style.addLayer(LineLayer(LAYER_OFFLINE_BOUNDARY, SOURCE_OFFLINE_BOUNDARY).withProperties(
                    lineColor("#FF00FF"),
                    lineWidth(3f),
                    lineOpacity(0.9f)
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

    // Livello di marea corrente (metri sullo Zero di Punta della Salute), usato per correggere
    // il valore di profondità mostrato in HUD. Aggiornato periodicamente in background: la
    // chiamata è di rete (o lettura asset in fallback), quindi mai fatta in modo sincrono da
    // updateHud che gira fino a ~20Hz.
    private var cachedTideM = 0.0
    private var lastTideFetchMs = 0L
    private val TIDE_REFRESH_MS = 5 * 60_000L
    private lateinit var prefs: SharedPreferences
    private var mapLibre: MapLibreMap? = null
    // Cache dello Style corrente, aggiornata quando viene caricato (setupMap). Usata SOLO nel
    // loop camera (runFrame, fino a ~45 volte al secondo): con
    // map.getStyle{} invece della cache, ogni fotogramma metteva in coda una nuova callback
    // asincrona sul thread principale — a quella frequenza il backlog di callback in coda non
    // faceva mai in tempo a svuotarsi (ogni "cameraLoop.frame" costava già 8-30ms), causando
    // aggiornamenti dell'icona fuori ordine/in ritardo percepiti come scatti, indipendenti da
    // qualunque valore di CameraTuning. Il loop stesso non tocca mai questa cache.
    private var mapStyle: Style? = null

    private var gnssProvider: PositionProvider? = null
    private val SOURCE_PREVIEW = "preview-route-source"
    private val LAYER_PREVIEW  = "preview-route-layer"
    private val SOURCE_OFFLINE_BOUNDARY = "offline-boundary-source"
    private val LAYER_OFFLINE_BOUNDARY  = "offline-boundary-layer"

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
    private var searchDebounceJob: Job? = null

    // Layer IDs
    private val SOURCE_GPS        = "gps-position-source"
    private val SOURCE_ROUTE_DONE = "route-done-source"    // tratto percorso
    private val SOURCE_ROUTE      = "route-source"         // tratto rimanente
    private val SOURCE_DEST       = "destination-source"
    private val LAYER_GPS         = "gps-position-layer"
    private val LAYER_ROUTE_DONE  = "route-done-layer"
    private val LAYER_ROUTE       = "route-layer"
    private val LAYER_DEST        = "destination-layer"
    private val SOURCE_SAVED_PLACES = "saved-places-source"
    private val LAYER_SAVED_PLACES  = "saved-places-layer"

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startGnss() }

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
        UiTuning.load(requireContext())

        setupMap(savedInstanceState)
        setupSearch()
        setupButtons()
        setFollowMode(true)  // di default la visuale segue la barca (anche alla prima apertura)
        binding.tvBuildTag.text = "v${it.lagunav.openlagunamaps.BuildConfig.VERSION_NAME} (${it.lagunav.openlagunamaps.BuildConfig.VERSION_CODE})"
        applyUiTuning()
    }

    /**
     * Applica posizione/dimensione di tachimetro, altimetro, pulsante segui e HUD canale,
     * lette da UiTuning (regolabili dal pannello Dev Tools). Richiamata all'avvio, ad ogni
     * rientro sulla schermata, e da Dev Tools stesso quando si muove uno slider.
     */
    fun applyUiTuning() {
        val density = resources.displayMetrics.density
        binding.cardCompass.scaleX = UiTuning.compassScale
        binding.cardCompass.scaleY = UiTuning.compassScale
        binding.speedometer.scaleX = UiTuning.gaugeScale
        binding.speedometer.scaleY = UiTuning.gaugeScale
        binding.speedometer.translationY = UiTuning.gaugeOffsetYDp * density
        // Altimetro impilato esattamente sopra il tachimetro (stessa X, stesso gruppo bottom|start
        // nel layout): parte dalla stessa posizione del tachimetro e sale di gaugeStackOffsetDp.
        binding.altitudeView.scaleX = UiTuning.gaugeScale
        binding.altitudeView.scaleY = UiTuning.gaugeScale
        binding.altitudeView.translationX = 0f
        binding.altitudeView.translationY = (UiTuning.gaugeOffsetYDp - UiTuning.gaugeStackOffsetDp) * density
        binding.layoutCentra.translationY = UiTuning.followBtnOffsetYDp * density
        binding.layoutCentra.translationX = UiTuning.followBtnOffsetXDp * density
        binding.layoutCentra.scaleX = UiTuning.followBtnScale
        binding.layoutCentra.scaleY = UiTuning.followBtnScale
        binding.cvHud.translationY = UiTuning.hudOffsetYDp * density

        // Scaling elementi Salva luogo
        val btnScale = UiTuning.savePlaceBtnScale
        val textScale = UiTuning.savePlaceTextScale
        binding.btnSavePlaceDelete.scaleX = UiTuning.deletePlaceBtnScale
        binding.btnSavePlaceDelete.scaleY = UiTuning.deletePlaceBtnScale
        binding.btnSavePlaceRoute.scaleX = btnScale
        binding.btnSavePlaceRoute.scaleY = btnScale
        binding.btnSavePlaceConfirm.scaleX = btnScale
        binding.btnSavePlaceConfirm.scaleY = btnScale
        
        binding.etSavePlaceName.scaleX = textScale
        binding.etSavePlaceName.scaleY = textScale
        binding.etSavePlaceNotes.scaleX = textScale
        binding.etSavePlaceNotes.scaleY = textScale

        mapLibre?.getStyle { style ->
            (style.getLayer(LAYER_GPS) as? SymbolLayer)?.setProperties(iconSize(UiTuning.mapObjectScale))
            PlaceType.values().forEach { type ->
                (style.getLayer("$LAYER_SAVED_PLACES-${type.name}") as? SymbolLayer)
                    ?.setProperties(iconSize(UiTuning.savedPlaceScale))
            }
            (style.getLayer("briccole-layer") as? CircleLayer)
                ?.setProperties(circleColor(UiTuning.briccoleColor))
        }
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

        if (hidden) {
            // NON guardato da isResumed (a differenza del ramo "torna visibile" sotto): se questo
            // fragment viene nascosto SUBITO dopo la creazione, prima ancora che la propria
            // onResume() sia arrivata (capita aprendo un'altra schermata molto in fretta dopo
            // l'avvio dell'app), isResumed sarebbe ancora false e l'arresto verrebbe saltato per
            // sempre — il motore di rendering restava acceso e girava per sempre in sottofondo,
            // in contesa per CPU/GPU con la mappa visibile (dimezzava il framerate del loop
            // camera, misurato ~24Hz invece di ~45Hz). Si "sistemava" solo al primo vero
            // background/foreground dell'intera app, l'unico altro punto che ferma tutto senza
            // questa guardia. Fermare qui è idempotente (vedi startCameraLoop/startGnss), quindi
            // sicuro anche se onPause() lo fa già poco dopo.
            if (debugMode) stopPositionTracking()
            stopCameraLoop()
            binding.mapView.onPause()
            return
        }

        if (!isResumed) return  // evita di avviare GPS/loop prima che arrivi onResume()
        if (debugMode) startPositionTracking()
        binding.mapView.onResume()
        startCameraLoop()
        setFollowMode(true)  // ad ogni cambio schermata la visuale torna centrata sulla barca
        applyUiTuning()
        refreshSavedPlacesLayer()
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

            map.setStyle(Style.Builder().fromUri(STYLE_DAY)) { style ->
                mapStyle = style
                setupAllLayers(style)
                // Notifica alla MainActivity che la mappa è carica per togliere la splash screen
                (activity as? it.lagunav.openlagunamaps.MainActivity)?.setReady()
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
                if (!isMapInteractionLocked()) {
                    // Se stavamo seguendo la barca, il loop di camera ricentrerebbe
                    // continuamente sulla barca sovrascrivendo il centraggio sul punto appena
                    // selezionato: va staccato, altrimenti la schermata sembra non rispondere.
                    setFollowMode(false)
                    selectedPlacePos = point
                    selectedPlaceName = routingEngine.nearestCanalName(point, 50.0) ?: "Punto sulla mappa"
                    centerPointInUpperScreen(point)
                    openSavePlaceScreen()
                }
                true
            }

            // Tap breve su un pallino già salvato -> apre la schermata di modifica. Stessa
            // logica di "punto più vicino entro una soglia" già usata altrove (es.
            // nearestCanalName/distanceToNearestCanalMeters), niente bisogno di leggere i
            // layer disegnati sulla mappa: i luoghi sono già tutti in PlacesStore.
            // Distanza confrontata in PIXEL SCHERMO, non metri: l'icona ha una dimensione fissa
            // sullo schermo indipendente dallo zoom, quindi anche la tolleranza del tap deve
            // esserlo — con una soglia in metri, zoomando fuori il pallino diventa pochi pixel
            // e serve mirare quasi esatto (da qui i tap falliti più volte di fila).
            map.addOnMapClickListener { point ->
                if (isMapInteractionLocked()) return@addOnMapClickListener false
                val tapScreen = map.projection.toScreenLocation(point)
                val tapRadiusPx = SAVED_PLACE_TAP_RADIUS_DP * resources.displayMetrics.density
                val nearest = PlacesStore.getSaved(requireContext())
                    .map { place -> place to map.projection.toScreenLocation(place.toLatLng()) }
                    .minByOrNull { (_, screen) -> screenDistance(screen, tapScreen) }
                    ?.takeIf { (_, screen) -> screenDistance(screen, tapScreen) < tapRadiusPx }
                    ?.first
                if (nearest != null) { setFollowMode(false); editSavedPlace(nearest); true } else false
            }
        }
    }

    private fun Int.dpToPx(ctx: Context): Int =
        (this * ctx.resources.displayMetrics.density).toInt()

    // =================================================================
    // LAYER
    // =================================================================

    private fun setupAllLayers(style: Style) {
        setupBoatIcon(style)
        setupLagunaLayers(style)
        if (debugMode) setupDebugLayers(style)
        setupRouteLayer(style)
        setupDestinationLayer(style)
        setupSavedPlacesLayer(style)
        // La barca va aggiunta PER ULTIMA: in MapLibre i layer si disegnano nell'ordine in cui
        // vengono aggiunti allo stile, quindi l'ultimo aggiunto sta sopra a tutti gli altri —
        // vogliamo che l'icona barca resti sempre in primo piano rispetto ai pallini salvati.
        setupGpsLayer(style)
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

    /** Bitmap tondo colorato con l'icona/emoji del tipo di luogo al centro (stesso stile del
     *  triangolo barca: disegnato su Canvas, nessuna risorsa immagine da aggiungere). */
    private fun buildPlaceIconBitmap(emoji: String, bgColor: Int): Bitmap {
        val size = 72
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor; style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f }
        val radius = size / 2f - 3f
        canvas.drawCircle(size / 2f, size / 2f, radius, fill)
        canvas.drawCircle(size / 2f, size / 2f, radius, stroke)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = size * 0.5f
        }
        val textY = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(emoji, size / 2f, textY, textPaint)
        return bmp
    }

    /** Un layer per tipo (stesso pattern di setupDebugLayers), tutti sulla stessa sorgente:
     *  più semplice e sicuro di un'espressione match() per scegliere l'icona giusta. */
    private fun setupSavedPlacesLayer(style: Style) {
        val colors = mapOf(
            PlaceType.BERTH to Color.parseColor("#1565C0"),
            PlaceType.FAVORITE to Color.parseColor("#C2185B"),
            PlaceType.TO_VISIT to Color.parseColor("#2E7D32"),
            PlaceType.GENERIC to Color.parseColor("#616161")
        )
        PlaceType.values().forEach { type ->
            style.addImage("place-icon-${type.name}", buildPlaceIconBitmap(type.icon, colors.getValue(type)))
        }
        style.addSource(GeoJsonSource(SOURCE_SAVED_PLACES, emptyFc()))
        PlaceType.values().forEach { type ->
            style.addLayer(
                SymbolLayer("$LAYER_SAVED_PLACES-${type.name}", SOURCE_SAVED_PLACES)
                    .withFilter(eq(get("type"), literal(type.name)))
                    .withProperties(
                        iconImage("place-icon-${type.name}"),
                        iconSize(UiTuning.savedPlaceScale),
                        iconAllowOverlap(true), iconIgnorePlacement(true)
                    )
            )
        }
        refreshSavedPlacesLayer()
    }

    /** Rimuove eventuali luoghi salvati fuori dall'area di progetto (il poligono verde): non si
     *  possono più creare da quando c'è il controllo in openSavePlaceScreen/editSavedPlace, ma
     *  luoghi salvati prima di questa modifica potrebbero essere rimasti fuori. Richiamata ad
     *  ogni refresh, così l'elenco si autopulisce senza bisogno di un'azione manuale. */
    private fun purgeSavedPlacesOutsideProject() {
        PlacesStore.getSaved(requireContext())
            .filterNot { routingEngine.isInsideProject(it.toLatLng()) }
            .forEach { PlacesStore.removeSaved(requireContext(), it) }
    }

    /** Rilegge PlacesStore e ridisegna tutti i luoghi salvati sulla mappa. Da richiamare dopo
     *  ogni salvataggio, e ad ogni rientro sulla schermata (l'altra istanza di MapFragment,
     *  es. in Dev Tools, ha la sua sorgente separata e potrebbe non essere aggiornata). */
    private fun refreshSavedPlacesLayer() {
        purgeSavedPlacesOutsideProject()
        val places = PlacesStore.getSaved(requireContext())
        val features = JsonArray()
        places.forEach { place ->
            features.add(JsonObject().apply {
                addProperty("type", "Feature")
                add("properties", JsonObject().apply {
                    addProperty("type", place.type.name)
                    addProperty("name", place.name)
                })
                add("geometry", JsonObject().apply {
                    addProperty("type", "Point")
                    add("coordinates", JsonArray().apply { add(place.lon); add(place.lat) })
                })
            })
        }
        val geoJson = JsonObject().apply {
            addProperty("type", "FeatureCollection")
            add("features", features)
        }.toString()
        mapLibre?.getStyle { style -> (style.getSource(SOURCE_SAVED_PLACES) as? GeoJsonSource)?.setGeoJson(geoJson) }
    }

    private fun setupGpsLayer(style: Style) {
        // Nessuna posizione fittizia di default: l'icona barca non deve comparire finché non
        // arriva un fix vero (vedi bracketFixes/fixBuffer nel loop camera). Sorgente vuota finché
        // onGpsFix/onSimFix non riempie il buffer.
        style.addSource(GeoJsonSource(SOURCE_GPS, emptyFc()))
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
            lineColor("#00CC44"), lineWidth(5f), lineOpacity(1f),
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
                    circleColor(UiTuning.briccoleColor),
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
        gnssProvider?.stop()  // idempotente: evita di lasciare un provider precedente orfano e attivo
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
        // Idempotente: se chiamata due volte senza uno stop in mezzo (es. onResume duplicato
        // per un fragment annidato dentro un fragment nascosto, dove la cascata onPause/onResume
        // non è sempre garantita), la vecchia Runnable andrebbe persa ma continuerebbe comunque
        // a ripostarsi da sola per sempre — un loop "fantasma" non più fermabile da stopCameraLoop().
        stopCameraLoop()
        val r = object : Runnable {
            override fun run() = PerfMonitor.trace("cameraLoop.frame") { runFrame() }

            private fun runFrame() {
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
                        PerfMonitor.trace("hud.updateHud") { updateHud(interpPos) }
                        lastGpsLocation?.let { loc ->
                            PerfMonitor.trace("hud.checkSpeedHud") { checkSpeedHud(loc, interpPos) }
                        }
                        PerfMonitor.trace("hud.updateNavigation") { updateNavigation(interpPos) }
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
                    if (map != null) PerfMonitor.trace("cameraLoop.render") {
                        // 1. Camera (solo follow mode): la barca non va al centro esatto dello
                        // schermo ma a FOLLOW_BOAT_SCREEN_Y_FRACTION dall'alto (2/5 dal basso),
                        // per lasciare più mappa visibile davanti alla direzione di marcia.
                        if (followMode) {
                            // Niente floor fisso qui: lo zoom in follow mode è già quello deciso
                            // dal pulsante CENTRA (CameraTuning.recenterZoom) o dall'utente con
                            // le dita — un coerceAtLeast qui lo sovrascriveva ad ogni fotogramma
                            // (~45 volte al secondo), annullando di fatto lo slider "distanza x".
                            val zoom = map.cameraPosition.zoom
                            val target = followCameraTarget(map, interpPos)
                            map.moveCamera(CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder().target(target).zoom(zoom).bearing(smoothedCamBearing).build()
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

                        // 3. Icona barca + split percorso a 30fps, sulla posizione interpolata.
                        // Usa la cache mapStyle invece di map.getStyle{}: quella è una callback
                        // asincrona che a ~45 chiamate/secondo non faceva mai in tempo a
                        // svuotarsi, accumulando un ritardo/disordine percepito come scatti
                        // (vedi commento su mapStyle).
                        val style = mapStyle
                        if (style != null) {
                            // setGeoJson(String) fa il parsing del testo su un thread separato e
                            // applica il risultato in modo asincrono (da qui l'esistenza di
                            // setGeoJsonSync come alternativa): a ~45 chiamate/secondo la coda di
                            // parsing non faceva mai in tempo a svuotarsi, con applicazioni in
                            // ritardo/fuori ordine percepite come scatti dell'icona — pur avendo
                            // valori interpolati corretti e lisci in ingresso (verificato via log).
                            // setGeoJsonSync su un Feature già tipizzato evita sia il parsing
                            // testuale sia la coda asincrona.
                            val boatFeature = Feature.fromGeometry(Point.fromLngLat(interpLon, interpLat))
                            boatFeature.addNumberProperty("bearing", smoothedIconBearing.toFloat())
                            (style.getSource(SOURCE_GPS) as? GeoJsonSource)?.setGeoJsonSync(boatFeature)

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

    /** Esposto per DevToolsFragment: quando l'app va davvero in background, la mappa qui
     *  incorporata (childMap, annidata in un fragment nascosto) non è garantito ricevere
     *  onPause() in cascata su tutti i dispositivi/versioni — meglio fermarla esplicitamente
     *  invece di scoprire un loop di camera "fantasma" rimasto attivo per sempre. Idempotente
     *  (vedi startCameraLoop/startGnss), quindi sicuro da richiamare anche se la cascata
     *  normale ha già fatto il suo lavoro. */
    /** Modalità di verifica per Dev Tools > Mappa Offline: forza MapLibre a non scaricare
     *  nulla via rete (org.maplibre.android.MapLibre.setConnected(false), lo stesso meccanismo
     *  usato da Mapbox/MapLibre per i test offline) — niente colori artificiali aggiunti, si
     *  vede semplicemente la mappa così com'è nella cache, senza poter scaricare altro online.
     *
     *  Ricarica anche lo stile da zero: senza questo, i tile già decodificati in memoria in
     *  questa sessione (indipendenti dalla cache su disco) restano a schermo comunque, rendendo
     *  il test inutile — sembra che "non cambi niente" anche quando la rete è davvero disattivata.
     *
     *  Il loop camera (~45Hz) tocca lo stile corrente ad ogni fotogramma (icona barca, ecc.):
     *  se lasciato girare durante il reload, arriva a chiamare metodi sullo Style vecchio proprio
     *  mentre MapLibre lo sta invalidando, e crasha. Va fermato prima e riavviato solo a
     *  caricamento completato (setupAllLayers finito). */
    fun setOfflineVerificationMode(enabled: Boolean) {
        org.maplibre.android.MapLibre.setConnected(if (enabled) false else null)
        stopCameraLoop()
        mapLibre?.setStyle(Style.Builder().fromUri(STYLE_DAY)) { style ->
            mapStyle = style
            setupAllLayers(style)
            startCameraLoop()
        }
    }

    fun pauseTracking() {
        stopPositionTracking()
        stopCameraLoop()
    }

    fun resumeTracking() {
        startPositionTracking()
        startCameraLoop()
    }

    // =================================================================
    // HUD
    // =================================================================

    /** Rilancia il fetch della marea in background se il valore in cache è troppo vecchio;
     *  non blocca mai updateHud, che nel frattempo continua a usare l'ultimo valore noto
     *  (0.0 finché il primo fetch non è ancora arrivato). */
    private fun refreshTideIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastTideFetchMs < TIDE_REFRESH_MS) return
        lastTideFetchMs = now
        viewLifecycleOwner.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { TideEngine.fetch(requireContext().applicationContext) }
            if (data != null) cachedTideM = data.nowM
        }
    }

    private fun updateHud(pos: LatLng) {
        refreshTideIfNeeded()
        val isAtSea    = PerfMonitor.trace("routingEngine.isAtSea") { routingEngine.isAtSea(pos) }
        val isNoGo     = PerfMonitor.trace("routingEngine.isPointInNoGo") { routingEngine.isPointInNoGo(pos) }
        val fixedDepth = PerfMonitor.trace("routingEngine.getFixedDepthAt") { routingEngine.getFixedDepthAt(pos) }
        // Limite di distanza dalla costa in mare aperto (patente/dotazioni di sicurezza, vedi
        // Impostazioni > Profilo Barca): calcolato solo se già in mare, non ha senso in laguna.
        val overDistanceLimit = isAtSea && PerfMonitor.trace("routingEngine.distanceFromSeaBoundaryMeters") {
            routingEngine.distanceFromSeaBoundaryMeters(pos) > NavigationLimits.maxDistanceMeters(requireContext())
        }

        val locationText: String; val depthValue: Float; val hudColor: Int
        when {
            isNoGo -> {
                locationText = if (isAtSea) "Possibile Basso Fondale" else "Terraferma"
                depthValue   = 0f
                hudColor     = resources.getColor(android.R.color.holo_red_dark, null)
            }
            overDistanceLimit -> {
                locationText = "Oltre limite"
                depthValue   = 12f
                hudColor     = resources.getColor(android.R.color.holo_red_dark, null)
            }
            fixedDepth != null -> {
                locationText = if (isAtSea) "Mare" else canalLocationLabel(pos)
                depthValue   = (fixedDepth + cachedTideM).toFloat()
                hudColor     = resources.getColor(R.color.marine_blue_dark, null)
            }
            isAtSea -> {
                locationText = "Mare"
                depthValue   = (12f + cachedTideM).toFloat()
                hudColor     = resources.getColor(R.color.marine_blue_dark, null)
            }
            else -> {
                val d = PerfMonitor.trace("bathyEngine.getDepthAt") {
                    bathyEngine.getDepthAt(pos.latitude, pos.longitude, routingEngine.getNoGoAreas())
                }
                locationText = canalLocationLabel(pos)
                // La marea corregge la profondità solo dove c'è davvero acqua: bathyEngine
                // ritorna 0 anche per la terraferma (non solo per i poligoni isNoGo sopra), e in
                // quel caso il valore deve restare 0 a prescindere dalla marea, non diventare
                // negativo/positivo per un pezzo di terra.
                depthValue   = if (d > 0f) (d + cachedTideM).toFloat() else 0f
                hudColor     = if (depthValue in 0.1f..1.2f) resources.getColor(android.R.color.holo_red_dark, null)
                               else resources.getColor(R.color.marine_blue_dark, null)
            }
        }
        binding.tvHudDepth.text = locationText
        binding.cvHud.setCardBackgroundColor(hudColor)
        binding.altitudeView.altitude = depthValue
    }

    // routingEngine.nearestCanalName() scansiona linearmente TUTTI i segmenti di canale con
    // nome (nessun indice spaziale, a differenza di distanceToNearestCanalMeters che usa la
    // griglia precalcolata) — con l'HUD che può arrivare a 20Hz sarebbe una chiamata pesante
    // ripetuta decine di volte al secondo sul main thread. Il nome del canale cambia raramente
    // (al massimo ogni 1s, quando cambi effettivamente canale), quindi lo ricalcoliamo al più
    // una volta al secondo indipendentemente dal refresh HUD, e riusiamo il valore in cache
    // per tutte le chiamate intermedie.
    private var cachedCanalLabel = "Canale sconosciuto"
    private var lastCanalLabelUpdateMs = 0L
    private val CANAL_LABEL_REFRESH_MS = 1000L

    /** "Fuori canale" se troppo lontani da qualsiasi canale; altrimenti il nome, o "Canale
     *  sconosciuto" se il canale più vicino non ha un tag nome nei dati OSM. */
    private fun canalLocationLabel(pos: LatLng): String {
        val now = System.currentTimeMillis()
        if (now - lastCanalLabelUpdateMs < CANAL_LABEL_REFRESH_MS) return cachedCanalLabel
        lastCanalLabelUpdateMs = now
        cachedCanalLabel = PerfMonitor.trace("hud.canalLocationLabel") {
            // Tracciati separati: distanceToNearestCanalMeters e nearestCanalName usano indici
            // diversi (griglia archi di routing vs griglia segmenti con nome) — utile tenerli
            // distinti nel log per non confondere quale dei due è lento.
            val distCanal = PerfMonitor.trace("routingEngine.distanceToNearestCanalMeters(canalLabel)") {
                routingEngine.distanceToNearestCanalMeters(pos)
            }
            if (distCanal > CameraTuning.canalLabelThresholdM) "Fuori canale"
            else PerfMonitor.trace("routingEngine.nearestCanalName") {
                routingEngine.nearestCanalName(pos, CameraTuning.canalLabelThresholdM) ?: "Canale sconosciuto"
            }
        }
        return cachedCanalLabel
    }

    private fun checkSpeedHud(location: Location, pos: LatLng) {
        val unit = SpeedUnit.get(requireContext())
        val speedMps = location.speed.toDouble()
        val speedKn = speedMps * 3600.0 / 1852.0
        val speedDisp = unit.fromMps(speedMps)
        val limitKn = PerfMonitor.trace("routingEngine.getMaxSpeedKnotsAt") { routingEngine.getMaxSpeedKnotsAt(pos) }

        binding.speedometer.maxSpeed = unit.gaugeMax
        binding.speedometer.unitLabel = unit.label
        binding.speedometer.speed = speedDisp.toFloat()
        binding.speedometer.speedLimit = limitKn?.let { unit.fromKnots(it).toFloat() }

        // Manteniamo tvHudSpeed per debug o se vogliamo info extra, ma lo nascondiamo se non serve
        binding.tvHudSpeed.visibility = View.GONE
    }

    // =================================================================
    // NAVIGAZIONE ATTIVA
    // =================================================================

    /** Imposta la destinazione e calcola il percorso. Esposto per DevToolsFragment. */
    fun setDestinationAndRoute(dest: LatLng) {
        destination = dest

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
        setNavigationUiActive(true)
        startBgReroute()
    }

    /** HUD normale (canale) SOLO fuori navigazione; durante la
     *  navigazione lo sostituiscono il chip in basso (tempo/distanza/arrivo).
     *  Tachimetro e Altimetro restano sempre visibili. */
    private fun setNavigationUiActive(active: Boolean) {
        binding.cvHud.visibility       = if (active) View.GONE else View.VISIBLE
        binding.cardNavChip.visibility = if (active) View.VISIBLE else View.GONE
    }

    // =================================================================
    // SELEZIONE LUOGO / SALVA / PIANIFICAZIONE PERCORSO
    // =================================================================
    // Cercare o tenere premuto sulla mappa apre un popup di selezione (nome, coordinate, X per
    // chiudere, Salva, Percorso) — NON calcola subito un percorso. "Percorso" apre la
    // pianificazione (partenza/destinazione, zoom-to-fit, ETA) e solo da lì "Avvia" mette in
    // follow mode e naviga sul serio. Finché una di queste UI è aperta, o c'è una navigazione
    // attiva, un nuovo tap lungo sulla mappa non fa nulla (va chiuso tutto prima con la X).
    // setDestinationAndRoute() sopra resta invariata ed esposta per DevTools, che vuole partire
    // subito senza passare da tutto questo.

    private var selectedPlacePos: LatLng? = null
    private var selectedPlaceName: String = "Luogo"
    private var selectedSaveType: PlaceType = PlaceType.GENERIC
    // Non-null quando la schermata "Salva luogo" è aperta per MODIFICARE un luogo già salvato
    // (tap su un pallino sulla mappa), invece che per salvarne uno nuovo: mostra "Elimina" e
    // aggiorna l'esistente invece di crearne uno nuovo.
    private var editingPlace: SavedPlace? = null

    private var planningDest: LatLng? = null
    private var planningRoute: List<LatLng>? = null

    /** true se c'è già un popup/schermata di pianificazione aperta o una navigazione attiva:
     *  in quel caso un nuovo tap lungo sulla mappa deve restare inerte finché non si chiude. */
    private fun isMapInteractionLocked(): Boolean =
        activeRoute != null ||
        binding.cardPlaceDetail.visibility == View.VISIBLE ||
        binding.cardRoutePlanning.visibility == View.VISIBLE ||
        binding.cardSavePlace.visibility == View.VISIBLE ||
        binding.cardSavedPlaces.visibility == View.VISIBLE

    /** Chiude la schermata/overlay attualmente in primo piano, dal più "interno" al più
     *  "esterno" (dettaglio punto → salva punto → pianificazione percorso → luoghi salvati →
     *  lista ricerca). Ritorna true se ha chiuso qualcosa (il tasto indietro va considerato
     *  gestito), false se la mappa è già nello stato base e il tasto indietro deve fare
     *  altro (es. tornare alla voce di menu precedente, o uscire dall'app). */
    fun handleBackPress(): Boolean {
        return when {
            binding.cardPlaceDetail.visibility == View.VISIBLE -> { closePlaceDetail(); true }
            binding.cardSavePlace.visibility == View.VISIBLE -> { closeSavePlaceScreen(); true }
            binding.cardRoutePlanning.visibility == View.VISIBLE -> { closeRoutePlanning(); true }
            binding.cardSavedPlaces.visibility == View.VISIBLE -> { closeSavedPlacesScreen(); true }
            binding.cardPlaces.visibility == View.VISIBLE -> { hidePlacesList(); true }
            else -> false
        }
    }

    fun showPlaceDetail(pos: LatLng, name: String) {
        if (isMapInteractionLocked()) return
        // Altrimenti il loop camera in follow mode ricentra sulla barca 45 volte al secondo,
        // annullando subito lo spostamento verso il punto selezionato qui sotto — sembrava che
        // il tap "non facesse nulla" (es. selezionando un risultato di ricerca).
        setFollowMode(false)
        selectedPlacePos = pos
        selectedPlaceName = name

        hidePlacesList()
        binding.cardSearch.visibility = View.GONE

        binding.tvPlaceDetailName.text = name
        val canal = routingEngine.nearestCanalName(pos, 50.0) ?: "Laguna aperta"
        binding.tvPlaceDetailCanal.text = canal
        binding.cardPlaceDetail.visibility = View.VISIBLE
        binding.speedometer.visibility = View.GONE
        binding.altitudeView.visibility = View.GONE

        // Segna il punto selezionato sulla mappa: altrimenti dopo il tap lungo/ricerca
        // non si vede più dove avevi effettivamente premuto.
        mapLibre?.getStyle { style -> drawDestination(style, pos) }
        centerPointInUpperScreen(pos)
    }

    /** Nasconde la tastiera e toglie il focus dal campo che la teneva aperta — da richiamare
     *  in OGNI punto che chiude un popup/schermata con la X, altrimenti se si stava scrivendo
     *  (ricerca, nome/note del luogo) la tastiera resta aperta anche a schermata chiusa. */
    private fun hideKeyboard() {
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.root.windowToken, 0)
        activity?.currentFocus?.clearFocus()
    }

    private fun closePlaceDetail() {
        hideKeyboard()
        selectedPlacePos = null
        binding.cardPlaceDetail.visibility = View.GONE
        binding.cardSearch.visibility = View.VISIBLE
        binding.speedometer.visibility = View.VISIBLE
        binding.altitudeView.visibility = View.VISIBLE
        mapLibre?.getStyle { style -> (style.getSource(SOURCE_DEST) as? GeoJsonSource)?.setGeoJson(emptyFc()) }
    }

    // --- Salva luogo (schermo intero) ---

    /** Mostra il modulo normale (nome/categoria/note/pulsanti) solo se il punto è dentro
     *  l'area di progetto (il poligono verde sulla mappa): fuori da lì un luogo non si può
     *  salvare, quindi la schermata mostra solo l'avviso e la X per chiudere. */
    private fun applySavePlaceValidity(pos: LatLng) {
        val valid = routingEngine.isInsideProject(pos)
        binding.layoutSavePlaceContent.visibility = if (valid) View.VISIBLE else View.GONE
        binding.tvSavePlaceInvalid.visibility = if (valid) View.GONE else View.VISIBLE
    }

    private fun openSavePlaceScreen() {
        val pos = selectedPlacePos ?: return
        editingPlace = null
        binding.btnSavePlaceDelete.visibility = View.GONE
        binding.cardPlaceDetail.visibility = View.GONE
        binding.etSavePlaceName.setText(selectedPlaceName)
        binding.etSavePlaceNotes.setText("")
        selectedSaveType = PlaceType.GENERIC
        updateSaveTypeButtons()
        binding.cardSearch.visibility = View.GONE
        binding.cardSavePlace.visibility = View.VISIBLE
        applySavePlaceValidity(pos)
        // Il tap lungo su un punto nuovo salta il popup di dettaglio e apre questa schermata
        // direttamente: senza questa chiamata il pallino rosso non compariva finché non si
        // passava da Itinerario -> Annulla (che riapre showPlaceDetail, l'unico altro punto
        // che disegnava il marker).
        mapLibre?.getStyle { style -> drawDestination(style, pos) }
    }

    /** Tap su un pallino già salvato sulla mappa: riapre la stessa schermata precompilata,
     *  con "Elimina" visibile (vedi setupMap/tapOnSavedPlace per la ricerca del luogo). */
    private fun editSavedPlace(place: SavedPlace) {
        if (isMapInteractionLocked()) return
        // Vedi commento in showPlaceDetail(): senza questo il follow mode ricentra sulla barca
        // ogni fotogramma, annullando lo spostamento verso il luogo selezionato.
        setFollowMode(false)
        selectedPlacePos = place.toLatLng()
        selectedPlaceName = place.name
        editingPlace = place
        selectedSaveType = place.type
        binding.etSavePlaceName.setText(place.name)
        binding.etSavePlaceNotes.setText(place.notes)
        updateSaveTypeButtons()
        binding.btnSavePlaceDelete.visibility = View.VISIBLE
        binding.cardSearch.visibility = View.GONE
        binding.cardSavePlace.visibility = View.VISIBLE
        applySavePlaceValidity(place.toLatLng())
        mapLibre?.getStyle { style -> drawDestination(style, place.toLatLng()) }
        centerPointInUpperScreen(place.toLatLng())
    }

    private fun closeSavePlaceScreen() {
        hideKeyboard()
        selectedPlacePos = null
        editingPlace = null
        binding.cardSavePlace.visibility = View.GONE
        binding.cardSearch.visibility = View.VISIBLE
        mapLibre?.getStyle { style -> (style.getSource(SOURCE_DEST) as? GeoJsonSource)?.setGeoJson(emptyFc()) }
    }

    private fun confirmSavePlace() {
        val pos = selectedPlacePos ?: return
        val name = binding.etSavePlaceName.text.toString().trim().ifEmpty { selectedPlaceName }
        val notes = binding.etSavePlaceNotes.text.toString().trim()
        val wasNewPlace = editingPlace == null
        val place = SavedPlace(name, pos.latitude, pos.longitude, selectedSaveType, notes = notes)
        // addSaved() aggiorna sul posto se esiste già un luogo salvato alla stessa posizione
        // (dedup per lat/lon in PlacesStore) — non serve altra logica per l'update.
        PlacesStore.addSaved(requireContext(), place)
        // Niente Toast: il pallino compare/si aggiorna sulla mappa, è già conferma visiva
        // sufficiente senza sovrapporre altri popup.
        refreshSavedPlacesLayer()
        closeSavePlaceScreen()
        // Punto NUOVO appena salvato (non una modifica di un luogo già esistente): riapre subito
        // la schermata di modifica, così il pulsante "Itinerario" è già lì pronto.
        if (wasNewPlace) editSavedPlace(place)
    }

    private fun deleteEditingPlace() {
        val place = editingPlace ?: return
        PlacesStore.removeSaved(requireContext(), place)
        closeSavePlaceScreen()
        refreshSavedPlacesLayer()
    }

    private fun updateSaveTypeButtons() {
        val selectedTint = android.content.res.ColorStateList.valueOf(Color.parseColor("#0091EA"))
        val normalTint    = android.content.res.ColorStateList.valueOf(Color.parseColor("#F5F5F5"))
        val selectedText = Color.WHITE
        val normalText   = Color.parseColor("#444444")
        
        mapOf(
            PlaceType.BERTH to binding.btnTypeBerth,
            PlaceType.FAVORITE to binding.btnTypeFavorite,
            PlaceType.TO_VISIT to binding.btnTypeSpecial,
            PlaceType.GENERIC to binding.btnTypeGeneric
        ).forEach { (type, btn) -> 
            val isSelected = type == selectedSaveType
            btn.backgroundTintList = if (isSelected) selectedTint else normalTint
            btn.setTextColor(if (isSelected) selectedText else normalText)
        }
        binding.tvSavePlaceTypeLabel.text = selectedSaveType.label
    }

    private fun openRoutePlanning() {
        val dest = selectedPlacePos ?: return
        binding.cardPlaceDetail.visibility = View.GONE
        binding.cardSearch.visibility = View.GONE
        planningDest = dest
        planningRoute = null

        val canal = routingEngine.nearestCanalName(dest, 50.0) ?: "Laguna aperta"
        binding.tvRouteDestination.text = canal
        binding.tvRoutePlanningTime.text = "-- min"
        binding.tvRoutePlanningDist.text = "-- km"
        binding.layoutRoutePlanningNormal.visibility = View.VISIBLE
        binding.layoutRoutePlanningOutsideArea.visibility = View.GONE
        binding.cardRoutePlanning.visibility = View.VISIBLE
        binding.speedometer.visibility = View.GONE
        binding.altitudeView.visibility = View.GONE
        showPreviewRoute(null)

        recalcPlanningRoute()
    }

    private fun recalcPlanningRoute() {
        val dest = planningDest ?: return
        val origin = lastGpsLocation?.let { LatLng(it.latitude, it.longitude) } ?: run {
            binding.tvRoutePlanningTime.text = "N/A"
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val route = withContext(Dispatchers.Default) { routingEngine.findRoute(origin, dest) }
            if (planningDest != dest) return@launch  // pianificazione chiusa/sostituita nel frattempo
            planningRoute = route
            if (route == null) {
                // Se la barca non è dentro l'area di progetto (la laguna), l'errore tecnico
                // ("nessun canale trovato vicino al punto di partenza") non è chiaro per
                // l'utente: mostriamo un messaggio esplicito, senza Annulla/Partenza (non ha
                // senso proporre di avviare una navigazione che non può esistere).
                if (!routingEngine.isInsideProject(origin)) {
                    binding.layoutRoutePlanningNormal.visibility = View.GONE
                    binding.layoutRoutePlanningOutsideArea.visibility = View.VISIBLE
                    return@launch
                }
                binding.tvRoutePlanningTime.text = "Errore"
                binding.tvRoutePlanningDist.text = routingEngine.lastRoutingError
                return@launch
            }
            val etaMin = routingEngine.calculateEstimatedTimeMinutes(route)
            val distKm = routingEngine.calculateTotalDistance(route) / 1000.0
            binding.tvRoutePlanningTime.text = "%d min".format(etaMin)
            binding.tvRoutePlanningDist.text = "%.1f km".format(distKm)
            showPreviewRoute(route)
            mapLibre?.getStyle { style -> drawDestination(style, dest) }
            zoomToFitRoute(route)
        }
    }

    /** Zooma/pana la camera per contenere tutto il percorso, con un margine attorno.
     * Considera che in basso c'è la card della pianificazione che copre una parte di mappa. */
    private fun zoomToFitRoute(route: List<LatLng>) {
        if (route.size < 2) return
        val map = mapLibre ?: return
        val boundsBuilder = org.maplibre.android.geometry.LatLngBounds.Builder()
        route.forEach { boundsBuilder.include(it) }

        val density = resources.displayMetrics.density
        val paddingSide = (48 * density).toInt()
        val paddingTop  = (48 * density).toInt()
        val paddingBottom = (260 * density).toInt() // Spazio per la card in basso

        map.animateCamera(CameraUpdateFactory.newLatLngBounds(
            boundsBuilder.build(), 
            paddingSide, paddingTop, paddingSide, paddingBottom
        ), 1000)
    }

    /** Chiudere la pianificazione (X) senza premere "Avvia" NON deve buttare via la selezione:
     *  torna al popup da cui si era partiti — la schermata di MODIFICA se il punto era già un
     *  luogo salvato (editingPlace != null), altrimenti quella di PRIMA CREAZIONE (place-detail)
     *  per un punto nuovo — così l'utente può ancora decidere di salvarlo, rinominarlo, ecc. */
    private fun closeRoutePlanning() {
        hideKeyboard()
        val dest = planningDest
        val name = selectedPlaceName
        val wasEditingExisting = editingPlace
        planningDest = null
        planningRoute = null
        binding.cardRoutePlanning.visibility = View.GONE
        binding.speedometer.visibility = View.VISIBLE
        binding.altitudeView.visibility = View.VISIBLE
        showPreviewRoute(null)
        mapLibre?.getStyle { style -> (style.getSource(SOURCE_DEST) as? GeoJsonSource)?.setGeoJson(emptyFc()) }

        if (dest == null) {
            // Niente da riaprire (caso limite): torna semplicemente alla mappa.
            selectedPlacePos = null
            binding.cardSearch.visibility = View.VISIBLE
            return
        }
        if (wasEditingExisting != null) editSavedPlace(wasEditingExisting) else showPlaceDetail(dest, name)
    }

    private fun startPlannedRoute() {
        val dest  = planningDest ?: return
        val route = planningRoute ?: return  // percorso non ancora calcolato: nessun popup, semplicemente non fa nulla
        destination = dest
        activeRoute = route
        currentWaypointIdx = 0
        setFollowMode(true)

        showPreviewRoute(null)  // il tratteggio verde non serve più: ora c'è il percorso vero
        mapLibre?.getStyle { style ->
            drawRouteSplit(style, route, 0)
            drawDestination(style, dest)
        }
        binding.cardRoutePlanning.visibility = View.GONE
        binding.speedometer.visibility = View.VISIBLE
        binding.altitudeView.visibility = View.VISIBLE
        binding.cardNavBanner.visibility     = View.VISIBLE
        binding.cardSearch.visibility        = View.GONE
        setNavigationUiActive(true)
        startBgReroute()

        PlacesStore.addRecent(requireContext(), SavedPlace(selectedPlaceName, dest.latitude, dest.longitude))
        selectedPlacePos = null
        planningDest = null
        planningRoute = null
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

        // Prossima svolta REALE (cambio di canale/incrocio), non il prossimo vertice del grafo
        // (che può essere a pochi metri, su una curva dolce dello stesso canale). L'angolo è
        // RELATIVO alla direzione di marcia attuale e arrotondato al multiplo di 45° più vicino
        // (0°=dritto, 90°=destra, 180°=indietro/inversione a U, 270°=sinistra).
        // Ricerca limitata a TURN_SEARCH_LOOKAHEAD vertici: senza questo limite, su un tratto
        // dritto lungo (nessuna svolta trovata) si scorreva l'intero percorso residuo ad ogni
        // fotogramma del loop camera, rendendo l'icona barca a scatti.
        val turnIdx = findNextTurnIndex(route, currentWaypointIdx, TURN_SEARCH_LOOKAHEAD)
        val targetIdx = turnIdx ?: minOf(currentWaypointIdx + TURN_SEARCH_LOOKAHEAD, route.size - 1)
        var distNext = haversineLocal(pos, route[currentWaypointIdx])
        for (i in currentWaypointIdx until targetIdx) distNext += haversineLocal(route[i], route[i + 1])
        
        val arrowRes = if (turnIdx != null && turnIdx < route.size - 1) {
            val bearingBefore = bearingTo(route[turnIdx - 1], route[turnIdx])
            val bearingAfter  = bearingTo(route[turnIdx], route[turnIdx + 1])
            val angle = relativeTurnAngleDeg(bearingBefore, bearingAfter)
            getArrowDrawableForAngle(angle)
        } else {
            R.drawable.ic_nav_straight
        }
        
        val remaining = route.drop(currentWaypointIdx)
        val etaMin    = PerfMonitor.trace("routingEngine.calculateEstimatedTimeMinutes") { routingEngine.calculateEstimatedTimeMinutes(remaining) }
        val distKm    = routingEngine.calculateTotalDistance(remaining) / 1000.0

        val distCanal = routingEngine.distanceToNearestCanalMeters(pos)
        val offCanal  = distCanal > OFF_CANAL_THRESHOLD_M && !routingEngine.isAtSea(pos)

        // Banner in alto: icona svolta + distanza dalla prossima svolta + canale su cui siamo ora.
        if (offCanal) {
            binding.cardNavBanner.setCardBackgroundColor(Color.parseColor("#CC880000"))
            binding.ivNavArrow.setImageResource(R.drawable.ic_nav_straight) // O un'icona di pericolo se preferisci
            binding.tvNavInstruction.text = "Fuori canale"
            binding.tvNavCanal.text       = "%.0f m dal canale più vicino".format(distCanal)
            // Il ricalcolo è gestito dal background job ogni 5 secondi — nessun trigger qui
        } else {
            binding.cardNavBanner.setCardBackgroundColor(Color.parseColor("#00695C"))
            binding.ivNavArrow.setImageResource(arrowRes)
            binding.tvNavInstruction.text = "%.0f m".format(distNext)
            binding.tvNavCanal.text       = canalLocationLabel(pos)
        }

        // Chip in basso (al posto dell'HUD): tempo residuo, distanza, orario di arrivo stimato.
        val arrivalTime = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
            .format(java.util.Date(System.currentTimeMillis() + etaMin * 60_000L))
        binding.tvNavChipTime.text   = "%d min".format(etaMin)
        binding.tvNavChipDetail.text = "%.1f km · %s".format(distKm, arrivalTime)
    }

    /** Cancella il percorso attivo. Esposto per DevToolsFragment. */
    fun cancelRoute() {
        hideKeyboard()
        bgRerouteJob?.cancel()
        activeRoute = null; destination = null; currentWaypointIdx = 0
        setFollowMode(false)
        binding.cardNavBanner.visibility = View.GONE
        binding.cardSearch.visibility    = View.VISIBLE
        setNavigationUiActive(false)
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
        // Un'unica lista di risultati in tempo reale (vedi updateLiveSearchResults) — niente
        // più il vecchio popup a risultato singolo: premere cerca/invio serve solo a chiudere
        // la tastiera, i risultati sono già lì sotto mentre scrivi.
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }
        binding.btnSearch.setOnClickListener { hideKeyboard() }

        // Lista luoghi (salvati/recenti): visibile quando la barra ha il focus ed è vuota,
        // come la cronologia di Google Maps.
        binding.etSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && binding.etSearch.text.isNullOrBlank()) showPlacesList() else hidePlacesList()
            // Con la tastiera aperta l'HUD in basso non deve comparire spinto a metà schermo:
            // lo nascondiamo finché si sta scrivendo (fuori navigazione, dove l'HUD non c'è già).
            if (activeRoute == null) binding.cvHud.visibility = if (hasFocus) View.GONE else View.VISIBLE
        }
        binding.etSearch.addTextChangedListener { text ->
            val q = text?.toString()?.trim().orEmpty()
            if (q.isBlank()) { if (binding.etSearch.hasFocus()) showPlacesList() }
            else updateLiveSearchResults(q)
        }

        // Schermata "Luoghi salvati"
        binding.btnMenu.setOnClickListener {
            (activity as? it.lagunav.openlagunamaps.MainActivity)?.openDrawer()
        }
        binding.btnSavedPlacesBack.setOnClickListener { closeSavedPlacesScreen() }
        binding.btnFilterBerth.setOnClickListener { toggleSavedPlacesFilter(PlaceType.BERTH) }
        binding.btnFilterFavorite.setOnClickListener { toggleSavedPlacesFilter(PlaceType.FAVORITE) }
        binding.btnFilterSpecial.setOnClickListener { toggleSavedPlacesFilter(PlaceType.TO_VISIT) }
        binding.btnFilterGeneric.setOnClickListener { toggleSavedPlacesFilter(PlaceType.GENERIC) }

        // Popup selezione luogo
        binding.btnPlaceDetailClose.setOnClickListener { closePlaceDetail() }
        binding.btnPlaceDetailSave.setOnClickListener { openSavePlaceScreen() }
        binding.btnPlaceDetailRoute.setOnClickListener { openRoutePlanning() }

        // Schermata Salva luogo
        binding.btnSavePlaceClose.setOnClickListener { closeSavePlaceScreen() }
        binding.btnSavePlaceConfirm.setOnClickListener { confirmSavePlace() }

        binding.etSavePlaceNotes.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE || 
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
                hideKeyboard()
                true
            } else false
        }

        binding.btnSavePlaceDelete.setOnClickListener { deleteEditingPlace() }
        binding.btnSavePlaceRoute.setOnClickListener {
            // NON chiudiamo lo stato con closeSavePlaceScreen()/confirmSavePlace(): altrimenti
            // openRoutePlanning() qui sotto trova selectedPlacePos già nullo e non apre nulla.
            //
            // Salvataggio in PlacesStore SOLO se stiamo modificando un luogo GIÀ salvato
            // (editingPlace != null): lì l'utente ha già scelto di tenerlo, ha senso aggiornare
            // le modifiche fatte prima di navigare. Su un punto NUOVO invece "Itinerario" deve
            // SOLO calcolare il percorso, senza salvarlo per sempre — altrimenti ogni volta che
            // chiedi indicazioni verso un punto qualsiasi te lo ritrovi tra i luoghi salvati
            // senza aver mai premuto "Salva".
            val pos = selectedPlacePos
            if (pos != null) {
                val name = binding.etSavePlaceName.text.toString().trim().ifEmpty { selectedPlaceName }
                selectedPlaceName = name
                if (editingPlace != null) {
                    val notes = binding.etSavePlaceNotes.text.toString().trim()
                    PlacesStore.addSaved(requireContext(), SavedPlace(name, pos.latitude, pos.longitude, selectedSaveType, notes = notes))
                    refreshSavedPlacesLayer()
                }
            }
            hideKeyboard()
            binding.cardSavePlace.visibility = View.GONE
            openRoutePlanning()
        }
        binding.btnTypeBerth.setOnClickListener { selectedSaveType = PlaceType.BERTH; updateSaveTypeButtons() }
        binding.btnTypeFavorite.setOnClickListener { selectedSaveType = PlaceType.FAVORITE; updateSaveTypeButtons() }
        binding.btnTypeSpecial.setOnClickListener { selectedSaveType = PlaceType.TO_VISIT; updateSaveTypeButtons() }
        binding.btnTypeGeneric.setOnClickListener { selectedSaveType = PlaceType.GENERIC; updateSaveTypeButtons() }

        // Pianificazione percorso
        binding.btnRoutePlanningCancel.setOnClickListener { closeRoutePlanning() }
        binding.btnRoutePlanningClose.setOnClickListener { closeRoutePlanning() }
        binding.btnRoutePlanningStart.setOnClickListener { startPlannedRoute() }
    }

    // =================================================================
    // LISTA LUOGHI — salvati (ancora/preferito/speciale/generico) + recenti
    // =================================================================

    /** Menu di ricerca a campo vuoto: un solo pulsante "Salvati" (apre la schermata a tutto
     *  schermo con l'elenco completo filtrabile) + i luoghi delle navigazioni DAVVERO avviate
     *  di recente (PlacesStore.getRecents, valorizzato solo in startPlannedRoute — non le
     *  semplici ricerche). Niente più elenco dei singoli luoghi salvati qui in mezzo. */
    private fun showPlacesList() {
        val ctx = requireContext()
        searchDebounceJob?.cancel()
        val container = binding.layoutPlacesList
        container.removeAllViews()

        val savedCount = PlacesStore.getSaved(ctx).size
        val savedSubtitle = if (savedCount > 0) "$savedCount luoghi" else "Nessun luogo salvato ancora"
        addPlaceRow(container, "🔖 Salvati", savedSubtitle) { openSavedPlacesScreen() }

        val recents = PlacesStore.getRecents(ctx)
        if (recents.isNotEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "Recenti"
                textSize = 11f
                setTextColor(Color.LTGRAY)
                setPadding(32, 20, 32, 4)
            })
            recents.forEach { rec -> addPlaceRow(container, rec.name, "Recente") { selectPlace(rec) } }
        }
        binding.cardPlaces.visibility = View.VISIBLE
    }

    /** Ricerca in tempo reale mentre si scrive: al massimo 2 luoghi salvati che corrispondono
     *  per nome, poi tutti i risultati "nuovi" (Nominatim, con debounce — vedi sotto). Se nessun
     *  salvato corrisponde non se ne mostra nessuno (niente ripiego su altro). */
    private fun updateLiveSearchResults(query: String) {
        val ctx = requireContext()
        val container = binding.layoutPlacesList
        container.removeAllViews()

        PlacesStore.getSaved(ctx)
            .filter { it.name.contains(query, ignoreCase = true) }
            .take(2)
            .forEach { place ->
                addPlaceRow(container, "${place.type.icon} ${place.name}", place.type.label) { selectPlace(place) }
            }
        binding.cardPlaces.visibility = View.VISIBLE

        // Debounce: Nominatim non va interrogato ad ogni tasto premuto (violerebbe le sue linee
        // guida d'uso e rischierebbe di far bloccare le richieste dell'app) — aspettiamo che
        // l'utente smetta di scrivere per un attimo prima di partire con la richiesta di rete.
        searchDebounceJob?.cancel()
        if (query.length < 3) return
        searchDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(450L)
            val results = withContext(Dispatchers.IO) { searchNominatimMulti(query) }
            if (_binding == null) return@launch
            if (binding.etSearch.text?.toString()?.trim() != query) return@launch  // testo cambiato nel frattempo
            results.forEach { (pos, name) ->
                addPlaceRow(container, name, "Nuovo luogo") {
                    hideKeyboard()
                    hidePlacesList()
                    showPlaceDetail(pos, name)
                }
            }
        }
    }

    private fun hidePlacesList() {
        searchDebounceJob?.cancel()
        _binding?.cardPlaces?.visibility = View.GONE
    }

    private fun selectPlace(place: SavedPlace) {
        hideKeyboard()
        hidePlacesList()
        editSavedPlace(place)
    }

    // --- Schermata a tutto schermo "Luoghi salvati" ---

    private val savedPlacesTypeFilter = PlaceType.values().toMutableSet()

    private fun openSavedPlacesScreen() {
        hidePlacesList()
        hideKeyboard()
        binding.cardSearch.visibility = View.GONE
        updateSavedPlacesFilterButtons()
        refreshSavedPlacesScreenList()
        binding.cardSavedPlaces.visibility = View.VISIBLE
    }

    private fun closeSavedPlacesScreen() {
        binding.cardSavedPlaces.visibility = View.GONE
        binding.cardSearch.visibility = View.VISIBLE
    }

    private fun refreshSavedPlacesScreenList() {
        val container = binding.layoutSavedPlacesFullList
        container.removeAllViews()
        PlacesStore.getSaved(requireContext())
            .filter { it.type in savedPlacesTypeFilter }
            .sortedBy { it.type.ordinal }
            .forEach { place ->
                addPlaceRow(
                    container, "${place.type.icon} ${place.name}", place.type.label,
                    titleColor = Color.parseColor("#222222"), subtitleColor = Color.parseColor("#888888")
                ) {
                    closeSavedPlacesScreen()
                    editSavedPlace(place)
                }
            }
    }

    private fun toggleSavedPlacesFilter(type: PlaceType) {
        if (!savedPlacesTypeFilter.remove(type)) savedPlacesTypeFilter.add(type)
        updateSavedPlacesFilterButtons()
        refreshSavedPlacesScreenList()
    }

    private fun updateSavedPlacesFilterButtons() {
        val selectedTint = android.content.res.ColorStateList.valueOf(Color.parseColor("#0091EA"))
        val normalTint    = android.content.res.ColorStateList.valueOf(Color.parseColor("#F5F5F5"))
        mapOf(
            PlaceType.BERTH to binding.btnFilterBerth,
            PlaceType.FAVORITE to binding.btnFilterFavorite,
            PlaceType.TO_VISIT to binding.btnFilterSpecial,
            PlaceType.GENERIC to binding.btnFilterGeneric
        ).forEach { (type, btn) ->
            val active = type in savedPlacesTypeFilter
            btn.backgroundTintList = if (active) selectedTint else normalTint
        }
    }

    private fun addPlaceRow(
        container: LinearLayout, title: String, subtitle: String,
        titleColor: Int = Color.WHITE, subtitleColor: Int = Color.LTGRAY,
        onClick: () -> Unit
    ) {
        val ctx = requireContext()
        val outValue = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            isClickable = true
            isFocusable = true
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { onClick() }
        }
        row.addView(TextView(ctx).apply { text = title; textSize = 14f; setTextColor(titleColor) })
        row.addView(TextView(ctx).apply { text = subtitle; textSize = 11f; setTextColor(subtitleColor) })
        container.addView(row)
    }

    /** Ricerca "nuovi posti" (OpenStreetMap/Nominatim) per la lista di ricerca in tempo reale
     *  (vedi updateLiveSearchResults). */
    private suspend fun searchNominatimMulti(query: String): List<Pair<LatLng, String>> {
        return try {
            val enc  = java.net.URLEncoder.encode(query, "UTF-8")
            val url  = "https://nominatim.openstreetmap.org/search?format=json&q=$enc&bounded=1&viewbox=11.7,45.65,12.85,45.05&limit=5"
            val conn = URL(url).openConnection()
            conn.setRequestProperty("User-Agent", "LagunaNav/1.0")
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val arr = JSONArray(conn.getInputStream().bufferedReader().readText())
            (0 until arr.length()).map { i ->
                val obj  = arr.getJSONObject(i)
                val name = obj.getString("display_name").split(",").take(2).joinToString(", ")
                LatLng(obj.getDouble("lat"), obj.getDouble("lon")) to name
            }
        } catch (_: Exception) { emptyList() }
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
                // CENTRA "intelligente": se sei già zoomato quanto (o più di) la distanza x
                // (CameraTuning.recenterZoom), lo zoom resta invariato — sei già più vicino di x,
                // ha senso solo centrare sulla barca. Se invece sei più lontano di x, si zooma
                // fino a x invece di lasciarti a un livello scomodo.
                val currentZoom = map.cameraPosition.zoom
                val zoom = if (currentZoom < CameraTuning.recenterZoom)
                    CameraTuning.recenterZoom else currentZoom
                val target = followCameraTarget(map, LatLng(lastFix.lat, lastFix.lon))
                map.animateCamera(CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(target).zoom(zoom).bearing(smoothedCamBearing).build()
                ), 500)
            }
        }
    }

    private fun setupButtons() {
        // CENTRA: riattiva follow mode → pulsante sparisce
        binding.fabRecentra.setOnClickListener {
            setFollowMode(true)
        }

        // Bussola custom: se sei in modalità Segui, il loop camera (~45Hz) ruota continuamente
        // la mappa per allinearla alla prua della barca — provare a "vincere" quella rotazione
        // frame per frame (lasciando followMode attivo) produceva uno snap che si annullava da
        // solo al fotogramma successivo, sembrando che il tap non facesse nulla. Si stacca quindi
        // il follow mode (la barca resta comunque al centro, semplicemente la camera smette di
        // ruotare da sola) e si riporta il nord in su. Se invece la camera era già libera, si
        // resetta solo il nord senza spostare il centro attuale (comportamento pre-esistente).
        binding.cardCompass.setOnClickListener {
            if (followMode) {
                val lastFix = fixBuffer.lastOrNull()
                val map = mapLibre
                setFollowMode(false)
                if (map != null && lastFix != null) {
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(LatLng(lastFix.lat, lastFix.lon))
                            .bearing(0.0).zoom(map.cameraPosition.zoom).build()
                    ), 400)
                }
            } else {
                mapLibre?.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder().bearing(0.0).build()
                    ), 500
                )
            }
        }

        binding.btnNavChipClose.setOnClickListener { cancelRoute() }

        // Banner GPS disattivato: tap -> apre le impostazioni di localizzazione di sistema
        binding.cardGpsDisabled.setOnClickListener {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    // =================================================================
    // GPS ATTIVO/DISATTIVATO (solo Mappa reale, non Dev Tools)
    // =================================================================

    private val gpsStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateGpsEnabledBanner()
    }

    private fun updateGpsEnabledBanner() {
        if (debugMode || _binding == null) return
        val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val enabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        binding.cardGpsDisabled.visibility = if (!enabled) View.VISIBLE else View.GONE
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

    private fun emptyFc() = """{"type":"FeatureCollection","features":[]}"""

    // =================================================================
    // GEOMETRIA
    // =================================================================

    private fun screenDistance(a: android.graphics.PointF, b: android.graphics.PointF): Float {
        val dx = a.x - b.x; val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    /** Calcola il target di camera (punto che finirà al centro schermo) tale per cui [boatPos]
     *  finisca invece a UiTuning.followBoatScreenYFraction dall'alto (regolabile da Dev Tools).
     *  Usa la projection corrente (riflette bearing/zoom di questo fotogramma) — stessa tecnica
     *  di centerPointInUpperScreen, qui applicata continuamente ad ogni fotogramma del follow
     *  mode invece che una tantum. */
    private fun followCameraTarget(map: MapLibreMap, boatPos: LatLng): LatLng {
        val width = binding.mapView.width.toFloat()
        val height = binding.mapView.height.toFloat()
        if (width <= 0f || height <= 0f) return boatPos
        val projection = map.projection
        val currentScreenPos = projection.toScreenLocation(boatPos)
        val screenCenter = android.graphics.PointF(width / 2f, height / 2f)
        val desiredScreenPos = android.graphics.PointF(width / 2f, height * UiTuning.followBoatScreenYFraction)
        val newTargetScreen = android.graphics.PointF(
            currentScreenPos.x + screenCenter.x - desiredScreenPos.x,
            currentScreenPos.y + screenCenter.y - desiredScreenPos.y
        )
        return projection.fromScreenLocation(newTargetScreen)
    }

    /** Sposta la camera in modo che "pos" finisca nella parte centro-alta dello schermo invece
     *  che al centro esatto: i popup (place-detail, salva luogo) occupano la parte bassa dello
     *  schermo e coprirebbero il punto appena selezionato. Calcolato tramite la projection
     *  corrente (screen -> LatLng e viceversa) così funziona anche con bearing/tilt della
     *  camera, non solo con la mappa orientata a nord. */
    private fun centerPointInUpperScreen(pos: LatLng) {
        val map = mapLibre ?: return
        val width = binding.mapView.width.toFloat()
        val height = binding.mapView.height.toFloat()
        if (width <= 0f || height <= 0f) return
        val projection = map.projection
        val currentScreenPos = projection.toScreenLocation(pos)
        val screenCenter = android.graphics.PointF(width / 2f, height / 2f)
        val desiredScreenPos = android.graphics.PointF(width / 2f, height * 0.3f)
        val newTargetScreen = android.graphics.PointF(
            currentScreenPos.x + screenCenter.x - desiredScreenPos.x,
            currentScreenPos.y + screenCenter.y - desiredScreenPos.y
        )
        val newTarget = projection.fromScreenLocation(newTargetScreen)
        map.easeCamera(CameraUpdateFactory.newLatLng(newTarget), 400)
    }

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

    // Oltre questa soglia (gradi) un cambio di bearing tra due segmenti consecutivi del percorso
    // è considerato una VERA svolta di canale/incrocio, non il rumore dei vertici del grafo su
    // una curva dolce dello stesso canale.
    private val TURN_ANGLE_THRESHOLD_DEG = 30f

    // Quanti vertici avanti cercare al massimo la prossima svolta: senza un limite, su un tratto
    // dritto lungo si scorrerebbe l'intero percorso residuo ad ogni fotogramma del loop camera
    // (chiamata ad ogni refresh HUD, fino a ~20 volte al secondo), rendendo la barca a scatti.
    private val TURN_SEARCH_LOOKAHEAD = 150

    /** Cerca avanti lungo il percorso (al massimo [lookahead] vertici) il prossimo cambio di
     *  direzione abbastanza marcato da essere una vera svolta (vedi TURN_ANGLE_THRESHOLD_DEG).
     *  Ritorna l'indice del vertice dove avviene la svolta, o null se non trovata entro il
     *  raggio di ricerca (percorso dritto, o svolta troppo lontana da mostrare già ora). */
    private fun findNextTurnIndex(route: List<LatLng>, fromIdx: Int, lookahead: Int): Int? {
        val limit = minOf(fromIdx + lookahead, route.size - 2)
        var k = fromIdx
        while (k < limit) {
            val b1 = bearingTo(route[k], route[k + 1])
            val b2 = bearingTo(route[k + 1], route[k + 2])
            val diff = Math.abs(((b2 - b1 + 540f) % 360f) - 180f)
            if (diff > TURN_ANGLE_THRESHOLD_DEG) return k + 1
            k++
        }
        return null
    }

    /** Angolo di svolta RELATIVO alla direzione di marcia attuale, arrotondato al multiplo di
     *  45° più vicino: 0=dritto, 90=destra, 180=indietro (inversione a U), 270=sinistra. */
    private fun relativeTurnAngleDeg(bearingBefore: Float, bearingAfter: Float): Float {
        val rel = ((bearingAfter - bearingBefore) % 360f + 360f) % 360f
        return ((rel + 22.5f) / 45f).toInt() % 8 * 45f
    }

    private fun formatTurnAngle(deg: Float): String = "%.0f°".format(deg)

    private fun getArrowDrawableForAngle(deg: Float): Int {
        return when (deg) {
            45f -> R.drawable.ic_nav_slight_right
            90f -> R.drawable.ic_nav_turn_right
            135f -> R.drawable.ic_nav_sharp_right
            180f -> R.drawable.ic_nav_uturn_right
            225f -> R.drawable.ic_nav_sharp_left
            270f -> R.drawable.ic_nav_turn_left
            315f -> R.drawable.ic_nav_slight_left
            else -> R.drawable.ic_nav_straight
        }
    }

    // =================================================================
    // LIFECYCLE
    // =================================================================

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        startPositionTracking()
        startCameraLoop()
        refreshSavedPlacesLayer()
        // Riavvia il controllo percorso ottimale se c'è una navigazione attiva
        if (activeRoute != null && destination != null) startBgReroute()

        // Banner "GPS disattivato": solo sulla Mappa reale, non in Dev Tools (simulatore).
        if (!debugMode) {
            updateGpsEnabledBanner()
            ContextCompat.registerReceiver(
                requireContext(), gpsStateReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
        stopPositionTracking()
        stopCameraLoop()

        bgRerouteJob?.cancel()

        if (!debugMode) {
            try { requireContext().unregisterReceiver(gpsStateReceiver) } catch (_: IllegalArgumentException) {}
        }
    }

    override fun onStop() {
        super.onStop()
        binding.mapView.onStop()
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); binding.mapView.onSaveInstanceState(out) }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }
    override fun onDestroyView() {
        super.onDestroyView()
        binding.mapView.onDestroy()
        _binding = null
    }
}
