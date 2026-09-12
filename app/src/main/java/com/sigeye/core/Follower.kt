package com.sigeye.core

import com.sigeye.core.analysis.FollowDecision
import com.sigeye.core.analysis.FollowRefusal
import com.sigeye.core.analysis.Following
import com.sigeye.core.analysis.Identity
import com.sigeye.core.analysis.LiveAddress
import com.sigeye.core.ble.Advert
import com.sigeye.core.ble.shape
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** One device being followed, as the screen needs to show it. */
data class Subject(
    val label: String,
    val address: String,
    val lists: Set<String>,
    val audible: Boolean,
    val lastSeenMs: Long,
    val packets: Int,
    val rotations: Int,
    /** Why it is not being followed right now, when that is the interesting part. */
    val note: String?,
)

data class FollowState(
    val subjects: List<Subject> = emptyList(),
    val watching: Int = 0,
    val addressesInPlay: Int = 0,
    val enabled: Boolean = false,
)

/**
 * Keeps the user's names attached to devices that change their address.
 *
 * A passenger, deliberately. It never turns the radio on - it listens to whatever is
 * already scanning, whether that is an experiment screen or a background recording - so
 * enabling it costs nothing until something else is looking anyway. When a followed device
 * rotates, its nickname and its lists move across in [DeviceBook], which is the one place
 * every other screen reads from, so Signal Watch, Discovery, Forensics and Travelling
 * Companions all keep the subject without knowing this class exists.
 *
 * The decision itself is [Following]'s, and it is written to refuse rather than guess. This
 * only supplies the observations and carries out what it is told.
 */
class Follower(
    private val book: DeviceBook,
    private val store: FollowStore,
) {

    private val live = LinkedHashMap<String, LiveAddress>()
    private val rotations = LinkedHashMap<String, Int>()
    private val notes = LinkedHashMap<String, String>()

    private val _state = MutableStateFlow(FollowState())
    val state: StateFlow<FollowState> = _state

    fun onAdvert(advert: Advert) {
        val key = advert.address.uppercase(Locale.US)
        val shape = advert.shape()
        val entry = live.getOrPut(key) {
            LiveAddress(shape, advert.isRandomAddress, advert.atMs, advert.atMs)
        }
        if (shape.distinctiveness > entry.shape.distinctiveness) entry.shape = shape
        entry.observe(advert.rssi, advert.atMs)
    }

    /**
     * Looks at every followed device that has gone quiet, and moves the ones it is sure of.
     *
     * Candidates are every address currently audible that nobody has named. A device the
     * user has already named is never adopted as somebody else's successor - it is
     * evidently a different thing, or the user would not have two names for it.
     */
    fun tick(nowMs: Long) {
        val followedLists = store.lists.value
        val allNotes = book.notes.value

        val targets = allNotes.values.filter { note ->
            note.lists.any { it in followedLists }
        }

        if (targets.isNotEmpty()) {
            val named = allNotes.keys
            val candidates: List<Identity> = live
                .filterKeys { it !in named }
                .filterValues { nowMs - it.lastSeenMs <= AUDIBLE_MS && it.packets >= MIN_PACKETS }
                .map { (address, entry) -> entry.identity(address) }

            targets.forEach { note ->
                val entry = live[note.address] ?: return@forEach
                if (entry.packets < MIN_PACKETS) return@forEach
                val previous = entry.identity(note.address)
                when (val decision = Following.decide(previous, candidates, nowMs)) {
                    is FollowDecision.Reacquired -> apply(note.address, decision, nowMs)
                    is FollowDecision.Refused -> remember(note.address, decision)
                }
            }
        }

        prune(nowMs)
        publish(nowMs, followedLists)
    }

    private fun apply(from: String, decision: FollowDecision.Reacquired, nowMs: Long) {
        val label = book.nicknameOf(from) ?: from
        if (!book.move(from, decision.address)) return
        val handover = Following.record(label, from, decision, nowMs)
        store.record(handover)
        rotations[decision.address] = (rotations.remove(from) ?: 0) + 1
        notes.remove(from)
        notes[decision.address] =
            "Followed here from $from on ${handover.points} points of evidence."
    }

    private fun remember(address: String, decision: FollowDecision.Refused) {
        // A device that is simply still talking, or has nothing matching yet, is the
        // normal case and does not need saying. The rest are the interesting part.
        if (decision.refusal == FollowRefusal.STILL_TALKING ||
            decision.refusal == FollowRefusal.NO_CANDIDATE
        ) {
            notes.remove(address)
            return
        }
        notes[address] = decision.message
    }

    /** Undoes one move, putting the name back where it was. */
    fun undo(handoverId: String): Boolean {
        val handover = store.handovers.value.firstOrNull { it.id == handoverId } ?: return false
        if (!book.move(handover.toAddress, handover.fromAddress)) return false
        store.forget(handoverId)
        rotations[handover.fromAddress] = (rotations.remove(handover.toAddress) ?: 1) - 1
        return true
    }

    private fun prune(nowMs: Long) {
        val stale = live.filterValues { nowMs - it.lastSeenMs > FORGET_MS }.keys.toList()
        stale.forEach { live.remove(it) }
    }

    private fun publish(nowMs: Long, followedLists: Set<String>) {
        val subjects = book.notes.value.values
            .filter { note -> note.lists.any { it in followedLists } }
            .map { note ->
                val entry = live[note.address]
                Subject(
                    label = note.nickname?.takeIf { it.isNotBlank() } ?: note.address,
                    address = note.address,
                    lists = note.lists.filter { it in followedLists }.toSet(),
                    audible = entry != null && nowMs - entry.lastSeenMs <= AUDIBLE_MS,
                    lastSeenMs = entry?.lastSeenMs ?: 0L,
                    packets = entry?.packets ?: 0,
                    rotations = rotations[note.address] ?: 0,
                    note = notes[note.address]
                        ?: if (entry == null) "Not in range." else null,
                )
            }
            .sortedBy { it.label.lowercase(Locale.US) }

        _state.value = FollowState(
            subjects = subjects,
            watching = subjects.size,
            addressesInPlay = live.count { nowMs - it.value.lastSeenMs <= AUDIBLE_MS },
            enabled = followedLists.isNotEmpty(),
        )
    }

    private companion object {
        /** Heard from this recently and it counts as present. */
        const val AUDIBLE_MS = 15_000L

        /** Dropped from memory after this, so a long session does not grow without bound. */
        const val FORGET_MS = 15 * 60_000L

        /** Packets needed before an address has an interval worth comparing. */
        const val MIN_PACKETS = 8
    }
}
