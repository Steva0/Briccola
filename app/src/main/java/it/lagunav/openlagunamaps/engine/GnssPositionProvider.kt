package it.lagunav.openlagunamaps.engine

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager

class GnssPositionProvider(private val context: Context) : PositionProvider {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null

    override fun start(onFix: (Location) -> Unit) {
        val l = LocationListener { onFix(it) }
        listener = l
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                1000L,   // minTimeMs: 1 Hz
                0f,      // minDistanceM: ogni fix
                l
            )
        } catch (_: SecurityException) {}
    }

    override fun stop() {
        listener?.let { locationManager.removeUpdates(it) }
        listener = null
    }
}
