package com.sigeye.ui

import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.sigeye.core.Takeaway
import com.sigeye.core.TakeawayImage
import java.util.Locale

/**
 * The way into presentation mode, which is one grey line until there is something to show.
 *
 * Every experiment here ends as a paragraph in the middle of a scrolling page, surrounded by
 * diagnostics and permission notes and explanatory text. That is right for working out what
 * a reading means and wrong for the other thing this app is for, which is showing somebody
 * else how exposed they are. A result worth filming needs to be the only thing on screen.
 *
 * Null means the experiment has not produced a takeaway yet, and the control is simply absent
 * rather than present and disabled. A greyed-out button on a screen that has not been run is
 * a puzzle nobody needs.
 */
@Composable
fun TakeawayButton(takeaway: Takeaway?, modifier: Modifier = Modifier) {
    if (takeaway == null) return
    var showing by remember { mutableStateOf(false) }

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = { showing = true }) { Text("Show this big") }
    }

    if (showing) {
        PresentationCard(takeaway, onClose = { showing = false })
    }
}

/**
 * One takeaway, alone on a black screen, in type you can read from across a room.
 *
 * Black and a fixed palette rather than the app's theme, for the same reason the shared
 * image is: this gets recorded and the recording gets watched somewhere else, and a card
 * that came out pale because of how somebody had their phone set up is a card nobody reads.
 *
 * The denominator and the limit are on the card and not behind anything. The whole design
 * constraint of [Takeaway] is that a number cannot be shown big without them, and putting
 * them behind a tap here would give that away at the last step.
 *
 * The number counts up when it is a number. On a screen recording, three appearing where
 * two hundred and fourteen used to be reads as a cut; three counting down from two hundred
 * and fourteen reads as an elimination, which is what actually happened.
 */
@Composable
fun PresentationCard(takeaway: Takeaway, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(GROUND)
                .padding(horizontal = 28.dp, vertical = 32.dp),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    takeaway.experiment.uppercase(Locale.US),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = ACCENT,
                )

                Spacer(Modifier.height(28.dp))
                Headline(takeaway.headline)

                Spacer(Modifier.height(6.dp))
                Text(
                    takeaway.unit,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = INK,
                )

                Spacer(Modifier.height(12.dp))
                Text(takeaway.denominator, fontSize = 20.sp, color = ACCENT)

                takeaway.conditions().takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 16.sp, color = MUTED)
                }

                Spacer(Modifier.height(28.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(PANEL, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    Column {
                        Text(
                            "WHAT THIS DOES NOT SHOW",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MUTED,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(takeaway.limit, fontSize = 15.sp, color = INK)
                    }
                }

                Spacer(Modifier.height(20.dp))
                Text("SigEye · ${takeaway.stamp()}", fontSize = 12.sp, color = MUTED)

                Spacer(Modifier.height(24.dp))
                ShareRow(takeaway, onClose)
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun Headline(headline: String) {
    val number = headline.toIntOrNull()
    if (number == null) {
        Text(
            headline,
            fontSize = 72.sp,
            fontWeight = FontWeight.Bold,
            color = INK,
        )
        return
    }
    // Counted up from nothing rather than from whatever was on screen before, because the
    // card is opened after the measurement and there is no honest earlier value to run from.
    val shown by animateIntAsState(
        targetValue = number,
        animationSpec = tween(durationMillis = 700),
        label = "takeaway",
    )
    Text(
        shown.toString(),
        fontSize = 96.sp,
        fontWeight = FontWeight.Bold,
        color = INK,
    )
}

@Composable
private fun ShareRow(takeaway: Takeaway, onClose: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { TakeawayImage.share(context, takeaway) },
            colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = GROUND),
            modifier = Modifier.weight(1f),
        ) { Text("Share the card") }

        TextButton(onClick = onClose, modifier = Modifier.weight(1f)) {
            Text("Done", color = MUTED)
        }
    }
}

// The same five colours the shared image uses, so the card somebody records and the card
// somebody posts are recognisably the same object.
private val INK = Color(0xFFF2F5F4)
private val GROUND = Color(0xFF0E1116)
private val ACCENT = Color(0xFF7FE3A3)
private val MUTED = Color(0xFF8C9AA3)
private val PANEL = Color(0xFF1A2028)
