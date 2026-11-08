package com.sigeye.core.analysis.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Forensics recording Wi-Fi networks beside Bluetooth devices.
 *
 * One reading per scan, every fifteen seconds, kept apart from the Bluetooth side so the
 * two can be reviewed separately and nothing is mistaken for the other.
 */
class ForensicsWifiTest {

    private val t0 = 1_700_000_000_000L

    /** A network heard once per 15-second scan from [fromMs] until [toMs]. */
    private fun ForensicRecorder.network(bssid: String, ssid: String?, fromMs: Long, toMs: Long, rssi: Int = -60) {
        var at = fromMs
        while (at <= toMs) {
            observeWifi(bssid, rssi, at, ssid)
            at += 15_000L
        }
    }

    @Test
    fun `networks are counted apart from Bluetooth devices`() {
        val recorder = ForensicRecorder().also { it.start(t0) }
        recorder.observe("AA:BB:CC:DD:EE:01", -60, t0 + 1_000L)
        recorder.network("00:1A:2B:3C:4D:01", "Home", t0, t0 + 120_000L)
        recorder.network("00:1A:2B:3C:4D:02", "Cafe", t0, t0 + 120_000L)
        recorder.stop(t0 + 120_000L)

        assertEquals(1, recorder.deviceCount)
        assertEquals(2, recorder.networkCount)
    }

    @Test
    fun `a network track carries its name and is marked as Wi-Fi`() {
        val recorder = ForensicRecorder().also { it.start(t0) }
        recorder.network("00:1A:2B:3C:4D:01", "Corner Cafe", t0, t0 + 60_000L)
        recorder.stop(t0 + 60_000L)

        val track = recorder.tracks().single()
        assertTrue(track.wifi)
        assertEquals("00:1A:2B:3C:4D:01", track.address)
        assertEquals("Corner Cafe", track.label)
    }

    @Test
    fun `a hidden network is named as hidden rather than left blank`() {
        val recorder = ForensicRecorder().also { it.start(t0) }
        recorder.network("00:1A:2B:3C:4D:01", null, t0, t0 + 60_000L)

        assertEquals("Hidden network", recorder.tracks().single().label)
    }

    @Test
    fun `a router there the whole time is furniture, a hotspot that came and went is not`() {
        val recorder = ForensicRecorder().also { it.start(t0) }
        recorder.network("00:1A:2B:3C:4D:01", "Office", t0, t0 + 30 * 60_000L)
        recorder.network("DA:A1:19:00:00:01", "Pixel hotspot", t0 + 10 * 60_000L, t0 + 14 * 60_000L)
        recorder.stop(t0 + 30 * 60_000L)

        val byName = recorder.tracks().associateBy { it.label }
        assertEquals(Behavior.THROUGHOUT_STEADY, byName.getValue("Office").behavior)
        assertTrue(byName.getValue("Pixel hotspot").behavior != Behavior.THROUGHOUT_STEADY)
    }

    @Test
    fun `a Bluetooth device that stopped by in the middle is not furniture either`() {
        // The bug this run found: arriving late and leaving early with a flat signal fell
        // through every rule to here the whole time, unchanging, so Hide unchanged hid it.
        val recorder = ForensicRecorder().also { it.start(t0) }
        repeat(60) { recorder.observe("AA:BB:CC:DD:EE:01", -60, t0 + 10 * 60_000L + it * 2_000L) }
        recorder.observe("AA:BB:CC:DD:EE:02", -70, t0)
        recorder.observe("AA:BB:CC:DD:EE:02", -70, t0 + 30 * 60_000L)
        recorder.stop(t0 + 30 * 60_000L)

        val visitor = recorder.tracks().single { it.address == "AA:BB:CC:DD:EE:01" }
        assertEquals(Behavior.VISITED, visitor.behavior)
        assertTrue(recorder.review(ForensicFilter(hideFurniture = true)).any { it.address == visitor.address })
    }

    @Test
    fun `hiding unchanged leaves only what came and went`() {
        val recorder = ForensicRecorder().also { it.start(t0) }
        recorder.network("00:1A:2B:3C:4D:01", "Office", t0, t0 + 30 * 60_000L)
        recorder.network("DA:A1:19:00:00:01", "Pixel hotspot", t0 + 10 * 60_000L, t0 + 14 * 60_000L)
        recorder.stop(t0 + 30 * 60_000L)

        val shown = recorder.review(ForensicFilter(hideFurniture = true)).filter { it.wifi }
        assertEquals(listOf("Pixel hotspot"), shown.map { it.label })
    }

    @Test
    fun `the census can be taken for one radio at a time`() {
        val recorder = ForensicRecorder().also { it.start(t0) }
        repeat(20) { recorder.observe("AA:BB:CC:DD:EE:01", -60, t0 + it * 60_000L) }
        recorder.network("00:1A:2B:3C:4D:01", "Office", t0, t0 + 19 * 60_000L)
        recorder.stop(t0 + 19 * 60_000L)

        assertEquals(1, recorder.census(wifi = false).values.sum())
        assertEquals(1, recorder.census(wifi = true).values.sum())
    }

    @Test
    fun `a BSSID matching a Bluetooth address is still two different things`() {
        val recorder = ForensicRecorder().also { it.start(t0) }
        recorder.observe("00:1A:2B:3C:4D:01", -60, t0)
        recorder.observeWifi("00:1A:2B:3C:4D:01", -60, t0, "Home")

        assertEquals(2, recorder.tracks().size)
    }

    @Test
    fun `a surveillance note travels with the network`() {
        val recorder = ForensicRecorder().also { it.start(t0) }
        recorder.observeWifi("9C:2F:9D:6F:45:A7", -55, t0, "Flock-6F45A7", note = "Flock Safety camera")

        assertEquals("Flock Safety camera", recorder.tracks().single().detail.surveillanceNote)
    }

    @Test
    fun `nothing is recorded before the recording starts`() {
        val recorder = ForensicRecorder()
        recorder.observeWifi("00:1A:2B:3C:4D:01", -60, t0, "Home")

        assertEquals(0, recorder.networkCount)
    }

    @Test
    fun `the export says which radio each reading came from`() {
        val recorder = ForensicRecorder().also { it.start(t0) }
        recorder.observe("AA:BB:CC:DD:EE:01", -60, t0)
        recorder.observeWifi("00:1A:2B:3C:4D:01", -60, t0, "Home")
        recorder.stop(t0 + 1_000L)

        val csv = recorder.csv()
        assertTrue(csv.contains("networks=1"))
        assertTrue(csv.contains(",bluetooth\n"))
        assertTrue(csv.contains(",wifi\n"))
        assertFalse(csv.contains("wifi:"))
    }
}
