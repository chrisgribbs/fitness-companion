package com.fitcompanion.core

import java.time.Instant
import kotlin.random.Random

/** Synthetic test tracks. Clearly fake: they start at 0°,0° in the ocean, one point per second. */
object TestData {
    val T0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    /** Points 1 m apart along the equator with the given altitudes. */
    fun track(altitudes: List<Double?>, metersPerPoint: Double = 1.0, hr: Int? = null): List<TrackPoint> =
        altitudes.mapIndexed { i, alt ->
            TrackPoint(
                time = T0.plusSeconds(i.toLong()),
                latitude = 0.0,
                longitude = i * metersPerPoint / 111_320.0,
                altitudeMeters = alt,
                distanceMeters = i * metersPerPoint,
                heartRateBpm = hr,
            )
        }

    fun noise(n: Int, amplitude: Double, seed: Int = 42): List<Double> {
        val r = Random(seed)
        return List(n) { (r.nextDouble() * 2 - 1) * amplitude }
    }
}
