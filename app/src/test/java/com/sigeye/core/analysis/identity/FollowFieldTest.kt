package com.sigeye.core.analysis.identity

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The things that went wrong on a street rather than in a test.
 *
 * All four came from the same place: what the app does under three hundred devices, two
 * threads and a minute of real time is not what it does in a unit test with six devices and
 * a fake clock.
 */
class FollowFieldTest {

    private val t0 = 1_700_000_000_000L

    private val shape = AdvertShape(
        companyId = 0x004C,
        manufacturerLength = 25,
        manufacturerPrefix = "0f05",
        txPower = 12,
        continuityTypes = setOf(0x10),
    )

    private fun session() = FollowSession(FollowTuning.DEFAULT)

    private fun FollowSession.hear(address: String, atMs: Long, rssi: Int = -60) {
        observe(address, rssi, atMs, null, null, isRandom = true, shape = shape)
    }

    /** A street: many devices, each heard a few times. */
    private fun FollowSession.crowd(count: Int, from: Long, packets: Int = 5) {
        repeat(packets) { packet ->
            repeat(count) { index ->
                hear("AA:BB:CC:00:%02X:%02X".format(index / 256, index % 256), from + packet * 300L)
            }
        }
    }

    // ------------------------------------------------------------------ the count only falls

    @Test
    fun `the count never climbs after the follow starts`() {
        // What it did on a street: the pool was fixed at the start, but devices already in
        // it kept crossing the minimum packet count for the next minute and appearing one
        // by one, so the number went up. Which is the opposite of what this does.
        val session = session()
        session.startBaseline(t0)

        // Forty devices heard once, which is not yet enough to count as anything.
        repeat(40) { session.hear("AA:BB:CC:01:00:%02X".format(it), t0 + 1_000L) }
        session.endBaseline(t0 + 35_000L)
        session.startFollowing(t0 + 35_000L)

        val atStart = session.state(t0 + 35_100L).stillIn.size

        // They carry on advertising, so they all pass the packet threshold shortly after.
        repeat(6) { packet ->
            repeat(40) {
                session.hear("AA:BB:CC:01:00:%02X".format(it), t0 + 36_000L + packet * 500L)
            }
        }

        val later = session.state(t0 + 40_000L).stillIn.size
        assertTrue("went from $atStart to $later", later <= atStart)
    }

    @Test
    fun `a device heard enough during the baseline is in the pool`() {
        val session = session()
        session.startBaseline(t0)
        repeat(6) { session.hear("AA:BB:CC:02:00:01", t0 + it * 400L) }
        session.endBaseline(t0 + 35_000L)
        session.startFollowing(t0 + 35_000L)

        assertEquals(1, session.state(t0 + 35_100L).stillIn.size)
    }

    // ------------------------------------------------------------------ the drop-off is visible

    @Test
    fun `a device on its way out is counted and timed`() {
        // The elimination is a minute of silence per device and it was invisible: the
        // number sat still while the app was working, which reads as stuck.
        val session = session()
        session.startBaseline(t0)
        repeat(6) { session.hear("AA:BB:CC:03:00:01", t0 + it * 400L) }
        session.endBaseline(t0 + 10_000L)
        session.startFollowing(t0 + 10_000L)

        val quiet = session.state(t0 + 40_000L)
        assertEquals(1, quiet.goingQuiet)
        assertTrue("nothing timed", quiet.nextDropInMs != null)
        assertTrue("${quiet.nextDropInMs}", quiet.nextDropInMs!! in 1..FollowTuning.DEFAULT.dropAfterMs)

        val gone = session.state(t0 + 120_000L)
        assertEquals(0, gone.stillIn.size)
        assertEquals("nothing left to be on its way out", 0, gone.goingQuiet)
    }

    // ------------------------------------------------------------------ faders go early

    @Test
    fun `something walking out of range is retired without the full wait`() {
        // It was receding, the silence is explained, and the remaining thirty-five seconds
        // buy nothing except a screen that appears not to be working.
        val session = session()
        session.startBaseline(t0)
        repeat(12) { session.hear("AA:BB:CC:06:00:01", t0 + it * 1_000L, rssi = -55 - it * 3) }
        session.endBaseline(t0 + 12_000L)
        session.startFollowing(t0 + 12_000L)

        val early = session.state(t0 + 12_000L + 30_000L)
        assertEquals("should already be gone", 0, early.stillIn.size)
    }

