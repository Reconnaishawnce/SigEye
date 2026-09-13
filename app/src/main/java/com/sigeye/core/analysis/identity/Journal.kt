package com.sigeye.core.analysis.identity

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Something the operator saw and the radio could not.
 *
 * The walk-by works because it is a known event with a known signal shape. But things worth
 * knowing happen constantly during a follow and none of them are scripted: they go behind a
 * pillar, cross the road, get on an escalator, stop at a light. A tap at the moment it
 * happens turns what somebody's eyes saw into a timestamp the trails can be read against.
 *
 * Deliberately small. Most people will never press any of these, and a screen that demanded
 * it would be worse than one that does not offer it.
 */
enum class Mark(val label: String, val hint: String) {
    CLOSER("Closer", "You are nearer them than you were."),
    FARTHER("Farther", "You have dropped back or they have pulled away."),
    BLOCKED("Blocked", "A pillar, a van, a corner. Something solid between you."),
    TURNED("Turned", "One of you went round a corner."),
    NOTE("Noted", "Something happened worth finding again."),
}

/** One thing that happened during a follow, at a time. */
sealed interface Moment {
    val atMs: Long

    data class Started(override val atMs: Long, val name: String) : Moment
    data class BaselineDone(override val atMs: Long, val heard: Int) : Moment
    data class Following(override val atMs: Long, val pool: Int, val targetPresent: Boolean) : Moment
    data class ProbeRan(override val atMs: Long, val kind: Probe, val index: Int) : Moment
    data class Rotated(
        override val atMs: Long,
        val fromAddress: String,
        val toAddress: String,
        val byHand: Boolean,
    ) : Moment

    data class Marked(override val atMs: Long, val mark: Mark, val note: String? = null) : Moment
    data class Held(override val atMs: Long, val address: String, val label: String?) : Moment
    data class Lost(override val atMs: Long, val address: String) : Moment

    /** What the count was, sampled on a fixed cadence so the chart has an even spine. */
    data class Count(override val atMs: Long, val stillIn: Int, val pool: Int) : Moment
}

/**
 * Everything that happened during a follow, in order.
 *
 * Three things wanted the same record and none of them existed. A follow could not be
 * replayed, so nobody could see the moment it went wrong or learn to trust it. Marks had
 * nowhere to live. And the evidence for a follow was spread across saved runs, CSV files
 * and the library, so assembling it into something another person could check was manual.
 *
 * One journal answers all three: scrub it, mark it, export it.
 *
 * Counts are sampled rather than recorded on every tick. A tick is twice a second and a
 * follow is half an hour, which would be a hundred thousand entries to hold and serialize
 * for a chart a few hundred pixels wide.
 *
 * Pure and Android-free.
 */
class Journal {

    private val moments = mutableListOf<Moment>()
    private var lastCountAtMs = 0L

    val size: Int get() = moments.size

    /** Everything, oldest first. */
    fun all(): List<Moment> = moments.toList()

    /** Everything except the count samples, which are the chart rather than the story. */
    fun events(): List<Moment> = moments.filterNot { it is Moment.Count }

    fun counts(): List<Moment.Count> = moments.filterIsInstance<Moment.Count>()

    fun marks(): List<Moment.Marked> = moments.filterIsInstance<Moment.Marked>()

    fun add(moment: Moment) {
        moments.add(moment)
        if (moments.size > MAX_MOMENTS) trim()
    }

    /** Records the count, no more often than the sampling period. */
    fun sample(atMs: Long, stillIn: Int, pool: Int) {
        if (atMs - lastCountAtMs < SAMPLE_MS) return
        lastCountAtMs = atMs
        add(Moment.Count(atMs, stillIn, pool))
    }

    fun clear() {
        moments.clear()
        lastCountAtMs = 0L
    }

    /**
     * Drops every other count sample when the journal gets long.
     *
     * Only the counts. Events are the record and there are never many of them; halving the
     * chart's resolution costs a follow nothing, and dropping a rotation would lose the
     * thing the journal exists to show.
     */
    private fun trim() {
        var keep = true
        val kept = moments.filter { moment ->
            if (moment !is Moment.Count) return@filter true
            keep = !keep
            keep
        }
        moments.clear()
        moments.addAll(kept)
    }

    // ------------------------------------------------------------------ reading it back

    /** What the count was at a moment, for a scrubber. Null before the first sample. */
    fun countAt(atMs: Long): Moment.Count? =
        counts().lastOrNull { it.atMs <= atMs } ?: counts().firstOrNull()

    /** Events within a window of a moment, which is what a scrubber should be showing. */
    fun around(atMs: Long, windowMs: Long = 15_000L): List<Moment> =
        events().filter { kotlin.math.abs(it.atMs - atMs) <= windowMs }

    val startedAtMs: Long? get() = moments.firstOrNull()?.atMs
    val endedAtMs: Long? get() = moments.lastOrNull()?.atMs

