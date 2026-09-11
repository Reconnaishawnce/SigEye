package com.blepulse.scan

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
    }.toTypedArray()

    /** The subset we genuinely cannot scan without. Notifications are optional. */
    fun blocking(): Array<String> = required()
        .filterNot { it == Manifest.permission.POST_NOTIFICATIONS }
        .toTypedArray()

    fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun canScan(context: Context): Boolean = blocking().all { granted(context, it) }

    fun missing(context: Context): List<String> = required().filterNot { granted(context, it) }
}
