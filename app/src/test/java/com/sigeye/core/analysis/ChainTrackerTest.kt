package com.sigeye.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainTrackerTest {

    private fun shapeFor(name: String, company: Int = 0x004C) = AdvertShape(
        companyId = company,
        serviceUuids = listOf("180f", "180a"),
        name = name,
        manufacturerLength = 25,
        manufacturerPrefix = "0215",
    )

    /** Seeded with everything, for the tests that are about chaining rather than scope. */
    private fun tracker(vararg seeds: String) = ChainTracker(silenceMs = 25_000L).apply {
        seed(if (seeds.isEmpty()) ALL_SEEDS else seeds.toList())
    }

    private val ALL_SEEDS = listOf(
        "AA", "A1", "A2", "B1", "B2", "FIXED1", "RANDOM", "LONE0", "LONE1", "LONE2",
        "LONE3", "LONE4", "LONE5",
    )

    /** One address advertising steadily across a window. */
    private fun ChainTracker.advertise(
        address: String,
        fromMs: Long,
        toMs: Long,
        shape: AdvertShape,
        rssi: Int = -55,
        intervalMs: Long = 100L,
        isRandom: Boolean = true,
    ) {
        var at = fromMs
        while (at <= toMs) {
            observe(address, rssi, at, shape, isRandom)
            at += intervalMs
        }
    }

    // ---------------------------------------------------------------- chaining

    @Test
    fun `a device wearing three addresses becomes one chain`() {
        val tracker = tracker()
        val shape = shapeFor("Phone")
        tracker.advertise("AA", 0L, 60_000L, shape)
        tracker.advertise("BB", 62_000L, 120_000L, shape)
        tracker.tick(95_000L)
        tracker.advertise("CC", 122_000L, 180_000L, shape)
        tracker.tick(155_000L)

        val chains = tracker.chains()
        assertEquals(1, chains.size)
        assertEquals(listOf("AA", "BB", "CC"), chains.first().addresses)
        assertEquals(2, chains.first().rotations)
        assertEquals("CC", chains.first().current)
    }

    @Test
    fun `two devices in the room stay two chains`() {
        // The failure this guards against: every iPhone in a cafe looks alike, and letting
        // several chains claim one successor collapses the room into one imaginary device.
        val tracker = tracker()
        val phone = shapeFor("Phone", company = 0x004C)
        val watch = shapeFor("Watch", company = 0x004C)

        tracker.advertise("A1", 0L, 60_000L, phone)
        tracker.advertise("B1", 0L, 60_000L, watch)
        tracker.advertise("A2", 62_000L, 120_000L, phone)
        tracker.advertise("B2", 62_000L, 120_000L, watch)
        tracker.tick(95_000L)

        val chains = tracker.chains()
        assertEquals(2, chains.size)
        assertTrue(chains.any { it.addresses == listOf("A1", "A2") })
        assertTrue(chains.any { it.addresses == listOf("B1", "B2") })
    }

    @Test
    fun `one successor cannot be claimed by two chains`() {
        val tracker = tracker()
        val shape = shapeFor("Identical")
        tracker.advertise("A1", 0L, 60_000L, shape, rssi = -55)
        tracker.advertise("A2", 0L, 60_000L, shape, rssi = -56)
        tracker.advertise("ONLY", 62_000L, 120_000L, shape, rssi = -55)
        tracker.tick(95_000L)

        val claims = tracker.chains().count { it.addresses.contains("ONLY") }
        assertEquals(1, claims)
    }

    @Test
    fun `a fixed address is never chained`() {
        val tracker = tracker()
        val shape = shapeFor("Beacon")
        tracker.advertise("FIXED1", 0L, 60_000L, shape, isRandom = false)
        tracker.advertise("FIXED2", 62_000L, 120_000L, shape, isRandom = false)
        tracker.tick(95_000L)
        assertTrue(tracker.chains().isEmpty())
    }

    @Test
    fun `an unrelated device does not extend a chain`() {
        val tracker = tracker()
        tracker.advertise("AA", 0L, 60_000L, shapeFor("Phone"))
        tracker.advertise(
            "KETTLE",
            62_000L,
            120_000L,
            AdvertShape(companyId = 0x0006, name = "Kettle"),
            rssi = -90,
            intervalMs = 1_000L,
        )
        tracker.tick(95_000L)
        assertTrue(tracker.chains().isEmpty())
    }

    // ----------------------------------------------------------- the schedule

    @Test
    fun `the rotation period is the mean life of a finished address`() {
        // The schedule is itself a fingerprint: iOS keeps to roughly fifteen minutes,
        // other systems do not.
        val tracker = tracker()
        val shape = shapeFor("Phone")
        tracker.advertise("AA", 0L, 600_000L, shape, intervalMs = 5_000L)
        tracker.advertise("BB", 602_000L, 1_200_000L, shape, intervalMs = 5_000L)
        tracker.tick(640_000L)
        tracker.advertise("CC", 1_202_000L, 1_800_000L, shape, intervalMs = 5_000L)
        tracker.tick(1_240_000L)

        val chain = tracker.chains().first()
        assertEquals(3, chain.addresses.size)
        // Each finished address lasted about ten minutes.
        assertEquals(600_000.0, chain.rotationPeriodMs.toDouble(), 30_000.0)
        assertTrue(chain.describePeriod().contains("10 minutes"))
    }

    @Test
    fun `a chain that has not rotated reports no period rather than zero minutes`() {
        val chain = Chain(
            id = 1,
            links = listOf(ChainLink("AA", 0L, 60_000L)),
            shape = AdvertShape(),
            label = "AA",
            lastRssi = -60,
        )
        assertEquals(0L, chain.rotationPeriodMs)
        assertTrue(chain.describePeriod().contains("not seen to rotate"))
    }

    @Test
    fun `a chain is only as good as its weakest link`() {
        val strong = LinkScore(emptyList(), LinkConfidence.STRONG)
        val likely = LinkScore(emptyList(), LinkConfidence.LIKELY)
        val chain = Chain(
            id = 1,
            links = listOf(
                ChainLink("AA", 0L, 10L),
                ChainLink("BB", 10L, 20L, strong),
                ChainLink("CC", 20L, 30L, likely),
            ),
            shape = AdvertShape(),
            label = "x",
            lastRssi = -60,
        )
        assertEquals(LinkConfidence.LIKELY, chain.weakestLink)
    }

    // ------------------------------------------------------- honest denominators

    @Test
    fun `unlinked addresses are counted, because they are most of any room`() {
        val tracker = tracker()
        repeat(6) { index ->
            tracker.advertise(
                "LONE$index",
                0L,
                30_000L,
                shapeFor("Phone$index", company = 0x0100 + index),
            )
        }
        assertEquals(6, tracker.unlinked(30_000L))
        assertTrue(tracker.chains().isEmpty())
    }

    @Test
    fun `fixed addresses are counted apart from the rest`() {
        val tracker = tracker()
        tracker.advertise("RANDOM", 0L, 30_000L, shapeFor("Phone"))
        tracker.advertise("FIXED", 0L, 30_000L, shapeFor("Camera"), isRandom = false)
        assertEquals(1, tracker.fixedCount(30_000L))
        assertEquals(1, tracker.unlinked(30_000L))
    }

    @Test
    fun `only watchlisted addresses start a chain`() {
        // The change that made this usable: chaining everything produced a wall of claims
        // about strangers' phones that nobody could check.
        val tracker = ChainTracker(silenceMs = 25_000L)
        tracker.seed(listOf("WATCHED"))
        val shape = shapeFor("Phone")

        tracker.advertise("WATCHED", 0L, 60_000L, shape)
        tracker.advertise("WATCHED2", 62_000L, 120_000L, shape)
        tracker.advertise("STRANGER", 0L, 60_000L, shapeFor("Other", company = 0x0006))
        tracker.advertise("STRANGER2", 62_000L, 120_000L, shapeFor("Other", company = 0x0006))
        tracker.tick(95_000L)

        val chains = tracker.chains()
        assertEquals(1, chains.size)
        assertEquals(listOf("WATCHED", "WATCHED2"), chains.first().addresses)
    }

    @Test
    fun `a chain already running is followed even after its seed is removed`() {
        // Abandoning a device halfway through a journey loses the thing being measured.
        val tracker = ChainTracker(silenceMs = 25_000L)
        tracker.seed(listOf("AA"))
        val shape = shapeFor("Phone")
        tracker.advertise("AA", 0L, 60_000L, shape)
        tracker.advertise("BB", 62_000L, 120_000L, shape)
        tracker.tick(95_000L)

        tracker.seed(emptyList())
        tracker.advertise("CC", 122_000L, 180_000L, shape)
        tracker.tick(155_000L)

        assertEquals(listOf("AA", "BB", "CC"), tracker.chains().first().addresses)
    }

    @Test
    fun `seeds in range are counted, so the screen can say it is watching nothing`() {
        val tracker = ChainTracker(silenceMs = 25_000L)
        tracker.seed(listOf("WATCHED"))
        tracker.advertise("WATCHED", 0L, 30_000L, shapeFor("Phone"))
        tracker.advertise("OTHER", 0L, 30_000L, shapeFor("Other"))
        assertEquals(1, tracker.seedsInRange(30_000L))
        assertEquals(1, tracker.seedCount)
    }

    @Test
    fun `resetting clears every chain`() {
        val tracker = tracker()
        val shape = shapeFor("Phone")
        tracker.advertise("AA", 0L, 60_000L, shape)
        tracker.advertise("BB", 62_000L, 120_000L, shape)
        tracker.tick(95_000L)
        assertTrue(tracker.chains().isNotEmpty())

        tracker.reset()
        assertTrue(tracker.chains().isEmpty())
        assertEquals(0, tracker.addressCount)
    }

    @Test
    fun `the export carries every link with its confidence`() {
        val tracker = tracker()
        val shape = shapeFor("Phone")
        tracker.advertise("AA", 0L, 60_000L, shape)
        tracker.advertise("BB", 62_000L, 120_000L, shape)
        tracker.tick(95_000L)

        val csv = tracker.csv()
        assertTrue(csv.contains("chain,position,address,started_ms,ended_ms,confidence,points"))
        assertTrue(csv.contains("SEED"))
        assertTrue(csv.contains("AA"))
        assertTrue(csv.contains("BB"))
        csv.trim().lines().drop(2).forEach {
            assertEquals("wrong column count in: $it", 7, it.split(',').size)
        }
    }
}
