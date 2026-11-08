package com.sigeye.core.analysis.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Traveling Companions with Wi-Fi.
 *
 * Routers stay at their building, so each one turns up in a single leg and drops out on its
 * own. A network heard in several unrelated places was carried: a phone hotspot, a car, a
 * portable router. These check that it is found, rated fairly against the legs that actually
 * scanned Wi-Fi, and kept apart from the Bluetooth side.
 */
class ConvoyWifiTest {

    private val minute = 60_000L
    private val t0 = 1_700_000_000_000L

    private val car = SeenNetwork("A4:2B:B0:11:22:33", "Toyota Wi-Fi", -48)
    private fun router(n: Int) = SeenNetwork("00:1A:2B:3C:4D:%02X".format(n), "Home-$n", -60)

    /** A leg of [minutes], scanned every 15 seconds, with the given networks in range. */
    private fun leg(
        tracker: ConvoyTracker,
        label: String,
        startMs: Long,
        minutes: Int,
        networks: List<SeenNetwork>,
    ) {
        tracker.startLeg(label, startMs)
        var at = startMs
        while (at < startMs + minutes * minute) {
            tracker.wifiScan(networks, at)
            at += 15_000L
        }
        tracker.closeLeg(startMs + minutes * minute)
    }

    private fun journey(): ConvoyTracker = ConvoyTracker().also {
        leg(it, "Home", t0, 5, listOf(car, router(1)))
        leg(it, "Cafe", t0 + 30 * minute, 5, listOf(car, router(2)))
        leg(it, "Office", t0 + 60 * minute, 5, listOf(car, router(3)))
    }

    @Test
    fun `a network heard in every leg is found and rated strongly`() {
        val report = journey().report()
        val found = report.candidates.single { it.wifi }

        assertEquals("A4:2B:B0:11:22:33", found.address)
        assertEquals("Toyota Wi-Fi", found.label)
        assertEquals(FollowConfidence.STRONG, found.confidence)
    }

    @Test
    fun `routers stay at their building and never become companions`() {
        val report = journey().report()

        assertTrue(report.candidates.none { it.label.startsWith("Home-") })
        assertEquals(4, report.networksSeen)
    }

    @Test
    fun `networks are counted apart from Bluetooth devices`() {
        val tracker = journey()
        val report = tracker.report()

        assertEquals(0, report.devicesSeen)
        assertEquals(3, report.wifiLegs)
    }

    @Test
    fun `a leg that never scanned Wi-Fi does not count against a network`() {
        // An old Bluetooth snapshot added as a leg has no Wi-Fi in it. Counting it would
        // mean no network could ever be in every leg.
        val tracker = journey()
        tracker.addLegFromSnapshot("Last week", t0 - 86_400_000L, emptyList())

        val found = tracker.report().candidates.single { it.wifi }

        assertEquals(3, found.totalLegs)
        assertTrue(found.everyLeg)
    }

    @Test
    fun `a hotspot on a private address is noticed but not rated strongly`() {
        // Some phone hotspots change their private address now and then, so the same
        // caution applies as to rotating Bluetooth.
        val hotspot = SeenNetwork("DA:A1:19:00:00:01", "Pixel hotspot", -45)
        val tracker = ConvoyTracker().also {
            leg(it, "A", t0, 5, listOf(hotspot))
            leg(it, "B", t0 + 30 * minute, 5, listOf(hotspot))
            leg(it, "C", t0 + 60 * minute, 5, listOf(hotspot))
        }

        val found = tracker.report().candidates.single()

        assertTrue(found.isRandom)
        assertEquals(FollowConfidence.WEAK, found.confidence)
    }

    @Test
    fun `the private address bit is read correctly`() {
        assertTrue(ConvoyTracker.isPrivateBssid("DA:A1:19:00:00:01"))
        assertTrue(ConvoyTracker.isPrivateBssid("02:00:00:00:00:00"))
        assertFalse(ConvoyTracker.isPrivateBssid("A4:2B:B0:11:22:33"))
        assertFalse(ConvoyTracker.isPrivateBssid("00:1A:2B:3C:4D:5E"))
    }

    @Test
    fun `a network caught in a single scan of a leg does not count as present`() {
        val passing = SeenNetwork("B8:27:EB:00:00:01", "Bus Wi-Fi", -80)
        val tracker = ConvoyTracker()
        tracker.startLeg("A", t0)
        tracker.wifiScan(listOf(passing), t0)
        tracker.wifiScan(emptyList(), t0 + 15_000L)
        tracker.closeLeg(t0 + 5 * minute)
        leg(tracker, "B", t0 + 30 * minute, 5, listOf(passing))

        assertTrue(tracker.report().candidates.isEmpty())
    }

    @Test
    fun `your own car is left out once you say it is yours`() {
        val report = journey().report(mine = setOf("a4:2b:b0:11:22:33"))

        assertTrue(report.candidates.none { it.wifi })
    }

    @Test
    fun `a journey with Wi-Fi survives being saved and read back`() {
        val original = journey()
        val restored = ConvoyTracker().also { it.restore(original.export()) }

        val found = restored.report().candidates.single { it.wifi }

        assertEquals("A4:2B:B0:11:22:33", found.address)
        assertEquals(3, restored.report().wifiLegs)
    }

    @Test
    fun `a BSSID that matches a Bluetooth address is still two different things`() {
        val tracker = ConvoyTracker()
        tracker.startLeg("A", t0)
        repeat(5) { tracker.observe("A4:2B:B0:11:22:33", -60, t0 + it * 1000L) }
        tracker.wifiScan(listOf(car), t0)
        tracker.wifiScan(listOf(car), t0 + 15_000L)
        tracker.closeLeg(t0 + 5 * minute)

        val report = tracker.report()
        assertEquals(1, report.devicesSeen)
        assertEquals(1, report.networksSeen)
    }

    @Test
    fun `the export says which radio each row came from`() {
        val csv = journey().csv()

        assertTrue(csv.contains("radio"))
        assertTrue(csv.contains(",wifi,A4:2B:B0:11:22:33,"))
    }
}
