package com.sigeye.core.ble

import java.util.Locale

/** How a characteristic's bytes should be read. */
enum class ValueKind { TEXT, PERCENT, NUMBER, APPEARANCE, HEX }

/**
 * Turning a connection into English.
 *
 * A GATT dump is a tree of 128-bit numbers, and reading one without a lookup table is
 * archaeology. Everything here answers the same question in a different place: what is this
 * thing, and what does the value mean?
 *
 * Pure and Android-free.
 */
object GattGlossary {

    /**
     * The 16-bit short form of a UUID, or null if it is genuinely custom.
     *
     * Every SIG-assigned UUID is the 16-bit number slotted into a fixed 128-bit base. A
     * UUID that does not fit the base was invented by the manufacturer, which is itself
     * worth reporting - it usually means a proprietary protocol.
     */
    fun short(uuid: String): Int? {
        val text = uuid.lowercase(Locale.US)
        if (!text.endsWith("-0000-1000-8000-00805f9b34fb")) return null
        val head = text.substringBefore('-')
        if (head.length != 8 || !head.startsWith("0000")) return null
        return head.drop(4).toIntOrNull(16)
    }

    fun serviceName(uuid: String): String? {
        val id = short(uuid) ?: return null
        return AssignedNumbers.SIG_SERVICES[id]
            ?: AssignedNumbers.MEMBER_SERVICES[id]?.let { "$it (vendor service)" }
    }

    fun characteristicName(uuid: String): String? =
        short(uuid)?.let { AssignedNumbers.CHARACTERISTICS[it] }

    /** What a service is for, in a sentence, for the ones people actually meet. */
    fun explainService(uuid: String): String {
        val id = short(uuid)
            ?: return "A custom service. The maker invented this one, so only their own " +
                "software knows what it holds - which is itself a finding."
        AssignedNumbers.MEMBER_SERVICES[id]?.let { owner ->
            return "Registered to $owner. Member UUIDs are allocated to one company, so " +
                "this names the ecosystem even when nothing else does."
        }
        return SERVICE_NOTES[id]
            ?: AssignedNumbers.SIG_SERVICES[id]?.let {
                "A standard service defined by the Bluetooth SIG."
            }
            ?: "Not in the registry this build carries."
    }

    /** What a characteristic holds. */
    fun explainCharacteristic(uuid: String): String {
        val id = short(uuid)
            ?: return "A custom value. Without the maker's documentation the bytes are " +
                "just bytes."
        return CHARACTERISTIC_NOTES[id]
            ?: AssignedNumbers.CHARACTERISTICS[id]?.let { "A standard value: $it." }
            ?: "Not in the registry this build carries."
    }

    fun kindOf(uuid: String): ValueKind = when (short(uuid)) {
        0x2A00, 0x2A24, 0x2A25, 0x2A26, 0x2A27, 0x2A28, 0x2A29 -> ValueKind.TEXT
        0x2A19 -> ValueKind.PERCENT
        0x2A01 -> ValueKind.APPEARANCE
        0x2A07, 0x2A6E, 0x2A6F -> ValueKind.NUMBER
        else -> ValueKind.HEX
    }

    /**
     * Renders a characteristic's bytes the way its type says they should be read.
     *
     * Falls back to hex rather than guessing. A wrong decode reads as a confident fact,
     * which is worse than a row of bytes that is obviously raw.
     */
    fun render(uuid: String, bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return "(empty)"
        return when (kindOf(uuid)) {
            ValueKind.TEXT -> asText(bytes) ?: hex(bytes)
            ValueKind.PERCENT -> "${bytes[0].toInt() and 0xFF}%"
            ValueKind.NUMBER -> (bytes[0].toInt() and 0xFF).toString()
            ValueKind.APPEARANCE -> {
                if (bytes.size < 2) {
                    hex(bytes)
                } else {
                    val value = ((bytes[1].toInt() and 0xFF) shl 8) or (bytes[0].toInt() and 0xFF)
                    Appearance.describe(value) ?: "Unspecified (0x%04X)".format(value)
                }
            }
            ValueKind.HEX -> hex(bytes)
        }
    }

