package com.sigeye.experiments.watchlist

import com.sigeye.core.DeviceBook
import com.sigeye.core.Vendors
import com.sigeye.core.analysis.surveillance.Certainty
import com.sigeye.core.analysis.surveillance.Surveillance
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
    SURVEILLANCE(
        "Known surveillance hardware",
        "Anything SigEye can recognize - Flock camera batteries, Axon gear. The value is " +
            "the weakest match worth an alert: CONFIRMED, LIKELY or POSSIBLE.",
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

            MatchKind.SURVEILLANCE -> {
                // One rule for every signature the app knows, rather than a rule per
                // company identifier that somebody has to come back and edit each time a
                // vendor changes a name. Flock did exactly that in March 2025.
                val sighting = Surveillance.fromBle(advert)
                sighting != null && sighting.certainty.ordinal <= floorOf(needle).ordinal
            }
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
        /** The weakest certainty an alert is worth. Anything unreadable means confirmed only. */
        fun floorOf(raw: String): Certainty =
            runCatching { Certainty.valueOf(raw.trim().uppercase()) }
                .getOrDefault(Certainty.CONFIRMED)

        fun normalizeOui(raw: String): String =
            raw.uppercase().replace('-', ':').filter { it.isLetterOrDigit() || it == ':' }
                .split(':').filter { it.isNotEmpty() }.take(3).joinToString(":")

        fun parseCompanyId(raw: String): Int? {
            val clean = raw.trim().removePrefix("0x").removePrefix("0X")
            return clean.toIntOrNull(16) ?: raw.trim().toIntOrNull()
        }

        /**
         * What a new install starts with, and what an existing one gains on upgrade.
         *
         * Ids are permanent. [WatchStore] remembers which of these it has already offered,
         * so a rule deleted on purpose stays deleted while a genuinely new one still
         * arrives. Adding to this list is therefore safe; renaming an id is not.
         */
        fun defaults(): List<WatchRule> = listOf(
            WatchRule(
                id = "surveillance-known",
                label = "Surveillance hardware",
                kind = MatchKind.SURVEILLANCE,
                value = Certainty.LIKELY.name,
                // Quiet enough to mean "on this street" rather than "in this district",
                // since the point of the alert is to be able to look up and see the thing.
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
        /**
         * The Axon rule that shipped before [MatchKind.SURVEILLANCE] existed.
         *
         * Superseded rather than deleted: the new rule catches the same block and explains
         * itself better. [WatchStore] drops this one only if it is untouched, because a
         * copy somebody has edited is theirs.
         */
        val RETIRED_AXON_OUI = WatchRule(
            id = "axon-oui",
            label = "Axon hardware",
            kind = MatchKind.OUI,
            value = Vendors.AXON_OUI,
            minRssi = -95,
            cooldownSeconds = 300,
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
