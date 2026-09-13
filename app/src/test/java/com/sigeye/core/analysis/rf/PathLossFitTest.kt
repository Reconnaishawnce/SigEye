package com.sigeye.core.analysis.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class PathLossFitTest {

    /**
     * A walk away from a transmitter obeying the model exactly.
     *
     * `rssi = reference - 10 n log10(d)`, which is the thing the fit is supposed to invert.
     */
    private fun walk(
        exponent: Double,
        reference: Double = -40.0,
        from: Double = 1.0,
        to: Double = 20.0,
        steps: Int = 40,
        noise: (Int) -> Int = { 0 },
    ): List<WalkSample> = (0 until steps).map { index ->
        val meters = from + (to - from) * index / (steps - 1).toDouble()
        val rssi = reference - 10.0 * exponent * log10(meters)
        WalkSample(meters, rssi.toInt() + noise(index))
    }

    // ------------------------------------------------------------ recovering n

    @Test
    fun `recovers free space from a clean walk`() {
        val fit = PathLossFit.fit(walk(exponent = 2.0))
        assertEquals(FitQuality.GOOD, fit.quality)
        assertEquals(2.0, fit.exponent, 0.15)
        assertEquals(-40.0, fit.referenceRssi, 2.0)
        assertTrue(fit.character().contains("free space"))
    }

    @Test
    fun `recovers a heavily obstructed environment`() {
        val fit = PathLossFit.fit(walk(exponent = 4.0))
        assertEquals(4.0, fit.exponent, 0.2)
        assertTrue(fit.character().contains("obstructed"))
    }

    @Test
    fun `recovers a corridor, which beats free space`() {
        // A corridor guides the wave rather than letting it spread, so n falls below two.
        val fit = PathLossFit.fit(walk(exponent = 1.4))
        assertEquals(1.4, fit.exponent, 0.2)
        assertTrue(fit.character().contains("corridor"))
    }

    @Test
    fun `survives realistic packet noise`() {
        val fit = PathLossFit.fit(
            walk(exponent = 3.0, noise = { index -> (index * 7 % 7) - 3 }),
        )
        assertTrue(fit.quality != FitQuality.REJECTED)
        assertEquals(3.0, fit.exponent, 0.4)
    }

    @Test
    fun `heavy scatter is kept but flagged as rough`() {
        val fit = PathLossFit.fit(
            walk(exponent = 2.5, noise = { index -> if (index % 2 == 0) 14 else -14 }),
        )
        assertEquals(FitQuality.ROUGH, fit.quality)
        assertNotNull(fit.reason)
        assertTrue(fit.reason!!.contains("scatter"))
    }

    // --------------------------------------------------------------- rejection

    @Test
    fun `a walk that went nowhere is refused`() {
        val samples = (0 until 30).map { WalkSample(2.0, -60) }
        val fit = PathLossFit.fit(samples)
        assertEquals(FitQuality.REJECTED, fit.quality)
        assertNotNull(fit.reason)
    }

    @Test
    fun `too short a walk is refused rather than extrapolated`() {
        val fit = PathLossFit.fit(walk(exponent = 2.0, from = 1.0, to = 3.0))
        assertEquals(FitQuality.REJECTED, fit.quality)
        assertTrue(fit.reason!!.contains("walk at least a few meters further"))
    }

    @Test
    fun `too few readings is refused`() {
        val fit = PathLossFit.fit(walk(exponent = 2.0, steps = 6))
        assertEquals(FitQuality.REJECTED, fit.quality)
        assertTrue(fit.reason!!.contains("readings past half a meter"))
    }

    @Test
    fun `walking the wrong way is named rather than reported as a negative exponent`() {
        // Signal rising with distance is not a measurement of anything.
        val samples = walk(exponent = 2.0).map { it.copy(rssi = -100 + it.meters.toInt() * 2) }
        val fit = PathLossFit.fit(samples)
        assertEquals(FitQuality.REJECTED, fit.quality)
        assertTrue(fit.reason!!.contains("did not fall as you walked away"))
    }

    @Test
    fun `the near field is excluded, because the model does not hold there`() {
        val close = (0 until 20).map { WalkSample(0.1, -30) }
        val fit = PathLossFit.fit(close + walk(exponent = 2.0))
        // The close readings are dropped rather than dragging the line.
        assertEquals(40, fit.samples)
        assertEquals(2.0, fit.exponent, 0.2)
    }

    // ---------------------------------------------------- what it is good for

    @Test
    fun `a measured exponent gives a different distance from an assumed one`() {
        // The payoff: every proximity feature guesses this number, and guessing wrong
        // scales the answer.
        val fit = PathLossFit.fit(walk(exponent = 3.5))
        val (assumed, measured) = PathLossFit.disagreementMetres(
            rssi = -80,
            referenceRssi = fit.referenceRssi,
            assumed = 2.0,
            measured = fit.exponent,
        )
        assertTrue("assumed $assumed measured $measured", assumed > measured * 2)
    }

    @Test
    fun `the fit can be inverted back to a distance`() {
        val fit = PathLossFit.fit(walk(exponent = 2.0, reference = -40.0))
        // -60 dBm is 20 dB down, which at n=2 is a factor of ten in distance.
        assertEquals(10.0, fit.metersFor(-60), 1.5)
    }

    @Test
    fun `stride from height uses the standard ratio`() {
        assertEquals(0.7055, PathLossFit.strideFromHeight(1.70), 0.001)
    }

    @Test
    fun `exponents close together are not treated as disagreeing`() {
        assertTrue(!PathLossFit.exponentsDiffer(2.0, 2.1))
        assertTrue(PathLossFit.exponentsDiffer(2.0, 3.0))
    }

    @Test
    fun `a clean fit has nothing to complain about`() {
        assertNull(PathLossFit.fit(walk(exponent = 2.0)).reason)
    }
}
