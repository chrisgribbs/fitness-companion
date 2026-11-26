package com.fitcompanion.core

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One reading from the standard Bluetooth Heart Rate Measurement characteristic (0x2A37). */
data class HrReading(val bpm: Int, val contact: Boolean?, val rrMs: List<Double>)

object HeartRateParser {
    /**
     * Parses the Bluetooth SIG Heart Rate Measurement format:
     * byte 0 flags (bit0 = 16-bit value, bits1-2 = sensor contact, bit3 = energy expended present, bit4 = RR present),
     * then the heart rate, optional energy (uint16), then RR intervals (uint16, units of 1/1024 s).
     */
    fun parse(bytes: ByteArray): HrReading? {
        if (bytes.size < 2) return null
        val flags = bytes[0].toInt() and 0xFF
        var i = 1
        val sixteen = flags and 0x01 != 0
        val bpm = if (sixteen) {
            if (bytes.size < 3) return null
            (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
        } else bytes[1].toInt() and 0xFF
        i += if (sixteen) 2 else 1
        val contactBits = (flags shr 1) and 0x03
        val contact = when (contactBits) { 3 -> true; 2 -> false; else -> null }
        if (flags and 0x08 != 0) i += 2
        val rr = mutableListOf<Double>()
        if (flags and 0x10 != 0) {
            while (i + 1 < bytes.size) {
                val v = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                rr += v * 1000.0 / 1024.0
                i += 2
            }
        }
        return HrReading(bpm, contact, rr)
    }
}

data class HrZone(val number: Int, val name: String, val low: Int, val high: Int, val feel: String)

/**
 * Five heart-rate zones using heart-rate reserve (Karvonen): resting + % × (max − resting).
 * Max heart rate is the user's own if they know it, otherwise estimated with Tanaka et al. (2001): 208 − 0.7 × age.
 */
object HeartZones {
    fun estimatedMax(age: Int) = (208 - 0.7 * age).roundToInt()

    fun zones(resting: Int, max: Int): List<HrZone> {
        val reserve = (max - resting).coerceAtLeast(20)
        fun at(p: Double) = (resting + p * reserve).roundToInt()
        return listOf(
            HrZone(1, "Recovery", at(0.50), at(0.60) - 1, "Very easy, could chat all day"),
            HrZone(2, "Easy", at(0.60), at(0.70) - 1, "Conversational: full sentences"),
            HrZone(3, "Steady", at(0.70), at(0.80) - 1, "Comfortably hard: a few words at a time"),
            HrZone(4, "Threshold", at(0.80), at(0.90) - 1, "Hard: a word or two"),
            HrZone(5, "Max", at(0.90), max, "All out, only for short bursts"),
        )
    }

    /** 0 = below zone 1. */
    fun zoneOf(bpm: Int, zones: List<HrZone>): Int = zones.lastOrNull { bpm >= it.low }?.number ?: 0
}

/** Summary of a live session, calculated in code. */
data class LiveSessionSummary(
    val startEpochMs: Long, val durationSec: Int, val avg: Int, val max: Int, val min: Int,
    val secondsInZone: List<Int>,      // index 0 = below zone 1, 1..5 = zones
    val rmssdMs: Double?, val targetZone: Int?, val secondsInTarget: Int,
)

object LiveStats {
    /** RMSSD from beat-to-beat intervals, ignoring implausible beats and big jumps (artefacts). Needs 20+ clean beats. */
    fun rmssd(rr: List<Double>): Double? {
        val clean = rr.filter { it in 300.0..2000.0 }
        val diffs = clean.zipWithNext { a, b -> b - a }.filter { kotlin.math.abs(it) < 0.2 * clean.average() }
        if (diffs.size < 20) return null
        return sqrt(diffs.sumOf { it * it } / diffs.size)
    }

    /** [samples] = one (epochMs, bpm) per reading; time between readings is credited to the earlier reading's zone. */
    fun summarise(samples: List<Pair<Long, Int>>, rr: List<Double>, zones: List<HrZone>, target: Int?): LiveSessionSummary? {
        if (samples.size < 2) return null
        val inZone = IntArray(6)
        var inTarget = 0
        for ((a, b) in samples.zipWithNext()) {
            val secs = ((b.first - a.first) / 1000).toInt().coerceIn(0, 10)   // gaps over 10 s (dropouts) aren't counted
            val z = HeartZones.zoneOf(a.second, zones)
            inZone[z] += secs
            if (target != null && z == target) inTarget += secs
        }
        val bpms = samples.map { it.second }
        return LiveSessionSummary(samples.first().first, ((samples.last().first - samples.first().first) / 1000).toInt(),
            bpms.average().roundToInt(), bpms.max(), bpms.min(), inZone.toList(), rmssd(rr), target, inTarget)
    }
}
