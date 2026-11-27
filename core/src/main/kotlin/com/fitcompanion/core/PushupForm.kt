package com.fitcompanion.core

import kotlin.math.abs

enum class CheckStatus { GOOD, WORK_ON, NOT_MEASURED }

/** One form standard, what it means, how the phone measures it and where it comes from. */
data class FormStandard(
    val id: String,
    val title: String,
    val good: String,
    val howMeasured: String,
    /** Which camera view can measure it. */
    val views: Set<PushupMethod>,
    val cue: String,
    val source: String,
)

/** The result of one standard for one set. */
data class FormCheck(val id: String, val status: CheckStatus, val result: String, val passReps: Int = 0, val measuredReps: Int = 0) {
    val standard get() = PushupStandards.byId(id)
}

/**
 * What good push-up form looks like, as specific measurable checks. Drawn from fitness-test standards
 * (US Air Force, RAF, FITNESSGRAM 90° push-up, ACE / Canadian Society for Exercise Physiology) and trainer guidance
 * (NASM, ACE). Pass marks allow ~10° for camera estimates. A check is GOOD when at least 80% of measured reps pass.
 */
object PushupStandards {
    private val SIDE = setOf(PushupMethod.CAMERA_SIDE)
    private val FRONT = setOf(PushupMethod.CAMERA_FRONT)
    private val CAMERA = setOf(PushupMethod.CAMERA_SIDE, PushupMethod.CAMERA_FRONT)
    private val ALL = PushupMethod.entries.toSet()

    const val DEPTH_ELBOW_PASS = 100.0     // standard 90°, +10° for camera error
    const val LOCKOUT_PASS = 160.0         // straight arms at the top
    const val BODY_LINE_PASS = 165.0       // shoulder–hip–ankle, straight = 180°
    const val NECK_PASS = 150.0            // ear–shoulder–hip, neutral ≈ 170–180°
    const val MIN_REP_SECONDS = 1.0        // faster than this is bouncing / momentum
    const val FRONT_DEPTH_OF_BEST = 0.8    // front-on depth vs your deepest rep
    const val HAND_WIDTH_MIN = 0.9         // wrists apart ÷ shoulders apart
    const val HAND_WIDTH_MAX = 1.7
    const val ELBOW_FLARE_MAX = 1.9        // elbows apart ÷ shoulders apart at the bottom
    const val LEVEL_MAX_TILT = 0.12        // one shoulder lower, ÷ shoulder width

    val all = listOf(
        FormStandard("depth", "Depth: elbows to 90°",
            "Lower until your elbows bend to 90° and your upper arms are parallel to the floor (chest about a fist from the floor).",
            "Side-on: the smallest elbow angle in each rep (pass ≤ ${DEPTH_ELBOW_PASS.toInt()}°, allowing ~10° for the camera). " +
                "Front-on: how far your shoulders drop, compared with your deepest rep of the set (pass ≥ ${(FRONT_DEPTH_OF_BEST * 100).toInt()}%).",
            CAMERA, "Go down until your upper arms are parallel to the floor. Fewer full reps beat more half reps.",
            "US Air Force push-up standard; RAF press-up standard; FITNESSGRAM 90° push-up"),
        FormStandard("lockout", "Full extension at the top",
            "Push all the way up until your arms are straight each rep.",
            "Side-on: the straightest your elbow gets between reps (pass ≥ ${LOCKOUT_PASS.toInt()}°).",
            SIDE, "Finish every rep with straight arms before going down again.",
            "ACE push-up assessment (CSEP protocol); US Air Force standard"),
        FormStandard("bodyline", "Straight body: no sagging or piking",
            "Keep a straight line from head to heels the whole way: hips neither sagging towards the floor nor hiked up.",
            "Side-on: the shoulder–hip–ankle angle in every frame of the rep (pass if it never drops below ${BODY_LINE_PASS.toInt()}°; 180° is perfectly straight). The app also tells sagging from piking.",
            SIDE, "Squeeze your glutes and brace your stomach as if about to be poked; think ribs down.",
            "NASM push-up guide; FITNESSGRAM; ACE"),
        FormStandard("neck", "Neutral neck",
            "Keep your head in line with your spine, looking at the floor slightly ahead; lead with your chest, not your chin.",
            "Side-on: the ear–shoulder–hip angle (pass ≥ ${NECK_PASS.toInt()}°).",
            SIDE, "Long neck: look at the floor just in front of your hands and lower your chest, not your head.",
            "NASM; Human Kinetics proper push-up form"),
        FormStandard("tempo", "Controlled tempo",
            "Lower and push with control, without bouncing or jerky momentum. Test pace is about 1 rep every 3 seconds.",
            "Time between reps (pass ≥ ${MIN_REP_SECONDS} s).",
            ALL, "Take about 2 seconds down and 1 second up.",
            "NASM common mistakes; FITNESSGRAM cadence"),
        FormStandard("hands", "Hands about shoulder-width",
            "Hands under your shoulders, about shoulder-width apart or slightly wider.",
            "Front-on: wrist spacing ÷ shoulder spacing (pass ${HAND_WIDTH_MIN}–${HAND_WIDTH_MAX}).",
            FRONT, "Put your hands just outside your shoulders, wrists under them.",
            "FITNESSGRAM; ACE"),
        FormStandard("elbows", "Elbows angled back, not flared",
            "Elbows travel back at about 45° to your body (an arrow shape), not straight out to the sides (a T shape).",
            "Front-on: elbow spacing ÷ shoulder spacing at the bottom (pass ≤ $ELBOW_FLARE_MAX). This is the app's estimate; the camera can't see the exact angle.",
            FRONT, "Think 'arrow, not T': tuck your elbows back towards your ribs.",
            "NASM; University of Calgary SHRed push-up cues"),
        FormStandard("level", "Level shoulders",
            "Both sides lower and rise together, without one shoulder dropping.",
            "Front-on: height difference between shoulders at the bottom ÷ shoulder width (pass ≤ $LEVEL_MAX_TILT).",
            FRONT, "Spread weight evenly through both hands.",
            "General technique guidance (symmetry)"),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** Checks the camera can't make from this view, so you know what to try next time. */
    fun notMeasuredFrom(method: PushupMethod) = all.filter { method !in it.views }
}

object PushupFormAssessor {
    private fun check(id: String, reps: List<RepForm>, pass: (RepForm) -> Boolean?, describe: (Int, Int) -> String): FormCheck {
        val measured = reps.mapNotNull { r -> pass(r) }
        if (measured.isEmpty()) return FormCheck(id, CheckStatus.NOT_MEASURED, "Not measured this set")
        val ok = measured.count { it }
        return FormCheck(id, if (ok >= 0.8 * measured.size) CheckStatus.GOOD else CheckStatus.WORK_ON, describe(ok, measured.size), ok, measured.size)
    }

