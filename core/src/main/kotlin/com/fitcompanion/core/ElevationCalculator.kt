package com.fitcompanion.core

import kotlin.math.max
import kotlin.math.min

/**
 * Settings for turning noisy recorded altitude into an ascent estimate.
 *
 * @param thresholdMeters a change in direction must exceed this before it counts. Phone GPS altitude
 *   commonly wanders by a few metres while standing still, so 3 m is a sensible default; use ~1 m if
 *   the source turns out to be barometric (much smoother).
 * @param medianWindow odd window size for the spike-removal median filter (1 disables it).
 */
data class ElevationOptions(
    val thresholdMeters: Double = 3.0,
    val medianWindow: Int = 5,
) {
    init {
        require(thresholdMeters >= 0) { "thresholdMeters must be >= 0" }
        require(medianWindow >= 1 && medianWindow % 2 == 1) { "medianWindow must be odd and >= 1" }
    }
}

data class ProfilePoint(val distanceMeters: Double, val altitudeMeters: Double)

data class ElevationResult(
    /** Total metres climbed (null when no altitude data). Always an estimate. */
    val ascentMeters: Double?,
    val descentMeters: Double?,
    /** Finish altitude minus start altitude — shown for comparison, NOT used as ascent. */
    val netChangeMeters: Double?,
    val minAltitudeMeters: Double?,
    val maxAltitudeMeters: Double?,
    val pointsWithAltitude: Int,
    val pointsMissingAltitude: Int,
    /** Smoothed profile for charting (distance vs altitude). The raw points are never modified. */
    val profile: List<ProfilePoint>,
    val quality: DataQuality,
    val method: String,
)

/**
 * Calculates elevation gained from a recorded route.
 *
 * Method (documented so the label in the UI can be honest):
 *  1. Ignore points with no altitude (they are counted and reported, not invented).
 *  2. Median filter to remove single-point GPS spikes.
 *  3. Hysteresis "trend" counting: a climb only counts once altitude has risen more than the
 *     threshold above the last turning point, and it keeps counting until altitude has fallen more
 *     than the threshold below the peak. This ignores small wobbles but still counts long, gentle
 *     climbs in full (unlike a simple per-point threshold).
 */
object ElevationCalculator {

    /**
     * Climb from a phone's GPS altitude, which is far noisier than a barometer (often ±10–20 m second to second,
     * plus slow drift). Counting every wobble turned a 5 km walk into ~1,000 m of "climb". So here:
     * 1) average altitude over each 50 m of distance (not each second), 2) median over ~450 m to drop spikes,
     * 3) light moving average, 4) only count rises of 15 m or more (hysteresis).
     * Tested on simulated noisy walks: typically within ±60 m of the true climb. If the result is still
     * implausible (over 100 m of climb per km), it's reported as unavailable rather than shown.
     */
    fun calculateGps(points: List<TrackPoint>): ElevationResult {
        val method = "GPS altitude: 50 m steps + median(9) + hysteresis(15 m)"
        val pos = points.filter { it.hasPosition }
        val dist = Geo.cumulativeDistances(pos)
        val withAlt = pos.indices.filter { pos[it].altitudeMeters != null }
        val missing = pos.size - withAlt.size
        val bins = sortedMapOf<Int, MutableList<Double>>()
        withAlt.forEach { i -> bins.getOrPut((dist[i] / GPS_BIN_M).toInt()) { mutableListOf() } += pos[i].altitudeMeters!! }
        fun none(why: String) = ElevationResult(null, null, null, withAlt.minOfOrNull { pos[it].altitudeMeters!! },
            withAlt.maxOfOrNull { pos[it].altitudeMeters!! }, withAlt.size, missing, emptyList(), DataQuality.MISSING, "$method; $why")
        if (bins.size < 6) return none("route too short")
        val keys = bins.keys.toList()
        val means = keys.map { k -> bins[k]!!.average() }
        val med = medianFilter(means, 9)
        val smooth = med.indices.map { i -> med.subList(max(0, i - 2), min(med.size, i + 3)).average() }
        val (ascent, descent) = hysteresis(smooth, 15.0)
        val km = (dist.lastOrNull() ?: 0.0) / 1000
        if (km > 0 && ascent / km > 100) return none("GPS altitude too noisy to estimate climb")
        return ElevationResult(
            ascentMeters = ascent, descentMeters = descent, netChangeMeters = smooth.last() - smooth.first(),
            minAltitudeMeters = smooth.min(), maxAltitudeMeters = smooth.max(),
            pointsWithAltitude = withAlt.size, pointsMissingAltitude = missing,
            profile = keys.indices.map { ProfilePoint(keys[it] * GPS_BIN_M + GPS_BIN_M / 2, smooth[it]) },
            quality = DataQuality.ESTIMATED, method = method,
        )
    }

