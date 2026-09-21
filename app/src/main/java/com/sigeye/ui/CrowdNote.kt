package com.sigeye.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sigeye.core.analysis.Crowd
import com.sigeye.core.analysis.Density

/**
 * One line telling somebody the room is busy and what changed because of it.
 *
 * Shows nothing at all in a quiet room, which is most of the time. When it does appear it
 * stays to a sentence: standing in an airport, the useful thing to know is that the app
 * noticed and that the numbers still mean what they did, not how a scan duty cycle works.
 */
@Composable
fun CrowdNote(
    devices: Int,
    advertsPerSecond: Double = 0.0,
    modifier: Modifier = Modifier,
    /** Anything else this screen is doing differently, in a few words. */
    extra: String? = null,
) {
    val density = Crowd.densityOf(devices, advertsPerSecond)
    val line = Crowd.describe(density, devices) ?: return

    Surface(
        color = if (density == Density.PACKED) {
            MaterialTheme.colorScheme.tertiaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                line,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            extra?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
