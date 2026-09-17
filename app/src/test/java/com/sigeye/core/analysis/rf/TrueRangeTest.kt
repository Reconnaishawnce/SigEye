package com.sigeye.core.analysis.rf

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checking the app's distance estimates against a distance that was measured.
 *
 * Everything else here turns loudness into metres by inverting a model with an exponent
 * somebody chose. These are about what happens when a real length turns up to argue.
 */
class TrueRangeTest {

    /** What an access point at this distance would read at, under a given exponent. */
    private fun levelAt(metres: Double, exponent: Double): Int =
        (TrueRange.REFERENCE_DBM - 10.0 * exponent * kotlin.math.log10(metres)).roundToInt()

    private fun reading(
        metres: Double,
        rssi: Int,
        bssid: String = "AA:BB:CC:DD:EE:01",
        trustworthy: Boolean = true,
    ) = TrueRange.Reading(bssid, "AP", metres, rssi, trustworthy)

    // ------------------------------------------------------------------ the round trip

    @Test
    fun `a room that matches the assumption reports that it matches`() {
        val readings = listOf(2.0, 5.0, 9.0, 14.0).mapIndexed { index, metres ->
            reading(metres, levelAt(metres, 2.5), bssid = "AA:0$index")
        }

        val verdict = TrueRange.check(readings, assumed = 2.5)

        assertTrue(verdict.enough)
        assertEquals(2.5, verdict.measuredExponent!!, 0.15)
        assertTrue("nothing should be flagged", !verdict.modelIsOff)
    }

    @Test
    fun `a room the model is wrong about says so, with the number`() {
        // The whole point. Four walls of an office is nearer a 3.5 than the 2.5 an
        // estimate assumes, and every distance the app prints there is short.
        val readings = listOf(2.0, 5.0, 9.0, 14.0).mapIndexed { index, metres ->
            reading(metres, levelAt(metres, 3.6), bssid = "AA:0$index")
        }

        val verdict = TrueRange.check(readings, assumed = 2.5)

        assertTrue(verdict.modelIsOff)
        assertTrue(verdict.measuredExponent!! > 3.0)
        assertTrue(verdict.verdict().contains("wrong place"))
    }

    @Test
    fun `the error is reported in metres, signed the way somebody reads it`() {
        // Under-reading an exponent makes the estimate too far, not too near.
        val metres = 10.0
        val verdict = TrueRange.check(
            listOf(reading(metres, levelAt(metres, 3.5))),
            assumed = 2.0,
        )

        val check = verdict.checks.single()
        assertEquals(metres, check.truthM, 0.001)
        assertTrue("guessed ${check.guessM}", check.guessM > check.truthM)
        assertTrue(check.errorM > 0)
    }

    // ------------------------------------------------------------------ refusing to fit

    @Test
    fun `one access point is a distance and not a model`() {
        val verdict = TrueRange.check(listOf(reading(6.0, levelAt(6.0, 2.5))), assumed = 2.5)

        assertNull(verdict.measuredExponent)
        assertTrue(!verdict.enough)
        assertTrue(verdict.verdict().contains("One access point"))
    }

    @Test
    fun `readings all at the same distance are one point drawn several times`() {
        // An exponent is a slope. Four readings at four metres have no slope through them,
        // and fitting one anyway would be fitting noise.
        val readings = (1..4).map { reading(4.0, levelAt(4.0, 2.5), bssid = "AA:0$it") }

        assertNull(TrueRange.check(readings, assumed = 2.5).measuredExponent)
    }

    @Test
    fun `a reading the radio was not sure about is kept but not fitted`() {
        // Shown, because somebody should see it was tried. Not fitted, because a timing
        // that bounced off a wall is a length to somewhere else.
        val readings = listOf(
            reading(2.0, levelAt(2.0, 2.5), bssid = "AA:01"),
            reading(6.0, levelAt(6.0, 2.5), bssid = "AA:02"),
            reading(11.0, levelAt(11.0, 2.5), bssid = "AA:03"),
            reading(40.0, -30, bssid = "AA:04", trustworthy = false),
        )

        val verdict = TrueRange.check(readings, assumed = 2.5)

        assertEquals("all four are shown", 4, verdict.checks.size)
        assertEquals("three are used", 3, verdict.usable.size)
        assertEquals(2.5, verdict.measuredExponent!!, 0.2)
    }

    @Test
    fun `nothing clean enough says so rather than returning a number`() {
        val verdict = TrueRange.check(
            listOf(reading(5.0, -60, trustworthy = false)),
            assumed = 2.5,
        )

        assertTrue(verdict.usable.isEmpty())
        assertTrue(verdict.verdict().contains("clean enough"))
        assertNull(verdict.typicalErrorM)
    }

    // ------------------------------------------------------------------ the arithmetic

    @Test
    fun `the estimate is the same inversion the rest of the app uses`() {
        // At the reference level the model has to say one metre, or everything built on it
        // is offset by a constant nobody would find.
        assertEquals(1.0, TrueRange.estimate(TrueRange.REFERENCE_DBM.toInt(), 2.5), 0.01)
        assertTrue(TrueRange.estimate(-70, 2.5) > TrueRange.estimate(-60, 2.5))
    }

    @Test
    fun `the implied exponent answers what it would have taken to be right`() {
        val exponent = TrueRange.impliedExponent(levelAt(10.0, 3.2), 10.0)!!

        assertEquals(3.2, exponent, 0.05)
    }

    @Test
    fun `a distance of one metre implies nothing, because every exponent fits it`() {
        // log of one is zero, and the exponent divides out. Returning a number here would
        // be dividing by zero and calling it a measurement.
        assertNull(TrueRange.impliedExponent(-40, 1.0))
        assertNull(TrueRange.impliedExponent(-40, 0.0))
    }
}
