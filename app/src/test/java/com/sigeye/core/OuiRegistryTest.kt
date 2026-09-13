package com.sigeye.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OuiRegistryTest {

    @After
    fun tearDown() = OuiRegistry.installForTest(null)

    /** Builds the same fixed-width format the generator emits. */
    private fun table(vararg entries: Pair<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        entries.sortedBy { it.first }.forEach { (oui, name) ->
            oui.chunked(2).forEach { out.write(it.toInt(16)) }
            val padded = name.padEnd(29).take(29).toByteArray(Charsets.UTF_8)
            out.write(padded, 0, 29)
        }
        return out.toByteArray()
    }

    @Test
    fun `finds entries at both ends and in the middle`() {
        OuiRegistry.installForTest(
            table("000000" to "First", "48CA43" to "Espressif", "FFFFFF" to "Last"),
        )
        assertEquals("First", OuiRegistry.lookup("00:00:00"))
        assertEquals("Espressif", OuiRegistry.lookup("48:CA:43"))
        assertEquals("Last", OuiRegistry.lookup("FF:FF:FF"))
    }

    @Test
    fun `an unassigned prefix is null rather than the nearest neighbor`() {
        OuiRegistry.installForTest(table("000000" to "First", "48CA43" to "Espressif"))
        assertNull(OuiRegistry.lookup("48:CA:44"))
        assertNull(OuiRegistry.lookup("99:99:99"))
    }

    @Test
    fun `accepts the forms an address actually arrives in`() {
        OuiRegistry.installForTest(table("48CA43" to "Espressif"))
        assertEquals("Espressif", OuiRegistry.lookup("48:ca:43".uppercase()))
        assertEquals("Espressif", OuiRegistry.lookupAddress("48:ca:43:ba:b2:c6"))
        assertEquals("Espressif", OuiRegistry.lookupAddress("48-CA-43-BA-B2-C6"))
    }

    @Test
    fun `malformed input does not throw`() {
        OuiRegistry.installForTest(table("48CA43" to "Espressif"))
        assertNull(OuiRegistry.lookup("48:CA"))
        assertNull(OuiRegistry.lookup(""))
        assertNull(OuiRegistry.lookup("ZZ:ZZ:ZZ"))
    }

    @Test
    fun `without a table it reports unavailable and answers nothing`() {
        assertTrue(!OuiRegistry.available)
        assertNull(OuiRegistry.lookup("48:CA:43"))
    }

    // ------------------------------------------------------- the shipped asset

    /**
     * The asset itself, not a synthetic one. A binary search over records that are not
     * sorted returns wrong answers rather than failing, so the ordering is worth asserting
     * against the file that actually ships.
     */
    @Test
    fun `the shipped registry is well formed, sorted and complete enough to be useful`() {
        val asset = File("src/main/assets/oui.bin")
        assertTrue("oui.bin is missing from assets", asset.exists())
        val bytes = asset.readBytes()
        assertEquals("record size", 0, bytes.size % 32)
        assertTrue("suspiciously small: ${bytes.size / 32} records", bytes.size / 32 > 30_000)

        var previous = -1
        for (index in 0 until bytes.size / 32) {
            val at = index * 32
            val oui = (bytes[at].toInt() and 0xFF shl 16) or
                (bytes[at + 1].toInt() and 0xFF shl 8) or
                (bytes[at + 2].toInt() and 0xFF)
            assertTrue("out of order at record $index", oui > previous)
            previous = oui
        }

        OuiRegistry.installForTest(bytes)
        // The device that prompted all this, plus the two the app calls out by name.
        assertEquals("Espressif", OuiRegistry.lookup("48:CA:43"))
        assertTrue(OuiRegistry.lookup("00:25:DF")!!.contains("Axon"))
        assertTrue(OuiRegistry.lookup("B4:1E:52")!!.contains("Flock"))
    }

    @Test
    fun `the curated name wins over the registry one`() {
        OuiRegistry.installForTest(File("src/main/assets/oui.bin").readBytes())
        // Both tables know this prefix; the curated one is the short readable name.
        assertEquals("Axon Enterprise", Vendors.byAddress("00:25:DF:11:22:33"))
        // Only the registry knows this one, and it now gets an answer where it used to
        // have none.
        assertEquals("Espressif", Vendors.byAddress("48:CA:43:BA:B2:C6"))
    }

    @Test
    fun `a registered prefix is never called randomized`() {
        OuiRegistry.installForTest(File("src/main/assets/oui.bin").readBytes())
        // 0x48 sets neither the locally-administered bit nor a public-only bit pattern -
        // its top two bits read as a resolvable private address. The registry overrules.
        assertTrue(!Vendors.isRandomAddress("48:CA:43:BA:B2:C6"))
    }

    @Test
    fun `random bits only decide once the registry can rule it out`() {
        assertTrue(!OuiRegistry.available)
        // Without the registry loaded we decline to guess from the bit pattern alone.
        assertTrue(!Vendors.isRandomAddress("4D:2A:6F:11:22:33"))

        OuiRegistry.installForTest(File("src/main/assets/oui.bin").readBytes())
        assertTrue(Vendors.isRandomAddress("4D:2A:6F:11:22:33"))
        // The locally-administered bit still works on its own terms.
        assertTrue(Vendors.isRandomAddress("4E:2A:6F:11:22:33"))
    }
}
