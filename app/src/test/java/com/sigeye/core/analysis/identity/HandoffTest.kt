package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Following a device through a rotation while somebody is standing there watching it.
 *
 * The two failures are not symmetric and the tests are built around that. Refusing loses
 * the target and ends the follow; guessing follows a stranger and produces a confident
 * trail that is fiction. Asking costs a tap.
 */
class HandoffTest {

    private val t0 = 1_700_000_000_000L

    private val appleShape = AdvertShape(
        companyId = 0x004C,
        manufacturerLength = 25,
        manufacturerPrefix = "0f05",
        appearance = null,
        txPower = 12,
        isConnectable = true,
    )

    private val plainShape = AdvertShape(companyId = 0x004C)

    private fun identity(
        address: String,
        firstSeen: Long,
        lastSeen: Long,
        rssi: Double = -60.0,
        gapMs: Long = 152L,
        shape: AdvertShape = appleShape,
        packets: Int = 40,
        random: Boolean = true,
    ) = Identity(
        address = address,
        shape = shape,
        isRandom = random,
        firstSeenMs = firstSeen,
        lastSeenMs = lastSeen,
        packets = packets,
        medianGapMs = gapMs,
        recentRssi = rssi,
        bestRssi = rssi.toInt() + 4,
    )

    /** A run of readings at a level, one a second. */
    private fun trail(from: Long, levels: List<Int>) =
        levels.mapIndexed { index, rssi -> (from + index * 1_000L) to rssi }

    // ------------------------------------------------------------------ how it left

    @Test
    fun `strong and steady right up to the silence is a rotation`() {
        val departure = Handoffs.classify(
            "AA",
            trail(t0, listOf(-58, -61, -59, -57, -60, -58, -59)),
            bestDbm = -56,
        )

        assertEquals(Exit.VANISHED, departure.exit)
        assertTrue(departure.worthChasing)
    }

    @Test
    fun `getting quieter and quieter is something walking away`() {
        // The case where hunting for a successor does active harm: the device is gone, so
        // anything that matches is a different device that shares a firmware.
        val departure = Handoffs.classify(
            "AA",
            trail(t0, listOf(-55, -59, -64, -69, -73, -78, -82)),
            bestDbm = -52,
        )

        assertEquals(Exit.FADED, departure.exit)
        assertTrue(!departure.worthChasing)
    }

    @Test
    fun `sitting at the edge of hearing counts as leaving however flat it is`() {
        // A device pinned at -95 the whole time is not steady, it is barely there, and the
        // next packet missing means nothing.
        val departure = Handoffs.classify(
            "AA",
            trail(t0, listOf(-94, -95, -96, -94, -95, -95)),
            bestDbm = -93,
        )

        assertEquals(Exit.FADED, departure.exit)
    }

    @Test
    fun `a device that was always quiet but did not move is not treated as leaving`() {
        // Its own best is what the ending is judged against. Ending at -80 means nothing
        // until you know whether it started at -78 or at -45.
        val departure = Handoffs.classify(
            "AA",
            trail(t0, listOf(-79, -81, -80, -78, -80, -81)),
            bestDbm = -77,
        )

        assertEquals(Exit.VANISHED, departure.exit)
    }

    @Test
    fun `too short a trail says so rather than guessing`() {
        val departure = Handoffs.classify("AA", trail(t0, listOf(-60, -61)), bestDbm = -58)

        assertEquals(Exit.UNCLEAR, departure.exit)
        assertEquals(2, departure.readings)
    }

    @Test
    fun `a fall with no slope and a slope with no fall are both unclear`() {
        // One decibel a second for twenty seconds would be a fall, but this one only ran
        // for six, so it never got far from its best.
        val steep = Handoffs.classify(
            "AA",
            trail(t0, listOf(-58, -59, -60, -61, -62, -63)),
            bestDbm = -57,
        )

        assertEquals(Exit.UNCLEAR, steep.exit)
    }

    @Test
    fun `the slope is measured in decibels per second`() {
        val slope = Handoffs.slopeDbPerSec(trail(t0, listOf(-50, -52, -54, -56, -58)))

        assertEquals(-2.0, slope, 0.001)
    }

