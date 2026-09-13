package com.sigeye.experiments.follow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sigeye.core.Feedback
import com.sigeye.core.Takeaway
import com.sigeye.core.TargetDevice
import com.sigeye.core.TargetStore
import com.sigeye.core.analysis.identity.CandidateScore
import com.sigeye.core.analysis.identity.CandidateWalkBy
import com.sigeye.core.analysis.identity.FollowCandidate
import com.sigeye.core.analysis.identity.FollowPhase
import com.sigeye.core.analysis.identity.FollowState
import com.sigeye.core.analysis.identity.FollowTuning
import com.sigeye.core.analysis.identity.Odds
import com.sigeye.core.analysis.identity.ProbeRun
import com.sigeye.core.ble.DeviceKind
import com.sigeye.core.ble.shape
import com.sigeye.ui.CountdownRing
import com.sigeye.ui.Field
import com.sigeye.ui.GeigerBar
import com.sigeye.ui.RotationCountdown
import com.sigeye.ui.Section
import java.util.Locale
import kotlin.math.roundToInt

/*
 * The steps and cards a follow draws, split out of FollowScreen.
 *
 * That file had grown to two and a half thousand lines holding both the flow and every
 * piece of furniture it puts on screen, which made it the file most likely to be broken by
 * accident. Everything here takes what it needs as parameters and draws it; the session,
 * the service and the decisions all stay on the other side.
 */

@Composable
internal fun WalkByStep(
    state: FollowState,
    elapsedMs: Long,
    marked: Boolean,
    onLevel: () -> Unit,
    onDone: () -> Unit,
) {
    Text(
        "Walking past them",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        if (marked) {
            "Keep going at the same pace, and stop about as far past them as you started " +
                "before them. The two ends have to be the same distance, or the test cannot " +
                "tell a device beside your path from one along it."
        } else {
            "Walk at a steady pace. Tap the moment you draw level with them - you know when " +
                "that is and no sensor on this phone does."
        },
        style = MaterialTheme.typography.bodySmall,
    )

    Spacer(Modifier.height(16.dp))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "${elapsedMs / 1000} s",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "${state.stillIn.size} still with them",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(16.dp))
    if (marked) {
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Stop here, I am past them")
        }
    } else {
        Button(onClick = onLevel, modifier = Modifier.fillMaxWidth()) {
            Text("I am level with them now")
        }
        Spacer(Modifier.height(6.dp))
        Grey("Give up on this one", onDone)
    }
}

/**
 * What the walk-by saw, with the traces it saw it in.
 *
 * Ranked, best first, and every one drawn. A verdict you cannot check is an assertion, and
 * the whole reason for showing the shape is that a person can tell a clean hill from a mess
 * the thresholds happened to let through in about a second - which is faster and more
 * reliable than any amount of tuning.
 */
/**
 * Every walk-by that has been done, newest first, with the traces to check each against.
 *
 * A list rather than one result, because you can do several and the reason for doing a
 * second is almost always that the first came out ambiguous. Showing only the latest would
 * hide the thing you did the second one to compare against.
 *
 * Each run expands to its own ranked table. A verdict you cannot check is an assertion, and
 * a person can tell a clean hill from a mess the thresholds happened to let through in
 * about a second - faster and more reliably than any amount of tuning from me.
 */
