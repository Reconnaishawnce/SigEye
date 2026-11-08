package com.sigeye.core.analysis.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Place Profiler fed Wi-Fi scans instead of Bluetooth packets.
 *
 * Same profile, different input: a scan every fifteen seconds rather than a packet every
 * fraction of one. These check that routers come out as the outline of a place and that
 * hotspots come out as the traffic, at the slow rate Wi-Fi actually arrives.
 */
class PlaceWifiTest {

    private val t0 = 1_700_000_000_000L
    private val slice = 10 * 60_000L

    /** A scan every 15 seconds for [hours], with the given access points visible. */
    private fun scans(
        profile: PlaceProfile,
        hours: Int,
        visible: (atMs: Long) -> List<Pair<String, Int>>,
    ) {
        var at = t0
        while (at < t0 + hours * 3_600_000L) {
            visible(at).forEach { (bssid, rssi) -> profile.observe(bssid, rssi, at, "net-$bssid") }
            at += 15_000L
        }
    }

    @Test
    fun `the router that never leaves is a fixture`() {
        val profile = PlaceProfile(bucketMs = slice).also { it.start(t0) }
        scans(profile, 3) { listOf("AA:AA:AA:AA:AA:01" to -55) }

        val report = profile.report(t0 + 3 * 3_600_000L - 1)

        assertEquals(1, report.fixtures.size)
        assertEquals("net-AA:AA:AA:AA:AA:01", report.fixtures.first().label)
    }

    @Test
    fun `a hotspot that showed up for ten minutes is passing, not part of the place`() {
        val profile = PlaceProfile(bucketMs = slice).also { it.start(t0) }
        val bus = t0 + 60 * 60_000L
        scans(profile, 3) { at ->
            buildList {
                add("AA:AA:AA:AA:AA:01" to -55)
                if (at in bus until bus + 10 * 60_000L) add("BB:BB:BB:BB:BB:02" to -70)
            }
        }

        val report = profile.report(t0 + 3 * 3_600_000L - 1)

        assertEquals(2, report.totalDevices)
        assertEquals(1, report.fixtures.size)
        assertTrue(report.residents.none { it.address == "BB:BB:BB:BB:BB:02" && it.fixture })
    }

    @Test
    fun `a router whose signal steps is reported as something having moved`() {
        // A wall of shelving pushed in front of it, or the router carried to another room.
        val profile = PlaceProfile(bucketMs = slice).also { it.start(t0) }
        val moved = t0 + 90 * 60_000L
        scans(profile, 3) { at ->
            listOf("AA:AA:AA:AA:AA:01" to if (at < moved) -50 else -72)
        }

        val report = profile.report(t0 + 3 * 3_600_000L - 1)

        assertEquals(1, report.movers.size)
        assertTrue(report.movers.first().shiftDb < -15)
    }

    @Test
    fun `fifteen seconds between scans is still enough to fill every slice`() {
        val profile = PlaceProfile(bucketMs = slice).also { it.start(t0) }
        scans(profile, 2) { listOf("AA:AA:AA:AA:AA:01" to -60) }

        val report = profile.report(t0 + 2 * 3_600_000L - 1)

        assertTrue(report.buckets.all { it.packets > 0 })
    }
}
