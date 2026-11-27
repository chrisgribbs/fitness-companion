package com.fitcompanion.poc.ai

import android.content.Context
import com.fitcompanion.core.Geo
import com.fitcompanion.core.WaypointCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Checks AI-suggested waypoints against OpenStreetMap's Nominatim search, so a made-up or misplaced
 * coordinate is caught before it becomes part of a challenge.
 *
 * Follows the Nominatim usage policy (https://operations.osmfoundation.org/policies/nominatim/):
 *  - only runs when you create a challenge and tap "Check places" — never periodically or in the background;
 *  - at most one request per second (we wait 1.1 s between requests);
 *  - identifies the app with its own User-Agent;
 *  - caches every answer on the phone so the same place is never looked up twice;
 *  - shows "© OpenStreetMap contributors" attribution on the results.
 * Only place names (and a rough area hint) are sent — never your health data or location.
 */
class PlaceChecker(context: Context) {
    private val cache = context.getSharedPreferences("geocode_cache", Context.MODE_PRIVATE)
    private val userAgent = "FitnessCompanionPersonal/0.1 (private single-user app)"

    /**
     * Looks [name] up within [countryCode] (e.g. "au"), preferring results inside [area] (the route's own bounding
     * box, padded), and takes the match closest to where the AI put it. Without this, "Torquay" finds England.
     */
    suspend fun check(name: String, aiLat: Double, aiLon: Double, countryCode: String?, area: DoubleArray?,
                      onProgress: (String) -> Unit = {}): WaypointCheck {
        val found = lookup(name, aiLat, aiLon, countryCode, area, onProgress)
        return WaypointCheck.of(name, aiLat, aiLon, found?.first, found?.second)
    }

    private suspend fun lookup(name: String, aiLat: Double, aiLon: Double, cc: String?, area: DoubleArray?,
                               onProgress: (String) -> Unit): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val key = "v2|${cc.orEmpty()}|${name.trim().lowercase()}|${"%.1f,%.1f".format(aiLat, aiLon)}"
        cache.getString(key, null)?.let { cached ->
            if (cached == "none") return@withContext null
            val (la, lo) = cached.split(',').map { it.toDouble() }
            return@withContext la to lo
        }
        onProgress("Looking up \"$name\"…")
        delay(1100) // stay under 1 request per second
        var url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&q=" + URLEncoder.encode(name, "UTF-8")
        if (!cc.isNullOrBlank()) url += "&countrycodes=" + URLEncoder.encode(cc.lowercase(), "UTF-8")
        // viewbox = left,top,right,bottom; not "bounded", so it's a strong preference rather than a hard limit.
        if (area != null) url += "&viewbox=%.4f,%.4f,%.4f,%.4f".format(area[1], area[2], area[3], area[0])
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("User-Agent", userAgent)
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            if (c.responseCode != 200) return@withContext null // not cached: may be temporary
            val arr = JSONArray(c.inputStream.use { String(it.readBytes()) })
            val result = (0 until arr.length()).map { i -> arr.getJSONObject(i).let { o: JSONObject ->
                o.getString("lat").toDouble() to o.getString("lon").toDouble() } }
                .minByOrNull { Geo.haversineMeters(aiLat, aiLon, it.first, it.second) }
            cache.edit().putString(key, result?.let { "${it.first},${it.second}" } ?: "none").apply()
            result
        } catch (_: java.io.IOException) {
            null
        } finally {
            c.disconnect()
        }
    }

    companion object {
        /** The route's bounding box padded by ~0.5° (about 50 km): [south, west, north, east]. */
        fun areaOf(points: List<Pair<Double, Double>>): DoubleArray? {
            if (points.isEmpty()) return null
            val la = points.map { it.first }; val lo = points.map { it.second }
            if (lo.max() - lo.min() > 180) return null // crosses the date line or the AI went wild: no hint
            return doubleArrayOf(la.min() - 0.5, lo.min() - 0.5, la.max() + 0.5, lo.max() + 0.5)
        }
    }
}
