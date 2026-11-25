package com.fitcompanion.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres between two WGS84 coordinates. */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /**
     * Cumulative distance for each point. Uses the source's own distance when every point has one
     * (more accurate: the device may have used step data), otherwise sums GPS segments.
     * Points without a position repeat the previous cumulative value.
     */
    fun cumulativeDistances(points: List<TrackPoint>): List<Double> {
        if (points.isNotEmpty() && points.all { it.distanceMeters != null }) {
            return points.map { it.distanceMeters!! }
        }
        val out = ArrayList<Double>(points.size)
        var total = 0.0
        var last: TrackPoint? = null
        for (p in points) {
            if (p.hasPosition) {
                val prev = last
                if (prev != null) {
                    total += haversineMeters(prev.latitude!!, prev.longitude!!, p.latitude!!, p.longitude!!)
                }
                last = p
            }
            out.add(total)
        }
        return out
    }
}
