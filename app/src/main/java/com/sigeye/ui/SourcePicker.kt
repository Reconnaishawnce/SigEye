package com.sigeye.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sigeye.core.DeviceBook
import com.sigeye.core.DeviceNote
import com.sigeye.core.DeviceRanking
import com.sigeye.core.SettingsStore
import com.sigeye.core.Vendors
import com.sigeye.core.ble.BleScanHub
import com.sigeye.experiments.watchlist.MatchKind
import com.sigeye.experiments.watchlist.WatchStore
import kotlinx.coroutines.delay
import java.util.Locale

/** One device a user could choose to measure against. */
data class Source(
    val address: String,
    val name: String?,
    val vendor: String?,
    val rssi: Int,
    val isRandom: Boolean,
    val sightings: Int,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
) {
    /**
     * Packets per second since first heard.
     *
     * The single most useful thing to know before choosing. A sweep needs several readings
     * in each of two dozen sectors; a fading measurement needs a reading every fraction of
     * a second. A sensor that speaks once a minute is a perfectly good device and a useless
     * subject, and its signal strength tells you nothing about that.
     */
    val rate: Double
        get() = sightings * 1000.0 / (lastSeenMs - firstSeenMs).coerceAtLeast(1L)

    /** The row will read as something other than raw hex. */
    val hasIdentity: Boolean get() = !name.isNullOrBlank() || !vendor.isNullOrBlank()

    fun label(nickname: String? = null): String =
        nickname ?: name?.takeIf { it.isNotBlank() } ?: vendor ?: address
}

/** What should be at the top of the list. */
enum class SourceOrder {
    /**
     * Things you can reason about first, then whatever is loud.
     *
     * The right default. A list sorted by signal is complete and useless - the loudest
     * thing in range is almost always anonymous, so the devices you named are buried
     * under a wall of hex.
     */
    RANKED,

    /** Chattiest first, for measurements that need packets rather than proximity. */
    RATE,

    /** Strongest first, for measurements that need a close, solid link. */
    SIGNAL,
}

/**
 * The list of things to measure against, once, for every experiment that needs one.
 *
 * Seven screens had grown their own copy of this: their own candidate type, their own
 * collection loop, their own freshness window and their own sort. They had drifted, which
 * is what copies do - twelve seconds here and twenty there, two sightings here and three
 * there, and the ranking that floats your named devices to the top existing in two of the
 * seven. None of those differences was a decision.
 *
 * What genuinely differs between screens is kept as parameters: what to sort by, whether a
 * randomized address is worth warning about, and how chatty a device has to be before it
 * is worth choosing. Everything else is now one behavior.
 *
 * Assumes the caller already holds the radio - every screen that shows a picker needs the
 * hub for the measurement afterwards anyway, and a component that acquired it separately
 * would leave two claims where there should be one.
 */
/**
 * The list of what is in range, without deciding how it should look.
 *
 * Split out from the picker because three screens need the same devices and a different
 * row. Bluetooth Explorer wants to say which of them will accept a connection, Faraday
 * wants its own pausing, and the motion detector picks several at once with checkboxes
 * rather than one by tapping. Forcing those into one presentation would be worse than the
 * duplication it replaced.
 *
 * What was actually duplicated is here: collecting the stream, keeping one entry per
 * address, deciding how long silence means gone, and sorting. That part has one
 * implementation now, and the rows on top of it can be whatever each screen needs.
 */
