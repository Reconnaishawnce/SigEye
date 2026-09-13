package com.sigeye.core.analysis.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turning headcounts somebody actually took into the devices-per-person factor. */
class CrowdCalibrationTest {

    private fun count(
        heads: Int,
        devices: Double,
        place: String = "Office",
        floor: Int = -85,
        presence: Int = 60,
        at: Long = 1_700_000_000_000L,
    ) = Calibration(
        place = place,
        headcount = heads,
        devices = devices,
        samples = 30,
        seconds = 30,
        rssiFloor = floor,
        presenceSeconds = presence,
        takenAtMs = at,
    )

    // ------------------------------------------------------------------ the estimator

    @Test
    fun `one headcount gives the factor it implies`() {
        val fit = CrowdCalibration.fit(listOf(count(heads = 10, devices = 24.0)))!!

        assertEquals(2.4, fit.factor, 0.001)
        assertEquals(1, fit.calibrations)
        assertEquals(10, fit.people)
    }

    @Test
    fun `a bigger headcount carries more weight than a smaller one`() {
        // Two people with five devices says 2.5. Forty people with eighty-two says 2.05.
        // Averaging the ratios gives 2.275, which treats a count of two as evidence equal
        // to a count of forty. Pooling the totals gives 2.07, which is the honest answer.
        val fit = CrowdCalibration.fit(
            listOf(count(heads = 2, devices = 5.0), count(heads = 40, devices = 82.0)),
        )!!

        assertEquals(87.0 / 42.0, fit.factor, 0.001)
        assertTrue("and it sits nearer the big count", fit.factor < 2.1)
        assertEquals("the sample size is people, not counts", 42, fit.people)
    }

    @Test
    fun `nothing usable gives no factor rather than a zero`() {
        assertNull(CrowdCalibration.fit(emptyList()))
        assertNull("a headcount of zero is not a calibration", CrowdCalibration.fit(
            listOf(count(heads = 0, devices = 12.0)),
        ))
        assertNull("and neither is hearing nothing", CrowdCalibration.fit(
            listOf(count(heads = 8, devices = 0.0)),
        ))
    }

    @Test
    fun `an unusable count does not poison the ones beside it`() {
        val fit = CrowdCalibration.fit(
            listOf(count(heads = 0, devices = 9.0), count(heads = 10, devices = 20.0)),
        )!!

        assertEquals(2.0, fit.factor, 0.001)
        assertEquals(1, fit.calibrations)
    }

    // ------------------------------------------------------------------ how much to believe it

    @Test
    fun `counts that land on each other are reported as agreeing`() {
        val fit = CrowdCalibration.fit(
            listOf(
                count(heads = 10, devices = 20.0),
                count(heads = 12, devices = 25.0),
                count(heads = 8, devices = 17.0),
            ),
        )!!

        assertEquals(Agreement.TIGHT, fit.agreement)
    }

    @Test
    fun `counts all over the place are not laundered into one confident number`() {
        // A factor is still returned, because arithmetic still works. What changes is that
        // the screen is told not to believe it.
        val fit = CrowdCalibration.fit(
            listOf(
                count(heads = 10, devices = 11.0),
                count(heads = 10, devices = 45.0),
                count(heads = 10, devices = 22.0),
            ),
        )!!

        assertEquals(Agreement.SCATTERED, fit.agreement)
        assertEquals(1.1, fit.lowRatio, 0.001)
        assertEquals(4.5, fit.highRatio, 0.001)
    }

    @Test
    fun `a single count never claims its own counts agree`() {
        val fit = CrowdCalibration.fit(listOf(count(heads = 10, devices = 20.0)))!!

        assertEquals("one count has nothing to agree with", Agreement.LOOSE, fit.agreement)
        assertEquals(0.0, fit.spread, 0.001)
    }

    // ------------------------------------------------------------------ where it was taken

    @Test
    fun `this place is preferred over everywhere else`() {
        val all = listOf(
            count(heads = 10, devices = 18.0, place = "Kitchen"),
            count(heads = 10, devices = 30.0, place = "Concourse"),
            count(heads = 10, devices = 32.0, place = "Concourse"),
        )

        val advice = CrowdCalibration.adviseFor(all, "Kitchen", rssiFloor = -85, presenceSeconds = 60)

        assertTrue(advice.fromHere)
        assertEquals(1.8, advice.suggested!!.factor, 0.001)
        assertEquals("and the rest is still carried", 3.1, advice.elsewhere!!.factor, 0.001)
    }

    @Test
    fun `a place with nothing on file falls back to everywhere else`() {
        val all = listOf(count(heads = 10, devices = 24.0, place = "Office"))

        val advice = CrowdCalibration.adviseFor(all, "Beach", rssiFloor = -85, presenceSeconds = 60)

        assertNull(advice.here)
        assertEquals(2.4, advice.suggested!!.factor, 0.001)
        assertTrue("and the screen can say it is borrowed", !advice.fromHere)
    }

    @Test
    fun `a factor measured at a different floor is set aside rather than pooled`() {
        // At -70 you are counting a room and at -95 you are counting the street. The same
        // people produce different device counts, so the factor does not carry.
        val all = listOf(
            count(heads = 10, devices = 12.0, floor = -70),
            count(heads = 10, devices = 40.0, floor = -95),
        )

        val advice = CrowdCalibration.adviseFor(all, "Office", rssiFloor = -70, presenceSeconds = 60)

        assertEquals(1.2, advice.here!!.factor, 0.001)
        assertEquals(1, advice.wrongFloor)
    }

    @Test
    fun `a floor a decibel or two away is the same room`() {
        // The floor is a slider. Refusing to pool -85 with -84 would mean almost nothing
        // ever pooled, which is a worse failure than the one it guards against.
        val all = listOf(count(heads = 10, devices = 20.0, floor = -84))

        val advice = CrowdCalibration.adviseFor(all, "Office", rssiFloor = -85, presenceSeconds = 60)

        assertNotNull(advice.here)
        assertEquals(0, advice.wrongFloor)
    }

    @Test
    fun `a different presence window is a different measurement`() {
        // How long a device counts as present changes the device count directly, so a
        // factor from a sixty second window means nothing against a fifteen second one.
        val all = listOf(count(heads = 10, devices = 20.0, presence = 15))

        val advice = CrowdCalibration.adviseFor(all, "Office", rssiFloor = -85, presenceSeconds = 60)

        assertNull(advice.here)
        assertEquals(1, advice.wrongFloor)
    }

    @Test
    fun `place names are matched without caring about case`() {
        val all = listOf(count(heads = 10, devices = 20.0, place = "the office"))

        assertTrue(
            CrowdCalibration.adviseFor(all, "The Office", -85, 60).fromHere,
        )
    }

    // ------------------------------------------------------------------ the window

    @Test
    fun `the device count is the mean of the window, not one instant`() {
        assertEquals(11.0, CrowdCalibration.meanDevices(listOf(10, 12, 11)), 0.001)
        assertEquals(0.0, CrowdCalibration.meanDevices(emptyList()), 0.001)
    }
}
