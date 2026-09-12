package com.sigeye

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.sigeye.core.Experiments
import com.sigeye.core.OuiRegistry
import com.sigeye.experiments.absorption.AbsorptionScreen
import com.sigeye.experiments.beacons.BeaconScreen
import com.sigeye.experiments.cells.CellScreen
import com.sigeye.experiments.convoy.ConvoyScreen
import com.sigeye.experiments.discovery.DiscoveryScreen
import com.sigeye.experiments.bands.BandsScreen
import com.sigeye.experiments.congestion.CongestionScreen
import com.sigeye.experiments.doppler.DopplerScreen
import com.sigeye.experiments.polarisation.PolarisationScreen
import com.sigeye.experiments.explorer.ExplorerScreen
import com.sigeye.experiments.fading.FadingScreen
import com.sigeye.experiments.faraday.FaradayScreen
import com.sigeye.experiments.forensics.ForensicsScreen
import com.sigeye.experiments.inspector.InspectorScreen
import com.sigeye.experiments.locate.LocateScreen
import com.sigeye.experiments.microwave.MicrowaveScreen
import com.sigeye.experiments.motion.MotionScreen
import com.sigeye.experiments.place.PlaceScreen
import com.sigeye.experiments.population.PopulationMode
import com.sigeye.experiments.population.PopulationScreen
import com.sigeye.experiments.radar.RadarScreen
import com.sigeye.experiments.rotation.RotationScreen
import com.sigeye.experiments.speed.SpeedScreen
import com.sigeye.experiments.trainspotter.TrainSpotterScreen
import com.sigeye.experiments.watchlist.WatchlistScreen
import com.sigeye.experiments.wifi.WifiScreen
import com.sigeye.home.HomeScreen
import com.sigeye.ui.SigEyeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A megabyte off disk, so not on the main thread. Everything that asks for a vendor
        // before it lands falls back to the curated table rather than waiting.
        lifecycleScope.launch(Dispatchers.IO) { OuiRegistry.load(applicationContext) }
        setContent { SigEyeTheme { SigEyeApp() } }
    }
}

/**
 * A handful of destinations is not worth a navigation library, but they do need a stack.
 *
 * There was a single current route and a one-off field remembering where Locate had been
 * opened from. Everything else hard-coded Back to the home screen, so any screen reached
 * from another screen threw you all the way out rather than back one step. A list of
 * routes costs nothing and removes the special case with it.
 *
 * A route is an experiment id, optionally with an argument after a colon - which only
 * Locate needs, and only ever an address.
 */
private const val LOCATE_PREFIX = "locate:"

@Composable
private fun SigEyeApp() {
    val stack = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf<String>() }

    val goBack: () -> Unit = { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    val open: (String) -> Unit = { stack.add(it) }

    BackHandler(enabled = stack.isNotEmpty(), onBack = goBack)

    Scaffold { padding ->
        val inset = Modifier.padding(padding)
        val current = stack.lastOrNull()

        if (current != null && current.startsWith(LOCATE_PREFIX)) {
            LocateScreen(
                address = current.removePrefix(LOCATE_PREFIX),
                onBack = goBack,
                modifier = inset,
            )
            return@Scaffold
        }

        val openLocate: (String) -> Unit = { address -> open(LOCATE_PREFIX + address) }

        when (current) {
            null -> HomeScreen(onOpen = open, modifier = inset)

            Experiments.TRAIN_SPOTTER ->
                TrainSpotterScreen(onBack = goBack, modifier = inset)

            Experiments.INSPECTOR -> InspectorScreen(
                onBack = goBack,
                onLocate = openLocate,
                modifier = inset,
            )

            Experiments.WATCHLIST ->
                WatchlistScreen(onBack = goBack, modifier = inset)

            Experiments.BEACONS ->
                BeaconScreen(onBack = goBack, modifier = inset)

            Experiments.RADAR -> RadarScreen(
                onBack = goBack,
                onLocate = openLocate,
                modifier = inset,
            )

            Experiments.ABSORPTION ->
                AbsorptionScreen(onBack = goBack, modifier = inset)

            Experiments.MICROWAVE ->
                MicrowaveScreen(onBack = goBack, modifier = inset)

            Experiments.FARADAY ->
                FaradayScreen(onBack = goBack, modifier = inset)

            Experiments.FADING ->
                FadingScreen(onBack = goBack, modifier = inset)

            Experiments.DISCOVERY ->
                DiscoveryScreen(onBack = goBack, modifier = inset)

            Experiments.EXPLORER ->
                ExplorerScreen(onBack = goBack, modifier = inset)

            Experiments.WIFI ->
                WifiScreen(onBack = goBack, modifier = inset)

            Experiments.PLACE ->
                PlaceScreen(onBack = goBack, modifier = inset)

            Experiments.FORENSICS ->
                ForensicsScreen(onBack = goBack, modifier = inset)

            Experiments.CONVOY ->
                ConvoyScreen(onBack = goBack, modifier = inset)

            Experiments.ROTATION ->
                RotationScreen(onBack = goBack, modifier = inset)

            Experiments.DOPPLER ->
                DopplerScreen(onBack = goBack, modifier = inset)

            Experiments.POLARISATION ->
                PolarisationScreen(onBack = goBack, modifier = inset)

            Experiments.CONGESTION ->
                CongestionScreen(onBack = goBack, modifier = inset)

            Experiments.BANDS ->
                BandsScreen(onBack = goBack, modifier = inset)

            Experiments.MOTION ->
                MotionScreen(onBack = goBack, modifier = inset)

            Experiments.SPEED ->
                SpeedScreen(onBack = goBack, modifier = inset)

            Experiments.CELLS ->
                CellScreen(onBack = goBack, modifier = inset)

            Experiments.DWELL -> PopulationScreen(
                mode = PopulationMode.DWELL,
                onBack = goBack,
                modifier = inset,
            )

            Experiments.CROWD -> PopulationScreen(
                mode = PopulationMode.CROWD,
                onBack = goBack,
                modifier = inset,
            )

            // An unknown id can only come from a stale saved state after an update.
            else -> HomeScreen(onOpen = open, modifier = inset)
        }
    }
}
