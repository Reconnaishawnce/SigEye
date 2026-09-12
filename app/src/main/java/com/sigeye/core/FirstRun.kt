package com.sigeye.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the welcome card has been read and put away.
 *
 * One flag rather than a wizard. A sequence of full-screen pages before someone is allowed
 * to use the app is a tax on everyone who would have worked it out in ten seconds, and it
 * is read once and forgotten by the people it was written for. A card at the top of the
 * home screen that explains the shape of the thing and then gets dismissed costs nobody
 * anything.
 */
class FirstRun private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("firstrun", Context.MODE_PRIVATE)

    private val _dismissed = MutableStateFlow(prefs.getBoolean(KEY_DISMISSED, false))
    val dismissed: StateFlow<Boolean> = _dismissed

    private val _backupWarned = MutableStateFlow(prefs.getBoolean(KEY_BACKUP_WARNED, false))

    /**
     * Whether the backup disclosure has been shown.
     *
     * Separate from the welcome card, and shown after it. Two dialogs at once is one
     * dialog too many, and this one is the more important of the pair - so it gets the
     * user's attention on its own rather than as the second half of a greeting.
     */
    val backupWarned: StateFlow<Boolean> = _backupWarned

    fun dismiss() {
        _dismissed.value = true
        prefs.edit().putBoolean(KEY_DISMISSED, true).apply()
    }

    fun markBackupWarned() {
        _backupWarned.value = true
        prefs.edit().putBoolean(KEY_BACKUP_WARNED, true).apply()
    }

    /** Lets the disclosure be reopened from a settings screen later. */
    fun resetBackupWarning() {
        _backupWarned.value = false
        prefs.edit().putBoolean(KEY_BACKUP_WARNED, false).apply()
    }

    companion object {
        private const val KEY_DISMISSED = "dismissed"
        private const val KEY_BACKUP_WARNED = "backup_warned"

        @Volatile
        private var instance: FirstRun? = null

        fun get(context: Context): FirstRun =
            instance ?: synchronized(this) {
                instance ?: FirstRun(context).also { instance = it }
            }
    }
}
