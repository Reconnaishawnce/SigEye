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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sigeye.core.analysis.identity.Fingerprint
import com.sigeye.core.analysis.identity.LinkConfidence
import com.sigeye.core.analysis.identity.LinkScore
import com.sigeye.core.analysis.identity.Rotation
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Every canvas here is laid out from its own measured size and from dp converted at draw
 * time. The first version of this file used raw pixel offsets inside canvases sized in dp,
 * which is a coordinate system that changes underneath you: on a three times density screen
 * everything bunched into the top corner and ran into the text below it. DrawScope is a
 * Density, so `12.dp.toPx()` is the whole fix, and labels live in composables rather than
 * being painted into the canvas where nothing can reflow them.
 */

/**
 * Showing the working, rather than reporting the verdict.
 *
 * This experiment makes a claim about a stranger's phone from six weak signals, none of
 * which is conclusive on its own. A screen that printed "almost certainly the same device"
 * and left it there would be asking to be believed, which is the opposite of the point.
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
        // is a gate rather than a reason, and an empty row would suggest the link was
        // missing something it never had.
        score.evidence.filter { it.weight > 0 }.forEach { item ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (item.holds) "✓" else "✗",
                    Modifier.width(18.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (item.holds) pass else fail,
                )
                Text(
                    item.label.ifBlank { "Signal" },
                    Modifier.weight(1f).padding(end = 8.dp),
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
                        Canvas(Modifier.width(12.dp).height(10.dp)) {
                            drawRect(color = if (item.holds) pass else idle, size = size)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Canvas(Modifier.fillMaxWidth().height(12.dp)) {
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
                    color = Color.Gray.copy(alpha = 0.8f),
                    start = Offset(size.width * mark, 0f),
                    end = Offset(size.width * mark, size.height),
                    strokeWidth = 1.dp.toPx(),
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
 * heard, spaced by the time that actually passed. The interval is a firmware constant that
 * renaming does not touch, which is far easier to see as a rhythm with a wobble than to
 * accept as a number inside a tolerance.
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

    val line = MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    val band = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)

    val shown = gaps.takeLast(SPIKES)
    val shortest = shown.min()

    Column(modifier.fillMaxWidth()) {
        Text(
            "One mark per advertisement, spaced by the time that actually passed",
            style = MaterialTheme.typography.labelSmall,
            color = faint,
        )
        Spacer(Modifier.height(6.dp))

        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val total = shown.sum().toFloat().coerceAtLeast(1f)
            val floor = size.height
            val tall = size.height * 0.78f
            val short = size.height * 0.55f

            // How far the rhythm may wander and still count as the same device, drawn
            // against the shortest gap so it sits where the eye is already looking.
            val slack = (Fingerprint.INTERVAL_TOLERANCE * shortest).toFloat()
            drawRect(
                color = band,
                topLeft = Offset(0f, floor - tall),
                size = Size(size.width * (slack / total) * 2f, tall),
            )

            var x = 0f
            var carried = 0f
            shown.forEach { gap ->
                val lit = (carried / total) <= playhead
                drawLine(
                    color = if (lit) line else faint.copy(alpha = 0.4f),
                    start = Offset(x, floor),
                    end = Offset(x, floor - if (lit) tall else short),
                    strokeWidth = 2.dp.toPx(),
                )
                x += size.width * (gap.toFloat() / total)
                carried += gap.toFloat()
            }

            drawLine(
                color = faint.copy(alpha = 0.5f),
                start = Offset(0f, floor),
                end = Offset(size.width, floor),
                strokeWidth = 1.dp.toPx(),
            )
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "shortest valley $shortest ms",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "shaded: room to wander and still match",
                style = MaterialTheme.typography.labelSmall,
                color = faint,
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                append("Beating every $baseMs ms, which is ")
                append((baseMs / 0.625).roundToInt())
                append(" slots of the 0.625 ms the specification counts in. ")
                append(
                    when {
                        jitter <= 0.05 -> "It holds that to a few percent, which is a metronome."
                        jitter <= 0.15 -> "It holds that fairly tightly."
                        jitter <= 0.4 -> "It wanders a bit."
                        else -> "It wanders a lot, so the rhythm is a weak clue for this one."
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
 * instant earlier, so something already talking is a different device however alike it
 * looks.
 */
