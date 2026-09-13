package com.sigeye.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Addresses the user has muted. Shared across every experiment: your own earbuds, the
 * neighbor's TV and the fridge are noise in all of them, so muting is a property of the
 * app rather than of one screen.
 *
 * Ignored devices are dropped at the very first step, before dedup or counting, so they
 * never touch a baseline or a bin.
 */
class IgnoreList private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("ignorelist", Context.MODE_PRIVATE)

    private val _addresses = MutableStateFlow(load())
    val addresses: StateFlow<Set<String>> = _addresses

    /** Cheap synchronous read for the scan hot path. */
    @Volatile
    private var snapshot: Set<String> = _addresses.value

    fun isIgnored(address: String): Boolean = snapshot.contains(address.uppercase())

    fun add(address: String) = mutate { it + address.uppercase() }

    fun remove(address: String) = mutate { it - address.uppercase() }

    fun toggle(address: String) = mutate {
        val key = address.uppercase()
        if (it.contains(key)) it - key else it + key
    }

    fun clear() = mutate { emptySet() }

    fun size(): Int = snapshot.size

    private fun mutate(block: (Set<String>) -> Set<String>) {
        val updated = block(snapshot)
        snapshot = updated
        _addresses.value = updated
        prefs.edit().putStringSet(KEY, updated).apply()
    }

    private fun load(): Set<String> = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

    companion object {
        private const val KEY = "addresses"

        @Volatile
        private var instance: IgnoreList? = null

        fun get(context: Context): IgnoreList =
            instance ?: synchronized(this) {
                instance ?: IgnoreList(context).also { instance = it }
            }
    }
}
