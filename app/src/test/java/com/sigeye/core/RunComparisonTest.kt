package com.sigeye.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunComparisonTest {

    private fun run(name: String, at: Long, vararg figures: RunFigure) =
        SavedRun("wall", name, at, figures.toList())

    @Test
    fun `matching figures line up by label, not by position`() {
        // The order a screen builds its figures in is not stable across versions. Matching
        // on position would silently compare loss against coverage.
        val before = run(
            "kitchen", 1L,
            RunFigure("Loss", 12.0, "dB"),
            RunFigure("Coverage", 40.0, "%"),
        )
        val after = run(
            "kitchen again", 2L,
            RunFigure("Coverage", 65.0, "%"),
            RunFigure("Loss", 9.0, "dB"),
        )

        val rows = compareRuns(before, after).associateBy { it.label }

        assertEquals(-3.0, rows.getValue("Loss").change!!, 1e-9)
        assertEquals(25.0, rows.getValue("Coverage").change!!, 1e-9)
    }

    @Test
    fun `a figure only the earlier run had is kept, not dropped`() {
        // An experiment that stopped reporting a number is a fact about the comparison.
        val before = run("then", 1L, RunFigure("Loss", 12.0, "dB"), RunFigure("Spots", 4.0))
        val after = run("now", 2L, RunFigure("Loss", 12.0, "dB"))

        val rows = compareRuns(before, after)

        assertEquals(listOf("Loss", "Spots"), rows.map { it.label })
        assertNull(rows.last().after)
        assertEquals("gone", rows.last().prettyChange())
    }

    @Test
    fun `a figure only the later run has reads as new`() {
        val rows = compareRuns(
            run("then", 1L, RunFigure("Loss", 12.0, "dB")),
            run("now", 2L, RunFigure("Loss", 12.0, "dB"), RunFigure("Spots", 4.0)),
        )

        assertEquals("new", rows.last().prettyChange())
        assertNull(rows.last().change)
    }

    @Test
    fun `two percent of movement is steady, not an improvement`() {
        // RSSI wanders by a couple of dB with nothing moving. Reporting that as a win is
        // how a measurement tool starts lying to the person holding it.
        val delta = compareRuns(
            run("then", 1L, RunFigure("Loss", 12.00, "dB", higherIsBetter = false)),
            run("now", 2L, RunFigure("Loss", 11.85, "dB", higherIsBetter = false)),
        ).single()

        assertTrue(delta.steady)
        assertNull(delta.better)
    }

    @Test
    fun `direction comes from the experiment, not from the sign`() {
        val lessLoss = compareRuns(
            run("then", 1L, RunFigure("Loss", 12.0, "dB", higherIsBetter = false)),
            run("now", 2L, RunFigure("Loss", 4.0, "dB", higherIsBetter = false)),
        ).single()
        val lessCoverage = compareRuns(
            run("then", 1L, RunFigure("Coverage", 80.0, "%", higherIsBetter = true)),
            run("now", 2L, RunFigure("Coverage", 30.0, "%", higherIsBetter = true)),
        ).single()

        assertEquals(true, lessLoss.better)
        assertEquals(false, lessCoverage.better)
    }

    @Test
    fun `a figure with no opinion is never judged`() {
        // Most numbers here are neither good nor bad. Colouring them anyway would invent a
        // verdict the experiment never made.
        val delta = compareRuns(
            run("then", 1L, RunFigure("Devices", 12.0)),
            run("now", 2L, RunFigure("Devices", 40.0)),
        ).single()

        assertNull(delta.better)
        assertFalse(delta.steady)
        assertEquals("+28.0", delta.prettyChange())
    }

    @Test
    fun `the change carries its unit and the figure's precision`() {
        val delta = compareRuns(
            run("then", 1L, RunFigure("Period", 900.0, "s", decimals = 0)),
            run("now", 2L, RunFigure("Period", 612.0, "s", decimals = 0)),
        ).single()

        assertEquals("-288 s", delta.prettyChange())
        assertEquals("900 s", delta.before!!.pretty())
    }

    @Test
    fun `a figure that reads zero in both runs is steady rather than a divide by zero`() {
        val delta = compareRuns(
            run("then", 1L, RunFigure("Alerts", 0.0, higherIsBetter = false)),
            run("now", 2L, RunFigure("Alerts", 0.0, higherIsBetter = false)),
        ).single()

        assertTrue(delta.steady)
        assertEquals("0.0", delta.prettyChange())
    }
}
