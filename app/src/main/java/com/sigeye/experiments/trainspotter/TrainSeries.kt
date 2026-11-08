package com.sigeye.experiments.trainspotter

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One second of a long recording.
 *
 * Two kinds of number, because the question is two questions. [new1], [new6] and [new60]
 * are arrivals: devices that were not here a moment ago. A train is a sudden wave of those.
 * [present60] is how many different devices were heard in the last minute, which is how
 * busy the place is whether or not anything is arriving.
 */
data class Second(
    val atMs: Long,
    /** Devices that arrived during this second. Sum these over a train to size it. */
    val new1: Int,
    /** Arrivals in the last 6 seconds. The burst signal: a train makes this jump. */
    val new6: Int,
    /** Arrivals in the last minute. */
    val new60: Int,
    /** Different devices heard in the last minute. The busy-ness signal. */
    val present60: Int,
    /** Packets heard this second, for checking the radio was working. */
    val adverts: Int,
    /** True for the first minute, when everything is new because the recording just began. */
    val settling: Boolean = false,
)

/**
 * Rolling counts of arrivals and presence, sampled once a second.
 *
 * Kept separate from the alert logic on purpose. The live alert has phases, a learned
 * baseline and a cooldown, all tuned for deciding whether to buzz somebody's pocket. A
 * recording wants the plain numbers underneath that, the same way every second for a whole
 * day, so a graph of 6 AM can be compared with a graph of 6 PM.
 *
 * Pure and Android-free.
 */
class RollingCounts(
    /** How long a device must be gone before it counts as arriving again. */
    forgetMs: Long,
    private val rssiFloor: Int,
    private val settleMs: Long = SETTLE_MS,
) {
    /** Never shorter than a minute, or [Second.present60] would undercount. */
    private val forgetMs = maxOf(forgetMs, PRESENT_MS)

    private val heard = HashMap<String, Long>()
    private val arrivals = ArrayDeque<Long>()
    private var startedAtMs = -1L
    private var newSinceSample = 0
    private var advertsSinceSample = 0

    fun start(atMs: Long) {
        startedAtMs = atMs
    }

    fun advert(address: String, rssi: Int, atMs: Long) {
        if (rssi < rssiFloor) return
        if (startedAtMs < 0) startedAtMs = atMs
        advertsSinceSample++

        val previous = heard.put(address, atMs)
        val arrived = previous == null || atMs - previous > forgetMs
        // In the first minute everything in range is "new" only because we just started
        // listening. Counting it would put a giant fake train at the start of every file.
        if (arrived && !settling(atMs)) {
            newSinceSample++
            arrivals.addLast(atMs)
        }
    }

    fun sample(nowMs: Long): Second {
        if (startedAtMs < 0) startedAtMs = nowMs

        while (arrivals.isNotEmpty() && arrivals.first() <= nowMs - PRESENT_MS) {
            arrivals.removeFirst()
        }
        var new6 = 0
        for (index in arrivals.indices.reversed()) {
            if (arrivals[index] > nowMs - BURST_MS) new6++ else break
        }

        var present = 0
        val iterator = heard.entries.iterator()
        while (iterator.hasNext()) {
            val seenAt = iterator.next().value
            when {
                seenAt < nowMs - forgetMs -> iterator.remove()
                seenAt > nowMs - PRESENT_MS -> present++
            }
        }

        val second = Second(
            atMs = nowMs,
            new1 = newSinceSample,
            new6 = new6,
            new60 = arrivals.size,
            present60 = present,
            adverts = advertsSinceSample,
            settling = settling(nowMs),
        )
        newSinceSample = 0
        advertsSinceSample = 0
        return second
    }

    private fun settling(atMs: Long) = startedAtMs >= 0 && atMs - startedAtMs < settleMs

    companion object {
        const val BURST_MS = 6_000L
        const val PRESENT_MS = 60_000L
        const val SETTLE_MS = 60_000L
    }
}

/**
 * The long-recording file format: one row a second, readable in any spreadsheet.
 *
 * The local time column is there so somebody opening it in Excel sees 07:42:13 rather than
 * a thirteen-digit number. The epoch column is there so nothing depends on the time zone
 * the file was opened in.
 *
 * Pure and Android-free.
 */
object TrainCsv {

    const val HEADER =
        "epoch_ms,local_time,new_1s,new_6s,new_60s,present_60s,adverts_1s,settling"

    private val LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun row(second: Second, zone: ZoneId): String = listOf(
        second.atMs.toString(),
        LOCAL.format(Instant.ofEpochMilli(second.atMs).atZone(zone)),
        second.new1.toString(),
        second.new6.toString(),
        second.new60.toString(),
        second.present60.toString(),
        second.adverts.toString(),
        if (second.settling) "1" else "0",
    ).joinToString(",")

    /** One row back, or null for the header or anything damaged. */
    fun parse(line: String): Second? {
        val parts = line.split(',')
        if (parts.size < 7) return null
        return runCatching {
            Second(
                atMs = parts[0].trim().toLong(),
                new1 = parts[2].trim().toInt(),
                new6 = parts[3].trim().toInt(),
                new60 = parts[4].trim().toInt(),
                present60 = parts[5].trim().toInt(),
                adverts = parts[6].trim().toInt(),
                settling = parts.getOrNull(7)?.trim() == "1",
            )
        }.getOrNull()
    }

    /**
     * Every readable row of a recording.
     *
     * A recording that was still being written when the phone died ends in half a line.
     * That line is skipped rather than failing the whole day.
     */
    fun parseAll(lines: Sequence<String>): List<Second> = lines.mapNotNull { parse(it) }.toList()
}
