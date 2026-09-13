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
 * The arranging controls only exist while arranging, and the way in is one grey word at
 * the bottom of the panels. A screen covered in little arrows all the time is a worse
 * screen for the ninety-nine percent of the time nobody is rearranging it, and a bright
 * button competing with the measurement is worse still. The control has to be findable,
 * not prominent, so it sits below the last panel in the same colour as a caption.
 *
 * The switch lives here rather than in the caller. Every screen that gained panels also
 * gained a copy of the same boolean and the same little row to toggle it, which is four
 * lines to get subtly different on each screen.
 *
 * Hiding is separate from ordering on purpose: putting a panel away should not forget
 * where it was if you bring it back.
 */
@Composable
fun TileColumn(
    screen: String,
    tiles: List<Tile>,
    modifier: Modifier = Modifier,
    spacing: Int = 16,
) {
    var arranging by remember { mutableStateOf(false) }
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

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (arranging) {
                Text(
                    "Back to the original layout",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { store.reset(screen) }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
            Text(
                if (arranging) "Done" else "Arrange panels",
                style = MaterialTheme.typography.labelMedium,
                color = if (arranging) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .clickable(
                        onClickLabel = if (arranging) {
                            "Finish arranging the panels"
                        } else {
                            "Rearrange, hide or show the panels on this screen"
                        },
                    ) { arranging = !arranging }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            )
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
