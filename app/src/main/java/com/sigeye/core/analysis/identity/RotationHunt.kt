package com.sigeye.core.analysis.identity

import com.sigeye.core.ble.AddressType
import com.sigeye.core.ble.PayloadVariance
import kotlin.math.abs

/** A claimed rotation, with the reasoning behind it. */
data class Rotation(
    val fromAddress: String,
    val toAddress: String,
    val atMs: Long,
    val score: LinkScore,
    /** Set once a walk-away test has been run against the new address. */
    val proof: WalkProof? = null,
) {
    val confirmed: Boolean get() = proof?.confirmed == true
    val disproved: Boolean get() = proof?.confirmed == false
}

/**
 * The result of carrying the device away and watching what happens.
 *
 * This is the only part of the experiment that is not inference. If the address the app
 * claims is your phone fades as you carry your phone down the corridor, and comes back as
 * you return, then it is your phone - no fingerprint argument required. If it sits there
 * unchanged while you are two rooms away, the claim was wrong and the app says so.
 */
data class WalkProof(
    val baselineRssi: Double,
    val lowestRssi: Int,
    val returnedRssi: Int?,
    val samples: Int,
    val confirmed: Boolean,
    val note: String,
) {
    val dropDb: Double get() = baselineRssi - lowestRssi
}

enum class HuntStage { PICK, LEARN, WATCH }

data class HuntState(
    val stage: HuntStage = HuntStage.PICK,
    val tracking: String? = null,
    val originalAddress: String? = null,
    val shape: AdvertShape = AdvertShape(),
    val intervalMs: Long = 0,
    val packets: Int = 0,
    val currentRssi: Int? = null,
    val rotations: List<Rotation> = emptyList(),
    val learnProgress: Float = 0f,
    /** Everything measured about the subject that is not the address itself. */
    val traits: Traits = Traits(),
) {
    val addressesLinked: Int get() = rotations.size + if (tracking != null) 1 else 0
    val confirmedRotations: Int get() = rotations.count { it.confirmed }
    val disprovedRotations: Int get() = rotations.count { it.disproved }
}

/**
 * Everything measurable about how a device advertises, rather than what it says.
 *
 * Gathered in one place because the point of the experiment is comparison: two devices
 * side by side, or one device before and after a rotation. A single number - how often it
 * advertises - was never going to carry that on its own.
 */
data class Traits(
    val addressType: AddressType = AddressType.UNKNOWN,
    val intervalMs: Long = 0,
    val intervalSlots: Int = 0,
    val intervalStability: String = "unknown",
    val intervalJitter: Double = 0.0,
    val rssiSpread: Double = 0.0,
    val isLegacy: Boolean = true,
    val isConnectable: Boolean = false,
    val primaryPhy: Int = 1,
    val secondaryPhy: Int = 0,
    val advertisingSid: Int = 0xFF,
    val payloadLength: Int = 0,
    val payloadStaticBytes: Int = 0,
    val payloadMask: String = "",
    val payloadNote: String = "",
    val payloadLeaksIdentity: Boolean = false,
)

/**
 * Following one device through its address changes.
 *
 * The experiment is a loop. Learn a device's fingerprint while you can see it. Carry it
 * away and watch the signal fall, which proves the app is looking at the right thing.
 * Wait for the address to rotate, at which point the app names its successor and says
 * exactly why it believes that. Then carry it away again - if the new address fades too,
 * the link was real, and the app was defeated by nothing.
 *
 * That last step is what makes this worth building rather than a demo. Every claim can be
 * checked by the person making it, and the app records which claims survived.
 *
 * Pure and Android-free.
 */
