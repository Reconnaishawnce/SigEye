package com.sigeye.core.analysis.record

import com.sigeye.core.analysis.presence.Sighting

/** Everything heard in one place at one time. */
data class Snapshot(
    val label: String,
    val takenAtMs: Long,
    val devices: List<Sighting>,
) {
    val size: Int get() = devices.size

    /** Only the fixed addresses. The randomized ones cannot be compared across time. */
    fun comparable(): List<Sighting> = devices.filter { !it.isRandom }

    val randomCount: Int get() = devices.count { it.isRandom }
}

/** One device's fate between two snapshots. */
data class SnapshotChange(
    val sighting: Sighting,
    val wasRssi: Int? = null,
    val nowRssi: Int? = null,
) {
    /** Positive means it got stronger, which usually means closer. */
    val deltaDb: Int? get() = if (wasRssi != null && nowRssi != null) nowRssi - wasRssi else null
}

data class SnapshotDiff(
    val before: Snapshot,
    val after: Snapshot,
    val arrived: List<SnapshotChange>,
    val departed: List<SnapshotChange>,
    val stayed: List<SnapshotChange>,
    /** Randomized addresses in either snapshot, which no comparison can track. */
    val untrackableBefore: Int,
    val untrackableAfter: Int,
) {
    val movedCloser: List<SnapshotChange>
        get() = stayed.filter { (it.deltaDb ?: 0) >= MEANINGFUL_SHIFT_DB }
            .sortedByDescending { it.deltaDb }

    val movedAway: List<SnapshotChange>
        get() = stayed.filter { (it.deltaDb ?: 0) <= -MEANINGFUL_SHIFT_DB }
            .sortedBy { it.deltaDb }

    /**
     * A plain-language summary, including the part people forget.
     *
     * The randomized count is in here on purpose. A comparison of two scans of the same
     * room will always show a pile of "arrivals" and "departures" that are nothing of the
     * sort - they are the same phones wearing different addresses - and a diff that does
     * not say so reads as an intruder alert every single time.
     */
    fun summary(): String {
        val parts = mutableListOf<String>()
        parts.add("${arrived.size} new, ${departed.size} gone, ${stayed.size} unchanged")
        if (untrackableBefore + untrackableAfter > 0) {
            parts.add(
                "${untrackableBefore + untrackableAfter} randomized addresses were left " +
                    "out - they change on their own and cannot be followed between scans",
            )
        }
        return parts.joinToString(". ") + "."
    }

    companion object {
        /**
         * Below this, a change in signal is just the room.
         *
         * Multipath alone moves a stationary link by several dB - the Multipath Fading
         * experiment exists to show exactly that - so anything smaller is not evidence of
         * something having moved.
         */
        const val MEANINGFUL_SHIFT_DB = 8
    }
}

/**
 * Comparing one place against itself, later.
 *
 * The at-home use: take a snapshot of a room you trust, take another next week, and ask
 * what changed. Only fixed addresses can be compared - a randomized address is a different
 * address every quarter of an hour, so matching on it would report a houseful of intruders
 * every time - which means the comparison is honest about being partial rather than
 * quietly wrong.
 */
object Snapshots {

    fun diff(before: Snapshot, after: Snapshot): SnapshotDiff {
        val beforeByAddress = before.comparable().associateBy { it.address }
        val afterByAddress = after.comparable().associateBy { it.address }

        val arrived = afterByAddress.filterKeys { it !in beforeByAddress }.values
            .map { SnapshotChange(it, wasRssi = null, nowRssi = it.rssi) }
            .sortedByDescending { it.sighting.rssi }

        val departed = beforeByAddress.filterKeys { it !in afterByAddress }.values
            .map { SnapshotChange(it, wasRssi = it.rssi, nowRssi = null) }
            .sortedByDescending { it.sighting.rssi }

        val stayed = afterByAddress.filterKeys { it in beforeByAddress }.values
            .map {
                SnapshotChange(
                    sighting = it,
                    wasRssi = beforeByAddress.getValue(it.address).rssi,
                    nowRssi = it.rssi,
                )
            }
            .sortedByDescending { it.sighting.rssi }

        return SnapshotDiff(
            before = before,
            after = after,
            arrived = arrived,
            departed = departed,
            stayed = stayed,
            untrackableBefore = before.randomCount,
            untrackableAfter = after.randomCount,
        )
    }

    /**
     * Devices present in every one of several snapshots.
     *
     * The counter-surveillance question in its simplest form: take a snapshot at home, at
     * work, and at a cafe, and anything fixed that appears in all three was traveling
     * with you rather than living in any of those places.
     */
    fun commonTo(snapshots: List<Snapshot>): List<Sighting> {
        if (snapshots.size < 2) return emptyList()
        val sets = snapshots.map { snapshot ->
            snapshot.comparable().associateBy { it.address }
        }
        val shared = sets.map { it.keys }.reduce { a, b -> a intersect b }
        return shared.mapNotNull { address -> sets.last()[address] }
            .sortedByDescending { it.rssi }
    }
}
