package com.sigeye.core.ble

/**
 * What sort of thing an advertisement came from.
 *
 * Only ever a guess from what the device chose to broadcast, and the guess is graded. A GAP
 * appearance is the device saying what it is in the language the specification provides, so
 * it wins. Apple's Continuity message types say what a device is doing, which narrows it a
 * long way without settling it. A name is the weakest of the three and the easiest to fake,
 * so it is only consulted when nothing better is present.
 */
enum class DeviceKind(val label: String, val emoji: String) {
    PHONE("Phone", "📱"),
    WATCH("Watch", "⌚"),
    EARBUDS("Earbuds", "🎧"),
    TAG("Tag", "🏷"),
    COMPUTER("Computer", "💻"),
    AUDIO("Speaker or TV", "🔊"),
    VEHICLE("Vehicle", "🚗"),
    BEACON("Beacon", "📡"),
    WEARABLE("Wearable", "❤"),

    /**
     * An Apple device that will not say which kind it is.
     *
     * Its own band, and this is the honest part. An iPhone and an Apple Watch sitting in a
     * pocket both send Nearby Info, both under company 0x004C, both with the body
     * randomized - and from one advertisement there is very often nothing that separates
     * them. Calling those "Phone" would be inventing a fact at exactly the moment somebody
     * is deciding which person to follow.
     */
    APPLE("Apple device", "🍎"),

    UNKNOWN("Unknown", "❓"),
    ;

    companion object {
        /** The ones worth offering as a filter. The rest are noise on a picker. */
        val FILTERABLE = listOf(PHONE, WATCH, EARBUDS, TAG, COMPUTER, AUDIO, APPLE)
    }
}

/** How much to trust [DeviceKinds.of]. */
enum class KindConfidence {
    /** The device declared it, in the field the specification provides for declaring it. */
    DECLARED,

    /** Inferred from what it broadcasts. Usually right, never certain. */
    INFERRED,

    /** A name, or nothing at all. */
    GUESSED,
}

data class KindGuess(
    val kind: DeviceKind,
    val confidence: KindConfidence,
    /** Why, in one line, for a screen that should not ask anybody to take its word. */
    val because: String,
) {
    val certain: Boolean get() = confidence == KindConfidence.DECLARED
}

/**
 * Working out what a device is from what it broadcasts.
 *
 * Worth having because a follow narrows to a handful of devices and the question then
 * becomes which of them is on the person rather than in a shop window, and "phone, watch,
 * earbuds" is most of that answer. It is also the shape of the finding for a paper: a
 * person carrying three things that can each be recognized by kind is far more identifiable
 * than a person carrying one.
 *
 * Pure and Android-free.
 */
object DeviceKinds {

    // GAP appearance is a sixteen bit value: the top ten bits are a category and the
    // bottom six a subcategory. Categories are what matter here.
    private const val CATEGORY_PHONE = 0x001
    private const val CATEGORY_COMPUTER = 0x002
    private const val CATEGORY_WATCH = 0x003
    private const val CATEGORY_DISPLAY = 0x00C
    private const val CATEGORY_TAG = 0x00D
    private const val CATEGORY_HEART_RATE = 0x00E
    private const val CATEGORY_AUDIO_OUT = 0x00A
    private const val CATEGORY_WEARABLE_AUDIO = 0x028
    private const val CATEGORY_OUTDOOR_SPORTS = 0x051

    /** Apple Continuity message types that pin down a kind on their own. */
    private const val PROXIMITY_PAIRING = 0x07
    private const val FIND_MY = 0x12
    private const val NEARBY_INFO = 0x10
    private const val HANDOFF = 0x0C
    private const val AIRPLAY_TARGET = 0x09
    private const val AIRPLAY_SOURCE = 0x0A
    private const val TETHERING_SOURCE = 0x0E

    /**
     * From the accumulated shape rather than one packet.
     *
     * Better than [of] on a single advertisement, because a device does not send every
     * Continuity message in every packet - the set builds up over a few seconds, and a
     * classification from the first packet would miss the very message that identifies it.
     */
    fun of(shape: com.sigeye.core.analysis.identity.AdvertShape): KindGuess = of(
        appearance = shape.appearance,
        companyId = shape.companyId,
        continuityTypes = shape.continuityTypes,
        serviceUuids = shape.serviceUuids,
        name = shape.name,
    )

    fun of(advert: Advert): KindGuess = of(
        appearance = advert.appearance,
        companyId = advert.companyId,
        continuityTypes = Continuity.types(advert.companyId, advert.manufacturerData),
        serviceUuids = advert.serviceUuids,
        name = advert.name,
    )

