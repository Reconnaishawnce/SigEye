package com.sigeye

import android.app.Application
import com.sigeye.core.CrashLog

/**
 * The first thing that runs, which is why the crash handler is installed here.
 *
 * Installing it in the activity would miss anything that goes wrong before the activity
 * starts, and startup is exactly where a crash is most likely and least explicable. This
 * class exists for that one line, and should stay that small: work done here runs before
 * anything is on screen and is paid for on every cold start.
 */
class SigEyeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
