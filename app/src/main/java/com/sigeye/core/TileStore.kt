package com.sigeye.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How each screen's panels are arranged, per screen, kept between launches.
 *
 * An experiment screen is a stack of panels and not everybody wants the same one at the
 * top. Somebody filming a train going past wants the count and the chart and nothing else;
 * somebody debugging a reading wants the diagnostics first. Rather than guess, let them
 * move it and remember what they chose.
 *
 * Hidden panels are stored separately from the order, because the two questions are
 * separate: hiding the diagnostics should not forget where they were if you bring them
 * back.
 */
class TileStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("tiles", Context.MODE_PRIVATE)

    private val _version = MutableStateFlow(0)

    /** Bumped on every change, so a screen reading the layout recomposes. */
    val version: StateFlow<Int> = _version

    /**
     * The order for one screen, with any panel the app has added since appended.
     *
     * [available] is what the screen can show today, in the order the screen would use if
     * nobody had rearranged anything. A panel added in a later version appears at its
     * natural position rather than being invisible because it was missing from a list
     * written before it existed.
     */
    fun order(screen: String, available: List<String>): List<String> {
        val stored = read(keyOrder(screen))
        return Ordering.merge(stored, available)
    }

    fun hidden(screen: String): Set<String> = read(keyHidden(screen)).toSet()

    fun isHidden(screen: String, tile: String): Boolean = hidden(screen).contains(tile)

    fun moveUp(screen: String, tile: String, available: List<String>) {
        write(keyOrder(screen), Ordering.moveUp(order(screen, available), tile))
    }

    fun moveDown(screen: String, tile: String, available: List<String>) {
        write(keyOrder(screen), Ordering.moveDown(order(screen, available), tile))
    }

    fun toggleHidden(screen: String, tile: String) {
        write(keyHidden(screen), Ordering.toggle(read(keyHidden(screen)), tile))
    }

    /** Back to the arrangement the screen ships with. */
    fun reset(screen: String) {
        prefs.edit().remove(keyOrder(screen)).remove(keyHidden(screen)).apply()
        _version.value = _version.value + 1
    }

    private fun read(key: String): List<String> =
        prefs.getString(key, null)?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()

    private fun write(key: String, value: List<String>) {
        prefs.edit().putString(key, value.joinToString(SEPARATOR)).apply()
        _version.value = _version.value + 1
    }

    private fun keyOrder(screen: String) = "$screen.order"

    private fun keyHidden(screen: String) = "$screen.hidden"

    companion object {
        /** Tile ids are lowercase words, so a comma cannot appear inside one. */
        private const val SEPARATOR = ","

        @Volatile
        private var instance: TileStore? = null

        fun get(context: Context): TileStore =
            instance ?: synchronized(this) {
                instance ?: TileStore(context).also { instance = it }
            }
    }
}
