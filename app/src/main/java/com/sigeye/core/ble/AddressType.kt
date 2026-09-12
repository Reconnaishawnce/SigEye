package com.sigeye.core.ble

import com.sigeye.core.Vendors

/**
 * Which kind of Bluetooth address this is, which decides whether it can rotate at all.
 *
 * The top two bits of the most significant byte are the whole story for a random address:
 * 11 is static, 01 is resolvable private, 00 is non-resolvable private. A public address
 * has no such encoding, which is why the registry is consulted first - an assigned prefix
 * settles it, and Espressif's 48:CA:43 reads as 01 while being perfectly public.
 *
 * The distinction matters more here than anywhere else in the app. A *static* random
 * address looks exactly like a privacy address and does not change until the device
 * reboots, so a device carrying one can be followed indefinitely with no cleverness at
 * all - and plenty of hardware ships that way by mistake.
 */
enum class AddressType(val label: String, val rotates: String) {
    PUBLIC(
        "Public",
        "Assigned by the IEEE and permanent. It identifies the maker and never changes.",
    ),
    RANDOM_STATIC(
        "Random static",
        "Randomly chosen, but fixed until the device restarts - which for something that " +
            "is never turned off means fixed for good. Looks like a privacy measure and " +
            "is not one.",
    ),
    RESOLVABLE_PRIVATE(
        "Resolvable private",
        "The real privacy address. It changes every fifteen minutes or so, and a device " +
            "that has been paired with can still recognise it.",
    ),
    NON_RESOLVABLE_PRIVATE(
        "Non-resolvable private",
        "Changes regularly and cannot be recognised by anyone, paired or not. Rare.",
    ),
    UNKNOWN("Unknown", "Not enough information to tell."),
    ;

    val isPrivacy: Boolean
        get() = this == RESOLVABLE_PRIVATE || this == NON_RESOLVABLE_PRIVATE

    companion object {
        fun of(address: String): AddressType {
            val first = address.substringBefore(':').toIntOrNull(16) ?: return UNKNOWN
            // A registered prefix settles it: the IEEE does not assign blocks that devices
            // then use as random addresses.
            if (Vendors.byAddress(address) != null) return PUBLIC
            return when ((first shr 6) and 0x03) {
                0b11 -> RANDOM_STATIC
                0b01 -> RESOLVABLE_PRIVATE
                0b00 -> NON_RESOLVABLE_PRIVATE
                else -> if ((first and 0x02) != 0) RANDOM_STATIC else PUBLIC
            }
        }
    }
}

/** The physical layer an advertisement arrived on. */
object Phy {
    const val LE_1M = 1
    const val LE_2M = 2
    const val LE_CODED = 3

    fun label(phy: Int): String = when (phy) {
        LE_1M -> "1M"
        LE_2M -> "2M"
        LE_CODED -> "Coded"
        0 -> "none"
        else -> "unknown ($phy)"
    }
}

/**
 * Which bytes of a payload actually change.
 *
 * The single most direct answer to what a privacy scheme is really doing. A device that
 * rotates its address but leaves twenty of its twenty-five manufacturer bytes untouched
 * has handed over a fingerprint far better than the address it just discarded; one that
 * changes every byte has done the job properly.
 *
 * Pure and Android-free.
 */
class PayloadVariance {

    private var reference: ByteArray? = null
    private var varying: BooleanArray? = null

    var samples: Int = 0
        private set

    fun observe(bytes: ByteArray?) {
        val data = bytes ?: return
        if (data.isEmpty()) return
        val existing = reference
        if (existing == null || existing.size != data.size) {
            // A change of length is itself a new shape; start again rather than compare
            // bytes at positions that no longer mean the same thing.
            reference = data.copyOf()
            varying = BooleanArray(data.size)
            samples = 1
            return
        }
        val marks = varying ?: return
        data.indices.forEach { index ->
            if (data[index] != existing[index]) marks[index] = true
        }
        samples++
    }

    val length: Int get() = reference?.size ?: 0

    val varyingBytes: Int get() = varying?.count { it } ?: 0

    val staticBytes: Int get() = length - varyingBytes

    /** `#` where a byte has never changed, `.` where it has. */
    fun mask(): String {
        val marks = varying ?: return ""
        return marks.joinToString("") { if (it) "." else "#" }
    }

    /**
     * Whether this payload is carrying an identifier through its own rotation.
     *
     * Needs several samples before it means anything - one packet has nothing to differ
     * from, and a device that has not rotated yet will look perfectly static.
     */
    fun leaksIdentity(): Boolean =
        samples >= MIN_SAMPLES && length > 0 && staticBytes >= length / 2

    fun describe(): String = when {
        length == 0 -> "No manufacturer payload."
        samples < MIN_SAMPLES -> "Not enough packets yet to see what changes."
        varyingBytes == 0 -> "All $length bytes identical every time. Nothing in the " +
            "payload rotates, so it identifies the device on its own."
        staticBytes == 0 -> "Every byte changes. The payload gives nothing away."
        else -> "$staticBytes of $length bytes never change" +
            if (leaksIdentity()) {
                " - more than half, so the payload is a better identifier than the address."
            } else {
                "."
            }
    }

    fun reset() {
        reference = null
        varying = null
        samples = 0
    }

    companion object {
        const val MIN_SAMPLES = 8
    }
}
