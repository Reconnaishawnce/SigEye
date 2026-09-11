package com.sigeye.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepDiagnosticsTest {

    @Test
    fun `counts packets, source packets and recordings apart`() {
        val diagnostics = SweepDiagnostics()
        diagnostics.reset(0L)
        repeat(10) { diagnostics.packet(it * 100L) }
        repeat(4) { diagnostics.fromSource(it * 100L) }
        repeat(3) { diagnostics.recorded(it * 40f) }
        diagnostics.dropped(DropReason.NO_COMPASS)

        val counters = diagnostics.counters(1_000L)
        assertEquals(10, counters.packetsSeen)
        assertEquals(4, counters.packetsFromSource)
        assertEquals(3, counters.recorded)
        assertEquals(1, counters.droppedNoCompass)
    }

    @Test
    fun `rates are per second of elapsed time`() {
        val diagnostics = SweepDiagnostics()
        diagnostics.reset(0L)
        repeat(20) { diagnostics.fromSource(it * 100L) }
        repeat(100) { diagnostics.heading() }
        val counters = diagnostics.counters(10_000L)
        assertEquals(2.0, counters.sourceRate, 0.001)
        assertEquals(10.0, counters.headingRate, 0.001)
    }

    // ------------------------------------------------------------- the verdict

    @Test
    fun `silence points at the radio`() {
        val diagnostics = SweepDiagnostics()
        diagnostics.reset(0L)
        assertTrue(diagnostics.counters(5_000L).verdict()!!.contains("No packets at all"))
    }

    @Test
    fun `packets but none from the source points at the source`() {
        val diagnostics = SweepDiagnostics()
        diagnostics.reset(0L)
        repeat(50) { diagnostics.packet(it * 100L) }
        val verdict = diagnostics.counters(5_000L).verdict()
        assertNotNull(verdict)
        assertTrue(verdict!!.contains("none from the device you picked"))
    }

    @Test
    fun `source packets but no compass points at the compass`() {
        val diagnostics = SweepDiagnostics()
        diagnostics.reset(0L)
        repeat(50) { diagnostics.packet(it * 100L) }
        repeat(50) { diagnostics.fromSource(it * 100L) }
        assertTrue(
            diagnostics.counters(5_000L).verdict()!!.contains("No compass readings"),
        )
    }

    @Test
    fun `a phone that never turned is called out as such`() {
        // This is the failure that looks exactly like a bug from the outside: everything
        // is arriving and being recorded, into two sectors.
        val diagnostics = SweepDiagnostics()
        diagnostics.reset(0L)
        repeat(50) {
            diagnostics.packet(it * 100L)
            diagnostics.fromSource(it * 100L)
            diagnostics.heading()
            diagnostics.recorded(10f)
        }
        assertTrue(
            diagnostics.counters(5_000L).verdict()!!.contains("compass barely moved"),
        )
    }

    @Test
    fun `a slow source is named as the problem rather than left implied`() {
        val diagnostics = SweepDiagnostics()
        diagnostics.reset(0L)
        repeat(10) {
            diagnostics.packet(it * 1000L)
            diagnostics.fromSource(it * 1000L)
            diagnostics.heading()
            diagnostics.recorded(it * 36f)
        }
        // Ten packets across twenty seconds is half a packet a second.
        val verdict = diagnostics.counters(20_000L).verdict()
        assertNotNull(verdict)
        assertTrue(verdict!!.contains("packets a second"))
    }

    @Test
    fun `a healthy sweep has nothing to say`() {
        val diagnostics = SweepDiagnostics()
        diagnostics.reset(0L)
        repeat(100) {
            diagnostics.packet(it * 100L)
            diagnostics.fromSource(it * 100L)
            diagnostics.heading()
            diagnostics.recorded(it * 3.6f)
        }
        assertNull(diagnostics.counters(10_000L).verdict())
    }

    @Test
    fun `resetting clears everything, including the turn check`() {
        val diagnostics = SweepDiagnostics()
        diagnostics.reset(0L)
        repeat(20) {
            diagnostics.packet(it * 100L)
            diagnostics.recorded(it * 18f)
        }
        diagnostics.reset(50_000L)
        val counters = diagnostics.counters(51_000L)
        assertEquals(0, counters.packetsSeen)
        assertEquals(0, counters.recorded)
        assertEquals(0, counters.distinctHeadings)
        assertEquals(1_000L, counters.elapsedMs)
    }
}