    @Test
    fun `something loud that simply stopped still gets the full minute`() {
        // That is what a rotation looks like, and retiring it early would lose the target
        // at the one moment it changed address.
        val session = session()
        session.startBaseline(t0)
        repeat(12) { session.hear("AA:BB:CC:07:00:01", t0 + it * 1_000L, rssi = -58) }
        session.endBaseline(t0 + 12_000L)
        session.startFollowing(t0 + 12_000L)

        assertEquals(1, session.state(t0 + 12_000L + 40_000L).stillIn.size)
        assertEquals(0, session.state(t0 + 12_000L + 70_000L).stillIn.size)
    }

    // ------------------------------------------------------------------ no questions in a crowd

    @Test
    fun `no rotation is chased in the first minutes of a follow`() {
        // A follow that has just started has almost nothing in its pool, so the survivor
        // count is trivially under the threshold and every device in the street is an
        // unexplained arrival. That is how a rotation question got asked about a crowd.
        val session = session()
        session.startBaseline(t0)
        repeat(6) { session.hear("AA:BB:CC:04:00:01", t0 + it * 400L) }
        session.endBaseline(t0 + 10_000L)
        session.startFollowing(t0 + 10_000L)

        session.crowd(200, t0 + 30_000L)

        assertTrue(session.state(t0 + 45_000L).questions.isEmpty())
    }

    @Test
    fun `a crowd of equally good matches is not put to somebody as three guesses`() {
        // The worst moment in the app: three devices offered at six percent each, while a
        // hundred and fifty others fitted just as well.
        val previous = Identity(
            address = "AA",
            shape = shape,
            isRandom = true,
            firstSeenMs = t0,
            lastSeenMs = t0 + 60_000L,
            packets = 200,
            medianGapMs = 152L,
            recentRssi = -60.0,
            bestRssi = -55,
        )
        val identical = (1..40).map {
            previous.copy(address = "C$it", firstSeenMs = t0 + 60_200L, lastSeenMs = t0 + 75_000L)
        }
        val departure = Departure("AA", Exit.VANISHED, 0.0, -60.0, -55, 10, t0 + 60_000L)

        val handoff = Handoffs.decide(previous, departure, identical, t0 + 80_000L)

        assertTrue("$handoff", handoff is Handoff.Gone)
        assertTrue((handoff as Handoff.Gone).why.contains("crowd"))
    }

    @Test
    fun `the shares somebody is shown add up to what is in front of them`() {
        // They were computed across everything considered and then only three were shown,
        // so the numbers on screen summed to a fraction and read as nonsense.
        val previous = Identity(
            address = "AA",
            shape = shape,
            isRandom = true,
            firstSeenMs = t0,
            lastSeenMs = t0 + 60_000L,
            packets = 200,
            medianGapMs = 152L,
            recentRssi = -60.0,
            bestRssi = -55,
        )
        val several = (1..Handoffs.TOO_MANY).map {
            previous.copy(
                address = "C$it",
                firstSeenMs = t0 + 60_000L + it * 100L,
                lastSeenMs = t0 + 75_000L,
                recentRssi = -60.0 - it,
            )
        }
        val departure = Departure("AA", Exit.VANISHED, 0.0, -60.0, -55, 10, t0 + 60_000L)

        val ask = Handoffs.decide(previous, departure, several, t0 + 80_000L) as Handoff.Ask

        assertTrue("only three are shown", ask.options.size <= Handoffs.MAX_OPTIONS)
        assertEquals(1.0, ask.options.sumOf { it.share }, 0.001)
    }

    // ------------------------------------------------------------------ two threads

    @Test
    fun `the session survives being read and written at the same time`() {
        // The scanning service writes from a background thread while the screen reads on
        // the main one. Nothing enforced that, and a follow died twice on a walk.
        val session = session()
        session.startBaseline(t0)
        session.endBaseline(t0 + 1_000L)
        session.startFollowing(t0 + 1_000L)

        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(4)
        val failures = mutableListOf<Throwable>()

        repeat(2) { worker ->
            pool.submit {
                runCatching {
                    repeat(400) { packet ->
                        session.hear(
                            "AA:BB:CC:05:%02X:%02X".format(worker, packet % 60),
                            t0 + 2_000L + packet * 10L,
                        )
                    }
                }.onFailure { synchronized(failures) { failures.add(it) } }
                done.countDown()
            }
        }
        repeat(2) {
            pool.submit {
                runCatching {
                    repeat(400) { round ->
                        val state = session.state(t0 + 2_000L + round * 10L)
                        state.candidates.forEach { it.describe() }
                        session.trails().forEach { (_, trail) -> trail.size }
                        session.journalCopy().events().forEach { moment -> moment.atMs }
                        session.stitches().size
                    }
                }.onFailure { synchronized(failures) { failures.add(it) } }
                done.countDown()
            }
        }

        assertTrue("workers did not finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertTrue("threw ${failures.firstOrNull()}", failures.isEmpty())
    }
}
