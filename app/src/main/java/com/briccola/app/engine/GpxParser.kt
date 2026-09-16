package com.briccola.app.engine

import android.location.Location
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object GpxParser {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun exportToGpx(track: Track): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Briccola App\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <metadata>\n")
        sb.append("    <name>${escapeXml(track.name)}</name>\n")
        sb.append("    <time>${isoFormat.format(Date(track.startTime))}</time>\n")
        sb.append("  </metadata>\n")
        sb.append("  <trk>\n")
        sb.append("    <name>${escapeXml(track.name)}</name>\n")
        sb.append("    <trkseg>\n")
        
        for (pt in track.points) {
            sb.append("      <trkpt lat=\"${pt.lat}\" lon=\"${pt.lon}\">\n")
            if (pt.speed > 0f) {
                sb.append("        <speed>${pt.speed}</speed>\n")
            }
            sb.append("        <time>${isoFormat.format(Date(pt.timestamp))}</time>\n")
            sb.append("      </trkpt>\n")
        }
        
        sb.append("    </trkseg>\n")
        sb.append("  </trk>\n")
        sb.append("</gpx>")
        return sb.toString()
    }

    fun importFromGpx(inputStream: InputStream): Track? {
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(inputStream, "UTF-8")

            var eventType = parser.eventType
            var trackName = "Traccia Importata"
            val points = mutableListOf<TrackPoint>()
            var currentLat = 0.0
            var currentLon = 0.0
            var currentTime = System.currentTimeMillis()
            var currentSpeed = 0f
            var inTrkpt = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName.equals("name", ignoreCase = true)) {
                            if (trackName == "Traccia Importata") {
                                val text = parser.nextText()
                                if (!text.isNullOrBlank()) trackName = text
                            }
                        } else if (tagName.equals("trkpt", ignoreCase = true)) {
                            inTrkpt = true
                            currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            currentTime = System.currentTimeMillis()
                            currentSpeed = 0f
                        } else if (inTrkpt && tagName.equals("time", ignoreCase = true)) {
                            val timeStr = parser.nextText()
                            try {
                                val date = isoFormat.parse(timeStr)
                                if (date != null) currentTime = date.time
                            } catch (_: Exception) {}
                        } else if (inTrkpt && tagName.equals("speed", ignoreCase = true)) {
                            currentSpeed = parser.nextText()?.toFloatOrNull() ?: 0f
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName.equals("trkpt", ignoreCase = true)) {
                            points.add(TrackPoint(currentLat, currentLon, currentTime, currentSpeed))
                            inTrkpt = false
                        }
                    }
                }
                eventType = parser.next()
            }

            if (points.isEmpty()) return null

            val startTime = points.first().timestamp
            val endTime = points.last().timestamp
            val durationSec = Math.max(1L, (endTime - startTime) / 1000L)

            var totalDist = 0f
            for (i in 1 until points.size) {
                val p1 = points[i - 1]
                val p2 = points[i]
                val results = FloatArray(1)
                Location.distanceBetween(p1.lat, p1.lon, p2.lat, p2.lon, results)
                totalDist += results[0]
            }

            val avgSpeedMps = totalDist / durationSec
            val avgSpeedKn = avgSpeedMps * 3600f / 1852f

            return Track(
                name = trackName,
                startTime = startTime,
                endTime = endTime,
                distanceMeters = totalDist,
                durationSeconds = durationSec,
                avgSpeedKnots = avgSpeedKn,
                points = points
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun escapeXml(str: String): String {
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
