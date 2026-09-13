package com.sigeye.core.analysis.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ProximityTest {

    // ------------------------------------------------------------ the filter

    @Test
    fun `the first reading is taken at face value`() {
        val f = RssiFilter()
        assertEquals(-70.0, f.update(-70.0), 0.001)
    }

    @Test
    fun `a single wild packet barely moves the estimate`() {
        val f = RssiFilter()
        repeat(20) { f.update(-70.0) }
        val before = f.value()
        f.update(-30.0) // an absurd outlier
        // It should nudge, not leap. Half the jump would make a locator unusable.
        assertTrue("moved ${f.value() - before}", f.value() - before < 12.0)
        assertTrue(f.value() < -55.0)
    }

    @Test
    fun `a sustained change is followed within a few seconds`() {
        val f = RssiFilter()
        repeat(20) { f.update(-80.0) }
        repeat(25) { f.update(-55.0) }
        // Converged most of the way to the new truth.
        assertTrue("settled at ${f.value()}", abs(f.value() - (-55.0)) < 4.0)
    }

    @Test
    fun `noise around a steady value settles near that value`() {
        val f = RssiFilter()
        val noise = listOf(-68, -74, -66, -76, -70, -72, -69, -73, -71, -70)
        repeat(6) { round -> noise.forEach { f.update(it.toDouble() + round * 0) } }
        assertTrue("settled at ${f.value()}", abs(f.value() - (-70.0)) < 3.0)
    }

    // -------------------------------------------------------------- distance

    @Test
    fun `distance at the reference power is one metre`() {
        val e = ProximityEstimator(txPowerAtOneMetre = -59, pathLossExponent = 2.0)
        assertEquals(1.0, e.metresFor(-59.0), 0.001)
    }

    @Test
    fun `weaker signal always means further away`() {
        val e = ProximityEstimator()
        val near = e.metresFor(-50.0)
        val mid = e.metresFor(-70.0)
        val far = e.metresFor(-90.0)
        assertTrue(near < mid)
        assertTrue(mid < far)
    }

    @Test
    fun `a higher path loss exponent reads the same signal as closer`() {
        val freeSpace = ProximityEstimator(pathLossExponent = 2.0)
        val indoors = ProximityEstimator(pathLossExponent = 3.0)
        assertTrue(indoors.metresFor(-80.0) < freeSpace.metresFor(-80.0))
    }

    @Test
    fun `a beacon declaring its own power changes the distance`() {
        val assumed = ProximityEstimator(txPowerAtOneMetre = -59)
        val declared = ProximityEstimator(txPowerAtOneMetre = -75)
        // A quieter transmitter at the same received strength must be nearer.
        assertTrue(declared.metresFor(-80.0) < assumed.metresFor(-80.0))
    }

    // ----------------------------------------------------------------- zones

    @Test
    fun `zones need a few samples before they commit`() {
        assertEquals(ProximityZone.UNKNOWN, ProximityEstimator.zoneFor(0.3, samples = 1))
        assertEquals(ProximityZone.IMMEDIATE, ProximityEstimator.zoneFor(0.3, samples = 5))
    }

    @Test
    fun `zone boundaries are where they claim to be`() {
        assertEquals(ProximityZone.IMMEDIATE, ProximityEstimator.zoneFor(0.4, 10))
        assertEquals(ProximityZone.NEAR, ProximityEstimator.zoneFor(1.5, 10))
        assertEquals(ProximityZone.FAR, ProximityEstimator.zoneFor(5.0, 10))
        assertEquals(ProximityZone.DISTANT, ProximityEstimator.zoneFor(20.0, 10))
    }

    // ----------------------------------------------------------------- trend

    @Test
    fun `walking towards something reads as getting closer`() {
        val e = ProximityEstimator()
        var now = 0L
        var rssi = -90
        repeat(24) {
            e.observe(rssi, now)
            now += 250L
            if (it % 2 == 0) rssi += 1
        }
        val reading = e.observe(rssi, now)
        assertEquals(Trend.CLOSER, reading.trend)
        assertTrue(reading.slopeDbPerSecond > 0)
    }

    @Test
    fun `walking away reads as getting further`() {
        val e = ProximityEstimator()
        var now = 0L
        var rssi = -50
        repeat(24) {
            e.observe(rssi, now)
            now += 250L
            if (it % 2 == 0) rssi -= 1
        }
        val reading = e.observe(rssi, now)
        assertEquals(Trend.FURTHER, reading.trend)
        assertTrue(reading.slopeDbPerSecond < 0)
    }

    @Test
    fun `standing still reads as holding, not as drift`() {
        val e = ProximityEstimator()
        var now = 0L
        // Realistic jitter with no actual movement.
        listOf(-70, -73, -68, -72, -70, -71, -69, -74, -70, -71, -72, -70).forEach {
            e.observe(it, now)
            now += 400L
        }
        val reading = e.observe(-70, now)
        assertEquals(Trend.STEADY, reading.trend)
    }

    @Test
    fun `trend is withheld until there are enough readings to fit a line`() {
        val e = ProximityEstimator()
        assertEquals(Trend.UNKNOWN, e.observe(-70, 0L).trend)
        assertEquals(Trend.UNKNOWN, e.observe(-69, 200L).trend)
        assertEquals(Trend.UNKNOWN, e.observe(-68, 400L).trend)
    }

    @Test
    fun `old readings leave the trend window`() {
        val e = ProximityEstimator(trendWindowMs = 2_000L)
        // A strong climb, then a long steady stretch.
        //
        // Two separate mechanisms have to settle before the trend goes flat: the window
        // has to drop the climbing samples, and the filter has to finish converging on
        // the new level. While it is still catching up the smoothed series genuinely is
        // rising, and reporting that as "closer" is correct - so the flat stretch here is
        // long enough to cover both.
        var now = 0L
        repeat(10) { e.observe(-90 + it * 3, now); now += 100L }
        now += 5_000L
        repeat(40) { e.observe(-60, now); now += 150L }
        val reading = e.observe(-60, now)
        assertEquals(Trend.STEADY, reading.trend)
        assertTrue("slope ${reading.slopeDbPerSecond}", abs(reading.slopeDbPerSecond) < 0.5)
    }

    @Test
    fun `the trend follows a reversal rather than averaging it away`() {
        val e = ProximityEstimator()
        var now = 0L
        // Walk in...
        var rssi = -90
        repeat(30) { e.observe(rssi, now); now += 200L; if (it % 2 == 0) rssi += 2 }
        assertEquals(Trend.CLOSER, e.observe(rssi, now).trend)
        // ...then turn around and walk out.
        repeat(40) { e.observe(rssi, now); now += 200L; if (it % 2 == 0) rssi -= 2 }
        assertEquals(Trend.FURTHER, e.observe(rssi, now).trend)
    }

    // ------------------------------------------------------------ confidence

    @Test
    fun `steady readings earn more confidence than erratic ones`() {
        val steady = ProximityEstimator()
        var now = 0L
        repeat(15) { steady.observe(-70, now); now += 300L }
        val steadyReading = steady.observe(-70, now)

        val erratic = ProximityEstimator()
        now = 0L
        listOf(-40, -95, -50, -90, -45, -99, -55, -88, -42, -97, -51, -93, -47, -91, -44)
            .forEach { erratic.observe(it, now); now += 300L }
        val erraticReading = erratic.observe(-95, now)

        assertTrue(
            "steady ${steadyReading.confidence} vs erratic ${erraticReading.confidence}",
            steadyReading.confidence > erraticReading.confidence,
        )
    }

    @Test
    fun `the best reading is remembered across the hunt`() {
        val e = ProximityEstimator()
        var now = 0L
        listOf(-80, -75, -62, -78, -83).forEach { e.observe(it, now); now += 300L }
        assertEquals(-62, e.observe(-85, now).bestRssi)
    }

    @Test
    fun `reset clears the hunt`() {
        val e = ProximityEstimator()
        var now = 0L
        repeat(10) { e.observe(-60, now); now += 300L }
        e.reset()
        val reading = e.observe(-90, now)
        assertEquals(-90, reading.bestRssi)
        assertEquals(1, reading.samples)
        assertEquals(Trend.UNKNOWN, reading.trend)
    }
}
