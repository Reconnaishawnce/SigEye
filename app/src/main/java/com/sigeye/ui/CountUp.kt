package com.sigeye.ui

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * A number that moves to its new value instead of teleporting.
 *
 * The count on Train Spotter is the thing somebody points a camera at when a train goes
 * past. Jumping from 3 to 41 between frames reads as a glitch. Running up to 41 over a
 * third of a second reads as a train arriving, which is what actually happened.
 *
 * Deliberately short. A long count-up is a lie about when the measurement was taken, and
 * the number would still be climbing after the train had gone.
 */
@Composable
fun CountUp(
    value: Int,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 88.sp,
    color: Color = MaterialTheme.colorScheme.primary,
    fontWeight: FontWeight = FontWeight.Bold,
) {
    val shown by animateIntAsState(
        targetValue = value,
        animationSpec = tween(durationMillis = RISE_MS),
        label = "countUp",
    )
    Text(
        text = shown.toString(),
        modifier = modifier,
        style = TextStyle(fontSize = fontSize, fontWeight = fontWeight, color = color),
    )
}

/** The same idea for a measured quantity, where the decimal matters. */
@Composable
fun CountUpDecimal(
    value: Double,
    modifier: Modifier = Modifier,
    decimals: Int = 1,
    suffix: String = "",
    fontSize: TextUnit = 48.sp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val shown by animateFloatAsState(
        targetValue = value.toFloat(),
        animationSpec = tween(durationMillis = RISE_MS),
        label = "countUpDecimal",
    )
    Text(
        text = String.format(java.util.Locale.US, "%.${decimals}f%s", shown, suffix),
        modifier = modifier,
        style = TextStyle(fontSize = fontSize, fontWeight = FontWeight.Bold, color = color),
    )
}

/** Long enough to read as motion, short enough not to misreport when it happened. */
private const val RISE_MS = 350
