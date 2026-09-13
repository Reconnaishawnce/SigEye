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

    fun toggle(id: String) = update(Favorites.toggle(_ids.value, id))

    fun moveUp(id: String) = update(Favorites.moveUp(_ids.value, id))

    fun moveDown(id: String) = update(Favorites.moveDown(_ids.value, id))

    /** Puts the recommended set back, for someone who has arranged themselves into a corner. */
    fun reset() = update(Favorites.seed(Experiments.featuredIds, knownIds()))

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
            val seeded = Favorites.seed(Experiments.featuredIds, known)
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
        return Favorites.sanitise(stored, known)
    }

    private fun knownIds(): Set<String> = Experiments.all
        .filter { it.status.openable }
        .map { it.id }
        .toSet()

    companion object {
        private const val KEY_IDS = "ids"
        private const val KEY_SEEDED = "seeded"
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
