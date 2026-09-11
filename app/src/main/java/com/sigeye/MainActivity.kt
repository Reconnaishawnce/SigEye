package com.sigeye

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.sigeye.core.Experiments
import com.sigeye.experiments.absorption.AbsorptionScreen
import com.sigeye.experiments.beacons.BeaconScreen
import com.sigeye.experiments.cells.CellScreen
import com.sigeye.experiments.inspector.InspectorScreen
import com.sigeye.experiments.locate.LocateScreen
import com.sigeye.experiments.microwave.MicrowaveScreen
import com.sigeye.experiments.motion.MotionScreen
import com.sigeye.experiments.population.PopulationMode
import com.sigeye.experiments.population.PopulationScreen
import com.sigeye.experiments.radar.RadarScreen
import com.sigeye.experiments.trainspotter.TrainSpotterScreen
import com.sigeye.experiments.watchlist.WatchlistScreen
import com.sigeye.home.HomeScreen
import com.sigeye.ui.SigEyeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SigEyeTheme { SigEyeApp() } }
    }
}

/**
 * A handful of destinations is not worth a navigation library.
 *
 * `null` is the home screen; any other value is an experiment id, optionally with an
 * argument after a colon - which only Locate needs, and only ever an address.
 */
private const val LOCATE_PREFIX = "locate:"

@Composable
private fun SigEyeApp() {
    var route by rememberSaveable { mutableStateOf<String?>(null) }
    // Where Locate was opened from, so Back returns there rather than to the home screen.
    var locateOrigin by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = route != null) {
        route = if (route?.startsWith(LOCATE_PREFIX) == true) locateOrigin else null
    }

    Scaffold { padding ->
        val inset = Modifier.padding(padding)
        val current = route

        if (current != null && current.startsWith(LOCATE_PREFIX)) {
            LocateScreen(
                address = current.removePrefix(LOCATE_PREFIX),
                onBack = { route = locateOrigin },
                modifier = inset,
            )
            return@Scaffold
        }

        val openLocate: (String) -> Unit = { address ->
            locateOrigin = route
            route = LOCATE_PREFIX + address
        }

        when (current) {
            null -> HomeScreen(onOpen = { route = it }, modifier = inset)

            Experiments.TRAIN_SPOTTER ->
                TrainSpotterScreen(onBack = { route = null }, modifier = inset)

            Experiments.INSPECTOR -> InspectorScreen(
                onBack = { route = null },
                onLocate = openLocate,
                modifier = inset,
            )

            Experiments.WATCHLIST ->
                WatchlistScreen(onBack = { route = null }, modifier = inset)

            Experiments.BEACONS ->
                BeaconScreen(onBack = { route = null }, modifier = inset)

            Experiments.RADAR -> RadarScreen(
                onBack = { route = null },
                onLocate = openLocate,
                modifier = inset,
            )

            Experiments.ABSORPTION ->
                AbsorptionScreen(onBack = { route = null }, modifier = inset)

            Experiments.MICROWAVE ->
                MicrowaveScreen(onBack = { route = null }, modifier = inset)

            Experiments.MOTION ->
                MotionScreen(onBack = { route = null }, modifier = inset)

            Experiments.CELLS ->
                CellScreen(onBack = { route = null }, modifier = inset)

            Experiments.DWELL -> PopulationScreen(
                mode = PopulationMode.DWELL,
                onBack = { route = null },
                modifier = inset,
            )

            Experiments.CROWD -> PopulationScreen(
                mode = PopulationMode.CROWD,
                onBack = { route = null },
                modifier = inset,
            )

            // An unknown id can only come from a stale saved state after an update.
            else -> HomeScreen(onOpen = { route = it }, modifier = inset)
        }
    }
}
