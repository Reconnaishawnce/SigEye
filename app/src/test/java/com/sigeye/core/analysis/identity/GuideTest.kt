package com.sigeye.core.analysis.identity

import com.sigeye.core.sensors.Walking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling somebody holding a phone on a street what to do with it.
 *
 * The app knew all of this and said none of it: a number with no context, and an operator
 * left to work out from first principles whether a still count meant progress, more
 * walking, or a broken app.
 */
class GuideTest {

    private val t0 = 1_700_000_000_000L
    private val tuning = FollowTuning.DEFAULT

    private fun candidate(address: String, carried: Boolean = false) = FollowCandidate(
        address = address,
        label = null,
        vendor = null,
        isRandom = true,
        packets = 400,
        meanRssi = if (carried) -40.0 else -70.0,
        recentRssi = if (carried) -40.0 else -70.0,
        closeFraction = if (carried) 0.95 else 0.1,
        spreadDb = if (carried) 3.0 else 20.0,
        firstSeenMs = t0,
        lastSeenMs = t0 + 600_000L,
        addresses = listOf(address),
        inPool = true,
    )

    private fun state(
        left: Int,
        elapsedMs: Long,
        carried: Int = 0,
    ): FollowState {
        val people = (1..left).map { candidate("AA:00:%02X".format(it)) } +
            (1..carried).map { candidate("BB:00:%02X".format(it), carried = true) }
        return FollowState(
            phase = FollowPhase.FOLLOWING,
            atMs = t0 + elapsedMs,
            candidates = people,
            followStartedAtMs = t0,
            baselineEndedAtMs = t0,
            tuning = tuning,
        )
    }

    private fun counts(from: Int, to: Int, overMs: Long, atMs: Long): List<Moment.Count> {
        val steps = 12
        return (0..steps).map { step ->
            Moment.Count(
                atMs = atMs - overMs + step * (overMs / steps),
                stillIn = from + (to - from) * step / steps,
                pool = from,
            )
        }
    }

    // ------------------------------------------------------------------ the dead minute

    @Test
    fun `the first ninety seconds say the count cannot move yet`() {
        // This is most of why it felt broken. Nothing has been silent for a full drop-off,
        // so the number genuinely cannot fall, and a still number reads as a stalled app.
        val advice = Guide.of(state(left = 300, elapsedMs = 40_000L), emptyList(), null)!!

        assertEquals(Stage.SETTLING, advice.stage)
        assertEquals(Move.WAIT, advice.move)
        assertTrue(advice.expect.contains("not the app being stuck"))
    }

    @Test
    fun `a follow that has not started gets no advice at all`() {
        assertNull(
            Guide.of(FollowState(phase = FollowPhase.BASELINE), emptyList(), null),
        )
    }

    // ------------------------------------------------------------------ standing still

    @Test
    fun `standing still is called out above everything else`() {
        // The one case where a still count means the method has stopped rather than the
        // app. Separation is the whole measurement.
        val advice = Guide.of(
            state(left = 200, elapsedMs = 600_000L),
            counts(300, 200, 600_000L, t0 + 600_000L),
            stillForMs = Guide.STILL_MS + 30_000L,
        )!!

        assertEquals(Stage.STALLED, advice.stage)
        assertEquals(Move.GET_MOVING, advice.move)
        assertTrue(advice.urgent)
    }

    @Test
    fun `standing still at the very end is not nagged about`() {
        // Down to a pocket, stopped, looking at the list. That is the correct thing to be
        // doing and telling somebody to walk would be wrong.
        val advice = Guide.of(
            state(left = 3, elapsedMs = 900_000L),
            counts(300, 3, 600_000L, t0 + 900_000L),
            stillForMs = Guide.STILL_MS * 3,
        )!!

        assertEquals(Stage.IDENTIFY, advice.stage)
    }

    @Test
    fun `a phone that cannot tell whether it is moving never says stand still`() {
        val advice = Guide.of(
            state(left = 200, elapsedMs = 600_000L),
            counts(300, 200, 600_000L, t0 + 600_000L),
            stillForMs = null,
        )!!

        assertTrue(advice.move != Move.GET_MOVING)
    }

    // ------------------------------------------------------------------ the walk-by

    @Test
    fun `a count that has stopped falling asks for a walk-by`() {
        // What survives this long is other people going your way at your speed, and more
        // walking does not separate them.
        val advice = Guide.of(
            state(left = 24, elapsedMs = 900_000L),
            counts(26, 24, Guide.STALL_WINDOW_MS, t0 + 900_000L),
            stillForMs = 0L,
        )!!

        assertEquals(Stage.STALLED, advice.stage)
        assertEquals(Move.WALK_BY, advice.move)
        assertTrue(advice.urgent)
    }

    @Test
    fun `a count still falling fast is left alone to fall`() {
        // The walk-by costs attention and a manoeuvre. Asking for one while elimination is
        // doing the work is interrupting something that is working.
        val advice = Guide.of(
            state(left = 60, elapsedMs = 300_000L),
            counts(300, 60, Guide.STALL_WINDOW_MS, t0 + 300_000L),
            stillForMs = 0L,
        )!!

        assertEquals(Stage.THINNING, advice.stage)
        assertEquals(Move.WALK, advice.move)
    }

    @Test
    fun `a short list asks for a walk-by without calling it stalled`() {
        val advice = Guide.of(
            state(left = 9, elapsedMs = 600_000L),
            counts(300, 9, Guide.STALL_WINDOW_MS, t0 + 600_000L),
            stillForMs = 0L,
        )!!

        assertEquals(Stage.SHORTLIST, advice.stage)
        assertEquals(Move.WALK_BY, advice.move)
        assertTrue("not an emergency", !advice.urgent)
    }

    @Test
    fun `your own pocket is dealt with before anything else about the list`() {
        val advice = Guide.of(
            state(left = 40, elapsedMs = 600_000L, carried = 3),
            counts(300, 43, Guide.STALL_WINDOW_MS, t0 + 600_000L),
            stillForMs = 0L,
        )!!

        assertEquals(Move.OWN_KIT, advice.move)
        assertTrue(advice.urgent)
    }

    // ------------------------------------------------------------------ stalling

    @Test
    fun `stalling is a fraction, not a number`() {
        // Eight lost out of three hundred is noise. Eight lost out of twenty is the follow
        // working.
        val now = t0 + 900_000L
        assertTrue(Guide.stalled(counts(300, 292, Guide.STALL_WINDOW_MS, now), now))
        assertTrue(!Guide.stalled(counts(20, 12, Guide.STALL_WINDOW_MS, now), now))
    }

    @Test
    fun `too little history is not a stall`() {
        val now = t0 + 200_000L
        assertTrue(!Guide.stalled(emptyList(), now))
        assertTrue(
            "a window that has only just opened has not said anything yet",
            !Guide.stalled(counts(40, 40, 30_000L, now), now),
        )
    }

    // ------------------------------------------------------------------ the sensor's half

    @Test
    fun `a phone on a table is not walking and a phone in a pocket is`() {
        // Gravity is always about 9.8, so a still phone reads flat. Carried, it swings
        // several metres per second squared with every step.
        val still = List(24) { 9.81f + (if (it % 2 == 0) 0.02f else -0.02f) }
        val walking = List(24) { 9.81f + (if (it % 2 == 0) 2.4f else -2.1f) }

        assertTrue(!Walking.moving(still))
        assertTrue(Walking.moving(walking))
    }

    @Test
    fun `too few readings never claims movement`() {
        assertTrue(!Walking.moving(emptyList()))
        assertTrue(!Walking.moving(listOf(9.8f)))
    }
}
