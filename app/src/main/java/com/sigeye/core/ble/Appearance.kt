package com.sigeye.core.ble

/**
 * GAP Appearance - the one field in an advertisement where a device states what it *is*.
 *
 * A name is whatever the maker typed, and a good half of them are a MAC fragment. Appearance
 * is a 16-bit number from the Bluetooth SIG's assigned-numbers list: the top ten bits are a
 * category (Phone, Watch, Tag, Access Control...) and the bottom six a subcategory. It is
 * optional and plenty of devices omit it, but when it is present it is the most honest label
 * available, because it comes from a registry rather than from marketing.
 *
 * Pure and Android-free so it can be tested.
 */
object Appearance {

    /** AD type 0x19, two bytes, little-endian. */
    const val AD_TYPE_APPEARANCE = 0x19

    /**
     * Pulls the appearance out of a raw advertising payload.
     *
     * The payload is a chain of length-type-value structures. A padded record is normal -
     * trailing zero bytes are how advertisers fill out the 31-byte budget - so a zero length
     * ends the walk quietly rather than throwing.
     */
    fun parse(raw: ByteArray?): Int? {
        if (raw == null) return null
        var index = 0
        while (index < raw.size) {
            val length = raw[index].toInt() and 0xFF
            if (length == 0) return null
            val type = raw.getOrNull(index + 1)?.toInt()?.and(0xFF) ?: return null
            if (type == AD_TYPE_APPEARANCE && length >= 3 && index + 3 < raw.size) {
                val low = raw[index + 2].toInt() and 0xFF
                val high = raw[index + 3].toInt() and 0xFF
                return (high shl 8) or low
            }
            index += length + 1
        }
        return null
    }

    fun categoryOf(appearance: Int): Int = (appearance shr 6) and 0x3FF

    fun subcategoryOf(appearance: Int): Int = appearance and 0x3F

    /**
     * A human label, as specific as the registry allows.
     *
     * Category 0 is the default a chip ships with, so it is reported as null rather than as
     * the word "Unknown" - a caller can then fall back to something more useful instead of
     * printing a non-answer in a field.
     */
    fun describe(appearance: Int?): String? {
        if (appearance == null) return null
        val category = categoryOf(appearance)
        if (category == 0) return null
        val name = AssignedNumbers.APPEARANCE_CATEGORIES[category]
            ?: return "Unlisted category $category"
        val sub = AssignedNumbers.APPEARANCE_SUBCATEGORIES[category]
            ?.get(subcategoryOf(appearance))
        return if (sub != null && !sub.equals(name, ignoreCase = true)) "$name - $sub" else name
    }
}
