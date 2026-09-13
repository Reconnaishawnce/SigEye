package com.sigeye.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The aggregation the whole Rotation Lab screen rests on, untested until now.
 *
 * Cohorts are the safe half - a company identifier is a fact in a payload. The rest of
 * this file is about the unsafe half behaving: that a room with nothing rotating in it
 * produces no tracks and no periods, rather than a confident claim about a stranger.
 */
class RotationLabTest {

    private fun shape(company: Int, services: List<String> = listOf("180F")) = AdvertShape(
        companyId = company,
        serviceUuids = services,
        appearance = 0x0040,
        txPower = 12,
        manufacturerLength = 25,
        manufacturerPrefix = "07%02X".format(company and 0xFF),
    )

    /** Feeds [count] packets from one address, every [everyMs], starting at [fromMs]. */
    private fun RotationLab.talk(
        address: String,
        fromMs: Long,
        count: Int,
        everyMs: Long = 152L,
        rssi: Int = -60,
        company: Int = 0x004C,
        vendor: String? = "Apple",
        random: Boolean = true,
    ) {
        var at = fromMs
        repeat(count) {
            observe(
                address = address,
                rssi = rssi,
                atMs = at,
                shape = shape(company),
                isRandom = random,
                payloadVendor = vendor,
                ouiVendor = null,
            )
            at += everyMs
        }
    }

    private val start = 1_700_000_000_000L

    // ------------------------------------------------------------------------ cohorts

    @Test
    fun `an empty room has no cohorts and no tracks`() {
        val lab = RotationLab()
        lab.tick(start)
        assertTrue(lab.cohorts(start).isEmpty())
        assertTrue(lab.tracks().isEmpty())
        assertEquals(0, lab.addressCount)
    }

    @Test
    fun `devices are grouped by who made them`() {
        val lab = RotationLab()
        lab.talk("5A:00:00:00:00:01", start, 20, company = 0x004C, vendor = "Apple")
        lab.talk("5A:00:00:00:00:02", start, 20, company = 0x004C, vendor = "Apple")
        lab.talk("5A:00:00:00:00:03", start, 20, company = 0x00E0, vendor = "Google")
        lab.tick(start + 10_000)

        val cohorts = lab.cohorts(start + 10_000)
        assertEquals(listOf("Apple", "Google"), cohorts.map { it.vendor })
        assertEquals(2, cohorts.first().size)
    }

    @Test
    fun `a fleet that never rotates shows as entirely fixed`() {
        val lab = RotationLab()
        repeat(3) { index ->
            lab.talk(
                "00:25:DF:00:00:0$index",
                start,
                20,
                company = 0x0999,
                vendor = "Beacons Ltd",
                random = false,
            )
        }
        lab.tick(start + 10_000)
        val cohort = lab.cohorts(start + 10_000).first { it.vendor == "Beacons Ltd" }
        assertEquals(0, cohort.randomised)
        assertEquals(3, cohort.fixed)
        assertTrue(cohort.policy().contains("not one of them rotates"))
    }

    @Test
    fun `an address heard once is still counted as present`() {
        val lab = RotationLab()
        lab.talk("5A:00:00:00:00:01", start, 1)
        lab.tick(start + 1_000)
        assertEquals(1, lab.addressCount)
        assertEquals(1, lab.audible(start + 1_000))
    }

    @Test
    fun `an address that has gone quiet stops being audible but is still remembered`() {
        // A track is built from addresses that have already fallen silent, so forgetting
        // them promptly would delete the thing being measured.
        val lab = RotationLab()
        lab.talk("5A:00:00:00:00:01", start, 20)
        val later = start + 5 * 60_000L
        lab.tick(later)
        assertEquals(0, lab.audible(later))
        assertEquals(1, lab.addressCount)
    }

    // ------------------------------------------------------------------------- tracks

    @Test
    fun `a room where nothing rotates produces no tracks at all`() {
        val lab = RotationLab()
        lab.talk("5A:00:00:00:00:01", start, 60)
        lab.talk("5A:00:00:00:00:02", start, 60)
        lab.tick(start + 60_000)
        assertTrue(lab.tracks().isEmpty())
    }

    @Test
    fun `a room with no measurable period says so rather than inventing one`() {
        val lab = RotationLab()
        lab.talk("5A:00:00:00:00:01", start, 60)
        lab.tick(start + 60_000)
        val room = lab.roomRhythm(lab.tracks())
        assertTrue(!room.measurable)
        assertNull(room.medianPeriodMs)
        assertEquals(0, room.tracksWithPhase)
        assertTrue(room.buckets.isEmpty())
    }

    @Test
    fun `a fixed address is never chained to anything`() {
        // A device that does not rotate has no successor to find, and linking one would be
        // a claim about two different pieces of hardware.
        val lab = RotationLab()
        lab.talk("00:25:DF:00:00:01", start, 40, random = false)
        lab.tick(start + 30_000)
        lab.talk("00:25:DF:00:00:02", start + 31_000, 40, random = false)
        lab.tick(start + 70_000)
        assertTrue(lab.tracks().isEmpty())
    }

    @Test
    fun `the trail of an address is what was heard from it`() {
        val lab = RotationLab()
        lab.talk("5A:00:00:00:00:01", start, 12, rssi = -55)
        val trail = lab.trail("5A:00:00:00:00:01")
        assertEquals(12, trail.size)
        assertTrue(trail.all { it.second == -55 })
        assertTrue(trail.zipWithNext().all { (a, b) -> a.first <= b.first })
    }

    @Test
    fun `an address nobody has heard of has an empty trail rather than throwing`() {
        assertTrue(RotationLab().trail("FF:FF:FF:FF:FF:FF").isEmpty())
    }

    @Test
    fun `the export carries a header even with nothing to report`() {
        val csv = RotationLab().csv()
        assertTrue(csv.contains("track,vendor,addresses"))
    }
}
