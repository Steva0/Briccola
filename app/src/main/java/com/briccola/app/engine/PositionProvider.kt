package com.briccola.app.engine

import android.location.Location

interface PositionProvider {
    fun start(onFix: (Location) -> Unit)
    fun stop()
}
