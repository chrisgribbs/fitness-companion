package com.fitcompanion.core

data class RouteProblem(val message: String, val blocksSaving: Boolean)

/**
 * Catches routes that can't be right before they're saved: a jump between two stops far bigger than the rest of
 * the route (a waypoint in the wrong country), or a route far longer than the AI said it would be.
 */
object RouteSanity {
    fun check(points: List<Waypoint>, aiEstimateMeters: Double? = null): List<RouteProblem> {
        val named = points.filter { it.name.isNotBlank() }.ifEmpty { points }
        if (named.size < 2) return emptyList()
        val legs = named.zipWithNext { a, b -> Triple(a, b, Geo.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)) }
        val sorted = legs.map { it.third }.sorted()
        val median = sorted[sorted.size / 2]
        val problems = mutableListOf<RouteProblem>()
        for ((a, b, m) in legs) {
            val impossible = m > 1_000_000 && m > 5 * median          // e.g. 16,000 km between two coastal towns
            val odd = m > 50_000 && m > 4 * median
            if (impossible || odd) problems += RouteProblem(
                "%s → %s is a %s jump in a straight line, far longer than the other stops (typical %s). One of them is probably in the wrong place."
                    .format(a.name.ifBlank { "a stop" }, b.name.ifBlank { "the next stop" }, km(m), km(median)), blocksSaving = impossible)
        }
        val total = points.zipWithNext { a, b -> Geo.haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude) }.sum()
        if (aiEstimateMeters != null && aiEstimateMeters > 0 && total > 3 * aiEstimateMeters && total - aiEstimateMeters > 20_000)
            problems += RouteProblem("The route measures %s but should be about %s.".format(km(total), km(aiEstimateMeters)), blocksSaving = total > 10 * aiEstimateMeters)
        return problems
    }

    private fun km(m: Double) = if (m >= 10_000) "%,.0f km".format(m / 1000) else "%.1f km".format(m / 1000)
}
