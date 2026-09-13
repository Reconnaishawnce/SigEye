package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowingTest {

    private val richShape = AdvertShape(
        companyId = 0x004C,
        serviceUuids = listOf("180F", "FD6F"),
        appearance = 0x0040,
        txPower = 12,
        manufacturerLength = 25,
        manufacturerPrefix = "0715",
    )

    private val plainShape = AdvertShape(companyId = 0x004C)

    private fun identity(
        address: String,
        shape: AdvertShape = richShape,
        firstSeenMs: Long = 0L,
        lastSeenMs: Long = 60_000L,
        gapMs: Long = 152L,
        rssi: Double = -60.0,
        isRandom: Boolean = true,
    ) = Identity(
        address = address,
        shape = shape,
        isRandom = isRandom,
        firstSeenMs = firstSeenMs,
        lastSeenMs = lastSeenMs,
        packets = 200,
        medianGapMs = gapMs,
        recentRssi = rssi,
        bestRssi = rssi.toInt(),
    )

    /** The device we are following, quiet since one minute in. */
    private val followed = identity("5A:11:11:11:11:11")

    /** Its next address: same firmware, appeared just after, same distance away. */
    private fun successor(
        address: String = "5B:22:22:22:22:22",
        shape: AdvertShape = richShape,
        gapMs: Long = 152L,
        rssi: Double = -62.0,
        firstSeenMs: Long = 62_000L,
    ) = identity(
        address = address,
        shape = shape,
        firstSeenMs = firstSeenMs,
        lastSeenMs = firstSeenMs + 40_000L,
        gapMs = gapMs,
        rssi = rssi,
    )

    private val now = 90_000L

    // ------------------------------------------------------------------- accepting

    @Test
    fun `a clean handover is followed`() {
        val decision = Following.decide(followed, listOf(successor()), now)
        assertTrue(decision.toString(), decision is FollowDecision.Reacquired)
        assertEquals(
            "5B:22:22:22:22:22",
            (decision as FollowDecision.Reacquired).address,
        )
        assertEquals(LinkConfidence.STRONG, decision.score.confidence)
    }

    @Test
    fun `the record carries the evidence, not just the answer`() {
        val decision = Following.decide(followed, listOf(successor()), now)
            as FollowDecision.Reacquired
        val handover = Following.record("the car", followed.address, decision, now)
        assertEquals("the car", handover.label)
        assertEquals(followed.address, handover.fromAddress)
        assertEquals("5B:22:22:22:22:22", handover.toAddress)
        assertTrue(handover.points >= 10)
        assertTrue(handover.reasons.isNotEmpty())
        assertTrue(handover.id.contains(followed.address))
    }

    // ------------------------------------------------------------------- refusing

    @Test
    fun `a fixed address has no rotation to follow`() {
        val decision = Following.decide(
            identity("AA:BB:CC:DD:EE:FF", isRandom = false),
            listOf(successor()),
            now,
        )
        assertEquals(
            FollowRefusal.NOT_RANDOM,
            (decision as FollowDecision.Refused).refusal,
        )
    }

    @Test
    fun `a featureless advertisement is never followed`() {
        val decision = Following.decide(
            identity("5A:11:11:11:11:11", shape = plainShape),
            listOf(successor(shape = plainShape)),
            now,
        )
        assertEquals(
            FollowRefusal.TOO_PLAIN,
            (decision as FollowDecision.Refused).refusal,
        )
    }

    @Test
    fun `a device still on the air is not replaced`() {
        // Ten seconds of quiet is a missed packet or two, not a rotation. Acting here
        // would let a lookalike steal the name while the real device is still talking.
        val decision = Following.decide(followed, listOf(successor()), 70_000L)
        assertEquals(
            FollowRefusal.STILL_TALKING,
            (decision as FollowDecision.Refused).refusal,
        )
    }

    @Test
    fun `a cold trail is given up rather than picked up later`() {
        val decision = Following.decide(followed, listOf(successor()), 60_000L + 20 * 60_000L)
        assertEquals(FollowRefusal.LOST, (decision as FollowDecision.Refused).refusal)
    }

    @Test
    fun `nothing matching means nothing happens`() {
        val different = successor(shape = AdvertShape(companyId = 0x0006, appearance = 1, txPower = 4, manufacturerLength = 9, manufacturerPrefix = "AABB"), gapMs = 1000L)
        val decision = Following.decide(followed, listOf(different), now)
        assertEquals(
            FollowRefusal.NO_CANDIDATE,
            (decision as FollowDecision.Refused).refusal,
        )
    }

    @Test
    fun `two equally convincing candidates stop the follow rather than picking one`() {
        // A cafe full of the same phone is precisely when guessing would be wrong, and
        // precisely when a best-of-two rule would produce a confident wrong answer.
        val decision = Following.decide(
            followed,
            listOf(successor("5B:22:22:22:22:22"), successor("5C:33:33:33:33:33")),
            now,
        )
        val refused = decision as FollowDecision.Refused
        assertEquals(FollowRefusal.AMBIGUOUS, refused.refusal)
        assertTrue(refused.message.contains("2 candidates"))
    }

    @Test
    fun `something already on the air before the handover is never adopted`() {
        // Same model, same firmware, been sitting there for a minute. It is a different
        // phone, and the only thing that says so is when it appeared.
        val bystander = successor(firstSeenMs = 0L)
        val decision = Following.decide(followed, listOf(bystander), now)
        assertEquals(
            FollowRefusal.NO_CANDIDATE,
            (decision as FollowDecision.Refused).refusal,
        )
    }

    @Test
    fun `merely likely is not enough`() {
        // Same shape, but a different advertising interval and twenty dB further away:
        // enough evidence to be interesting and not enough to move somebody's name.
        val weak = successor(gapMs = 1000L, rssi = -85.0)
        val score = Fingerprint.score(followed, weak)
        assertTrue(
            "confidence was ${score.confidence}",
            score.confidence.ordinal < LinkConfidence.STRONG.ordinal,
        )
        val decision = Following.decide(followed, listOf(weak), now)
        assertTrue(decision is FollowDecision.Refused)
    }

    @Test
    fun `the device's own address is not offered as its own successor`() {
        val decision = Following.decide(followed, listOf(followed), now)
        assertEquals(
            FollowRefusal.NO_CANDIDATE,
            (decision as FollowDecision.Refused).refusal,
        )
    }

    @Test
    fun `a refusal always says why`() {
        FollowRefusal.entries.forEach { refusal ->
            assertTrue(refusal.name, refusal.reason.isNotBlank())
        }
    }
}