    /**
     * Bytes as a string, or null if they are plainly not one.
     *
     * Devices routinely leave a string field zero-filled, and NUL is not whitespace,
     * so trimming alone leaves a "value" that renders as nothing at all on screen.
     * Anything carrying control characters is reported as hex instead, which at least
     * looks raw rather than looking empty.
     */
    private fun asText(bytes: ByteArray): String? {
        val decoded = bytes.decodeToString()
        if (decoded.any { it.code < 0x20 || it.code == 0x7F }) return null
        return decoded.trim().ifBlank { null }
    }

    fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }

    /**
     * The properties of a characteristic in plain words.
     *
     * @param properties the Android property bitmask, passed in so this stays Android-free.
     */
    fun describeProperties(properties: Int): String {
        val parts = mutableListOf<String>()
        if (properties and PROPERTY_READ != 0) parts.add("readable")
        if (properties and PROPERTY_WRITE != 0) parts.add("writable")
        if (properties and PROPERTY_WRITE_NO_RESPONSE != 0) parts.add("writable without reply")
        if (properties and PROPERTY_NOTIFY != 0) parts.add("notifies")
        if (properties and PROPERTY_INDICATE != 0) parts.add("indicates")
        return if (parts.isEmpty()) "no properties declared" else parts.joinToString(", ")
    }

    fun isReadable(properties: Int): Boolean = properties and PROPERTY_READ != 0

    // Android's BluetoothGattCharacteristic constants, restated so this file needs no
    // Android import and can be tested on the JVM.
    const val PROPERTY_READ = 0x02
    const val PROPERTY_WRITE_NO_RESPONSE = 0x04
    const val PROPERTY_WRITE = 0x08
    const val PROPERTY_NOTIFY = 0x10
    const val PROPERTY_INDICATE = 0x20

    /** Notes for the services someone is actually likely to meet. */
    private val SERVICE_NOTES = mapOf(
        0x1800 to "Generic Access. Every device has one. It carries the name the device " +
            "calls itself and what kind of thing it claims to be.",
        0x1801 to "Generic Attribute. Bookkeeping for the GATT table itself - it is how a " +
            "device tells you its layout has changed since you last looked.",
        0x180A to "Device Information. The interesting one: maker, model, serial number, " +
            "firmware version. Not every device fills it in, and nothing verifies it.",
        0x180F to "Battery Service. A single percentage. Often the only thing a tag will " +
            "tell you without pairing.",
        0x1812 to "Human Interface Device. Keyboards, mice and game controllers. Reading " +
            "these normally needs a bonded pairing rather than a plain connection.",
        0x180D to "Heart Rate. A fitness band or chest strap.",
        0x181A to "Environmental Sensing. Temperature, humidity, pressure - a weather or " +
            "room sensor.",
        0x1819 to "Location and Navigation. Something that knows where it is.",
        0x1827 to "Mesh Provisioning. A device waiting to be added to a Bluetooth mesh, " +
            "which means it is currently unclaimed.",
        0x1828 to "Mesh Proxy. Already part of a mesh network and relaying for it.",
        0xFE59 to "Nordic's DFU service. The device can have its firmware replaced over " +
            "the air, which is worth knowing about anything you did not install yourself.",
    )

    /** Notes for the characteristics worth a sentence. */
    private val CHARACTERISTIC_NOTES = mapOf(
        0x2A00 to "The name the device calls itself when asked directly. It can differ " +
            "from the name it advertises, and occasionally that difference is the point.",
        0x2A01 to "Appearance: the registry category the device claims to belong to.",
        0x2A19 to "Battery level, as a percentage.",
        0x2A23 to "System ID. Usually contains the device's own MAC address - which means " +
            "a device using a randomized address can leak its real one here.",
        0x2A24 to "Model number, as free text set by the maker.",
        0x2A25 to "Serial number. Unique to the unit, and unlike a randomized MAC it does " +
            "not change - which makes it the strongest identifier most devices hand out.",
        0x2A26 to "Firmware revision. Useful for telling two otherwise identical devices " +
            "apart, and for knowing how old the software on it is.",
        0x2A27 to "Hardware revision - which board is inside.",
        0x2A28 to "Software revision, as distinct from firmware.",
        0x2A29 to "Manufacturer name, as free text. Nothing verifies it.",
        0x2A50 to "PnP ID: a vendor ID and product ID pair, in the same registries USB " +
            "uses. Often the most precise identification a device offers.",
    )
}