@Composable
fun rememberSources(
    order: SourceOrder = SourceOrder.RANKED,
    minSightings: Int = 3,
    limit: Int = 25,
    paused: Boolean = false,
): List<Source> {
    val context = LocalContext.current
    val book = remember { DeviceBook.get(context) }
    val settings = remember { SettingsStore.get(context) }
    val notes by book.notes.collectAsStateWithLifecycle()
    val watchRules by remember { WatchStore.get(context) }.rules.collectAsStateWithLifecycle()
    val watched = remember(watchRules) {
        watchRules.filter { it.kind == MatchKind.ADDRESS }
            .map { it.value.uppercase(Locale.US) }
            .toSet()
    }

    val table = remember { LinkedHashMap<String, Source>() }
    var shown by remember { mutableStateOf<List<Source>>(emptyList()) }

    LaunchedEffect(Unit) {
        BleScanHub.adverts.collect { advert ->
            synchronized(table) {
                val existing = table[advert.address]
                table[advert.address] = Source(
                    address = advert.address,
                    name = advert.name?.takeIf { it.isNotBlank() } ?: existing?.name,
                    vendor = existing?.vendor
                        ?: Vendors.byAddress(advert.address)
                        ?: advert.companyId?.let { Vendors.byCompanyId(it) },
                    rssi = advert.rssi,
                    isRandom = advert.isRandomAddress,
                    sightings = (existing?.sightings ?: 0) + 1,
                    firstSeenMs = existing?.firstSeenMs ?: advert.atMs,
                    lastSeenMs = advert.atMs,
                )
            }
        }
    }

    // Republished on a timer rather than per packet. A busy room delivers hundreds a
    // second, and redrawing a list that often is the difference between a screen that
    // costs battery and one that costs a lot of it.
    LaunchedEffect(paused, notes, watched, order) {
        while (!paused) {
            delay(REFRESH_MS)
            val now = System.currentTimeMillis()
            val freshMs = settings.tuning.freshnessSeconds * 1000L
            shown = synchronized(table) { table.values.toList() }
                .filter { now - it.lastSeenMs < freshMs && it.sightings >= minSightings }
                .sortedWith(comparatorFor(order, notes, watched))
                .take(limit)
        }
    }

    return shown
}

/**
 * The common case: a list you tap once to choose from.
 *
 * Built on [rememberSources], so a screen that needs different rows can take the same
 * devices without taking the same presentation.
 */
@Composable
fun SourcePicker(
    onPick: (Source) -> Unit,
    modifier: Modifier = Modifier,
    heading: String = "Pick something to measure",
    hint: String? = null,
    order: SourceOrder = SourceOrder.RANKED,
    warnOnRandom: Boolean = false,
    /** Below this many packets a second, the row is shown but marked as a poor subject. */
    wantsRate: Double? = null,
    minSightings: Int = 3,
    limit: Int = 25,
    paused: Boolean = false,
) {
    val context = LocalContext.current
    val book = remember { DeviceBook.get(context) }
    val notes by book.notes.collectAsStateWithLifecycle()
    val shown = rememberSources(order, minSightings, limit, paused)

    Text(heading, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    hint?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))

    if (shown.isEmpty()) {
        Text(
            "Listening...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    Column(modifier) {
        shown.forEach { source ->
            val nickname = notes[source.address.uppercase(Locale.US)]?.nickname
            SourceRow(
                source = source,
                nickname = nickname,
                warnOnRandom = warnOnRandom,
                wantsRate = wantsRate,
                onPick = { onPick(source) },
            )
        }
    }
}

@Composable
private fun SourceRow(
    source: Source,
    nickname: String?,
    warnOnRandom: Boolean,
    wantsRate: Double?,
    onPick: () -> Unit,
) {
    val chatty = wantsRate == null || source.rate >= wantsRate

    Card(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clickable(onClickLabel = "Measure against ${source.label(nickname)}", onClick = onPick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.padding(end = 8.dp)) {
                    Text(source.label(nickname), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        source.address,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        String.format(Locale.US, "%.1f/s", source.rate),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (chatty) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Text(
                        "${source.rssi} dBm",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (warnOnRandom && source.isRandom) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Randomized address - this device will change it partway through and " +
                        "the measurement will stop where it does.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun comparatorFor(
    order: SourceOrder,
    notes: Map<String, DeviceNote>,
    watched: Set<String>,
): Comparator<Source> = when (order) {
    SourceOrder.RATE -> compareByDescending { it.rate }
    SourceOrder.SIGNAL -> compareByDescending { it.rssi }
    SourceOrder.RANKED -> compareBy<Source> { source ->
        val note = notes[source.address.uppercase(Locale.US)]
        DeviceRanking.rank(
            watched = watched.contains(source.address.uppercase(Locale.US)),
            nickname = note?.nickname,
            lists = note?.lists.orEmpty(),
            hasIdentity = source.hasIdentity,
        )
    }.thenByDescending { it.rssi }
}

/** Twice a second is faster than anyone reads and slow enough to cost nothing. */
private const val REFRESH_MS = 500L
