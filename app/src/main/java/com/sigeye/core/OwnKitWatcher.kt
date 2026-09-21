package com.sigeye.core

import com.sigeye.core.analysis.identity.Adoption
import com.sigeye.core.analysis.identity.AdvertShape
import com.sigeye.core.analysis.identity.Handoffs
import com.sigeye.core.analysis.identity.Identity
import com.sigeye.core.analysis.identity.NotAdopted
import com.sigeye.core.analysis.identity.OwnKit
import com.sigeye.core.ble.Advert
import com.sigeye.core.ble.shape

/**
 * Watching the devices you marked as yours, so the mark survives their address changing.
 *
 * A passenger, like the follower is. It holds no claim on the radio and does nothing at all
 * while the whitelist is empty, so subscribing for the life of the process costs nothing.
 *
 * Every entry point is synchronized. This is written from the scan service's thread and
 * read from the main thread by a screen, and cross-thread mutation of a process-scoped
 * recorder is a bug this project has already had more than once.
 *
 * See [OwnKit] for why the bar for adopting is set so much higher than a follow's.
 */
class OwnKitWatcher(private val mine: MyDevices) {

    /** What is known about one address while it is being listened to. */
    private class Trail(val address: String) {
        var shape: AdvertShape? = null
        var isRandom = false
        var firstSeenMs = 0L
        var lastSeenMs = 0L
        var packets = 0
        var bestRssi = -127
        var recentRssi = -127.0

        /** Arrival time and level, for classifying how it ended. Bounded; this runs for hours. */
        val recent = ArrayDeque<Pair<Long, Int>>()

        /** Gaps between arrivals, for the advertising interval. Bounded for the same reason. */
        val gaps = ArrayDeque<Long>()

        fun observe(advert: Advert) {
            if (firstSeenMs == 0L) firstSeenMs = advert.atMs
            if (lastSeenMs > 0L) {
                val gap = advert.atMs - lastSeenMs
                if (gap in 1..MAX_GAP_MS) {
                    gaps.addLast(gap)
                    while (gaps.size > KEEP) gaps.removeFirst()
                }
            }
            shape = advert.shape()
            isRandom = advert.isRandomAddress
            lastSeenMs = advert.atMs
            packets++
            bestRssi = maxOf(bestRssi, advert.rssi)
            recentRssi = advert.rssi.toDouble()
            recent.addLast(advert.atMs to advert.rssi)
            while (recent.size > KEEP) recent.removeFirst()
        }

        fun identity() = Identity(
            address = address,
            shape = shape ?: AdvertShape(),
            isRandom = isRandom,
            firstSeenMs = firstSeenMs,
            lastSeenMs = lastSeenMs,
            packets = packets,
            medianGapMs = median(gaps),
            recentRssi = recentRssi,
            bestRssi = bestRssi,
        )

        private fun median(values: Collection<Long>): Long {
            if (values.isEmpty()) return 0L
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }
    }

    private val trails = mutableMapOf<String, Trail>()

    private val _adoptions = mutableListOf<Adoption>()
    private val _declined = mutableMapOf<String, NotAdopted>()

    /** Everything adopted this run, newest first, so the screen can show and undo it. */
    @Synchronized
    fun adoptions(): List<Adoption> = _adoptions.reversed()

    /** Address changes that were looked at and not taken, with the reason. */
    @Synchronized
    fun declined(): List<NotAdopted> = _declined.values.toList()

    @Synchronized
    fun onAdvert(advert: Advert) {
        val key = advert.address.uppercase()

        // Everything loud is kept, not only what is already whitelisted. A successor has
        // to have been heard before it can be recognized as one, and by the time the old
        // address goes quiet it is too late to start listening to its replacement.
        if (!mine.isMine(key) && advert.rssi < OwnKit.ON_PERSON_DBM) return

        trails.getOrPut(key) { Trail(key) }.observe(advert)
        if (trails.size > MAX_TRAILS) prune(advert.atMs)
    }

    /**
     * Looks for whitelisted devices that have gone quiet, and for where they went.
     *
     * Called on a timer rather than per packet, because the question is about an absence
     * and an absence does not arrive.
     */
    @Synchronized
    fun tick(nowMs: Long) {
        if (mine.size() == 0 || trails.isEmpty()) return

        val silent = trails.values.filter { trail ->
            mine.isMine(trail.address) &&
                trail.packets >= Handoffs.MIN_TRAIL &&
                nowMs - trail.lastSeenMs >= Handoffs.SILENCE_MS &&
                nowMs - trail.lastSeenMs <= Handoffs.GIVE_UP_MS
        }
        if (silent.isEmpty()) return

        // Addresses that turned up around the time something went quiet, and are not
        // already spoken for.
        val arrivals = trails.values
            .filter { trail ->
                !mine.isMine(trail.address) &&
                    trail.isRandom &&
                    trail.packets >= Handoffs.MIN_TRAIL &&
                    nowMs - trail.lastSeenMs <= Handoffs.SILENCE_MS
            }
            .map { it.identity() }

        silent.forEach { trail ->
            val previous = trail.identity()
            val departure = Handoffs.classify(trail.address, trail.recent.toList(), trail.bestRssi)
            val label = mine.devices.value.firstOrNull { it.address == trail.address }?.label
                ?: trail.address

            val (adopted, refused) = OwnKit.consider(
                previous = previous,
                departure = departure,
                candidates = arrivals.filterNot { taken.contains(it.address) },
                label = label,
                nowMs = nowMs,
            )

            if (adopted != null) {
                taken.add(adopted.toAddress)
                mine.replace(adopted.fromAddress, adopted.toAddress, adopted.label)
                _adoptions.add(adopted)
                while (_adoptions.size > MAX_LOG) _adoptions.removeAt(0)
                _declined.remove(trail.address)
                trails.remove(trail.address)
            } else if (refused != null) {
                _declined[trail.address] = refused
                // Said once. Leaving it in the map would mean re-deciding the same dead
                // address on every tick for the next three minutes.
                if (nowMs - trail.lastSeenMs > Handoffs.GIVE_UP_MS / 2) {
                    trails.remove(trail.address)
                }
            }
        }
    }

    /** Undo one adoption, for somebody who looked at the log and disagreed. */
    @Synchronized
    fun undo(adoption: Adoption) {
        mine.remove(adoption.toAddress)
        _adoptions.remove(adoption)
        taken.remove(adoption.toAddress)
    }

    @Synchronized
    fun forget() {
        trails.clear()
        _adoptions.clear()
        _declined.clear()
        taken.clear()
    }

    private val taken = mutableSetOf<String>()

    /**
     * Drops the oldest trails once there are too many.
     *
     * This runs for the life of the process in rooms with a thousand devices in them, and
     * an unbounded map of everything ever heard is how an app that is meant to be left
     * running for hours stops being one.
     */
    private fun prune(nowMs: Long) {
        trails.entries
            .filterNot { mine.isMine(it.key) }
            .sortedBy { it.value.lastSeenMs }
            .take(trails.size - KEEP_TRAILS)
            .forEach { trails.remove(it.key) }
    }

    private companion object {
        const val KEEP = 60
        const val MAX_GAP_MS = 30_000L
        const val MAX_TRAILS = 400
        const val KEEP_TRAILS = 250
        const val MAX_LOG = 50
    }
}
