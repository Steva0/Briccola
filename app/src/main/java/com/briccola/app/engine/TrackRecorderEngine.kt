package com.briccola.app.engine

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val timestamp: Long,
    val speed: Float = 0f
)

data class Track(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val startTime: Long,
    var endTime: Long,
    var distanceMeters: Float,
    var durationSeconds: Long,
    var avgSpeedKnots: Float,
    val points: List<TrackPoint> = emptyList()
)

object TrackRecorderEngine {
    private const val TRACKS_DIR = "tracks"

    private var isRecording = false
    private var currentTrackName: String = ""
    private var startTimeMs = 0L
    private val currentPoints = mutableListOf<TrackPoint>()
    private var lastLocation: Location? = null
    private var accumulatedDistanceMeters = 0f

    fun isRecordingActive(): Boolean = isRecording

    fun startRecording(defaultName: String = "Traccia ${
        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(
            Date()
        )}") {
        isRecording = true
        currentTrackName = defaultName
        startTimeMs = System.currentTimeMillis()
        currentPoints.clear()
        lastLocation = null
        accumulatedDistanceMeters = 0f
    }

    fun addLocation(location: Location) {
        if (!isRecording) return

        lastLocation?.let { prev ->
            val dist = prev.distanceTo(location)
            if (dist >= 1f) {
                accumulatedDistanceMeters += dist
            }
        }
        lastLocation = location

        currentPoints.add(
            TrackPoint(
                lat = location.latitude,
                lon = location.longitude,
                timestamp = location.time,
                speed = location.speed
            )
        )
    }

    fun stopRecording(context: Context): Track? {
        if (!isRecording) return null
        isRecording = false

        val endTimeMs = System.currentTimeMillis()
        val durationSec = Math.max(1L, (endTimeMs - startTimeMs) / 1000L)
        val avgSpeedMps = accumulatedDistanceMeters / durationSec
        val avgSpeedKn = avgSpeedMps * 3600f / 1852f

        val track = Track(
            name = currentTrackName,
            startTime = startTimeMs,
            endTime = endTimeMs,
            distanceMeters = accumulatedDistanceMeters,
            durationSeconds = durationSec,
            avgSpeedKnots = avgSpeedKn,
            points = currentPoints.toList()
        )

        saveTrack(context, track)
        return track
    }

    fun getAllTracks(context: Context): List<Track> {
        val dir = File(context.filesDir, TRACKS_DIR)
        if (!dir.exists()) return emptyList()

        val tracks = mutableListOf<Track>()
        dir.listFiles { file -> file.extension == "json" }?.forEach { file ->
            try {
                val content = file.readText()
                val obj = JSONObject(content)
                val points = mutableListOf<TrackPoint>()
                val pointsArray = obj.optJSONArray("points") ?: JSONArray()
                for (i in 0 until pointsArray.length()) {
                    val pObj = pointsArray.getJSONObject(i)
                    points.add(
                        TrackPoint(
                            lat = pObj.getDouble("lat"),
                            lon = pObj.getDouble("lon"),
                            timestamp = pObj.getLong("timestamp"),
                            speed = pObj.optDouble("speed", 0.0).toFloat()
                        )
                    )
                }
                val track = Track(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    startTime = obj.getLong("startTime"),
                    endTime = obj.getLong("endTime"),
                    distanceMeters = obj.getDouble("distanceMeters").toFloat(),
                    durationSeconds = obj.getLong("durationSeconds"),
                    avgSpeedKnots = obj.getDouble("avgSpeedKnots").toFloat(),
                    points = points
                )
                tracks.add(track)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return tracks.sortedByDescending { it.startTime }
    }

    fun saveTrack(context: Context, track: Track) {
        val dir = File(context.filesDir, TRACKS_DIR)
        if (!dir.exists()) dir.mkdirs()

        val obj = JSONObject().apply {
            put("id", track.id)
            put("name", track.name)
            put("startTime", track.startTime)
            put("endTime", track.endTime)
            put("distanceMeters", track.distanceMeters.toDouble())
            put("durationSeconds", track.durationSeconds)
            put("avgSpeedKnots", track.avgSpeedKnots.toDouble())
            val pointsArray = JSONArray()
            for (p in track.points) {
                val pObj = JSONObject().apply {
                    put("lat", p.lat)
                    put("lon", p.lon)
                    put("timestamp", p.timestamp)
                    put("speed", p.speed.toDouble())
                }
                pointsArray.put(pObj)
            }
            put("points", pointsArray)
        }

        val file = File(dir, "${track.id}.json")
        file.writeText(obj.toString())
    }

    fun deleteTrack(context: Context, trackId: String) {
        val dir = File(context.filesDir, TRACKS_DIR)
        val jsonFile = File(dir, "$trackId.json")
        if (jsonFile.exists()) jsonFile.delete()

        val gpxFile = File(dir, "$trackId.gpx")
        if (gpxFile.exists()) gpxFile.delete()
    }

    fun renameTrack(context: Context, trackId: String, newName: String) {
        val tracks = getAllTracks(context)
        tracks.find { it.id == trackId }?.let { track ->
            track.name = newName
            saveTrack(context, track)
        }
    }
}
