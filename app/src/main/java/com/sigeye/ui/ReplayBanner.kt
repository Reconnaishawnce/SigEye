package com.sigeye.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.Replay

/**
 * Says, everywhere and at all times, that what is on screen is a recording.
 *
 * The replay works by being undetectable. Every experiment reads one flow and none of them
 * can tell whether the packets came from the radio or from a file, which is the entire
 * value of the feature and also its one dangerous property: a crowd count from a train last
 * Tuesday renders identically to the room somebody is standing in, and nothing anywhere
 * said which they were looking at.
 *
 * An app that spends this much effort refusing to dress inference up as measurement cannot
 * then present a recording as the present moment. So it is mounted once, above everything,
 * next to the pinned target. Putting it inside each experiment would be thirty places to
 * forget it, and the one that got forgotten would be the one somebody screenshotted.
 */
@Composable
fun ReplayBanner(onStop: () -> Unit = {}) {
    val replaying by Replay.state.collectAsStateWithLifecycle()

    AnimatedVisibility(visible = replaying != null) {
        replaying?.let { running ->
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Replaying ${running.label}",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Text(
                                "Not live. Every screen is reading this recording at " +
                                    "${running.speedLabel}.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                        TextButton(onClick = onStop) { Text("Stop") }
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { running.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
