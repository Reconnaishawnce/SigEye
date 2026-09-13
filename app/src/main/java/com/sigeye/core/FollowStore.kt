package com.sigeye.core

import android.content.Context
import com.sigeye.core.analysis.identity.Handover
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which lists are followed across address changes, and every move that has been made.
 *
 * The log is the important half. Following rewrites what the rest of the app believes
 * about who is who, so there has to be a record of every rewrite that the user can read
 * and reverse - otherwise a single wrong re-acquisition becomes an unfalsifiable fact
 * about a stranger's phone.
 */
class FollowStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("following", Context.MODE_PRIVATE)

    private val _lists = MutableStateFlow(loadLists())

    /** Names of the [DeviceBook] lists whose devices are followed. */
    val lists: StateFlow<Set<String>> = _lists

    private val _handovers = MutableStateFlow(loadHandovers())

    /** Newest first. */
    val handovers: StateFlow<List<Handover>> = _handovers

    fun isFollowed(list: String): Boolean = _lists.value.contains(list)

    fun toggle(list: String) {
        _lists.value = if (_lists.value.contains(list)) {
            _lists.value - list
        } else {
            _lists.value + list
        }
        prefs.edit().putStringSet(KEY_LISTS, _lists.value).apply()
    }

    fun record(handover: Handover) {
        _handovers.value = (listOf(handover) + _handovers.value).take(MAX_HANDOVERS)
        saveHandovers()
    }

    /** Drops a record after its move has been undone, so the log stays true. */
    fun forget(id: String) {
        _handovers.value = _handovers.value.filterNot { it.id == id }
        saveHandovers()
    }

    fun clearLog() {
        _handovers.value = emptyList()
        saveHandovers()
    }

    private fun loadLists(): Set<String> =
        prefs.getStringSet(KEY_LISTS, emptySet())?.toSet() ?: emptySet()

    private fun loadHandovers(): List<Handover> {
        val raw = prefs.getString(KEY_HANDOVERS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                val reasons = obj.optJSONArray("reasons") ?: JSONArray()
                Handover(
                    atMs = obj.getLong("at"),
                    label = obj.getString("label"),
                    fromAddress = obj.getString("from"),
                    toAddress = obj.getString("to"),
                    points = obj.optInt("points"),
                    reasons = (0 until reasons.length()).map { reasons.getString(it) },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveHandovers() {
        val array = JSONArray()
        _handovers.value.forEach { handover ->
            array.put(
                JSONObject().apply {
                    put("at", handover.atMs)
                    put("label", handover.label)
                    put("from", handover.fromAddress)
                    put("to", handover.toAddress)
                    put("points", handover.points)
                    put("reasons", JSONArray().apply { handover.reasons.forEach { put(it) } })
                },
            )
        }
        prefs.edit().putString(KEY_HANDOVERS, array.toString()).apply()
    }

    companion object {
        private const val KEY_LISTS = "lists"
        private const val KEY_HANDOVERS = "handovers"

        /** Enough to audit a day out, not so many that the screen becomes a database. */
        private const val MAX_HANDOVERS = 100

        @Volatile
        private var instance: FollowStore? = null

        fun get(context: Context): FollowStore =
            instance ?: synchronized(this) {
                instance ?: FollowStore(context).also { instance = it }
            }
    }
}
