package com.sigeye.core.analysis.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adding transmitters up, and being honest about what the total is. */
class RadioWeatherTest {

    private fun wifi(dbm: Int, label: String = "AP") = Emitter(Source.WIFI, label, dbm)

    private fun cell(dbm: Int, channel: String?, label: String = "cell") =
        Emitter(Source.CELLULAR, label, dbm, channelKey = channel)

    // ------------------------------------------------------------------ decibels do not add

    @Test
    fun `two equal signals are three decibels, not double the number`() {
        // The single most common error in anything claiming to measure radio. Decibels are
        // logarithms: two at -70 arriving together are -67, not -140 and not -35.
        val weather = RadioWeather.combine(listOf(wifi(-70), wifi(-70)))

        assertEquals(-67.0, weather.totalDbm!!, 0.05)
    }

    @Test
    fun `ten equal signals are ten decibels up`() {
        val weather = RadioWeather.combine((1..10).map { wifi(-80, "AP$it") })

        assertEquals(-70.0, weather.totalDbm!!, 0.05)
    }

    @Test
    fun `a loud one drowns a pile of quiet ones`() {
        // Twenty decibels is a hundred times the power, so one strong transmitter really
        // does make twenty weak ones a rounding error.
        val weather = RadioWeather.combine(listOf(wifi(-40)) + (1..20).map { wifi(-80, "AP$it") })

        assertEquals(-39.9, weather.totalDbm!!, 0.2)
    }

    @Test
    fun `nothing heard is nothing rather than a floor value`() {
        val weather = RadioWeather.combine(emptyList())

        assertNull(weather.totalDbm)
        assertEquals(0, weather.heard)
        assertNull(weather.dominant)
        assertTrue(RadioWeather.describe(null).contains("Nothing heard"))
    }

    // ------------------------------------------------------------------ counting once

    @Test
    fun `cells on one channel are one transmission, not four`() {
        // A modem reporting a serving cell and its neighbours on the same channel is
        // reporting one transmission several times. Summing them invents cellular power.
        val deduped = RadioWeather.combine(
            listOf(
                cell(-70, "lte-1850"),
                cell(-76, "lte-1850"),
                cell(-80, "lte-1850"),
                cell(-88, "lte-1850"),
            ),
        )

        assertEquals("only the strongest on the channel", -70.0, deduped.totalDbm!!, 0.05)
    }

    @Test
    fun `cells on different channels really are separate transmissions`() {
        val weather = RadioWeather.combine(
            listOf(cell(-70, "lte-1850"), cell(-70, "nr-3500")),
        )

        assertEquals(-67.0, weather.totalDbm!!, 0.05)
    }

    @Test
    fun `a cell with no known channel cannot be deduped and is counted alone`() {
        val weather = RadioWeather.combine(
            listOf(cell(-70, "lte-1850"), cell(-70, null), cell(-76, "lte-1850")),
        )

        assertEquals(-67.0, weather.totalDbm!!, 0.05)
    }

    @Test
    fun `wifi and bluetooth are never deduped, because they are never duplicates`() {
        // Two access points on channel 6 are two transmitters. Only cellular has the
        // one-transmission-reported-several-times problem.
        val weather = RadioWeather.combine(listOf(wifi(-70, "a"), wifi(-70, "b")))

        assertEquals(-67.0, weather.totalDbm!!, 0.05)
    }

    // ------------------------------------------------------------------ the shape

    @Test
    fun `the dominant radio is worked out in power, not in decibels`() {
        // A share of a logarithm is a share of nothing. Cellular at -50 against fifteen
        // Wi-Fi at -80 is cellular by a mile, and averaging the dB numbers would not say so.
        val weather = RadioWeather.combine(
            listOf(cell(-50, "lte-1850")) + (1..15).map { wifi(-80, "AP$it") },
        )

        assertEquals(Source.CELLULAR, weather.dominant)
        assertTrue("share was ${weather.dominantShare}", weather.dominantShare > 0.9)
        assertTrue(RadioWeather.shape(weather).contains("essentially all of it"))
    }

    @Test
    fun `an even split is reported as no single radio dominating`() {
        val weather = RadioWeather.combine(
            listOf(
                cell(-60, "lte-1850"),
                wifi(-60),
                Emitter(Source.BLUETOOTH, "earbuds", -60),
            ),
        )

        assertTrue(weather.dominantShare < 0.5)
        assertTrue(RadioWeather.shape(weather).contains("No single radio"))
    }

    @Test
    fun `each radio is totalled on its own as well as together`() {
        val weather = RadioWeather.combine(
            listOf(wifi(-70), wifi(-70), Emitter(Source.BLUETOOTH, "tag", -90)),
        )

        assertEquals(-67.0, weather.perSource[Source.WIFI]!!, 0.05)
        assertEquals(-90.0, weather.perSource[Source.BLUETOOTH]!!, 0.05)
        assertEquals(2, weather.counts[Source.WIFI])
        assertNull(weather.perSource[Source.CELLULAR])
    }

    // ------------------------------------------------------------------ what it will not say

    @Test
    fun `the description talks about busy and quiet, never about safe`() {
        // A busy place and a quiet one differ by thousands in power and are both nowhere
        // near any exposure limit. Calling a reading high or safe would be a claim this
        // cannot support.
        val words = listOf(-95.0, -75.0, -60.0, -45.0, -30.0).map { RadioWeather.describe(it) }

        assertTrue(words.none { it.contains("safe", true) })
        assertTrue(words.none { it.contains("danger", true) })
        assertTrue(words.none { it.contains("harm", true) })
        assertTrue(words.first().contains("quiet", true))
        assertTrue(words.last().contains("close", true))
    }

    @Test
    fun `the gauge runs from nothing to a transmitter in the room`() {
        assertEquals(0f, RadioWeather.scale(null))
        assertEquals(0f, RadioWeather.scale(RadioWeather.FLOOR_DBM))
        assertEquals(1f, RadioWeather.scale(RadioWeather.CEILING_DBM))
        assertEquals(0f, RadioWeather.scale(-200.0))
        assertEquals(1f, RadioWeather.scale(0.0))
    }

    @Test
    fun `milliwatts and decibels round trip`() {
        listOf(-30.0, -60.0, -90.0).forEach {
            assertEquals(it, RadioWeather.dbm(RadioWeather.milliwatts(it)), 0.001)
        }
    }
}
