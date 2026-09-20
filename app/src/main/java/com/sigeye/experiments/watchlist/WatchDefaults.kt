package com.sigeye.experiments.watchlist

/**
 * Giving an existing install the default rules that did not exist when it was set up.
 *
 * Rules are seeded once and then belong to whoever is using them, so a signature shipped in
 * an update would otherwise never reach anybody who already had the app - which is the
 * entire value of adding one. Flock's batteries are the case in point: the app could not
 * have watched for them before somebody worked out what they sound like.
 *
 * The rule that makes this safe is that a default is offered exactly once. The set of ids
 * already offered is remembered separately from the rules themselves, so a rule deleted on
 * purpose is not quietly reinstated on the next launch, which would be the app arguing with
 * its user once a version.
 *
 * Pure, so the awkward half of an upgrade is testable without a device.
 */
object WatchDefaults {

    /** What the rule list should become, given what is stored and what has been offered. */
    fun adopt(existing: List<WatchRule>, offered: Set<String>): List<WatchRule> {
        val have = existing.map { it.id }.toSet()
        val additions = WatchRule.defaults().filter { it.id !in offered && it.id !in have }

        // A superseded rule goes only if nobody has touched it. An edited copy is theirs,
        // and replacing it would throw away a threshold somebody chose deliberately.
        val superseded = existing.filter { it == WatchRule.RETIRED_AXON_OUI }.toSet()

        if (additions.isEmpty() && superseded.isEmpty()) return existing
        return existing - superseded + additions
    }

    /** Every id that has now been offered, whether or not it survived. */
    fun offeredAfter(offered: Set<String>): Set<String> =
        offered + WatchRule.defaults().map { it.id } + WatchRule.RETIRED_AXON_OUI.id
}
