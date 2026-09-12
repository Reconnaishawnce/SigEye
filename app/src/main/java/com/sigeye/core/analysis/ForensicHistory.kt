package com.sigeye.core.analysis

import java.util.Locale

/** A device as it was in a recording that has been saved. */
data class SavedDevice(
    val address: String,
    val label: String,
    val vendor: String? = null,
    val isRandom: Boolean = false,
    val packets: Int = 0,
    val peakRssi: Int = -127,
    val behaviour: String = "",
)

/** A recording kept for later comparison. */
data class SavedSession(
    val label: String,
    val recordedAtMs: Long,
    val spanMs: Long,
    val devices: List<SavedDevice>,
) {
    val size: Int get() = devices.size
    val fixedCount: Int get() = devices.count { !it.isRandom }

    fun contains(address: String): Boolean =
        devices.any { it.address.equals(address, ignoreCase = true) }

    fun device(address: String): SavedDevice? =
        devices.firstOrNull { it.address.equals(address, ignoreCase = true) }
}

/** One previous encounter with a device. */
data class Encounter(
    val where: String,
    val atMs: Long,
    val detail: String,
)

/** What is known about a device from everything the app has stored. */
data class Provenance(
    val address: String,
    val encounters: List<Encounter>,
    val nickname: String? = null,
    val lists: Set<String> = emptySet(),
    val watched: Boolean = false,
) {
    val seenBefore: Boolean get() = encounters.isNotEmpty()

    val familiar: Boolean
        get() = seenBefore || !nickname.isNullOrBlank() || lists.isNotEmpty() || watched

    /**
     * One line for the row, in the order it changes what you would do.
     *
     * Being on the watchlist outranks everything, then having been named, then simply
     * having been met before. "Never seen before" is last because on a street it is the
     * overwhelming majority and says nothing on its own.
     */
    fun headline(): String = when {
        watched -> "On your watchlist"
        !nickname.isNullOrBlank() -> "You named this \"$nickname\""
        lists.isNotEmpty() -> "On your list: " + lists.joinToString(", ")
        encounters.size == 1 -> "Seen once before, in ${encounters.first().where}"
        encounters.size > 1 -> "Seen in ${encounters.size} earlier recordings"
        else -> "Never seen before"
    }
}

/** New, gone, and still here between two recordings. */
data class SessionDiff(
    val before: SavedSession,
    val onlyBefore: List<SavedDevice>,
    val onlyAfter: List<Track>,
    val inBoth: List<Track>,
    val randomBefore: Int,
    val randomAfter: Int,
) {
    fun summary(): String = buildString {
        append(onlyAfter.size).append(" new, ")
        append(onlyBefore.size).append(" gone, ")
        append(inBoth.size).append(" in both")
        if (randomBefore + randomAfter > 0) {
            append(". ").append(randomBefore + randomAfter)
                .append(" randomised addresses were left out - they change on their own ")
                .append("and cannot be matched between recordings")
        }
        append('.')
    }
}

/**
 * What the app already knew about a device before this recording.
 *
 * The question a forensic review keeps asking is "have I met you before", and the answer
 * lives in four different places: earlier recordings, saved place snapshots, whatever the
 * user has named or filed, and the watchlist. Gathering them in one place is the difference
 * between a list of addresses and a list of leads.
 *
 * Pure and Android-free.
 */
object ForensicHistory {

    fun provenance(
        address: String,
        sessions: List<SavedSession>,
        snapshots: List<Snapshot>,
        nickname: String? = null,
        lists: Set<String> = emptySet(),
        watched: Boolean = false,
    ): Provenance {
        val key = address.uppercase(Locale.US)
        val encounters = mutableListOf<Encounter>()

        sessions.forEach { session ->
            session.device(key)?.let { device ->
                encounters.add(
                    Encounter(
                        where = session.label,
                        atMs = session.recordedAtMs,
                        detail = "${device.packets} packets, peak ${device.peakRssi} dBm",
                    ),
                )
            }
        }

        snapshots.forEach { snapshot ->
            snapshot.devices.firstOrNull { it.address.equals(key, ignoreCase = true) }
                ?.let { sighting ->
                    encounters.add(
                        Encounter(
                            where = snapshot.label + " (snapshot)",
                            atMs = snapshot.takenAtMs,
                            detail = "${sighting.sightings} sightings, ${sighting.rssi} dBm",
                        ),
                    )
                }
        }

        return Provenance(
            address = key,
            encounters = encounters.sortedByDescending { it.atMs },
            nickname = nickname,
            lists = lists,
            watched = watched,
        )
    }

    /**
     * Compares a live recording against one that was saved.
     *
     * Randomised addresses are excluded from the matching and counted separately, exactly
     * as the snapshot comparison does - a recording of the same street an hour apart would
     * otherwise report every phone in it as both departed and newly arrived.
     */
    fun diff(before: SavedSession, after: List<Track>): SessionDiff {
        val beforeFixed = before.devices.filter { !it.isRandom }
        val afterFixed = after.filter { !it.isRandom }
        val beforeKeys = beforeFixed.map { it.address.uppercase(Locale.US) }.toSet()
        val afterKeys = afterFixed.map { it.address.uppercase(Locale.US) }.toSet()

        return SessionDiff(
            before = before,
            onlyBefore = beforeFixed
                .filter { it.address.uppercase(Locale.US) !in afterKeys }
                .sortedByDescending { it.peakRssi },
            onlyAfter = afterFixed
                .filter { it.address.uppercase(Locale.US) !in beforeKeys }
                .sortedByDescending { it.peakRssi },
            inBoth = afterFixed
                .filter { it.address.uppercase(Locale.US) in beforeKeys }
                .sortedByDescending { it.peakRssi },
            randomBefore = before.devices.count { it.isRandom },
            randomAfter = after.count { it.isRandom },
        )
    }

    /** Turns a finished recording into something worth keeping. */
    fun save(label: String, atMs: Long, spanMs: Long, tracks: List<Track>): SavedSession =
        SavedSession(
            label = label,
            recordedAtMs = atMs,
            spanMs = spanMs,
            devices = tracks.map { track ->
                SavedDevice(
                    address = track.address,
                    label = track.label,
                    vendor = track.vendor,
                    isRandom = track.isRandom,
                    packets = track.packets,
                    peakRssi = track.peakRssi,
                    behaviour = track.behaviour.name,
                )
            },
        )
}
