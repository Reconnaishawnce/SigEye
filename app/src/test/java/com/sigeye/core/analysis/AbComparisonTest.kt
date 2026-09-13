package com.sigeye.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The before-and-after machinery behind Microwave Interference, untested until now.
 *
 * Every "does X affect Y" experiment rests on this saying whether a difference is bigger
 * than the noise. Getting that wrong in the confident direction - calling a difference
 * clear when it is two noisy runs - is exactly the failure the app is otherwise careful
 * about everywhere else.
 */
class AbComparisonTest {

    private fun run(baselineValues: List<Double>, testValues: List<Double>): AbResult {
        val comparison = AbComparison()
        comparison.startBaseline()
        baselineValues.forEach { comparison.record(it) }
        comparison.startTest()
        testValues.forEach { comparison.record(it) }
        comparison.stop()
        return comparison.result()
    }

    // ------------------------------------------------------------------ the statistics

    @Test
    fun `an empty phase has no statistics rather than zeroes that look like readings`() {
        assertEquals(PhaseStats.EMPTY, PhaseStats.of(emptyList()))
        assertEquals(0, PhaseStats.EMPTY.samples)
        assertTrue(PhaseStats.EMPTY.standardError.isNaN())
    }

    @Test
    fun `one reading has a mean and no spread`() {
        val stats = PhaseStats.of(listOf(-60.0))
        assertEquals(-60.0, stats.mean, 0.001)
        assertEquals(0.0, stats.standardDeviation, 0.001)
        assertTrue("one sample cannot have a standard error", stats.standardError.isNaN())
    }

    @Test
    fun `the spread is the sample one, dividing by n minus one`() {
        // The population form would understate how much a short run wandered, which is
        // the direction that makes a difference look more real than it is.
        val stats = PhaseStats.of(listOf(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0))
        assertEquals(5.0, stats.mean, 0.001)
        assertEquals(2.138, stats.standardDeviation, 0.01)
        assertEquals(2.0, stats.min, 0.001)
        assertEquals(9.0, stats.max, 0.001)
    }

    // --------------------------------------------------------------------- the verdict

    @Test
    fun `two runs of the same thing are not a difference`() {
        val result = run(
            List(20) { -60.0 + (it % 3) },
            List(20) { -60.0 + (it % 3) },
        )
        assertEquals(Significance.NONE, result.significance)
        assertEquals(0.0, result.delta, 0.001)
    }

    @Test
    fun `an oven flattening the band is a clear difference`() {
        val result = run(
            List(30) { -55.0 + (it % 3) },
            List(30) { -80.0 + (it % 3) },
        )
        assertEquals(Significance.CLEAR, result.significance)
        assertTrue("delta was ${result.delta}", result.delta < -20)
    }

    @Test
    fun `a small shift inside a lot of noise is not called clear`() {
        // Two decibels of difference under ten decibels of wander is what a careless
        // version of this would report as a finding.
        val noisy = { offset: Double -> List(30) { offset + (it * 7 % 21) - 10.0 } }
        val result = run(noisy(-60.0), noisy(-62.0))
        assertTrue(
            "significance was ${result.significance}",
            result.significance != Significance.CLEAR,
        )
    }

    @Test
    fun `four readings is not enough to say anything`() {
        val result = run(List(4) { -60.0 }, List(4) { -90.0 })
        assertEquals(Significance.INSUFFICIENT, result.significance)
    }

    @Test
    fun `a phase that never happened is insufficient, not a difference`() {
        val result = run(List(30) { -60.0 + (it % 3) }, emptyList())
        assertEquals(Significance.INSUFFICIENT, result.significance)
        assertNull(result.tStatistic)
    }

    @Test
    fun `two perfectly flat phases do not divide by zero`() {
        // Identical readings both ways gives no variance at all, and the t statistic has
        // nothing to divide by.
        val result = run(List(20) { -60.0 }, List(20) { -60.0 })
        assertNull(result.tStatistic)
        assertEquals(Significance.INSUFFICIENT, result.significance)
    }

    @Test
    fun `percent change is refused when the baseline sits on zero`() {
        // dBm readings straddle zero happily, and dividing by a mean near it produces a
        // number that looks like a measurement and is not one.
        val result = run(List(20) { if (it % 2 == 0) -0.0000001 else 0.0000001 }, List(20) { 5.0 })
        assertNull(result.percentChange)
    }

    // ---------------------------------------------------------------------- collecting

    @Test
    fun `readings land in whichever phase is running`() {
        val comparison = AbComparison()
        comparison.record(-1.0)
        assertEquals(0, comparison.baselineSamples())
        assertEquals(0, comparison.testSamples())

        comparison.startBaseline()
        repeat(3) { comparison.record(-60.0) }
        comparison.startTest()
        repeat(5) { comparison.record(-80.0) }
        comparison.stop()
        comparison.record(-1.0)

        assertEquals(3, comparison.baselineSamples())
        assertEquals(5, comparison.testSamples())
        assertEquals(AbComparison.Phase.IDLE, comparison.phase)
    }

    @Test
    fun `reset clears both phases, so a second attempt is a second attempt`() {
        val comparison = AbComparison()
        comparison.startBaseline()
        repeat(10) { comparison.record(-60.0) }
        comparison.reset()
        assertEquals(0, comparison.baselineSamples())
        assertEquals(0, comparison.testSamples())
        assertEquals(AbComparison.Phase.IDLE, comparison.phase)
    }

    @Test
    fun `the series come back in the order they were recorded`() {
        val comparison = AbComparison()
        comparison.startBaseline()
        listOf(-60.0, -61.0, -62.0).forEach { comparison.record(it) }
        assertEquals(listOf(-60.0, -61.0, -62.0), comparison.baselineSeries())
    }
}
