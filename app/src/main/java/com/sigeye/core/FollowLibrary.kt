package com.sigeye.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One device a follow was still considering when it finished. */
data class FollowLead(
    val address: String,
    val label: String?,
    val vendor: String?,
    val evidence: String,
)

/**
 * A follow that happened, kept so it can be looked at again.
 *
 * A follow is twenty minutes of walking about, and until now it evaporated the moment the
 * screen closed. That is wrong twice over: it is the longest single thing anybody does in
 * this app, and it is the one whose result somebody might want to check against a second
 * attempt in the same place a week later.
 *
 * What is kept is the conclusion and the conditions, not the packets. Which tests were run,
 * how many devices it started from, what it came down to. A capture is what Settings is
 * for, and keeping one per follow would fill a phone.
 */
data class SavedFollow(
    val id: String,
    val name: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    /** Everything heard at any point. The denominator. */
    val watched: Int,
    /** What the tests came down to. */
    val leads: List<FollowLead>,
    /** Which tests were run, in order, in words. */
    val tests: List<String>,
) {
    val durationMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0L)

    fun stamp(): String = SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(startedAtMs))

    fun summary(): String = when {
        leads.isEmpty() -> "$watched in range, nothing narrowed"
        leads.size == 1 -> "one of $watched"
        else -> "${leads.size} of $watched"
    }
}

/**
 * Past follows, newest first.
 *
 * One file rather than one per follow: a follow record is a few hundred bytes because it
 * keeps conclusions rather than packets, and the interesting operation is reading the list.
 */
class FollowLibrary private constructor(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE)

    private val _follows = MutableStateFlow(load())
    val follows: StateFlow<List<SavedFollow>> = _follows

    fun save(follow: SavedFollow) {
        _follows.value = (listOf(follow) + _follows.value.filterNot { it.id == follow.id })
            .sortedByDescending { it.startedAtMs }
            .take(MAX)
        write()
    }

    fun delete(id: String) {
        _follows.value = _follows.value.filterNot { it.id == id }
        write()
    }

    fun clear() {
        _follows.value = emptyList()
        write()
    }

    private fun write() {
        runCatching {
            val array = JSONArray()
            _follows.value.forEach { follow ->
                val leads = JSONArray()
                follow.leads.forEach { lead ->
                    leads.put(
                        JSONObject()
                            .put("address", lead.address)
                            .put("label", lead.label ?: JSONObject.NULL)
                            .put("vendor", lead.vendor ?: JSONObject.NULL)
                            .put("evidence", lead.evidence),
                    )
                }
                array.put(
                    JSONObject()
                        .put("id", follow.id)
                        .put("name", follow.name)
                        .put("started", follow.startedAtMs)
                        .put("ended", follow.endedAtMs)
                        .put("watched", follow.watched)
                        .put("leads", leads)
                        .put("tests", JSONArray(follow.tests)),
                )
            }
            file.writeText(array.toString())
        }
    }

    private fun load(): List<SavedFollow> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            val leads = json.optJSONArray("leads") ?: JSONArray()
            val tests = json.optJSONArray("tests") ?: JSONArray()
            SavedFollow(
                id = json.optString("id"),
                name = json.optString("name", "Unnamed"),
                startedAtMs = json.optLong("started"),
                endedAtMs = json.optLong("ended"),
                watched = json.optInt("watched"),
                leads = (0 until leads.length()).map { at ->
                    val lead = leads.getJSONObject(at)
                    FollowLead(
                        address = lead.optString("address"),
                        label = lead.optString("label").takeIf { it.isNotBlank() && it != "null" },
                        vendor = lead.optString("vendor").takeIf { it.isNotBlank() && it != "null" },
                        evidence = lead.optString("evidence"),
                    )
                },
                tests = (0 until tests.length()).map { at -> tests.getString(at) },
            )
        }.sortedByDescending { it.startedAtMs }
    }.getOrDefault(emptyList())

    companion object {
        /** Enough to see whether the same place behaves the same way twice. */
        const val MAX = 30

        private const val FILE = "follows.json"

        @Volatile
        private var instance: FollowLibrary? = null

        fun get(context: Context): FollowLibrary = instance ?: synchronized(this) {
            instance ?: FollowLibrary(context).also { instance = it }
        }
    }
}
