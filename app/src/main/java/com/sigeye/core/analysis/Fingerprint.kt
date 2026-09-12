package com.sigeye.core.analysis

import java.util.Locale
import kotlin.math.abs

/**
 * The shape of what a device broadcasts, with everything that rotates stripped out.
 *
 * An address changes every quarter of an hour. The *structure* of the advertisement almost
 * never does: which fields are present, how long they are, which company claims it, which
 * services it lists. That structure is set by firmware rather than by the privacy scheme,
 * so it survives a rotation intact and is the cheapest useful way through it.
 *
 * Manufacturer data is deliberately reduced to its length and first two bytes. The contents
 * rotate on purpose - Apple's Continuity payload changes with the address - but the length
 * and the message type generally do not.
 */
data class AdvertShape(
    val companyId: Int? = null,
    val serviceUuids: List<String> = emptyList(),
    val appearance: Int? = null,
    val txPower: Int? = null,
    val name: String? = null,
    val manufacturerLength: Int = 0,
    val manufacturerPrefix: String? = null,
    val serviceDataKeys: List<String> = emptyList(),
    /**
     * Link-layer traits, which no privacy scheme touches.
     *
     * These come from the advertising parameters rather than the payload: whether the
     * device uses the extended advertising introduced in Bluetooth 5, whether it will
     * accept a connection, which physical layer it advertises on, and its advertising set
     * id. A device cannot change any of them without changing how it advertises, which is
     * a firmware decision rather than a privacy one.
     */
    val isLegacy: Boolean = true,
    val isConnectable: Boolean = false,
    val primaryPhy: Int = 1,
    val secondaryPhy: Int = 0,
    val advertisingSid: Int = 0xFF,
) {
    /** A stable string for two shapes to be compared by. */
    val key: String
        get() = listOf(
            companyId?.toString() ?: "-",
            serviceUuids.sorted().joinToString("|").ifEmpty { "-" },
            appearance?.toString() ?: "-",
            txPower?.toString() ?: "-",
            name ?: "-",
            manufacturerLength.toString(),
            manufacturerPrefix ?: "-",
            serviceDataKeys.sorted().joinToString("|").ifEmpty { "-" },
            if (isLegacy) "L" else "X",
            if (isConnectable) "C" else "-",
            primaryPhy.toString(),
            secondaryPhy.toString(),
            advertisingSid.toString(),
        ).joinToString("/")

    /**
     * How distinctive this shape is, roughly.
     *
     * A bare advertisement with nothing but a company ID is shared by thousands of
     * devices, and matching on it would link half a street together. Something carrying a
     * name, several services and an appearance is close to unique in any one room.
     */
    val distinctiveness: Int
        get() = listOfNotNull(
            companyId,
            appearance,
            txPower,
            name?.takeIf { it.isNotBlank() },
            manufacturerPrefix,
            // Extended advertising and a non-default advertising set are both unusual
            // enough to be worth a point on their own.
            if (!isLegacy) 1 else null,
            if (advertisingSid != 0xFF) 1 else null,
        ).size + serviceUuids.size + serviceDataKeys.size

    val tooPlainToMatchOn: Boolean get() = distinctiveness < 2
}

/** How sure we are that two addresses are one device. */
enum class LinkConfidence(val label: String) {
    NONE("Different devices"),
    POSSIBLE("Possibly the same"),
    LIKELY("Probably the same"),
    STRONG("Almost certainly the same"),
}

/** One piece of evidence for or against a link. */
data class LinkEvidence(val holds: Boolean, val weight: Int, val text: String)

data class LinkScore(
    val evidence: List<LinkEvidence>,
    val confidence: LinkConfidence,
) {
    val points: Int get() = evidence.filter { it.holds }.sumOf { it.weight }
    val supporting: List<LinkEvidence> get() = evidence.filter { it.holds }
    val against: List<LinkEvidence> get() = evidence.filter { !it.holds }
}

