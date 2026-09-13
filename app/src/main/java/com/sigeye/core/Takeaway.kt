package com.sigeye.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One result, in the form it can be shown big and shared without lying.
 *
 * The point of this app is the demonstration, and a demonstration ends up as a screenshot
 * or a screen recording that somebody else looks at without the surrounding screen. "Three
 * devices still with you" cropped away from "of two hundred and fourteen, after two legs
 * and a mile" is exactly the overclaim every verdict in this app is written to avoid - and
 * cropping is the normal fate of screenshots, not a rare accident.
 *
 * So the denominator and the limit are not optional fields on this type. A takeaway that
 * cannot say what its number is out of, and cannot say in one line what it does not prove,
 * is not ready to be shown big. That constraint is the whole reason this exists rather than
 * each screen formatting its own card.
 *
 * @param headline the number itself, already formatted. Big type, nothing else near it.
 * @param unit what the number counts, in words. "devices still with you", not "count".
 * @param denominator what it is out of. "of 214 in range". Not optional.
 * @param context the conditions that make the number mean anything: how long, how far,
 *   how many legs. A reader needs these to judge the claim and they are the first thing
 *   a crop removes.
 * @param limit one line on what this does not prove. Not optional, and it travels with
 *   the image.
 */
data class Takeaway(
    val experiment: String,
    val headline: String,
    val unit: String,
    val denominator: String,
    val context: List<String>,
    val limit: String,
    val takenAtMs: Long = System.currentTimeMillis(),
) {
    init {
        require(headline.isNotBlank()) { "A takeaway with no number is nothing to show." }
        require(denominator.isNotBlank()) {
            "A takeaway has to say what its number is out of. That is the whole point of it."
        }
        require(limit.isNotBlank()) {
            "A takeaway has to say in one line what it does not prove."
        }
    }

    fun stamp(): String =
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.US).format(Date(takenAtMs))

    /** The conditions on one line, for the places that only have one. */
    fun conditions(): String = context.filter { it.isNotBlank() }.joinToString(" · ")

    /**
     * The whole thing as text, for a share that carries no image.
     *
     * Same order as the card, so somebody who saw one and then reads the other is not
     * comparing two differently shaped claims.
     */
    fun asText(): String = buildString {
        appendLine("$headline $unit")
        appendLine(denominator)
        conditions().takeIf { it.isNotBlank() }?.let { appendLine(it) }
        appendLine()
        appendLine(limit)
        appendLine()
        appendLine("$experiment · SigEye · ${stamp()}")
    }
}
