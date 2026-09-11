package com.sigeye.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Half-width of the wedge a torso is assumed to block. */
private const val SHADOW_HALF_ANGLE = 38f

/**
 * A top-down picture of what the experiment is actually doing.
 *
 * The measurement is hard to picture from a polar plot alone, so this draws the situation
 * instead: you in the middle, the phone on your chest facing whichever way you face, the
 * wedge your torso is blocking behind you, and where the source appears to be.
 *
 * The point it makes visible is the geometry. With the phone on your chest, facing the
 * source is line of sight and facing away puts your body in the path - so the loudest and
 * quietest directions should come out roughly opposite each other. When they do not, what
 * was measured was the room.
 */
@Composable
fun BodyDiagram(
    /** Which way you are facing, in compass degrees. */
    headingDegrees: Float,
    /** Best guess at where the source is: the loudest direction so far. */
    sourceBearingDegrees: Float?,
    /** 0..1, how strong the signal is right now relative to this sweep's range. */
    strength: Float,
    /** True when the torso wedge currently covers the source. */
    blocking: Boolean,
    modifier: Modifier = Modifier,
) {
    val ring = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
    val bodyColour = MaterialTheme.colorScheme.onSurfaceVariant
    val phoneColour = MaterialTheme.colorScheme.primary
    val shadowColour = MaterialTheme.colorScheme.error
    val sourceColour = MaterialTheme.colorScheme.tertiary
    val labels = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()

    Box(modifier.fillMaxWidth().aspectRatio(1f)) {
        Canvas(Modifier.fillMaxSize()) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f * 0.78f

            drawCircle(color = ring, radius = radius, center = centre, style = Stroke(width = 1.5f))
            drawCompassLabels(measurer, centre, radius, labels)

            // The wedge your torso is blocking: behind you, so heading + 180.
            drawShadowWedge(
                centre = centre,
                radius = radius,
                bearing = headingDegrees + 180f,
                colour = shadowColour,
                strong = blocking,
            )

            sourceBearingDegrees?.let {
                drawSource(measurer, centre, radius, it, sourceColour, blocking)
                // The straight path from source to you, broken where your body sits.
                drawPath(centre, radius, it, sourceColour, blocking)
            }

            drawPerson(centre, headingDegrees, bodyColour, phoneColour, strength)
        }
    }
}

private fun DrawScope.drawShadowWedge(
    centre: Offset,
    radius: Float,
    bearing: Float,
    colour: Color,
    strong: Boolean,
) {
    val start = bearing - 90f - SHADOW_HALF_ANGLE
    val sweep = SHADOW_HALF_ANGLE * 2f
    drawArc(
        brush = Brush.radialGradient(
            colors = listOf(
                colour.copy(alpha = if (strong) 0.42f else 0.16f),
                Color.Transparent,
            ),
            center = centre,
            radius = radius,
        ),
        startAngle = start,
        sweepAngle = sweep,
        useCenter = true,
        topLeft = Offset(centre.x - radius, centre.y - radius),
        size = Size(radius * 2, radius * 2),
    )
    drawArc(
        color = colour.copy(alpha = if (strong) 0.8f else 0.3f),
        startAngle = start,
        sweepAngle = sweep,
        useCenter = true,
        topLeft = Offset(centre.x - radius, centre.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = 1.5f),
    )
}

private fun DrawScope.drawSource(
    measurer: TextMeasurer,
    centre: Offset,
    radius: Float,
    bearing: Float,
    colour: Color,
    blocked: Boolean,
) {
    val radians = (bearing - 90f) * PI.toFloat() / 180f
    val point = Offset(
        centre.x + radius * cos(radians),
        centre.y + radius * sin(radians),
    )
    drawCircle(color = colour.copy(alpha = 0.25f), radius = 20f, center = point)
    drawCircle(color = colour, radius = 9f, center = point)

    val layout = measurer.measure(
        text = if (blocked) "source (blocked)" else "source",
        style = TextStyle(fontSize = 9.sp, color = colour),
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            (point.x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width),
            (point.y + 14f).coerceAtMost(size.height - layout.size.height),
        ),
    )
}

/** The line from the source to the phone, dashed and red where the body interrupts it. */
private fun DrawScope.drawPath(
    centre: Offset,
    radius: Float,
    bearing: Float,
    colour: Color,
    blocked: Boolean,
) {
    val radians = (bearing - 90f) * PI.toFloat() / 180f
    val outer = Offset(centre.x + radius * cos(radians), centre.y + radius * sin(radians))
    drawLine(
        color = colour.copy(alpha = if (blocked) 0.35f else 0.75f),
        start = outer,
        end = centre,
        strokeWidth = 2f,
        pathEffect = if (blocked) PathEffect.dashPathEffect(floatArrayOf(6f, 8f)) else null,
    )
}

/**
 * You, from above, facing [headingDegrees]. The phone sits on the front of your chest,
 * which is the whole reason the geometry works.
 */
private fun DrawScope.drawPerson(
    centre: Offset,
    headingDegrees: Float,
    bodyColour: Color,
    phoneColour: Color,
    strength: Float,
) {
    rotate(degrees = headingDegrees, pivot = centre) {
        // Torso: an oval, broader across the shoulders than front to back.
        val halfWidth = 26f
        val halfDepth = 17f
        drawOval(
            color = bodyColour.copy(alpha = 0.55f),
            topLeft = Offset(centre.x - halfWidth, centre.y - halfDepth),
            size = Size(halfWidth * 2, halfDepth * 2),
        )
        // Shoulders line, so the facing direction is unambiguous.
        drawLine(
            color = bodyColour.copy(alpha = 0.8f),
            start = Offset(centre.x - halfWidth, centre.y),
            end = Offset(centre.x + halfWidth, centre.y),
            strokeWidth = 2f,
        )

        // The phone, held flat on the chest, screen facing out.
        val phoneTop = centre.y - halfDepth - 11f
        drawRoundRect(
            color = phoneColour,
            topLeft = Offset(centre.x - 9f, phoneTop),
            size = Size(18f, 11f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f),
        )
        // A little cone of what the phone can hear, brighter when signal is strong.
        val cone = Path().apply {
            moveTo(centre.x, phoneTop)
            lineTo(centre.x - 34f, phoneTop - 46f)
            lineTo(centre.x + 34f, phoneTop - 46f)
            close()
        }
        drawPath(cone, color = phoneColour.copy(alpha = 0.10f + strength * 0.35f))
    }
}

private fun DrawScope.drawCompassLabels(
    measurer: TextMeasurer,
    centre: Offset,
    radius: Float,
    colour: Color,
) {
    listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f).forEach { (text, angle) ->
        val radians = (angle - 90f) * PI.toFloat() / 180f
        val layout = measurer.measure(
            text = text,
            style = TextStyle(fontSize = 10.sp, color = colour.copy(alpha = 0.7f)),
        )
        val at = radius + 13f
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                centre.x + at * cos(radians) - layout.size.width / 2f,
                centre.y + at * sin(radians) - layout.size.height / 2f,
            ),
        )
    }
}

/** True when [bearing] falls inside the torso wedge for someone facing [heading]. */
fun isBlocking(heading: Float, bearing: Float): Boolean {
    var difference = abs(((heading + 180f) % 360f) - bearing) % 360f
    if (difference > 180f) difference = 360f - difference
    return difference <= SHADOW_HALF_ANGLE
}
