package com.sigeye.core.analysis.presence

import com.sigeye.core.analysis.record.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvoyPersistenceTest {

    private val minute = 60_000L

    private fun ConvoyTracker.present(address: String, atMs: Long, packets: Int = 5) {
        repeat(packets) { step -> observe(address, -60, atMs + step * 1_000L) }
    }

    private fun journey(): ConvoyTracker {
        val tracker = ConvoyTracker()
        tracker.startLeg("Home", 0L)
        tracker.present("FOLLOWER", 0L)
        repeat(5) { tracker.present("HOME$it", 0L) }
        tracker.closeLeg(5 * minute)

        tracker.startLeg("Work", 40 * minute)
        tracker.present("FOLLOWER", 40 * minute)
        repeat(5) { tracker.present("WORK$it", 40 * minute) }
        tracker.closeLeg(45 * minute)
        return tracker
    }

    @Test
    fun `a journey survives being written out and read back`() {
        val before = journey().report()
        val restored = ConvoyTracker()
        restored.restore(journey().export())
        val after = restored.report()

        assertEquals(before.legs.size, after.legs.size)
        assertEquals(before.devicesSeen, after.devicesSeen)
        assertEquals(
            before.candidates.map { it.address },
            after.candidates.map { it.address },
        )
    }

    @Test
    fun `restoring keeps the leg labels and timings that the verdict depends on`() {
        val restored = ConvoyTracker()
        restored.restore(journey().export())
        val report = restored.report()

        assertEquals(listOf("Home", "Work"), report.legs.map { it.label })
        // The span is what decides whether the journey is worth believing at all.
        assertEquals(45 * minute, report.totalSpan())
        assertTrue(report.legs.all { it.durationMs >= ConvoyReport.MIN_LEG_MS })
    }

    @Test
    fun `restoring replaces whatever was there rather than appending`() {
        val tracker = journey()
        tracker.restore(journey().export())
        assertEquals(2, tracker.legCount)
        assertTrue(!tracker.isRecording)
    }

    @Test
    fun `a restored journey can be carried on with`() {
        val tracker = ConvoyTracker()
        tracker.restore(journey().export())
        tracker.startLeg("Cafe", 80 * minute)
        tracker.present("FOLLOWER", 80 * minute)
        repeat(5) { tracker.present("CAFE$it", 80 * minute) }
        tracker.closeLeg(85 * minute)

        val follower = tracker.report().candidates.first()
        assertEquals("FOLLOWER", follower.address)
        assertEquals(3, follower.legsShared)
        assertEquals(FollowConfidence.STRONG, follower.confidence)
    }

    // ------------------------------------------------------ legs from snapshots

    @Test
    fun `a saved place can stand in as a leg`() {
        // The point: a snapshot from home last week has real separation from a leg
        // recorded now, which is the hardest thing to arrange live.
        val tracker = ConvoyTracker()
        tracker.addLegFromSnapshot(
            label = "Home last week",
            atMs = 0L,
            devices = listOf(
                Sighting(address = "FOLLOWER", rssi = -60, bestRssi = -55, sightings = 30),
                Sighting(address = "FRIDGE", rssi = -70, bestRssi = -70, sightings = 30),
            ),
        )
        tracker.startLeg("Today", 7 * 24 * 3_600_000L)
        tracker.present("FOLLOWER", 7 * 24 * 3_600_000L)
        repeat(5) { tracker.present("STREET$it", 7 * 24 * 3_600_000L) }
        tracker.closeLeg(7 * 24 * 3_600_000L + 5 * minute)

        val report = tracker.report()
        assertEquals(2, report.legs.size)
        assertEquals(listOf("FOLLOWER"), report.candidates.map { it.address })
    }

    @Test
    fun `a snapshot leg does not swallow the leg being recorded`() {
        val tracker = ConvoyTracker()
        tracker.startLeg("Live", 0L)
        tracker.present("X", 0L)
        tracker.addLegFromSnapshot("Snapshot", 60 * minute, emptyList())
        // The live leg was closed, so nothing further belongs to it.
        assertTrue(!tracker.isRecording)
        assertEquals(2, tracker.legCount)
    }

    // --------------------------------------------------------------- exporting

    @Test
    fun `the export names every device in every leg`() {
        val csv = journey().csv()
        assertTrue(csv.contains("leg,leg_label,address,label,packets,best_rssi_dbm,random"))
        assertTrue(csv.contains("FOLLOWER"))
        assertTrue(csv.contains("Home"))
        assertTrue(csv.contains("Work"))
    }

    @Test
    fun `a label containing a comma cannot break the export`() {
        val tracker = ConvoyTracker()
        tracker.startLeg("Home, upstairs", 0L)
        tracker.observe("A", -60, 1_000L, label = "Bob, the printer")
        tracker.closeLeg(minute)
        tracker.csv().trim().lines().drop(2).forEach {
            assertEquals("wrong column count in: $it", 7, it.split(',').size)
        }
    }

    @Test
    fun `an empty journey exports a header and nothing else`() {
        assertEquals(2, ConvoyTracker().csv().trim().lines().size)
    }
}