class RotationHunt(
    /** How long to watch a device before its fingerprint is worth using. */
    var learnMs: Long = 30_000L,
    /** Silence after which the tracked address is assumed to have been dropped. */
    private val silenceMs: Long = 20_000L,
) {

    private class Watched(
        var shape: AdvertShape,
        val isRandom: Boolean,
        val firstSeenMs: Long,
        var lastSeenMs: Long,
        var packets: Int = 0,
        val gaps: MutableList<Long> = mutableListOf(),
        val recent: ArrayDeque<Int> = ArrayDeque(),
        var bestRssi: Int = -127,
        val payload: PayloadVariance = PayloadVariance(),
    ) {
        fun observe(rssi: Int, atMs: Long) {
            if (packets > 0) gaps.add(atMs - lastSeenMs)
            lastSeenMs = atMs
            packets++
            if (rssi > bestRssi) bestRssi = rssi
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
                bestRssi = bestRssi,
                intervalJitter = Fingerprint.intervalJitter(gaps, base),
                rssiSpread = Fingerprint.spread(recent.toList()),
            )
        }
    }

    private val everything = LinkedHashMap<String, Watched>()
    private var trackedAddress: String? = null
    private var originalAddress: String? = null
    private var learnStartedMs = 0L
    private var stage = HuntStage.PICK
    private val rotations = mutableListOf<Rotation>()

    // Walk-away test state.
    private var walkBaseline: Double? = null
    private var walkLowest = Int.MAX_VALUE
    private var walkSamples = 0
    private var walkReturned: Int? = null

    fun reset() {
        everything.clear()
        rotations.clear()
        trackedAddress = null
        originalAddress = null
        stage = HuntStage.PICK
        clearWalk()
    }

    /** Starts following one address and learning what it looks like. */
    fun track(address: String, nowMs: Long) {
        trackedAddress = address.uppercase()
        originalAddress = trackedAddress
        learnStartedMs = nowMs
        stage = HuntStage.LEARN
        rotations.clear()
        clearWalk()
    }

    fun observe(
        address: String,
        rssi: Int,
        atMs: Long,
        shape: AdvertShape,
        isRandom: Boolean,
        manufacturerData: ByteArray? = null,
    ) {
        val key = address.uppercase()
        val watched = everything.getOrPut(key) {
            Watched(shape, isRandom, atMs, atMs)
        }
        watched.payload.observe(manufacturerData)
        // Shape is refreshed rather than fixed at first sight: a device does not put
        // everything in every packet, so the fullest picture builds up over a few.
        if (shape.distinctiveness > watched.shape.distinctiveness) watched.shape = shape
        watched.observe(rssi, atMs)

        if (key == trackedAddress) {
            walkBaseline?.let {
                walkSamples++
                if (rssi < walkLowest) walkLowest = rssi
                walkReturned = rssi
            }
        }
    }

    fun tick(nowMs: Long) {
        if (stage == HuntStage.LEARN && nowMs - learnStartedMs >= learnMs) {
            stage = HuntStage.WATCH
        }
        if (stage != HuntStage.WATCH) return

        val tracked = trackedAddress ?: return
        val watched = everything[tracked] ?: return
        if (nowMs - watched.lastSeenMs < silenceMs) return

        // The tracked address has gone quiet. Anything that started since it was last
        // heard is a candidate for what it turned into.
        val previous = watched.identity(tracked)
        val best = everything.entries
            .filter { (address, candidate) ->
                address != tracked &&
                    candidate.firstSeenMs >= watched.lastSeenMs - 2_000L &&
                    candidate.packets >= MIN_CANDIDATE_PACKETS &&
                    rotations.none { it.toAddress == address } &&
                    address != originalAddress
            }
            .map { (address, candidate) ->
                address to Fingerprint.score(previous, candidate.identity(address))
            }
            .filter { it.second.confidence != LinkConfidence.NONE }
            .maxByOrNull { it.second.points }
            ?: return

        rotations.add(
            Rotation(
                fromAddress = tracked,
                toAddress = best.first,
                atMs = nowMs,
                score = best.second,
            ),
        )
        trackedAddress = best.first
        clearWalk()
    }

    // ------------------------------------------------------------- walk-away test

    fun startWalkTest() {
        val tracked = trackedAddress ?: return
        val watched = everything[tracked] ?: return
        walkBaseline = watched.recentRssi
        walkLowest = Int.MAX_VALUE
        walkSamples = 0
        walkReturned = null
    }

    val walkInProgress: Boolean get() = walkBaseline != null

    val walkDropSoFar: Double
        get() {
            val baseline = walkBaseline ?: return 0.0
            if (walkLowest == Int.MAX_VALUE) return 0.0
            return baseline - walkLowest
        }

    /**
     * Ends the test and records what it showed.
     *
     * A device that went quiet entirely counts as confirmed: walking out of range is the
     * strongest version of the signal falling away, and refusing to credit it would fail
     * the clearest possible result.
     */
    fun finishWalkTest(): WalkProof? {
        val baseline = walkBaseline ?: return null
        val drop = if (walkLowest == Int.MAX_VALUE) Double.MAX_VALUE else baseline - walkLowest
        val vanished = walkSamples == 0

        val confirmed = vanished || drop >= CONFIRMING_DROP_DB
        val proof = WalkProof(
            baselineRssi = baseline,
            lowestRssi = if (walkLowest == Int.MAX_VALUE) -127 else walkLowest,
            returnedRssi = walkReturned,
            samples = walkSamples,
            confirmed = confirmed,
            note = when {
                vanished -> "It stopped being heard entirely while you carried the device " +
                    "away. That is as clear as this gets."
                confirmed -> String.format(
                    java.util.Locale.US,
                    "The signal fell %.0f dB as you walked away. Whatever this address " +
                        "is, it went with you.",
                    drop,
                )
                else -> String.format(
                    java.util.Locale.US,
                    "The signal only moved %.0f dB. Either you did not go far enough, or " +
                        "this address is not the device you are carrying - and the second " +
                        "would mean the link was wrong.",
                    drop,
                )
            },
        )

        if (rotations.isNotEmpty()) {
            rotations[rotations.lastIndex] = rotations.last().copy(proof = proof)
        }
        clearWalk()
        return proof
    }

    private fun clearWalk() {
        walkBaseline = null
        walkLowest = Int.MAX_VALUE
        walkSamples = 0
        walkReturned = null
    }

    // ------------------------------------------------------------------- reading

    fun state(nowMs: Long): HuntState {
        val tracked = trackedAddress
        val watched = tracked?.let { everything[it] }
        return HuntState(
            stage = stage,
            tracking = tracked,
            originalAddress = originalAddress,
            shape = watched?.shape ?: AdvertShape(),
            intervalMs = watched?.let { Fingerprint.baseIntervalMs(it.gaps) } ?: 0L,
            packets = watched?.packets ?: 0,
            currentRssi = watched?.recent?.lastOrNull(),
            traits = watched?.let { entry ->
                val identity = entry.identity(tracked ?: "")
                Traits(
                    addressType = AddressType.of(tracked ?: ""),
                    intervalMs = identity.medianGapMs,
                    intervalSlots = identity.intervalSlots,
                    intervalStability = identity.intervalStability,
                    intervalJitter = identity.intervalJitter,
                    rssiSpread = identity.rssiSpread,
                    isLegacy = entry.shape.isLegacy,
                    isConnectable = entry.shape.isConnectable,
                    primaryPhy = entry.shape.primaryPhy,
                    secondaryPhy = entry.shape.secondaryPhy,
                    advertisingSid = entry.shape.advertisingSid,
                    payloadLength = entry.payload.length,
                    payloadStaticBytes = entry.payload.staticBytes,
                    payloadMask = entry.payload.mask(),
                    payloadNote = entry.payload.describe(),
                    payloadLeaksIdentity = entry.payload.leaksIdentity(),
                )
            } ?: Traits(),
            rotations = rotations.toList(),
            learnProgress = if (stage == HuntStage.LEARN) {
                ((nowMs - learnStartedMs).toFloat() / learnMs).coerceIn(0f, 1f)
            } else if (stage == HuntStage.WATCH) {
                1f
            } else {
                0f
            },
        )
    }

    /** Candidates worth offering as something to follow. */
    fun candidates(nowMs: Long): List<Identity> = everything
        .filter { (_, watched) ->
            nowMs - watched.lastSeenMs < 10_000L &&
                watched.packets >= MIN_CANDIDATE_PACKETS
        }
        .map { (address, watched) -> watched.identity(address) }
        .sortedByDescending { it.recentRssi }

    companion object {
        /** Enough packets to have an interval and a shape worth comparing. */
        const val MIN_CANDIDATE_PACKETS = 6

        /**
         * How far the signal has to fall for a walk-away test to mean anything.
         *
         * Multipath alone moves a stationary link several dB, so a small drop proves
         * nothing. Leaving a room is normally twenty or more.
         */
        const val CONFIRMING_DROP_DB = 12.0
    }
}
