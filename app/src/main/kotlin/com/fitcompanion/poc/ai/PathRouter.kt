package com.fitcompanion.poc.ai

import com.fitcompanion.core.Geo
import com.fitcompanion.core.Waypoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turns the AI's stops into a route along real walking paths and roads, using the free OpenStreetMap walking router
 * run by FOSSGIS (routing.openstreetmap.de, OSRM "foot" profile). Its rules: at most one request a second, so legs
 * are routed one at a time with a pause. Only the stops' coordinates are sent, never anything about you.
 * A leg that can't be routed (no path, too long, server down) stays a straight line, and that's reported.
 */
object PathRouter {
    private const val USER_AGENT = "FitnessCompanionPersonal/0.1 (private single-user app)"
    private const val MAX_LEG_METERS = 400_000.0
    private const val SPACING_METERS = 100.0

    data class Result(val points: List<Waypoint>, val routedLegs: Int, val straightLegs: List<String>, val detours: List<String>)

    suspend fun route(stops: List<Waypoint>, onProgress: (String) -> Unit): Result = withContext(Dispatchers.IO) {
        val out = mutableListOf<Waypoint>()
        val straight = mutableListOf<String>(); val detours = mutableListOf<String>()
        var routed = 0
        for (i in 0 until stops.size - 1) {
            val a = stops[i]; val b = stops[i + 1]
            out += a
            val direct = Geo.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            onProgress("Following paths ${i + 1} of ${stops.size - 1}: ${a.name} → ${b.name}")
            val leg = if (direct in 50.0..MAX_LEG_METERS) { if (i > 0) delay(1100); runCatching { leg(a, b) }.getOrNull() } else null
            if (leg == null) {
                if (direct >= 50.0) straight += "${a.name} → ${b.name}"
                continue
            }
            routed++
            val (path, meters) = leg
            if (meters > 3 * direct && meters - direct > 20_000)
                detours += "${a.name} → ${b.name}: %.0f km by path vs %.0f km direct".format(meters / 1000, direct / 1000)
            // Intermediate points only (the named stops themselves are kept exactly), thinned to ~100 m apart.
            var last = a.latitude to a.longitude
            for (p in path.drop(1).dropLast(1)) {
                if (Geo.haversineMeters(last.first, last.second, p.first, p.second) >= SPACING_METERS) {
                    out += Waypoint("", p.first, p.second); last = p
                }
            }
        }
        out += stops.last()
        Result(out, routed, straight, detours)
    }

    /** One leg: the walking path's points (lat, lon) and its length in metres. */
    private fun leg(a: Waypoint, b: Waypoint): Pair<List<Pair<Double, Double>>, Double>? {
        val coords = "%.6f,%.6f;%.6f,%.6f".format(java.util.Locale.ROOT, a.longitude, a.latitude, b.longitude, b.latitude)
        val url = "https://routing.openstreetmap.de/routed-foot/route/v1/driving/$coords?overview=full&geometries=geojson&steps=false"
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.connectTimeout = 10_000; c.readTimeout = 30_000
            if (c.responseCode != 200) return null
            val o = JSONObject(c.inputStream.use { String(it.readBytes()) })
            if (o.optString("code") != "Ok") return null
            val r = o.getJSONArray("routes").getJSONObject(0)
            val arr = r.getJSONObject("geometry").getJSONArray("coordinates")
            val pts = (0 until arr.length()).map { i -> arr.getJSONArray(i).let { it.getDouble(1) to it.getDouble(0) } }
            return if (pts.size >= 2) pts to r.getDouble("distance") else null
        } finally {
            c.disconnect()
        }
    }
}
