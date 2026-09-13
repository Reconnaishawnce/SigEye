package com.sigeye.experiments.follow

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sigeye.core.analysis.identity.FollowTuning
import kotlin.math.roundToInt

/**
 * The numbers a follow runs on, with what each one costs written next to it.
 *
 * Every slider here is a trade rather than a preference, so every one says which way it
 * goes wrong in both directions. A settings screen that lists values without saying what
 * moving them does is a settings screen people move things in at random.
 */
@Composable
fun FollowSettingsDialog(
    initial: FollowTuning,
    onDismiss: () -> Unit,
    onSave: (FollowTuning) -> Unit,
) {
    var dropSeconds by remember { mutableFloatStateOf(initial.dropAfterMs / 1000f) }
    var baselineSeconds by remember { mutableFloatStateOf(initial.baselineMs / 1000f) }
    var circleSeconds by remember { mutableFloatStateOf(initial.circleMs / 1000f) }
    var shortlist by remember { mutableFloatStateOf(initial.shortlistMax.toFloat()) }
    var listable by remember { mutableFloatStateOf(initial.listableAt.toFloat()) }
    var rebaselineMinutes by remember { mutableFloatStateOf(initial.rebaselineAfterMs / 60_000f) }
    var minPackets by remember { mutableFloatStateOf(initial.minPackets.toFloat()) }
    var carriedDbm by remember { mutableFloatStateOf(initial.carriedDbm.toFloat()) }
    var carriedSpread by remember { mutableFloatStateOf(initial.carriedSpreadDb.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How this follow behaves") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Setting(
                    label = "Drop-off",
                    value = dropSeconds,
                    range = 20f..180f,
                    steps = 31,
                    display = "${dropSeconds.roundToInt()} s",
                    detail = "How long a device may go unheard before it counts as having " +
                        "dropped out. This is the whole elimination. Shorter and a phone in " +
                        "a back pocket behind a body is lost along with the street; longer " +
                        "and half the street is still on the list at the corner.",
                ) { dropSeconds = it }

                Setting(
                    label = "Baseline",
                    value = baselineSeconds,
                    range = 15f..120f,
                    steps = 20,
                    display = "${baselineSeconds.roundToInt()} s",
                    detail = "How long the opening census runs. Longer catches devices that " +
                        "advertise rarely, which means a bigger and more honest denominator " +
                        "and a longer wait before anything starts.",
                ) { baselineSeconds = it }

                Setting(
                    label = "Circle",
                    value = circleSeconds,
                    range = 30f..180f,
                    steps = 29,
                    display = "${circleSeconds.roundToInt()} s",
                    detail = "How long one lap around them should take. Match it to how big " +
                        "a circle you can actually walk - the test assumes you go all the " +
                        "way round in this time.",
                ) { circleSeconds = it }

                Setting(
                    label = "Short list at",
                    value = shortlist,
                    range = 2f..10f,
                    steps = 7,
                    display = shortlist.roundToInt().toString(),
                    detail = "At or below this many, every remaining device gets watched " +
                        "individually for an address change. Higher means more devices " +
                        "watched and more chances to follow a rotation, and more noise.",
                ) { shortlist = it }

                Setting(
                    label = "Worth saving at",
                    value = listable,
                    range = 5f..40f,
                    steps = 34,
                    display = listable.roundToInt().toString(),
                    detail = "At or below this many, the list is short enough to be worth " +
                        "keeping - the point at which you can look down it and recognise " +
                        "something.",
                ) { listable = it }

                Setting(
                    label = "Suggest starting again after",
                    value = rebaselineMinutes,
                    range = 2f..20f,
                    steps = 17,
                    display = "${rebaselineMinutes.roundToInt()} min",
                    detail = "How long to let a follow flounder before offering a fresh " +
                        "baseline. The usual reason a follow stops narrowing is the target " +
                        "changing address, and the fix is starting the pool again.",
                ) { rebaselineMinutes = it }

                Setting(
                    label = "Packets before it counts",
                    value = minPackets,
                    range = 1f..10f,
                    steps = 8,
                    display = minPackets.roundToInt().toString(),
                    detail = "How many advertisements a device has to send before it is a " +
                        "candidate at all. One packet from a passing car is not a device in " +
                        "the room, and counting it inflates the denominator.",
                ) { minPackets = it }

                Setting(
                    label = "Probably yours above",
                    value = carriedDbm,
                    range = -75f..-40f,
                    steps = 34,
                    display = carriedDbm.roundToInt().toString() + " dBm",
                    detail = "A device this loud for almost the whole follow is in your own " +
                        "pocket rather than theirs, and gets called out rather than left at " +
                        "the top of the list. Move it down if your own kit is being missed, " +
                        "and up if it is flagging theirs - which is the worse mistake of " +
                        "the two.",
                ) { carriedDbm = it }

                Setting(
                    label = "Yours if it moves less than",
                    value = carriedSpread,
                    range = 4f..20f,
                    steps = 15,
                    display = carriedSpread.roundToInt().toString() + " dB",
                    detail = "Something in your own bag keeps a fixed distance from this " +
                        "phone, so its level barely wanders over a whole walk. Somebody " +
                        "walking beside you cannot manage that. Lower is stricter; if your " +
                        "own kit keeps surviving a long walk, raise it.",
                ) { carriedSpread = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        FollowTuning(
                            baselineMs = (baselineSeconds.roundToInt() * 1000L),
                            dropAfterMs = (dropSeconds.roundToInt() * 1000L),
                            circleMs = (circleSeconds.roundToInt() * 1000L),
                            shortlistMax = shortlist.roundToInt(),
                            listableAt = listable.roundToInt(),
                            rebaselineAfterMs = (rebaselineMinutes.roundToInt() * 60_000L),
                            minPackets = minPackets.roundToInt(),
                            lostAfterMs = initial.lostAfterMs,
                            carriedDbm = carriedDbm.roundToInt(),
                            carriedSpreadDb = carriedSpread.toDouble(),
                            carriedAfterMs = initial.carriedAfterMs,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(FollowTuning.DEFAULT) }) { Text("Defaults") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Setting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    detail: String,
    onChange: (Float) -> Unit,
) {
    Spacer(Modifier.height(10.dp))
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(display, style = MaterialTheme.typography.labelLarge)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    Text(
        detail,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
