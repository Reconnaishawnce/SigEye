package com.sigeye.experiments.trainspotter

import android.content.Context
import android.content.SharedPreferences

/** Plain SharedPreferences - a handful of ints does not justify a DataStore dependency. */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("trainspotter", Context.MODE_PRIVATE)

    fun load(): PulseConfig {
        val d = PulseConfig.DEFAULT
        return PulseConfig(
            binSeconds = prefs.getInt(KEY_BIN_SECONDS, d.binSeconds),
            windowMinutes = prefs.getInt(KEY_WINDOW_MINUTES, d.windowMinutes),
            rssiFloor = prefs.getInt(KEY_RSSI_FLOOR, d.rssiFloor),
            historyMinutes = prefs.getInt(KEY_HISTORY_MINUTES, d.historyMinutes),
            baselineBins = prefs.getInt(KEY_BASELINE_BINS, d.baselineBins),
            warmupBins = prefs.getInt(KEY_WARMUP_BINS, d.warmupBins),
            spikeFactor = prefs.getFloat(KEY_SPIKE_FACTOR, d.spikeFactor.toFloat()).toDouble(),
            spikeMinCount = prefs.getInt(KEY_SPIKE_MIN_COUNT, d.spikeMinCount),
            alertCooldownSeconds = prefs.getInt(KEY_COOLDOWN, d.alertCooldownSeconds),
        )
    }

    fun save(config: PulseConfig) {
        prefs.edit()
            .putInt(KEY_BIN_SECONDS, config.binSeconds)
            .putInt(KEY_WINDOW_MINUTES, config.windowMinutes)
            .putInt(KEY_RSSI_FLOOR, config.rssiFloor)
            .putInt(KEY_HISTORY_MINUTES, config.historyMinutes)
            .putInt(KEY_BASELINE_BINS, config.baselineBins)
            .putInt(KEY_WARMUP_BINS, config.warmupBins)
            .putFloat(KEY_SPIKE_FACTOR, config.spikeFactor.toFloat())
            .putInt(KEY_SPIKE_MIN_COUNT, config.spikeMinCount)
            .putInt(KEY_COOLDOWN, config.alertCooldownSeconds)
            .apply()
    }

    private companion object {
        const val KEY_BIN_SECONDS = "bin_seconds"
        const val KEY_WINDOW_MINUTES = "window_minutes"
        const val KEY_RSSI_FLOOR = "rssi_floor"
        const val KEY_HISTORY_MINUTES = "history_minutes"
        const val KEY_BASELINE_BINS = "baseline_bins"
        const val KEY_WARMUP_BINS = "warmup_bins"
        const val KEY_SPIKE_FACTOR = "spike_factor"
        const val KEY_SPIKE_MIN_COUNT = "spike_min_count"
        const val KEY_COOLDOWN = "alert_cooldown_seconds"
    }
}
