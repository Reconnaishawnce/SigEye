package com.sigeye.core.analysis.identity

import java.util.Locale

/** One address in a chain, and how it was linked to the one before. */
data class ChainLink(
    val address: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    /** Null for the first address in a chain - nothing preceded it. */
    val score: LinkScore? = null,
) {
    val durationMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0L)
}

/**
 * One device, as far as anything here can tell, wearing a succession of addresses.
 *
 * The interesting number is [rotationPeriodMs]. Devices rotate on a schedule set by their
 * operating system, and that schedule is itself a fingerprint - iOS keeps to roughly
 * fifteen minutes, Android varies by version and vendor, and plenty of cheap hardware
 * never rotates at all. Watching a room for an hour and finding two devices rotating in
 * lockstep every fifteen minutes says something about what they are.
 */
data class Chain(
    val id: Int,
    val links: List<ChainLink>,
    val shape: AdvertShape,
    val label: String,
    val lastRssi: Int,
) {
    val addresses: List<String> get() = links.map { it.address }
    val current: String get() = links.last().address
    val rotations: Int get() = links.size - 1

    val spanMs: Long
        get() = (links.last().endedAtMs - links.first().startedAtMs).coerceAtLeast(0L)

    /** Mean time an address lasted, ignoring the one currently in use. */
    val rotationPeriodMs: Long
        get() {
            val finished = links.dropLast(1)
            if (finished.isEmpty()) return 0L
            return finished.sumOf { it.durationMs } / finished.size
        }

    val weakestLink: LinkConfidence
        get() = links.mapNotNull { it.score?.confidence }.minByOrNull { it.ordinal }
            ?: LinkConfidence.NONE

    fun describePeriod(): String {
        val period = rotationPeriodMs
        if (period <= 0) return "not seen to rotate yet"
        return String.format(Locale.US, "about every %.0f minutes", period / 60_000.0)
    }
}

/**
 * Following the devices you have asked to be followed, through their address changes.
 *
 * This began by chaining everything in range, which produced a wall of claims about
 * strangers' phones that nobody could check and nobody wanted. It is seeded now: a chain
 * only starts from an address you put on the watchlist, and then follows wherever that
 * address goes. Everything else in the room is counted and otherwise left alone.
 *
 * That also makes the claim worth more. Watchlisting a device is a statement that you know
 * what it is, so a chain leading away from it is about something identified rather than
 * about an anonymous address among forty others.
 *
 * The focused hunt is still the stronger tool, because a link there can be proved by
 * walking away with the device. Nothing here can be - so it is argued, never asserted.
 *
 * Pure and Android-free.
 */
