package com.sigeye.core.analysis.covert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A message carried in whether a device is advertising or silent.
 *
 * The payload is never touched. Everything here is about the gaps, which is the same
 * channel Follow Me and Defeating Randomization read by accident, used on purpose.
 */
class BlinkTest {

    private val slotMs = 500L
    private val t0 = 1_700_000_000_000L

    /** What a transmitter actually puts on the air: several packets inside every on slot. */
    private fun transmit(
        message: String,
        startMs: Long = t0,
        perSlot: Int = 4,
        drop: Set<Int> = emptySet(),
    ): List<Long> {
        val slots = Blink.encode(message)
        return buildList {
            slots.forEachIndexed { index, on ->
                if (!on || index in drop) return@forEachIndexed
                repeat(perSlot) { packet ->
                    add(startMs + index * slotMs + packet * (slotMs / perSlot))
                }
            }
        }
    }

    private fun roundTrip(message: String, arrivals: List<Long>): Decoded {
        val start = Blink.findStart(arrivals, slotMs)!!
        val span = ((arrivals.max() - start) / slotMs).toInt() + 2
        return Blink.decode(Blink.occupancy(arrivals, start, slotMs, span))
    }

    // ------------------------------------------------------------------ there and back

    @Test
    fun `a message survives the round trip`() {
        val message = "MEET AT 9"
        val decoded = roundTrip(message, transmit(message))

        assertEquals(message, decoded.text)
        assertTrue(decoded.complete)
        assertEquals(0, decoded.corrupt)
    }

    @Test
    fun `every character the alphabet claims to carry actually survives`() {
        val decoded = roundTrip(Blink.sendable(), transmit(Blink.sendable()))

        assertEquals(Blink.sendable(), decoded.text)
    }

    @Test
    fun `lower case is sent as upper rather than dropped`() {
        val decoded = roundTrip("hello", transmit("hello"))

        assertEquals("HELLO", decoded.text)
    }

    @Test
    fun `characters the alphabet cannot carry are removed before sending, not silently`() {
        // Better for the screen to show what will actually go out than to quietly turn an
        // emoji into a space somewhere inside the transmission.
        assertEquals("SEND 5", Blink.clean("Send~ 5é"))
    }

    // ------------------------------------------------------------------ finding the boundaries

    @Test
    fun `the receiver finds the slot boundaries without a shared clock`() {
        // The transmitter started at an arbitrary moment and nothing told the receiver
        // when. Everything downstream depends on recovering it.
        val odd = t0 + 137
        val arrivals = transmit("SYNC", startMs = odd)

        val found = Blink.findStart(arrivals, slotMs)!!

        assertTrue("found $found against $odd", kotlin.math.abs(found - odd) <= slotMs / 4)
        assertEquals("SYNC", roundTrip("SYNC", arrivals).text)
    }

    @Test
    fun `a device advertising steadily is not mistaken for a message`() {
        // An ordinary beacon is on in every slot, which is the opposite of an alternating
        // preamble. Reading a message out of it would be inventing one.
        val steady = (0 until 200).map { t0 + it * 100L }

        assertNull(Blink.findStart(steady, slotMs))
    }

    @Test
    fun `too few packets to hold a preamble is not a message`() {
        assertNull(Blink.findStart(listOf(t0, t0 + 500), slotMs))
        assertNull(Blink.findStart(emptyList(), slotMs))
    }

    // ------------------------------------------------------------------ losing packets

    @Test
    fun `one packet lost inside a slot changes nothing`() {
        // Why the transmitter sends several times per slot. A collision or a missed scan
        // window should not flip a bit.
        val message = "ROBUST"
        val arrivals = transmit(message, perSlot = 4).filterIndexed { index, _ -> index % 4 != 0 }

        assertEquals(message, roundTrip(message, arrivals).text)
    }

    @Test
    fun `a whole slot lost is caught by parity rather than read as a different letter`() {
        // A dropped on-slot turns a one into a zero, which is a different character
        // entirely. The parity bit is what makes that visible instead of plausible.
        val message = "ABCDEF"
        val slots = Blink.encode(message)
        val onSlots = slots.indices.filter { slots[it] }
        val victim = onSlots.first { it > Blink.PREAMBLE.size + Blink.START.size + 1 }

        val decoded = roundTrip(message, transmit(message, drop = setOf(victim)))

        assertTrue("nothing was flagged", decoded.corrupt > 0)
        assertTrue(decoded.text.contains(Blink.CORRUPT_MARK))
    }

    @Test
    fun `a corrupt character is shown rather than quietly skipped`() {
        // A gap nobody is told about is worse than a visible one.
        val bad = Blink.encode("HI").toMutableList()
        val at = Blink.PREAMBLE.size + Blink.START.size
        bad[at] = !bad[at]

        val decoded = Blink.decode(bad)

        assertEquals(1, decoded.corrupt)
        assertEquals(Blink.CORRUPT_MARK, decoded.text.first())
    }

    // ------------------------------------------------------------------ the frame

    @Test
    fun `a message ends where it says it ends, not where the slots run out`() {
        val slots = Blink.encode("END") + List(40) { it % 3 == 0 }

        val decoded = Blink.decode(slots)

        assertEquals("END", decoded.text)
        assertTrue(decoded.complete)
    }

    @Test
    fun `a message cut off partway reads what arrived and says it is incomplete`() {
        val whole = Blink.encode("TRUNCATED")
        val partial = whole.take(Blink.PREAMBLE.size + Blink.START.size + Blink.SLOTS_PER_CHAR * 4)

        val decoded = Blink.decode(partial)

        assertEquals("TRUN", decoded.text)
        assertTrue(!decoded.complete)
        assertTrue(decoded.sawPreamble)
    }

    @Test
    fun `the start marker cannot occur inside the preamble`() {
        // Three ones in a row, which an alternating run cannot produce however it is
        // aligned, so the receiver cannot mistake one for the other.
        val preamble = Blink.PREAMBLE
        val runs = preamble.indices.drop(2).count { index ->
            preamble[index] && preamble[index - 1] && preamble[index - 2]
        }

        assertEquals(0, runs)
    }

    @Test
    fun `no preamble at all is reported rather than guessed around`() {
        val decoded = Blink.decode(List(40) { false })

        assertTrue(!decoded.sawPreamble)
        assertTrue(!decoded.anything)
    }

    // ------------------------------------------------------------------ what it costs

    @Test
    fun `parity adds one slot per character and nothing else does`() {
        assertEquals(Blink.BITS + 1, Blink.SLOTS_PER_CHAR)
        assertEquals(Blink.SLOTS_PER_CHAR, Blink.symbol(Blink.codeFor('A')).size)
    }

    @Test
    fun `the speed is stated plainly because it is slow`() {
        // Seven slots a character at half a second is about seventeen characters a minute,
        // which is slower than somebody sending morse by hand.
        assertEquals(17.1, Blink.charsPerMinute(500L), 0.2)

        val message = "HELLO"
        assertEquals(
            Blink.encode(message).size * 500L,
            Blink.durationMs(message, 500L),
        )
    }
}
