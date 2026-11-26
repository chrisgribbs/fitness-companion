package com.fitcompanion.core

import kotlin.math.max
import kotlin.math.min

/**
 * Climb from a terrain model (e.g. Queensland's 1 m LiDAR ground model) instead of the phone's GPS altitude.
 * The route is sampled every [SPACING_M] metres; each sample's ground height comes from the terrain service.
 */
object TerrainClimb {
    const val SPACING_M = 10.0
    /** Below this (metres above sea level) a sample is treated as water: you were on a bridge or ferry, not the river bed. */
    const val WATER_BELOW_M = 0.5

    data class Sample(val distanceMeters: Double, val latitude: Double, val longitude: Double)

    /** Points every [spacing] metres along the route (plus the end). */
    fun resample(points: List<TrackPoint>, spacing: Double = SPACING_M): List<Sample> {
        val pos = points.filter { it.hasPosition }
        if (pos.size < 2) return emptyList()
        val out = mutableListOf(Sample(0.0, pos[0].latitude!!, pos[0].longitude!!))
        var travelled = 0.0
        var next = spacing
        for (i in 1 until pos.size) {
            val a = pos[i - 1]; val b = pos[i]
            val seg = Geo.haversineMeters(a.latitude!!, a.longitude!!, b.latitude!!, b.longitude!!)
            while (seg > 0 && next <= travelled + seg) {
                val f = (next - travelled) / seg
                out += Sample(next, a.latitude + f * (b.latitude - a.latitude), a.longitude + f * (b.longitude - a.longitude))
                next += spacing
            }
            travelled += seg
        }
        if (travelled - out.last().distanceMeters > spacing / 2) out += Sample(travelled, pos.last().latitude!!, pos.last().longitude!!)
        return out
    }

    /**
     * Ascent/descent from terrain heights (null = no data). Water and gaps are bridged by straight lines between
     * the land either side; a 70 m median removes short bridges/underpasses and edge effects; rises under 2 m don't count.
     */
    fun calculate(samples: List<Sample>, heights: List<Double?>, source: String): ElevationResult {
        val method = "$source; route sampled every ${SPACING_M.toInt()} m, water bridged, median(7) + hysteresis(2 m)"
        val land = heights.map { h -> h?.takeIf { it >= WATER_BELOW_M } }
        val known = land.indices.filter { land[it] != null }
        if (known.size < max(5, samples.size / 3)) return ElevationResult(null, null, null, null, null, known.size, samples.size - known.size,
            emptyList(), DataQuality.MISSING, "$method; not enough terrain data for this route")
        // Fill gaps by linear interpolation between neighbouring land samples (ends: hold the nearest value).
        val filled = DoubleArray(land.size)
        for (i in land.indices) {
            land[i]?.let { filled[i] = it; continue }
            val prev = known.lastOrNull { it < i }; val next = known.firstOrNull { it > i }
            filled[i] = when {
                prev != null && next != null -> land[prev]!! + (land[next]!! - land[prev]!!) * (i - prev).toDouble() / (next - prev)
                prev != null -> land[prev]!!
                else -> land[next!!]!!
            }
        }
        val smooth = ElevationCalculator.medianFilter(filled.toList(), 7)
        val (ascent, descent) = ElevationCalculator.hysteresis(smooth, 2.0)
        return ElevationResult(
            ascentMeters = ascent, descentMeters = descent, netChangeMeters = smooth.last() - smooth.first(),
            minAltitudeMeters = smooth.min(), maxAltitudeMeters = smooth.max(),
            pointsWithAltitude = known.size, pointsMissingAltitude = samples.size - known.size,
            profile = samples.indices.map { ProfilePoint(samples[it].distanceMeters, smooth[it]) },
            quality = DataQuality.ESTIMATED, method = method,
        )
    }

}
