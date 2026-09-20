package com.sigeye.experiments.following

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.DeviceBook
import com.sigeye.core.Experiments
import com.sigeye.core.FollowStore
import com.sigeye.core.Permissions
import com.sigeye.core.Recordings
import com.sigeye.core.Subject
import com.sigeye.core.analysis.identity.Following
import com.sigeye.core.analysis.identity.Handover
import com.sigeye.core.ble.BleScanHub
import com.sigeye.ui.Diagnostic
import com.sigeye.ui.DiagnosticsPanel
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.Field
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.Section
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val HUB_TAG = "following"

@Composable
fun FollowingScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The suite's mode switcher, drawn under this screen's own header.
     *
     * A slot rather than a bar the container draws above everything, so the switcher lands
     * below the title it belongs to instead of above the back button. Empty by default,
     * which is what keeps this screen openable on its own.
     */
    modes: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.FOLLOWING, onBack)
        modes()
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To recognize a named device after it changes its address.",
                ),
                PermissionReason("Location", "Android returns no scan results without it."),
            ),
            footnote = "Names and lists live on this phone and go nowhere. Following " +
                "rewrites which address a name is attached to, and every rewrite is " +
                "logged below and can be undone.",
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
    val store = remember { FollowStore.get(context) }
    val follower = remember {
        Recordings.init(context)
        Recordings.follower
    } ?: return

    val lists by book.lists.collectAsStateWithLifecycle()
    val followed by store.lists.collectAsStateWithLifecycle()
    val handovers by store.handovers.collectAsStateWithLifecycle()
    val state by follower.state.collectAsStateWithLifecycle()

    // Holding the radio while this screen is open, so a rotation that happens while you
    // are watching is actually caught. The follower itself never does this.
    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose { BleScanHub.release(HUB_TAG) }
    }

    Headline(state.watching, state.addressesInPlay, handovers.size)

    Spacer(Modifier.height(14.dp))
    Text("Lists to follow", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Text(
        "Devices on a followed list keep their name when they change address. Everything " +
            "else is left alone.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    if (lists.isEmpty()) {
        Text(
            "No lists yet. Make one in Device Inspector - tap a device, give it a name " +
                "and put it on a list - and it will appear here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            lists.forEach { list ->
                FilterChip(
                    selected = followed.contains(list),
                    onClick = { store.toggle(list) },
                    label = { Text(list) },
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Text("Being followed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    if (state.subjects.isEmpty()) {
        Text(
            if (followed.isEmpty()) {
                "Nothing is being followed. Turn on a list above."
            } else {
                "Nothing on those lists has a name yet."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        state.subjects.forEach { SubjectCard(it) }
    }

    if (handovers.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Every move it has made",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            TextButton(onClick = { store.clearLog() }) { Text("Clear log") }
        }
        handovers.forEach { handover ->
            HandoverCard(handover) { follower.undo(handover.id) }
        }
    }

    Spacer(Modifier.height(16.dp))
    Section(
        title = "When it refuses, and why that is the point",
        summary = "Losing a device is an inconvenience. Naming the wrong one is a lie.",
        emphasis = true,
    ) {
        Text(
            "A nickname is stored against an address. That works for a speaker and is " +
                "useless for a phone, which puts on a new address every quarter of an " +
                "hour - so the device you named comes back as a stranger. This follows it " +
                "across, using the same fingerprinting Defeating Randomization " +
                "demonstrates: the advertisement's structure, the advertising interval, " +
                "the signal not jumping, and the timing of the swap.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Getting it wrong is far worse than getting nothing. A name on the wrong " +
                "phone becomes a fact that Signal Watch, Discovery, Forensics and " +
                "Traveling Companions all then repeat, and nothing later corrects it. So " +
                "it refuses in every case where it cannot be sure:",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Field("Strongest only", "Merely probable is not acted on.")
        Field(
            "Two matches means stop",
            "A room of identical phones is exactly when the better of two would be wrong.",
        )
        Field("Plain advertisements", "Nothing distinctive means any match is coincidence.")
        Field(
            "Appeared at the swap",
            "Something already on the air beforehand is a different device.",
        )
        Field("Ten minutes", "After that the trail is cold and it stops trying.")
        Field("Never onto a named device", "A device you have named is evidently not this one.")
    }

    Spacer(Modifier.height(10.dp))
    Section(
        title = "It does not turn the radio on",
        summary = "A passenger on whatever is already scanning.",
    ) {
        Text(
            "Following listens to whatever scanning is already happening - an experiment " +
                "screen, or a background recording - and never starts a scan of its own. " +
                "So it costs nothing while the app is idle, and equally it notices " +
                "nothing then. To follow a device through an afternoon, leave a " +
                "background recording running: Forensics or Journey will do.",
            style = MaterialTheme.typography.bodySmall,
        )
    }

    Spacer(Modifier.height(12.dp))
    DiagnosticsPanel(
        title = "What this is seeing",
        diagnostics = listOf(
            Diagnostic("Followed lists", "${followed.size}", "of ${lists.size}"),
            Diagnostic("Subjects", "${state.watching}", "named devices"),
            Diagnostic("In range", "${state.subjects.count { it.audible }}", "right now"),
            Diagnostic("Addresses", "${state.addressesInPlay}", "audible candidates"),
            Diagnostic("Moves", "${handovers.size}", "logged"),
            Diagnostic("Silence", "${Following.SILENCE_MS / 1000}s", "before it looks"),
        ),
        footnote = "A device is only considered gone after " +
            "${Following.SILENCE_MS / 1000} seconds of silence, and given up on after " +
            "${Following.GIVE_UP_MS / 60_000} minutes. In between, every audible address " +
            "nobody has named is a candidate.",
    )
}

// -------------------------------------------------------------------------- headline

@Composable
private fun Headline(watching: Int, addresses: Int, moves: Int) {
    val active = watching > 0
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (active) {
                    "Following $watching device${if (watching == 1) "" else "s"}"
                } else {
                    "Nothing is being followed"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "$addresses addresses audible · $moves move${if (moves == 1) "" else "s"} made",
                style = MaterialTheme.typography.bodySmall,
                color = if (active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

// --------------------------------------------------------------------- one subject

@Composable
private fun SubjectCard(subject: Subject) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.padding(end = 8.dp)) {
                    Text(subject.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        subject.address,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (subject.audible) "in range" else "quiet",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (subject.audible) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(subject.lists.joinToString(", "))
                    if (subject.rotations > 0) {
                        append(" · followed through ${subject.rotations} ")
                        append(if (subject.rotations == 1) "rotation" else "rotations")
                    }
                    if (subject.packets > 0) append(" · ${subject.packets} packets")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            subject.note?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// -------------------------------------------------------------------------- the log

@Composable
private fun HandoverCard(handover: Handover, onUndo: () -> Unit) {
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Card(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${handover.label} moved address",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                "${clock.format(Date(handover.atMs))} · ${handover.fromAddress} → " +
                    handover.toAddress,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(6.dp))
            handover.reasons.forEach {
                Text(
                    "· $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${handover.points} points of evidence",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                TextButton(onClick = onUndo) { Text("That was wrong") }
            }
        }
    }
}
