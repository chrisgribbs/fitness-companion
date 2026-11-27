package com.fitcompanion.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * What drove your heart rate on a run or walk: pace, hills, or neither (drift). Worked out in code from the
 * workout's heart-rate samples, its GPS route and ground heights along it; the coach only explains the result.
 *
 * Method:
 *  - The workout is cut into 1-minute pieces. Each gets speed (route), slope and climb (terrain heights), and
 *    heart rate taken 45 s later, because heart rate takes about 30–60 s to respond to a change in effort.
 *  - Two-factor regression of heart rate on speed and climbing rate shows which one heart rate followed, and how
 *    much of the variation they explain together (R²). Below R² 0.2 neither explains it well.
 *  - Uphill vs flat vs downhill minutes compared directly; the highest-heart-rate moments checked for a climb or a
 *    surge just before; cardiac drift = heart rate change at the same pace on the flat, first half vs second.
 */
object WorkoutAnalysis {
    const val LAG_MS = 45_000L
    const val PIECE_MS = 60_000L

    data class Input(
        val title: String,
        val startMs: Long,
        val endMs: Long,
        /** Timed GPS points (null/empty = no route on the phone). */
        val route: List<TrackPoint>?,
        /** Ground height at a distance along the route (null = unknown), and where it comes from. */
        val heightAt: ((Double) -> Double?)?,
        val heightSource: String?,
        /** Heart-rate samples (epoch ms, bpm) from the watch. */
        val hr: List<Pair<Long, Int>>,
        val restingHr: Int?,
        val maxHr: Int?,
    )

    data class Piece(val t0: Long, val meters: Double, val speedKmh: Double?, val climb: Double?, val grade: Double?, val hr: Double?)

    private fun mean(v: List<Double>) = if (v.isEmpty()) null else v.average()

    fun pearson(x: List<Double>, y: List<Double>): Double? {
        if (x.size < 5) return null
        val mx = x.average(); val my = y.average()
        val sxy = x.indices.sumOf { (x[it] - mx) * (y[it] - my) }
        val sxx = x.sumOf { (it - mx) * (it - mx) }; val syy = y.sumOf { (it - my) * (it - my) }
        return if (sxx == 0.0 || syy == 0.0) null else sxy / sqrt(sxx * syy)
    }

    /** y = a + b1·x1 + b2·x2. Returns standardised betas and R², or null if it can't be fitted. */
    fun regress(x1: List<Double>, x2: List<Double>, y: List<Double>): Triple<Double, Double, Double>? {
        val n = y.size
        if (n < 8) return null
        fun sd(v: List<Double>): Double { val m = v.average(); return sqrt(v.sumOf { (it - m) * (it - m) } / (n - 1)) }
        val s1 = sd(x1); val s2 = sd(x2); val sy = sd(y)
        if (s1 == 0.0 || s2 == 0.0 || sy == 0.0) return null
        val r1 = pearson(x1, y) ?: return null
        val r2 = pearson(x2, y) ?: return null
        val r12 = pearson(x1, x2) ?: 0.0
        val den = 1 - r12 * r12
        if (den < 0.05) return null // speed and climbing move together too much to separate
        val b1 = (r1 - r2 * r12) / den
        val b2 = (r2 - r1 * r12) / den
        val r2all = (b1 * r1 + b2 * r2).coerceIn(0.0, 1.0)
        return Triple(b1, b2, r2all)
    }

    private fun hrBetween(hr: List<Pair<Long, Int>>, a: Long, b: Long): Double? =
        mean(hr.filter { it.first in a until b }.map { it.second.toDouble() })

    private fun pace(kmh: Double) = if (kmh <= 0.3) "--" else Splits.formatPace(3600.0 / kmh) + "/km"

