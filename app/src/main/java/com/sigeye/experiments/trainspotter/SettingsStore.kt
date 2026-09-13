package com.sigeye.experiments.trainspotter

import android.content.Context
import android.content.SharedPreferences

/** Plain SharedPreferences - a handful of ints does not justify a DataStore dependency. */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("trainspotter", Context.MODE_PRIVATE)

    private val settings = com.sigeye.core.SettingsStore.get(context)

    /**
     * The saved settings, with anything never touched taken from the surroundings profile.
     *
     * What counts as a burst is a judgement about where you are rather than a fact about
     * radio: four new devices in ninety seconds is a train past a cottage and is background
     * noise on a concourse. So the two burst settings default to the profile rather than to
     * a constant - and stop doing so the moment somebody sets one by hand, because a slider
     * a user has moved should not be moved back by a profile.
     */
    fun load(): PulseConfig {
        val d = PulseConfig.DEFAULT
        val tuning = settings.tuning
        return PulseConfig(
            binSeconds = prefs.getInt(KEY_BIN_SECONDS, d.binSeconds),
            windowMinutes = prefs.getInt(KEY_WINDOW_MINUTES, d.windowMinutes),
            rssiFloor = prefs.getInt(KEY_RSSI_FLOOR, d.rssiFloor),
            historyMinutes = prefs.getInt(KEY_HISTORY_MINUTES, d.historyMinutes),
            enrollmentSeconds = prefs.getInt(KEY_ENROLLMENT_SECONDS, d.enrollmentSeconds),
            warmupSeconds = prefs.getInt(KEY_WARMUP_SECONDS, d.warmupSeconds),
            baselineBins = prefs.getInt(KEY_BASELINE_BINS, d.baselineBins),
            spikeFactor = prefs
                .getFloat(KEY_SPIKE_FACTOR, tuning.burstMultiple.toFloat())
                .toDouble(),
            spikeMinCount = prefs.getInt(KEY_SPIKE_MIN_COUNT, tuning.minimumBurst),
            alertCooldownSeconds = prefs.getInt(KEY_COOLDOWN, d.alertCooldownSeconds),
            scanCycleMinutes = prefs.getInt(KEY_SCAN_CYCLE_MINUTES, d.scanCycleMinutes),
        )
    }

    fun save(config: PulseConfig) {
        prefs.edit()
            .putInt(KEY_BIN_SECONDS, config.binSeconds)
            .putInt(KEY_WINDOW_MINUTES, config.windowMinutes)
            .putInt(KEY_RSSI_FLOOR, config.rssiFloor)
            .putInt(KEY_HISTORY_MINUTES, config.historyMinutes)
            .putInt(KEY_ENROLLMENT_SECONDS, config.enrollmentSeconds)
            .putInt(KEY_WARMUP_SECONDS, config.warmupSeconds)
            .putInt(KEY_BASELINE_BINS, config.baselineBins)
            .putFloat(KEY_SPIKE_FACTOR, config.spikeFactor.toFloat())
            .putInt(KEY_SPIKE_MIN_COUNT, config.spikeMinCount)
            .putInt(KEY_COOLDOWN, config.alertCooldownSeconds)
            .putInt(KEY_SCAN_CYCLE_MINUTES, config.scanCycleMinutes)
            .apply()
    }

    private companion object {
        const val KEY_BIN_SECONDS = "bin_seconds"
        const val KEY_WINDOW_MINUTES = "window_minutes"
        const val KEY_RSSI_FLOOR = "rssi_floor"
        const val KEY_HISTORY_MINUTES = "history_minutes"
        const val KEY_ENROLLMENT_SECONDS = "enrollment_seconds"
        const val KEY_WARMUP_SECONDS = "warmup_seconds"
        const val KEY_BASELINE_BINS = "baseline_bins"
        const val KEY_SPIKE_FACTOR = "spike_factor"
        const val KEY_SPIKE_MIN_COUNT = "spike_min_count"
        const val KEY_COOLDOWN = "alert_cooldown_seconds"
        const val KEY_SCAN_CYCLE_MINUTES = "scan_cycle_minutes"
    }
}
