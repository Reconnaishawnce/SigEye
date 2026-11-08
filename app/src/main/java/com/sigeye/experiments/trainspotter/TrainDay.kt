package com.sigeye.experiments.trainspotter

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.max

/** Ten seconds of a recording, the unit everything below is drawn and detected in. */
data class Bucket(
    val startMs: Long,
    /** Devices that arrived in these ten seconds. */
    val new: Int,
    /** The highest 6-second arrival count inside it. A train makes this jump. */
    val peak6: Int,
    /** Average devices around during it. */
    val present: Int,
    /** Seconds actually recorded. Less than ten means the phone missed some. */
    val seconds: Int,
    val settling: Boolean = false,
)

/** One burst of arrivals that looks like a train. */
data class TrainEvent(
    val startMs: Long,
    val endMs: Long,
    val peakMs: Long,
    /** Devices that arrived during it, which is roughly how many phones were on board. */
    val size: Int,
    val peak6: Int,
    /** Most devices around at once during it. */
    val present: Int,
)

/** One service day, which runs from 3 AM to 3 AM so a late train counts with its evening. */
data class ServiceDay(
    val date: LocalDate,
    val firstMs: Long,
    val lastMs: Long,
    val trains: Int,
    val devices: Int,
)

data class HourLoad(val hour: Int, val trains: Int, val devices: Int)

data class TrainSummary(
    val startMs: Long,
    val endMs: Long,
    val recordedSeconds: Long,
    /** Seconds inside the recording that the phone did not record. */
    val missedSeconds: Long,
    val events: List<TrainEvent>,
    val days: List<ServiceDay>,
    /** Every hour of the day that saw a train, in clock order. */
    val hours: List<HourLoad>,
    val busiestHour: HourLoad?,
    val biggest: TrainEvent?,
    val meanPresent: Int,
    val peakPresent: Int,
)

/**
 * Turning a day of one-second rows into trains, rush hours and a picture.
 *
 * The detector works the same way a person reading the graph would. Each ten seconds is
 * compared with what the last ten minutes were like; a jump well above that is a burst, and
 * bursts close together are one train. Comparing against the recent past rather than a fixed
 * number is what lets the same settings work on a quiet suburban platform at 5 AM and a
 * packed central station at 8.
 *
 * Pure and Android-free.
 */
object TrainDay {

    const val BUCKET_MS = 10_000L

    /** Fewer arrivals than this in six seconds is never a train, however quiet it was. */
    const val MIN_BURST = 4

    /** How far above the recent typical a burst has to reach. */
    const val FACTOR = 3.0

    /** Ten minutes of buckets, which is what "recently" means for the baseline. */
    const val BASELINE_BUCKETS = 60

    /** Bursts closer together than this are one train: doors, then the last stragglers. */
    const val MERGE_GAP_MS = 60_000L

    /** A train smaller than this many arrivals is more likely a group walking past. */
    const val MIN_SIZE = 6

    /** Trains before this hour belong to the night before. */
    const val SERVICE_DAY_STARTS = 3

    fun threshold(baseline: Double): Int = max(MIN_BURST, ceil(baseline * FACTOR).toInt())

    /** Collects seconds into buckets as they arrive, for a recording still in progress. */
    class Accumulator {
        private var startMs = Long.MIN_VALUE
        private var new = 0
        private var peak6 = 0
        private var presentSum = 0L
        private var seconds = 0
        private var settling = true

        /** Adds one second, returning the bucket it closed if it crossed a boundary. */
        fun add(second: Second): Bucket? {
            val slot = second.atMs - Math.floorMod(second.atMs, BUCKET_MS)
            val closed = if (startMs != Long.MIN_VALUE && slot != startMs) flush() else null
            if (startMs == Long.MIN_VALUE || slot != startMs) {
                startMs = slot
                new = 0
                peak6 = 0
                presentSum = 0
                seconds = 0
                settling = true
            }
            new += second.new1
            peak6 = max(peak6, second.new6)
            presentSum += second.present60
            seconds++
            settling = settling && second.settling
            return closed
        }

        /** The bucket in progress, closed early. */
        fun flush(): Bucket? {
            if (startMs == Long.MIN_VALUE || seconds == 0) return null
            return Bucket(
                startMs = startMs,
                new = new,
                peak6 = peak6,
                present = (presentSum / seconds).toInt(),
                seconds = seconds,
                settling = settling,
            )
        }
    }

    fun buckets(seconds: List<Second>): List<Bucket> {
        val accumulator = Accumulator()
        val out = ArrayList<Bucket>(seconds.size / 10 + 1)
        seconds.sortedBy { it.atMs }.forEach { second -> accumulator.add(second)?.let(out::add) }
        accumulator.flush()?.let(out::add)
        return out
    }

