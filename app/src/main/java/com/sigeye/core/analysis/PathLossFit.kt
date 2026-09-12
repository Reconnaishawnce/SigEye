package com.sigeye.core.analysis

import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** One reading, paired with how far the walker had gone when it arrived. */
data class WalkSample(val metres: Double, val rssi: Int)

enum class FitQuality(val label: String) {
    GOOD("Clean fit"),
    ROUGH("Rough fit"),
    REJECTED("Not usable"),
}

/**
 * The result of walking away from something and watching it fade.
 *
 * [exponent] is the number this exists to produce. It is the `n` in the log-distance model
 * every proximity feature in the world quietly assumes, and it is normally guessed - two
 * for free space, three-ish for indoors, four for something unkind. Measuring it turns
 * every distance estimate in this app from a guess into a calibrated one.
 */
data class PathLossResult(
    val exponent: Double,
    val referenceRssi: Double,
    val samples: Int,
    val rSquared: Double,
    val residualDb: Double,
    val spanMetres: Double,
    val quality: FitQuality,
    val reason: String?,
) {
    /** What this fit says about the environment, in words. */
    fun character(): String = when {
        exponent < 1.6 -> "Lower than free space. That happens in a corridor, which guides " +
            "the wave along it rather than letting it spread - a waveguide, more or less."
        exponent < 2.4 -> "About free space. A clear line of sight with little around to " +
            "absorb or reflect."
        exponent < 3.2 -> "Typical indoors. Furniture, people and a wall or two."
        exponent < 4.2 -> "Heavily obstructed. Several walls, or a floor."
        else -> "Very heavily obstructed. Signal is falling away far faster than distance " +
            "alone would explain."
    }

    /** Distance a given reading implies, under this fit rather than a guess. */
    fun metresFor(rssi: Int): Double =
        10.0.pow((referenceRssi - rssi) / (10.0 * exponent))
}

/**
 * Fitting the log-distance path loss model to a walk.
 *
 * The model is `rssi = reference - 10 n log10(d)`. Plot RSSI against `log10(distance)` and
 * it is a straight line whose slope is `-10n`, so a least-squares fit over a walk recovers
 * both the exponent and the one-metre reference at once.
 *
 * The distance has to be real, which is the entire difficulty and the reason this
 * experiment needs a step counter. Everything else in the app that talks about distance is
 * inverting this model with a *guessed* exponent; this is the one that measures it.
 *
 * Pure and Android-free.
 */
object PathLossFit {

    /** Below this a walk has not covered enough ground for the slope to mean anything. */
    const val MIN_SPAN_METRES = 4.0

    const val MIN_SAMPLES = 12

    /** Readings closer than this are in the near field, where the model does not hold. */
    const val NEAR_FIELD_METRES = 0.5

    /**
     * The walk as it was recorded, plus the fit that came out of it.
     *
     * Distance and signal per reading, unaggregated. The fit is one line through these
     * points and a reader with the points can put a different line through them - which is
     * the whole reason to hand over raw readings rather than a conclusion.
     */
    fun csv(samples: List<WalkSample>, result: PathLossResult, strideMetres: Double): String =
        buildString {
            appendLine("# stride_m=$strideMetres readings=${samples.size}")
            appendLine(
                "# exponent=" + String.format(Locale.US, "%.3f", result.exponent) +
                    " reference_dbm=" + String.format(Locale.US, "%.1f", result.referenceRssi) +
                    " r_squared=" + String.format(Locale.US, "%.3f", result.rSquared) +
                    " residual_db=" + String.format(Locale.US, "%.2f", result.residualDb) +
                    " span_m=" + String.format(Locale.US, "%.1f", result.spanMetres) +
                    " quality=" + result.quality.name,
            )
            appendLine("metres,rssi_dbm,log10_metres,modelled_dbm")
            samples.forEach { sample ->
                val modelled = result.referenceRssi -
                    10.0 * result.exponent * log10(sample.metres.coerceAtLeast(0.01))
                appendLine(
                    String.format(
                        Locale.US,
                        "%.3f,%d,%.4f,%.2f",
                        sample.metres,
                        sample.rssi,
                        log10(sample.metres.coerceAtLeast(0.01)),
                        modelled,
                    ),
                )
            }
        }

