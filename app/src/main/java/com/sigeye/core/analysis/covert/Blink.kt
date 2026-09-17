package com.sigeye.core.analysis.covert

/** What came back out of a run of blinks. */
data class Decoded(
    val text: String,
    /** Characters whose parity bit did not agree, shown as a block in the text. */
    val corrupt: Int,
    /** Slots consumed, so a screen can say how far through it is. */
    val slotsRead: Int,
    val sawPreamble: Boolean,
    val complete: Boolean,
) {
    val anything: Boolean get() = text.isNotEmpty()
}

/**
 * A message in the existence of advertisements.
 *
 * Bluetooth advertising has a payload, so putting bytes in it would be a data transfer
 * rather than a demonstration. This uses none of it: every packet is identical, and the
 * message is carried entirely in whether the device is advertising or silent during each
 * slot of time. On for a slot is a one, off is a zero, which is the same idea as a lamp and
 * a shutter and about the same speed.
 *
 * It is worth building because it makes a point the rest of this app keeps running into
 * from the other direction. Follow Me works because presence over time is informative;
 * Defeating Randomization works because the rhythm of a transmitter is a fingerprint. Both
 * of those are cases of something leaking through a channel nobody designed. This is the
 * same channel used deliberately, which is the clearest way to show it exists.
 *
 * The address is not part of it. It rotates while a message is being sent, so the receiver
 * recognizes the transmitter by a marker in its payload and reads the meaning out of the
 * timing. That split is the demonstration: the identity is in the packet, the message is
 * in the gaps.
 *
 * Pure and Android-free.
 */
object Blink {

    /**
     * Symbols per character.
     *
     * Six bits holds forty-three useful characters with room left, and every slot spent is
     * another chance to lose synchronization, so a smaller alphabet is a faster and more
     * robust message. Eight-bit bytes would be a third slower for characters nobody sends.
     */
    const val BITS = 6

    /** Plus one for parity, which catches any single flipped slot in a character. */
    const val SLOTS_PER_CHAR = BITS + 1

    /**
     * Alternating slots at the start, which is how a receiver finds the boundaries.
     *
     * Without it the receiver knows a device is blinking and has no idea where one slot
     * ends and the next begins, and a message read half a slot out is noise.
     */
    val PREAMBLE: List<Boolean> = List(12) { it % 2 == 0 }

    /**
     * What says the preamble has finished.
     *
     * Three ones in a row, which cannot occur inside an alternating preamble however it is
     * aligned, so the receiver cannot mistake one for the other.
     */
    val START: List<Boolean> = listOf(true, true, true, false)

    /** All six bits set. Nothing in the alphabet uses it, so it can end a message. */
    const val END_CODE = 63

    private const val ALPHABET = " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,?!-/:"

    /** Shown where a character arrived with its parity wrong. */
    const val CORRUPT_MARK = '░'

    fun codeFor(character: Char): Int {
        val index = ALPHABET.indexOf(character.uppercaseChar())
        return if (index >= 0) index else ALPHABET.indexOf(' ')
    }

    fun charFor(code: Int): Char? = when {
        code == END_CODE -> null
        code in ALPHABET.indices -> ALPHABET[code]
        else -> CORRUPT_MARK
    }

    /** Which characters can be sent, for a screen that should not let somebody type an X it drops. */
    fun sendable(): String = ALPHABET

    /** Strips anything the alphabet cannot carry, rather than silently sending spaces. */
    fun clean(message: String): String =
        message.uppercase().filter { ALPHABET.indexOf(it) >= 0 }

    // ------------------------------------------------------------------ sending

    /** The whole transmission, one boolean per slot. */
    fun encode(message: String): List<Boolean> = buildList {
        addAll(PREAMBLE)
        addAll(START)
        clean(message).forEach { character -> addAll(symbol(codeFor(character))) }
        addAll(symbol(END_CODE))
    }

    /** One character: six bits most significant first, then an even parity bit. */
    fun symbol(code: Int): List<Boolean> {
        val bits = (BITS - 1 downTo 0).map { position -> (code shr position) and 1 == 1 }
        return bits + bits.count { it }.rem(2).let { it == 1 }
    }

    /** How long a message takes to send, so somebody can be told before they start. */
    fun durationMs(message: String, slotMs: Long): Long = encode(message).size * slotMs

    // ------------------------------------------------------------------ receiving

