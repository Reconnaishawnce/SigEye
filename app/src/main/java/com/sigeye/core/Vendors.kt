package com.sigeye.core

/**
 * Turns raw advertisement identifiers into readable names.
 *
 * Two separate namespaces, often confused:
 *
 *  - **OUI** - the first three bytes of a MAC address, assigned by the IEEE. Only
 *    meaningful when the device advertises a public (non-randomized) address, which is
 *    typical of fixed-function hardware: beacons, tags, body cameras, car kits, readers.
 *  - **Company ID** - a 16-bit number assigned by the Bluetooth SIG, carried in the first
 *    two bytes of manufacturer-specific data. Present regardless of address randomization,
 *    which makes it the more reliable of the two for phones.
 *
 * The tables themselves live in [VendorData], generated from the registries.
 */
object Vendors {

    /** Axon Enterprise's only IEEE block. Body cameras, docks, TASERs, fleet gear. */
    const val AXON_OUI = "00:25:DF"

    /** HID Global's Bluetooth SIG company identifier. */
    const val HID_COMPANY_ID = 0x0124

    private val HID_OUIS = setOf("00:06:8E", "00:30:8E", "00:06:33")

    /**
     * Whether this address is one the device made up rather than one it was assigned.
     *
     * Three tells, in order of how much they are worth:
     *
     *  1. The prefix is in the IEEE registry. Blocks are assigned to real companies and are
     *     not used as random addresses, so this settles it.
     *  2. The locally-administered bit. The classic MAC tell, but only half of Bluetooth's
     *     resolvable private addresses happen to set it.
     *  3. The top two bits, which for a random address encode its kind - 11 static,
     *     01 resolvable, 00 non-resolvable. A *public* address can land on those same
     *     patterns (Espressif's 48:CA:43 reads as 01), which is why this is only trusted
     *     once the full registry is loaded and has been given its chance to say otherwise.
     */
    fun isRandomAddress(address: String): Boolean {
        val firstOctet = address.substringBefore(':').toIntOrNull(16) ?: return false
        if (registered(address) != null) return false
        if ((firstOctet and 0x02) != 0) return true
        if (!OuiRegistry.available) return false
        val topBits = (firstOctet shr 6) and 0x03
        return topBits == 0b01 || topBits == 0b11
    }

    fun ouiOf(address: String): String =
        address.uppercase().replace('-', ':').split(':').take(3).joinToString(":")

    /**
     * Whoever the IEEE assigned this prefix to, ignoring whether the address looks random.
     *
     * The curated table wins where it has an entry: its names are shorter and it is the one
     * that knows which vendors are worth remarking on. The full registry covers the other
     * forty thousand.
     */
    private fun registered(address: String): String? {
        val oui = ouiOf(address)
        return VendorData.OUI[oui] ?: OuiRegistry.lookup(oui)
    }

    /** Vendor for a MAC, or null when the address is randomized or simply unknown. */
    fun byAddress(address: String): String? {
        if (isRandomAddress(address)) return null
        return registered(address)
    }

    fun byCompanyId(id: Int): String? = VendorData.COMPANY[id]

    fun companyIdHex(id: Int): String = "0x" + id.toString(16).uppercase().padStart(4, '0')

    fun isAxon(address: String): Boolean =
        !isRandomAddress(address) && ouiOf(address) == AXON_OUI

    fun isHid(address: String): Boolean =
        !isRandomAddress(address) && HID_OUIS.contains(ouiOf(address))

    /**
     * Devices worth calling out on sight, with the caveat that belongs beside the claim.
     *
     * Every one of these is an inference from a manufacturer identifier, not proof of a
     * particular product or of what it is doing. The caveat is returned with the label so
     * it travels with the alert instead of living in a README.
     */
    fun surveillanceNote(address: String, companyId: Int?, name: String?): String? = when {
        isAxon(address) ->
            "Axon Enterprise hardware. That block covers body cameras, docks, TASERs and " +
                "fleet gear alike, and presence is not recording."

        isHid(address) || companyId == HID_COMPANY_ID ->
            "HID access control hardware. Signo and iCLASS SE readers advertise " +
                "continuously for mobile credentials."

        else -> null
    }
}
