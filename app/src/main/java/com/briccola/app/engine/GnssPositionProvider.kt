package com.briccola.app.engine

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/**
 * Provider GPS reale con strategia a quattro livelli per minimizzare il TTFF:
 *
 * 1. PASSIVE_PROVIDER   — istantaneo, prende la posizione cacheata da qualsiasi altra app
 *                         che abbia già usato il GPS recentemente (spesso c'è Maps in background).
 * 2. Last known location — istantanea, anche se stale: dà subito un punto di partenza visivo.
 * 3. NETWORK_PROVIDER   — 1-5 secondi, basata su WiFi/celle, ~50-200m di precisione.
 *                         Senza Google Play Services è il massimo che possiamo fare rapidamente.
 * 4. GPS_PROVIDER       — 10-90 secondi a freddo, <10m di precisione. Sostitisce rete appena pronto.
 *
 * Nota: senza FusedLocationProviderClient (Google Play Services) non possiamo eguagliare
 * Google Maps (<1s). Il TTFF a freddo del chip GPS è un vincolo hardware, non software.
 */
class GnssPositionProvider(private val context: Context) : PositionProvider {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val listeners = mutableListOf<LocationListener>()
    private var gotGpsFix = false

    // Le chiamate a LocationManager (get/isProviderEnabled/request/removeUpdates) sono IPC verso
    // il servizio di sistema: possono bloccare per decine di ms. Farle sul thread principale
    // causa uno scatto visibile ogni volta che si avvia/ferma il tracking (es. cambio schermata
    // o toggle "posizione reale" in Dev Tools) — le eseguiamo quindi su un thread dedicato.
    // I fix continuano ad arrivare sul thread principale (vedi callbackLooper sotto), quindi
    // onFix() può toccare la UI/mappa come prima.
    private val setupThread = HandlerThread("GnssSetup").apply { start() }
    private val setupHandler = Handler(setupThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun start(onFix: (Location) -> Unit) {
        gotGpsFix = false
        val callbackLooper = Looper.getMainLooper()
        setupHandler.post {
            try {
                // 1. Passive: posizioni cacheate da altre app (istantaneo, 0 batteria)
                lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                    ?.takeIf { it.accuracy < 1000f }?.let { loc -> mainHandler.post { onFix(loc) } }

                // 2. Last known GPS/rete: feedback immediato anche se stale
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { loc -> mainHandler.post { onFix(loc) } }
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?.takeIf { it.accuracy < 500f }?.let { loc -> mainHandler.post { onFix(loc) } }

                // 3. Rete: fix rapido mentre il GPS si scalda (minTime=0 per il primo fix)
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    val netL = LocationListener { loc -> if (!gotGpsFix) onFix(loc) }
                    listeners.add(netL)
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, netL, callbackLooper)
                }

                // 4. GPS: preciso, una volta agganciato prende il sopravvento sulla rete
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    val gpsL = LocationListener { loc -> gotGpsFix = true; onFix(loc) }
                    listeners.add(gpsL)
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsL, callbackLooper)
                }

                // Passive aggiornato (bassa frequenza, zero consumo batteria)
                if (lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                    val passL = LocationListener { loc -> if (!gotGpsFix) onFix(loc) }
                    listeners.add(passL)
                    lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 2000L, 0f, passL, callbackLooper)
                }
            } catch (_: SecurityException) {}
        }
    }

    override fun stop() {
        setupHandler.post {
            listeners.forEach { lm.removeUpdates(it) }
            listeners.clear()
            gotGpsFix = false
        }
        setupThread.quitSafely()  // termina il thread di supporto dopo aver processato il cleanup sopra
    }
}
