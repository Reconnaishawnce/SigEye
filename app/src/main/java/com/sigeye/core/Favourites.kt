package com.sigeye.core

/**
 * The order the user wants their experiments in.
 *
 * A list of twenty-seven experiments across five categories is a good library and a poor
 * front door: everything is equally weighted, so nothing is. A starred, ordered list at the
 * top fixes that per person rather than by guessing, and it starts pre-filled so someone
 * opening the app for the first time still gets a recommendation rather than an empty
 * shelf.
 *
 * The list is only ever ids. It is written to preferences and read back on a later version
 * of the app, which may have renamed or dropped an experiment - so [sanitise] runs on every
 * read and nothing else in here has to worry about a favourite that no longer exists.
 *
 * Pure and Android-free.
 */
object Favourites {

    /**
     * Prepares a stored list for use: no duplicates, nothing that no longer exists.
     *
     * A favourite pointing at a deleted experiment would be an untappable row with no
     * title, which is exactly the kind of thing that survives for months because it only
     * happens to people who used an older version.
     */
    fun sanitise(stored: List<String>, available: Set<String>): List<String> =
        stored.filter { it in available }.distinct()

    /**
     * The starting set: the recommended ones, in the recommended order, that actually exist.
     */
    fun seed(preferred: List<String>, available: Set<String>): List<String> =
        sanitise(preferred, available)

    /**
     * Stars or unstars one experiment.
     *
     * A newly starred experiment goes to the bottom rather than the top. Someone who has
     * arranged their list has said what order they want, and inserting at the top would
     * quietly rearrange it every time they starred something else.
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
