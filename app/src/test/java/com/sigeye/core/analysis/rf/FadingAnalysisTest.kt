package com.sigeye.core.analysis.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class FadingAnalysisTest {

    /**
     * Builds a Rician-fading record with a known K.
     *
     * One steady path of amplitude `A` plus a complex Gaussian cloud of scattered ones,
     * which is the textbook model and the thing the estimator claims to invert. Setting the
     * cloud's variance to one puts `A = sqrt(2K)`.
     */
    private fun ricianRecord(
        kLinear: Double,
        count: Int = 2000,
        meanDbm: Double = -60.0,
        seed: Int = 7,
    ): List<FadeSample> {
        val random = Random(seed)
        val amplitude = sqrt(2.0 * kLinear)
        // Mean power of the model, so the record can be scaled to a realistic level.
        val modelMean = amplitude * amplitude + 2.0
        val scale = Math.pow(10.0, meanDbm / 10.0) / modelMean

        fun gaussianPair(): Pair<Double, Double> {
            val u1 = random.nextDouble().coerceAtLeast(1e-12)
            val u2 = random.nextDouble()
            val radius = sqrt(-2.0 * ln(u1))
            return radius * cos(2.0 * Math.PI * u2) to radius * sin(2.0 * Math.PI * u2)
        }

        return (0 until count).map { index ->
            val (real, imaginary) = gaussianPair()
            val i = amplitude + real
            val q = imaginary
            val power = (i * i + q * q) * scale
            // Radios report whole dBm and stop hearing anything below their noise floor,
            // so the model has to do both - an unclamped deep fade is minus infinity.
            val dbm = (10.0 * log10(power.coerceAtLeast(1e-30))).coerceAtLeast(-100.0)
            FadeSample(index * 100L, dbm.roundToInt())
        }
    }

    // --------------------------------------------------------- the K estimator

    @Test
    fun `recovers a strong dominant path`() {
        // K = 20 linear, about 13 dB: a clear line of sight with echoes around it.
        val stats = FadingAnalysis.analyze(ricianRecord(kLinear = 20.0))
        val kDb = stats.ricianKDb!!
        assertEquals(13.0, kDb, 3.0)
        assertTrue(
            "expected a steady character, got ${stats.character}",
            stats.character == FadingCharacter.STEADY,
        )
    }

    @Test
    fun `recovers a middling one`() {
        val stats = FadingAnalysis.analyze(ricianRecord(kLinear = 3.0))
        assertEquals(10.0 * log10(3.0), stats.ricianKDb!!, 3.0)
    }

    @Test
    fun `pure Rayleigh has no dominant path and says so`() {
        // K = 0: everything arriving has bounced.
        val stats = FadingAnalysis.analyze(ricianRecord(kLinear = 0.0))
        assertEquals(0.0, stats.ricianK!!, 0.6)
        assertEquals(FadingCharacter.SCATTERED, stats.character)
        // And it swings hard - this is the number that makes the point.
        assertTrue("only ${stats.rangeDb} dB of swing", stats.rangeDb > 10)
    }

    @Test
    fun `a record noisier than Rayleigh is clamped rather than extrapolated`() {
        // Something was moving, so the model does not apply. A negative K would be worse
        // than useless - it would be printed.
        // Long quiet stretches broken by occasional spikes: more spread than an
        // exponential, which is what "noisier than Rayleigh" means in practice.
        val wild = (0 until 200).map {
            FadeSample(it * 100L, if (it % 20 == 0) -40 else -90)
        }
        assertEquals(0.0, FadingAnalysis.ricianK(wild.map { it.rssi })!!, 1e-9)
    }

    @Test
    fun `a dead flat signal is infinitely dominated by one path`() {
        val flat = (0 until 100).map { FadeSample(it * 100L, -55) }
        val stats = FadingAnalysis.analyze(flat)
        assertEquals(0.0, stats.sdDb, 1e-9)
        assertEquals(0, stats.rangeDb)
        assertEquals(FadingCharacter.STEADY, stats.character)
        // No fading means no distance error from fading.
        assertEquals(1.0, stats.distanceErrorFactor(2.0), 1e-9)
    }

    @Test
    fun `too few readings is refused rather than estimated`() {
        val few = (0 until 5).map { FadeSample(it * 100L, -60) }
        val stats = FadingAnalysis.analyze(few)
        assertEquals(FadingCharacter.TOO_FEW, stats.character)
        assertNull(stats.ricianK)
        assertNull(FadingAnalysis.ricianK(listOf(-60, -61, -59)))
    }

    // ------------------------------------------------------------- descriptives

    @Test
    fun `reports the spread the way a reader needs it`() {
        val samples = listOf(-70, -66, -64, -63, -62, -61, -60, -59, -58, -50)
            .flatMap { rssi -> List(3) { rssi } }
            .mapIndexed { index, rssi -> FadeSample(index * 100L, rssi) }
        val stats = FadingAnalysis.analyze(samples)

        assertEquals(30, stats.samples)
        assertEquals(-50, stats.maxDbm)
        assertEquals(-70, stats.minDbm)
        assertEquals(20, stats.rangeDb)
        assertTrue(stats.p10Dbm < stats.meanDbm)
        assertTrue(stats.p90Dbm > stats.meanDbm)
        assertTrue("fade depth should be positive", stats.fadeDepthDb > 0)
    }

    @Test
    fun `packet rate comes from the span, not from the count`() {
        val samples = (0 until 50).map { FadeSample(it * 200L, -60) }
        // Fifty samples across 9.8 s of span.
        assertEquals(50 / 9.8, FadingAnalysis.analyze(samples).packetsPerSecond, 0.05)
    }

    @Test
    fun `a single sample does not divide by zero`() {
        val stats = FadingAnalysis.analyze(listOf(FadeSample(0L, -60)))
        assertEquals(0.0, stats.spanSeconds, 1e-9)
        assertEquals(0.0, stats.packetsPerSecond, 1e-9)
        assertEquals(FadingCharacter.TOO_FEW, stats.character)
    }

    // ------------------------------------------------- what it means for range

    @Test
    fun `six dB of fading doubles the distance you might be at`() {
        val samples = (0 until 100).map {
            // Half at +6 dB and half at -6 dB about the mean gives exactly 6 dB of SD.
            FadeSample(it * 100L, if (it % 2 == 0) -54 else -66)
        }
        val stats = FadingAnalysis.analyze(samples)
        assertEquals(6.0, stats.sdDb, 1e-9)
        assertEquals(2.0, stats.distanceErrorFactor(2.0), 0.01)

        val range = stats.distanceRange(meters = 10.0, pathLossExponent = 2.0)
        assertEquals(5.0, range.start, 0.05)
        assertEquals(20.0, range.endInclusive, 0.2)
    }

    @Test
    fun `a higher path loss exponent absorbs the same fading into less distance`() {
        val samples = (0 until 100).map {
            FadeSample(it * 100L, if (it % 2 == 0) -54 else -66)
        }
        val stats = FadingAnalysis.analyze(samples)
        assertTrue(stats.distanceErrorFactor(4.0) < stats.distanceErrorFactor(2.0))
        assertEquals(sqrt(2.0), stats.distanceErrorFactor(4.0), 0.01)
    }
}
