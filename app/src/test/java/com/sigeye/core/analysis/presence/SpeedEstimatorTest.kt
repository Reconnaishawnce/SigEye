package com.sigeye.core.analysis.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.sqrt

class SpeedEstimatorTest {

    /**
     * Builds the signal a transmitter would actually produce going past in a straight
     * line: range from Pythagoras, strength from the log-distance model.
     */
    private fun syntheticPass(
        speedMps: Double,
        distanceMetres: Double,
        pathLoss: Double = 2.0,
        seconds: Double = 12.0,
        hz: Double = 5.0,
        referenceAtOneMetre: Int = -40,
        noise: (Int) -> Int = { 0 },
    ): List<PassSample> {
        val samples = mutableListOf<PassSample>()
        val total = (seconds * hz).toInt()
        val stepMs = (1000.0 / hz).toLong()
        val passAt = total / 2
        repeat(total) { index ->
            val alongTrack = (index - passAt) / hz * speedMps
            val range = sqrt(distanceMetres * distanceMetres + alongTrack * alongTrack)
            val rssi = referenceAtOneMetre - 10.0 * pathLoss * log10(range.coerceAtLeast(0.5))
            samples.add(PassSample(index * stepMs, rssi.toInt() + noise(index)))
        }
        return samples
    }

    @Test
    fun `recovers a known speed from a clean pass`() {
        val samples = syntheticPass(speedMps = 10.0, distanceMetres = 10.0)
        val result = SpeedEstimator.analyse("A", samples, distanceMetres = 10.0)

        assertEquals(PassQuality.GOOD, result.quality)
        assertNotNull(result.speedMetresPerSecond)
        assertEquals(10.0, result.speedMetresPerSecond!!, 1.5)
    }

    @Test
    fun `a faster pass reads faster`() {
        val slow = SpeedEstimator.analyse(
            "A",
            syntheticPass(speedMps = 5.0, distanceMetres = 12.0),
            distanceMetres = 12.0,
        )
        val fast = SpeedEstimator.analyse(
            "A",
            syntheticPass(speedMps = 25.0, distanceMetres = 12.0, seconds = 8.0, hz = 10.0),
            distanceMetres = 12.0,
        )
        assertTrue(fast.speedMetresPerSecond!! > slow.speedMetresPerSecond!! * 2)
    }

    @Test
    fun `a train at line speed comes out in the right range`() {
        // 30 m/s is about 108 km/h, twenty metres from the track.
        val samples = syntheticPass(
            speedMps = 30.0,
            distanceMetres = 20.0,
            seconds = 8.0,
            hz = 10.0,
        )
        val result = SpeedEstimator.analyse("A", samples, distanceMetres = 20.0)
        assertNotNull(result.speedMetresPerSecond)
        assertEquals(108.0, result.kmh!!, 15.0)
    }

    @Test
    fun `getting the distance wrong scales the answer, and only that`() {
        val samples = syntheticPass(speedMps = 10.0, distanceMetres = 10.0)
        val right = SpeedEstimator.analyse("A", samples, distanceMetres = 10.0)
        val doubled = SpeedEstimator.analyse("A", samples, distanceMetres = 20.0)
        // Twice the assumed distance is twice the track length over the same time.
        assertEquals(
            right.speedMetresPerSecond!! * 2,
            doubled.speedMetresPerSecond!!,
            0.5,
        )
    }

    @Test
    fun `survives realistic packet noise`() {
        val samples = syntheticPass(
            speedMps = 15.0,
            distanceMetres = 15.0,
            hz = 8.0,
            noise = { index -> (index * 7 % 5) - 2 },
        )
        val result = SpeedEstimator.analyse("A", samples, distanceMetres = 15.0)
        assertNotNull(result.speedMetresPerSecond)
        assertEquals(15.0, result.speedMetresPerSecond!!, 4.0)
    }

