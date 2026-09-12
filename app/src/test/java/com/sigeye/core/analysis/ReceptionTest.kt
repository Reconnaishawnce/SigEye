package com.sigeye.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceptionTest {

    /**
     * Arrivals for a device advertising every [intervalMs], where [keep] of every ten
     * packets survive the trip.
     */
    private fun arrivals(intervalMs: Long, count: Int, keep: Int = 10): List<Long> {
        val out = mutableListOf<Long>()
        repeat(count) { index ->
            if (index % 10 < keep) out.add(index * intervalMs)
        }
        return out
    }

    @Test
    fun `a device losing nothing reports everything arriving`() {
        val reception = Reception.of("AA", null, arrivals(100, 100))
        assertEquals(100L, reception.baseIntervalMs)
        assertEquals(10.0, reception.expectedPerSecond!!, 0.01)
        assertEquals(1.0, reception.ratio!!, 0.02)
        assertEquals(0, reception.missed)
    }

    @Test
    fun `half the packets missing halves the ratio`() {
        val reception = Reception.of("AA", null, arrivals(100, 200, keep = 5))
        // The interval still reads 100: the smallest gaps are the ones where nothing was
        // dropped, which is exactly why a low percentile is used rather than the median.
        assertEquals(100L, reception.baseIntervalMs)
        assertEquals(0.5, reception.ratio!!, 0.05)
        assertTrue("missed ${reception.missed}", reception.missed!! > 80)
    }

    @Test
    fun `the ratio never exceeds everything`() {
        // Nine packets span eight intervals, so counting packets against that span always
        // comes out slightly above the rate that produced them.
        val reception = Reception.of("AA", null, (0..8).map { it * 100L })
        assertTrue(reception.observedPerSecond > reception.expectedPerSecond!!)
        assertEquals(1.0, reception.ratio!!, 0.0001)
    }

    @Test
    fun `too few packets is refused rather than extrapolated`() {
        val reception = Reception.of("AA", null, listOf(0L, 100L, 200L))
        assertTrue(!reception.usable)
        assertEquals(0L, reception.baseIntervalMs)
        assertNull(reception.ratio)
        assertEquals("-", reception.percent())
    }

    @Test
    fun `a device present for half the recording is judged on the half it was there`() {
        // Arrivals start at thirty seconds and run to sixty. The window is the span of the
        // packets, not the wall clock, so this reads as a clean link rather than as fifty
        // percent loss.
        val late = (0..300).map { 30_000L + it * 100L }
        val reception = Reception.of("AA", null, late)
        assertEquals(1.0, reception.ratio!!, 0.02)
    }

    @Test
    fun `mean signal comes back alongside the ratio`() {
        val reception = Reception.of("AA", null, arrivals(100, 20), List(20) { -60 })
        assertEquals(-60, reception.meanRssi)
    }

    @Test
    fun `signal is left null rather than invented when none was recorded`() {
        assertNull(Reception.of("AA", null, arrivals(100, 20)).meanRssi)
    }

    // -------------------------------------------------- comparing against the room

    @Test
    fun `the best device in view becomes the reference`() {
        val good = Reception.of("AA", null, arrivals(100, 200))
        val bad = Reception.of("BB", null, arrivals(100, 200, keep = 3))
        val best = Reception.best(listOf(bad, good))
        assertNotNull(best)
        assertEquals("AA", best!!.address)
    }

    @Test
    fun `shortfall is measured against the room, not against perfection`() {
        val good = Reception.of("AA", null, arrivals(100, 200))
        val bad = Reception.of("BB", null, arrivals(100, 200, keep = 4))
        val shortfall = Reception.shortfall(bad, good)
        assertNotNull(shortfall)
        assertEquals(60.0, shortfall!!.toDouble(), 8.0)
    }

    @Test
    fun `the best device is not compared against itself`() {
        val only = Reception.of("AA", null, arrivals(100, 200))
        assertNull(Reception.shortfall(only, only))
    }

    @Test
    fun `nothing usable in view means no reference and no comparison`() {
        val thin = Reception.of("AA", null, listOf(0L, 100L))
        assertNull(Reception.best(listOf(thin)))
        assertNull(Reception.shortfall(thin, null))
    }
}
