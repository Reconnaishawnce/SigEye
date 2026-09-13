package com.sigeye.core

import android.content.Context
import com.sigeye.core.analysis.rf.SurveySpot
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The reference a survey's spots are measured against: gap per access point, and when. */
data class SurveyBaseline(val takenAtMs: Long, val gaps: Map<String, Double>)

/**
 * A building survey, kept between sessions.
 *
 * Surveying a house means walking it, and walking it means the screen gets locked, put in a
 * pocket and reopened in the next room. Holding the spots in composition meant the survey
 * lasted exactly as long as nobody looked away from it, which for this experiment is the
 * one thing it needed to survive.
 *
 * The baseline is stored with the spots and not separately. Excess loss is a difference
 * from that reference, so spots without it are numbers with no meaning - keeping one and
 * losing the other would leave a file full of readings nothing could interpret.
 */
class SurveyStore private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, "wall-survey.json")

    private val _spots = MutableStateFlow<List<SurveySpot>>(emptyList())
    val spots: StateFlow<List<SurveySpot>> = _spots

    private val _baseline = MutableStateFlow<SurveyBaseline?>(null)
    val baseline: StateFlow<SurveyBaseline?> = _baseline

    init {
        load()
    }

    /** Starts a new survey. Older spots keep their own baseline and are left alone. */
    fun startSurvey(gaps: Map<String, Double>, atMs: Long) {
        _baseline.value = SurveyBaseline(atMs, gaps)
        write()
    }

    fun add(spot: SurveySpot) {
        _spots.value = (_spots.value + spot).sortedByDescending { it.takenAtMs }.take(MAX)
        write()
    }

    fun rename(takenAtMs: Long, name: String) {
        _spots.value = _spots.value.map {
            if (it.takenAtMs == takenAtMs) it.copy(name = name) else it
        }
        write()
    }

    fun remove(takenAtMs: Long) {
        _spots.value = _spots.value.filterNot { it.takenAtMs == takenAtMs }
        write()
    }

    /** Drops one whole survey, which is what starting again means here. */
    fun clearSurvey(baselineAtMs: Long) {
        _spots.value = _spots.value.filterNot { it.baselineAtMs == baselineAtMs }
        write()
    }

    /** Drops everything except the survey currently being walked. */
    fun clearOlderThan(baselineAtMs: Long) {
        _spots.value = _spots.value.filter { it.baselineAtMs == baselineAtMs }
        write()
    }

    private fun write() {
        runCatching {
            val spots = JSONArray()
            _spots.value.forEach { spots.put(encode(it)) }
            val gaps = JSONObject()
            _baseline.value?.gaps?.forEach { (key, value) -> gaps.put(key, value) }
            val root = JSONObject()
                .put("spots", spots)
                .put("baselineAt", _baseline.value?.takenAtMs ?: 0L)
                .put("baseline", gaps)
            file.writeText(root.toString())
        }
    }

    private fun load() {
        runCatching {
            if (!file.exists()) return
            val root = JSONObject(file.readText())

            val array = root.optJSONArray("spots") ?: JSONArray()
            _spots.value = (0 until array.length())
                .mapNotNull { runCatching { decode(array.getJSONObject(it)) }.getOrNull() }
                .sortedByDescending { it.takenAtMs }

            val at = root.optLong("baselineAt")
            val gaps = root.optJSONObject("baseline")
            _baseline.value = if (at > 0L && gaps != null) {
                SurveyBaseline(
                    takenAtMs = at,
                    gaps = gaps.keys().asSequence().associateWith { gaps.optDouble(it) },
                )
            } else {
                null
            }
        }
    }

    private fun encode(spot: SurveySpot): JSONObject = JSONObject()
        .put("name", spot.name)
        .put("excess", spot.excessDb ?: JSONObject.NULL)
        .put("pairs", spot.pairs)
        .put("lost5", spot.lost5)
        .put("taken", spot.takenAtMs)
        .put("baseline", spot.baselineAtMs)

    private fun decode(json: JSONObject): SurveySpot = SurveySpot(
        name = json.optString("name", "Spot"),
        excessDb = if (json.isNull("excess")) null else json.optDouble("excess"),
        pairs = json.optInt("pairs"),
        lost5 = json.optInt("lost5"),
        takenAtMs = json.optLong("taken"),
        baselineAtMs = json.optLong("baseline"),
    )

    companion object {
        /** Several buildings' worth. Past this the oldest spots are not being read. */
        const val MAX = 120

        @Volatile
        private var instance: SurveyStore? = null

        fun get(context: Context): SurveyStore = instance ?: synchronized(this) {
            instance ?: SurveyStore(context).also { instance = it }
        }
    }
}
