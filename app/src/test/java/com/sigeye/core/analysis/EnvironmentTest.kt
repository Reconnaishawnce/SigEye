package com.sigeye.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentTest {

    private val minute = 60_000L

    @Test
    fun `a rate is addresses over the time they took to arrive`() {
        assertEquals(30.0, Environment.ratePerMinute(30, minute), 0.001)
        assertEquals(60.0, Environment.ratePerMinute(30, minute / 2), 0.001)
        assertEquals(0.0, Environment.ratePerMinute(30, 0), 0.001)
    }

    @Test
    fun `a quiet room, a street, a city and a train all come out differently`() {
        assertEquals(Density.QUIET, Environment.detect(3, minute))
        assertEquals(Density.SUBURBAN, Environment.detect(15, minute))
        assertEquals(Density.URBAN, Environment.detect(40, minute))
        assertEquals(Density.CROWDED, Environment.detect(200, minute))
    }

    @Test
    fun `six seconds of listening is refused rather than guessed at`() {
        // The moment a user is most likely to believe an answer is the moment it is least
        // likely to be right, so there isn't one until the window is long enough.
        assertNull(Environment.detect(2, 6_000L))
        assertNull(Environment.detect(500, 6_000L))
    }

    @Test
    fun `the boundaries are where they say they are`() {
        assertEquals(
            Density.SUBURBAN,
            Environment.detect(Environment.SUBURBAN_FROM.toInt(), minute),
        )
        assertEquals(
            Density.QUIET,
            Environment.detect(Environment.SUBURBAN_FROM.toInt() - 1, minute),
        )
        assertEquals(Density.CROWDED, Environment.detect(Environment.CROWDED_FROM.toInt(), minute))
    }

    // -------------------------------------------------------------------- the tuning

    @Test
    fun `a busier profile always waits longer before it says anything`() {
        val ordered = Density.entries.map { Environment.tuningFor(it) }
        ordered.zipWithNext { quieter, busier ->
            assertTrue(busier.baselineSeconds >= quieter.baselineSeconds)
            assertTrue(busier.burstMultiple >= quieter.burstMultiple)
            assertTrue(busier.minimumBurst >= quieter.minimumBurst)
            assertTrue(busier.residentMinutes >= quieter.residentMinutes)
        }
    }

    @Test
    fun `a busier profile drops a silent device sooner, not later`() {
        // The one value that moves the other way: where a hundred devices are in range,
        // something that has not spoken for twenty seconds has almost certainly gone.
        val ordered = Density.entries.map { Environment.tuningFor(it) }
        ordered.zipWithNext { quieter, busier ->
            assertTrue(busier.freshnessSeconds <= quieter.freshnessSeconds)
        }
    }

    @Test
    fun `every profile produces usable numbers rather than zeroes`() {
        Density.entries.forEach { density ->
            val tuning = Environment.tuningFor(density)
            assertTrue(density.name, tuning.baselineSeconds > 0)
            assertTrue(density.name, tuning.burstMultiple > 1.0)
            assertTrue(density.name, tuning.minimumBurst > 0)
            assertTrue(density.name, tuning.residentMinutes > 0)
            assertTrue(density.name, tuning.devicesPerPerson > 0.0)
            assertTrue(density.name, tuning.freshnessSeconds > 0)
            assertTrue(density.name, density.blurb.isNotBlank())
        }
    }

    @Test
    fun `the default is the middle of the road, not the extreme`() {
        assertEquals(Density.SUBURBAN, Environment.DEFAULT)
    }

    @Test
    fun `the detector shows its working`() {
        val described = Environment.describe(40, minute)
        assertTrue(described, described.contains("40 new addresses a minute"))
        assertTrue(described, described.contains("60 seconds"))
        assertEquals("nothing heard yet", Environment.describe(0, 0))
    }
}
