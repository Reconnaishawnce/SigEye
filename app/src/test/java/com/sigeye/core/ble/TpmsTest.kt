package com.sigeye.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TpmsTest {

    /** The documented example advertisement, minus the company ID Android strips off. */
    private val example = hex("80EACA108A78E36D0000E60A00005B00")

    private fun hex(text: String): ByteArray =
        text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `decodes the reference advertisement`() {
        val reading = Tpms.decode(Tpms.COMPANY_ID, example)
        assertNotNull(reading)
        reading!!
        assertEquals(1, reading.sensorNumber)
        assertEquals("10:8A:78", reading.sensorAddress)
        assertEquals(91, reading.batteryPercent)
        assertTrue(!reading.alarm)
    }

    /**
     * The cross-check that settles the units.
     *
     * The community write-up says pressure divides by a thousand, which would make this
     * advertisement 28 kPa - a flat tyre, or no tyre. Temperature in the same packet
     * divides by a hundred and gives 27.9 C, which is exactly a room, and applying the
     * same hundred to pressure gives 281 kPa: 41 psi, a perfectly ordinary car tyre. Two
     * independent fields agreeing on the divisor is better evidence than the write-up.
     */
    @Test
    fun `the converted values are physically sensible`() {
        val reading = Tpms.decode(Tpms.COMPANY_ID, example)!!
        assertEquals(27.9, reading.celsius, 0.01)
        assertEquals(82.2, reading.fahrenheit, 0.1)
        assertEquals(281.31, reading.kilopascals, 0.01)
        assertEquals(40.8, reading.psi, 0.1)
        assertEquals(2.81, reading.bar, 0.01)
        // And the raw numbers survive, so a real sensor can be checked against a gauge.
        assertEquals(28131, reading.rawPressure)
        assertEquals(2790, reading.rawTemperature)
    }

    @Test
    fun `each tyre reports its own number`() {
        listOf(0x80 to 1, 0x81 to 2, 0x82 to 3, 0x83 to 4).forEach { (byte, tyre) ->
            val data = example.copyOf()
            data[0] = byte.toByte()
            val reading = Tpms.decode(Tpms.COMPANY_ID, data)
            assertEquals(tyre, reading!!.sensorNumber)
            assertEquals("Tyre $tyre", reading.tyreLabel)
        }
    }

    @Test
    fun `the alarm flag is read`() {
        val data = example.copyOf()
        data[15] = 1
        assertTrue(Tpms.decode(Tpms.COMPANY_ID, data)!!.alarm)
    }

    // ------------------------------------------------------------- rejection

    @Test
    fun `another company's advertisement is not read as a tyre`() {
        assertNull(Tpms.decode(0x004C, example))
        assertNull(Tpms.decode(null, example))
    }

    @Test
    fun `a TomTom device that is actually a TomTom is rejected`() {
        // The company ID genuinely belongs to somebody else, so the EA CA marker is what
        // makes the claim safe. Without that check every TomTom in range would be a tyre.
        val notATyre = hex("80112233445566778899AABBCCDDEEFF")
        assertNull(Tpms.decode(Tpms.COMPANY_ID, notATyre))
    }

    @Test
    fun `a truncated payload is refused rather than read past the end`() {
        assertNull(Tpms.decode(Tpms.COMPANY_ID, hex("80EACA108A78")))
        assertNull(Tpms.decode(Tpms.COMPANY_ID, ByteArray(0)))
        assertNull(Tpms.decode(Tpms.COMPANY_ID, null))
    }

    @Test
    fun `a sensor number outside the plausible range is refused`() {
        val data = example.copyOf()
        data[0] = 0x40
        assertNull(Tpms.decode(Tpms.COMPANY_ID, data))
    }

    // --------------------------------------------------------- by address alone

    @Test
    fun `the address pattern is recognised without a payload`() {
        assertTrue(Tpms.looksLikeSensor("80:EA:CA:10:8A:78"))
        assertTrue(Tpms.looksLikeSensor("83:ea:ca:11:22:33"))
        assertTrue(Tpms.looksLikeSensor("81-EA-CA-11-22-33"))
    }

    @Test
    fun `an ordinary address is not mistaken for a sensor`() {
        assertTrue(!Tpms.looksLikeSensor("48:CA:43:BA:B2:C6"))
        assertTrue(!Tpms.looksLikeSensor("80:11:22:33:44:55"))
        assertTrue(!Tpms.looksLikeSensor("nonsense"))
        assertTrue(!Tpms.looksLikeSensor(""))
    }

    @Test
    fun `four sensors from one vehicle share an address family`() {
        // A set of four is a fingerprint for one car, and unlike a phone it never rotates.
        val wheels = listOf(
            "80:EA:CA:10:8A:78",
            "81:EA:CA:10:8A:79",
            "82:EA:CA:10:8A:7A",
            "83:EA:CA:10:8A:7B",
        )
        assertTrue(wheels.all { Tpms.looksLikeSensor(it) })
    }
}