class ChainTracker(
    /** Silence after which an address is considered finished. */
    private val silenceMs: Long = 25_000L,
    /** Weakest link worth extending a chain with. */
    private val minimumConfidence: LinkConfidence = LinkConfidence.LIKELY,
) {

    /** [LiveAddress] plus the two facts only a chain cares about. */
    private class Live(
        shape: AdvertShape,
        isRandom: Boolean,
        firstSeenMs: Long,
        lastSeenMs: Long,
        var chainId: Int? = null,
        var closed: Boolean = false,
    ) : LiveAddress(shape, isRandom, firstSeenMs, lastSeenMs)

    private val live = LinkedHashMap<String, Live>()
    private val chains = LinkedHashMap<Int, MutableList<ChainLink>>()
    private var nextChainId = 1

    /**
     * Addresses a chain may start from.
     *
     * Updated as the watchlist changes. An address already inside a chain keeps being
     * followed even if it is removed from the seeds, because abandoning a device halfway
     * through a journey would lose the thing being measured.
     */
    private var seeds: Set<String> = emptySet()

    fun seed(addresses: Collection<String>) {
        seeds = addresses.map { it.uppercase(Locale.US) }.toSet()
    }

    val seedCount: Int get() = seeds.size

    val addressCount: Int get() = live.size

    fun reset() {
        live.clear()
        chains.clear()
        nextChainId = 1
    }

    fun observe(address: String, rssi: Int, atMs: Long, shape: AdvertShape, isRandom: Boolean) {
        val key = address.uppercase(Locale.US)
        val entry = live.getOrPut(key) { Live(shape, isRandom, atMs, atMs) }
        if (shape.distinctiveness > entry.shape.distinctiveness) entry.shape = shape
        entry.observe(rssi, atMs)
    }

    /**
     * Closes anything that has gone quiet and tries to find where it went.
     *
     * Deliberately one pass: an address is matched at most once, against the candidate
     * with the best score. Allowing several chains to claim the same successor would let
     * one popular shape - every iPhone in a cafe looks alike - collapse the whole room
     * into a single imaginary device.
     */
    fun tick(nowMs: Long) {
        val finished = live.entries
            .filter { !it.value.closed && nowMs - it.value.lastSeenMs > silenceMs }
            .filter { it.value.isRandom && it.value.packets >= MIN_PACKETS }
            // Seeded, not room-wide: a chain starts from something you asked about, or
            // continues one already running. Chaining everything produced a wall of
            // unverifiable claims about strangers.
            .filter { it.value.chainId != null || seeds.contains(it.key) }
            .sortedBy { it.value.lastSeenMs }

        val claimed = mutableSetOf<String>()

        finished.forEach { (address, entry) ->
            entry.closed = true
            val chainId = entry.chainId ?: startChain(address, entry)

            val previous = entry.identity(address)
            val best = live.entries
                .filter { (candidate, other) ->
                    candidate != address &&
                        candidate !in claimed &&
                        other.chainId == null &&
                        !other.closed &&
                        other.isRandom &&
                        other.packets >= MIN_PACKETS &&
                        other.firstSeenMs >= entry.lastSeenMs - 2_000L
                }
                .map { (candidate, other) ->
                    candidate to Fingerprint.score(previous, other.identity(candidate))
                }
                .filter { it.second.confidence.ordinal >= minimumConfidence.ordinal }
                .maxByOrNull { it.second.points }
                ?: return@forEach

            claimed.add(best.first)
            val successor = live.getValue(best.first)
            successor.chainId = chainId
            chains.getValue(chainId).add(
                ChainLink(
                    address = best.first,
                    startedAtMs = successor.firstSeenMs,
                    endedAtMs = successor.lastSeenMs,
                    score = best.second,
                ),
            )
        }

        // Keep the end of each chain current, so a chain in use shows a live duration.
        chains.forEach { (id, links) ->
            val last = links.last()
            live[last.address]?.let { entry ->
                links[links.lastIndex] = last.copy(endedAtMs = entry.lastSeenMs)
            }
            // An address that was closed and then linked keeps its recorded end.
            if (id == 0) return@forEach
        }
    }

    private fun startChain(address: String, entry: Live): Int {
        val id = nextChainId++
        entry.chainId = id
        chains[id] = mutableListOf(
            ChainLink(address, entry.firstSeenMs, entry.lastSeenMs, score = null),
        )
        return id
    }

    /** Watchlisted addresses currently audible, whether or not they have rotated yet. */
    fun seedsInRange(nowMs: Long): Int = live.count { (address, entry) ->
        seeds.contains(address) && nowMs - entry.lastSeenMs <= silenceMs
    }

    /** Chains that have actually rotated at least once, longest first. */
    fun chains(nicknameOf: (String) -> String? = { null }): List<Chain> = chains
        .filterValues { it.size >= 2 }
        .map { (id, links) ->
            val head = live[links.last().address]
            Chain(
                id = id,
                links = links.toList(),
                shape = head?.shape ?: AdvertShape(),
                label = nicknameOf(links.last().address)
                    ?: head?.shape?.name
                    ?: links.last().address,
                lastRssi = head?.lastRssi ?: -127,
            )
        }
        .sortedByDescending { it.rotations }

    /**
     * Randomized addresses currently audible that have never been linked to anything.
     *
     * Useful as a denominator: forty unlinked addresses against three chains says the
     * matching is catching very little, which is the honest reading of most rooms.
     */
    fun unlinked(nowMs: Long): Int = live.count { (_, entry) ->
        entry.isRandom && entry.chainId == null && nowMs - entry.lastSeenMs <= silenceMs
    }

    /** Fixed addresses in range - the ones that never needed defeating. */
    fun fixedCount(nowMs: Long): Int = live.count { (_, entry) ->
        !entry.isRandom && nowMs - entry.lastSeenMs <= silenceMs
    }

    fun csv(): String = buildString {
        appendLine("# SigEye rotation chains")
        appendLine("chain,position,address,started_ms,ended_ms,confidence,points")
        chains.filterValues { it.size >= 2 }.forEach { (id, links) ->
            links.forEachIndexed { index, link ->
                append(id).append(',')
                    .append(index).append(',')
                    .append(link.address).append(',')
                    .append(link.startedAtMs).append(',')
                    .append(link.endedAtMs).append(',')
                    .append(link.score?.confidence?.name ?: "SEED").append(',')
                    .append(link.score?.points ?: 0)
                appendLine()
            }
        }
    }

    companion object {
        const val MIN_PACKETS = 8
    }
}
