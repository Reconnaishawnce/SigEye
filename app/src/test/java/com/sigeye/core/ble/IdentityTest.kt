package com.sigeye.core.ble

import com.sigeye.core.OuiRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class AppearanceTest {

    /** Builds a length-type-value advertising payload. */
    private fun record(vararg structures: Pair<Int, ByteArray>): ByteArray {
        val out = mutableListOf<Byte>()
        structures.forEach { (type, value) ->
            out.add((value.size + 1).toByte())
            out.add(type.toByte())
            value.forEach { out.add(it) }
        }
        return out.toByteArray()
    }

    private fun appearance(value: Int) = Appearance.AD_TYPE_APPEARANCE to
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    @Test
    fun `reads an appearance out of a payload`() {
        // 0x0341 - Watch, subcategory 1, a sports watch.
        assertEquals(0x0341, Appearance.parse(record(appearance(0x0341))))
    }

    @Test
    fun `finds the appearance after other structures`() {
        val raw = record(
            0x01 to byteArrayOf(0x06),                       // flags
            0x09 to "Tile".toByteArray(),                    // complete local name
            appearance(0x0200),                              // computer
        )
        assertEquals(0x0200, Appearance.parse(raw))
    }

    @Test
    fun `padding ends the walk rather than throwing`() {
        val raw = record(0x09 to "x".toByteArray()) + ByteArray(20)
        assertNull(Appearance.parse(raw))
    }

    @Test
    fun `a truncated appearance structure is not read`() {
        // Claims two bytes of value but the record ends.
        val raw = byteArrayOf(0x03, Appearance.AD_TYPE_APPEARANCE.toByte(), 0x41)
        assertNull(Appearance.parse(raw))
    }

    @Test
    fun `a length that runs off the end terminates`() {
        val raw = byteArrayOf(0x40, 0x09, 0x61, 0x62)
        assertNull(Appearance.parse(raw))
    }

    @Test
    fun `no payload is not an error`() {
        assertNull(Appearance.parse(null))
        assertNull(Appearance.parse(ByteArray(0)))
    }

    @Test
    fun `splits category from subcategory`() {
        assertEquals(0x003, Appearance.categoryOf(0x00C1))
        assertEquals(1, Appearance.subcategoryOf(0x00C1))
    }

    @Test
    fun `describes known values from the registry`() {
        assertEquals("Watch - Smartwatch", Appearance.describe(0x00C2))
        assertEquals("Phone", Appearance.describe(0x0040))
        assertTrue(Appearance.describe(0x03C1)!!.contains("Human Interface Device"))
    }

    @Test
    fun `the default of zero is reported as nothing, not as Unknown`() {
        // Category 0 is what a chip ships with. Printing "Unknown" in a field implies the
        // device said something, and it did not.
        assertNull(Appearance.describe(0x0000))
        assertNull(Appearance.describe(null))
    }

    @Test
    fun `an unlisted category still says what it saw`() {
        // Top ten bits all set: category 1023, which the registry does not define and is
        // never going to. Better to print the number than to swallow it.
        val appearance = 0xFFC0
        assertEquals(1023, Appearance.categoryOf(appearance))
        val described = Appearance.describe(appearance)
        assertNotNull(described)
        assertTrue(described!!.contains("1023"))
    }
}

class IdentityTest {

    // The clues lean on the full IEEE registry, so the tests use the asset that actually
    // ships rather than a stand-in - that way a broken asset fails here too.
    @Before
    fun setUp() = OuiRegistry.installForTest(File("src/main/assets/oui.bin").readBytes())

    @After
    fun tearDown() = OuiRegistry.installForTest(null)

    private fun clueText(clues: List<Clue>) = clues.joinToString(" | ") { it.label + ": " + it.detail }

    @Test
    fun `a randomised address is called out as telling you nothing`() {
        val clues = Identity.clues("4D:2A:6F:11:22:33", null, null, null)
        assertTrue(clueText(clues).contains("Randomised"))
        // And nothing pretends to name a manufacturer from it.
        assertTrue(clues.none { it.label == "Registered to" })
    }

