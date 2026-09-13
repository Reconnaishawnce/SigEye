package com.sigeye.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The one device the app is currently about. */
data class Pinned(
    val address: String,
    val label: String?,
    val vendor: String?,
    /** Where it came from, so a screen can say why this is on the bar. */
    val source: String,
    val pinnedAtMs: Long,
    /** Addresses it has worn since being pinned, oldest first. */
    val addresses: List<String> = listOf(address),
) {
    val name: String get() = label ?: vendor ?: address

    val rotations: Int get() = (addresses.size - 1).coerceAtLeast(0)
}

/**
 * One target, carried between experiments.
 *
 * Finding a device was the hard part and the app made it the easy part. Follow Me narrowed
 * a street to one phone and then the only way to look at it anywhere else was to read its
 * address off the screen and type it into a filter box - which is absurd for a value that
 * changes every fifteen minutes, and absurd twice over in an app whose whole subject is
 * that it changes.
 *
 * So the target is pinned once and every screen can reach it: watch it rotate in Defeating
 * Randomization, walk it down in Locate, filter to it on the radar, alert on it in Signal
 * Watch. Pinning is deliberately not the same as adding it to [TargetStore]. A pin is "this
 * is what I am working on now" and is meant to be replaced constantly; a target is a
 * finding, with the evidence that produced it, and is meant to be kept.
 *
 * It follows the device through address changes, because a pin that went stale every
 * quarter of an hour would be the typing problem again with extra steps.
 */
class CurrentTarget private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("current_target", Context.MODE_PRIVATE)

    private val _pinned = MutableStateFlow(load())
    val pinned: StateFlow<Pinned?> = _pinned

    fun pin(address: String, label: String?, vendor: String?, source: String) {
        val key = address.uppercase()
        _pinned.value = Pinned(
            address = key,
            label = label?.takeIf { it.isNotBlank() },
            vendor = vendor?.takeIf { it.isNotBlank() },
            source = source,
            pinnedAtMs = System.currentTimeMillis(),
            addresses = listOf(key),
        )
        save()
    }

    fun clear() {
        _pinned.value = null
        prefs.edit().clear().apply()
    }

    fun isPinned(address: String): Boolean =
        _pinned.value?.address.equals(address, ignoreCase = true)

    /**
     * Carries the pin onto the address the device has just put on.
     *
     * Called from wherever a rotation is decided rather than deciding one here. Nothing in
     * this class knows how to tell one phone from another, and it should not: a pin
     * following the wrong device is a worse outcome than a pin going stale.
     */
    fun reacquire(oldAddress: String, newAddress: String) {
        val held = _pinned.value ?: return
        if (!held.address.equals(oldAddress, ignoreCase = true)) return
        val key = newAddress.uppercase()
        _pinned.value = held.copy(address = key, addresses = held.addresses + key)
        save()
    }

    private fun save() {
        val held = _pinned.value
        if (held == null) {
            prefs.edit().clear().apply()
            return
        }
        prefs.edit()
            .putString(KEY_ADDRESS, held.address)
            .putString(KEY_LABEL, held.label)
            .putString(KEY_VENDOR, held.vendor)
            .putString(KEY_SOURCE, held.source)
            .putLong(KEY_AT, held.pinnedAtMs)
            .putString(KEY_WORN, held.addresses.joinToString(","))
            .apply()
    }

    private fun load(): Pinned? {
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        val worn = prefs.getString(KEY_WORN, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: listOf(address)
        return Pinned(
            address = address,
            label = prefs.getString(KEY_LABEL, null),
            vendor = prefs.getString(KEY_VENDOR, null),
            source = prefs.getString(KEY_SOURCE, null) ?: "pinned",
            pinnedAtMs = prefs.getLong(KEY_AT, 0L),
            addresses = worn,
        )
    }

    companion object {
        private const val KEY_ADDRESS = "address"
        private const val KEY_LABEL = "label"
        private const val KEY_VENDOR = "vendor"
        private const val KEY_SOURCE = "source"
        private const val KEY_AT = "at"
        private const val KEY_WORN = "worn"

        @Volatile
        private var instance: CurrentTarget? = null

        fun get(context: Context): CurrentTarget = instance ?: synchronized(this) {
            instance ?: CurrentTarget(context).also { instance = it }
        }
    }
}
