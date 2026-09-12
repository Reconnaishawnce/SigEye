package com.sigeye.core

import android.content.Context
import com.sigeye.core.analysis.JourneyDevice
import com.sigeye.core.analysis.JourneyLeg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A saved journey: a name, when it was recorded, and every leg of it. */
data class SavedJourney(
    val label: String,
    val savedAtMs: Long,
    val legs: List<JourneyLeg>,
) {
    val legCount: Int get() = legs.size
    val deviceCount: Int get() = legs.flatMap { leg -> leg.devices.map { it.address } }
        .distinct().size
}

/**
 * Journeys on disk, including the one in progress.
 *
 * A journey is hours long by design - that is the whole point of it - and the screen
 * holding it gets closed at a traffic light. Keeping it only in memory made the experiment
 * unusable for exactly the thing it exists to do, so the current journey is written after
 * every leg and read back when the screen opens.
 */
class JourneyStore private constructor(context: Context) {

    private val directory = File(context.applicationContext.filesDir, "journeys").apply {
        if (!exists()) mkdirs()
    }

    private val inProgress = File(directory, "current.json")

    private val _journeys = MutableStateFlow(load())
    val journeys: StateFlow<List<SavedJourney>> = _journeys

    fun save(journey: SavedJourney) {
        runCatching {
            File(directory, "journey-${journey.savedAtMs}.json")
                .writeText(encode(journey).toString())
        }
        _journeys.value = load()
    }

    fun delete(journey: SavedJourney) {
        runCatching { File(directory, "journey-${journey.savedAtMs}.json").delete() }
        _journeys.value = load()
    }

    /** Keeps the journey being recorded, so closing the screen does not lose it. */
    fun keepCurrent(legs: List<JourneyLeg>) {
        runCatching {
            if (legs.isEmpty()) {
                inProgress.delete()
            } else {
                inProgress.writeText(
                    encode(SavedJourney("In progress", 0L, legs)).toString(),
                )
            }
        }
    }

    fun loadCurrent(): List<JourneyLeg> = runCatching {
        if (!inProgress.exists()) return emptyList()
        decode(JSONObject(inProgress.readText())).legs
    }.getOrDefault(emptyList())

    fun clearCurrent() {
        runCatching { inProgress.delete() }
    }

    private fun load(): List<SavedJourney> = runCatching {
        directory.listFiles()
            ?.filter { it.name.startsWith("journey-") && it.name.endsWith(".json") }
            ?.mapNotNull { file ->
                runCatching { decode(JSONObject(file.readText())) }.getOrNull()
            }
            ?.sortedByDescending { it.savedAtMs }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun encode(journey: SavedJourney): JSONObject {
        val legs = JSONArray()
        journey.legs.forEach { leg ->
            val devices = JSONArray()
            leg.devices.forEach { device ->
                devices.put(
                    JSONObject()
                        .put("address", device.address)
                        .put("label", device.label)
                        .put("vendor", device.vendor ?: JSONObject.NULL)
                        .put("random", device.isRandom)
                        .put("packets", device.packets)
                        .put("first", device.firstSeenMs)
                        .put("last", device.lastSeenMs)
                        .put("best", device.bestRssi),
                )
            }
            legs.put(
                JSONObject()
                    .put("index", leg.index)
                    .put("label", leg.label)
                    .put("started", leg.startedAtMs)
                    .put("ended", leg.endedAtMs)
                    .put("devices", devices),
            )
        }
        return JSONObject()
            .put("label", journey.label)
            .put("saved", journey.savedAtMs)
            .put("legs", legs)
    }

    private fun decode(json: JSONObject): SavedJourney {
        val legs = json.optJSONArray("legs") ?: JSONArray()
        return SavedJourney(
            label = json.optString("label", "Unnamed"),
            savedAtMs = json.optLong("saved"),
            legs = (0 until legs.length()).map { index ->
                val leg = legs.getJSONObject(index)
                val devices = leg.optJSONArray("devices") ?: JSONArray()
                JourneyLeg(
                    index = leg.optInt("index", index),
                    label = leg.optString("label", "Leg ${index + 1}"),
                    startedAtMs = leg.optLong("started"),
                    endedAtMs = leg.optLong("ended"),
                    devices = (0 until devices.length()).map { position ->
                        val device = devices.getJSONObject(position)
                        JourneyDevice(
                            address = device.optString("address"),
                            label = device.optString("label"),
                            vendor = device.optString("vendor").takeIf {
                                it.isNotBlank() && it != "null"
                            },
                            isRandom = device.optBoolean("random"),
                            packets = device.optInt("packets"),
                            firstSeenMs = device.optLong("first"),
                            lastSeenMs = device.optLong("last"),
                            bestRssi = device.optInt("best", -127),
                        )
                    },
                )
            },
        )
    }

    companion object {
        @Volatile
        private var instance: JourneyStore? = null

        fun get(context: Context): JourneyStore =
            instance ?: synchronized(this) {
                instance ?: JourneyStore(context).also { instance = it }
            }
    }
}
