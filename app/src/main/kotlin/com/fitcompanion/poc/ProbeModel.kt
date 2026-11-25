package com.fitcompanion.poc

/** The three outcomes the brief asks for, plus "not run yet" and a separate error state. */
enum class ProbeStatus(val label: String) {
    AVAILABLE_TESTED("Available and tested"),
    SUPPORTED_NOT_VERIFIED("Supported in theory, not yet verified with your account"),
    NOT_AVAILABLE("Not available (or only via another source)"),
    ERROR("Error while testing"),
    NOT_RUN("Not run yet"),
}

enum class Source(val label: String) { HEALTH_CONNECT("Health Connect"), GOOGLE_HEALTH_API("Google Health API") }

/**
 * One line of the report. [details] holds plain-English facts that were observed (counts, dates,
 * which app wrote the data). Raw GPS coordinates are deliberately never put in a result.
 */
data class ProbeResult(
    val source: Source,
    val item: String,
    val status: ProbeStatus,
    val details: String,
)

/** The items from the brief, in order, so every item appears in the report even if not run. */
val PROBE_ITEMS = listOf(
    "Recent run + summary",
    "GPS route coordinates",
    "Elevation / altitude",
    "Distance, duration, pace",
    "Heart rate during activity",
    "Daily steps + walking distance",
    "Sleep",
    "Resting heart rate",
    "HRV",
    "Historical data",
    "Background access",
    "TCX export (route + elevation)",
    "Splits / laps",
    "Paired devices",
)

object Report {
    fun format(results: List<ProbeResult>, generatedAt: String): String = buildString {
        appendLine("Fitness PoC report — $generatedAt")
        appendLine("(No GPS coordinates are included. Review before sharing.)")
        for (src in Source.entries) {
            appendLine()
            appendLine("== ${src.label} ==")
            for (item in PROBE_ITEMS) {
                val rows = results.filter { it.source == src && it.item == item }
                if (rows.isEmpty()) continue
                for (r in rows) {
                    appendLine("• ${r.item}: ${r.status.label}")
                    if (r.details.isNotBlank()) appendLine("    ${r.details.replace("\n", "\n    ")}")
                }
            }
        }
    }
}
