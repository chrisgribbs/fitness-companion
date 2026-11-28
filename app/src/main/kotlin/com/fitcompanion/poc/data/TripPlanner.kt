package com.fitcompanion.poc.data

import com.fitcompanion.core.TrackPoint
import com.fitcompanion.core.TripStreet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/**
 * Plans a walk or run between two places, using free OpenStreetMap services:
 *  - place search (Nominatim), biased towards where you are (only a rough area box is sent, not your position);
 *  - the walking router (FOSSGIS / OSRM foot profile), with street names.
 * What's sent: the place names you typed, and the two end points for routing. Nothing about your health.
 */
object TripPlanner {
    private const val UA = "FitnessCompanionPersonal/0.1 (private single-user app)"

    data class Place(val name: String, val label: String, val lat: Double, val lon: Double)
    data class Route(val points: List<Pair<Double, Double>>, val meters: Double, val streets: List<TripStreet>)

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.setRequestProperty("User-Agent", UA); c.connectTimeout = 10_000; c.readTimeout = 30_000
            if (c.responseCode != 200) null else c.inputStream.use { String(it.readBytes()) }
        } catch (_: java.io.IOException) { null } finally { c.disconnect() }
    }

    /** Up to 5 matches, nearest to [near] first (if given). */
    suspend fun search(query: String, countryCode: String?, near: Pair<Double, Double>?): List<Place> = withContext(Dispatchers.IO) {
        delay(1100) // Nominatim: at most 1 request a second
        var url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5&addressdetails=0&q=" + URLEncoder.encode(query, "UTF-8")
        if (!countryCode.isNullOrBlank()) url += "&countrycodes=" + countryCode.lowercase()
        if (near != null) url += "&viewbox=%.3f,%.3f,%.3f,%.3f".format(java.util.Locale.ROOT, near.second - 0.25, near.first + 0.25, near.second + 0.25, near.first - 0.25)
        val arr = get(url)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return@withContext emptyList()
        val list = (0 until arr.length()).map { k -> arr.getJSONObject(k).let { o: JSONObject ->
            val label = o.optString("display_name")
            Place(o.optString("name").ifBlank { label.substringBefore(',') }, label, o.getString("lat").toDouble(), o.getString("lon").toDouble())
        } }
        if (near == null) list else list.sortedBy { com.fitcompanion.core.Geo.haversineMeters(near.first, near.second, it.lat, it.lon) }
    }

    /** The suburb/town and city for a rough position (rounded to ~10 km before sending), e.g. "Spring Hill, Brisbane". */
    suspend fun areaName(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=10&lat=%.1f&lon=%.1f".format(java.util.Locale.ROOT, lat, lon)
        get(url)?.let { runCatching { JSONObject(it).optString("display_name").split(",").map { s -> s.trim() }.take(3).joinToString(", ") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
    }

    /** Walking route with street names. Null if the router can't be reached or finds no path. */
    suspend fun route(a: Place, b: Place): Route? = withContext(Dispatchers.IO) {
        val coords = "%.6f,%.6f;%.6f,%.6f".format(java.util.Locale.ROOT, a.lon, a.lat, b.lon, b.lat)
        val body = get("https://routing.openstreetmap.de/routed-foot/route/v1/driving/$coords?overview=full&geometries=geojson&steps=true") ?: return@withContext null
        runCatching {
            val o = JSONObject(body)
            if (o.optString("code") != "Ok") return@withContext null
            val r = o.getJSONArray("routes").getJSONObject(0)
            val g = r.getJSONObject("geometry").getJSONArray("coordinates")
            val pts = (0 until g.length()).map { k -> g.getJSONArray(k).let { it.getDouble(1) to it.getDouble(0) } }
            val streets = mutableListOf<TripStreet>()
            val legs = r.getJSONArray("legs")
            for (l in 0 until legs.length()) {
                val steps = legs.getJSONObject(l).getJSONArray("steps")
                for (s in 0 until steps.length()) {
                    val st = steps.getJSONObject(s)
                    val name = st.optString("name"); val m = st.optDouble("distance", 0.0)
                    // Merge consecutive steps on the same street.
                    if (streets.isNotEmpty() && streets.last().name == name) streets[streets.lastIndex] = TripStreet(name, streets.last().meters + m)
                    else if (m > 0) streets += TripStreet(name, m)
                }
            }
            Route(pts, r.getDouble("distance"), streets)
        }.getOrNull()
    }

    /** As timed track points (times are placeholders), for the terrain lookup. */
    fun asTrack(points: List<Pair<Double, Double>>) =
        points.mapIndexed { k, p -> TrackPoint(Instant.ofEpochSecond(k.toLong()), p.first, p.second) }
}
