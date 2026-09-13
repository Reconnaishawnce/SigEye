package com.sigeye.core.analysis.identity

import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a candidate's case actually amounts to, rather than how many are left.
 *
 * The count on the screen answers "how many have not been eliminated", which flatters
 * itself: being on the list is the absence of a reason to leave, not evidence of being
 * right. These are about the difference.
 */
class ScoringTest {

    private val t0 = 1_700_000_000_000L
    private val tuning = FollowTuning.DEFAULT

    private fun candidate(
        address: String,
        heldMinutes: Long = 5,
        arrived: Boolean = false,
        walkBys: List<CandidateWalkBy> = emptyList(),
        orbits: List<CandidateOrbit> = emptyList(),
        addresses: List<String> = listOf(address),
        rssi: Double = -60.0,
        spread: Double = 20.0,
        packets: Int = 300,
        returned: Long? = null,
        dropped: Long? = null,
    ) = FollowCandidate(
        address = address,
        label = null,
        vendor = null,
        isRandom = true,
        packets = packets,
        meanRssi = rssi,
        recentRssi = rssi,
        closeFraction = 0.1,
        spreadDb = spread,
        firstSeenMs = t0,
        lastSeenMs = t0 + heldMinutes * 60_000L,
        addresses = addresses,
        inPool = true,
        droppedAtMs = dropped,
        returnedAtMs = returned,
        arrived = arrived,
        orbits = orbits,
        walkBys = walkBys,
    )

    /** A walk-by that either rose in the middle and came back down, or stayed flat. */
    private fun walkBy(passed: Boolean) = CandidateWalkBy(
        probeIndex = 0,
        score = WalkByScore(
            packets = 60,
            startDbm = -78.0,
            peakDbm = if (passed) -58.0 else -77.0,
            peakAtMs = t0 + 10_000L,
            endDbm = -78.0,
            midAtMs = t0 + 10_000L,
            walkMs = 20_000L,
        ),
        trail = emptyList(),
    )

    /** A circle either held all the way round, or swinging like something off to one side. */
    private fun orbit(centred: Boolean) = CandidateOrbit(
        probeIndex = 0,
        score = OrbitScore(
            arcsHeard = 8,
            arcsTotal = 8,
            packets = 120,
            meanRssi = -62.0,
            spreadDb = if (centred) 3.0 else 24.0,
        ),
    )

    private fun score(vararg people: FollowCandidate) =
        Scoring.score(people.toList(), tuning, t0 + 5 * 60_000L)

    // ------------------------------------------------------------------ surviving is not evidence

    @Test
    fun `a device that has only survived is not called probable`() {
        val only = score(candidate("AA", heldMinutes = 4)).single()

        assertEquals(Odds.STILL_HERE, only.odds)
        assertTrue(only.headline().contains("not the same as evidence"))
    }

    @Test
    fun `turning up after the baseline is worth more than surviving it`() {
        val results = score(
            candidate("AA", heldMinutes = 5),
            candidate("BB", heldMinutes = 5, arrived = true),
        )

        assertEquals("BB", results.first().address)
        assertTrue(results.first().points > results.last().points)
    }

    // ------------------------------------------------------------------ evidence runs both ways

    @Test
    fun `failing a walk-by rules a device out rather than merely not helping it`() {
        // The one test something on that person cannot fail. Before this, a device that
        // failed sat on the list looking exactly like one nothing was known about.
        val results = score(
            candidate("AA", heldMinutes = 20, arrived = true, walkBys = listOf(walkBy(false))),
            candidate("BB", heldMinutes = 2),
        )

        val failed = results.single { it.address == "AA" }
        assertEquals(Odds.RULED_OUT, failed.odds)
        assertTrue("even with everything else going for it", failed.points < 20.0)
        assertTrue(failed.against.isNotEmpty())
    }

    @Test
    fun `swinging around during an orbit counts against`() {
        val results = score(
            candidate("AA", heldMinutes = 10, orbits = listOf(orbit(false))),
            candidate("BB", heldMinutes = 10, orbits = listOf(orbit(true))),
        )

        assertEquals("BB", results.first().address)
        assertTrue(results.single { it.address == "AA" }.against.isNotEmpty())
    }

    @Test
    fun `something that never moves relative to you is ruled out as your own`() {
        val mine = score(
            candidate(
                "AA",
                heldMinutes = 10,
                spread = 3.0,
                rssi = -40.0,
                packets = 600,
            ),
        ).single()

        assertEquals(Odds.RULED_OUT, mine.odds)
    }

    @Test
    fun `dropping out and coming back counts against, mildly`() {
        val results = score(
            candidate("AA", heldMinutes = 10, returned = t0 + 100_000),
            candidate("BB", heldMinutes = 10),
        )

        assertEquals("BB", results.first().address)
        assertTrue(results.single { it.address == "AA" }.odds != Odds.RULED_OUT)
    }

    // ------------------------------------------------------------------ rotations