    fun pieces(i: Input): List<Piece> {
        val pts = i.route.orEmpty().filter { it.hasPosition }.sortedBy { it.time }
        val times = pts.map { it.time.toEpochMilli() }
        val cum = DoubleArray(pts.size)
        for (k in 1 until pts.size) cum[k] = cum[k - 1] + Geo.haversineMeters(pts[k - 1].latitude!!, pts[k - 1].longitude!!, pts[k].latitude!!, pts[k].longitude!!)
        fun distAt(t: Long): Double? {
            if (pts.size < 2 || t < times.first() || t > times.last()) return null
            val k = times.indexOfFirst { it >= t }.coerceAtLeast(1)
            val span = (times[k] - times[k - 1]).toDouble()
            return if (span <= 0) cum[k] else cum[k - 1] + (cum[k] - cum[k - 1]) * (t - times[k - 1]) / span
        }
        val out = mutableListOf<Piece>()
        var t = i.startMs
        while (t + PIECE_MS / 2 <= i.endMs) {
            val e = minOf(t + PIECE_MS, i.endMs)
            val d0 = distAt(t); val d1 = distAt(e)
            val meters = if (d0 != null && d1 != null) d1 - d0 else 0.0
            val speed = if (d0 != null && d1 != null) meters / ((e - t) / 1000.0) * 3.6 else null
            val h0 = d0?.let { i.heightAt?.invoke(it) }; val h1 = d1?.let { i.heightAt?.invoke(it) }
            val climb = if (h0 != null && h1 != null) h1 - h0 else null
            val grade = if (climb != null && meters > 20) climb / meters * 100 else null
            out += Piece(t, meters, speed, climb, grade, hrBetween(i.hr, t + LAG_MS, e + LAG_MS) ?: hrBetween(i.hr, t, e))
            t = e
        }
        return out
    }

