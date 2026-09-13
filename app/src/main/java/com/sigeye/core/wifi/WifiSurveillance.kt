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
 * Matching access points against prefixes that surveillance hardware is known to use.
 *
 * This used to carry the flock-you prefix list, and it has been taken out. Twenty-one of
 * its thirty-two prefixes belong to Liteon, and the rest to Silicon Labs, Espressif,
 * Universal Global Scientific and Samsung. Those are component vendors whose Wi-Fi modules
 * sit inside an enormous number of ordinary devices. flock-you gets away with using them
 * because it also fingerprints a probe request, and a phone cannot do that half: probe
 * requests are management frames from client devices, and capturing them needs monitor
 * mode, a chipset, a driver and root.
 *
 * So the match was a vendor prefix and nothing else, which meant it fired on laptops and
 * televisions and smart plugs. A warning that cries wolf is worse than no warning, because
 * it teaches people to ignore the one that matters. It is gone rather than demoted.
 *
 * What is left is what a phone can actually stand behind: an IEEE block registered to the
 * company that makes the product, and a network name that somebody typed. The first is a
 * real claim about hardware. The second is a prompt to go and look, and says so.
 *
 * Pure and Android-free.
 */
object WifiSurveillance {

    /**
     * IEEE blocks registered to the company that sells the product.
     *
     * The distinction that matters: a block assigned to Axon names Axon, whereas a block
     * assigned to the company that made the radio module names nobody in particular.
     */
    private val REGISTERED: Map<String, String> = mapOf(
        "00:25:DF" to "Axon Enterprise",
    )

    fun match(bssid: String, ssid: String?): WifiMatch? {
        val oui = ouiOf(bssid)

        REGISTERED[oui]?.let { company ->
            return WifiMatch(
                reason = "Registered to $company",
                confidence = Confidence.STRONG,
                caveat = "This prefix is assigned to $company themselves rather than to a " +
                    "component maker, so it names the company directly. It says nothing " +
                    "about which of their products this is, or what it is doing.",
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

    /** Names worth a second look. Weak by definition - anyone can name a network anything. */
    private val SSID_HINTS: List<Pair<String, String>> = listOf(
        "axon" to "Axon make body cameras and in-car systems. The name is self-reported.",
        "axis-" to "Axis make surveillance cameras and their access points often keep the " +
            "default name. Self-reported either way.",
        "motorola solutions" to "Motorola Solutions supply police radio and camera " +
            "systems. Self-reported.",
    )
}