    fun events(buckets: List<Bucket>): List<TrainEvent> {
        if (buckets.isEmpty()) return emptyList()

        val active = BooleanArray(buckets.size)
        buckets.forEachIndexed { index, bucket ->
            if (bucket.settling) return@forEachIndexed
            val recent = buckets
                .subList(max(0, index - BASELINE_BUCKETS), index)
                .filterNot { it.settling }
                .map { it.peak6 }
            active[index] = bucket.peak6 >= threshold(median(recent))
        }

        val events = mutableListOf<TrainEvent>()
        var first = -1
        var last = -1
        fun close() {
            if (first < 0) return
            val span = buckets.subList(first, last + 1)
            val size = span.sumOf { it.new }
            if (size >= MIN_SIZE) {
                val loudest = span.maxBy { it.peak6 }
                events += TrainEvent(
                    startMs = span.first().startMs,
                    endMs = span.last().startMs + BUCKET_MS,
                    peakMs = loudest.startMs,
                    size = size,
                    peak6 = loudest.peak6,
                    present = span.maxOf { it.present },
                )
            }
            first = -1
        }

        buckets.indices.forEach { index ->
            if (!active[index]) return@forEach
            if (first >= 0 && buckets[index].startMs - (buckets[last].startMs + BUCKET_MS) > MERGE_GAP_MS) {
                close()
            }
            if (first < 0) first = index
            last = index
        }
        close()
        return events
    }

    fun summarize(buckets: List<Bucket>, zone: ZoneId): TrainSummary {
        val events = events(buckets)
        val live = buckets.filterNot { it.settling }

        val startMs = buckets.firstOrNull()?.startMs ?: 0L
        val endMs = buckets.lastOrNull()?.let { it.startMs + BUCKET_MS } ?: 0L
        val recorded = buckets.sumOf { it.seconds.toLong() }
        val span = ((endMs - startMs) / 1000L).coerceAtLeast(0L)

        val days = events
            .groupBy { serviceDate(it.startMs, zone) }
            .map { (date, trains) ->
                ServiceDay(
                    date = date,
                    firstMs = trains.minOf { it.startMs },
                    lastMs = trains.maxOf { it.startMs },
                    trains = trains.size,
                    devices = trains.sumOf { it.size },
                )
            }
            .sortedBy { it.date }

        val hours = events
            .groupBy { Instant.ofEpochMilli(it.startMs).atZone(zone).hour }
            .map { (hour, trains) -> HourLoad(hour, trains.size, trains.sumOf { it.size }) }
            .sortedBy { it.hour }

        return TrainSummary(
            startMs = startMs,
            endMs = endMs,
            recordedSeconds = recorded,
            missedSeconds = (span - recorded).coerceAtLeast(0L),
            events = events,
            days = days,
            hours = hours,
            busiestHour = hours.maxWithOrNull(compareBy({ it.devices }, { it.trains })),
            biggest = events.maxByOrNull { it.size },
            meanPresent = if (live.isEmpty()) 0 else live.sumOf { it.present } / live.size,
            peakPresent = live.maxOfOrNull { it.present } ?: 0,
        )
    }

    /**
     * Fewer, wider buckets for drawing.
     *
     * A day is 8,640 buckets and a phone screen is a few hundred pixels wide. Arrivals are
     * added, the burst peak keeps its maximum so a train never gets averaged away, and the
     * busy-ness is averaged.
     */
    fun downsample(buckets: List<Bucket>, maxPoints: Int): List<Bucket> {
        if (buckets.size <= maxPoints || maxPoints <= 0) return buckets
        val group = ceil(buckets.size.toDouble() / maxPoints).toInt()
        return buckets.chunked(group).map { chunk ->
            val seconds = chunk.sumOf { it.seconds }
            Bucket(
                startMs = chunk.first().startMs,
                new = chunk.sumOf { it.new },
                peak6 = chunk.maxOf { it.peak6 },
                present = if (seconds == 0) 0 else
                    (chunk.sumOf { it.present.toLong() * it.seconds } / seconds).toInt(),
                seconds = seconds,
                settling = chunk.all { it.settling },
            )
        }
    }

    /** The trains as a spreadsheet, one row each. */
    const val EVENTS_HEADER = "start_local,end_local,duration_s,devices,peak_new_6s,peak_present"

    fun eventsCsv(events: List<TrainEvent>, zone: ZoneId): String = buildString {
        appendLine(EVENTS_HEADER)
        val format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        events.forEach { train ->
            appendLine(
                listOf(
                    format.format(Instant.ofEpochMilli(train.startMs).atZone(zone)),
                    format.format(Instant.ofEpochMilli(train.endMs).atZone(zone)),
                    ((train.endMs - train.startMs) / 1000).toString(),
                    train.size.toString(),
                    train.peak6.toString(),
                    train.present.toString(),
                ).joinToString(","),
            )
        }
    }

    fun serviceDate(atMs: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(atMs).atZone(zone).minusHours(SERVICE_DAY_STARTS.toLong()).toLocalDate()

    private fun median(values: List<Int>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle].toDouble()
        else (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}