    private const val GPS_BIN_M = 50.0

    fun calculate(points: List<TrackPoint>, options: ElevationOptions = ElevationOptions()): ElevationResult {
        val distances = Geo.cumulativeDistances(points)
        val withAlt = points.indices.filter { points[it].altitudeMeters != null }
        val missing = points.size - withAlt.size
        val method = "median(${options.medianWindow}) + hysteresis(${options.thresholdMeters} m)"

        if (withAlt.size < 2) {
            return ElevationResult(
                ascentMeters = null, descentMeters = null, netChangeMeters = null,
                minAltitudeMeters = withAlt.firstOrNull()?.let { points[it].altitudeMeters },
                maxAltitudeMeters = withAlt.firstOrNull()?.let { points[it].altitudeMeters },
                pointsWithAltitude = withAlt.size, pointsMissingAltitude = missing,
                profile = emptyList(), quality = DataQuality.MISSING, method = method,
            )
        }

        val raw = withAlt.map { points[it].altitudeMeters!! }
        val smoothed = medianFilter(raw, options.medianWindow)
        val (ascent, descent) = hysteresis(smoothed, options.thresholdMeters)

        return ElevationResult(
            ascentMeters = ascent,
            descentMeters = descent,
            netChangeMeters = smoothed.last() - smoothed.first(),
            minAltitudeMeters = raw.min(),
            maxAltitudeMeters = raw.max(),
            pointsWithAltitude = withAlt.size,
            pointsMissingAltitude = missing,
            profile = withAlt.indices.map { i -> ProfilePoint(distances[withAlt[i]], smoothed[i]) },
            quality = DataQuality.ESTIMATED,
            method = method,
        )
    }

    internal fun medianFilter(values: List<Double>, window: Int): List<Double> {
        if (window <= 1 || values.size < 3) return values
        val half = window / 2
        return values.indices.map { i ->
            val from = max(0, i - half)
            val to = min(values.size - 1, i + half)
            val slice = values.subList(from, to + 1).sorted()
            val n = slice.size
            if (n % 2 == 1) slice[n / 2] else (slice[n / 2 - 1] + slice[n / 2]) / 2.0
        }
    }

    private enum class Trend { NONE, UP, DOWN }

    /** Returns (ascent, descent). */
    internal fun hysteresis(values: List<Double>, threshold: Double): Pair<Double, Double> {
        var ascent = 0.0
        var descent = 0.0
        var pivot = values.first()   // last confirmed turning point
        var extreme = pivot          // highest (UP) or lowest (DOWN) point of the current leg
        var trend = Trend.NONE

        for (a in values.drop(1)) {
            when (trend) {
                Trend.NONE -> when {
                    a - pivot > threshold -> { trend = Trend.UP; extreme = a }
                    pivot - a > threshold -> { trend = Trend.DOWN; extreme = a }
                }
                Trend.UP -> when {
                    a > extreme -> extreme = a
                    extreme - a > threshold -> {
                        ascent += extreme - pivot
                        pivot = extreme; trend = Trend.DOWN; extreme = a
                    }
                }
                Trend.DOWN -> when {
                    a < extreme -> extreme = a
                    a - extreme > threshold -> {
                        descent += pivot - extreme
                        pivot = extreme; trend = Trend.UP; extreme = a
                    }
                }
            }
        }
        when (trend) {
            Trend.UP -> ascent += extreme - pivot
            Trend.DOWN -> descent += pivot - extreme
            Trend.NONE -> Unit
        }
        return ascent to descent
    }
}
