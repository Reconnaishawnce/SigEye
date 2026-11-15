package com.sigeye.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pieces behind shake to report: the shake itself, the freeze log, frames and redaction. */
class DiagnosisTest {

    private val g = ShakeDetector.EARTH

    /** Readings at 50 Hz with a spike of [peakG] every [everyMs]. */
    private fun ShakeDetector.feed(peakG: Float, everyMs: Long, forMs: Long, fromMs: Long = 0L): Int {
        var shakes = 0
        var t = fromMs
        while (t < fromMs + forMs) {
            val spike = (t - fromMs) % everyMs == 0L
            if (reading(0f, 0f, if (spike) peakG * g else g, t)) shakes++
            t += 20
        }
        return shakes
    }

    // ------------------------------------------------------------------ shaking

    @Test
    fun `a firm shake is detected once`() {
        assertEquals(1, ShakeDetector().feed(peakG = 3.5f, everyMs = 200, forMs = 900))
    }

    @Test
    fun `walking never counts as a shake`() {
        // Footsteps peak around 1.3 g, twice a second, for a whole minute.
        assertEquals(0, ShakeDetector().feed(peakG = 1.4f, everyMs = 500, forMs = 60_000))
    }

    @Test
    fun `a single bump, like setting the phone down hard, is not a shake`() {
        assertEquals(0, ShakeDetector().feed(peakG = 4f, everyMs = 5_000, forMs = 4_000))
    }

    @Test
    fun `peaks too far apart do not add up`() {
        // Three hard knocks over three seconds are not one shake.
        assertEquals(0, ShakeDetector().feed(peakG = 3.5f, everyMs = 1_000, forMs = 3_000))
    }

    @Test
    fun `shaking for a long time opens one report, not several`() {
        // Three seconds of continuous shaking stays inside the cooldown after the first.
        assertEquals(1, ShakeDetector().feed(peakG = 3.5f, everyMs = 200, forMs = 2_900))
    }

    @Test
    fun `one jolt ringing across several samples counts once`() {
        val detector = ShakeDetector()
        var shakes = 0
        listOf(0L, 20L, 40L, 60L, 80L).forEach { if (detector.reading(0f, 0f, 4f * g, it)) shakes++ }
        assertEquals(0, shakes)
    }

    // ------------------------------------------------------------------ freezes

    @Test
    fun `a freeze over two seconds counts as frozen, a short one does not`() {
        assertTrue(Stall(0L, 2_400L, "radar", emptyList()).frozen)
        assertFalse(Stall(0L, 900L, "radar", emptyList()).frozen)
    }

    @Test
    fun `the freeze log keeps the latest few and counts all of them`() {
        val log = StallLog(keep = 3)
        repeat(5) { log.record(Stall(it * 1_000L, 1_000L, "home", emptyList())) }

        assertEquals(3, log.recent().size)
        assertEquals(4_000L, log.recent().last().atMs)
        assertEquals(5, log.count())
        assertEquals(5_000L, log.totalMs())
    }

    @Test
    fun `only a recent freeze is mentioned in the dialog`() {
        val log = StallLog()
        log.record(Stall(10_000L, 1_000L, "follow", emptyList()))

        assertEquals("follow", log.latestWithin(60_000L, 120_000L)?.screen)
        assertNull(log.latestWithin(500_000L, 120_000L))
    }

    // ------------------------------------------------------------------ frames

    @Test
    fun `frames are sorted into fine, slow and frozen per screen`() {
        val stats = FrameStats()
        repeat(90) { stats.frame("radar", 12L) }
        repeat(9) { stats.frame("radar", 80L) }
        stats.frame("radar", 1_500L)
        stats.frame("home", 10L)

        val radar = stats.snapshot().getValue("radar")
        assertEquals(100, radar.frames)
        assertEquals(10, radar.slow)
        assertEquals(1, radar.frozen)
        assertEquals(1_500L, radar.worstMs)
        assertEquals(10.0, radar.slowPercent, 0.001)
        assertEquals(1, stats.snapshot().getValue("home").frames)
    }

    // ------------------------------------------------------------------ privacy

    @Test
    fun `addresses are removed from anything that leaves the phone`() {
        val line = "Focused on AA:BB:CC:DD:EE:FF and 11-22-33-44-55-66, rssi -60"

        val clean = Redact.text(line)
        assertFalse(clean.contains("AA:BB"))
        assertFalse(clean.contains("11-22"))
        assertTrue(clean.contains("rssi -60"))
        assertEquals(2, Regex("\\[address]").findAll(clean).count())
    }

    @Test
    fun `ordinary text and times survive redaction`() {
        val line = "12:34:56.789 I/SigEye: started at 10:15"
        assertEquals(line, Redact.text(line))
    }

    @Test
    fun `a screen route loses the address it carries`() {
        assertEquals("locate", Redact.screen("locate:AA:BB:CC:DD:EE:FF"))
        assertEquals("radar", Redact.screen("radar"))
        assertEquals("home", Redact.screen(null))
    }
}
