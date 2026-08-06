package com.briccola.app

import android.app.Application
import org.maplibre.android.MapLibre

class LagunaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Inizializzazione globale di MapLibre
        MapLibre.getInstance(this)
    }
}