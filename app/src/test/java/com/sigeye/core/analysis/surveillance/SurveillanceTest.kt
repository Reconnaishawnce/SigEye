package com.sigeye.core.analysis.surveillance

import com.sigeye.core.ble.Advert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recognizing Flock Safety and Axon hardware from what it puts on the air.
 *
 * The Flock fixtures are not invented. They are the packet ryanohoro captured and published
 * at https://www.ryanohoro.com/post/spotting-flock-safety-s-falcon-cameras, byte for byte,
 * so these tests fail if the matcher stops recognizing a real camera battery.
 */
class SurveillanceTest {

    private fun hex(text: String): ByteArray {
        val clean = text.replace(" ", "")
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    private fun advert(
        address: String = "D8:A0:D8:9F:4A:5E",
        name: String? = null,
        companyId: Int? = null,
        manufacturerData: ByteArray? = null,
    ) = Advert(
        address = address,
        rssi = -70,
        atMs = 1_700_000_000_000L,
        name = name,
        companyId = companyId,
        manufacturerData = manufacturerData,
    )

    /**
     * The real thing.
     *
     * Scan response `1dffc809d8a0d89f4a5e2030502a544e3732303233303232303030373731` from a
     * device advertising as d8:a0:d8:9f:4a:5e. Strip the length, the manufacturer-specific
     * type and the little-endian company identifier, and this is what is left.
     */
    private val capturedPayload = hex("d8a0d89f4a5e2030502a544e3732303233303232303030373731")

    private fun capturedBattery(address: String = "D8:A0:D8:9F:4A:5E") =
        advert(address = address, companyId = Flock.XUNTONG, manufacturerData = capturedPayload)

    // ------------------------------------------------------------------ the serial

    @Test
    fun `the published capture yields the serial printed on the battery`() {
        assertEquals("TN72023022000771", Flock.serialOf(capturedBattery()))
    }

    @Test
    fun `a battery is confirmed by its serial rather than by its company identifier`() {
        val sighting = Surveillance.fromBle(capturedBattery())!!

        assertEquals(Certainty.CONFIRMED, sighting.certainty)
        assertEquals("Flock Safety", sighting.operator)
        assertEquals("TN72023022000771", sighting.serial)
        assertTrue(sighting.why.contains("TN72023022000771"))
    }

    @Test
    fun `the serial survives the address rotating, which is the whole point`() {
        // The advertising address is random and changes. The payload echoes whatever the
        // current address is, and the serial at the end of it does not move. If this ever
        // stops holding, recognizing one physical pole over days stops working.
        val rotated = "5C:11:22:33:44:55"
        val payload = hex("5c1122334455" + "2030502a" + "544e3732303233303232303030373731")

        val sighting = Surveillance.fromBle(
            advert(address = rotated, companyId = Flock.XUNTONG, manufacturerData = payload),
        )!!

        assertEquals("TN72023022000771", sighting.serial)
        assertEquals(Certainty.CONFIRMED, sighting.certainty)
    }

    @Test
    fun `a payload that does not open with its own address is not read as a serial`() {
        // The check that separates a camera battery from anything else sharing the XUNTONG
        // number. Without it, any payload with readable bytes in it would be a Flock hit.
        val impostor = advert(
            address = "AA:BB:CC:DD:EE:FF",
            companyId = Flock.XUNTONG,
            manufacturerData = capturedPayload,
        )

        assertNull(Flock.serialOf(impostor))
        assertEquals(Certainty.POSSIBLE, Surveillance.fromBle(impostor)!!.certainty)
    }

    @Test
    fun `binary where the serial should be is not printed as text`() {
        // Mojibake on screen presented as evidence is worse than saying nothing.
        val binary = hex("d8a0d89f4a5e" + "2030502a" + "00ff01fe02fd03fc04fb05fa")

        assertNull(Flock.serialOf(capturedBattery().copy(manufacturerData = binary)))
    }

    @Test
    fun `a payload too short to hold a serial is rejected rather than truncated`() {
        val stub = hex("d8a0d89f4a5e2030502a544e")

        assertNull(Flock.serialOf(capturedBattery().copy(manufacturerData = stub)))
    }

    // ------------------------------------------------------------------ the names

    @Test
    fun `a hardwired unit names itself outright`() {
        val sighting = Surveillance.fromBle(advert(name = "FS Ext Battery"))!!

        assertEquals(Certainty.CONFIRMED, sighting.certainty)
        assertEquals("Flock Safety", sighting.operator)
    }

    @Test
    fun `firmware from before March 2025 still says Penguin`() {
        val sighting = Surveillance.fromBle(advert(name = "Penguin-1234567890"))!!

        assertEquals(Certainty.CONFIRMED, sighting.certainty)
        assertTrue(sighting.caveat!!.contains("March 2025"))
    }

    @Test
    fun `ten bare digits count only alongside the company identifier`() {
        // Flock stripped the word Penguin in March 2025 and left the digits. On their own
        // those are nothing - a great many devices advertise a number.
        val alone = Surveillance.fromBle(advert(name = "1234567890"))
        val withCompany = Surveillance.fromBle(
            advert(name = "1234567890", companyId = Flock.XUNTONG),
        )!!

        assertNull(alone)
        assertEquals(Certainty.LIKELY, withCompany.certainty)
    }

    @Test
    fun `a nine digit name is not the pattern`() {
        assertNull(Surveillance.fromBle(advert(name = "123456789", companyId = null)))
    }

    // ------------------------------------------------------------------ not crying wolf

    @Test
    fun `a bare XUNTONG device is possible and is told it might be a tyre`() {
        // The same company identifier is on tyre pressure sensors, so a hit with no serial
        // has to say so rather than announce a camera.
        val sighting = Surveillance.fromBle(advert(companyId = Flock.XUNTONG))!!

        assertEquals(Certainty.POSSIBLE, sighting.certainty)
        assertTrue(sighting.caveat!!.contains("tyre"))
    }

    @Test
    fun `an ordinary phone is not surveillance hardware`() {
        val phone = advert(
            address = "5A:11:22:33:44:55",
            companyId = 0x004C,
            manufacturerData = hex("10050318c0ffee"),
        )

        assertNull(Surveillance.fromBle(phone))
    }

    // ------------------------------------------------------------------ Wi-Fi

    @Test
    fun `an installer network corroborated by its own address is confirmed`() {
        val sighting = Surveillance.fromWifi("9C:2F:9D:6F:45:A7", "Flock-6F45A7")!!

        assertEquals(Certainty.CONFIRMED, sighting.certainty)
        assertTrue(sighting.why.contains("own address"))
        assertTrue(sighting.caveat!!.contains("installation"))
    }

    @Test
    fun `the naming scheme alone is likely rather than confirmed`() {
        val sighting = Surveillance.fromWifi("AA:BB:CC:DD:EE:FF", "Flock-123456")!!

        assertEquals(Certainty.LIKELY, sighting.certainty)
    }

    @Test
    fun `a network somebody named after a bird is only possible`() {
        val sighting = Surveillance.fromWifi("AA:BB:CC:DD:EE:FF", "flock of seagulls")!!

        assertEquals(Certainty.POSSIBLE, sighting.certainty)
    }

    // ------------------------------------------------------------------ Axon

    @Test
    fun `Axon hardware is named by its IEEE block on either radio`() {
        // It used to be checked on Wi-Fi only, so a body camera advertising over Bluetooth
        // from a fixed address went unremarked.
        val overBle = Surveillance.fromBle(advert(address = "00:25:DF:11:22:33"))!!
        val overWifi = Surveillance.fromWifi("00:25:DF:11:22:33", "SomeNetwork")!!

        assertEquals("Axon Enterprise", overBle.operator)
        assertEquals("Axon Enterprise", overWifi.operator)
        assertEquals(Certainty.CONFIRMED, overBle.certainty)
    }

    @Test
    fun `an Axon hit says that silence proves nothing`() {
        // Axon said in 2023 it was moving to rotating addresses and dropping serials. A
        // matcher that stays quiet afterwards must not be read as an all clear.
        val sighting = Surveillance.fromBle(advert(address = "00:25:DF:11:22:33"))!!

        assertTrue(sighting.caveat!!.contains("not evidence that nothing is there"))
    }

    @Test
    fun `a randomized address is never credited to a manufacturer block`() {
        // 5A is a random static address. The first two bits say so, and the bytes after
        // them mean nothing, so reading an OUI out of them would invent a vendor.
        assertNull(Surveillance.fromBle(advert(address = "5A:25:DF:11:22:33")))
    }

    @Test
    fun `component vendor prefixes are still not treated as findings`() {
        // The Liteon module inside a Falcon is also inside a great many laptops. Matching
        // on it fires constantly, and a warning that cries wolf teaches people to ignore
        // the one that matters.
        listOf("00:22:6C", "48:CA:43", "1C:5F:2B").forEach { oui ->
            assertNull(oui, Surveillance.fromWifi("$oui:11:22:33", "Home"))
        }
    }

    @Test
    fun `an unremarkable network is not reported at all`() {
        assertNull(Surveillance.fromWifi("48:CA:43:11:22:33", "MyWiFi"))
        assertNull(Surveillance.fromWifi("00:11:22:33:44:55", null))
    }

    @Test
    fun `self reported names stay possible however suggestive`() {
        val sighting = Surveillance.fromWifi("00:11:22:33:44:55", "axon-truck-12")!!

        assertEquals(Certainty.POSSIBLE, sighting.certainty)
        assertTrue(sighting.caveat!!.contains("self-reported"))
    }

    @Test
    fun `hardware beats a name when both are present`() {
        val sighting = Surveillance.fromWifi("00:25:DF:11:22:33", "axon")!!

        assertEquals(Certainty.CONFIRMED, sighting.certainty)
        assertTrue(sighting.why.contains("00:25:DF"))
    }

    @Test
    fun `an address is read the same however it is punctuated`() {
        assertEquals("00:25:DF", Surveillance.ouiOf("00-25-df-11-22-33"))
        assertEquals("00:25:DF", Surveillance.ouiOf("00:25:DF:11:22:33"))
        assertNotNull(Surveillance.fromWifi("00:25:df:aa:bb:cc", null))
    }
}
