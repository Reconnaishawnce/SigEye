package com.sigeye.core.evidence

/** One file going into a bundle. */
data class Piece(
    /** Path inside the bundle. */
    val name: String,
    val content: String,
    /** One line saying what this is, for the manifest. */
    val about: String,
)

/** Where a bundle came from, so a reading can be checked against its conditions later. */
data class Provenance(
    val appVersion: String,
    val device: String,
    val androidVersion: String,
    val takenAtMs: Long,
    /**
     * The settings that were in force, as name to value.
     *
     * Read from the running code rather than typed out here, so they cannot drift from
     * what actually produced the numbers.
     */
    val settings: Map<String, String> = emptyMap(),
)

/**
 * Packing up what an experiment produced, along with enough to check it later.
 *
 * Exports were scattered: saved runs went out one way, a sweep another, a capture a third,
 * and a case file as loose text. Each was fine on its own and none of them carried the
 * thing that matters six months later, which is what the app was doing at the time. A
 * distance in a spreadsheet means nothing without the path loss exponent it assumed, and
 * that exponent lives in a setting somebody may since have changed.
 *
 * So a bundle carries the numbers, the raw data they came from where there is any, the app
 * version and phone, the settings in force, and a checksum of every file. The checksums are
 * the part that makes it worth anything to somebody who was not there: a file that does not
 * hash to what the manifest says has been edited since.
 *
 * Pure and Android-free.
 */
object Evidence {

    const val MANIFEST = "MANIFEST.txt"

    /**
     * A plain-text manifest.
     *
     * Text rather than JSON on purpose. This is read by a person deciding whether to trust
     * a folder of numbers, sometimes years later, possibly attached to a records request,
     * and it should not need a tool to open.
     */
    fun manifest(
        provenance: Provenance,
        pieces: List<Piece>,
        hashOf: (Piece) -> String,
    ): String = buildString {
        appendLine("SigEye evidence bundle")
        appendLine()
        appendLine("Taken:    ${provenance.takenAtMs}")
        appendLine("App:      SigEye ${provenance.appVersion}")
        appendLine("Phone:    ${provenance.device}")
        appendLine("Android:  ${provenance.androidVersion}")
        appendLine()

        appendLine("CONTENTS")
        pieces.forEach { piece ->
            appendLine("  ${piece.name}")
            appendLine("    ${piece.about}")
            appendLine("    sha256 ${hashOf(piece)}")
        }
        appendLine()

        if (provenance.settings.isNotEmpty()) {
            appendLine("SETTINGS IN FORCE")
            appendLine(
                "  What the app was doing when these numbers were produced. A reading " +
                    "cannot be checked without them.",
            )
            provenance.settings.toSortedMap().forEach { (name, value) ->
                appendLine("  $name = $value")
            }
            appendLine()
        }

        appendLine("CHECKING THIS")
        appendLine(
            "  Every file above is listed with its SHA-256. If a file does not hash to " +
                "the value here, it has been changed since the bundle was made.",
        )
        appendLine()
        appendLine("LIMITATIONS")
        appendLine(
            "  A phone is not a calibrated instrument. Signal strengths are whole " +
                "decibels with no calibration, and two phones will not agree. These " +
                "numbers are good for comparing readings taken on this phone and poor as " +
                "absolute measurements.",
        )
    }

    /** A file name that sorts by date and does not collide. */
    fun fileName(takenAtMs: Long): String = "sigeye-evidence-$takenAtMs.zip"

    /** Whether there is anything worth packing. */
    fun worthExporting(pieces: List<Piece>): Boolean =
        pieces.any { it.content.isNotBlank() }

    /** One line for a button, so nobody exports an empty folder by accident. */
    fun summarize(pieces: List<Piece>): String = when {
        pieces.isEmpty() -> "Nothing saved to export yet."
        else -> "${pieces.size} file${if (pieces.size == 1) "" else "s"}, " +
            "plus a manifest with checksums."
    }
}
