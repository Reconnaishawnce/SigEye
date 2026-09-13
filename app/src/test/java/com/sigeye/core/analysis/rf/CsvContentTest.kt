package com.sigeye.core.analysis.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exports carry arithmetic, not just formatting.
 *
 * A modeled value or a subtracted baseline written into a file is a claim like any other,
 * and one nobody checks until they open the file weeks later - by which time the walk
 * cannot be repeated.
 */
class CsvContentTest {

    private fun lines(csv: String) = csv.trim().lines()

    private fun rows(csv: String) = lines(csv).filterNot { it.startsWith("#") }.drop(1)

    // -------------------------------------------------------------- path loss

    @Test
    fun `every reading of the walk is in the file, unaggregated`() {
        val walk = (1..30).map { WalkSample(it * 0.7, -40 - it) }
        val fit = PathLossFit.fit(walk)
        assertEquals(walk.size, rows(PathLossFit.csv(walk, fit, 0.7)).size)
    }

    @Test
    fun `the modeled column is the fit evaluated at that distance`() {
        val walk = (1..30).map { WalkSample(it * 0.7, -40 - it) }
        val fit = PathLossFit.fit(walk)
        val csv = PathLossFit.csv(walk, fit, 0.7)
        val first = rows(csv).first().split(",")
        val meters = first[0].toDouble()
        val modeled = first[3].toDouble()
        val expected = fit.referenceRssi - 10.0 * fit.exponent * Math.log10(meters)
        assertEquals(expected, modeled, 0.01)
    }

    @Test
    fun `the header carries the fit, so the file explains itself`() {
        val walk = (1..30).map { WalkSample(it * 0.7, -40 - it) }
        val csv = PathLossFit.csv(walk, PathLossFit.fit(walk), 0.7)
        assertTrue(csv.contains("stride_m=0.7"))
        assertTrue(csv.contains("exponent="))
        assertTrue(csv.contains("r_squared="))
    }

    @Test
    fun `a walk with nothing in it still produces a readable file`() {
        val csv = PathLossFit.csv(emptyList(), PathLossFit.fit(emptyList()), 0.7)
        assertTrue(rows(csv).isEmpty())
        assertTrue(csv.contains("meters,rssi_dbm"))
    }

    // ------------------------------------------------------------ penetration

    @Test
    fun `the excess written out is the gap minus the baseline that was subtracted`() {
        val pair = DualBand.pair(
            listOf(
                Radio("AA:BB:CC:DD:EE:01", "Home", 2437, -50.0, 5),
                Radio("AA:BB:CC:DD:EE:02", "Home", 5180, -70.0, 5),
            ),
        ).first()
        val csv = DualBand.csv(listOf(Penetration(pair, baselineGapDb = 8.0)), true)
        val row = rows(csv).first().split(",")
        val gap = row[7].toDouble()
        val baseline = row[9].toDouble()
        val excess = row[10].toDouble()
        assertEquals(20.0, gap, 0.01)
        assertEquals(8.0, baseline, 0.01)
        assertEquals(gap - baseline, excess, 0.01)
    }

    @Test
    fun `a pair with no baseline leaves the excess empty rather than zero`() {
        // Zero would read as "no difference", which is a measurement. Empty is the truth.
        val pair = DualBand.pair(
            listOf(
                Radio("AA:BB:CC:DD:EE:01", "Home", 2437, -50.0, 5),
                Radio("AA:BB:CC:DD:EE:02", "Home", 5180, -70.0, 5),
            ),
        ).first()
        val row = rows(DualBand.csv(listOf(Penetration(pair, null)), false)).first().split(",")
        assertEquals("", row[9])
        assertEquals("", row[10])
    }

    @Test
    fun `a network name containing a comma cannot break the columns`() {
        val pair = DualBand.pair(
            listOf(
                Radio("AA:BB:CC:DD:EE:01", "Bob, Alice and Co", 2437, -50.0, 5),
                Radio("AA:BB:CC:DD:EE:02", "Bob, Alice and Co", 5180, -60.0, 5),
            ),
        ).first()
        val csv = DualBand.csv(listOf(Penetration(pair, 7.0)), true)
        val header = lines(csv).first { !it.startsWith("#") }
        assertEquals(header.split(",").size, rows(csv).first().split(",").size)
    }

    // -------------------------------------------------------------- spectrum

    @Test
    fun `the band export carries all three sections`() {
        val csv = Spectrum.csv(
            listOf(
                Occupant(2412, 20, -45, "one"),
                Occupant(2437, 20, -55, "six"),
            ),
        )
        assertTrue(csv.contains("section=wifi_channels"))
        assertTrue(csv.contains("section=ble_advertising_channels"))
        assertTrue(csv.contains("section=occupants"))
        // 37, 38 and 39, always, whether or not anything is sitting on them.
        assertTrue(csv.contains("37,2402"))
        assertTrue(csv.contains("38,2426"))
        assertTrue(csv.contains("39,2480"))
    }

    @Test
    fun `an access point outside the band is left out of the band's export`() {
        val csv = Spectrum.csv(listOf(Occupant(5180, 80, -45, "fivegig")))
        assertTrue(!csv.contains("fivegig"))
    }

    // --------------------------------------------------------- polarization

    @Test
    fun `the roll export gives both the plotted angle and the measured one`() {
        val sweep = PolarSweep(sectorCount = 12, minSamplesPerSector = 3)
        listOf(0f, 45f, 90f, 135f).forEach { roll ->
            repeat(4) { sweep.add(Polarization.plotAngle(roll), -50) }
        }
        val csv = Polarization.csv(sweep, Polarization.analyze(sweep))
        val row = rows(csv).first().split(",")
        val plot = row[1].toFloat()
        val roll = row[2].toFloat()
        assertEquals(plot / 2f, roll, 0.05f)
        assertEquals(16, rows(csv).size)
    }
}
