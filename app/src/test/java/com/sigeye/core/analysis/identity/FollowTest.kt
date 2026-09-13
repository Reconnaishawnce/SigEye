package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowTest {

    private val start = 1_700_000_000_000L

    private fun FollowSession.hear(
        address: String,
        atMs: Long,
        packets: Int = 10,
        rssi: Int = -60,
        random: Boolean = true,
    ) {
        repeat(packets) {
            observe(
                address = address,
                rssi = rssi,
                atMs = atMs + it * 150L,
                label = null,
                vendor = "Apple",
                isRandom = random,
            )
        }
    }

    // -------------------------------------------------------------------- elimination

    @Test
    fun `standing still narrows nothing, and says so`() {
        // Everybody in the lobby survives the lobby. The screen has to admit that or it is
        // asserting a finding it does not have.
        val session = FollowSession()
        session.beginLeg("lobby", kind = LegKind.STILL, atMs = start)
        listOf("5A:01", "5A:02", "5A:03").forEach { session.hear(it, start) }
        session.endLeg(start + 60_000)

        val state = session.state(start + 60_000)
        assertEquals(3, state.survivors)
        assertEquals(3, state.watching)
        assertTrue(state.narrowing().contains("not narrowed anything"))
    }

    @Test
    fun `traveling together cuts what stayed behind`() {
        val session = FollowSession()

        session.beginLeg("the office", kind = LegKind.STILL, atMs = start)
        listOf("5A:01", "5A:02", "5A:03", "5A:04").forEach { session.hear(it, start) }
        session.endLeg(start + 60_000)

        // A mile later, only the one that came along is still audible.
        session.beginLeg("the walk", kind = LegKind.TOGETHER, atMs = start + 120_000)
        session.hear("5A:01", start + 120_000)
        session.endLeg(start + 300_000)

        val state = session.state(start + 300_000)
        assertEquals(1, state.survivors)
        assertEquals(4, state.watching)
        assertEquals("5A:01", state.candidates.first().address)
        assertTrue(state.narrowing().contains("1 of 4"))
    }

    @Test
    fun `a moving leg is worth more than a standing one`() {
        val session = FollowSession()
        session.beginLeg("stood about", kind = LegKind.STILL, atMs = start)
        session.hear("5A:01", start)
        session.hear("5A:02", start)
        session.endLeg(start + 30_000)

        session.beginLeg("walked", kind = LegKind.TOGETHER, atMs = start + 40_000)
        session.hear("5A:02", start + 40_000)
        session.endLeg(start + 90_000)

        val candidates = session.state(start + 90_000).candidates
        // Both were seen once; the one seen while moving is the better claim.
        assertEquals("5A:02", candidates.first().address)
        assertTrue(candidates.first().weight > candidates.last().weight)
    }

    @Test
    fun `a device that drops out and comes back has not survived the leg it missed`() {
        val session = FollowSession()
        session.beginLeg("one", kind = LegKind.TOGETHER, atMs = start)
        session.hear("5A:01", start)
        session.hear("5A:02", start)
        session.endLeg(start + 60_000)

        session.beginLeg("two", kind = LegKind.TOGETHER, atMs = start + 70_000)
        session.hear("5A:01", start + 70_000)
        session.endLeg(start + 130_000)

        session.beginLeg("three", kind = LegKind.TOGETHER, atMs = start + 140_000)
        session.hear("5A:01", start + 140_000)
        session.hear("5A:02", start + 140_000)
        session.endLeg(start + 200_000)

        val state = session.state(start + 200_000)
        assertEquals(1, state.survivors)
        assertEquals(
            2,
            state.candidates.first { it.address == "5A:02" }.legsSeen,
        )
    }

    @Test
    fun `a device heard once is not a candidate`() {
        val session = FollowSession()
        session.beginLeg("one", kind = LegKind.TOGETHER, atMs = start)
        session.hear("5A:01", start, packets = 1)
        session.hear("5A:02", start, packets = 10)
        session.endLeg(start + 60_000)
        assertEquals(listOf("5A:02"), session.state(start + 60_000).candidates.map { it.address })
    }

    @Test
    fun `nothing heard is nothing claimed`() {
        val state = FollowSession().state(start)
        assertEquals(0, state.watching)
        assertEquals(0, state.survivors)
        assertNull(state.target)
        assertTrue(state.narrowing().contains("Nothing heard"))
    }

    @Test
    fun `beginning a leg closes the one before it`() {
        val session = FollowSession()
        session.beginLeg("one", kind = LegKind.STILL, atMs = start)
        session.beginLeg("two", kind = LegKind.TOGETHER, atMs = start + 10_000)
        val legs = session.state(start + 20_000).legs
        assertEquals(2, legs.size)
        assertEquals(start + 10_000, legs.first().endedAtMs)
        assertTrue(legs.last().running)
    }

    // ------------------------------------------------------------------------ holding

    @Test
    fun `locking a candidate moves the session to holding`() {
        val session = FollowSession()
        session.beginLeg("one", kind = LegKind.TOGETHER, atMs = start)
        session.hear("5A:01", start)
        session.lock("5A:01")
        val state = session.state(start + 5_000)
        assertEquals(FollowPhase.HOLDING, state.phase)
        assertEquals("5A:01", state.target!!.address)
    }

    @Test
    fun `a target that has gone quiet is reported lost, not still held`() {
        val session = FollowSession()
        session.beginLeg("one", kind = LegKind.TOGETHER, atMs = start)
        session.hear("5A:01", start)
        session.lock("5A:01")
        val state = session.state(start + 5 * 60_000L)
        assertEquals(FollowPhase.LOST, state.phase)
        assertTrue(state.silentForMs > FollowSession.LOST_AFTER_MS)
    }

    @Test
    fun `a couple of dropped packets is not being lost`() {
        val session = FollowSession()
        session.beginLeg("one", kind = LegKind.TOGETHER, atMs = start)
        session.hear("5A:01", start)
        session.lock("5A:01")
        assertEquals(FollowPhase.HOLDING, session.state(start + 20_000).phase)
    }

    @Test
    fun `unlocking goes back to narrowing rather than starting over`() {
        val session = FollowSession()
        session.beginLeg("one", kind = LegKind.TOGETHER, atMs = start)
        session.hear("5A:01", start)
        session.lock("5A:01")
        session.unlock()
        val state = session.state(start + 5_000)
        assertEquals(FollowPhase.NARROWING, state.phase)
        assertNull(state.target)
        assertEquals(1, state.legs.size)
    }

    // ---------------------------------------------------------------------- rotation

    @Test
    fun `a re-acquired target carries its history onto the new address`() {
        val session = FollowSession()
        session.beginLeg("one", kind = LegKind.TOGETHER, atMs = start)
        session.hear("5A:01", start)
        session.endLeg(start + 60_000)
        session.beginLeg("two", kind = LegKind.TOGETHER, atMs = start + 70_000)
        session.hear("5A:01", start + 70_000)
        session.lock("5A:01")

        session.hear("5B:02", start + 130_000)
        session.reacquire("5B:02", start + 130_000)

        val target = session.state(start + 135_000).target!!
        assertEquals("5B:02", target.address)
        assertEquals(listOf("5A:01", "5B:02"), target.addresses)
        assertEquals(1, target.rotations)
        // The legs it survived before the rotation still count for it.
        assertEquals(2, target.legsSeen)
    }

    @Test
    fun `nothing is predicted about a return until a rotation has been watched`() {
        // A device that has never changed address while being watched could do it at any
        // moment. A countdown to a made-up deadline is worse than no countdown.
        val session = FollowSession()
        session.beginLeg("one", kind = LegKind.TOGETHER, atMs = start)
        session.hear("5A:01", start)
        session.lock("5A:01")
        assertNull(session.state(start + 10_000).expectedReturnMs)
    }

    @Test
    fun `one rotation is enough to expect the next, on the specification default`() {
        val session = FollowSession()
        session.beginLeg("one", kind = LegKind.TOGETHER, atMs = start)
        session.hear("5A:01", start)
        session.lock("5A:01")
        session.hear("5B:02", start + 100_000)
        session.reacquire("5B:02", start + 100_000)

        val state = session.state(start + 110_000)
        assertEquals(
            start + 100_000 + RotationRhythm.SPEC_DEFAULT_MS,
            state.expectedReturnMs,
        )
        // One change is not a rhythm, so nothing is claimed to have been measured.
        assertNull(state.rhythm)
    }

    @Test
    fun `a measured rhythm beats the default once there is one`() {
        val session = FollowSession()
        session.beginLeg("one", kind = LegKind.TOGETHER, atMs = start)
        session.hear("5A:01", start)
        session.lock("5A:01")

        var at = start
        listOf("5B:02", "5C:03", "5D:04", "5E:05").forEach { address ->
            at += 300_000L
            session.hear(address, at)
            session.reacquire(address, at)
        }

        val state = session.state(at + 10_000)
        assertEquals(300_000L, state.rhythm!!.medianPeriodMs)
        assertEquals(at + 300_000L, state.expectedReturnMs)
    }

    @Test
    fun `re-acquiring something never heard from changes nothing`() {
        val session = FollowSession()
        session.beginLeg("one", kind = LegKind.TOGETHER, atMs = start)
        session.hear("5A:01", start)
        session.lock("5A:01")
        session.reacquire("FF:FF", start + 10_000)
        assertEquals("5A:01", session.state(start + 12_000).target!!.address)
    }

    @Test
    fun `the export carries the legs and the survivors`() {
        val session = FollowSession()
        session.beginLeg("the walk", kind = LegKind.TOGETHER, atMs = start)
        session.hear("5A:01", start)
        session.endLeg(start + 60_000)
        val csv = session.csv()
        assertTrue(csv.contains("the walk"))
        assertTrue(csv.contains("5A:01"))
        assertTrue(csv.contains("legs_seen"))
    }
}
