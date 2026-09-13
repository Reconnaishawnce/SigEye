package com.sigeye.core.analysis.rf

import java.util.Locale
import kotlin.math.abs

/** What a roll sweep found. */
data class PolarizationResult(
    val sweep: SweepResult,
    val bestRollDegrees: Float?,
    val worstRollDegrees: Float?,
    val depthDb: Double?,
    val separationDegrees: Float?,
    val coverage: Float,
) {
    /**
     * Two nulls a right angle apart is the signature of polarization rather than of a room.
     *
     * A dipole's response has two maxima and two minima, ninety degrees apart, repeating
     * every half turn. So the strongest and weakest roll angles should sit about ninety
     * degrees from each other - and a null that turns up somewhere else is almost
     * certainly something moving, or a hand covering the antenna.
     */
    val looksLikePolarisation: Boolean
        get() {
            val separation = separationDegrees ?: return false
            val depth = depthDb ?: return false
            return depth >= MEANINGFUL_DEPTH_DB &&
                abs(separation - 90f) <= SEPARATION_TOLERANCE
        }

    fun verdict(): String? = when {
        coverage < MIN_COVERAGE -> "Only ${(coverage * 100).toInt()}% of the roll " +
            "covered so far. Keep turning. One full revolution about the long axis, then " +
            "back the other way."
        depthDb == null -> "Nothing measured yet."
        depthDb < MEANINGFUL_DEPTH_DB ->
            String.format(
                Locale.US,
                "Only %.1f dB between the best and worst angle. Either the source is " +
                    "close enough that reflections fill in the null, or its antenna is " +
                    "not linearly polarized. Try further away, or with a clearer path.",
                depthDb,
            )
        !looksLikePolarisation ->
            String.format(
                Locale.US,
                "The strongest and weakest angles are %.0f degrees apart. Polarization " +
                    "puts them ninety apart, so something else is doing this - a hand " +
                    "over the antenna, or something moving while you turned.",
                separationDegrees ?: 0f,
            )
        else -> null
    }

    fun describe(): String = when {
        depthDb == null -> "No reading yet."
        else -> String.format(
            Locale.US,
            "%.1f dB between aligned and crossed, %.0f degrees apart.",
            depthDb,
            separationDegrees ?: 0f,
        )
    }

    companion object {
        /**
         * Below this the difference is multipath rather than polarization.
         *
         * A stationary link wanders several dB on its own - which the Multipath Fading
         * experiment exists to demonstrate - so anything smaller proves nothing.
         */
        const val MEANINGFUL_DEPTH_DB = 6.0

        /** How far from a right angle the two extremes may sit and still count. */
        const val SEPARATION_TOLERANCE = 35f

        /** Below this the sweep has not seen enough of the response to judge its shape. */
        const val MIN_COVERAGE = 0.6f
    }
}

/**
 * Signal against the roll angle of the phone, which is antenna polarization.
 *
 * A linearly polarized antenna radiates a field oriented one way, and a receiving antenna
 * turned across that field picks up far less of it - in theory nothing at all, in a real
 * room ten to twenty dB. So rolling a phone about its long axis while watching one
 * transmitter traces out that response, and the depth of the null is a direct measurement
 * of how much of the signal is arriving by a single clean path.
 *
 * Reuses [PolarSweep] wholesale: the binning, the adaptive resolution and the
 * not-moving gate are all the same problem as the compass sweep, only about a different
 * axis. The axis is the improvement - roll comes from gravity, which no amount of metal in
 * a building distorts.
 *
 * Pure and Android-free.
 */
object Polarization {

    /**
     * Roll is symmetric over half a turn, so the response repeats every 180 degrees.
     *
     * Folding the two halves together doubles the readings in every bin, which matters a
     * great deal when the whole sweep is one slow turn of the wrist.
     */
    fun fold(rollDegrees: Float): Float = ((rollDegrees % 180f) + 180f) % 180f

    /**
     * Where a roll angle belongs on [PolarSweep]'s circle, which is a full 360 degrees.
     *
     * The sweep spine bins over a whole circle because it was built for a compass, and
     * feeding it folded angles would leave half of every plot permanently empty - coverage
     * could never pass fifty percent and the sweep would never be judged complete.
     * Doubling the folded angle spreads half a turn of roll across the full circle
     * instead, so every bin is reachable and the two lobes of a dipole response land
     * opposite each other, where peak-against-notch is exactly what the spine measures
     * best. Angles coming back out are halved again by [analyze].
     */
    fun plotAngle(rollDegrees: Float): Float = fold(rollDegrees) * 2f

    fun analyze(sweep: PolarSweep, resolution: Int = 12): PolarizationResult {
        val result = sweep.result(resolution)
        val best = result.peakBearingDegrees?.let { fold(it / 2f) }
        val worst = result.notchBearingDegrees?.let { fold(it / 2f) }
        val depth = result.frontToBackDb

        // Separation on a half-turn circle: 170 and 10 are twenty degrees apart, not a
        // hundred and sixty.
        val separation = if (best != null && worst != null) {
            var difference = abs(best - worst) % 180f
            if (difference > 90f) difference = 180f - difference
            difference
        } else {
            null
        }

        return PolarizationResult(
            sweep = result,
            bestRollDegrees = best,
            worstRollDegrees = worst,
            depthDb = depth,
            separationDegrees = separation,
            coverage = result.coverage,
        )
    }

    /**
     * How much of the signal must be arriving by a single path, from the null depth.
     *
     * A perfectly clean single path would vanish entirely when crossed; every dB of signal
     * left in the null arrived by some other route. Treating the null as the scattered
     * power and the peak as everything gives the ratio directly, which is the same
     * quantity the fading experiment calls K - arrived at from a completely different
     * measurement, which is a pleasing thing to be able to check.
     */
    /**
     * The roll sweep as it was recorded, plus what was made of it.
     *
     * Roll is given both as it was measured and as it was plotted, because the doubling in
     * [plotAngle] is the one step a reader would not guess from the numbers - and without
     * it the bearings in the summary look like they disagree with the readings.
     */
    fun csv(sweep: PolarSweep, result: PolarizationResult): String = buildString {
        appendLine(
            "# depth_db=" + (result.depthDb?.let { String.format(Locale.US, "%.2f", it) } ?: "") +
                " best_roll_deg=" + (result.bestRollDegrees?.let {
                    String.format(Locale.US, "%.1f", it)
                } ?: "") +
                " worst_roll_deg=" + (result.worstRollDegrees?.let {
                    String.format(Locale.US, "%.1f", it)
                } ?: "") +
                " separation_deg=" + (result.separationDegrees?.let {
                    String.format(Locale.US, "%.1f", it)
                } ?: "") +
                " coverage=" + String.format(Locale.US, "%.2f", result.coverage) +
                " polarization=" + result.looksLikePolarisation,
        )
        appendLine("# roll_deg is the measured roll; plot_deg is roll folded to a half turn and doubled")
        appendLine("elapsed_ms,plot_deg,roll_deg,rssi_dbm")
        val readings = sweep.readings()
        val first = readings.firstOrNull()?.atMs ?: 0L
        readings.forEach {
            appendLine(
                String.format(
                    Locale.US,
                    "%d,%.1f,%.1f,%d",
                    it.atMs - first,
                    it.headingDegrees,
                    fold(it.headingDegrees / 2f),
                    it.rssi,
                ),
            )
        }
    }

    fun directPathFraction(depthDb: Double): Double {
        if (depthDb <= 0.0) return 0.0
        val ratio = Math.pow(10.0, -depthDb / 10.0)
        return (1.0 - ratio).coerceIn(0.0, 1.0)
    }
}
