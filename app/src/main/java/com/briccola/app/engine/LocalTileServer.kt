package com.briccola.app.engine

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Color
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sinh

/**
 * Serve localmente (127.0.0.1) le tile vettoriali/raster, i glifi e lo sprite della mappa
 * offline precotta, leggendoli dai database SQLite copiati in filesDir da LocalAssetInstaller.
 */
object LocalTileServer {
    private const val REMOTE_TILEJSON_URL = "https://tiles.openfreemap.org/planet"
    private const val REMOTE_RETRY_BACKOFF_MS = 30_000L
    private const val REMOTE_TIMEOUT_MS = 10_000

    private const val MAX_CACHE_BYTES = 200L * 1_000_000L
    private const val VENICE_LAT = 45.4371
    private const val VENICE_LON = 12.3345

    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private var server: Server? = null
    private var bathyEngine: BathymetryEngine? = null

    var port: Int = -1
        private set

    @Volatile private var remoteVectorTemplate: String? = null
    @Volatile private var nextRemoteAttemptAllowedAt: Long = 0L

    fun startIfNeeded(context: Context, bathy: BathymetryEngine? = null) {
        if (server != null) return
        bathyEngine = bathy
        val freePort = ServerSocket(0).use { it.localPort }
        val instance = Server(context.applicationContext, freePort, bathy)
        instance.setAsyncRunner(PooledAsyncRunner())
        try {
            instance.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = instance
            port = freePort
        } catch (e: Exception) {
            // Impossibile avviare il server locale
        }
    }

    /**
     * Forza la chiusura e la riapertura dei file database della mappa.
     * Utile dopo un download o un'eliminazione per applicare le modifiche senza riavviare l'app.
     */
    fun resetCache() {
        server?.reset()
    }

    private fun resolveRemoteVectorTemplate(): String? {
        remoteVectorTemplate?.let { return it }
        val now = System.currentTimeMillis()
        if (now < nextRemoteAttemptAllowedAt) return null
        
        return try {
            val conn = (URL(REMOTE_TILEJSON_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = REMOTE_TIMEOUT_MS
                readTimeout = REMOTE_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val template = JSONObject(body).getJSONArray("tiles").getString(0)
            remoteVectorTemplate = template
            template
        } catch (e: Exception) {
            val fallback = "https://tiles.openfreemap.org/planet/{z}/{x}/{y}.pbf"
            nextRemoteAttemptAllowedAt = now + REMOTE_RETRY_BACKOFF_MS
            fallback
        }
    }

    private fun fetchRemoteTile(url: String): ByteArray? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = REMOTE_TIMEOUT_MS
                readTimeout = REMOTE_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (conn.responseCode != 200) {
                return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) null else bytes
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchRemoteTile(template: String, z: Int, x: Int, y: Int): ByteArray? {
        val url = template.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
        return fetchRemoteTile(url)
    }

    private fun tileDistanceFromVeniceKm(z: Int, x: Int, y: Int): Double {
        val n = (1 shl z).toDouble()
        val lon = (x + 0.5) / n * 360.0 - 180.0
        val latRad = kotlin.math.atan(kotlin.math.sinh(Math.PI * (1 - 2 * (y + 0.5) / n)))
        val lat = Math.toDegrees(latRad)
        val dLat = Math.toRadians(lat - VENICE_LAT)
        val dLon = Math.toRadians(lon - VENICE_LON) * kotlin.math.cos(Math.toRadians((lat + VENICE_LAT) / 2))
        return kotlin.math.sqrt(dLat * dLat + dLon * dLon) * 6371.0
    }

    private class PooledAsyncRunner(poolSize: Int = 32) : NanoHTTPD.AsyncRunner {
        private val executor = java.util.concurrent.Executors.newFixedThreadPool(poolSize)
        private val running = java.util.Collections.synchronizedList(mutableListOf<NanoHTTPD.ClientHandler>())
        override fun closeAll() { synchronized(running) { running.toList() }.forEach { it.close() } }
        override fun closed(clientHandler: NanoHTTPD.ClientHandler) { running.remove(clientHandler) }
        override fun exec(clientHandler: NanoHTTPD.ClientHandler) { running.add(clientHandler); executor.execute(clientHandler) }
    }

    private class Server(
        private val context: Context, 
        port: Int, 
        private val bathy: BathymetryEngine? = null
    ) : NanoHTTPD("127.0.0.1", port) {
        private val cacheDbLock = Any()
        private var cacheDb: SQLiteDatabase? = null
        private val readOnlyDbsLock = Any()
        private val readOnlyDbs = HashMap<String, SQLiteDatabase?>()

        fun reset() {
            synchronized(readOnlyDbsLock) {
                readOnlyDbs.values.forEach { it?.close() }
                readOnlyDbs.clear()
            }
            synchronized(cacheDbLock) {
                cacheDb?.close()
                cacheDb = null
            }
        }

        private fun dbFile(name: String) = File(context.filesDir, name)

        private fun openReadOnly(name: String): SQLiteDatabase? = synchronized(readOnlyDbsLock) {
            if (readOnlyDbs.containsKey(name)) return readOnlyDbs[name]
            val f = dbFile(name)
            val db = if (f.exists()) {
                try { SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READONLY) }
                catch (e: Exception) { null }
            } else null
            readOnlyDbs[name] = db
            db
        }

        private fun openCacheDb(): SQLiteDatabase = synchronized(cacheDbLock) {
            cacheDb?.let { return it }
            val db = SQLiteDatabase.openOrCreateDatabase(dbFile("tiles_vector_cache.mbtiles"), null)
            db.execSQL("CREATE TABLE IF NOT EXISTS tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, dist_km REAL, PRIMARY KEY (zoom_level, tile_column, tile_row))")
            cacheDb = db
            db
        }

        private fun insertIntoCacheAndEvict(cache: SQLiteDatabase, z: Int, x: Int, y: Int, tmsRow: Int, data: ByteArray) {
            synchronized(cacheDbLock) {
                val distKm = tileDistanceFromVeniceKm(z, x, y)
                cache.execSQL("INSERT OR REPLACE INTO tiles (zoom_level, tile_column, tile_row, tile_data, dist_km) VALUES (?, ?, ?, ?, ?)", arrayOf(z, x, tmsRow, data, distKm))
                var totalBytes = cache.rawQuery("SELECT COALESCE(SUM(LENGTH(tile_data)), 0) FROM tiles", null).use { it.moveToFirst(); it.getLong(0) }
                if (totalBytes <= MAX_CACHE_BYTES) return
                cache.rawQuery("SELECT zoom_level, tile_column, tile_row, LENGTH(tile_data) FROM tiles ORDER BY dist_km DESC", null).use { cursor ->
                    while (totalBytes > MAX_CACHE_BYTES && cursor.moveToNext()) {
                        val zz = cursor.getInt(0); val xx = cursor.getInt(1); val rr = cursor.getInt(2); val size = cursor.getLong(3)
                        cache.execSQL("DELETE FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", arrayOf(zz, xx, rr))
                        totalBytes -= size
                    }
                }
            }
        }

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            return try {
                when {
                    uri.startsWith("/bathy-heatmap/") -> serveBathyHeatmap(uri, session.parameters)
                    uri.startsWith("/tiles/") -> serveVectorTile(uri.removePrefix("/tiles/"))
                    uri.startsWith("/raster/") -> serveTile(uri.removePrefix("/raster/"), "tiles_raster.mbtiles", "image/png")
                    uri.startsWith("/fonts/") -> serveGlyph(uri.removePrefix("/fonts/"))
                    uri.startsWith("/sprite/") -> serveSprite(uri.removePrefix("/sprite/"))
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
                }
            } catch (e: Exception) { newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "error") }
        }

