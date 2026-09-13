package com.sigeye.core.analysis.rf

import com.sigeye.core.analysis.Stats
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** One reading in a fading record. */
data class FadeSample(val atMs: Long, val rssi: Int)

/** What the fading looks like, in words. */
enum class FadingCharacter(val label: String, val meaning: String) {
    TOO_FEW(
        "Not enough yet",
        "Keep recording - a few seconds of packets cannot show a distribution.",
    ),
    STEADY(
        "Barely fading",
        "One path dominates and the reflections are weak beside it. Typical of a source " +
            "in the same room with nothing between you.",
    ),
    DIRECT(
        "Direct path, with echoes",
        "There is a clear dominant path, but reflections are strong enough to move the " +
            "level by a few dB on their own.",
    ),
    MIXED(
        "Mixed",
        "No single path is running the show. Signal strength here is a rough indicator " +
            "and nothing more.",
    ),
    SCATTERED(
        "Scattered",
        "Everything arriving has bounced. The level swings widely with nothing moving, " +
            "which is the classic indoor case and the reason range estimates go wrong.",
    ),
}

/**
 * What a signal does when nothing is moving.
 *
 * A transmitter reaches a receiver by more than one path - straight there, off the ceiling,
 * off a filing cabinet - and those copies arrive slightly out of step. Where they add, the
 * signal is strong; where they cancel, it collapses. The pattern is fixed in space, so
 * moving the phone a few centimetres changes the level by several dB while the distance is
 * to all intents unchanged.
 *
 * The headline number is [ricianKDb], the ratio between the power in the dominant path and
 * the power in everything else. It falls out of the first two moments of the *linear* power
 * rather than of the dB values, because fading is multiplicative and dB averages flatter it.
 *
 * Pure and Android-free.
 */
object FadingAnalysis {

    const val MIN_SAMPLES = 20

    /** Below this the readings are mostly the radio's 1 dB quantisation. */
    const val STEADY_SD_DB = 1.0

    fun analyze(samples: List<FadeSample>): FadingStats {
        if (samples.size < MIN_SAMPLES) {
            return FadingStats(
                samples = samples.size,
                meanDbm = samples.map { it.rssi }.average().takeIf { samples.isNotEmpty() }
                    ?: 0.0,
                sdDb = 0.0,
                minDbm = samples.minOfOrNull { it.rssi } ?: 0,
                maxDbm = samples.maxOfOrNull { it.rssi } ?: 0,
                p10Dbm = 0.0,
                p90Dbm = 0.0,
                ricianK = null,
                spanSeconds = span(samples),
            )
        }

        val values = samples.map { it.rssi }
        val mean = values.average()
        val variance = values.sumOf { (it - mean).pow(2) } / values.size
        val sorted = values.sorted().map { it.toDouble() }

        return FadingStats(
            samples = samples.size,
            meanDbm = mean,
            sdDb = sqrt(variance),
            minDbm = values.min(),
            maxDbm = values.max(),
            p10Dbm = Stats.percentile(sorted, 0.10),
            p90Dbm = Stats.percentile(sorted, 0.90),
            ricianK = ricianK(values),
            spanSeconds = span(samples),
        )
    }

    /**
     * Rice's K factor by the method of moments, from the linear power.
     *
     * With `g` the variance of the power over its mean squared, `K = (u + sqrt(u)) / g`
     * where `u = 1 - g`. It comes straight out of the Rician moments: the power of a signal
     * that is one steady path plus a cloud of scattered ones has `Var(P)/E(P)^2 =
     * (2K + 1)/(K + 1)^2`, and that inverts in closed form.
     *
     * `g = 1` is Rayleigh - no dominant path at all - and returns 0. Anything above 1 is
     * noisier than Rayleigh, which means something was moving and the premise of the
     * measurement has gone; it is clamped to 0 rather than extrapolated into nonsense.
     */
    fun ricianK(rssiValues: List<Int>): Double? {
        if (rssiValues.size < MIN_SAMPLES) return null
        val powers = rssiValues.map { 10.0.pow(it / 10.0) }
        val mean = powers.average()
        if (mean <= 0.0) return null
        val variance = powers.sumOf { (it - mean).pow(2) } / powers.size
        val g = variance / (mean * mean)
        if (g <= 0.0) return Double.POSITIVE_INFINITY
        if (g >= 1.0) return 0.0
        val u = 1.0 - g
        return (u + sqrt(u)) / g
    }

    private fun span(samples: List<FadeSample>): Double {
        if (samples.size < 2) return 0.0
        return (samples.last().atMs - samples.first().atMs) / 1000.0
    }
}

data class FadingStats(
    val samples: Int,
    val meanDbm: Double,
    val sdDb: Double,
    val minDbm: Int,
    val maxDbm: Int,
    val p10Dbm: Double,
    val p90Dbm: Double,
    /** Linear ratio of dominant-path power to scattered power; null until enough samples. */
    val ricianK: Double?,
    val spanSeconds: Double,
) {
    val rangeDb: Int get() = maxDbm - minDbm

    /** How far below the average the deep fades go - the margin a link has to carry. */
    val fadeDepthDb: Double get() = meanDbm - p10Dbm

    val ricianKDb: Double?
        get() = ricianK?.let {
            when {
                it <= 0.0 -> Double.NEGATIVE_INFINITY
                it.isInfinite() -> Double.POSITIVE_INFINITY
                else -> 10.0 * log10(it)
            }
        }

    val packetsPerSecond: Double
        get() = if (spanSeconds <= 0.0) 0.0 else samples / spanSeconds

    val character: FadingCharacter
        get() {
            if (samples < FadingAnalysis.MIN_SAMPLES) return FadingCharacter.TOO_FEW
            if (sdDb < FadingAnalysis.STEADY_SD_DB) return FadingCharacter.STEADY
            val k = ricianKDb ?: return FadingCharacter.TOO_FEW
            return when {
                k >= 10.0 -> FadingCharacter.STEADY
                k >= 6.0 -> FadingCharacter.DIRECT
                k >= 2.0 -> FadingCharacter.MIXED
                else -> FadingCharacter.SCATTERED
            }
        }

    /**
     * How badly this fading corrupts a distance estimate.
     *
     * Range from signal strength inverts `rssi = reference - 10 n log10(d)`, so an error of
     * `sd` dB multiplies or divides the answer by `10^(sd / 10n)`. Six dB of fading at an
     * exponent of two is a factor of two - the thing worth knowing about every proximity
     * feature anyone has ever shipped.
     */
    fun distanceErrorFactor(pathLossExponent: Double = 2.0): Double =
        10.0.pow(sdDb / (10.0 * pathLossExponent))

    /** The same, as a range around a nominal distance. */
    fun distanceRange(meters: Double, pathLossExponent: Double = 2.0): ClosedFloatingPointRange<Double> {
        val factor = distanceErrorFactor(pathLossExponent)
        return (meters / factor)..(meters * factor)
    }
}
