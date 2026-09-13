package com.sigeye.core.analysis.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvoyTest {

    private val minute = 60_000L

    /** Puts a device firmly inside the current leg. */
    private fun ConvoyTracker.present(
        address: String,
        atMs: Long,
        packets: Int = 5,
        rssi: Int = -60,
        isRandom: Boolean = false,
    ) {
        repeat(packets) { step ->
            observe(address, rssi, atMs + step * 1_000L, isRandom = isRandom)
        }
    }

    /** Three legs half an hour apart, each with its own locals. */
    private fun journey(): ConvoyTracker {
        val tracker = ConvoyTracker()
        tracker.startLeg("Home", 0L)
        tracker.present("FOLLOWER", 0L)
        repeat(5) { tracker.present("HOME$it", 0L) }
        tracker.closeLeg(5 * minute)

        tracker.startLeg("Work", 30 * minute)
        tracker.present("FOLLOWER", 30 * minute)
        repeat(5) { tracker.present("WORK$it", 30 * minute) }
        tracker.closeLeg(35 * minute)

        tracker.startLeg("Cafe", 60 * minute)
        tracker.present("FOLLOWER", 60 * minute)
        repeat(5) { tracker.present("CAFE$it", 60 * minute) }
        tracker.closeLeg(65 * minute)
        return tracker
    }

    // -------------------------------------------------------------- the basics

    @Test
    fun `something in every leg is the one that stands out`() {
        val report = journey().report()
        assertEquals(listOf("FOLLOWER"), report.candidates.map { it.address })
        assertEquals(FollowConfidence.STRONG, report.candidates.first().confidence)
        assertTrue(report.candidates.first().everyLeg)
    }

    @Test
    fun `locals that appear in only one leg are not followers`() {
        val report = journey().report()
        assertTrue(report.followers.none { it.address.startsWith("HOME") })
        assertTrue(report.followers.none { it.address.startsWith("CAFE") })
    }

    @Test
    fun `two legs out of three is noticed but not called a convoy`() {
        val tracker = journey()
        tracker.startLeg("Shop", 90 * minute)
        repeat(5) { tracker.present("SHOP$it", 90 * minute) }
        tracker.closeLeg(95 * minute)

        val follower = tracker.report().candidates.first { it.address == "FOLLOWER" }
        assertEquals(3, follower.legsShared)
        assertEquals(4, follower.totalLegs)
        assertTrue(!follower.everyLeg)
        assertEquals(FollowConfidence.NOTABLE, follower.confidence)
    }

    @Test
    fun `your own things are excluded rather than alarming you`() {
        // Without this the list is forty rows of your own earbuds and the feature is
        // useless, which is worse than it being absent.
        val tracker = journey()
        tracker.startLeg("Home again", 90 * minute)
        tracker.present("MY_WATCH", 90 * minute)
        tracker.closeLeg(95 * minute)
        tracker.startLeg("Out", 120 * minute)
        tracker.present("MY_WATCH", 120 * minute)
        tracker.closeLeg(125 * minute)

        val report = tracker.report(mine = setOf("my_watch"))
        val watch = report.followers.first { it.address == "MY_WATCH" }
        assertTrue(watch.yours)
        assertEquals(FollowConfidence.NONE, watch.confidence)
        assertTrue(report.candidates.none { it.address == "MY_WATCH" })
        assertTrue(watch.reasons().first().contains("one of yours"))
    }

    @Test
    fun `a single stray packet does not make something a travelling companion`() {
        val tracker = ConvoyTracker()
        tracker.startLeg("A", 0L)
        tracker.present("SOLID", 0L)
        tracker.observe("STRAY", -95, 1_000L)
        tracker.closeLeg(5 * minute)
        tracker.startLeg("B", 30 * minute)
        tracker.present("SOLID", 30 * minute)
        tracker.observe("STRAY", -95, 30 * minute + 1_000L)
        tracker.closeLeg(35 * minute)

        val report = tracker.report()
        assertTrue(report.followers.none { it.address == "STRAY" })
        assertTrue(report.followers.any { it.address == "SOLID" })
    }

    // --------------------------------------------------- randomised addresses

    @Test
    fun `a randomised address can never rate above weak`() {
        // It is a different address every quarter of an hour. Seeing the same one in
        // three legs means it is not rotating, or the legs were close together, and
        // nothing here can tell those apart - so it must not be reported as certainty.
        val tracker = ConvoyTracker()
        listOf(0L, 30 * minute, 60 * minute).forEachIndexed { index, at ->
            tracker.startLeg("Leg $index", at)
            tracker.present("ROTATOR", at, isRandom = true)
            repeat(5) { tracker.present("LOCAL$index$it", at) }
            tracker.closeLeg(at + 5 * minute)
        }

        val rotator = tracker.report().followers.first { it.address == "ROTATOR" }
        assertEquals(3, rotator.legsShared)
        assertEquals(FollowConfidence.WEAK, rotator.confidence)
        assertTrue(rotator.reasons().any { it.contains("randomised") })
    }

    @Test
    fun `a fixed address says plainly that it really is the same device`() {
        val follower = journey().report().candidates.first()
        assertTrue(follower.reasons().any { it.contains("really is the same device") })
    }

    // -------------------------------------------------------------- base rates

    @Test
    fun `one leg draws no conclusions`() {
        val tracker = ConvoyTracker()
        tracker.startLeg("Only", 0L)
        repeat(10) { tracker.present("D$it", 0L) }
        tracker.closeLeg(5 * minute)

        val verdict = tracker.report().verdict()
        assertNotNull(verdict)
        assertTrue(verdict!!.contains("One leg so far"))
    }

    @Test
    fun `two legs a minute apart are refused rather than charted`() {
        // The failure this prevents: recording twice in the same room and being told that
        // the whole room is following you.
        val tracker = ConvoyTracker()
        tracker.startLeg("A", 0L)
        repeat(10) { tracker.present("ROOM$it", 0L) }
        tracker.closeLeg(2 * minute)
        tracker.startLeg("B", 3 * minute)
        repeat(10) { tracker.present("ROOM$it", 3 * minute) }
        tracker.closeLeg(5 * minute)

        val verdict = tracker.report().verdict()
        assertNotNull(verdict)
        assertTrue(verdict!!.contains("give it longer"))
    }

    @Test
    fun `a very short leg is called out, because absence would mean nothing`() {
        val tracker = ConvoyTracker()
        tracker.startLeg("Proper", 0L)
        repeat(10) { tracker.present("D$it", 0L) }
        tracker.closeLeg(10 * minute)
        tracker.startLeg("Rushed", 40 * minute)
        tracker.present("D0", 40 * minute)
        tracker.closeLeg(40 * minute + 10_000L)

        assertTrue(tracker.report().verdict()!!.contains("very short"))
    }

    @Test
    fun `somewhere too quiet cannot distinguish a follower from the only thing there`() {
        val tracker = ConvoyTracker()
        listOf(0L, 30 * minute).forEachIndexed { index, at ->
            tracker.startLeg("Leg $index", at)
            tracker.present("LONELY", at)
            tracker.closeLeg(at + 5 * minute)
        }
        assertTrue(tracker.report().verdict()!!.contains("cannot"))
    }

    @Test
    fun `a proper journey has nothing to complain about`() {
        assertNull(journey().report().verdict())
    }

    // ---------------------------------------------------------------- details

    @Test
    fun `a gap in the middle is counted and reported`() {
        val tracker = ConvoyTracker()
        listOf(0L, 30 * minute, 60 * minute, 90 * minute).forEachIndexed { index, at ->
            tracker.startLeg("Leg $index", at)
            // Absent from leg two only.
            if (index != 2) tracker.present("ONOFF", at)
            repeat(5) { tracker.present("LOCAL$index$it", at) }
            tracker.closeLeg(at + 5 * minute)
        }

        val follower = tracker.report().followers.first { it.address == "ONOFF" }
        assertEquals(3, follower.legsShared)
        assertEquals(1, follower.gaps)
        assertTrue(follower.reasons().any { it.contains("Absent for 1 leg") })
    }

    @Test
    fun `the span covers first sighting to last, across every leg`() {
        val follower = journey().report().candidates.first()
        // First leg starts at zero, last leg runs to about 65 minutes.
        assertTrue("span was ${follower.spanMs}", follower.spanMs >= 60 * minute)
    }

    @Test
    fun `starting a leg closes the one before it`() {
        val tracker = ConvoyTracker()
        tracker.startLeg("A", 0L)
        tracker.present("X", 0L)
        tracker.startLeg("B", 30 * minute)
        assertEquals(2, tracker.legCount)
        assertEquals("B", tracker.currentLabel)

        // Anything arriving now belongs to B, not to A.
        tracker.present("X", 30 * minute)
        val follower = tracker.report().followers.first { it.address == "X" }
        assertEquals(listOf(0, 1), follower.presences.map { it.legIndex })
    }

    @Test
    fun `nothing is recorded between legs`() {
        val tracker = ConvoyTracker()
        tracker.startLeg("A", 0L)
        tracker.present("X", 0L)
        tracker.closeLeg(5 * minute)
        tracker.present("GHOST", 10 * minute)
        assertTrue(tracker.report().followers.none { it.address == "GHOST" })
        assertTrue(!tracker.isRecording)
    }

    @Test
    fun `reset forgets the whole journey`() {
        val tracker = journey()
        tracker.reset()
        assertEquals(0, tracker.legCount)
        assertTrue(tracker.report().followers.isEmpty())
    }
}
