package com.sigeye.core

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** How much a missing thing matters. */
enum class CheckLevel { BLOCKING, DEGRADED, OPTIONAL }

/**
 * One thing that has to be true for the app to work properly.
 *
 * [fix] is what to do about it in words, and [settingsAction] is the system settings screen
 * that does it, where Android exposes one.
 */
data class Check(
    val title: String,
    val passing: Boolean,
    val level: CheckLevel,
    val why: String,
    val fix: String? = null,
    val settingsAction: String? = null,
) {
    val blocking: Boolean get() = !passing && level == CheckLevel.BLOCKING
}

/**
 * Everything the app needs switched on, checked in one place.
 *
 * Scanning fails in a dozen different ways that all look identical from inside an
 * experiment - an empty list - and most of them are a setting rather than a bug. Location
 * services being off stops Bluetooth scan results dead, which surprises everyone; Wi-Fi
 * scan throttling quietly caps you at four scans in two minutes; battery optimisation kills
 * a background watch after a while and never says so.
 *
 * Diagnosing that from inside the experiment is too late. This is the version of the
 * diagnostics panel that runs before anything starts.
 */
object Preflight {

    fun run(context: Context): List<Check> {
        val checks = mutableListOf<Check>()

        checks += bluetoothEnabled(context)
        checks += locationServices(context)
        checks += permissions(context)
        checks += wifiEnabled(context)
        checks += wifiScanThrottling()
        checks += batteryOptimisation(context)
        checks += alertsAudible(context)

        return checks
    }

    fun blockingCount(checks: List<Check>): Int = checks.count { it.blocking }

    private fun bluetoothEnabled(context: Context): Check {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        return Check(
            title = "Bluetooth on",
            passing = adapter?.isEnabled == true,
            level = CheckLevel.BLOCKING,
            why = "Every experiment here reads Bluetooth advertisements.",
            fix = if (adapter == null) {
                "This phone reports no Bluetooth adapter at all."
            } else {
                "Turn Bluetooth on in quick settings."
            },
            settingsAction = Settings.ACTION_BLUETOOTH_SETTINGS,
        )
    }

    /**
     * The one that catches everyone.
     *
     * Android ties BLE scan results to location services being on at the system level, not
     * merely to the location permission being granted. With it off, scans run and return
     * nothing, with no error anywhere.
     */
    private fun locationServices(context: Context): Check {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val on = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager?.isLocationEnabled == true
            } else {
                manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                    manager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            }
        }.getOrDefault(false)

        return Check(
            title = "Location services on",
            passing = on,
            level = CheckLevel.BLOCKING,
            why = "Android returns no Bluetooth scan results at all when location " +
                "services are off. Not fewer - none, silently. Nothing here uses your " +
                "position; the requirement is Android's, not this app's.",
            fix = "Turn on Location in quick settings or system settings.",
            settingsAction = Settings.ACTION_LOCATION_SOURCE_SETTINGS,
        )
    }

    private fun permissions(context: Context): Check {
        val needed = Permissions.required().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        return Check(
            title = "Permissions granted",
            passing = needed.isEmpty(),
            level = CheckLevel.BLOCKING,
            why = "Scanning needs nearby-devices permission, and Android additionally " +
                "requires location permission for it.",
            fix = needed.takeIf { it.isNotEmpty() }?.let { missing ->
                "Missing: " + missing.joinToString(", ") { it.substringAfterLast('.') } +
                    ". Grant them in app settings."
            },
            settingsAction = Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        )
    }

    private fun wifiEnabled(context: Context): Check {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE)
            as? WifiManager
        val enabled = runCatching { wifi?.isWifiEnabled == true }.getOrDefault(false)
        // Deprecated, and not merely noisily: from Android 13 the platform stopped
        // answering this and always returns false, because the setting became the user's
        // rather than something an app may read. Consulting it there would report scanning
        // as unavailable on a phone that is scanning perfectly well, so it is only asked
        // where it can still answer.
        val scanAlways = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            runCatching { wifi?.isScanAlwaysAvailable == true }.getOrDefault(false)
        } else {
            false
        }
        return Check(
            title = "Wi-Fi on",
            passing = enabled || scanAlways,
            level = CheckLevel.DEGRADED,
            why = "Only the Wi-Fi experiments need this. Bluetooth ones work without it.",
            fix = "Turn Wi-Fi on, or enable Wi-Fi scanning in Location settings - that " +
                "second one lets scans run with Wi-Fi otherwise off.",
            settingsAction = Settings.ACTION_WIFI_SETTINGS,
        )
    }

    /**
     * Scan throttling, which cannot be read from inside the app.
     *
     * Android 9 and later caps foreground Wi-Fi scans at four in two minutes. There is no
     * API to query it, so this is reported as advice rather than as a pass or a fail -
     * claiming to have checked something unreadable would be worse than not checking.
     */
    private fun wifiScanThrottling(): Check = Check(
        title = "Wi-Fi scan throttling off",
        passing = true,
        level = CheckLevel.OPTIONAL,
        why = "Android caps Wi-Fi scans at four every two minutes, which makes anything " +
            "that watches Wi-Fi over time very coarse. The app cannot read this setting, " +
            "so it cannot tell you whether you have already turned it off.",
        fix = "Developer options, then turn off 'Wi-Fi scan throttling'.",
        settingsAction = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
    )

    private fun batteryOptimisation(context: Context): Check {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val exempt = runCatching {
            power?.isIgnoringBatteryOptimizations(context.packageName) == true
        }.getOrDefault(false)
        return Check(
            title = "Background scanning allowed",
            passing = exempt,
            level = CheckLevel.DEGRADED,
            why = "Only matters for the experiments that keep running after you leave the " +
                "app - Train Spotter and Signal Watch. Without this Android will stop " +
                "them after a while and not tell you.",
            fix = "Battery settings for this app, then choose Unrestricted.",
            settingsAction = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
        )
    }

    private fun alertsAudible(context: Context): Check {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val volume = runCatching {
            audio?.getStreamVolume(AudioManager.STREAM_ALARM) ?: 0
        }.getOrDefault(0)
        return Check(
            title = "Alerts can be heard",
            passing = volume > 0,
            level = CheckLevel.OPTIONAL,
            why = "Beeps play on the alarm stream so they survive a quiet phone. At zero " +
                "volume they play silently.",
            fix = "Raise alarm volume in sound settings.",
            settingsAction = Settings.ACTION_SOUND_SETTINGS,
        )
    }

    /** The settings screen for a check, or null if it does not name one. */
    fun intentFor(context: Context, check: Check): Intent? {
        val action = check.settingsAction ?: return null
        return Intent(action).apply {
            // Only the app-details screen takes a package. The battery optimisation one
            // is a system-wide list, and handing it a URI makes it fail to resolve.
            if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                data = android.net.Uri.fromParts("package", context.packageName, null)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