    fun report(i: Input): String = buildString {
        val mins = (i.endMs - i.startMs) / 60_000
        appendLine("### ${i.title} (${mins} min)")
        val hrIn = i.hr.filter { it.first in i.startMs..i.endMs }
        if (hrIn.size < 5) {
            appendLine("- Heart rate: not recorded for this workout (no samples in Health Connect), so what drove it can't be analysed.")
        } else {
            val bpm = hrIn.map { it.second }
            val gaps = hrIn.zipWithNext { a, b -> b.first - a.first }.sorted()
            val typicalGap = gaps.getOrNull(gaps.size / 2)?.div(1000) ?: 0
            appendLine("- Heart rate (measured by your watch, ${hrIn.size} readings, about every ${typicalGap}s): average ${bpm.average().toInt()}, max ${bpm.max()}, min ${bpm.min()} bpm")
            if (i.restingHr != null && i.maxHr != null) {
                val zones = HeartZones.zones(i.restingHr, i.maxHr)
                val secs = IntArray(6)
                for ((a, b) in hrIn.zipWithNext()) {
                    val dt = ((b.first - a.first) / 1000).toInt().coerceAtMost(120)
                    val z = zones.lastOrNull { a.second >= it.low }?.number ?: 0
                    secs[z] += dt
                }
                val total = secs.sum().coerceAtLeast(1)
                appendLine("- Time in zones (heart-rate reserve from resting ${i.restingHr} and max ${i.maxHr}): below Z1 ${secs[0] * 100 / total}%, " +
                    (1..5).joinToString(", ") { "Z$it ${zones[it - 1].name} ${secs[it] * 100 / total}%" })
            }
        }
        val ps = pieces(i)
        val withRoute = ps.filter { it.speedKmh != null }
        if (withRoute.isEmpty()) {
            appendLine("- Pace and slope: no GPS route on the phone for this workout (open it in the app and tap Show route on map), so heart rate can't be matched to pace or hills.")
            return@buildString
        }
        val hasHeights = withRoute.any { it.grade != null }
        if (!hasHeights) appendLine("- Slope: no ground heights for this route yet (terrain map off or not looked up), so hills can't be separated from pace.")
        else appendLine("- Slope from ${i.heightSource ?: "terrain heights"}; heart rate matched to effort 45 s earlier (its usual response time).")

        // Per-km splits.
        appendLine("- Splits (pace · climb · avg heart rate):")
        var kmStart = 0; var acc = 0.0; var km = 1
        for (k in withRoute.indices) {
            acc += withRoute[k].meters
            val last = k == withRoute.lastIndex
            if (acc >= 1000 || (last && acc > 200)) {
                val seg = withRoute.subList(kmStart, k + 1)
                val minutes = seg.size.toDouble()
                val speed = acc / 1000 / (minutes / 60)
                val climb = seg.mapNotNull { it.climb }.filter { it > 0 }.sum()
                val hr = mean(seg.mapNotNull { it.hr })
                appendLine("  - km $km${if (acc < 1000) " (last %.2f km)".format(acc / 1000) else ""}: ${pace(speed)}" +
                    (if (hasHeights) ", climb %.0f m".format(climb) else "") + (hr?.let { ", %.0f bpm".format(it) } ?: ""))
                km++; kmStart = k + 1; acc = 0.0
            }
        }

        val usable = withRoute.filter { it.hr != null && it.meters > 20 }
        if (usable.size < 5) { appendLine("- Too short or too few heart-rate readings to say what drove heart rate."); return@buildString }
        val avgSpeed = usable.map { it.speedKmh!! }.average()

        // Uphill vs flat vs downhill.
        if (hasHeights) {
            fun group(name: String, f: (Double) -> Boolean) {
                val g = usable.filter { it.grade != null && f(it.grade) }
                if (g.size >= 2) appendLine("  - $name (${g.size} min): %.0f bpm at %s, slope %+.1f%%".format(g.mapNotNull { it.hr }.average(), pace(g.map { it.speedKmh!! }.average()), g.map { it.grade!! }.average()))
            }
            appendLine("- Uphill vs flat vs downhill minutes:")
            group("Uphill (≥2%)") { it >= 2 }; group("Flat (±1%)") { abs(it) <= 1 }; group("Downhill (≤-2%)") { it <= -2 }
        }

        // Which one heart rate followed.
        val speeds = usable.map { it.speedKmh!! }; val hrs = usable.map { it.hr!! }
        val climbRate = usable.map { max(0.0, it.climb ?: 0.0) } // metres climbed per minute
        val rSpeed = pearson(speeds, hrs)
        val rClimb = if (hasHeights) pearson(climbRate, hrs) else null
        val fit = if (hasHeights) regress(speeds, climbRate, hrs) else null
        appendLine("- What heart rate followed (correlation with heart rate 45 s later; 1 = moves exactly together): pace/speed r=%s".format(rSpeed?.let { "%.2f".format(it) } ?: "n/a") +
            (rClimb?.let { ", climbing r=%.2f".format(it) } ?: ""))
        if (fit != null) {
            val (bSpeed, bClimb, r2) = fit
            val sum = abs(bSpeed) + abs(bClimb)
            if (r2 < 0.2) appendLine("  - Verdict: neither pace nor hills explains much of the heart-rate change (R² %.2f). More likely drift, heat, stops, or sensor noise.".format(r2))
            else {
                val climbShare = if (sum > 0) abs(bClimb) / sum * 100 else 0.0
                val lead = when { climbShare >= 60 -> "HILLS"; climbShare <= 40 -> "PACE"; else -> "BOTH ABOUT EQUALLY" }
                appendLine("  - Verdict: heart rate was driven mainly by $lead (hills %.0f%% / pace %.0f%% of the explained part; together they explain %.0f%% of the heart-rate variation, R² %.2f).".format(climbShare, 100 - climbShare, r2 * 100, r2))
            }
        } else if (hasHeights) appendLine("  - Can't separate pace from hills on this workout (too short, or pace and climbing changed together).")

        // Highest heart-rate moments and what came just before.
        val peaks = usable.withIndex().sortedByDescending { it.value.hr }.fold(mutableListOf<IndexedValue<Piece>>()) { acc2, p ->
            if (acc2.size < 3 && acc2.none { abs(it.index - p.index) < 3 }) acc2 += p; acc2 }
        val avgHr = hrs.average()
        if (peaks.isNotEmpty()) {
            appendLine("- Highest heart-rate moments:")
            peaks.sortedBy { it.index }.forEach { (idx, p) ->
                val minute = (p.t0 - i.startMs) / 60_000
                val before = usable.subList(max(0, idx - 2), idx + 1)
                val climbed = before.mapNotNull { it.climb }.sum()
                val speedVs = (before.map { it.speedKmh!! }.average() / avgSpeed - 1) * 100
                val cause = buildList {
                    if (hasHeights && climbed >= 4) add("climbing %.0f m in the previous 2–3 min".format(climbed))
                    if (speedVs >= 8) add("going %.0f%% faster than your average".format(speedVs))
                }.ifEmpty { listOf("no climb or speed-up just before (likely accumulated effort or drift)") }
                appendLine("  - Minute $minute: %.0f bpm (%+.0f vs average) — %s".format(p.hr, p.hr!! - avgHr, cause.joinToString(" and ")))
            }
        }

        // Cardiac drift: same pace on the flat, first half vs second half.
        val medSpeed = speeds.sorted()[speeds.size / 2]
        val steady = usable.filter { abs(it.speedKmh!! / medSpeed - 1) <= 0.07 && (it.grade == null || abs(it.grade) < 2) }
        val half = i.startMs + (i.endMs - i.startMs) / 2
        val first = steady.filter { it.t0 < half }.mapNotNull { it.hr }; val second = steady.filter { it.t0 >= half }.mapNotNull { it.hr }
        if (first.size >= 3 && second.size >= 3) {
            val d = second.average() - first.average()
            appendLine("- Drift at the same pace on the flat: %+.0f bpm second half vs first".format(d) + when {
                d >= 5 -> " (noticeable cardiac drift: common with heat, dehydration or fatigue)"
                d <= -3 -> " (heart rate settled as you warmed up)"
                else -> " (steady; little drift)"
            })
        }
    }
}
