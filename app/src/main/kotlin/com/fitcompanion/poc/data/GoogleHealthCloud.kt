package com.fitcompanion.poc.data

import com.fitcompanion.core.ActivityKind
import com.fitcompanion.core.ActivityRecord
import com.fitcompanion.core.DailyMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Reads your data straight from your Google Health account (the same data the Google Health app
 * shows), using a short-lived sign-in token held only in memory. Read-only.
 *
 * Response fields are read defensively: anything missing is left as "no data", never guessed.
 */
class GoogleHealthCloud(private val token: String) {

    class CloudError(val code: Int, message: String) : Exception(message)

    data class Result(
        val activities: List<ActivityRecord>,
        val daily: List<DailyMetric>,
        /** Per-section problems, e.g. "Sleep: HTTP 403". Empty when everything was read. */
        val problems: List<String>,
    )

    private val base = "https://health.googleapis.com/v4/users/me/dataTypes"

    suspend fun read(days: Long, zone: ZoneId): Result = withContext(Dispatchers.IO) {
        val problems = mutableListOf<String>()
        val since = LocalDate.now(zone).minusDays(days - 1)
        fun <T> section(name: String, empty: T, block: () -> T): T = try { block() } catch (e: CloudError) {
            problems += "$name: ${e.message}"; empty
        } catch (e: java.io.IOException) {
            problems += "$name: network problem (${e.message})"; empty
        } catch (e: Exception) {
            problems += "$name: ${e::class.simpleName} ${e.message}"; empty
        }

        val activities = section("Workouts", emptyList()) { exercises(since, zone) }
        val steps = section("Steps", emptyMap()) { rollup("steps", since, zone) }
        val rhr = section("Resting heart rate", emptyMap()) { dailyValues("daily-resting-heart-rate", since, "beatsPerMinute") }
        val hrv = section("HRV", emptyMap()) { dailyValues("daily-heart-rate-variability", since, "averageHeartRateVariabilityMilliseconds") }
        val sleep = section("Sleep", emptyMap()) { sleepMinutes(since, zone) }

        val today = LocalDate.now(zone)
        val daily = generateSequence(since) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.map { d ->
            DailyMetric(d, steps[d]?.toLong(), rhr[d]?.toInt(), hrv[d], sleep[d])
        }.toList()
        Result(activities, daily, problems)
    }

    // ---- Workouts ---------------------------------------------------------------------------------

    private fun exercises(since: LocalDate, zone: ZoneId): List<ActivityRecord> {
        val out = mutableListOf<ActivityRecord>()
        var pageToken: String? = null
        var pages = 0
        val sinceInstant = since.atStartOfDay(zone).toInstant()
        do {
            val json = get("$base/exercise/dataPoints?pageSize=25" + (pageToken?.let { "&pageToken=${enc(it)}" } ?: ""))
            val points = json.optJSONArray("dataPoints") ?: JSONArray()
            var reachedOlder = false
            for (i in 0 until points.length()) {
                val dp = points.getJSONObject(i)
                val e = dp.optJSONObject("exercise") ?: continue
                val interval = e.optJSONObject("interval") ?: continue
                val start = time(interval.optString("startTime")) ?: continue
                val end = time(interval.optString("endTime")) ?: start
                if (start.isBefore(sinceInstant)) { reachedOlder = true; continue }
                val m = e.optJSONObject("metricsSummary") ?: JSONObject()
                out += ActivityRecord(
                    id = "gh:" + dp.optString("name"),
                    kind = kindOf(e.optString("exerciseType")),
                    start = start, end = end,
                    distanceMeters = m.num("distanceMillimeters")?.div(1000),
                    ascentMeters = m.num("elevationGainMillimeters")?.div(1000),
                )
            }
            pageToken = json.optString("nextPageToken").ifBlank { null }
        } while (pageToken != null && !reachedOlder && ++pages < 20)
        return out
    }

    // ---- Daily values -------------------------------------------------------------------------------

