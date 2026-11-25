package com.fitcompanion.core

import org.w3c.dom.Element
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class TcxActivity(
    val sport: String?,
    val id: String?,
    val lapCount: Int,
    val totalTimeSeconds: Double?,
    val totalDistanceMeters: Double?,
    val points: List<TrackPoint>,
) {
    val pointsWithPosition get() = points.count { it.hasPosition }
    val pointsWithAltitude get() = points.count { it.altitudeMeters != null }
    val pointsWithHeartRate get() = points.count { it.heartRateBpm != null }
}

/**
 * Minimal Training Center XML (TCX v2) reader for the Google Health API's exportExerciseTcx output.
 * Reads only what the app needs and tolerates missing elements (e.g. an indoor walk has no Position).
 */
object TcxParser {

    fun parse(input: InputStream): TcxActivity {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            trySetFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            trySetFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            trySetFeature("http://xml.org/sax/features/external-general-entities", false)
        }
        val doc = factory.newDocumentBuilder().parse(input)
        val activity = doc.getElementsByTagNameNS("*", "Activity").item(0) as? Element

        val laps = doc.getElementsByTagNameNS("*", "Lap")
        var totalTime = 0.0; var totalDist = 0.0; var anyTime = false; var anyDist = false
        for (i in 0 until laps.length) {
            val lap = laps.item(i) as Element
            childText(lap, "TotalTimeSeconds")?.toDoubleOrNull()?.let { totalTime += it; anyTime = true }
            childText(lap, "DistanceMeters")?.toDoubleOrNull()?.let { totalDist += it; anyDist = true }
        }

        val tps = doc.getElementsByTagNameNS("*", "Trackpoint")
        val points = ArrayList<TrackPoint>(tps.length)
        for (i in 0 until tps.length) {
            val tp = tps.item(i) as Element
            val time = childText(tp, "Time")?.let(::parseTime) ?: continue
            val pos = firstChild(tp, "Position")
            val hr = firstChild(tp, "HeartRateBpm")
            points += TrackPoint(
                time = time,
                latitude = pos?.let { childText(it, "LatitudeDegrees")?.toDoubleOrNull() },
                longitude = pos?.let { childText(it, "LongitudeDegrees")?.toDoubleOrNull() },
                altitudeMeters = childText(tp, "AltitudeMeters")?.toDoubleOrNull(),
                distanceMeters = childText(tp, "DistanceMeters")?.toDoubleOrNull(),
                heartRateBpm = hr?.let { childText(it, "Value")?.toDoubleOrNull()?.toInt() },
            )
        }
        return TcxActivity(
            sport = activity?.getAttribute("Sport")?.ifBlank { null },
            id = activity?.let { childText(it, "Id") },
            lapCount = laps.length,
            totalTimeSeconds = if (anyTime) totalTime else null,
            totalDistanceMeters = if (anyDist) totalDist else null,
            points = points.sortedBy { it.time },
        )
    }

    internal fun parseTime(text: String): Instant? = try {
        OffsetDateTime.parse(text.trim()).toInstant()
    } catch (_: Exception) {
        try { Instant.parse(text.trim()) } catch (_: Exception) { null }
    }

    /** Direct child only — avoids picking up a Lap's DistanceMeters when reading a Trackpoint. */
    private fun firstChild(parent: Element, localName: String): Element? {
        var n = parent.firstChild
        while (n != null) {
            if (n is Element && (n.localName ?: n.tagName.substringAfter(':')) == localName) return n
            n = n.nextSibling
        }
        return null
    }

    private fun childText(parent: Element, localName: String): String? =
        firstChild(parent, localName)?.textContent?.trim()?.ifEmpty { null }

    private fun DocumentBuilderFactory.trySetFeature(name: String, value: Boolean) {
        try { setFeature(name, value) } catch (_: Exception) { /* not supported on this platform */ }
    }
}
