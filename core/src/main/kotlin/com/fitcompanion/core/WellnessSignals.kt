package com.fitcompanion.core

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

enum class SignalLevel { TOO_LITTLE_DATA, NORMAL, BETTER_THAN_USUAL, WORTH_WATCHING, NOTABLE }

data class WellnessSignal(val metric: String, val level: SignalLevel, val summary: String)

/**
 * Judges resting heart rate and HRV against the person's own normal range, in code, so the coach can't
 * over-read a one-beat change. The method follows the research:
 *  - Resting HR is very individual and mostly stable day to day; brief swings happen (Quer et al., PLOS ONE 2020,
 *    92,457 wearers). Wearables report whole beats, so 1–2 bpm is rounding and normal noise.
 *  - Illness studies flag a *sustained* rise above the person's own baseline, usually alongside sleep changes
 *    (Radin et al., Lancet Digital Health 2020).
 *  - HRV: compare the 7-day average of ln(RMSSD) with your normal range = baseline mean ± 0.5 SD (the smallest
 *    worthwhile change), not single days (Plews et al. 2012/2013; Australian Institute of Sport HRV guide).
 * The exact cut-offs below (3 bpm / 1 SD "normal", 5 bpm on 3+ days "notable") are this app's conservative
 * reading of that research, not a medical test.
 */
object WellnessSignals {

    private fun sd(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
    }

    private fun window(daily: List<DailyMetric>, from: LocalDate, to: LocalDate) =
        daily.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }

    fun restingHeartRate(daily: List<DailyMetric>, today: LocalDate): WellnessSignal {
        val name = "Resting heart rate"
        // Usual = the 4 weeks before the last 7 days, so a recent change doesn't hide itself in the baseline.
        val base = window(daily, today.minusDays(34), today.minusDays(7)).mapNotNull { it.restingHeartRate?.toDouble() }
        val recentDays = window(daily, today.minusDays(6), today).filter { it.restingHeartRate != null }
        if (base.size < 14 || recentDays.size < 3)
            return WellnessSignal(name, SignalLevel.TOO_LITTLE_DATA,
                "not enough days yet to judge (need 14 usual days and 3 recent; have ${base.size} and ${recentDays.size}). Don't draw conclusions.")
        val mean = base.average(); val s = sd(base)
        val band = max(3.0, s)
        val r7 = recentDays.map { it.restingHeartRate!!.toDouble() }.average()
        val last3 = recentDays.sortedByDescending { it.date }.take(3).map { it.restingHeartRate!!.toDouble() }.average()
        val highDays = recentDays.count { it.restingHeartRate!! >= mean + 5 }
        val delta = r7 - mean
        val head = "last 7 days avg %.1f bpm vs your usual %.1f bpm (normal range %.0f–%.0f, from %d days); change %+.1f bpm"
            .format(r7, mean, mean - band, mean + band, base.size, delta)
        val level = when {
            last3 - mean >= max(5.0, 2 * s) && highDays >= 3 -> SignalLevel.NOTABLE
            abs(delta) < band -> SignalLevel.NORMAL
            delta < 0 -> SignalLevel.BETTER_THAN_USUAL
            else -> SignalLevel.WORTH_WATCHING
        }
        val meaning = when (level) {
            SignalLevel.NORMAL -> "within your normal day-to-day variation. Wearables report whole beats, so 1–2 bpm differences are noise. Do NOT present this as a concern; at most say it's steady."
            SignalLevel.BETTER_THAN_USUAL -> "a little below your usual, which is common with good recovery or improving fitness. Not a concern."
            SignalLevel.WORTH_WATCHING -> "slightly above your normal range but not sustained. Mention gently only if relevant (e.g. with poor sleep or hard training); say one more week of data will tell."
            SignalLevel.NOTABLE -> "raised ≥5 bpm above your usual on $highDays of the last 7 days. A sustained rise like this can come with illness, poor sleep, stress, alcohol, heat or heavy training. Suggest an easier day and rest; suggest a GP if it persists or comes with symptoms."
            SignalLevel.TOO_LITTLE_DATA -> ""
        }
        return WellnessSignal(name, level, "$head. Verdict: $meaning")
    }

    fun hrv(daily: List<DailyMetric>, today: LocalDate): WellnessSignal {
        val name = "HRV (RMSSD)"
        val base = window(daily, today.minusDays(60), today.minusDays(7)).mapNotNull { it.hrvMillis?.takeIf { v -> v > 0 }?.let(::ln) }
        val recent = window(daily, today.minusDays(6), today).mapNotNull { it.hrvMillis?.takeIf { v -> v > 0 }?.let(::ln) }
        if (base.size < 14 || recent.size < 4)
            return WellnessSignal(name, SignalLevel.TOO_LITTLE_DATA,
                "not enough readings yet to judge (need 14 usual days and 4 of the last 7; have ${base.size} and ${recent.size}). Don't draw conclusions from single nights.")
        val m = base.average(); val swc = 0.5 * sd(base)
        val r = recent.average()
        val lo = exp(m - swc); val hi = exp(m + swc)
        val head = "7-day avg %.0f ms vs your usual %.0f ms (normal range %.0f–%.0f ms = usual ± smallest worthwhile change, from %d days)"
            .format(exp(r), exp(m), lo, hi, base.size)
        val level = when {
            r >= m - swc && r <= m + swc -> SignalLevel.NORMAL
            r > m + swc -> SignalLevel.BETTER_THAN_USUAL
            r < m - 2 * swc -> SignalLevel.NOTABLE
            else -> SignalLevel.WORTH_WATCHING
        }
        val meaning = when (level) {
            SignalLevel.NORMAL -> "within your normal range. Single nights going up or down are normal; do NOT present this as a dip or a concern."
            SignalLevel.BETTER_THAN_USUAL -> "above your usual range, generally a sign of good recovery. Not a concern."
            SignalLevel.WORTH_WATCHING -> "slightly below your normal range for the week. Can follow hard training, short sleep, stress or alcohol. Mention gently; not alarming on its own."
            SignalLevel.NOTABLE -> "clearly below your normal range for the week. Suggest easier training and more sleep until it returns to range; if it comes with a raised resting HR or symptoms, rest, and see a GP if it persists."
            SignalLevel.TOO_LITTLE_DATA -> ""
        }
        return WellnessSignal(name, level, "$head. Verdict: $meaning")
    }
}