    // ------------------------------------------------------------------ where it went

    private fun departed(atMs: Long) = Departure(
        address = "AA",
        exit = Exit.VANISHED,
        slopeDbPerSec = 0.0,
        finalDbm = -60.0,
        bestDbm = -56,
        readings = 10,
        lastSeenMs = atMs,
    )

    @Test
    fun `one clear match is taken without asking`() {
        val previous = identity("AA", t0, t0 + 60_000)
        val candidate = identity("BB", t0 + 60_500, t0 + 75_000)

        val handoff = Handoffs.decide(
            previous = previous,
            departure = departed(t0 + 60_000),
            candidates = listOf(candidate),
            nowMs = t0 + 80_000,
        )

        assertTrue("$handoff", handoff is Handoff.Rotated)
        assertEquals("BB", (handoff as Handoff.Rotated).to.address)
    }

    @Test
    fun `two equally good matches are put to the operator, not guessed between`() {
        // The room full of identical iPhones. Guessing here is how a follow becomes fiction.
        val previous = identity("AA", t0, t0 + 60_000)
        val twins = listOf(
            identity("BB", t0 + 60_400, t0 + 75_000),
            identity("CC", t0 + 60_600, t0 + 75_000),
        )

        val handoff = Handoffs.decide(previous, departed(t0 + 60_000), twins, t0 + 80_000)

        assertTrue("$handoff", handoff is Handoff.Ask)
        val ask = handoff as Handoff.Ask
        assertEquals(2, ask.options.size)
        assertTrue("neither holds the evidence", ask.options.all { it.share < 0.7 })
    }

    @Test
    fun `the options carry the numbers somebody would need to break the tie`() {
        val previous = identity("AA", t0, t0 + 60_000, rssi = -60.0, gapMs = 152L)
        val options = listOf(
            identity("BB", t0 + 60_300, t0 + 75_000, rssi = -62.0, gapMs = 152L),
            identity("CC", t0 + 60_900, t0 + 75_000, rssi = -75.0, gapMs = 1020L),
        )

        val ask = Handoffs.decide(previous, departed(t0 + 60_000), options, t0 + 80_000)
            as Handoff.Ask

        val near = ask.options.single { it.address == "BB" }
        assertEquals(300L, near.gapMs)
        assertEquals(-2.0, near.rssiDeltaDb, 0.001)
        assertTrue(near.intervalsMatch)

        val far = ask.options.single { it.address == "CC" }
        assertTrue("a seven times slower interval is not a match", !far.intervalsMatch)
        assertTrue(Handoffs.describe(far).contains("which does not"))
    }

    @Test
    fun `only three options are offered, best first`() {
        // A menu on a street corner is not a decision, it is a wall.
        val previous = identity("AA", t0, t0 + 60_000)
        val several = (1..Handoffs.TOO_MANY).map {
            identity("C$it", t0 + 60_000 + it * 100L, t0 + 75_000, rssi = -60.0 - it)
        }

        val ask = Handoffs.decide(previous, departed(t0 + 60_000), several, t0 + 80_000)
            as Handoff.Ask

        assertEquals(Handoffs.MAX_OPTIONS, ask.options.size)
        assertTrue(ask.options[0].score.points >= ask.options[1].score.points)
        assertTrue(ask.options[1].score.points >= ask.options[2].score.points)
    }

    @Test
    fun `past a certain number of equally good matches there is no question to ask`() {
        // Trimming a crowd to its first three and calling that a choice was the worst
        // moment in the app: three options at six percent each, with the rest of the
        // street fitting just as well and not shown at all.
        val previous = identity("AA", t0, t0 + 60_000)
        val crowd = (1..Handoffs.TOO_MANY + 1).map {
            identity("C$it", t0 + 60_000 + it * 100L, t0 + 75_000, rssi = -60.0 - it)
        }

        val handoff = Handoffs.decide(previous, departed(t0 + 60_000), crowd, t0 + 80_000)

        assertTrue("$handoff", handoff is Handoff.Gone)
    }

