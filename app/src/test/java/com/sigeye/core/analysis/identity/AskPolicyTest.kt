package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which rotation questions get to stop somebody who is walking.
 *
 * Written after a follow in a busy place produced a conveyor belt of dialogs about devices
 * the person had never looked at. The fix is not a threshold. It is that a question nobody
 * can answer should never have been a dialog in the first place.
 */
class AskPolicyTest {

    private val t0 = 1_700_000_000_000L
    private val target = "AA:BB:CC:DD:EE:FF"
    private val stranger = "11:22:33:44:55:66"

    private fun urgency(
        departure: String = stranger,
        pinned: String? = null,
        pool: Int = 40,
        lastMs: Long = 0L,
        now: Long = t0,
        muted: Boolean = false,
    ) = AskPolicy.urgencyOf(departure, pinned, pool, lastMs, now, muted)

    // ------------------------------------------------------------------ what interrupts

    @Test
    fun `the device somebody picked is worth stopping them for`() {
        // They are watching it. They can answer. This is the event the experiment exists
        // for and it is time-limited, because the successor has to still be audible.
        assertEquals(AskUrgency.INTERRUPT, urgency(departure = target, pinned = target))
    }

    @Test
    fun `a stranger in a crowd is not, however plausible the match`() {
        // "Did A4:C1:38 become 7B:09:EE" about a device never looked at has two available
        // answers, a guess and Later, and neither is worth being stopped for.
        assertEquals(AskUrgency.TRAY, urgency(pool = 40))
    }

    @Test
    fun `once the pool is small its members are people somebody has been watching`() {
        assertEquals(AskUrgency.INTERRUPT, urgency(pool = AskPolicy.POOL_SMALL))
        assertEquals(AskUrgency.TRAY, urgency(pool = AskPolicy.POOL_SMALL + 1))
    }

    @Test
    fun `a pinned device outranks a crowded pool`() {
        // The pool being large is why strangers wait. It is not a reason to sit on the one
        // question that matters.
        assertEquals(
            AskUrgency.INTERRUPT,
            urgency(departure = target, pinned = target, pool = 200),
        )
    }

    @Test
    fun `the pin is matched however either side is punctuated`() {
        assertEquals(
            AskUrgency.INTERRUPT,
            urgency(departure = target.lowercase(), pinned = target),
        )
    }

    // ------------------------------------------------------------------ not twice over

    @Test
    fun `a second dialog inside the quiet gap waits instead`() {
        // Two dialogs in a minute means the second gets tapped through unread, which turns
        // a deliberate answer into a reflex. Worse than not showing it.
        val justNow = t0 - AskPolicy.MIN_GAP_MS / 2

        assertEquals(
            AskUrgency.TRAY,
            urgency(departure = target, pinned = target, lastMs = justNow),
        )
    }

    @Test
    fun `once the gap has passed it may interrupt again`() {
        val earlier = t0 - AskPolicy.MIN_GAP_MS

        assertEquals(
            AskUrgency.INTERRUPT,
            urgency(departure = target, pinned = target, lastMs = earlier),
        )
    }

    @Test
    fun `the first question of a run is not held back by a gap that has not started`() {
        assertEquals(
            AskUrgency.INTERRUPT,
            urgency(departure = target, pinned = target, lastMs = 0L),
        )
    }

    // ------------------------------------------------------------------ being left alone

    @Test
    fun `muting stops the interruptions and not the questions`() {
        // The distinction the whole design rests on. Suppressing a question would lose the
        // target; suppressing the dialog only postpones the answer.
        assertEquals(
            AskUrgency.TRAY,
            urgency(departure = target, pinned = target, muted = true),
        )
    }

    // ------------------------------------------------------------------ what the tray says

    @Test
    fun `an empty tray says nothing at all`() {
        assertNull(AskPolicy.trayLabel(0))
        assertNull(AskPolicy.trayLabel(-1))
    }

    @Test
    fun `the tray counts in plain words`() {
        assertEquals("1 rotation to check", AskPolicy.trayLabel(1))
        assertEquals("7 rotations to check", AskPolicy.trayLabel(7))
    }

    @Test
    fun `the tray says why it is filling up rather than looking stuck`() {
        val crowded = AskPolicy.whyWaiting(poolSize = 40, muted = false)
        val quiet = AskPolicy.whyWaiting(poolSize = 40, muted = true)
        val recent = AskPolicy.whyWaiting(poolSize = 2, muted = false)

        assertTrue(crowded.contains("nobody can answer"))
        assertTrue(quiet.contains("still here to answer"))
        assertTrue(recent.contains("waiting"))
    }
}
