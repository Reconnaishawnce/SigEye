package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A follow is one continuous thing and the number it shows can only fall.
 *
 * That is the whole design, and it replaced a leg-based one that was wrong in a way a real
 * walk made obvious: the count climbed as you went. These tests are written as walks
 * through real time, because the thing being tested is behaviour over minutes.
 */
class FollowTest {

    private val start = 1_700_000_000_000L
    private val minute = 60_000L

    private fun FollowSession.hear(address: String, atMs: Long, packets: Int = 5, rssi: Int = -60) {
        repeat(packets) { observe(address, rssi, atMs + it * 100L, null, null, true) }
    }

    /** Baseline, then start following, with everything named audible throughout the census. */
    private fun following(vararg addresses: String): FollowSession = FollowSession().apply {
        startBaseline(start)
        addresses.forEach { hear(it, start) }
        endBaseline(start + 30_000L)
        startFollowing(start + 30_000L)
    }

    // ------------------------------------------------------------------ the pool

    @Test
    fun `the pool closes when the follow starts`() {
        val session = following("5A:01", "5A:02")

        // Met half a mile later. In the denominator, never on the list.
        session.hear("5A:99", start + 5 * minute)
        val state = session.state(start + 5 * minute + 1_000L)

        assertEquals(3, state.watching)
        assertEquals(2, state.poolSize)
        assertFalse(state.candidates.first { it.address == "5A:99" }.inPool)
    }

    @Test
    fun `nothing is eliminated during the baseline`() {
        // A baseline is a census, not a test. Dropping from it would be eliminating on the
        // strength of having stood still for half a minute.
        val session = FollowSession()
        session.startBaseline(start)
        session.hear("5A:01", start)
        session.hear("5A:02", start)

        val state = session.state(start + 10 * minute)

        assertTrue(state.dropped.isEmpty())
        assertNull(state.followStartedAtMs)
        assertTrue(state.narrowing().contains("has not started"))
    }

    // ------------------------------------------------------------------ the falling number

    @Test
    fun `the street falls away behind you and the count only goes down`() {
        val session = following("5A:01", "5A:02", "5A:03", "5A:04", "5A:05")
        val counts = mutableListOf<Int>()

        fun tick(atMs: Long, vararg stillAudible: String) {
            stillAudible.forEach { session.hear(it, atMs) }
            counts += session.state(atMs + 1_000L).stillIn.size
        }

        tick(start + 60_000L, "5A:01", "5A:02", "5A:03", "5A:04", "5A:05")
        tick(start + 120_000L, "5A:01", "5A:02", "5A:03")
        tick(start + 200_000L, "5A:01", "5A:02")
        tick(start + 300_000L, "5A:01")
        tick(start + 400_000L, "5A:01")

        assertEquals(listOf(5, 3, 2, 1, 1), counts)
        assertEquals(
            "it never climbed",
            counts,
            counts.sortedDescending(),
        )
    }

    @Test
    fun `standing still narrows nothing, and says so`() {
        // Everybody in the lobby survives the lobby. The screen has to admit that or it is
        // asserting a finding it does not have.
        val session = following("5A:01", "5A:02", "5A:03")
        repeat(10) { minutePassed ->
            listOf("5A:01", "5A:02", "5A:03").forEach {
                session.hear(it, start + 30_000L + minutePassed * 30_000L)
            }
        }

        val state = session.state(start + 30_000L + 10 * 30_000L)

        assertEquals(3, state.stillIn.size)
        assertTrue(state.narrowing().contains("not narrowed anything"))
    }

    @Test
    fun `a drop is permanent, and coming back is reported rather than undone`() {
        // A device that went quiet for a minute while you covered a quarter of a mile did
        // not come with you. Letting it back in would undo the only claim this makes.
        val session = following("5A:01", "5A:02")
        session.hear("5A:01", start + 60_000L)
        session.hear("5A:02", start + 60_000L)

        // 5A:01 keeps answering. 5A:02 goes quiet and is dropped.
        session.hear("5A:01", start + 110_000L)
        session.hear("5A:01", start + 170_000L)
        assertEquals(listOf("5A:01"), session.state(start + 171_000L).stillIn.map { it.address })

        // Then it turns up again. Noted, not reinstated.
        session.hear("5A:01", start + 230_000L)
        session.hear("5A:02", start + 230_000L)
        val state = session.state(start + 231_000L)

        assertEquals(listOf("5A:01"), state.stillIn.map { it.address })
        assertEquals(listOf("5A:02"), state.returned.map { it.address })
        assertNotNull(state.candidates.first { it.address == "5A:02" }.droppedAtMs)
    }

