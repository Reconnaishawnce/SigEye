package com.sigeye.core.analysis.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The record of a follow: what happened, when, and what somebody saw with their own eyes. */
class JournalTest {

    private val t0 = 1_700_000_000_000L

    private fun journal(): Journal = Journal().apply {
        add(Moment.Started(t0, "Food court"))
        add(Moment.BaselineDone(t0 + 35_000L, heard = 84))
        add(Moment.Following(t0 + 36_000L, pool = 84, targetPresent = true))
    }

    // ------------------------------------------------------------------ sampling

    @Test
    fun `counts are sampled rather than recorded on every tick`() {
        // A tick is twice a second and a follow is half an hour. Recording all of them
        // would be a hundred thousand entries for a chart a few hundred pixels wide.
        val journal = Journal()
        repeat(200) { journal.sample(t0 + it * 500L, stillIn = 40 - it / 10, pool = 40) }

        val expected = (200 * 500L / Journal.SAMPLE_MS).toInt()
        assertTrue(
            "kept ${journal.counts().size}, expected about $expected",
            journal.counts().size in (expected - 1)..(expected + 1),
        )
    }

    @Test
    fun `events are never thinned away, however long the follow runs`() {
        // Halving the chart's resolution costs nothing. Dropping a rotation would lose the
        // thing the journal exists to show.
        val journal = journal()
        journal.add(Moment.Rotated(t0 + 60_000L, "AA", "BB", byHand = false))
        repeat(6_000) { journal.sample(t0 + 100_000L + it * 5_000L, stillIn = 5, pool = 40) }

        assertEquals(1, journal.events().count { it is Moment.Rotated })
        assertEquals(1, journal.events().count { it is Moment.Started })
        assertTrue("journal grew to ${journal.size}", journal.size <= Journal.MAX_MOMENTS)
    }

    // ------------------------------------------------------------------ scrubbing

    @Test
    fun `the count at a moment is the last one recorded before it`() {
        val journal = Journal()
        journal.sample(t0, stillIn = 40, pool = 40)
        journal.sample(t0 + 60_000L, stillIn = 12, pool = 40)
        journal.sample(t0 + 120_000L, stillIn = 4, pool = 40)

        assertEquals(12, journal.countAt(t0 + 90_000L)?.stillIn)
        assertEquals(4, journal.countAt(t0 + 600_000L)?.stillIn)
    }

    @Test
    fun `scrubbing before the first sample gives the first rather than nothing`() {
        val journal = Journal()
        journal.sample(t0 + 60_000L, stillIn = 12, pool = 40)

        assertEquals(12, journal.countAt(t0)?.stillIn)
        assertNull(Journal().countAt(t0))
    }

    @Test
    fun `a scrubber sees what happened near where it is, not everything`() {
        val journal = journal()
        journal.add(Moment.Marked(t0 + 300_000L, Mark.BLOCKED))
        journal.add(Moment.Rotated(t0 + 305_000L, "AA", "BB", byHand = true))
        journal.add(Moment.Marked(t0 + 900_000L, Mark.CLOSER))

        val near = journal.around(t0 + 302_000L)

        assertEquals(2, near.size)
        assertTrue(near.any { it is Moment.Marked })
        assertTrue(near.any { it is Moment.Rotated })
    }

    @Test
    fun `the clock runs from the start of the follow, not from the epoch`() {
        val journal = journal()

        assertEquals("0:00", journal.clock(t0))
        assertEquals("1:05", journal.clock(t0 + 65_000L))
        assertEquals("12:30", journal.clock(t0 + 750_000L))
    }

    // ------------------------------------------------------------------ what it says

    @Test
    fun `a rotation says whether the app took it or a person did`() {
        val journal = journal()
        val automatic = Moment.Rotated(t0, "AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02", false)
        val byHand = Moment.Rotated(t0, "AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02", true)

        assertTrue(journal.describe(automatic).contains("automatically"))
        assertTrue(journal.describe(byHand).contains("you picked it"))
    }

    @Test
    fun `a mark with a note says the note`() {
        val journal = journal()

        assertEquals(
            "Blocked: went behind the pillar",
            journal.describe(Moment.Marked(t0, Mark.BLOCKED, "went behind the pillar")),
        )
        assertEquals("Closer", journal.describe(Moment.Marked(t0, Mark.CLOSER)))
    }

    // ------------------------------------------------------------------ surviving a restart

    @Test
    fun `a journal reads back as what it was`() {
        // A follow survives the screen closing, so its record has to as well.
        val original = journal()
        original.add(Moment.ProbeRan(t0 + 200_000L, Probe.WALK_BY, index = 0))
        original.add(Moment.Marked(t0 + 210_000L, Mark.BLOCKED, "van"))
        original.add(Moment.Rotated(t0 + 400_000L, "AA", "BB", byHand = true))
        original.add(Moment.Held(t0 + 500_000L, "BB", "their phone"))
        original.sample(t0 + 600_000L, stillIn = 2, pool = 84)

        val restored = Journal().apply { restore(original.snapshot()) }

        assertEquals(original.size, restored.size)
        assertEquals(original.events().map { it.atMs }, restored.events().map { it.atMs })
        val mark = restored.marks().single()
        assertEquals(Mark.BLOCKED, mark.mark)
        assertEquals("van", mark.note)
        assertEquals(2, restored.counts().single().stillIn)
    }

    @Test
    fun `a journal written by a newer version does not take the old one down with it`() {
        // These files are written by an app that gets updated between follows.
        val array = org.json.JSONArray()
        array.put(org.json.JSONObject().put("at", t0).put("t", "something-new"))
        array.put(
            org.json.JSONObject().put("at", t0 + 1_000L).put("t", "mark").put("mark", "NONSENSE"),
        )
        array.put(org.json.JSONObject().put("at", t0 + 2_000L).put("t", "count").put("in", 3))

        val restored = Journal().apply { restore(array) }

        assertEquals("only the row it understood", 1, restored.size)
        assertEquals(3, restored.counts().single().stillIn)
    }

    @Test
    fun `restoring nothing leaves an empty journal rather than throwing`() {
        val journal = journal()
        journal.restore(null)

        assertEquals(0, journal.size)
        assertEquals(0L, journal.spanMs)
    }
}
