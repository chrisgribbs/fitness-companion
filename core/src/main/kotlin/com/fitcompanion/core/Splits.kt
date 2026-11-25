package com.fitcompanion.core

import java.time.Duration

data class Split(
    /** 1-based split number. */
    val index: Int,
    val distanceMeters: Double,
    val duration: Duration,
    /** Seconds per kilometre for this split. */
    val paceSecondsPerKm: Double,
    val averageHeartRateBpm: Int?,
    /** True for the final split when it is shorter than a full kilometre. */
    val partial: Boolean,
)

object Splits {

    /**
     * Kilometre (or [splitMeters]) splits from a recorded track, interpolating the moment each
     * boundary is crossed. Uses elapsed time between points, so pauses inside a split count unless
     * the source removed them — the app labels this "elapsed pace".
     */
    fun compute(points: List<TrackPoint>, splitMeters: Double = 1000.0, minPartialMeters: Double = 50.0): List<Split> {
        if (points.size < 2) return emptyList()
        val dist = Geo.cumulativeDistances(points)
        val total = dist.last()
        if (total <= 0.0) return emptyList()

        val splits = mutableListOf<Split>()
        var splitStartTime = points.first().time.toEpochMilli().toDouble()
        var splitStartDist = dist.first()
        var nextBoundary = splitStartDist + splitMeters
        val hrInSplit = mutableListOf<Int>()

        for (i in 1 until points.size) {
            points[i].heartRateBpm?.let { hrInSplit.add(it) }
            val d0 = dist[i - 1]; val d1 = dist[i]
            val t0 = points[i - 1].time.toEpochMilli().toDouble()
            val t1 = points[i].time.toEpochMilli().toDouble()
            while (d1 >= nextBoundary && d1 > d0) {
                val frac = (nextBoundary - d0) / (d1 - d0)
                val tCross = t0 + frac * (t1 - t0)
                splits += make(splits.size + 1, splitMeters, tCross - splitStartTime, hrInSplit, partial = false)
                hrInSplit.clear()
                splitStartTime = tCross
                splitStartDist = nextBoundary
                nextBoundary += splitMeters
            }
        }
        val remaining = total - splitStartDist
        if (remaining >= minPartialMeters) {
            val endTime = points.last().time.toEpochMilli().toDouble()
            splits += make(splits.size + 1, remaining, endTime - splitStartTime, hrInSplit, partial = true)
        }
        return splits
    }

    private fun make(index: Int, meters: Double, millis: Double, hr: List<Int>, partial: Boolean) = Split(
        index = index,
        distanceMeters = meters,
        duration = Duration.ofMillis(millis.toLong()),
        paceSecondsPerKm = (millis / 1000.0) / (meters / 1000.0),
        averageHeartRateBpm = if (hr.isEmpty()) null else hr.average().toInt(),
        partial = partial,
    )

    /** "5:32" style pace text, or "—" when unknown. */
    fun formatPace(secondsPerKm: Double?): String {
        if (secondsPerKm == null || secondsPerKm.isNaN() || secondsPerKm.isInfinite()) return "—"
        val s = Math.round(secondsPerKm)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
