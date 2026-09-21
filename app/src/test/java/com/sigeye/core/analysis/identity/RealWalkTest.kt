package com.sigeye.core.analysis.identity

import com.sigeye.core.ble.AdvertCodec
import com.sigeye.core.ble.shape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The part of the calibration that needs a real street.
 *
 * [CalibrationTest] found that a model cannot settle these thresholds: the failures they
 * defend against are reflections, dropped scan results and two phones that genuinely rotate
 * in the same instant, and a model built from guesses about those cannot measure them. This
 * runs the same [Trial] over a capture recorded on an actual walk, where somebody wrote
 * down which device was theirs.
 *
 * It skips when there is no capture, rather than failing, because the repository does not
 * carry one and should not: a capture of a real street is a record of other people's
 * devices, and committing one would be publishing their movements to make a test go green.
 *
 * ## Adding a walk
 *
 * 1. In the app, Settings, Capture and replay, start recording. Then open Identity and do
 *    a follow, so the radio is actually on.
 * 2. Walk for twenty minutes with somebody who agreed to it, through at least two of the
 *    target's rotations.
 * 3. Stop recording and pull the file off the phone. It lands in the app's external files
 *    directory under captures.
 * 4. Put it at `app/src/test/resources/walks/<name>.csv`.
 * 5. Beside it, `<name>.truth`, one handover per line, `oldAddress -> newAddress`, taken
 *    from the follow's own stitch log or from the case file it writes. Blank lines and
 *    lines starting with # are ignored.
 *
 * Then this runs, and the sweeps in [CalibrationTest] can be pointed at something that
 * knows what a wrong follow looks like.
 */
class RealWalkTest {

    @Test
    fun `recorded walks are followed as well as the shipped settings claim`() {
        val walks = walks()
        if (walks.isEmpty()) {
            println(
                "No recorded walks. The rotation thresholds are currently justified by " +
                    "reasoning and a model that cannot test them - see CalibrationTest. " +
                    "Drop a capture and its truth file into src/test/resources/walks to " +
                    "change that; the header of this file says how.",
            )
            return
        }

        walks.forEach { (name, walk) ->
            val score = Trial.run(walk)
            println("$name: $score")

            assertTrue(
                "$name: followed none of its ${walk.truth.size} handovers. $score",
                score.followed > 0,
            )
            assertEquals(
                "$name: took a wrong rotation, which is the failure that matters. $score",
                0,
                score.wrong,
            )
        }
    }

    @Test
    fun `a walk that is present is readable and has its answer beside it`() {
        // A capture with a malformed or missing truth file would otherwise be silently
        // skipped, and a calibration that quietly tests nothing is the worst outcome here.
        captures().forEach { file ->
            val truth = File(file.parentFile, file.nameWithoutExtension + ".truth")
            assertTrue(
                "${file.name} has no ${truth.name} beside it, so nothing knows what the " +
                    "right answer was",
                truth.exists(),
            )
            assertTrue(
                "${truth.name} lists no handovers",
                handovers(truth).isNotEmpty(),
            )
        }
    }

    // ------------------------------------------------------------------ reading them

    private fun directory(): File = File("src/test/resources/walks")

    private fun captures(): List<File> =
        directory().listFiles()?.filter { it.extension == "csv" }.orEmpty().sortedBy { it.name }

    private fun handovers(truth: File): List<Pair<String, String>> =
        truth.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split("->").map { it.trim().uppercase() }
                if (parts.size == 2 && parts.all { it.isNotEmpty() }) {
                    parts[0] to parts[1]
                } else {
                    null
                }
            }

    private fun walks(): List<Pair<String, WalkModel.Walk>> = captures().mapNotNull { file ->
        val truth = File(file.parentFile, file.nameWithoutExtension + ".truth")
        if (!truth.exists()) return@mapNotNull null

        val packets = file.readLines()
            .drop(1) // the header AdvertCodec writes
            .mapNotNull { AdvertCodec.decode(it, 0L) }
            .map { advert ->
                WalkModel.Packet(
                    address = advert.address.uppercase(),
                    atMs = advert.atMs,
                    rssi = advert.rssi,
                    shape = advert.shape(),
                )
            }
            .sortedBy { it.atMs }

        if (packets.isEmpty()) return@mapNotNull null
        file.nameWithoutExtension to WalkModel.Walk(packets, handovers(truth))
    }
}
