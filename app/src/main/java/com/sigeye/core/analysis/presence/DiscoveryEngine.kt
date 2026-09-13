package com.sigeye.core.analysis.presence

import com.sigeye.core.analysis.record.Snapshot
import com.sigeye.core.analysis.record.Track
import com.sigeye.core.analysis.rf.ProximityEstimator
import com.sigeye.core.analysis.rf.Trend

/** What the engine is currently doing. */
enum class DiscoveryStage(val label: String) {
    IDLE("Not started"),
    BASELINE("Learning what is normally here"),
    WATCHING("Watching for anything new"),
}

/** Everything known about one advertiser in this session. */
data class Sighting(
    val address: String,
    val name: String? = null,
    val vendor: String? = null,
    val isRandom: Boolean = false,
    val firstSeenMs: Long = 0,
    val lastSeenMs: Long = 0,
    val sightings: Int = 0,
    val rssi: Int = -127,
    val bestRssi: Int = -127,
) {
    fun label(): String = name?.takeIf { it.isNotBlank() } ?: vendor ?: address
}

/**
 * Something that turned up after the baseline closed.
 *
 * [possibleRotationOf] is a suspicion, never a finding: a randomized address that changes
 * looks exactly like a stranger arriving, and on a busy street most "arrivals" are the
 * former. See [DiscoveryEngine.suspectRotation].
 */
data class Arrival(
    val sighting: Sighting,
    val trend: Trend = Trend.STEADY,
    val slopeDbPerSecond: Double = 0.0,
    val meters: Double = 0.0,
    val possibleRotationOf: String? = null,
) {
    val approaching: Boolean get() = trend == Trend.CLOSER
}

/**
 * Learn what is normally around you, then show only what is not.
 *
 * A scan of a street or a house returns fifty devices and no way to tell which of them
 * matter. The useful question is almost never "what is here" but "what is here *now* that
 * was not here a minute ago" - the car that just pulled up, the phone walking towards you,
 * the thing in your living room that was not in your living room last week.
 *
 * So: a baseline period where everything heard is filed as furniture, and then a watching
 * period where anything unheard-of surfaces. Arrivals carry a trend, because a stranger
 * getting steadily louder is a different proposition from one that appeared once.
 *
 * Pure and Android-free.
 */
