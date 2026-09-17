package com.sigeye.core.analysis.rf

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10

/** One access point measured both ways at once. */
data class RangeCheck(
    val bssid: String,
    val label: String,
    /** Time of flight, in metres. The one that is a length. */
    val truthM: Double,
    /** What the path loss model says, from the level at the same instant. */
    val guessM: Double,
    val rssi: Int,
    val trustworthy: Boolean,
) {
    val errorM: Double get() = guessM - truthM

    /** Error as a share of the real distance, which is the fair way to compare near and far. */
    val errorFraction: Double
        get() = if (truthM <= 0.0) 0.0 else abs(errorM) / truthM

    fun describe(): String = String.format(
        Locale.US,
        "%.1f m by timing, %.1f m by loudness, off by %.1f",
        truthM,
        guessM,
        errorM,
    )
}

/** What a set of ranged access points says about the model the rest of the app uses. */
data class RangeVerdict(
    val checks: List<RangeCheck>,
    /** Exponent fitted against time-of-flight distances, or null with too few points. */
    val measuredExponent: Double?,
    /** The exponent the estimate was using. */
    val assumedExponent: Double,
    /** Median absolute error of the loudness estimate, in metres. */
    val typicalErrorM: Double?,
) {
    val usable: List<RangeCheck> get() = checks.filter { it.trustworthy }

    val enough: Boolean get() = measuredExponent != null

    /** Whether the assumption the app has been making is wrong enough to matter. */
    val modelIsOff: Boolean
        get() = measuredExponent?.let { PathLossFit.exponentsDiffer(it, assumedExponent) } == true

    fun verdict(): String = when {
        usable.isEmpty() ->
            "No reading here was clean enough to compare. Ranging needs a clear path, and " +
                "a wall is usually enough to stop it."

        measuredExponent == null ->
            "One access point answered. That is a distance, but it takes several at " +
                "different ranges before anything can be said about the model."

        modelIsOff -> String.format(
            Locale.US,
            "This room is a %.1f, and the estimate was assuming %.1f. That is why loudness " +
                "has been putting things in the wrong place here.",
            measuredExponent,
            assumedExponent,
        )

        else -> String.format(
            Locale.US,
            "The fitted exponent is %.1f against an assumed %.1f. The model is about right " +
                "for this room.",
            measuredExponent,
            assumedExponent,
        )
    }
}

/**
 * Checking the app's own distance estimates against a distance that was measured.
 *
 * Five things in SigEye turn loudness into metres by inverting a path loss model with an
 * exponent somebody chose. Doppler Walk exists to measure that exponent, and it does it by
 * pacing a distance out and counting steps, which means the ground truth is a person's
 * stride length multiplied by a guess.
 *
 * 802.11mc measures the distance by timing light, which gives the exponent for this room
 * without anybody walking anywhere, and comparing the two is the only way this app has ever
 * had to check its own arithmetic against something outside itself.
 *
 * It does not reuse [PathLossFit], and the reason is the shape of the data rather than
 * taste. A walk is many readings of one access point at many distances, and that fitter's
 * twelve sample minimum is about the noise in a walk. Ranging is one reading each of
 * several access points at fixed distances, so twelve samples would mean twelve access
 * points and almost no room has them. The regression is the same regression; the guards
 * around it have to be different because the thing being guarded against is different.
 *
 * Pure and Android-free.
 */
object TrueRange {

    /** Fewer than this and there is a distance but no curve to fit. */
    const val MIN_POINTS = 3

    /**
     * Metres of spread between the nearest and furthest access point.
     *
     * An exponent is a slope. Three readings all taken at four metres are one point drawn
     * three times, and the slope through them is noise.
     */
    const val MIN_SPAN_M = 3.0

