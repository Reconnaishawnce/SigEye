package com.sigeye.core.analysis

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
 * Rotation-hunting across everything in the room at once.
 *
 * The focused hunt follows one device you chose and lets you prove the link by walking
 * away with it. This does the same reasoning without a subject: every randomised address
 * that goes quiet is offered to every address that has just appeared, and the best match
 * above a threshold extends a chain.
 *
 * That is a weaker claim and it is treated as one - there is no walk-away test available
 * for a stranger's phone, so nothing here can ever be confirmed, only argued. What it is
 * good for is the shape of the room: how many distinct devices are actually present behind
 * forty addresses, how often each rotates, and whether anything is not rotating at all,
 * which is far more interesting than anything that is.
 *
 * Pure and Android-free.
 */
class ChainTracker(
    /** Silence after which an address is considered finished. */
    private val silenceMs: Long = 25_000L,
    /** Weakest link worth extending a chain with. */
    private val minimumConfidence: LinkConfidence = LinkConfidence.LIKELY,
) {

    private class Live(
        var shape: AdvertShape,
        val isRandom: Boolean,
        val firstSeenMs: Long,
        var lastSeenMs: Long,
        var packets: Int = 0,
        val gaps: MutableList<Long> = mutableListOf(),
        var lastRssi: Int = -127,
        val recent: ArrayDeque<Int> = ArrayDeque(),
        var chainId: Int? = null,
        var closed: Boolean = false,
    ) {
        fun observe(rssi: Int, atMs: Long) {
            if (packets > 0) gaps.add(atMs - lastSeenMs)
            lastSeenMs = atMs
            packets++
            lastRssi = rssi
            recent.addLast(rssi)
            while (recent.size > 20) recent.removeFirst()
        }

        val recentRssi: Double get() = if (recent.isEmpty()) -127.0 else recent.average()

        fun identity(address: String): Identity {
            val base = Fingerprint.baseIntervalMs(gaps)
            return Identity(
                address = address,
                shape = shape,
                isRandom = isRandom,
                firstSeenMs = firstSeenMs,
                lastSeenMs = lastSeenMs,
                packets = packets,
                medianGapMs = base,
                recentRssi = recentRssi,
                bestRssi = recent.maxOrNull() ?: -127,
                intervalJitter = Fingerprint.intervalJitter(gaps, base),
                rssiSpread = Fingerprint.spread(recent.toList()),
            )
        }
    }

    private val live = LinkedHashMap<String, Live>()
    private val chains = LinkedHashMap<Int, MutableList<ChainLink>>()
    private var nextChainId = 1

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
     * Randomised addresses currently audible that have never been linked to anything.
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
