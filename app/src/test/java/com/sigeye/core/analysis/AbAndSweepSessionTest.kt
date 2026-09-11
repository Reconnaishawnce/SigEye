package com.sigeye.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AbComparisonTest {

    private fun comparison(baseline: List<Double>, test: List<Double>): AbResult {
        val ab = AbComparison()
        ab.startBaseline()
        baseline.forEach { ab.record(it) }
        ab.startTest()
        test.forEach { ab.record(it) }
        return ab.result()
    }

    @Test
    fun `records land in the running phase and nowhere else`() {
        val ab = AbComparison()
        ab.record(99.0) // idle - discarded
        ab.startBaseline()
        repeat(3) { ab.record(10.0) }
        ab.startTest()
        repeat(2) { ab.record(5.0) }
        ab.stop()
        ab.record(99.0) // idle again - discarded
        assertEquals(3, ab.baselineSamples())
        assertEquals(2, ab.testSamples())
    }

    @Test
    fun `a big clean drop is called clearly`() {
        val result = comparison(
            baseline = List(20) { 40.0 + (it % 3) },
            test = List(20) { 12.0 + (it % 3) },
        )
        assertEquals(Significance.CLEAR, result.significance)
        assertTrue(result.delta < 0)
        assertEquals(-70.0, result.percentChange!!, 3.0)
    }

    @Test
    fun `two phases of the same thing show no difference`() {
        val result = comparison(
            baseline = listOf(30.0, 33.0, 28.0, 31.0, 29.0, 32.0, 30.0, 31.0),
            test = listOf(31.0, 29.0, 32.0, 30.0, 33.0, 28.0, 31.0, 30.0),
        )
        assertEquals(Significance.NONE, result.significance)
    }

    @Test
    fun `a difference swamped by its own noise is not called clear`() {
        // Means differ, but each phase wanders far more than the gap between them.
        val result = comparison(
            baseline = listOf(10.0, 90.0, 20.0, 80.0, 15.0, 85.0, 25.0, 75.0),
            test = listOf(5.0, 95.0, 30.0, 70.0, 10.0, 88.0, 22.0, 79.0),
        )
        assertTrue(result.significance != Significance.CLEAR)
    }

    @Test
    fun `too few samples is reported as such, not as no effect`() {
        val result = comparison(baseline = listOf(40.0, 41.0), test = listOf(10.0, 11.0))
        assertEquals(Significance.INSUFFICIENT, result.significance)
    }

    @Test
    fun `percent change is withheld when the baseline is zero`() {
        val result = comparison(baseline = List(8) { 0.0 }, test = List(8) { 5.0 })
        assertNull(result.percentChange)
    }

    @Test
    fun `stats describe the phase they came from`() {
        val stats = PhaseStats.of(listOf(10.0, 20.0, 30.0))
        assertEquals(3, stats.samples)
        assertEquals(20.0, stats.mean, 0.001)
        assertEquals(10.0, stats.standardDeviation, 0.001)
        assertEquals(10.0, stats.min, 0.001)
        assertEquals(30.0, stats.max, 0.001)
    }

    @Test
    fun `a single sample has no spread rather than an undefined one`() {
        val stats = PhaseStats.of(listOf(7.0))
        assertEquals(0.0, stats.standardDeviation, 0.001)
        assertTrue(stats.standardError.isNaN())
    }
}

class SweepSessionTest {

    /** A sweep with a notch at [notchDegrees], everything else flat. */
    private fun sweepWithNotch(notchDegrees: Int, depth: Int = 20): SweepResult {
        val polar = PolarSweep(sectorCount = 24, minSamplesPerSector = 3)
        (0 until 360 step 15).forEach { heading ->
            val rssi = if (abs(heading - notchDegrees) < 8) -55 - depth else -55
            repeat(4) { polar.add(heading.toFloat() + 7f, rssi) }
        }
        return polar.result()
    }

    @Test
    fun `one sweep cannot tell you anything about agreement`() {
        val session = SweepSession()
        session.add(sweepWithNotch(180))
        val result = session.result()
        assertEquals(SweepAgreement.UNKNOWN, result.agreement)
        assertNull(result.notchSpreadDegrees)
    }

    @Test
    fun `sweeps that agree are called consistent`() {
        val session = SweepSession()
        session.add(sweepWithNotch(180))
        session.add(sweepWithNotch(180))
        session.add(sweepWithNotch(195))
        val result = session.result()
        assertEquals(SweepAgreement.CONSISTENT, result.agreement)
        assertEquals(3, result.runCount)
    }

    @Test
    fun `sweeps that disagree are called scattered`() {
        val session = SweepSession()
        session.add(sweepWithNotch(0))
        session.add(sweepWithNotch(120))
        session.add(sweepWithNotch(240))
        assertEquals(SweepAgreement.SCATTERED, session.result().agreement)
    }

    @Test
    fun `agreement across north is measured the short way round`() {
        // 350 and 10 are twenty degrees apart, not three hundred and forty.
        val spread = SweepSession.circularSpread(listOf(350f, 10f, 0f))
        assertNotNull(spread)
        assertTrue("spread was $spread", spread!! < 30f)
    }

    @Test
    fun `combining runs averages sector by sector`() {
        val session = SweepSession()
        session.add(sweepWithNotch(180, depth = 20))
        session.add(sweepWithNotch(180, depth = 10))
        val combined = session.result().combined
        // The notch survives, at a depth between the two runs.
        assertEquals(12, combined.notch!!.index)
        val difference = combined.frontToBackDb!!
        assertTrue("depth was $difference", difference in 10.0..20.0)
    }

    @Test
    fun `an empty session produces an empty result rather than throwing`() {
        val result = SweepSession().result()
        assertEquals(0, result.runCount)
        assertNull(result.combined.peak)
        assertEquals(SweepAgreement.UNKNOWN, result.agreement)
    }

    @Test
    fun `clear resets the session`() {
        val session = SweepSession()
        session.add(sweepWithNotch(90))
        session.add(sweepWithNotch(90))
        session.clear()
        assertEquals(0, session.count())
        assertEquals(SweepAgreement.UNKNOWN, session.result().agreement)
    }

    @Test
    fun `runs at different resolutions combine by bearing, not by sector number`() {
        // A fast source binned finely and a slow one binned coarsely, both with the same
        // hole due south. Lining these up by sector index would average 135 degrees with
        // 52 and drop two thirds of the coarse run.
        fun run(sectors: Int, packets: Int): SweepResult {
            val sweep = PolarSweep(sectorCount = sectors, minSamplesPerSector = 3)
            repeat(packets) { index ->
                val heading = index * 360f / packets
                val rssi = if (kotlin.math.abs(heading - 180f) < 30f) -85 else -60
                sweep.add(heading, rssi, index * 100L)
            }
            return sweep.result(sectors)
        }

        val session = SweepSession()
        session.add(run(24, 240))
        session.add(run(8, 40))
        val combined = session.result().combined

        // No finer than the coarsest run that went into it.
        assertEquals(8, combined.totalSectors)
        // Every reading is still accounted for.
        assertEquals(280, combined.totalSamples)
        // And the hole is still due south rather than smeared somewhere else.
        val notch = combined.notchBearingDegrees
        assertNotNull(notch)
        assertEquals(180f, notch!!, 30f)
    }
}