    fun fit(samples: List<WalkSample>): PathLossResult {
        val usable = samples.filter { it.metres >= NEAR_FIELD_METRES }
        if (usable.size < MIN_SAMPLES) {
            return rejected(
                usable.size,
                "Only ${usable.size} readings past half a metre. Walk further, or pick " +
                    "something that advertises more often.",
            )
        }

        val span = (usable.maxOf { it.metres } - usable.minOf { it.metres })
        if (span < MIN_SPAN_METRES) {
            return rejected(
                usable.size,
                String.format(
                    java.util.Locale.US,
                    "You covered %.1f m. The slope of a line needs the line to go " +
                        "somewhere - walk at least a few metres further.",
                    span,
                ),
            )
        }

        // Least squares of rssi against log10(distance). The slope is -10n.
        val xs = usable.map { log10(it.metres) }
        val ys = usable.map { it.rssi.toDouble() }
        val meanX = xs.average()
        val meanY = ys.average()
        var covariance = 0.0
        var varianceX = 0.0
        xs.indices.forEach { index ->
            val dx = xs[index] - meanX
            covariance += dx * (ys[index] - meanY)
            varianceX += dx * dx
        }
        if (varianceX <= 0.0) {
            return rejected(usable.size, "Every reading came from the same distance.")
        }

        val slope = covariance / varianceX
        val intercept = meanY - slope * meanX
        val exponent = -slope / 10.0

        // How much of the variation the line actually explains, and how far the readings
        // sit from it - the second being the more honest of the two for a reader.
        val predicted = xs.map { intercept + slope * it }
        val ssResidual = ys.indices.sumOf { (ys[it] - predicted[it]).pow(2) }
        val ssTotal = ys.sumOf { (it - meanY).pow(2) }
        val rSquared = if (ssTotal <= 0.0) 0.0 else (1.0 - ssResidual / ssTotal)
        val residual = sqrt(ssResidual / ys.size)

        // A negative exponent means the signal grew as the walker left, which is not a
        // path loss measurement of anything - it is somebody walking the wrong way, or a
        // reflection dominating the whole walk.
        if (exponent <= 0.2) {
            return PathLossResult(
                exponent = exponent,
                referenceRssi = intercept,
                samples = usable.size,
                rSquared = rSquared,
                residualDb = residual,
                spanMetres = span,
                quality = FitQuality.REJECTED,
                reason = "The signal did not fall as you walked away. Either the walk went " +
                    "towards the device rather than away from it, or a reflection was " +
                    "louder than the direct path the whole time.",
            )
        }

        val rough = rSquared < 0.5 || residual > 8.0
        return PathLossResult(
            exponent = exponent,
            referenceRssi = intercept,
            samples = usable.size,
            rSquared = rSquared,
            residualDb = residual,
            spanMetres = span,
            quality = if (rough) FitQuality.ROUGH else FitQuality.GOOD,
            reason = if (rough) {
                String.format(
                    java.util.Locale.US,
                    "The readings scatter %.1f dB about the line, so treat the exponent " +
                        "as an indication rather than a figure. Multipath does this, and " +
                        "a slower walk with more readings helps.",
                    residual,
                )
            } else {
                null
            },
        )
    }

    private fun rejected(samples: Int, reason: String) = PathLossResult(
        exponent = 0.0,
        referenceRssi = 0.0,
        samples = samples,
        rSquared = 0.0,
        residualDb = 0.0,
        spanMetres = 0.0,
        quality = FitQuality.REJECTED,
        reason = reason,
    )

    /**
     * Stride length from height, for phones whose step counter gives steps and not metres.
     *
     * The 0.415 ratio is the standard anthropometric estimate for walking. It is wrong for
     * any individual by a few percent, which propagates straight into the distance and
     * therefore into the exponent - so the screen offers a measured stride instead and
     * says why that is better.
     */
    fun strideFromHeight(heightMetres: Double): Double = heightMetres * 0.415

    /** Difference two exponents make to a distance estimate at a given reading. */
    fun disagreementMetres(
        rssi: Int,
        referenceRssi: Double,
        assumed: Double,
        measured: Double,
    ): Pair<Double, Double> {
        val a = 10.0.pow((referenceRssi - rssi) / (10.0 * assumed))
        val b = 10.0.pow((referenceRssi - rssi) / (10.0 * measured))
        return a to b
    }

    fun exponentsDiffer(a: Double, b: Double): Boolean = abs(a - b) >= 0.3
}
