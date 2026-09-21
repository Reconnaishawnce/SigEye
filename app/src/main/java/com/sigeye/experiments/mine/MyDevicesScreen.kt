package com.sigeye.experiments.mine

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.sigeye.core.MyDevices
import com.sigeye.core.Recordings
import com.sigeye.core.Permissions
import com.sigeye.core.analysis.identity.MyKit
import com.sigeye.core.analysis.identity.Nearby
import com.sigeye.core.analysis.identity.Owned
import com.sigeye.core.ble.BleScanHub
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.Section
import kotlinx.coroutines.delay

private const val HUB_TAG = "mydevices"

/** How long to listen before deciding. Long enough that somebody turns around once. */
private const val WINDOW_MS = 30_000L

/**
 * Marking the devices you are carrying, so the rest of the app stops counting them.
 *
 * The problem this solves is quiet and it is everywhere. Your watch, your earbuds and your
 * car are in range of every experiment, and none of them is a stranger. In Follow Me it is
 * worse than a counting error: the method is elimination by walking, your own kit walks
 * with you by definition, so it survives every round and ends up looking like the answer.
 *
 * Typing in addresses was never going to work. Android will not tell an app its own
 * Bluetooth address, so the phone cannot look them up, and nobody knows what their earbuds
 * advertise as. So this listens for half a minute and works out which devices behave like
 * they are attached to you rather than sitting in the room. See [MyKit] for how, and why
 * holding still matters more than being loud.
 *
 * It suggests and never decides. Marking a stranger's phone as yours would mute it from
 * every count in the app silently, and a silent wrong exclusion is the kind of error
 * nobody ever finds.
 */