    fun assess(reps: List<RepForm>, method: PushupMethod): List<FormCheck> {
        if (reps.isEmpty()) return emptyList()
        val out = mutableListOf<FormCheck>()
        val side = method == PushupMethod.CAMERA_SIDE
        val front = method == PushupMethod.CAMERA_FRONT
        if (side) {
            out += check("depth", reps, { r -> r.elbowDegrees?.let { it <= PushupStandards.DEPTH_ELBOW_PASS } }) { ok, n ->
                val avg = reps.mapNotNull { it.elbowDegrees }.average()
                "$ok of $n reps reached 90° (average lowest elbow angle %.0f°)".format(avg)
            }
            out += check("lockout", reps, { r -> r.lockoutDegrees?.let { it >= PushupStandards.LOCKOUT_PASS } }) { ok, n -> "$ok of $n reps finished with straight arms" }
            out += check("bodyline", reps, { r -> r.bodyLineDegrees?.let { it >= PushupStandards.BODY_LINE_PASS } }) { ok, n ->
                val bad = reps.filter { (it.bodyLineDegrees ?: 180.0) < PushupStandards.BODY_LINE_PASS }
                val sag = bad.count { it.hipsLow == true }; val pike = bad.count { it.hipsLow == false }
                "$ok of $n reps kept a straight body" + when {
                    sag > 0 && pike == 0 -> "; hips sagged on $sag"
                    pike > 0 && sag == 0 -> "; hips piked up on $pike"
                    sag > 0 -> "; hips sagged on $sag and piked on $pike"
                    else -> ""
                }
            }
            out += check("neck", reps, { r -> r.neckDegrees?.let { it >= PushupStandards.NECK_PASS } }) { ok, n -> "$ok of $n reps kept the head in line" }
        }
        if (front) {
            val best = reps.maxOf { it.depth }
            out += check("depth", reps, { r -> if (best > 0) r.depth >= PushupStandards.FRONT_DEPTH_OF_BEST * best else null }) { ok, n ->
                "$ok of $n reps went as deep as your best (front-on can only compare with your own deepest rep; side-on measures 90°)"
            }
            out += check("hands", reps, { r -> r.handWidth?.let { it in PushupStandards.HAND_WIDTH_MIN..PushupStandards.HAND_WIDTH_MAX } }) { _, _ ->
                val w = reps.mapNotNull { it.handWidth }.average()
                "Hands %.1f× shoulder width".format(w) + when {
                    w < PushupStandards.HAND_WIDTH_MIN -> " (narrow)"
                    w > PushupStandards.HAND_WIDTH_MAX -> " (wide)"
                    else -> ""
                }
            }
            out += check("elbows", reps, { r -> r.elbowFlare?.let { it <= PushupStandards.ELBOW_FLARE_MAX } }) { ok, n -> "$ok of $n reps kept elbows tucked (estimate)" }
            out += check("level", reps, { r -> r.tilt?.let { abs(it) <= PushupStandards.LEVEL_MAX_TILT } }) { ok, n -> "$ok of $n reps had level shoulders" }
        }
        out += check("tempo", reps, { r -> r.durationMs / 1000.0 >= PushupStandards.MIN_REP_SECONDS }) { ok, n ->
            "$ok of $n reps controlled; average %.1f s per rep".format(reps.map { it.durationMs / 1000.0 }.average())
        }
        // Order as in the guide.
        return out.sortedBy { c -> PushupStandards.all.indexOfFirst { it.id == c.id } }
    }

    /** Reps that met every standard measured for them. */
    fun cleanReps(reps: List<RepForm>, method: PushupMethod): Int {
        val best = reps.maxOfOrNull { it.depth } ?: 0.0
        return reps.count { r ->
            val checks = listOfNotNull(
                r.elbowDegrees?.let { it <= PushupStandards.DEPTH_ELBOW_PASS },
                r.lockoutDegrees?.let { it >= PushupStandards.LOCKOUT_PASS },
                r.bodyLineDegrees?.let { it >= PushupStandards.BODY_LINE_PASS },
                r.neckDegrees?.let { it >= PushupStandards.NECK_PASS },
                if (method == PushupMethod.CAMERA_FRONT && best > 0) r.depth >= PushupStandards.FRONT_DEPTH_OF_BEST * best else null,
                r.elbowFlare?.let { it <= PushupStandards.ELBOW_FLARE_MAX },
                r.tilt?.let { abs(it) <= PushupStandards.LEVEL_MAX_TILT },
                r.durationMs / 1000.0 >= PushupStandards.MIN_REP_SECONDS,
            )
            checks.all { it }
        }
    }
}
