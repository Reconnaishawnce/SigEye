package com.sigeye.core.ble

import android.bluetooth.le.ScanResult
import com.sigeye.core.Vendors
import com.sigeye.core.analysis.identity.AdvertShape

/**
 * One decoded BLE advertisement, shared by every experiment.
 *
 * Decoded once at the hub rather than separately per consumer: the radio hands the same
 * packet to everyone, and parsing it three times would be three times the allocation on
 * the hottest path in the app.
 */
data class Advert(
    val address: String,
    val rssi: Int,
    val atMs: Long,
    val name: String? = null,
    val companyId: Int? = null,
    val manufacturerData: ByteArray? = null,
    val serviceUuids: List<String> = emptyList(),
    val serviceData: Map<String, ByteArray> = emptyMap(),
    val txPower: Int? = null,
    /** GAP Appearance, when the device bothered to declare one. */
    val appearance: Int? = null,
    /**
     * Link-layer traits, all API 26 and none of them part of the payload.
     *
     * These are set by the controller and the advertising parameters rather than by the
     * privacy scheme, which makes them worth as much as the payload shape for telling one
     * device from another - and unlike the payload, nothing rotates them.
     */
    val isLegacy: Boolean = true,
    val isConnectable: Boolean = false,
    val primaryPhy: Int = 1,
    val secondaryPhy: Int = 0,
    val advertisingSid: Int = 0xFF,
) {
    val oui: String get() = Vendors.ouiOf(address)

    val isRandomAddress: Boolean get() = Vendors.isRandomAddress(address)

    val vendor: String?
        get() = Vendors.byAddress(address) ?: companyId?.let { Vendors.byCompanyId(it) }

    /** What the device says it is, e.g. "Wearable audio - earbud". */
    val appearanceLabel: String? get() = Appearance.describe(appearance)

    /** Which kind of address this is, which decides whether it can rotate at all. */
    val addressType: AddressType get() = AddressType.of(address)

    /** Identity by address; the payload arrays would otherwise compare by reference. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is Advert && other.address == address && other.atMs == atMs)

    override fun hashCode(): Int = address.hashCode() * 31 + atMs.hashCode()

    companion object {
        fun from(result: ScanResult, nowMs: Long): Advert? {
            val address = result.device?.address ?: return null
            val record = result.scanRecord

            var companyId: Int? = null
            var manufacturerData: ByteArray? = null
            val mfg = record?.manufacturerSpecificData
            if (mfg != null && mfg.size() > 0) {
                companyId = mfg.keyAt(0)
                manufacturerData = mfg.valueAt(0)
            }

            return Advert(
                address = address,
                rssi = result.rssi,
                atMs = nowMs,
                name = record?.deviceName,
                companyId = companyId,
                manufacturerData = manufacturerData,
                serviceUuids = record?.serviceUuids?.map { it.uuid.toString() }.orEmpty(),
                serviceData = record?.serviceData
                    ?.mapKeys { it.key.uuid.toString() }
                    ?.mapValues { it.value ?: ByteArray(0) }
                    .orEmpty(),
                txPower = record?.txPowerLevel?.takeIf { it != Int.MIN_VALUE },
                appearance = Appearance.parse(record?.bytes),
                isLegacy = result.isLegacy,
                isConnectable = result.isConnectable,
                primaryPhy = result.primaryPhy,
                secondaryPhy = result.secondaryPhy,
                advertisingSid = result.advertisingSid,
            )
        }
    }
}

/**
 * The fingerprintable shape of this advertisement, with everything that rotates removed.
 *
 * Lived in two screens as a copied block until following needed a third. The mapping is a
 * fact about the wire format rather than about any one experiment, so it belongs beside
 * the packet it maps.
 */
fun Advert.shape(): AdvertShape = AdvertShape(
    companyId = companyId,
    serviceUuids = serviceUuids,
    appearance = appearance,
    txPower = txPower,
    name = name?.takeIf { it.isNotBlank() },
    manufacturerLength = manufacturerData?.size ?: 0,
    manufacturerPrefix = manufacturerData?.take(2)?.joinToString("") { "%02X".format(it) },
    serviceDataKeys = serviceData.keys.toList(),
    continuityTypes = Continuity.types(companyId, manufacturerData),
    isLegacy = isLegacy,
    isConnectable = isConnectable,
    primaryPhy = primaryPhy,
    secondaryPhy = secondaryPhy,
    advertisingSid = advertisingSid,
)

/** What the radio is currently managing to deliver. */
data class ScanHealth(
    val scanning: Boolean = false,
    val advertsPerSecond: Double = 0.0,
    /** Best rate seen early in the current scan cycle - what healthy looks like here. */
    val referenceRate: Double = 0.0,
    val restarts: Int = 0,
    val subscribers: Int = 0,
    /**
     * Who is currently holding the radio on.
     *
     * A count alone cannot be acted on: "2 subscribers" with one screen open means
     * something leaked a claim, and the whole symptom of a leak is a battery that empties
     * with the app apparently idle. Naming them makes it a thing somebody can see and
     * report rather than a thing nobody notices for a month.
     */
    val claims: Set<String> = emptySet(),
    val error: String? = null,
) {
    /** Delivery has collapsed relative to what this phone managed a moment ago. */
    val starved: Boolean
        get() = scanning && referenceRate >= 1.0 && advertsPerSecond < referenceRate * 0.25
}
