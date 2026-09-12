package com.sigeye.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattGlossaryTest {

    private fun sig(short: String) = "0000$short-0000-1000-8000-00805f9b34fb"

    // ------------------------------------------------------------- identifying

    @Test
    fun `a SIG uuid resolves to its registry name`() {
        assertEquals("Battery Service", GattGlossary.serviceName(sig("180f")))
        assertEquals("Device Name", GattGlossary.characteristicName(sig("2a00")))
        assertEquals("Serial Number String", GattGlossary.characteristicName(sig("2a25")))
    }

    @Test
    fun `a member uuid names the company that owns it`() {
        val explained = GattGlossary.explainService(sig("fe2c"))
        assertTrue(explained, explained.contains("Google"))
    }

    @Test
    fun `a custom uuid is reported as custom rather than guessed at`() {
        val custom = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        assertNull(GattGlossary.short(custom))
        assertNull(GattGlossary.serviceName(custom))
        assertTrue(GattGlossary.explainService(custom).contains("custom"))
        assertTrue(GattGlossary.explainCharacteristic(custom).contains("just bytes"))
    }

    @Test
    fun `case does not matter, because Android is inconsistent about it`() {
        assertEquals(0x180F, GattGlossary.short("0000180F-0000-1000-8000-00805F9B34FB"))
        assertEquals(0x180F, GattGlossary.short(sig("180f")))
    }

    @Test
    fun `a uuid that only looks like the base is not accepted`() {
        assertNull(GattGlossary.short("1234180f-0000-1000-8000-00805f9b34fb"))
        assertNull(GattGlossary.short("not-a-uuid"))
        assertNull(GattGlossary.short(""))
    }

    // ----------------------------------------------------------------- values

    @Test
    fun `text characteristics come back as text`() {
        assertEquals(
            "Pixel Buds",
            GattGlossary.render(sig("2a00"), "Pixel Buds".toByteArray()),
        )
        assertEquals(
            "ACME Ltd",
            GattGlossary.render(sig("2a29"), "ACME Ltd ".toByteArray()),
        )
    }

    @Test
    fun `battery level is a percentage, not a byte`() {
        assertEquals("87%", GattGlossary.render(sig("2a19"), byteArrayOf(87)))
        // And the top of the range does not wrap into a negative.
        assertEquals("200%", GattGlossary.render(sig("2a19"), byteArrayOf(200.toByte())))
    }

    @Test
    fun `appearance is decoded through the registry`() {
        // 0x00C2: Watch, subcategory 2 - a smartwatch. Little-endian on the wire.
        val rendered = GattGlossary.render(sig("2a01"), byteArrayOf(0xC2.toByte(), 0x00))
        assertEquals("Watch - Smartwatch", rendered)
    }

    @Test
    fun `anything unrecognised falls back to hex rather than a guess`() {
        // A wrong decode reads as a confident fact. Raw bytes obviously do not.
        val custom = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        assertEquals("DE AD BE EF", GattGlossary.render(custom, byteArrayOf(
            0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(),
        )))
    }

    @Test
    fun `empty and missing values say so instead of rendering as nothing`() {
        assertEquals("(empty)", GattGlossary.render(sig("2a00"), null))
        assertEquals("(empty)", GattGlossary.render(sig("2a00"), ByteArray(0)))
    }

    @Test
    fun `a text field holding unprintable bytes falls back to hex`() {
        // Some devices pad a string field with zeroes and nothing else.
        assertEquals("00 00", GattGlossary.render(sig("2a24"), byteArrayOf(0, 0)))
    }

    @Test
    fun `a truncated appearance is not read as a wrong category`() {
        val rendered = GattGlossary.render(sig("2a01"), byteArrayOf(0xC2.toByte()))
        assertEquals("C2", rendered)
    }

    // ------------------------------------------------------------- properties

    @Test
    fun `properties are described in words`() {
        val readWrite = GattGlossary.PROPERTY_READ or GattGlossary.PROPERTY_WRITE
        val described = GattGlossary.describeProperties(readWrite)
        assertTrue(described.contains("readable"))
        assertTrue(described.contains("writable"))
        assertTrue(GattGlossary.isReadable(readWrite))
    }

    @Test
    fun `a notify-only characteristic is not treated as readable`() {
        assertTrue(!GattGlossary.isReadable(GattGlossary.PROPERTY_NOTIFY))
        assertEquals("notifies", GattGlossary.describeProperties(GattGlossary.PROPERTY_NOTIFY))
    }

    @Test
    fun `a characteristic declaring nothing says so rather than looking readable`() {
        assertEquals("no properties declared", GattGlossary.describeProperties(0))
        assertTrue(!GattGlossary.isReadable(0))
    }

    // ------------------------------------------------------------ explanations

    @Test
    fun `the identifiers worth worrying about carry a warning`() {
        // A serial number does not rotate the way a random MAC does, which is the whole
        // point of mentioning it.
        assertTrue(GattGlossary.explainCharacteristic(sig("2a25")).contains("does not change"))
        // System ID commonly contains the device's real MAC.
        assertTrue(GattGlossary.explainCharacteristic(sig("2a23")).contains("MAC"))
    }

    @Test
    fun `every explanation says something, even for unknown registry entries`() {
        listOf("2a00", "2a01", "2a19", "2a24", "2a29", "1800", "180a", "180f").forEach {
            assertTrue(GattGlossary.explainCharacteristic(sig(it)).isNotBlank())
            assertTrue(GattGlossary.explainService(sig(it)).isNotBlank())
        }
    }
}
