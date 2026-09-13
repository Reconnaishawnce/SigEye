package com.sigeye.core

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.sigeye.core.analysis.rf.HeadingSample
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes a sweep out as CSV and hands it to whatever the phone can share with.
 *
 * Every reading, in arrival order, with nothing aggregated away - because the point is to
 * let someone else look at the raw turn and work out what went wrong, which no amount of
 * on-screen summary can substitute for.
 */
object SweepExport {

    fun write(context: Context, source: String, readings: List<HeadingSample>): File? {
        val directory = File(context.getExternalFilesDir(null), "sweeps")
        if (!directory.exists() && !directory.mkdirs()) return null

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(directory, "sweep-$stamp.csv")

        return runCatching {
            file.bufferedWriter().use { out ->
                out.write("# SigEye body absorption sweep\n")
                out.write("# source=$source readings=${readings.size}\n")
                out.write("elapsed_ms,heading_deg,rssi_dbm\n")
                val first = readings.firstOrNull()?.atMs ?: 0L
                readings.forEach {
                    out.write(
                        String.format(
                            Locale.US,
                            "%d,%.1f,%d\n",
                            it.atMs - first,
                            it.headingDegrees,
                            it.rssi,
                        ),
                    )
                }
            }
            file
        }.getOrNull()
    }

    /**
     * A share sheet for one exported file.
     *
     * The type and the chooser title are arguments because this is now the only share in
     * the app and not everything it sends is a sweep. A crash trace announced as text/csv
     * offers to open in a spreadsheet, which is a small lie that wastes somebody's time.
     */
    fun share(
        context: Context,
        file: File,
        mime: String = "text/csv",
        title: String = "Send sweep",
    ) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, title))
        }
    }
}
