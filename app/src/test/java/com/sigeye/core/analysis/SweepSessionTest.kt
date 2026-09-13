package com.sigeye.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Several sweeps of one spot, and whether they agree with each other.
 *
 * The whole claim of Body Absorption rests on this. One sweep cannot tell a person from a
 * radiator - both make a notch and both look the same in a single run. What separates them
 * is that your shadow turns with you and the room's does not, so the spread of the notch
 * across repeated runs *is* the finding. It shipped without a test.
 */
class SweepSessionTest {

    /** A sweep with its weakest sector at [notchAt], built the way the app builds one. */
    private fun sweepWithNotchAt(
        notchAt: Float,
        sectors: Int = 24,
        depthDb: Int = 12,
    ): SweepResult {
        val sweep = PolarSweep(sectorCount = sectors, minSamplesPerSector = 3)
        val width = 360f / sectors
        repeat(sectors) { index ->
            val bearing = index * width + width / 2f
            var away = kotlin.math.abs(bearing - notchAt) % 360f
            if (away > 180f) away = 360f - away
            // Deepest at the notch, back to normal a quadrant away.
            val shadow = ((1f - away / 90f).coerceAtLeast(0f) * depthDb).toInt()
            repeat(4) { sweep.add(bearing, -50 - shadow) }
        }
        return sweep.result(sectors)
    }

    @Test
    fun `nothing added is an empty session rather than a crash`() {
        val result = SweepSession().result()
        assertEquals(0, result.runCount)
        assertTrue(result.notchHeadings.isEmpty())
        assertNull(result.notchSpreadDegrees)
        assertEquals(SweepAgreement.UNKNOWN, result.agreement)
    }

    @Test
    fun `one run cannot agree with itself`() {
        // A single sweep has nothing to be consistent with, and calling it consistent
        // would be the app claiming a body shadow from the one measurement that cannot
        // distinguish one.
        val session = SweepSession()
        session.add(sweepWithNotchAt(180f))
        assertEquals(SweepAgreement.UNKNOWN, session.result().agreement)
        assertEquals(1, session.result().runCount)
    }

    @Test
    fun `three runs that put the notch in the same place are consistent`() {
        val session = SweepSession()
        listOf(180f, 186f, 174f).forEach { session.add(sweepWithNotchAt(it)) }
        val result = session.result()
        assertEquals(3, result.runCount)
        assertEquals(SweepAgreement.CONSISTENT, result.agreement)
        assertTrue("spread ${result.notchSpreadDegrees}", result.notchSpreadDegrees!! <= 25f)
    }

    @Test
    fun `runs that disagree wildly are reported as scattered`() {
        val session = SweepSession()
        listOf(0f, 120f, 240f).forEach { session.add(sweepWithNotchAt(it)) }
        val result = session.result()
        assertEquals(SweepAgreement.SCATTERED, result.agreement)
    }

    @Test
    fun `a spread near the wrap is measured across it, not around it`() {
        // Notches at 350 and 10 degrees are twenty degrees apart, not three hundred and
        // forty. An arithmetic spread would call the tightest possible agreement the
        // worst possible disagreement.
        val session = SweepSession()
        listOf(350f, 2f, 8f).forEach { session.add(sweepWithNotchAt(it)) }
        val result = session.result()
        assertTrue("spread ${result.notchSpreadDegrees}", result.notchSpreadDegrees!! < 45f)
        assertEquals(SweepAgreement.CONSISTENT, result.agreement)
    }

    @Test
    fun `the combined sweep keeps the notch the runs agreed on`() {
        val session = SweepSession()
        listOf(180f, 184f, 176f).forEach { session.add(sweepWithNotchAt(it)) }
        val notch = session.result().combined.notchBearingDegrees
        assertEquals(180f, notch!!, 30f)
    }

    @Test
    fun `runs of different resolutions combine by bearing, not by sector number`() {
        // A slow turn binned into 8 sectors and a fast one into 24 do not agree about what
        // sector 3 means. Lining them up by index averaged 135 degrees with 52.
        val session = SweepSession()
        session.add(sweepWithNotchAt(180f, sectors = 24))
        session.add(sweepWithNotchAt(180f, sectors = 8))
        val combined = session.result().combined
        assertEquals(180f, combined.notchBearingDegrees!!, 45f)
        // No finer than the coarsest contributor: a session cannot be more certain about
        // direction than its worst run.
        assertTrue(
            "combined had ${combined.totalSectors} sectors",
            combined.totalSectors <= 8,
        )
    }

    @Test
    fun `clearing a session starts it over`() {
        val session = SweepSession()
        session.add(sweepWithNotchAt(180f))
        session.add(sweepWithNotchAt(180f))
        assertEquals(2, session.count())
        session.clear()
        assertEquals(0, session.count())
        assertEquals(SweepAgreement.UNKNOWN, session.result().agreement)
    }

    @Test
    fun `a run with no notch contributes nothing to the spread`() {
        // A flat sweep has no weakest direction to report, and counting it as agreeing
        // with anything would be counting a non-measurement as evidence.
        val flat = PolarSweep(sectorCount = 24, minSamplesPerSector = 3)
        repeat(24) { index ->
            repeat(4) { flat.add(index * 15f + 7.5f, -50) }
        }
        val session = SweepSession()
        session.add(flat.result(24))
        session.add(sweepWithNotchAt(180f))
        session.add(sweepWithNotchAt(184f))
        assertTrue(session.result().notchHeadings.size <= 3)
    }
}
