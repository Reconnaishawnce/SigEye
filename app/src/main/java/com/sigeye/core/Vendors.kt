package com.sigeye.core

/**
 * Turns raw advertisement identifiers into readable names.
 *
 * Two separate namespaces, often confused:
 *
 *  - **OUI** - the first three bytes of a MAC address, assigned by the IEEE. Only
 *    meaningful when the device advertises a public (non-randomised) address, which is
 *    typical of fixed-function hardware: beacons, tags, body cameras, car kits, readers.
 *  - **Company ID** - a 16-bit number assigned by the Bluetooth SIG, carried in the first
 *    two bytes of manufacturer-specific data. Present regardless of address randomisation,
 *    which makes it the more reliable of the two for phones.
 *
 * The tables themselves live in [VendorData], generated from the registries.
 */
object Vendors {

    /** Axon Enterprise's only IEEE block. Body cameras, docks, TASERs, fleet gear. */
    const val AXON_OUI = "00:25:DF"

    /** Flock Safety's only IEEE block. */
    const val FLOCK_OUI = "B4:1E:52"

    /** XUNTONG, the manufacturer ID seen in Flock external battery advertisements. */
    const val FLOCK_BATTERY_COMPANY_ID = 0x09C8

    /** HID Global's Bluetooth SIG company identifier. */
    const val HID_COMPANY_ID = 0x0124

    private val HID_OUIS = setOf("00:06:8E", "00:30:8E", "00:06:33")

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
        return VendorData.OUI[ouiOf(address)]
    }

    fun byCompanyId(id: Int): String? = VendorData.COMPANY[id]

    fun companyIdHex(id: Int): String = "0x" + id.toString(16).uppercase().padStart(4, '0')

    fun isAxon(address: String): Boolean =
        !isRandomAddress(address) && ouiOf(address) == AXON_OUI

    fun isFlock(address: String): Boolean =
        !isRandomAddress(address) && ouiOf(address) == FLOCK_OUI

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

        isFlock(address) ->
            "Flock Safety hardware, by IEEE block."

        companyId == FLOCK_BATTERY_COMPANY_ID || name?.startsWith("Penguin-") == true ->
            "Possible Flock Safety external battery. These relay battery health to the " +
                "camera over Bluetooth, so one nearby suggests a camera - but solar " +
                "Falcon units have no external battery and stay silent."

        isHid(address) || companyId == HID_COMPANY_ID ->
            "HID access control hardware. Signo and iCLASS SE readers advertise " +
                "continuously for mobile credentials."

        else -> null
    }
}
