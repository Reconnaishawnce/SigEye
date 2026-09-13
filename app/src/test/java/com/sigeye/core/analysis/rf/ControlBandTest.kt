package com.sigeye.core.analysis.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlBandTest {

    private fun radio(bssid: String, ssid: String?, mhz: Int, rssi: Double) =
        Radio(bssid = bssid, ssid = ssid, frequencyMhz = mhz, rssi = rssi, scans = 5)

    private fun phases(baseline: List<Double>, test: List<Double>) =
        AbResult(PhaseStats.of(baseline), PhaseStats.of(test))

    private fun steady(value: Double, n: Int = 5) = List(n) { value }

    // ------------------------------------------------------------------ choosing

    @Test
    fun `one box with two radios is preferred over two separate boxes`() {
        // Same enclosure, same distance, same walls. Everything but the frequency cancels,
        // which is the whole reason this is a control rather than a second measurement.
        val radios = listOf(
            radio("AA:BB:CC:DD:EE:01", "home", 2437, -40.0),
            radio("AA:BB:CC:DD:EE:02", "home", 5180, -50.0),
            radio("11:22:33:44:55:66", "neighbour", 5200, -30.0),
        )

        val chosen = ControlBand.choose(radios)

        assertTrue(chosen.sameBox)
        assertEquals("AA:BB:CC:DD:EE:01", chosen.low?.bssid)
        assertEquals("AA:BB:CC:DD:EE:02", chosen.high?.bssid)
    }

    @Test
    fun `with no pair it falls back to the loudest in each band and says so`() {
        val radios = listOf(
            radio("AA:BB:CC:DD:EE:01", "one", 2437, -70.0),
            radio("11:22:33:44:55:66", "two", 2462, -40.0),
            radio("99:88:77:66:55:44", "three", 5180, -55.0),
        )

        val chosen = ControlBand.choose(radios)

        assertFalse(chosen.sameBox)
        assertEquals("11:22:33:44:55:66", chosen.low?.bssid)
        assertEquals("99:88:77:66:55:44", chosen.high?.bssid)
        assertTrue(chosen.describe().contains("weaker control"))
    }

    @Test
    fun `a band with nothing in it leaves the pair unusable rather than half formed`() {
        val chosen = ControlBand.choose(listOf(radio("AA:BB:CC:DD:EE:01", "home", 2437, -40.0)))

        assertNull(chosen.high)
        assertFalse(chosen.usable)
        assertEquals(ControlVerdict.UNAVAILABLE, ControlBand.judge(null, null))
    }

    // ------------------------------------------------------------------ reading a scan

    @Test
    fun `a radio missing from a scan is censored at the floor, not dropped`() {
        // Dropping it would score the phase on only the scans where it was audible, which
        // is the survivorship bug this control exists to avoid.
        val scan = mapOf("AA:BB:CC:DD:EE:01" to -44)

        assertEquals(-44.0, ControlBand.levelOf("AA:BB:CC:DD:EE:01", scan)!!, 1e-9)
        assertEquals(ControlBand.FLOOR_DBM, ControlBand.levelOf("99:99:99:99:99:99", scan)!!, 1e-9)
        assertNull(ControlBand.levelOf(null, scan))
    }

    // ------------------------------------------------------------------ judging

    @Test
    fun `2 point 4 falling alone is the finding`() {
        val verdict = ControlBand.judge(
            low = phases(steady(-45.0), steady(-62.0)),
            high = phases(steady(-52.0), steady(-53.0)),
        )

        assertEquals(ControlVerdict.ONLY_LOW_FELL, verdict)
    }

    @Test
    fun `both bands falling means the experiment failed, and it says which way to fix it`() {
        // An oven radiates at 2.45 GHz. If 5 GHz went down too, the phone moved.
        val low = phases(steady(-45.0), steady(-60.0))
        val high = phases(steady(-50.0), steady(-66.0))

        val verdict = ControlBand.judge(low, high)

        assertEquals(ControlVerdict.BOTH_FELL, verdict)
        val words = ControlBand.explain(verdict, low, high)
        assertTrue(words.contains("the phone moved"))
        assertTrue("magnitudes are written without a sign", words.contains("15.0 dB"))
    }

    @Test
    fun `two dB of wander is not a fall in either band`() {
        val verdict = ControlBand.judge(
            low = phases(steady(-45.0), steady(-47.0)),
            high = phases(steady(-50.0), steady(-51.0)),
        )

        assertEquals(ControlVerdict.NEITHER_FELL, verdict)
    }

    @Test
    fun `5 GHz falling alone is called out as not being an oven`() {
        val low = phases(steady(-45.0), steady(-45.0))
        val high = phases(steady(-80.0), steady(-95.0))

        val verdict = ControlBand.judge(low, high)

        assertEquals(ControlVerdict.ONLY_HIGH_FELL, verdict)
        assertTrue(ControlBand.explain(verdict, low, high).contains("Nothing about an oven"))
    }

    @Test
    fun `two Wi-Fi scans in a phase is not enough to judge on`() {
        // Scans arrive every eight seconds or so, so a thirty second phase barely has
        // three. Saying nothing is better than a verdict from two readings.
        val low = phases(steady(-45.0, n = 2), steady(-70.0, n = 2))
        val high = phases(steady(-50.0, n = 2), steady(-50.0, n = 2))

        assertFalse(ControlBand.enough(low))
        assertEquals(ControlVerdict.UNAVAILABLE, ControlBand.judge(low, high))
        assertTrue(
            ControlBand.explain(ControlVerdict.UNAVAILABLE, low, high)
                .contains("running each phase for a minute"),
        )
    }
}