    /** Daily totals via dailyRollUp. The value's field name is found rather than assumed. */
    private fun rollup(type: String, since: LocalDate, zone: ZoneId): Map<LocalDate, Double> {
        val today = LocalDate.now(zone)
        val start = maxOf(since, today.minusDays(89))
        val body = JSONObject()
            .put("range", JSONObject().put("start", civil(start)).put("end", civil(today.plusDays(1))))
            .put("windowSizeDays", 1)
            .put("pageSize", 90)
        val json = post("$base/$type/dataPoints:dailyRollUp", body)
        val rows = json.optJSONArray("rollupDataPoints") ?: return emptyMap()
        val out = mutableMapOf<LocalDate, Double>()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val date = rowDate(r) ?: continue
            val v = r.optJSONObject(type)?.let(::firstNumber) ?: continue
            out[date] = v
        }
        return out
    }

    /** Daily data types (one point per day) — e.g. resting heart rate. */
    private fun dailyValues(type: String, since: LocalDate, field: String): Map<LocalDate, Double> {
        val key = camel(type)
        val out = mutableMapOf<LocalDate, Double>()
        var pageToken: String? = null
        var pages = 0
        do {
            val json = get("$base/$type/dataPoints?pageSize=100" + (pageToken?.let { "&pageToken=${enc(it)}" } ?: ""))
            val points = json.optJSONArray("dataPoints") ?: JSONArray()
            var older = false
            for (i in 0 until points.length()) {
                val o = points.getJSONObject(i).optJSONObject(key) ?: continue
                val date = o.optJSONObject("date")?.let(::date) ?: continue
                if (date.isBefore(since)) { older = true; continue }
                o.num(field)?.let { out[date] = it }
            }
            pageToken = json.optString("nextPageToken").ifBlank { null }
        } while (pageToken != null && !older && ++pages < 10)
        return out
    }

    /** Minutes asleep, attributed to the morning you woke up. */
    private fun sleepMinutes(since: LocalDate, zone: ZoneId): Map<LocalDate, Int> {
        val out = mutableMapOf<LocalDate, Int>()
        var pageToken: String? = null
        var pages = 0
        do {
            val json = get("$base/sleep/dataPoints?pageSize=25" + (pageToken?.let { "&pageToken=${enc(it)}" } ?: ""))
            val points = json.optJSONArray("dataPoints") ?: JSONArray()
            var older = false
            for (i in 0 until points.length()) {
                val s = points.getJSONObject(i).optJSONObject("sleep") ?: continue
                val end = s.optJSONObject("interval")?.optString("endTime")?.let(::time) ?: continue
                val day = end.atZone(zone).toLocalDate()
                if (day.isBefore(since)) { older = true; continue }
                val mins = s.optJSONObject("summary")?.num("minutesAsleep")?.toInt() ?: continue
                out[day] = (out[day] ?: 0) + mins
            }
            pageToken = json.optString("nextPageToken").ifBlank { null }
        } while (pageToken != null && !older && ++pages < 10)
        return out
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    companion object {
        fun kindOf(type: String): ActivityKind = when (type.uppercase()) {
            "RUNNING", "TREADMILL", "TRAIL_RUNNING" -> ActivityKind.RUN
            "WALKING", "TREADMILL_WALK", "INCLINE_WALK", "POWER_WALKING", "NORDIC_WALKING", "HIKING" -> ActivityKind.WALK
            else -> ActivityKind.OTHER
        }
    }

    private fun camel(kebab: String) = kebab.split('-').mapIndexed { i, p -> if (i == 0) p else p.replaceFirstChar { it.uppercase() } }.joinToString("")

    private fun time(s: String): Instant? = if (s.isBlank()) null else
        runCatching { OffsetDateTime.parse(s).toInstant() }.getOrNull() ?: runCatching { Instant.parse(s) }.getOrNull()

    private fun date(o: JSONObject): LocalDate? = runCatching { LocalDate.of(o.getInt("year"), o.getInt("month"), o.getInt("day")) }.getOrNull()

    /** A rollup row's day: from its civil start time, or a date field, whichever is present. */
    private fun rowDate(r: JSONObject): LocalDate? {
        r.optJSONObject("civilStartTime")?.optJSONObject("date")?.let(::date)?.let { return it }
        r.optJSONObject("date")?.let(::date)?.let { return it }
        r.optJSONObject("interval")?.optJSONObject("civilStartTime")?.optJSONObject("date")?.let(::date)?.let { return it }
        return null
    }

    /** First numeric value in an object (rollup value field names vary by type). */
    private fun firstNumber(o: JSONObject): Double? {
        for (k in o.keys()) o.num(k)?.let { return it }
        return null
    }

    private fun JSONObject.num(k: String): Double? {
        if (!has(k) || isNull(k)) return null
        return when (val v = get(k)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            else -> null
        }
    }

    private fun civil(d: LocalDate) = JSONObject()
        .put("date", JSONObject().put("year", d.year).put("month", d.monthValue).put("day", d.dayOfMonth))
        .put("time", JSONObject().put("hours", 0).put("minutes", 0).put("seconds", 0))

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun get(url: String) = JSONObject(String(request("GET", url, null)))
    private fun post(url: String, body: JSONObject) = JSONObject(String(request("POST", url, body.toString())))

    private fun request(method: String, url: String, body: String?): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.use { String(it.readBytes()) }.orEmpty()
                val msg = runCatching { JSONObject(err).getJSONObject("error").getString("message") }.getOrNull() ?: err.take(150)
                throw CloudError(code, when (code) {
                    401 -> "sign-in expired"
                    403 -> "permission denied ($msg)"
                    else -> "HTTP $code $msg"
                })
            }
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
