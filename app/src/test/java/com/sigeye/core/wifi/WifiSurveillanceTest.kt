package com.sigeye.core.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiSurveillanceTest {

    @Test
    fun `a prefix registered to the company that sells the thing is a strong match`() {
        val match = WifiSurveillance.match("00:25:DF:11:22:33", "SomeNetwork")
        assertNotNull(match)
        assertEquals(Confidence.STRONG, match!!.confidence)
        assertTrue(match.reason.contains("Axon"))
    }

    @Test
    fun `even a strong match says nothing about what the device is doing`() {
        val match = WifiSurveillance.match("00:25:DF:11:22:33", null)!!
        assertTrue(match.caveat!!.contains("which of their products"))
    }

    @Test
    fun `a component vendor prefix matches nothing at all any more`() {
        // Liteon, Silicon Labs, Espressif and the rest of the flock-you list are module
        // makers whose parts are inside an enormous number of ordinary devices. Matching
        // on those flagged laptops and televisions, and a warning that cries wolf teaches
        // people to ignore the one that matters.
        listOf("70:C9:4E", "58:8E:81", "3C:71:BF", "08:3A:88", "48:27:EA").forEach { oui ->
            assertNull(oui, WifiSurveillance.match("$oui:11:22:33", "Home"))
        }
    }

    @Test
    fun `an ordinary access point matches nothing`() {
        assertNull(WifiSurveillance.match("48:CA:43:11:22:33", "MyWiFi"))
        assertNull(WifiSurveillance.match("00:11:22:33:44:55", null))
    }

    @Test
    fun `a suggestive name is a prompt rather than a finding`() {
        val match = WifiSurveillance.match("00:11:22:33:44:55", "axon-truck-12")
        assertNotNull(match)
        assertEquals(Confidence.WEAK, match!!.confidence)
        assertTrue(match.caveat!!.contains("self-reported"))
    }

    @Test
    fun `a registered prefix outranks a suggestive name`() {
        // Both would match; the one backed by a registry should win.
        val match = WifiSurveillance.match("00:25:DF:11:22:33", "axon")!!
        assertEquals(Confidence.STRONG, match.confidence)
    }

    @Test
    fun `case and separator do not matter`() {
        assertEquals("00:25:DF", WifiSurveillance.ouiOf("00-25-df-11-22-33"))
        assertEquals("00:25:DF", WifiSurveillance.ouiOf("00:25:DF:11:22:33"))
        assertNotNull(WifiSurveillance.match("00:25:df:aa:bb:cc", null))
    }

    @Test
    fun `every match carries a caveat, because one without reads as certainty`() {
        listOf(
            WifiSurveillance.match("00:25:DF:11:22:33", null),
            WifiSurveillance.match("00:11:22:33:44:55", "axis-cam-1"),
        ).forEach { match ->
            assertNotNull(match)
            assertTrue(match!!.caveat!!.isNotBlank())
        }
    }
}
