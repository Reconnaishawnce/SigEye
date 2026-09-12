package com.sigeye.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForensicHistoryTest {

    private fun device(address: String, random: Boolean = false) = SavedDevice(
        address = address,
        label = address,
        isRandom = random,
        packets = 20,
        peakRssi = -55,
        behaviour = "THROUGHOUT_STEADY",
    )

    private fun session(label: String, atMs: Long, vararg devices: SavedDevice) =
        SavedSession(label, atMs, 180_000L, devices.toList())

    private fun track(address: String, random: Boolean = false) = Track(
        address = address,
        label = address,
        vendor = null,
        isRandom = random,
        pings = (0 until 20).map { Ping(it * 1_000L, -55) },
        recordingStartMs = 0L,
        recordingEndMs = 20_000L,
    )

    // -------------------------------------------------------------- provenance

    @Test
    fun `a device from an earlier recording is recognised`() {
        val provenance = ForensicHistory.provenance(
            address = "AA",
            sessions = listOf(session("High Street", 1_000L, device("AA"))),
            snapshots = emptyList(),
        )
        assertTrue(provenance.seenBefore)
        assertEquals(1, provenance.encounters.size)
        assertTrue(provenance.headline().contains("High Street"))
    }

    @Test
    fun `snapshots count as history too`() {
        val snapshot = Snapshot(
            label = "Kitchen",
            takenAtMs = 500L,
            devices = listOf(Sighting(address = "AA", rssi = -60, sightings = 9)),
        )
        val provenance = ForensicHistory.provenance("AA", emptyList(), listOf(snapshot))
        assertTrue(provenance.seenBefore)
        assertTrue(provenance.encounters.first().where.contains("Kitchen"))
    }

    @Test
    fun `encounters come back newest first`() {
        val provenance = ForensicHistory.provenance(
            address = "AA",
            sessions = listOf(
                session("Old", 1_000L, device("AA")),
                session("Recent", 9_000L, device("AA")),
            ),
            snapshots = emptyList(),
        )
        assertEquals(listOf("Recent", "Old"), provenance.encounters.map { it.where })
    }

    @Test
    fun `the watchlist outranks everything else in the headline`() {
        val provenance = ForensicHistory.provenance(
            address = "AA",
            sessions = listOf(session("Street", 1_000L, device("AA"))),
            snapshots = emptyList(),
            nickname = "Blue van",
            lists = setOf("Vehicles"),
            watched = true,
        )
        assertEquals("On your watchlist", provenance.headline())
    }

    @Test
    fun `a nickname beats a bare history`() {
        val provenance = ForensicHistory.provenance(
            address = "AA",
            sessions = listOf(session("Street", 1_000L, device("AA"))),
            snapshots = emptyList(),
            nickname = "Blue van",
        )
        assertTrue(provenance.headline().contains("Blue van"))
    }

    @Test
    fun `never seen before is the last resort, not the first answer`() {
        val provenance = ForensicHistory.provenance("ZZ", emptyList(), emptyList())
        assertEquals("Never seen before", provenance.headline())
        assertTrue(!provenance.familiar)
    }

    @Test
    fun `being named is enough to be familiar without any history`() {
        val provenance = ForensicHistory.provenance(
            address = "AA",
            sessions = emptyList(),
            snapshots = emptyList(),
            lists = setOf("Mine"),
        )
        assertTrue(!provenance.seenBefore)
        assertTrue(provenance.familiar)
    }

    // ------------------------------------------------------------- comparison

    @Test
    fun `comparing finds what is new, gone and unchanged`() {
        val before = session("Monday", 0L, device("STAYED"), device("GONE"))
        val after = listOf(track("STAYED"), track("NEW"))

        val diff = ForensicHistory.diff(before, after)
        assertEquals(listOf("NEW"), diff.onlyAfter.map { it.address })
        assertEquals(listOf("GONE"), diff.onlyBefore.map { it.address })
        assertEquals(listOf("STAYED"), diff.inBoth.map { it.address })
    }

    @Test
    fun `randomised addresses are excluded rather than reported as churn`() {
        // Two recordings of the same street an hour apart would otherwise report every
        // phone in it as both departed and newly arrived.
        val before = session("Monday", 0L, device("FIXED"), device("R1", random = true))
        val after = listOf(track("FIXED"), track("R2", random = true))

        val diff = ForensicHistory.diff(before, after)
        assertTrue(diff.onlyAfter.isEmpty())
        assertTrue(diff.onlyBefore.isEmpty())
        assertEquals(1, diff.randomBefore)
        assertEquals(1, diff.randomAfter)
        assertTrue(diff.summary().contains("randomised"))
    }

    @Test
    fun `comparing a recording with itself finds no change`() {
        val tracks = listOf(track("A"), track("B"))
        val before = ForensicHistory.save("Now", 0L, 20_000L, tracks)
        val diff = ForensicHistory.diff(before, tracks)
        assertTrue(diff.onlyAfter.isEmpty())
        assertTrue(diff.onlyBefore.isEmpty())
        assertEquals(2, diff.inBoth.size)
    }

    @Test
    fun `saving keeps what a comparison needs and drops what it does not`() {
        val saved = ForensicHistory.save("Street", 5_000L, 20_000L, listOf(track("AA")))
        assertEquals("Street", saved.label)
        assertEquals(1, saved.size)
        assertEquals(20, saved.devices.first().packets)
        assertEquals(-55, saved.devices.first().peakRssi)
        assertEquals("THROUGHOUT_STEADY", saved.devices.first().behaviour)
    }

    @Test
    fun `address matching ignores case, because stores disagree about it`() {
        val before = session("Monday", 0L, device("aa:bb:cc"))
        val diff = ForensicHistory.diff(before, listOf(track("AA:BB:CC")))
        assertEquals(1, diff.inBoth.size)
        assertTrue(before.contains("AA:BB:CC"))
    }
}
