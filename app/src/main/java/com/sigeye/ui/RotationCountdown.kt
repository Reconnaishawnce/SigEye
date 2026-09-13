package com.sigeye.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sigeye.core.analysis.identity.Rhythm
import com.sigeye.core.analysis.identity.RotationRhythm

/**
 * When a device that has gone quiet is next due to change address, or why nobody knows.
 *
 * A lost device is not necessarily gone. Far more often it is still in the room wearing a
 * name nothing recognises, and the useful question is when the next change is due - because
 * that is when it will surface as a stranger and can be linked back, and because a
 * countdown that expires with nothing found is the moment to admit the trail is cold.
 *
 * The refusal matters as much as the countdown. A device that has never changed address
 * while being watched has no rhythm to predict from and could put on a new one at any
 * second. Showing fifteen minutes from now would be inventing a deadline out of a
 * specification default that this particular device may not even use, and a made-up
 * countdown is worse than none - somebody watches it run out and concludes something.
 *
 * Shared between Follow Me's held target and its saved targets, because they are the same
 * question asked about two devices and answering it twice would let the two answers drift.
 */
@Composable
fun RotationCountdown(
    /** Every moment this device was seen putting on a new address, in order. */
    changesAtMs: List<Long>,
    nowMs: Long,
    modifier: Modifier = Modifier,
    onColour: Color = MaterialTheme.colorScheme.onErrorContainer,
) {
    val rhythm: Rhythm? = if (changesAtMs.size >= 2) RotationRhythm.analyze(changesAtMs) else null
    val lastChange = changesAtMs.lastOrNull()

    if (lastChange == null) {
        Text(
            "It has not changed address while being watched, so there is no rhythm to " +
                "predict from. It could put on a new one at any moment, and a countdown to " +
                "a made-up deadline would be worse than none.",
            style = MaterialTheme.typography.labelSmall,
            color = onColour,
            modifier = modifier,
        )
        return
    }

    val measured = rhythm?.takeIf { it.measurable && it.regular }?.medianPeriodMs
    val period = measured ?: RotationRhythm.SPEC_DEFAULT_MS
    val due = lastChange + period

    CountdownBar(
        elapsedMs = (period - (due - nowMs)).coerceIn(0L, period),
        totalMs = period,
        label = if (measured != null) {
            "next address due, on its measured rhythm"
        } else {
            "next address due, on the specification default"
        },
        modifier = modifier,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        if (measured != null) {
            "Measured from ${changesAtMs.size} changes this device actually made, so this " +
                "is its own rhythm rather than the usual one."
        } else {
            "Counted from the specification's nine hundred second default, because this " +
                "device has not changed often enough to have a measured rhythm. Fifteen " +
                "minutes is what most stacks inherit, not what the standard requires."
        },
        style = MaterialTheme.typography.labelSmall,
        color = onColour,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "When it changes it will reappear as a stranger, and it will only be taken back on " +
            "unambiguous evidence. If the countdown passes with nothing found, the trail is " +
            "cold.",
        style = MaterialTheme.typography.labelSmall,
        color = onColour,
    )
}
