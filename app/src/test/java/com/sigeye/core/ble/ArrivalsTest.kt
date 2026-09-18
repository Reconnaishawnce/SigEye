package com.sigeye.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How often a device is heard, which decides whether it can be chased on foot at all.
 *
 * Written after a locate attempt in an airport, where the target produced a reading about
 * every ten seconds and the screen gave no hint that this was the problem.
 */
class ArrivalsTest {

    private val t0 = 1_700_000_000_000L

    private fun every(gapMs: Long, count: Int): List<Long> =
        (0 until count).map { t0 + it * gapMs }

    // ------------------------------------------------------------------ the measurement

    @Test
    fun `a steady device reports the gap it is actually keeping`() {
        assertEquals(1_000L, Arrivals.typicalGapMs(every(1_000L, 10)))
    }

    @Test
    fun `one lost packet does not redefine the device`() {
        // The whole reason for a median. A single missed advertisement doubles one gap,
        // and an average would let that one miss describe everything.
        val withHole = every(1_000L, 10).filterIndexed { index, _ -> index != 4 }

        assertEquals(1_000L, Arrivals.typicalGapMs(withHole))
    }

    @Test
    fun `two arrivals is one gap and one gap proves nothing`() {
        assertNull(Arrivals.typicalGapMs(listOf(t0, t0 + 1_000)))
        assertNull(Arrivals.typicalGapMs(listOf(t0)))
        assertNull(Arrivals.typicalGapMs(emptyList()))
    }

    @Test
    fun `duplicate timestamps do not become a gap of zero`() {
        // Two packets decoded inside the same millisecond say nothing about the interval,
        // and letting them through as a zero would report a device speaking infinitely fast.
        val doubled = listOf(t0, t0, t0 + 1_000, t0 + 1_000, t0 + 2_000, t0 + 3_000, t0 + 4_000)

        assertEquals(1_000L, Arrivals.typicalGapMs(doubled))
    }

    @Test
    fun `only the recent past counts`() {
        // A device that was quick a minute ago and is slow now is a slow device. Keeping
        // the whole history would average the two and describe neither.
        val old = every(200L, 40)
        var kept = emptyList<Long>()
        old.forEach { kept = Arrivals.record(kept, it) }
        repeat(30) { kept = Arrivals.record(kept, old.last() + (it + 1) * 8_000L) }

        assertEquals(Arrivals.WINDOW, kept.size)
        assertEquals(8_000L, Arrivals.typicalGapMs(kept))
    }

    // ------------------------------------------------------------------ what it means

    @Test
    fun `the pace bands match what a person can do with them`() {
        assertEquals(Arrivals.Pace.QUICK, Arrivals.paceOf(300L))
        assertEquals(Arrivals.Pace.WORKABLE, Arrivals.paceOf(2_500L))
        assertEquals(Arrivals.Pace.SLOW, Arrivals.paceOf(10_000L))
        assertEquals(Arrivals.Pace.UNKNOWN, Arrivals.paceOf(null))
    }

    @Test
    fun `a device heard several times a second needs no advice`() {
        assertNull(Arrivals.advice(250L, crowded = false))
        assertNull(Arrivals.advice(250L, crowded = true))
    }

    @Test
    fun `a slow device in a quiet room is not blamed on the room`() {
        val advice = Arrivals.advice(10_000L, crowded = false)

        assertNotNull(advice)
        assertTrue(advice!!.contains("asleep in a pocket"))
    }

    @Test
    fun `a slow device in a crowd says what the app already did about it`() {
        // Somebody standing in a terminal watching a reading crawl deserves to know the
        // app noticed, rather than concluding it is broken.
        val advice = Arrivals.advice(10_000L, crowded = true)!!

        assertTrue(advice.contains("filter of its own"))
    }

    @Test
    fun `the rate is stated in units a person uses`() {
        assertTrue(Arrivals.describe(200L).contains("several times a second"))
        assertTrue(Arrivals.describe(1_000L).contains("once a second"))
        assertEquals("Heard about every 10 seconds.", Arrivals.describe(10_000L))
        assertEquals("Heard about every 5 minutes.", Arrivals.describe(300_000L))
        assertTrue(Arrivals.describe(null).contains("Still counting"))
    }
}
