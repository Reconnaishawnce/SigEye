package com.sigeye.core.analysis.rf

import kotlin.math.abs
import kotlin.math.sqrt

/** Summary statistics for one phase of a before-and-after measurement. */
data class PhaseStats(
    val samples: Int,
    val mean: Double,
    val standardDeviation: Double,
    val min: Double,
    val max: Double,
) {
    val standardError: Double
        get() = if (samples < 2) Double.NaN else standardDeviation / sqrt(samples.toDouble())

    companion object {
        val EMPTY = PhaseStats(0, 0.0, 0.0, 0.0, 0.0)

        fun of(values: List<Double>): PhaseStats {
            if (values.isEmpty()) return EMPTY
            val mean = values.average()
            val variance = if (values.size < 2) {
                0.0
            } else {
                values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
            }
            return PhaseStats(
                samples = values.size,
                mean = mean,
                standardDeviation = sqrt(variance),
                min = values.min(),
                max = values.max(),
            )
        }
    }
}

/** How much to believe a difference between two phases. */
enum class Significance(val label: String) {
    INSUFFICIENT("Not enough data"),
    NONE("No detectable difference"),
    WEAK("Possible difference"),
    CLEAR("Clear difference"),
}

data class AbResult(
    val baseline: PhaseStats,
    val test: PhaseStats,
) {
    /** Test minus baseline. Negative means the test phase was lower. */
    val delta: Double get() = test.mean - baseline.mean

    val percentChange: Double?
        get() = if (abs(baseline.mean) < 1e-9) null else delta / baseline.mean * 100.0

    /**
     * Welch's t statistic - the version that does not assume the two phases have equal
     * variance, which they will not: a microwave makes readings noisier as well as fewer.
     */
    val tStatistic: Double?
        get() {
            if (baseline.samples < 2 || test.samples < 2) return null
            val a = baseline.standardDeviation * baseline.standardDeviation / baseline.samples
            val b = test.standardDeviation * test.standardDeviation / test.samples
            val denominator = sqrt(a + b)
            if (denominator < 1e-12) return null
            return delta / denominator
        }

    /**
     * A deliberately coarse verdict.
     *
     * Reporting a p-value from a phone held in someone's hand would be false precision -
     * the samples are not independent and the conditions are not controlled. The honest
     * statement is whether the difference is large compared to how much each phase
     * wandered on its own.
     */
    val significance: Significance
        get() {
            if (baseline.samples < MIN_SAMPLES || test.samples < MIN_SAMPLES) {
                return Significance.INSUFFICIENT
            }
            val t = tStatistic ?: return Significance.INSUFFICIENT
            return when {
                abs(t) >= 4.0 -> Significance.CLEAR
                abs(t) >= 2.0 -> Significance.WEAK
                else -> Significance.NONE
            }
        }

    companion object {
        const val MIN_SAMPLES = 5
    }
}

/**
 * Collects two phases of a measurement and compares them.
 *
 * The shape every "does X affect Y" experiment needs: measure with the thing off, measure
 * with it on, and say whether the difference is bigger than the noise. Used by Microwave
 * Interference, and the same machinery a Faraday cage test wants.
 *
 * Pure and Android-free.
 */
class AbComparison {

    enum class Phase { IDLE, BASELINE, TEST }

    private val baseline = mutableListOf<Double>()
    private val test = mutableListOf<Double>()

    var phase: Phase = Phase.IDLE
        private set

    fun startBaseline() {
        baseline.clear()
        phase = Phase.BASELINE
    }

    fun startTest() {
        test.clear()
        phase = Phase.TEST
    }

    fun stop() {
        phase = Phase.IDLE
    }

    fun reset() {
        baseline.clear()
        test.clear()
        phase = Phase.IDLE
    }

    /** Records into whichever phase is running. Ignored when idle. */
    fun record(value: Double) {
        when (phase) {
            Phase.BASELINE -> baseline.add(value)
            Phase.TEST -> test.add(value)
            Phase.IDLE -> Unit
        }
    }

    fun baselineSamples(): Int = baseline.size

    fun testSamples(): Int = test.size

    fun baselineSeries(): List<Double> = baseline.toList()

    fun testSeries(): List<Double> = test.toList()

    fun result(): AbResult = AbResult(
        baseline = PhaseStats.of(baseline),
        test = PhaseStats.of(test),
    )
}
