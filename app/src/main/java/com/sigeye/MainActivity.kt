package com.sigeye

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.sigeye.core.Experiments
import com.sigeye.core.OuiRegistry
import com.sigeye.core.Recordings
import com.sigeye.experiments.beacons.BeaconScreen
import com.sigeye.experiments.blink.BlinkScreen
import com.sigeye.experiments.cells.CellScreen
import com.sigeye.experiments.congestion.CongestionScreen
import com.sigeye.experiments.convoy.ConvoyScreen
import com.sigeye.experiments.discovery.DiscoveryScreen
import com.sigeye.experiments.explorer.ExplorerScreen
import com.sigeye.experiments.forensics.ForensicsScreen
import com.sigeye.experiments.inspector.InspectorScreen
import com.sigeye.experiments.locate.LocateScreen
import com.sigeye.experiments.motion.MotionScreen
import com.sigeye.experiments.place.PlaceScreen
import com.sigeye.experiments.population.PopulationMode
import com.sigeye.experiments.population.PopulationScreen
import com.sigeye.experiments.radar.RadarScreen
import com.sigeye.experiments.ranging.RangingScreen
import com.sigeye.experiments.bench.Bench
import com.sigeye.experiments.bench.BenchScreen
import com.sigeye.experiments.identity.IdentityScreen
import com.sigeye.experiments.mine.MyDevicesScreen
import com.sigeye.experiments.identity.Mode
import com.sigeye.experiments.settings.SettingsScreen
import com.sigeye.experiments.sky.SkyScreen
import com.sigeye.experiments.speed.SpeedScreen
import com.sigeye.experiments.trainspotter.TrainSpotterScreen
import com.sigeye.experiments.vulnerability.VulnerabilityScreen
import com.sigeye.experiments.watchlist.WatchlistScreen
import com.sigeye.experiments.weather.RadioWeatherScreen
import com.sigeye.experiments.wifi.WifiScreen
import com.sigeye.home.HomeScreen
import com.sigeye.ui.SigEyeTheme
import com.sigeye.core.ble.BleScanHub
import com.sigeye.ui.ReplayBanner
import com.sigeye.ui.TargetBar
import com.sigeye.ui.TargetRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A megabyte off disk, so not on the main thread. Everything that asks for a vendor
        // before it lands falls back to the curated table rather than waiting.
        lifecycleScope.launch(Dispatchers.IO) { OuiRegistry.load(applicationContext) }
        // Starts the follower, which is a passenger on whatever scanning happens rather
        // than a scan of its own - so it has to exist before the first screen opens the
        // radio, or the rotation that screen was there to watch goes unnoticed.
        Recordings.init(applicationContext)
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
/**
 * Routes that carry a device with them.
 *
 * Finding the right phone and then having nothing to do with it was the gap: a follow ends
 * with an address and every other experiment starts by asking you to pick one out of forty.
 * These hand it over, so "open this on the radar" is one tap rather than a hunt through a
 * picker for a name you have to remember.
 */
private const val LOCATE_PREFIX = "locate:"
private const val RADAR_PREFIX = "radar:"
private const val ROTATION_PREFIX = "rotation:"

/** Not an experiment, so it is routed by a reserved id rather than through the registry. */
private const val SETTINGS = "settings"
private val MY_DEVICES = com.sigeye.home.MY_DEVICES_ROUTE

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
        Column(Modifier.padding(padding)) {
            // One mount for the whole app. A pinned target is useful on every screen, and
            // putting this bar in each of them would be twenty-eight places to forget it.
            // Above everything, because a recording that renders identically to live data
            // is the one thing in this app that could quietly mislead somebody.
            ReplayBanner(onStop = { BleScanHub.stopReplay() })
            TargetBar(
                TargetRoutes(
                    onLocate = { address -> open(LOCATE_PREFIX + address) },
                    onRadar = { address -> open(RADAR_PREFIX + address) },
                    onRotation = { address -> open(ROTATION_PREFIX + address) },
                ),
            )
            Screen(stack, open, goBack)
        }
    }
}

