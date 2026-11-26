package com.fitcompanion.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** One day's wellness numbers; null = not available that day. */
data class DailyMetric(
    val date: LocalDate,
    val steps: Long? = null,
    val restingHeartRate: Int? = null,
    val hrvMillis: Double? = null,
    val sleepMinutes: Int? = null,
)

/**
 * Builds the plain-text "facts sheet" the AI is allowed to see. Every total, average and comparison
 * is calculated here, in code. The AI only explains these numbers; it never receives raw GPS,
 * heart-rate streams or anything not listed on the sheet. The user sees this exact text before
 * anything is sent.
 */
object AnalysisFacts {

    fun build(
        activities: List<ActivityRecord>,
        daily: List<DailyMetric>,
        challenges: List<Pair<ChallengeDefinition, ChallengeProgress>>,
        today: LocalDate,
        zone: ZoneId,
    ): String = buildString {
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        fun dateOf(a: ActivityRecord) = a.start.atZone(zone).toLocalDate()
        fun inRange(a: ActivityRecord, from: LocalDate, toExcl: LocalDate) = dateOf(a).let { !it.isBefore(from) && it.isBefore(toExcl) }

        appendLine("FACTS SHEET (calculated by the app on ${today.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)} $today; weeks start Monday; today's figures are still being recorded)")
        if (activities.any { it.isDemo }) appendLine("NOTE: includes DEMO data, not real recordings.")
        appendLine()

        appendLine("## Weekly activity, last 4 weeks (newest first)")
        for (i in 0..3) {
            val s = weekStart.minusWeeks(i.toLong()); val e = s.plusWeeks(1)
            val label = if (i == 0) "this week so far" else "week of $s"
            for (kind in listOf(ActivityKind.RUN, ActivityKind.WALK)) {
                val list = activities.filter { it.kind == kind && inRange(it, s, e) }
                append("- $label, ${kind.name.lowercase()}s: ${list.size}")
                if (list.isNotEmpty()) {
                    append(", distance ${km(list.mapNotNull { it.distanceMeters }.sum())}")
                    val asc = list.mapNotNull { it.ascentMeters }
                    append(", climb ${if (asc.isEmpty()) "not available" else "%.0f m".format(asc.sum())}")
                    val missing = list.count { it.distanceMeters == null }
                    if (missing > 0) append(" ($missing without distance)")
                }
                appendLine()
            }
        }

        val last28 = activities.filter { inRange(it, today.minusDays(27), today.plusDays(1)) }
        val longestRun = last28.filter { it.kind == ActivityKind.RUN && it.distanceMeters != null }.maxByOrNull { it.distanceMeters!! }
        appendLine()
        appendLine("## Longest run in last 28 days")
        appendLine(longestRun?.let { "- ${km(it.distanceMeters!!)} on ${dateOf(it)}" } ?: "- no runs with distance recorded")

        appendLine()
        appendLine("## Daily wellness, last 14 days (date: steps / resting HR / HRV / sleep)")
        val byDate = daily.associateBy { it.date }
        for (i in 0 until 14) {
            val d = today.minusDays(i.toLong()); val m = byDate[d]
            appendLine("- $d: ${m?.steps ?: "—"} / ${m?.restingHeartRate?.let { "$it bpm" } ?: "—"} / " +
                "${m?.hrvMillis?.let { "%.0f ms".format(it) } ?: "—"} / ${m?.sleepMinutes?.let { "${it / 60}h ${it % 60}m" } ?: "—"}")
        }

        appendLine()
        appendLine("## Personal ranges (90 days vs last 7 days)")
        fun avg(values: List<Double>) = if (values.isEmpty()) null else values.average()
        val d90 = daily.filter { !it.date.isBefore(today.minusDays(89)) && !it.date.isAfter(today) }
        val d7 = daily.filter { !it.date.isBefore(today.minusDays(6)) && !it.date.isAfter(today) }
        fun line(label: String, unit: String, pick: (DailyMetric) -> Double?) {
            val a90 = avg(d90.mapNotNull(pick)); val a7 = avg(d7.mapNotNull(pick))
            val n90 = d90.count { pick(it) != null }
            val v90 = d90.mapNotNull(pick)
            val range = if (v90.size >= 10) " (90-day range %.0f–%.0f)".format(v90.min(), v90.max()) else ""
            appendLine("- $label: 90-day avg ${a90?.let { "%.1f $unit".format(it) } ?: "n/a"} from $n90 days$range; " +
                "7-day avg ${a7?.let { "%.1f $unit".format(it) } ?: "n/a"}")
        }
        line("Resting heart rate", "bpm") { it.restingHeartRate?.toDouble() }
        line("HRV (RMSSD)", "ms") { it.hrvMillis }
        line("Steps", "steps") { it.steps?.toDouble() }

        if (challenges.isNotEmpty()) {
            appendLine()
            appendLine("## Challenges")
            for ((c, p) in challenges) {
                val unit = if (c.metric == ChallengeMetric.ASCENT) "climb" else "distance"
                appendLine("- ${c.name}: ${km(p.totalMeters)} of ${km(p.goalMeters)} $unit (${"%.1f".format(p.percent)}%), " +
                    "this week ${km(p.thisWeekMeters)}" +
                    (p.next?.let { ", next milestone '${it.name}' in ${km(it.atMeters - p.totalMeters)}" } ?: ""))
            }
        }
    }

    private fun km(m: Double) = if (m < 10_000) "%.0f m".format(m) else "%.1f km".format(m / 1000)
}
