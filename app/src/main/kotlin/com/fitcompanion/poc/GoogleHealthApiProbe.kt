package com.fitcompanion.poc

import com.fitcompanion.core.ElevationCalculator
import com.fitcompanion.core.Splits
import com.fitcompanion.core.TcxParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId

/**
 * Calls the Google Health API (REST, v4) with a user access token and reports what came back.
 * Field names are read defensively: where the response shape differs from the docs, the report
 * lists the keys actually present so the difference is visible rather than hidden.
 */
class GoogleHealthApiProbe(private val accessToken: String, private val grantedScopes: List<String>) {

    private val base = "https://health.googleapis.com/v4/users/me"
    private val src = Source.GOOGLE_HEALTH_API

    class ApiError(val code: Int, val body: String) : Exception("HTTP $code: ${body.take(300)}")

    suspend fun run(): List<ProbeResult> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ProbeResult>()
        val missing = GoogleAuth.SCOPES.filterNot { it in grantedScopes }
            .map { it.removePrefix("https://www.googleapis.com/auth/googlehealth.") }
        if (missing.isNotEmpty()) {
            out += ProbeResult(src, "Recent run + summary", ProbeStatus.SUPPORTED_NOT_VERIFIED,
                "Scopes not granted: ${missing.joinToString()}. Items needing them will fail.")
        }

