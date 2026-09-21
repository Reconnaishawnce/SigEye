package com.sigeye.core

import android.content.Context
import com.sigeye.core.analysis.surveillance.Certainty
import com.sigeye.core.analysis.surveillance.Found
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * What sweeps have found, kept between them.
 *
 * Persisted because the point of a serial that outlives an address is that passing the same
 * pole next week updates the same row. A log that emptied on every launch would count one
 * camera as fifty over a month.
 *
 * Only holds hardware recognized as surveillance. Nothing about the people or phones that
 * were in range at the same time is written here or anywhere else.
 */
class SweepStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("sweeps", Context.MODE_PRIVATE)

    private val _found = MutableStateFlow(load())
    val found: StateFlow<Map<String, Found>> = _found

    fun get(key: String): Found? = _found.value[key]

    @Synchronized
    fun put(one: Found) {
        _found.value = _found.value + (one.key to one)
        save()
    }

    @Synchronized
    fun forget(key: String) {
        _found.value = _found.value - key
        save()
    }

    @Synchronized
    fun clear() {
        _found.value = emptyMap()
        save()
    }

    private fun save() {
        val array = JSONArray()
        _found.value.values.forEach { one ->
            array.put(
                JSONObject()
                    .put("key", one.key)
                    .put("product", one.product)
                    .put("operator", one.operator)
                    .put("certainty", one.certainty.name)
                    .put("serial", one.serial ?: JSONObject.NULL)
                    .put("lat", one.latitude ?: JSONObject.NULL)
                    .put("lon", one.longitude ?: JSONObject.NULL)
                    .put("acc", one.accuracyM?.toDouble() ?: JSONObject.NULL)
                    .put("first", one.firstSeenMs)
                    .put("last", one.lastSeenMs)
                    .put("rssi", one.bestRssi)
                    .put("n", one.sightings),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): Map<String, Found> {
        val raw = prefs.getString(KEY, null) ?: return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).associate { index ->
                val obj = array.getJSONObject(index)
                val one = Found(
                    key = obj.getString("key"),
                    product = obj.getString("product"),
                    operator = obj.optString("operator", "unknown"),
                    certainty = runCatching {
                        Certainty.valueOf(obj.getString("certainty"))
                    }.getOrDefault(Certainty.POSSIBLE),
                    serial = obj.optString("serial", "").ifBlank { null },
                    latitude = if (obj.isNull("lat")) null else obj.getDouble("lat"),
                    longitude = if (obj.isNull("lon")) null else obj.getDouble("lon"),
                    accuracyM = if (obj.isNull("acc")) null else obj.getDouble("acc").toFloat(),
                    firstSeenMs = obj.optLong("first", 0L),
                    lastSeenMs = obj.optLong("last", 0L),
                    bestRssi = obj.optInt("rssi", -127),
                    sightings = obj.optInt("n", 1),
                )
                one.key to one
            }
        }.getOrDefault(emptyMap())
    }

    companion object {
        private const val KEY = "found"

        @Volatile
        private var instance: SweepStore? = null

        fun get(context: Context): SweepStore =
            instance ?: synchronized(this) {
                instance ?: SweepStore(context).also { instance = it }
            }
    }
}