class DiscoveryEngine(
    /** How long to spend learning before anything counts as new. */
    var baselineMs: Long = 30_000L,
    /** How long an arrival stays on the list after it was last heard. */
    private val staleMs: Long = 120_000L,
) {

    private val known = mutableSetOf<String>()
    private val seen = LinkedHashMap<String, Sighting>()
    private val estimators = mutableMapOf<String, ProximityEstimator>()
    private val arrived = LinkedHashMap<String, Arrival>()

    var stage: DiscoveryStage = DiscoveryStage.IDLE
        private set

    private var startedAtMs = 0L

    /** Addresses filed as furniture during the baseline. */
    val baselineSize: Int get() = known.size

    val arrivalCount: Int get() = arrived.size

    fun start(nowMs: Long) {
        known.clear()
        seen.clear()
        estimators.clear()
        arrived.clear()
        startedAtMs = nowMs
        stage = DiscoveryStage.BASELINE
    }

    /** Adds whatever is currently in range to the baseline without restarting it. */
    fun absorbIntoBaseline() {
        known.addAll(seen.keys)
        arrived.clear()
    }

    /**
     * Starts from a baseline recorded somewhere else, rather than learning one here.
     *
     * The sweep-for-bugs use: take the baseline at home while you trust the place, then
     * seed it into a session somewhere else - a hotel room, a rental, an office - and
     * anything already there is new by definition, without waiting thirty seconds first.
     * It also works the other way round: seed last week's scan of your own house and only
     * what has appeared since will surface.
     */
    fun seedBaseline(addresses: Collection<String>, nowMs: Long) {
        known.clear()
        seen.clear()
        estimators.clear()
        arrived.clear()
        known.addAll(addresses.map { it.uppercase() })
        startedAtMs = nowMs
        stage = DiscoveryStage.WATCHING
    }

    fun isKnown(address: String): Boolean = known.contains(address.uppercase())

    /** Forgets one arrival, for dismissing something you have identified. */
    fun ignore(address: String) {
        known.add(address.uppercase())
        arrived.remove(address.uppercase())
    }

    fun baselineProgress(nowMs: Long): Float = when (stage) {
        DiscoveryStage.BASELINE ->
            ((nowMs - startedAtMs).toFloat() / baselineMs).coerceIn(0f, 1f)
        DiscoveryStage.WATCHING -> 1f
        DiscoveryStage.IDLE -> 0f
    }

    fun observe(
        address: String,
        rssi: Int,
        atMs: Long,
        name: String? = null,
        vendor: String? = null,
        isRandom: Boolean = false,
    ) {
        if (stage == DiscoveryStage.IDLE) return
        val key = address.uppercase()

        val existing = seen[key]
        val sighting = Sighting(
            address = key,
            name = name?.takeIf { it.isNotBlank() } ?: existing?.name,
            vendor = vendor ?: existing?.vendor,
            isRandom = isRandom,
            firstSeenMs = existing?.firstSeenMs ?: atMs,
            lastSeenMs = atMs,
            sightings = (existing?.sightings ?: 0) + 1,
            rssi = rssi,
            bestRssi = maxOf(rssi, existing?.bestRssi ?: rssi),
        )
        seen[key] = sighting

        if (stage == DiscoveryStage.BASELINE) {
            known.add(key)
            return
        }

        if (known.contains(key)) return

        // New. Track its approach, which is the thing worth knowing about a stranger.
        val estimator = estimators.getOrPut(key) { ProximityEstimator() }
        val reading = estimator.observe(rssi, atMs)
        arrived[key] = Arrival(
            sighting = sighting,
            trend = reading.trend,
            slopeDbPerSecond = reading.slopeDbPerSecond,
            meters = reading.meters,
            possibleRotationOf = arrived[key]?.possibleRotationOf
                ?: suspectRotation(sighting),
        )
    }

    /** Advances the stage and drops arrivals nothing has been heard from. */
    fun tick(nowMs: Long) {
        if (stage == DiscoveryStage.BASELINE && nowMs - startedAtMs >= baselineMs) {
            stage = DiscoveryStage.WATCHING
        }
        val stale = arrived.filterValues { nowMs - it.sighting.lastSeenMs > staleMs }.keys
        stale.forEach {
            arrived.remove(it)
            estimators.remove(it)
        }
    }

    /**
     * Arrivals, closest first - the one walking towards you is the one you want on top.
     *
     * The two filters exist because on a street the list is mostly phones rotating their
     * addresses, and a list that is mostly noise is a list nobody reads. Hiding suspected
     * rotations is the conservative one; hiding randomized addresses altogether is blunt,
     * and leaves only devices with fixed addresses - which is to say fitted equipment
     * rather than people walking past.
     */
    fun arrivals(
        hideSuspectedRotations: Boolean = false,
        hideRandomAddresses: Boolean = false,
    ): List<Arrival> = arrived.values
        .filter { !(hideSuspectedRotations && it.possibleRotationOf != null) }
        .filter { !(hideRandomAddresses && it.sighting.isRandom) }
        .sortedWith(
            compareByDescending<Arrival> { it.approaching }
                .thenByDescending { it.sighting.rssi },
        )

    /** Everything heard recently, for plotting. Known and new are kept apart. */
    fun inRange(nowMs: Long, withinMs: Long = 20_000L): List<Sighting> =
        seen.values.filter { nowMs - it.lastSeenMs <= withinMs }

    /** Everything heard this session, baseline included. */
    fun everything(): List<Sighting> = seen.values.toList()

    /**
     * Whether this arrival might just be a device we already know changing its address.
     *
     * Phones rotate their random address every fifteen minutes or so, and the rotation is
     * indistinguishable from a stranger walking up: a new address, similar signal, at the
     * same moment the old one stops. Guessing wrong in either direction is bad - an alert
     * for every rotation makes the feature useless, and quietly swallowing a real arrival
     * makes it dangerous - so this reports a suspicion for the screen to show, and the
     * arrival is listed either way.
     *
     * The test is deliberately narrow: both addresses randomized, the old one falling
     * silent within a minute of the new one appearing, and their strongest readings within
     * a few dB. A public address never qualifies, because public addresses do not rotate.
     */
    fun suspectRotation(arrival: Sighting): String? {
        if (!arrival.isRandom) return null
        return seen.values
            .filter { candidate ->
                candidate.address != arrival.address &&
                    candidate.isRandom &&
                    known.contains(candidate.address) &&
                    candidate.lastSeenMs < arrival.firstSeenMs &&
                    arrival.firstSeenMs - candidate.lastSeenMs <= ROTATION_WINDOW_MS &&
                    kotlin.math.abs(candidate.bestRssi - arrival.bestRssi) <= ROTATION_RSSI_DB
            }
            // The closest match in signal, which is the only evidence available.
            .minByOrNull { kotlin.math.abs(it.bestRssi - arrival.bestRssi) }
            ?.address
    }

    /** A named record of everything heard, for comparing against later. */
    fun snapshot(label: String, atMs: Long): Snapshot = Snapshot(
        label = label,
        takenAtMs = atMs,
        devices = seen.values.toList(),
    )

    companion object {
        /** A rotation happens in one step, so the gap between the two is short. */
        const val ROTATION_WINDOW_MS = 60_000L

        /** Same device, same place, so the signal should barely change across the swap. */
        const val ROTATION_RSSI_DB = 6
    }
}
