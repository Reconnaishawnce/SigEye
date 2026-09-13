package com.sigeye.core.analysis.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ForensicsTest {

    private val span = 180_000L

    private fun recorder() = ForensicRecorder()

    /** A device present for a window, at a level that can vary over it. */
    private fun ForensicRecorder.heard(
        address: String,
        fromMs: Long,
        toMs: Long,
        stepMs: Long = 1_000L,
        isRandom: Boolean = false,
        rssi: (Long) -> Int = { -60 },
    ) {
        var at = fromMs
        while (at <= toMs) {
            observe(address, rssi(at), at, isRandom = isRandom)
            at += stepMs
        }
    }

    /** The shape of something driving past: rises to a peak in the middle, falls away. */
    private fun pass(centerMs: Long, widthMs: Long, peak: Int = -45, floor: Int = -85) =
        { at: Long ->
            val offset = abs(at - centerMs).toDouble() / widthMs
            (peak - (peak - floor) * offset.coerceAtMost(1.0)).toInt()
        }

    // ------------------------------------------------------------ behaviors

    @Test
    fun `something that rose, peaked and left is recognized as a pass`() {
        val recorder = recorder()
        recorder.start(0L)
        recorder.heard("CAR", 60_000L, 120_000L, rssi = pass(90_000L, 30_000L))
        recorder.heard("WALL", 0L, span)
        recorder.stop(span)

        val car = recorder.tracks().first { it.address == "CAR" }
        assertTrue("range was ${car.rangeDb}", car.looksLikePass)
        assertEquals(Behavior.PASSED, car.behavior)
    }

    @Test
    fun `something that walked up and stayed is not a pass`() {
        // Identical rise, no fall. In a list of signal strengths these look the same,
        // which is exactly why the peak has to be in the middle.
        val recorder = recorder()
        recorder.start(0L)
        recorder.heard("ARRIVER", 60_000L, span) { at ->
            (-85 + (at - 60_000L) / 1_000L).toInt().coerceAtMost(-45)
        }
        recorder.stop(span)

        val track = recorder.tracks().first()
        assertTrue(!track.looksLikePass)
        assertEquals(Behavior.ARRIVED, track.behavior)
    }

    @Test
    fun `something present throughout without changing is furniture`() {
        val recorder = recorder()
        recorder.start(0L)
        recorder.heard("FRIDGE", 0L, span)
        recorder.stop(span)
        assertEquals(Behavior.THROUGHOUT_STEADY, recorder.tracks().first().behavior)
    }

    @Test
    fun `something present throughout that moves a lot is kept apart from furniture`() {
        val recorder = recorder()
        recorder.start(0L)
        recorder.heard("POCKET", 0L, span) { at -> if ((at / 20_000L) % 2 == 0L) -40 else -80 }
        recorder.stop(span)
        assertEquals(Behavior.THROUGHOUT_VARYING, recorder.tracks().first().behavior)
    }

    @Test
    fun `something that was here and left is labeled as such`() {
        val recorder = recorder()
        recorder.start(0L)
        recorder.heard("LEAVER", 0L, 60_000L)
        recorder.stop(span)
        assertEquals(Behavior.LEFT, recorder.tracks().first().behavior)
    }

    @Test
    fun `too few readings says so rather than guessing a shape`() {
        val recorder = recorder()
        recorder.start(0L)
        recorder.observe("BLIP", -70, 10_000L)
        recorder.observe("BLIP", -68, 11_000L)
        recorder.stop(span)
        assertEquals(Behavior.GLIMPSED, recorder.tracks().first().behavior)
    }

    // --------------------------------------------------------------- filters

    @Test
    fun `hiding furniture leaves the things that did something`() {
        val recorder = recorder()
        recorder.start(0L)
        repeat(20) { index -> recorder.heard("FIXED$index", 0L, span) }
        recorder.heard("CAR", 60_000L, 120_000L, rssi = pass(90_000L, 30_000L))
        recorder.stop(span)

        assertEquals(21, recorder.deviceCount)
        val left = recorder.review(ForensicFilter(hideFurniture = true))
        assertEquals(listOf("CAR"), left.map { it.address })
    }

    @Test
    fun `hiding what you already knew leaves the strangers`() {
        val recorder = recorder()
        recorder.start(0L)
        recorder.heard("MY_EARBUDS", 0L, span) { at -> if (at % 40_000L < 20_000L) -40 else -70 }
        recorder.heard("STRANGER", 30_000L, 90_000L, rssi = pass(60_000L, 30_000L))
        recorder.stop(span)

        val left = recorder.review(
            filter = ForensicFilter(hideKnown = true),
            known = setOf("my_earbuds"),
        )
        assertEquals(listOf("STRANGER"), left.map { it.address })
    }

    @Test
    fun `passes only is the one that finds the vehicle`() {
        val recorder = recorder()
        recorder.start(0L)
        repeat(30) { index -> recorder.heard("NOISE$index", 0L, span) }
        recorder.heard("VAN", 40_000L, 100_000L, rssi = pass(70_000L, 30_000L))
        recorder.stop(span)

        val left = recorder.review(ForensicFilter(passesOnly = true))
        assertEquals(listOf("VAN"), left.map { it.address })
    }

    @Test
    fun `fixed only drops the randomized addresses`() {
        val recorder = recorder()
        recorder.start(0L)
        recorder.heard("PHONE", 0L, span, isRandom = true)
        recorder.heard("CAMERA", 0L, span, isRandom = false)
        recorder.stop(span)

        assertEquals(
            listOf("CAMERA"),
            recorder.review(ForensicFilter(fixedOnly = true)).map { it.address },
        )
    }

    @Test
    fun `filters combine, which is how two hundred becomes one`() {
        val recorder = recorder()
        recorder.start(0L)
        repeat(50) { index -> recorder.heard("FIXED$index", 0L, span) }
        recorder.heard("KNOWN_PASSER", 20_000L, 60_000L, rssi = pass(40_000L, 20_000L))
        recorder.heard("TARGET", 100_000L, 150_000L, rssi = pass(125_000L, 25_000L))
        recorder.stop(span)

        val left = recorder.review(
            filter = ForensicFilter(hideFurniture = true, hideKnown = true, passesOnly = true),
            known = setOf("KNOWN_PASSER"),
        )
        assertEquals(listOf("TARGET"), left.map { it.address })
    }

    // ----------------------------------------------------------------- sorts

    @Test
    fun `sorting by arrival puts the earliest first`() {
        val recorder = recorder()
        recorder.start(0L)
        recorder.heard("LATE", 120_000L, 150_000L)
        recorder.heard("EARLY", 10_000L, 40_000L)
        recorder.stop(span)

        assertEquals(
            listOf("EARLY", "LATE"),
            recorder.review(sort = ForensicSort.ARRIVAL).map { it.address },
        )
    }

    @Test
    fun `sorting by strength puts the closest first`() {
        val recorder = recorder()
        recorder.start(0L)
        recorder.heard("FAR", 0L, span) { -90 }
        recorder.heard("NEAR", 0L, span) { -40 }
        recorder.stop(span)

        assertEquals(
            listOf("NEAR", "FAR"),
            recorder.review(sort = ForensicSort.STRENGTH).map { it.address },
        )
    }

    @Test
    fun `sorting by variation puts the most changeable first`() {
        val recorder = recorder()
        recorder.start(0L)
        recorder.heard("STILL", 0L, span) { -60 }
        recorder.heard("SWINGING", 0L, span) { at -> if (at % 20_000L < 10_000L) -35 else -90 }
        recorder.stop(span)

        assertEquals(
            "SWINGING",
            recorder.review(sort = ForensicSort.VARIATION).first().address,
        )
    }

    // --------------------------------------------------------------- hygiene

    @Test
    fun `nothing is recorded before start or after stop`() {
        val recorder = recorder()
        recorder.observe("EARLY", -50, 0L)
        assertEquals(0, recorder.deviceCount)

        recorder.start(1_000L)
        recorder.observe("BEFORE", -50, 0L)
        assertEquals(0, recorder.deviceCount)

        recorder.observe("DURING", -50, 2_000L)
        recorder.stop(3_000L)
        recorder.observe("AFTER", -50, 4_000L)
        assertEquals(1, recorder.deviceCount)
    }

    @Test
    fun `a long recording is thinned rather than truncated`() {
        // Keeping the first N readings would give a detailed first minute and nothing
        // after it, which is the opposite of what a recording is for.
        val recorder = ForensicRecorder(maxPingsPerDevice = 100)
        recorder.start(0L)
        repeat(1_000) { index -> recorder.observe("CHATTY", -60, index * 100L) }
        recorder.stop(100_000L)

        val track = recorder.tracks().first()
        assertTrue("kept ${track.packets}", track.packets <= 100)
        // The last reading is still near the end of the recording, not the start.
        assertTrue("last at ${track.lastSeenMs}", track.lastSeenMs > 90_000L)
    }

    @Test
    fun `the census counts every device exactly once`() {
        val recorder = recorder()
        recorder.start(0L)
        repeat(5) { index -> recorder.heard("F$index", 0L, span) }
        recorder.heard("CAR", 60_000L, 120_000L, rssi = pass(90_000L, 30_000L))
        recorder.stop(span)

        assertEquals(recorder.deviceCount, recorder.census().values.sum())
        assertEquals(1, recorder.census()[Behavior.PASSED])
    }

    @Test
    fun `the export carries every reading with its behavior`() {
        val recorder = recorder()
        recorder.start(0L)
        recorder.heard("A", 0L, 10_000L)
        recorder.stop(10_000L)

        val csv = recorder.csv()
        assertTrue(csv.contains("elapsed_ms,address,label,rssi_dbm,behavior"))
        assertTrue(csv.contains("THROUGHOUT_STEADY"))
        // Eleven readings plus three header lines.
        assertEquals(14, csv.trim().lines().size)
    }

    @Test
    fun `a label containing a comma cannot break the export`() {
        val recorder = recorder()
        recorder.start(0L)
        recorder.observe("A", -60, 1_000L, label = "Bob, the printer")
        recorder.stop(2_000L)
        recorder.csv().trim().lines().drop(3).forEach {
            assertEquals("wrong column count in: $it", 5, it.split(',').size)
        }
    }
}
