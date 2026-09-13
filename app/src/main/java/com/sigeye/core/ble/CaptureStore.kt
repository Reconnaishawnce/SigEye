package com.sigeye.core.ble

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A saved capture, as the screen needs to list it. */
data class Capture(
    val file: File,
    val packets: Int,
    val spanMs: Long,
    val savedAtMs: Long,
) {
    val name: String get() = file.name

    fun describe(): String = buildString {
        append("$packets packets")
        if (spanMs > 0) append(" over ${spanMs / 1000} s")
        append(" · ")
        append(SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(savedAtMs)))
    }
}

/**
 * Recording the raw advertisement stream to a file, and reading one back.
 *
 * The recording half is a passenger on whatever is already scanning, like the follower is:
 * it never turns the radio on, so arming it costs nothing until an experiment is open.
 *
 * Files go to app-specific external storage, which means they can be pulled off with adb
 * or shared out of the app, and they are plain text - the point of a capture is that
 * somebody who is not holding the phone can look at it.
 */
class CaptureStore private constructor(context: Context) {

    private val directory = File(context.applicationContext.getExternalFilesDir(null), "captures")

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording

    private val _packets = MutableStateFlow(0)
    val packets: StateFlow<Int> = _packets

    private var writer: java.io.BufferedWriter? = null
    private var startMs = 0L

    @Synchronized
    fun start(): Boolean {
        if (_recording.value) return true
        if (!directory.exists() && !directory.mkdirs()) return false
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(directory, "capture-$stamp.csv")
        return runCatching {
            writer = file.bufferedWriter().also { out ->
                out.write("# SigEye capture v${AdvertCodec.VERSION}\n")
                out.write("# started ${Date()}\n")
                out.write(AdvertCodec.HEADER + "\n")
            }
            startMs = System.currentTimeMillis()
            _packets.value = 0
            _recording.value = true
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun stop() {
        runCatching { writer?.flush(); writer?.close() }
        writer = null
        _recording.value = false
    }

    @Synchronized
    fun write(advert: Advert) {
        val out = writer ?: return
        runCatching {
            out.write(AdvertCodec.encode(advert, startMs))
            out.write("\n")
            _packets.value = _packets.value + 1
        }
    }

    /** Newest first. */
    fun list(): List<Capture> {
        val files = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".csv") }
            ?: return emptyList()
        return files
            .map { file ->
                val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
                Capture(
                    file = file,
                    packets = lines.count {
                        val t = it.trim()
                        t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("t_ms")
                    },
                    spanMs = AdvertCodec.spanMs(lines),
                    savedAtMs = file.lastModified(),
                )
            }
            .sortedByDescending { it.savedAtMs }
    }

    /** Every advertisement in a capture, timed from [baseMs]. */
    fun read(file: File, baseMs: Long): List<Advert> =
        runCatching { file.readLines() }.getOrDefault(emptyList())
            .mapNotNull { AdvertCodec.decode(it, baseMs) }

    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)

    companion object {
        @Volatile
        private var instance: CaptureStore? = null

        fun get(context: Context): CaptureStore =
            instance ?: synchronized(this) {
                instance ?: CaptureStore(context).also { instance = it }
            }
    }
}
