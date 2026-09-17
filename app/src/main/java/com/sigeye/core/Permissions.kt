package com.sigeye.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {

    /** Everything we ask for up front, tailored to the running API level. */
    fun required(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        // Still needed on 12+ because we scan unfiltered without neverForLocation,
        // and it is the only way to get results at all on 26-30.
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Doppler Walk's step counter. Not blocking - every other experiment works
        // without it, and that one falls back to entering the distance by hand.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }.toTypedArray()

    /**
     * The subset we genuinely cannot scan without.
     *
     * Notably NOT BLUETOOTH_CONNECT: scanning needs only BLUETOOTH_SCAN, and gating on
     * CONNECT would let a user brick the app by declining a permission it never uses.
     * It is still requested, for reading device names in later experiments.
     * POST_NOTIFICATIONS is likewise optional - a denied notification costs the alert,
     * not the data.
     */
    fun blocking(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }.toTypedArray()

    /**
     * What Blink needs on top of everything else.
     *
     * Kept out of [required] deliberately. Thirty-five experiments listen and one
     * transmits, and asking every user of this app for permission to broadcast because one
     * screen does would be asking for more than the app generally needs.
     */
    fun advertising(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyArray()
        }

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun canScan(context: Context): Boolean = blocking().all { granted(context, it) }

    fun missing(context: Context): List<String> = required().filterNot { granted(context, it) }
}
