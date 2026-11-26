package com.fitcompanion.poc.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import com.fitcompanion.core.ElevationCalculator
import com.fitcompanion.core.ElevationResult
import com.fitcompanion.core.Geo
import com.fitcompanion.core.Split
import com.fitcompanion.core.Splits
import com.fitcompanion.core.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** A GPS route plus what's worked out from it in code. */
data class RouteInfo(val points: List<TrackPoint>, val source: String) {
    val latLngs: List<Pair<Double, Double>> get() = points.filter { it.hasPosition }.map { it.latitude!! to it.longitude!! }
    val distanceMeters: Double by lazy { Geo.cumulativeDistances(points.filter { it.hasPosition }).lastOrNull() ?: 0.0 }
    val splits: List<Split> by lazy { runCatching { Splits.compute(points.filter { it.hasPosition }) }.getOrDefault(emptyList()) }
    /** Climb from noisy phone-GPS altitude, heavily smoothed; an estimate, or null if the altitude is too poor. */
    val elevation: ElevationResult by lazy { ElevationCalculator.calculateGps(points) }
}

sealed interface RouteLookup {
    data class Found(val route: RouteInfo) : RouteLookup
    /** Health Connect has a route but asks you to approve sharing it with this app (once per workout). */
    data object NeedsConsent : RouteLookup
    data object None : RouteLookup
    data class Unavailable(val why: String) : RouteLookup
}

/**
 * Routes recorded with your phone's GPS. Workout routes come from Health Connect (only after you open a workout and,
 * if Android asks, approve it); live-workout routes are recorded by this app. Both are kept only on this phone.
 */
class RouteStore(private val context: Context) {
    private val dir get() = File(context.filesDir, "routes").apply { mkdirs() }
    private fun file(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    fun cached(id: String): RouteInfo? = runCatching { if (file(id).exists()) decode(file(id).readText()) else null }.getOrNull()

    fun save(id: String, route: RouteInfo) = runCatching { file(id).writeText(encode(route)) }

    fun deleteAll() { dir.listFiles()?.forEach { it.delete() } }

    /** Reads the GPS route of a Health Connect workout. */
    suspend fun fromHealthConnect(sessionId: String): RouteLookup = withContext(Dispatchers.IO) {
        cached(sessionId)?.let { return@withContext RouteLookup.Found(it) }
        val client = runCatching { HealthConnectClient.getOrCreate(context) }.getOrElse { return@withContext RouteLookup.Unavailable("Health Connect isn't available.") }
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())
        if ("android.permission.health.READ_EXERCISE_ROUTES" !in granted)
            return@withContext RouteLookup.Unavailable("Allow \"Exercise routes\" for this app in Health Connect to see maps of your workouts.")
        val record = runCatching { client.readRecord(ExerciseSessionRecord::class, sessionId).record }.getOrElse {
            return@withContext RouteLookup.Unavailable("Couldn't read that workout from Health Connect.")
        }
        when (val r = record.exerciseRouteResult) {
            is ExerciseRouteResult.Data -> fromRoute(sessionId, r.exerciseRoute)?.let { RouteLookup.Found(it) } ?: RouteLookup.None
            is ExerciseRouteResult.ConsentRequired -> RouteLookup.NeedsConsent
            else -> RouteLookup.None
        }
    }

    /** After you approve a route in Android's prompt. */
    fun fromRoute(sessionId: String, route: ExerciseRoute): RouteInfo? {
        val pts = route.route.map {
            TrackPoint(time = it.time, latitude = it.latitude, longitude = it.longitude, altitudeMeters = it.altitude?.inMeters)
        }
        if (pts.size < 2) return null
        return RouteInfo(pts, "Recorded with your phone's GPS (via Health Connect)").also { save(sessionId, it) }
    }

    private fun encode(r: RouteInfo): String = JSONObject().put("source", r.source).put("points", JSONArray().apply {
        r.points.forEach { p ->
            put(JSONArray().put(p.time.toEpochMilli()).put(p.latitude ?: JSONObject.NULL).put(p.longitude ?: JSONObject.NULL)
                .put(p.altitudeMeters ?: JSONObject.NULL).put(p.heartRateBpm ?: JSONObject.NULL))
        }
    }).toString()

    private fun decode(s: String): RouteInfo {
        val o = JSONObject(s); val a = o.getJSONArray("points")
        fun d(x: JSONArray, i: Int) = if (x.isNull(i)) null else x.getDouble(i)
        return RouteInfo((0 until a.length()).map { a.getJSONArray(it) }.map { p ->
            TrackPoint(time = Instant.ofEpochMilli(p.getLong(0)), latitude = d(p, 1), longitude = d(p, 2), altitudeMeters = d(p, 3),
                heartRateBpm = if (p.length() > 4 && !p.isNull(4)) p.getInt(4) else null)
        }, o.optString("source"))
    }
}
