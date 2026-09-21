package com.sigeye.core.analysis.identity

import kotlin.math.max
import kotlin.random.Random

/**
 * A modeled walk with the answer written down, for measuring the rotation thresholds.
 *
 * **This is a model and it is not a walk.** It knows what a rotation looks like because it
 * was written by somebody who believes they know what a rotation looks like, so a threshold
 * that scores perfectly here has been shown to be self-consistent and nothing more. Real air
 * has reflections, bodies, other people's phones rotating at the same instant, and devices
 * whose firmware does something nobody modeled. Treat a result from this as evidence that a
 * change is not obviously wrong, never as evidence that it is right.
 *
 * What it is genuinely good for is the other direction. If moving a threshold makes the
 * model worse, the threshold was doing something, and a change that breaks a case this
 * simple would certainly break a street. That is worth catching in CI, which is why the
 * sweep exists.
 *
 * The real thing replaces it: a capture recorded on an actual walk, with the target's
 * addresses written down, replayed through the same [Trial]. See RealWalkTest.
 */
object WalkModel {

    /** One device and what it does, as the model understands it. */
    data class Actor(
        val addresses: List<String>,
        /** When each address gives way to the next. Same length as [addresses] minus one. */
        val rotationsAtMs: List<Long>,
        val shape: AdvertShape,
        val intervalMs: Long,
        /** Level at the phone, before noise. */
        val rssi: Int,
    )

    data class Walk(
        val packets: List<Packet>,
        /** Address handovers that genuinely happened, as from-to pairs. */
        val truth: List<Pair<String, String>>,
    )

    data class Packet(
        val address: String,
        val atMs: Long,
        val rssi: Int,
        val shape: AdvertShape,
    )

    private fun appleShape(seed: Int) = AdvertShape(
        companyId = 0x004C,
        serviceUuids = if (seed % 3 == 0) listOf("FD6F") else emptyList(),
        manufacturerLength = 20 + seed % 8,
        manufacturerPrefix = "10%02X".format(seed % 16),
        txPower = -20 - seed % 6,
    )

    /**
     * A target that rotates, plus [crowd] other devices that also rotate.
     *
     * The crowd is the part that matters. One device rotating in an empty room is a problem
     * nobody has; the question every threshold here is really answering is how to tell your
     * device's new address from the six other new addresses that appeared in the same
     * second, which only exists when other things are rotating too.
     */
    fun walk(
        durationMs: Long = 20 * 60_000L,
        crowd: Int = 12,
        /**
         * Devices indistinguishable from the target by payload, at a similar level.
         *
         * The first version of this model left them out, and the sweep immediately showed
         * why that was useless: with every device carrying a distinguishable advertisement,
         * no threshold ever produced a wrong follow, so looser always scored better and the
         * model recommended guessing. The thing every threshold here exists for is the room
         * full of identical phones. A model without one is not modeling the problem.
         */
        twins: Int = 4,
        rotateEveryMs: Long = 4 * 60_000L,
        seed: Int = 7,
        startMs: Long = 1_700_000_000_000L,
    ): Walk {
        val random = Random(seed)
        val actors = mutableListOf<Actor>()
        val truth = mutableListOf<Pair<String, String>>()

        fun actor(
            index: Int,
            rssi: Int,
            offsetMs: Long,
            shape: AdvertShape = appleShape(index),
            intervalMs: Long = 150L + index * 37L,
        ): Actor {
            val turns = max(1, (durationMs / rotateEveryMs).toInt())
            val addresses = (0..turns).map { step -> address(index, step) }
            val rotations = (1..turns).map { step -> offsetMs + step * rotateEveryMs }
            addresses.zipWithNext().forEach { truth.add(it) }
            return Actor(
                addresses = addresses,
                rotationsAtMs = rotations,
                shape = shape,
                intervalMs = intervalMs,
                rssi = rssi,
            )
        }

        // The one being followed: close, because somebody is walking with it.
        val targetShape = appleShape(0)
        actors += actor(0, rssi = -52, offsetMs = 11_000L, shape = targetShape)

        // The same model of phone, at a similar distance, rotating around the same time.
        // Nothing in the payload separates these from the target, which is the case the
        // thresholds are for.
        repeat(twins) { twin ->
            actors += actor(
                index = 100 + twin,
                // Same pocket depth. A twin two rooms away is not confusable and the
                // level alone would separate it.
                rssi = -52 - random.nextInt(3),
                // And rotating inside the window a successor is looked for in, which is
                // the only way two devices are ever genuinely candidates for each other.
                offsetMs = 11_000L + random.nextLong(-4_000L, 4_000L),
                shape = targetShape,
                // Same model of phone means the same firmware means the same nominal
                // interval. Giving twins different intervals was handing the matcher a
                // perfect discriminator that the real case does not have.
                intervalMs = 150L,
            )
        }

        repeat(crowd) { other ->
            actors += actor(
                other + 1,
                rssi = -62 - random.nextInt(25),
                // Spread out, so the model is not a row of devices rotating in lockstep.
                offsetMs = random.nextLong(rotateEveryMs),
            )
        }

        val packets = mutableListOf<Packet>()
        actors.forEach { who ->
            var at = startMs
            while (at < startMs + durationMs) {
                val index = who.rotationsAtMs.count { at - startMs >= it }
                packets += Packet(
                    address = who.addresses[index.coerceAtMost(who.addresses.lastIndex)],
                    atMs = at,
                    // A couple of dB of shimmer, which is what standing still actually looks
                    // like. Without it every level is identical and the match is too easy.
                    rssi = who.rssi + random.nextInt(-3, 4),
                    shape = who.shape,
                )
                // The specification requires a random 0 to 10 ms delay on every advertising
                // event, so that two devices on the same interval do not collide forever.
                // Leaving it out made every device a metronome, and a metronome has a
                // perfect fingerprint: two identical phones were being told apart by an
                // interval that in reality wobbles by more than the difference.
                at += who.intervalMs + random.nextLong(0, ADV_DELAY_MS)
            }
        }

        return Walk(packets.sortedBy { it.atMs }, truth)
    }

    /** The random delay the Bluetooth specification adds to every advertising event. */
    private const val ADV_DELAY_MS = 11L

    private fun address(actor: Int, step: Int): String {
        val body = (actor * 1000 + step * 7 + 1)
        // Top two bits set marks a random static address, which is what a rotation produces.
        return "%02X:%02X:%02X:%02X:%02X:%02X".format(
            0xC0 or (actor and 0x0F),
            step and 0xFF,
            (body shr 8) and 0xFF,
            body and 0xFF,
            (actor * 13) and 0xFF,
            (step * 29) and 0xFF,
        )
    }
}
