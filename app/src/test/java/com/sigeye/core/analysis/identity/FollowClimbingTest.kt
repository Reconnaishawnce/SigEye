package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The number on screen while you walk has to fall, and it was climbing.
 *
 * Reported from a real follow: "even after I did the baseline, the number of potential
 * devices kept going up as I walked". Two separate things were doing that, and both are
 * pinned here.
 */
class FollowClimbingTest {

    private val start = 1_700_000_000_000L

    private fun FollowSession.hear(address: String, atMs: Long, times: Int = 5) {
        repeat(times) { observe(address, -60, atMs + it * 100L, null, null, true) }
    }

    @Test
    fun `the survivor count cannot climb during a leg`() {
        // The bug. Legs still running used to count towards legsPossible, so every device
        // began the leg having missed it and became a survivor again the moment it was
        // next heard - which meant the count rose steadily through the leg it was supposed
        // to be falling through.
        val session = FollowSession()
        session.beginLeg("baseline", LegKind.BASELINE, start)
        listOf("5A:01", "5A:02", "5A:03").forEach { session.hear(it, start) }
        session.endBaseline(start + 30_000L)

        session.beginLeg("walk", LegKind.TOGETHER, start + 30_000L)

        // One minute in, only one has been heard again. It must not read as "one survivor
        // out of three" - nothing has been eliminated, because the leg is not over.
        session.hear("5A:01", start + 60_000L)
        val early = session.state(start + 90_000L)

        // Two minutes in, a second one turns up. Under the bug this went from 1 to 2.
        session.hear("5A:02", start + 150_000L)
        val later = session.state(start + 180_000L)

        assertEquals("no finished test, so no verdict", 0, early.survivors)
        assertEquals(0, later.survivors)
        assertTrue("and it did not climb", later.survivors <= early.survivors)
    }

    @Test
    fun `the verdict arrives when the leg ends, and it is a cut`() {
        val session = FollowSession()
        session.beginLeg("baseline", LegKind.BASELINE, start)
        listOf("5A:01", "5A:02", "5A:03").forEach { session.hear(it, start) }
        session.endBaseline(start + 30_000L)

        session.beginLeg("walk", LegKind.TOGETHER, start + 30_000L)
        session.hear("5A:01", start + 60_000L)
        session.endLeg(start + 180_000L)

        val state = session.state(start + 180_000L)

        assertEquals(1, state.survivors)
        assertEquals(3, state.watching)
        assertEquals(1, state.testsDone)
    }

    @Test
    fun `what is still audible falls as things drop behind you`() {
        // The number worth watching while walking. It is not a verdict, it is "who is
        // still answering", and it moves in real time.
        val session = FollowSession()
        session.beginLeg("baseline", LegKind.BASELINE, start)
        listOf("5A:01", "5A:02", "5A:03", "5A:04").forEach { session.hear(it, start) }
        session.endBaseline(start + 30_000L)

        session.beginLeg("walk", LegKind.TOGETHER, start + 30_000L)
        listOf("5A:01", "5A:02", "5A:03", "5A:04").forEach { session.hear(it, start + 31_000L) }
        assertEquals(4, session.state(start + 35_000L).stillHere.size)

        // Then the room falls away behind you, one at a time.
        listOf("5A:01", "5A:02", "5A:03").forEach { session.hear(it, start + 60_000L) }
        assertEquals(3, session.state(start + 65_000L).stillHere.size)

        listOf("5A:01", "5A:02").forEach { session.hear(it, start + 120_000L) }
        assertEquals(2, session.state(start + 125_000L).stillHere.size)

        session.hear("5A:01", start + 200_000L)
        assertEquals(1, session.state(start + 205_000L).stillHere.size)
    }

    @Test
    fun `something first heard halfway down the street is not something that came with you`() {
        // The other half of the climb. Every new device passed on the walk used to join
        // the denominator *and* the list of things apparently still with you.
        val session = FollowSession()
        session.beginLeg("baseline", LegKind.BASELINE, start)
        session.hear("5A:01", start)
        session.endBaseline(start + 30_000L)

        session.beginLeg("walk", LegKind.TOGETHER, start + 30_000L)
        session.hear("5A:01", start + 60_000L)
        // A shop's beacon, met in passing.
        session.hear("5A:99", start + 60_000L)

        val state = session.state(start + 65_000L)

        assertEquals(listOf("5A:01"), state.stillHere.map { it.address })
        assertEquals("but it is still counted in the denominator", 2, state.watching)
    }

    @Test
    fun `the denominator is everything ever heard, and it is meant to climb`() {
        // Not a bug, and the screen says "heard so far" rather than "in range" because of
        // this. Walking a mile through a town hears hundreds of devices, and the strength
        // of the claim at the end is exactly that it is a few out of hundreds.
        val session = FollowSession()
        session.beginLeg("walk", LegKind.TOGETHER, start)

        repeat(50) { session.hear("5A:%02d".format(it), start + it * 1_000L) }

        assertEquals(50, session.state(start + 60_000L).watching)
    }
}