@Composable
internal fun Review(
    state: FollowState,
    targets: List<TargetDevice>,
    expanded: Int?,
    onExpand: (Int?) -> Unit,
    onPromote: (FollowCandidate) -> Unit,
    onKeep: (FollowCandidate) -> Unit,
    onHold: (FollowCandidate) -> Unit,
    onNewWalkBy: () -> Unit,
    onBack: () -> Unit,
) {
    val runs = state.walkBys.sortedByDescending { it.startedAtMs }

    Text(
        "Walk-bys",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        if (runs.isEmpty()) {
            "None yet."
        } else {
            "${runs.size} done. The vertical line on each chart is where you tapped, the " +
                "dashed line is the level the rise is measured against, and the shaded ends " +
                "are the two windows it came from."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(12.dp))
    Button(onClick = onNewWalkBy, modifier = Modifier.fillMaxWidth()) {
        Text(if (runs.isEmpty()) "Do a walk-by" else "Do another walk-by")
    }

    runs.forEach { run ->
        val results = state.walkByResults(run.index)
        val passed = results.count { it.second.score.passed }
        val open = expanded == run.index

        Spacer(Modifier.height(10.dp))
        Card(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Walk-by at ${clock(run.startedAtMs)}") {
                    onExpand(if (open) null else run.index)
                },
            colors = CardDefaults.cardColors(
                containerColor = if (passed > 0) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            clock(run.startedAtMs),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${run.durationMs(run.endedAtMs ?: run.startedAtMs) / 1000} s " +
                                "walk · ${results.size} devices scored",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (passed == 0) "none passed" else "$passed passed",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (!open) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tap to see the traces",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@Column
                }

                if (results.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Nothing was heard often enough during this one to have a shape.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    return@Column
                }

                results.take(8).forEach { (candidate, result) ->
                    Spacer(Modifier.height(12.dp))
                    WalkByResultCard(
                        candidate = candidate,
                        result = result,
                        run = run,
                        alreadyTarget = targets.any {
                            it.address.equals(candidate.address, true)
                        },
                        onPromote = { onPromote(candidate) },
                        onKeep = { onKeep(candidate) },
                        onHold = { onHold(candidate) },
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    Grey("Back to the follow", onBack)
}

@Composable
private fun WalkByResultCard(
    candidate: FollowCandidate,
    result: CandidateWalkBy,
    run: ProbeRun,
    alreadyTarget: Boolean,
    onPromote: () -> Unit,
    onKeep: () -> Unit,
    onHold: () -> Unit,
) {
    val score = result.score
    Column {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.padding(end = 8.dp)) {
                Text(
                    candidate.label ?: candidate.vendor ?: candidate.address,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    candidate.address,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                if (score.passed) "passed" else "no",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(8.dp))
        WalkByChart(
            trail = result.trail,
            score = score,
            startMs = run.startedAtMs,
            endMs = run.endedAtMs ?: score.midAtMs,
        )

        Spacer(Modifier.height(6.dp))
        Field("Rise as you passed", "${score.riseDb.roundToInt()} dB")
        Field("Ends differ by", "${score.symmetryDb.roundToInt()} dB")
        Field("Peak off the mark by", "${score.offsetMs / 1000} s")
        Field("Readings", "${score.packets}")
        Spacer(Modifier.height(4.dp))
        Text(score.describe(), style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onKeep, modifier = Modifier.weight(1f)) { Text("Name and list") }
            if (!alreadyTarget) {
                OutlinedButton(onClick = onPromote, modifier = Modifier.weight(1f)) {
                    Text("Target")
                }
            }
            OutlinedButton(onClick = onHold, modifier = Modifier.weight(1f)) { Text("Hold") }
        }
    }
}

/** A wall-clock time, for telling one walk-by from another. */
private fun clock(atMs: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(atMs))

@Composable
internal fun Targets(
    targets: List<TargetDevice>,
    nowMs: Long,
    onLocate: (String) -> Unit,
    onForget: (String) -> Unit,
    onBack: () -> Unit,
) {
    Text("Targets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Text(
        "Devices a follow settled on, with the evidence it settled on them for. Each one is " +
            "also on a device list called \"${TargetStore.LIST}\", which means every other " +
            "experiment here can already see it: Signal Watch will alert when it comes back " +
            "into range, Persistent Tracking will follow it across address changes, and " +
            "Device Inspector will decode whatever it is broadcasting.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(14.dp))
    if (targets.isEmpty()) {
        Text("Nothing here yet.", style = MaterialTheme.typography.bodyMedium)
    }

    targets.forEach { target ->
        val silentFor = nowMs - target.lastSeenMs
        val lost = silentFor > TARGET_LOST_MS
        Card(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (lost) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    target.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    target.address,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(6.dp))
                Field("From", target.fromFollow + " · " + target.added())
                Field("Evidence", target.evidence)
                if (target.rotations > 0) {
                    Field("Followed through", "${target.rotations} address changes")
                }
                Field(
                    "Heard",
                    if (lost) "not for ${silentFor / 1000} s" else "${silentFor / 1000} s ago",
                )

                if (lost) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "HUNT MODE",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Lost. Far more often than not that means it is still here wearing " +
                            "a name nothing recognises, rather than gone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.height(10.dp))
                    RotationCountdown(changesAtMs = target.changesAtMs, nowMs = nowMs)
                }

                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { onLocate(target.address) },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (lost) "Hunt for it" else "Locate it") }
                    OutlinedButton(
                        onClick = { onForget(target.address) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Forget") }
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Grey("Back", onBack)
}

/** A step the clock ends rather than a tap: the ring is the whole instruction. */
@Composable
internal fun Timed(
    elapsedMs: Long,
    totalMs: Long,
    label: String,
    caption: String,
    instruction: String,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        CountdownRing(
            elapsedMs = elapsedMs.coerceIn(0L, totalMs),
            totalMs = totalMs,
            label = label,
            caption = caption,
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(instruction, style = MaterialTheme.typography.bodySmall)
}

// ------------------------------------------------------------------------ small parts

@Composable
internal fun ProbeCard(
    title: String,
    detail: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    emphasis: Boolean = false,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .then(
                if (enabled) {
                    Modifier.clickable(onClickLabel = title, onClick = onClick)
                } else {
                    Modifier
                },
            ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                emphasis -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun Choice(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

/** A way onward, in grey, for the thing that is not the main action. */
@Composable
internal fun Grey(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable(onClickLabel = label, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 10.dp),
        )
    }
}

@Composable
internal fun CandidateCard(
    candidate: FollowCandidate,
    nowMs: Long,
    onClick: (() -> Unit)?,
    tuning: FollowTuning = FollowTuning.DEFAULT,
    score: CandidateScore? = null,
    /** True while this device is one the session is following through its address changes. */
    watchedForRotation: Boolean = false,
    action: String? = null,
    onAction: () -> Unit = {},
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    tertiary: String? = null,
    onTertiary: () -> Unit = {},
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.padding(end = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            candidate.label ?: candidate.vendor ?: candidate.address,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (watchedForRotation) {
                            Spacer(Modifier.width(6.dp))
                            RotationTag(candidate.rotations)
                        }
                    }
                    Text(
                        candidate.address,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        candidate.describe(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    candidate.carriedReason(tuning)?.let {
                        Text(
                            "probably yours: $it",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    score?.let {
                        Text(
                            it.headline(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = oddsColor(it.odds),
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    score?.let {
                        Text(
                            "${it.points.roundToInt()}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = oddsColor(it.odds),
                        )
                        Text(
                            it.odds.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = oddsColor(it.odds),
                        )
                    }
                    Text(
                        "${candidate.meanRssi.roundToInt()} dBm",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        "${candidate.heldForMs(nowMs) / 60_000} min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Address type and a steadiness figure, because "AA:BB:.." and a percentage is
            // not enough to recognise anything by. A random address that has never changed
            // is a different thing from a fixed one, and a device that has not moved a
            // decibel in ten minutes is a different thing again.
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    if (candidate.kind != DeviceKind.UNKNOWN) {
                        append(candidate.kind.emoji)
                        append(" ")
                        append(candidate.kind.label)
                        // A guess and a declaration look identical unless one of them says
                        // so, and this one is about to be used to pick a person.
                        if (!candidate.kindCertain) append("?")
                        append(" · ")
                    }
                    append(if (candidate.isRandom) "random address" else "fixed address")
                    append(" · ${candidate.packets} packets")
                    if (candidate.spreadDb < 1_000) {
                        append(" · moved ${candidate.spreadDb.roundToInt()} dB")
                    }
                    if (candidate.rotations > 0) append(" · ${candidate.rotations} rotations")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (action != null || secondary != null || tertiary != null) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    action?.let { TextButton(onClick = onAction) { Text(it) } }
                    secondary?.let { TextButton(onClick = onSecondary) { Text(it) } }
                    tertiary?.let { TextButton(onClick = onTertiary) { Text(it) } }
                }
            }
        }
    }
}

@Composable
internal fun Explainer() {
    Section(
        title = "What this is, said plainly",
        summary = "A demonstration of following somebody by their phone.",
        emphasis = true,
    ) {
        Text(
            "This narrows a street down to the device travelling with somebody, without " +
                "knowing anything about it in advance. It works by elimination: stand " +
                "together and most of what is in range stays in range, which proves " +
                "nothing. Walk half a mile together and almost nothing does - the shops " +
                "fall away, the parked cars fall away, the other passengers get off.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "It is in the app because the alternative is asserting that this is possible " +
                "and asking to be believed. Run it on a phone you own, carried by somebody " +
                "who knows you are running it. Everything it does, anybody with a phone can " +
                "do, which is the part worth taking away.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "The honest output is the short list and its denominator, never a name. Two out " +
                "of two hundred after three miles is a strong claim. Forty out of two " +
                "hundred after standing in a lobby is no claim at all, and the screen shows " +
                "both numbers so you can tell which you have.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The short list as something that can be shown big, or null when it is not worth showing.
 *
 * Refused until the follow has actually run for a while, because a number taken thirty
 * seconds in is "everything in the room" dressed up as "everything following them" - and a
 * card is the one place that mistake travels furthest.
 */
internal fun takeawayFrom(state: FollowState): Takeaway? {
    if (state.followStartedAtMs == null || state.poolSize == 0) return null
    if (state.runningForMs < TAKEAWAY_AFTER_MS) return null

    return Takeaway(
        experiment = "Follow Me",
        headline = "${state.stillIn.size}",
        unit = if (state.stillIn.size == 1) {
            "device stayed with them"
        } else {
            "devices stayed with them"
        },
        denominator = "out of ${state.poolSize} that were in range when it started",
        context = listOfNotNull(
            "${state.runningForMs / 60_000} minutes of following",
            "${state.watching} heard along the way",
            if (state.orbited) "${state.centred} survived the circle" else null,
            if (state.walkedBy) "${state.passed} peaked as you passed" else null,
        ),
        limit = "Co-presence is not identity. These devices were in range whenever you " +
            "were - nothing here says any of them is a phone, or whose, and none of them " +
            "was connected to.",
    )
}

// --------------------------------------------------------------------------- holding

@Composable
internal fun Holding(
    state: FollowState,
    log: List<String>,
    geiger: Boolean,
    onGeiger: (Boolean) -> Unit,
    feedback: Feedback,
    onUnlock: () -> Unit,
    onLocate: () -> Unit,
) {
    val target = state.target
    if (target == null) {
        Text("The target is no longer in the list.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onUnlock, modifier = Modifier.fillMaxWidth()) {
            Text("Back to the list")
        }
        return
    }

    Field("Address now", target.address)
    Field("Evidence", target.describe())
    Field("Signal", "${target.meanRssi.roundToInt()} dBm average")
    Field("With you for", "${target.heldForMs(state.atMs) / 60_000} min")

    // The whole point of having committed to one device: you can stop looking at this.
    Spacer(Modifier.height(12.dp))
    GeigerBar(
        recentRssi = target.recentRssi.takeIf { it > -127 },
        silentForMs = state.silentForMs,
        label = target.label ?: target.vendor,
        running = geiger,
        onToggle = onGeiger,
        feedback = feedback,
    )

    if (state.phase == FollowPhase.LOST) {
        Spacer(Modifier.height(12.dp))
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Out of range for ${state.silentForMs / 1000} seconds",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.height(6.dp))
                RotationCountdown(changesAtMs = state.rotationChangesAtMs, nowMs = state.atMs)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onLocate, modifier = Modifier.fillMaxWidth()) {
                    Text("Try to locate it")
                }
            }
        }
    } else {
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onLocate, modifier = Modifier.fillMaxWidth()) {
            Text("Locate it")
        }
    }

    if (log.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            "What it has done",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        log.take(10).forEach {
            Text(
                "· $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Grey("Let it go", onUnlock)
}

/**
 * The three ways out of the problem that your own pocket is always the best candidate.
 *
 * Offered once, near the start, because every minute it goes unanswered is a minute of
 * evidence built on a list with your earbuds at the top of it.
 */
@Composable
internal fun OwnKitChooser(
    tuning: FollowTuning,
    onSkip: () -> Unit,
    onAutoMute: (Int) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "What about your own devices?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Whatever is in your own pockets survives every test here by construction. " +
                    "It orbits when you orbit and passes when you pass, because it goes " +
                    "where you go. Left in, it sits at the top of the short list forever " +
                    "and the follow looks like it worked.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Find them as I walk") }
            Text(
                "The patient one. After about ${tuning.carriedAfterMs / 60_000} minutes of " +
                    "walking, anything that has barely moved relative to you is offered up " +
                    "as probably yours, and you say whether it is. Costs nothing and can " +
                    "tell the difference between your pocket and theirs, which the quick " +
                    "way cannot.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { onAutoMute(tuning.carriedDbm) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Mute anything above ${tuning.carriedDbm} dBm now") }
            Text(
                "The quick one, working from the first ten seconds. It is a rule about " +
                    "distance rather than ownership, so it will mute them too if you end " +
                    "up walking beside them. The level is editable in settings.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
                Text("Nothing on me, move on")
            }
        }
    }
}

/**
 * The badge on a device the session is following through its address changes.
 *
 * Worth saying out loud on the row rather than only in the headline. Being watched for a
 * rotation is the difference between a device that will be lost the next time its phone
 * changes address and one that will not, and which of those you are looking at changes
 * what a falling count means.
 */
@Composable
private fun RotationTag(rotations: Int) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Text(
            if (rotations > 0) "ROTATION ×$rotations" else "ROTATION WATCH",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun oddsColor(odds: Odds) = when (odds) {
    Odds.RULED_OUT -> MaterialTheme.colorScheme.error
    Odds.STILL_HERE -> MaterialTheme.colorScheme.onSurfaceVariant
    Odds.PROBABLE -> MaterialTheme.colorScheme.tertiary
    Odds.STANDOUT -> MaterialTheme.colorScheme.primary
}
