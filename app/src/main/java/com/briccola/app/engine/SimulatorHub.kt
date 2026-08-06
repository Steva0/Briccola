package com.briccola.app.engine

import android.location.Location

/**
 * Singleton che fa da ponte tra DevToolsFragment (che controlla il simulatore)
 * e MapFragment (che usa la posizione simulata come se fosse GPS reale).
 * Quando il simulatore è attivo in DevTools, MapFragment riceve ogni fix
 * tramite questo hub invece che dal GPS reale.
 */
object SimulatorHub {

    @Volatile var provider: SimulatedPositionProvider? = null
    val isActive get() = provider != null

    private val listeners = mutableSetOf<(Location) -> Unit>()

    fun addListener(cb: (Location) -> Unit) { synchronized(listeners) { listeners.add(cb) } }
    fun removeListener(cb: (Location) -> Unit) { synchronized(listeners) { listeners.remove(cb) } }
    fun notify(location: Location) { synchronized(listeners) { listeners.forEach { it(location) } } }
}
