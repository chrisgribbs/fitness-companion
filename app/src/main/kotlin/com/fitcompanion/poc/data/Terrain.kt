package com.fitcompanion.poc.data

import android.content.Context
import com.fitcompanion.core.ElevationResult
import com.fitcompanion.core.TerrainClimb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Accurate climb from terrain maps rather than phone GPS altitude.
 * 1) Queensland Government elevation service: best available bare-ground model (LiDAR, mostly 1 m in SE Qld).
 * 2) Elsewhere, or where Queensland has no data: Open-Meteo (Copernicus 90 m model).
 * Only map coordinates of points along the route are sent (no times, name, heart rate or other health data),
 * and only if you've turned this on. Results are kept on the phone so each route is looked up once.
 */
class Terrain(private val context: Context) {
    private val prefs = context.getSharedPreferences("terrain", Context.MODE_PRIVATE)
    private val dir get() = File(context.filesDir, "terrain").apply { mkdirs() }
    private fun file(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    /** null = not asked yet. */
    var enabled: Boolean?
        get() = if (prefs.contains("on")) prefs.getBoolean("on", false) else null
        set(v) { if (v == null) prefs.edit().remove("on").apply() else prefs.edit().putBoolean("on", v).apply() }

    fun cached(id: String): ElevationResult? = runCatching {
        if (!file(id).exists()) return null
        val o = JSONObject(file(id).readText())
        val h = o.getJSONArray("h"); val lat = o.getJSONArray("lat"); val lon = o.getJSONArray("lon"); val d = o.getJSONArray("d")
        val samples = (0 until d.length()).map { TerrainClimb.Sample(d.getDouble(it), lat.getDouble(it), lon.getDouble(it)) }
        TerrainClimb.calculate(samples, (0 until h.length()).map { if (h.isNull(it)) null else h.getDouble(it) }, o.getString("src"))
    }.getOrNull()

    fun deleteAll() { dir.listFiles()?.forEach { it.delete() } }

    /** Looks up ground heights for the route (once) and returns the climb. */
    suspend fun climb(id: String, route: RouteInfo): ElevationResult? = withContext(Dispatchers.IO) {
        cached(id)?.let { return@withContext it }
        val samples = TerrainClimb.resample(route.points)
        if (samples.size < 5) return@withContext null
        val heights = arrayOfNulls<Double>(samples.size)
        var qld = 0
        // Queensland LiDAR first (batches of 200 points).
        if (samples.any { inQueensland(it.latitude, it.longitude) }) samples.indices.chunked(200).forEach { idx ->
            runCatching { queensland(idx.map { samples[it] }) }.getOrNull()?.forEachIndexed { k, v -> if (v != null) { heights[idx[k]] = v; qld++ } }
        }
        // Fill anything missing from Open-Meteo (Copernicus 90 m), batches of 100.
        val missing = samples.indices.filter { heights[it] == null }
        var meteo = 0
        if (missing.isNotEmpty()) missing.chunked(100).forEach { idx ->
            runCatching { openMeteo(idx.map { samples[it] }) }.getOrNull()?.forEachIndexed { k, v -> if (v != null) { heights[idx[k]] = v; meteo++ } }
        }
        if (qld + meteo == 0) return@withContext null
        val src = when {
            meteo == 0 -> "Terrain: Queensland Government LiDAR ground model"
            qld == 0 -> "Terrain: Copernicus 90 m model via Open-Meteo"
            else -> "Terrain: Queensland LiDAR + Copernicus 90 m (Open-Meteo)"
        }
        file(id).writeText(JSONObject().put("src", src)
            .put("d", JSONArray(samples.map { it.distanceMeters })).put("lat", JSONArray(samples.map { it.latitude }))
            .put("lon", JSONArray(samples.map { it.longitude }))
            .put("h", JSONArray().apply { heights.forEach { put(it ?: JSONObject.NULL) } }).toString())
        TerrainClimb.calculate(samples, heights.toList(), src)
    }

    private fun inQueensland(lat: Double, lon: Double) = lat in -29.2..-9.0 && lon in 137.9..154.0

    private fun queensland(pts: List<TerrainClimb.Sample>): List<Double?> {
        val geometry = JSONObject().put("points", JSONArray(pts.map { JSONArray().put(round(it.longitude)).put(round(it.latitude)) }))
            .put("spatialReference", JSONObject().put("wkid", 4326))
        val body = listOf("geometry" to geometry.toString(), "geometryType" to "esriGeometryMultipoint",
            "returnFirstValueOnly" to "true", "interpolation" to "RSP_BilinearInterpolation", "f" to "json")
            .joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val c = URL(QLD).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setRequestProperty("User-Agent", "FitnessCompanion/1.0 (personal fitness app)")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) return List(pts.size) { null }
            val samples = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("samples") ?: return List(pts.size) { null }
            val out = MutableList<Double?>(pts.size) { null }
            for (i in 0 until samples.length()) {
                val s = samples.getJSONObject(i)
                val id = s.optInt("locationId", -1)
                val v = s.optString("value").toDoubleOrNull()
                if (id in out.indices && v != null && v > -500) out[id] = v
            }
            return out
        } finally { c.disconnect() }
    }

    private fun openMeteo(pts: List<TerrainClimb.Sample>): List<Double?> {
        val url = "https://api.open-meteo.com/v1/elevation?latitude=" + pts.joinToString(",") { round(it.latitude).toString() } +
            "&longitude=" + pts.joinToString(",") { round(it.longitude).toString() }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            if (c.responseCode != 200) return List(pts.size) { null }
            val e = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("elevation")
            return (0 until pts.size).map { i -> if (i < e.length() && !e.isNull(i)) e.getDouble(i).takeIf { it > -500 } else null }
        } finally { c.disconnect() }
    }

    private fun round(x: Double) = Math.round(x * 1e6) / 1e6

    companion object {
        const val QLD = "https://spatial-img.information.qld.gov.au/arcgis/rest/services/Elevation/QldDem/ImageServer/getSamples"
    }
}
