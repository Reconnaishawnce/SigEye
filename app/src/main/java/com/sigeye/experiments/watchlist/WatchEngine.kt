package com.sigeye.experiments.watchlist

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.sigeye.core.DeviceBook
import com.sigeye.core.ble.Advert

/**
 * Matches advertisements against the watch rules and decides when to shout.
 *
 * Pure of Android UI, and it never touches the radio - it is handed adverts by whatever
 * is running it, so the same engine serves the foreground screen and the background
 * service without either duplicating a scan.
 */
class WatchEngine(
    context: Context,
    private val store: WatchStore,
    private val onAlert: (WatchHit) -> Unit,
) {
    private val book = DeviceBook.get(context)

    /** Last alert time per rule+address, so one arrival is one alert. */
    private val lastAlertMs = HashMap<String, Long>()

    fun onAdvert(advert: Advert) {
        val rules = store.rules.value
        if (rules.isEmpty()) return

        for (rule in rules) {
            if (!rule.matches(advert, book)) continue

            val key = rule.id + "|" + advert.address
            val previous = lastAlertMs[key]
            val cooldown = rule.cooldownSeconds * 1_000L
            if (previous != null && advert.atMs - previous < cooldown) continue
            lastAlertMs[key] = advert.atMs

            val hit = WatchHit(
                ruleId = rule.id,
                ruleLabel = rule.label,
                address = advert.address,
                displayName = book.nicknameOf(advert.address)
                    ?: advert.name?.takeIf { it.isNotBlank() }
                    ?: advert.vendor
                    ?: advert.address,
                rssi = advert.rssi,
                atMs = advert.atMs,
            )
            store.recordHit(hit)
            Log.i(TAG, "watch hit: ${rule.label} -> ${hit.displayName} @ ${advert.rssi}")
            if (rule.notify) onAlert(hit)
        }
    }

    /** Drops cooldown entries for devices not seen in a while, so the map cannot grow. */
    fun prune(nowMs: Long) {
        val cutoff = nowMs - PRUNE_AFTER_MS
        lastAlertMs.entries.removeAll { it.value < cutoff }
    }

    fun reset() = lastAlertMs.clear()

    companion object {
        private const val TAG = "SigEye/Watch"
        private const val PRUNE_AFTER_MS = 60 * 60_000L

        const val CHANNEL_ID = "watch"
        const val NOTIF_BASE = 100

        fun createChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID,
                    "Watch alerts",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Fires when a device on your watchlist comes into range."
                    enableVibration(true)
                },
            )
        }
    }
}
