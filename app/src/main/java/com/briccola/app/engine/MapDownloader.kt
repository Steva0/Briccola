package com.briccola.app.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gestisce il download dei database della mappa offline da un server esterno (es. GitHub).
 */
object MapDownloader {
    private const val BASE_URL = "https://github.com/Steva0/Briccola/releases/download/v1-assets/"

    sealed class DownloadState {
        object Preparing : DownloadState()
        data class Progress(
            val percentage: Int,
            val mbDownloaded: Float,
            val mbTotal: Float,
            val etaSeconds: Long
        ) : DownloadState()
        object Completed : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    fun areMapsInstalled(context: Context): Boolean {
        return LocalAssetInstaller.DB_ASSETS.all { (name, _) ->
            File(context.filesDir, name).exists()
        }
    }

    private suspend fun getTotalDownloadSize(files: List<String>): Long = withContext(Dispatchers.IO) {
        var total = 0L
        for (fileName in files) {
            try {
                val connection = (URL(BASE_URL + fileName).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 3000
                    readTimeout = 3000
                }
                val size = if (android.os.Build.VERSION.SDK_INT >= 24) connection.contentLengthLong else connection.contentLength.toLong()
                if (size > 0) total += size
                connection.disconnect()
            } catch (_: Exception) {
                // Impossibile leggere dimensione
            }
        }
        if (total <= 0) 165_000_000L else total
    }

    fun downloadMaps(context: Context): Flow<DownloadState> = flow {
        emit(DownloadState.Preparing)
        
        val filesToDownload = LocalAssetInstaller.DB_ASSETS.map { it.first } + "tiles_version.txt"
        val totalExpectedBytes = getTotalDownloadSize(filesToDownload)
        
        var bytesDownloadedSoFar = 0L
        val startTime = System.currentTimeMillis()
        var lastEmitTime = 0L
        
        for (fileName in filesToDownload) {
            try {
                val connection = (URL(BASE_URL + fileName).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    emit(DownloadState.Error("Errore server: ${connection.responseCode}"))
                    return@flow
                }

                val destFile = File(context.filesDir, fileName)
                val tempFile = File(context.filesDir, "$fileName.tmp")

                // Buffer da 8KB per aggiornamenti più frequenti
                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        val data = ByteArray(8192)
                        var count: Int
                        while (input.read(data).also { count = it } != -1) {
                            output.write(data, 0, count)
                            bytesDownloadedSoFar += count
                            
                            val now = System.currentTimeMillis()
                            // Aggiorna la UI al massimo ogni 100ms per fluidità ottimale
                            if (now - lastEmitTime > 100) {
                                val elapsedMs = now - startTime
                                val speedBps = if (elapsedMs > 500) (bytesDownloadedSoFar * 1000.0 / elapsedMs) else 0.0
                                val eta = if (speedBps > 1000) ((totalExpectedBytes - bytesDownloadedSoFar) / speedBps).toLong() else 0L
                                val progress = ((bytesDownloadedSoFar.toDouble() / totalExpectedBytes.toDouble()) * 100).toInt().coerceIn(0, 99)
                                
                                emit(DownloadState.Progress(
                                    progress, 
                                    bytesDownloadedSoFar / 1048576f, 
                                    totalExpectedBytes / 1048576f, 
                                    eta
                                ))
                                lastEmitTime = now
                            }
                        }
                    }
                }
                tempFile.renameTo(destFile)
            } catch (e: Exception) {
                emit(DownloadState.Error("Errore download: ${e.localizedMessage}"))
                return@flow
            }
        }
        emit(DownloadState.Completed)
    }.flowOn(Dispatchers.IO)
}
