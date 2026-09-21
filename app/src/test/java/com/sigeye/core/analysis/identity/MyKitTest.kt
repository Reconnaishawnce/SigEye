package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling a device in your pocket from a device across the room.
 *
 * The distinction is not loudness. A phone on the next table is loud. What separates your
 * own kit is that its level barely moves while you turn around, because the geometry
 * between the two radios does not change when they travel together.
 */
class MyKitTest {

    private val t0 = 1_700_000_000_000L
    private val window = 30_000L

    /** A device heard steadily for the whole window, at [level] give or take [wander]. */
    private fun device(
        address: String = "AA:BB:CC:DD:EE:FF",
        level: Int,
        wander: Int = 2,
        packets: Int = 60,
        spanMs: Long = window,
        name: String? = null,
    ): Nearby {
        val rssis = (0 until packets).map { index ->
            level + ((index % (wander + 1)) - wander / 2)
        }
        return Nearby(
            address = address,
            name = name,
            vendor = "Acme",
            rssis = rssis,
            firstSeenMs = t0,
            lastSeenMs = t0 + spanMs,
        )
    }

    private fun judged(device: Nearby) = MyKit.candidates(listOf(device), window)

    // ------------------------------------------------------------------ what is yours

    @Test
    fun `something loud and steady for the whole window is offered as yours`() {
        val found = judged(device(level = -48, wander = 2))

        assertEquals(1, found.size)
        assertEquals(-48, found.first().medianRssi)
    }

    @Test
    fun `a device across the room is loud and does not hold still`() {
        // People walk between it and the phone, so the level swells and fades. This is the
        // case a loudness-only rule gets wrong, and it gets it wrong constantly in a cafe.
        val found = judged(device(level = -50, wander = 26))

        assertTrue(found.isEmpty())
    }

    @Test
    fun `a device that is steady but distant is not on you`() {
        // A beacon screwed to a wall is the steadiest thing in the building.
        assertTrue(judged(device(level = -80, wander = 1)).isEmpty())
    }

    @Test
    fun `the boundary is the level stated and not one either side of it`() {
        assertTrue(judged(device(level = MyKit.ON_PERSON_DBM, wander = 0)).isNotEmpty())
        assertTrue(judged(device(level = MyKit.ON_PERSON_DBM - 1, wander = 0)).isEmpty())
    }

    // ------------------------------------------------------------------ not enough to say

    @Test
    fun `a handful of packets proves nothing either way`() {
        assertTrue(judged(device(level = -45, packets = MyKit.MIN_PACKETS - 1)).isEmpty())
    }

    @Test
    fun `something that arrived halfway through has not been watched long enough`() {
        // Yours never takes a break. Something present for a third of the window is
        // somebody walking past with their phone out.
        assertTrue(judged(device(level = -45, spanMs = window / 3)).isEmpty())
    }

    @Test
    fun `a window too short to have turned around in decides nothing`() {
        val brief = MyKit.MIN_SPAN_MS - 1_000

        assertTrue(
            MyKit.candidates(listOf(device(level = -45, spanMs = brief)), brief).isEmpty(),
        )
    }

    // ------------------------------------------------------------------ the arithmetic

    @Test
    fun `one deep null does not disqualify a device that is otherwise glued to you`() {
        // A hand passing over a pocket. The middle of the distribution describes the
        // device; the tails describe the moment.
        val steady = (0 until 60).map { -46 }
        val withDropout = steady.toMutableList().also { it[30] = -95 }

        assertTrue(MyKit.wanderOf(withDropout) <= MyKit.MAX_WANDER_DB)
    }

    @Test
    fun `the median is the middle reading rather than an average pulled by outliers`() {
        assertEquals(-50, MyKit.medianOf(listOf(-50, -50, -50, -99)))
        assertEquals(-50, MyKit.medianOf(listOf(-50)))
        assertNull(MyKit.medianOf(emptyList()))
    }

    @Test
    fun `wander of a flat list is zero and of a short list is not guessed at`() {
        assertEquals(0, MyKit.wanderOf(List(20) { -60 }))
        assertEquals(0, MyKit.wanderOf(listOf(-60, -20)))
    }

    // ------------------------------------------------------------------ the offer

    @Test
    fun `candidates come back loudest first, which is the order somebody recognizes them in`() {
        val found = MyKit.candidates(
            listOf(
                device(address = "11:11:11:11:11:11", level = -55),
                device(address = "22:22:22:22:22:22", level = -40),
                device(address = "33:33:33:33:33:33", level = -48),
            ),
            window,
        )

        assertEquals(
            listOf("22:22:22:22:22:22", "33:33:33:33:33:33", "11:11:11:11:11:11"),
            found.map { it.address },
        )
    }

    @Test
    fun `a device with a name is offered under it rather than under its address`() {
        val found = judged(device(level = -45, name = "Shawn's Watch"))

        assertEquals("Shawn's Watch", found.first().label)
    }

    @Test
    fun `every offer says why, because nothing here is marked without somebody agreeing`() {
        val why = judged(device(level = -45)).first().why

        assertTrue(why.contains("dBm"))
        assertTrue(why.contains("steady"))
    }
}
