package com.sigeye.core

import android.content.Context
import com.sigeye.core.analysis.Density
import com.sigeye.core.analysis.Environment
import com.sigeye.core.analysis.Tuning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The settings that apply to the whole app rather than to one experiment.
 *
 * Two kinds of setting live in this app and they want different treatment. A handful are
 * app-wide - which devices are muted everywhere, how busy the app should assume the
 * surroundings are - and belong in one place a user can find. The rest are specific to one
 * experiment, belong on that experiment's screen where the reading they affect is visible,
 * and would be meaningless in a list.
 *
 * This holds the first kind. The density profile is the interesting part: it does not
 * change any measurement, it changes how long several experiments wait before they are
 * willing to say something, which is a judgement about surroundings rather than about
 * physics. Every value it sets stays individually visible, and the profile in force is
 * written into every export so a file from six months ago still says what produced it.
 */
class SettingsStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _density = MutableStateFlow(loadDensity())
    val density: StateFlow<Density> = _density

    private val _detected = MutableStateFlow(prefs.getBoolean(KEY_DETECTED, false))

    /** Whether the current profile was measured or chosen by hand. */
    val detected: StateFlow<Boolean> = _detected

    val tuning: Tuning get() = Environment.tuningFor(_density.value)

    fun setDensity(density: Density, detected: Boolean) {
        _density.value = density
        _detected.value = detected
        prefs.edit()
            .putString(KEY_DENSITY, density.name)
            .putBoolean(KEY_DETECTED, detected)
            .apply()
    }

    /** One line for a CSV header, so an exported measurement says what settings made it. */
    fun exportNote(): String =
        "density=${_density.value.name.lowercase()} " +
            "(${if (_detected.value) "measured" else "chosen"})"

    private fun loadDensity(): Density {
        val stored = prefs.getString(KEY_DENSITY, null) ?: return Environment.DEFAULT
        return runCatching { Density.valueOf(stored) }.getOrDefault(Environment.DEFAULT)
    }

    companion object {
        private const val KEY_DENSITY = "density"
        private const val KEY_DETECTED = "density_detected"

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context).also { instance = it }
            }
    }
}