    @Test
    fun `the drop-off is a setting, and a shorter one cuts sooner`() {
        val patient = FollowSession(FollowTuning.DEFAULT.copy(dropAfterMs = 120_000L))
        val impatient = FollowSession(FollowTuning.DEFAULT.copy(dropAfterMs = 20_000L))

        listOf(patient, impatient).forEach { session ->
            session.startBaseline(start)
            session.hear("5A:01", start)
            session.endBaseline(start + 30_000L)
            session.startFollowing(start + 30_000L)
        }

        // Silent for a minute after the follow began.
        assertEquals(1, patient.state(start + 90_000L).stillIn.size)
        assertEquals(0, impatient.state(start + 90_000L).stillIn.size)
    }

    @Test
    fun `a device heard once is not a candidate at all`() {
        val session = following()
        session.hear("5A:01", start + 40_000L, packets = 1)
        session.hear("5A:02", start + 40_000L, packets = 10)

        assertEquals(
            listOf("5A:02"),
            session.state(start + 45_000L).candidates.map { it.address },
        )
    }

    @Test
    fun `nothing heard is nothing claimed`() {
        val state = FollowSession().state(start)

        assertEquals(0, state.watching)
        assertTrue(state.stillIn.isEmpty())
        assertNull(state.target)
        assertTrue(state.narrowing().contains("Nothing heard"))
    }

    // ------------------------------------------------------------------ thresholds

    @Test
    fun `fifteen or fewer is worth saving, five or fewer is a short list`() {
        val session = following(*(1..20).map { "5A:%02d".format(it) }.toTypedArray())
        fun surviving(count: Int, atMs: Long): FollowState {
            (1..count).forEach { session.hear("5A:%02d".format(it), atMs) }
            return session.state(atMs + 1_000L)
        }

        val room = surviving(20, start + 45_000L)
        assertFalse("twenty is a room", room.listable)
        assertFalse(room.narrowed)

        val handful = surviving(12, start + 105_000L)
        assertTrue("twelve is worth saving", handful.listable)
        assertFalse("but it is not a short list", handful.narrowed)

        val few = surviving(4, start + 200_000L)
        assertTrue(few.listable)
        assertTrue("four is a short list", few.narrowed)
        assertEquals(4, few.shortlist.size)
    }

    @Test
    fun `the thresholds are settings too`() {
        val session = FollowSession(FollowTuning.DEFAULT.copy(shortlistMax = 2, listableAt = 3))
        session.startBaseline(start)
        listOf("5A:01", "5A:02", "5A:03").forEach { session.hear(it, start) }
        session.endBaseline(start + 30_000L)
        session.startFollowing(start + 30_000L)

        val state = session.state(start + 35_000L)

        assertTrue("three is listable under a threshold of three", state.listable)
        assertFalse("but not a short list of two", state.narrowed)
    }

    // ------------------------------------------------------------------ arrivals

    @Test
    fun `a device that turns up after the baseline is marked as an arrival`() {
        val session = FollowSession()
        session.startBaseline(start)
        session.hear("5A:01", start)
        session.endBaseline(start + 30_000L)

        // They walk in. The follow starts when they do, so they are in the pool.
        session.hear("5A:07", start + 60_000L)
        session.startFollowing(start + 65_000L)

        val byAddress = session.state(start + 70_000L).candidates.associateBy { it.address }

        assertFalse(byAddress.getValue("5A:01").arrived)
        assertTrue(byAddress.getValue("5A:07").arrived)
        assertTrue(byAddress.getValue("5A:07").inPool)
    }

    // ------------------------------------------------------------------ starting again

    @Test
    fun `a follow that has got nowhere suggests starting again`() {
        val session = following(*(1..20).map { "5A:%02d".format(it) }.toTypedArray())
        var at = start + 30_000L
        repeat(12) {
            at += 30_000L
            (1..20).forEach { session.hear("5A:%02d".format(it), at) }
        }

        assertTrue(session.state(at).shouldRebaseline)
    }

    @Test
    fun `a follow that did narrow down is left alone`() {
        val session = following("5A:01", "5A:02")
        var at = start + 30_000L
        repeat(12) {
            at += 30_000L
            session.hear("5A:01", at)
        }

        val state = session.state(at)
        assertTrue(state.narrowed)
        assertFalse(state.shouldRebaseline)
    }

