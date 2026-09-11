package com.sigeye.experiments.inspector

import com.sigeye.core.Vendors

/**
 * Everything one advertiser has told us about itself.
 *
 * Deliberately keeps the decoded fields rather than the raw packet alone, because the
 * whole point of the Inspector is to make an advertisement legible: what is broadcasting,
 * who made it, and what it is saying.
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
    val firstSeenMs: Long = 0,
    val lastSeenMs: Long = 0,
    val sightings: Int = 0,
) {
    val isRandomAddress: Boolean get() = Vendors.isRandomAddress(address)

    val oui: String get() = Vendors.ouiOf(address)

    /** Manufacturer by MAC block, then by Bluetooth SIG company ID, else null. */
    val vendor: String?
        get() = Vendors.byAddress(address) ?: companyId?.let { Vendors.byCompanyId(it) }

    val isAxon: Boolean get() = Vendors.isAxon(address)

    /** Best short label for a list row. */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: vendor ?: address

    /**
     * A rough distance, from the log-distance path loss model with an assumed reference of
     * -59 dBm at one metre and an exponent of 2. Wrong by a factor of two or more in any
     * real room - see the Multipath Fading experiment for why - so it is only ever shown
     * as a coarse hint, never as a measurement.
     */
    fun roughMetres(): Double {
        val ref = txPower ?: -59
        return Math.pow(10.0, (ref - rssi) / 20.0)
    }

    /** Equality by address; the payload arrays would otherwise compare by reference. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is SeenDevice && other.address == address)

    override fun hashCode(): Int = address.hashCode()
}

/** How the device list is ordered. */
enum class SortMode(val label: String) {
    STRONGEST("Closest"),
    NEWEST("Newest"),
    CHATTIEST("Chattiest"),
    NAME("Name"),
}
