package it.lagunav.openlagunamaps.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
    private const val TAG = "OfflinePackInstaller"
    private const val ASSET_NAME = "mbgl-offline.db"

    fun installIfNeeded(context: Context, onDone: () -> Unit) {
        val dest = File(context.filesDir, ASSET_NAME)
        // Se esiste già (bundle precedente già installato, o un pacchetto scaricato con Dev
        // Tools) non lo tocchiamo: non vogliamo cancellare un download più recente dell'utente.
        if (dest.exists()) {
            Log.d(TAG, "Pacchetto già presente (${dest.length()} byte), copia saltata")
            onDone()
            return
        }
        Thread {
            val startedAt = System.currentTimeMillis()
            try {
                val assetList = context.assets.list("")?.toList() ?: emptyList()
                Log.d(TAG, "Asset in root: $assetList (contiene $ASSET_NAME? ${assetList.contains(ASSET_NAME)})")
                Log.d(TAG, "filesDir=${context.filesDir.absolutePath} spazio libero=${context.filesDir.freeSpace} byte")

                val assetSize = context.assets.openFd(ASSET_NAME).use { it.length }
                Log.d(TAG, "Asset $ASSET_NAME apribile via AssetFileDescriptor, size dichiarata=$assetSize byte")
                context.assets.open(ASSET_NAME).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
                }
                val elapsedMs = System.currentTimeMillis() - startedAt
                // Verifica esplicita invece di assumere che "nessuna eccezione" = copia integra:
                // un asset di queste dimensioni (~150MB) è proprio il caso in cui Android può
                // troncare/fallire la lettura senza sollevare un'eccezione chiara.
                if (dest.length() != assetSize) {
                    Log.e(TAG, "Copia incompleta: attesi $assetSize byte, copiati ${dest.length()} in ${elapsedMs}ms")
                    dest.delete()
                } else {
                    Log.d(TAG, "Pacchetto copiato correttamente (${dest.length()} byte) in ${elapsedMs}ms")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Errore copiando il pacchetto offline", e)
                dest.delete()
            }
            Handler(Looper.getMainLooper()).post { onDone() }
        }.start()
    }
}
