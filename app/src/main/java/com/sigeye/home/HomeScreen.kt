package com.sigeye.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.CheckLevel
import com.sigeye.core.Experiment
import com.sigeye.core.Experiments
import com.sigeye.core.FavouriteStore
import com.sigeye.core.FirstRun
import com.sigeye.core.Preflight
import com.sigeye.core.ScanService
import com.sigeye.core.ble.BleScanHub
import com.sigeye.ui.BackupWarning
import java.util.Locale

@Composable
fun HomeScreen(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { FavouriteStore.get(context) }
    val firstRun = remember { FirstRun.get(context) }
    val favouriteIds by store.ids.collectAsStateWithLifecycle()
    val welcomed by firstRun.dismissed.collectAsStateWithLifecycle()
    val backupWarned by firstRun.backupWarned.collectAsStateWithLifecycle()
    var arranging by remember { mutableStateOf(false) }

    // After the welcome card has been put away, not alongside it. Two dialogs at once is
    // one dialog too many, and this is the one that must actually be read.
    if (welcomed && !backupWarned) {
        BackupWarning(onDismiss = { firstRun.markBackupWarned() })
    }

    val favourites = remember(favouriteIds) {
        favouriteIds.mapNotNull { Experiments.byId(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = "SigEye",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Experiments for the radio signals around you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${Experiments.readyCount} ready · ${Experiments.all.size} planned in total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        if (!welcomed) {
            Welcome { firstRun.dismiss() }
            Spacer(Modifier.height(10.dp))
        }
        RadioStatus()
        Spacer(Modifier.height(10.dp))
        PreflightCard()
        Spacer(Modifier.height(14.dp))

        FavouritesSection(
            favourites = favourites,
            editing = arranging,
            store = store,
            onEditToggle = { arranging = !arranging },
            onOpen = onOpen,
        )

        Experiments.byCategory().forEach { (category, experiments) ->
            Text(
                text = category.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = category.blurb,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            experiments.forEach { experiment ->
                ExperimentCard(
                    experiment = experiment,
                    favourite = favouriteIds.contains(experiment.id),
                    onToggleFavourite = { store.toggle(experiment.id) },
                    onClick = {
                        if (experiment.status.openable) onOpen(experiment.id)
                    },
                )
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(14.dp))
        }

        Text(
            text = "Everything runs on this phone. No accounts, no network, no analytics.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
    }
}

/**
 * What this is, in the time somebody will actually give it.
 *
 * Not a wizard. A sequence of full-screen pages before anyone is allowed to use the app is
 * a tax on everyone who would have worked it out anyway, and is skipped by exactly the
 * people it was written for. Three short paragraphs at the top of the list, dismissed for
 * good with one tap.
 */
@Composable
private fun Welcome(onDismiss: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Your phone is already listening",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Everything around you is broadcasting - earbuds, cars, tills, " +
                    "doorbells, and every phone in the room. This turns that into " +
                    "experiments you can run and check, rather than a list of addresses.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Start with a favourite below. Each one opens with what it needs, " +
                    "how to run it, what the reading means, and where it lies to you - " +
                    "tap the title for that. Nothing leaves the phone and nothing is " +
                    "connected to unless you ask.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Scanning costs battery, so it happens only while an experiment is " +
                    "open or a recording is running. The card below always says which.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onDismiss) { Text("Got it") }
        }
    }
}

/**
 * The front door: the user's own shortlist, in the user's own order.
 *
 * A categorised list of twenty-seven experiments is a good library and a bad first
 * impression, because everything is equally weighted and so nothing is. This starts
 * pre-filled with the recommended set - an empty shelf on first launch would be worse than
 * a guess - and from then on it is the user's. Starring is on every card everywhere, and
 * the arrows only appear once the header's edit toggle is on, so the common case stays a
 * list of cards rather than a list of controls.
 */
@Composable
private fun FavouritesSection(
    favourites: List<Experiment>,
    editing: Boolean,
    store: FavouriteStore,
    onEditToggle: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Favourites",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = if (favourites.isEmpty()) {
                    "Star anything below to put it up here."
                } else if (editing) {
                    "Move them about, or tap a star to remove one."
                } else {
                    "Start here. Yours to rearrange."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (favourites.isNotEmpty() || editing) {
            TextButton(onClick = onEditToggle) {
                Text(if (editing) "Done" else "Arrange")
            }
        }
    }
    Spacer(Modifier.height(10.dp))

    favourites.forEach { experiment ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !editing) { onOpen(experiment.id) },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(
                        text = experiment.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = Experiments.featuredReasons[experiment.id] ?: experiment.blurb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                if (editing) {
                    // Enabled from the list actually on screen rather than from the store,
                    // so the arrow a thumb is over always matches what it can see.
                    Glyph(
                        text = "▲",
                        enabled = favourites.first().id != experiment.id,
                        colour = MaterialTheme.colorScheme.onPrimaryContainer,
                        description = "Move ${experiment.title} up",
                    ) { store.moveUp(experiment.id) }
                    Glyph(
                        text = "▼",
                        enabled = favourites.last().id != experiment.id,
                        colour = MaterialTheme.colorScheme.onPrimaryContainer,
                        description = "Move ${experiment.title} down",
                    ) { store.moveDown(experiment.id) }
                }
                Glyph(
                    text = "★",
                    colour = MaterialTheme.colorScheme.onPrimaryContainer,
                    description = "Remove ${experiment.title} from favourites",
                ) { store.toggle(experiment.id) }
                if (!editing) {
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(end = 14.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (editing && favourites.isNotEmpty()) {
        TextButton(onClick = { store.reset() }) { Text("Back to the recommended set") }
    }
    Spacer(Modifier.height(16.dp))
}

/**
 * A tappable character.
 *
 * Text rather than an icon: the app pulls in no icon library, and a star and two arrows
 * are three glyphs every font already has. The touch target is padded out to something a
 * thumb can hit, which is the part that actually matters.
 */
@Composable
private fun Glyph(
    text: String,
    colour: Color,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) colour else colour.copy(alpha = 0.25f),
        modifier = Modifier
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    )
}

/**
 * Whether the radio is actually on, and whether it will stay on after you leave.
 *
 * Worth its own place on the home screen: scanning is the one thing this app does that
 * costs battery, and until now there was no way to tell from the outside whether anything
 * was running. Two separate facts, deliberately kept apart - an experiment holds the radio
 * only while its screen is open, whereas a background mode survives leaving the app and
 * keeps a notification in the status bar.
 */
@Composable
private fun RadioStatus() {
    val health by BleScanHub.health.collectAsStateWithLifecycle()
    val activeModes by ScanService.activeModes.collectAsStateWithLifecycle()

    val background = activeModes.isNotEmpty()
    val scanning = health.scanning

    val container = when {
        background -> MaterialTheme.colorScheme.errorContainer
        scanning -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when {
        background -> MaterialTheme.colorScheme.onErrorContainer
        scanning -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(10.dp)) {
                drawCircle(color = if (scanning || background) dotOn else dotOff)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        background -> "Recording in the background"
                        scanning -> "Radio on"
                        else -> "Idle"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = content,
                )
                Text(
                    when {
                        background -> activeModes.joinToString(", ") { it.label } +
                            " will keep scanning after you leave the app, and will keep " +
                            "using battery, until you stop it from its own screen."
                        scanning -> String.format(
                            Locale.US,
                            "%.0f packets a second. Stops when you leave the experiment.",
                            health.advertsPerSecond,
                        )
                        else -> "Nothing is scanning. Open an experiment to start."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = content,
                )
                // Who is holding the radio, named. The radio staying on with nothing open
                // is a leaked claim, and a count alone cannot be acted on - the whole
                // symptom is a battery that empties while the app looks idle.
                if (scanning && health.claims.isNotEmpty()) {
                    Text(
                        "Held by " + health.claims.sorted().joinToString(", "),
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

private val dotOn = Color(0xFF35C759)
private val dotOff = Color(0xFF8A8A8E)

/**
 * Everything that has to be switched on, checked before anything starts.
 *
 * Scanning fails in several different ways that all look identical from inside an
 * experiment - an empty list - and most of them are a setting rather than a bug. The worst
 * offender is location services: Android returns no Bluetooth results at all with it off,
 * silently, which surprises everyone who meets it.
 */
@Composable
private fun PreflightCard() {
    val context = LocalContext.current
    var attempt by remember { mutableStateOf(0) }
    val checks = remember(attempt) { Preflight.run(context) }
    val failing = checks.filter { !it.passing }
    val blocking = Preflight.blockingCount(checks)
    var expanded by remember { mutableStateOf(false) }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                blocking > 0 -> MaterialTheme.colorScheme.errorContainer
                failing.isNotEmpty() -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            blocking > 0 -> "$blocking thing${if (blocking == 1) "" else "s"} " +
                                "will stop this working"
                            failing.isNotEmpty() -> "${failing.size} optional " +
                                "thing${if (failing.size == 1) "" else "s"} to look at"
                            else -> "Everything needed is switched on"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (!expanded && blocking > 0) {
                        Text(
                            failing.first { it.blocking }.title,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                Text(
                    if (expanded) "Hide  ▴" else "Check  ▾",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            if (expanded) {
                checks.forEach { check ->
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (check.passing) "✓" else "✗",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (check.passing) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            check.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        check.why,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 22.dp),
                    )
                    if (!check.passing || check.level == CheckLevel.OPTIONAL) {
                        check.fix?.let { fix ->
                            Text(
                                fix,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 22.dp, top = 2.dp),
                            )
                        }
                        Preflight.intentFor(context, check)?.let { intent ->
                            TextButton(
                                onClick = { runCatching { context.startActivity(intent) } },
                                modifier = Modifier.padding(start = 14.dp),
                            ) {
                                Text("Open settings", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { attempt++ }) {
                    Text("Check again", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ExperimentCard(
    experiment: Experiment,
    favourite: Boolean,
    onToggleFavourite: () -> Unit,
    onClick: () -> Unit,
) {
    val ready = experiment.status.openable
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = ready, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (ready) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = experiment.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    color = if (ready) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (ready) {
                    // Hollow when it is not a favourite, so the row of cards reads as
                    // cards rather than as a column of controls.
                    Glyph(
                        text = if (favourite) "★" else "☆",
                        colour = if (favourite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        description = if (favourite) {
                            "Remove ${experiment.title} from favourites"
                        } else {
                            "Add ${experiment.title} to favourites"
                        },
                        onClick = onToggleFavourite,
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            "soon",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = experiment.blurb,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Planned entries get the "why" too - the roadmap should be readable, not a
            // row of teasers.
            if (!ready) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = experiment.teaches,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                experiment.needs?.let { needs ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Needs: $needs",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
