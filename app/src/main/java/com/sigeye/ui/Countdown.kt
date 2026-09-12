package com.sigeye.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A clock face that empties, for the minute nothing is happening.
 *
 * Several experiments open by asking the user to wait: Discovery learns a room, Train
 * Spotter warms up a baseline, the surroundings detector listens. A progress bar and a
 * number are accurate and they are also the moment somebody decides the app has hung -
 * the one thing on screen that is moving is a thin line most of a phone away from where
 * they are looking.
 *
 * A ring that visibly drains reads as a thing in progress from across a room, which is
 * the point: this is the part of an experiment somebody is most likely to film, and a
 * bar that inches along is not worth filming.
 *
 * The animation is tied to real elapsed time rather than run free, so what it shows is
 * the measurement and not a decoration playing alongside it.
 */
@Composable
fun CountdownRing(
    elapsedMs: Long,
    totalMs: Long,
    modifier: Modifier = Modifier,
    label: String? = null,
    caption: String? = null,
) {
    val fraction = if (totalMs <= 0) 0f else (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)
    val animated by animateFloatAsState(targetValue = fraction, label = "countdown")
    val remaining = ((totalMs - elapsedMs).coerceAtLeast(0L) / 1000.0).roundToInt()

    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val filled = MaterialTheme.colorScheme.primary
    val done = fraction >= 1f

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(RING_SIZE.dp).aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.09f
                val inset = stroke / 2f
                val box = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = track,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = box,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                // Clockwise from the top, like anything else anybody has ever waited on.
                drawArc(
                    color = filled,
                    startAngle = -90f,
                    sweepAngle = 360f * animated,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = box,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (done) "done" else "$remaining",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (!done) {
                    Text(
                        "seconds",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        label?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        caption?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The same idea flattened, for a screen that has no room for a ring.
 *
 * Still animated against real time, and still says how long is left rather than only how
 * far along it is - "22 seconds" is a thing somebody can decide to wait for, and a bar
 * three quarters full is not.
 */
@Composable
fun CountdownBar(
    elapsedMs: Long,
    totalMs: Long,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val fraction = if (totalMs <= 0) 0f else (elapsedMs.toFloat() / totalMs).coerceIn(0f, 1f)
    val animated by animateFloatAsState(targetValue = fraction, label = "countdownBar")
    val remaining = ((totalMs - elapsedMs).coerceAtLeast(0L) / 1000.0).roundToInt()

    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val filled = MaterialTheme.colorScheme.primary

    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            drawRect(color = track, size = Size(size.width, size.height))
            drawRect(color = filled, size = Size(size.width * animated, size.height))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label?.let { "$it · $remaining s left" } ?: "$remaining s left",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val RING_SIZE = 140
