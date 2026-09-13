package com.sigeye.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** What the user has said about one address. */
data class DeviceNote(
    val address: String,
    val nickname: String? = null,
    val lists: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = nickname.isNullOrBlank() && lists.isEmpty()
}

/**
 * The user's own names and groupings for devices they care about.
 *
 * Addresses are a poor handle for a human - "the car", "downstairs neighbor's TV" is what
 * you actually think in. Lists then turn those names into something a watch rule can
 * target, so "alert me about anything on my Vehicles list" needs no rule per device.
 *
 * Only useful for devices with a stable address. Phones rotate theirs roughly every
 * fifteen minutes, so a nickname on a random address will go stale - the Inspector says
 * so at the point of naming rather than letting you find out later.
 */
class DeviceBook private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("devicebook", Context.MODE_PRIVATE)

    private val _notes = MutableStateFlow(loadNotes())
    val notes: StateFlow<Map<String, DeviceNote>> = _notes

    private val _lists = MutableStateFlow(loadLists())

    /** Every list the user has created, in creation order. */
    val lists: StateFlow<List<String>> = _lists

    fun noteFor(address: String): DeviceNote? = _notes.value[address.uppercase()]

    fun nicknameOf(address: String): String? = noteFor(address)?.nickname?.takeIf {
        it.isNotBlank()
    }

    fun listsOf(address: String): Set<String> = noteFor(address)?.lists.orEmpty()

    fun addressesIn(list: String): Set<String> =
        _notes.value.values.filter { it.lists.contains(list) }.map { it.address }.toSet()

    fun setNickname(address: String, nickname: String?) {
        val key = address.uppercase()
        mutateNote(key) { it.copy(nickname = nickname?.trim()?.takeIf(String::isNotEmpty)) }
    }

    fun createList(name: String): Boolean {
        val clean = name.trim()
        if (clean.isEmpty()) return false
        if (_lists.value.any { it.equals(clean, ignoreCase = true) }) return false
        _lists.value = _lists.value + clean
        saveLists()
        return true
    }

    /** Removes the list and strips it from every device that was in it. */
    fun deleteList(name: String) {
        _lists.value = _lists.value.filterNot { it == name }
        saveLists()
        val updated = _notes.value.mapValues { (_, note) ->
            if (note.lists.contains(name)) note.copy(lists = note.lists - name) else note
        }.filterValues { !it.isEmpty }
        _notes.value = updated
        saveNotes()
    }

    fun toggleList(address: String, list: String) {
        val key = address.uppercase()
        mutateNote(key) {
            if (it.lists.contains(list)) {
                it.copy(lists = it.lists - list)
            } else {
                it.copy(lists = it.lists + list)
            }
        }
    }

    /**
     * Carries a device's name and lists over to the address it has just put on.
     *
     * Used by [Follower] when a followed device rotates. Refuses when the destination
     * already has a note of its own: overwriting one would mean two devices had been
     * confused for each other, and the quiet loss of whatever the user had written about
     * the second one is exactly the kind of damage following must never do.
     *
     * @return true if the note moved.
     */
    fun move(fromAddress: String, toAddress: String): Boolean {
        val from = fromAddress.uppercase()
        val to = toAddress.uppercase()
        if (from == to) return false
        val note = _notes.value[from] ?: return false
        if (_notes.value[to] != null) return false
        _notes.value = _notes.value - from + (to to note.copy(address = to))
        saveNotes()
        return true
    }

    fun clearNote(address: String) {
        val key = address.uppercase()
        _notes.value = _notes.value - key
        saveNotes()
    }

    private fun mutateNote(key: String, block: (DeviceNote) -> DeviceNote) {
        val existing = _notes.value[key] ?: DeviceNote(key)
        val updated = block(existing)
        _notes.value = if (updated.isEmpty) {
            _notes.value - key
        } else {
            _notes.value + (key to updated)
        }
        saveNotes()
    }

    // ------------------------------------------------------------ persistence

    private fun loadLists(): List<String> {
        val raw = prefs.getString(KEY_LISTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun saveLists() {
        val array = JSONArray()
        _lists.value.forEach { array.put(it) }
        prefs.edit().putString(KEY_LISTS, array.toString()).apply()
    }

    private fun loadNotes(): Map<String, DeviceNote> {
        val raw = prefs.getString(KEY_NOTES, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            root.keys().asSequence().associateWith { key ->
                val obj = root.getJSONObject(key)
                val listsArray = obj.optJSONArray("lists") ?: JSONArray()
                DeviceNote(
                    address = key,
                    nickname = obj.optString("nickname").takeIf { it.isNotEmpty() },
                    lists = (0 until listsArray.length()).map { listsArray.getString(it) }.toSet(),
                )
            }
        }.getOrDefault(emptyMap())
    }

    private fun saveNotes() {
        val root = JSONObject()
        _notes.value.forEach { (address, note) ->
            val obj = JSONObject()
            note.nickname?.let { obj.put("nickname", it) }
            if (note.lists.isNotEmpty()) {
                val array = JSONArray()
                note.lists.forEach { array.put(it) }
                obj.put("lists", array)
            }
            root.put(address, obj)
        }
        prefs.edit().putString(KEY_NOTES, root.toString()).apply()
    }

    companion object {
        private const val KEY_NOTES = "notes"
        private const val KEY_LISTS = "lists"

        @Volatile
        private var instance: DeviceBook? = null

        fun get(context: Context): DeviceBook =
            instance ?: synchronized(this) {
                instance ?: DeviceBook(context).also { instance = it }
            }
    }
}
