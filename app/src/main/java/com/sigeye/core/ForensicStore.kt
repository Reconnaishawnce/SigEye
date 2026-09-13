package com.sigeye.core

import android.content.Context
import com.sigeye.core.analysis.record.SavedDevice
import com.sigeye.core.analysis.record.SavedSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Finished recordings, kept so later ones have something to be compared against.
 *
 * Only the per-device summary is stored, not every reading. A busy street produces tens of
 * thousands of packets and none of them are needed to answer "have I met this before" - the
 * raw readings are what the CSV export is for.
 */
class ForensicStore private constructor(context: Context) {

    private val directory = File(context.applicationContext.filesDir, "forensics").apply {
        if (!exists()) mkdirs()
    }

    private val _sessions = MutableStateFlow(load())
    val sessions: StateFlow<List<SavedSession>> = _sessions

    fun save(session: SavedSession) {
        runCatching {
            File(directory, "session-${session.recordedAtMs}.json")
                .writeText(encode(session).toString())
        }
        _sessions.value = load()
    }

    fun delete(session: SavedSession) {
        runCatching { File(directory, "session-${session.recordedAtMs}.json").delete() }
        _sessions.value = load()
    }

    private fun load(): List<SavedSession> = runCatching {
        directory.listFiles()
            ?.filter { it.name.endsWith(".json") }
            ?.mapNotNull { file ->
                runCatching { decode(JSONObject(file.readText())) }.getOrNull()
            }
            ?.sortedByDescending { it.recordedAtMs }
            .orEmpty()
    }.getOrDefault(emptyList())

    private fun encode(session: SavedSession): JSONObject {
        val devices = JSONArray()
        session.devices.forEach { device ->
            devices.put(
                JSONObject()
                    .put("address", device.address)
                    .put("label", device.label)
                    .put("vendor", device.vendor ?: JSONObject.NULL)
                    .put("random", device.isRandom)
                    .put("packets", device.packets)
                    .put("peak", device.peakRssi)
                    .put("behaviour", device.behaviour),
            )
        }
        return JSONObject()
            .put("label", session.label)
            .put("recorded", session.recordedAtMs)
            .put("span", session.spanMs)
            .put("devices", devices)
    }

    private fun decode(json: JSONObject): SavedSession {
        val devices = json.optJSONArray("devices") ?: JSONArray()
        return SavedSession(
            label = json.optString("label", "Unnamed"),
            recordedAtMs = json.optLong("recorded"),
            spanMs = json.optLong("span"),
            devices = (0 until devices.length()).map { index ->
                val device = devices.getJSONObject(index)
                SavedDevice(
                    address = device.optString("address"),
                    label = device.optString("label"),
                    vendor = device.optString("vendor").takeIf {
                        it.isNotBlank() && it != "null"
                    },
                    isRandom = device.optBoolean("random"),
                    packets = device.optInt("packets"),
                    peakRssi = device.optInt("peak", -127),
                    behaviour = device.optString("behaviour"),
                )
            },
        )
    }

    companion object {
        @Volatile
        private var instance: ForensicStore? = null

        fun get(context: Context): ForensicStore =
            instance ?: synchronized(this) {
                instance ?: ForensicStore(context).also { instance = it }
            }
    }
}
