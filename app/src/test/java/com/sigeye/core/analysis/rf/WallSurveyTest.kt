package com.sigeye.core.analysis.rf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Walking a building and ending up with something you can act on. */
class WallSurveyTest {

    private var clock = 1_700_000_000_000L

    private fun spot(
        name: String,
        excess: Double?,
        pairs: Int = 3,
        lost5: Int = 0,
        baseline: Long = 1L,
    ) = SurveySpot(
        name = name,
        excessDb = excess,
        pairs = pairs,
        lost5 = lost5,
        takenAtMs = clock++,
        baselineAtMs = baseline,
    )

    // ------------------------------------------------------------------ order

    @Test
    fun `the worst room is first, because that is what a survey is looking for`() {
        val ordered = WallSurvey.order(
            listOf(
                spot("Study", 2.0),
                spot("Garage", 18.0),
                spot("Kitchen", 9.0),
            ),
        )

        assertEquals(listOf("Garage", "Kitchen", "Study"), ordered.map { it.name })
    }

    @Test
    fun `a spot that measured nothing sinks rather than floats`() {
        // A null is not a good reading. Sorting it to the top would put the least
        // informative row where the eye lands first.
        val ordered = WallSurvey.order(
            listOf(spot("Shed", null), spot("Hall", 1.0), spot("Loft", 12.0)),
        )

        assertEquals(listOf("Loft", "Hall", "Shed"), ordered.map { it.name })
    }

    @Test
    fun `two rooms that cost the same keep the order they were walked in`() {
        val ordered = WallSurvey.order(listOf(spot("First", 7.0), spot("Second", 7.0)))

        assertEquals(listOf("First", "Second"), ordered.map { it.name })
    }

    // ------------------------------------------------------------------ the baseline they belong to

    @Test
    fun `spots measured against a different baseline are not in the same survey`() {
        // Excess loss is a difference from a reference. Re-baselining moves the reference,
        // so comparing across one is comparing two different measurements.
        val all = listOf(
            spot("Kitchen", 8.0, baseline = 1L),
            spot("Garage", 14.0, baseline = 2L),
        )

        assertEquals(listOf("Kitchen"), WallSurvey.against(all, 1L).map { it.name })
        assertEquals(listOf("Garage"), WallSurvey.against(all, 2L).map { it.name })
    }

    // ------------------------------------------------------------------ the finding

    @Test
    fun `the finding is the gap between the best room and the worst`() {
        val summary = WallSurvey.summarize(
            listOf(spot("Study", 2.0), spot("Garage", 18.0), spot("Kitchen", 9.0)),
        )!!

        assertEquals("Garage", summary.worst.name)
        assertEquals("Study", summary.best.name)
        assertEquals(16.0, summary.spanDb, 0.001)
        assertTrue(WallSurvey.finding(summary).contains("Garage costs 16.0 dB more than Study"))
    }

    @Test
    fun `one spot is not a survey`() {
        // One spot has no best and no worst. Saying it is both would be a sentence with no
        // content wearing the clothes of a finding.
        assertNull(WallSurvey.summarize(listOf(spot("Kitchen", 8.0))))
        assertNull(WallSurvey.summarize(emptyList()))
    }

    @Test
    fun `spots that could not be measured do not count toward having a survey`() {
        assertNull(WallSurvey.summarize(listOf(spot("Kitchen", 8.0), spot("Shed", null))))
    }

    @Test
    fun `a building that changes nothing is told so plainly`() {
        val summary = WallSurvey.summarize(
            listOf(spot("Kitchen", 6.0), spot("Hall", 7.0), spot("Study", 6.5)),
        )!!

        assertFalse(summary.tellsYouSomething)
        assertTrue(
            WallSurvey.finding(summary)
                .contains("The building is not what is costing you 5 GHz here"),
        )
    }

    @Test
    fun `a room where 5 GHz is gone entirely is called out`() {
        val summary = WallSurvey.summarize(
            listOf(
                spot("Study", 1.0),
                spot("Basement", 22.0, pairs = 2, lost5 = 2),
            ),
        )!!

        assertEquals(1, summary.blackspots)
        assertTrue(WallSurvey.finding(summary).contains("lost 5 GHz altogether"))
    }

    @Test
    fun `a spot with no access points at all is not a blackspot`() {
        // Nothing heard is not the same as the fast band being blocked, and reporting it as
        // one would invent a finding out of a failed reading.
        assertFalse(spot("Nowhere", null, pairs = 0, lost5 = 0).blackspot)
    }

    // ------------------------------------------------------------------ names

    @Test
    fun `the suggested name is a room, and not one already used`() {
        assertEquals("Living room", WallSurvey.suggestName(emptyList()))
        assertEquals(
            "Kitchen",
            WallSurvey.suggestName(listOf(spot("Living room", 3.0))),
        )
    }

    @Test
    fun `once the rooms run out it falls back to a number`() {
        val used = WallSurvey.roomNames().map { spot(it, 3.0) }

        assertEquals("Spot ${used.size + 1}", WallSurvey.suggestName(used))
    }

    @Test
    fun `a name already used in a different case is still used`() {
        assertEquals("Kitchen", WallSurvey.suggestName(listOf(spot("LIVING ROOM", 3.0))))
    }

    // ------------------------------------------------------------------ export

    @Test
    fun `the survey exports worst first with the names you gave it`() {
        val csv = WallSurvey.csv(listOf(spot("Study", 2.0), spot("Garage", 18.0)))
        val lines = csv.trim().lines()

        assertEquals("spot,excess_db,access_points,lost_5ghz,blackspot,taken_ms", lines[0])
        assertTrue(lines[1].startsWith("Garage,18.0,3,0,0,"))
        assertTrue(lines[2].startsWith("Study,2.0,3,0,0,"))
    }

    @Test
    fun `a comma in a room name does not become a column`() {
        val csv = WallSurvey.csv(listOf(spot("Kitchen, back wall", 5.0)))

        assertTrue(csv.contains("Kitchen  back wall,5.0"))
    }

    @Test
    fun `a spot that measured nothing exports an empty cell rather than a zero`() {
        // Zero excess is a real reading that means the building took nothing. Writing it
        // where there was no reading at all would put a fact in the file that is not true.
        val csv = WallSurvey.csv(listOf(spot("Shed", null, pairs = 0)))

        assertTrue(csv.contains("Shed,,0,0,0,"))
    }
}
