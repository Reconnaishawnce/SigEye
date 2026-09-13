package com.sigeye.core.analysis.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.sqrt

class PassWatcherTest {

    /** The same synthetic pass geometry the estimator's own tests use. */
    private fun pass(
        watcher: PassWatcher,
        address: String,
        startMs: Long,
        speedMps: Double = 15.0,
        distanceMetres: Double = 12.0,
        seconds: Double = 8.0,
        hz: Double = 8.0,
    ): Long {
        val total = (seconds * hz).toInt()
        val stepMs = (1000.0 / hz).toLong()
        val passAt = total / 2
        var at = startMs
        repeat(total) { index ->
            val along = (index - passAt) / hz * speedMps
            val range = sqrt(distanceMetres * distanceMetres + along * along)
            val rssi = -40.0 - 10.0 * 2.0 * log10(range.coerceAtLeast(0.5))
            at = startMs + index * stepMs
            watcher.observe(address, rssi.toInt(), at)
        }
        return at
    }

    @Test
    fun `nothing is reported while a device is still in range`() {
        val watcher = PassWatcher(distanceMetres = 12.0)
        val last = pass(watcher, "AA", 0L)
        // Still chattering: a pass is not over until the device has gone.
        assertTrue(watcher.tick(last + 1_000).isEmpty())
        assertEquals(1, watcher.stats(last + 1_000).tracking)
    }

    @Test
    fun `a pass is reported once the device falls quiet`() {
        val watcher = PassWatcher(distanceMetres = 12.0)
        val last = pass(watcher, "AA", 0L, speedMps = 15.0, distanceMetres = 12.0)
        val found = watcher.tick(last + 6_000)
        assertEquals(1, found.size)
        assertEquals("AA", found.first().address)
        assertEquals(15.0, found.first().speedMetresPerSecond!!, 4.0)
    }

    @Test
    fun `several vehicles are timed independently and at once`() {
        val watcher = PassWatcher(distanceMetres = 12.0)
        pass(watcher, "AA", 0L, speedMps = 10.0)
        pass(watcher, "BB", 200L, speedMps = 25.0)
        val found = watcher.tick(20_000L)
        assertEquals(2, found.size)
        val byAddress = found.associateBy { it.address }
        assertTrue(
            byAddress.getValue("BB").speedMetresPerSecond!! >
                byAddress.getValue("AA").speedMetresPerSecond!!,
        )
    }

    @Test
    fun `a device that arrives and stays is never reported`() {
        val watcher = PassWatcher()
        repeat(40) { watcher.observe("AA", -50, it * 200L) }
        // It never goes quiet, so it is never finalised - and it is not a pass anyway.
        assertTrue(watcher.tick(8_000L - 1).isEmpty())
    }

    @Test
    fun `a brief appearance is counted as too few rather than guessed at`() {
        val watcher = PassWatcher()
        repeat(3) { watcher.observe("AA", -60 + it, it * 200L) }
        assertTrue(watcher.tick(10_000L).isEmpty())
        val stats = watcher.stats(10_000L)
        assertEquals(1, stats.finished)
        assertEquals(0, stats.passes)
        assertEquals(1, stats.rejectedTooFew)
    }

    @Test
    fun `walking out of range is not a pass`() {
        val watcher = PassWatcher()
        // Monotonic decline: no peak, so no closest approach to time from.
        repeat(30) { watcher.observe("AA", -45 - it, it * 200L) }
        assertTrue(watcher.tick(20_000L).isEmpty())
        assertEquals(1, watcher.stats(20_000L).rejectedNoShape)
    }

    @Test
    fun `the window is bounded so a resident beacon cannot grow forever`() {
        val watcher = PassWatcher(windowMs = 5_000L)
        repeat(500) { watcher.observe("AA", -50, it * 100L) }
        // 50 s of packets, 5 s of window: the track cannot hold more than the window.
        val live = watcher.live(50_000L)
        assertEquals(1, live.size)
        // Finalising it proves the track was pruned rather than accumulated.
        watcher.tick(60_000L)
        assertEquals(1, watcher.stats(60_000L).finished)
    }

    @Test
    fun `a track is only finalised once`() {
        val watcher = PassWatcher(distanceMetres = 12.0)
        val last = pass(watcher, "AA", 0L)
        assertEquals(1, watcher.tick(last + 6_000).size)
        assertTrue(watcher.tick(last + 12_000).isEmpty())
        assertEquals(1, watcher.stats(last + 12_000).passes)
    }

    @Test
    fun `distance and path loss are applied at finalisation, not at capture`() {
        // The same recorded pass, read at two assumed distances.
        fun speedAt(distance: Double): Double {
            val watcher = PassWatcher(distanceMetres = distance)
            val last = pass(watcher, "AA", 0L, speedMps = 15.0, distanceMetres = 12.0)
            return watcher.tick(last + 6_000).first().speedMetresPerSecond!!
        }
        assertEquals(speedAt(10.0) * 2, speedAt(20.0), 0.5)
    }

    // ------------------------------------------------------------- the verdict

    @Test
    fun `an empty sky says so`() {
        val watcher = PassWatcher()
        assertTrue(watcher.stats(0L).verdict()!!.contains("Nothing is advertising"))
    }

    @Test
    fun `devices present but none gone yet is its own message`() {
        val watcher = PassWatcher()
        repeat(20) { watcher.observe("AA", -50, it * 100L) }
        assertTrue(watcher.stats(2_000L).verdict()!!.contains("none has left yet"))
    }

    @Test
    fun `brief visitors are blamed on the traffic, not on the user`() {
        val watcher = PassWatcher()
        repeat(5) { index ->
            repeat(3) { watcher.observe("A$index", -60, index * 20_000L + it * 100L) }
            watcher.tick(index * 20_000L + 10_000L)
        }
        val verdict = watcher.stats(200_000L).verdict()
        assertNotNull(verdict)
        assertTrue(verdict!!.contains("too briefly to time"))
    }

    @Test
    fun `a watcher that found a pass has nothing to complain about`() {
        val watcher = PassWatcher(distanceMetres = 12.0)
        val last = pass(watcher, "AA", 0L)
        watcher.tick(last + 6_000)
        assertNull(watcher.stats(last + 6_000).verdict())
    }

    @Test
    fun `reset clears the tally as well as the tracks`() {
        val watcher = PassWatcher(distanceMetres = 12.0)
        val last = pass(watcher, "AA", 0L)
        watcher.tick(last + 6_000)
        watcher.reset()
        val stats = watcher.stats(last + 6_000)
        assertEquals(0, stats.passes)
        assertEquals(0, stats.finished)
        assertEquals(0, stats.tracking)
    }
}
