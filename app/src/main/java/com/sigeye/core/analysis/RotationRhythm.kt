package com.sigeye.core.analysis

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** One bar of the period histogram. */
data class PeriodBucket(val fromMs: Long, val toMs: Long, val count: Int)

/** How one device times its address changes. */
data class Rhythm(
    /** Gaps between consecutive address changes, in the order they happened. */
    val periodsMs: List<Long>,
    val medianPeriodMs: Long?,
    /** Half the spread between the quickest and slowest, as a fraction of the median. */
    val looseness: Double?,
    /**
     * Where in the cycle this device changes, measured from the Unix epoch.
     *
     * Null until there is enough to say. See [RotationRhythm] for why this is an
     * identifier rather than a curiosity.
     */
    val phaseMs: Long?,
    val phaseSpreadMs: Long?,
    val changes: Int,
) {
    val measurable: Boolean get() = medianPeriodMs != null

    /** Whether this device is using the specification's default timeout. */
    val matchesSpecDefault: Boolean
        get() {
            val period = medianPeriodMs ?: return false
            return abs(period - RotationRhythm.SPEC_DEFAULT_MS) <=
                RotationRhythm.SPEC_DEFAULT_MS * RotationRhythm.STANDARD_TOLERANCE
        }

    /** A metronome rather than a rough timer, which is what makes the phase worth having. */
    val regular: Boolean
        get() = (looseness ?: 1.0) <= RotationRhythm.REGULAR_LOOSENESS

    /**
     * True once the phase has held still across enough changes to be a handle.
     *
     * Two changes agree on a phase by construction - the second one *defines* it - so
     * nothing is claimed until a third has confirmed it.
     */
    val phaseUsable: Boolean
        get() = phaseMs != null &&
            changes >= RotationRhythm.MIN_CHANGES_FOR_PHASE &&
            regular &&
            (phaseSpreadMs ?: Long.MAX_VALUE) <= RotationRhythm.PHASE_TOLERANCE_MS

    fun describePeriod(): String {
        val period = medianPeriodMs ?: return "not measured yet"
        return when {
            period >= 60_000 -> String.format(Locale.US, "every %.1f minutes", period / 60_000.0)
            else -> String.format(Locale.US, "every %.0f seconds", period / 1000.0)
        }
    }
}

/**
 * Measuring how often a device changes address, instead of assuming fifteen minutes.
 *
 * Fifteen minutes is a convention, not a rule. The Bluetooth specification makes the
 * resolvable private address timeout a settable value anywhere from one second to an hour,
 * with a default of nine hundred seconds, and implementations are free to pick anything in
 * that range - so the only way to know what a device in front of you does is to watch it.
 *
 * The more interesting consequence is what the timer is *not*: it is not aligned to a
 * clock. It runs from the moment the last address was generated, so two phones sitting on
 * the same table, both on the default nine hundred seconds, change at different seconds
 * and keep doing so. That offset - the phase - is a property of the device that every
 * rotation preserves, which is exactly what a rotation is meant to destroy. It survives
 * until something restarts the timer: Bluetooth switched off and on, flight mode, a
 * reboot.
 *
 * So this measures both, and says plainly when it has too little to say either.
 *
 * Pure and Android-free.
 */
object RotationRhythm {

    /** The specification's default resolvable private address timeout: 900 seconds. */
    const val SPEC_DEFAULT_MS = 900_000L

    /** The lowest and highest the specification allows the timeout to be set to. */
    const val SPEC_MIN_MS = 1_000L
    const val SPEC_MAX_MS = 3_600_000L

    /** How far from a round value a measurement may sit and still be called that value. */
    const val STANDARD_TOLERANCE = 0.12

    /** Above this, the device is not keeping time well enough for the phase to mean much. */
    const val REGULAR_LOOSENESS = 0.15

    /**
     * How far the phase may wander and still count as the same phase.
     *
     * Generous on purpose: a change is only seen to the nearest advertising interval, and
     * the old address is noticed as gone only after it has been silent a while, so half a
     * minute of slop is measurement rather than drift.
     */
    const val PHASE_TOLERANCE_MS = 45_000L

    /** Two changes define a phase; three test it. */
    const val MIN_CHANGES_FOR_PHASE = 3

