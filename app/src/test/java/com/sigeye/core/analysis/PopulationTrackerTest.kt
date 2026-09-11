package com.sigeye.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopulationTrackerTest {

    private val config = PopulationConfig(
        rssiFloor = -85,
        presenceSeconds = 60,
        lingeringMinutes = 2,
        residentMinutes = 20,
        devicesPerPerson = 2.0,
        forgetMinutes = 60,
    )

    private fun tracker() = PopulationTracker(config)

    private val minute = 60_000L

    @Test
    fun `a device seen once and never again is passing`() {
        val t = tracker()
        t.observe("A", -60, 0L, false)
        val snap = t.snapshot(0L)
        assertEquals(1, snap.passing)
        assertEquals(0, snap.lingering)
        assertEquals(0, snap.resident)
    }

    @Test
    fun `dwell classes move up as a device sticks around`() {
        val t = tracker()
        t.observe("A", -60, 0L, false)

        t.observe("A", -60, 90_000L, false) // 1.5 min
        assertEquals(DwellClass.PASSING, t.snapshot(90_000L).devices.first().dwellClass(config))

        t.observe("A", -60, 3 * minute, false)
        assertEquals(DwellClass.LINGERING, t.snapshot(3 * minute).devices.first().dwellClass(config))

        t.observe("A", -60, 25 * minute, false)
        assertEquals(DwellClass.RESIDENT, t.snapshot(25 * minute).devices.first().dwellClass(config))
    }

    @Test
    fun `presence uses the last sighting, not the dwell`() {
        val t = tracker()
        t.observe("resident", -60, 0L, false)
        t.observe("resident", -60, 30 * minute, false)

        // Heard 10 seconds ago: present.
        assertEquals(1, t.snapshot(30 * minute + 10_000L).presentNow)
        // Heard five minutes ago: gone, even though it dwelled for half an hour.
        assertEquals(0, t.snapshot(35 * minute).presentNow)
    }

    @Test
    fun `advertisements below the rssi floor never enter the population`() {
        val t = tracker()
        t.observe("far", -95, 0L, false)
        assertEquals(0, t.size())
        assertEquals(0, t.snapshot(0L).presentNow)
    }

    @Test
    fun `the rssi floor is what defines how big here is`() {
        val near = PopulationTracker(config.copy(rssiFloor = -70))
        near.observe("close", -60, 0L, false)
        near.observe("far", -80, 0L, false)
        assertEquals(1, near.size())
    }

    @Test
    fun `crowd estimate divides by the calibration factor`() {
        val t = tracker()
        repeat(10) { t.observe("dev$it", -60, 0L, false) }
        val snap = t.snapshot(0L)
        assertEquals(10, snap.presentNow)
        assertEquals(5.0, snap.estimatedPeople, 0.001)
    }

    @Test
    fun `a calibration factor of one means devices equal people`() {
        val t = PopulationTracker(config.copy(devicesPerPerson = 1.0))
        repeat(7) { t.observe("dev$it", -60, 0L, false) }
        assertEquals(7.0, t.snapshot(0L).estimatedPeople, 0.001)
    }

    @Test
    fun `a nonsensical calibration factor does not produce infinity`() {
        val t = PopulationTracker(config.copy(devicesPerPerson = 0.0))
        repeat(4) { t.observe("dev$it", -60, 0L, false) }
        val people = t.snapshot(0L).estimatedPeople
        assertTrue(people.isFinite())
        assertEquals(4.0, people, 0.001)
    }

    @Test
    fun `randomised address share is reported so the count can be read sceptically`() {
        val t = tracker()
        t.observe("fixed1", -60, 0L, false)
        t.observe("fixed2", -60, 0L, false)
        t.observe("rand1", -60, 0L, true)
        t.observe("rand2", -60, 0L, true)
        assertEquals(0.5, t.snapshot(0L).randomAddressShare, 0.001)
    }

    @Test
    fun `pruning forgets devices past the window and keeps recent ones`() {
        val t = tracker()
        t.observe("old", -60, 0L, false)
        t.observe("new", -60, 50 * minute, false)
        t.prune(70 * minute)
        assertEquals(1, t.size())
        assertEquals("new", t.snapshot(70 * minute).devices.first().address)
    }

    @Test
    fun `repeat sightings accumulate rather than creating new devices`() {
        val t = tracker()
        repeat(5) { t.observe("A", -60 - it, it * 1_000L, false) }
        assertEquals(1, t.size())
        val device = t.snapshot(5_000L).devices.first()
        assertEquals(5, device.sightings)
        assertEquals(-60, device.bestRssi)
        assertEquals(-64, device.lastRssi)
    }

    @Test
    fun `observed duration starts at the first advertisement, not construction`() {
        val t = tracker()
        assertEquals(0L, t.snapshot(10 * minute).observedForMs)
        t.observe("A", -60, 10 * minute, false)
        assertEquals(5 * minute, t.snapshot(15 * minute).observedForMs)
    }

    @Test
    fun `a device is not present before it has ever been heard`() {
        val t = tracker()
        t.observe("A", -60, 0L, false)
        val device = t.snapshot(0L).devices.first()
        assertTrue(device.isPresent(30_000L, config))
        assertFalse(device.isPresent(120_000L, config))
    }
}
