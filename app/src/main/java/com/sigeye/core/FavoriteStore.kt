package com.sigeye.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The user's starred experiments, in their order, kept between launches.
 *
 * Seeded once with the recommended set, and then never seeded again. The flag matters: a
 * person who deliberately unstars everything has said they want an unadorned list, and
 * refilling it on the next launch would read as the app arguing with them.
 */
class FavoriteStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        // Spelled the old way on purpose. This names a file on disk, and renaming it would
        // orphan the one every existing install has already written their starred list to.
        .getSharedPreferences("favourites", Context.MODE_PRIVATE)

    private val _ids = MutableStateFlow(load())

    /** Starred experiment ids, in the order they should appear. */
    val ids: StateFlow<List<String>> = _ids

    fun isFavourite(id: String): Boolean = _ids.value.contains(id)

    fun toggle(id: String) = update(Ordering.toggle(_ids.value, id))

    fun moveUp(id: String) = update(Ordering.moveUp(_ids.value, id))

    fun moveDown(id: String) = update(Ordering.moveDown(_ids.value, id))

    /** Puts the recommended set back, for someone who has arranged themselves into a corner. */
    fun reset() = update(Ordering.seed(Experiments.featuredIds, knownIds()))

    private fun update(next: List<String>) {
        _ids.value = next
        prefs.edit()
            .putString(KEY_IDS, next.joinToString(SEPARATOR))
            .putBoolean(KEY_SEEDED, true)
            .apply()
    }

    private fun load(): List<String> {
        val known = knownIds()
        if (!prefs.getBoolean(KEY_SEEDED, false)) {
            val seeded = Ordering.seed(Experiments.featuredIds, known)
            prefs.edit()
                .putString(KEY_IDS, seeded.joinToString(SEPARATOR))
                .putBoolean(KEY_SEEDED, true)
                .apply()
            return seeded
        }
        val stored = prefs.getString(KEY_IDS, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()
        return Ordering.sanitise(offer(stored), known)
    }

    /**
     * Gives an existing install the recommended experiments that did not exist when it
     * was set up.
     *
     * Favourites are seeded once and then belong to whoever is using them, so a new one
     * would otherwise never reach anybody who already had the app. Each id is offered
     * exactly once and that is remembered separately, so a favourite somebody removed on
     * purpose is not quietly put back on the next launch.
     */
    private fun offer(stored: List<String>): List<String> {
        val offered = prefs.getStringSet(KEY_OFFERED, emptySet()).orEmpty()
        val additions = Experiments.featuredIds.filter { it !in offered && it !in stored }
        val all = offered + Experiments.featuredIds

        if (additions.isEmpty()) {
            if (all != offered) prefs.edit().putStringSet(KEY_OFFERED, all).apply()
            return stored
        }

        val next = additions + stored
        prefs.edit()
            .putString(KEY_IDS, next.joinToString(SEPARATOR))
            .putStringSet(KEY_OFFERED, all)
            .apply()
        return next
    }

    private fun knownIds(): Set<String> = Experiments.all
        .filter { it.status.openable }
        .map { it.id }
        .toSet()

    companion object {
        private const val KEY_IDS = "ids"
        private const val KEY_SEEDED = "seeded"

        /** Recommended ids this install has already been given, kept or not. */
        private const val KEY_OFFERED = "offered_ids"
        /** Ids are lowercase words, so a comma cannot appear inside one. */
        private const val SEPARATOR = ","

        @Volatile
        private var instance: FavoriteStore? = null

        fun get(context: Context): FavoriteStore =
            instance ?: synchronized(this) {
                instance ?: FavoriteStore(context).also { instance = it }
            }
    }
}
