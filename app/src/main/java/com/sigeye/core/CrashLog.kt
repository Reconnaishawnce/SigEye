package com.sigeye.core

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One crash that happened on this phone. */
data class CrashReport(
    val file: File,
    val atMs: Long,
    /** The exception class and message, for a list you can scan without opening anything. */
    val headline: String,
) {
    fun stamp(): String = SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(atMs))
}

/**
 * Writes a stack trace to this phone when the app falls over, and nowhere else.
 *
 * There is no crash reporting in this app and there is not going to be, because the whole
 * promise is that what it records about the devices around you does not leave the device.
 * A crash reporter is a network call that fires at the exact moment the app's state is
 * least understood, and bolting one on would quietly break the only claim that matters.
 *
 * The consequence of that, though, is that a crash on somebody else's phone is invisible
 * forever - they see the app vanish, and there is nothing they could send even if they
 * wanted to. So: the trace is written to app-private storage, and sharing it is a button
 * somebody presses.
 *
 * **The writing is not opt-in and the sharing is.** That is a deliberate split. Writing a
 * file into this app's own directory is what a log is; it reaches nobody, survives no
 * backup - `backup_rules.xml` does not include it - and does the person no harm whether
 * they ever look at it or not. Asking permission for it would be theatre, and worse, it
 * would mean the one crash somebody wanted to report is the one that was not recorded.
 * Sending it anywhere is a different act, so that is the one behind a tap.
 *
 * A trace can carry a Bluetooth address if the crash happened somewhere holding one, so the
 * share screen says so before anything is sent.
 *
 * The previous handler is chained rather than replaced. Android's default handler is what
 * actually kills the process and shows the dialog, and an app that swallows that ends up as
 * a frozen black screen instead of a crash.
 */
object CrashLog {

    private const val DIRECTORY = "crashes"

    /** Enough to spot a pattern. Older ones past this are deleted as new ones arrive. */
    const val KEEP = 20

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun reports(context: Context): List<CrashReport> = runCatching {
        directory(context).listFiles()
            ?.filter { it.name.endsWith(".txt") }
            ?.mapNotNull { file ->
                val at = file.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull()
                    ?: return@mapNotNull null
                CrashReport(file, at, headlineOf(file))
            }
            ?.sortedByDescending { it.atMs }
            .orEmpty()
    }.getOrDefault(emptyList())

    fun clear(context: Context) {
        runCatching { directory(context).listFiles()?.forEach { it.delete() } }
    }

    /** Everything, as one file, for sharing. */
    fun bundle(context: Context): File? {
        val reports = reports(context)
        if (reports.isEmpty()) return null
        val file = File(directory(context), "sigeye-crashes.txt")
        runCatching {
            file.writeText(
                buildString {
                    appendLine("# SigEye crash reports")
                    appendLine("# ${reports.size} of them, newest first")
                    appendLine()
                    reports.forEach {
                        appendLine(it.file.readText())
                        appendLine("----")
                        appendLine()
                    }
                },
            )
        }
        return file
    }

    private fun directory(context: Context) =
        File(context.applicationContext.filesDir, DIRECTORY).apply { if (!exists()) mkdirs() }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val now = System.currentTimeMillis()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()

        val text = buildString {
            appendLine("when: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now)))
            appendLine("app: ${appVersion(context)}")
            appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("thread: ${thread.name}")
            appendLine()
            append(trace)
        }

        File(directory(context), "crash-$now.txt").writeText(text)
        prune(context)
    }

    private fun prune(context: Context) {
        val kept = reports(context)
        if (kept.size <= KEEP) return
        kept.drop(KEEP).forEach { runCatching { it.file.delete() } }
    }

    private fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    }.getOrDefault("unknown")

    /** First line of the trace, which is the exception and its message. */
    private fun headlineOf(file: File): String = runCatching {
        file.readLines()
            .dropWhile { it.isNotBlank() }
            .firstOrNull { it.isNotBlank() }
            ?.take(140)
            ?: "Unreadable"
    }.getOrDefault("Unreadable")
}
