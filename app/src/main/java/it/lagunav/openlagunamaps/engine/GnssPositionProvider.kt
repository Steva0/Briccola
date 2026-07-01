package it.lagunav.openlagunamaps.engine

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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

    override fun start(onFix: (Location) -> Unit) {
        gotGpsFix = false
        val looper = Looper.getMainLooper()

        try {
            // 1. Passive: posizioni cacheate da altre app (istantaneo, 0 batteria)
            lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                ?.takeIf { it.accuracy < 1000f }?.let { onFix(it) }

            // 2. Last known GPS/rete: feedback immediato anche se stale
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onFix(it) }
            lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?.takeIf { it.accuracy < 500f }?.let { onFix(it) }

            // 3. Rete: fix rapido mentre il GPS si scalda (minTime=0 per il primo fix)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                val netL = LocationListener { loc -> if (!gotGpsFix) onFix(loc) }
                listeners.add(netL)
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, netL, looper)
            }

            // 4. GPS: preciso, una volta agganciato prende il sopravvento sulla rete
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                val gpsL = LocationListener { loc -> gotGpsFix = true; onFix(loc) }
                listeners.add(gpsL)
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsL, looper)
            }

            // Passive aggiornato (bassa frequenza, zero consumo batteria)
            if (lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                val passL = LocationListener { loc -> if (!gotGpsFix) onFix(loc) }
                listeners.add(passL)
                lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 2000L, 0f, passL, looper)
            }
        } catch (_: SecurityException) {}
    }

    override fun stop() {
        listeners.forEach { lm.removeUpdates(it) }
        listeners.clear()
        gotGpsFix = false
    }
}
