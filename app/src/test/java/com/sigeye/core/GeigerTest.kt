package com.sigeye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Following something by feel, with the phone in a pocket. */
class GeigerTest {

    @Test
    fun `closer is faster`() {
        val near = Geiger.intervalMs(-50.0)
        val mid = Geiger.intervalMs(-70.0)
        val far = Geiger.intervalMs(-90.0)

        assertTrue("$near < $mid < $far", near < mid && mid < far)
    }

    @Test
    fun `the ends are clamped rather than running away`() {
        // Below the fastest, separate buzzes stop being separate buzzes. Above the
        // slowest, it reads as having switched itself off.
        assertEquals(Geiger.FASTEST_MS, Geiger.intervalMs(-20.0))
        assertEquals(Geiger.FASTEST_MS, Geiger.intervalMs(Geiger.NEAR_DBM))
        assertEquals(Geiger.SLOWEST_MS, Geiger.intervalMs(-120.0))
        assertEquals(Geiger.SLOWEST_MS, Geiger.intervalMs(Geiger.FAR_DBM))
    }

    @Test
    fun `the pulse gets firmer as well as faster`() {
        // Rate alone is hard to read through a coat, which is the situation this exists for.
        assertTrue(Geiger.strength(-50.0) > Geiger.strength(-85.0))
    }

    @Test
    fun `even the faintest pulse can still be felt`() {
        // A pulse nobody can feel is the same as no pulse, and at the far end this still
        // has to be saying something rather than appearing to have stopped.
        assertTrue(Geiger.strength(-120.0) > 0.2)
    }

    @Test
    fun `halfway in decibels is halfway in rate`() {
        // Linear in decibels, not in power. Power spans five orders of magnitude across a
        // room, which as a pulse rate would be unusable at one end and indistinguishable
        // at the other.
        val midDbm = (Geiger.NEAR_DBM + Geiger.FAR_DBM) / 2
        val expected = (Geiger.SLOWEST_MS + Geiger.FASTEST_MS) / 2

        assertEquals(expected.toDouble(), Geiger.intervalMs(midDbm).toDouble(), 1.0)
    }

    // ------------------------------------------------------------------ the three states

    @Test
    fun `nothing to follow is idle rather than a very slow pulse`() {
        assertEquals(Beat.Idle, Geiger.beat(null, 0L))
    }

    @Test
    fun `gone quiet is its own beat, not a very weak signal`() {
        // Through a coat those feel identical and they mean completely different things:
        // one is the target moving away, the other is the target having gone.
        val lost = Geiger.beat(-60.0, Geiger.STALE_MS + 1_000L)

        assertTrue("$lost", lost is Beat.Lost)
    }

    @Test
    fun `a live signal pulses at the rate its level implies`() {
        val beat = Geiger.beat(-60.0, 500L) as Beat.Pulse

        assertEquals(Geiger.intervalMs(-60.0), beat.intervalMs)
        assertEquals(Geiger.strength(-60.0), beat.strength, 0.001)
    }

    @Test
    fun `the description never lets it be read as a distance`() {
        val text = Geiger.describe(-60.0)

        assertTrue(text.contains("not a distance"))
        assertTrue(Geiger.describe(null).contains("Nothing to follow"))
    }
}
