package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CohortsTest {

    private fun member(
        address: String,
        payloadVendor: String? = null,
        ouiVendor: String? = null,
        isRandom: Boolean = true,
        intervalMs: Long = 152L,
        shapeKey: String = "shape-a",
        rotations: Int = 0,
    ) = CohortMember(address, payloadVendor, ouiVendor, isRandom, intervalMs, shapeKey, rotations)

    // ----------------------------------------------------------- who made this

    @Test
    fun `a randomised address is named by its payload, never by its prefix`() {
        // The prefix of a random address belongs to nobody. Reading a vendor out of it is
        // how a scanner reports a street full of companies that do not exist.
        val hidden = member("5A:11:22:33:44:55", payloadVendor = null, ouiVendor = "Cisco")
        assertEquals(Cohorts.UNKNOWN, Cohorts.vendorOf(hidden))
    }

    @Test
    fun `a fixed address may be named by its prefix`() {
        val fixed = member("00:25:DF:11:22:33", ouiVendor = "Axon", isRandom = false)
        assertEquals("Axon", Cohorts.vendorOf(fixed))
    }

    @Test
    fun `the payload wins over the prefix when both exist`() {
        // A prefix belongs to whoever made the radio module; a company identifier is a
        // deliberate statement about the product.
        val both = member(
            "00:11:22:33:44:55",
            payloadVendor = "Apple",
            ouiVendor = "Liteon",
            isRandom = false,
        )
        assertEquals("Apple", Cohorts.vendorOf(both))
    }

    @Test
    fun `a payload vendor survives the address being random`() {
        val apple = member("5A:11:22:33:44:55", payloadVendor = "Apple")
        assertEquals("Apple", Cohorts.vendorOf(apple))
    }

    // ------------------------------------------------------------ the grouping

    @Test
    fun `cohorts are biggest first, with the unidentified pile last`() {
        val cohorts = Cohorts.build(
            listOf(
                member("A1", payloadVendor = "Apple"),
                member("A2", payloadVendor = "Apple"),
                member("A3", payloadVendor = "Apple"),
                member("G1", payloadVendor = "Google"),
                member("U1"),
                member("U2"),
                member("U3"),
                member("U4"),
                member("U5"),
            ),
        )
        assertEquals(listOf("Apple", "Google", Cohorts.UNKNOWN), cohorts.map { it.vendor })
    }

    @Test
    fun `how much of the room could be attributed at all is reported`() {
        val cohorts = Cohorts.build(
            listOf(
                member("A1", payloadVendor = "Apple"),
                member("U1"),
                member("U2"),
                member("U3"),
            ),
        )
        assertEquals(0.25f, Cohorts.identifiedFraction(cohorts), 0.001f)
        assertEquals(0f, Cohorts.identifiedFraction(emptyList()), 0.001f)
    }

    // --------------------------------------------------------- what it reveals

    @Test
    fun `a fleet that never rotates is reported as trackable`() {
        val cohorts = Cohorts.build(
            listOf(
                member("B1", payloadVendor = "CheapBeacon", isRandom = false),
                member("B2", payloadVendor = "CheapBeacon", isRandom = false),
                member("B3", payloadVendor = "CheapBeacon", isRandom = false),
                member("A1", payloadVendor = "Apple"),
                member("A2", payloadVendor = "Apple"),
            ),
        )
        val trackable = Cohorts.trackable(cohorts)
        assertEquals(listOf("CheapBeacon"), trackable.map { it.vendor })
        assertTrue(trackable.first().policy().contains("not one of them rotates"))
    }

    @Test
    fun `a single device is not a fleet`() {
        val cohorts = Cohorts.build(listOf(member("B1", payloadVendor = "Thing", isRandom = false)))
        assertTrue(Cohorts.trackable(cohorts).isEmpty())
    }

    @Test
    fun `the unidentified pile is never reported as a trackable fleet`() {
        // It is a measure of what could not be worked out, not a finding about a maker.
        val cohorts = Cohorts.build(
            (1..5).map { member("U$it", isRandom = false) },
        )
        assertTrue(Cohorts.trackable(cohorts).isEmpty())
    }

    @Test
    fun `a mixed fleet says how it splits`() {
        val cohort = Cohorts.build(
            listOf(
                member("M1", payloadVendor = "Mixed", isRandom = true),
                member("M2", payloadVendor = "Mixed", isRandom = false),
                member("M3", payloadVendor = "Mixed", isRandom = false),
            ),
        ).first()
        assertEquals(1, cohort.randomised)
        assertEquals(2, cohort.fixed)
        assertEquals(1f / 3f, cohort.privacyFraction, 0.001f)
        assertTrue(cohort.policy().contains("1 of 3 rotate"))
    }

    @Test
    fun `distinct advertisement structures are counted as distinct products`() {
        val cohort = Cohorts.build(
            listOf(
                member("A1", payloadVendor = "Apple", shapeKey = "watch"),
                member("A2", payloadVendor = "Apple", shapeKey = "watch"),
                member("A3", payloadVendor = "Apple", shapeKey = "phone"),
                member("A4", payloadVendor = "Apple", shapeKey = "buds"),
            ),
        ).first()
        assertEquals(3, cohort.shapes)
        assertTrue(Cohorts.describe(cohort).contains("3 shapes"))
    }

    @Test
    fun `the vendor's usual advertising interval is the median of its fleet`() {
        val cohort = Cohorts.build(
            listOf(
                member("A1", payloadVendor = "Apple", intervalMs = 150),
                member("A2", payloadVendor = "Apple", intervalMs = 152),
                member("A3", payloadVendor = "Apple", intervalMs = 1000),
                member("A4", payloadVendor = "Apple", intervalMs = 0),
            ),
        ).first()
        // The zero is not a measurement and does not get a vote.
        assertEquals(152L, cohort.medianIntervalMs)
    }

    @Test
    fun `an empty cohort divides by nothing`() {
        val empty = Cohort("Nobody", emptyList())
        assertEquals(0f, empty.privacyFraction, 0.001f)
        assertEquals(0L, empty.medianIntervalMs)
        assertEquals("nothing here", empty.policy())
    }
}
