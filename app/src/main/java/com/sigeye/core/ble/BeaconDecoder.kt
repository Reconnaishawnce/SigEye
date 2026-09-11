package com.sigeye.core.ble

import com.sigeye.core.Vendors

/** One decoded field of a beacon frame. */
data class BeaconField(val label: String, val value: String)

/**
 * A recognised advertisement format.
 *
 * [protocol] is what it speaks, [summary] is the one-line identity a person would read,
 * and [fields] is the full decode.
 */
data class Beacon(
    val protocol: String,
    val summary: String,
    val fields: List<BeaconField>,
    /** Transmit power at 1 m, when the format carries it. Lets range be estimated. */
    val measuredPower: Int? = null,
    val note: String? = null,
)

/**
 * Turns advertisement payloads into named formats.
 *
 * The Device Inspector shows you the bytes; this reads them. An iBeacon is not a blob of
 * manufacturer data, it is a UUID, a major and a minor - and knowing that is what lets you
 * tell a supermarket's beacon from a colleague's AirTag.
 *
 * Pure and Android-free so it can be unit tested against captured payloads.
 */
object BeaconDecoder {

    private const val APPLE = 0x004C
    private const val MICROSOFT = 0x0006

    /** Eddystone lives under this 16-bit service UUID. */
    private const val EDDYSTONE_SHORT = "FEAA"

    /** 16-bit service UUIDs worth naming on sight. */
    private val KNOWN_SERVICES = mapOf(
        "FEAA" to "Eddystone",
        "FD6F" to "Exposure Notification",
        "FE2C" to "Google Fast Pair",
        "FE9F" to "Google",
        "FEED" to "Tile",
        "FEEC" to "Tile",
        "FE07" to "Microsoft Swift Pair",
        "FDF0" to "Google Nearby",
        "FE50" to "Google",
        "FD5A" to "Samsung",
        "FE03" to "Amazon",
    )

    /** Apple's Continuity message types, the first byte of their manufacturer data. */
    private val APPLE_TYPES = mapOf(
        0x02 to "iBeacon",
        0x05 to "AirDrop",
        0x07 to "Proximity Pairing (AirPods)",
        0x09 to "AirPlay",
        0x0A to "AirPlay target",
        0x0C to "Handoff",
        0x0D to "Instant Hotspot",
        0x0E to "Instant Hotspot",
        0x10 to "Nearby",
        0x12 to "Find My",
        0x16 to "AirPrint",
    )

    fun decode(advert: Advert): Beacon? = decode(
        companyId = advert.companyId,
        manufacturerData = advert.manufacturerData,
        serviceData = advert.serviceData,
        serviceUuids = advert.serviceUuids,
    )

    fun decode(
        companyId: Int?,
        manufacturerData: ByteArray?,
        serviceData: Map<String, ByteArray>,
        serviceUuids: List<String>,
    ): Beacon? {
        // Manufacturer formats first: they carry the most identity.
        if (manufacturerData != null && companyId != null) {
            iBeacon(companyId, manufacturerData)?.let { return it }
            altBeacon(companyId, manufacturerData)?.let { return it }
            if (companyId == APPLE) appleContinuity(manufacturerData)?.let { return it }
            if (companyId == MICROSOFT) microsoft(manufacturerData)?.let { return it }
        }

        // Then service data formats.
        serviceData.forEach { (uuid, bytes) ->
            if (shortUuid(uuid) == EDDYSTONE_SHORT) {
                eddystone(bytes)?.let { return it }
            }
        }

        // Finally, a service UUID we can at least name.
        val named = (serviceUuids + serviceData.keys).firstNotNullOfOrNull { uuid ->
            KNOWN_SERVICES[shortUuid(uuid)]?.let { it to uuid }
        }
        if (named != null) {
            return Beacon(
                protocol = named.first,
                summary = named.first,
                fields = listOf(BeaconField("Service UUID", shortUuid(named.second))),
            )
        }

        // Manufacturer known but format unrecognised - still worth naming the vendor.
        if (companyId != null) {
            val vendor = Vendors.byCompanyId(companyId) ?: return null
            return Beacon(
                protocol = vendor,
                summary = "$vendor (format not recognised)",
                fields = listOf(
                    BeaconField("Company ID", Vendors.companyIdHex(companyId)),
                    BeaconField("Payload", manufacturerData?.hex() ?: "-"),
                ),
            )
        }
        return null
    }

