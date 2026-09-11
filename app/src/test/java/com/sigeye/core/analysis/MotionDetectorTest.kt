package com.sigeye.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {

    private val config = MotionConfig(
        calibrationSeconds = 10,
        windowMs = 2_500L,
        sensitivity = 3.5,
        minAgreement = 2,
        ticksToFire = 2,
        minPacketsPerSecond = 1.5,
        maxBaselineSigma = 5.0,
        sigmaFloor = 1.2,
    )

    private fun detector() = MotionDetector(config)

    /** Feeds [devices] steady traffic for [seconds], five packets a second each. */
    private fun MotionDetector.feedSteady(
        devices: List<String>,
        seconds: Int,
        startMs: Long,
        rssi: Int = -70,
        jitter: Int = 1,
    ): Long {
        var now = startMs
        repeat(seconds * 5) { step ->
            devices.forEach { address ->
                observe(address, rssi + (step % (jitter * 2 + 1)) - jitter, now)
            }
            now += 200L
        }
        return now
    }

    /** Runs [ticks] detector ticks, returning the last reading. */
    private fun MotionDetector.run(
        devices: List<String>,
        ticks: Int,
        startMs: Long,
        rssi: Int = -70,
        jitter: Int = 1,
    ): Pair<MotionReading, Long> {
        var now = startMs
        var reading = tick(now)
        repeat(ticks) {
            now = feedSteady(devices, 1, now, rssi, jitter)
            reading = tick(now)
        }
        return reading to now
    }

    private val three = listOf("A", "B", "C")

    // ----------------------------------------------------------- calibration

    @Test
    fun `calibration reports progress and does not detect`() {
        val d = detector()
        d.startCalibration(0L)
        val now = d.feedSteady(three, 3, 0L)
        val reading = d.tick(now)
        assertEquals(MotionState.CALIBRATING, reading.state)
        assertTrue(reading.calibrationProgress > 0f)
        assertTrue(reading.calibrationProgress < 1f)
    }

    @Test
    fun `calibration keeps chatty steady devices as references`() {
        val d = detector()
        d.startCalibration(0L)
        val now = d.feedSteady(three, 11, 0L)
        val reading = d.tick(now)
        assertEquals(3, reading.referenceCount)
        assertEquals(MotionState.QUIET, reading.state)
    }

    @Test
    fun `a device that is itself wandering is rejected as a reference`() {
        val d = detector()
        d.startCalibration(0L)
        var now = 0L
        repeat(55) { step ->
            d.observe("steady", -70 + (step % 3) - 1, now)
            // A phone in a pocket: swinging over 30 dB.
            d.observe("moving", -50 - (step * 7 % 40), now)
            now += 200L
        }
        val reading = d.tick(now)
        assertEquals(1, reading.referenceCount)
        assertEquals("steady", reading.references.first().address)
    }

    @Test
    fun `a device too quiet to carry a link is rejected`() {
        val d = detector()
        d.startCalibration(0L)
        var now = 0L
        repeat(55) { step ->
            d.observe("chatty", -70, now)
            // One packet every 2 seconds, well under the floor.
            if (step % 10 == 0) d.observe("sleepy", -70, now)
            now += 200L
        }
        assertEquals(1, d.tick(now).referenceCount)
    }

    // -------------------------------------------------------------- quietness

    @Test
    fun `a still room stays still`() {
        val d = detector()
        d.startCalibration(0L)
        var now = d.feedSteady(three, 11, 0L)
        val (reading, _) = d.run(three, 10, now)
        assertEquals(MotionState.QUIET, reading.state)
        assertEquals(0, reading.disturbed)
    }

    // ----------------------------------------------------------------- motion

    @Test
    fun `a level shift across several links is motion`() {
        val d = detector()
        d.startCalibration(0L)
        var now = d.feedSteady(three, 11, 0L)
        now = d.run(three, 4, now).second

        // Someone stands in the path: every link drops hard.
        repeat(4) {
            now = d.feedSteady(three, 1, now, rssi = -85)
        }
        assertEquals(MotionState.MOTION, d.tick(now).state)
    }

    @Test
    fun `a jitter rise with no level change is motion`() {
        val d = detector()
        d.startCalibration(0L)
        var now = d.feedSteady(three, 11, 0L)
        now = d.run(three, 4, now).second

        // Someone walks through: same average, far less stable. This is the case a
        // level-only detector misses entirely.
        repeat(5) {
            now = d.feedSteady(three, 1, now, rssi = -70, jitter = 9)
        }
        val reading = d.tick(now)
        assertNotEquals(MotionState.QUIET, reading.state)
        assertTrue("score was ${reading.score}", reading.score >= config.sensitivity)
    }

    @Test
    fun `one device going haywire alone does not fire`() {
        val d = detector()
        d.startCalibration(0L)
        var now = d.feedSteady(three, 11, 0L)
        now = d.run(three, 4, now).second

        // A single link collapses - a re-pair, a battery sag, a glitch. The other two
        // are calm, so this must not be called motion.
        repeat(6) { step ->
            repeat(5) {
                d.observe("A", -110, now)
                d.observe("B", -70 + (step % 3) - 1, now)
                d.observe("C", -70 + (step % 3) - 1, now)
                now += 200L
            }
        }
        assertNotEquals(MotionState.MOTION, d.tick(now).state)
    }

    @Test
    fun `a single glitchy tick does not fire on its own`() {
        val d = MotionDetector(config.copy(ticksToFire = 3))
        d.startCalibration(0L)
        var now = d.feedSteady(three, 11, 0L)
        now = d.run(three, 4, now).second

        now = d.feedSteady(three, 1, now, rssi = -88)
        assertNotEquals(MotionState.MOTION, d.tick(now).state)
    }

    @Test
    fun `events are recorded with their peak and then closed`() {
        val d = detector()
        d.startCalibration(0L)
        var now = d.feedSteady(three, 11, 0L)
        now = d.run(three, 4, now).second

        repeat(4) { now = d.feedSteady(three, 1, now, rssi = -88) }
        d.tick(now)
        assertEquals(MotionState.MOTION, d.tick(now).state)

        // Room settles.
        repeat(8) { now = d.feedSteady(three, 1, now, rssi = -70) }
        d.tick(now)

        val log = d.eventLog()
        assertTrue("expected an event", log.isNotEmpty())
        assertTrue(log.first().peakScore >= config.sensitivity)
        assertTrue(log.first().peakDisturbed >= 2)
    }

    // ------------------------------------------------------------ robustness

    @Test
    fun `the baseline does not drift toward an ongoing disturbance`() {
        val d = detector()
        d.startCalibration(0L)
        var now = d.feedSteady(three, 11, 0L)
        val original = d.tick(now).references.first { it.address == "A" }.baselineMean

        // Someone stands there for a long time. If the baseline adapted, the detector
        // would decide this was the new normal and stop reporting them.
        repeat(30) { now = d.feedSteady(three, 1, now, rssi = -88) }
        val reading = d.tick(now)
        val drifted = reading.references.first { it.address == "A" }.baselineMean

        assertEquals(MotionState.MOTION, reading.state)
        assertTrue(
            "baseline moved from $original to $drifted",
            kotlin.math.abs(drifted - original) < 4.0,
        )
    }

    @Test
    fun `a reference that stops advertising drops out rather than reading as motion`() {
        val d = detector()
        d.startCalibration(0L)
        var now = d.feedSteady(three, 11, 0L)
        now = d.run(three, 3, now).second

        // A and B keep talking, C goes silent entirely.
        repeat(12) { now = d.feedSteady(listOf("A", "B"), 1, now) }
        val reading = d.tick(now)
        assertEquals(2, reading.liveReferences)
        assertEquals(3, reading.referenceCount)
        assertEquals(MotionState.QUIET, reading.state)
    }

    @Test
    fun `with only one live reference it still works rather than refusing`() {
        val d = detector()
        d.startCalibration(0L)
        var now = d.feedSteady(listOf("solo"), 11, 0L)
        now = d.run(listOf("solo"), 4, now).second

        repeat(5) { now = d.feedSteady(listOf("solo"), 1, now, rssi = -90) }
        // Agreement cannot exceed the number of live links, so one link is enough when
        // one link is all there is.
        assertEquals(MotionState.MOTION, d.tick(now).state)
    }

    @Test
    fun `sensitivity changes what counts as disturbed`() {
        val strict = MotionDetector(config.copy(sensitivity = 12.0))
        strict.startCalibration(0L)
        var now = strict.feedSteady(three, 11, 0L)
        now = strict.run(three, 3, now).second
        repeat(4) { now = strict.feedSteady(three, 1, now, rssi = -78) }
        assertNotEquals(MotionState.MOTION, strict.tick(now).state)
    }

    @Test
    fun `reset clears references and events`() {
        val d = detector()
        d.startCalibration(0L)
        val now = d.feedSteady(three, 11, 0L)
        d.tick(now)
        d.reset()
        val reading = d.tick(now)
        assertEquals(0, reading.referenceCount)
        assertEquals(MotionState.CALIBRATING, reading.state)
        assertTrue(d.eventLog().isEmpty())
    }
}
