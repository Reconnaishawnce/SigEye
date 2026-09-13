package com.sigeye.core.analysis.identity

import java.util.Locale

/** One address, reduced to the facts a cohort is built from. */
data class CohortMember(
    val address: String,
    /** Vendor from the payload's company identifier. Survives a rotation. */
    val payloadVendor: String?,
    /** Vendor from the address prefix. Meaningless once the address is random. */
    val ouiVendor: String?,
    val isRandom: Boolean,
    val intervalMs: Long,
    val shapeKey: String,
    val rotations: Int = 0,
    val lastSeenMs: Long = 0,
)

/** Everything in range that came out of the same firmware house. */
data class Cohort(
    val vendor: String,
    val members: List<CohortMember>,
) {
    val size: Int get() = members.size

    val randomized: Int get() = members.count { it.isRandom }

    val fixed: Int get() = size - randomized

    /** How much of this vendor's fleet bothers with a private address. */
    val privacyFraction: Float get() = if (size == 0) 0f else randomized.toFloat() / size

    /** Distinct advertisement structures, which is roughly distinct products. */
    val shapes: Int get() = members.map { it.shapeKey }.distinct().size

    val rotationsSeen: Int get() = members.sumOf { it.rotations }

    /** The interval this vendor's firmware tends to use. */
    val medianIntervalMs: Long
        get() {
            val values = members.map { it.intervalMs }.filter { it > 0 }.sorted()
            return if (values.isEmpty()) 0L else values[values.size / 2]
        }

    val strongestVendorEvidence: Boolean
        get() = members.any { it.payloadVendor != null }

    /**
     * What this cohort's address policy amounts to, in a sentence.
     *
     * The interesting cases are the two extremes. A fleet that is entirely randomized is a
     * vendor that took the specification seriously; a fleet that is entirely fixed is
     * every one of those devices trackable for the life of the hardware.
     */
    fun policy(): String = when {
        size == 0 -> "nothing here"
        randomized == size -> "every one of them rotates"
        fixed == size -> "not one of them rotates - all $size are trackable for good"
        else -> "$randomized of $size rotate, $fixed do not"
    }
}

/**
 * Grouping what is in range by who made it, which is grouping it by how it behaves.
 *
 * Rotation is a firmware decision, so it is a vendor trait: every Apple device in a room
 * rotates on the same schedule with the same payload structure, and a cheap beacon from a
 * factory that never read the privacy chapter does not rotate at all. Seeing the room
 * split that way makes the behavior legible in a way a flat list of eighty addresses
 * never will.
 *
 * The identification is the subtle part. An address prefix names a vendor only while the
 * address is real - the moment a device randomizes it, the prefix is noise, and reading a
 * vendor out of it is how a scanner ends up reporting a street full of devices from
 * companies that do not exist. What does survive a rotation is the company identifier
 * inside the advertisement, because the payload is what the device wants understood.
 *
 * Pure and Android-free.
 */
object Cohorts {

    const val UNKNOWN = "Unidentified"

    /**
     * Who made this, using whichever handle is actually valid.
     *
     * A randomized address has no vendor in it, so only the payload counts. A fixed
     * address has a real prefix assigned by the IEEE, which is the stronger claim of the
     * two - but the payload still wins where both exist and disagree, because a company
     * identifier is a deliberate statement and a prefix can belong to whoever made the
     * radio module rather than the product.
     */
    fun vendorOf(member: CohortMember): String = when {
        member.payloadVendor != null -> member.payloadVendor
        !member.isRandom && member.ouiVendor != null -> member.ouiVendor
        else -> UNKNOWN
    }

    fun build(members: List<CohortMember>): List<Cohort> = members
        .groupBy { vendorOf(it) }
        .map { (vendor, group) -> Cohort(vendor, group) }
        // Biggest first, but the unidentified pile goes last however large it is - it is a
        // measure of what could not be worked out, not a finding about a manufacturer.
        .sortedWith(
            compareBy<Cohort> { it.vendor == UNKNOWN }.thenByDescending { it.size },
        )

    /**
     * How much of the room could be attributed at all.
     *
     * Worth showing next to the cohorts: if two thirds of the addresses are unidentified,
     * the cohorts describe a third of the room, and any conclusion drawn from them should
     * be read accordingly.
     */
    fun identifiedFraction(cohorts: List<Cohort>): Float {
        val total = cohorts.sumOf { it.size }
        if (total == 0) return 0f
        val named = cohorts.filter { it.vendor != UNKNOWN }.sumOf { it.size }
        return named.toFloat() / total
    }

    /**
     * Cohorts whose devices are all trackable, largest first.
     *
     * The headline finding of the whole screen, usually: the fleet that never rotates is
     * the one anybody with a phone can follow around indefinitely.
     */
    fun trackable(cohorts: List<Cohort>, minimumSize: Int = 2): List<Cohort> = cohorts
        .filter { it.vendor != UNKNOWN && it.size >= minimumSize && it.randomized == 0 }
        .sortedByDescending { it.size }

    fun describe(cohort: Cohort): String = String.format(
        Locale.US,
        "%d address%s, %d shape%s, %s",
        cohort.size,
        if (cohort.size == 1) "" else "es",
        cohort.shapes,
        if (cohort.shapes == 1) "" else "s",
        cohort.policy(),
    )
}
