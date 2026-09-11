package com.sigeye.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolarSweepTest {

    private fun sweep() = PolarSweep(sectorCount = 24, minSamplesPerSector = 3)

    /** Fills every sector with [rssi], enough samples to settle. */
    private fun PolarSweep.fillCircle(rssi: Int) {
        (0 until 360 step 15).forEach { heading ->
            repeat(3) { add(heading.toFloat() + 7f, rssi) }
        }
    }

    @Test
    fun `headings land in the expected sector`() {
        val s = sweep() // 15 degree sectors
        assertEquals(0, s.sectorOf(0f))
        assertEquals(0, s.sectorOf(14.9f))
        assertEquals(1, s.sectorOf(15f))
        assertEquals(23, s.sectorOf(359f))
    }

    @Test
    fun `headings outside zero to 360 are normalised, not clamped`() {
        val s = sweep()
        // Negative and over-wound headings are what sensors actually hand you.
        assertEquals(s.sectorOf(10f), s.sectorOf(370f))
        assertEquals(s.sectorOf(350f), s.sectorOf(-10f))
        assertEquals(s.sectorOf(10f), s.sectorOf(730f))
    }

    @Test
    fun `a sector averages its readings`() {
        val s = sweep()
        s.add(10f, -60)
        s.add(10f, -70)
        s.add(10f, -80)
        val sector = s.result().sectors[0]
        assertEquals(3, sector.samples)
        assertEquals(-70.0, sector.meanRssi, 0.001)
        assertEquals(-80, sector.minRssi)
        assertEquals(-60, sector.maxRssi)
    }

    @Test
    fun `a single stray reading cannot define the shadow`() {
        val s = sweep()
        s.fillCircle(-60)
        // One very weak packet in an otherwise normal sector.
        s.add(190f, -110)
        val result = s.result()
        // The notch must still be a properly sampled sector, not the outlier's.
        assertNotNull(result.notch)
        assertTrue(result.notch!!.samples >= 3)
    }

    @Test
    fun `an unsampled sector is never the peak or the notch`() {
        val s = sweep()
        repeat(5) { s.add(10f, -60) }
        repeat(5) { s.add(100f, -80) }
        val result = s.result()
        assertEquals(2, result.settledSectors)
        assertEquals(-60.0, result.peak!!.meanRssi, 0.001)
        assertEquals(-80.0, result.notch!!.meanRssi, 0.001)
    }

    @Test
    fun `a body shaped notch is found at the right heading`() {
        val s = sweep()
        s.fillCircle(-55)
        // Something absorbing hard around due south.
        repeat(6) { s.add(180f, -78) }
        val result = s.result()
        assertEquals(12, result.notch!!.index) // 180 / 15
        assertTrue("expected a real difference", result.frontToBackDb!! > 5.0)
    }

    @Test
    fun `front to back is null until both ends exist`() {
        val s = sweep()
        assertNull(s.result().frontToBackDb)
        repeat(3) { s.add(0f, -60) }
        // One settled sector is both peak and notch, so the ratio is zero, not absent.
        assertEquals(0.0, s.result().frontToBackDb!!, 0.001)
    }

    @Test
    fun `coverage reflects how much of the circle was actually walked`() {
        val s = sweep()
        assertEquals(0f, s.result().coverage, 0.001f)

        // Half the circle, properly sampled.
        (0 until 180 step 15).forEach { heading ->
            repeat(3) { s.add(heading.toFloat() + 7f, -60) }
        }
        assertEquals(0.5f, s.result().coverage, 0.001f)
        assertFalse("half a circle is not a sweep", s.result().isUsable())

        (180 until 360 step 15).forEach { heading ->
            repeat(3) { s.add(heading.toFloat() + 7f, -60) }
        }
        assertEquals(1f, s.result().coverage, 0.001f)
        assertTrue(s.result().isUsable())
    }

    @Test
    fun `a sector glanced at briefly does not count as covered`() {
        val s = sweep()
        s.add(10f, -60)
        s.add(10f, -60) // two, one short of settled
        assertEquals(0, s.result().settledSectors)
        s.add(10f, -60)
        assertEquals(1, s.result().settledSectors)
    }

    @Test
    fun `an even signal all the way round reports no meaningful shadow`() {
        val s = sweep()
        s.fillCircle(-65)
        val result = s.result()
        assertTrue(result.isUsable())
        // Nothing blocking means nothing to find - the honest answer is about zero.
        assertEquals(0.0, result.frontToBackDb!!, 0.001)
    }

    @Test
    fun `reset clears everything`() {
        val s = sweep()
        s.fillCircle(-60)
        s.reset()
        val result = s.result()
        assertEquals(0, result.totalSamples)
        assertEquals(0, result.settledSectors)
        assertNull(result.peak)
    }

    @Test
    fun `sector centres sit in the middle of their span`() {
        val s = sweep()
        val sectors = s.result().sectors
        assertEquals(7.5f, sectors[0].centreDegrees, 0.001f)
        assertEquals(187.5f, sectors[12].centreDegrees, 0.001f)
    }
}
