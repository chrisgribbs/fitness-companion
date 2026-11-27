package com.fitcompanion.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/**
 * Counts repetitions from one up-and-down signal (bigger = higher up). It learns your own range from the movement
 * itself (no calibration step): a rep is going down past 40% of your recent range and coming back above 75%.
 * [minRange] stops small wobbles counting; [minRepMs] stops one rep counting twice.
 */
class RepCounter(
    private val minRange: Double,
    private val minRepMs: Long = 450,
    private val windowMs: Long = 8_000,
    /** 0–1: how much each new sample moves the smoothed value (1 = no smoothing, for on/off sensors). */
    private val smoothing: Double = 0.5,
) {
    data class Rep(val number: Int, val atMs: Long, val durationMs: Long, val depth: Double)

    private val samples = ArrayDeque<Pair<Long, Double>>()
    private var smooth: Double? = null
    private var down = false
    private var downAt = 0L
    private var bottom = 0.0
    private var topBefore = 0.0
    private var lastRepAt = -1L
    val reps = mutableListOf<Rep>()

    /** 0 = bottom of your range, 1 = top; for the on-screen gauge. */
    var position = 1.0; private set
    val isDown get() = down

    fun feed(tMs: Long, raw: Double): Rep? {
        val v = smooth?.let { it + smoothing * (raw - it) } ?: raw
        smooth = v
        samples.addLast(tMs to v)
        while (samples.size > 1 && tMs - samples.first().first > windowMs) samples.removeFirst()
        val hi = samples.maxOf { it.second }
        val lo = samples.minOf { it.second }
        val range = hi - lo
        position = if (range > 0) ((v - lo) / range).coerceIn(0.0, 1.0) else 1.0
        if (range < minRange) return null
        if (!down) {
            if (v < lo + 0.4 * range) { down = true; downAt = tMs; bottom = v; topBefore = hi }
            return null
        }
        if (v < bottom) bottom = v
        if (v > lo + 0.75 * range) {
            down = false
            if (lastRepAt >= 0 && tMs - lastRepAt < minRepMs) return null
            val rep = Rep(reps.size + 1, tMs, tMs - (if (lastRepAt >= 0) lastRepAt else downAt), (topBefore - bottom).coerceAtLeast(0.0))
            reps += rep
            lastRepAt = tMs
            return rep
        }
        return null
    }
}

enum class PushupMethod(val label: String) { CAMERA_FRONT("camera, front-on"), CAMERA_SIDE("camera, side-on"), FLOOR("phone under chest") }

/** Extra measurements at the bottom of a rep, when the camera can see them. */
data class RepForm(
    val depth: Double,
    val durationMs: Long,
    /** Side-on: smallest elbow angle in the rep (90° or less = full depth). */
    val elbowDegrees: Double? = null,
    /** Side-on: shoulder–hip–ankle angle at the bottom (about 180° = straight body). */
    val bodyLineDegrees: Double? = null,
    /** Front-on: how much lower one shoulder was than the other, as a fraction of shoulder width. */
    val tilt: Double? = null,
)

data class PushupSet(
    val start: Instant,
    val reps: Int,
    val seconds: Int,
    val method: PushupMethod,
    /** Plain-language notes worked out in code from the measurements; never invented. */
    val notes: List<String> = emptyList(),
)

object PushupAnalysis {
    /** Form notes for a set. Only says what the measurements show; floor-sensor sets get timing notes only. */
    fun notes(reps: List<RepForm>, method: PushupMethod): List<String> {
        if (reps.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val secs = reps.map { it.durationMs / 1000.0 }
        out += "Average %.1f s per push-up.".format(secs.average())
        if (reps.size >= 6) {
            val third = reps.size / 3
            val early = secs.take(third).average(); val late = secs.takeLast(third).average()
            if (late > early * 1.4) {
                val from = secs.indices.drop(third).firstOrNull { secs[it] > early * 1.4 }?.plus(1)
                out += "You slowed down towards the end" + (from?.let { " (from about rep $it)" } ?: "") + ", a normal sign of fatigue."
            }
        }
        if (method == PushupMethod.FLOOR) return out

        val depths = reps.map { it.depth }
        val median = depths.sorted()[depths.size / 2]
        if (median > 0 && reps.size >= 3) {
            val shallow = depths.count { it < 0.7 * median }
            if (shallow > 0) out += "$shallow of ${reps.size} reps were noticeably shallower than your usual depth."
            if (reps.size >= 6) {
                val firstAvg = depths.take(3).average()
                val drop = (3 until depths.size).firstOrNull { i -> depths.subList(i - 2, i + 1).average() < 0.75 * firstAvg }
                if (drop != null) out += "Depth started dropping around rep ${drop - 1}."
            }
        }
        reps.mapNotNull { it.elbowDegrees }.takeIf { it.size >= 3 }?.let { e ->
            val avg = e.average()
            out += if (avg <= 95) "Elbows bent to about %.0f° at the bottom: full depth.".format(avg)
                   else "Elbows bent to about %.0f° at the bottom; around 90° is a full push-up.".format(avg)
        }
        reps.mapNotNull { it.bodyLineDegrees }.takeIf { it.size >= 3 }?.let { b ->
            val avg = b.average()
            if (avg < 160) out += "Your body wasn't straight at the bottom (about %.0f° at the hips; straight is about 180°). Squeeze your glutes and brace your core.".format(avg)
            else out += "Body stayed in a straight line."
        }
        reps.mapNotNull { it.tilt }.takeIf { it.size >= 3 }?.let { t ->
            if (t.map(::abs).average() > 0.15) out += "One shoulder tended to drop lower than the other."
        }
        return out
    }

    /** Totals for the coach and the history screen. */
    fun facts(sets: List<PushupSet>, today: LocalDate, zone: ZoneId): String = buildString {
        if (sets.isEmpty()) return@buildString
        fun day(s: PushupSet) = s.start.atZone(zone).toLocalDate()
        val week = sets.filter { !day(it).isBefore(today.minusDays(6)) }
        appendLine("## Push-ups (counted by the phone: ${sets.map { it.method.label }.distinct().joinToString(", ")})")
        appendLine("- Today: ${sets.filter { day(it) == today }.sumOf { it.reps }} · last 7 days: ${week.sumOf { it.reps }} in ${week.size} sets · best set ever: ${sets.maxOf { it.reps }}")
        sets.sortedByDescending { it.start }.take(5).forEach { s ->
            appendLine("- ${day(s)}: ${s.reps} reps in ${s.seconds}s (${s.method.label})" + if (s.notes.isNotEmpty()) " — " + s.notes.joinToString(" ") else "")
        }
    }
}
