package com.sigeye.core.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiSurveillanceTest {

    @Test
    fun `Flock's own registered prefix is a strong match`() {
        val match = WifiSurveillance.match("B4:1E:52:11:22:33", "SomeNetwork")
        assertNotNull(match)
        assertEquals(Confidence.STRONG, match!!.confidence)
        assertTrue(match.reason.contains("Flock Safety"))
    }

    @Test
    fun `a component vendor prefix is weak and says which vendor`() {
        // Twenty-one of the thirty-two prefixes on the flock-you list are Liteon, a Wi-Fi
        // module maker whose parts are in an enormous number of ordinary devices. Calling
        // this strong would mean flagging half a street.
        val match = WifiSurveillance.match("70:C9:4E:11:22:33", "Home")
        assertNotNull(match)
        assertEquals(Confidence.WEAK, match!!.confidence)
        assertTrue(match.reason.contains("Liteon"))
        assertTrue(match.caveat!!.contains("component vendor"))
    }

    @Test
    fun `the weak caveat explains what cannot be checked from a phone`() {
        val match = WifiSurveillance.match("3C:71:BF:11:22:33", null)!!
        // The half of flock-you's test that needs monitor mode.
        assertTrue(match.caveat!!.contains("probe-request"))
        // And what to do instead.
        assertTrue(match.caveat.contains("Corroborate"))
    }

    @Test
    fun `an ordinary access point matches nothing`() {
        assertNull(WifiSurveillance.match("48:CA:43:11:22:33", "MyWiFi"))
        assertNull(WifiSurveillance.match("00:11:22:33:44:55", null))
    }

    @Test
    fun `a suggestive name is a prompt rather than a finding`() {
        val match = WifiSurveillance.match("00:11:22:33:44:55", "FLOCK-CAM-04")
        assertNotNull(match)
        assertEquals(Confidence.WEAK, match!!.confidence)
        assertTrue(match.caveat!!.contains("Anyone can call a network anything"))
    }

    @Test
    fun `a registered prefix outranks a suggestive name`() {
        // Both would match; the one backed by a registry should win.
        val match = WifiSurveillance.match("B4:1E:52:11:22:33", "flock")!!
        assertEquals(Confidence.STRONG, match.confidence)
    }

    @Test
    fun `case and separator do not matter`() {
        assertEquals("B4:1E:52", WifiSurveillance.ouiOf("b4-1e-52-11-22-33"))
        assertEquals("B4:1E:52", WifiSurveillance.ouiOf("B4:1E:52:11:22:33"))
        assertNotNull(WifiSurveillance.match("b4:1e:52:aa:bb:cc", null))
    }

    @Test
    fun `every weak match carries a caveat, because a weak match without one reads as strong`() {
        listOf("70:C9:4E", "58:8E:81", "3C:71:BF", "08:3A:88", "48:27:EA").forEach { oui ->
            val match = WifiSurveillance.match("$oui:11:22:33", null)
            assertNotNull("no match for $oui", match)
            assertEquals(Confidence.WEAK, match!!.confidence)
            assertTrue("no caveat for $oui", !match.caveat.isNullOrBlank())
        }
    }
}

class AccessPointTest {

    private fun ap(
        frequency: Int = 2437,
        capabilities: String = "[WPA2-PSK-CCMP][ESS]",
        ssid: String? = "Net",
    ) = AccessPoint(
        bssid = "00:11:22:33:44:55",
        ssid = ssid,
        rssi = -60,
        frequencyMhz = frequency,
        capabilities = capabilities,
        seenAtMs = 0L,
    )

    @Test
    fun `channels come out right in every band`() {
        assertEquals(1, ap(frequency = 2412).channel)
        assertEquals(6, ap(frequency = 2437).channel)
        assertEquals(11, ap(frequency = 2462).channel)
        assertEquals(14, ap(frequency = 2484).channel)
        assertEquals(36, ap(frequency = 5180).channel)
        assertEquals(149, ap(frequency = 5745).channel)
    }

    @Test
    fun `bands are named from the frequency`() {
        assertEquals("2.4 GHz", ap(frequency = 2437).band)
        assertEquals("5 GHz", ap(frequency = 5180).band)
        assertEquals("6 GHz", ap(frequency = 6135).band)
    }

    @Test
    fun `security is read from the capability string`() {
        assertEquals("WPA2", ap(capabilities = "[WPA2-PSK-CCMP][ESS]").security)
        assertEquals("WPA3", ap(capabilities = "[RSN-SAE-CCMP][ESS]").security)
        assertEquals("WEP", ap(capabilities = "[WEP][ESS]").security)
        assertEquals("Open", ap(capabilities = "[ESS]").security)
        assertTrue(ap(capabilities = "[ESS]").open)
        assertTrue(!ap(capabilities = "[WPA2-PSK-CCMP][ESS]").open)
    }

    @Test
    fun `a hidden network is flagged rather than shown as blank`() {
        assertTrue(ap(ssid = null).hidden)
        assertTrue(ap(ssid = "").hidden)
        assertTrue(!ap(ssid = "Net").hidden)
    }

    @Test
    fun `an unknown frequency does not produce a nonsense channel`() {
        assertEquals(0, ap(frequency = 1000).channel)
    }
}
