package com.sigeye.core.analysis.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading a sky. */
class SkyTest {

    private fun sat(
        id: Int,
        constellation: Constellation = Constellation.GPS,
        band: Band = Band.L1,
        cn0: Float = 40f,
        elevation: Float? = 45f,
        azimuth: Float? = 90f,
        used: Boolean = true,
    ) = Satellite(
        constellation = constellation,
        id = id,
        band = band,
        cn0DbHz = cn0,
        elevationDeg = elevation,
        azimuthDeg = azimuth,
        usedInFix = used,
        hasEphemeris = true,
    )

    /** Satellites spread around the sky, which is what a good fix looks like. */
    private fun spread(count: Int, band: Band = Band.L1, used: Boolean = true) =
        (1..count).map { sat(it, band = band, azimuth = it * (360f / count), used = used) }

    // ------------------------------------------------------------------ bands

    @Test
    fun `carrier frequency picks out the band`() {
        assertEquals(Band.L1, Sky.bandFor(1_575_420_000.0))
        assertEquals(Band.L5, Sky.bandFor(1_176_450_000.0))
        assertEquals(Band.E5B, Sky.bandFor(1_207_140_000.0))
        assertEquals(Band.B1I, Sky.bandFor(1_561_098_000.0))
    }

    @Test
    fun `glonass gives every satellite its own frequency and they all still land in one band`() {
        // FDMA rather than CDMA: the channels sit a few hundred kilohertz apart around
        // 1602 MHz, so the band has to be a range rather than a line.
        listOf(1_598_060_000.0, 1_602_000_000.0, 1_605_380_000.0).forEach {
            assertEquals("$it", Band.G1, Sky.bandFor(it))
        }
    }

    @Test
    fun `a frequency nothing claims is unknown rather than the nearest guess`() {
        assertEquals(Band.UNKNOWN, Sky.bandFor(1_400_000_000.0))
        assertEquals(Band.UNKNOWN, Sky.bandFor(null))
        assertEquals(Band.UNKNOWN, Sky.bandFor(0.0))
    }

    @Test
    fun `only the second-generation bands count as modern`() {
        assertTrue(Band.L5.modern && Band.E5B.modern && Band.L2.modern)
        assertTrue(!Band.L1.modern && !Band.G1.modern && !Band.B1I.modern)
    }

    // ------------------------------------------------------------------ the fix

    @Test
    fun `three satellites are not a position`() {
        // Three would fix a point in space if the receiver's clock were perfect. It is a
        // cheap crystal, and a microsecond of drift is three hundred metres of light.
        val view = SkyView(spread(3), atMs = 0L)

        assertTrue(!view.canFix)
        assertTrue(Sky.verdict(view).contains("clock"))
    }

    @Test
    fun `four spread around the sky is a fix`() {
        val view = SkyView(spread(4), atMs = 0L)

        assertTrue(view.canFix)
        assertEquals(4, view.used.size)
    }

    @Test
    fun `satellites heard but not used do not count toward a fix`() {
        val view = SkyView(spread(6, used = false), atMs = 0L)

        assertEquals(6, view.visible)
        assertTrue(!view.canFix)
    }

    @Test
    fun `four bunched together is called out as worse than four spread`() {
        // The same count in one corner of the sky is a far weaker position, which is why a
        // street between tall buildings is hard even with plenty overhead.
        val bunched = (1..5).map { sat(it, azimuth = 90f + it) }

        val view = SkyView(bunched, atMs = 0L)

        assertTrue(view.canFix)
        assertTrue(view.spread < 0.4)
        assertTrue(Sky.verdict(view).contains("bunched"))
    }

    // ------------------------------------------------------------------ two frequencies

    @Test
    fun `hearing a modern band is reported as dual frequency`() {
        val view = SkyView(spread(4) + spread(3, band = Band.L5), atMs = 0L)

        assertTrue(view.dualFrequency)
        assertTrue(Sky.verdict(view).contains("bounced"))
    }

    @Test
    fun `one frequency only says so plainly rather than saying nothing`() {
        val view = SkyView(spread(6), atMs = 0L)

        assertTrue(!view.dualFrequency)
        assertTrue(Sky.verdict(view).contains("original civil"))
    }

    @Test
    fun `a satellite heard on two bands at once is the thing worth naming`() {
        // Same satellite, both frequencies. That pair is what the receiver compares to
        // throw away a reflection.
        val view = SkyView(
            listOf(
                sat(5, band = Band.L1),
                sat(5, band = Band.L5),
                sat(9, band = Band.L1),
            ),
            atMs = 0L,
        )

        assertEquals(listOf("G5"), view.onTwoBands)
    }

    // ------------------------------------------------------------------ reading it

    @Test
    fun `an empty sky says why rather than showing a zero`() {
        val view = SkyView(emptyList(), atMs = 0L)

        assertNull(view.medianCn0)
        assertTrue(Sky.verdict(view).contains("weaker than the receiver's own noise"))
    }

    @Test
    fun `a satellite with no known orbit is not placed on the plot`() {
        // The receiver reports zeroes until it has the almanac, and drawing that would put
        // a satellite on the horizon due north that is not there.
        val unplaced = sat(11, elevation = null, azimuth = null)

        assertTrue(!unplaced.placed)
        assertTrue(sat(11).placed)
    }

    @Test
    fun `constellations are listed once each`() {
        val view = SkyView(
            listOf(
                sat(1, Constellation.GPS),
                sat(2, Constellation.GPS),
                sat(3, Constellation.GALILEO),
                sat(4, Constellation.BEIDOU),
            ),
            atMs = 0L,
        )

        assertEquals(3, view.constellations.size)
    }

    @Test
    fun `carrier to noise is described without calling it signal strength`() {
        // C/N0 is power against noise in one hertz, and it is not the dBm the rest of the
        // app deals in - a satellite at 45 dB-Hz arrives at about -155 dBm, which every
        // other experiment here would call silence.
        assertEquals("not heard", Sky.describeCn0(0f))
        assertTrue(Sky.describeCn0(22f).contains("weak"))
        assertTrue(Sky.describeCn0(48f).contains("clear view"))
    }

    @Test
    fun `azimuth reads as a compass point somebody can check against the sky`() {
        assertEquals("N", Sky.compass(0f))
        assertEquals("E", Sky.compass(90f))
        assertEquals("SW", Sky.compass(225f))
        assertEquals("N", Sky.compass(359f))
        assertEquals("N", Sky.compass(-1f))
    }
}
