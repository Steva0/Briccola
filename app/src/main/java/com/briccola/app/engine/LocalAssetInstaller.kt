package com.briccola.app.engine

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Copia i database SQLite della mappa offline precotta (tile vettoriali, tile raster di
 * sfondo, glifi — vedi genera_tiles_offline.py) dagli asset a filesDir al primo avvio, cosi'
 * LocalTileServer puo' aprirli con un path reale (SQLite non puo' leggere direttamente da un
 * asset compresso dentro l'APK).
 *
 * Sostituisce il vecchio OfflinePackInstaller, che copiava il database interno di MapLibre
 * (mbgl-offline.db) confidando che la libreria nativa lo riconoscesse: non funzionava in modo
 * affidabile. Qui copiamo formati SQLite standard che apriamo e serviamo noi, quindi la
 * verifica di integrita' e' una query SQL reale, non solo un confronto di dimensioni.
 */
object LocalAssetInstaller {

    val DB_ASSETS = listOf(
        "tiles_vector.mbtiles" to "SELECT count(*) FROM tiles LIMIT 1",
        "tiles_raster.mbtiles" to "SELECT count(*) FROM tiles LIMIT 1",
        "glyphs.db" to "SELECT count(*) FROM glyphs LIMIT 1",
    )

    private const val VERSION_ASSET = "tiles_version.txt"

    fun installIfNeeded(context: Context, onDone: () -> Unit) {
        Thread {
            try {
                // 1. Verifica se ci sono nuovi dati bundlati nell'APK (se presente tiles_version.txt)
                val forceReinstall = isBundledVersionNewer(context)
                
                // 2. Prova a installare ogni asset definito. 
                for ((assetName, checkQuery) in DB_ASSETS) {
                    installOne(context, assetName, checkQuery, forceReinstall)
                }
                
                updateInstalledVersionMarker(context)
            } catch (e: Exception) {
                // Errore critico durante l'installazione asset
            } finally {
                // Fondamentale: assicuriamoci che onDone venga SEMPRE chiamata,
                // altrimenti l'app rimane grigia all'avvio se un file manca.
                Handler(Looper.getMainLooper()).post { onDone() }
            }
        }.start()
    }
    
    /** Ritorna true se l'asset specificato esiste fisicamente dentro l'APK. */
    private fun assetExists(context: Context, assetName: String): Boolean {
        return try {
            context.assets.open(assetName).use { true }
        } catch (e: Exception) {
            false
        }
    }

    private fun isBundledVersionNewer(context: Context): Boolean {
        if (!assetExists(context, VERSION_ASSET)) return false
        val bundled = try {
            context.assets.open(VERSION_ASSET).bufferedReader().use { it.readText() }.trim()
        } catch (e: Exception) {
            return false
        }
        val installed = File(context.filesDir, VERSION_ASSET).takeIf { it.exists() }
            ?.readText()?.trim()
        return bundled != installed
    }

    private fun updateInstalledVersionMarker(context: Context) {
        try {
            if (!assetExists(context, VERSION_ASSET)) return
            val bundled = context.assets.open(VERSION_ASSET).bufferedReader().use { it.readText() }
            File(context.filesDir, VERSION_ASSET).writeText(bundled)
        } catch (e: Exception) {
            // Nessun VERSION_ASSET negli asset: niente da salvare, non è un errore.
        }
    }

    private fun installOne(context: Context, assetName: String, checkQuery: String, forceReinstall: Boolean) {
        val dest = File(context.filesDir, assetName)
        
        // Se il file esiste già ed è valido, e non è richiesto un aggiornamento forzato, siamo a posto.
        if (!forceReinstall && dest.exists() && isValidSqlite(dest, checkQuery)) {
            return
        }

        // Se l'asset non esiste nell'APK, non possiamo fare "installazione" (copia dagli asset).
        // Il file dovrà essere scaricato tramite MapDownloader se manca.
        if (!assetExists(context, assetName)) {
            return
        }

        try {
            val assetSize = context.assets.openFd(assetName).use { it.length }
            context.assets.open(assetName).use { input ->
                dest.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
            }
            if (dest.length() != assetSize) {
                dest.delete()
                return
            }
            if (!isValidSqlite(dest, checkQuery)) {
                dest.delete()
                return
            }
        } catch (e: Exception) {
            dest.delete()
        }
    }

    private fun isValidSqlite(file: File, checkQuery: String): Boolean {
        return try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(checkQuery, null).use { it.moveToFirst() }
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
