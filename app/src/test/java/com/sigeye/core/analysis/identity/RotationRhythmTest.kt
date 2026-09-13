package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationRhythmTest {

    /** Address changes for a device on [periodMs], starting at [startMs], with slop. */
    private fun changes(
        periodMs: Long,
        count: Int,
        startMs: Long = 1_700_000_000_000L,
        slop: List<Long> = emptyList(),
    ): List<Long> = (0 until count).map { index ->
        startMs + index * periodMs + (slop.getOrNull(index) ?: 0L)
    }

    // ------------------------------------------------------------- the period

    @Test
    fun `a device on the specification default measures as fifteen minutes`() {
        val rhythm = RotationRhythm.analyze(changes(900_000L, 5))
        assertEquals(900_000L, rhythm.medianPeriodMs)
        assertTrue(rhythm.matchesSpecDefault)
        assertTrue(rhythm.describePeriod().contains("15.0 minutes"))
        assertEquals(
            "the specification's default, 900 seconds",
            RotationRhythm.familiarName(rhythm.medianPeriodMs),
        )
    }

    @Test
    fun `a device on something other than the default is not rounded to it`() {
        // The timeout is settable anywhere from a second to an hour. Reporting everything
        // as fifteen minutes would erase the only interesting thing about this device.
        val rhythm = RotationRhythm.analyze(changes(300_000L, 5))
        assertEquals(300_000L, rhythm.medianPeriodMs)
        assertTrue(!rhythm.matchesSpecDefault)
        assertEquals("five minutes", RotationRhythm.familiarName(rhythm.medianPeriodMs))
    }

    @Test
    fun `an unfamiliar period is left unnamed rather than forced onto a round number`() {
        assertNull(RotationRhythm.familiarName(430_000L))
    }

    @Test
    fun `one address change is not a period`() {
        val rhythm = RotationRhythm.analyze(listOf(1_000L))
        assertTrue(!rhythm.measurable)
        assertNull(rhythm.medianPeriodMs)
        assertNull(rhythm.phaseMs)
        assertEquals("not measured yet", rhythm.describePeriod())
    }

    @Test
    fun `nothing at all measures nothing`() {
        val rhythm = RotationRhythm.analyze(emptyList())
        assertTrue(!rhythm.measurable)
        assertEquals(0, rhythm.changes)
    }

    @Test
    fun `a period outside what the specification allows is refused`() {
        assertTrue(RotationRhythm.withinSpec(900_000L))
        assertTrue(!RotationRhythm.withinSpec(7_200_000L))
        assertTrue(!RotationRhythm.withinSpec(200L))
        assertTrue(!RotationRhythm.withinSpec(null))
    }

    @Test
    fun `a ragged timer is not called regular`() {
        val ragged = RotationRhythm.analyze(
            listOf(0L, 900_000L, 1_200_000L, 3_000_000L, 3_100_000L),
        )
        assertTrue(!ragged.regular)
        assertTrue(!ragged.phaseUsable)
    }

    // -------------------------------------------------------------- the phase

    @Test
    fun `a free running timer keeps the same phase through every rotation`() {
        // This is the whole point: the offset survives the thing that was meant to
        // destroy continuity.
        val rhythm = RotationRhythm.analyze(changes(900_000L, 6, startMs = 1_700_000_123_456L))
        assertTrue(rhythm.phaseUsable)
        assertEquals(1_700_000_123_456L % 900_000L, rhythm.phaseMs)
        assertEquals(0L, rhythm.phaseSpreadMs)
    }

    @Test
    fun `two phones on the same period land on different phases`() {
        val one = RotationRhythm.analyze(changes(900_000L, 5, startMs = 1_700_000_000_000L))
        val other = RotationRhythm.analyze(changes(900_000L, 5, startMs = 1_700_000_400_000L))
        assertEquals(one.medianPeriodMs, other.medianPeriodMs)
        assertTrue(one.phaseMs != other.phaseMs)
    }

    @Test
    fun `two changes define a phase, so nothing is claimed from two`() {
        val rhythm = RotationRhythm.analyze(changes(900_000L, 2))
        assertTrue(rhythm.measurable)
        assertTrue("changes were ${rhythm.changes}", !rhythm.phaseUsable)
    }

    @Test
    fun `a little slop still counts as the same phase`() {
        // A change is only seen to the nearest advertising interval, and only after the
        // old address has been silent a while. Seconds of slop is measurement, not drift.
        val rhythm = RotationRhythm.analyze(
            changes(900_000L, 5, slop = listOf(0L, 4_000L, -3_000L, 8_000L, -6_000L)),
        )
        assertTrue(rhythm.phaseUsable)
        assertTrue("spread ${rhythm.phaseSpreadMs}", rhythm.phaseSpreadMs!! <= 45_000L)
    }

    @Test
    fun `a phase near the wrap is measured across it, not around it`() {
        // Half a second into the cycle and half a second before the end of it are one
        // second apart, not fifteen minutes. These four changes sit at 500, 500, 899_500
        // and 500 - an arithmetic mean would put the phase at 225_250, on the far side of
        // the cycle from every reading it was built from.
        val start = 1_699_999_200_500L
        val rhythm = RotationRhythm.analyze(
            listOf(start, start + 900_000L, start + 1_799_000L, start + 2_700_000L),
        )
        assertEquals(900_000L, rhythm.medianPeriodMs)
        assertTrue("spread ${rhythm.phaseSpreadMs}", rhythm.phaseSpreadMs!! <= 10_000L)
        assertTrue(rhythm.phaseUsable)
    }

    @Test
    fun `what a phase is worth is expressed as one in how many`() {
        // Fifteen minutes known to forty-five seconds is one slot in twenty: useful
        // alongside other evidence, useless alone, and the number says which.
        assertEquals(20, RotationRhythm.phaseSlots(900_000L))
        assertEquals(1, RotationRhythm.phaseSlots(30_000L))
        assertEquals(0, RotationRhythm.phaseSlots(null))
    }

    // ---------------------------------------------------------- the histogram

    @Test
    fun `a histogram covers every measurement it was given`() {
        val periods = listOf(300_000L, 880_000L, 900_000L, 905_000L, 910_000L, 1_800_000L)
        val buckets = RotationRhythm.histogram(periods)
        assertEquals(periods.size, buckets.sumOf { it.count })
        assertTrue(buckets.first().fromMs <= periods.min())
        assertTrue(buckets.last().toMs >= periods.max())
    }

    @Test
    fun `a histogram of one value does not divide by zero`() {
        val buckets = RotationRhythm.histogram(listOf(900_000L))
        assertEquals(1, buckets.sumOf { it.count })
    }

    @Test
    fun `nothing to bucket produces no bars rather than empty ones`() {
        assertTrue(RotationRhythm.histogram(emptyList()).isEmpty())
    }
}