    // ---------------------------------------------------------------- formats

    /** Apple 0x004C, subtype 0x02, length 0x15, then UUID / major / minor / power. */
    private fun iBeacon(companyId: Int, data: ByteArray): Beacon? {
        if (companyId != APPLE) return null
        if (data.size < 23) return null
        if (data[0].toInt() and 0xFF != 0x02) return null
        if (data[1].toInt() and 0xFF != 0x15) return null

        val uuid = data.copyOfRange(2, 18).uuidString()
        val major = data.u16(18)
        val minor = data.u16(20)
        val power = data[22].toInt()

        return Beacon(
            protocol = "iBeacon",
            summary = "iBeacon $major/$minor",
            measuredPower = power,
            fields = listOf(
                BeaconField("Proximity UUID", uuid),
                BeaconField("Major", major.toString()),
                BeaconField("Minor", minor.toString()),
                BeaconField("Measured power", "$power dBm at 1 m"),
            ),
        )
    }

    /** AltBeacon: 0xBEAC, a 20-byte id, a reference RSSI. Vendor-neutral iBeacon. */
    private fun altBeacon(companyId: Int, data: ByteArray): Beacon? {
        if (data.size < 24) return null
        if (data[0].toInt() and 0xFF != 0xBE) return null
        if (data[1].toInt() and 0xFF != 0xAC) return null

        val id = data.copyOfRange(2, 22).hex(separator = "")
        val power = data[22].toInt()
        return Beacon(
            protocol = "AltBeacon",
            summary = "AltBeacon " + id.take(8),
            measuredPower = power,
            fields = listOf(
                BeaconField("Beacon ID", id),
                BeaconField("Reference RSSI", "$power dBm at 1 m"),
                BeaconField("Company ID", Vendors.companyIdHex(companyId)),
            ),
        )
    }

    /**
     * Eddystone, Google's open format. Four frame types share one service UUID and are
     * told apart by the first byte.
     */
    private fun eddystone(data: ByteArray): Beacon? {
        if (data.isEmpty()) return null
        return when (data[0].toInt() and 0xFF) {
            0x00 -> {
                if (data.size < 18) return null
                val namespace = data.copyOfRange(2, 12).hex(separator = "")
                val instance = data.copyOfRange(12, 18).hex(separator = "")
                Beacon(
                    protocol = "Eddystone-UID",
                    summary = "Eddystone " + instance,
                    measuredPower = data[1].toInt(),
                    fields = listOf(
                        BeaconField("Namespace", namespace),
                        BeaconField("Instance", instance),
                        BeaconField("Ranging data", "${data[1].toInt()} dBm at 0 m"),
                    ),
                )
            }

            0x10 -> {
                if (data.size < 3) return null
                val url = decodeEddystoneUrl(data)
                Beacon(
                    protocol = "Eddystone-URL",
                    summary = url,
                    measuredPower = data[1].toInt(),
                    fields = listOf(
                        BeaconField("URL", url),
                        BeaconField("Ranging data", "${data[1].toInt()} dBm at 0 m"),
                    ),
                    note = "Broadcasting a web address to anything in range.",
                )
            }

            0x20 -> {
                if (data.size < 14) return null
                val batteryMv = data.u16(2)
                val tempRaw = (data[4].toInt() shl 8) or (data[5].toInt() and 0xFF)
                val temperature = tempRaw / 256.0
                val advCount = data.u32(6)
                val secCount = data.u32(10)
                Beacon(
                    protocol = "Eddystone-TLM",
                    summary = "Eddystone telemetry",
                    fields = listOf(
                        BeaconField("Battery", if (batteryMv == 0) "not supported" else "$batteryMv mV"),
                        BeaconField("Temperature", String.format("%.1f C", temperature)),
                        BeaconField("Advertisements", advCount.toString()),
                        BeaconField("Uptime", formatUptime(secCount / 10)),
                    ),
                )
            }

            0x30 -> Beacon(
                protocol = "Eddystone-EID",
                summary = "Eddystone (ephemeral)",
                fields = listOf(
                    BeaconField("Ephemeral ID", data.drop(2).toByteArray().hex(separator = "")),
                ),
                note = "Rotates on a schedule only the owner can resolve.",
            )

            else -> null
        }
    }

