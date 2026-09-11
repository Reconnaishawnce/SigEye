package com.sigeye.core.analysis

import kotlin.math.abs
import kotlin.math.pow

/** Coarse distance bands, in the spirit of iBeacon's - honest about being bands. */
enum class ProximityZone(val label: String, val blurb: String) {
    IMMEDIATE("Immediate", "Within arm's reach."),
    NEAR("Near", "Same room, a couple of paces."),
    FAR("Far", "Same floor, across a room or through a wall."),
    DISTANT("Distant", "At the edge of range."),
    UNKNOWN("Unknown", "Not enough signal to say."),
}

/** Which way the thing is going, which is what actually lets you hunt for it. */
enum class Trend(val label: String, val arrow: String) {
    CLOSER("Getting closer", "▲"),
    STEADY("Holding", "●"),
    FURTHER("Getting further", "▼"),
    UNKNOWN("Feeling it out", "·"),
}

data class ProximityReading(
    val rawRssi: Int,
    val smoothedRssi: Double,
    val metres: Double,
    val zone: ProximityZone,
    val trend: Trend,
    /** dB per second. Positive means the signal is strengthening. */
    val slopeDbPerSecond: Double,
    /** 0..1, from how steady recent readings have been. */
    val confidence: Double,
    val bestRssi: Int,
    val samples: Int,
)

/**
 * A one-dimensional Kalman filter over RSSI.
 *
 * Raw BLE signal jumps several dB between consecutive packets with nothing moving - that
 * is multipath, not distance. Showing it unfiltered makes a locator useless, because the
 * needle swings wildly while you stand still and barely moves when you walk. The filter
 * trusts its own estimate more than any single packet, so the reading settles while still
 * following a genuine change within a second or two.
 */
class RssiFilter(
    /** How much the true value is expected to drift between readings. */
    private val processNoise: Double = 0.18,
    /** How noisy one packet is. Around 6 dB is realistic indoors. */
    private val measurementNoise: Double = 6.0,
) {
    private var estimate = Double.NaN
    private var errorCovariance = 1.0

    val hasEstimate: Boolean get() = !estimate.isNaN()

    fun value(): Double = estimate

    fun reset() {
        estimate = Double.NaN
        errorCovariance = 1.0
    }

    fun update(measurement: Double): Double {
        if (estimate.isNaN()) {
            estimate = measurement
            return estimate
        }
        val predictedCovariance = errorCovariance + processNoise
        val gain = predictedCovariance / (predictedCovariance + measurementNoise)
        estimate += gain * (measurement - estimate)
        errorCovariance = (1 - gain) * predictedCovariance
        return estimate
    }
}

/**
 * Turns a stream of RSSI readings into something you can walk towards.
 *
 * The distance figure uses the log-distance path loss model and is deliberately presented
 * as a band rather than a number wherever the UI can manage it - see [ProximityZone]. The
 * useful output is [Trend]: absolute distance from RSSI is unreliable indoors, but whether
 * it is getting stronger as you move is reliable, and that is enough to find something.
 */
class ProximityEstimator(
    /** Signal at one metre. Beacons often declare their own; otherwise assume. */
    var txPowerAtOneMetre: Int = -59,
    /**
     * Path loss exponent. 2.0 is free space; indoors with walls is nearer 3. Raising it
     * makes the same signal read as a shorter distance.
     */
    var pathLossExponent: Double = 2.0,
    private val trendWindowMs: Long = 6_000L,
) {
    private val filter = RssiFilter()
    private val history = ArrayDeque<Pair<Long, Double>>()
    private var best = Int.MIN_VALUE
    private var samples = 0

    fun reset() {
        filter.reset()
        history.clear()
        best = Int.MIN_VALUE
        samples = 0
    }

    fun observe(rssi: Int, atMs: Long): ProximityReading {
        val smoothed = filter.update(rssi.toDouble())
        if (rssi > best) best = rssi
        samples++

        history.addLast(atMs to smoothed)
        while (history.isNotEmpty() && atMs - history.first().first > trendWindowMs) {
            history.removeFirst()
        }

        val slope = slopeDbPerSecond()
        return ProximityReading(
            rawRssi = rssi,
            smoothedRssi = smoothed,
            metres = metresFor(smoothed),
            zone = zoneFor(metresFor(smoothed), samples),
            trend = trendFor(slope),
            slopeDbPerSecond = slope,
            confidence = confidence(),
            bestRssi = best,
            samples = samples,
        )
    }

    fun metresFor(rssi: Double): Double =
        10.0.pow((txPowerAtOneMetre - rssi) / (10.0 * pathLossExponent))

    /**
     * Least squares slope over the trend window.
     *
     * A slope rather than a comparison of two points: single readings are noisy enough
     * that first-versus-last would flip direction constantly.
     */
    fun slopeDbPerSecond(): Double {
        if (history.size < 4) return 0.0
        val firstMs = history.first().first
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumXX = 0.0
        history.forEach { (timeMs, value) ->
            val x = (timeMs - firstMs) / 1000.0
            sumX += x
            sumY += value
            sumXY += x * value
            sumXX += x * x
        }
        val n = history.size
        val denominator = n * sumXX - sumX * sumX
        if (abs(denominator) < 1e-9) return 0.0
        return (n * sumXY - sumX * sumY) / denominator
    }

    private fun trendFor(slope: Double): Trend = when {
        history.size < 4 -> Trend.UNKNOWN
        slope >= TREND_THRESHOLD -> Trend.CLOSER
        slope <= -TREND_THRESHOLD -> Trend.FURTHER
        else -> Trend.STEADY
    }

    /** Steadier recent readings mean the distance estimate deserves more belief. */
    private fun confidence(): Double {
        if (history.size < 4) return 0.0
        val values = history.map { it.second }
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        // Around 9 dB of variance is thoroughly unreliable; near zero is as good as it gets.
        return (1.0 - (variance / 9.0)).coerceIn(0.0, 1.0)
    }

    companion object {
        /** dB per second that counts as movement rather than drift. */
        const val TREND_THRESHOLD = 0.9

        fun zoneFor(metres: Double, samples: Int): ProximityZone = when {
            samples < 3 -> ProximityZone.UNKNOWN
            metres < 0.5 -> ProximityZone.IMMEDIATE
            metres < 2.0 -> ProximityZone.NEAR
            metres < 8.0 -> ProximityZone.FAR
            else -> ProximityZone.DISTANT
        }
    }
}
