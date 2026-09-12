package com.sigeye.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Stops the screen sleeping while something is recording.
 *
 * Every long-running experiment here has the same problem: the radio is held by the
 * screen, so when the display sleeps the measurement quietly stops and the user comes back
 * to a recording that ended twenty minutes after they walked away. Leaving the screen on
 * is the honest fix for that until these move into the background service properly.
 *
 * Scoped to the composition, so it releases the moment recording stops or the screen is
 * left - a flag like this left set is exactly how an app earns a reputation for eating
 * batteries.
 */
@Composable
fun KeepScreenOn(active: Boolean = true) {
    val view = LocalView.current
    DisposableEffect(active, view) {
        if (active) view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
