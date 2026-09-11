package com.sigeye.core

import android.content.Context
import android.util.Log
import com.sigeye.experiments.trainspotter.Bin
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Appends one row per closed bin to app-specific external storage, so it is
 * readable over adb without any storage permission:
 *   adb pull /sdcard/Android/data/com.sigeye/files/
 */
class CsvLogger(context: Context) {

    private val dir: File? = context.getExternalFilesDir(null)
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val stampFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    private var writer: FileWriter? = null
    private var openDay: String? = null

    val currentFile: File?
        get() = dir?.let { File(it, "sigeye-${dayFormat.format(Date())}.csv") }

    fun append(bin: Bin) {
        val file = fileFor(bin.startMs) ?: return
        try {
            rotateIfNeeded(file)
            val w = writer ?: return
            w.append(
                buildString {
                    append(stampFormat.format(Date(bin.startMs))).append(',')
                    append(bin.startMs).append(',')
                    append(bin.newCount).append(',')
                    append(bin.activeUnique).append(',')
                    append(String.format(Locale.US, "%.2f", bin.baseline)).append(',')
                    append(if (bin.spike) 1 else 0).append(',')
                    append(bin.label)
                    append('\n')
                }
            )
            w.flush()
        } catch (e: Exception) {
            Log.w(TAG, "CSV append failed", e)
        }
    }

    fun listFiles(): List<File> =
        dir?.listFiles { f -> f.name.startsWith("sigeye-") && f.name.endsWith(".csv") }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * Drops logs older than [days]. One bin every five seconds is about 700 KB a day,
     * which is harmless for a week and less so after a year of unattended running.
     */
    fun pruneOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        listFiles().forEach { file ->
            if (file.name == currentFile?.name) return@forEach
            if (file.lastModified() < cutoff) {
                val deleted = file.delete()
                Log.i(TAG, "Pruned " + file.name + ", deleted=" + deleted)
            }
        }
    }

    fun close() {
        try {
            writer?.flush()
            writer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "CSV close failed", e)
        }
        writer = null
        openDay = null
    }

    private fun fileFor(timeMs: Long): File? =
        dir?.let { File(it, "sigeye-${dayFormat.format(Date(timeMs))}.csv") }

    private fun rotateIfNeeded(file: File) {
        val day = file.name
        if (openDay == day && writer != null) return
        close()
        val isNew = !file.exists() || file.length() == 0L
        writer = FileWriter(file, true)
        if (isNew) writer?.append(HEADER)
        openDay = day
    }

    private companion object {
        const val TAG = "SigEye/Csv"
        const val HEADER = "timestamp,epoch_ms,new_count,active_unique,baseline,spike,label\n"
    }
}
