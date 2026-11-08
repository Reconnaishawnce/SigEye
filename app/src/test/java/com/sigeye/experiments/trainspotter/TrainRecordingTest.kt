package com.sigeye.experiments.trainspotter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * A day of trains, from one-second rows to "first train 5:41, rush hour at 8".
 *
 * The synthetic day below is a quiet platform with a trickle of people, and trains that
 * each bring a wave of new phones. Every test is a question somebody would ask of a real
 * recording.
 */
class TrainRecordingTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun at(hour: Int, minute: Int, second: Int = 0, day: Int = 7): Long =
        LocalDateTime.of(2026, 11, day, hour, minute, second).toInstant(ZoneOffset.UTC).toEpochMilli()

    // ------------------------------------------------------------------ rolling counts

    @Test
    fun `the first minute is settling and counts nobody as new`() {
        // Otherwise every recording starts with a giant fake train: everything in range
        // looks new to a phone that only just started listening.
        val counts = RollingCounts(forgetMs = 600_000L, rssiFloor = -90)
        counts.start(0L)
        repeat(50) { counts.advert("D$it", -60, 1_000L) }

        val second = counts.sample(2_000L)

        assertTrue(second.settling)
        assertEquals(0, second.new1)
        assertEquals(50, second.present60)
    }

    @Test
    fun `arrivals are counted per second and over the last six`() {
        val counts = RollingCounts(forgetMs = 600_000L, rssiFloor = -90)
        counts.start(0L)
        val t = 120_000L

        repeat(3) { counts.advert("A$it", -60, t) }
        assertEquals(3, counts.sample(t + 500).new1)

        repeat(4) { counts.advert("B$it", -60, t + 3_000) }
        val second = counts.sample(t + 3_500)
        assertEquals(4, second.new1)
        assertEquals(7, second.new6)

        // Eight seconds on, the first three have aged out of the six-second window.
        assertEquals(4, counts.sample(t + 8_000).new6)
    }

    @Test
    fun `a device heard again is not new until it has been gone a while`() {
        val counts = RollingCounts(forgetMs = 600_000L, rssiFloor = -90)
        counts.start(0L)
        counts.advert("A", -60, 120_000L)
        counts.sample(120_500L)

        counts.advert("A", -60, 125_000L)
        assertEquals(0, counts.sample(125_500L).new1)

        // Gone longer than the forget time: a person who came back on a later train.
        counts.advert("A", -60, 800_000L)
        assertEquals(1, counts.sample(800_500L).new1)
    }

    @Test
    fun `devices too faint to be on the platform are ignored`() {
        val counts = RollingCounts(forgetMs = 600_000L, rssiFloor = -85)
        counts.start(0L)
        counts.advert("far", -95, 120_000L)

        val second = counts.sample(120_500L)
        assertEquals(0, second.new1)
        assertEquals(0, second.present60)
    }

    @Test
    fun `present means heard in the last minute`() {
        val counts = RollingCounts(forgetMs = 600_000L, rssiFloor = -90)
        counts.start(0L)
        counts.advert("A", -60, 100_000L)
        counts.advert("B", -60, 150_000L)

        assertEquals(1, counts.sample(170_000L).present60)
    }

    // ------------------------------------------------------------------ the file

    @Test
    fun `a row goes out and comes back unchanged`() {
        val second = Second(at(7, 42, 13), 3, 9, 20, 140, 812, settling = false)

        val line = TrainCsv.row(second, zone)

        assertTrue(line.contains("2026-11-07 07:42:13"))
        assertEquals(second, TrainCsv.parse(line))
    }

    @Test
    fun `the header and a half written last line are skipped, not fatal`() {
        val rows = TrainCsv.parseAll(
            sequenceOf(
                TrainCsv.HEADER,
                TrainCsv.row(Second(at(6, 0), 1, 1, 1, 10, 50), zone),
                "1762498800000,2026-11-07 06:0",
            ),
        )
        assertEquals(1, rows.size)
    }

    @Test
    fun `the header names every column a spreadsheet will show`() {
        listOf("new_1s", "new_6s", "present_60s", "local_time").forEach {
            assertTrue(it, TrainCsv.HEADER.contains(it))
        }
    }

    // ------------------------------------------------------------------ a synthetic day

    /**
     * A quiet platform from 5 AM to 11 PM: one new device every 30 seconds, about 40
     * around, and a train that brings [size] new phones in a few seconds.
     */
    private fun day(trains: Map<Long, Int>, startHour: Int = 5, endHour: Int = 23): List<Second> {
        val out = mutableListOf<Second>()
        val recent = ArrayDeque<Pair<Long, Int>>()
        var t = at(startHour, 0)
        val end = at(endHour, 0)
        while (t < end) {
            var arrivals = if ((t / 1000) % 30 == 0L) 1 else 0
            trains.forEach { (when_, size) ->
                val into = (t - when_) / 1000
                if (into in 0..3) arrivals += size / 4
            }
            recent.addLast(t to arrivals)
            while (recent.first().first <= t - 6_000) recent.removeFirst()
            val busy = 40 + trains.entries.sumOf { (when_, size) ->
                if (t - when_ in 0..120_000) size else 0
            }
            out += Second(t, arrivals, recent.sumOf { it.second }, 0, busy, 300)
            t += 1000
        }
        return out
    }

    @Test
    fun `each train is found once, at the right time`() {
        val trains = mapOf(at(5, 41) to 40, at(8, 10) to 120, at(8, 25) to 100, at(22, 50) to 30)

        val events = TrainDay.events(TrainDay.buckets(day(trains)))

        assertEquals(4, events.size)
        trains.keys.sorted().zip(events).forEach { (expected, found) ->
            assertTrue("train at $expected found at ${found.startMs}",
                kotlin.math.abs(found.startMs - expected) <= 20_000)
        }
    }

    @Test
    fun `a train's size is roughly how many phones it brought`() {
        val events = TrainDay.events(TrainDay.buckets(day(mapOf(at(8, 10) to 120))))

        assertEquals(1, events.size)
        assertTrue("size ${events.first().size}", events.first().size in 110..130)
    }

    @Test
    fun `the background trickle of people is not mistaken for trains`() {
        assertTrue(TrainDay.events(TrainDay.buckets(day(emptyMap()))).isEmpty())
    }

    @Test
    fun `the summary names first train, last train and rush hour`() {
        val trains = mapOf(
            at(5, 41) to 30,
            at(8, 5) to 120, at(8, 20) to 110, at(8, 40) to 130,
            at(13, 0) to 40,
            at(22, 50) to 25,
        )

        val summary = TrainDay.summarize(TrainDay.buckets(day(trains)), zone)

        assertEquals(6, summary.events.size)
        assertEquals(1, summary.days.size)
        assertTrue(kotlin.math.abs(summary.days.first().firstMs - at(5, 41)) <= 20_000)
        assertTrue(kotlin.math.abs(summary.days.first().lastMs - at(22, 50)) <= 20_000)
        assertEquals(8, summary.busiestHour!!.hour)
        assertEquals(3, summary.busiestHour!!.trains)
        assertTrue(kotlin.math.abs(summary.biggest!!.startMs - at(8, 40)) <= 20_000)
    }

    @Test
    fun `a train after midnight belongs to the evening before`() {
        // Service days run 3 AM to 3 AM, so the 12:40 AM last train is the end of Saturday
        // rather than the start of Sunday.
        val late = at(0, 40, day = 8)
        val summary = TrainDay.summarize(
            TrainDay.buckets(day(mapOf(at(22, 0) to 40)) + day(mapOf(late to 40), 0, 1).map {
                it.copy(atMs = it.atMs + 86_400_000L)
            }),
            zone,
        )

        assertEquals(LocalDate.of(2026, 11, 7), TrainDay.serviceDate(late, zone))
        assertEquals(1, summary.days.size)
    }

    // ------------------------------------------------------------------ buckets and drawing

    @Test
    fun `buckets add arrivals and keep the biggest burst`() {
        val seconds = (0 until 10).map { Second(at(9, 0, it), new1 = 2, new6 = it, new60 = 0, present60 = 50, adverts = 1) }

        val bucket = TrainDay.buckets(seconds).single()

        assertEquals(20, bucket.new)
        assertEquals(9, bucket.peak6)
        assertEquals(50, bucket.present)
        assertEquals(10, bucket.seconds)
    }

    @Test
    fun `a day squeezed onto a screen never averages a train away`() {
        val buckets = TrainDay.buckets(day(mapOf(at(12, 0) to 120)))
        val drawn = TrainDay.downsample(buckets, 300)

        assertTrue(drawn.size <= 300)
        assertEquals(buckets.maxOf { it.peak6 }, drawn.maxOf { it.peak6 })
        assertEquals(buckets.sumOf { it.new }, drawn.sumOf { it.new })
    }

    @Test
    fun `seconds the phone slept through are reported as missed`() {
        val seconds = day(emptyMap(), 9, 10).filterNot { it.atMs in at(9, 20)..at(9, 29, 59) }

        val summary = TrainDay.summarize(TrainDay.buckets(seconds), zone)

        assertTrue("missed ${summary.missedSeconds}", summary.missedSeconds in 590L..610L)
    }

    @Test
    fun `an empty recording summarizes to nothing rather than crashing`() {
        val summary = TrainDay.summarize(emptyList(), zone)

        assertTrue(summary.events.isEmpty())
        assertNull(summary.busiestHour)
        assertNull(summary.biggest)
    }

    @Test
    fun `the trains export has one row per train`() {
        val events = TrainDay.events(TrainDay.buckets(day(mapOf(at(7, 0) to 60, at(9, 0) to 60))))
        val lines = TrainDay.eventsCsv(events, zone).trim().lines()

        assertEquals(TrainDay.EVENTS_HEADER, lines.first())
        assertEquals(3, lines.size)
    }
}
