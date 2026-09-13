package com.sigeye.core.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvertCodecTest {

    private val base = 1_700_000_000_000L

    private val full = Advert(
        address = "AA:BB:CC:DD:EE:FF",
        rssi = -63,
        atMs = base + 4_500L,
        name = "Sam's AirPods",
        companyId = 0x004C,
        manufacturerData = byteArrayOf(0x07, 0x15, 0x00, 0xFF.toByte()),
        serviceUuids = listOf("180F", "FD6F"),
        serviceData = mapOf("FD6F" to byteArrayOf(0x01, 0x02)),
        txPower = 12,
        appearance = 0x0040,
        isLegacy = false,
        isConnectable = true,
        primaryPhy = 1,
        secondaryPhy = 2,
        advertisingSid = 3,
    )

    @Test
    fun `an advertisement survives the round trip intact`() {
        val line = AdvertCodec.encode(full, base)
        val back = AdvertCodec.decode(line, base)!!

        assertEquals(full.address, back.address)
        assertEquals(full.rssi, back.rssi)
        assertEquals(full.atMs, back.atMs)
        assertEquals(full.name, back.name)
        assertEquals(full.companyId, back.companyId)
        assertArrayEquals(full.manufacturerData, back.manufacturerData)
        assertEquals(full.serviceUuids, back.serviceUuids)
        assertArrayEquals(full.serviceData["FD6F"], back.serviceData["FD6F"])
        assertEquals(full.txPower, back.txPower)
        assertEquals(full.appearance, back.appearance)
        assertEquals(full.isLegacy, back.isLegacy)
        assertEquals(full.isConnectable, back.isConnectable)
        assertEquals(full.primaryPhy, back.primaryPhy)
        assertEquals(full.secondaryPhy, back.secondaryPhy)
        assertEquals(full.advertisingSid, back.advertisingSid)
    }

    @Test
    fun `a name with a comma in it does not shift every column after it`() {
        // The kind of corruption nobody notices until a replay makes no sense.
        val named = full.copy(name = "Kitchen, back")
        val line = AdvertCodec.encode(named, base)
        val back = AdvertCodec.decode(line, base)!!
        assertEquals("Kitchen, back", back.name)
        assertEquals(named.rssi, back.rssi)
        assertEquals(named.advertisingSid, back.advertisingSid)
    }

    @Test
    fun `a bare advertisement round trips with its empty fields still empty`() {
        val bare = Advert(address = "5A:11:22:33:44:55", rssi = -90, atMs = base)
        val back = AdvertCodec.decode(AdvertCodec.encode(bare, base), base)!!
        assertNull(back.name)
        assertNull(back.companyId)
        assertNull(back.manufacturerData)
        assertNull(back.txPower)
        assertNull(back.appearance)
        assertTrue(back.serviceUuids.isEmpty())
        assertTrue(back.serviceData.isEmpty())
    }

    @Test
    fun `times are written relative, so a capture can be replayed as now`() {
        val line = AdvertCodec.encode(full, base)
        assertTrue(line.startsWith("4500,"))
        // Replayed against a different base, the packet claims to have arrived now rather
        // than in 2023 - which matters, because half the app decides whether a device is
        // present by comparing a timestamp to the clock.
        val later = 1_900_000_000_000L
        assertEquals(later + 4_500L, AdvertCodec.decode(line, later)!!.atMs)
    }

    @Test
    fun `headers, comments and blank lines are skipped rather than decoded`() {
        assertNull(AdvertCodec.decode(AdvertCodec.HEADER, base))
        assertNull(AdvertCodec.decode("# SigEye capture v1", base))
        assertNull(AdvertCodec.decode("   ", base))
    }

    @Test
    fun `one corrupt line does not throw away the rest of the afternoon`() {
        assertNull(AdvertCodec.decode("nonsense", base))
        assertNull(AdvertCodec.decode("4500,AA:BB", base))
        assertNull(AdvertCodec.decode(",,,,,,,,,,,,,,", base))
    }

    @Test
    fun `hex survives every byte value, including the negative ones`() {
        val bytes = ByteArray(256) { (it - 128).toByte() }
        assertArrayEquals(bytes, AdvertCodec.unhex(AdvertCodec.hex(bytes)))
    }

    @Test
    fun `hex that is not hex comes back as nothing rather than as garbage`() {
        assertNull(AdvertCodec.unhex("ABC"))
        assertNull(AdvertCodec.unhex("ZZ"))
    }

    @Test
    fun `the span of a capture is its first offset to its last`() {
        val lines = listOf(
            "# comment",
            AdvertCodec.HEADER,
            AdvertCodec.encode(full.copy(atMs = base + 1_000), base),
            AdvertCodec.encode(full.copy(atMs = base + 61_000), base),
        )
        assertEquals(60_000L, AdvertCodec.spanMs(lines))
        assertEquals(0L, AdvertCodec.spanMs(listOf("# nothing here")))
    }
}
