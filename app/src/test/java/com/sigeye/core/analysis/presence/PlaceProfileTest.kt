package com.sigeye.core.analysis.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceProfileTest {

    private val bucket = 10 * 60_000L

    private fun profile() = PlaceProfile(bucketMs = bucket)

    /** Puts a device in a run of slices, at a steady level unless told otherwise. */
    private fun PlaceProfile.present(
        address: String,
        buckets: IntRange,
        rssi: (Int) -> Int = { -60 },
        perBucket: Int = 5,
    ) {
        buckets.forEach { index ->
            repeat(perBucket) { step ->
                observe(address, rssi(index), index * bucket + step * 1000L)
            }
        }
    }

    // ----------------------------------------------------------- busy and quiet

    @Test
    fun `finds the busiest and quietest slices`() {
        val place = profile()
        place.start(0L)
        // Two residents throughout, and a rush of visitors in slice three.
        place.present("FRIDGE", 0..5)
        place.present("TV", 0..5)
        repeat(6) { visitor -> place.present("V$visitor", 3..3) }

        val report = place.report(5 * bucket)
        assertEquals(3, report.busiest!!.index)
        assertEquals(8, report.busiest!!.devices)
        assertEquals(2, report.quietest!!.devices)
    }

    @Test
    fun `arrivals count a device once, however often it blinks`() {
        // A device that drops out and comes back is not a new arrival each time, and
        // counting it as one makes churn meaningless.
        val place = profile()
        place.start(0L)
        place.present("FLICKER", 0..0)
        place.present("FLICKER", 2..2)
        place.present("FLICKER", 4..4)

        val report = place.report(4 * bucket)
        assertEquals(1, report.buckets.sumOf { it.arrivals })
        assertEquals(0, report.buckets[2].arrivals)
    }

    @Test
    fun `departures are counted when something stops being heard`() {
        val place = profile()
        place.start(0L)
        place.present("LEAVER", 0..1)
        place.present("STAYER", 0..4)

        val report = place.report(4 * bucket)
        assertEquals(1, report.buckets[2].departures)
        assertEquals(0, report.buckets[3].departures)
    }

    @Test
    fun `the median ignores slices where nothing was recorded`() {
        val place = profile()
        place.start(0L)
        place.present("A", 0..2)
        place.present("B", 0..2)
        // Slices three and four have nothing at all - the phone was somewhere else, or
        // the radio dropped. Counting those as quiet would halve the median.
        val report = place.report(4 * bucket)
        assertEquals(2.0, report.medianDevices, 0.001)
    }

    // ------------------------------------------------------ traffic and fixtures

    @Test
    fun `something present throughout is a fixture, something passing is not`() {
        val place = profile()
        place.start(0L)
        place.present("BOILER", 0..9)
        place.present("PASSERBY", 4..4)

        val report = place.report(9 * bucket)
        val boiler = report.residents.first { it.address == "BOILER" }
        assertTrue(boiler.fixture)
        assertEquals(1f, boiler.presenceFraction, 0.001f)
        // One slice is not enough to be a resident at all.
        assertTrue(report.residents.none { it.address == "PASSERBY" })
    }

    @Test
    fun `a device needs several slices before it is called a resident`() {
        val place = profile()
        place.start(0L)
        place.present("BRIEF", 0..1)
        place.present("SETTLED", 0..2)

        val report = place.report(5 * bucket)
        assertTrue(report.residents.none { it.address == "BRIEF" })
        assertTrue(report.residents.any { it.address == "SETTLED" })
    }

    // --------------------------------------------------- something that moved

    @Test
    fun `a fixture whose signal steps abruptly is flagged as having moved`() {
        // The finding this exists for: something that had been sitting still was moved.
        val place = profile()
        place.start(0L)
        place.present("SHELF", 0..9, rssi = { index -> if (index < 5) -80 else -55 })

        val report = place.report(9 * bucket)
        val shelf = report.residents.first { it.address == "SHELF" }
        assertTrue("shift was ${shelf.shiftDb}", shelf.moved)
        assertEquals(5, shelf.shiftedAtBucket)
        assertEquals(25.0, shelf.shiftDb, 1.0)
        assertEquals(listOf("SHELF"), report.movers.map { it.address })
    }

    @Test
    fun `ordinary wander is not reported as movement`() {
        // Multipath moves a stationary link by several dB with nothing touching it. A
        // threshold that fires on that would flag every device in the building.
        val place = profile()
        place.start(0L)
        place.present("STEADY", 0..9, rssi = { index -> -60 + (index % 3) - 1 })

        val report = place.report(9 * bucket)
        assertTrue(!report.residents.first { it.address == "STEADY" }.moved)
        assertTrue(report.movers.isEmpty())
    }

    @Test
    fun `a big step is ignored on a device that is noisy anyway`() {
        // A move is one big step among near-zeroes; oscillation is the same big step
        // every slice. Comparing against the device's overall spread does not separate
        // those - this link has a spread of about 22 and a step of 45 every time, so it
        // clears any multiple of its own spread. Comparing against the typical step does.
        val place = profile()
        place.start(0L)
        place.present("NOISY", 0..9, rssi = { index -> if (index % 2 == 0) -40 else -85 })

        val report = place.report(9 * bucket)
        assertTrue(!report.residents.first { it.address == "NOISY" }.moved)
    }

    @Test
    fun `the direction of the step is kept, because it says which way it went`() {
        val place = profile()
        place.start(0L)
        place.present("AWAY", 0..9, rssi = { index -> if (index < 5) -50 else -80 })
        val away = place.report(9 * bucket).residents.first { it.address == "AWAY" }
        assertTrue("expected a negative step, got ${away.shiftDb}", away.shiftDb < 0)
    }

    // ------------------------------------------------------------- the verdict

    @Test
    fun `a short recording is called short rather than charted`() {
        val place = profile()
        place.start(0L)
        place.present("A", 0..1)
        val verdict = place.report(1 * bucket).verdict()
        assertNotNull(verdict)
        assertTrue(verdict!!.contains("hours, not minutes"))
    }

    @Test
    fun `an empty recording says so`() {
        val place = profile()
        place.start(0L)
        assertTrue(place.report(5 * bucket).verdict()!!.contains("Nothing was recorded"))
    }

    @Test
    fun `a somewhere too quiet to have a rhythm says that instead`() {
        val place = profile()
        place.start(0L)
        place.present("LONELY", 0..9)
        val verdict = place.report(9 * bucket).verdict()
        assertNotNull(verdict)
        assertTrue(verdict!!.contains("no rhythm"))
    }

    @Test
    fun `a proper recording has nothing to complain about`() {
        val place = profile()
        place.start(0L)
        repeat(6) { index -> place.present("D$index", 0..9) }
        assertNull(place.report(9 * bucket).verdict())
    }

    // ---------------------------------------------------------------- hygiene

    @Test
    fun `nothing is recorded before it is started`() {
        val place = profile()
        place.observe("A", -50, 0L)
        assertEquals(0, place.deviceCount)
        assertEquals(0, place.report(0L).totalDevices)
    }

    @Test
    fun `starting again forgets the previous recording`() {
        val place = profile()
        place.start(0L)
        place.present("OLD", 0..4)
        assertEquals(1, place.deviceCount)
        place.start(100 * bucket)
        assertEquals(0, place.deviceCount)
    }

    @Test
    fun `a reading from before the start is ignored rather than bucketed negatively`() {
        val place = profile()
        place.start(10 * bucket)
        place.observe("STRAY", -50, 0L)
        assertEquals(0, place.deviceCount)
    }
}
