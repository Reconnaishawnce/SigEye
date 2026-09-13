package com.sigeye.core.analysis.identity

import java.util.Locale

/**
 * One follow, written out so somebody who was not there can check it.
 *
 * The evidence for a follow was spread across saved runs, exported CSVs and the library,
 * and assembling it into something another person could read was a manual job nobody would
 * do twice. That matters more here than in most of this app: a claim that a particular
 * phone travelled with a particular person is exactly the kind of claim that should not be
 * taken on trust, including from the tool that made it.
 *
 * So the report leads with what would make it wrong. The number of survivors is meaningless
 * without the size of the pool it came from, a walk-by is meaningless without knowing how
 * many were run, and a rotation followed automatically is a different quality of evidence
 * from one a person picked out of three options.
 *
 * Plain text rather than CSV. A case file is read, and the numbers that want to be columns
 * are already exported separately.
 *
 * Pure and Android-free.
 */
object CaseFile {

    fun write(
        name: String,
        state: FollowState,
        journal: Journal,
        scores: List<CandidateScore>,
        stitches: List<Stitch>,
    ): String = buildString {
        appendLine("SIGEYE FOLLOW: ${name.ifBlank { "Unnamed" }}")
        appendLine("=".repeat(60))
        appendLine()

        appendLine("WHAT HAPPENED")
        appendLine("-".repeat(60))
        appendLine("Ran for ${minutes(state.listeningForMs)}.")
        appendLine("${state.poolSize} devices were audible when the follow started.")
        appendLine("${state.stillIn.size} were still with you at the end.")
        if (state.blindMs > 30_000L) {
            appendLine(
                "${minutes(state.blindMs)} of that was spent not listening, which is not " +
                    "counted as silence against any device.",
            )
        }
        appendLine("${stitches.size} address changes were followed through.")
        appendLine()

        appendLine("HOW TO READ THIS")
        appendLine("-".repeat(60))
        appendLine(
            "Surviving is not evidence. A device is on the list because nothing ruled it " +
                "out, and on a short walk in a quiet street that can be most of what was " +
                "there. What counts is the tests below and the size of the pool above.",
        )
        appendLine(
            "Signal strength is not distance. Everything here is loudness through bodies, " +
                "bags and walls.",
        )
        appendLine(
            "A rotation followed automatically was taken because one candidate was clearly " +
                "ahead. A rotation you picked was one where more than one fitted, and the " +
                "pick is a human judgement rather than a measurement.",
        )
        appendLine()

        appendLine("WHAT WAS FOUND")
        appendLine("-".repeat(60))
        val ranked = scores.filter { it.points > 0 }.sortedByDescending { it.points }
        if (ranked.isEmpty()) {
            appendLine("Nothing scored above zero. This follow did not find anything.")
        }
        ranked.take(TOP).forEachIndexed { index, score ->
            val candidate = state.candidates.firstOrNull { it.address == score.address }
            appendLine()
            appendLine("${index + 1}. ${candidate?.label ?: candidate?.vendor ?: score.address}")
            appendLine("   address    ${score.address}")
            candidate?.let {
                appendLine("   kind       ${it.kind.label}" + if (it.kindCertain) "" else " (a guess)")
                appendLine("   held for   ${minutes(it.heldForMs(state.atMs))}")
                if (it.rotations > 0) appendLine("   rotations  ${it.rotations} followed through")
                appendLine("   addresses  ${it.addresses.joinToString(" -> ")}")
            }
            appendLine(
                "   score      ${score.points.toInt()}, ${score.odds.label.lowercase()}" +
                    ", ${percent(score.share)} of the evidence, ${score.rivals} others still in",
            )
            score.supporting.forEach { appendLine("   for        ${it.text}") }
            score.against.forEach { appendLine("   against    ${it.text}") }
            if (score.companions.isNotEmpty()) {
                appendLine(
                    "   with       ${score.companions.joinToString(", ")} " +
                        "(rising and falling in step, so probably one pocket)",
                )
            }
        }
        appendLine()

        appendLine("THE TIMELINE")
        appendLine("-".repeat(60))
        val events = journal.events()
        if (events.isEmpty()) appendLine("Nothing was recorded.")
        events.forEach { moment ->
            appendLine("${journal.clock(moment.atMs).padStart(6)}  ${journal.describe(moment)}")
        }
        appendLine()

        val marks = journal.marks()
        if (marks.isNotEmpty()) {
            appendLine("WHAT YOU SAW")
            appendLine("-".repeat(60))
            appendLine(
                "Marks are the operator's eyes rather than the radio's, and they are the " +
                    "only ground truth in this document. Everything else is inference.",
            )
            marks.forEach { mark ->
                appendLine("${journal.clock(mark.atMs).padStart(6)}  ${journal.describe(mark)}")
            }
            appendLine()
        }

        appendLine("THE COUNT, EVERY ${Journal.SAMPLE_MS / 1000} SECONDS")
        appendLine("-".repeat(60))
        appendLine("time,still_in,pool")
        journal.counts().forEach { count ->
            appendLine("${journal.clock(count.atMs)},${count.stillIn},${count.pool}")
        }
    }

    private fun minutes(ms: Long): String {
        val total = ms / 1000
        return if (total < 60) {
            "$total seconds"
        } else {
            String.format(Locale.US, "%d min %02d s", total / 60, total % 60)
        }
    }

    private fun percent(share: Double): String =
        String.format(Locale.US, "%.0f%%", share * 100)

    /** Enough to show the finding and its nearest rivals. Past that it is a phone book. */
    private const val TOP = 6
}