@Composable
fun HandoverStrip(rotation: Rotation, modifier: Modifier = Modifier) {
    val old = MaterialTheme.colorScheme.onSurfaceVariant
    val new = MaterialTheme.colorScheme.primary
    val label = MaterialTheme.colorScheme.onSurface

    val reveal by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 900),
        label = "reveal",
    )

    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                rotation.fromAddress.takeLast(8),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = old,
            )
            Text(
                if (rotation.gapMs < 0) {
                    "${-rotation.gapMs} ms overlap"
                } else {
                    "${rotation.gapMs} ms apart"
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = label,
            )
            Text(
                rotation.toAddress.takeLast(8),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = new,
            )
        }

        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(52.dp)) {
            val mid = size.width * 0.5f
            val slot = 10.dp.toPx()
            val gapHalf = 14.dp.toPx()
            val upper = size.height * 0.34f
            val lower = size.height * 0.78f

            // The old address runs out on the left, the new one starts on the right, and
            // the space between them is the handover.
            var x = 0f
            while (x < mid - gapHalf) {
                drawLine(old, Offset(x, upper), Offset(x, upper - upper * 0.7f), 2.dp.toPx())
                x += slot
            }
            val from = mid + gapHalf
            var y = from
            while (y < size.width && y < from + (size.width - from) * reveal) {
                drawLine(new, Offset(y, lower), Offset(y, lower - upper * 0.7f), 2.dp.toPx())
                y += slot
            }

            drawRect(
                color = label.copy(alpha = 0.08f),
                topLeft = Offset(mid - gapHalf, 0f),
                size = Size(gapHalf * 2, size.height),
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                append("The signal moved ")
                append(String.format(Locale.US, "%.0f dB", abs(rotation.rssiDeltaDb)))
                append(
                    if (abs(rotation.rssiDeltaDb) <= Fingerprint.RSSI_CONTINUITY_DB) {
                        " across the swap, which is not far for something that did not move."
                    } else {
                        " across the swap, which is a long way for something that did not move."
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
 * A diagram rather than a measurement, and the caption says so. The name changes and
 * nothing underneath it does, which is the whole idea in one picture.
 */
@Composable
fun WhatRotationLooksLike(modifier: Modifier = Modifier) {
    val cycle = rememberInfiniteTransition(label = "rotation")
    val phase by cycle.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    val shell = MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.onSurfaceVariant

    val names = listOf("4A:1C:88:E0", "7F:22:0B:91", "C3:9D:45:2E")
    val step = phase.toInt().coerceIn(0, 2)

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "underneath: same heartbeat, same packet shape, same place",
            style = MaterialTheme.typography.labelSmall,
            color = faint,
        )
        Spacer(Modifier.height(8.dp))

        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().height(74.dp)) {
                val centre = Offset(size.width * 0.5f, size.height * 0.5f)
                val pulse = 1f + 0.07f * sin(phase * 6.28f)
                val radius = 30.dp.toPx() * pulse
                drawCircle(shell.copy(alpha = 0.16f), radius, centre)
                drawCircle(shell, radius, centre, style = Stroke(width = 2.dp.toPx()))
            }
            Text(
                names[step],
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = shell,
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "the name it broadcasts, new every 15 minutes",
            style = MaterialTheme.typography.labelSmall,
            color = faint,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "The address is the only thing designed to change. Everything this app measures " +
                "is chosen because it does not: how often the device talks, what its packet " +
                "is made of, and the fact that it cannot have moved in the instant it " +
                "renamed itself.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

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
                "None of them identifies anybody alone, which is the point: the claim is " +
                "only ever as good as the pile.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))

        SIGNALS.forEach { (weight, pair) ->
            val (title, blurb) = pair
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Row(
                    Modifier.width(62.dp).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    repeat(weight) {
                        Canvas(Modifier.width(11.dp).height(9.dp)) {
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
                "time and space rather than about the packet, and they are the hardest for a " +
                "manufacturer to take away.",
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

/** Spikes drawn. More than this and the marks merge into a block. */
private const val SPIKES = 18
