package com.sigeye.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumTest {

    private fun ap(channel: Int, rssi: Int, widthMhz: Int = 20) =
        Occupant(Spectrum.centreOf(channel), widthMhz, rssi, "ch$channel")

    // ------------------------------------------------------------- the band plan

    @Test
    fun `channel centres follow the five megahertz step, and fourteen does not`() {
        assertEquals(2412, Spectrum.centreOf(1))
        assertEquals(2437, Spectrum.centreOf(6))
        assertEquals(2462, Spectrum.centreOf(11))
        assertEquals(2472, Spectrum.centreOf(13))
        assertEquals(2484, Spectrum.centreOf(14))
    }

    @Test
    fun `one six and eleven are the only three that do not overlap`() {
        val one = ap(1, -50)
        val six = ap(6, -50)
        val eleven = ap(11, -50)
        // Twenty megahertz apart at twenty-five megahertz of separation.
        assertEquals(0.0, one.overlapFraction(six.lowMhz, six.highMhz), 0.001)
        assertEquals(0.0, six.overlapFraction(eleven.lowMhz, eleven.highMhz), 0.001)
        // Channel 3 lands halfway into both of its neighbours, which is the whole problem.
        val three = ap(3, -50)
        assertTrue(three.overlapFraction(one.lowMhz, one.highMhz) > 0.4)
        assertTrue(three.overlapFraction(six.lowMhz, six.highMhz) > 0.1)
    }

    @Test
    fun `a forty megahertz access point only puts half its power on one channel`() {
        val wide = Occupant(Spectrum.centreOf(3), 40, -50)
        assertEquals(0.5, wide.overlapFraction(2402, 2422), 0.001)
    }

    // ------------------------------------------------------------------- adding up

    @Test
    fun `two equal neighbours are three decibels, not twice the number`() {
        val load = Spectrum.loadOver(2402, 2422, listOf(ap(1, -60), ap(1, -60)))
        assertNotNull(load)
        assertEquals(-57.0, load!!, 0.1)
    }

    @Test
    fun `a far weaker neighbour barely moves the total`() {
        val load = Spectrum.loadOver(2402, 2422, listOf(ap(1, -50), ap(1, -80)))
        assertEquals(-50.0, load!!, 0.1)
    }

    @Test
    fun `an empty channel says nothing rather than saying silence`() {
        assertNull(Spectrum.loadOver(2402, 2422, listOf(ap(11, -40))))
    }

    // -------------------------------------------------- where bluetooth advertises

    @Test
    fun `the three advertising channels are where the spec puts them`() {
        val channels = Spectrum.advertChannels(emptyList())
        assertEquals(listOf(37, 38, 39), channels.map { it.channel })
        assertEquals(listOf(2402, 2426, 2480), channels.map { it.frequencyMhz })
    }

    @Test
    fun `a tidy one six eleven band leaves all three advertising channels alone`() {
        val tidy = listOf(ap(1, -45), ap(6, -45), ap(11, -45))
        val channels = Spectrum.advertChannels(tidy)
        // 2426 sits in the gap between 1 and 6, and 2480 above 11. 2402 is the very bottom
        // edge of channel 1 - the spec put it there on purpose, but it is not untouched.
        assertTrue(channels.first { it.channel == 38 }.clear)
        assertTrue(channels.first { it.channel == 39 }.clear)
    }

    @Test
    fun `someone parked on channel four lands directly on advertising channel 38`() {
        val channels = Spectrum.advertChannels(listOf(ap(4, -50)))
        val thirtyEight = channels.first { it.channel == 38 }
        assertEquals(1, thirtyEight.occupants)
        assertTrue("load was ${thirtyEight.loadDbm}", !thirtyEight.clear)
    }

    @Test
    fun `a weak neighbour on the same frequency is reported but not called busy`() {
        val channels = Spectrum.advertChannels(listOf(ap(4, -88)))
        val thirtyEight = channels.first { it.channel == 38 }
        assertEquals(1, thirtyEight.occupants)
        assertTrue(thirtyEight.clear)
    }

    // ---------------------------------------------------------------- the verdicts

    @Test
    fun `the quietest lane is one of the three, never a convenient gap`() {
        // Channel 9 is empty and channels 1, 6 and 11 are all occupied - recommending 9
        // would be how a band ends up with nothing but partial overlaps.
        val crowded = listOf(ap(1, -40), ap(6, -45), ap(11, -70))
        val quietest = Spectrum.quietestOfOneSixEleven(crowded)
        assertEquals(11, quietest!!.channel)
    }

    @Test
    fun `the quietest lane prefers an empty one over a merely quiet one`() {
        val quietest = Spectrum.quietestOfOneSixEleven(listOf(ap(1, -40), ap(11, -90)))
        assertEquals(6, quietest!!.channel)
    }

    @Test
    fun `access points off the three lanes are called out`() {
        val mixed = listOf(ap(1, -50), ap(3, -50), ap(6, -50), ap(9, -50), ap(11, -50))
        val off = Spectrum.offGrid(mixed)
        assertEquals(setOf(Spectrum.centreOf(3), Spectrum.centreOf(9)), off.map { it.centreMhz }.toSet())
    }

    @Test
    fun `a distant off-lane access point is not worth naming`() {
        assertTrue(Spectrum.offGrid(listOf(ap(3, -92))).isEmpty())
    }

    @Test
    fun `an empty band is not busy and a full one is`() {
        assertEquals(0f, Spectrum.busyFraction(emptyList()), 0.001f)
        val everywhere = (1..13).map { ap(it, -40) }
        assertEquals(1f, Spectrum.busyFraction(everywhere), 0.001f)
    }

    @Test
    fun `bar heights are clamped rather than running off the top of the chart`() {
        assertEquals(0f, Spectrum.barHeight(null), 0.001f)
        assertEquals(0f, Spectrum.barHeight(-120.0), 0.001f)
        assertEquals(1f, Spectrum.barHeight(-20.0), 0.001f)
        assertEquals(0.5f, Spectrum.barHeight(-65.0), 0.01f)
    }

    @Test
    fun `android's width constants become megahertz`() {
        assertEquals(20, Spectrum.widthFromAndroid(0))
        assertEquals(40, Spectrum.widthFromAndroid(1))
        assertEquals(80, Spectrum.widthFromAndroid(2))
        assertEquals(160, Spectrum.widthFromAndroid(3))
        // Anything unrecognised is treated as the narrowest case rather than guessed wide.
        assertEquals(20, Spectrum.widthFromAndroid(99))
    }
}