    @Test
    fun `surviving a rotation is worth more than surviving time`() {
        // A rotation is a moment at which a coincidence usually ends. Something keeping
        // pace by chance does not change address in the same instant and carry on.
        val results = score(
            candidate("AA", heldMinutes = 10),
            candidate("BB", heldMinutes = 10, addresses = listOf("XX", "YY", "BB")),
        )

        assertEquals("BB", results.first().address)
        assertTrue(results.first().supporting.any { it.text.contains("address changes") })
    }

    // ------------------------------------------------------------------ two ways to stand out

    @Test
    fun `a lot of evidence stands out however crowded it is`() {
        val crowd = (1..12).map { candidate("C$it", heldMinutes = 3) }
        val strong = candidate(
            "AA",
            heldMinutes = 15,
            arrived = true,
            walkBys = listOf(walkBy(true)),
            orbits = listOf(orbit(true)),
        )

        val results = Scoring.score(crowd + strong, tuning, t0 + 15 * 60_000L)

        assertEquals(Odds.STANDOUT, results.first().odds)
        assertEquals("AA", results.first().address)
    }

    @Test
    fun `less evidence also stands out once almost nothing is left`() {
        // Eliminating forty things is itself the measurement. That is what the walk was for,
        // and a score that ignored it would treat the end of a follow like the start.
        val thin = Scoring.score(
            listOf(
                candidate("AA", heldMinutes = 12, orbits = listOf(orbit(true))),
                candidate("BB", heldMinutes = 3),
            ),
            tuning,
            t0 + 12 * 60_000L,
        )

        assertEquals(Odds.STANDOUT, thin.first().odds)

        val crowded = Scoring.score(
            listOf(candidate("AA", heldMinutes = 12, orbits = listOf(orbit(true)))) +
                (1..10).map { candidate("C$it", heldMinutes = 3) },
            tuning,
            t0 + 12 * 60_000L,
        )

        assertEquals(
            "the same evidence, in a crowd, is not a standout",
            Odds.PROBABLE,
            crowded.first().odds,
        )
    }

    // ------------------------------------------------------------------ one pocket

    /** A walk: levels wandering with body shadowing and multipath. */
    private fun walk(seed: Double, offset: Int = 0, from: Long = t0) =
        (0..59).map { step ->
            val t = from + step * 5_000L
            val level = -60 + (12 * sin(step * 0.4 + seed)).toInt() + offset
            t to level
        }

    @Test
    fun `two things in one pocket reinforce each other instead of competing`() {
        // They are shadowed by the same body and reflected by the same walls, so their
        // levels rise and fall together. Treating them as rivals splitting the evidence is
        // backwards: each is a reason to believe the other.
        val phone = candidate("AA", heldMinutes = 10, rssi = -60.0)
        val watch = candidate("BB", heldMinutes = 10, rssi = -66.0)
        val stranger = candidate("CC", heldMinutes = 10, rssi = -62.0)

        val results = Scoring.score(
            listOf(phone, watch, stranger),
            tuning,
            t0 + 10 * 60_000L,
            trails = mapOf(
                "AA" to walk(0.0),
                "BB" to walk(0.0, offset = -6),
                "CC" to walk(2.5),
            ),
        )

        val pair = results.filter { it.address in setOf("AA", "BB") }
        assertTrue("both found each other", pair.all { it.companions.isNotEmpty() })
        assertTrue(
            "and the loner did not",
            results.single { it.address == "CC" }.companions.isEmpty(),
        )
        assertTrue(
            "so the pocket outranks the stranger",
            pair.all { it.points > results.single { r -> r.address == "CC" }.points },
        )
    }

    @Test
    fun `things merely in the same place are not called one pocket`() {
        // Everything in a lift gets quiet at once. Correlation alone would link a street to
        // itself, which is why the levels have to be close as well.
        val near = candidate("AA", rssi = -45.0)
        val far = candidate("BB", rssi = -88.0)

        val results = Scoring.score(
            listOf(near, far),
            tuning,
            t0 + 5 * 60_000L,
            trails = mapOf("AA" to walk(0.0), "BB" to walk(0.0, offset = -43)),
        )

        assertTrue(results.all { it.companions.isEmpty() })
    }

    @Test
    fun `two flat lines are not a pair`() {
        // A trail that never moved has no shape to match, and dividing by its zero variance
        // would report any two of them as perfect partners.
        val flat = (0..20).associate { it.toLong() to -60.0 }

        assertNull(Scoring.correlation(flat, flat))
    }

    @Test
    fun `trails that barely overlap are not correlated at all`() {
        val early = Scoring.bucket(walk(0.0, from = t0))
        val late = Scoring.bucket(walk(0.0, from = t0 + 20 * 60_000L))

        assertNull(Scoring.correlation(early, late))
    }

    @Test
    fun `a crowd is not searched for pockets`() {
        // Every pair against every other, twice a second, for an answer nobody can use: at
        // forty candidates you are looking at a crowd rather than at somebody's pocket.
        val crowd = (1..Scoring.COMPANION_LIMIT + 1).map { candidate("C$it") }

        assertTrue(Scoring.companions(crowd, emptyMap()).isEmpty())
    }
}
