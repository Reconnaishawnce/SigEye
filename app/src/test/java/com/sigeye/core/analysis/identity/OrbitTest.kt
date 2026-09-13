package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The circle test: walk a lap around somebody and see what stays the same distance away.
 *
 * Every case here is written as a walk round a circle in real time, because the thing being
 * tested is a geometry rather than a formula, and a test that pokes the numbers in directly
 * would pass with the arcs wired up backwards.
 */
class OrbitTest {

    private val start = 1_700_000_000_000L
    private val lapMs = 60_000L

    /** Feeds one device through a whole lap, one advertisement a second. */
    private fun walk(session: FollowSession, address: String, levels: (Long) -> Int?) {
        var at = start
        while (at < start + lapMs) {
            levels(at - start)?.let { rssi ->
                session.observe(address, rssi, at, null, null, isRandom = true)
            }
            at += 1_000L
        }
    }

    private fun circling(): FollowSession = FollowSession().apply {
        beginProbe(Probe.ORBIT, start)
    }

    @Test
    fun `a device on the person stays the same distance all the way round`() {
        val session = circling()
        // Two dB of wander and nothing else: you are orbiting it, so the range never changes.
        walk(session, "AA:BB:CC:DD:EE:01") { elapsed -> -55 + (elapsed / 10_000L).toInt() % 3 }
        session.endProbe(start + lapMs)

        val orbit = session.candidates(start + lapMs).single().orbit!!

        assertTrue(orbit.continuous)
        assertTrue(orbit.near)
        assertTrue(orbit.flat)
        assertTrue(orbit.centred)
    }

    @Test
    fun `a device across the room swings as you come round to its side`() {
        val session = circling()
        // Near on one half of the lap, far on the other. Heard throughout, but not flat.
        walk(session, "AA:BB:CC:DD:EE:02") { elapsed ->
            if (elapsed < lapMs / 2) -48 else -82
        }
        session.endProbe(start + lapMs)

        val orbit = session.candidates(start + lapMs).single().orbit!!

        assertTrue("it never dropped out", orbit.continuous)
        assertFalse("but the level moved 34 dB", orbit.flat)
        assertFalse(orbit.centred)
        assertTrue(orbit.describe().contains("off to one side"))
    }

    @Test
    fun `a device that goes quiet for part of the lap has not survived the circle`() {
        val session = circling()
        // Shadowed for the middle third. Nothing on the person can be.
        walk(session, "AA:BB:CC:DD:EE:03") { elapsed ->
            if (elapsed in 20_000L..40_000L) null else -60
        }
        session.endProbe(start + lapMs)

        val orbit = session.candidates(start + lapMs).single().orbit!!

        assertFalse(orbit.continuous)
        assertFalse(orbit.centred)
        assertTrue(orbit.describe().contains("dropped out"))
    }

    @Test
    fun `something far away is flat too, which is why loudness is also required`() {
        // This is the case the flatness test alone gets wrong. Five paces across a forty
        // metre baseline changes the distance by a tenth, so a distant beacon is as steady
        // as the phone in the pocket. Only its level tells them apart.
        val session = circling()
        walk(session, "AA:BB:CC:DD:EE:04") { -92 }
        session.endProbe(start + lapMs)

        val orbit = session.candidates(start + lapMs).single().orbit!!

        assertTrue(orbit.continuous)
        assertTrue(orbit.flat)
        assertFalse("too faint to be on the person", orbit.near)
        assertFalse(orbit.centred)
    }

    @Test
    fun `a handful of packets is sparse, not flat, even when it covered every arc`() {
        // Half a lap, one packet per arc. It was heard everywhere and the level never
        // moved, but five readings have no shape, and calling that flat would put a device
        // that was barely there at the top of the list.
        val session = circling()
        var at = start
        while (at < start + 30_000L) {
            session.observe("AA:BB:CC:DD:EE:05", -50, at, null, null, isRandom = true)
            at += 7_000L
        }

        val orbit = session.candidates(start + 30_000L).single().orbit!!

        assertTrue(orbit.continuous)
        assertTrue(orbit.sparse)
        assertFalse("a spread of zero from five readings means nothing", orbit.flat)
        assertFalse(orbit.centred)
        assertTrue(orbit.describe().contains("only 5 packets"))
    }

