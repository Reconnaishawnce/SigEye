package com.sigeye.core.evidence

import android.content.Context
import android.os.Build
import com.sigeye.BuildConfig
import com.sigeye.core.Clock
import com.sigeye.core.Experiments
import com.sigeye.core.RunStore
import com.sigeye.core.SweepStore
import com.sigeye.core.analysis.Crowd
import com.sigeye.core.analysis.identity.AskPolicy
import com.sigeye.core.analysis.identity.Handoffs
import com.sigeye.core.analysis.identity.MyKit
import com.sigeye.core.analysis.identity.OwnKit
import com.sigeye.core.analysis.surveillance.Sweep
import com.sigeye.core.ble.CaptureStore
import com.sigeye.experiments.follow.FollowSettings
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Gathers everything saved into one zip, with a manifest and checksums.
 *
 * See [Evidence] for why this exists rather than the handful of separate exports it joins
 * up. The short version: the numbers were exportable and the conditions they were taken
 * under were not, and a distance without the exponent it assumed is not a measurement.
 */
object Bundler {

    /** Everything currently worth packing, cheapest first. */
    fun gather(context: Context, includeCapture: File? = null): List<Piece> = buildList {
        val runs = RunStore.get(context)
        Experiments.all.forEach { experiment ->
            val saved = runs.runs(experiment.id)
            if (saved.isEmpty()) return@forEach
            add(
                Piece(
                    name = "runs/${experiment.id}.csv",
                    content = runsCsv(saved.map { it.name to it.figures.map { f -> f.label to f.pretty() } }),
                    about = "Saved runs from ${experiment.title}.",
                ),
            )
        }

        val found = SweepStore.get(context).found.value.values
        if (found.isNotEmpty()) {
            add(
                Piece(
                    name = "sweep/found.csv",
                    content = Sweep.csv(found),
                    about = "Surveillance hardware found on sweeps, one row each.",
                ),
            )
        }

        includeCapture?.takeIf { it.exists() }?.let { capture ->
            add(
                Piece(
                    name = "capture/${capture.name}",
                    content = capture.readText(),
                    about = "Raw advertisement capture. Every packet, as recorded.",
                ),
            )
        }
    }

    /** The captures on this phone, so a screen can offer one. */
    fun captures(context: Context) = CaptureStore.get(context).list()

    /**
     * What the app was doing, read from the running code.
     *
     * Read rather than written out, so it cannot drift from what actually produced the
     * numbers. The follow settings come from the user's own preferences because those are
     * the ones most likely to differ from the defaults.
     */
    fun settings(context: Context): Map<String, String> {
        val follow = FollowSettings(context).load()
        return mapOf(
            "follow.baselineMs" to follow.baselineMs.toString(),
            "follow.dropAfterMs" to follow.dropAfterMs.toString(),
            "follow.minPackets" to follow.minPackets.toString(),
            "follow.bridgeAtOrBelow" to follow.bridgeAtOrBelow.toString(),
            "follow.carriedDbm" to follow.carriedDbm.toString(),
            "follow.autoMuteAboveDbm" to (follow.autoMuteAboveDbm?.toString() ?: "off"),

            "handoff.silenceMs" to Handoffs.SILENCE_MS.toString(),
            "handoff.giveUpMs" to Handoffs.GIVE_UP_MS.toString(),
            "handoff.appearedWithinMs" to Handoffs.APPEARED_WITHIN_MS.toString(),
            "handoff.autoShare" to Handoffs.AUTO_SHARE.toString(),
            "handoff.autoConfidence" to Handoffs.AUTO_CONFIDENCE.name,
            "handoff.tooMany" to Handoffs.TOO_MANY.toString(),

            "ask.minGapMs" to AskPolicy.MIN_GAP_MS.toString(),
            "ask.poolSmall" to AskPolicy.POOL_SMALL.toString(),

            "ownKit.onPersonDbm" to OwnKit.ON_PERSON_DBM.toString(),
            "ownKit.minShare" to OwnKit.MIN_SHARE.toString(),
            "ownKit.minConfidence" to OwnKit.MIN_CONFIDENCE.name,

            "myKit.onPersonDbm" to MyKit.ON_PERSON_DBM.toString(),
            "myKit.maxWanderDb" to MyKit.MAX_WANDER_DB.toString(),

            "crowd.busyDevices" to Crowd.BUSY_DEVICES.toString(),
            "crowd.packedDevices" to Crowd.PACKED_DEVICES.toString(),
            "crowd.packedRate" to Crowd.PACKED_RATE.toString(),
        )
    }

    fun provenance(context: Context): Provenance = Provenance(
        appVersion = BuildConfig.VERSION_NAME,
        device = "${Build.MANUFACTURER} ${Build.MODEL}",
        androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        takenAtMs = Clock.nowMs(),
        settings = settings(context),
    )

    /**
     * Writes the zip and returns it, or null if it could not be written.
     *
     * The manifest is built last and added first, because it carries a checksum of every
     * other file and cannot be written until they all exist.
     */
    fun build(context: Context, pieces: List<Piece>, provenance: Provenance): File? {
        if (!Evidence.worthExporting(pieces)) return null

        val folder = File(context.getExternalFilesDir(null), "evidence").apply { mkdirs() }
        val file = File(folder, Evidence.fileName(provenance.takenAtMs))

        return runCatching {
            val manifest = Piece(
                name = Evidence.MANIFEST,
                content = Evidence.manifest(provenance, pieces) { sha256(it.content) },
                about = "This file.",
            )

            ZipOutputStream(FileOutputStream(file)).use { zip ->
                (listOf(manifest) + pieces).forEach { piece ->
                    zip.putNextEntry(ZipEntry(piece.name))
                    zip.write(piece.content.toByteArray())
                    zip.closeEntry()
                }
            }
            file
        }.getOrNull()
    }

    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun runsCsv(runs: List<Pair<String, List<Pair<String, String>>>>): String =
        buildString {
            appendLine("run,figure,value")
            runs.forEach { (name, figures) ->
                figures.forEach { (label, value) ->
                    appendLine(
                        listOf(name, label, value)
                            .joinToString(",") { it.replace(",", " ") },
                    )
                }
            }
        }
}
