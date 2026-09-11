package com.sigeye.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotTest {

    private fun device(
        address: String,
        rssi: Int = -60,
        isRandom: Boolean = false,
        name: String? = null,
    ) = Sighting(
        address = address,
        name = name,
        isRandom = isRandom,
        rssi = rssi,
        bestRssi = rssi,
        sightings = 10,
    )

    private fun snapshot(label: String, vararg devices: Sighting) =
        Snapshot(label, takenAtMs = 0L, devices = devices.toList())

    @Test
    fun `a device present only afterwards has arrived`() {
        val diff = Snapshots.diff(
            snapshot("Monday", device("AA")),
            snapshot("Tuesday", device("AA"), device("BB")),
        )
        assertEquals(1, diff.arrived.size)
        assertEquals("BB", diff.arrived.first().sighting.address)
        assertTrue(diff.departed.isEmpty())
        assertEquals(1, diff.stayed.size)
    }

    @Test
    fun `a device present only beforehand has gone`() {
        val diff = Snapshots.diff(
            snapshot("Monday", device("AA"), device("BB")),
            snapshot("Tuesday", device("AA")),
        )
        assertEquals(1, diff.departed.size)
        assertEquals("BB", diff.departed.first().sighting.address)
    }

    @Test
    fun `randomised addresses are left out rather than reported as intruders`() {
        // The failure this prevents: two scans of the same untouched room, every phone in
        // it having rotated its address, reported as a houseful of arrivals.
        val diff = Snapshots.diff(
            snapshot("Monday", device("AA"), device("R1", isRandom = true)),
            snapshot("Tuesday", device("AA"), device("R2", isRandom = true)),
        )
        assertTrue(diff.arrived.isEmpty())
        assertTrue(diff.departed.isEmpty())
        assertEquals(1, diff.untrackableBefore)
        assertEquals(1, diff.untrackableAfter)
        assertTrue(diff.summary().contains("randomised"))
    }

    @Test
    fun `a device that got substantially stronger is called out as closer`() {
        val diff = Snapshots.diff(
            snapshot("Before", device("AA", rssi = -80)),
            snapshot("After", device("AA", rssi = -55)),
        )
        assertEquals(1, diff.movedCloser.size)
        assertEquals(25, diff.movedCloser.first().deltaDb)
        assertTrue(diff.movedAway.isEmpty())
    }

    @Test
    fun `a small change is the room, not a movement`() {
        // Multipath alone moves a stationary link several dB - the whole point of the
        // fading experiment - so a handful of dB is not evidence of anything.
        val diff = Snapshots.diff(
            snapshot("Before", device("AA", rssi = -60)),
            snapshot("After", device("AA", rssi = -56)),
        )
        assertTrue(diff.movedCloser.isEmpty())
        assertTrue(diff.movedAway.isEmpty())
    }

    @Test
    fun `a device that got weaker is called out as further away`() {
        val diff = Snapshots.diff(
            snapshot("Before", device("AA", rssi = -50)),
            snapshot("After", device("AA", rssi = -75)),
        )
        assertEquals(1, diff.movedAway.size)
        assertEquals(-25, diff.movedAway.first().deltaDb)
    }

    @Test
    fun `an arrival has no before reading and a departure has no after`() {
        val diff = Snapshots.diff(
            snapshot("Before", device("GONE")),
            snapshot("After", device("NEW")),
        )
        assertNull(diff.arrived.first().wasRssi)
        assertNull(diff.arrived.first().deltaDb)
        assertNull(diff.departed.first().nowRssi)
    }

    @Test
    fun `comparing a place with itself finds nothing`() {
        val room = snapshot("Room", device("AA"), device("BB"), device("CC"))
        val diff = Snapshots.diff(room, room)
        assertTrue(diff.arrived.isEmpty())
        assertTrue(diff.departed.isEmpty())
        assertEquals(3, diff.stayed.size)
    }

    // ------------------------------------------------- travelling with you

    @Test
    fun `something fixed in all three places was travelling with you`() {
        val home = snapshot("Home", device("FOLLOWER"), device("FRIDGE"))
        val work = snapshot("Work", device("FOLLOWER"), device("PRINTER"))
        val cafe = snapshot("Cafe", device("FOLLOWER"), device("TILL"))

        val common = Snapshots.commonTo(listOf(home, work, cafe))
        assertEquals(1, common.size)
        assertEquals("FOLLOWER", common.first().address)
    }

    @Test
    fun `a device in only two of three places is not following you`() {
        val a = snapshot("A", device("X"), device("Y"))
        val b = snapshot("B", device("X"))
        val c = snapshot("C", device("Y"))
        assertTrue(Snapshots.commonTo(listOf(a, b, c)).isEmpty())
    }

    @Test
    fun `a randomised address cannot be a follower, however often it appears`() {
        // It is a different address in every snapshot by construction, so matching on it
        // would be meaningless in both directions.
        val a = snapshot("A", device("R", isRandom = true))
        val b = snapshot("B", device("R", isRandom = true))
        assertTrue(Snapshots.commonTo(listOf(a, b)).isEmpty())
    }

    @Test
    fun `one place on its own says nothing about following`() {
        assertTrue(Snapshots.commonTo(listOf(snapshot("A", device("X")))).isEmpty())
        assertTrue(Snapshots.commonTo(emptyList()).isEmpty())
    }
}
