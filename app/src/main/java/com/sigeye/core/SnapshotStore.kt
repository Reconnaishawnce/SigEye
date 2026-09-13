package com.sigeye.core

import android.content.Context
import com.sigeye.core.analysis.presence.Sighting
import com.sigeye.core.analysis.record.Snapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saved scans of places, on disk.
 *
 * One file per snapshot rather than a single blob: a scan of a busy street runs to a few
 * hundred devices, and the interesting operation is comparing two of them rather than
 * loading all of them at once.
 */
class SnapshotStore private constructor(context: Context) {

    private val directory = File(context.applicationContext.filesDir, "snapshots").apply {
        if (!exists()) mkdirs()
    }

    private val _snapshots = MutableStateFlow(load())
    val snapshots: StateFlow<List<Snapshot>> = _snapshots

    fun save(snapshot: Snapshot) {
        runCatching {
            File(directory, fileNameFor(snapshot)).writeText(encode(snapshot).toString())
        }
        _snapshots.value = load()
    }

    fun delete(snapshot: Snapshot) {
        runCatching { File(directory, fileNameFor(snapshot)).delete() }
        _snapshots.value = load()
    }

    private fun fileNameFor(snapshot: Snapshot) = "snap-${snapshot.takenAtMs}.json"

    private fun load(): List<Snapshot> = runCatching {
        directory.listFiles()
            ?.filter { it.name.endsWith(".json") }
            ?.mapNotNull { file -> runCatching { decode(JSONObject(file.readText())) }.getOrNull() }
            ?.sortedByDescending { it.takenAtMs }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun encode(snapshot: Snapshot): JSONObject {
        val devices = JSONArray()
        snapshot.devices.forEach { device ->
            devices.put(
                JSONObject()
                    .put("address", device.address)
                    .put("name", device.name ?: JSONObject.NULL)
                    .put("vendor", device.vendor ?: JSONObject.NULL)
                    .put("random", device.isRandom)
                    .put("first", device.firstSeenMs)
                    .put("last", device.lastSeenMs)
                    .put("count", device.sightings)
                    .put("rssi", device.rssi)
                    .put("best", device.bestRssi),
            )
        }
        return JSONObject()
            .put("label", snapshot.label)
            .put("taken", snapshot.takenAtMs)
            .put("devices", devices)
    }

    private fun decode(json: JSONObject): Snapshot {
        val devices = json.optJSONArray("devices") ?: JSONArray()
        return Snapshot(
            label = json.optString("label", "Unnamed"),
            takenAtMs = json.optLong("taken"),
            devices = (0 until devices.length()).map { index ->
                val device = devices.getJSONObject(index)
                Sighting(
                    address = device.optString("address"),
                    name = device.optString("name").takeIf {
                        it.isNotBlank() && it != "null"
                    },
                    vendor = device.optString("vendor").takeIf {
                        it.isNotBlank() && it != "null"
                    },
                    isRandom = device.optBoolean("random"),
                    firstSeenMs = device.optLong("first"),
                    lastSeenMs = device.optLong("last"),
                    sightings = device.optInt("count"),
                    rssi = device.optInt("rssi", -127),
                    bestRssi = device.optInt("best", -127),
                )
            },
        )
    }

    companion object {
        @Volatile
        private var instance: SnapshotStore? = null

        fun get(context: Context): SnapshotStore =
            instance ?: synchronized(this) {
                instance ?: SnapshotStore(context).also { instance = it }
            }
    }
}
