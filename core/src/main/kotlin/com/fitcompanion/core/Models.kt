package com.fitcompanion.core

import java.time.Instant

/**
 * How trustworthy a displayed number is. Every value the app shows carries one of these,
 * so measured Fitbit/Google Health values are never confused with app-calculated estimates.
 */
enum class DataQuality {
    /** Recorded by the device or Google Health and passed through unchanged. */
    MEASURED,

    /** Calculated by this app (e.g. smoothed ascent from GPS altitude). Always labelled "estimate". */
    ESTIMATED,

    /** Not available from any source. Shown as "—", never as zero. */
    MISSING,
}

/** One point of a recorded route. Any field except [time] may be absent. */
data class TrackPoint(
    val time: Instant,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    /** Cumulative distance from the start, if the source provides it (TCX does). */
    val distanceMeters: Double? = null,
    val heartRateBpm: Int? = null,
) {
    val hasPosition: Boolean get() = latitude != null && longitude != null
}
