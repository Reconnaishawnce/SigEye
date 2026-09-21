package com.sigeye.experiments.follow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sigeye.core.analysis.identity.AskPolicy

/**
 * The rotation questions that were not worth stopping somebody for.
 *
 * The thing this replaces was a conveyor belt. A question would appear, and answering it
 * produced the next one immediately, and on a busy street that meant a dozen dialogs about
 * devices nobody could identify. The queue was draining straight into somebody's face
 * while they were trying to walk.
 *
 * A tray is the same queue with the person in charge of when it opens. It carries a count
 * and, more importantly, the reason those questions are sitting there, because a pile that
 * grows with no explanation reads like the app falling behind rather than the app declining
 * to interrupt.
 *
 * Nothing here is discarded or auto-answered. Every question in this pile is the same
 * question it would have been as a dialog, and picking a successor wrongly is much worse
 * than picking one late.
 */
@Composable
fun RotationTray(
    waiting: Int,
    why: String,
    muted: Boolean,
    onOpen: () -> Unit,
    onMute: (Boolean) -> Unit,
) {
    val label = AskPolicy.trayLabel(waiting) ?: return

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                why,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onOpen) { Text("Go through them") }
                if (muted) {
                    TextButton(onClick = { onMute(false) }) { Text("Let it interrupt again") }
                }
            }
        }
    }
}
