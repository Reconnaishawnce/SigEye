package com.sigeye.core.analysis

import kotlin.math.pow
import kotlin.math.sqrt

/** One sample of a passing transmitter. */
data class PassSample(val atMs: Long, val rssi: Int)

/** How much to believe a speed. */
enum class PassQuality(val label: String) {
    GOOD("Clean pass"),
    ROUGH("Rough pass"),
    REJECTED("Not a pass"),
}

data class PassResult(
    val address: String,
    val speedMetresPerSecond: Double?,
    val peakRssi: Int,
    val peakAtMs: Long,
    /** Time between the two points [DROP_DB] below the peak. */
    val crossingMs: Long?,
    val samples: Int,
    val quality: PassQuality,
    val reason: String?,
) {
    val kmh: Double? get() = speedMetresPerSecond?.times(3.6)
    val mph: Double? get() = speedMetresPerSecond?.times(2.23694)
}

/**
 * Works out how fast something went past, from the shape of its signal.
 *
 * The geometry: a transmitter travelling in a straight line at perpendicular distance *d*
 * is closest - and loudest - at the moment it draws level. Some number of dB below that
 * peak corresponds to a known greater range, and a known range at a known perpendicular
 * distance is a right-angled triangle, so the along-track offset falls out as
 * `sqrt(r² - d²)`. The signal crosses that offset twice, once approaching and once
 * leaving, and the time between those two crossings covers `2 sqrt(r² - d²)` of track.
 *
 * Everything therefore rests on two inputs the phone cannot know: the distance to the
 * track, and the path loss exponent. Both are the user's to supply, and the result is only
 * as good as they are - which the screen says plainly rather than printing a speed to two
 * decimal places.
 *
 * Pure and Android-free.
 */
object SpeedEstimator {

    /** How far below the peak the crossing points are measured. */
    const val DROP_DB = 6.0

    /** A pass needs at least this much rise and fall to be a pass at all. */
    const val MIN_AMPLITUDE_DB = 8.0

    const val MIN_SAMPLES = 8

    /** Beyond this ratio between approach and departure, the pass is not a clean one. */
    const val MAX_ASYMMETRY = 1.6

    fun analyse(
        address: String,
        samples: List<PassSample>,
        distanceMetres: Double,
        pathLossExponent: Double = 2.0,
    ): PassResult {
        if (samples.size < MIN_SAMPLES) {
            return rejected(address, samples, "Only ${samples.size} readings - too few to " +
                "see a shape.")
        }

        // Median-smooth before looking for shape. Raw RSSI swings several dB packet to
        // packet, and every measurement here is a crossing - a single spurious sample
        // dipping below the threshold near the peak would narrow the crossing and report
        // a train going half again as fast as it was.
        val series = smooth(samples)

        // The middle of the plateau, not its first sample. Smoothing flattens the top,
        // and integer dBm ties anyway, so the strongest reading is usually several
        // samples wide - taking the first puts closest approach early by half the
        // plateau, which then makes a symmetric pass look lopsided.
        val strongest = series.maxOf { it.rssi }
        val plateau = series.indices.filter { series[it].rssi == strongest }
        val peakIndex = plateau[plateau.size / 2]
        val peak = series[peakIndex]

        val before = series.take(peakIndex + 1)
        val after = series.drop(peakIndex)
        // A pass has a rise and a fall. One-sided means the device was already alongside
        // when it appeared, or never left - either way the timing is not a crossing.
        val riseDb = peak.rssi - (before.minOfOrNull { it.rssi } ?: peak.rssi)
        val fallDb = peak.rssi - (after.minOfOrNull { it.rssi } ?: peak.rssi)

        if (riseDb < MIN_AMPLITUDE_DB || fallDb < MIN_AMPLITUDE_DB) {
            return rejected(
                address,
                samples,
                "Signal rose $riseDb dB and fell $fallDb dB. A pass needs both sides " +
                    "to move at least ${MIN_AMPLITUDE_DB.toInt()} dB.",
                peak,
            )
        }

        val threshold = peak.rssi - DROP_DB
        val entry = crossingBefore(before, threshold)
        val exit = crossingAfter(after, threshold)
        if (entry == null || exit == null || exit <= entry) {
            return rejected(
                address,
                samples,
                "Could not find both crossing points cleanly.",
                peak,
            )
        }

        val crossingMs = exit - entry
        val range = distanceMetres * 10.0.pow(DROP_DB / (10.0 * pathLossExponent))
        val alongTrack = sqrt((range * range - distanceMetres * distanceMetres)
            .coerceAtLeast(0.0))
        val trackLength = 2 * alongTrack
        val speed = if (crossingMs > 0) trackLength / (crossingMs / 1000.0) else null

        // A clean pass is symmetric: approaching and leaving should take about as long as
        // each other. Compare them directly rather than against half the total - the
        // indirect version barely moves for a pass that is genuinely twice as long on one
        // side as the other.
        val approach = (peak.atMs - entry).coerceAtLeast(1L).toDouble()
        val departure = (exit - peak.atMs).coerceAtLeast(1L).toDouble()
        val ratio = maxOf(approach / departure, departure / approach)
        val lopsided = ratio > MAX_ASYMMETRY

        return PassResult(
            address = address,
            speedMetresPerSecond = speed,
            peakRssi = peak.rssi,
            peakAtMs = peak.atMs,
            crossingMs = crossingMs,
            samples = samples.size,
            quality = if (lopsided) PassQuality.ROUGH else PassQuality.GOOD,
            reason = if (lopsided) {
                String.format(
                    java.util.Locale.US,
                    "Approach and departure differed by %.1fx, so treat this loosely.",
                    ratio,
                )
            } else {
                null
            },
        )
    }

