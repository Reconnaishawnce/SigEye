package com.sigeye.experiments.inspector

import com.sigeye.core.Vendors
import com.sigeye.core.analysis.surveillance.Sighting
import com.sigeye.core.analysis.surveillance.Surveillance
import com.sigeye.core.ble.Advert
import com.sigeye.core.ble.Product
import com.sigeye.core.ble.Products

/**
 * Everything one advertiser has told us about itself, accumulated across sightings.
 */
data class SeenDevice(
    val address: String,
    val name: String? = null,
    val rssi: Int = -127,
    val bestRssi: Int = -127,
    val companyId: Int? = null,
    val manufacturerData: ByteArray? = null,
    val serviceUuids: List<String> = emptyList(),
    val serviceData: Map<String, ByteArray> = emptyMap(),
    val txPower: Int? = null,
    val appearance: Int? = null,
    val firstSeenMs: Long = 0,
    val lastSeenMs: Long = 0,
    val sightings: Int = 0,
) {
    val isRandomAddress: Boolean get() = Vendors.isRandomAddress(address)

    val oui: String get() = Vendors.ouiOf(address)

    val vendor: String?
        get() = Vendors.byAddress(address) ?: companyId?.let { Vendors.byCompanyId(it) }

    val isAxon: Boolean get() = Vendors.isAxon(address)

    /**
     * What this thing appears to be, named as specifically as the evidence allows.
     *
     * Built from the accumulated record rather than one packet, which matters: a device
     * sends a rich advertisement and a bare one in turn, so the services arrive in one and
     * the manufacturer data in another.
     */
    val product: Product
        get() = Products.of(
            Advert(
                address = address,
                rssi = rssi,
                atMs = lastSeenMs,
                name = name,
                companyId = companyId,
                manufacturerData = manufacturerData,
                serviceUuids = serviceUuids,
                serviceData = serviceData,
                txPower = txPower,
                appearance = appearance,
            ),
        )

    /**
     * Surveillance hardware this device has identified itself as, if any.
     *
     * Built from the accumulated record rather than from one packet, which matters for
     * Flock battery packs: the name and the manufacturer data arrive in different frames,
     * so a single advertisement often has one and not the other.
     */
    val sighting: Sighting?
        get() = Surveillance.fromBle(address, name, companyId, manufacturerData)

    /** Worth highlighting in the list: something we have a thing to say about. */
    val flagged: Boolean
        get() = sighting != null || Vendors.surveillanceNote(address, companyId, name) != null

    /** Best label when the user has not supplied one of their own. */
    val fallbackName: String
        get() = name?.takeIf { it.isNotBlank() } ?: vendor ?: address

    /**
     * A rough distance, from the log-distance path loss model with an assumed reference of
     * -59 dBm at one meter and an exponent of 2. Wrong by a factor of two or more in any
     * real room, so it is only ever shown as a coarse hint, never as a measurement.
     */
    fun roughMetres(): Double {
        val ref = txPower ?: -59
        return Math.pow(10.0, (ref - rssi) / 20.0)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is SeenDevice && other.address == address)

    override fun hashCode(): Int = address.hashCode()

    fun merge(advert: Advert): SeenDevice = copy(
        name = advert.name?.takeIf { it.isNotBlank() } ?: name,
        rssi = advert.rssi,
        bestRssi = maxOf(advert.rssi, bestRssi),
        companyId = advert.companyId ?: companyId,
        manufacturerData = advert.manufacturerData ?: manufacturerData,
        serviceUuids = advert.serviceUuids.ifEmpty { serviceUuids },
        serviceData = advert.serviceData.ifEmpty { serviceData },
        txPower = advert.txPower ?: txPower,
        appearance = advert.appearance ?: appearance,
        lastSeenMs = advert.atMs,
        sightings = sightings + 1,
    )

    companion object {
        fun from(advert: Advert): SeenDevice = SeenDevice(
            address = advert.address,
            name = advert.name?.takeIf { it.isNotBlank() },
            rssi = advert.rssi,
            bestRssi = advert.rssi,
            companyId = advert.companyId,
            manufacturerData = advert.manufacturerData,
            serviceUuids = advert.serviceUuids,
            serviceData = advert.serviceData,
            txPower = advert.txPower,
            appearance = advert.appearance,
            firstSeenMs = advert.atMs,
            lastSeenMs = advert.atMs,
            sightings = 1,
        )
    }
}

/**
 * Accumulates adverts into a device table.
 *
 * Deliberately separate from the UI so the screen can pull a snapshot on its own schedule.
 * Publishing on every advertisement would mean hundreds of list copies a second in a busy
 * area, each recomposing the whole list - the table gets written hot and read slow.
 */
class DeviceTable {
    private val devices = LinkedHashMap<String, SeenDevice>()

    @Synchronized
    fun record(advert: Advert) {
        val existing = devices[advert.address]
        devices[advert.address] = existing?.merge(advert) ?: SeenDevice.from(advert)
    }

    @Synchronized
    fun snapshot(): List<SeenDevice> = devices.values.toList()

    @Synchronized
    fun forget(address: String) {
        devices.remove(address)
    }

    @Synchronized
    fun clear() = devices.clear()
}

enum class SortMode(val label: String) {
    STRONGEST("Closest"),
    NEWEST("Newest"),
    CHATTIEST("Chattiest"),
    NAME("Name"),
}

fun List<SeenDevice>.freshWithin(millis: Long, now: Long) =
    filter { now - it.lastSeenMs <= millis }

fun List<SeenDevice>.sortedBy(mode: SortMode, nameOf: (SeenDevice) -> String) = when (mode) {
    SortMode.STRONGEST -> sortedByDescending { it.rssi }
    SortMode.NEWEST -> sortedByDescending { it.firstSeenMs }
    SortMode.CHATTIEST -> sortedByDescending { it.sightings }
    SortMode.NAME -> sortedBy { nameOf(it).lowercase() }
}

fun ByteArray.toHex(limit: Int = 24): String {
    val shown = take(limit).joinToString(" ") { String.format("%02X", it) }
    return if (size > limit) "$shown ..." else shown
}
