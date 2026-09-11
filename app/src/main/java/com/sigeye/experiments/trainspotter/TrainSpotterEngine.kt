package com.sigeye.experiments.trainspotter

import android.content.Context
import android.util.Log
import com.sigeye.core.CsvLogger
import com.sigeye.core.ble.Advert
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.ble.ScanHealth

/**
 * The Train Spotter, with the radio taken out of it.
 *
 * Everything that used to live in its service - the aggregator, the CSV, the alert
 * cooldown - is here, driven by adverts handed in from [BleScanHub]. The service now only
 * owns the notification and the lifecycle, so the watchlist can run beside it on the same
 * scan rather than starting a second one.
 *
 * Single-threaded by contract: the service confines every call to one coroutine, so
 * nothing here needs locking.
 */
class TrainSpotterEngine(
    context: Context,
    private val onSpike: (Bin) -> Unit,
    private val onBinClosed: () -> Unit,
) {
    private val settings = SettingsStore(context)
    private val csv = CsvLogger(context)

    private val aggregator = PulseAggregator(settings.load())
    private var config: PulseConfig = aggregator.config

    private var nextCloseAtMs = 0L
    private var lastAlertMs = 0L

    fun start(nowMs: Long) {
        config = settings.load()
        aggregator.reconfigure(config)
        aggregator.reset()
        csv.pruneOlderThan(CSV_RETENTION_DAYS)
        BleScanHub.cycleMillis = config.scanCycleMillis
        lastAlertMs = 0L
        nextCloseAtMs = nowMs + config.binMillis

        PulseState.update {
            it.copy(
                running = true,
                bins = emptyList(),
                currentCount = 0,
                activeUnique = 0,
                baseline = 0.0,
                phase = Phase.ENROLL,
                secondsUntilArmed = config.armedAfterBins * config.binSeconds,
                armingProgress = 0f,
                advertsPerSecond = 0.0,
                referenceRate = 0.0,
                totalAdvertisements = 0,
                scanRestarts = 0,
                config = config,
                error = null,
                csvPath = csv.currentFile?.absolutePath,
            )
        }
    }

    fun reloadConfig() {
        config = settings.load()
        aggregator.reconfigure(config)
        BleScanHub.cycleMillis = config.scanCycleMillis
        PulseState.update { it.copy(config = config) }
    }

    fun markLabel(label: String) = aggregator.markLabel(label)

    fun onAdvert(advert: Advert) {
        aggregator.observe(advert.address, advert.rssi, advert.atMs)
    }

    /** Called on the service tick. Closes a bin when one is due. */
    fun tick(nowMs: Long) {
        // A backwards clock jump would otherwise park the next boundary in the far
        // future and stall binning indefinitely.
        if (nextCloseAtMs - nowMs > config.binMillis * 2) {
            Log.w(TAG, "Clock moved backwards, resyncing bin schedule")
            nextCloseAtMs = nowMs + config.binMillis
        }
        if (nowMs >= nextCloseAtMs) {
            nextCloseAtMs = nowMs + config.binMillis
            aggregator.closeBin(nowMs)?.let { bin ->
                csv.append(bin)
                Log.d(
                    TAG,
                    "bin new=${bin.newCount} active=${bin.activeUnique} " +
                        "baseline=${"%.1f".format(bin.baseline)} " +
                        "phase=${bin.phase.csv()} spike=${bin.spike}",
                )
                if (bin.spike) maybeAlert(bin, nowMs)
                onBinClosed()
            }
        }
    }

    fun publish(health: ScanHealth) {
        PulseState.update {
            it.copy(
                running = true,
                bins = aggregator.history(),
                currentCount = aggregator.currentCount(),
                activeUnique = aggregator.activeUnique(),
                baseline = aggregator.computeBaseline(),
                phase = aggregator.phase(),
                secondsUntilArmed = aggregator.secondsUntilArmed(),
                armingProgress = aggregator.armingProgress(),
                totalAdvertisements = aggregator.totalAdvertisements,
                advertsPerSecond = health.advertsPerSecond,
                referenceRate = health.referenceRate,
                scanRestarts = health.restarts,
                error = health.error,
                csvPath = csv.currentFile?.absolutePath,
            )
        }
    }

    fun stop() {
        csv.close()
        PulseState.update { it.copy(running = false, currentCount = 0) }
    }

    private fun maybeAlert(bin: Bin, nowMs: Long) {
        if (nowMs - lastAlertMs < config.alertCooldownMillis) return
        lastAlertMs = nowMs
        PulseState.update { it.copy(lastAlertMs = nowMs) }
        onSpike(bin)
    }

    companion object {
        private const val TAG = "SigEye/Train"
        private const val CSV_RETENTION_DAYS = 30
    }
}