    /** Timeouts that turn up often enough to be worth naming when a measurement lands on one. */
    private val FAMILIAR: List<Pair<Long, String>> = listOf(
        900_000L to "the specification's default, 900 seconds",
        600_000L to "ten minutes",
        300_000L to "five minutes",
        120_000L to "two minutes",
        60_000L to "one minute",
        15_000L to "fifteen seconds",
    )

    /**
     * @param changeTimesMs when each address change was observed, in any order.
     */
    fun analyse(changeTimesMs: List<Long>): Rhythm {
        val times = changeTimesMs.sorted()
        val periods = times.zipWithNext { a, b -> b - a }.filter { it > 0 }

        if (periods.isEmpty()) {
            return Rhythm(
                periodsMs = emptyList(),
                medianPeriodMs = null,
                looseness = null,
                phaseMs = null,
                phaseSpreadMs = null,
                changes = times.size,
            )
        }

        val sorted = periods.sorted()
        val median = sorted[sorted.size / 2]
        val looseness = if (median <= 0) {
            null
        } else {
            (sorted.last() - sorted.first()) / 2.0 / median
        }

        // Phase is where in the cycle the change lands, measured against the epoch. Taken
        // on the circle rather than as a plain average: a device changing at 899_000 and
        // one at 1_000 are two seconds apart, not the whole cycle.
        val phases = times.map { ((it % median) + median) % median }
        val phase = circularMedian(phases, median)
        val spread = phase?.let { centre ->
            phases.maxOfOrNull { circularDistance(it, centre, median) }
        }

        return Rhythm(
            periodsMs = periods,
            medianPeriodMs = median,
            looseness = looseness,
            phaseMs = phase,
            phaseSpreadMs = spread,
            changes = times.size,
        )
    }

    /** Names a measured period when it lands on one people recognise. */
    fun familiarName(periodMs: Long?): String? {
        val period = periodMs ?: return null
        return FAMILIAR.firstOrNull {
            abs(period - it.first) <= it.first * STANDARD_TOLERANCE
        }?.second
    }

    /** Whether a measured period is even legal, which is worth checking before believing it. */
    fun withinSpec(periodMs: Long?): Boolean {
        val period = periodMs ?: return false
        return period in SPEC_MIN_MS..SPEC_MAX_MS
    }

    /**
     * Buckets a set of measured periods, for showing a room's habits side by side.
     *
     * Buckets are relative to the specification's default rather than absolute, so the
     * bar everybody expects sits in the middle and anything unusual is visibly not there.
     */
    fun histogram(periodsMs: List<Long>, buckets: Int = 12): List<PeriodBucket> {
        if (periodsMs.isEmpty()) return emptyList()
        val lowest = periodsMs.min()
        val highest = periodsMs.max()
        val span = (highest - lowest).coerceAtLeast(1L)
        val width = (span.toDouble() / buckets).roundToLong().coerceAtLeast(1L)
        return (0 until buckets).map { index ->
            val from = lowest + index * width
            val to = from + width
            PeriodBucket(
                fromMs = from,
                toMs = to,
                count = periodsMs.count {
                    it >= from && (it < to || (index == buckets - 1 && it <= to))
                },
            )
        }
    }

    /**
     * How distinctive a phase is, as one in how many.
     *
     * A phase known to within its tolerance divides the cycle into that many slots, and
     * two unrelated devices land in the same slot by chance one time in that many. It is
     * the honest way to express what the measurement is worth: on a fifteen minute cycle
     * known to within forty-five seconds, one in twenty - useful alongside other evidence
     * and nowhere near enough on its own.
     */
    fun phaseSlots(periodMs: Long?): Int {
        val period = periodMs ?: return 0
        if (period <= 0) return 0
        return (period / PHASE_TOLERANCE_MS).toInt().coerceAtLeast(1)
    }

    private fun circularMedian(values: List<Long>, period: Long): Long? {
        if (values.isEmpty() || period <= 0) return null
        // Try each value as the origin and keep whichever ordering is tightest. Cheap at
        // the handful of changes this ever sees, and correct across the wrap.
        var best: Long? = null
        var bestSpread = Long.MAX_VALUE
        values.forEach { origin ->
            val spread = values.maxOf { circularDistance(it, origin, period) }
            if (spread < bestSpread) {
                bestSpread = spread
                best = origin
            }
        }
        return best
    }

    private fun circularDistance(a: Long, b: Long, period: Long): Long {
        val raw = abs(a - b) % period
        return minOf(raw, period - raw)
    }
}
