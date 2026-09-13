package com.sigeye.experiments.watchlist

import com.sigeye.core.DeviceBook
import com.sigeye.core.Vendors
import com.sigeye.core.ble.Advert

/**
 * What a rule matches on.
 *
 * The useful ones are deliberately not addresses. Phones rotate their address roughly
 * every fifteen minutes, so an address rule only holds for fixed-function hardware -
 * beacons, tags, body cameras, car kits. [OUI] and [COMPANY_ID] identify the manufacturer
 * regardless, and [SERVICE_DATA] survives even a rotating address because the payload is
 * the device describing itself.
 */
enum class MatchKind(val label: String, val hint: String) {
    OUI(
        "Manufacturer (MAC prefix)",
        "First three bytes of the address, e.g. 00:25:DF. Only works on devices that " +
            "do not randomize their address.",
    ),
    COMPANY_ID(
        "Company ID",
        "Bluetooth SIG number in the manufacturer data, e.g. 0x004C for Apple. Survives " +
            "address randomization.",
    ),
    SERVICE_UUID(
        "Service UUID",
        "A service the device advertises. Matches on any part of the UUID.",
    ),
    SERVICE_DATA(
        "Service data contains",
        "Hex or text inside the service data payload. The most durable signature there " +
            "is, because it is the device describing itself.",
    ),
    NAME_CONTAINS(
        "Name contains",
        "Substring of the advertised name, case-insensitive.",
    ),
    ADDRESS(
        "Exact address",
        "One specific device. Useless for phones, fine for fixed hardware.",
    ),
    LIST(
        "Anything on a list",
        "Every device you have added to one of your lists.",
    ),
    ;
}

data class WatchRule(
    val id: String,
    val label: String,
    val kind: MatchKind,
    val value: String,
    val enabled: Boolean = true,
    /** Ignore matches weaker than this, so a rule can mean "near me" not "in the area". */
    val minRssi: Int = -100,
    /** One arrival is one alert, however many packets it sends. */
    val cooldownSeconds: Int = 180,
    val notify: Boolean = true,
) {
    fun matches(advert: Advert, book: DeviceBook): Boolean {
        if (!enabled) return false
        if (advert.rssi < minRssi) return false
        val needle = value.trim()
        if (needle.isEmpty()) return false

        return when (kind) {
            MatchKind.OUI ->
                !advert.isRandomAddress &&
                    advert.oui.equals(normalizeOui(needle), ignoreCase = true)

            MatchKind.COMPANY_ID ->
                advert.companyId != null && advert.companyId == parseCompanyId(needle)

            MatchKind.SERVICE_UUID ->
                advert.serviceUuids.any { it.contains(needle, ignoreCase = true) }

            MatchKind.SERVICE_DATA -> matchesServiceData(advert, needle)

            MatchKind.NAME_CONTAINS ->
                advert.name?.contains(needle, ignoreCase = true) == true

            MatchKind.ADDRESS ->
                advert.address.equals(needle, ignoreCase = true)

            MatchKind.LIST ->
                book.listsOf(advert.address).contains(needle)
        }
    }

    private fun matchesServiceData(advert: Advert, needle: String): Boolean {
        if (advert.serviceData.isEmpty()) return false
        val hexNeedle = needle.replace(" ", "").replace(":", "").uppercase()
        val looksHex = hexNeedle.length >= 2 &&
            hexNeedle.length % 2 == 0 &&
            hexNeedle.all { it in "0123456789ABCDEF" }

        return advert.serviceData.values.any { bytes ->
            if (looksHex && bytes.toHexCompact().contains(hexNeedle)) return@any true
            // Many vendors put a readable tag in service data, so try text too.
            String(bytes, Charsets.US_ASCII).contains(needle, ignoreCase = true)
        }
    }

    companion object {
        fun normalizeOui(raw: String): String =
            raw.uppercase().replace('-', ':').filter { it.isLetterOrDigit() || it == ':' }
                .split(':').filter { it.isNotEmpty() }.take(3).joinToString(":")

        fun parseCompanyId(raw: String): Int? {
            val clean = raw.trim().removePrefix("0x").removePrefix("0X")
            return clean.toIntOrNull(16) ?: raw.trim().toIntOrNull()
        }

        /**
         * Seeded on first run. Axon's IEEE block is 00:25:DF, verified against the
         * registry - their only one, registered originally as TASER International.
         *
         * It covers docks, TASERs and fleet gear as well as body cameras, and a hit says
         * nothing about whether anything is recording. The rule label says so, because
         * that caveat needs to travel with the alert rather than live in a README.
         */
        fun defaults(): List<WatchRule> = listOf(
            WatchRule(
                id = "axon-oui",
                label = "Axon hardware",
                kind = MatchKind.OUI,
                value = Vendors.AXON_OUI,
                minRssi = -95,
                cooldownSeconds = 300,
            ),
            WatchRule(
                id = "hid-company",
                label = "HID access reader",
                kind = MatchKind.COMPANY_ID,
                value = "0x0124",
                enabled = false,
                minRssi = -85,
                cooldownSeconds = 600,
            ),
            WatchRule(
                id = "hid-oui",
                label = "HID hardware",
                kind = MatchKind.OUI,
                value = "00:06:8E",
                enabled = false,
                minRssi = -85,
                cooldownSeconds = 600,
            ),
        )
    }
}

fun ByteArray.toHexCompact(): String =
    joinToString("") { String.format("%02X", it) }

/** One rule firing. Kept in memory so the screen can show what has been seen. */
data class WatchHit(
    val ruleId: String,
    val ruleLabel: String,
    val address: String,
    val displayName: String,
    val rssi: Int,
    val atMs: Long,
) {
    val vendor: String? get() = Vendors.byAddress(address)
}