    @Test
    fun `a single spurious dip near the peak does not narrow the crossing`() {
        val clean = syntheticPass(speedMps = 12.0, distanceMetres = 12.0, hz = 8.0)
        val fromClean = SpeedEstimator.analyse("A", clean, distanceMetres = 12.0)

        // One packet arrives 12 dB low, just before closest approach. Unsmoothed, that
        // would be read as an early crossing and report a much faster pass.
        val spiked = clean.toMutableList()
        val target = clean.size / 2 - 2
        spiked[target] = spiked[target].copy(rssi = spiked[target].rssi - 12)
        val fromSpiked = SpeedEstimator.analyse("A", spiked, distanceMetres = 12.0)

        assertNotNull(fromSpiked.speedMetresPerSecond)
        assertEquals(
            fromClean.speedMetresPerSecond!!,
            fromSpiked.speedMetresPerSecond!!,
            2.0,
        )
    }

    // ------------------------------------------------------------- rejection

    @Test
    fun `a flat signal is not a pass`() {
        val samples = (0 until 40).map { PassSample(it * 200L, -70) }
        val result = SpeedEstimator.analyse("A", samples, distanceMetres = 10.0)
        assertEquals(PassQuality.REJECTED, result.quality)
        assertNull(result.speedMetresPerSecond)
        assertNotNull(result.reason)
    }

    @Test
    fun `something that arrives and stays is not a pass`() {
        // Rises and then sits there - a device that walked up and stopped.
        val rising = (0 until 20).map { PassSample(it * 200L, -90 + it * 2) }
        val flat = (20 until 40).map { PassSample(it * 200L, -50) }
        val result = SpeedEstimator.analyse("A", rising + flat, distanceMetres = 10.0)
        assertEquals(PassQuality.REJECTED, result.quality)
    }

    @Test
    fun `something that leaves without arriving is not a pass`() {
        val flat = (0 until 20).map { PassSample(it * 200L, -50) }
        val falling = (20 until 40).map { PassSample(it * 200L, -50 - (it - 20) * 2) }
        val result = SpeedEstimator.analyse("A", flat + falling, distanceMetres = 10.0)
        assertEquals(PassQuality.REJECTED, result.quality)
    }

    @Test
    fun `too few readings is rejected rather than guessed`() {
        val samples = (0 until 5).map { PassSample(it * 200L, -90 + it * 10) }
        val result = SpeedEstimator.analyse("A", samples, distanceMetres = 10.0)
        assertEquals(PassQuality.REJECTED, result.quality)
        assertTrue(result.reason!!.contains("too few"))
    }

    @Test
    fun `a lopsided pass is kept but flagged as rough`() {
        // Slow approach, abrupt departure - something else was changing.
        val approach = (0 until 30).map {
            PassSample(it * 200L, (-85 + it * 1.0).toInt())
        }
        val departure = (30 until 40).map {
            PassSample(it * 200L, (-55 - (it - 30) * 3.5).toInt())
        }
        val result = SpeedEstimator.analyse("A", approach + departure, distanceMetres = 10.0)
        assertEquals(PassQuality.ROUGH, result.quality)
        assertNotNull(result.speedMetresPerSecond)
        assertNotNull(result.reason)
    }

    // ---------------------------------------------------------------- output

    @Test
    fun `speed is offered in the units people actually use`() {
        val samples = syntheticPass(speedMps = 20.0, distanceMetres = 15.0, hz = 10.0)
        val result = SpeedEstimator.analyse("A", samples, distanceMetres = 15.0)
        val mps = result.speedMetresPerSecond!!
        assertEquals(mps * 3.6, result.kmh!!, 0.01)
        assertEquals(mps * 2.23694, result.mph!!, 0.01)
    }

    @Test
    fun `the peak is reported where the pass actually happened`() {
        val samples = syntheticPass(speedMps = 10.0, distanceMetres = 10.0, seconds = 12.0)
        val result = SpeedEstimator.analyse("A", samples, distanceMetres = 10.0)
        // Closest approach is the middle of the run by construction.
        val middle = samples[samples.size / 2].atMs
        assertTrue(kotlin.math.abs(result.peakAtMs - middle) < 600)
    }
}
