package com.sigeye.core

/**
 * Turns raw advertisement identifiers into readable names.
 *
 * Two separate namespaces, often confused:
 *
 *  - **OUI** - the first three bytes of a MAC address, assigned by the IEEE. Only
 *    meaningful when the device advertises a public (non-randomised) address, which is
 *    typical of fixed-function hardware: beacons, tags, body cameras, car kits, docks.
 *  - **Company ID** - a 16-bit number assigned by the Bluetooth SIG, carried in the first
 *    two bytes of manufacturer-specific data. Present regardless of address randomisation,
 *    which makes it the more reliable of the two for phones.
 *
 * Deliberately a curated list rather than the full IEEE registry: the complete OUI file is
 * several megabytes, and an unrecognised prefix is shown as raw hex anyway.
 */
object Vendors {

    /**
     * IEEE OUI prefixes worth calling out by name.
     *
     * Axon's block is verified against the IEEE registry: 00:25:DF, Axon Enterprise Inc.,
     * Scottsdale AZ, registered originally as TASER International, MA-L, not randomised.
     * It is their only block, so anything on it is Axon hardware - which includes docks,
     * TASERs and fleet gear, not only body cameras.
     */
    private val OUI: Map<String, String> = mapOf(
        "00:25:DF" to "Axon Enterprise",
        // A different company entirely, despite the name. Easy to confuse.
        "00:58:28" to "Axon Networks (unrelated)",
        "84:70:03" to "Axon Networks (unrelated)",
        "00:07:80" to "Zebra / Motorola Solutions",
        "00:15:70" to "Motorola Solutions",
        "00:17:88" to "Philips Hue",
        "B8:27:EB" to "Raspberry Pi",
        "DC:A6:32" to "Raspberry Pi",
        "E4:5F:01" to "Raspberry Pi",
        "EC:FA:BC" to "Espressif",
        "24:0A:C4" to "Espressif",
        "7C:DF:A1" to "Espressif",
        "3C:5A:B4" to "Google",
        "F4:F5:D8" to "Google",
    )

    /** Bluetooth SIG company identifiers, the ones you actually meet in the wild. */
    private val COMPANY: Map<Int, String> = mapOf(
        0x0001 to "Ericsson",
        0x0002 to "Intel",
        0x0006 to "Microsoft",
        0x000D to "Texas Instruments",
        0x000F to "Broadcom",
        0x0030 to "ST Microelectronics",
        0x004C to "Apple",
        0x0059 to "Nordic Semiconductor",
        0x0075 to "Samsung",
        0x0087 to "Garmin",
        0x009E to "Bose",
        0x00E0 to "Google",
        0x0110 to "Tile",
        0x0118 to "Fitbit",
        0x0131 to "Cypress",
        0x0154 to "Withings",
        0x0157 to "Huami / Amazfit",
        0x0171 to "Amazon",
        0x02E5 to "Espressif",
        0x038F to "Xiaomi",
        0x0499 to "Ruuvi",
        0x0553 to "Whoop",
        0x05A7 to "Sonos",
    )

    /** Second bit of the first octet set means the address is locally administered. */
    fun isRandomAddress(address: String): Boolean {
        val firstOctet = address.substringBefore(':').toIntOrNull(16) ?: return false
        return (firstOctet and 0x02) != 0
    }

    fun ouiOf(address: String): String =
        address.uppercase().replace('-', ':').split(':').take(3).joinToString(":")

    /** Vendor for a MAC, or null when the address is randomised or simply unknown. */
    fun byAddress(address: String): String? {
        if (isRandomAddress(address)) return null
        return OUI[ouiOf(address)]
    }

    fun byCompanyId(id: Int): String? = COMPANY[id]

    fun companyIdHex(id: Int): String = "0x" + id.toString(16).uppercase().padStart(4, '0')

    /** True when this address belongs to Axon Enterprise's IEEE block. */
    fun isAxon(address: String): Boolean =
        !isRandomAddress(address) && ouiOf(address) == "00:25:DF"
}
