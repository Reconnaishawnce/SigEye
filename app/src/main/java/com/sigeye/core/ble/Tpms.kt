package com.sigeye.core.ble

import java.util.Locale

/** One tyre's worth of telemetry, as broadcast in the clear. */
data class TpmsReading(
    val sensorNumber: Int,
    val sensorAddress: String,
    val rawPressure: Int,
    val rawTemperature: Int,
    val batteryPercent: Int,
    val alarm: Boolean,
) {
    /** Kilopascals. See [Tpms] on why the divisor is a hundred. */
    val kilopascals: Double get() = rawPressure / 100.0

    val psi: Double get() = kilopascals / 6.89476

    val bar: Double get() = kilopascals / 100.0

    val celsius: Double get() = rawTemperature / 100.0

    val fahrenheit: Double get() = celsius * 9.0 / 5.0 + 32.0

    /** Tyres are numbered from one; the wire format counts from 0x80. */
    val tyreLabel: String get() = "Tyre $sensorNumber"

    fun summary(): String = String.format(
        Locale.US,
        "%.0f kPa (%.1f psi), %.1f C, battery %d%%",
        kilopascals,
        psi,
        celsius,
        batteryPercent,
    )
}

/**
 * Aftermarket Bluetooth tyre pressure sensors, which broadcast in the clear.
 *
 * The cheap valve-cap sensors sold everywhere advertise pressure, temperature and battery
 * continuously, unencrypted and unauthenticated, to anybody within range. That makes them a
 * genuinely useful thing to watch: a set of four is a fingerprint for one vehicle, it moves
 * with the vehicle, and unlike a phone the address never rotates.
 *
 * Original-equipment sensors are a different matter and this cannot see them. Those
 * transmit at 315 MHz in the United States and 433 MHz in Europe, which is nowhere near
 * 2.4 GHz - no phone has a receiver for it, and no amount of software will change that. It
 * takes a software-defined radio.
 *
 * The format is community-derived rather than published, so the raw values are kept
 * alongside the converted ones and the screen shows both.
 *
 * Pure and Android-free.
 */
object Tpms {

    /**
     * The company ID these sensors advertise under: TomTom's, which they are not.
     *
     * Manufacturer data begins with a 16-bit company ID and nothing stops a maker putting
     * somebody else's in it. Plenty of cheap hardware ships with whatever the reference
     * design had, and this family carries 0x0100 throughout.
     */
    const val COMPANY_ID = 0x0100

    /** Sensor one reports 0x80, two 0x81, and so on. */
    const val FIRST_SENSOR_BYTE = 0x80

    /** Every sensor in this family sits on a MAC of the form 8n:EA:CA:xx:xx:xx. */
    const val ADDRESS_MARKER = "EA:CA"

    /**
     * Decodes an advertisement, or returns null if it is not one of these.
     *
     * @param manufacturerData the payload after the company ID, which is what Android
     *   hands over.
     */
    fun decode(companyId: Int?, manufacturerData: ByteArray?): TpmsReading? {
        if (companyId != COMPANY_ID) return null
        val data = manufacturerData ?: return null
        if (data.size < 16) return null

        val sensorByte = data[0].toInt() and 0xFF
        val sensorNumber = sensorByte - FIRST_SENSOR_BYTE + 1
        if (sensorNumber !in 1..8) return null

        // The EA CA marker is the part that makes this safe to claim. Company ID 0x0100
        // belongs to somebody else entirely, so without a second check every TomTom device
        // in range would be reported as a tyre.
        if ((data[1].toInt() and 0xFF) != 0xEA || (data[2].toInt() and 0xFF) != 0xCA) {
            return null
        }

        return TpmsReading(
            sensorNumber = sensorNumber,
            sensorAddress = data.copyOfRange(3, 6).joinToString(":") { "%02X".format(it) },
            rawPressure = littleEndian(data, 6),
            rawTemperature = littleEndian(data, 10),
            batteryPercent = data[14].toInt() and 0xFF,
            alarm = (data[15].toInt() and 0xFF) != 0,
        )
    }

    /** Whether an address looks like one of these sensors, payload or no payload. */
    fun looksLikeSensor(address: String): Boolean {
        val parts = address.uppercase(Locale.US).split(':', '-')
        if (parts.size < 3) return false
        val first = parts[0].toIntOrNull(16) ?: return false
        return first in FIRST_SENSOR_BYTE..(FIRST_SENSOR_BYTE + 7) &&
            parts[1] == "EA" && parts[2] == "CA"
    }

    private fun littleEndian(data: ByteArray, offset: Int): Int {
        var value = 0
        for (index in 3 downTo 0) {
            value = (value shl 8) or (data[offset + index].toInt() and 0xFF)
        }
        return value
    }
}
