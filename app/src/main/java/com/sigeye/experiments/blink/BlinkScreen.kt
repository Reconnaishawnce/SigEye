package com.sigeye.experiments.blink

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.Experiments
import com.sigeye.core.Permissions
import com.sigeye.core.analysis.covert.Blink
import com.sigeye.core.analysis.covert.Decoded
import com.sigeye.core.ble.BleScanHub
import com.sigeye.core.ble.BlinkSupport
import com.sigeye.core.ble.Blinker
import com.sigeye.ui.ExperimentHeader
import com.sigeye.ui.Field
import com.sigeye.ui.KeepScreenOn
import com.sigeye.ui.NextStep
import com.sigeye.ui.NextStepCard
import com.sigeye.ui.PermissionGate
import com.sigeye.ui.PermissionReason
import com.sigeye.ui.Section
import com.sigeye.ui.StepTone
import java.util.Locale
import kotlinx.coroutines.delay

private const val HUB_TAG = "blink"

/** Which half of the experiment is showing. */
private enum class Side(val label: String) {
    SEND("Send"),
    LISTEN("Listen"),
}

@Composable
fun BlinkScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))
        ExperimentHeader(Experiments.BLINK, onBack)
        Spacer(Modifier.height(16.dp))

        PermissionGate(
            request = Permissions.required(),
            blocking = Permissions.blocking(),
            reasons = listOf(
                PermissionReason(
                    "Nearby devices",
                    "To transmit on one phone and hear it on the other. Nothing connects.",
                ),
                PermissionReason(
                    "Location",
                    "Android returns no Bluetooth scan results without it.",
                ),
            ),
            footnote = "This one transmits, and it is the only experiment here that does. " +
                "It broadcasts an anonymous marker with no data in it and stops the moment " +
                "you leave the screen.",
        ) {
            Live()
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Live() {
    val context = LocalContext.current
    val blinker = remember { Blinker(context) }
    val sending by blinker.state.collectAsStateWithLifecycle()

    var side by remember { mutableStateOf(Side.SEND) }
    var message by remember { mutableStateOf("HELLO") }
    var slotMs by remember { mutableStateOf(500f) }
    var go by remember { mutableStateOf(false) }

    KeepScreenOn(true)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Side.entries.forEach { option ->
            FilterChip(
                selected = side == option,
                onClick = { side = option },
                label = { Text(option.label) },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    when (side) {
        Side.SEND -> Send(
            blinker = blinker,
            sending = sending,
            message = message,
            onMessage = { message = it },
            slotMs = slotMs,
            onSlotMs = { slotMs = it },
            go = go,
            onGo = { go = it },
        )

        Side.LISTEN -> Listen(slotMs.toLong())
    }

    Spacer(Modifier.height(20.dp))
    Section(
        title = "There is no data in any of this",
        summary = "Every packet is identical. The message is in the gaps.",
        emphasis = true,
    ) {
        Text(
            "A Bluetooth advertisement has a payload, so putting the letters in it would be " +
                "an ordinary data transfer with extra steps. Nothing here touches it. Every " +
                "packet this sends is byte for byte the same, and the message is carried " +
                "entirely in whether the radio is transmitting during each half second.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Which means somebody capturing the traffic sees one device saying the same " +
                "nothing over and over. The content is not hidden or encrypted - there is " +
                "no content. It is a lamp and a shutter, at about the speed a person sends " +
                "morse by hand.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "The one thing in the packet is a marker saying which device to watch, and that " +
                "is a necessary cheat worth naming. The address rotates partway through a " +
                "long message, so the sender cannot be recognized by address. Identity in " +
                "the packet, meaning in the gaps.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(12.dp))
    Section(
        title = "Why this is in a measurement app",
        summary = "It is the same channel the rest of SigEye reads by accident.",
    ) {
        Text(
            "Follow Me narrows a street to one phone using nothing but which devices are " +
                "present over time. Defeating Randomization recognizes a phone through an " +
                "address change partly by how often it talks. Both of those are reading a " +
                "channel nobody designed and nobody can turn off, and both are easier to " +
                "believe once you have watched somebody deliberately send a sentence down it.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Encryption does not touch any of this. A protocol can hide what a packet says " +
                "and cannot hide that a packet happened.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }

    Spacer(Modifier.height(12.dp))
    Section(
        title = "How a character is spelled",
        summary = "Six bits, a parity bit, and a preamble to find the boundaries.",
    ) {
        Field("Alphabet", "${Blink.sendable().length} characters, so six slots each")
        Field("Parity", "One more slot, which catches any single flipped bit")
        Field("Preamble", "${Blink.PREAMBLE.size} alternating slots, then a marker of three ones")
        Spacer(Modifier.height(8.dp))
        Text(
            "The preamble is there because the two phones share no clock. The receiver knows " +
                "a device is blinking and has no idea where one slot ends and the next " +
                "begins, so it tries a spread of alignments and keeps whichever makes the " +
                "opening alternation look most like an alternation. Three ones in a row " +
                "cannot occur inside an alternating run, which is how it knows the preamble " +
                "has finished.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Several packets go out in every on slot on purpose. Losing one to a collision " +
                "or a missed scan window should not flip a bit, and needing only one arrival " +
                "out of four is what makes that true. Losing a whole slot does flip one, " +
                "which is what the parity bit is for - a character that arrives wrong is " +
                "shown as a block rather than quietly becoming a different letter.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Send(
    blinker: Blinker,
    sending: com.sigeye.core.ble.Blinking,
    message: String,
    onMessage: (String) -> Unit,
    slotMs: Float,
    onSlotMs: (Float) -> Unit,
    go: Boolean,
    onGo: (Boolean) -> Unit,
) {
    val support = remember { blinker.support() }

    if (support != BlinkSupport.Ready) {
        NextStepCard(
            when (support) {
                BlinkSupport.BluetoothOff -> NextStep(
                    problem = "Bluetooth is off",
                    doThis = "Turn it on. Transmitting needs the radio running, not just " +
                        "scanning permission.",
                    tone = StepTone.SHAKY,
                )

                BlinkSupport.CannotAdvertise -> NextStep(
                    problem = "This phone will not transmit Bluetooth",
                    doThis = "Some chipsets receive perfectly well and will not advertise. " +
                        "You can still use the Listen side to decode another phone.",
                )

                else -> NextStep(
                    problem = "No permission to transmit",
                    doThis = "Grant nearby devices access and come back.",
                    tone = StepTone.SHAKY,
                )
            },
        )
        return
    }

    // The lamp. Everything else on this screen is explanation; this is the experiment.
    Lamp(sending.lit, sending.running)

    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = message,
        onValueChange = { onMessage(Blink.clean(it).take(40)) },
        label = { Text("Message") },
        singleLine = true,
        enabled = !sending.running,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Letters, digits and a little punctuation. Anything else is dropped as you type, " +
            "rather than turned into a space somewhere inside the transmission.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(14.dp))
    Text(
        "Half a slot: ${slotMs.toInt()} ms",
        style = MaterialTheme.typography.labelLarge,
    )
    Slider(
        value = slotMs,
        onValueChange = onSlotMs,
        valueRange = 250f..1200f,
        steps = 18,
        enabled = !sending.running,
    )
    Text(
        String.format(
            Locale.US,
            "About %.0f characters a minute, and this message takes %d seconds. Shorter " +
                "slots are faster and lose synchronization sooner.",
            Blink.charsPerMinute(slotMs.toLong()),
            Blink.durationMs(message, slotMs.toLong()) / 1000,
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(14.dp))
    Button(
        onClick = { onGo(!go) },
        enabled = message.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (sending.running) "Stop" else "Send it")
    }

    // Transmission lives and dies with this composable, so leaving the screen stops the
    // radio. A phone quietly advertising after somebody walked away is not acceptable.
    LaunchedEffect(go, message, slotMs) {
        if (!go) return@LaunchedEffect
        blinker.transmit(message, slotMs.toLong())
        onGo(false)
    }

    if (sending.running) {
        Spacer(Modifier.height(12.dp))
        Field("Slot", "${sending.slot} of ${sending.slots}")
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            drawRect(color = trackColour, size = size)
            drawRect(
                color = litColour,
                size = Size(size.width * sending.progress.coerceIn(0f, 1f), size.height),
            )
        }
    }

    sending.trouble?.let {
        Spacer(Modifier.height(12.dp))
        NextStepCard(NextStep(problem = "The radio refused", doThis = it, tone = StepTone.SHAKY))
    }
}

@Composable
private fun Listen(slotMs: Long) {
    val context = LocalContext.current
    val arrivals = remember { mutableStateListOf<Long>() }
    var decoded by remember { mutableStateOf(Decoded("", 0, 0, false, false)) }
    var lastHeardMs by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) {
        BleScanHub.init(context)
        BleScanHub.acquire(HUB_TAG)
        onDispose { BleScanHub.release(HUB_TAG) }
    }

    LaunchedEffect(Unit) {
        BleScanHub.adverts.collect { advert ->
            if (!Blinker.isBlink(advert)) return@collect
            arrivals.add(advert.atMs)
            lastHeardMs = advert.atMs
            // A transmission is seconds long, not minutes. Holding more than one message
            // worth of arrivals would have the decoder hunting through yesterday.
            while (arrivals.size > ARRIVAL_CAP) arrivals.removeAt(0)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(700)
            val taken = arrivals.toList()
            decoded = Blink.findStart(taken, slotMs)?.let { start ->
                val span = ((taken.max() - start) / slotMs).toInt() + 2
                Blink.decode(Blink.occupancy(taken, start, slotMs, span))
            } ?: Decoded("", 0, 0, sawPreamble = false, complete = false)
        }
    }

    val quiet = lastHeardMs == 0L || System.currentTimeMillis() - lastHeardMs > 5_000

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                decoded.complete -> MaterialTheme.colorScheme.primaryContainer
                decoded.anything -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                when {
                    decoded.anything -> decoded.text
                    decoded.sawPreamble -> "Locked on. Reading..."
                    quiet -> "Nothing blinking yet"
                    else -> "Hearing a lamp, waiting for a preamble"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    decoded.complete -> "Complete message."
                    decoded.anything && decoded.corrupt > 0 ->
                        "${decoded.corrupt} characters arrived with the parity wrong and are " +
                            "shown as blocks. Move the phones closer or use a longer slot."

                    decoded.anything -> "Still arriving."
                    else ->
                        "Set the other phone sending on the same slot length. This decodes " +
                            "nothing until it finds the alternating preamble, which is how it " +
                            "tells a message from an ordinary beacon."
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Field("Packets heard", "${arrivals.size}")
    Field("Slot length", "$slotMs ms, set on the Send tab")

    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = {
            arrivals.clear()
            decoded = Decoded("", 0, 0, sawPreamble = false, complete = false)
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Start listening again") }

    if (arrivals.size >= 2) {
        Spacer(Modifier.height(16.dp))
        Text(
            "What is arriving",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        SlotTrain(arrivals.toList(), slotMs)
    }
}

/** The packets as they land, so the on and off slots are visible before any decoding. */
@Composable
private fun SlotTrain(arrivals: List<Long>, slotMs: Long) {
    val lit = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant

    val start = arrivals.max() - slotMs * SHOWN_SLOTS
    val slots = Blink.occupancy(arrivals, start, slotMs, SHOWN_SLOTS)

    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
        val cell = size.width / SHOWN_SLOTS
        slots.forEachIndexed { index, on ->
            drawRect(
                color = if (on) lit else track,
                topLeft = Offset(index * cell, if (on) 0f else size.height * 0.4f),
                size = Size(cell - 1.dp.toPx(), if (on) size.height else size.height * 0.2f),
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "The last ${SHOWN_SLOTS * slotMs / 1000} seconds. Tall is a slot with packets in " +
            "it, flat is silence. A steady beacon is tall the whole way across, which is " +
            "why it can never be mistaken for a message.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The lamp itself. */
@Composable
private fun Lamp(lit: Boolean, running: Boolean) {
    val glow by animateFloatAsState(
        targetValue = if (lit) 1f else 0f,
        animationSpec = tween(durationMillis = 90),
        label = "lamp",
    )
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.surfaceVariant

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(120.dp)) {
            val centre = Offset(size.width / 2f, size.height / 2f)
            drawCircle(off, size.minDimension / 2.6f, centre)
            if (glow > 0.01f) {
                drawCircle(on.copy(alpha = glow * 0.25f), size.minDimension / 2f, centre)
                drawCircle(on.copy(alpha = glow), size.minDimension / 2.6f, centre)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (running) {
                if (lit) "transmitting" else "silent"
            } else {
                "idle"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Arrivals kept. A message is seconds long, so this is several of them. */
private const val ARRIVAL_CAP = 4000

/** Slots drawn on the live train. */
private const val SHOWN_SLOTS = 60

private val trackColour = androidx.compose.ui.graphics.Color(0x33888888)
private val litColour = androidx.compose.ui.graphics.Color(0xFF4FC3F7)
