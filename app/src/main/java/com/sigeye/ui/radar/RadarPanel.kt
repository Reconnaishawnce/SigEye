package com.sigeye.ui.radar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/** Widest and narrowest signal window the radar will show. */
private const val WIDEST_OUTER_DBM = -100f
private const val NARROWEST_OUTER_DBM = -55f
private const val INNER_DBM = -35f
private const val ZOOM_STEP_DB = 8f

/**
 * The radar, plus everything that should behave the same way wherever it appears.
 *
 * There were two radars in this app with different abilities, which meant tapping a blip
 * worked on one screen and did nothing on another. Every screen now uses this, so the
 * behaviour is the same everywhere: pinch or use the buttons to zoom, tap a blip to select
 * it, tap the background to clear, and whatever the screen knows about the selected device
 * appears underneath.
 *
 * Zoom narrows the signal window rather than magnifying pixels - the outer ring moves from
 * -100 dBm in towards -55, which spreads out whatever is close to you. The blips grow with
 * it as feedback, though their radius is still where the information lives.
 */
@Composable
fun RadarPanel(
    targets: List<RadarTarget>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    footnote: String? = null,
    /**
     * Whether to draw the selection card.
     *
     * Proximity Radar renders a much fuller one of its own - distance, trend, a locate
     * button - so it turns this off rather than showing a thinner version of the same
     * thing directly above it. The behaviour that has to match everywhere is tapping,
     * zooming and the selection existing at all, not the exact card.
     */
    showSelectionCard: Boolean = true,
    detail: @Composable ColumnScope.(RadarTarget) -> Unit = {},
) {
    var outerDbm by remember { mutableStateOf(WIDEST_OUTER_DBM) }

    // One at the widest window, growing as the window narrows. The cap lives in the scene.
    val span = (outerDbm - INNER_DBM).let { if (it == 0f) 1f else -it }
    val widestSpan = -(WIDEST_OUTER_DBM - INNER_DBM)
    val blipScale = (widestSpan / span).coerceIn(1f, 2.6f)

    Column(modifier.fillMaxWidth()) {
        RadarScene(
            targets = targets,
            outerDbm = outerDbm,
            innerDbm = INNER_DBM,
            selectedAddress = selected,
            onSelect = onSelect,
            onZoom = { zoom ->
                outerDbm = (outerDbm + (zoom - 1f) * 60f)
                    .coerceIn(WIDEST_OUTER_DBM, NARROWEST_OUTER_DBM)
            },
            blipScale = blipScale,
        )

        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                String.format(
                    Locale.US,
                    "%d shown · outer ring %d dBm",
                    targets.size,
                    outerDbm.roundToInt(),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(
                    onClick = {
                        outerDbm = (outerDbm - ZOOM_STEP_DB).coerceAtLeast(WIDEST_OUTER_DBM)
                    },
                    enabled = outerDbm > WIDEST_OUTER_DBM,
                ) { Text("−", style = MaterialTheme.typography.labelLarge) }
                TextButton(onClick = { outerDbm = WIDEST_OUTER_DBM }) {
                    Text("Reset", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(
                    onClick = {
                        outerDbm = (outerDbm + ZOOM_STEP_DB).coerceAtMost(NARROWEST_OUTER_DBM)
                    },
                    enabled = outerDbm < NARROWEST_OUTER_DBM,
                ) { Text("+", style = MaterialTheme.typography.labelLarge) }
            }
        }

        footnote?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val chosen = targets.firstOrNull { it.address == selected }
            .takeIf { showSelectionCard }
        if (showSelectionCard && selected != null && chosen == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "That one has gone out of range.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        chosen?.let { target ->
            Spacer(Modifier.height(10.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.padding(end = 8.dp)) {
                            Text(
                                target.label,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                target.address,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            String.format(Locale.US, "%.0f dBm", target.smoothedRssi),
                            style = MaterialTheme.typography.titleSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    detail(target)
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { onSelect(null) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Clear selection") }
                }
            }
        }
    }
}
