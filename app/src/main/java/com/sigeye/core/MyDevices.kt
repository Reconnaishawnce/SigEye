package com.sigeye.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Something the user has said belongs to them. */
data class Mine(
    val address: String,
    val label: String,
    val addedAtMs: Long,
)

/**
 * The devices that belong to whoever is holding the phone.
 *
 * Separate from [IgnoreList] on purpose, and the difference is not bookkeeping. Muting says
 * "I do not want to see this", which is a preference about a screen. This says "this one is
 * mine", which is a fact about the world, and the two want opposite treatment: a mute is
 * something you undo when you change your mind, and your own watch is still your own watch
 * next week. Rolling them together would mean that clearing the mute list, which people do
 * when a room changes, silently puts your own earbuds back into every count.
 *
 * It matters most in a follow. The whole method is elimination by walking: everything that
 * did not come with you drops out. Your own kit comes with you by definition, so it never
 * drops, survives to the end, and sits there looking like the best answer in the pool. A
 * follow that converges on somebody's own watch has not made a small error, it has
 * confirmed the method while getting the result exactly wrong.
 *
 * Addresses only, deliberately. A device that randomizes its address will need marking
 * again, and pretending otherwise by matching on payload shape would mute every identical
 * pair of earbuds in the building rather than the pair in this pocket. See [MyKit] for the
 * part that makes re-marking a ten-second job instead of a chore.
 */
class MyDevices private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("mydevices", Context.MODE_PRIVATE)

    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<List<Mine>> = _devices

    /** Cheap synchronous read, since this is consulted per advertisement. */
    @Volatile
    private var snapshot: Set<String> = _devices.value.map { it.address }.toSet()

    fun isMine(address: String): Boolean = snapshot.contains(address.uppercase())

    val addresses: Set<String> get() = snapshot

    fun size(): Int = snapshot.size

    fun add(address: String, label: String, nowMs: Long = System.currentTimeMillis()) {
        val key = address.uppercase()
        mutate { current ->
            current.filterNot { it.address == key } + Mine(key, label, nowMs)
        }
    }

    fun remove(address: String) {
        val key = address.uppercase()
        mutate { current -> current.filterNot { it.address == key } }
    }

    fun clear() = mutate { emptyList() }

    private fun mutate(block: (List<Mine>) -> List<Mine>) {
        val updated = block(_devices.value).sortedBy { it.label.lowercase() }
        snapshot = updated.map { it.address }.toSet()
        _devices.value = updated
        save(updated)
    }

    private fun save(devices: List<Mine>) {
        val array = JSONArray()
        devices.forEach { mine ->
            array.put(
                JSONObject()
                    .put("address", mine.address)
                    .put("label", mine.label)
                    .put("at", mine.addedAtMs),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): List<Mine> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                Mine(
                    address = obj.getString("address").uppercase(),
                    label = obj.optString("label").ifBlank { obj.getString("address") },
                    addedAtMs = obj.optLong("at", 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val KEY = "devices"

        @Volatile
        private var instance: MyDevices? = null

        fun get(context: Context): MyDevices =
            instance ?: synchronized(this) {
                instance ?: MyDevices(context).also { instance = it }
            }
    }
}
