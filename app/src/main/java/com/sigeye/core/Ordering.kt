package com.sigeye.core

/**
 * An ordered list of ids that a person has arranged, and the rules for arranging it.
 *
 * Two things in this app are exactly this problem. Which experiments are starred and in
 * what order, and which panels a screen shows and in what order. Both store ids, both
 * survive into a version of the app where an id may have been renamed or dropped, and both
 * need the same half dozen operations. Writing it twice would mean fixing it twice.
 *
 * Every read goes through [sanitise], so nothing downstream has to think about an id that
 * no longer points at anything. That is not defensive tidying: an id left dangling from an
 * older version renders as a blank untappable row, and the only people who ever see it are
 * the ones who have been using the app longest.
 *
 * Pure and Android-free.
 */
object Ordering {

    /** No duplicates, and nothing that no longer exists. */
    fun sanitise(stored: List<String>, available: Set<String>): List<String> =
        stored.filter { it in available }.distinct()

    /** The starting arrangement: what was recommended, in the recommended order. */
    fun seed(preferred: List<String>, available: Set<String>): List<String> =
        sanitise(preferred, available)

    /**
     * Adds or removes one id.
     *
     * A new id goes to the bottom rather than the top. Somebody who has arranged a list has
     * said what order they want, and inserting at the top would quietly rearrange it every
     * time they added something else.
     */
    fun toggle(current: List<String>, id: String): List<String> =
        if (id in current) current - id else current + id

    fun moveUp(current: List<String>, id: String): List<String> = swap(current, id, -1)

    fun moveDown(current: List<String>, id: String): List<String> = swap(current, id, 1)

    fun canMoveUp(current: List<String>, id: String): Boolean = current.indexOf(id) > 0

    fun canMoveDown(current: List<String>, id: String): Boolean {
        val index = current.indexOf(id)
        return index >= 0 && index < current.lastIndex
    }

    /**
     * The stored order, with anything new appended in its natural position.
     *
     * The case this exists for: the app ships a new panel on a screen somebody has already
     * arranged. Their arrangement has to survive, and the new panel has to appear rather
     * than be invisible because it was not in a list written before it existed.
     */
    fun merge(stored: List<String>, available: List<String>): List<String> {
        val kept = sanitise(stored, available.toSet())
        return kept + available.filterNot { it in kept }
    }

    private fun swap(current: List<String>, id: String, by: Int): List<String> {
        val index = current.indexOf(id)
        val target = index + by
        if (index < 0 || target !in current.indices) return current
        val out = current.toMutableList()
        out[index] = out[target]
        out[target] = id
        return out
    }
}
