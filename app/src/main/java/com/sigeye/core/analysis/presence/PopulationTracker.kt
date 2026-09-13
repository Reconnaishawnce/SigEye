package com.sigeye.core.analysis.presence

import com.sigeye.core.Vendors

/** How long a device has stuck around. */
enum class DwellClass(val label: String, val blurb: String) {
    PASSING("Passing", "Seen briefly and gone. Traffic."),
    LINGERING("Lingering", "Stayed a while. A visitor, a queue, a parked car."),
    RESIDENT("Resident", "Been here the whole time. Neighbours, your own kit, fixtures."),
}

data class TrackedDevice(
    val address: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val sightings: Int,
    val bestRssi: Int,
    val lastRssi: Int,
    val isRandomAddress: Boolean,
    /** Advertised name, when it gives one. */
    val name: String? = null,
    val companyId: Int? = null,
) {
    val vendor: String?
        get() = Vendors.byAddress(address) ?: companyId?.let { Vendors.byCompanyId(it) }

    /** What to call it before the user names it themselves. */
    val fallbackName: String
        get() = name?.takeIf { it.isNotBlank() } ?: vendor ?: address

    /** How long between the first and last time we heard it. */
    val dwellMs: Long get() = lastSeenMs - firstSeenMs

    fun dwellClass(config: PopulationConfig): DwellClass = when {
        dwellMs >= config.residentMillis -> DwellClass.RESIDENT
        dwellMs >= config.lingeringMillis -> DwellClass.LINGERING
        else -> DwellClass.PASSING
    }

    fun isPresent(nowMs: Long, config: PopulationConfig): Boolean =
        nowMs - lastSeenMs <= config.presenceMillis
}

data class PopulationConfig(
    /** Ignore anything weaker than this - it sets how big "here" is. */
    val rssiFloor: Int = -85,
    /** Still counted as present if heard within this long. */
    val presenceSeconds: Int = 60,
    /** At or above this dwell, a device is lingering rather than passing. */
    val lingeringMinutes: Int = 2,
    /** At or above this dwell, it is a fixture rather than a visitor. */
    val residentMinutes: Int = 20,
    /**
     * Devices per person, for the crowd estimate.
     *
     * Not a constant of nature. A commuter carries a phone, earbuds and maybe a watch,
     * each advertising separately; a room also contains televisions and printers that
     * belong to nobody. Calibrate it against a headcount you actually know.
     */
    val devicesPerPerson: Double = 2.0,
    /** Forget devices unseen for this long, so memory does not grow without bound. */
    val forgetMinutes: Int = 60,
) {
    val presenceMillis: Long get() = presenceSeconds * 1_000L
    val lingeringMillis: Long get() = lingeringMinutes * 60_000L
    val residentMillis: Long get() = residentMinutes * 60_000L
    val forgetMillis: Long get() = forgetMinutes * 60_000L
}

data class PopulationSnapshot(
    val devices: List<TrackedDevice>,
    val presentNow: Int,
    val passing: Int,
    val lingering: Int,
    val resident: Int,
    val randomAddressShare: Double,
    val estimatedPeople: Double,
    val observedForMs: Long,
)

/**
 * Accumulates who has been around and for how long.
 *
 * Shared by Dwell Time and the Crowd Counter because they are two readings of the same
 * measurement: one asks how long devices stay, the other how many are here at once.
 *
 * Pure and Android-free so the classification can be tested without a radio.
 *
 * The honest caveat, which both screens repeat: address randomisation means one phone can
 * appear as several devices over an evening. That inflates "passing" and inflates any
 * crowd estimate taken over a long window - which is why [PopulationSnapshot.presentNow]
 * uses a short presence window rather than everything ever seen.
 */
class PopulationTracker(var config: PopulationConfig = PopulationConfig()) {

    private val devices = HashMap<String, TrackedDevice>()

    // Nullable rather than a zero sentinel: zero is a legitimate timestamp, and treating
    // it as "unset" would make the observation window read zero forever.
    private var startedAtMs: Long? = null

    fun reset() {
        devices.clear()
        startedAtMs = null
    }

    fun observe(
        address: String,
        rssi: Int,
        nowMs: Long,
        isRandomAddress: Boolean,
        name: String? = null,
        companyId: Int? = null,
    ) {
        if (rssi < config.rssiFloor) return
        if (startedAtMs == null) startedAtMs = nowMs

        val existing = devices[address]
        devices[address] = if (existing == null) {
            TrackedDevice(
                address = address,
                firstSeenMs = nowMs,
                lastSeenMs = nowMs,
                sightings = 1,
                bestRssi = rssi,
                lastRssi = rssi,
                isRandomAddress = isRandomAddress,
                name = name,
                companyId = companyId,
            )
        } else {
            existing.copy(
                lastSeenMs = nowMs,
                sightings = existing.sightings + 1,
                bestRssi = maxOf(existing.bestRssi, rssi),
                lastRssi = rssi,
                // Names and company ids arrive on some packets and not others.
                name = name?.takeIf { it.isNotBlank() } ?: existing.name,
                companyId = companyId ?: existing.companyId,
            )
        }
    }

    /** Drops devices unheard for longer than the forget window. */
    fun prune(nowMs: Long) {
        val cutoff = nowMs - config.forgetMillis
        devices.entries.removeAll { it.value.lastSeenMs < cutoff }
    }

    fun snapshot(nowMs: Long): PopulationSnapshot {
        val all = devices.values.toList()
        val present = all.filter { it.isPresent(nowMs, config) }

        var passing = 0
        var lingering = 0
        var resident = 0
        all.forEach {
            when (it.dwellClass(config)) {
                DwellClass.PASSING -> passing++
                DwellClass.LINGERING -> lingering++
                DwellClass.RESIDENT -> resident++
            }
        }

        val randomShare = if (all.isEmpty()) {
            0.0
        } else {
            all.count { it.isRandomAddress }.toDouble() / all.size
        }

        return PopulationSnapshot(
            devices = all,
            presentNow = present.size,
            passing = passing,
            lingering = lingering,
            resident = resident,
            randomAddressShare = randomShare,
            estimatedPeople = estimatePeople(present.size),
            observedForMs = startedAtMs?.let { nowMs - it } ?: 0L,
        )
    }

    /** Present devices divided by the calibration factor, floored at zero. */
    fun estimatePeople(presentDevices: Int): Double {
        val perPerson = config.devicesPerPerson
        if (perPerson <= 0.0) return presentDevices.toDouble()
        return presentDevices / perPerson
    }

    fun size(): Int = devices.size
}
