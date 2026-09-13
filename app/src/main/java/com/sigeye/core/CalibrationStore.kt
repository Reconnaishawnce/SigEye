package com.sigeye.core

import android.content.Context
import com.sigeye.core.analysis.presence.Calibration
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Headcounts somebody actually took, kept between sessions.
 *
 * On disk rather than in memory because the whole value of a headcount is that it outlives
 * the moment: you count the eleven people in the meeting room once and every crowd estimate
 * you take in that room afterwards is worth something. A calibration that vanished when the
 * screen closed would be the slider again, wearing a lab coat.
 */
class CalibrationStore private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, "calibrations.json")

    private val _calibrations = MutableStateFlow(load())
    val calibrations: StateFlow<List<Calibration>> = _calibrations

    fun add(calibration: Calibration) {
        val kept = (_calibrations.value + calibration)
            .sortedByDescending { it.takenAtMs }
            .take(MAX)
        write(kept)
    }

    fun remove(takenAtMs: Long) =
        write(_calibrations.value.filterNot { it.takenAtMs == takenAtMs })

    fun clear() = write(emptyList())

    /** Every place a headcount has been taken in, most recent first. */
    fun places(): List<String> = _calibrations.value
        .map { it.place }
        .distinctBy { it.lowercase() }

    private fun write(list: List<Calibration>) {
        _calibrations.value = list
        runCatching {
            val array = JSONArray()
            list.forEach { array.put(encode(it)) }
            file.writeText(array.toString())
        }
    }

    private fun load(): List<Calibration> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length())
            .mapNotNull { runCatching { decode(array.getJSONObject(it)) }.getOrNull() }
            .sortedByDescending { it.takenAtMs }
    }.getOrDefault(emptyList())

    private fun encode(c: Calibration): JSONObject = JSONObject()
        .put("place", c.place)
        .put("heads", c.headcount)
        .put("devices", c.devices)
        .put("samples", c.samples)
        .put("seconds", c.seconds)
        .put("floor", c.rssiFloor)
        .put("presence", c.presenceSeconds)
        .put("taken", c.takenAtMs)

    private fun decode(json: JSONObject): Calibration = Calibration(
        place = json.optString("place", "Unnamed"),
        headcount = json.optInt("heads"),
        devices = json.optDouble("devices", 0.0),
        samples = json.optInt("samples"),
        seconds = json.optInt("seconds"),
        rssiFloor = json.optInt("floor", -85),
        presenceSeconds = json.optInt("presence", 60),
        takenAtMs = json.optLong("taken"),
    )

    companion object {
        /** Well past the point where more counts stop changing the answer. */
        const val MAX = 60

        @Volatile
        private var instance: CalibrationStore? = null

        fun get(context: Context): CalibrationStore = instance ?: synchronized(this) {
            instance ?: CalibrationStore(context).also { instance = it }
        }
    }
}
