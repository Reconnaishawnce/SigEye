package com.sigeye.experiments.trainspotter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fitting the burst threshold to the trains somebody actually marked.
 *
 * Built as replays of recorded days, because that is what the thing does: the count and the
 * baseline in each row are what the app really had at that moment, so a threshold that
 * catches a train in here would have caught it then.
 */
class ThresholdFitTest {

    private val start = 1_700_000_000_000L
    private val binMs = 8_000L

    private fun bin(
        index: Int,
        newCount: Int,
        baseline: Double = 4.0,
        label: String = "",
        phase: Phase = Phase.ARMED,
    ) = LoggedBin(start + index * binMs, newCount, baseline, phase, label)

    private fun advise(bins: List<LoggedBin>, current: Double = 3.0, minCount: Int = 4) =
        ThresholdFit.advise(bins, binMs, current, minCount)

    // ------------------------------------------------------------------ catching

    @Test
    fun `a marked bin that would spike counts as caught`() {
        val advice = advise(listOf(bin(0, 4), bin(1, 40, label = "TRAIN"), bin(2, 4)))

        val at3 = advice.scores.single { it.factor == 3.0 }
        assertEquals(1, at3.caught)
        assertEquals(0, at3.missed)
    }

    @Test
    fun `a tap a couple of bins late still counts`() {
        // The train is bin 5, the thumb found bin 7. That is what a tap looks like: people
        // reach for the phone while it is still going past, or just after.
        val bins = (0..10).map { index ->
            when (index) {
                5 -> bin(index, 40)
                7 -> bin(index, 4, label = "TRAIN")
                else -> bin(index, 4)
            }
        }

        assertEquals(1, advise(bins).scores.single { it.factor == 3.0 }.caught)
    }

    @Test
    fun `a tap far from any spike is a miss`() {
        val bins = (0..20).map { index ->
            when (index) {
                2 -> bin(index, 40)
                18 -> bin(index, 4, label = "TRAIN")
                else -> bin(index, 4)
            }
        }

        val at3 = advise(bins).scores.single { it.factor == 3.0 }
        assertEquals(0, at3.caught)
        assertEquals(1, at3.missed)
        assertEquals("and the far-off spike is counted as an extra", 1, at3.unmarked)
    }

    // ------------------------------------------------------------------ the suggestion

    @Test
    fun `the strictest threshold that still catches everything is the one offered`() {
        // Two trains, one at twelve times the baseline and one at four. Anything above four
        // misses the quiet one, so four is the quietest threshold that still catches both.
        val bins = (0..40).map { index ->
            when (index) {
                10 -> bin(index, 48, label = "TRAIN")
                30 -> bin(index, 16, label = "TRAIN")
                else -> bin(index, 4)
            }
        }

        val suggested = advise(bins).suggested!!

        assertEquals(0, suggested.missed)
        assertEquals(2, suggested.caught)
        assertEquals(4.0, suggested.factor, 0.001)
    }

    @Test
    fun `missing a train is never traded away for a quieter threshold`() {
        // The whole point of the button. A threshold that misses one is not offered however
        // few extra alerts it would produce.
        val bins = (0..60).map { index ->
            when (index) {
                10 -> bin(index, 48, label = "TRAIN")
                20 -> bin(index, 14, label = "TRAIN")
                // A steady drizzle of medium bursts, so a low threshold is noisy.
                else -> if (index % 3 == 0) bin(index, 13) else bin(index, 4)
            }
        }

        val suggested = advise(bins).suggested!!

        assertEquals(0, suggested.missed)
        assertTrue("even though it costs extra alerts", suggested.unmarked > 0)
    }

    @Test
    fun `when nothing catches everything, the one that catches most is offered`() {
        // A train at barely above the baseline cannot be caught at any threshold, because
        // the minimum burst size rules it out. The answer is still the best available one.
        val bins = (0..40).map { index ->
            when (index) {
                10 -> bin(index, 48, label = "TRAIN")
                30 -> bin(index, 3, label = "TRAIN")
                else -> bin(index, 4)
            }
        }

        val suggested = advise(bins).suggested!!

        assertEquals(1, suggested.caught)
        assertEquals(1, suggested.missed)
    }

    @Test
    fun `nothing marked means no suggestion at all`() {
        val advice = advise((0..30).map { bin(it, if (it == 5) 40 else 4) })

        assertFalse(advice.enough)
        assertNull(advice.suggested)
    }

    // ------------------------------------------------------------------ what counts

    @Test
    fun `enrolment and warm-up bins are not evidence`() {
        // Enrolment counts are deliberately zero and warm-up had no trustworthy baseline.
        // Neither says anything about where a threshold should sit.
        val bins = listOf(
            bin(0, 0, phase = Phase.ENROLL, label = "TRAIN"),
            bin(1, 40, phase = Phase.WARMUP),
            bin(2, 4),
        )

        val advice = advise(bins)

        assertEquals(1, advice.bins)
        assertEquals(0, advice.marked)
    }

    @Test
    fun `a quiet baseline cannot make every blip a spike`() {
        // The floor of one on the baseline, same as the live rule. Without it a dead-quiet
        // stretch makes three new devices look like a ten-times burst.
        val bins = (0..20).map { bin(it, if (it == 5) 3 else 0, baseline = 0.0) }

        assertEquals(0, advise(bins).scores.single { it.factor == 3.0 }.unmarked)
    }

    @Test
    fun `the cost of a threshold is reported as extras per catch, not as accuracy`() {
        // An unmarked spike is not a false positive - nobody stands at a window for six days
        // with a finger on the button. Reporting one accuracy figure would be quietly wrong.
        val bins = (0..30).map { index ->
            when (index) {
                10 -> bin(index, 40, label = "TRAIN")
                20, 25 -> bin(index, 40)
                else -> bin(index, 4)
            }
        }

        val at3 = advise(bins).scores.single { it.factor == 3.0 }

        assertEquals(1, at3.caught)
        assertEquals(2, at3.unmarked)
        assertEquals(2.0, at3.extrasPerCatch, 0.001)
        assertEquals(1.0, at3.catchRate, 0.001)
    }

    // ------------------------------------------------------------------ reading it back

    @Test
    fun `a recorded row reads back into a bin`() {
        val row = "2026-09-13T08:15:00,1700000000000,17,42,4.25,1,armed,TRAIN"

        val bin = ThresholdFit.parse(row)!!

        assertEquals(1_700_000_000_000L, bin.startMs)
        assertEquals(17, bin.newCount)
        assertEquals(4.25, bin.baseline, 0.001)
        assertEquals(Phase.ARMED, bin.phase)
        assertEquals("TRAIN", bin.label)
        assertTrue(bin.labelled)
    }

    @Test
    fun `a short row from an older version is skipped rather than fatal`() {
        // These files are appended to over days by an app that gets updated in between.
        assertNull(ThresholdFit.parse("2026-09-13T08:15:00,1700000000000,17"))
        assertNull(ThresholdFit.parse(""))
        assertNull(ThresholdFit.parse("time,start_ms,new,active,baseline,spike,phase,label"))
    }

    @Test
    fun `a row with no label reads back unlabelled rather than as a mark`() {
        val bin = ThresholdFit.parse("2026-09-13T08:15:00,1700000000000,17,42,4.25,0,armed,")!!

        assertFalse(bin.labelled)
    }
}
