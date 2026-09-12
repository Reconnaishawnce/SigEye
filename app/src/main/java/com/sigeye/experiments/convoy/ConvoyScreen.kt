package com.sigeye.experiments.convoy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.AlertStyle
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.Feedback
import com.sigeye.core.Permissions
import com.sigeye.core.analysis.ConvoyReport
import com.sigeye.core.analysis.ConvoyTracker
import com.sigeye.core.analysis.FollowConfidence
import com.sigeye.core.analysis.Follower
import com.sigeye.core.ble.BleScanHub
import com.sigeye.ui.AlertPicker
import com.sigeye.ui.Diagnostic
import com.sigeye.ui.DiagnosticsPanel
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.KeepScreenOn
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import kotlinx.coroutines.delay
import java.util.Locale

private const val HUB_TAG = "convoy"
private const val TICK_MS = 3_000L

/** The list devices get filed into when you mark them as your own. */
private const val MINE_LIST = "Mine"

@Composable
fun ConvoyScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.CONVOY, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To compare what is around you in one place against another.",
                ),
                PermissionReason("Location", "Android returns no scan results without it."),
            ),
            footnote = "Nothing is transmitted, and no positions are recorded - only which " +
                "devices were audible in each leg.",
        ) {
            Live()
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Live() {
    val context = LocalContext.current
    val book = remember { DeviceBook.get(context) }
    val feedback = remember { Feedback(context) }
    val tracker = remember { ConvoyTracker() }

    val notes by book.notes.collectAsStateWithLifecycle()

    var recording by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(ConvoyReport(emptyList(), emptyList(), 0)) }
    var naming by remember { mutableStateOf(false) }
    var alertStyle by remember { mutableStateOf(AlertStyle.BUZZ) }
    var announced by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expanded by remember { mutableStateOf<String?>(null) }

    val mine = remember(notes) {
        notes.filterValues { it.lists.contains(MINE_LIST) }.keys
    }

    KeepScreenOn(recording)

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose {
            feedback.release()
            BleScanHub.release(HUB_TAG)
        }
    }

    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        BleScanHub.adverts.collect { advert ->
            tracker.observe(
                address = advert.address,
                rssi = advert.rssi,
                atMs = advert.atMs,
                label = notes[advert.address.uppercase(Locale.US)]?.nickname
                    ?: advert.name?.takeIf { it.isNotBlank() }
                    ?: advert.vendor,
                vendor = advert.vendor,
                isRandom = advert.isRandomAddress,
            )
        }
    }

    LaunchedEffect(recording, mine) {
        while (true) {
            delay(TICK_MS)
            val fresh = tracker.report(mine = mine)
            report = fresh
            // Alert only on the strongest rating, and only once each. Anything looser
            // fires constantly on a journey and stops meaning anything.
            if (fresh.verdict() == null) {
                val newStrong = fresh.strong.filter { it.address !in announced }
                if (newStrong.isNotEmpty()) {
                    announced = announced + newStrong.map { it.address }
                    feedback.alert(alertStyle, urgent = true)
                }
            }
        }
    }

    // ------------------------------------------------------------------ intro

    if (tracker.legCount == 0) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Has anything come with you?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Record a leg in one place, another somewhere else, a third somewhere " +
                        "else again. Anything appearing in all of them was travelling " +
                        "rather than living in any of them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Before you start: file your own earbuds, watch and car under a list " +
                        "called \"$MINE_LIST\" in Device Inspector. They follow you " +
                        "perfectly, and without that the list is forty rows of your own " +
                        "belongings.",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        AlertPicker(
            style = alertStyle,
            onStyle = { alertStyle = it },
            feedback = feedback,
            title = "Alert on a companion",
            note = "Fires once, and only for something present in every leg over a real " +
                "span of time.",
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { naming = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Start the first leg")
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat("Legs", "${tracker.legCount}", "recorded")
            Stat("Devices", "${report.devicesSeen}", "heard anywhere")
            Stat("Candidates", "${report.candidates.size}", "in more than one")
        }

        Spacer(Modifier.height(10.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (recording) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    if (recording) {
                        "Recording leg ${tracker.legCount}: ${tracker.currentLabel}"
                    } else {
                        "Between legs - nothing is being recorded"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (recording) {
                        "Give it a few minutes so everything nearby has a chance to speak, " +
                            "then stop before you move on."
                    } else {
                        "Travel somewhere genuinely different, then start the next leg."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        if (recording) {
            Button(
                onClick = {
                    tracker.closeLeg(System.currentTimeMillis())
                    recording = false
                    report = tracker.report(mine = mine)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Finish this leg") }
        } else {
            Button(onClick = { naming = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Start leg ${tracker.legCount + 1}")
            }
        }

        Spacer(Modifier.height(12.dp))
        DiagnosticsPanel(
            title = "How much this is worth",
            verdict = report.verdict(),
            diagnostics = listOf(
                Diagnostic("Legs", "${report.legs.size}", "closed"),
                Diagnostic(
                    "Span",
                    "${report.totalSpan() / 60_000L}m",
                    "first to last",
                ),
                Diagnostic("Devices", "${report.devicesSeen}", "in total"),
                Diagnostic("Shared", "${report.followers.size}", "in 2+ legs"),
                Diagnostic("Yours", "${mine.size}", "filed as yours"),
                Diagnostic("Flagged", "${report.strong.size}", "in every leg"),
            ),
            footnote = "Two legs recorded close together share most of their contents " +
                "simply because they overlap, so this refuses to draw conclusions until " +
                "there is real time and distance between them. Randomised addresses " +
                "defeat it outright: a phone following you appears as a stranger in every " +
                "leg. What it can catch is fitted equipment, tyre sensors and cheap " +
                "trackers - things whose address never changes.",
        )

        if (report.candidates.isEmpty() && report.legs.size >= 2) {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "Nothing with a fixed address turned up in more than one leg. That is " +
                        "the answer you want, and it is a partial one - anything rotating " +
                        "its address could not have been caught this way.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        report.candidates.forEach { follower ->
            Spacer(Modifier.height(8.dp))
            FollowerCard(
                follower = follower,
                legs = report.legs.size,
                expanded = expanded == follower.address,
                onToggle = {
                    expanded = if (expanded == follower.address) null else follower.address
                },
                onMine = {
                    book.createList(MINE_LIST)
                    book.toggleList(follower.address, MINE_LIST)
                },
            )
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = {
                tracker.reset()
                recording = false
                announced = emptySet()
                report = tracker.report(mine = mine)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start a new journey") }
    }

    if (naming) {
        var label by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Where are you?") },
            text = {
                Column {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Name this leg") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Only a label for you - no position is recorded. Legs need to be " +
                            "genuinely different places, far enough apart that the same " +
                            "devices would not be audible in both by accident.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    tracker.startLeg(
                        label.ifBlank { "Leg ${tracker.legCount + 1}" },
                        System.currentTimeMillis(),
                    )
                    recording = true
                    naming = false
                }) { Text("Start") }
            },
            dismissButton = {
                TextButton(onClick = { naming = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FollowerCard(
    follower: Follower,
    legs: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onMine: () -> Unit,
) {
    val strong = follower.confidence == FollowConfidence.STRONG
    Card(
        Modifier.fillMaxWidth().clickable { onToggle() },
        colors = CardDefaults.cardColors(
            containerColor = when (follower.confidence) {
                FollowConfidence.STRONG -> MaterialTheme.colorScheme.errorContainer
                FollowConfidence.NOTABLE -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.padding(end = 8.dp)) {
                    Text(
                        follower.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        follower.address + if (follower.isRandom) "  (random)" else "",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${follower.legsShared}/$legs",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "legs",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                follower.confidence.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (expanded) {
                Spacer(Modifier.height(6.dp))
                follower.reasons().forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                follower.vendor?.let {
                    Text(
                        "Vendor: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    String.format(
                        Locale.US,
                        "Strongest reading %d dBm, across %.0f minutes.",
                        follower.bestRssi,
                        follower.spanMs / 60_000.0,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onMine) {
                    Text(
                        "This is mine - stop flagging it",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else {
                Text(
                    "Tap for why",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, caption: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
