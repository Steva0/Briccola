package it.lagunav.openlagunamaps.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Copia il pacchetto mappa offline precotto (regione laguna + 35km, scaricata una volta con
 * Dev Tools > Mappa Offline e bundlata come asset) nello storage interno dell'app al primo
 * avvio, così è già disponibile senza bisogno di connessione o di passare dal download manuale.
 *
 * Va chiamata PRIMA che una qualunque MapView/MapLibreMap venga creata: MapLibre apre/crea il
 * proprio database ("mbgl-offline.db" in context.filesDir) al primo utilizzo, quindi se la
 * copia arriva dopo trova già un file (vuoto) al suo posto e non lo sovrascrive.
 */
object OfflinePackInstaller {
    private const val ASSET_NAME = "mbgl-offline.db"

    fun installIfNeeded(context: Context, onDone: () -> Unit) {
        val dest = File(context.filesDir, ASSET_NAME)
        // Se esiste già (bundle precedente già installato, o un pacchetto scaricato con Dev
        // Tools) non lo tocchiamo: non vogliamo cancellare un download più recente dell'utente.
        if (dest.exists()) {
            onDone()
            return
        }
        Thread {
            try {
                context.assets.open(ASSET_NAME).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                dest.delete()
            }
            Handler(Looper.getMainLooper()).post { onDone() }
        }.start()
    }
}
