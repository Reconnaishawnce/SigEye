package com.sigeye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two clocks in a program that can replay a recording.
 *
 * One is what the wall says. The other is where the recording has got to. Confusing them is
 * what limited replay to real time: packets used to be restamped with the wall clock as
 * they went out, so the only thing keeping their spacing honest was that the replay slept
 * between them. Play that back quickly and every interval in the app is wrong by the speed
 * factor, and an advertising interval is a fingerprint here.
 */
class ClockTest {

    private val wall = 1_700_000_000_000L
    private val captured = 1_600_000_000_000L

    private fun clock(speed: Double = 1.0, spanMs: Long = 600_000L) = ReplayClock(
        startedAtMs = wall,
        firstPacketMs = captured,
        lastPacketMs = captured + spanMs,
        speed = speed,
    )

    // ------------------------------------------------------------------ the mapping

    @Test
    fun `at the moment it starts, the capture is at its first packet`() {
        assertEquals(captured, clock().nowMs(wall))
    }

    @Test
    fun `at real time, a second of wall clock is a second of capture`() {
        assertEquals(captured + 30_000L, clock().nowMs(wall + 30_000L))
    }

    @Test
    fun `at speed, a second of wall clock is more of the capture`() {
        assertEquals(captured + 600_000L, clock(speed = 60.0).nowMs(wall + 10_000L))
    }

    @Test
    fun `the clock never runs backwards before the replay started`() {
        assertEquals(captured, clock().nowMs(wall - 5_000L))
    }

    // ------------------------------------------------------------------ what it buys

    @Test
    fun `the gap between two packets is the same at any speed`() {
        // The whole reason for the virtual clock. A three second gap stays three seconds
        // at sixty times, because the number travels with the packet rather than being
        // read off the wall when it arrives.
        val first = captured + 10_000L
        val second = captured + 13_000L

        listOf(1.0, 4.0, 60.0).forEach { speed ->
            val running = clock(speed = speed)
            val firstDue = running.dueAtMs(first)
            val secondDue = running.dueAtMs(second)

            // The wall clock gap compresses, which is the point of going faster.
            assertEquals((3_000 / speed).toLong(), secondDue - firstDue)
            // And the capture clock at each of those moments is three seconds apart.
            assertEquals(3_000L, running.nowMs(secondDue) - running.nowMs(firstDue))
        }
    }

    @Test
    fun `an hour at sixty times takes a minute`() {
        val hour = clock(speed = 60.0, spanMs = 3_600_000L)

        assertEquals(60_000L, hour.realDurationMs)
    }

    @Test
    fun `a speed of zero is not allowed to divide by itself`() {
        val stopped = clock(speed = 0.0)

        assertEquals(stopped.spanMs, stopped.realDurationMs)
        assertEquals(wall + 10_000L, stopped.dueAtMs(captured + 10_000L))
    }

    // ------------------------------------------------------------------ progress

    @Test
    fun `progress runs from nothing to all of it and stops there`() {
        val running = clock(spanMs = 100_000L)

        assertEquals(0f, running.progress(wall), 0.001f)
        assertEquals(0.5f, running.progress(wall + 50_000L), 0.001f)
        assertEquals(1f, running.progress(wall + 500_000L), 0.001f)
    }

    @Test
    fun `a capture with no span is finished the moment it starts`() {
        val instant = clock(spanMs = 0L)

        assertEquals(1f, instant.progress(wall), 0.001f)
        assertTrue(instant.finished(wall))
    }

    @Test
    fun `finished means the last packet has gone by`() {
        val running = clock(spanMs = 100_000L)

        assertFalse(running.finished(wall + 99_000L))
        assertTrue(running.finished(wall + 100_000L))
    }

    // ------------------------------------------------------------------ the global

    @Test
    fun `with nothing replaying the clock is the wall clock`() {
        Clock.useReplay(null)

        assertFalse(Clock.replaying)
        assertTrue(Math.abs(Clock.nowMs() - System.currentTimeMillis()) < 1_000L)
    }

    @Test
    fun `while replaying the clock reports the capture, not today`() {
        try {
            Clock.useReplay(
                ReplayClock(
                    startedAtMs = System.currentTimeMillis(),
                    firstPacketMs = captured,
                    lastPacketMs = captured + 60_000L,
                ),
            )

            assertTrue(Clock.replaying)
            assertTrue("read ${Clock.nowMs()}", Clock.nowMs() in captured..(captured + 5_000L))
        } finally {
            Clock.useReplay(null)
        }
    }

    // ------------------------------------------------------------------ staying migrated

    /**
     * Guards the migration against quietly rotting.
     *
     * Adding `System.currentTimeMillis()` back into one of these files is a one-word change
     * that compiles, passes every other test, and silently makes that screen wrong during a
     * replay - it would read the wall clock, compare it to a packet from last Tuesday, and
     * conclude everything is hours stale. Nothing else would catch it.
     */
    @Test
    fun `screens that read the packet stream use the replay clock`() {
        val offenders = PACKET_TIME_FILES.filter { rel ->
            File(SOURCE, rel).readText().contains("System.currentTimeMillis()")
        }

        assertTrue(
            "These read the advertisement stream, so their idea of now has to follow a " +
                "replay rather than the wall: " + offenders.joinToString(", ") +
                ". Use Clock.nowMs() instead.",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the scan hub keeps its own wall clock, on purpose`() {
        // The counter-case, asserted so nobody "finishes the migration" by changing it. The
        // watchdog restarts a starved radio after so many real seconds, and the duplicate
        // filter fills up in real time whatever a recording is doing. A replay must not be
        // able to stop the radio being looked after.
        val hub = File(SOURCE, "core/ble/BleScanHub.kt").readText()

        assertTrue(hub.contains("System.currentTimeMillis()"))
    }

    companion object {
        private val SOURCE = File("src/main/java/com/sigeye")

        /** Everything whose "now" is about the packets rather than about today. */
        private val PACKET_TIME_FILES = listOf(
            "experiments/follow/FollowScreen.kt",
            "experiments/rotation/RotationScreen.kt",
            "experiments/rotationlab/RotationLabScreen.kt",
            "experiments/trainspotter/TrainSpotterScreen.kt",
            "experiments/trainspotter/TrainSpotterEngine.kt",
            "experiments/population/PopulationScreen.kt",
            "experiments/place/PlaceScreen.kt",
            "experiments/discovery/DiscoveryScreen.kt",
            "experiments/convoy/ConvoyScreen.kt",
            "experiments/forensics/ForensicsScreen.kt",
            "experiments/vulnerability/VulnerabilityScreen.kt",
            "experiments/inspector/InspectorScreen.kt",
            "experiments/locate/LocateScreen.kt",
            "experiments/radar/RadarScreen.kt",
            "experiments/speed/SpeedScreen.kt",
            "experiments/beacons/BeaconScreen.kt",
            "ui/radar/RadarScene.kt",
            "core/ScanService.kt",
            "core/Recordings.kt",
            "core/FollowRunner.kt",
            "core/analysis/identity/Follow.kt",
        )
    }
}
