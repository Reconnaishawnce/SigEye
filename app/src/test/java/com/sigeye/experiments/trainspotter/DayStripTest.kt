package com.sigeye.experiments.trainspotter

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Folding days of eight second bins into one picture of what a street does. */
class DayStripTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    /** Midnight UTC on a known Wednesday. */
    private val midnight: Long = Calendar.getInstance(utc).apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 9, 0, 0, 0)
    }.timeInMillis

    private fun bin(
        atMs: Long,
        newCount: Int,
        label: String = "",
        phase: Phase = Phase.ARMED,
    ) = LoggedBin(atMs, newCount, 4.0, phase, label)

    @Test
    fun `a bucket keeps the biggest burst in it, not the average`() {
        // A train is thirty seconds of a hundred devices inside a quiet hour. Averaging
        // would draw the hour instead of the train, which is the opposite of the point.
        val start = midnight + 8 * 3_600_000L
        val strip = DayStrip.build(
            listOf(
                bin(start, 3),
                bin(start + 8_000L, 140),
                bin(start + 16_000L, 4),
                bin(start + 24_000L, 2),
            ),
            utc,
        )

        val column = DayStrip.columnOf(start, midnight)
        assertEquals(140, strip.days.single().buckets[column])
    }

    @Test
    fun `buckets nothing was recorded in stay empty rather than reading as quiet`() {
        // A gap in the recording and a quiet street are different facts. Drawing the first
        // as the second would invent hours of silence the app never listened through.
        val strip = DayStrip.build(listOf(bin(midnight + 3_600_000L, 12)), utc)
        val day = strip.days.single()

        assertEquals(1, day.recorded)
        assertNull(day.buckets[0])
        assertEquals(DayStrip.COLUMNS, day.buckets.size)
    }

    @Test
    fun `the whole strip is scaled together so days can be compared`() {
        // Scaling each day to its own maximum would make a dead Sunday look exactly as
        // busy as a Monday rush.
        val strip = DayStrip.build(
            listOf(
                bin(midnight + 3_600_000L, 20),
                bin(midnight + 86_400_000L + 3_600_000L, 200),
            ),
            utc,
        )

        assertEquals(2, strip.days.size)
        assertEquals(200, strip.ceiling)
    }

    @Test
    fun `enrolment and warm-up never reach the picture`() {
        // Enrolment counts are deliberately zero and warm-up had no trustworthy baseline.
        // Drawing either puts a dark band across every morning the app was restarted.
        val strip = DayStrip.build(
            listOf(
                bin(midnight + 3_600_000L, 0, phase = Phase.ENROLL),
                bin(midnight + 3_610_000L, 90, phase = Phase.WARMUP),
                bin(midnight + 3_620_000L, 15),
            ),
            utc,
        )

        assertEquals(15, strip.ceiling)
        assertEquals(1, strip.days.single().recorded)
    }

    @Test
    fun `a marked bin marks its bucket`() {
        val start = midnight + 7 * 3_600_000L
        val strip = DayStrip.build(listOf(bin(start, 60, label = "TRAIN")), utc)

        assertEquals(1, strip.marks)
        assertTrue(DayStrip.columnOf(start, midnight) in strip.days.single().marked)
    }

    @Test
    fun `days are ordered oldest first and capped`() {
        val bins = (0 until DayStrip.MAX_DAYS + 6).map {
            bin(midnight + it * 86_400_000L + 3_600_000L, 10 + it)
        }

        val strip = DayStrip.build(bins, utc)

        assertEquals(DayStrip.MAX_DAYS, strip.days.size)
        assertTrue(strip.days.zipWithNext().all { (a, b) -> a.startMs < b.startMs })
        assertTrue("the newest days are the ones kept", strip.days.last().busiest!! > 20)
    }

    @Test
    fun `nothing recorded is an empty strip rather than a crash`() {
        assertTrue(DayStrip.build(emptyList(), utc).empty)
        assertTrue(DayStrip.build(listOf(bin(0L, 5)), utc).empty)
    }

    @Test
    fun `a day divides into whole five minute columns`() {
        assertEquals(288, DayStrip.COLUMNS)
        assertEquals(0, DayStrip.hourOf(0))
        assertEquals(12, DayStrip.hourOf(DayStrip.COLUMNS / 2))
    }
}
