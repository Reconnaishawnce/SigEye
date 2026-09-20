package com.sigeye.core.analysis.surveillance

import com.sigeye.core.Vendors
import com.sigeye.core.ble.Advert
import java.util.Locale

/**
 * How much a match is worth.
 *
 * The bands are about the kind of evidence, not about a score. [CONFIRMED] means the
 * device described itself in a way nothing else does. [LIKELY] means the structure fits
 * and one supporting detail is missing. [POSSIBLE] means somebody would have to go and
 * look.
 */
enum class Certainty(val label: String) {
    CONFIRMED("Confirmed"),
    LIKELY("Likely"),
    POSSIBLE("Possible"),
}

/**
 * A piece of surveillance hardware the phone believes it can hear.
 *
 * [why] is the evidence in one line, because an alert that does not say what set it off is
 * a thing people learn to dismiss. [serial] is filled in when the device broadcasts a
 * stable identifier of its own.
 */
data class Sighting(
    val product: String,
    val operator: String,
    val why: String,
    val certainty: Certainty,
    val serial: String? = null,
    val caveat: String? = null,
)

/**
 * Flock Safety's Falcon cameras and the batteries that power them.
 *
 * Falcon is a pole-mounted automatic license plate reader. The camera itself talks to Flock
 * over cellular and is close to silent on the bands a phone can hear, but almost every
 * installation has an external battery pack bolted to the same pole, and that battery
 * reports its health to the camera over Bluetooth Low Energy. The battery is the loud part.
 *
 * The signatures below come from ryanohoro's teardown, which decoded the QR labels on units
 * sold secondhand and captured the advertising traffic:
 * https://www.ryanohoro.com/post/spotting-flock-safety-s-falcon-cameras
 *
 * **The battery address rotates and its serial number does not.** That is the interesting
 * part, and it is why this is a file rather than a row in a vendor table. The advertising
 * address is random, so the usual way of recognizing fixed hardware - a manufacturer prefix
 * on a stable MAC - finds nothing at all. Meanwhile the payload carries a printed serial in
 * plain ASCII, unchanged across every rotation, which identifies one physical battery on
 * one physical pole indefinitely. Careful about the MAC and careless about the payload is
 * the same mistake this whole app is about, made this time by the people selling
 * surveillance rather than by the people carrying phones.
 *
 * Pure and Android-free.
 */
object Flock {

    /**
     * XUNTONG, in the Bluetooth SIG company list.
     *
     * Not Flock's own number. The battery pack is somebody else's hardware with Flock's
     * name on the outside, which is why a match on the company alone is not enough: the
     * same identifier turns up on tyre pressure sensors.
     */
    const val XUNTONG = 0x09C8

    /** The name a hardwired unit still advertises. FS for Flock Safety. */
    const val EXTERNAL_BATTERY_NAME = "FS Ext Battery"

    /** Serials seen so far run to sixteen characters. Eight is a floor, not a spec. */
    const val MIN_SERIAL = 8

    /** Six bytes of address echo, then four bytes of something, then the serial. */
    const val SERIAL_OFFSET = 10

    private val OLD_NAME = Regex("^Penguin-\\d{10}$")

    /**
     * What the firmware update of March 2025 left behind.
     *
     * The old name gave the game away, so it was removed, and the batteries now advertise
     * ten bare digits. On its own that is nothing, because plenty of devices advertise a
     * number, which is exactly why it only counts next to the company identifier.
     */
    private val BARE_NAME = Regex("^\\d{10}$")

    /** "Flock-" and a piece of the radio's own MAC, seen on installer Wi-Fi. */
    private val SSID = Regex("^Flock-([0-9A-Fa-f]{4,12})$")

    /**
     * The serial the battery prints on itself, if this advertisement carries one.
     *
     * Three things have to line up, and the middle one is what makes this trustworthy
     * rather than a guess about a company identifier: the payload opens by repeating the
     * device's own advertising address. A tyre sensor sharing the XUNTONG number does not
     * do that, and nothing else has a reason to.
     */
    fun serialOf(advert: Advert): String? =
        serialOf(advert.address, advert.companyId, advert.manufacturerData)

    fun serialOf(address: String, companyId: Int?, data: ByteArray?): String? {
        if (companyId != XUNTONG) return null
        if (data == null || data.size < SERIAL_OFFSET + MIN_SERIAL) return null
        if (!echoesOwnAddress(address, data)) return null
        return readableTail(data)
    }

    /** Whether the payload opens with the six bytes of the address it arrived under. */
    fun echoesOwnAddress(address: String, data: ByteArray): Boolean {
        val own = addressBytes(address) ?: return false
        if (data.size < own.size) return false
        return own.indices.all { data[it] == own[it] }
    }