        out += safely("Paired devices") { probeDevices() }
        val exerciseResults = safely("Recent run + summary") { probeExercise() }
        out += exerciseResults
        out += safely("Daily steps + walking distance") { probeSteps() }
        out += safely("Sleep") { probeLatest("sleep", "Sleep", pageSize = 5) }
        out += safely("Resting heart rate") { probeLatest("daily-resting-heart-rate", "Resting heart rate", pageSize = 30) }
        out += safely("HRV") {
            probeLatest("daily-heart-rate-variability", "HRV", pageSize = 30) +
                probeLatest("heart-rate-variability", "HRV", pageSize = 5, label = "intraday samples")
        }
        out += safely("Historical data") { probeHistory() }
        out += ProbeResult(src, "Background access", ProbeStatus.SUPPORTED_NOT_VERIFIED,
            "The API itself works from anywhere (it's a web API). For a phone-only app, background sync needs a valid token; " +
                "with an unverified 'Testing' Google Cloud project, consent may need renewing about weekly — watch for this over the next 7+ days.")
        out
    }

    // ---------------------------------------------------------------------------------------

    private fun probeDevices(): List<ProbeResult> {
        val json = get("$base/pairedDevices")
        val devices = json.optJSONArray("pairedDevices") ?: JSONArray()
        val names = (0 until devices.length()).map { i ->
            val d = devices.getJSONObject(i)
            d.optString("displayName").ifBlank { d.optString("deviceModel").ifBlank { d.keys().asSequence().joinToString("/") } }
        }
        return listOf(ProbeResult(src, "Paired devices",
            if (names.isNotEmpty()) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE,
            if (names.isEmpty()) "No paired devices returned. Top-level keys: ${json.keys().asSequence().toList()}"
            else "Devices: ${names.joinToString()}"))
    }

    private fun probeExercise(): List<ProbeResult> {
        val out = mutableListOf<ProbeResult>()
        val json = get("$base/dataTypes/exercise/dataPoints?pageSize=25")
        val points = json.optJSONArray("dataPoints") ?: JSONArray()
        val all = (0 until points.length()).map { points.getJSONObject(it) }
        val ex = all.map { it to (it.optJSONObject("exercise") ?: JSONObject()) }
        val runsOrWalks = ex.filter { (_, e) -> e.optString("exerciseType") in setOf("RUNNING", "WALKING") }
        val target = runsOrWalks.firstOrNull { (_, e) -> e.optJSONObject("exerciseMetadata")?.optBoolean("hasGps") == true }
            ?: runsOrWalks.firstOrNull()

        if (target == null) {
            out += ProbeResult(src, "Recent run + summary", ProbeStatus.NOT_AVAILABLE,
                "${all.size} recent exercises returned, none RUNNING/WALKING. Types seen: ${ex.map { it.second.optString("exerciseType") }.distinct()}")
            return out
        }
        val (dp, e) = target
        val name = dp.getString("name")
        val summary = e.optJSONObject("metricsSummary") ?: JSONObject()
        val interval = e.optJSONObject("interval") ?: JSONObject()
        val hasGps = e.optJSONObject("exerciseMetadata")?.optBoolean("hasGps")

        out += ProbeResult(src, "Recent run + summary", ProbeStatus.AVAILABLE_TESTED,
            "${all.size} recent exercises (${runsOrWalks.size} runs/walks). Tested: ${e.optString("exerciseType")} " +
                "starting ${interval.optString("startTime")}, hasGps=$hasGps.\nSummary fields present: ${summary.keys().asSequence().toList()}")

        val distMm = summary.optDoubleOrNull("distanceMillimeters")
        val activeDur = e.optString("activeDuration")
        val pace = summary.optDoubleOrNull("averagePaceSecondsPerMeter")
        out += ProbeResult(src, "Distance, duration, pace",
            if (distMm != null) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE,
            "distance=${distMm?.let { "%.2f km".format(it / 1e6) } ?: "missing"}, activeDuration=${activeDur.ifBlank { "missing" }}, " +
                "averagePace=${pace?.let { Splits.formatPace(it * 1000) + " /km" } ?: "missing"} (measured by source)")

        val avgHr = summary.optDoubleOrNull("averageHeartRateBeatsPerMinute")
        out += probeHeartRateWindow(interval.optString("startTime"), interval.optString("endTime"), avgHr)

        val splits = e.optJSONArray("splitSummaries")
        out += ProbeResult(src, "Splits / laps",
            if (splits != null && splits.length() > 0) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE,
            if (splits != null && splits.length() > 0) "${splits.length()} split summaries returned (type ${splits.getJSONObject(0).optString("splitType")})."
            else "No splitSummaries on this exercise. The app can still compute km splits from the TCX track.")

        val gainMm = summary.optDoubleOrNull("elevationGainMillimeters")
        out += probeTcx(name, hasGps, gainMm)
        return out
    }

    private fun probeTcx(name: String, hasGps: Boolean?, summaryGainMm: Double?): List<ProbeResult> {
        val summaryGain = summaryGainMm?.let { "%.0f m".format(it / 1000) } ?: "not provided"
        val bytes = try {
            getBytes("https://health.googleapis.com/v4/$name:exportExerciseTcx?alt=media")
        } catch (e: ApiError) {
            return listOf(
                ProbeResult(src, "TCX export (route + elevation)", ProbeStatus.ERROR, "Export failed: ${e.message}. hasGps=$hasGps. (Needs location scope.)"),
                ProbeResult(src, "Elevation / altitude", if (summaryGainMm != null) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE,
                    "Summary elevation gain: $summaryGain. No TCX, so no profile."),
            )
        }
        val tcx = TcxParser.parse(ByteArrayInputStream(bytes))
        val elev = ElevationCalculator.calculateGps(tcx.points)
        val splits = Splits.compute(tcx.points)
        return listOf(
            ProbeResult(src, "TCX export (route + elevation)",
                if (tcx.pointsWithPosition > 0) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE,
                "TCX: ${bytes.size / 1024} KB, sport=${tcx.sport}, ${tcx.lapCount} lap(s), ${tcx.points.size} trackpoints; " +
                    "${tcx.pointsWithPosition} with position, ${tcx.pointsWithAltitude} with altitude, ${tcx.pointsWithHeartRate} with heart rate."),
            ProbeResult(src, "GPS route coordinates",
                if (tcx.pointsWithPosition > 0) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE,
                "${tcx.pointsWithPosition} positioned points via TCX (coordinates not shown in this report)."),
            ProbeResult(src, "Elevation / altitude",
                if (tcx.pointsWithAltitude > 0 || summaryGainMm != null) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE,
                "Google's summary elevation gain: $summaryGain (measured by source). " +
                    (elev.ascentMeters?.let {
                        "App estimate from TCX altitude: ascent ${"%.0f".format(it)} m, descent ${"%.0f".format(elev.descentMeters)} m, " +
                            "altitude range ${"%.0f".format(elev.minAltitudeMeters)}–${"%.0f".format(elev.maxAltitudeMeters)} m (${elev.method})."
                    } ?: "No per-point altitude in TCX.")),
            ProbeResult(src, "Splits / laps", if (splits.isNotEmpty()) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE,
                "App-computed from TCX: ${splits.size} splits; " + splits.take(3).joinToString { "km${it.index} ${Splits.formatPace(it.paceSecondsPerKm)}" }),
        )
    }

    private fun probeHeartRateWindow(start: String, end: String, summaryAvg: Double?): ProbeResult {
        if (start.isBlank() || end.isBlank()) return ProbeResult(src, "Heart rate during activity", ProbeStatus.NOT_AVAILABLE, "Exercise has no interval.")
        val filter = "heart_rate.sample_time.physical_time >= \"$start\" AND heart_rate.sample_time.physical_time < \"$end\""
        val json = get("$base/dataTypes/heart-rate/dataPoints?pageSize=10000&filter=${enc(filter)}")
        val n = json.optJSONArray("dataPoints")?.length() ?: 0
        return ProbeResult(src, "Heart rate during activity",
            if (n > 0) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE,
            "Summary average HR: ${summaryAvg?.let { "%.0f bpm".format(it) } ?: "missing"}; $n heart-rate samples in the exercise window" +
                (if (json.optString("nextPageToken").isNotBlank()) " (more pages available)" else "") + ".")
    }

    private fun probeSteps(): List<ProbeResult> {
        val today = LocalDate.now(ZoneId.systemDefault())
        val start = today.minusDays(6)
        fun body(pageSize: Int) = JSONObject()
            .put("range", JSONObject().put("start", civil(start)).put("end", civil(today.plusDays(1))))
            .put("windowSizeDays", 1).put("pageSize", pageSize)
        val steps = post("$base/dataTypes/steps/dataPoints:dailyRollUp", body(7))
        val dist = post("$base/dataTypes/distance/dataPoints:dailyRollUp", body(7))
        val sRows = steps.optJSONArray("rollupDataPoints")?.length() ?: 0
        val dRows = dist.optJSONArray("rollupDataPoints")?.length() ?: 0
        val lastSteps = steps.optJSONArray("rollupDataPoints")?.let { if (it.length() > 0) it.getJSONObject(0).optJSONObject("steps")?.toString() else null }
        return listOf(ProbeResult(src, "Daily steps + walking distance",
            if (sRows > 0) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE,
            "Last 7 days: $sRows daily step rows, $dRows daily distance rows. First row steps value: ${lastSteps ?: "none"}. " +
                "Distance is all-day; walking-only distance comes from walk exercises."))
    }

    private fun probeLatest(type: String, item: String, pageSize: Int, label: String = "records"): List<ProbeResult> {
        val json = get("$base/dataTypes/$type/dataPoints?pageSize=$pageSize")
        val pts = json.optJSONArray("dataPoints") ?: JSONArray()
        if (pts.length() == 0) return listOf(ProbeResult(src, item, ProbeStatus.NOT_AVAILABLE, "$type: no $label returned."))
        val first = pts.getJSONObject(0)
        val payloadKey = first.keys().asSequence().firstOrNull { it !in setOf("name", "dataSource") }
        val payload = payloadKey?.let { first.optJSONObject(it) }
        return listOf(ProbeResult(src, item, ProbeStatus.AVAILABLE_TESTED,
            "$type: ${pts.length()} $label returned (page). Latest: ${payload?.toString()?.take(220) ?: first.toString().take(220)}"))
    }

    /** Is there exercise or steps data older than 1, 2 and 5 years? (Results are newest-first.) */
    private fun probeHistory(): List<ProbeResult> {
        val today = LocalDate.now()
        val found = listOf(1L, 2L, 5L).map { years ->
            val cutoff = today.minusYears(years).toString()
            val f = "exercise.interval.civil_start_time < \"$cutoff\""
            val json = get("$base/dataTypes/exercise/dataPoints?pageSize=1&filter=${enc(f)}")
            val p = json.optJSONArray("dataPoints")
            val start = if (p != null && p.length() > 0) p.getJSONObject(0).optJSONObject("exercise")?.optJSONObject("interval")?.optString("startTime") else null
            "older than ${years}y: ${start ?: "none"}"
        }
        val any = found.any { !it.endsWith("none") }
        return listOf(ProbeResult(src, "Historical data", if (any) ProbeStatus.AVAILABLE_TESTED else ProbeStatus.NOT_AVAILABLE,
            "Most recent exercise found " + found.joinToString("; ") +
                ". (None may simply mean you have no older exercises.) Queries are limited to 90-day windows per request for most types, 14 days for heart rate."))
    }

    // ---- HTTP helpers --------------------------------------------------------------------------

    private fun safely(item: String, block: () -> List<ProbeResult>): List<ProbeResult> = try {
        block()
    } catch (e: ApiError) {
        val hint = when (e.code) {
            401 -> " Access token expired or invalid — sign in again."
            403 -> " Permission denied — a scope may not be granted, or your account isn't a test user."
            400 -> " Request rejected — the API may expect different parameters than documented."
            else -> ""
        }
        listOf(ProbeResult(src, item, ProbeStatus.ERROR, "${e.message}$hint"))
    } catch (e: java.io.IOException) {
        listOf(ProbeResult(src, item, ProbeStatus.ERROR, "Network problem: ${e.message}. Check your connection and retry."))
    } catch (e: Exception) {
        listOf(ProbeResult(src, item, ProbeStatus.ERROR, "${e::class.simpleName}: ${e.message}"))
    }

    private fun get(url: String): JSONObject = JSONObject(String(request("GET", url, null)))
    private fun post(url: String, body: JSONObject): JSONObject = JSONObject(String(request("POST", url, body.toString())))
    private fun getBytes(url: String): ByteArray = request("GET", url, null)

    private fun request(method: String, url: String, body: String?): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("Authorization", "Bearer $accessToken")
            c.setRequestProperty("Accept-Language", "en-AU")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.use { String(it.readBytes()) } ?: ""
                throw ApiError(code, err)
            }
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun civil(d: LocalDate) = JSONObject()
        .put("date", JSONObject().put("year", d.year).put("month", d.monthValue).put("day", d.dayOfMonth))
        .put("time", JSONObject().put("hours", 0).put("minutes", 0).put("seconds", 0))

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optString(key).toDoubleOrNull() ?: optDouble(key).takeIf { !it.isNaN() } else null
}
