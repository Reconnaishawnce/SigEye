package com.sigeye.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.sigeye.BuildConfig

/**
 * Opening a mail app with a report already half written.
 *
 * A bug report that does not say which build it came from, on what phone, running what
 * version of Android, is a report nobody can act on - and asking a user to go and find
 * those three things is asking them not to bother. They are filled in here.
 *
 * Nothing else is. No device list, no scan results, no identifiers of anything the app has
 * seen: an app that records the people around you should not put any of that in an email
 * without being asked, and there is nothing in the three lines below that is not already
 * printed on the box.
 */
object Contact {

    const val ADDRESS = "abels023@umn.edu"

    enum class Kind(val subject: String, val opening: String) {
        BUG(
            "SigEye bug report",
            "What happened, and what you expected instead:",
        ),
        SUGGESTION(
            "SigEye suggestion",
            "What you would like it to do:",
        ),
        EXPERIMENT(
            "SigEye experiment idea",
            "An experiment you would like to see, and what you think it would show:",
        ),
    }

    /**
     * Builds the mail intent. Returns null if nothing on the phone can send mail, so the
     * caller can say so rather than firing an intent into nowhere.
     */
    fun mail(context: Context, kind: Kind): Intent? {
        val body = buildString {
            appendLine(kind.opening)
            appendLine()
            appendLine()
            appendLine("---")
            appendLine("SigEye ${BuildConfig.VERSION_NAME}")
            appendLine("${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        }
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$ADDRESS")
            putExtra(Intent.EXTRA_SUBJECT, kind.subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        return if (intent.resolveActivity(context.packageManager) != null) intent else null
    }

    /** @return false if the phone has no mail app, so the screen can say so. */
    fun send(context: Context, kind: Kind): Boolean {
        val intent = mail(context, kind) ?: return false
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }
}
