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
        baselineBins = 60,
        warmupBins = 24,
        spikeFactor = 3.0,
        spikeMinCount = 4,
    )

    @Test
    fun `first sighting of an address counts as new`() {
        val agg = PulseAggregator(config)
        assertTrue(agg.observe("AA:BB", -60, 0L))
        assertEquals(1, agg.currentCount())
    }

    @Test
    fun `repeat sighting inside the window does not count again`() {
        val agg = PulseAggregator(config)
        agg.observe("AA:BB", -60, 0L)
        assertFalse(agg.observe("AA:BB", -60, 60_000L))
        assertFalse(agg.observe("AA:BB", -60, 9 * 60_000L))
        assertEquals(1, agg.currentCount())
    }

    @Test
    fun `sighting after the window counts as new again`() {
        val agg = PulseAggregator(config)
        agg.observe("AA:BB", -60, 0L)
        // 10 minutes plus a second
        assertTrue(agg.observe("AA:BB", -60, 601_000L))
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

    @Test
    fun `closing a bin resets the counter and records the count`() {
        val agg = PulseAggregator(config)
        agg.observe("A", -60, 0L)
        agg.observe("B", -60, 1_000L)
        val bin = agg.closeBin(5_000L)
        assertNotNull(bin)
        assertEquals(2, bin!!.newCount)
        assertEquals(2, bin.activeUnique)
        assertEquals(0, agg.currentCount())
    }

    @Test
    fun `addresses age out of the active set`() {
        val agg = PulseAggregator(config)
        agg.observe("A", -60, 0L)
        agg.closeBin(5_000L)
        assertEquals(1, agg.activeUnique())
        // Close a bin well past the 10 minute window with no further sightings.
        agg.closeBin(11 * 60_000L)
        assertEquals(0, agg.activeUnique())
    }

    @Test
    fun `a quiet baseline followed by a burst raises a spike`() {
        val agg = PulseAggregator(config)
        var now = 0L
        var addr = 0

        // 40 bins of steady background: 1 new device per bin.
        repeat(40) {
            agg.observe("bg${addr++}", -60, now)
            now += 5_000L
            agg.closeBin(now)
        }
        assertEquals(1.0, agg.computeBaseline(), 0.001)

        // A train: 12 new devices in one bin.
        repeat(12) { agg.observe("train${addr++}", -60, now) }
        now += 5_000L
        val bin = agg.closeBin(now)!!
        assertTrue("12 devices over a baseline of 1 should spike", bin.spike)
        assertEquals(12, bin.newCount)
    }

    @Test
    fun `a busy baseline suppresses an ordinary bin`() {
        val agg = PulseAggregator(config)
        var now = 0L
        var addr = 0

        // Busy street: 10 new devices per bin is normal here.
        repeat(40) {
            repeat(10) { agg.observe("bg${addr++}", -60, now) }
            now += 5_000L
            agg.closeBin(now)
        }
        assertEquals(10.0, agg.computeBaseline(), 0.001)

        // 12 devices is above the mean but nowhere near 3x - should not fire.
        repeat(12) { agg.observe("x${addr++}", -60, now) }
        now += 5_000L
        assertFalse(agg.closeBin(now)!!.spike)
    }

    @Test
    fun `spikeMinCount blocks tiny relative spikes in dead silence`() {
        val agg = PulseAggregator(config)
        // Baseline of zero, a single device appears. 1 device is not a train.
        assertFalse(agg.isSpike(1, 0.0))
        assertFalse(agg.isSpike(3, 0.0))
        assertTrue(agg.isSpike(4, 0.0))
    }

    @Test
    fun `spiking bins are excluded from the baseline`() {
        val agg = PulseAggregator(config)
        var now = 0L
        var addr = 0
        repeat(30) {
            agg.observe("bg${addr++}", -60, now)
            now += 5_000L
            agg.closeBin(now)
        }
        // Three consecutive heavy bins, as a long train would produce.
        repeat(3) {
            repeat(15) { agg.observe("t${addr++}", -60, now) }
            now += 5_000L
            assertTrue(agg.closeBin(now)!!.spike)
        }
        // Baseline must still reflect the quiet street, not the train.
        assertEquals(1.0, agg.computeBaseline(), 0.001)
    }

    @Test
    fun `no spike can fire during the warm-up period`() {
        val agg = PulseAggregator(config)
        var now = 0L
        var addr = 0
        // A huge burst in the very first bin must not alert - there is no baseline yet.
        repeat(50) { agg.observe("x${addr++}", -60, now) }
        now += 5_000L
        assertFalse(agg.closeBin(now)!!.spike)
        assertFalse(agg.isWarm())

        // Idle through the rest of the warm-up.
        repeat(25) {
            now += 5_000L
            agg.closeBin(now)
        }
        assertTrue(agg.isWarm())
        assertEquals(0, agg.binsUntilWarm())

        // Now the same burst does alert.
        repeat(50) { agg.observe("y${addr++}", -60, now) }
        now += 5_000L
        assertTrue(agg.closeBin(now)!!.spike)
    }

    @Test
    fun `history is capped to the configured window`() {
        val small = config.copy(historyMinutes = 1) // 12 bins at 5s
        val agg = PulseAggregator(small)
        var now = 0L
        repeat(50) {
            now += 5_000L
            agg.closeBin(now)
        }
        assertEquals(12, agg.history().size)
    }

    @Test
    fun `a backwards clock jump does not emit a bin or stall`() {
        val agg = PulseAggregator(config)
        agg.observe("A", -60, 1_000_000L)
        assertNotNull(agg.closeBin(1_005_000L))
        // Clock yanked back an hour mid-run.
        assertNull(agg.closeBin(1_005_000L - 3_600_000L))
        // The very next bin closes normally against the new reference.
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
}