/**
 * Whichever experiment is on top of the stack.
 *
 * Its own function so [TargetBar] can sit above every one of them. As one long lambda
 * inside the scaffold, the early returns for the addressed routes returned from the
 * scaffold itself, and anything drawn before them was drawn only on the screens that fell
 * through to the end.
 */
@Composable
private fun Screen(
    stack: SnapshotStateList<String>,
    open: (String) -> Unit,
    goBack: () -> Unit,
) {
    val inset = Modifier
    val current = stack.lastOrNull()

    if (current != null && current.startsWith(LOCATE_PREFIX)) {
        LocateScreen(
            address = current.removePrefix(LOCATE_PREFIX),
            onBack = goBack,
            modifier = inset,
        )
        return
    }

    val openLocate: (String) -> Unit = { address -> open(LOCATE_PREFIX + address) }

    if (current != null && current.startsWith(RADAR_PREFIX)) {
        RadarScreen(
            onBack = goBack,
            onLocate = openLocate,
            initialQuery = current.removePrefix(RADAR_PREFIX),
            modifier = inset,
        )
        return
    }

    if (current != null && current.startsWith(ROTATION_PREFIX)) {
        IdentityScreen(
            onBack = goBack,
            onLocate = openLocate,
            onRadar = { address -> open(RADAR_PREFIX + address) },
            initialMode = Mode.DEFEAT,
            initialAddress = current.removePrefix(ROTATION_PREFIX),
            modifier = inset,
        )
        return
    }

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

        Experiments.BENCH, Experiments.DOPPLER, Experiments.BANDS, Experiments.FADING,
        Experiments.ABSORPTION, Experiments.POLARIZATION, Experiments.FARADAY,
        Experiments.MICROWAVE,
        -> BenchScreen(
            onBack = goBack,
            modifier = inset,
            initialMode = when (current) {
                Experiments.BANDS -> Bench.WALLS
                Experiments.FADING -> Bench.FADING
                Experiments.ABSORPTION -> Bench.BODY
                Experiments.POLARIZATION -> Bench.ANGLE
                Experiments.FARADAY -> Bench.SHIELDING
                Experiments.MICROWAVE -> Bench.MICROWAVE
                else -> Bench.PATH_LOSS
            },
        )

        Experiments.IDENTITY, Experiments.ROTATION, Experiments.ROTATION_LAB,
        Experiments.FOLLOWING, Experiments.FOLLOW,
        -> IdentityScreen(
            onBack = goBack,
            onLocate = openLocate,
            onRadar = { address -> open(RADAR_PREFIX + address) },
            initialMode = when (current) {
                Experiments.ROTATION_LAB -> Mode.LAB
                Experiments.FOLLOW -> Mode.FOLLOW
                Experiments.FOLLOWING -> Mode.WATCH
                else -> Mode.DEFEAT
            },
            modifier = inset,
        )

        Experiments.CONGESTION ->
            CongestionScreen(onBack = goBack, modifier = inset)

        Experiments.VULNERABILITY ->
            VulnerabilityScreen(onBack = goBack, modifier = inset)

        Experiments.RTT ->
            RangingScreen(onBack = goBack, modifier = inset)

        Experiments.SKY ->
            SkyScreen(onBack = goBack, modifier = inset)

        Experiments.WEATHER ->
            RadioWeatherScreen(onBack = goBack, modifier = inset)

        Experiments.BLINK ->
            BlinkScreen(onBack = goBack, modifier = inset)

        SETTINGS ->
            SettingsScreen(onBack = goBack, onMyDevices = { open(MY_DEVICES) }, modifier = inset)

        MY_DEVICES ->
            MyDevicesScreen(onBack = goBack, modifier = inset)

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
