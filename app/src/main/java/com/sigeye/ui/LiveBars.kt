package com.sigeye.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A live histogram that moves, for the minute where nothing else is happening.
 *
 * Every experiment here has a warm-up, and a warm-up is a screen that says "wait" for a
 * minute. That is the worst minute in the app: it is the first thing a new person sees, it
 * is the part of a recording somebody would cut, and until now it was a number that changed
 * once every eight seconds with no explanation of what was being learned.
 *
 * The radio is producing something every fraction of a second throughout, so the honest fix
 * is to show that rather than to decorate the wait. These bars are real: each one is a
 * reading, newest on the right, sliding left as the next arrives. The waiting is the
 * measurement, which is exactly what the caption should say.
 *
 * One composable per bar rather than one `Canvas`, because then each bar springs to its own
 * height for free and the row reads as something alive rather than as a chart redrawn twice
 * a second. Thirty-odd small boxes is nothing to Compose, and the alternative - animating a
 * Canvas by hand - would be more code that looked worse.
 *
 * @param values newest last. Longer than [bars] is fine; the tail is what gets drawn.
 * @param spoken what the row is, in words, for anybody who cannot see it moving.
 */
@Composable
fun LiveBars(
    values: List<Float>,
    spoken: String,
    modifier: Modifier = Modifier,
    heightDp: Int = 56,
    bars: Int = 30,
) {
    val shown = values.takeLast(bars)
    // Padded from the left so the first readings appear at the right and travel leftwards,
    // rather than the row starting full width and squashing as it fills.
    val padded = List(bars - shown.size) { 0f } + shown
    val ceiling = (shown.maxOrNull() ?: 1f).coerceAtLeast(1f)

    val low = MaterialTheme.colorScheme.primary
    val high = MaterialTheme.colorScheme.tertiary
    val empty = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)

    Row(
        modifier
            .fillMaxWidth()
            .height(heightDp.dp)
            .semantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        padded.forEachIndexed { index, value ->
            val fraction = (value / ceiling).coerceIn(0f, 1f)
            // Springy rather than linear. A bar that eases into place reads as a
            // measurement settling; one that ramps at a constant rate reads as a loading
            // bar, which is the thing this is trying not to be.
            val grown by animateFloatAsState(
                targetValue = fraction,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
                label = "bar$index",
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(top = 0.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        // A floor so an empty slot is a visible trough rather than a gap,
                        // which keeps the row the same shape while it fills up.
                        .fillMaxHeight(grown.coerceAtLeast(0.03f))
                        .background(
                            color = if (value <= 0f) empty else lerp(low, high, fraction),
                            shape = RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
    }
}

/** The usual sentence for a rate, so callers do not each invent their own. */
fun barsSpoken(values: List<Float>, unit: String): String {
    if (values.isEmpty()) return "Nothing measured yet."
    val latest = values.last()
    return "Live bar chart of $unit. Latest ${latest.roundToInt()}, " +
        "highest ${(values.max()).roundToInt()}, " +
        "average ${values.average().roundToInt()} across ${values.size} readings."
}
