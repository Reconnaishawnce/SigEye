package com.sigeye.core.analysis.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class PolarisationTest {

    /**
     * A dipole response: strongest when aligned, weakest ninety degrees away.
     *
     * Power follows cos squared of the angle between the two antennas, with a floor set by
     * whatever arrives by reflection rather than directly.
     *
     * Readings go in without timestamps, so the sweep's not-moving gate stays out of the
     * way - that gate has its own tests, and every reading here is meant to be kept.
     */
    private fun dipoleSweep(
        peakDbm: Int = -45,
        nullDbm: Int = -70,
        alignedAt: Float = 0f,
        readingsPerBin: Int = 6,
        bins: Int = 12,
    ): PolarSweep {
        val sweep = PolarSweep(sectorCount = bins, minSamplesPerSector = 3)
        repeat(bins) { index ->
            val roll = index * (180f / bins) + (180f / bins) / 2f
            val offset = Math.toRadians((roll - alignedAt).toDouble())
            val fraction = cos(offset) * cos(offset)
            val rssi = (nullDbm + (peakDbm - nullDbm) * fraction).toInt()
            repeat(readingsPerBin) { sweep.add(Polarisation.plotAngle(roll), rssi) }
        }
        return sweep
    }

    /** Twelve bins of fifteen degrees of roll, with whatever signal each is given. */
    private fun rollSweep(rssiFor: (Int) -> Int): PolarSweep {
        val sweep = PolarSweep(sectorCount = 12, minSamplesPerSector = 3)
        repeat(12) { index ->
            val roll = index * 15f + 7.5f
            repeat(6) { sweep.add(Polarisation.plotAngle(roll), rssiFor(index)) }
        }
        return sweep
    }

    // ----------------------------------------------------------------- folding

    @Test
    fun `roll folds onto a half turn, because the response repeats`() {
        assertEquals(10f, Polarisation.fold(10f), 0.01f)
        assertEquals(10f, Polarisation.fold(190f), 0.01f)
        assertEquals(170f, Polarisation.fold(350f), 0.01f)
        assertEquals(170f, Polarisation.fold(-10f), 0.01f)
    }

    @Test
    fun `half a turn of roll is stretched across the whole of the sweep's circle`() {
        // Otherwise half of every plot is permanently empty and coverage never passes 50%.
        assertEquals(0f, Polarisation.plotAngle(0f), 0.01f)
        assertEquals(180f, Polarisation.plotAngle(90f), 0.01f)
        assertEquals(340f, Polarisation.plotAngle(170f), 0.01f)
        assertEquals(20f, Polarisation.plotAngle(190f), 0.01f)
    }

    @Test
    fun `folding doubles the readings in a bin, which is the point of it`() {
        val sweep = PolarSweep(sectorCount = 12, minSamplesPerSector = 3)
        listOf(30f, 210f).forEach { roll ->
            repeat(4) { sweep.add(Polarisation.plotAngle(roll), -60) }
        }
        // Both halves land in the same bin rather than two bins half empty.
        assertEquals(1, sweep.result(12).sectors.count { it.samples > 0 })
        assertEquals(8, sweep.result(12).totalSamples)
    }

    @Test
    fun `a full turn of the phone covers the whole response`() {
        val sweep = PolarSweep(sectorCount = 12, minSamplesPerSector = 3)
        var roll = 0f
        while (roll < 360f) {
            repeat(3) { sweep.add(Polarisation.plotAngle(roll), -60) }
            roll += 10f
        }
        assertEquals(1f, Polarisation.analyse(sweep).coverage, 0.001f)
    }

    // --------------------------------------------------------------- the nulls

    @Test
    fun `a dipole response is recognised as polarisation`() {
        val result = Polarisation.analyse(dipoleSweep())
        assertTrue(result.looksLikePolarisation)
        assertNotNull(result.depthDb)
        assertTrue("depth was ${result.depthDb}", result.depthDb!! >= 20.0)
        assertEquals(90f, result.separationDegrees!!, 25f)
        assertNull(result.verdict())
    }

    @Test
    fun `the strongest angle is where the antennas line up`() {
        val result = Polarisation.analyse(dipoleSweep(alignedAt = 60f))
        assertNotNull(result.bestRollDegrees)
        assertEquals(60f, result.bestRollDegrees!!, 25f)
    }

    @Test
    fun `every reported angle stays inside the half turn it was measured over`() {
        val result = Polarisation.analyse(dipoleSweep(alignedAt = 150f))
        assertTrue("best ${result.bestRollDegrees}", result.bestRollDegrees!! in 0f..180f)
        assertTrue("worst ${result.worstRollDegrees}", result.worstRollDegrees!! in 0f..180f)
    }

    @Test
    fun `a shallow null is refused rather than reported as polarisation`() {
        // Close to the source, reflections fill the null in and there is nothing to see.
        val result = Polarisation.analyse(dipoleSweep(peakDbm = -50, nullDbm = -53))
        assertTrue(!result.looksLikePolarisation)
        assertNotNull(result.verdict())
        assertTrue(result.verdict()!!.contains("reflections fill in the null"))
    }

    @Test
    fun `extremes that are not a right angle apart are called out as something else`() {
        // A hand over the antenna, or something moving mid-sweep: a hole thirty degrees
        // from the peak rather than the right angle a dipole actually puts it at.
        val result = Polarisation.analyse(
            rollSweep { index ->
                when (index) {
                    0 -> -45
                    2 -> -85
                    else -> -60
                }
            },
        )
        assertTrue(result.depthDb!! >= PolarisationResult.MEANINGFUL_DEPTH_DB)
        assertTrue("separation ${result.separationDegrees}", !result.looksLikePolarisation)
        assertTrue(result.verdict()!!.contains("degrees apart"))
    }

    @Test
    fun `half a turn is refused, because the response needs the whole of it`() {
        val sweep = PolarSweep(sectorCount = 12, minSamplesPerSector = 3)
        repeat(4) { index ->
            repeat(6) { sweep.add(Polarisation.plotAngle(index * 15f + 7.5f), -50) }
        }
        val result = Polarisation.analyse(sweep)
        assertNotNull(result.verdict())
        assertTrue(result.verdict()!!.contains("all the way over"))
    }

    // ------------------------------------------------- what the depth implies

    @Test
    fun `a deep null means most of the signal came by one path`() {
        // Twenty dB of null means only a hundredth of the power survived crossing, so
        // ninety-nine percent of it arrived directly.
        assertEquals(0.99, Polarisation.directPathFraction(20.0), 0.005)
        assertEquals(0.90, Polarisation.directPathFraction(10.0), 0.005)
    }

    @Test
    fun `no null means nothing arrived by a single path`() {
        assertEquals(0.0, Polarisation.directPathFraction(0.0), 0.001)
        assertEquals(0.0, Polarisation.directPathFraction(-3.0), 0.001)
    }

    @Test
    fun `the fraction never exceeds certainty`() {
        assertTrue(Polarisation.directPathFraction(120.0) <= 1.0)
    }

    @Test
    fun `separation is measured the short way round a half turn`() {
        // 172 and 7 are fifteen degrees apart on a circle that repeats every 180.
        val result = Polarisation.analyse(
            rollSweep { index ->
                when (index) {
                    11 -> -40
                    0 -> -80
                    else -> -60
                }
            },
        )
        assertTrue("separation ${result.separationDegrees}", result.separationDegrees!! < 45f)
    }

    @Test
    fun `an empty sweep says nothing rather than dividing by zero`() {
        val result = Polarisation.analyse(PolarSweep(sectorCount = 12))
        assertNull(result.depthDb)
        assertNull(result.bestRollDegrees)
        assertNotNull(result.verdict())
        assertTrue(result.describe().contains("No reading"))
    }
}