    val spanMs: Long
        get() {
            val from = startedAtMs ?: return 0L
            return ((endedAtMs ?: from) - from).coerceAtLeast(0L)
        }

    // ------------------------------------------------------------------ saying it

    /** One line for a moment, in the reader's terms rather than the code's. */
    fun describe(moment: Moment): String = when (moment) {
        is Moment.Started -> "Follow started: ${moment.name}"
        is Moment.BaselineDone -> "Baseline done, ${moment.heard} devices audible"
        is Moment.Following ->
            "Following ${moment.pool} devices" +
                if (moment.targetPresent) ", target already here" else ", waiting for them"

        is Moment.ProbeRan -> when (moment.kind) {
            Probe.ORBIT -> "Circled them (${moment.index + 1})"
            Probe.WALK_BY -> "Walked past them (${moment.index + 1})"
        }

        is Moment.Rotated ->
            "${moment.fromAddress.takeLast(8)} became ${moment.toAddress.takeLast(8)}" +
                if (moment.byHand) ", you picked it" else ", followed automatically"

        is Moment.Marked -> moment.note?.let { "${moment.mark.label}: $it" } ?: moment.mark.label
        is Moment.Held -> "Held ${moment.label ?: moment.address}"
        is Moment.Lost -> "Lost ${moment.address.takeLast(8)}"
        is Moment.Count -> "${moment.stillIn} of ${moment.pool} still with them"
    }

    /** Minutes and seconds from the start of the follow. */
    fun clock(atMs: Long): String {
        val from = startedAtMs ?: return "0:00"
        val seconds = ((atMs - from) / 1000).coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }

    // ------------------------------------------------------------------ persistence

    fun snapshot(): JSONArray {
        val array = JSONArray()
        moments.forEach { moment ->
            val json = JSONObject().put("at", moment.atMs)
            when (moment) {
                is Moment.Started -> json.put("t", "start").put("name", moment.name)
                is Moment.BaselineDone -> json.put("t", "baseline").put("heard", moment.heard)
                is Moment.Following -> json.put("t", "follow")
                    .put("pool", moment.pool)
                    .put("here", moment.targetPresent)

                is Moment.ProbeRan -> json.put("t", "probe")
                    .put("kind", moment.kind.name)
                    .put("index", moment.index)

                is Moment.Rotated -> json.put("t", "rotate")
                    .put("from", moment.fromAddress)
                    .put("to", moment.toAddress)
                    .put("hand", moment.byHand)

                is Moment.Marked -> json.put("t", "mark")
                    .put("mark", moment.mark.name)
                    .put("note", moment.note ?: JSONObject.NULL)

                is Moment.Held -> json.put("t", "held")
                    .put("address", moment.address)
                    .put("label", moment.label ?: JSONObject.NULL)

                is Moment.Lost -> json.put("t", "lost").put("address", moment.address)
                is Moment.Count -> json.put("t", "count")
                    .put("in", moment.stillIn)
                    .put("pool", moment.pool)
            }
            array.put(json)
        }
        return array
    }

    fun restore(array: JSONArray?) {
        clear()
        if (array == null) return
        (0 until array.length()).forEach { index ->
            val json = array.optJSONObject(index) ?: return@forEach
            val at = json.optLong("at")
            val moment = when (json.optString("t")) {
                "start" -> Moment.Started(at, json.optString("name"))
                "baseline" -> Moment.BaselineDone(at, json.optInt("heard"))
                "follow" -> Moment.Following(at, json.optInt("pool"), json.optBoolean("here"))
                "probe" -> runCatching {
                    Moment.ProbeRan(at, Probe.valueOf(json.optString("kind")), json.optInt("index"))
                }.getOrNull()

                "rotate" -> Moment.Rotated(
                    at,
                    json.optString("from"),
                    json.optString("to"),
                    json.optBoolean("hand"),
                )

                "mark" -> runCatching {
                    Moment.Marked(
                        at,
                        Mark.valueOf(json.optString("mark")),
                        json.optString("note").takeIf { it.isNotBlank() && it != "null" },
                    )
                }.getOrNull()

                "held" -> Moment.Held(
                    at,
                    json.optString("address"),
                    json.optString("label").takeIf { it.isNotBlank() && it != "null" },
                )

                "lost" -> Moment.Lost(at, json.optString("address"))
                "count" -> Moment.Count(at, json.optInt("in"), json.optInt("pool"))
                else -> null
            }
            moment?.let { moments.add(it) }
            if (moment is Moment.Count) lastCountAtMs = at
        }
    }

    companion object {
        /** How often the count is sampled. Fine for a chart, and a chart is what it is for. */
        const val SAMPLE_MS = 5_000L

        /** Past this the counts are halved. A half-hour follow is nowhere near it. */
        const val MAX_MOMENTS = 2_000
    }
}