    private fun readableTail(data: ByteArray): String? {
        val tail = data.copyOfRange(SERIAL_OFFSET, data.size)
        val text = String(tail, Charsets.US_ASCII).trim { it <= ' ' }
        if (text.length < MIN_SERIAL) return null
        // A serial is letters and digits. Anything else means these bytes are not text, and
        // reading them as text would put mojibake on the screen and call it evidence.
        if (!text.all { it.isLetterOrDigit() }) return null
        return text
    }

    private fun addressBytes(address: String): ByteArray? {
        val parts = address.split(':', '-')
        if (parts.size != 6) return null
        val bytes = ByteArray(6)
        parts.forEachIndexed { index, part ->
            val value = part.toIntOrNull(16) ?: return null
            bytes[index] = value.toByte()
        }
        return bytes
    }

    // ------------------------------------------------------------------ the verdict

    fun fromBle(advert: Advert): Sighting? =
        fromBle(advert.address, advert.name, advert.companyId, advert.manufacturerData)

    fun fromBle(address: String, rawName: String?, companyId: Int?, data: ByteArray?): Sighting? {
        val name = rawName?.trim()

        serialOf(address, companyId, data)?.let { serial ->
            return Sighting(
                product = "Flock Safety camera battery",
                operator = "Flock Safety",
                why = "Broadcasts its own serial number, $serial",
                certainty = Certainty.CONFIRMED,
                serial = serial,
                caveat = "This battery powers a Falcon license plate reader, so the camera " +
                    "is on the same pole. The serial does not change when the Bluetooth " +
                    "address does, so the same battery will be recognized again days later.",
            )
        }

        if (name != null && name.equals(EXTERNAL_BATTERY_NAME, ignoreCase = true)) {
            return Sighting(
                product = "Flock Safety camera battery",
                operator = "Flock Safety",
                why = "Advertises the name \"$EXTERNAL_BATTERY_NAME\"",
                certainty = Certainty.CONFIRMED,
                caveat = "FS is Flock Safety. Look up for a pole-mounted camera.",
            )
        }

        if (name != null && OLD_NAME.matches(name)) {
            return Sighting(
                product = "Flock Safety camera battery",
                operator = "Flock Safety",
                why = "Advertises as \"$name\", the battery name used before 2025",
                certainty = Certainty.CONFIRMED,
                caveat = "Firmware from before March 2025, which still gives its own name " +
                    "away. Newer units advertise the digits without the word.",
            )
        }

        if (companyId == XUNTONG && name != null && BARE_NAME.matches(name)) {
            return Sighting(
                product = "Flock Safety camera battery",
                operator = "Flock Safety",
                why = "Ten bare digits from a XUNTONG device, which is the 2025 battery name",
                certainty = Certainty.LIKELY,
                caveat = "Flock removed the word Penguin from this name in March 2025. The " +
                    "digits and the company identifier together are still distinctive. " +
                    "Either one alone would not be.",
            )
        }

        if (companyId == XUNTONG) {
            return Sighting(
                product = "XUNTONG device",
                operator = "unknown",
                why = "Manufacturer data from XUNTONG (0x09C8)",
                certainty = Certainty.POSSIBLE,
                caveat = "Flock camera batteries use this company identifier and so do tyre " +
                    "pressure sensors. Without the serial in the payload there is no way to " +
                    "tell them apart, so treat a hit that drives past as a tyre.",
            )
        }

        return null
    }

    fun fromWifi(bssid: String, ssid: String?): Sighting? {
        val name = ssid?.trim().orEmpty()
        val match = SSID.find(name)

        if (match != null) {
            val fragment = match.groupValues[1]
            val bare = bssid.uppercase(Locale.US).replace(":", "").replace("-", "")
            val corroborated = bare.contains(fragment.uppercase(Locale.US))
            return Sighting(
                product = "Flock Safety camera, in setup mode",
                operator = "Flock Safety",
                why = if (corroborated) {
                    "Network named \"$name\", and $fragment is part of this radio's own address"
                } else {
                    "Network named \"$name\", which is Flock's installer naming scheme"
                },
                certainty = if (corroborated) Certainty.CONFIRMED else Certainty.LIKELY,
                caveat = "Falcon cameras do not normally use Wi-Fi in service. The access " +
                    "point comes up for installation and field maintenance, so this one was " +
                    "probably put up recently or is being worked on.",
            )
        }

        if (name.contains("flock", ignoreCase = true)) {
            return Sighting(
                product = "possibly Flock Safety",
                operator = "unknown",
                why = "Network name contains \"flock\"",
                certainty = Certainty.POSSIBLE,
                caveat = "Anyone can name a network anything. This is a reason to look up, " +
                    "not a finding.",
            )
        }

        return null
    }
}

