package com.sigeye.core.analysis.rf

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
    fun `headings outside zero to 360 are normalized, not clamped`() {
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
    fun `a body shadow sits opposite the source`() {
        val s = sweep()
        // Facing the source is loud, facing away is quiet - the phone is on your chest.
        (0 until 360 step 15).forEach { heading ->
            val facingSource = heading < 60 || heading > 300
            val facingAway = heading in 120..240
            val rssi = when {
                facingSource -> -50
                facingAway -> -72
                else -> -60
            }
            repeat(4) { s.add(heading.toFloat() + 7f, rssi) }
        }
        val result = s.result()
        assertTrue("separation ${result.peakToNotchDegrees}", result.looksLikeBodyShadow)
        assertTrue(result.peakToNotchDegrees!! >= 130f)
    }

    @Test
    fun `a notch beside the peak is a reflection, not a body`() {
        val s = sweep()
        s.fillCircle(-60)
        // A hard notch only 45 degrees from the strongest direction. A torso cannot do
        // that - it is always on the far side of you from the source.
        repeat(6) { s.add(90f, -40) }
        repeat(6) { s.add(135f, -85) }
        val result = s.result()
        assertTrue("separation ${result.peakToNotchDegrees}", result.peakToNotchDegrees!! < 130f)
        assertFalse(result.looksLikeBodyShadow)
    }

    @Test
    fun `separation is measured the short way round`() {
        val s = sweep()
        s.fillCircle(-60)
        repeat(6) { s.add(352f, -40) }
        repeat(6) { s.add(7f, -80) }
        // 352 and 7 are fifteen degrees apart, not three hundred and forty-five.
        assertTrue(s.result().peakToNotchDegrees!! < 30f)
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
    fun `sector centers sit in the middle of their span`() {
        val s = sweep()
        val sectors = s.result().sectors
        assertEquals(7.5f, sectors[0].centerDegrees, 0.001f)
        assertEquals(187.5f, sectors[12].centerDegrees, 0.001f)
    }

    // ------------------------------------------------- progress versus measurement

    @Test
    fun `a visited sector counts as touched long before it counts as measured`() {
        val sweep = PolarSweep(sectorCount = 24, minSamplesPerSector = 3)
        // One packet in each of six sectors: a turn plainly under way, nothing settled.
        repeat(6) { sweep.add(it * 15f, -60) }
        val result = sweep.result()

        assertEquals(6, result.touchedSectors)
        assertEquals(0, result.settledSectors)
        assertEquals(0f, result.coverage, 0.001f)
        assertEquals(0.25f, result.touchedFraction, 0.001f)
    }

    @Test
    fun `touched and settled converge once the sectors fill`() {
        val sweep = PolarSweep(sectorCount = 8, minSamplesPerSector = 3)
        repeat(8) { sector ->
            repeat(3) { sweep.add(sector * 45f + 5f, -70) }
        }
        val result = sweep.result()
        assertEquals(8, result.touchedSectors)
        assertEquals(8, result.settledSectors)
        assertEquals(1f, result.touchedFraction, 0.001f)
    }

    @Test
    fun `an untouched circle reports nothing rather than dividing by zero`() {
        val result = PolarSweep(sectorCount = 12).result()
        assertEquals(0, result.touchedSectors)
        assertEquals(0f, result.touchedFraction, 0.001f)
    }

    // ------------------------------------------------------ adaptive resolution

    /** A turn at a given packet rate, one lap, evenly spread. */
    private fun lap(packets: Int, rssiAt: (Float) -> Int = { -60 }): PolarSweep {
        val sweep = PolarSweep(sectorCount = 24, minSamplesPerSector = 3)
        repeat(packets) { index ->
            val heading = index * 360f / packets
            sweep.add(heading, rssiAt(heading), index * 100L)
        }
        return sweep
    }

    @Test
    fun `a chatty source keeps the full resolution`() {
        // 24 sectors need 72 well-spread packets; 200 is comfortable.
        assertEquals(24, lap(200).bestResolution())
    }

    @Test
    fun `a slow source drops to a coarser binning rather than staying empty`() {
        // Thirty packets cannot settle 24 sectors, but settle 8 easily. The old fixed
        // binning drew almost nothing here, which is what "it only captures a tiny bit of
        // my turn" looked like from the outside.
        val sweep = lap(30)
        assertEquals(0, sweep.result(24).settledSectors)
        val resolution = sweep.bestResolution()
        assertTrue("resolution was $resolution", resolution <= 12)
        assertTrue(sweep.result(resolution).coverage >= 0.75f)
    }

    @Test
    fun `the adaptive result is the one the resolution chose`() {
        val sweep = lap(30)
        assertEquals(sweep.bestResolution(), sweep.adaptiveResult().totalSectors)
    }

    @Test
    fun `too little for any resolution falls back to the coarsest rather than failing`() {
        val sweep = lap(3)
        assertEquals(8, sweep.bestResolution())
        assertEquals(8, sweep.adaptiveResult().totalSectors)
    }

    @Test
    fun `re-binning the same turn keeps every reading`() {
        val sweep = lap(48)
        assertEquals(48, sweep.sampleCount)
        listOf(8, 12, 18, 24).forEach {
            assertEquals("at $it sectors", 48, sweep.result(it).totalSamples)
        }
    }

    @Test
    fun `a notch survives being re-binned coarser`() {
        // A 20 dB hole centerd on due south should still be the weakest direction at any
        // resolution - the measurement must not depend on the binning that draws it.
        val sweep = lap(240) { heading ->
            if (kotlin.math.abs(heading - 180f) < 25f) -85 else -60
        }
        listOf(8, 12, 18, 24).forEach { resolution ->
            val notch = sweep.result(resolution).notchBearingDegrees
            assertNotNull("no notch at $resolution sectors", notch)
            assertEquals("at $resolution sectors", 180f, notch!!, 25f)
        }
    }

    @Test
    fun `readings come back in arrival order for export`() {
        val sweep = PolarSweep(sectorCount = 24)
        sweep.add(10f, -50, 100L)
        sweep.add(20f, -55, 200L)
        sweep.add(30f, -60, 300L)
        val readings = sweep.readings()
        assertEquals(listOf(100L, 200L, 300L), readings.map { it.atMs })
        assertEquals(listOf(-50, -55, -60), readings.map { it.rssi })
    }

    @Test
    fun `reset clears the readings, not just the bins`() {
        val sweep = lap(50)
        sweep.reset()
        assertEquals(0, sweep.sampleCount)
        assertTrue(sweep.readings().isEmpty())
        assertEquals(0, sweep.result().totalSamples)
    }

    // --------------------------------------------- standing still, and lone spikes

    @Test
    fun `readings taken while the phone is not turning are dropped`() {
        val sweep = PolarSweep(sectorCount = 24, minSamplesPerSector = 3)
        // Seven seconds of standing still, exactly as an exported sweep began.
        assertTrue("the first reading is always kept", sweep.add(345f, -46, 0L))
        repeat(69) { assertFalse(sweep.add(345f, -46, (it + 1) * 100L)) }
        assertEquals(1, sweep.sampleCount)
        assertEquals(69, sweep.stationaryDrops)
    }

    @Test
    fun `a deliberate slow turn still counts`() {
        val sweep = PolarSweep(sectorCount = 24, minSamplesPerSector = 3)
        // A full circle in three minutes: two degrees a second, which is slow but real.
        var kept = 0
        repeat(100) { index ->
            if (sweep.add(index * 3f, -60, index * 1000L)) kept++
        }
        assertEquals(100, kept)
    }

    @Test
    fun `the first reading is always kept`() {
        val sweep = PolarSweep()
        assertTrue(sweep.add(120f, -50, 5_000L))
    }

    @Test
    fun `readings without timestamps are kept, so old callers still work`() {
        val sweep = PolarSweep()
        repeat(10) { assertTrue(sweep.add(90f, -50)) }
        assertEquals(10, sweep.sampleCount)
    }

    @Test
    fun `zero is a real timestamp, not a missing one`() {
        // An earlier version used 0 to mean "no timestamp", so a sweep whose first reading
        // landed on zero skipped the turn gate for its second reading as well.
        val sweep = PolarSweep()
        assertTrue(sweep.add(345f, -46, 0L))
        assertFalse(sweep.add(345f, -46, 100L))
        assertEquals(1, sweep.stationaryDrops)
    }

    @Test
    fun `reset clears the stationary tally too`() {
        val sweep = PolarSweep()
        repeat(20) { sweep.add(10f, -50, it * 100L) }
        assertTrue(sweep.stationaryDrops > 0)
        sweep.reset()
        assertEquals(0, sweep.stationaryDrops)
    }
}
