package com.sigeye.core.analysis.presence

import com.sigeye.experiments.trainspotter.Bin
import com.sigeye.experiments.trainspotter.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrainEventsTest {

    private val binMs = 30_000L

    private fun bin(
        index: Int,
        newCount: Int,
        spike: Boolean,
        baseline: Double = 2.0,
        phase: Phase = Phase.ARMED,
    ) = Bin(
        startMs = index * binMs,
        newCount = newCount,
        activeUnique = newCount + 5,
        baseline = baseline,
        spike = spike,
        phase = phase,
    )

    @Test
    fun `consecutive spiking bins are one pass, not three`() {
        // The bug this fixes: a train takes longer than a bin, so one train arrived as
        // three or four separate events and the count meant nothing.
        val log = TrainLog()
        log.add(bin(0, 2, spike = false))
        log.add(bin(1, 14, spike = true))
        log.add(bin(2, 18, spike = true))
        log.add(bin(3, 11, spike = true))
        log.add(bin(4, 2, spike = false))
        log.add(bin(5, 2, spike = false))

        assertEquals(1, log.count)
        val pass = log.passes().first()
        assertEquals(3, pass.bins)
        assertEquals(18, pass.peakNew)
        assertEquals(43, pass.totalNew)
    }

    @Test
    fun `a brief dip in the middle does not split a long pass`() {
        // A gap between carriages, or a stretch where nobody's phone spoke.
        val log = TrainLog(gapTolerance = 1)
        log.add(bin(0, 12, spike = true))
        log.add(bin(1, 3, spike = false))
        log.add(bin(2, 15, spike = true))
        log.add(bin(3, 2, spike = false))
        log.add(bin(4, 2, spike = false))

        assertEquals(1, log.count)
        assertEquals(2, log.passes().first().bins)
    }

    @Test
    fun `a real gap does split two passes`() {
        val log = TrainLog(gapTolerance = 1)
        log.add(bin(0, 12, spike = true))
        repeat(4) { log.add(bin(1 + it, 2, spike = false)) }
        log.add(bin(5, 15, spike = true))
        log.add(bin(6, 2, spike = false))
        log.add(bin(7, 2, spike = false))

        assertEquals(2, log.count)
    }

    @Test
    fun `enrollment bins are ignored entirely`() {
        val log = TrainLog()
        log.add(bin(0, 40, spike = true, phase = Phase.ENROLL))
        log.add(bin(1, 40, spike = true, phase = Phase.ENROLL))
        assertEquals(0, log.count)
        assertTrue(!log.inProgress)
    }

    @Test
    fun `a pass still happening is open rather than counted`() {
        val log = TrainLog()
        log.add(bin(0, 12, spike = true))
        assertTrue(log.inProgress)
        assertEquals(0, log.count)

        log.flush(binMs * 2)
        assertEquals(1, log.count)
        assertTrue(!log.inProgress)
    }

    @Test
    fun `strength is the peak against what the place normally does`() {
        val log = TrainLog()
        log.add(bin(0, 20, spike = true, baseline = 4.0))
        log.flush(binMs)
        assertEquals(5.0, log.passes().first().strength, 0.001)
    }

    @Test
    fun `a baseline of zero does not divide by zero`() {
        val log = TrainLog()
        log.add(bin(0, 20, spike = true, baseline = 0.0))
        log.flush(binMs)
        assertEquals(0.0, log.passes().first().strength, 0.001)
    }

    @Test
    fun `the service interval is the gap between passes starting`() {
        val log = TrainLog(gapTolerance = 0)
        // Three passes, twenty bins apart - ten minutes at thirty seconds a bin.
        listOf(0, 20, 40).forEach { start ->
            log.add(bin(start, 15, spike = true))
            log.add(bin(start + 1, 2, spike = false))
        }
        log.flush(binMs * 60)

        assertEquals(3, log.count)
        assertEquals(10 * 60_000L, log.meanIntervalMs)
        assertTrue(log.summary().contains("10 minutes apart"))
    }

    @Test
    fun `passes are bucketed by hour, so a timetable can show through`() {
        val log = TrainLog(gapTolerance = 0)
        log.add(bin(0, 15, spike = true))
        log.flush(binMs)
        val hours = log.byHour()
        assertEquals(1, hours.values.sum())
        assertEquals(1, hours.size)
    }

    @Test
    fun `an empty log says so rather than reporting zeroes`() {
        val log = TrainLog()
        assertEquals(0, log.count)
        assertEquals(0L, log.meanDurationMs)
        assertEquals(0L, log.meanIntervalMs)
        assertTrue(log.summary().contains("Nothing has gone past"))
    }

    @Test
    fun `resetting clears the log`() {
        val log = TrainLog()
        log.add(bin(0, 15, spike = true))
        log.flush(binMs)
        log.reset()
        assertEquals(0, log.count)
        assertTrue(!log.inProgress)
    }
}
