package com.sigeye.experiments.trainspotter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseAggregatorTest {

    private val config = PulseConfig(
        binSeconds = 5,
        windowMinutes = 10,
        rssiFloor = -85,
        historyMinutes = 30,
        enrollmentSeconds = 20, // 4 bins
        warmupSeconds = 60, // 12 bins
        baselineBins = 60,
        spikeFactor = 3.0,
        spikeMinCount = 4,
    )

    /** Advances past enrollment and warm-up with a steady [perBin] arrivals per bin. */
    private fun PulseAggregator.runIn(bins: Int, perBin: Int, startAt: Long = 0L): Long {
        var now = startAt
        var seq = 0
        // One seed advertisement so the first closeBin is a real bin even when perBin
        // is zero - the phase clock only starts once data is flowing.
        observe("seed-$startAt", -60, now)
        repeat(bins) {
            repeat(perBin) { observe("warm-$now-${seq++}", -60, now) }
            now += 5_000L
            closeBin(now)
        }
        return now
    }

    @Test
    fun `first sighting of an address counts as new once armed`() {
        val agg = PulseAggregator(config)
        val now = agg.runIn(config.armedAfterBins, 0)
        assertTrue(agg.observe("AA:BB", -60, now))
        assertEquals(1, agg.currentCount())
    }

    @Test
    fun `repeat sighting inside the window does not count again`() {
        val agg = PulseAggregator(config)
        var now = agg.runIn(config.armedAfterBins, 0)
        agg.observe("AA:BB", -60, now)
        assertFalse(agg.observe("AA:BB", -60, now + 60_000L))
        assertEquals(1, agg.currentCount())
    }

    @Test
    fun `sighting after the window counts as new again`() {
        val agg = PulseAggregator(config)
        val now = agg.runIn(config.armedAfterBins, 0)
        agg.observe("AA:BB", -60, now)
        assertTrue(agg.observe("AA:BB", -60, now + 601_000L))
        assertEquals(2, agg.currentCount())
    }

    @Test
    fun `advertisements below the rssi floor are dropped entirely`() {
        val agg = PulseAggregator(config)
        assertFalse(agg.observe("AA:BB", -95, 0L))
        assertEquals(0, agg.currentCount())
        assertEquals(0, agg.activeUnique())
        assertEquals(0L, agg.totalAdvertisements)
    }

    // ------------------------------------------------------------ enrollment

    @Test
    fun `enrollment records devices without counting them`() {
        val agg = PulseAggregator(config)
        assertEquals(Phase.ENROLL, agg.phase())

        // The opening sweep: 200 devices all appear at once.
        repeat(200) { assertFalse(agg.observe("dev$it", -60, 0L)) }
        assertEquals(0, agg.currentCount())
        assertEquals(200, agg.activeUnique())

        val bin = agg.closeBin(5_000L)!!
        assertEquals(0, bin.newCount)
        assertEquals(200, bin.activeUnique)
        assertEquals(Phase.ENROLL, bin.phase)
        assertFalse(bin.countsTowardScale)
    }

    @Test
    fun `a device enrolled at the start does not count when seen again later`() {
        val agg = PulseAggregator(config)
        agg.observe("resident", -60, 0L)
        val now = agg.runIn(config.armedAfterBins, 0)
        // Still inside the 10 minute window, so it is not new.
        assertFalse(agg.observe("resident", -60, now))
        assertEquals(0, agg.currentCount())
    }

    @Test
    fun `the phase clock starts with the first advertisement, not the first tick`() {
        val agg = PulseAggregator(config)
        // Bins closed before any data has arrived only establish the bin boundary. If
        // Bluetooth is slow to deliver, enrollment should not burn away on empty air.
        repeat(10) { agg.closeBin(it * 5_000L) }
        assertEquals(Phase.ENROLL, agg.phase())
        assertEquals(config.armedAfterBins * config.binSeconds, agg.secondsUntilArmed())
    }

    @Test
    fun `phase advances enroll then warmup then armed`() {
        val agg = PulseAggregator(config)
        var now = 0L
        assertEquals(Phase.ENROLL, agg.phase())

        // Seed one advertisement so the first bin is a real bin, see the test above.
        agg.observe("seed", -60, now)

        repeat(config.enrollmentBins) { now += 5_000L; agg.closeBin(now) }
        assertEquals(Phase.WARMUP, agg.phase())

        repeat(config.warmupBins) { now += 5_000L; agg.closeBin(now) }
        assertEquals(Phase.ARMED, agg.phase())
        assertTrue(agg.isWarm())
        assertEquals(0, agg.secondsUntilArmed())
        assertEquals(1f, agg.armingProgress(), 0.001f)
    }

    @Test
    fun `countdown reports the configured total at the start`() {
        val agg = PulseAggregator(config)
        // 4 enrollment bins + 12 warm-up bins, 5s each = 80s.
        assertEquals(80, agg.secondsUntilArmed())
        assertEquals(0f, agg.armingProgress(), 0.001f)
    }

    @Test
    fun `enrollment bins are excluded from the baseline`() {
        val agg = PulseAggregator(config)
        // A huge enrollment bin, then quiet bins of 1.
        repeat(200) { agg.observe("dev$it", -60, 0L) }
        agg.closeBin(5_000L)
        agg.runIn(30, 1, startAt = 5_000L)
        // Median of the quiet bins only; the 200 never entered the pool.
        assertEquals(1.0, agg.computeBaseline(), 0.001)
    }

    // ----------------------------------------------------------------- spikes

    @Test
    fun `no spike can fire before the app is armed`() {
        val agg = PulseAggregator(config)
        repeat(50) { agg.observe("x$it", -60, 0L) }
        assertFalse(agg.closeBin(5_000L)!!.spike)
        assertFalse(agg.isWarm())
    }

    @Test
    fun `a quiet baseline followed by a burst raises a spike`() {
        val agg = PulseAggregator(config)
        var now = agg.runIn(40, 1)
        assertEquals(1.0, agg.computeBaseline(), 0.001)

        repeat(12) { agg.observe("train$it", -60, now) }
        now += 5_000L
        val bin = agg.closeBin(now)!!
        assertTrue("12 devices over a baseline of 1 should spike", bin.spike)
        assertEquals(Phase.ARMED, bin.phase)
    }

    @Test
    fun `a busy baseline suppresses an ordinary bin`() {
        val agg = PulseAggregator(config)
        var now = agg.runIn(40, 10)
        assertEquals(10.0, agg.computeBaseline(), 0.001)

        repeat(12) { agg.observe("x$it", -60, now) }
        now += 5_000L
        assertFalse(agg.closeBin(now)!!.spike)
    }

    @Test
    fun `spikeMinCount blocks tiny relative spikes in dead silence`() {
        val agg = PulseAggregator(config)
        assertFalse(agg.isSpike(1, 0.0))
        assertFalse(agg.isSpike(3, 0.0))
        assertTrue(agg.isSpike(4, 0.0))
    }

    @Test
    fun `spiking bins are excluded from the baseline`() {
        val agg = PulseAggregator(config)
        var now = agg.runIn(30, 1)
        var seq = 0
        repeat(3) {
            repeat(15) { agg.observe("t${seq++}", -60, now) }
            now += 5_000L
            assertTrue(agg.closeBin(now)!!.spike)
        }
        assertEquals(1.0, agg.computeBaseline(), 0.001)
    }

    // ------------------------------------------------------------ bookkeeping

    @Test
    fun `history is capped to the configured window`() {
        val small = config.copy(historyMinutes = 1) // 12 bins at 5s
        val agg = PulseAggregator(small)
        var now = 0L
        repeat(50) { now += 5_000L; agg.closeBin(now) }
        assertEquals(12, agg.history().size)
    }

    @Test
    fun `addresses age out of the active set`() {
        val agg = PulseAggregator(config)
        agg.observe("A", -60, 0L)
        agg.closeBin(5_000L)
        assertEquals(1, agg.activeUnique())
        agg.closeBin(11 * 60_000L)
        assertEquals(0, agg.activeUnique())
    }

    @Test
    fun `a backwards clock jump does not emit a bin or stall`() {
        val agg = PulseAggregator(config)
        agg.observe("A", -60, 1_000_000L)
        assertNotNull(agg.closeBin(1_005_000L))
        assertNull(agg.closeBin(1_005_000L - 3_600_000L))
        agg.observe("B", -60, 1_005_000L - 3_600_000L + 1_000L)
        assertNotNull(agg.closeBin(1_005_000L - 3_600_000L + 5_000L))
    }

    @Test
    fun `a pending label lands on the next closed bin only`() {
        val agg = PulseAggregator(config)
        agg.closeBin(5_000L)
        agg.markLabel("TRAIN")
        assertEquals("TRAIN", agg.closeBin(10_000L)!!.label)
        assertEquals("", agg.closeBin(15_000L)!!.label)
    }

    @Test
    fun `config derives bin counts from seconds`() {
        val c = PulseConfig(binSeconds = 5, enrollmentSeconds = 20, warmupSeconds = 60)
        assertEquals(4, c.enrollmentBins)
        assertEquals(12, c.warmupBins)
        assertEquals(16, c.armedAfterBins)

        // Rounds up, and never to zero, whatever the bin width.
        val coarse = PulseConfig(binSeconds = 30, enrollmentSeconds = 20, warmupSeconds = 10)
        assertEquals(1, coarse.enrollmentBins)
        assertEquals(1, coarse.warmupBins)
    }
}
