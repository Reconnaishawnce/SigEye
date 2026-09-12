package com.sigeye.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryEngineTest {

    private fun engine(baselineMs: Long = 30_000L) = DiscoveryEngine(baselineMs = baselineMs)

    /** Keeps a device audible across a span, as a real advertiser would be. */
    private fun DiscoveryEngine.chatter(
        address: String,
        fromMs: Long,
        toMs: Long,
        rssi: Int = -60,
        isRandom: Boolean = false,
        stepMs: Long = 500L,
    ) {
        var at = fromMs
        while (at <= toMs) {
            observe(address, rssi, at, isRandom = isRandom)
            at += stepMs
        }
    }

    // ------------------------------------------------------------ the baseline

    @Test
    fun `everything heard during the baseline becomes furniture`() {
        val engine = engine()
        engine.start(0L)
        engine.chatter("AA", 0L, 5_000L)
        engine.chatter("BB", 0L, 5_000L)
        engine.tick(5_000L)

        assertEquals(DiscoveryStage.BASELINE, engine.stage)
        assertEquals(2, engine.baselineSize)
        assertEquals(0, engine.arrivalCount)
    }

    @Test
    fun `the baseline closes on time and not before`() {
        val engine = engine(baselineMs = 30_000L)
        engine.start(0L)
        engine.tick(29_999L)
        assertEquals(DiscoveryStage.BASELINE, engine.stage)
        engine.tick(30_000L)
        assertEquals(DiscoveryStage.WATCHING, engine.stage)
    }

    @Test
    fun `progress runs from nothing to everything`() {
        val engine = engine(baselineMs = 10_000L)
        engine.start(0L)
        assertEquals(0f, engine.baselineProgress(0L), 0.001f)
        assertEquals(0.5f, engine.baselineProgress(5_000L), 0.001f)
        assertEquals(1f, engine.baselineProgress(20_000L), 0.001f)
    }

    @Test
    fun `nothing is recorded before it is started`() {
        val engine = engine()
        engine.observe("AA", -50, 0L)
        assertEquals(0, engine.baselineSize)
        assertEquals(DiscoveryStage.IDLE, engine.stage)
    }

    // ------------------------------------------------------------- arrivals

    @Test
    fun `only devices unheard of during the baseline surface`() {
        val engine = engine(baselineMs = 10_000L)
        engine.start(0L)
        engine.chatter("KNOWN", 0L, 9_000L)
        engine.tick(10_000L)

        engine.chatter("KNOWN", 11_000L, 15_000L)
        engine.chatter("STRANGER", 11_000L, 15_000L)
        engine.tick(15_000L)

        val arrivals = engine.arrivals()
        assertEquals(1, arrivals.size)
        assertEquals("STRANGER", arrivals.first().sighting.address)
    }

    @Test
    fun `something walking towards you sorts above something that is not`() {
        val engine = engine(baselineMs = 1_000L)
        engine.start(0L)
        engine.tick(1_000L)

        // Steady at arm's length.
        engine.chatter("STEADY", 2_000L, 12_000L, rssi = -55)
        // Getting louder, fast - somebody approaching.
        var at = 2_000L
        var rssi = -90
        while (at <= 12_000L) {
            engine.observe("CLOSING", rssi, at)
            at += 500L
            rssi += 2
        }
        engine.tick(12_000L)

        val arrivals = engine.arrivals()
        assertEquals("CLOSING", arrivals.first().sighting.address)
        assertTrue(arrivals.first().approaching)
        assertTrue(arrivals.first().slopeDbPerSecond > 0)
    }

    @Test
    fun `an arrival goes away once it stops being heard`() {
        val engine = DiscoveryEngine(baselineMs = 1_000L, staleMs = 10_000L)
        engine.start(0L)
        engine.tick(1_000L)
        engine.chatter("PASSERBY", 2_000L, 4_000L)
        engine.tick(4_000L)
        assertEquals(1, engine.arrivalCount)

        engine.tick(20_000L)
        assertEquals(0, engine.arrivalCount)
    }

    @Test
    fun `dismissing an arrival files it as furniture for good`() {
        val engine = engine(baselineMs = 1_000L)
        engine.start(0L)
        engine.tick(1_000L)
        engine.chatter("MINE", 2_000L, 4_000L)
        assertEquals(1, engine.arrivalCount)

        engine.ignore("MINE")
        assertEquals(0, engine.arrivalCount)
        engine.chatter("MINE", 5_000L, 7_000L)
        assertEquals(0, engine.arrivalCount)
    }

    @Test
    fun `absorbing the room clears the list without restarting the baseline`() {
        val engine = engine(baselineMs = 1_000L)
        engine.start(0L)
        engine.tick(1_000L)
        engine.chatter("A", 2_000L, 3_000L)
        engine.chatter("B", 2_000L, 3_000L)
        assertEquals(2, engine.arrivalCount)

        engine.absorbIntoBaseline()
        assertEquals(0, engine.arrivalCount)
        assertEquals(DiscoveryStage.WATCHING, engine.stage)
        engine.chatter("A", 4_000L, 5_000L)
        assertEquals(0, engine.arrivalCount)
    }

    // --------------------------------------------------- randomised addresses

    @Test
    fun `a rotation is flagged as a suspicion rather than swallowed`() {
        val engine = engine(baselineMs = 5_000L)
        engine.start(0L)
        // A phone in the room during the baseline, randomised address.
        engine.chatter("OLD", 0L, 4_000L, rssi = -58, isRandom = true)
        engine.tick(5_000L)

        // It rotates: the old address stops, a new one appears at the same strength.
        engine.chatter("NEW", 20_000L, 22_000L, rssi = -60, isRandom = true)
        engine.tick(22_000L)

        val arrival = engine.arrivals().first { it.sighting.address == "NEW" }
        // Still listed - swallowing it would be worse than mentioning it.
        assertEquals("OLD", arrival.possibleRotationOf)
    }

    @Test
    fun `a public address is never explained away as a rotation`() {
        val engine = engine(baselineMs = 5_000L)
        engine.start(0L)
        engine.chatter("OLD", 0L, 4_000L, rssi = -58, isRandom = true)
        engine.tick(5_000L)
        engine.chatter("FIXED", 20_000L, 22_000L, rssi = -58, isRandom = false)
        engine.tick(22_000L)

        assertNull(engine.arrivals().first().possibleRotationOf)
    }

    @Test
    fun `a stranger at a different distance is not mistaken for a rotation`() {
        val engine = engine(baselineMs = 5_000L)
        engine.start(0L)
        engine.chatter("OLD", 0L, 4_000L, rssi = -80, isRandom = true)
        engine.tick(5_000L)
        // Far too loud to be the same device in the same place.
        engine.chatter("NEW", 20_000L, 22_000L, rssi = -40, isRandom = true)
        engine.tick(22_000L)

        assertNull(engine.arrivals().first().possibleRotationOf)
    }

    @Test
    fun `a rotation long after the old address went quiet is not linked`() {
        val engine = engine(baselineMs = 5_000L)
        engine.start(0L)
        engine.chatter("OLD", 0L, 4_000L, rssi = -58, isRandom = true)
        engine.tick(5_000L)
        // Ten minutes later is a different visit, not a rotation.
        engine.chatter("NEW", 600_000L, 602_000L, rssi = -58, isRandom = true)
        engine.tick(602_000L)

        assertNull(engine.arrivals().first().possibleRotationOf)
    }

    // ------------------------------------------------------------- snapshots

    @Test
    fun `a snapshot captures everything heard, baseline included`() {
        val engine = engine(baselineMs = 1_000L)
        engine.start(0L)
        engine.chatter("A", 0L, 900L)
        engine.tick(1_000L)
        engine.chatter("B", 2_000L, 3_000L)

        val snapshot = engine.snapshot("Kitchen", 3_000L)
        assertEquals("Kitchen", snapshot.label)
        assertEquals(2, snapshot.size)
    }

    @Test
    fun `starting again forgets the previous run entirely`() {
        val engine = engine(baselineMs = 1_000L)
        engine.start(0L)
        engine.chatter("A", 0L, 900L)
        engine.tick(1_000L)
        engine.chatter("B", 2_000L, 3_000L)
        assertEquals(1, engine.arrivalCount)

        engine.start(10_000L)
        assertEquals(0, engine.arrivalCount)
        assertEquals(0, engine.baselineSize)
        assertEquals(DiscoveryStage.BASELINE, engine.stage)
    }

    // ------------------------------------------------- seeding and filtering

    @Test
    fun `a seeded baseline needs no learning period at all`() {
        val engine = engine()
        engine.seedBaseline(listOf("FRIDGE", "TV"), 0L)

        assertEquals(DiscoveryStage.WATCHING, engine.stage)
        assertEquals(2, engine.baselineSize)
        assertTrue(engine.isKnown("fridge"))

        engine.chatter("FRIDGE", 1_000L, 3_000L)
        engine.chatter("BUG", 1_000L, 3_000L)
        engine.tick(3_000L)

        assertEquals(1, engine.arrivalCount)
        assertEquals("BUG", engine.arrivals().first().sighting.address)
    }

    @Test
    fun `seeding replaces whatever went before rather than adding to it`() {
        val engine = engine(baselineMs = 1_000L)
        engine.start(0L)
        engine.chatter("OLD", 0L, 900L)
        engine.tick(1_000L)
        assertEquals(1, engine.baselineSize)

        engine.seedBaseline(listOf("A", "B", "C"), 5_000L)
        assertEquals(3, engine.baselineSize)
        assertTrue(!engine.isKnown("OLD"))
    }

    @Test
    fun `hiding rotations removes only the suspected ones`() {
        val engine = engine(baselineMs = 5_000L)
        engine.start(0L)
        engine.chatter("OLD", 0L, 4_000L, rssi = -58, isRandom = true)
        engine.tick(5_000L)

        // One that looks like OLD having rotated, and one that plainly does not.
        engine.chatter("ROTATED", 20_000L, 22_000L, rssi = -59, isRandom = true)
        engine.chatter("STRANGER", 20_000L, 22_000L, rssi = -40, isRandom = false)
        engine.tick(22_000L)

        assertEquals(2, engine.arrivals().size)
        val filtered = engine.arrivals(hideSuspectedRotations = true)
        assertEquals(1, filtered.size)
        assertEquals("STRANGER", filtered.first().sighting.address)
    }

    @Test
    fun `fixed-only leaves the installed equipment and drops the passers-by`() {
        val engine = engine(baselineMs = 1_000L)
        engine.start(0L)
        engine.tick(1_000L)
        engine.chatter("CAMERA", 2_000L, 4_000L, isRandom = false)
        engine.chatter("PHONE", 2_000L, 4_000L, isRandom = true)
        engine.tick(4_000L)

        val fixed = engine.arrivals(hideRandomAddresses = true)
        assertEquals(1, fixed.size)
        assertEquals("CAMERA", fixed.first().sighting.address)
    }

    @Test
    fun `both filters together are the room-sweep setting`() {
        val engine = engine(baselineMs = 1_000L)
        engine.start(0L)
        engine.tick(1_000L)
        engine.chatter("CAMERA", 2_000L, 4_000L, isRandom = false)
        engine.chatter("PHONE", 2_000L, 4_000L, isRandom = true)
        engine.tick(4_000L)

        val swept = engine.arrivals(
            hideSuspectedRotations = true,
            hideRandomAddresses = true,
        )
        assertEquals(listOf("CAMERA"), swept.map { it.sighting.address })
    }

    // --------------------------------------------------------- what to plot

    @Test
    fun `in-range covers the baseline as well as the arrivals`() {
        val engine = engine(baselineMs = 1_000L)
        engine.start(0L)
        engine.chatter("KNOWN", 0L, 900L)
        engine.tick(1_000L)
        engine.chatter("KNOWN", 2_000L, 3_000L)
        engine.chatter("NEW", 2_000L, 3_000L)

        val plotted = engine.inRange(3_000L).map { it.address }.toSet()
        assertEquals(setOf("KNOWN", "NEW"), plotted)
    }

    @Test
    fun `something long gone is not plotted as though it were still there`() {
        val engine = engine(baselineMs = 1_000L)
        engine.start(0L)
        engine.chatter("GONE", 0L, 900L)
        engine.tick(1_000L)
        assertTrue(engine.inRange(120_000L, withinMs = 20_000L).isEmpty())
    }
}
