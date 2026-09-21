package com.sigeye.core.ble

/** What a device appears to be, and everything it gave away while saying so. */
data class Product(
    /** The most specific honest name. "Chromecast or Google speaker", not "Google device". */
    val name: String,
    val maker: String?,
    val kind: DeviceKind,
    val confidence: KindConfidence,
    /** Why, in one line. Nobody should have to take this on trust. */
    val because: String,
    /** Everything else worth reading, as label to value. */
    val details: List<Pair<String, String>> = emptyList(),
)

/**
 * Naming the thing rather than its category.
 *
 * [DeviceKinds] answers phone, watch or earbuds, which is what a follow needs. This answers
 * "which of these is the Chromecast", which is what somebody looking at a list of forty
 * devices in their own house needs. They are different questions and the second one has a
 * lot more evidence available than the first was using.
 *
 * Three kinds of evidence, and they are not equally good:
 *
 *  - **A service the Bluetooth SIG assigned to one company for one product.** Google Cast
 *    is 0xFEA0 and nothing else uses it, so a device sending it is a Cast device. This is
 *    the strongest thing available short of asking.
 *  - **A field a protocol defines for saying what you are.** Microsoft's Connected Devices
 *    beacon carries a device type byte. It is self-reported, so what it supports is
 *    "it says it is a laptop", which is worth exactly that and no more.
 *  - **Which messages a device sends.** Apple's are the interesting case and the hardest,
 *    because Apple deliberately made a watch and a phone look alike. Where they cannot be
 *    told apart this says so rather than picking the likelier one.
 *
 * What is deliberately absent is a table of Apple model numbers. Proximity Pairing carries
 * one and it identifies the exact model of AirPods; the tables circulating for it are
 * copied between projects without anybody saying which hardware they checked against. The
 * number is surfaced so it can be looked up, and it is not named from memory.
 *
 * Pure and Android-free.
 */
object Products {

    // Services the SIG lists against one company. Verified in AssignedNumbers, not recalled.
    const val GOOGLE_CAST = 0xFEA0
    const val FAST_PAIR = 0xFE2C
    const val GOOGLE_NEARBY = 0xFEF3
    const val TILE_A = 0xFEED
    const val TILE_B = 0xFEEC
    const val SAMSUNG = 0xFD5A
    const val EXPOSURE_NOTIFICATION = 0xFD6F

    const val MICROSOFT = 0x0006

    // Apple Continuity message types, from Continuity.
    private const val PROXIMITY_PAIRING = 0x07
    private const val AIRPLAY_TARGET = 0x09
    private const val TETHERING_SOURCE = 0x0E
    private const val NEARBY_INFO = 0x10
    private const val FIND_MY = 0x12
    private const val HANDOFF = 0x0C
    private const val HEY_SIRI = 0x08

    fun of(advert: Advert): Product {
        val services = advert.serviceUuids.mapNotNull { shortUuid(it) }.toSet()
        val serviceData = advert.serviceData.entries
            .mapNotNull { (uuid, bytes) -> shortUuid(uuid)?.let { it to bytes } }
            .toMap()

        google(services, serviceData)?.let { return it }
        tile(services)?.let { return it }
        microsoft(advert)?.let { return it }
        apple(advert)?.let { return it }
        samsung(services, serviceData)?.let { return it }
        exposure(services)?.let { return it }

        // Nothing named it, so fall back to the category guess and say where that came from.
        val guess = DeviceKinds.of(advert)
        return Product(
            name = guess.kind.label,
            maker = advert.vendor,
            kind = guess.kind,
            confidence = guess.confidence,
            because = guess.because,
            details = common(advert),
        )
    }

    // ------------------------------------------------------------------ Google