@Composable
fun MyDevicesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) { Text("← Back") }
        Text(
            "Your own devices",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Everything you are carrying is in range of every experiment, and none of it " +
                "is a stranger. In a follow your own kit never drops out, because it goes " +
                "everywhere you go, which is exactly the test being applied.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To hear what you are carrying. Nothing is connected to.",
                ),
                PermissionReason("Location", "Android returns no scan results without it."),
            ),
            footnote = "The list stays on this phone.",
        ) {
            Finder()
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Finder() {
    val context = LocalContext.current
    val mine = remember { MyDevices.get(context) }
    val owned by mine.devices.collectAsStateWithLifecycle()

    var listening by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var found by remember { mutableStateOf<List<Owned>>(emptyList()) }
    val picked = remember { mutableStateMapOf<String, Boolean>() }
    val heard = remember { mutableStateMapOf<String, Nearby>() }

    if (listening) {
        DisposableEffect(Unit) {
            BleScanHub.init(context)
            BleScanHub.acquire(HUB_TAG)
            onDispose { BleScanHub.release(HUB_TAG) }
        }

        LaunchedEffect(Unit) {
            heard.clear()
            BleScanHub.adverts.collect { advert ->
                val key = advert.address.uppercase()
                val existing = heard[key]
                heard[key] = if (existing == null) {
                    Nearby(
                        address = key,
                        name = advert.name,
                        vendor = advert.vendor,
                        rssis = listOf(advert.rssi),
                        firstSeenMs = advert.atMs,
                        lastSeenMs = advert.atMs,
                    )
                } else {
                    existing.copy(
                        name = advert.name ?: existing.name,
                        vendor = advert.vendor ?: existing.vendor,
                        rssis = existing.rssis + advert.rssi,
                        lastSeenMs = advert.atMs,
                    )
                }
            }
        }

        LaunchedEffect(Unit) {
            val startedAt = System.currentTimeMillis()
            while (true) {
                delay(250)
                elapsedMs = System.currentTimeMillis() - startedAt
                if (elapsedMs >= WINDOW_MS) break
            }
            found = MyKit.candidates(heard.values.toList(), WINDOW_MS)
                .filterNot { mine.isMine(it.address) }
            found.forEach { picked[it.address] = true }
            listening = false
        }
    }

    Section(
        title = "Find what you are carrying",
        summary = "Listen for half a minute and see what behaves like it is on you.",
        initiallyExpanded = true,
    ) {
        if (listening) {
            Text(
                "Listening. Turn around once, or walk a few steps and come back. The thing " +
                    "that gives your own kit away is that its signal barely moves while " +
                    "everything in the room swells and fades.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (elapsedMs.toFloat() / WINDOW_MS).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${(WINDOW_MS - elapsedMs).coerceAtLeast(0L) / 1000}s left, " +
                    "${heard.size} devices heard",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Button(
                onClick = {
                    found = emptyList()
                    picked.clear()
                    elapsedMs = 0L
                    listening = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (found.isEmpty()) "Listen for 30 seconds" else "Listen again") }
        }
    }

    if (!listening && found.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Section(
            title = "These look like yours",
            summary = "Suggestions only. Nothing is marked until you check it.",
            initiallyExpanded = true,
        ) {
            Text(
                "Nothing is marked until you say so. Check the ones you recognize.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            found.forEach { candidate ->
                CandidateRow(
                    candidate = candidate,
                    checked = picked[candidate.address] == true,
                    onCheck = { picked[candidate.address] = it },
                )
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = {
                    found.filter { picked[it.address] == true }
                        .forEach { mine.add(it.address, it.label) }
                    found = emptyList()
                    picked.clear()
                },
                enabled = picked.values.any { it },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("These are mine") }
        }
    }

    if (!listening && found.isEmpty() && elapsedMs >= WINDOW_MS) {
        Spacer(Modifier.height(14.dp))
        Text(
            "Nothing behaved like it was on you. That is a real answer rather than a " +
                "failure: if your watch is paired and idle it may not have advertised at " +
                "all in that window. Try again with the watch screen woken up, or with " +
                "earbuds taken out of the case.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    AdoptionLog()

    Spacer(Modifier.height(18.dp))
    Section(
        title = "Marked as yours (${owned.size})",
        summary = "Excluded from follows and counts across the app.",
        initiallyExpanded = true,
    ) {
        if (owned.isEmpty()) {
            Text(
                "Nothing yet. Until something is here, every count in the app includes " +
                    "whatever you are carrying.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            owned.forEach { device ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(device.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            device.address,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { mine.remove(device.address) }) { Text("Not mine") }
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { mine.clear() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Clear the list") }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "A device that randomizes its address will need marking again once it " +
                "rotates. Matching on the payload instead would mute every identical pair " +
                "of earbuds in the building rather than the pair in your pocket, which is " +
                "worse than asking you to press a button now and then.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Every address change the app made on your behalf, and every one it declined.
 *
 * Not decoration. Moving a mark onto the wrong device would quietly exclude a stranger's
 * phone from every count in the app, indefinitely, and a silent wrong exclusion is the one
 * kind of error nobody goes looking for. So each one is written down with what convinced
 * it, and each one can be undone by somebody who disagrees.
 */
@Composable
private fun AdoptionLog() {
    val watcher = Recordings.ownKit ?: return
    // Recomposed off the device list, which is what changes when an adoption lands.
    val mine = MyDevices.get(LocalContext.current)
    val owned by mine.devices.collectAsStateWithLifecycle()
    val adoptions = remember(owned) { watcher.adoptions() }
    val declined = remember(owned) { watcher.declined() }

    if (adoptions.isEmpty() && declined.isEmpty()) return

    Spacer(Modifier.height(18.dp))
    Section(
        title = "Address changes followed (${adoptions.size})",
        summary = "What the app moved on your behalf, and what it refused to.",
        initiallyExpanded = adoptions.isNotEmpty(),
    ) {
        adoptions.forEach { adoption ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "${adoption.label} became ${adoption.toAddress}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        adoption.fromAddress,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${adoption.confidence.label}. ${adoption.why} Heard at " +
                            "${adoption.rssi} dBm.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { watcher.undo(adoption) }) {
                        Text("That was not mine")
                    }
                }
            }
        }

        if (declined.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Not followed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            declined.forEach { refusal ->
                Text(
                    "${refusal.fromAddress}: ${refusal.why}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: Owned, checked: Boolean, onCheck: (Boolean) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheck)
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text(
                    candidate.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    candidate.address,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    candidate.why,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
