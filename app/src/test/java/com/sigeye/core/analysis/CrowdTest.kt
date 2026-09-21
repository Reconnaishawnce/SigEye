package com.sigeye.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Doing less when there is more, so a busy place stays readable.
 *
 * Written after an airport produced a thousand devices in thirty seconds. Almost everything
 * in this app was built and tested in a quiet room, which is a different problem: a radar
 * with twenty-four dots is a picture and the same radar in a terminal is a smear.
 */
class CrowdTest {

    @Test
    fun `a room, a cafe and an airport are told apart`() {
        assertEquals(Density.ROOM, Crowd.densityOf(20))
        assertEquals(Density.BUSY, Crowd.densityOf(Crowd.BUSY_DEVICES))
        assertEquals(Density.PACKED, Crowd.densityOf(Crowd.PACKED_DEVICES))
        assertEquals(Density.PACKED, Crowd.densityOf(1_200))
    }

    @Test
    fun `a handful of very loud devices counts as packed too`() {
        // The two go wrong separately. A wall of beacons is a high rate from few devices,
        // and a terminal full of sleeping phones is many devices at a modest rate. Either
        // is a reason to simplify.
        assertEquals(Density.PACKED, Crowd.densityOf(devices = 30, advertsPerSecond = 900.0))
    }

    @Test
    fun `the radar draws fewer dots when packed, not more`() {
        // Counter-intuitive and deliberate. The radar is for seeing one device move
        // relative to the others, and past about a dozen overlapping dots nobody can.
        assertTrue(Crowd.radarBlips(Density.PACKED) < Crowd.radarBlips(Density.ROOM))
        assertTrue(Crowd.radarBlips(Density.PACKED) >= 10)
    }

    @Test
    fun `the every-pair comparison is capped harder in a crowd`() {
        // The work grows with the square. Twenty devices is a hundred and ninety
        // comparisons; five hundred is a hundred and twenty thousand, several times a
        // second, on a phone in somebody's hand.
        assertTrue(Crowd.companionLimit(Density.PACKED) < Crowd.companionLimit(Density.ROOM))
    }

    @Test
    fun `the pool is only thinned when the place is genuinely packed`() {
        // The one adjustment that changes what is being followed rather than how it is
        // drawn, so it needs the strongest condition.
        assertTrue(Crowd.thinsThePool(Density.PACKED))
        assertTrue(!Crowd.thinsThePool(Density.BUSY))
        assertTrue(!Crowd.thinsThePool(Density.ROOM))
    }

    @Test
    fun `a quiet room is not told it is a quiet room`() {
        // A note that appears everywhere is a note nobody reads.
        assertNull(Crowd.describe(Density.ROOM, 12))
    }

    @Test
    fun `the note is one plain sentence with the number in it`() {
        val busy = Crowd.describe(Density.BUSY, 90)!!
        val packed = Crowd.describe(Density.PACKED, 800)!!

        assertTrue(busy.contains("90"))
        assertTrue(packed.contains("800"))
        // Short enough to read while walking. Nobody standing in a terminal wants a
        // paragraph about scan duty cycles.
        assertTrue("too long: $busy", busy.length < 110)
        assertTrue("too long: $packed", packed.length < 110)
    }

    @Test
    fun `nothing here changes what gets counted`() {
        // Every limit is about what a person can read or a phone can finish. A device is
        // never dropped from a count because the room is busy, and if that ever changes
        // this test is where somebody should have to argue for it.
        Density.entries.forEach { density ->
            assertTrue(Crowd.radarBlips(density) > 0)
            assertTrue(Crowd.companionLimit(density) > 0)
        }
    }
}
