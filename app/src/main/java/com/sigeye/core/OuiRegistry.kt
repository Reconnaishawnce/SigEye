package com.sigeye.core

import android.content.Context
import java.util.Locale

/**
 * The whole IEEE MA-L registry, forty thousand prefixes, searched in place.
 *
 * The curated table in [VendorData] covers the vendors this app has something to *say*
 * about, with short readable names. It does not cover the long tail, which is most of what
 * anyone actually walks past - the case that prompted this was an ESP32 whose prefix simply
 * was not in the curated fifty-seven, so the app had nothing to offer about a device it
 * could in fact have named.
 *
 * Held as raw bytes rather than a map: fixed 32-byte records, sorted, binary searched. A
 * `HashMap<String, String>` of the same data is several megabytes of objects for something
 * consulted a few times a second at most.
 */
object OuiRegistry {

    private const val NAME_BYTES = 29
    private const val RECORD_BYTES = 3 + NAME_BYTES
    private const val ASSET = "oui.bin"

    @Volatile
    private var table: ByteArray? = null

    /** True once the full registry is in memory; false in unit tests and before load. */
    val available: Boolean get() = table != null

    /**
     * Reads the asset. Costs about a megabyte of heap and a few milliseconds, so call it
     * off the main thread at startup; every lookup before it finishes simply falls back to
     * the curated table.
     */
    fun load(context: Context) {
        if (table != null) return
        runCatching { context.applicationContext.assets.open(ASSET).use { it.readBytes() } }
            .onSuccess { bytes ->
                if (bytes.isNotEmpty() && bytes.size % RECORD_BYTES == 0) table = bytes
            }
    }

    /** For tests: install a table directly. */
    internal fun installForTest(bytes: ByteArray?) {
        table = bytes
    }

    /**
     * Organisation for an OUI written as `48:CA:43`, or null if it is not assigned.
     */
    fun lookup(oui: String): String? {
        val bytes = table ?: return null
        val parts = oui.split(':', '-')
        if (parts.size < 3) return null
        val first = parts[0].trim().toIntOrNull(16) ?: return null
        val second = parts[1].trim().toIntOrNull(16) ?: return null
        val third = parts[2].trim().toIntOrNull(16) ?: return null
        val key = intArrayOf(first, second, third)

        var low = 0
        var high = bytes.size / RECORD_BYTES - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val at = mid * RECORD_BYTES
            var comparison = 0
            for (index in 0 until 3) {
                comparison = (bytes[at + index].toInt() and 0xFF) - key[index]
                if (comparison != 0) break
            }
            when {
                comparison < 0 -> low = mid + 1
                comparison > 0 -> high = mid - 1
                else -> return String(bytes, at + 3, NAME_BYTES, Charsets.UTF_8)
                    .trim()
                    .takeIf { it.isNotEmpty() && it != "?" }
            }
        }
        return null
    }

    fun lookupAddress(address: String): String? =
        lookup(address.uppercase(Locale.US).replace('-', ':'))
}
