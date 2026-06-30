package it.lagunav.openlagunamaps.engine

import android.location.Location

interface PositionProvider {
    fun start(onFix: (Location) -> Unit)
    fun stop()
}
