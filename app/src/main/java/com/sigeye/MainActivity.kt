package com.sigeye

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import com.sigeye.core.Experiments
import com.sigeye.experiments.beacons.BeaconScreen
import com.sigeye.experiments.inspector.InspectorScreen
import com.sigeye.experiments.population.PopulationMode
import com.sigeye.experiments.population.PopulationScreen
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
 * Two destinations is not worth a navigation library. `null` is the home screen, any
 * other value is an experiment id from [Experiments].
 */
@Composable
private fun SigEyeApp() {
    var openExperiment by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = openExperiment != null) { openExperiment = null }

    Scaffold { padding ->
        val inset = Modifier.padding(padding)
        when (openExperiment) {
            null -> HomeScreen(onOpen = { openExperiment = it }, modifier = inset)
            Experiments.TRAIN_SPOTTER ->
                TrainSpotterScreen(onBack = { openExperiment = null }, modifier = inset)
            Experiments.INSPECTOR ->
                InspectorScreen(onBack = { openExperiment = null }, modifier = inset)
            Experiments.WATCHLIST ->
                WatchlistScreen(onBack = { openExperiment = null }, modifier = inset)
            Experiments.BEACONS ->
                BeaconScreen(onBack = { openExperiment = null }, modifier = inset)
            Experiments.DWELL -> PopulationScreen(
                mode = PopulationMode.DWELL,
                onBack = { openExperiment = null },
                modifier = inset,
            )
            Experiments.CROWD -> PopulationScreen(
                mode = PopulationMode.CROWD,
                onBack = { openExperiment = null },
                modifier = inset,
            )
            // An unknown id can only come from a stale saved state after an update.
            else -> HomeScreen(onOpen = { openExperiment = it }, modifier = inset)
        }
    }
}