/** What is known about one address while it is being watched. */
data class Identity(
    val address: String,
    val shape: AdvertShape,
    val isRandom: Boolean,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val packets: Int,
    val medianGapMs: Long,
    val recentRssi: Double,
    val bestRssi: Int,
    /** Spread of the gaps about the base interval, as a fraction of it. */
    val intervalJitter: Double = 0.0,
    /** Standard deviation of RSSI while the device was audible. */
    val rssiSpread: Double = 0.0,
) {
    /**
     * The advertising interval in the units the specification actually uses.
     *
     * Intervals are set as a whole number of 0.625 ms slots, so the raw estimate snaps to
     * one. The common values are recognisable on sight - 32 slots is 20 ms, 160 is 100 ms,
     * 244 is the 152.5 ms Apple uses, 1636 is the 1022.5 ms of a device trying to save
     * power - and a researcher comparing two devices wants the slot count, not a number
     * of milliseconds that happens to be close to it.
     */
    val intervalSlots: Int get() = if (medianGapMs <= 0) 0 else (medianGapMs / 0.625).toInt()

    /** How tightly the device holds its interval. Under a tenth is metronomic. */
    val intervalStability: String
        get() = when {
            medianGapMs <= 0 -> "unknown"
            intervalJitter <= 0.05 -> "very tight"
            intervalJitter <= 0.15 -> "tight"
            intervalJitter <= 0.4 -> "loose"
            else -> "irregular"
        }
}

/**
 * Linking one address to the next one a device puts on.
 *
 * Four independent signals, none of them conclusive alone:
 *
 *  - the advertisement's structure, which firmware sets and privacy does not touch;
 *  - the advertising interval, which is a firmware constant plus a small random slop;
 *  - signal continuity, since a device does not teleport when it changes name;
 *  - the handover itself - one address falling silent as another starts.
 *
 * None of this is proof and the app never calls it proof. What makes the experiment worth
 * anything is that the user can test a claim themselves by walking the device away and
 * watching the new address fade, which is ground truth rather than inference.
 *
 * Pure and Android-free.
 */
object Fingerprint {

    /** Gap either side of the handover, in which a rotation plausibly happened. */
    const val HANDOVER_WINDOW_MS = 30_000L

    /** Advertising intervals this close count as the same firmware constant. */
    const val INTERVAL_TOLERANCE = 0.18

    /** A device does not move far in the moment it changes address. */
    const val RSSI_CONTINUITY_DB = 10

    fun score(previous: Identity, candidate: Identity): LinkScore {
        val evidence = mutableListOf<LinkEvidence>()

        // A fixed address has no reason to rotate, so a link involving one is meaningless.
        val bothRandom = previous.isRandom && candidate.isRandom
        evidence.add(
            LinkEvidence(
                holds = bothRandom,
                weight = 0,
                text = if (bothRandom) {
                    "Both addresses are randomised, which is what rotation looks like."
                } else {
                    "One of these addresses is fixed, and a fixed address does not rotate."
                },
            ),
        )

        val shapesMatch = previous.shape.key == candidate.shape.key &&
            !previous.shape.tooPlainToMatchOn
        evidence.add(
            LinkEvidence(
                holds = shapesMatch,
                weight = 4,
                text = if (shapesMatch) {
                    "The advertisement has exactly the same structure: " +
                        describeShape(candidate.shape) + ". Firmware sets that, and " +
                        "changing address does not change it."
                } else if (previous.shape.tooPlainToMatchOn) {
                    "The advertisement is too plain to match on - it carries almost " +
                        "nothing that would distinguish one device from another."
                } else {
                    "The advertisement structure is different, which normally means a " +
                        "different device."
                },
            ),
        )

        val intervalMatch = intervalsAgree(previous.medianGapMs, candidate.medianGapMs)
        evidence.add(
            LinkEvidence(
                holds = intervalMatch,
                weight = 3,
                text = if (intervalMatch) {
                    "Both advertise about every " + candidate.medianGapMs +
                        " ms. The interval is a firmware constant, not part of the " +
                        "privacy scheme."
                } else {
                    "They advertise at different rates - " + previous.medianGapMs +
                        " ms against " + candidate.medianGapMs + " ms."
                },
            ),
        )

        val gap = candidate.firstSeenMs - previous.lastSeenMs
        val handover = gap in -2_000L..HANDOVER_WINDOW_MS
        evidence.add(
            LinkEvidence(
                holds = handover,
                weight = 2,
                text = if (handover) {
                    "The new address appeared " + (gap / 1000) +
                        "s after the old one went quiet, which is what a handover " +
                        "looks like."
                } else {
                    "The timing does not fit a handover: " + (gap / 1000) +
                        "s between the old one stopping and this one starting."
                },
            ),
        )

        val rssiGap = abs(previous.recentRssi - candidate.recentRssi)
        val continuity = rssiGap <= RSSI_CONTINUITY_DB
        evidence.add(
            LinkEvidence(
                holds = continuity,
                weight = 2,
                text = if (continuity) {
                    String.format(
                        Locale.US,
                        "The signal barely changed across the swap - %.0f dB apart. A " +
                            "device does not move when it changes its name.",
                        rssiGap,
                    )
                } else {
                    String.format(
                        Locale.US,
                        "The signal jumped %.0f dB across the swap, which is a lot for " +
                            "something that did not move.",
                        rssiGap,
                    )
                },
            ),
        )

        val points = evidence.filter { it.holds }.sumOf { it.weight }
        return LinkScore(
            evidence = evidence,
            confidence = when {
                !bothRandom -> LinkConfidence.NONE
                !shapesMatch && !intervalMatch -> LinkConfidence.NONE
                points >= 10 -> LinkConfidence.STRONG
                points >= 7 -> LinkConfidence.LIKELY
                points >= 4 -> LinkConfidence.POSSIBLE
                else -> LinkConfidence.NONE
            },
        )
    }

