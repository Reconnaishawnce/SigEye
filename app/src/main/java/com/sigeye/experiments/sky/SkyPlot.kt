package com.sigeye.experiments.sky

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sigeye.core.analysis.gnss.Constellation
import com.sigeye.core.analysis.gnss.Satellite
import com.sigeye.core.analysis.gnss.SkyView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The sky overhead, looking up.
 *
 * Straight down the middle is directly above you and the rim is the horizon, which is the
 * projection every receiver has drawn since receivers existed. It is worth the screen space
 * because it shows obstruction directly: a bare arc on one side is the building on that
 * side, and it will be the same bare arc tomorrow.
 *
 * Filled means the satellite is in the fix. Hollow means the phone can hear it but is not
 * using it, which happens when the orbit is not yet known or the signal is too weak to
 * trust.
 */
@Composable
fun SkyPlot(view: SkyView, modifier: Modifier = Modifier) {
    val sweep = rememberInfiniteTransition(label = "sky")
    val turn by sweep.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "turn",
    )

    val grid = MaterialTheme.colorScheme.onSurfaceVariant
    val ground = MaterialTheme.colorScheme.surfaceVariant
    val sweepColour = MaterialTheme.colorScheme.primary

    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
                val centre = Offset(size.width / 2f, size.height / 2f)
                val radius = minOf(size.width, size.height) / 2f - 14.dp.toPx()

                drawCircle(ground.copy(alpha = 0.35f), radius, centre)

                // Rings at sixty, thirty and zero degrees of elevation. Straight up is the
                // middle, so a satellite's distance from the centre is how low it is.
                listOf(1f, 2f / 3f, 1f / 3f).forEach { fraction ->
                    drawCircle(
                        color = grid.copy(alpha = 0.4f),
                        radius = radius * fraction,
                        center = centre,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
                listOf(0f, 90f, 180f, 270f).forEach { angle ->
                    val radians = (angle - 90f) * PI.toFloat() / 180f
                    drawLine(
                        color = grid.copy(alpha = 0.3f),
                        start = centre,
                        end = Offset(
                            centre.x + radius * cos(radians),
                            centre.y + radius * sin(radians),
                        ),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                // A slow hand, purely so the thing looks alive. It is not scanning; the
                // receiver hears every satellite at once.
                val hand = (turn - 90f) * PI.toFloat() / 180f
                drawLine(
                    color = sweepColour.copy(alpha = 0.25f),
                    start = centre,
                    end = Offset(
                        centre.x + radius * cos(hand),
                        centre.y + radius * sin(hand),
                    ),
                    strokeWidth = 2.dp.toPx(),
                )

                view.satellites.filter { it.placed }.forEach { satellite ->
                    val elevation = satellite.elevationDeg ?: return@forEach
                    val azimuth = satellite.azimuthDeg ?: return@forEach

                    // Elevation ninety is the centre, zero is the rim.
                    val out = radius * (1f - (elevation.coerceIn(0f, 90f) / 90f))
                    val radians = (azimuth - 90f) * PI.toFloat() / 180f
                    val at = Offset(
                        centre.x + out * cos(radians),
                        centre.y + out * sin(radians),
                    )

                    val colour = colourOf(satellite.constellation)
                    // Size carries the carrier to noise figure, so the strong ones are the
                    // ones the eye goes to.
                    val size = (3.dp.toPx() + (satellite.cn0DbHz / 50f).coerceIn(0f, 1f) * 5.dp.toPx())

                    if (satellite.usedInFix) {
                        drawCircle(colour, size, at)
                        drawCircle(colour.copy(alpha = 0.22f), size * 2.1f, at)
                    } else {
                        drawCircle(colour.copy(alpha = 0.7f), size, at, style = Stroke(1.5.dp.toPx()))
                    }
                }
            }

            Text(
                "N",
                Modifier.padding(bottom = 260.dp),
                style = MaterialTheme.typography.labelSmall,
                color = grid,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "Straight up is the middle and the rim is the horizon. Filled is in the fix, " +
                "hollow is heard but not used. A bare patch is whatever is standing in the " +
                "way, and it will be in the same place tomorrow.",
            style = MaterialTheme.typography.labelSmall,
            color = grid,
        )

        if (view.constellations.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                view.constellations.forEach { constellation ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.width(10.dp).height(10.dp)) {
                            drawCircle(colourOf(constellation), size.minDimension / 2f)
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            constellation.label,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A colour per constellation.
 *
 * Fixed rather than pulled from the theme, because these have to stay apart from each other
 * across six of them and a palette built for two accent colours will not do that.
 */
fun colourOf(constellation: Constellation): Color = when (constellation) {
    Constellation.GPS -> Color(0xFF4FC3F7)
    Constellation.GLONASS -> Color(0xFFFF8A65)
    Constellation.GALILEO -> Color(0xFF81C784)
    Constellation.BEIDOU -> Color(0xFFFFD54F)
    Constellation.QZSS -> Color(0xFFBA68C8)
    Constellation.NAVIC -> Color(0xFF4DB6AC)
    Constellation.SBAS -> Color(0xFFA1887F)
    Constellation.UNKNOWN -> Color(0xFF90A4AE)
}

/** One satellite as a bar, for the list under the plot. */
@Composable
fun SatelliteBar(satellite: Satellite, modifier: Modifier = Modifier) {
    val colour = colourOf(satellite.constellation)
    val track = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            satellite.name,
            Modifier.width(44.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (satellite.usedInFix) FontWeight.Bold else FontWeight.Normal,
            color = colour,
        )
        Canvas(Modifier.weight(1f).height(12.dp)) {
            drawRect(track, size = androidx.compose.ui.geometry.Size(size.width, size.height))
            // Fifty dB-Hz is about as good as a phone antenna gets, so the bar is scaled to
            // that rather than to whatever happens to be loudest right now.
            drawRect(
                color = if (satellite.usedInFix) colour else colour.copy(alpha = 0.45f),
                size = androidx.compose.ui.geometry.Size(
                    size.width * (satellite.cn0DbHz / 50f).coerceIn(0f, 1f),
                    size.height,
                ),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${satellite.cn0DbHz.toInt()}",
            Modifier.width(26.dp),
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            if (satellite.band.modern) "L5" else "",
            Modifier.width(22.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
