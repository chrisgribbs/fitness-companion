package com.fitcompanion.poc.ai

import android.content.Context
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

    suspend fun check(name: String, aiLat: Double, aiLon: Double, onProgress: (String) -> Unit = {}): WaypointCheck {
        val found = lookup(name, onProgress)
        return WaypointCheck.of(name, aiLat, aiLon, found?.first, found?.second)
    }

    private suspend fun lookup(name: String, onProgress: (String) -> Unit): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val key = name.trim().lowercase()
        cache.getString(key, null)?.let { cached ->
            if (cached == "none") return@withContext null
            val (la, lo) = cached.split(',').map { it.toDouble() }
            return@withContext la to lo
        }
        onProgress("Looking up \"$name\"…")
        delay(1100) // stay under 1 request per second
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" + URLEncoder.encode(name, "UTF-8")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("User-Agent", userAgent)
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            if (c.responseCode != 200) return@withContext null // not cached: may be temporary
            val arr = JSONArray(c.inputStream.use { String(it.readBytes()) })
            val result = if (arr.length() == 0) null else arr.getJSONObject(0).let { o: JSONObject ->
                o.getString("lat").toDouble() to o.getString("lon").toDouble()
            }
            cache.edit().putString(key, result?.let { "${it.first},${it.second}" } ?: "none").apply()
            result
        } catch (_: java.io.IOException) {
            null
        } finally {
            c.disconnect()
        }
    }
}
