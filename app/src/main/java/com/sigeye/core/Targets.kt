package com.sigeye.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A device a follow ended on, and the evidence it ended on.
 *
 * The evidence is stored with it and never regenerated, because it is a record of what was
 * true at the moment somebody decided. A target that says "survived 3 legs including a mile
 * of walking, passed the circle" is a claim that can be argued with later; one that says
 * only "AA:BB:CC:DD:EE:FF" is an assertion about a stranger's phone with nothing behind it,
 * and this app does not make those.
 */
data class TargetDevice(
    val address: String,
    val label: String?,
    val vendor: String?,
    /** What the follow had on it when it was promoted, in words. */
    val evidence: String,
    /** Which follow produced it. */
    val fromFollow: String,
    val addedAtMs: Long,
    /** Every address it has been seen wearing, oldest first. */
    val addresses: List<String> = listOf(address),
    /**
     * When each of those changes happened.
     *
     * Kept because it is the only way to say when the next one is due. Two changes give a
     * measured rhythm for this particular device, which beats the specification default by
     * a distance - the default is what most stacks inherit, not what any given phone does.
     */
    val changesAtMs: List<Long> = emptyList(),
    /** Updated as it is heard again, so a lost target can say how long it has been gone. */
    val lastSeenMs: Long = addedAtMs,
) {
    val name: String get() = label ?: vendor ?: address

    val rotations: Int get() = (addresses.size - 1).coerceAtLeast(0)

    fun added(): String = SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(addedAtMs))
}

/**
 * The devices a follow has settled on, kept between sessions.
 *
 * Deliberately also writes each one into [DeviceBook] under a list of its own. Everything
 * else in this app already works on device-book lists - Signal Watch alerts on them,
 * Persistent Tracking follows them across address changes, the pickers sort by them - so a
 * target that is a device-book entry is immediately usable by every experiment here without
 * a line of new plumbing. A parallel list that only Follow Me knew about would be a target
 * you could not do anything with.
 *
 * Removing a target takes it off the list again. It does not delete the nickname: somebody
 * who named a device meant it, and a store that quietly unpicks a person's own labelling is
 * worse than one that leaves a stray name behind.
 */
class TargetStore private constructor(context: Context) {

    private val app = context.applicationContext
    private val book = DeviceBook.get(app)

    private val file = File(app.filesDir, FILE)

    private val _targets = MutableStateFlow(load())

    /** Newest first. */
    val targets: StateFlow<List<TargetDevice>> = _targets

    fun add(target: TargetDevice) {
        book.createList(LIST)
        if (!book.listsOf(target.address).contains(LIST)) {
            book.toggleList(target.address, LIST)
        }
        target.label?.takeIf { it.isNotBlank() }?.let { book.setNickname(target.address, it) }

        _targets.value = (listOf(target) + _targets.value.filterNot { it.address == target.address })
            .take(MAX)
        save()
    }

    fun remove(address: String) {
        if (book.listsOf(address).contains(LIST)) book.toggleList(address, LIST)
        _targets.value = _targets.value.filterNot { it.address.equals(address, ignoreCase = true) }
        save()
    }

    /**
     * Moves a target onto the address it has just put on.
     *
     * The old address is kept in the trail rather than replaced, because the trail is the
     * finding: a device followed across three rotations is a much stronger statement than
     * one seen once, and throwing the history away would erase it.
     */
    fun reacquire(oldAddress: String, newAddress: String, atMs: Long) {
        // The pin follows the device too. Keeping them in step here rather than at every
        // call site means a screen cannot move one and forget the other, which would leave
        // the bar pointing at an address nothing is going to say again.
        CurrentTarget.get(app).reacquire(oldAddress, newAddress)
        val existing = _targets.value.firstOrNull {
            it.address.equals(oldAddress, ignoreCase = true)
        } ?: return
        remove(existing.address)
        add(
            existing.copy(
                address = newAddress.uppercase(Locale.US),
                addresses = existing.addresses + newAddress.uppercase(Locale.US),
                changesAtMs = existing.changesAtMs + atMs,
                lastSeenMs = atMs,
            ),
        )
    }

    fun heard(address: String, atMs: Long) {
        var changed = false
        _targets.value = _targets.value.map { target ->
            if (target.address.equals(address, ignoreCase = true)) {
                changed = true
                target.copy(lastSeenMs = atMs)
            } else {
                target
            }
        }
        if (changed) save()
    }

    fun clear() {
        _targets.value.forEach { remove(it.address) }
    }

    private fun save() {
        runCatching {
            val array = JSONArray()
            _targets.value.forEach { target ->
                array.put(
                    JSONObject()
                        .put("address", target.address)
                        .put("label", target.label ?: JSONObject.NULL)
                        .put("vendor", target.vendor ?: JSONObject.NULL)
                        .put("evidence", target.evidence)
                        .put("from", target.fromFollow)
                        .put("added", target.addedAtMs)
                        .put("last", target.lastSeenMs)
                        .put("addresses", JSONArray(target.addresses))
                        .put("changes", JSONArray(target.changesAtMs)),
                )
            }
            file.writeText(array.toString())
        }
    }

    private fun load(): List<TargetDevice> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).map { index ->
            val json = array.getJSONObject(index)
            val worn = json.optJSONArray("addresses") ?: JSONArray()
            TargetDevice(
                address = json.optString("address"),
                label = json.optString("label").takeIf { it.isNotBlank() && it != "null" },
                vendor = json.optString("vendor").takeIf { it.isNotBlank() && it != "null" },
                evidence = json.optString("evidence"),
                fromFollow = json.optString("from"),
                addedAtMs = json.optLong("added"),
                addresses = (0 until worn.length()).map { worn.getString(it) }
                    .ifEmpty { listOf(json.optString("address")) },
                lastSeenMs = json.optLong("last", json.optLong("added")),
                changesAtMs = (json.optJSONArray("changes") ?: JSONArray()).let { changes ->
                    (0 until changes.length()).map { changes.getLong(it) }
                },
            )
        }
    }.getOrDefault(emptyList())

    companion object {
        /** The device-book list every target is also written into. */
        const val LIST = "Targets"

        /**
         * More than a handful is not a follow any more, it is a watchlist.
         *
         * Signal Watch already exists and is the right tool for keeping an eye on twenty
         * things. This is for the two or three a follow actually ended on.
         */
        const val MAX = 10

        private const val FILE = "targets.json"

        @Volatile
        private var instance: TargetStore? = null

        fun get(context: Context): TargetStore = instance ?: synchronized(this) {
            instance ?: TargetStore(context).also { instance = it }
        }
    }
}
