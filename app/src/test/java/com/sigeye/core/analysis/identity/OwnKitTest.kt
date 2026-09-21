package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Carrying a "this is mine" mark across an address change, unattended.
 *
 * The most dangerous automatic decision in the app. Everywhere else a wrong link produces
 * a visible mistake somebody notices within a minute. Here it quietly excludes a stranger's
 * phone from every count, every follow and every baseline, indefinitely, with nobody told.
 * These tests exist to keep the bar where it is.
 */
class OwnKitTest {

    private val t0 = 1_700_000_000_000L

    private val shape = AdvertShape(
        companyId = 0x004C,
        serviceUuids = listOf("FD6F"),
        manufacturerLength = 23,
        manufacturerPrefix = "1005",
    )

    private fun identity(
        address: String,
        shape: AdvertShape = this.shape,
        firstSeenMs: Long = t0,
        lastSeenMs: Long = t0 + 60_000L,
        rssi: Double = -45.0,
        gapMs: Long = 1_000L,
        packets: Int = 60,
    ) = Identity(
        address = address,
        shape = shape,
        isRandom = true,
        firstSeenMs = firstSeenMs,
        lastSeenMs = lastSeenMs,
        packets = packets,
        medianGapMs = gapMs,
        recentRssi = rssi,
        bestRssi = rssi.toInt(),
    )

    /** A device that stopped dead rather than fading, which is what a rotation looks like. */
    private fun stopped(address: String, lastSeenMs: Long = t0 + 60_000L): Departure {
        val trail = (0 until 20).map { (lastSeenMs - (19 - it) * 1_000L) to -45 }
        return Handoffs.classify(address, trail, bestDbm = -44)
    }

    private fun consider(
        candidates: List<Identity>,
        previous: Identity = identity("AA:BB:CC:DD:EE:FF"),
        nowMs: Long = t0 + 60_000L + Handoffs.SILENCE_MS + 1_000L,
    ) = OwnKit.consider(
        previous = previous,
        departure = stopped(previous.address, previous.lastSeenMs),
        candidates = candidates,
        label = "My watch",
        nowMs = nowMs,
    )

    private fun successor(address: String, rssi: Double = -45.0, at: Long = t0 + 61_000L) =
        identity(address, firstSeenMs = at, lastSeenMs = at + 20_000L, rssi = rssi)

    // ------------------------------------------------------------------ the loudness gate

    @Test
    fun `a candidate across the room is not the watch on your wrist`() {
        // The constraint no follow has, and the one that removes almost everything a shape
        // match could otherwise reach. It costs nothing: a device genuinely yours and
        // genuinely out of earshot is not affecting any count anyway.
        val (adopted, refused) = consider(listOf(successor("11:22:33:44:55:66", rssi = -85.0)))

        assertNull(adopted)
        assertNotNull(refused)
        assertTrue(refused!!.why.contains("near enough"))
    }

    @Test
    fun `nothing at all nearby is reported rather than reached for`() {
        val (adopted, refused) = consider(emptyList())

        assertNull(adopted)
        assertTrue(refused!!.why.contains("near enough"))
    }

    // ------------------------------------------------------------------ walking away

    @Test
    fun `a device that faded out was left somewhere, not renamed`() {
        // Your own kit does not walk away from you. Hunting for whatever is loud nearby
        // after a genuine fade is how a mark lands on a stranger.
        val fading = (0 until 20).map { index ->
            (t0 + index * 1_000L) to (-45 - index * 2)
        }
        val departure = Handoffs.classify("AA:BB:CC:DD:EE:FF", fading, bestDbm = -44)

        val (adopted, refused) = OwnKit.consider(
            previous = identity("AA:BB:CC:DD:EE:FF", lastSeenMs = t0 + 19_000L),
            departure = departure,
            candidates = listOf(successor("11:22:33:44:55:66")),
            label = "My watch",
            nowMs = t0 + 19_000L + Handoffs.SILENCE_MS + 1_000L,
        )

        assertNull(adopted)
        assertTrue(refused!!.why.contains("faded"))
    }

    // ------------------------------------------------------------------ never guessing

    @Test
    fun `two candidates that both fit means neither is taken`() {
        // A follow would ask, because somebody is standing there watching. Nothing is
        // watching here, so the honest outcome is to let the mark lapse.
        val (adopted, refused) = consider(
            listOf(successor("11:22:33:44:55:66"), successor("77:88:99:AA:BB:CC")),
        )

        assertNull(adopted)
        assertNotNull(refused)
        assertTrue(refused!!.why.contains("nobody is watching"))
    }

    @Test
    fun `the bar is higher than the one a follow uses`() {
        // Stated as a test because the two numbers sit in different files and the whole
        // argument for this feature being safe is that they are not the same number.
        assertTrue(OwnKit.MIN_SHARE > Handoffs.AUTO_SHARE)
        assertTrue(OwnKit.MIN_CONFIDENCE > Handoffs.AUTO_CONFIDENCE)
    }

    // ------------------------------------------------------------------ when it does take one

    @Test
    fun `one loud candidate with a matching fingerprint carries the mark`() {
        val (adopted, refused) = consider(listOf(successor("11:22:33:44:55:66")))

        assertNotNull("nothing was adopted: ${refused?.why}", adopted)
        assertEquals("AA:BB:CC:DD:EE:FF", adopted!!.fromAddress)
        assertEquals("11:22:33:44:55:66", adopted.toAddress)
        assertEquals("My watch", adopted.label)
        assertTrue(adopted.rssi >= OwnKit.ON_PERSON_DBM)
    }

    @Test
    fun `an adoption says what convinced it, because it can be argued with`() {
        val adopted = consider(listOf(successor("11:22:33:44:55:66"))).first!!

        assertTrue(adopted.why.contains("dB"))
        assertTrue(adopted.confidence >= OwnKit.MIN_CONFIDENCE)
    }

    @Test
    fun `a different device that happens to be loud is not adopted`() {
        // Same room, same moment, completely different advertisement.
        val other = successor("11:22:33:44:55:66").copy(
            shape = AdvertShape(companyId = 0x0075, serviceUuids = listOf("180F")),
        )

        assertNull(consider(listOf(other)).first)
    }

    @Test
    fun `still inside the silence window is neither adopted nor refused`() {
        // Nothing has happened yet. Reporting a refusal here would fill the log with
        // devices that are about to come back.
        val (adopted, refused) = consider(
            candidates = listOf(successor("11:22:33:44:55:66")),
            nowMs = t0 + 60_000L + 1_000L,
        )

        assertNull(adopted)
        assertNull(refused)
    }
}