    /**
     * Which slots had a packet in them.
     *
     * A slot counts as on if anything arrived during it. Advertising several times per slot
     * is the point: losing one packet to a collision or a missed scan window should not
     * flip a bit, and requiring only one arrival out of several is what makes that true.
     */
    fun occupancy(arrivalsMs: List<Long>, startMs: Long, slotMs: Long, slots: Int): List<Boolean> {
        if (slotMs <= 0L || slots <= 0) return emptyList()
        val hit = BooleanArray(slots)
        arrivalsMs.forEach { at ->
            val index = ((at - startMs) / slotMs).toInt()
            if (index in 0 until slots) hit[index] = true
        }
        return hit.toList()
    }

    /**
     * Finds where the slot boundaries fall.
     *
     * The receiver has no shared clock with the transmitter, so it tries a spread of
     * offsets within one slot and keeps whichever makes the opening alternation look most
     * like an alternation. Everything downstream depends on getting this right, which is
     * why the preamble is twelve slots rather than two.
     *
     * @return the start time to measure slots from, or null when nothing looks like a
     *   preamble at all.
     */
    fun findStart(arrivalsMs: List<Long>, slotMs: Long): Long? {
        if (arrivalsMs.size < PREAMBLE.size / 2 || slotMs <= 0L) return null
        val first = arrivalsMs.min()
        val span = arrivalsMs.max() - first
        val slots = (span / slotMs).toInt() + 2
        if (slots < PREAMBLE.size) return null

        var best: Long? = null
        var bestScore = 0

        // A fraction of a slot at a time. Finer than this buys nothing, because the
        // transmitter's own switching is not that precise either.
        val step = (slotMs / PHASE_STEPS).coerceAtLeast(1L)
        for (offset in 0 until PHASE_STEPS) {
            val start = first - offset * step
            val read = occupancy(arrivalsMs, start, slotMs, slots)
            val score = PREAMBLE.indices.count { read.getOrNull(it) == PREAMBLE[it] }
            if (score > bestScore) {
                bestScore = score
                best = start
            }
        }
        return if (bestScore >= PREAMBLE.size - PREAMBLE_SLACK) best else null
    }

    /** Offsets tried within one slot when hunting for the boundaries. */
    const val PHASE_STEPS = 8

    /** Preamble slots allowed to be wrong before the alignment is rejected. */
    const val PREAMBLE_SLACK = 2

    /** Reads a message back out of slot occupancy. */
    fun decode(slots: List<Boolean>): Decoded {
        val at = startOfMessage(slots)
            ?: return Decoded("", 0, 0, sawPreamble = false, complete = false)

        val text = StringBuilder()
        var corrupt = 0
        var cursor = at
        var complete = false

        while (cursor + SLOTS_PER_CHAR <= slots.size) {
            val group = slots.subList(cursor, cursor + SLOTS_PER_CHAR)
            cursor += SLOTS_PER_CHAR

            val bits = group.take(BITS)
            val code = bits.fold(0) { acc, bit -> (acc shl 1) or if (bit) 1 else 0 }
            val evenParity = (bits.count { it } + if (group[BITS]) 1 else 0) % 2 == 0

            if (!evenParity) {
                // The character is known to be wrong and is not silently dropped. A gap in
                // a decoded message that nobody is told about is worse than a visible one.
                corrupt++
                text.append(CORRUPT_MARK)
                continue
            }
            if (code == END_CODE) {
                complete = true
                break
            }
            charFor(code)?.let { text.append(it) }
        }

        return Decoded(
            text = text.toString(),
            corrupt = corrupt,
            slotsRead = cursor,
            sawPreamble = true,
            complete = complete,
        )
    }

    /** Where the characters begin: after the preamble and the start marker. */
    fun startOfMessage(slots: List<Boolean>): Int? {
        val limit = slots.size - START.size
        for (index in 0..limit) {
            if (START.indices.all { slots[index + it] == START[it] }) {
                return index + START.size
            }
        }
        return null
    }

    /**
     * Characters a second, which is worth telling somebody before they start typing.
     *
     * At half a second a slot this is about three and a half seconds a character, which is
     * slower than a competent operator sending morse by hand. Nobody should be surprised by
     * that: it is a channel with one bit of state and it is being used at walking pace.
     */
    fun charsPerMinute(slotMs: Long): Double =
        if (slotMs <= 0L) 0.0 else 60_000.0 / (slotMs * SLOTS_PER_CHAR)
}
