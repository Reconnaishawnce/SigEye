package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding your own hotspot and car in half a minute of Wi-Fi scans.
 *
 * Wi-Fi gives one reading per scan, so thirty seconds is three or four readings. The test
 * is loud and in almost every scan, not steady over dozens of packets.
 */
class MyKitWifiTest {

    private fun network(bssid: String, ssid: String?, vararg rssis: Int) = Nearby(
        address = bssid,
        name = ssid,
        rssis = rssis.toList(),
        firstSeenMs = 0L,
        lastSeenMs = 30_000L,
    )

    @Test
    fun `a loud hotspot heard in every scan is offered as yours`() {
        val found = MyKit.wifiCandidates(listOf(network("DA:A1:19:00:00:01", "Shawn's Pixel", -38, -40, -37, -39)), scans = 4)

        val hotspot = found.single()
        assertTrue(hotspot.wifi)
        assertTrue(hotspot.privateAddress)
        assertEquals("Shawn's Pixel", hotspot.label)
    }

    @Test
    fun `a car's Wi-Fi on a normal address is offered but marked as not private`() {
        val found = MyKit.wifiCandidates(listOf(network("A4:2B:B0:11:22:33", "Toyota Wi-Fi", -45, -47, -44, -46)), scans = 4)

        assertFalse(found.single().privateAddress)
    }

    @Test
    fun `the neighbour's router across the street is not yours`() {
        val found = MyKit.wifiCandidates(listOf(network("00:1A:2B:3C:4D:01", "Neighbor", -78, -80, -77, -79)), scans = 4)

        assertTrue(found.isEmpty())
    }

    @Test
    fun `a network missing from most scans is not yours`() {
        // Heard once out of four: something that drove past, not something you carry.
        val found = MyKit.wifiCandidates(listOf(network("DA:A1:19:00:00:02", "Passing", -40)), scans = 4)

        assertTrue(found.isEmpty())
    }

    @Test
    fun `missing a single scan is forgiven`() {
        // Scans drop networks now and then. Three of four is still present.
        val found = MyKit.wifiCandidates(listOf(network("DA:A1:19:00:00:01", "Hotspot", -40, -41, -39)), scans = 4)

        assertEquals(1, found.size)
    }

    @Test
    fun `one scan is too little to say anything`() {
        val found = MyKit.wifiCandidates(listOf(network("DA:A1:19:00:00:01", "Hotspot", -40)), scans = 1)

        assertTrue(found.isEmpty())
    }

    @Test
    fun `a hidden network is named as hidden`() {
        val found = MyKit.wifiCandidates(listOf(network("DA:A1:19:00:00:01", null, -40, -40, -40)), scans = 3)

        assertEquals("Hidden network", found.single().label)
    }

    @Test
    fun `every offer says why, and warns about routers you are standing beside`() {
        val why = MyKit.wifiCandidates(listOf(network("A4:2B:B0:11:22:33", "Car", -45, -45, -45)), scans = 3)
            .single().why

        assertTrue(why.contains("dBm"))
        assertTrue(why.contains("router"))
    }

    @Test
    fun `the loudest comes first`() {
        val found = MyKit.wifiCandidates(
            listOf(
                network("DA:A1:19:00:00:01", "Further", -52, -52, -52),
                network("DA:A1:19:00:00:02", "Closer", -35, -35, -35),
            ),
            scans = 3,
        )

        assertEquals(listOf("Closer", "Further"), found.map { it.label })
    }
}
