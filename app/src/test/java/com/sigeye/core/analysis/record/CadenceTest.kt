package com.sigeye.core.analysis.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenceTest {

    private fun track(gaps: List<Long>): Track {
        var at = 0L
        val pings = mutableListOf(Ping(0L, -60))
        gaps.forEach { gap ->
            at += gap
            pings.add(Ping(at, -60))
        }
        return Track(
            address = "AA",
            label = "AA",
            vendor = null,
            isRandom = false,
            pings = pings,
            recordingStartMs = 0L,
            recordingEndMs = at,
        )
    }

    @Test
    fun `a beacon advertising on a timer reads as metronomic`() {
        // The thing this identifies: something whose only job is to advertise.
        assertEquals(Cadence.METRONOMIC, track(List(20) { 100L }).cadence)
    }

    @Test
    fun `a little slop is still regular`() {
        val gaps = List(20) { index -> 100L + (index % 5) * 40L }
        assertEquals(Cadence.REGULAR, track(gaps).cadence)
    }

    @Test
    fun `clumps with gaps between them read as bursty`() {
        // A phone: fast while the screen is on, slow when it is not.
        val gaps = List(20) { index -> if (index % 4 == 0) 900L else 120L }
        assertEquals(Cadence.BURSTY, track(gaps).cadence)
    }

    @Test
    fun `mostly quick with rare enormous gaps reads as sporadic`() {
        // Something at the edge of range: heard in snatches, with long silences that are
        // missed packets rather than a rhythm.
        val gaps = List(18) { 60L } + listOf(30_000L, 45_000L)
        assertEquals(Cadence.SPORADIC, track(gaps).cadence)
    }

    @Test
    fun `alternating fast and slow is bursty rather than sporadic`() {
        // Two rates rather than no rate. Bursty is the honest description.
        val gaps = listOf(50L, 4_000L, 80L, 9_000L, 60L, 12_000L, 70L, 6_000L, 90L, 15_000L)
        assertEquals(Cadence.BURSTY, track(gaps).cadence)
    }

    @Test
    fun `too few packets says so rather than guessing a class`() {
        assertEquals(Cadence.UNKNOWN, track(listOf(100L, 100L)).cadence)
    }

    @Test
    fun `one missed packet does not reclassify a metronomic device`() {
        // The reason this uses median absolute deviation rather than a standard
        // deviation: a single doubled gap is a dropped packet, not a change of character.
        val gaps = MutableList(30) { 100L }
        gaps[15] = 200L
        assertEquals(Cadence.METRONOMIC, track(gaps).cadence)
    }

    @Test
    fun `the typical gap is reported in milliseconds`() {
        assertEquals(100L, track(List(20) { 100L }).medianGapMs)
    }

    @Test
    fun `packet rate comes from the span it was actually audible for`() {
        // Ten pings spanning nine seconds: the rate is over the span, not the count.
        val subject = track(List(9) { 1_000L })
        assertEquals(10.0 / 9.0, subject.packetsPerSecond, 0.05)
    }
}
