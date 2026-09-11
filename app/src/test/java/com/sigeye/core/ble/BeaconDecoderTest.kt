package com.sigeye.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconDecoderTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun decode(
        companyId: Int? = null,
        manufacturerData: ByteArray? = null,
        serviceData: Map<String, ByteArray> = emptyMap(),
        serviceUuids: List<String> = emptyList(),
    ) = BeaconDecoder.decode(companyId, manufacturerData, serviceData, serviceUuids)

    private val eddystoneUuid = "0000feaa-0000-1000-8000-00805f9b34fb"

    // ---------------------------------------------------------------- iBeacon

    @Test
    fun `decodes an iBeacon`() {
        val payload = bytes(
            0x02, 0x15,
            0xE2, 0xC5, 0x6D, 0xB5, 0xDF, 0xFB, 0x48, 0xD2,
            0xB0, 0x60, 0xD0, 0xF5, 0xA7, 0x10, 0x96, 0xE0,
            0x00, 0x07, // major 7
            0x00, 0x2A, // minor 42
            0xC5, // -59 dBm
        )
        val beacon = decode(companyId = 0x004C, manufacturerData = payload)
        assertNotNull(beacon)
        assertEquals("iBeacon", beacon!!.protocol)
        assertEquals(-59, beacon.measuredPower)

        val fields = beacon.fields.associate { it.label to it.value }
        assertEquals("e2c56db5-dffb-48d2-b060-d0f5a71096e0", fields["Proximity UUID"])
        assertEquals("7", fields["Major"])
        assertEquals("42", fields["Minor"])
    }

    @Test
    fun `an apple payload that is not an iBeacon is not read as one`() {
        // Type 0x12 is Find My, not 0x02.
        val beacon = decode(companyId = 0x004C, manufacturerData = bytes(0x12, 0x19, 0x00, 0x01))
        assertNotNull(beacon)
        assertTrue(beacon!!.protocol.contains("Find My"))
        assertNotNull("Find My deserves an explanatory note", beacon.note)
    }

    @Test
    fun `an iBeacon-shaped payload from another vendor is not read as an iBeacon`() {
        // The 0x02 0x15 header only means iBeacon under Apple's company ID. Under anyone
        // else it is just bytes, and the decoder must not borrow Apple's meaning for them.
        val payload = bytes(0x02, 0x15) + ByteArray(21)
        val beacon = decode(companyId = 0x0075, manufacturerData = payload)
        assertNotEquals("iBeacon", beacon?.protocol)
        // It still names the vendor rather than throwing the advertisement away.
        assertEquals("Samsung", beacon?.protocol)
    }

    @Test
    fun `a truncated iBeacon does not crash or half-decode`() {
        val payload = bytes(0x02, 0x15, 0xE2, 0xC5)
        val beacon = decode(companyId = 0x004C, manufacturerData = payload)
        // Falls through to the Apple continuity reader rather than pretending to be a beacon.
        assertTrue(beacon == null || beacon.protocol != "iBeacon")
    }

    // -------------------------------------------------------------- Eddystone

    @Test
    fun `decodes an Eddystone UID frame`() {
        val payload = bytes(
            0x00, 0xEE,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, // namespace
            0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, // instance
        )
        val beacon = decode(serviceData = mapOf(eddystoneUuid to payload))
        assertNotNull(beacon)
        assertEquals("Eddystone-UID", beacon!!.protocol)
        val fields = beacon.fields.associate { it.label to it.value }
        assertEquals("0102030405060708090A", fields["Namespace"])
        assertEquals("AABBCCDDEEFF", fields["Instance"])
    }

    @Test
    fun `decodes an Eddystone URL with scheme and suffix compression`() {
        // 0x10 URL, ranging -18, scheme 0x03 = https://, "example", 0x00 = ".com/"
        val payload = bytes(0x10, 0xEE, 0x03) +
            "example".toByteArray(Charsets.US_ASCII) +
            bytes(0x00)
        val beacon = decode(serviceData = mapOf(eddystoneUuid to payload))
        assertNotNull(beacon)
        assertEquals("Eddystone-URL", beacon!!.protocol)
        assertEquals("https://example.com/", beacon.fields.first().value)
    }

    @Test
    fun `decodes Eddystone telemetry`() {
        val payload = bytes(
            0x20, 0x00,
            0x0B, 0xB8, // 3000 mV
            0x15, 0x00, // 21.0 C
            0x00, 0x00, 0x03, 0xE8, // 1000 adverts
            0x00, 0x00, 0x1C, 0x20, // 7200 * 0.1s = 720s = 12m
        )
        val beacon = decode(serviceData = mapOf(eddystoneUuid to payload))
        assertNotNull(beacon)
        assertEquals("Eddystone-TLM", beacon!!.protocol)
        val fields = beacon.fields.associate { it.label to it.value }
        assertEquals("3000 mV", fields["Battery"])
        assertTrue(fields["Temperature"]!!.startsWith("21.0"))
        assertEquals("1000", fields["Advertisements"])
        assertEquals("12m", fields["Uptime"])
    }

    @Test
    fun `an unknown Eddystone frame type is not guessed at`() {
        val beacon = decode(serviceData = mapOf(eddystoneUuid to bytes(0x99, 0x00)))
        // Falls back to naming the service rather than inventing a decode.
        assertTrue(beacon == null || beacon.protocol == "Eddystone")
    }

    // ------------------------------------------------------------- AltBeacon

    @Test
    fun `decodes an AltBeacon`() {
        val payload = bytes(0xBE, 0xAC) + ByteArray(20) { (it + 1).toByte() } +
            bytes(0xC5, 0x00)
        val beacon = decode(companyId = 0x0118, manufacturerData = payload)
        assertNotNull(beacon)
        assertEquals("AltBeacon", beacon!!.protocol)
        assertEquals(-59, beacon.measuredPower)
    }

    // ------------------------------------------------------------------ misc

    @Test
    fun `names a known service uuid when nothing else matches`() {
        val beacon = decode(serviceUuids = listOf("0000fd6f-0000-1000-8000-00805f9b34fb"))
        assertNotNull(beacon)
        assertEquals("Exposure Notification", beacon!!.protocol)
    }

    @Test
    fun `names the vendor when the manufacturer format is unrecognised`() {
        val beacon = decode(companyId = 0x0075, manufacturerData = bytes(0x01, 0x02, 0x03))
        assertNotNull(beacon)
        assertEquals("Samsung", beacon!!.protocol)
    }

    @Test
    fun `returns null when there is nothing to say`() {
        assertNull(decode())
        assertNull(decode(companyId = 0xFFFF, manufacturerData = bytes(0x01)))
    }

    @Test
    fun `shortens a 128-bit uuid to its 16-bit form`() {
        assertEquals("FEAA", BeaconDecoder.shortUuid(eddystoneUuid))
        assertEquals("FD6F", BeaconDecoder.shortUuid("0000FD6F-0000-1000-8000-00805F9B34FB"))
    }
}