    fun intervalsAgree(a: Long, b: Long): Boolean {
        if (a <= 0 || b <= 0) return false
        val larger = maxOf(a, b).toDouble()
        return abs(a - b) / larger <= INTERVAL_TOLERANCE
    }

    /**
     * The base advertising interval, estimated from the gaps between packets.
     *
     * Not the median: a scanner misses packets, so most gaps are two or three intervals
     * rather than one, and the median lands on whichever multiple happened to dominate.
     * The smallest gaps are the ones where nothing was missed, so a low percentile
     * estimates the true interval and ignores the misses above it.
     */
    fun baseIntervalMs(gaps: List<Long>): Long {
        val usable = gaps.filter { it > 0 }.sorted()
        if (usable.size < 4) return 0L
        val index = ((usable.size - 1) * 0.15).toInt().coerceIn(0, usable.lastIndex)
        return usable[index]
    }

    /**
     * How loosely the gaps sit about the base interval, as a fraction of it.
     *
     * Only gaps close to one interval are counted. A missed packet doubles a gap, and
     * including those would measure how well the scanner is keeping up rather than how
     * steadily the device advertises - which is the opposite of the intent.
     */
    fun intervalJitter(gaps: List<Long>, baseMs: Long): Double {
        if (baseMs <= 0) return 0.0
        val singles = gaps.filter { it in (baseMs / 2)..(baseMs * 3 / 2) }
        if (singles.size < 4) return 0.0
        val mean = singles.average()
        if (mean <= 0.0) return 0.0
        val variance = singles.sumOf { (it - mean) * (it - mean) } / singles.size
        return kotlin.math.sqrt(variance) / mean
    }

    /** Standard deviation of a run of readings. */
    fun spread(values: List<Int>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private fun describeShape(shape: AdvertShape): String {
        val parts = mutableListOf<String>()
        shape.name?.takeIf { it.isNotBlank() }?.let { parts.add("name \"$it\"") }
        shape.companyId?.let { parts.add(String.format(Locale.US, "company 0x%04X", it)) }
        if (shape.serviceUuids.isNotEmpty()) {
            parts.add("${shape.serviceUuids.size} service UUIDs")
        }
        if (shape.manufacturerLength > 0) {
            parts.add("${shape.manufacturerLength} bytes of manufacturer data")
        }
        shape.manufacturerPrefix?.let { parts.add("starting $it") }
        shape.appearance?.let { parts.add("appearance set") }
        return parts.joinToString(", ").ifEmpty { "an empty advertisement" }
    }
}
