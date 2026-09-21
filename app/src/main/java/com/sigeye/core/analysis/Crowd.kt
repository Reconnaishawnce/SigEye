package com.sigeye.core.analysis

/** Roughly how busy the air is, from what the radio is actually delivering. */
enum class Density(val label: String) {
    /** A home, an office, a quiet carriage. Everything in the app was designed for this. */
    ROOM("Quiet"),

    /** A cafe, a busy street, a train at rush hour. */
    BUSY("Busy"),

    /** An airport, a stadium, a conference floor. Hundreds of devices at once. */
    PACKED("Packed"),
}

/**
 * What to do differently when there are hundreds of devices instead of twenty.
 *
 * Almost everything in this app was written and tested in a quiet room, and a quiet room is
 * a different problem. A radar with twenty-four dots is a picture; the same radar in an
 * airport is a smear. A comparison that runs over every pair of devices is instant with
 * thirty and is a stall with a thousand. A follow that starts by listing everything in
 * earshot starts with a list nobody can narrow.
 *
 * So the limits move with the room rather than being one number chosen in a kitchen. Every
 * adjustment here is about what a person can read and what a phone can finish, never about
 * what gets measured: nothing is dropped from a count because the room is busy.
 *
 * Pure and Android-free.
 */
object Crowd {

    /** Devices heard recently, above which a room counts as busy. */
    const val BUSY_DEVICES = 60

    /** And above which it is packed. */
    const val PACKED_DEVICES = 250

    /**
     * Packets a second that says the phone is the limit rather than the room.
     *
     * Kept alongside the device count because the two go wrong separately. A hundred
     * beacons all shouting is a high rate from few devices; a terminal full of sleeping
     * phones is a lot of devices at a modest rate. Either one is a reason to simplify.
     */
    const val PACKED_RATE = 400.0

    fun densityOf(devices: Int, advertsPerSecond: Double = 0.0): Density = when {
        devices >= PACKED_DEVICES || advertsPerSecond >= PACKED_RATE -> Density.PACKED
        devices >= BUSY_DEVICES -> Density.BUSY
        else -> Density.ROOM
    }

    /**
     * Dots to draw on a radar.
     *
     * Fewer when packed, which is the opposite of what it sounds like it should be. The
     * point of the radar is to see one device move relative to the others, and past about a
     * dozen overlapping dots that stops being possible for anybody.
     */
    fun radarBlips(density: Density): Int = when (density) {
        Density.ROOM -> 24
        Density.BUSY -> 20
        Density.PACKED -> 14
    }

    /**
     * How many devices are worth comparing against each other for travelling together.
     *
     * The comparison is every pair, so the work grows with the square. Twenty devices is
     * a hundred and ninety comparisons and finishes instantly; five hundred is a hundred
     * and twenty thousand, several times a second, on a phone somebody is carrying.
     */
    fun companionLimit(density: Density): Int = when (density) {
        Density.ROOM -> 20
        Density.BUSY -> 20
        Density.PACKED -> 12
    }

    /**
     * Whether a follow should leave out devices that could never survive a rotation.
     *
     * A device whose advertisement carries almost nothing distinctive cannot be matched to
     * its own successor - the app already refuses to try, and says so. In a quiet room it
     * still belongs in the pool, because it might simply never rotate during the walk. In a
     * terminal it is one of eight hundred, it will dilute the pool for twenty minutes, and
     * if it does rotate it is lost anyway.
     *
     * Only ever done when packed, and only ever with the count shown, because this is the
     * one adjustment here that changes what is being followed rather than how it is drawn.
     */
    fun thinsThePool(density: Density): Boolean = density == Density.PACKED

    /**
     * One plain sentence about the room, or nothing at all in a quiet one.
     *
     * Deliberately short. Somebody standing in an airport does not want a paragraph about
     * scan duty cycles, they want to know the app noticed and that the numbers still mean
     * something.
     */
    fun describe(density: Density, devices: Int): String? = when (density) {
        Density.ROOM -> null
        Density.BUSY -> "Busy here: $devices devices. Readings are fine, the screen is fuller."
        Density.PACKED ->
            "Packed here: $devices devices. Showing fewer at once so it stays readable."
    }
}
