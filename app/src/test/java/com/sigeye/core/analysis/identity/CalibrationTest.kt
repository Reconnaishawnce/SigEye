package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Measuring the rotation thresholds instead of arguing about them, and finding out what a
 * model can and cannot settle.
 *
 * Every number in [HandoffTuning] was picked by reasoning about what ought to work. That is
 * how you start and not where you should stop, because reasoning cannot tell you whether
 * eighteen seconds is the right line between a device that rotated and one that walked
 * round a corner.
 *
 * ## What this found
 *
 * The harness works and the model cannot calibrate the thresholds. That is a real result
 * and it is written down here rather than tidied away.
 *
 * Against [WalkModel] the app never makes a wrong follow. Not at the shipped settings, and
 * not when [Trial] is told to take its best guess every single time instead of asking. The
 * discriminator doing all the work is the arrival gap: the real successor appears in the
 * same instant the old address stops, and a twin phone rotating four seconds later does not
 * beat that however identical its payload is.
 *
 * So every sweep below reduces to "looser catches more", because the cost of loose - a
 * wrong follow - never materializes. A test that demanded the shipped value be optimal here
 * would drive every threshold to its loosest setting and call it calibration. That is worse
 * than not measuring, so these assert non-regression instead: the defaults must keep
 * following rotations and must never take a wrong one.
 *
 * ## What would settle it
 *
 * The failure modes the thresholds actually defend against are the ones a model does not
 * contain. A reflection that moves the level ten dB between two packets. Android dropping
 * a second of results so a gap is an artifact of the scanner rather than the device. Two
 * phones in a terminal that genuinely do rotate in the same three hundred milliseconds,
 * which at airport density is a certainty rather than a coincidence. Producing those on
 * purpose would mean guessing at their shape, and a model built from guesses about the
 * failure it is meant to measure cannot measure it.
 *
 * A recorded walk can. See RealWalkTest, which runs the same [Trial] and is waiting for a
 * capture.
 */
class CalibrationTest {

    private val walk = WalkModel.walk()

    // ------------------------------------------------------------------ the model itself

    @Test
    fun `the modeled walk actually contains rotations to find`() {
        // A harness that scores well on an empty problem is the classic way to calibrate
        // something into uselessness without noticing.
        assertTrue("no handovers in the model", walk.truth.size >= 20)
        assertTrue("no packets", walk.packets.size > 5_000)
        assertEquals(walk.truth.size, walk.truth.map { it.first }.distinct().size)
    }

    @Test
    fun `the model contains phones that are identical on the air`() {
        // Same payload, same advertising interval, same pocket depth, rotating within a few
        // seconds of each other. This is as close to the room-full-of-iPhones case as a
        // model gets, and the app still separates them, which is the finding above.
        val shapes = walk.packets.groupBy { it.shape }
        val biggest = shapes.maxByOrNull { it.value.size }!!

        assertTrue(
            "no two devices in the model share an advertisement, so nothing here is hard",
            biggest.value.map { it.address }.distinct().size > 5,
        )
    }

    // ------------------------------------------------------------------ non-regression

    @Test
    fun `what ships follows rotations and takes none of them wrong`() {
        val score = Trial.run(walk)

        println("shipped defaults: $score")
        assertTrue("followed nothing: $score", score.followed >= 30)
        assertEquals("took a wrong rotation: $score", 0, score.wrong)
    }

    @Test
    fun `no setting in any sweep makes the app take a wrong rotation`() {
        // The property worth guarding. The sweeps cannot tell us which value is best, but
        // they can tell us the day one of them starts producing the expensive failure.
        val everything = SILENCE_VALUES.map { value ->
            "silenceMs=$value" to Trial.run(walk, HandoffTuning.DEFAULT.copy(silenceMs = value))
        } + SHARE_VALUES.map { value ->
            "autoShare=$value" to Trial.run(walk, HandoffTuning.DEFAULT.copy(autoShare = value))
        } + WINDOW_VALUES.map { value ->
            "appearedWithinMs=$value" to
                Trial.run(walk, HandoffTuning.DEFAULT.copy(appearedWithinMs = value))
        }

        val offenders = everything.filter { it.second.wrong > 0 }

        assertTrue(
            "these settings produced a wrong follow, which the model has never done " +
                "before, so either the model changed or the matching did: " +
                offenders.joinToString(", ") { "${it.first} ${it.second}" },
            offenders.isEmpty(),
        )
    }

    // ------------------------------------------------------------------ the negative result

    @Test
    fun `the model cannot tell the share thresholds apart, and says so`() {
        // Kept as an assertion rather than a comment so that it fails the day the model
        // gains the failure mode it is missing - at which point these sweeps start being
        // able to calibrate, and this test should be replaced with one that does.
        val results = SHARE_VALUES.map { value ->
            value to Trial.run(walk, HandoffTuning.DEFAULT.copy(autoShare = value))
        }
        results.forEach { (value, score) -> println("autoShare=$value $score") }

        assertTrue(
            "the model now distinguishes share values. It could not before, because a " +
                "wrong follow never happened in it. Work out what changed and calibrate " +
                "against it properly instead of leaving this test here.",
            results.all { it.second.wrong == 0 },
        )
    }

    @Test
    fun `asking costs recall in the model, which is not a reason to stop asking`() {
        // Taking the best option every time scores nearly twice the follows here, at no
        // cost, because the cost lives in conditions the model does not have. Recorded so
        // that nobody reads the numbers on their own and draws the obvious wrong lesson.
        val careful = Trial.run(walk)
        val reckless = Trial.run(walk, takeAsks = true)

        println("careful:  $careful")
        println("reckless: $reckless")
        assertTrue(reckless.followed > careful.followed)
        assertEquals(0, reckless.wrong)
    }

    @Test
    fun `a wrong follow is scored as worse than a missed one, because it is`() {
        // The weighting the sweeps would optimize against once they can. Missing a rotation
        // ends a follow, which somebody notices. Taking the wrong one produces a confident
        // trail to a stranger, which nobody notices at all.
        val missing = Score(followed = 5, wrong = 0, missed = 5, asked = 0)
        val guessing = Score(followed = 8, wrong = 2, missed = 0, asked = 0)

        assertTrue(missing.quality > guessing.quality)
    }

    private companion object {
        val SILENCE_VALUES = listOf(6_000L, 10_000L, 14_000L, 18_000L, 25_000L, 40_000L)
        val SHARE_VALUES = listOf(0.5, 0.6, 0.72, 0.85, 0.95)
        val WINDOW_VALUES = listOf(2_000L, 5_000L, 8_000L, 15_000L, 30_000L)
    }
}
