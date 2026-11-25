package com.fitcompanion.core

import java.time.Instant

enum class ActivityKind { RUN, WALK, OTHER }

/**
 * One recorded activity, already reduced to the numbers challenges need.
 * A null value means "not available" and is never treated as zero.
 */
data class ActivityRecord(
    val id: String,
    val kind: ActivityKind,
    val start: Instant,
    val end: Instant,
    val distanceMeters: Double?,
    val ascentMeters: Double?,
    /** MEASURED when Google Health supplied the ascent, ESTIMATED when the app calculated it. */
    val ascentQuality: DataQuality = if (ascentMeters == null) DataQuality.MISSING else DataQuality.MEASURED,
    /** True only for clearly labelled demo data. */
    val isDemo: Boolean = false,
)