    @Test
    fun `re-baselining reopens the pool without forgetting the room`() {
        val session = following("5A:01", "5A:02")
        session.hear("5A:01", start + 60_000L)
        // 5A:02 has been silent since the baseline and is out; 5A:01 was heard forty
        // seconds ago and is not.
        assertEquals(1, session.state(start + 100_000L).stillIn.size)

        session.rebaseline(start + 110_000L)
        val after = session.state(start + 110_000L)

        assertEquals("nobody forgotten", 2, after.candidates.size)
        assertEquals("and nobody in the pool until it starts again", 0, after.poolSize)
        assertEquals(FollowPhase.BASELINE, after.phase)
        assertFalse(after.shouldRebaseline)
    }

    // ------------------------------------------------------------------ holding

    @Test
    fun `locking a candidate moves the follow to holding`() {
        val session = following("5A:01")
        session.lock("5A:01")

        val state = session.state(start + 35_000L)

        assertEquals(FollowPhase.HOLDING, state.phase)
        assertEquals("5A:01", state.target!!.address)
    }

    @Test
    fun `a target that has gone quiet is reported lost, not still held`() {
        val session = following("5A:01")
        session.lock("5A:01")

        val state = session.state(start + 5 * minute)

        assertEquals(FollowPhase.LOST, state.phase)
        assertTrue(state.silentForMs > FollowTuning.DEFAULT.lostAfterMs)
    }

    @Test
    fun `a couple of dropped packets is not being lost`() {
        val session = following("5A:01")
        session.lock("5A:01")
        session.hear("5A:01", start + 40_000L)

        // Ten seconds of silence against a forty-five second threshold.
        assertEquals(FollowPhase.HOLDING, session.state(start + 50_000L).phase)
    }

    // ------------------------------------------------------------------ rotation

    @Test
    fun `a re-acquired target carries its history and its place in the pool`() {
        val session = following("5A:01")
        session.hear("5A:01", start + 60_000L)
        session.lock("5A:01")

        // A new address appears mid-follow. On its own it would be out of the pool.
        session.hear("5B:02", start + 80_000L)
        assertFalse(session.state(start + 85_000L).candidates.first { it.address == "5B:02" }.inPool)

        session.reacquire("5B:02", start + 80_000L)
        val target = session.state(start + 85_000L).target!!

        assertEquals("5B:02", target.address)
        assertEquals(listOf("5A:01", "5B:02"), target.addresses)
        assertEquals(1, target.rotations)
        assertTrue("a rotation is the one way in mid-follow", target.inPool)
        assertTrue(target.stillIn)
    }

    @Test
    fun `nothing is predicted about a return until a rotation has been watched`() {
        val session = following("5A:01")
        session.lock("5A:01")

        assertNull(session.state(start + 35_000L).expectedReturnMs)
    }

    @Test
    fun `one rotation is enough to expect the next, on the specification default`() {
        val session = following("5A:01")
        session.lock("5A:01")
        session.hear("5B:02", start + 100_000L)
        session.reacquire("5B:02", start + 100_000L)

        val state = session.state(start + 110_000L)

        assertEquals(start + 100_000L + RotationRhythm.SPEC_DEFAULT_MS, state.expectedReturnMs)
        assertNull("one change is not a rhythm", state.rhythm)
    }

    @Test
    fun `a measured rhythm beats the default once there is one`() {
        val session = following("5A:01")
        session.lock("5A:01")

        var at = start + 30_000L
        listOf("5B:02", "5C:03", "5D:04", "5E:05").forEach { address ->
            at += 300_000L
            session.hear(address, at)
            session.reacquire(address, at)
        }

        val state = session.state(at + 10_000L)

        assertEquals(300_000L, state.rhythm!!.medianPeriodMs)
        assertEquals(at + 300_000L, state.expectedReturnMs)
    }

    @Test
    fun `re-acquiring something never heard from changes nothing`() {
        val session = following("5A:01")
        session.lock("5A:01")
        session.reacquire("FF:FF", start + 40_000L)

        assertEquals("5A:01", session.state(start + 42_000L).target!!.address)
    }

    // ------------------------------------------------------------------ the export

    // ------------------------------------------------------------------ picking it up