    /**
     * Turns ranged access points into the comparison.
     *
     * @param assumed the exponent the rest of the app has been using for this environment.
     * @param referenceDbm level at one metre, which is what the inverted model needs.
     */
    fun check(
        readings: List<Reading>,
        assumed: Double,
        referenceDbm: Double = REFERENCE_DBM,
    ): RangeVerdict {
        val checks = readings.map { reading ->
            RangeCheck(
                bssid = reading.bssid,
                label = reading.label,
                truthM = reading.distanceM,
                guessM = estimate(reading.rssi, assumed, referenceDbm),
                rssi = reading.rssi,
                trustworthy = reading.trustworthy && reading.distanceM > 0.0,
            )
        }

        val usable = checks.filter { it.trustworthy }
        val span = (usable.maxOfOrNull { it.truthM } ?: 0.0) -
            (usable.minOfOrNull { it.truthM } ?: 0.0)

        val fitted = if (usable.size >= MIN_POINTS && span >= MIN_SPAN_M) {
            exponentFrom(usable.map { it.truthM to it.rssi })
        } else {
            null
        }

        return RangeVerdict(
            checks = checks.sortedBy { it.truthM },
            measuredExponent = fitted,
            assumedExponent = assumed,
            typicalErrorM = usable.map { abs(it.errorM) }.sorted().medianOrNull(),
        )
    }

    /** One access point, ranged and heard at the same moment. */
    data class Reading(
        val bssid: String,
        val label: String,
        val distanceM: Double,
        val rssi: Int,
        val trustworthy: Boolean,
    )

    /**
     * Distance the way the rest of the app does it: invert the log-distance model.
     *
     * Here so the comparison is against what SigEye actually believes rather than against a
     * tidied-up version of it.
     */
    fun estimate(rssi: Int, exponent: Double, referenceDbm: Double = REFERENCE_DBM): Double {
        if (exponent <= 0.0) return 0.0
        return Math.pow(10.0, (referenceDbm - rssi) / (10.0 * exponent))
    }

    /** What the exponent would have to be for loudness to have been right. */
    fun impliedExponent(rssi: Int, distanceM: Double, referenceDbm: Double = REFERENCE_DBM): Double? {
        if (distanceM <= 0.0) return null
        val decades = log10(distanceM)
        if (abs(decades) < 1e-6) return null
        return (referenceDbm - rssi) / (10.0 * decades)
    }

    /**
     * Least squares through the readings, in the coordinates the model is linear in.
     *
     * The log-distance model is a straight line once distance is put on a log axis:
     * level = reference - 10n log10(d). So the slope is -10n and the exponent falls out of
     * it. Refused when the points do not actually lie on a line, because a slope through a
     * scatter is a number with no meaning attached.
     */
    fun exponentFrom(points: List<Pair<Double, Int>>): Double? {
        val usable = points.filter { it.first > 0.0 }
        if (usable.size < MIN_POINTS) return null

        val xs = usable.map { log10(it.first) }
        val ys = usable.map { it.second.toDouble() }
        val meanX = xs.average()
        val meanY = ys.average()

        var covariance = 0.0
        var varianceX = 0.0
        for (index in xs.indices) {
            val dx = xs[index] - meanX
            covariance += dx * (ys[index] - meanY)
            varianceX += dx * dx
        }
        if (varianceX <= 0.0) return null

        val slope = covariance / varianceX
        // A positive slope is a signal that got louder with distance, which is a room doing
        // something the model has no opinion about rather than an exponent.
        if (slope >= 0.0) return null

        val intercept = meanY - slope * meanX
        val residual = xs.indices.sumOf { index ->
            val predicted = intercept + slope * xs[index]
            val error = ys[index] - predicted
            error * error
        }
        val total = ys.sumOf { (it - meanY) * (it - meanY) }
        if (total > 0.0 && 1.0 - residual / total < MIN_FIT) return null

        return -slope / 10.0
    }

    /** How well the points have to lie on a line before the slope is worth reporting. */
    const val MIN_FIT = 0.5

    private fun List<Double>.medianOrNull(): Double? = when {
        isEmpty() -> null
        size % 2 == 1 -> this[size / 2]
        else -> (this[size / 2 - 1] + this[size / 2]) / 2.0
    }

    /**
     * Received level at one metre from a typical access point.
     *
     * A convention rather than a measurement, and it is the other half of why loudness is a
     * poor ruler: transmit power and antenna gain vary by several decibels between access
     * points, and this number pretends they do not.
     */
    const val REFERENCE_DBM = -40.0
}
