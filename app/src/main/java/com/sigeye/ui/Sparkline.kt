package com.sigeye.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.sigeye.experiments.trainspotter.Bin
import kotlin.math.max

/**
 * A hand-rolled sparkline. A chart library would add a dependency and a version
 * to keep chasing for what amounts to one polyline and a dashed rule.
 */
@Composable
fun Sparkline(
    bins: List<Bin>,
    baseline: Double,
    modifier: Modifier = Modifier,
) {
    val line = MaterialTheme.colorScheme.primary
    val fillTop = line.copy(alpha = 0.35f)
    val fillBottom = line.copy(alpha = 0.02f)
    val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val spikeColor = MaterialTheme.colorScheme.error
    val labelColor = MaterialTheme.colorScheme.tertiary
    val surface = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(16.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(surface)
            if (bins.isEmpty()) return@Canvas

            val peak = bins.maxOfOrNull { it.newCount } ?: 0
            val yMax = max(max(peak.toDouble(), baseline * 2.0), 4.0).toFloat()

            val padTop = 10f
            val padBottom = 10f
            val usableHeight = size.height - padTop - padBottom
            val stepX = if (bins.size > 1) size.width / (bins.size - 1) else size.width

            fun yFor(value: Float): Float =
                padTop + usableHeight - (value / yMax).coerceIn(0f, 1f) * usableHeight

            drawBaseline(baseline.toFloat(), ::yFor, baselineColor)

            val linePath = Path()
            val fillPath = Path()
            bins.forEachIndexed { index, bin ->
                val x = index * stepX
                val y = yFor(bin.newCount.toFloat())
                if (index == 0) {
                    linePath.moveTo(x, y)
                    fillPath.moveTo(x, size.height)
                    fillPath.lineTo(x, y)
                } else {
                    linePath.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo((bins.size - 1).coerceAtLeast(0) * stepX, size.height)
            fillPath.close()

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(listOf(fillTop, fillBottom)),
            )
            drawPath(path = linePath, color = line, style = Stroke(width = 2.5f))

            bins.forEachIndexed { index, bin ->
                val x = index * stepX
                if (bin.spike) {
                    drawCircle(
                        color = spikeColor,
                        radius = 4.5f,
                        center = Offset(x, yFor(bin.newCount.toFloat())),
                    )
                }
                if (bin.label.isNotEmpty()) {
                    drawLine(
                        color = labelColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 2f,
                    )
                }
            }
        }

        if (bins.isEmpty()) {
            Text(
                text = "No data yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
            )
        }
    }
}

private fun DrawScope.drawBaseline(
    baseline: Float,
    yFor: (Float) -> Float,
    color: Color,
) {
    if (baseline <= 0f) return
    val y = yFor(baseline)
    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = 1.5f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f),
    )
}