    /**
     * Median filter, which removes spikes without dragging edges the way a mean would.
     *
     * A moving average would round off the peak itself, and the peak is where the
     * measurement starts.
     */
    private fun smooth(samples: List<PassSample>, window: Int = 5): List<PassSample> {
        if (samples.size < window) return samples
        val half = window / 2
        return samples.mapIndexed { index, sample ->
            val from = (index - half).coerceAtLeast(0)
            val to = (index + half).coerceAtMost(samples.lastIndex)
            val values = (from..to).map { samples[it].rssi }.sorted()
            sample.copy(rssi = values[values.size / 2])
        }
    }

    /** Interpolated time at which the rising side last crossed [threshold]. */
    private fun crossingBefore(rising: List<PassSample>, threshold: Double): Long? {
        for (index in rising.indices.reversed()) {
            if (rising[index].rssi <= threshold) {
                val low = rising[index]
                val high = rising.getOrNull(index + 1) ?: return low.atMs
                return interpolate(low, high, threshold)
            }
        }
        return null
    }

    /** Interpolated time at which the falling side first crossed [threshold]. */
    private fun crossingAfter(falling: List<PassSample>, threshold: Double): Long? {
        for (index in falling.indices) {
            if (falling[index].rssi <= threshold) {
                val low = falling[index]
                val high = falling.getOrNull(index - 1) ?: return low.atMs
                return interpolate(high, low, threshold)
            }
        }
        return null
    }

    /**
     * Straight-line interpolation between two samples.
     *
     * Worth doing rather than snapping to the nearest sample: at a few packets a second a
     * whole sample can be a car length, and the crossing is the entire measurement.
     */
    private fun interpolate(a: PassSample, b: PassSample, threshold: Double): Long {
        val span = (b.rssi - a.rssi).toDouble()
        if (span == 0.0) return a.atMs
        val fraction = ((threshold - a.rssi) / span).coerceIn(0.0, 1.0)
        return a.atMs + ((b.atMs - a.atMs) * fraction).toLong()
    }

    private fun rejected(
        address: String,
        samples: List<PassSample>,
        reason: String,
        peak: PassSample? = null,
    ) = PassResult(
        address = address,
        speedMetresPerSecond = null,
        peakRssi = peak?.rssi ?: (samples.maxOfOrNull { it.rssi } ?: -127),
        peakAtMs = peak?.atMs ?: (samples.lastOrNull()?.atMs ?: 0L),
        crossingMs = null,
        samples = samples.size,
        quality = PassQuality.REJECTED,
        reason = reason,
    )
}