    private fun appleContinuity(data: ByteArray): Beacon? {
        if (data.isEmpty()) return null
        val type = data[0].toInt() and 0xFF
        val name = APPLE_TYPES[type] ?: return Beacon(
            protocol = "Apple",
            summary = "Apple (type 0x${type.toString(16).uppercase()})",
            fields = listOf(BeaconField("Payload", data.hex())),
        )
        return Beacon(
            protocol = "Apple $name",
            summary = "Apple $name",
            fields = listOf(
                BeaconField("Continuity type", "0x${type.toString(16).uppercase()} - $name"),
                BeaconField("Payload", data.hex()),
            ),
            note = if (type == 0x12) {
                "Find My. An AirTag or a device advertising for the offline finding " +
                    "network. The identifier rotates, so it cannot be tracked across days."
            } else {
                null
            },
        )
    }

    private fun microsoft(data: ByteArray): Beacon = Beacon(
        protocol = "Microsoft CDP",
        summary = "Microsoft device",
        fields = listOf(BeaconField("Payload", data.hex())),
        note = "Connected Devices Platform - Windows nearby sharing and Swift Pair.",
    )

    // ----------------------------------------------------------------- helpers

    private val URL_SCHEMES = arrayOf("http://www.", "https://www.", "http://", "https://")

    private val URL_SUFFIXES = arrayOf(
        ".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/",
        ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov",
    )

    private fun decodeEddystoneUrl(data: ByteArray): String {
        val scheme = URL_SCHEMES.getOrNull(data[2].toInt() and 0xFF) ?: ""
        val body = StringBuilder(scheme)
        for (index in 3 until data.size) {
            val byte = data[index].toInt() and 0xFF
            if (byte < URL_SUFFIXES.size) {
                body.append(URL_SUFFIXES[byte])
            } else {
                body.append(byte.toChar())
            }
        }
        return body.toString()
    }

    private fun formatUptime(seconds: Long): String {
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val minutes = (seconds % 3_600) / 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    /** "0000feaa-0000-1000-8000-00805f9b34fb" -> "FEAA". */
    fun shortUuid(uuid: String): String {
        val clean = uuid.replace("-", "").uppercase()
        return if (clean.length >= 8) clean.substring(4, 8) else clean
    }

    private fun ByteArray.u16(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

    private fun ByteArray.u32(offset: Int): Long {
        var value = 0L
        for (index in offset until offset + 4) {
            value = (value shl 8) or (this[index].toLong() and 0xFF)
        }
        return value
    }

    private fun ByteArray.uuidString(): String {
        val hex = hex(separator = "").lowercase()
        return buildString {
            append(hex.substring(0, 8)).append('-')
            append(hex.substring(8, 12)).append('-')
            append(hex.substring(12, 16)).append('-')
            append(hex.substring(16, 20)).append('-')
            append(hex.substring(20, 32))
        }
    }

    private fun ByteArray.hex(separator: String = " "): String =
        joinToString(separator) { String.format("%02X", it) }
}