        private fun serveBathyHeatmap(uri: String, params: Map<String, List<String>>): Response {
            val path = uri.removePrefix("/bathy-heatmap/")
            val (z, x, y) = parseZxy(path) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "bad path")
            
            // Se lo zoom è troppo basso, il rendering diventa troppo costoso e meno utile
            if (z < 10) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "zoom low")

            val tide = params["tide"]?.firstOrNull()?.toDoubleOrNull() ?: 0.0
            val draft = params["draft"]?.firstOrNull()?.toDoubleOrNull() ?: 0.5
            
            // Coordinate geografiche del tile
            val n = 2.0.pow(z.toDouble())
            val lonMin = x / n * 360.0 - 180.0
            val lonMax = (x + 1) / n * 360.0 - 180.0
            val latMin = Math.toDegrees(atan(sinh(Math.PI * (1 - 2 * (y + 1) / n))))
            val latMax = Math.toDegrees(atan(sinh(Math.PI * (1 - 2 * y / n))))
            
            val resolution = 128 // Manteniamo 128 per velocita', ma con antialiasing
            val lonStep = (lonMax - lonMin) / resolution.toDouble()
            val latStep = (latMax - latMin) / resolution.toDouble()

            val bitmap = Bitmap.createBitmap(resolution, resolution, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint()

            // Definizione colori e alpha per le zone
            val colorRed    = Color.argb(180, 211, 47, 47)
            val colorOrange = Color.argb(160, 255, 152, 0)
            val colorYellow = Color.argb(120, 255, 235, 59)
            val colorTrans  = Color.argb(0, 255, 235, 59)

            fun mix(c1: Int, c2: Int, f: Float): Int {
                val alpha = (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * f).toInt()
                val r = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * f).toInt()
                val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * f).toInt()
                val b = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * f).toInt()
                return Color.argb(alpha, r, g, b)
            }

            for (py in 0 until resolution) {
                for (px in 0 until resolution) {
                    val lat = latMax - py * latStep
                    val lon = lonMin + px * lonStep
                    
                    val depth = bathy?.getRawDepthAt(lat, lon) ?: 0f
                    if (depth > 0.05f) {
                        val margin = (depth + tide) - draft
                        
                        val color = when {
                            margin < 0.275 -> colorRed
                            margin < 0.325 -> mix(colorRed, colorOrange, (margin.toFloat() - 0.275f) / 0.05f)
                            margin < 0.475 -> colorOrange
                            margin < 0.525 -> mix(colorOrange, colorYellow, (margin.toFloat() - 0.475f) / 0.05f)
                            margin < 0.95  -> colorYellow
                            margin < 1.05  -> mix(colorYellow, colorTrans, (margin.toFloat() - 0.95f) / 0.1f)
                            else -> Color.TRANSPARENT
                        }

                        if (color != Color.TRANSPARENT) {
                            paint.color = color
                            canvas.drawPoint(px.toFloat(), py.toFloat(), paint)
                        }
                    }
                }
            }

            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            return newFixedLengthResponse(Response.Status.OK, "image/png", out.toByteArray().inputStream(), out.size().toLong())
        }

        private fun parseZxy(path: String): Triple<Int, Int, Int>? {
            val parts = path.substringBeforeLast('.').split("/")
            if (parts.size != 3) return null
            val z = parts[0].toIntOrNull() ?: return null
            val x = parts[1].toIntOrNull() ?: return null
            val y = parts[2].toIntOrNull() ?: return null
            return Triple(z, x, y)
        }

        private fun queryTile(db: SQLiteDatabase, z: Int, x: Int, tmsRow: Int): ByteArray? {
            db.rawQuery("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?", arrayOf(z.toString(), x.toString(), tmsRow.toString())).use { cursor ->
                if (cursor.moveToFirst()) return cursor.getBlob(0)
            }
            return null
        }

        private fun serveTile(path: String, dbName: String, mime: String): Response {
            val (z, x, y) = parseZxy(path) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "bad path")
            val tmsRow = (1 shl z) - 1 - y
            openReadOnly(dbName)?.let { db ->
                queryTile(db, z, x, tmsRow)?.let { data -> return newFixedLengthResponse(Response.Status.OK, mime, data.inputStream(), data.size.toLong()) }
            }
            if (z <= 12) {
                val url = "https://demotiles.maplibre.org/raster/$z/$x/$y.png"
                fetchRemoteTile(url)?.let { data -> return newFixedLengthResponse(Response.Status.OK, mime, data.inputStream(), data.size.toLong()) }
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no tile")
        }

        private fun serveVectorTile(path: String): Response {
            val (z, x, y) = parseZxy(path) ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "bad path")
            val tmsRow = (1 shl z) - 1 - y
            val mime = "application/x-protobuf"
            
            if (z > 12) {
                openReadOnly("tiles_vector.mbtiles")?.let { db ->
                    queryTile(db, z, x, tmsRow)?.let { data -> return newFixedLengthResponse(Response.Status.OK, mime, data.inputStream(), data.size.toLong()) }
                }
            }
            val cache = openCacheDb()
            queryTile(cache, z, x, tmsRow)?.let { data -> return newFixedLengthResponse(Response.Status.OK, mime, data.inputStream(), data.size.toLong()) }
            val template = resolveRemoteVectorTemplate()
            if (template != null) {
                val data = fetchRemoteTile(template, z, x, y)
                if (data != null) {
                    insertIntoCacheAndEvict(cache, z, x, y, tmsRow, data)
                    return newFixedLengthResponse(Response.Status.OK, mime, data.inputStream(), data.size.toLong())
                }
            }
            openReadOnly("tiles_vector.mbtiles")?.let { db ->
                queryTile(db, z, x, tmsRow)?.let { data -> return newFixedLengthResponse(Response.Status.OK, mime, data.inputStream(), data.size.toLong()) }
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no tile")
        }

        private fun serveGlyph(path: String): Response {
            val lastSlash = path.lastIndexOf('/'); if (lastSlash < 0) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "bad path")
            val fontstack = java.net.URLDecoder.decode(path.substring(0, lastSlash), "UTF-8")
            val range = path.substring(lastSlash + 1).removeSuffix(".pbf")
            val rangeStart = range.substringBefore('-').toIntOrNull() ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "bad range")
            openReadOnly("glyphs.db")?.let { db ->
                db.rawQuery("SELECT data FROM glyphs WHERE fontstack=? AND range_start=?", arrayOf(fontstack, rangeStart.toString())).use { cursor ->
                    if (cursor.moveToFirst()) { val data = cursor.getBlob(0); return newFixedLengthResponse(Response.Status.OK, "application/x-protobuf", data.inputStream(), data.size.toLong()) }
                }
            }
            val url = "https://tiles.openfreemap.org/fonts/$fontstack/$range.pbf"
            fetchRemoteTile(url)?.let { data ->
                return newFixedLengthResponse(Response.Status.OK, "application/x-protobuf", data.inputStream(), data.size.toLong())
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no glyph")
        }

        private fun serveSprite(name: String): Response {
            val fileName = "sprite_$name"; val mime = if (name.endsWith(".png")) "image/png" else "application/json"
            return try { val bytes = context.assets.open(fileName).use { it.readBytes() }; newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong()) }
            catch (e: Exception) { newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "no sprite") }
        }
    }
}
