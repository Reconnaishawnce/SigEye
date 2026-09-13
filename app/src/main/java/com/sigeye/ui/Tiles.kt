package com.sigeye.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.TileStore

/** One movable panel on a screen. */
data class Tile(
    val id: String,
    /** Shown only while arranging, so the usual view stays uncluttered. */
    val title: String,
    val content: @Composable () -> Unit,
)

/**
 * A screen's panels, in the order the person using it put them.
 *
 * Not everybody wants the same thing at the top. Somebody filming a train going past wants
 * the count and the chart and nothing else. Somebody working out why a reading looks odd
 * wants the diagnostics first. Rather than guess, this lets them move it, and remembers.
 *
 * The arranging controls only exist while arranging. A screen covered in little arrows all
 * the time is a worse screen for the ninety-nine percent of the time nobody is rearranging
 * it, and this app is meant to be pointed at things rather than fiddled with.
 *
 * Hiding is separate from ordering on purpose: putting a panel away should not forget
 * where it was if you bring it back.
 */
@Composable
fun TileColumn(
    screen: String,
    tiles: List<Tile>,
    modifier: Modifier = Modifier,
    arranging: Boolean = false,
    spacing: Int = 16,
) {
    val context = LocalContext.current
    val store = remember { TileStore.get(context) }
    val version by store.version.collectAsStateWithLifecycle()

    val available = tiles.map { it.id }
    val order = remember(version, available) { store.order(screen, available) }
    val hidden = remember(version) { store.hidden(screen) }
    val byId = tiles.associateBy { it.id }

    Column(modifier) {
        order.forEach { id ->
            val tile = byId[id] ?: return@forEach
            val isHidden = hidden.contains(id)

            if (arranging) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tile.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isHidden) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TileGlyph(
                            text = "▲",
                            enabled = order.first() != id,
                            description = "Move ${tile.title} up",
                        ) { store.moveUp(screen, id, available) }
                        TileGlyph(
                            text = "▼",
                            enabled = order.last() != id,
                            description = "Move ${tile.title} down",
                        ) { store.moveDown(screen, id, available) }
                        TileGlyph(
                            text = if (isHidden) "☐" else "☑",
                            description = if (isHidden) {
                                "Show ${tile.title}"
                            } else {
                                "Hide ${tile.title}"
                            },
                        ) { store.toggleHidden(screen, id) }
                    }
                }
            }

            // Animated so a panel moving or disappearing reads as a thing that moved,
            // rather than the screen flickering into a different shape.
            AnimatedVisibility(visible = !isHidden) {
                Column {
                    tile.content()
                    Spacer(Modifier.height(spacing.dp))
                }
            }
            if (isHidden && arranging) Spacer(Modifier.height(8.dp))
        }

        if (arranging) {
            TextButton(onClick = { store.reset(screen) }) {
                Text("Back to the original layout")
            }
        }
    }
}

/** A tappable character with a thumb-sized target around it. */
@Composable
private fun TileGlyph(
    text: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
        },
        modifier = Modifier
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

/** Convenience so a screen can build its tile list inline and readably. */
fun tiles(build: MutableList<Tile>.() -> Unit): List<Tile> =
    mutableListOf<Tile>().apply(build)

fun MutableList<Tile>.tile(id: String, title: String, content: @Composable () -> Unit) {
    add(Tile(id, title, content))
}
