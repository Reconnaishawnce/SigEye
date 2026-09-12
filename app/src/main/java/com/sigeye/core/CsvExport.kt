package com.sigeye.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One way to get a measurement off the phone.
 *
 * Four screens had grown their own copy of the same six lines - make a folder, stamp a
 * name, write the text, hand it to the share sheet - and four more had no way out at all,
 * which is worse: an experiment whose result can only be photographed is an experiment
 * nobody can check. A measurement that cannot leave the phone is a claim rather than a
 * result.
 *
 * The file goes to app-specific external storage, so it survives the share sheet being
 * dismissed and can be pulled over `adb` later, and it is handed on through the same
 * FileProvider the sweep export already uses.
 */
object CsvExport {

    /**
     * Writes [content] under [folder] and offers it to whatever the phone can share with.
     *
     * @param prefix the start of the file name; a timestamp is appended so a second export
     *   never silently replaces the first.
     * @return the file, or null if it could not be written.
     */
    fun shareText(
        context: Context,
        folder: String,
        prefix: String,
        content: String,
    ): File? {
        val file = write(context, folder, prefix, content) ?: return null
        SweepExport.share(context, file)
        return file
    }

    fun write(context: Context, folder: String, prefix: String, content: String): File? {
        val directory = File(context.getExternalFilesDir(null), folder)
        if (!directory.exists() && !directory.mkdirs()) return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(directory, "$prefix-$stamp.csv")
        return runCatching {
            file.writeText(content)
            file
        }.getOrNull()
    }

    /**
     * A header every export carries.
     *
     * A CSV that turns up in a downloads folder six months later should say what it is and
     * when it was taken without anyone having to remember. The comment lines start with a
     * hash, which every spreadsheet and every plotting library skips.
     */
    fun header(what: String, vararg notes: String): String = buildString {
        appendLine("# SigEye $what")
        appendLine("# taken " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        notes.forEach { appendLine("# $it") }
    }
}
