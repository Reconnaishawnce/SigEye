package com.sigeye.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * One number from a run, with enough about it to be compared later.
 *
 * The unit is carried rather than baked into the label because a comparison has to know
 * whether "6.2" and "5.1" are decibels or metres before it can say anything sensible about
 * the difference between them.
 */
data class RunFigure(
    val label: String,
    val value: Double,
    val unit: String = "",
    val decimals: Int = 1,
    /**
     * True when a bigger number is the better outcome. Only used to colour a change, and
     * left null when the experiment has no opinion - most of them do not.
     */
    val higherIsBetter: Boolean? = null,
) {
    fun pretty(): String {
        val number = "%.${decimals}f".format(java.util.Locale.US, value)
        return if (unit.isEmpty()) number else "$number $unit"
    }
}

/** A finished run of one experiment, named by the person who ran it. */
data class SavedRun(
    val experiment: String,
    val name: String,
    val takenAtMs: Long,
    val figures: List<RunFigure>,
    val note: String? = null,
) {
    fun figure(label: String): RunFigure? = figures.firstOrNull { it.label == label }
}

/** One line of a comparison between two runs. */
data class RunDelta(
    val label: String,
    val before: RunFigure?,
    val after: RunFigure?,
) {
    val change: Double? = if (before != null && after != null) after.value - before.value else null

    /** True when the two runs measured the same thing and the number barely moved. */
    val steady: Boolean
        get() {
            val moved = change ?: return false
            val scale = maxOf(abs(before?.value ?: 0.0), abs(after?.value ?: 0.0), 1.0)
            return abs(moved) / scale < 0.02
        }

    /** null when there is nothing to judge: no pair, no opinion, or no movement worth one. */
    val better: Boolean?
        get() {
            val moved = change ?: return null
            val opinion = after?.higherIsBetter ?: before?.higherIsBetter ?: return null
            if (steady) return null
            return if (opinion) moved > 0 else moved < 0
        }

    fun prettyChange(): String {
        val moved = change ?: return if (after == null) "gone" else "new"
        val figure = after ?: return "gone"
        val sign = if (moved > 0) "+" else ""
        val number = "%.${figure.decimals}f".format(java.util.Locale.US, moved)
        return if (figure.unit.isEmpty()) "$sign$number" else "$sign$number ${figure.unit}"
    }
}

/**
 * Compares two runs line by line, matching on the figure's label.
 *
 * Order follows the later run, with anything the later run dropped listed after it, so a
 * reading in the same row of both columns is genuinely the same measurement. Figures that
 * only one of the runs has are kept rather than quietly dropped: an experiment that stopped
 * reporting a number is a fact about the comparison, not noise to hide.
 */
fun compareRuns(before: SavedRun, after: SavedRun): List<RunDelta> {
    val seen = mutableSetOf<String>()
    val rows = after.figures.map { figure ->
        seen += figure.label
        RunDelta(figure.label, before.figure(figure.label), figure)
    }
    val dropped = before.figures.filterNot { seen.contains(it.label) }
        .map { RunDelta(it.label, it, null) }
    return rows + dropped
}

/**
 * Saved runs, on disk, for any experiment that wants them.
 *
 * Most experiments are a live readout that forgets everything the moment you leave. That is
 * right for pointing a phone at something and wrong for the question people actually ask,
 * which is "is this worse than it was last week". One store rather than one per experiment
 * because a run is the same shape everywhere: a name, a time and a handful of numbers.
 *
 * One file per experiment. Runs are small, a person saves a few dozen at most, and keeping
 * an experiment's history in one file means listing it costs one read.
 */
class RunStore private constructor(context: Context) {

    private val directory = File(context.applicationContext.filesDir, "runs").apply {
        if (!exists()) mkdirs()
    }

    private val _version = MutableStateFlow(0)

    /** Bumped on every change, so a screen can recompose without holding every run in memory. */
    val version: StateFlow<Int> = _version

    fun runs(experiment: String): List<SavedRun> = runCatching {
        val file = fileFor(experiment)
        if (!file.exists()) return@runCatching emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length())
            .mapNotNull { runCatching { decode(experiment, array.getJSONObject(it)) }.getOrNull() }
            .sortedByDescending { it.takenAtMs }
    }.getOrDefault(emptyList())

    fun save(run: SavedRun) {
        val kept = (runs(run.experiment) + run)
            .sortedByDescending { it.takenAtMs }
            .take(MAX_RUNS)
        write(run.experiment, kept)
    }

    fun delete(run: SavedRun) {
        write(run.experiment, runs(run.experiment).filterNot { it.takenAtMs == run.takenAtMs })
    }

    fun clear(experiment: String) = write(experiment, emptyList())

    private fun write(experiment: String, runs: List<SavedRun>) {
        runCatching {
            val array = JSONArray()
            runs.forEach { array.put(encode(it)) }
            fileFor(experiment).writeText(array.toString())
        }
        _version.value++
    }

    private fun fileFor(experiment: String) =
        File(directory, experiment.filter { it.isLetterOrDigit() || it == '-' } + ".json")

    private fun encode(run: SavedRun): JSONObject {
        val figures = JSONArray()
        run.figures.forEach { figure ->
            figures.put(
                JSONObject()
                    .put("label", figure.label)
                    .put("value", figure.value)
                    .put("unit", figure.unit)
                    .put("decimals", figure.decimals)
                    .put("higher", figure.higherIsBetter ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("name", run.name)
            .put("taken", run.takenAtMs)
            .put("note", run.note ?: JSONObject.NULL)
            .put("figures", figures)
    }

    private fun decode(experiment: String, json: JSONObject): SavedRun {
        val figures = json.optJSONArray("figures") ?: JSONArray()
        return SavedRun(
            experiment = experiment,
            name = json.optString("name", "Unnamed"),
            takenAtMs = json.optLong("taken"),
            note = json.optString("note").takeIf { it.isNotBlank() && it != "null" },
            figures = (0 until figures.length()).map { index ->
                val figure = figures.getJSONObject(index)
                RunFigure(
                    label = figure.optString("label"),
                    value = figure.optDouble("value", 0.0),
                    unit = figure.optString("unit", ""),
                    decimals = figure.optInt("decimals", 1),
                    higherIsBetter = if (figure.isNull("higher")) null else figure.optBoolean("higher"),
                )
            },
        )
    }

    companion object {
        /** Enough to see a trend over a season. Past that, the old ones are not being read. */
        const val MAX_RUNS = 50

        @Volatile
        private var instance: RunStore? = null

        fun get(context: Context): RunStore = instance ?: synchronized(this) {
            instance ?: RunStore(context).also { instance = it }
        }
    }
}
