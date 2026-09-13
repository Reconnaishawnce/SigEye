package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walking past somebody standing still, and picking out the device you passed.
 *
 * Written as real walks through a real session rather than by handing numbers to the
 * scorer, because what is being tested is a shape in time and a test that pokes the fields
 * directly would pass with the marks wired up backwards.
 */
class WalkByTest {

    private val start = 1_700_000_000_000L
    private val walkMs = 40_000L
    private val midMs = start + walkMs / 2

    private fun walking(): FollowSession = FollowSession().apply {
        beginProbe(Probe.WALK_BY, start)
    }

    /** Feeds one device through the whole walk, one advertisement a second. */
    private fun walk(session: FollowSession, address: String, level: (Long) -> Int?) {
        var at = start
        while (at <= start + walkMs) {
            level(at - start)?.let { session.observe(address, it, at, null, null, true) }
            at += 1_000L
        }
    }

    /** Triangular: far, near, far. What passing somebody actually looks like. */
    private fun passing(elapsed: Long, peak: Int = -45, far: Int = -75): Int {
        val half = walkMs / 2
        val distanceFromMiddle = kotlin.math.abs(elapsed - half).toDouble() / half
        return (peak + (far - peak) * distanceFromMiddle).toInt()
    }

    private fun finish(session: FollowSession, markMiddle: Boolean = true) {
        if (markMiddle) session.markClosest(midMs)
        session.endProbe(start + walkMs)
    }

    @Test
    fun `a device on the person you walk past rises and comes back down`() {
        val session = walking()
        walk(session, "AA:BB:CC:DD:EE:01") { passing(it) }
        finish(session)

        val score = session.candidates(start + walkMs).single().walkBy!!

        assertTrue(score.rose)
        assertTrue(score.centred)
        assertTrue(score.symmetric)
        assertTrue(score.passed)
        assertTrue(score.describe().contains("came back down"))
    }

    @Test
    fun `something you walked away from is loudest at the start and does not pass`() {
        // The check that the rise alone cannot do. A device behind you falls the whole way,
        // and a device you walk towards climbs the whole way; both are wrong and neither is
        // the person you passed.
        val session = walking()
        walk(session, "AA:BB:CC:DD:EE:02") { elapsed -> (-45 - elapsed / 1_000L).toInt() }
        finish(session)

        val score = session.candidates(start + walkMs).single().walkBy!!

        assertFalse(score.passed)
        assertFalse(score.rose)
    }

    @Test
    fun `something you walked towards peaks at the end and is called out as such`() {
        val session = walking()
        walk(session, "AA:BB:CC:DD:EE:03") { elapsed -> (-85 + elapsed / 1_000L).toInt() }
        finish(session)

        val score = session.candidates(start + walkMs).single().walkBy!!

        assertFalse("but not in the middle", score.centred)
        assertFalse("and the two ends disagree, which is the tell", score.symmetric)
        assertFalse(score.passed)
        assertTrue(score.describe().contains("along your path"))
    }

    @Test
    fun `a peak in the middle that never comes back down is along your path, not beside it`() {
        // Rises like a pass and ends thirty dB above where it started. Something in the
        // direction you were walking, not the thing you drew level with.
        val session = walking()
        walk(session, "AA:BB:CC:DD:EE:04") { elapsed ->
            if (elapsed < walkMs / 2) (-90 + elapsed / 500L).toInt() else -45
        }
        finish(session)

        val score = session.candidates(start + walkMs).single().walkBy!!

        assertFalse(score.symmetric)
        assertFalse(score.passed)
        assertTrue(score.describe().contains("along your path"))
    }

    @Test
    fun `a steady device across the room passes nothing`() {
        val session = walking()
        walk(session, "AA:BB:CC:DD:EE:05") { -70 }
        finish(session)

        val score = session.candidates(start + walkMs).single().walkBy!!

        assertFalse(score.rose)
        assertFalse(score.passed)
        assertEquals(0.0, score.symmetryDb, 1.0)
    }

    @Test
    fun `one lucky packet in the middle is not a peak`() {
        // The dangerous case: a single spike at exactly the moment you marked. Levels jump
        // several dB between consecutive packets with nothing moving, so without a median
        // the noisiest device in the room wins every walk - and it would win here, where it
        // is symmetric and centred and needs only a rise to pass.
        val session = walking()
        walk(session, "AA:BB:CC:DD:EE:06") { elapsed -> if (elapsed == 20_000L) -40 else -75 }
        finish(session)

        val score = session.candidates(start + walkMs).single().walkBy!!

        assertTrue("the ends agree", score.symmetric)
        assertFalse("but a single spike is not a peak", score.rose)
        assertFalse(score.passed)
    }

    @Test
    fun `without a middle mark nothing is scored at all`() {
        // Half a walk has no far end to compare against, and an unmarked one has no middle
        // to test the peak against. Better to say nothing than to guess the mark.
        val session = walking()
        walk(session, "AA:BB:CC:DD:EE:07") { passing(it) }
        session.endProbe(start + walkMs)

        assertNull(session.candidates(start + walkMs).single().walkBy)
    }

    @Test
    fun `a walk still running is not scored either`() {
        val session = walking()
        walk(session, "AA:BB:CC:DD:EE:08") { passing(it) }
        session.markClosest(midMs)

        assertNull(session.candidates(start + walkMs).single().walkBy)
    }

    @Test
    fun `the last mark wins, because people correct themselves`() {
        val session = walking()
        walk(session, "AA:BB:CC:DD:EE:09") { passing(it) }
        session.markClosest(start + 5_000L)
        session.markClosest(midMs)
        session.endProbe(start + walkMs)

        val score = session.candidates(start + walkMs).single().walkBy!!

        assertEquals(midMs, score.midAtMs)
        assertTrue(score.passed)
    }

    @Test
    fun `a second walk-by is refused, like a second circle`() {
        val session = walking()
        walk(session, "AA:BB:CC:DD:EE:0A") { passing(it) }
        finish(session)

        session.beginProbe(Probe.WALK_BY, start + walkMs + 1_000L)

        assertEquals(1, session.state(start + walkMs + 2_000L).probes.size)
    }

    @Test
    fun `passing the walk-by counts for more than passing the circle`() {
        // A circle says a device is somewhere near the middle of a lap. A walk-by says you
        // drew level with it at a moment you chose. The second is the stronger statement
        // and the ranking has to say so.
        val now = start + 10 * 60_000L
        val base = FollowCandidate(
            address = "AA", label = null, vendor = null, isRandom = true,
            packets = 40, meanRssi = -60.0, recentRssi = -60.0, closeFraction = 0.0,
            firstSeenMs = start, lastSeenMs = now, addresses = listOf("AA"),
            inPool = true,
        )
        val centred = OrbitScore(8, 8, 60, -55.0, 4.0)
        val passed = WalkByScore(40, -80.0, -45.0, midMs, -79.0, midMs, walkMs)

        assertTrue(passed.passed)
        assertTrue(
            base.copy(walkBy = passed).weight(now) > base.copy(orbit = centred).weight(now),
        )
    }
}
