package com.sigeye.experiments.follow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sigeye.core.analysis.identity.WalkBy
import com.sigeye.core.analysis.identity.WalkByScore

/**
 * One device's signal through a walk-by, so the shape can be looked at rather than trusted.
 *
 * The score says "rose 14 dB as you passed and came back down". This is what that sentence
 * was computed from, and the whole point of drawing it is that a person can see in a second
 * whether it is a clean hill or a mess the thresholds happened to let through. A verdict you
 * cannot check is an assertion.
 *
 * Three things are marked. The vertical line is where you said you drew level. The two
 * shaded ends are the windows the two baselines were averaged over. The horizontal line is
 * the higher of those two baselines, which is what the rise is measured against - so the
 * height of the hill above that line is literally the number in the verdict.
 */
@Composable
fun WalkByChart(
    trail: List<Pair<Long, Int>>,
    score: WalkByScore,
    startMs: Long,
    endMs: Long,
    modifier: Modifier = Modifier,
    heightDp: Int = 120,
) {
    val line = MaterialTheme.colorScheme.primary
    val mark = MaterialTheme.colorScheme.error
    val ends = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val reference = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)

    val spoken = "Signal through the walk-by. " + score.describe() + ". " +
        "Peak ${score.peakDbm.toInt()} dBm, ends ${score.startDbm.toInt()} and " +
        "${score.endDbm.toInt()} dBm, from ${score.packets} readings."

    Box(
        modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .semantics { contentDescription = spoken },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (trail.size < 2) return@Canvas

            val span = (endMs - startMs).coerceAtLeast(1L)
            val levels = trail.map { it.second }
            // A little headroom either side, so the hill is not clipped by its own extremes.
            val top = (levels.max() + 3).toFloat()
            val bottom = (levels.min() - 3).toFloat()
            val range = (top - bottom).coerceAtLeast(1f)

            fun x(atMs: Long): Float = (atMs - startMs).toFloat() / span * size.width
            fun y(dbm: Float): Float = size.height - (dbm - bottom) / range * size.height

            // The two windows the baselines came from.
            val windowMs = (span * WalkBy.END_FRACTION).toLong()
            drawRect(color = ends, size = Size(x(startMs + windowMs), size.height))
            drawRect(
                color = ends,
                topLeft = Offset(x(endMs - windowMs), 0f),
                size = Size(size.width - x(endMs - windowMs), size.height),
            )

            // What the rise is measured against.
            val baseline = maxOf(score.startDbm, score.endDbm).toFloat()
            drawLine(
                color = reference,
                start = Offset(0f, y(baseline)),
                end = Offset(size.width, y(baseline)),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )

            // Where you said you were level.
            drawLine(
                color = mark,
                start = Offset(x(score.midAtMs), 0f),
                end = Offset(x(score.midAtMs), size.height),
                strokeWidth = 2f,
            )

            trail.zipWithNext().forEach { (from, to) ->
                drawLine(
                    color = line,
                    start = Offset(x(from.first), y(from.second.toFloat())),
                    end = Offset(x(to.first), y(to.second.toFloat())),
                    strokeWidth = 3f,
                )
            }

            // The peak the verdict used, so it is obvious which bump was picked.
            drawCircle(
                color = mark,
                radius = 5f,
                center = Offset(x(score.peakAtMs), y(score.peakDbm.toFloat())),
                style = Stroke(width = 2.5f),
            )
        }
    }
}