    @Test
    fun `time nobody was listening does not count against anybody`() {
        // The worst thing this could get wrong. Put the phone away for five minutes and
        // every device in the pool goes silent at once - not because they left but because
        // nothing was listening - and coming back to an empty list would be the most
        // confidently wrong the app could be.
        val session = following("5A:01", "5A:02")
        session.hear("5A:01", start + 40_000L)
        session.hear("5A:02", start + 40_000L)

        session.pause(start + 45_000L)
        session.resume(start + 5 * minute)

        // Five minutes of wall clock has passed and five seconds of listening.
        assertEquals(2, session.state(start + 5 * minute).stillIn.size)

        // And the drop-off still works once it is listening again.
        session.hear("5A:01", start + 5 * minute + 1_000L)
        assertEquals(1, session.state(start + 6 * minute + 10_000L).stillIn.size)
    }

    @Test
    fun `a follow survives being written down and read back`() {
        val session = following("5A:01", "5A:02")
        session.hear("5A:01", start + 40_000L)
        session.beginProbe(Probe.WALK_BY, start + 50_000L)
        session.hear("5A:01", start + 55_000L)
        session.markClosest(start + 58_000L)
        session.endProbe(start + 65_000L)
        val before = session.state(start + 70_000L)

        val restored = FollowSession()
        restored.restore(session.snapshot())
        val after = restored.state(start + 70_000L)

        assertEquals(before.watching, after.watching)
        assertEquals(before.poolSize, after.poolSize)
        assertEquals(before.stillIn.map { it.address }, after.stillIn.map { it.address })
        assertEquals(1, after.walkBys.size)
        assertEquals(
            "the trace comes back too, or a saved walk-by cannot be checked",
            before.walkByResults(0).first().second.trail,
            after.walkByResults(0).first().second.trail,
        )
    }

    // ------------------------------------------------------------------ several probes

    @Test
    fun `a second walk-by is a second walk-by, not a refusal`() {
        val session = following("5A:01")

        session.beginProbe(Probe.WALK_BY, start + 40_000L)
        session.hear("5A:01", start + 45_000L)
        session.markClosest(start + 48_000L)
        session.endProbe(start + 55_000L)

        session.beginProbe(Probe.WALK_BY, start + 70_000L)
        session.hear("5A:01", start + 75_000L)
        session.markClosest(start + 78_000L)
        session.endProbe(start + 85_000L)

        val state = session.state(start + 90_000L)

        assertEquals(2, state.walkBys.size)
        assertEquals(2, state.candidates.first().walkBys.size)
        // Each keeps its own readings, or the second would be drawn from the first's trace.
        assertEquals(listOf(0, 1), state.candidates.first().walkBys.map { it.probeIndex })
    }

    @Test
    fun `the headline result is the latest, not the best`() {
        // A second walk-by is usually done because the first was unconvincing. Quietly
        // reporting whichever came out better would turn "try it again" into "keep trying
        // until it passes".
        val session = following("5A:01")

        session.beginProbe(Probe.WALK_BY, start + 40_000L)
        repeat(20) { session.observe("5A:01", if (it in 8..11) -40 else -85, start + 40_000L + it * 1_000L, null, null, true) }
        session.markClosest(start + 50_000L)
        session.endProbe(start + 60_000L)
        assertTrue("the first one passed", session.state(start + 61_000L).candidates.first().walkBy!!.passed)

        session.beginProbe(Probe.WALK_BY, start + 70_000L)
        repeat(20) { session.observe("5A:01", -70, start + 70_000L + it * 1_000L, null, null, true) }
        session.markClosest(start + 80_000L)
        session.endProbe(start + 90_000L)

        val candidate = session.state(start + 91_000L).candidates.first()
        assertFalse("and the headline is now the flat second one", candidate.walkBy!!.passed)
        assertTrue("but passing one of them still counts for something", candidate.passedAnyWalkBy)
    }

    @Test
    fun `the export carries the tuning, the probes and the verdicts`() {
        val session = following("5A:01")
        session.beginProbe(Probe.WALK_BY, start + 40_000L)
        session.markClosest(start + 60_000L)
        session.endProbe(start + 80_000L)

        val csv = session.csv()

        assertTrue(csv.contains("drop_after_ms,60000"))
        assertTrue(csv.contains("WALK_BY"))
        assertTrue(csv.contains("5A:01"))
        assertTrue(csv.contains("still_in"))
    }
}
