package com.sigeye.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the message types out of an Apple Continuity advertisement.
 *
 * The bodies rotate with the address on purpose. The set of types does not, because it says
 * what the device is and what it is doing rather than which one it is, and that is what
 * makes it worth reading through a rotation.
 */
class ContinuityTest {

    private fun bytes(vararg values: Int) =
        ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `a nearby info message on its own is found`() {
        // Type 0x10, five bytes of body.
        val types = Continuity.types(Continuity.APPLE, bytes(0x10, 0x05, 1, 2, 3, 4, 5))

        assertEquals(setOf(0x10), types)
    }

    @Test
    fun `several messages in one packet are all found`() {
        // What an iPhone near its own watch actually sends: handoff and nearby info
        // packed one after the other in the same manufacturer field.
        val types = Continuity.types(
            Continuity.APPLE,
            bytes(0x0C, 0x02, 0xAA, 0xBB, 0x10, 0x03, 1, 2, 3),
        )

        assertEquals(setOf(0x0C, 0x10), types)
    }

    @Test
    fun `a message with an empty body still counts`() {
        assertEquals(setOf(0x0F), Continuity.types(Continuity.APPLE, bytes(0x0F, 0x00)))
    }

    @Test
    fun `nothing is read out of another vendor's payload`() {
        // The type-length-value layout is Apple's. Walking somebody else's bytes with it
        // would invent structure and put it into a fingerprint.
        assertTrue(Continuity.types(0x0075, bytes(0x10, 0x05, 1, 2, 3, 4, 5)).isEmpty())
        assertTrue(Continuity.types(null, bytes(0x10, 0x02, 1, 2)).isEmpty())
    }

    @Test
    fun `a payload whose lengths do not add up yields nothing at all`() {
        // Partial is worse than empty here. A misread packet that still returns its first
        // message puts a wrong fact into a link score with nothing to flag it.
        assertTrue(
            "body runs past the end",
            Continuity.types(Continuity.APPLE, bytes(0x10, 0x20, 1, 2)).isEmpty(),
        )
        assertTrue(
            "trailing type with no length",
            Continuity.types(Continuity.APPLE, bytes(0x0C, 0x02, 0xAA, 0xBB, 0x10)).isEmpty(),
        )
        assertTrue(Continuity.types(Continuity.APPLE, ByteArray(0)).isEmpty())
    }

    @Test
    fun `an unnamed type is still matched on`() {
        // Being unable to name a message does not make its presence less of a fact.
        val types = Continuity.types(Continuity.APPLE, bytes(0x77, 0x01, 0x00))

        assertEquals(setOf(0x77), types)
        assertTrue(Continuity.name(0x77).contains("0x77"))
    }

    @Test
    fun `the known types read as their names`() {
        assertEquals("Nearby Info", Continuity.name(0x10))
        assertEquals("Proximity Pairing", Continuity.name(0x07))
        assertTrue(Continuity.describe(setOf(0x0C, 0x10)).contains("Handoff"))
        assertEquals("no Apple messages", Continuity.describe(emptySet()))
    }
}
