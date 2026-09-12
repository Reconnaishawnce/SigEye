package com.sigeye.core.wifi

import java.util.Locale

/** How much a match is worth. */
enum class Confidence(val label: String) {
    STRONG("Strong"),
    WEAK("Weak"),
}

data class WifiMatch(
    val reason: String,
    val confidence: Confidence,
    val caveat: String? = null,
)

/**
 * Matching access points against the prefixes surveillance hardware is known to use.
 *
 * The list comes from the flock-you project, which detects Flock Safety cameras from a
 * microcontroller in promiscuous mode. Two things about it matter, and both are reasons to
 * be careful rather than reasons not to try.
 *
 * The first is that twenty-one of its thirty-two prefixes belong to **Liteon**, with the
 * rest split between Silicon Labs, Espressif, Universal Global Scientific and Samsung.
 * Those are component vendors. A Liteon Wi-Fi module is inside an enormous number of
 * ordinary consumer devices, so the prefix on its own is close to worthless as evidence -
 * flock-you gets away with it because it *also* fingerprints a probe request, matching a
 * wildcard SSID against a captured information-element pattern.
 *
 * The second is that Android cannot do that half. Probe requests are management frames
 * from client devices and capturing them needs monitor mode, which needs a chipset, a
 * driver and root. `WifiManager` returns access points and nothing else.
 *
 * So this reports a prefix match as weak by construction and says why, and keeps the
 * strong label for Flock Safety's own IEEE assignment, which is registered to them rather
 * than to whoever made the radio inside.
 *
 * Pure and Android-free.
 */
object WifiSurveillance {

    /** Flock Safety's own IEEE block. A match here names the company, not a supplier. */
    const val FLOCK_OUI = "B4:1E:52"

    fun match(bssid: String, ssid: String?): WifiMatch? {
        val oui = ouiOf(bssid)

        if (oui == FLOCK_OUI) {
            return WifiMatch(
                reason = "Registered to Flock Safety",
                confidence = Confidence.STRONG,
                caveat = "This prefix is assigned to Flock Safety themselves rather than " +
                    "to a component maker, so it names the company directly.",
            )
        }

        COMPONENT_PREFIXES[oui]?.let { vendor ->
            return WifiMatch(
                reason = "Prefix used by Flock hardware ($vendor)",
                confidence = Confidence.WEAK,
                caveat = "This is a component vendor, not Flock. $vendor modules are in " +
                    "a great many ordinary devices, and this prefix appears on the " +
                    "flock-you list only alongside a probe-request fingerprint that no " +
                    "phone can capture. On its own it is a hint to look further, not a " +
                    "finding. Corroborate: is it fixed in place, is it outdoors, does it " +
                    "hide its name, is it still there tomorrow?",
            )
        }

        ssid?.let { name ->
            SSID_HINTS.forEach { (needle, note) ->
                if (name.contains(needle, ignoreCase = true)) {
                    return WifiMatch(
                        reason = "Network name contains \"$needle\"",
                        confidence = Confidence.WEAK,
                        caveat = note,
                    )
                }
            }
        }

        return null
    }

    fun ouiOf(bssid: String): String =
        bssid.uppercase(Locale.US).replace('-', ':').split(':').take(3).joinToString(":")

    /**
     * The flock-you prefix list, resolved to the company each is registered to.
     *
     * Kept with the vendor names attached rather than as a bare set, because the vendor is
     * the reason the match is weak and hiding it would make the result look stronger than
     * it is.
     */
    private val COMPONENT_PREFIXES: Map<String, String> = mapOf(
        "70:C9:4E" to "Liteon", "3C:91:80" to "Liteon", "D8:F3:BC" to "Liteon",
        "80:30:49" to "Liteon", "14:5A:FC" to "Liteon", "74:4C:A1" to "Liteon",
        "9C:2F:9D" to "Liteon", "C0:35:32" to "Liteon", "94:08:53" to "Liteon",
        "E4:AA:EA" to "Liteon", "F4:6A:DD" to "Liteon", "E0:0A:F6" to "Liteon",
        "24:B2:B9" to "Liteon", "00:F4:8D" to "Liteon", "D0:39:57" to "Liteon",
        "E8:D0:FC" to "Liteon", "B8:1E:A4" to "Liteon", "70:08:94" to "Liteon",
        "58:00:E3" to "Liteon", "5C:93:A2" to "Liteon", "64:6E:69" to "Liteon",
        "14:B5:CD" to "Liteon",
        "58:8E:81" to "Silicon Labs", "EC:1B:BD" to "Silicon Labs",
        "90:35:EA" to "Silicon Labs",
        "3C:71:BF" to "Espressif", "A4:CF:12" to "Espressif",
        "08:3A:88" to "Universal Global Scientific",
        "E0:4F:43" to "Universal Global Scientific",
        "48:27:EA" to "Samsung",
        // Two on the list are in no IEEE block this build carries; one of them has the
        // locally-administered bit set, so it was never assigned to anybody.
        "B8:35:32" to "unregistered prefix",
        "82:6B:F2" to "locally-administered address",
    )

    /** Names worth a second look. Weak by definition - anyone can name a network anything. */
    private val SSID_HINTS: List<Pair<String, String>> = listOf(
        "flock" to "Anyone can call a network anything. This is a prompt to look, not " +
            "evidence of what the device is.",
        "axon" to "Axon make body cameras and in-car systems. The name is self-reported.",
        "axis-" to "Axis make surveillance cameras and their access points often keep the " +
            "default name. Self-reported either way.",
        "motorola solutions" to "Motorola Solutions supply police radio and camera " +
            "systems. Self-reported.",
    )
}
