package com.sigeye.core.ble

/**
 * The message types inside an Apple Continuity advertisement.
 *
 * Apple packs several small messages into one manufacturer-data field under company 0x004C,
 * each a type byte, a length byte and then that many bytes of body. The bodies rotate: they
 * carry the randomized identifiers and state that change with the address, which is the
 * whole point of them. The *set of types* does not, because it is a statement about what
 * the device is and what it is currently doing - an iPhone near a paired watch emits
 * different messages from a lone pair of earbuds, and it keeps emitting them across a
 * rotation.
 *
 * That makes the type set worth more than the payload for recognizing a device through an
 * address change, and it is the part of the packet nothing in this app was reading.
 *
 * Deliberately kept out of [com.sigeye.core.analysis.identity.AdvertShape.key]. A device
 * does not send every message in every packet - the set grows over a few seconds of
 * listening - so making it part of an equality key would report two readings of the same
 * phone as different devices.
 */
object Continuity {

    /** Apple's company identifier. Nothing here applies to any other vendor's payload. */
    const val APPLE = 0x004C

    /**
     * Type bytes worth naming. There are more, and unknown ones are still used for
     * matching - being unable to name a message does not make its presence less of a fact.
     */
    private val NAMES = mapOf(
        0x02 to "iBeacon",
        0x05 to "AirDrop",
        0x06 to "HomeKit",
        0x07 to "Proximity Pairing",
        0x08 to "Hey Siri",
        0x09 to "AirPlay target",
        0x0A to "AirPlay source",
        0x0B to "Magic Switch",
        0x0C to "Handoff",
        0x0D to "Tethering target",
        0x0E to "Tethering source",
        0x0F to "Nearby Action",
        0x10 to "Nearby Info",
        0x12 to "Find My",
    )

    /**
     * The message types present in one manufacturer-data field.
     *
     * Empty for anything that is not Apple, and empty rather than partial when the length
     * bytes do not add up - a payload that does not parse is a payload that was misread,
     * and guessing at the rest of it would put invented structure into a fingerprint.
     */
    fun types(companyId: Int?, data: ByteArray?): Set<Int> {
        if (companyId != APPLE || data == null || data.isEmpty()) return emptySet()

        val found = mutableSetOf<Int>()
        var index = 0
        while (index + 1 < data.size) {
            val type = data[index].toInt() and 0xFF
            val length = data[index + 1].toInt() and 0xFF
            val body = index + 2
            if (body + length > data.size) return emptySet()
            found.add(type)
            index = body + length
        }
        // Trailing byte with no length after it means the walk did not land cleanly.
        return if (index == data.size) found else emptySet()
    }

    /**
     * The messages with their bodies, for anything that needs to read inside one.
     *
     * [types] answers "what is it sending", which is all a fingerprint needs. Working out
     * which Apple device it is means reading the body: the model number lives inside
     * Proximity Pairing and nowhere else.
     *
     * Empty on the same terms as [types]. A payload whose lengths do not add up was
     * misread, and guessing at the rest would put invented structure into an answer.
     */
    fun messages(companyId: Int?, data: ByteArray?): Map<Int, ByteArray> {
        if (companyId != APPLE || data == null || data.isEmpty()) return emptyMap()

        val found = LinkedHashMap<Int, ByteArray>()
        var index = 0
        while (index + 1 < data.size) {
            val type = data[index].toInt() and 0xFF
            val length = data[index + 1].toInt() and 0xFF
            val body = index + 2
            if (body + length > data.size) return emptyMap()
            found[type] = data.copyOfRange(body, body + length)
            index = body + length
        }
        return if (index == data.size) found else emptyMap()
    }

    fun name(type: Int): String = NAMES[type] ?: String.format("type 0x%02X", type)

    /** What a set of types reads as, for a screen explaining why two addresses were linked. */
    fun describe(types: Set<Int>): String =
        types.sorted().joinToString(", ") { name(it) }.ifEmpty { "no Apple messages" }
}
