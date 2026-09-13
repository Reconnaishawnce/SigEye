package com.sigeye.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sigeye.core.AlertStyle
import com.sigeye.core.Beat
import com.sigeye.core.Feedback
import com.sigeye.core.Geiger
import kotlinx.coroutines.delay

/**
 * Following something by feel, so the phone can stay in a pocket.
 *
 * The screen is the worst part of this app in the field: following somebody while staring
 * at a phone is conspicuous, and it means the one moment worth watching is the one nobody
 * is watching. This turns the strongest candidate into a pulse that quickens as it gets
 * nearer, and then you can put the phone away and use your eyes for the person.
 *
 * Keeps running while the composition is alive, which includes the screen being off, and
 * stops the moment it is not - a phone buzzing in somebody's pocket after they left the
 * experiment is the sort of thing that gets an app deleted.
 */
@Composable
fun GeigerBar(
    /** The level to follow, or null when there is nothing worth following. */
    recentRssi: Double?,
    silentForMs: Long,
    label: String?,
    running: Boolean,
    onToggle: (Boolean) -> Unit,
    feedback: Feedback,
    style: AlertStyle = AlertStyle.BUZZ,
    modifier: Modifier = Modifier,
) {
    val level by rememberUpdatedState(recentRssi)
    val silence by rememberUpdatedState(silentForMs)

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            when (val beat = Geiger.beat(level, silence)) {
                Beat.Idle -> delay(500)

                is Beat.Lost -> {
                    // Two short pulses rather than one firm one. Unmistakably not the
                    // tracking beat, because "further away" and "gone" have to feel
                    // different through a coat.
                    if (style.vibrates) feedback.doubleBuzz()
                    if (style.beeps) feedback.beep(60)
                    delay(Geiger.LOST_INTERVAL_MS)
                }

                is Beat.Pulse -> {
                    if (style.vibrates) feedback.buzz(beat.strength)
                    if (style.beeps) feedback.beep((40 + beat.strength * 60).toInt())
                    delay(beat.intervalMs)
                }
            }
        }
    }

    // Leaving the screen has to stop it, whatever the toggle says.
    DisposableEffect(Unit) {
        onDispose { if (running) onToggle(false) }
    }

    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (running) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (running) "Following by feel" else "Put the phone away",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                label?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (running) {
                    Geiger.describe(recentRssi)
                } else {
                    "Pulses in your pocket, faster as the signal gets stronger. Following " +
                        "somebody while staring at a phone is conspicuous and means the " +
                        "moment worth watching is the one nobody is watching."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (running && silentForMs >= Geiger.STALE_MS) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Two short pulses means nothing heard for " +
                        "${silentForMs / 1000} seconds, which is a different thing from " +
                        "being far away.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onToggle(!running) },
                enabled = recentRssi != null || running,
                colors = if (running) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (running) "Stop" else "Start pulsing")
            }
        }
    }
}