    fun of(
        appearance: Int?,
        companyId: Int?,
        continuityTypes: Set<Int>,
        serviceUuids: List<String>,
        name: String?,
    ): KindGuess {
        // The device said so itself, in the field made for saying so.
        appearance?.let { value ->
            fromAppearance(value)?.let { kind ->
                return KindGuess(
                    kind,
                    KindConfidence.DECLARED,
                    "It declares itself a ${kind.label.lowercase()} in its appearance field.",
                )
            }
        }

        if (companyId == Continuity.APPLE && continuityTypes.isNotEmpty()) {
            return fromContinuity(continuityTypes)
        }

        if (serviceUuids.any { it.startsWith(FIND_MY_SERVICE, ignoreCase = true) }) {
            return KindGuess(
                DeviceKind.TAG,
                KindConfidence.INFERRED,
                "Advertising the Find My network service, which is what a tag does when " +
                    "it is away from its owner.",
            )
        }

        fromName(name)?.let { kind ->
            return KindGuess(
                kind,
                KindConfidence.GUESSED,
                "Only from the name it broadcasts, which is the weakest evidence here and " +
                    "the easiest to set to anything.",
            )
        }

        if (companyId == Continuity.APPLE) {
            return KindGuess(
                DeviceKind.APPLE,
                KindConfidence.INFERRED,
                "Apple, and nothing in the packet says which kind.",
            )
        }

        return KindGuess(DeviceKind.UNKNOWN, KindConfidence.GUESSED, "Nothing says what it is.")
    }

    private fun fromAppearance(value: Int): DeviceKind? = when (value shr 6) {
        CATEGORY_PHONE -> DeviceKind.PHONE
        CATEGORY_COMPUTER -> DeviceKind.COMPUTER
        CATEGORY_WATCH -> DeviceKind.WATCH
        CATEGORY_DISPLAY -> DeviceKind.AUDIO
        CATEGORY_TAG -> DeviceKind.TAG
        CATEGORY_HEART_RATE, CATEGORY_OUTDOOR_SPORTS -> DeviceKind.WEARABLE
        CATEGORY_AUDIO_OUT, CATEGORY_WEARABLE_AUDIO -> DeviceKind.EARBUDS
        else -> null
    }

    /**
     * What Apple's message types give away.
     *
     * Ordered by how much each one narrows things. Proximity Pairing is only sent by
     * headphones, and Find My only by something separated from its owner. Nearby Info is
     * sent by everything Apple makes that has a screen or a wrist, so it is the end of the
     * road rather than an answer.
     */
    private fun fromContinuity(types: Set<Int>): KindGuess = when {
        PROXIMITY_PAIRING in types -> KindGuess(
            DeviceKind.EARBUDS,
            KindConfidence.INFERRED,
            "Sending Proximity Pairing, which is how AirPods and Beats announce themselves.",
        )

        FIND_MY in types && NEARBY_INFO !in types -> KindGuess(
            DeviceKind.TAG,
            KindConfidence.INFERRED,
            "Sending Find My and nothing else, which is a tag or a device separated from " +
                "its owner rather than one in somebody's hand.",
        )

        AIRPLAY_TARGET in types && NEARBY_INFO !in types -> KindGuess(
            DeviceKind.AUDIO,
            KindConfidence.INFERRED,
            "Offering itself as an AirPlay target, which a speaker or a TV does and a " +
                "phone in a pocket does not.",
        )

        TETHERING_SOURCE in types || AIRPLAY_SOURCE in types || HANDOFF in types -> KindGuess(
            DeviceKind.APPLE,
            KindConfidence.INFERRED,
            "In active use - handing off or sharing a connection - so it is a phone, a " +
                "watch, a tablet or a laptop. The packet does not say which, and guessing " +
                "at it here would be inventing the one fact you are trying to establish.",
        )

        else -> KindGuess(
            DeviceKind.APPLE,
            KindConfidence.INFERRED,
            "Apple, and the messages it sends are ones every Apple device sends.",
        )
    }

    /**
     * Names, consulted last and trusted least.
     *
     * A name is whatever the owner typed, and plenty of devices carry the name of something
     * they are not - a phone called "Car", a speaker called "iPhone".
     */
    private fun fromName(name: String?): DeviceKind? {
        val text = name?.takeIf { it.isNotBlank() }?.lowercase() ?: return null
        return when {
            text.contains("airpod") || text.contains("buds") || text.contains("headphone") ->
                DeviceKind.EARBUDS

            text.contains("watch") || text.contains("band") -> DeviceKind.WATCH
            text.contains("iphone") || text.contains("galaxy") || text.contains("pixel") ->
                DeviceKind.PHONE

            text.contains("macbook") || text.contains("laptop") -> DeviceKind.COMPUTER
            text.contains("airtag") || text.contains("tile") -> DeviceKind.TAG
            text.contains("tv") || text.contains("speaker") || text.contains("soundbar") ->
                DeviceKind.AUDIO

            text.contains("tpms") || text.contains("carplay") -> DeviceKind.VEHICLE
            text.contains("beacon") -> DeviceKind.BEACON
            else -> null
        }
    }

    /** The sixteen bit service UUID Apple's Find My network advertises under. */
    private const val FIND_MY_SERVICE = "0000fd44"
}