    private fun google(services: Set<Int>, data: Map<Int, ByteArray>): Product? {
        if (GOOGLE_CAST in services) {
            return Product(
                name = "Chromecast or Google speaker",
                maker = "Google",
                kind = DeviceKind.AUDIO,
                confidence = KindConfidence.DECLARED,
                because = "Advertises Google Cast (0xFEA0), which only Cast devices use.",
                details = listOfNotNull(
                    data[GOOGLE_CAST]?.let { "Cast data" to hex(it) },
                ),
            )
        }

        if (FAST_PAIR in services) {
            val payload = data[FAST_PAIR]
            // Three bytes is a model number, broadcast while the thing is waiting to pair.
            // Anything longer is the filter a paired accessory sends instead, which says
            // nothing about the model.
            val model = payload?.takeIf { it.size == 3 }?.let { hex(it) }
            return Product(
                name = if (model != null) {
                    "Fast Pair accessory, waiting to pair"
                } else {
                    "Fast Pair accessory, already paired"
                },
                maker = "Google Fast Pair",
                kind = DeviceKind.EARBUDS,
                confidence = KindConfidence.INFERRED,
                because = if (model != null) {
                    "Broadcasting a Fast Pair model number, which it only does in pairing mode."
                } else {
                    "Sending a Fast Pair account key, which is what a paired accessory sends."
                },
                details = listOfNotNull(
                    model?.let { "Fast Pair model" to "0x$it" },
                    model?.let { "Look up" to "Google's Fast Pair registry names this model." },
                    payload?.let { "Service data" to hex(it) },
                ),
            )
        }

        if (GOOGLE_NEARBY in services) {
            return Product(
                name = "Android device",
                maker = "Google",
                kind = DeviceKind.PHONE,
                confidence = KindConfidence.INFERRED,
                because = "Advertises Google's nearby-device service (0xFEF3).",
                details = listOfNotNull(data[GOOGLE_NEARBY]?.let { "Service data" to hex(it) }),
            )
        }
        return null
    }

    // ------------------------------------------------------------------ Tile

    private fun tile(services: Set<Int>): Product? =
        if (TILE_A in services || TILE_B in services) {
            Product(
                name = "Tile tracker",
                maker = "Tile",
                kind = DeviceKind.TAG,
                confidence = KindConfidence.DECLARED,
                because = "Advertises a service the SIG assigned to Tile.",
            )
        } else {
            null
        }

    // ------------------------------------------------------------------ Microsoft

    /**
     * Microsoft's Connected Devices beacon, which carries a device type byte.
     *
     * Self-reported, so the wording stays self-reported: it says what it is. Types outside
     * the ones named here are shown as a number rather than guessed at.
     */
    private fun microsoft(advert: Advert): Product? {
        if (advert.companyId != MICROSOFT) return null
        val data = advert.manufacturerData ?: return null
        if (data.size < 2) return null

        val type = data[1].toInt() and 0x1F
        val says = MICROSOFT_TYPES[type]
        val kind = when (type) {
            6, 11 -> DeviceKind.PHONE
            7, 16 -> DeviceKind.COMPUTER
            9, 15, 12, 13 -> DeviceKind.COMPUTER
            1 -> DeviceKind.AUDIO
            else -> DeviceKind.UNKNOWN
        }

        return Product(
            name = says?.let { "Says it is $it" } ?: "Microsoft nearby device",
            maker = "Microsoft",
            kind = kind,
            confidence = KindConfidence.INFERRED,
            because = "Sending a Microsoft Connected Devices beacon, which carries a " +
                "device type. The device chose what to put there.",
            details = listOf(
                "Device type" to (says ?: "type $type"),
                "Beacon" to hex(data),
            ),
        )
    }

    /** From Microsoft's Connected Devices Platform beacon. Unlisted values stay numbers. */
    private val MICROSOFT_TYPES = mapOf(
        1 to "an Xbox One",
        6 to "an iPhone",
        7 to "an iPad",
        8 to "an Android device",
        9 to "a Windows desktop",
        11 to "a Windows phone",
        12 to "a Linux machine",
        14 to "a Surface Hub",
        15 to "a Windows laptop",
        16 to "a Windows tablet",
    )

    // ------------------------------------------------------------------ Apple

