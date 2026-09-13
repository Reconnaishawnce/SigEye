package com.sigeye.core.ble

import com.sigeye.core.Vendors
import java.util.Locale

/** One thing that can be said about a device, and how firm it is. */
data class Clue(val label: String, val detail: String, val firm: Boolean)

/**
 * Everything that can honestly be said about an unfamiliar advertiser.
 *
 * There is no database that turns a Bluetooth name into a product. Names are free text the
 * maker typed, nobody registers them, and the crowd-sourced collections that do exist
 * (WiGLE and friends) index Wi-Fi SSIDs and locations rather than BLE names. So instead of
 * guessing at the name, this assembles the clues that *are* backed by registries - the OUI,
 * the company ID, the appearance, the service UUIDs - and says plainly where each one came
 * from, including when the honest answer is that the address is randomized and tells you
 * nothing at all.
 *
 * Pure and Android-free.
 */
object Identity {

    fun clues(
        address: String,
        name: String?,
        companyId: Int?,
        appearance: Int?,
        serviceUuids: List<String> = emptyList(),
    ): List<Clue> {
        val out = mutableListOf<Clue>()

        if (Vendors.isRandomAddress(address)) {
            out += Clue(
                "Address",
                "Randomized. It is reassigned roughly every fifteen minutes and identifies " +
                    "no manufacturer, so nothing here can be learned from it.",
                firm = true,
            )
        } else {
            val vendor = Vendors.byAddress(address)
            out += if (vendor != null) {
                Clue(
                    "Registered to",
                    "$vendor, from the IEEE assignment for ${Vendors.ouiOf(address)}. This " +
                        "is who made the radio, which is often a chip vendor rather than " +
                        "whoever put the product in a box.",
                    firm = true,
                )
            } else {
                Clue(
                    "Address",
                    "Fixed, but ${Vendors.ouiOf(address)} is not in the IEEE list this build " +
                        "carries.",
                    firm = false,
                )
            }
        }

        companyId?.let { id ->
            val vendor = Vendors.byCompanyId(id)
            out += Clue(
                "Declares itself as",
                (vendor ?: "an unlisted member") + ", company ID ${Vendors.companyIdHex(id)} " +
                    "in its manufacturer data. Self-reported, and cheap to copy.",
                firm = vendor != null,
            )
        }

        Appearance.describe(appearance)?.let {
            out += Clue(
                "Says it is a",
                "$it, from the GAP Appearance field. A registry value, so the category is " +
                    "meaningful - though nothing stops a device declaring the wrong one.",
                firm = true,
            )
        }

        serviceUuids.mapNotNull { short(it) }.distinct().forEach { uuid ->
            AssignedNumbers.MEMBER_SERVICES[uuid]?.let {
                out += Clue(
                    "Uses a service owned by",
                    "$it (UUID ${hex(uuid)}). Member UUIDs are allocated to one company, so " +
                        "this names the ecosystem even when nothing else does.",
                    firm = true,
                )
            }
            AssignedNumbers.SIG_SERVICES[uuid]?.let {
                out += Clue("Offers", "$it (${hex(uuid)}).", firm = true)
            }
        }

        derivedName(address, name)?.let { out += it }

        if (out.none { it.firm }) {
            out += Clue(
                "Best guess",
                "Nothing in this advertisement is backed by a registry. Watching how it " +
                    "behaves - when it appears, whether it moves, how often it advertises - " +
                    "will say more than the payload does.",
                firm = false,
            )
        }
        return out
    }

    /**
     * Catches the very common case of a name that is just part of the device's own MAC.
     *
     * Worth calling out twice over: it means the name is a factory default rather than
     * anything descriptive, and the near-miss form is itself a fingerprint. Espressif parts,
     * for instance, derive the Bluetooth address from the base Wi-Fi address by adding a
     * small offset, so a name ending "bab2c4" on a device advertising ...BA:B2:C6 is the
     * same chip naming itself after its other radio.
     */
    private fun derivedName(address: String, name: String?): Clue? {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.length < 4) return null
        val tail = trimmed.takeLastWhile { it.isDigit() || it in "abcdefABCDEF" }
        if (tail.length < 4 || tail.length % 2 != 0) return null

        val mac = address.replace(":", "").uppercase(Locale.US)
        val suffix = tail.uppercase(Locale.US)
        if (mac.length < suffix.length) return null
        val macTail = mac.takeLast(suffix.length)

        if (macTail == suffix) {
            return Clue(
                "Name",
                "\"$trimmed\" ends with this device's own address bytes. That is a factory " +
                    "default, not a chosen name, so it says nothing about what the thing is.",
                firm = true,
            )
        }

        // Same bytes but the last one, and only just off: the two radios in one chip.
        val headMatches = macTail.dropLast(2) == suffix.dropLast(2)
        val delta = runCatching {
            kotlin.math.abs(macTail.takeLast(2).toInt(16) - suffix.takeLast(2).toInt(16))
        }.getOrNull() ?: return null
        if (headMatches && delta in 1..8) {
            return Clue(
                "Name",
                "\"$trimmed\" ends with an address $delta from this one. Chips that carry " +
                    "both Wi-Fi and Bluetooth commonly derive one address from the other by " +
                    "a small offset and name themselves after the first - Espressif parts do " +
                    "exactly this. So it is a factory default, and the same module is " +
                    "probably also on Wi-Fi nearby.",
                firm = true,
            )
        }
        return null
    }

    /** 16-bit form of a service UUID, or null if it is a genuinely custom 128-bit one. */
    fun short(uuid: String): Int? {
        val text = uuid.lowercase(Locale.US)
        if (!text.endsWith("-0000-1000-8000-00805f9b34fb")) return null
        val head = text.substringBefore('-')
        if (head.length != 8 || !head.startsWith("0000")) return null
        return head.drop(4).toIntOrNull(16)
    }

    private fun hex(uuid16: Int) = String.format(Locale.US, "0x%04X", uuid16)
}
