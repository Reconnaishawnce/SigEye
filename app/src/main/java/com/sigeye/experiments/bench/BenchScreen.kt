package com.sigeye.experiments.bench

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sigeye.core.Experiments
import com.sigeye.experiments.absorption.AbsorptionScreen
import com.sigeye.experiments.bands.BandsScreen
import com.sigeye.experiments.doppler.DopplerScreen
import com.sigeye.experiments.fading.FadingScreen
import com.sigeye.experiments.faraday.FaradayScreen
import com.sigeye.experiments.microwave.MicrowaveScreen
import com.sigeye.experiments.polarization.PolarizationScreen

/**
 * Seven measurements that share a method, behind one door.
 *
 * Path loss, wall penetration, multipath fading, body absorption, polarization, the Faraday
 * cage and the microwave were seven cards on the home screen. They are one activity: hold a
 * pose, watch what the level does, keep the number. Somebody arriving wanting to know what
 * their building does to a signal had to already know which of seven names meant that.
 *
 * **Nothing was merged away.** Each mode is the original screen, entire, including its own
 * header, its own walkthrough and its own saved runs. Consolidation here is one card
 * instead of seven on the way in.
 *
 * What is new is the notebook. Every one of these already saved its runs, and every one
 * kept them to itself, so the question people actually have - what do I already know about
 * this building - had no screen at all. It does now.
 */
@Composable
fun BenchScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Which measurement to open on, for a link that means one of them specifically. */
    initialMode: Bench = Bench.PATH_LOSS,
) {
    var mode by rememberSaveable { mutableStateOf(initialMode) }

    val switcher: @Composable () -> Unit = {
        BenchBar(current = mode, onPick = { mode = it })
    }

    when (mode) {
        Bench.PATH_LOSS -> DopplerScreen(onBack, modifier, switcher)
        Bench.WALLS -> BandsScreen(onBack, modifier, switcher)
        Bench.FADING -> FadingScreen(onBack, modifier, switcher)
        Bench.BODY -> AbsorptionScreen(onBack, modifier, switcher)
        Bench.ANGLE -> PolarizationScreen(onBack, modifier, switcher)
        Bench.SHIELDING -> FaradayScreen(onBack, modifier, switcher)
        Bench.MICROWAVE -> MicrowaveScreen(onBack, modifier, switcher)
        Bench.NOTEBOOK -> BenchNotebook(onBack, modifier, switcher)
    }
}

/**
 * One measurement on the bench.
 *
 * [experimentId] is the registry row the mode's screen reads its header and walkthrough
 * from, which is why those rows still exist after the consolidation. The notebook has none,
 * because it is not an experiment - it is the drawer the experiments put their results in.
 */
enum class Bench(val label: String, val experimentId: String?, val forWhat: String) {
    PATH_LOSS(
        "Path loss",
        Experiments.DOPPLER,
        "Walk away counting steps and measure the exponent every distance in this app " +
            "otherwise assumes.",
    ),
    WALLS(
        "Walls",
        Experiments.BANDS,
        "How many more decibels your building takes out of 5 GHz than out of 2.4.",
    ),
    FADING(
        "Fading",
        Experiments.FADING,
        "Stand perfectly still and watch the signal move anyway. That is multipath.",
    ),
    BODY(
        "Body",
        Experiments.ABSORPTION,
        "Turn in a circle and find the shadow you cast in your own radio.",
    ),
    ANGLE(
        "Angle",
        Experiments.POLARIZATION,
        "Turn the phone over and watch the signal die as the antennas stop lining up.",
    ),
    SHIELDING(
        "Shielding",
        Experiments.FARADAY,
        "Settle the argument about whether a crisp packet blocks anything.",
    ),
    MICROWAVE(
        "Microwave",
        Experiments.MICROWAVE,
        "Watch an oven flatten the band your Wi-Fi is on, live.",
    ),
    NOTEBOOK(
        "Notebook",
        null,
        "Every run you have saved, from all of these, in one place.",
    ),
    ;

    companion object {
        /** The measurements, without the drawer they file into. */
        val MEASUREMENTS: List<Bench> get() = entries.filter { it.experimentId != null }
    }
}

/**
 * The switcher, plus a line saying what the current measurement is for.
 *
 * The line does more work here than it looks. Seven chips reading Walls, Fading, Body and
 * Angle mean nothing to somebody who has not used them, and on the home screen those seven
 * titles were doing that explaining for free.
 */
@Composable
internal fun BenchBar(current: Bench, onPick: (Bench) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Bench.entries.forEach { mode ->
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