    @Test
    fun `the circle is scored while it is still running`() {
        // The screen shows a live count during the lap, so the arc total cannot wait for
        // the leg to end.
        val session = circling()
        walk(session, "AA:BB:CC:DD:EE:06") { elapsed -> if (elapsed < 30_000L) -55 else null }

        val halfway = session.candidates(start + 30_000L).single().orbit!!

        assertEquals(4, halfway.arcsTotal)
        assertEquals(4, halfway.arcsHeard)
        assertTrue(halfway.centred)
    }

    @Test
    fun `no circle means no score at all, rather than a passing one`() {
        val session = FollowSession()
        walk(session, "AA:BB:CC:DD:EE:07") { -55 }

        val candidate = session.candidates(start + lapMs).single()

        assertNull(candidate.orbit)
        assertFalse(session.state(start + lapMs).orbited)
    }

    @Test
    fun `a second circle is a second measurement, not a refusal`() {
        // It used to be refused, on the grounds that two laps at different radii are not
        // the same measurement. True, and an argument against merging them rather than
        // against walking two - scored separately they are two readings of the same room.
        val session = circling()
        walk(session, "AA:BB:CC:DD:EE:08") { -55 }
        session.endProbe(start + lapMs)

        session.beginProbe(Probe.ORBIT, start + lapMs + 1_000L)
        session.observe("AA:BB:CC:DD:EE:08", -55, start + lapMs + 2_000L, null, null, true)
        session.endProbe(start + lapMs * 2)

        val candidate = session.state(start + lapMs * 2).candidates.single()

        assertEquals(2, candidate.orbits.size)
        assertEquals(listOf(0, 1), candidate.orbits.map { it.probeIndex })
    }

    @Test
    fun `being centred counts for something, but never for everything`() {
        // Weight has to stay ordered so the list reads: stayed with you and centred, stayed
        // with you, centred only, dropped. A device sitting on the table you happened to
        // circle must not outrank one that walked a mile with you.
        val now = start + 10 * 60_000L
        val base = FollowCandidate(
            address = "AA", label = null, vendor = null, isRandom = true,
            packets = 40, meanRssi = -60.0, recentRssi = -60.0, closeFraction = 0.0,
            spreadDb = 30.0,
            firstSeenMs = start, lastSeenMs = now, addresses = listOf("AA"),
            inPool = true,
        )
        val centred = OrbitScore(
            arcsHeard = 8, arcsTotal = 8, packets = 60, meanRssi = -55.0, spreadDb = 4.0,
        )

        val lap = listOf(CandidateOrbit(probeIndex = 0, score = centred))
        val stayed = base
        val stayedAndCentred = base.copy(orbits = lap)
        val droppedButCentred = base.copy(droppedAtMs = start + 60_000L, orbits = lap)
        val dropped = base.copy(droppedAtMs = start + 60_000L)

        assertTrue(stayedAndCentred.weight(now) > stayed.weight(now))
        assertTrue(stayed.weight(now) > droppedButCentred.weight(now))
        assertTrue(droppedButCentred.weight(now) > dropped.weight(now))
    }

    @Test
    fun `the state reports how many survivors the circle agreed with`() {
        val session = circling()
        walk(session, "AA:BB:CC:DD:EE:09") { -55 }
        walk(session, "AA:BB:CC:DD:EE:0A") { elapsed -> if (elapsed < lapMs / 2) -48 else -84 }
        session.endProbe(start + lapMs)

        val state = session.state(start + lapMs)

        assertTrue(state.orbited)
        assertEquals(2, state.stillIn.size)
        assertEquals(1, state.centred)
    }
}
