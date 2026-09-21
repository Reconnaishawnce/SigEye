package com.sigeye.core.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * Packing up numbers with enough to check them later.
 *
 * The exports were already fine at carrying numbers. What none of them carried is what the
 * app was doing at the time, and a distance without the path loss exponent it assumed is
 * not a measurement, it is a number.
 */
class EvidenceTest {

    private val provenance = Provenance(
        appVersion = "0.1.42",
        device = "Google Pixel 8",
        androidVersion = "Android 14 (API 34)",
        takenAtMs = 1_700_000_000_000L,
        settings = mapOf(
            "handoff.autoShare" to "0.72",
            "follow.dropAfterMs" to "60000",
        ),
    )

    private val pieces = listOf(
        Piece("runs/body.csv", "run,figure,value\nkitchen,Shadow,8.0 dB\n", "Saved runs."),
        Piece("sweep/found.csv", "key,serial\nTN720,TN720\n", "Cameras found."),
    )

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun manifest() = Evidence.manifest(provenance, pieces) { sha256(it.content) }

    // ------------------------------------------------------------------ what it carries

    @Test
    fun `the manifest names the app, the phone and when`() {
        val text = manifest()

        assertTrue(text.contains("0.1.42"))
        assertTrue(text.contains("Google Pixel 8"))
        assertTrue(text.contains("API 34"))
        assertTrue(text.contains("1700000000000"))
    }

    @Test
    fun `every file is listed with what it is`() {
        val text = manifest()

        pieces.forEach { piece ->
            assertTrue("${piece.name} missing", text.contains(piece.name))
            assertTrue("${piece.about} missing", text.contains(piece.about))
        }
    }

    @Test
    fun `the settings in force are written down`() {
        // The whole point. Without these, a reading cannot be checked against the
        // conditions that produced it.
        val text = manifest()

        assertTrue(text.contains("handoff.autoShare = 0.72"))
        assertTrue(text.contains("follow.dropAfterMs = 60000"))
    }

    @Test
    fun `settings are listed in a fixed order so two bundles can be compared`() {
        val jumbled = provenance.copy(
            settings = mapOf("z.last" to "1", "a.first" to "2", "m.middle" to "3"),
        )
        val text = Evidence.manifest(jumbled, pieces) { sha256(it.content) }

        val order = listOf("a.first", "m.middle", "z.last").map { text.indexOf(it) }
        assertEquals(order.sorted(), order)
    }

    // ------------------------------------------------------------------ the checksums

    @Test
    fun `each file carries a checksum somebody can verify`() {
        val text = manifest()

        pieces.forEach { piece ->
            assertTrue("no hash for ${piece.name}", text.contains(sha256(piece.content)))
        }
    }

    @Test
    fun `changing one byte changes that file's checksum`() {
        // Which is the only thing making a bundle worth anything to somebody who was not
        // there when it was made.
        val original = sha256(pieces.first().content)
        val edited = sha256(pieces.first().content.replace("8.0", "9.0"))

        assertTrue(original != edited)
    }

    @Test
    fun `the manifest says how to check it`() {
        assertTrue(manifest().contains("SHA-256"))
    }

    // ------------------------------------------------------------------ the limits

    @Test
    fun `the manifest admits a phone is not an instrument`() {
        // It travels with the file rather than living in a README nobody exports, because
        // the bundle is the thing that gets sent to somebody else.
        val text = manifest()

        assertTrue(text.contains("LIMITATIONS"))
        assertTrue(text.contains("not a calibrated instrument"))
    }

    // ------------------------------------------------------------------ not exporting nothing

    @Test
    fun `an empty set of files is not worth exporting`() {
        assertTrue(!Evidence.worthExporting(emptyList()))
        assertTrue(!Evidence.worthExporting(listOf(Piece("empty.csv", "", "Nothing."))))
        assertTrue(Evidence.worthExporting(pieces))
    }

    @Test
    fun `the summary counts the files and says the manifest is extra`() {
        assertTrue(Evidence.summarize(pieces).contains("2 files"))
        assertTrue(Evidence.summarize(pieces).contains("manifest"))
        assertTrue(Evidence.summarize(emptyList()).contains("Nothing saved"))
    }

    @Test
    fun `two bundles taken at different moments do not collide`() {
        assertTrue(Evidence.fileName(1L) != Evidence.fileName(2L))
        assertTrue(Evidence.fileName(1L).endsWith(".zip"))
    }
}
