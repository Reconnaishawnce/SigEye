package com.sigeye.core.analysis.presence

import kotlin.math.abs

/**
 * One headcount somebody actually took, and what the radio heard while they took it.
 *
 * The floor and the presence window are recorded with it, and they are not bookkeeping.
 * A devices-per-person factor is only meaningful relative to the "here" it was measured
 * in: at -70 dBm you are counting a room and at -85 you are counting the building and the
 * street outside it, and the same twelve people produce wildly different device counts in
 * those two. A factor carried across that boundary is worse than no factor, because it
 * looks like a measurement.
 */
data class Calibration(
    val place: String,
    val headcount: Int,
    /** Mean present-device count across the window, not the count at one instant. */
    val devices: Double,
    val samples: Int,
    val seconds: Int,
    val rssiFloor: Int,
    val presenceSeconds: Int,
    val takenAtMs: Long,
) {
    /** Devices per person from this one count, on its own. */
    val ratio: Double get() = if (headcount <= 0) 0.0 else devices / headcount

    val usable: Boolean
        get() = headcount > 0 && devices > 0.0 && samples > 0

    /**
     * Whether this count was taken in a close enough "here" to be pooled with another.
     *
     * A few decibels of slack because the floor is a slider and the set of devices above
     * it barely moves over one or two - past that you are counting a different size of
     * room and the factor does not carry.
     */
    fun comparableTo(rssiFloor: Int, presenceSeconds: Int): Boolean =
        abs(this.rssiFloor - rssiFloor) <= CrowdCalibration.FLOOR_TOLERANCE_DB &&
            this.presenceSeconds == presenceSeconds
}

/** How much a set of headcounts disagree with each other. */
enum class Agreement(val label: String) {
    /** The counts land on each other. The factor is a measurement. */
    TIGHT("They agree"),

    /** Real scatter, but a usable middle. */
    LOOSE("They roughly agree"),

    /** So far apart that the average is arithmetic rather than evidence. */
    SCATTERED("They disagree"),
}

/** What a set of headcounts says the factor should be. */
data class CrowdFit(
    val factor: Double,
    val calibrations: Int,
    /** Total people counted across all of them. This, not the count, is the sample size. */
    val people: Int,
    val lowRatio: Double,
    val highRatio: Double,
) {
    val spread: Double get() = highRatio - lowRatio

    val agreement: Agreement
        get() {
            if (calibrations < 2 || factor <= 0.0) return Agreement.LOOSE
            val relative = spread / factor
            return when {
                relative <= CrowdCalibration.TIGHT_SPREAD -> Agreement.TIGHT
                relative <= CrowdCalibration.LOOSE_SPREAD -> Agreement.LOOSE
                else -> Agreement.SCATTERED
            }
        }
}

/**
 * What to offer on this screen, right now, at this floor.
 *
 * [here] is what this place says and [elsewhere] is what everywhere else says. Both are
 * carried rather than merged, because "your kitchen says 1.8 and everywhere else says 2.4"
 * is a more useful thing to be told than either number alone.
 */
data class CrowdAdvice(
    val place: String,
    val here: CrowdFit?,
    val elsewhere: CrowdFit?,
    /** Counts set aside because they were taken at a different floor. */
    val wrongFloor: Int,
) {
    /** This place if it has anything to say, otherwise the pooled rest. */
    val suggested: CrowdFit? get() = here ?: elsewhere

    val fromHere: Boolean get() = here != null
}

/**
 * Turning headcounts into the devices-per-person factor.
 *
 * This was the weakest number in the app: a constant of 2.0 whose own doc comment said to
 * calibrate it against a headcount you know, above a screen that gave no way to do that.
 * What it had instead was a slider and the instruction to adjust until the estimate looked
 * right, which is not a calibration - it is the answer being typed in by hand.
 *
 * Pure and Android-free.
 */
object CrowdCalibration {

    /** Shorter than this and you are averaging noise rather than a device count. */
    const val MIN_SECONDS = 20

    /** How far the floor may differ before two counts are measuring different rooms. */
    const val FLOOR_TOLERANCE_DB = 3

    /** Spread as a fraction of the factor, at or under which the counts agree. */
    const val TIGHT_SPREAD = 0.25

    /** And past which they do not agree at all. */
    const val LOOSE_SPREAD = 0.6

    /**
     * The factor a set of headcounts implies.
     *
     * Total devices over total people, not the average of the individual ratios. Those are
     * different numbers and the difference matters: two people carrying five devices gives
     * a ratio of 2.5 and forty people carrying eighty-two gives 2.05, and averaging the
     * ratios treats those as equally good evidence when the second is twenty times the
     * sample. Pooling the totals weights each count by the headcount behind it, which is
     * what a bigger count has earned.
     */
    fun fit(calibrations: List<Calibration>): CrowdFit? {
        val usable = calibrations.filter { it.usable }
        if (usable.isEmpty()) return null

        val people = usable.sumOf { it.headcount }
        val devices = usable.sumOf { it.devices }
        if (people <= 0 || devices <= 0.0) return null

        val ratios = usable.map { it.ratio }
        return CrowdFit(
            factor = devices / people,
            calibrations = usable.size,
            people = people,
            lowRatio = ratios.min(),
            highRatio = ratios.max(),
        )
    }

    /** Splits what is on file into what this place says and what everywhere else says. */
    fun adviseFor(
        all: List<Calibration>,
        place: String,
        rssiFloor: Int,
        presenceSeconds: Int,
    ): CrowdAdvice {
        val comparable = all.filter { it.usable && it.comparableTo(rssiFloor, presenceSeconds) }
        val here = comparable.filter { it.place.equals(place, ignoreCase = true) }
        val rest = comparable.filterNot { it.place.equals(place, ignoreCase = true) }

        return CrowdAdvice(
            place = place,
            here = fit(here),
            elsewhere = fit(rest),
            wrongFloor = all.count { it.usable } - comparable.size,
        )
    }

    /**
     * Averages a window of device counts into the one number a calibration records.
     *
     * A single instant is the wrong thing to divide a headcount by: the present count moves
     * every second as packets arrive and lapse, and whichever second the button was pressed
     * in has no claim to being the right one.
     */
    fun meanDevices(samples: List<Int>): Double =
        if (samples.isEmpty()) 0.0 else samples.sum().toDouble() / samples.size
}
