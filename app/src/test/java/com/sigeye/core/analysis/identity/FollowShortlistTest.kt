package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three things that turn a room into a short list: a baseline, arrivals, and knowing
 * when to give up and start again.
 */
class FollowShortlistTest {

    private val start = 1_700_000_000_000L

    private fun FollowSession.hear(address: String, atMs: Long, times: Int = 5) {
        repeat(times) { observe(address, -60, atMs + it * 100L, null, null, true) }
    }

    // ------------------------------------------------------------------ arrivals

    @Test
    fun `a device that turns up after the baseline is marked as an arrival`() {
        // The whole point of taking a baseline with the target out of the room. Whoever
        // walks in afterwards is a much shorter list than whoever is in the building.
        val session = FollowSession()
        session.beginLeg("baseline", LegKind.BASELINE, start)
        session.hear("AA:BB:CC:DD:EE:01", start)
        session.endBaseline(start + 30_000L)

        session.beginLeg("waiting", LegKind.STILL, start + 30_000L)
        session.hear("AA:BB:CC:DD:EE:02", start + 40_000L)

        val byAddress = session.candidates(start + 60_000L).associateBy { it.address }

        assertFalse(byAddress.getValue("AA:BB:CC:DD:EE:01").arrived)
        assertTrue(byAddress.getValue("AA:BB:CC:DD:EE:02").arrived)
        assertTrue(byAddress.getValue("AA:BB:CC:DD:EE:02").describe().contains("arrived"))
    }

    @Test
    fun `arriving is decided once, so a device that arrives and goes quiet stays an arrival`() {
        val session = FollowSession()
        session.beginLeg("baseline", LegKind.BASELINE, start)
        session.endBaseline(start + 30_000L)
        session.beginLeg("waiting", LegKind.STILL, start + 30_000L)
        session.hear("AA:BB:CC:DD:EE:03", start + 40_000L)

        // Heard again much later, long after the baseline. Still the same arrival.
        session.hear("AA:BB:CC:DD:EE:03", start + 400_000L)

        assertTrue(session.candidates(start + 400_000L).single().arrived)
    }

    @Test
    fun `nothing is an arrival before a baseline has been closed`() {
        // Otherwise every device in the room is "new", which is true and useless.
        val session = FollowSession()
        session.beginLeg("baseline", LegKind.BASELINE, start)
        session.hear("AA:BB:CC:DD:EE:04", start + 5_000L)

        assertFalse(session.candidates(start + 10_000L).single().arrived)
    }

    // ------------------------------------------------------------------ the short list

    @Test
    fun `a room is not a short list`() {
        val session = FollowSession()
        session.beginLeg("walk", LegKind.TOGETHER, start)
        repeat(12) { session.hear("AA:BB:CC:DD:EE:%02d".format(it), start) }

        val state = session.state(start + 10_000L)

        assertEquals(12, state.survivors)
        assertTrue("twelve is a room", state.shortlist.isEmpty())
        assertFalse(state.narrowed)
    }

    @Test
    fun `five or fewer is a short list`() {
        val session = FollowSession()
        session.beginLeg("walk", LegKind.TOGETHER, start)
        repeat(FollowSession.SHORTLIST_MAX) {
            session.hear("AA:BB:CC:DD:EE:%02d".format(it), start)
        }

        val state = session.state(start + 10_000L)

        assertEquals(FollowSession.SHORTLIST_MAX, state.shortlist.size)
        assertTrue(state.narrowed)
    }

    @Test
    fun `only survivors are on it`() {
        // A device that missed a leg is out, however loud it is. That is the elimination.
        val session = FollowSession()
        session.beginLeg("one", LegKind.TOGETHER, start)
        session.hear("AA:BB:CC:DD:EE:01", start)
        session.hear("AA:BB:CC:DD:EE:02", start)
        session.beginLeg("two", LegKind.TOGETHER, start + 60_000L)
        session.hear("AA:BB:CC:DD:EE:01", start + 60_000L)

        val state = session.state(start + 120_000L)

        assertEquals(listOf("AA:BB:CC:DD:EE:01"), state.shortlist.map { it.address })
    }

    // ------------------------------------------------------------------ starting again

    @Test
    fun `a session that has got nowhere for five minutes suggests starting again`() {
        // Almost always one thing: the target changed address partway through, so the
        // device being narrowed towards stopped existing.
        val session = FollowSession()
        session.beginLeg("baseline", LegKind.BASELINE, start)
        repeat(12) { session.hear("AA:BB:CC:DD:EE:%02d".format(it), start) }
        session.beginLeg("walk", LegKind.TOGETHER, start + 60_000L)
        repeat(12) { session.hear("AA:BB:CC:DD:EE:%02d".format(it), start + 60_000L) }

        assertFalse(session.state(start + 60_000L).shouldRebaseline)
        assertTrue(session.state(start + FollowSession.REBASELINE_AFTER_MS + 1_000L).shouldRebaseline)
    }

    @Test
    fun `a session that did narrow down is left alone`() {
        val session = FollowSession()
        session.beginLeg("baseline", LegKind.BASELINE, start)
        session.beginLeg("walk", LegKind.TOGETHER, start + 60_000L)
        session.hear("AA:BB:CC:DD:EE:01", start + 60_000L)

        val late = session.state(start + FollowSession.REBASELINE_AFTER_MS + 1_000L)

        assertTrue(late.narrowed)
        assertFalse(late.shouldRebaseline)
    }

    @Test
    fun `re-baselining puts every device back on equal terms without forgetting the room`() {
        val session = FollowSession()
        session.beginLeg("baseline", LegKind.BASELINE, start)
        session.hear("AA:BB:CC:DD:EE:01", start)
        session.hear("AA:BB:CC:DD:EE:02", start)
        session.beginLeg("walk", LegKind.TOGETHER, start + 60_000L)
        session.hear("AA:BB:CC:DD:EE:01", start + 60_000L)

        // Before: one survivor, because the other missed the walk.
        assertEquals(1, session.state(start + 90_000L).survivors)

        session.rebaseline(start + 100_000L)
        val after = session.state(start + 100_000L)

        assertEquals("no legs, so nothing has been eliminated", 0, after.legs.size)
        assertEquals("and nobody has been forgotten", 2, after.candidates.size)
        assertEquals(FollowPhase.CENSUS, after.phase)
        assertFalse(after.shouldRebaseline)
    }

    @Test
    fun `re-baselining moves what counts as arriving`() {
        // A device first heard an hour ago is not news now, whatever it was before.
        val session = FollowSession()
        session.beginLeg("baseline", LegKind.BASELINE, start)
        session.endBaseline(start + 30_000L)
        session.beginLeg("waiting", LegKind.STILL, start + 30_000L)
        session.hear("AA:BB:CC:DD:EE:05", start + 40_000L)

        assertTrue(session.candidates(start + 50_000L).single().arrived)

        session.rebaseline(start + 60_000L)

        assertFalse(session.candidates(start + 70_000L).single().arrived)
    }
}
