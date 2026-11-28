package com.fitcompanion.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/** One street (or path) along a planned route, as the walking router names it. */
data class TripStreet(val name: String, val meters: Double)

data class TripInput(
    val fromName: String,
    val toName: String,
    /** Route points (lat, lon) in order. */
    val route: List<Pair<Double, Double>>,
    val streets: List<TripStreet>,
    /** Ground height at a distance along the route (one way), or null if unknown. */
    val heightAt: ((Double) -> Double?)?,
    val heightSource: String?,
    val run: Boolean,
    val roundTrip: Boolean,
    /** Your usual pace on the flat (seconds per km), and where it came from. */
    val flatPaceSecPerKm: Double,
    val paceSource: String,
)

data class SteepBit(val street: String, val climbM: Double, val meters: Double, val gradePct: Double)

data class TripEstimate(
    val meters: Double,
    val minutes: Double,
    val climbM: Double?,
    val descentM: Double?,
    val steps: Int,
    val steepest: List<SteepBit>,
    val avgPaceSecPerKm: Double,
    val effort: String,
)

/**
 * Time, climb, steps and effort for a planned walk or run, worked out in code.
 *  - Walking: Tobler's hiking function, speed = 6·e^(−3.5·|slope + 0.05|) km/h, scaled so its flat speed matches
 *    your own usual walking pace. Slows you uphill and on steep downhill, slightly faster on gentle downhill.
 *  - Running: grade-adjusted pace rule of thumb: about +3.3% time per 1% uphill, −1.8% per 1% downhill
 *    (no more than 15% faster). An estimate.
 *  - Steps: distance ÷ a typical stride (0.75 m walking, 1.1 m running), so an estimate.
 */
object TripEstimator {
    private const val STEP_M = 20.0
    private const val TOBLER_FLAT_KMH = 5.036 // 6·e^(−0.175)

    fun tobler(gradeFraction: Double) = 6.0 * exp(-3.5 * abs(gradeFraction + 0.05))

    fun estimate(i: TripInput): TripEstimate {
        val cum = DoubleArray(i.route.size)
        for (k in 1 until i.route.size) cum[k] = cum[k - 1] + Geo.haversineMeters(i.route[k - 1].first, i.route[k - 1].second, i.route[k].first, i.route[k].second)
        val oneWay = cum.lastOrNull() ?: 0.0
        val flatKmh = 3600.0 / i.flatPaceSecPerKm

        // Heights every 20 m (one way).
        val n = max(1, (oneWay / STEP_M).toInt())
        val hs = i.heightAt?.let { f -> (0..n).map { k -> f(oneWay * k / n) } }
        val segLen = oneWay / n

        fun legSeconds(reverse: Boolean): Double {
            var secs = 0.0
            for (k in 0 until n) {
                val g = if (hs == null || hs[k] == null || hs[k + 1] == null) 0.0
                        else (hs[k + 1]!! - hs[k]!!) / segLen * (if (reverse) -1 else 1)
                secs += if (i.run) {
                    val pct = g * 100
                    val factor = if (pct >= 0) 1 + 0.033 * pct else max(0.85, 1 + 0.018 * pct)
                    segLen / 1000 * i.flatPaceSecPerKm * factor
                } else {
                    val kmh = tobler(g) * flatKmh / TOBLER_FLAT_KMH
                    segLen / 1000 / kmh * 3600
                }
            }
            return secs
        }

        var climb: Double? = null; var descent: Double? = null
        if (hs != null && hs.count { it != null } > n / 2) {
            var up = 0.0; var down = 0.0
            for (k in 0 until n) { val a = hs[k]; val b = hs[k + 1]; if (a != null && b != null) { if (b > a) up += b - a else down += a - b } }
            climb = up; descent = down
        }
        val seconds = legSeconds(false) + if (i.roundTrip) legSeconds(true) else 0.0
        val meters = oneWay * if (i.roundTrip) 2 else 1
        val (c, d) = if (i.roundTrip && climb != null) (climb + descent!!) to (descent + climb) else climb to descent

        // Steepest streets (one way), by climb.
        val steep = mutableListOf<SteepBit>()
        if (i.heightAt != null) {
            var at = 0.0
            for (st in i.streets) {
                if (st.meters >= 60) {
                    var up = 0.0
                    val parts = max(1, (st.meters / STEP_M).toInt())
                    var prev = i.heightAt.invoke(at)
                    for (k in 1..parts) {
                        val h = i.heightAt.invoke(at + st.meters * k / parts)
                        if (prev != null && h != null && h > prev) up += h - prev
                        prev = h
                    }
                    val h0 = i.heightAt.invoke(at); val h1 = i.heightAt.invoke(at + st.meters)
                    val grade = if (h0 != null && h1 != null) (h1 - h0) / st.meters * 100 else 0.0
                    if (up >= 4) steep += SteepBit(st.name.ifBlank { "unnamed path" }, up, st.meters, grade)
                }
                at += st.meters
            }
        }
        val avgPace = if (meters > 0) seconds / (meters / 1000) else i.flatPaceSecPerKm
        val climbPerKm = (c ?: 0.0) / max(0.1, meters / 1000)
        val effort = when {
            i.run -> if (climbPerKm > 25) "hard (hilly run)" else if (climbPerKm > 10) "moderate to hard" else "moderate"
            climbPerKm > 30 -> "vigorous in places (steep climbs)"
            climbPerKm > 12 -> "moderate with some uphill effort"
            else -> "light to moderate"
        }
        return TripEstimate(meters, seconds / 60, c, d, (meters / if (i.run) 1.1 else 0.75).toInt(),
            steep.sortedByDescending { it.climbM }.take(3), avgPace, effort)
    }

    fun facts(i: TripInput, e: TripEstimate, challengeLines: List<String>): String = buildString {
        appendLine("## Trip plan (worked out by the app: route on OpenStreetMap, heights from ${i.heightSource ?: "unknown (no hills used)"})")
        appendLine("- ${if (i.run) "Run" else "Walk"} from ${i.fromName} to ${i.toName}" + (if (i.roundTrip) " and back" else " (one way)"))
        appendLine("- Distance %.2f km · estimated time %.0f min (avg %s) · steps about %,d (estimated from a typical stride)".format(
            e.meters / 1000, e.minutes, Splits.formatPace(e.avgPaceSecPerKm) + "/km", e.steps))
        appendLine("- Based on your usual flat pace of ${Splits.formatPace(i.flatPaceSecPerKm)}/km (${i.paceSource}), slowed for hills " +
            (if (i.run) "(grade-adjusted pace rule of thumb)" else "(Tobler's hiking function)"))
        if (e.climbM != null) appendLine("- Climb %.0f m up, %.0f m down · effort: %s".format(e.climbM, e.descentM ?: 0.0, e.effort))
        else appendLine("- Hills: unknown (terrain heights unavailable); effort: ${e.effort}")
        if (e.steepest.isNotEmpty()) appendLine("- Steepest bits (one way): " + e.steepest.joinToString("; ") { "${it.street}: +%.0f m over %.0f m (%+.1f%%)".format(it.climbM, it.meters, it.gradePct) })
        if (i.streets.isNotEmpty()) appendLine("- Via: " + i.streets.filter { it.meters >= 100 && it.name.isNotBlank() }.map { it.name }.distinct().take(8).joinToString(", "))
        challengeLines.forEach { appendLine("- $it") }
    }
}
