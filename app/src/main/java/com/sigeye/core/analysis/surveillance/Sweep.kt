package com.sigeye.core.analysis.surveillance

/** One piece of surveillance hardware, as found on a sweep. */
data class Found(
    /**
     * What identifies this one across days.
     *
     * The serial when the device broadcasts one, which is the whole reason a sweep can add
     * up over time. Otherwise the address, which will rotate, so the same camera turns up
     * as a new row tomorrow. That difference is stated on screen rather than hidden.
     */
    val key: String,
    val product: String,
    val operator: String,
    val certainty: Certainty,
    val serial: String?,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyM: Float?,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    /** Strongest reading, which is roughly how close you got to it. */
    val bestRssi: Int,
    val sightings: Int,
) {
    val durable: Boolean get() = serial != null

    val hasPlace: Boolean get() = latitude != null && longitude != null
}

/**
 * Adding up what a walk or a drive found.
 *
 * Only ever holds things [Surveillance] recognized as surveillance hardware. Not a log of
 * everything in range: a file of every phone that passed a car is a record of people's
 * movements, and this exists to record where the cameras are, not who drove past them.
 *
 * A find is kept under its serial where it broadcasts one. Flock's camera batteries do,
 * and that serial does not change when the Bluetooth address does, so passing the same pole
 * next week updates the same row instead of inventing a second camera. Anything without a
 * serial is kept under its address and will duplicate itself once it rotates, which the
 * screen says plainly rather than quietly producing a count that drifts upwards.
 *
 * Pure and Android-free.
 */
object Sweep {

    /**
     * How close two readings must be in time before the better position wins.
     *
     * A find keeps the position it was heard loudest at, because that is the closest you
     * got to it, and that is a better guess at where it is than wherever you happened to
     * be standing the last time you heard it faintly.
     */
    const val MIN_ACCURACY_M = 40f

    fun keyFor(sighting: Sighting, address: String): String =
        sighting.serial ?: address.uppercase()

    /**
     * Folds one sighting into what is already known.
     *
     * @param existing the row for this key, or null for a first find.
     */
    fun fold(
        existing: Found?,
        sighting: Sighting,
        address: String,
        rssi: Int,
        atMs: Long,
        latitude: Double?,
        longitude: Double?,
        accuracyM: Float?,
    ): Found {
        val key = keyFor(sighting, address)
        if (existing == null) {
            return Found(
                key = key,
                product = sighting.product,
                operator = sighting.operator,
                certainty = sighting.certainty,
                serial = sighting.serial,
                latitude = latitude,
                longitude = longitude,
                accuracyM = accuracyM,
                firstSeenMs = atMs,
                lastSeenMs = atMs,
                bestRssi = rssi,
                sightings = 1,
            )
        }

        // The loudest reading is the closest you got, so that is where the thing is. A
        // later, fainter reading taken from across a car park would otherwise overwrite a
        // good position with a worse one.
        val closer = rssi > existing.bestRssi
        val hasPlace = latitude != null && longitude != null

        return existing.copy(
            // A stronger claim can arrive later: the name arrives in one frame and the
            // serial in another, so a possible becomes a confirmed as the packets land.
            product = if (sighting.certainty < existing.certainty) {
                sighting.product
            } else {
                existing.product
            },
            certainty = minOf(sighting.certainty, existing.certainty),
            serial = existing.serial ?: sighting.serial,
            latitude = if (closer && hasPlace) latitude else existing.latitude,
            longitude = if (closer && hasPlace) longitude else existing.longitude,
            accuracyM = if (closer && hasPlace) accuracyM else existing.accuracyM,
            lastSeenMs = maxOf(existing.lastSeenMs, atMs),
            bestRssi = maxOf(existing.bestRssi, rssi),
            sightings = existing.sightings + 1,
        )
    }

    /** Confirmed first, then by how close you got. What somebody would check in that order. */
    fun ordered(found: Collection<Found>): List<Found> =
        found.sortedWith(compareBy({ it.certainty.ordinal }, { -it.bestRssi }))

    /**
     * A row per find, for a spreadsheet.
     *
     * Columns chosen to be pasted somewhere rather than read here. The key first, because
     * that is what a second sweep will match on.
     */
    const val HEADER =
        "key,serial,product,operator,certainty,latitude,longitude,accuracy_m," +
            "first_seen_ms,last_seen_ms,best_rssi,sightings"

    fun csv(found: Collection<Found>): String = buildString {
        appendLine(HEADER)
        ordered(found).forEach { one ->
            appendLine(
                listOf(
                    one.key,
                    one.serial.orEmpty(),
                    one.product,
                    one.operator,
                    one.certainty.label,
                    one.latitude?.let { "%.6f".format(it) }.orEmpty(),
                    one.longitude?.let { "%.6f".format(it) }.orEmpty(),
                    one.accuracyM?.let { "%.0f".format(it) }.orEmpty(),
                    one.firstSeenMs.toString(),
                    one.lastSeenMs.toString(),
                    one.bestRssi.toString(),
                    one.sightings.toString(),
                ).joinToString(",") { field -> field.replace(",", " ") },
            )
        }
    }

    /** What the sweep found, in one line. */
    fun summarize(found: Collection<Found>): String {
        if (found.isEmpty()) return "Nothing found yet."
        val confirmed = found.count { it.certainty == Certainty.CONFIRMED }
        val placed = found.count { it.hasPlace }
        return "${found.size} found, $confirmed confirmed, $placed with a location."
    }
}
