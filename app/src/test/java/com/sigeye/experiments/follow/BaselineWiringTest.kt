package com.sigeye.experiments.follow

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The baseline has to be fed, and only the service feeds it.
 *
 * The census is the denominator of the whole experiment: everything after it is subtraction
 * from what was audible when you started. The session holding it is driven by
 * [com.sigeye.core.ScanService] and by nothing else, so the screen starting that service
 * only after the baseline had finished meant the census counted nothing at all - and then
 * every device in the room looked like an arrival, because arriving is what happens to
 * anything first heard after a baseline ends.
 *
 * Invisible from the inside. Nothing throws, the countdown runs, the screen shows a
 * confident zero. So it is checked by reading the source, which is the only way to assert
 * on wiring without an instrumented test and a real radio.
 */
class BaselineWiringTest {

    private val source = File("src/main/java/com/sigeye/experiments/follow/FollowScreen.kt")
        .readText()

    @Test
    fun `every way into the baseline starts the scan first`() {
        assertTrue("FollowScreen.kt not where this test expected it", source.isNotEmpty())

        val direct = Regex("""goTo\(Step\.BASELINE\)""").findAll(source).count()
        assertTrue(
            "Something walks to Step.BASELINE without starting ScanService. Use " +
                "beginBaseline(), which starts the service that feeds the census.",
            direct == 1,
        )

        val opener = source.substringAfter("fun beginBaseline()").substringBefore("\n    }")
        assertTrue(
            "beginBaseline() no longer starts the FOLLOW scan, so the census counts nothing",
            opener.contains("ScanService.start") && opener.contains("Mode.FOLLOW"),
        )
        assertTrue(
            "beginBaseline() should be the one thing that walks to the baseline step",
            opener.contains("goTo(Step.BASELINE)"),
        )
    }

    @Test
    fun `the screen does not feed the session itself`() {
        // If it ever did, the check above would stop meaning anything: the census would
        // work from the screen and quietly stop working the moment the phone went into a
        // pocket, which is most of a follow.
        assertTrue(
            "The screen now calls FollowRunner.onAdvert. Either that is the feed, in which " +
                "case a pocketed phone records nothing, or it is a second feed and every " +
                "packet is being counted twice.",
            !source.contains("FollowRunner.onAdvert"),
        )
    }
}