    /**
     * Apple, where the whole point of the design is that these look alike.
     *
     * A watch and a phone in a pocket both send Nearby Info under the same company with the
     * body randomized, and from one packet there is often nothing between them. What can be
     * said is said; where it cannot, this returns an Apple device and lists the messages so
     * somebody can see the same ambiguity it saw.
     */
    private fun apple(advert: Advert): Product? {
        val messages = Continuity.messages(advert.companyId, advert.manufacturerData)
        if (messages.isEmpty()) return null

        val types = messages.keys
        val detail = mutableListOf<Pair<String, String>>()
        detail += "Apple messages" to Continuity.describe(types)

        if (PROXIMITY_PAIRING in types) {
            val body = messages[PROXIMITY_PAIRING]
            // Bytes 1 and 2 are the model. Not named here: see the note on this file.
            val model = body?.takeIf { it.size >= 3 }
                ?.let { "%02X%02X".format(it[1], it[2]) }
            model?.let {
                detail += "Model number" to "0x$it"
                detail += "Naming it" to "This number identifies the exact model. SigEye " +
                    "does not carry a table for it rather than copy an unverified one."
            }
            body?.let { detail += "Pairing data" to hex(it) }

            return Product(
                name = "AirPods or Beats",
                maker = "Apple",
                kind = DeviceKind.EARBUDS,
                confidence = KindConfidence.DECLARED,
                because = "Sending Apple Proximity Pairing, which only Apple audio " +
                    "accessories use.",
                details = detail + common(advert),
            )
        }

        if (FIND_MY in types && NEARBY_INFO !in types) {
            messages[FIND_MY]?.let { detail += "Find My data" to hex(it) }
            return Product(
                name = "AirTag or Find My tag",
                maker = "Apple Find My",
                kind = DeviceKind.TAG,
                confidence = KindConfidence.INFERRED,
                because = "Sending Find My and nothing a phone or watch sends. A phone " +
                    "relaying Find My sends Nearby Info alongside it; this does not.",
                details = detail + common(advert),
            )
        }

        if (AIRPLAY_TARGET in types && NEARBY_INFO !in types) {
            return Product(
                name = "Apple TV or HomePod",
                maker = "Apple",
                kind = DeviceKind.AUDIO,
                confidence = KindConfidence.INFERRED,
                because = "Advertising as an AirPlay target without the messages a " +
                    "pocket device sends.",
                details = detail + common(advert),
            )
        }

        // Personal Hotspot. A watch does not offer one, so this is a phone or a cellular
        // iPad rather than the pocket-Apple-device ambiguity below.
        if (TETHERING_SOURCE in types) {
            return Product(
                name = "iPhone or cellular iPad",
                maker = "Apple",
                kind = DeviceKind.PHONE,
                confidence = KindConfidence.INFERRED,
                because = "Offering Personal Hotspot, which only a device with its own " +
                    "cellular connection does. A watch does not.",
                details = detail + common(advert),
            )
        }

        val handheld = NEARBY_INFO in types || HANDOFF in types || HEY_SIRI in types
        return Product(
            name = if (handheld) "Apple device, kind unclear" else "Apple device",
            maker = "Apple",
            kind = DeviceKind.APPLE,
            confidence = KindConfidence.INFERRED,
            because = if (handheld) {
                "An iPhone, iPad, Watch or Mac. Apple sends the same messages from all of " +
                    "them with the identifying parts randomized, so from the air there is " +
                    "nothing here that separates them."
            } else {
                "Apple manufacturer data, with no message that names a product."
            },
            details = detail + common(advert),
        )
    }

    // ------------------------------------------------------------------ the rest

    private fun samsung(services: Set<Int>, data: Map<Int, ByteArray>): Product? =
        if (SAMSUNG in services) {
            Product(
                name = "Samsung device",
                maker = "Samsung",
                kind = DeviceKind.UNKNOWN,
                confidence = KindConfidence.INFERRED,
                because = "Advertises a service the SIG assigned to Samsung.",
                details = listOfNotNull(data[SAMSUNG]?.let { "Service data" to hex(it) }),
            )
        } else {
            null
        }

    private fun exposure(services: Set<Int>): Product? =
        if (EXPOSURE_NOTIFICATION in services) {
            Product(
                name = "Phone running exposure notifications",
                maker = null,
                kind = DeviceKind.PHONE,
                confidence = KindConfidence.INFERRED,
                because = "Advertising the Google and Apple exposure notification service, " +
                    "which only runs on a phone.",
            )
        } else {
            null
        }

    /** Everything a packet gives away regardless of who made it. */
    fun common(advert: Advert): List<Pair<String, String>> = listOfNotNull(
        advert.name?.takeIf { it.isNotBlank() }?.let { "Broadcast name" to it },
        advert.txPower?.let { "Transmit power" to "$it dBm" },
        advert.appearanceLabel?.let { "Declared appearance" to it },
        "Address type" to advert.addressType.label,
        advert.serviceUuids.takeIf { it.isNotEmpty() }
            ?.let { "Services" to it.joinToString(", ") { uuid -> shortName(uuid) } },
        if (!advert.isLegacy) "Advertising" to "Extended (Bluetooth 5)" else null,
    )

    /** The 16-bit form of a UUID, or null when it is a full 128-bit one. */
    fun shortUuid(uuid: String): Int? {
        val text = uuid.trim()
        if (text.length == 4) return text.toIntOrNull(16)
        // Android hands back the full form of a short UUID, which is the base with the
        // short value at one fixed place inside it.
        if (text.length == 36 && text.endsWith("-0000-1000-8000-00805f9b34fb", true)) {
            return text.substring(4, 8).toIntOrNull(16)
        }
        return null
    }

    private fun shortName(uuid: String): String {
        val short = shortUuid(uuid) ?: return uuid.take(8)
        val named = AssignedNumbers.SIG_SERVICES[short] ?: AssignedNumbers.MEMBER_SERVICES[short]
        return named?.let { "$it (0x%04X)".format(short) } ?: "0x%04X".format(short)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }
}
