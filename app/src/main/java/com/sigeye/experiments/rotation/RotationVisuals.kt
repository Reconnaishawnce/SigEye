package com.sigeye.experiments.rotation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sigeye.core.analysis.identity.Fingerprint
import com.sigeye.core.analysis.identity.LinkConfidence
import com.sigeye.core.analysis.identity.LinkScore
import com.sigeye.core.analysis.identity.Rotation
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Showing the working, rather than reporting the verdict.
 *
 * This experiment makes a claim about a stranger's phone from six weak signals, none of
 * which is conclusive on its own. A screen that printed "almost certainly the same device"
 * and left it there would be asking to be believed, which is the opposite of the point.
 *
 * So the scorecard draws each signal at the weight it actually carries, the ones that
 * failed as prominently as the ones that passed, and the bar underneath is the sum against
 * what was available rather than a number out of the air.
 */
@Composable
fun SignalScorecard(score: LinkScore, modifier: Modifier = Modifier) {
    val offered = score.evidence.sumOf { it.weight }.coerceAtLeast(1)
    val earned by animateFloatAsState(
        targetValue = score.points.toFloat() / offered,
        animationSpec = tween(durationMillis = 700),
        label = "earned",
    )

    val pass = MaterialTheme.colorScheme.primary
    val fail = MaterialTheme.colorScheme.error
    val idle = MaterialTheme.colorScheme.surfaceVariant

    Column(modifier.fillMaxWidth()) {
        // The scored evidence only. The both-randomized check carries no weight because it
        // is a gate rather than a reason, and drawing it as an empty row would suggest the
        // link was missing something it never had.
        score.evidence.filter { it.weight > 0 }.forEach { item ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (item.holds) "✓" else "✗",
                    Modifier.width(20.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (item.holds) pass else fail,
                )
                Text(
                    item.label.ifBlank { "Signal" },
                    Modifier.width(150.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item.holds) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                // One block per point, so the weights are visible as size rather than
                // stated as a number somebody has to trust.
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(item.weight) {
                        Canvas(Modifier.width(14.dp).height(10.dp)) {
                            drawRect(
                                color = if (item.holds) pass else idle,
                                size = size,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            drawRect(color = idle, size = size)
            drawRect(
                color = when (score.confidence) {
                    LinkConfidence.STRONG, LinkConfidence.LIKELY -> pass
                    LinkConfidence.POSSIBLE -> fail.copy(alpha = 0.6f)
                    LinkConfidence.NONE -> fail
                },
                size = Size(size.width * earned.coerceIn(0f, 1f), size.height),
            )
            // Where the bands fall, so the bar is a measurement against a threshold rather
            // than a progress bar toward nothing.
            listOf(0.4f, 0.6f, 0.85f).forEach { mark ->
                drawLine(
                    color = Color.Gray.copy(alpha = 0.7f),
                    start = Offset(size.width * mark, 0f),
                    end = Offset(size.width * mark, size.height),
                    strokeWidth = 2f,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${score.points} of $offered points available. The marks are where possible, " +
                "probably and almost certainly begin.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The device's own packet train, and the gaps that are the measurement.
 *
 * Real gaps, not a picture of gaps. Every spike is an advertisement this phone actually
 * heard, spaced by the time that actually passed, and the bracket under the shortest run
 * of them is the interval the estimator settled on.
 *
 * The gaps are why the interval is a fingerprint at all: the device picks a heartbeat in
 * firmware and keeps it whatever its address says, so two addresses beating at the same
 * rate are worth a second look.
 */
@Composable
fun Heartbeat(
    gaps: List<Long>,
    baseMs: Long,
    jitter: Double,
    modifier: Modifier = Modifier,
) {
    if (gaps.size < 3 || baseMs <= 0L) {
        Text(
            "Waiting for enough packets to measure a rhythm. It takes a few seconds.",
            modifier,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val sweep = rememberInfiniteTransition(label = "heartbeat")
    val playhead by sweep.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "playhead",
    )

    val measurer = rememberTextMeasurer()
    val line = MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    val band = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val label = MaterialTheme.colorScheme.onSurface

    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val shown = gaps.takeLast(SPIKES)
            val total = shown.sum().toFloat().coerceAtLeast(1f)
            val floor = size.height - 26f

            // The jitter band, drawn behind everything: how much room the rhythm is
            // allowed to wander before two readings stop counting as the same device.
            val slack = (Fingerprint.INTERVAL_TOLERANCE * baseMs).toFloat()
            val baseWidth = size.width * (baseMs / total)
            drawRect(
                color = band,
                topLeft = Offset(0f, floor - 58f),
                size = Size(size.width * (slack / total) * 2, 58f),
            )

            var x = 0f
            var carriedMs = 0f
            shown.forEachIndexed { index, gap ->
                val next = x + size.width * (gap.toFloat() / total)

                // Each advertisement as a spike. A packet is well under a millisecond of
                // air time, so the spike is a mark rather than a width - what is being
                // measured is the silence between them.
                val lit = (carriedMs / total) <= playhead
                drawLine(
                    color = if (lit) line else faint.copy(alpha = 0.35f),
                    start = Offset(x, floor),
                    end = Offset(x, floor - if (lit) 52f else 40f),
                    strokeWidth = 3f,
                )

                // The valley. Bracketed on the first pair so the number has somewhere to
                // point, and left plain after that so the rhythm is what you see.
                if (index == 0) {
                    val mid = (x + next) / 2
                    drawLine(
                        color = label,
                        start = Offset(x + 2f, floor - 46f),
                        end = Offset(next - 2f, floor - 46f),
                        strokeWidth = 2f,
                    )
                    text(measurer, "$gap ms", Offset(mid - 22f, floor - 68f), label)
                }

                x = next
                carriedMs += gap.toFloat()
            }

            drawLine(
                color = faint.copy(alpha = 0.5f),
                start = Offset(0f, floor),
                end = Offset(size.width, floor),
                strokeWidth = 1.5f,
            )
            text(
                measurer,
                "each mark is one advertisement",
                Offset(0f, floor + 8f),
                faint,
            )
            if (baseWidth > 30f) {
                text(
                    measurer,
                    "shaded: how far it may wander and still match",
                    Offset(0f, floor - 88f),
                    faint,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                append("Beating every $baseMs ms, which is ")
                append((baseMs / 0.625).roundToInt())
                append(" slots of the 0.625 ms the specification counts in. ")
                append(
                    when {
                        jitter <= 0.05 -> "It holds that to within a few percent, which is a metronome."
                        jitter <= 0.15 -> "It holds that fairly tightly."
                        jitter <= 0.4 -> "It wanders a bit."
                        else -> "It wanders a lot, which makes the rhythm a weak clue for this one."
                    },
                )
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The swap itself: one address stopping, another starting, and the moment between them.
 *
 * The gap is the whole argument. A rotation produces an address that did not exist an
 * instant earlier, so something that was already talking is a different device however
 * alike it looks.
 */
@Composable
fun HandoverStrip(rotation: Rotation, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val old = MaterialTheme.colorScheme.onSurfaceVariant
    val new = MaterialTheme.colorScheme.primary
    val label = MaterialTheme.colorScheme.onSurface

    val reveal by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 900),
        label = "reveal",
    )

    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(88.dp)) {
            val mid = size.width * 0.5f
            val lane = size.height * 0.45f

            // Two lanes. The old address runs out on the left, the new one starts on the
            // right, and the space between is the handover.
            var x = 4f
            while (x < mid - 24f) {
                drawLine(old, Offset(x, lane), Offset(x, lane - 22f), strokeWidth = 3f)
                x += 22f
            }
            var y = mid + 24f
            val end = size.width - 4f
            while (y < end && y < mid + 24f + (end - mid - 24f) * reveal) {
                drawLine(new, Offset(y, lane + 34f), Offset(y, lane + 12f), strokeWidth = 3f)
                y += 22f
            }

            drawRect(
                color = label.copy(alpha = 0.08f),
                topLeft = Offset(mid - 24f, 0f),
                size = Size(48f, size.height - 18f),
            )
            drawLine(
                color = label,
                start = Offset(mid - 24f, lane + 6f),
                end = Offset(mid + 24f, lane + 6f),
                strokeWidth = 2f,
            )
            text(
                measurer,
                if (rotation.gapMs < 0) {
                    "${-rotation.gapMs} ms overlap"
                } else {
                    "${rotation.gapMs} ms"
                },
                Offset(mid - 30f, lane - 16f),
                label,
            )
            text(measurer, rotation.fromAddress.takeLast(8), Offset(4f, lane + 10f), old)
            text(
                measurer,
                rotation.toAddress.takeLast(8),
                Offset(mid + 28f, lane + 38f),
                new,
            )
        }

        Text(
            buildString {
                append("The old address stopped and this one started ")
                append(if (rotation.gapMs < 0) "just before" else "${rotation.gapMs} ms after")
                append(". The signal moved ")
                append(String.format(Locale.US, "%.0f dB", abs(rotation.rssiDeltaDb)))
                append(
                    if (abs(rotation.rssiDeltaDb) <= Fingerprint.RSSI_CONTINUITY_DB) {
                        ", which is not far for something that did not move."
                    } else {
                        ", which is a long way for something that did not move."
                    },
                )
                if (rotation.fromSlots > 0 && rotation.toSlots > 0) {
                    append(" Both beat about every ")
                    append(String.format(Locale.US, "%.0f", rotation.toSlots * 0.625))
                    append(" ms.")
                }
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * What a rotation is, for somebody who has never heard of one.
 *
 * A diagram rather than a measurement, and it says so. Every fifteen minutes the name
 * changes and nothing else does, which is the whole idea in one picture.
 */
@Composable
fun WhatRotationLooksLike(modifier: Modifier = Modifier) {
    val cycle = rememberInfiniteTransition(label = "rotation")
    val phase by cycle.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    val measurer = rememberTextMeasurer()
    val shell = MaterialTheme.colorScheme.primary
    val body = MaterialTheme.colorScheme.onSurface
    val faint = MaterialTheme.colorScheme.onSurfaceVariant

    val names = listOf("4A:1C:88:E0", "7F:22:0B:91", "C3:9D:45:2E")
    val step = phase.toInt().coerceIn(0, 2)

    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val centre = Offset(size.width * 0.5f, size.height * 0.42f)

            // The address, changing. Everything drawn inside it stays put.
            val pulse = 1f + 0.06f * kotlin.math.sin(phase * 6.28f).toFloat()
            drawCircle(
                color = shell.copy(alpha = 0.16f),
                radius = 46f * pulse,
                center = centre,
            )
            drawCircle(
                color = shell,
                radius = 46f * pulse,
                center = centre,
                style = Stroke(width = 2.5f),
            )
            text(measurer, names[step], Offset(centre.x - 44f, centre.y - 8f), shell, mono = true)

            text(
                measurer,
                "the name it broadcasts, new every 15 minutes",
                Offset(centre.x - 130f, centre.y + 46f),
                faint,
            )
            text(
                measurer,
                "underneath: same heartbeat, same packet shape, same place",
                Offset(4f, 6f),
                body,
            )
        }
        Text(
            "The address is the only thing designed to change. Everything the app measures " +
                "is chosen because it does not: how often the device talks, what its packet " +
                "is made of, and the fact that it cannot have moved in the instant it " +
                "renamed itself.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun DrawScope.text(
    measurer: TextMeasurer,
    value: String,
    at: Offset,
    color: Color,
    mono: Boolean = false,
) {
    drawText(
        textMeasurer = measurer,
        text = value,
        topLeft = at,
        style = TextStyle(
            color = color,
            fontSize = 10.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        ),
    )
}

/** Spikes drawn. More than this and the marks merge into a block. */
private const val SPIKES = 18

/**
 * The six signals, in the order they are worth, for somebody meeting them for the first time.
 *
 * Weights drawn as blocks rather than written as numbers, so the shape of the argument is
 * visible before a word of it is read: one big signal, one nearly as big, and four small
 * ones that only matter together.
 */
@Composable
fun SixSignals(modifier: Modifier = Modifier) {
    val pass = MaterialTheme.colorScheme.primary

    Column(modifier.fillMaxWidth()) {
        Text(
            "A phone changes its address every quarter of an hour and changes nothing else. " +
                "These are the six things it keeps, ranked by how much each one is worth. " +
                "None of them identifies anybody on its own, which is the point: the claim " +
                "is only ever as good as the pile.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))

        SIGNALS.forEach { (weight, pair) ->
            val (title, blurb) = pair
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Row(
                    Modifier.width(70.dp).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    repeat(weight) {
                        Canvas(Modifier.width(12.dp).height(10.dp)) {
                            drawRect(color = pass, size = size)
                        }
                    }
                }
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        blurb,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Two of these are free. A device cannot move in the instant it renames itself, " +
                "and a new name cannot have been talking a minute ago. Those are facts about " +
                "time and space rather than about the packet, and they are the hardest for " +
                "a manufacturer to take away.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val SIGNALS = listOf(
    4 to ("Same packet shape" to
        "Which fields the advertisement carries, how long they are and who claims them. " +
            "Firmware decides all of it and the privacy scheme never touches it."),
    3 to ("Same heartbeat" to
        "How often it talks, set in firmware as a whole number of 0.625 ms slots. " +
            "Renaming yourself does not change how often you speak."),
    2 to ("Same Apple messages" to
        "Apple packs several small messages into one packet. The contents rotate on " +
            "purpose; which messages get sent says what the device is and does not."),
    2 to ("Right moment" to
        "The new name appeared in the seconds after the old one went quiet. Something " +
            "already talking is a different device, however alike it looks."),
    2 to ("Did not move" to
        "The signal is about as loud as it was a moment ago. Nothing crosses a room in " +
            "the time it takes to change its name."),
    1 to ("Same steadiness" to
        "How tightly it holds that heartbeat. Two devices can share an interval while one " +
            "is a metronome and the other wanders."),
)
