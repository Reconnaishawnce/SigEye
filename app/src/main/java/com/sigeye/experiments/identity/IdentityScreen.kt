package com.sigeye.experiments.identity

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.CurrentTarget
import com.sigeye.core.Experiments
import com.sigeye.experiments.follow.FollowScreen
import com.sigeye.experiments.following.FollowingScreen
import com.sigeye.experiments.rotation.RotationScreen
import com.sigeye.experiments.rotationlab.RotationLabScreen

/**
 * Four ways of doing the same thing, behind one door.
 *
 * Defeating Randomization, Rotation Lab, Follow Me and Persistent Tracking were four
 * separate experiments, and somebody arriving at the home screen had to guess which of the
 * four was the one that answered their question. They are not four ideas. They are one
 * idea - that a device gives away more than the address it rotates - approached from four
 * distances: one device in detail, a whole room in aggregate, a person on foot, and a name
 * that has to survive.
 *
 * **Nothing was merged away.** Each mode is the original screen, entire, including its own
 * header and its own walkthrough read from the registry by id. Consolidation here means one
 * card instead of four on the way in, not four explanations collapsed into one. Somebody
 * who wants to sit and learn how rotation works still gets exactly the screen that teaches
 * it, animations and all.
 *
 * What is new is that the modes now know about each other. A device pinned in one is
 * pinned in all of them, so finding something interesting in Lab and taking it into Defeat
 * is two taps instead of writing an address down.
 */
@Composable
fun IdentityScreen(
    onBack: () -> Unit,
    onLocate: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRadar: (String) -> Unit = {},
    /** Which mode to open on, for a link that means one of them specifically. */
    initialMode: Mode = Mode.DEFEAT,
    /** A device to start on, for arriving from somewhere that already found one. */
    initialAddress: String = "",
) {
    var mode by rememberSaveable { mutableStateOf(initialMode) }

    val context = LocalContext.current
    val target = remember(context) { CurrentTarget.get(context) }
    val pinned by target.pinned.collectAsStateWithLifecycle()

    /**
     * What Defeat should open on.
     *
     * An explicit address from a link wins, because somebody following that link asked for
     * that device. Otherwise whatever is pinned, which is how a find in Lab arrives here
     * without being typed out.
     */
    val startOn = initialAddress.ifBlank { pinned?.address.orEmpty() }

    val switcher: @Composable () -> Unit = {
        ModeBar(current = mode, onPick = { mode = it })
    }

    when (mode) {
        Mode.DEFEAT -> RotationScreen(
            onBack = onBack,
            modifier = modifier,
            initialAddress = startOn,
            modes = switcher,
        )

        Mode.LAB -> RotationLabScreen(
            onBack = onBack,
            modifier = modifier,
            modes = switcher,
        )

        Mode.FOLLOW -> FollowScreen(
            onBack = onBack,
            onLocate = onLocate,
            modifier = modifier,
            onRadar = onRadar,
            // Staying inside the suite rather than pushing another destination. The mode
            // already exists on this screen and it opens on whatever is pinned.
            onRotation = { address ->
                target.pin(address, label = null, vendor = null, source = "Follow Me")
                mode = Mode.DEFEAT
            },
            modes = switcher,
        )

        Mode.WATCH -> FollowingScreen(
            onBack = onBack,
            modifier = modifier,
            modes = switcher,
        )
    }
}

/**
 * One mode of [IdentityScreen].
 *
 * [experimentId] is the registry row the mode's screen reads its header and walkthrough
 * from, which is why those rows still exist after the consolidation.
 */
enum class Mode(val label: String, val experimentId: String, val forWhat: String) {
    DEFEAT(
        "Defeat",
        Experiments.ROTATION,
        "One phone, in detail. Learn its fingerprint, then watch what survives a rotation.",
    ),
    LAB(
        "Lab",
        Experiments.ROTATION_LAB,
        "The whole room, by maker. Find out how often each fleet actually rotates.",
    ),
    FOLLOW(
        "Follow",
        Experiments.FOLLOW,
        "On foot. Narrow a street down to the one device walking with you.",
    ),
    WATCH(
        "Watch",
        Experiments.FOLLOWING,
        "Quietly. Keep a name attached to a device across its address changes.",
    ),
    ;
}

/**
 * The mode switcher, plus a line saying what the current one is for.
 *
 * The line matters more than it looks. Four chips called Defeat, Lab, Follow and Watch are
 * meaningless to somebody who has not used them, and the whole reason these were four
 * separate cards was that their names explained themselves on the home screen. That
 * explanation has to survive the move.
 */
@Composable
private fun ModeBar(current: Mode, onPick: (Mode) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Mode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == current,
                    onClick = { if (mode != current) onPick(mode) },
                    label = { Text(mode.label) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            current.forWhat,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
