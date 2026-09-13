package com.sigeye.experiments.trainspotter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sliding window that decides, next to the bins that record.
 *
 * The reason this exists is the case in the middle of this file: a burst landing across a
 * bin boundary is cut in two, and neither half clears a threshold the whole would have
 * cleared easily. That is a silent miss - the train goes past and nothing says so - and it
 * is invisible in the recorded data because the recorded data is what did the missing.
 */
class RollingWindowTest {

    private val start = 1_700_000_000_000L

    /** Short stages, so a test does not have to simulate a minute and a half of nothing. */
    private val config = PulseConfig(
        binSeconds = 8,
        enrollmentSeconds = 8,
        warmupSeconds = 8,
        spikeFactor = 3.0,
        spikeMinCount = 4,
    )

    private fun armed(): PulseAggregator = PulseAggregator(config).apply {
        // Two closed bins gets past enrolment and warm-up, so counting is live.
        observe("00:00:00:00:00:01", -50, start)
        closeBin(start + 8_000L)
        closeBin(start + 16_000L)
    }

    private fun PulseAggregator.arrive(count: Int, atMs: Long, from: Int = 100) {
        repeat(count) { observe("AA:BB:CC:DD:%02X:%02X".format(from + it, it), -50, atMs) }
    }

    @Test
    fun `the window is the same span as a bin, it just ends now`() {
        val aggregator = armed()
        aggregator.arrive(5, start + 20_000L)

        assertEquals(5, aggregator.rollingNew(start + 24_000L))
        // Eight seconds later the same arrivals have aged out of it.
        assertEquals(0, aggregator.rollingNew(start + 29_000L))
    }

    @Test
    fun `a burst across a bin boundary is halved by bins and whole in the window`() {
        // The reason for all of this. Eleven devices land just before a boundary and nine
        // just after: twenty in eight seconds, which is a train. Neither bin sees more than
        // eleven, and against a baseline of four neither one clears three times it.
        val aggregator = armed()
        val baseline = 4.0

        aggregator.arrive(11, start + 22_000L, from = 100)
        val firstBin = aggregator.closeBin(start + 24_000L)!!
        aggregator.arrive(9, start + 25_000L, from = 150)

        assertEquals(11, firstBin.newCount)
        assertFalse("the first half alone is not a spike", aggregator.isSpike(11, baseline))
        assertFalse("nor is the second", aggregator.isSpike(9, baseline))

        // The window that ends now contains all twenty, because it has no boundary in it.
        assertEquals(20, aggregator.rollingNew(start + 26_000L))
        assertTrue(aggregator.isSpike(aggregator.rollingNew(start + 26_000L), baseline))
    }

    @Test
    fun `the window sees a burst within a second rather than at the next boundary`() {
        val aggregator = armed()
        aggregator.arrive(20, start + 17_000L)

        // One second after they arrive, with the bin not due to close for another seven.
        assertTrue(aggregator.isSpike(aggregator.rollingNew(start + 18_000L), 4.0))
    }

    @Test
    fun `coming back down is what re-arms the alert`() {
        // Overlapping windows mean one burst is over the line in every window containing
        // it. Without a fall-back rule the same train fires the alert once a tick.
        val aggregator = armed()
        val baseline = 4.0

        aggregator.arrive(20, start + 20_000L)
        assertTrue(aggregator.isSpike(aggregator.rollingNew(start + 21_000L), baseline))
        assertFalse(
            "still over the line four seconds later, so not re-armed",
            aggregator.hasFallenBack(aggregator.rollingNew(start + 24_000L), baseline),
        )

        // Once the burst has aged out of the window, it is ready for the next one.
        assertTrue(aggregator.hasFallenBack(aggregator.rollingNew(start + 30_000L), baseline))
    }

    @Test
    fun `hovering on the threshold does not chatter`() {
        // Coming back to just below the line and rising again would otherwise re-arm and
        // re-fire on every tick. It has to fall well clear.
        val aggregator = armed()
        val baseline = 4.0
        val line = baseline * config.spikeFactor

        assertFalse(
            "just under the line is not far enough back",
            aggregator.hasFallenBack((line - 1).toInt(), baseline),
        )
        assertTrue(
            "well clear of it is",
            aggregator.hasFallenBack((line * 0.5).toInt(), baseline),
        )
    }

    @Test
    fun `a clock that jumps backwards does not leave arrivals stranded in the future`() {
        val aggregator = armed()
        aggregator.arrive(6, start + 40_000L)

        // The clock is corrected backwards past the arrivals.
        assertEquals(0, aggregator.rollingNew(start + 20_000L))
    }

    @Test
    fun `enrolment still counts for nothing`() {
        // The opening sweep discovers the whole standing population at once, and it is not
        // an event whichever shape of window is looking at it.
        val aggregator = PulseAggregator(config)
        aggregator.arrive(30, start)

        assertEquals(0, aggregator.rollingNew(start + 1_000L))
    }

    @Test
    fun `bins are still the unit of record`() {
        // Everything recorded stays bin-shaped, because days of ground-truth labels are
        // recorded against bins and a rolling count would not be comparable with them.
        val aggregator = armed()
        aggregator.arrive(7, start + 18_000L)
        val bin = aggregator.closeBin(start + 24_000L)!!

        assertEquals(7, bin.newCount)
        assertEquals(start + 16_000L, bin.startMs)
    }
}