    @Test
    fun `shares add up across what was offered`() {
        val previous = identity("AA", t0, t0 + 60_000)
        val two = listOf(
            identity("BB", t0 + 60_400, t0 + 75_000),
            identity("CC", t0 + 60_600, t0 + 75_000),
        )

        val ask = Handoffs.decide(previous, departed(t0 + 60_000), two, t0 + 80_000)
            as Handoff.Ask

        assertEquals(1.0, ask.options.sumOf { it.share }, 0.001)
    }

    // ------------------------------------------------------------------ when not to chase

    @Test
    fun `a device that faded out is never chased`() {
        // Even with a perfect match sitting right there. This is the guard that stops the
        // app inventing a trail leading to a stranger.
        val previous = identity("AA", t0, t0 + 60_000)
        val perfect = identity("BB", t0 + 60_200, t0 + 75_000)
        val faded = departed(t0 + 60_000).copy(exit = Exit.FADED, slopeDbPerSec = -1.2)

        val handoff = Handoffs.decide(previous, faded, listOf(perfect), t0 + 80_000)

        assertTrue("$handoff", handoff is Handoff.Gone)
    }

    @Test
    fun `something already on the air before the silence is not a successor`() {
        // A rotation produces an address that did not exist a moment earlier. This one has
        // been broadcasting happily for a minute, so it is a different device.
        val previous = identity("AA", t0, t0 + 60_000)
        val bystander = identity("BB", t0 + 20_000, t0 + 75_000)

        val handoff = Handoffs.decide(previous, departed(t0 + 60_000), listOf(bystander), t0 + 80_000)

        assertTrue("$handoff", handoff is Handoff.Gone)
    }

    @Test
    fun `a fixed address has no rotation to follow`() {
        val previous = identity("AA", t0, t0 + 60_000, random = false)
        val candidate = identity("BB", t0 + 60_300, t0 + 75_000)

        val handoff = Handoffs.decide(previous, departed(t0 + 60_000), listOf(candidate), t0 + 80_000)

        assertTrue("$handoff", handoff is Handoff.Gone)
        assertTrue((handoff as Handoff.Gone).why.contains("fixed"))
    }

    @Test
    fun `an advertisement with nothing distinctive in it is not matched on`() {
        val previous = identity("AA", t0, t0 + 60_000, shape = plainShape)
        val candidate = identity("BB", t0 + 60_300, t0 + 75_000, shape = plainShape)

        val handoff = Handoffs.decide(previous, departed(t0 + 60_000), listOf(candidate), t0 + 80_000)

        assertTrue("$handoff", handoff is Handoff.Gone)
    }

    @Test
    fun `silence shorter than the window is not a departure yet`() {
        val previous = identity("AA", t0, t0 + 60_000)

        val handoff = Handoffs.decide(previous, departed(t0 + 60_000), emptyList(), t0 + 62_000)

        assertEquals(Handoff.Waiting, handoff)
    }

    @Test
    fun `a trail gone cold is not picked up again`() {
        val previous = identity("AA", t0, t0 + 60_000)
        val late = identity("BB", t0 + 60_100, t0 + 300_000)

        val handoff = Handoffs.decide(previous, departed(t0 + 60_000), listOf(late), t0 + 300_000)

        assertTrue("$handoff", handoff is Handoff.Gone)
        assertTrue((handoff as Handoff.Gone).why.contains("minutes"))
    }

    @Test
    fun `an address already claimed by another device is not offered twice`() {
        // Two candidates rotating at once would otherwise both be handed the same
        // successor, which collapses two people into one.
        val previous = identity("AA", t0, t0 + 60_000)
        val candidate = identity("BB", t0 + 60_300, t0 + 75_000)

        val handoff = Handoffs.decide(
            previous = previous,
            departure = departed(t0 + 60_000),
            candidates = listOf(candidate),
            nowMs = t0 + 80_000,
            taken = setOf("BB"),
        )

        assertTrue("$handoff", handoff is Handoff.Gone)
    }
}