/**
 * One place that answers "is this surveillance hardware", for either radio.
 *
 * What counts as evidence, in order. A device broadcasting a structure nothing else
 * broadcasts has identified itself, and that is the end of it. An IEEE block registered to
 * the company that sells the product is a real claim about who built the hardware. A
 * network name somebody typed is a reason to go and look, and is labeled as one.
 *
 * What does not count, and used to: a MAC prefix belonging to the company that made the
 * radio module inside the product. Most of those blocks are Liteon, Silicon Labs, Espressif
 * and Universal Global Scientific, whose parts sit inside an enormous number of ordinary
 * laptops and televisions and smart plugs. Matching on them fires constantly, and a warning
 * that cries wolf is worse than no warning, because it teaches people to ignore the one
 * that matters. Tools that get away with it also fingerprint probe requests, which needs
 * monitor mode, a chipset, a driver and root. A phone cannot do that half.
 *
 * Pure and Android-free.
 */
object Surveillance {

    /**
     * IEEE blocks registered to the company that sells the product, not to a parts maker.
     *
     * Short on purpose. A block assigned to Axon names Axon; a block assigned to whoever
     * made the Wi-Fi module names nobody in particular.
     */
    private val REGISTERED: Map<String, String> = mapOf(
        Vendors.AXON_OUI to "Axon Enterprise",
    )

    fun fromBle(advert: Advert): Sighting? =
        fromBle(advert.address, advert.name, advert.companyId, advert.manufacturerData)

    fun fromBle(address: String, name: String?, companyId: Int?, data: ByteArray?): Sighting? {
        Flock.fromBle(address, name, companyId, data)?.let { return it }

        // A random address carries no manufacturer prefix. The bytes after the type bits
        // are noise, so reading a vendor out of them would be inventing one.
        if (!Vendors.isRandomAddress(address)) {
            val oui = Vendors.ouiOf(address)
            REGISTERED[oui]?.let { company ->
                return Sighting(
                    product = "$company hardware",
                    operator = company,
                    why = "Address starts with $oui, an IEEE block registered to $company",
                    certainty = Certainty.CONFIRMED,
                    caveat = AXON_CAVEAT,
                )
            }
        }

        return null
    }

    fun fromWifi(bssid: String, ssid: String?): Sighting? {
        Flock.fromWifi(bssid, ssid)?.let { return it }

        val oui = ouiOf(bssid)
        REGISTERED[oui]?.let { company ->
            return Sighting(
                product = "$company hardware",
                operator = company,
                why = "Address starts with $oui, an IEEE block registered to $company",
                certainty = Certainty.CONFIRMED,
                caveat = AXON_CAVEAT,
            )
        }

        ssid?.let { name ->
            SSID_HINTS.forEach { (needle, note) ->
                if (name.contains(needle, ignoreCase = true)) {
                    return Sighting(
                        product = "possibly $needle hardware",
                        operator = "unknown",
                        why = "Network name contains \"$needle\"",
                        certainty = Certainty.POSSIBLE,
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
     * Why a quiet result does not mean there is no Axon gear in the room.
     *
     * In October 2023, after researchers showed at DEF CON 31 that police equipment could
     * be located and its activity inferred from Bluetooth alone, Axon told Engadget it was
     * working on "rotation of unique BLE device addresses" and "removing the need for
     * including serial numbers in Bluetooth broadcasts". A vendor doing that is a vendor
     * taking the problem seriously, and it also means the prefix above now only finds
     * hardware old enough, or un-updated enough, to still use a fixed address.
     */
    const val AXON_CAVEAT =
        "That IEEE block covers body cameras, docks, TASERs and fleet gear, and a hit says " +
            "nothing about whether anything is recording. Axon also said in 2023 that it " +
            "was moving to rotating Bluetooth addresses and dropping serial numbers from " +
            "its broadcasts, so newer equipment will not show up this way at all. Hearing " +
            "nothing here is not evidence that nothing is there."

    /** Names worth a second look. Weak by definition - anyone can name a network anything. */
    private val SSID_HINTS: List<Pair<String, String>> = listOf(
        "axon" to "Axon make body cameras and in-car systems. The name is self-reported.",
        "axis-" to "Axis make surveillance cameras and their access points often keep the " +
            "default name. Self-reported either way.",
        "motorola solutions" to "Motorola Solutions supply police radio and camera " +
            "systems. Self-reported.",
    )
}