    @Test
    fun `a public address is attributed to its IEEE assignee`() {
        val clues = Identity.clues("48:CA:43:BA:B2:C6", null, null, null)
        val text = clueText(clues)
        assertTrue(text.contains("48:CA:43"))
        assertTrue(text.contains("Espressif"))
    }

    @Test
    fun `a name ending in the device's own address is flagged as a factory default`() {
        val clues = Identity.clues("48:CA:43:BA:B2:C6", "widget-bab2c6", null, null)
        val name = clues.first { it.label == "Name" }
        assertTrue(name.detail.contains("factory default"))
    }

    @Test
    fun `a name one address off is recognised as the chip's other radio`() {
        // The real case: an ESP32 advertising ...BA:B2:C6 and calling itself smax-bab2c4,
        // because its Bluetooth address is the Wi-Fi one plus an offset.
        val clues = Identity.clues("48:CA:43:BA:B2:C6", "smax-bab2c4", null, null)
        val name = clues.first { it.label == "Name" }
        assertTrue(name.detail.contains("Espressif"))
        assertTrue(name.detail.contains("Wi-Fi"))
    }

    @Test
    fun `an unrelated name is not forced into a MAC explanation`() {
        val clues = Identity.clues("48:CA:43:BA:B2:C6", "Kitchen Scale", null, null)
        assertTrue(clues.none { it.label == "Name" })
        // Nor is a hex-looking tail that simply does not match.
        val other = Identity.clues("48:CA:43:BA:B2:C6", "sensor-99ff", null, null)
        assertTrue(other.none { it.label == "Name" })
    }

    @Test
    fun `a member service UUID names the ecosystem`() {
        val clues = Identity.clues(
            address = "4D:2A:6F:11:22:33",
            name = null,
            companyId = null,
            appearance = null,
            serviceUuids = listOf("0000fe2c-0000-1000-8000-00805f9b34fb"),
        )
        assertTrue(clueText(clues).contains("Google"))
    }

    @Test
    fun `a SIG service says what the device can do`() {
        val clues = Identity.clues(
            address = "4D:2A:6F:11:22:33",
            name = null,
            companyId = null,
            appearance = null,
            serviceUuids = listOf("0000180f-0000-1000-8000-00805f9b34fb"),
        )
        assertTrue(clueText(clues).contains("Battery"))
    }

    @Test
    fun `a custom 128-bit UUID is not mistaken for a registered one`() {
        assertNull(Identity.short("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))
        assertEquals(0x180F, Identity.short("0000180F-0000-1000-8000-00805F9B34FB"))
    }

    @Test
    fun `when nothing is backed by a registry it says so`() {
        // A fixed address whose prefix nobody holds: not randomised, so there is no firm
        // statement to make about it either way. 00:27:6F is unassigned, and its top bits
        // are the pattern no random address uses.
        val clues = Identity.clues("00:27:6F:11:22:33", null, null, null)
        assertTrue(clues.none { it.firm })
        assertTrue(clueText(clues).contains("Nothing in this advertisement"))
    }

    @Test
    fun `a randomised address is itself a firm statement`() {
        // It says the device is deliberately unidentifiable, which is worth knowing - so
        // the "nothing to go on" fallback should not also fire.
        val clues = Identity.clues("4D:2A:6F:11:22:33", null, null, null)
        assertTrue(clues.any { it.firm })
        assertTrue(!clueText(clues).contains("Nothing in this advertisement"))
    }

    @Test
    fun `a self-reported company ID is marked softer than the address`() {
        val clues = Identity.clues("48:CA:43:BA:B2:C6", null, 0x004C, null)
        val declared = clues.first { it.label == "Declares itself as" }
        assertTrue(declared.detail.contains("cheap to copy"))
    }
}
